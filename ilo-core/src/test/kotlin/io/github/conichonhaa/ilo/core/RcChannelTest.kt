package io.github.conichonhaa.ilo.core

import io.github.conichonhaa.ilo.core.crypto.AesOfb8
import io.github.conichonhaa.ilo.core.crypto.CipherType
import io.github.conichonhaa.ilo.core.net.RcInfo
import io.github.conichonhaa.ilo.core.rc.HandshakeResult
import io.github.conichonhaa.ilo.core.rc.RcChannel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class RcChannelTest {
    private val sessionKey = "0123456789abcdef0123456789abcdef"

    private fun withServer(handler: (Socket) -> Unit, client: (Int) -> Unit) {
        ServerSocket(0).use { server ->
            var failure: Throwable? = null
            val t = thread {
                try {
                    server.accept().use(handler)
                } catch (e: Throwable) {
                    failure = e
                }
            }
            client(server.localPort)
            t.join(5000)
            failure?.let { throw it }
        }
    }

    @Test
    fun v1HandshakeWithObfuscatedTokenThenAesStream() {
        val keyHex = "00112233445566778899aabbccddeeff"
        val info = RcInfo(version2 = false, keyHex = keyHex, port = 0, obfuscateToken = true)
        withServer({ s ->
            val input = DataInputStream(s.getInputStream())
            s.getOutputStream().write(0x50)
            val cmd = ByteArray(2).also(input::readFully)
            assertEquals(0xA001, (cmd[0].toInt() and 0xff) or ((cmd[1].toInt() and 0xff) shl 8))
            val token = ByteArray(32).also(input::readFully)
            val clear = ByteArray(32) { (token[it].toInt() xor keyHex[it].code).toByte() }
            assertEquals(sessionKey, String(clear))
            s.getOutputStream().write(0x52)
            // After the header switches to AES, the stream uses a zero IV.
            val payload = "hello".toByteArray()
            AesOfb8(info.key, ByteArray(16)).process(payload)
            s.getOutputStream().write(payload)
            s.getOutputStream().flush()
            Thread.sleep(200)
        }) { port ->
            RcChannel("127.0.0.1", port).use { c ->
                assertEquals(HandshakeResult.OK, c.handshake(info, sessionKey, RcChannel.Kind.KVM))
                c.setKeys(info.key, info.key)
                c.initCrypto(CipherType.AES128)
                val buf = ByteArray(5)
                c.readFully(buf)
                assertEquals("hello", String(buf))
            }
        }
    }

    @Test
    fun v2HandshakeIsEncryptedBothWays() {
        val enc = ByteArray(16) { (it + 1).toByte() }
        val dec = ByteArray(16) { (it + 100).toByte() }
        val serverIv = ByteArray(16) { 7 }
        val info = RcInfo(version2 = true, keyHex = "ff".repeat(16), port = 0, obfuscateToken = false)
        withServer({ s ->
            val input = DataInputStream(s.getInputStream())
            val iv = ByteArray(16).also(input::readFully)
            val hello = ByteArray(38).also(input::readFully)
            AesOfb8(enc, iv).process(hello)
            assertEquals(0, hello[0].toInt())
            assertEquals(1, hello[1].toInt()) // command channel
            assertArrayEquals(sessionKey.toByteArray(), hello.copyOfRange(6, 38))
            val reply = ByteArray(37) // status 0 = OK
            AesOfb8(dec, serverIv).process(reply)
            s.getOutputStream().write(serverIv + reply)
            s.getOutputStream().flush()
            Thread.sleep(200)
        }) { port ->
            RcChannel("127.0.0.1", port).use { c ->
                c.setKeys(enc, dec)
                assertEquals(HandshakeResult.OK, c.handshake(info, sessionKey, RcChannel.Kind.COMMAND))
                assertEquals(CipherType.AES128, c.cipher)
            }
        }
    }
}
