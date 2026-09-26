package io.github.conichonhaa.ilo.core.rc

import java.io.IOException

/** Data source/sink for the decoder: the (already decrypted) KVM stream. */
interface DvcTransport {
    /** Reads at most [max] decrypted bytes into [buf]. Blocks; returns > 0 or throws. */
    fun read(buf: ByteArray, max: Int): Int

    /** The stream announced a new cipher; subsequent reads/writes must use it. */
    fun setEncryption(type: Int)

    /** Sends a client message on the KVM channel. */
    fun send(data: ByteArray)
}

interface DvcListener {
    fun onVideoMode(width: Int, height: Int, hasSignal: Boolean) {}
    fun onFrameUpdated() {}
    fun onEncryption(type: Int) {}
    fun onStreamHeader(licensed: Boolean, osStarted: Boolean) {}
    fun onPowerChanged(on: Boolean) {}
    fun onSeized() {}
}

/**
 * Decoder for the "DVC" video stream of the iLO remote console.
 *
 * The stream is a bit stream (LSB first within each byte, codes MSB first) driving a state
 * machine. The screen is split in 16x16 (or 16x8) blocks; each block is encoded pixel by pixel
 * using explicit RGB values, references into a small MRU colour cache and run lengths, or is
 * repeated/skipped as a whole. The same stream carries in-band commands (video mode, colour
 * depth, encryption, power state...).
 */
class DvcDecoder(
    private val transport: DvcTransport,
    private val listener: DvcListener,
    val frameBuffer: FrameBuffer = FrameBuffer(),
) {
    private class State(val bits: Int, val next0: Int, var next1: Int) {
        var bitsToRead = bits
    }

    private val states = arrayOf(
        State(0, 1, 1),     // 0 RESET
        State(1, 2, 15),    // 1 START
        State(1, 31, 3),    // 2 PIXELS
        State(1, 2, 11),    // 3 PIXLRU1
        State(1, 2, 11),    // 4 PIXLRU0
        State(1, 10, 10),   // 5 PIXCODE1
        State(2, 10, 10),   // 6 PIXCODE2
        State(3, 10, 10),   // 7 PIXCODE3
        State(4, 10, 10),   // 8 PIXGREY
        State(4, 41, 41),   // 9 PIXRGBR
        State(1, 2, 11),    // 10 PIXRPT
        State(1, 33, 12),   // 11 PIXRPT1
        State(3, 2, 2),     // 12 PIXRPTSTD1
        State(3, 2, 2),     // 13 PIXRPTSTD2
        State(8, 2, 2),     // 14 PIXRPTNSTD
        State(1, 16, 17),   // 15 CMD
        State(1, 19, 18),   // 16 CMD0
        State(7, 39, 39),   // 17 MOVEXY0
        State(1, 22, 23),   // 18 EXTCMD
        State(1, 20, 21),   // 19 CMDX
        State(3, 1, 1),     // 20 MOVESHORTX
        State(7, 1, 1),     // 21 MOVELONGX
        State(1, 34, 28),   // 22 BLKRPT
        State(1, 25, 24),   // 23 EXTCMD1
        State(8, 46, 46),   // 24 FIRMWARE
        State(1, 26, 27),   // 25 EXTCMD2
        State(7, 40, 40),   // 26 MODE0
        State(0, 1, 1),     // 27 TIMEOUT
        State(1, 29, 30),   // 28 BLKRPT1
        State(3, 1, 1),     // 29 BLKRPTSTD
        State(7, 1, 1),     // 30 BLKRPTNSTD
        State(1, 36, 35),   // 31 PIXFAN
        State(4, 10, 10),   // 32 PIXCODE4
        State(0, 2, 2),     // 33 PIXDUP
        State(0, 1, 1),     // 34 BLKDUP
        State(0, 35, 35),   // 35 PIXCODE
        State(1, 8, 9),     // 36 PIXSPEC
        State(0, 37, 37),   // 37 EXIT
        State(1, 38, 38),   // 38 LATCHED
        State(7, 1, 1),     // 39 MOVEXY1
        State(7, 47, 47),   // 40 MODE1
        State(4, 42, 42),   // 41 PIXRGBG
        State(4, 10, 10),   // 42 PIXRGBB
        State(1, 43, 0),    // 43 HUNT
        State(8, 45, 45),   // 44 PRINT0
        State(8, 45, 45),   // 45 PRINT1
        State(1, 1, 24),    // 46 CORP
        State(4, 1, 1),     // 47 MODE2
    )

    private val cache = ColorCache()
    private val readBuf = ByteArray(2048)
    private var readPos = 0
    private var readLen = 0
    private var firstRead = true

    private var bitAcc = 0
    private var bitCount = 0
    private var zeroCount = 0
    private var code = 0

    private var state = S_RESET
    private var nextState = S_RESET

    private var bitsPerColor = 5
    private val pixelTable = IntArray(1 shl 15)
    private val blockWidth = 16
    private var blockHeight = 16
    private val block = IntArray(16 * 16)
    private var pixelCount = 0
    private var lastColor = 0
    private var red = 0
    private var green = 0
    private var blue = 0

    private var blockX = 0
    private var blockY = 0
    private var newX = 0
    private var newY = 0
    private var sizeX = 0
    private var sizeY = 0

    private var latchedCount = 0
    private var iteration = 0
    private var timeoutMark = -1

    private val cmdParams = ByteArray(256)
    private var cmdCount = 0
    private var cmdLast = 0

    private var halfHeightCapable = false

    /** Width/height of the current video mode, used to scale absolute mouse coordinates. */
    @Volatile var videoWidth = 1600
        private set
    @Volatile var videoHeight = 1200
        private set

    @Volatile var licensed = true
        private set

    @Volatile private var running = true

    init {
        setBitsPerColor(0)
    }

    fun stop() {
        running = false
    }

    /** Decodes until [stop] is called, the stream ends (EXIT command) or an I/O error occurs. */
    @Throws(IOException::class)
    fun run() {
        while (running) {
            iteration++
            if (!readBits(states[state].bitsToRead)) continue // resynchronised
            nextState = if (code == 0) states[state].next0 else states[state].next1
            step()
            if (nextState == S_PIXELS && pixelCount == blockHeight * blockWidth) {
                nextBlock(1)
                cache.prune()
                states[S_PIXFAN].next1 = cache.pixcode
            }
            state = nextState
        }
    }

    private fun step() {
        when (state) {
            S_RESET -> {
                cache.reset()
                pixelCount = 0
                blockX = 0
                blockY = 0
                latchedCount = 0
                red = 0; green = 0; blue = 0
                timeoutMark = -1
            }
            S_PIXLRU1, S_PIXLRU0, S_PIXCODE1, S_PIXCODE2, S_PIXCODE3, S_PIXCODE4 -> {
                code = when {
                    cache.active == 1 -> cache.firstUsage()
                    state == S_PIXLRU0 -> 0
                    state == S_PIXLRU1 -> 1
                    code != 0 -> code + 1
                    else -> 0
                }
                val c = cache.find(code)
                if (c < 0) {
                    cache.invalidate()
                    nextState = S_LATCHED
                } else {
                    storePixel(c)
                }
            }
            S_PIXGREY -> {
                red = code shl (2 * bitsPerColor)
                green = code shl bitsPerColor
                explicitColor()
            }
            S_PIXRGBR -> red = code shl (2 * bitsPerColor)
            S_PIXRGBG -> green = code shl bitsPerColor
            S_PIXRGBB -> explicitColor()
            S_PIXRPTSTD1 -> when (code) {
                7 -> nextState = S_PIXRPTNSTD
                6 -> nextState = S_PIXRPTSTD2
                else -> repeatLast(code + 2)
            }
            S_PIXRPTSTD2 -> repeatLast(code + 8)
            S_PIXRPTNSTD -> repeatLast(code)
            S_MOVEXY0 -> newX = code
            S_MOVESHORTX -> {
                code += blockX + 1
                blockX = code and 0x7f
            }
            S_MOVELONGX -> {
                blockX = code
                if (blockHeight == 16) blockX = blockX and 0x7f
            }
            S_FIRMWARE -> {
                // Command bytes: parameters first, the command code last.
                if (cmdCount != 0 && cmdCount <= cmdParams.size) cmdParams[cmdCount - 1] = cmdLast.toByte()
                cmdCount++
                cmdLast = code
            }
            S_MODE0 -> newX = code
            S_TIMEOUT -> {
                if (timeoutMark == iteration - 1) nextState = S_LATCHED
                while (bitCount and 7 != 0) readBits(1)
                timeoutMark = iteration
            }
            S_BLKRPTSTD -> nextBlock(code + 2)
            S_BLKRPTNSTD -> nextBlock(code)
            S_PIXDUP -> storePixel(lastColor)
            S_BLKDUP -> nextBlock(1)
            S_PIXCODE -> nextState = cache.pixcode
            S_EXIT -> running = false
            S_LATCHED -> {
                if (latchedCount == 0x800000) {
                    transport.send(byteArrayOf(5, 0)) // ask for a full refresh
                    latchedCount = 0
                }
                latchedCount++
            }
            S_MOVEXY1 -> {
                newY = code
                if (blockHeight == 16) newY = newY and 0x7f
                blockX = newX
                blockY = newY
            }
            S_MODE1 -> {
                if (sizeX != newX || sizeY != code) {
                    frameBuffer.clear()
                    listener.onFrameUpdated()
                }
                sizeX = newX
                sizeY = code
            }
            S_HUNT -> if (nextState != state) {
                bitCount = 0
                bitAcc = 0
                zeroCount = 0
                iteration = 0
            }
            S_PRINT1 -> if (code == 0) nextState = S_START
            S_CORP -> if (code == 0) {
                executeCommand(cmdLast)
                cmdCount = 0
            }
            S_MODE2 -> {
                blockX = 0
                blockY = 0
                pixelCount = 0
                cache.reset()
                switchVideoMode(sizeX * blockWidth, sizeY * 16 + code)
            }
        }
    }

    private fun explicitColor() {
        blue = code
        val c = red or green or blue
        val known = cache.touch(c)
        states[S_PIXFAN].next1 = cache.pixcode
        if (known) nextState = S_LATCHED else storePixel(c)
    }

    private fun repeatLast(n: Int) {
        repeat(n) { storePixel(lastColor) }
    }

    private fun executeCommand(cmd: Int) {
        when (cmd) {
            1 -> nextState = S_EXIT
            2 -> nextState = S_PRINT0
            4 -> listener.onPowerChanged(true)
            5 -> listener.onPowerChanged(false)
            6 -> {
                frameBuffer.clear()
                listener.onFrameUpdated()
            }
            9 -> if (bitCount and 7 != 0) readBits(bitCount and 7)
            10 -> listener.onSeized()
            11 -> setBitsPerColor(cmdParams[0].toInt())
            12 -> setEncryption(cmdParams[0].toInt())
            13 -> {
                setBitsPerColor(cmdParams[0].toInt())
                setEncryption(cmdParams[1].toInt())
                val lic = cmdParams[2].toInt()
                val flags = cmdParams[3].toInt()
                licensed = lic and 1 != 0
                // Takes effect at the next video mode switch.
                halfHeightCapable = flags and 8 != 0
                listener.onStreamHeader(licensed, flags and 1 != 0)
            }
            16 -> transport.send(byteArrayOf(12, 0)) // acknowledge
            128 -> listener.onFrameUpdated()
        }
    }

    private fun setBitsPerColor(value: Int) {
        bitsPerColor = 5 - (value and 3)
        states[S_PIXGREY].bitsToRead = bitsPerColor
        states[S_PIXRGBR].bitsToRead = bitsPerColor
        states[S_PIXRGBG].bitsToRead = bitsPerColor
        states[S_PIXRGBB].bitsToRead = bitsPerColor
        buildPixelTable()
    }

    private fun setEncryption(type: Int) {
        transport.setEncryption(type)
        listener.onEncryption(type)
    }

    private fun switchVideoMode(width: Int, height: Int) {
        val hasSignal = width != 0 && height != 0
        val w = if (hasSignal) width else 800
        val h = if (hasSignal) height else 600
        videoWidth = w
        videoHeight = h
        frameBuffer.resize(w, h)
        if (hasSignal) updateBlockHeight()
        listener.onVideoMode(w, h, hasSignal)
        listener.onFrameUpdated()
    }

    /** Wide modes may use 16x8 blocks, with 8-bit block coordinates. */
    private fun updateBlockHeight() {
        val bits = if (videoWidth > 1616) {
            if (!halfHeightCapable) return
            blockHeight = 8
            8
        } else {
            blockHeight = 16
            7
        }
        states[S_MOVELONGX].bitsToRead = bits
        states[S_MOVEXY0].bitsToRead = bits
        states[S_MOVEXY1].bitsToRead = bits
        states[S_BLKRPTNSTD].bitsToRead = bits
    }

    private fun buildPixelTable() {
        val bpc = bitsPerColor
        val mask = (1 shl bpc) - 1
        val shift = 8 - bpc
        for (i in pixelTable.indices) {
            val b = (i and mask) shl shift
            val g = ((i shr bpc) and mask) shl shift
            val r = ((i shr (2 * bpc)) and mask) shl shift
            pixelTable[i] = FrameBuffer.OPAQUE_BLACK or (r shl 16) or (g shl 8) or b
        }
    }

    private fun storePixel(c: Int) {
        if (pixelCount < blockHeight * blockWidth) block[pixelCount] = pixelTable[c and 0x7fff]
        lastColor = c
        pixelCount++
    }

    private fun nextBlock(count: Int) {
        nextState = S_START
        pixelCount = 0
        val y = blockY * blockHeight
        for (i in 0 until count) {
            if (blockX < sizeX && y < videoHeight) {
                frameBuffer.blit(block, blockWidth, blockHeight, blockX * blockWidth, y)
                blockX++
            }
        }
        listener.onFrameUpdated()
    }

    /**
     * Reads [len] (0..8) bits into [code]. Returns false when a run of more than 30 zero bits
     * was seen, which is the resynchronisation marker: the decoder then restarts at RESET.
     */
    private fun readBits(len: Int): Boolean {
        if (len == 0) return true
        if (bitCount < len) {
            var b = nextByte()
            zeroCount += TRAILING_ZEROS[b]
            if (zeroCount > 30) {
                while (b == 0) b = nextByte()
                bitCount = 0
                bitAcc = 0
                zeroCount = TRAILING_ZEROS[b]
                state = S_RESET
                nextState = S_RESET
                return false
            }
            if (b != 0) zeroCount = LEADING_ZEROS[b]
            bitAcc = bitAcc or (b shl bitCount)
            bitCount += 8
        }
        val v = bitAcc and ((1 shl len) - 1)
        bitCount -= len
        bitAcc = bitAcc ushr len
        code = REVERSED[v] shr (8 - len)
        return true
    }

    private fun nextByte(): Int {
        if (readPos >= readLen) {
            if (!running) throw StoppedException()
            // The first chunk is limited to the 7 byte stream header: it may switch on encryption
            // for everything that follows, and data is decrypted as it is read.
            val max = if (firstRead) 7 else readBuf.size
            firstRead = false
            readLen = transport.read(readBuf, max)
            readPos = 0
        }
        return readBuf[readPos++].toInt() and 0xff
    }

    class StoppedException : IOException("Decoder stopped")

    companion object {
        const val S_RESET = 0
        const val S_START = 1
        const val S_PIXELS = 2
        const val S_PIXLRU1 = 3
        const val S_PIXLRU0 = 4
        const val S_PIXCODE1 = 5
        const val S_PIXCODE2 = 6
        const val S_PIXCODE3 = 7
        const val S_PIXGREY = 8
        const val S_PIXRGBR = 9
        const val S_PIXRPTSTD1 = 12
        const val S_PIXRPTSTD2 = 13
        const val S_PIXRPTNSTD = 14
        const val S_MOVEXY0 = 17
        const val S_MOVESHORTX = 20
        const val S_MOVELONGX = 21
        const val S_FIRMWARE = 24
        const val S_MODE0 = 26
        const val S_TIMEOUT = 27
        const val S_BLKRPTSTD = 29
        const val S_BLKRPTNSTD = 30
        const val S_PIXFAN = 31
        const val S_PIXCODE4 = 32
        const val S_PIXDUP = 33
        const val S_BLKDUP = 34
        const val S_PIXCODE = 35
        const val S_EXIT = 37
        const val S_LATCHED = 38
        const val S_MOVEXY1 = 39
        const val S_MODE1 = 40
        const val S_PIXRGBG = 41
        const val S_PIXRGBB = 42
        const val S_HUNT = 43
        const val S_PRINT0 = 44
        const val S_PRINT1 = 45
        const val S_CORP = 46
        const val S_MODE2 = 47

        private val REVERSED = IntArray(256)
        private val TRAILING_ZEROS = IntArray(256)
        private val LEADING_ZEROS = IntArray(256)

        init {
            for (i in 0 until 256) {
                REVERSED[i] = Integer.reverse(i) ushr 24
                TRAILING_ZEROS[i] = if (i == 0) 8 else Integer.numberOfTrailingZeros(i)
                LEADING_ZEROS[i] = if (i == 0) 8 else Integer.numberOfLeadingZeros(i) - 24
            }
        }
    }
}
