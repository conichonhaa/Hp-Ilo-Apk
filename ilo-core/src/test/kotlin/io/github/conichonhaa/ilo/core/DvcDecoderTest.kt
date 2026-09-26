package io.github.conichonhaa.ilo.core

import io.github.conichonhaa.ilo.core.rc.DvcDecoder
import io.github.conichonhaa.ilo.core.rc.DvcListener
import io.github.conichonhaa.ilo.core.rc.DvcTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException

/** Writes codes MSB first, packing bits LSB first into bytes, as the iLO encoder does. */
private class BitWriter {
    private val bytes = ArrayList<Byte>()
    private var acc = 0
    private var n = 0

    fun bits(value: Int, len: Int): BitWriter {
        for (i in len - 1 downTo 0) {
            acc = acc or (((value shr i) and 1) shl n)
            if (++n == 8) flush()
        }
        return this
    }

    private fun flush() {
        bytes.add(acc.toByte())
        acc = 0
        n = 0
    }

    fun toByteArray(): ByteArray {
        if (n > 0) flush()
        return bytes.toByteArray()
    }
}

class DvcDecoderTest {
    private class Recorder : DvcListener {
        var mode: Triple<Int, Int, Boolean>? = null
        var power: Boolean? = null
        var encryption: Int? = null
        override fun onVideoMode(width: Int, height: Int, hasSignal: Boolean) {
            mode = Triple(width, height, hasSignal)
        }
        override fun onPowerChanged(on: Boolean) {
            power = on
        }
        override fun onEncryption(type: Int) {
            encryption = type
        }
    }

    private fun decode(stream: ByteArray, recorder: Recorder): DvcDecoder {
        var pos = 0
        val transport = object : DvcTransport {
            override fun read(buf: ByteArray, max: Int): Int {
                if (pos >= stream.size) throw EOFException()
                val n = minOf(max, stream.size - pos)
                stream.copyInto(buf, 0, pos, pos + n)
                pos += n
                return n
            }
            override fun setEncryption(type: Int) {}
            override fun send(data: ByteArray) {}
        }
        val d = DvcDecoder(transport, recorder)
        try {
            d.run()
        } catch (_: EOFException) {
        }
        return d
    }

    /** START=1, CMD=0, CMD0=1, EXTCMD=1, EXTCMD1=0, EXTCMD2=0 -> MODE0/MODE1/MODE2. */
    private fun BitWriter.videoMode(blocksX: Int, blocksY: Int, extraLines: Int) =
        bits(1, 1).bits(0, 1).bits(1, 1).bits(1, 1).bits(0, 1).bits(0, 1)
            .bits(blocksX, 7).bits(blocksY, 7).bits(extraLines, 4)

    /** In-band command: FIRMWARE byte(s) chained with CORP=1, terminated by CORP=0. */
    private fun BitWriter.command(vararg bytes: Int): BitWriter {
        bits(1, 1).bits(0, 1).bits(1, 1).bits(1, 1).bits(1, 1) // START..EXTCMD1 -> FIRMWARE
        bytes.forEachIndexed { i, b ->
            bits(b, 8)
            bits(if (i == bytes.lastIndex) 0 else 1, 1)
        }
        return this
    }

    @Test
    fun videoModeAndSolidBlocks() {
        val w = BitWriter()
        w.videoMode(64, 48, 0)
        // One block: explicit RGB colour then a run of 255 repeats.
        w.bits(0, 1) // START -> PIXELS
        w.bits(0, 1) // PIXELS -> PIXFAN
        w.bits(0, 1) // PIXFAN -> PIXSPEC
        w.bits(1, 1) // PIXSPEC -> PIXRGBR
        w.bits(31, 5).bits(16, 5).bits(1, 5) // R, G, B
        w.bits(1, 1) // PIXRPT -> PIXRPT1
        w.bits(1, 1) // PIXRPT1 -> PIXRPTSTD1
        w.bits(7, 3) // -> PIXRPTNSTD
        w.bits(255, 8)
        // Repeat the previous block once: START=1, CMD=0, CMD0=1, EXTCMD=0, BLKRPT=0 -> BLKDUP.
        w.bits(1, 1).bits(0, 1).bits(1, 1).bits(0, 1).bits(0, 1)
        w.command(4) // power on notification
        w.bits(1, 1) // keep some trailing data

        val rec = Recorder()
        val d = decode(w.toByteArray(), rec)
        assertEquals(Triple(1024, 768, true), rec.mode)
        assertEquals(true, rec.power)
        val fb = d.frameBuffer
        val expected = 0xFF000000.toInt() or (0xF8 shl 16) or (0x80 shl 8) or 0x08
        for (y in 0 until 16) for (x in 0 until 32) {
            assertEquals("pixel $x,$y", expected, fb.pixels[y * fb.width + x])
        }
        assertEquals(0xFF000000.toInt(), fb.pixels[32])
    }

    @Test
    fun streamHeaderSetsColourDepthAndEncryption() {
        val w = BitWriter()
        // Parameters: bits-per-colour code 1 (4 bits), cipher AES128, licensed, flags; command 13.
        w.command(1, 2, 1, 0, 13)
        w.videoMode(50, 37, 8) // 800x600
        w.bits(0, 1).bits(0, 1).bits(0, 1).bits(0, 1) // START, PIXELS, PIXFAN, PIXSPEC -> PIXGREY
        w.bits(15, 4) // white in 4 bits per colour
        w.bits(1, 1).bits(1, 1).bits(7, 3).bits(255, 8)
        w.bits(1, 1)
        val rec = Recorder()
        val d = decode(w.toByteArray(), rec)
        assertEquals(2, rec.encryption)
        assertEquals(Triple(800, 600, true), rec.mode)
        assertEquals(0xFFF0F0F0.toInt(), d.frameBuffer.pixels[0])
        assertTrue(d.licensed)
    }
}
