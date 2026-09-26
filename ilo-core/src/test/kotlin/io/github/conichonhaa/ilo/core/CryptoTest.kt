package io.github.conichonhaa.ilo.core

import io.github.conichonhaa.ilo.core.crypto.Aes
import io.github.conichonhaa.ilo.core.crypto.AesOfb8
import io.github.conichonhaa.ilo.core.crypto.KeyDerivation
import io.github.conichonhaa.ilo.core.crypto.Rc4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Random
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class CryptoTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    @Test
    fun aesFips197Vectors() {
        val pt = hex("00112233445566778899aabbccddeeff")
        val cases = mapOf(
            "000102030405060708090a0b0c0d0e0f" to "69c4e0d86a7b0430d8cdb78070b4c55a",
            "000102030405060708090a0b0c0d0e0f1011121314151617" to "dda97ca4864cdfe06eaf70a0ec0d7191",
            "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f" to "8ea2b7ca516745bfeafc49904b496089",
        )
        for ((key, expected) in cases) {
            val out = ByteArray(16)
            Aes(hex(key)).encryptBlock(pt, 0, out, 0)
            assertEquals(expected, out.hex())
        }
    }

    @Test
    fun aesMatchesJce() {
        val rnd = Random(1)
        repeat(50) {
            val key = ByteArray(if (it % 2 == 0) 16 else 32).also(rnd::nextBytes)
            val block = ByteArray(16).also(rnd::nextBytes)
            val jce = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")) }
            val out = ByteArray(16)
            Aes(key).encryptBlock(block, 0, out, 0)
            assertArrayEquals(jce.doFinal(block), out)
        }
    }

    @Test
    fun ofb8MatchesJceInChunks() {
        val rnd = Random(2)
        val key = ByteArray(16).also(rnd::nextBytes)
        val iv = ByteArray(16).also(rnd::nextBytes)
        val data = ByteArray(5000).also(rnd::nextBytes)
        val jce = Cipher.getInstance("AES/OFB8/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }
        val expected = jce.doFinal(data)
        val mine = data.copyOf()
        val c = AesOfb8(key, iv)
        var off = 0
        while (off < mine.size) {
            val n = minOf(1 + rnd.nextInt(700), mine.size - off)
            c.process(mine, off, n)
            off += n
        }
        assertArrayEquals(expected, mine)
        // Decryption is the same operation.
        AesOfb8(key, iv).process(mine)
        assertArrayEquals(data, mine)
    }

    @Test
    fun rc4Vectors() {
        val data = "Plaintext".toByteArray()
        Rc4("Key".toByteArray()).process(data)
        assertEquals("bbf316e8d940af0ad3", data.hex())
        val wiki = "Attack at dawn".toByteArray()
        Rc4("Secret".toByteArray()).process(wiki)
        assertEquals("45a01f645fc35b383552544b9bf5", wiki.hex())
    }

    @Test
    fun keyDerivationLayout() {
        val master = ByteArray(16) { it.toByte() }
        val keys = KeyDerivation.derive(master)
        val msg = hex("01000000") + "iLO IRC".toByteArray() + byteArrayOf(0) + "key derivation".toByteArray() + hex("00020000")
        val mac = Mac.getInstance("HmacSHA512").apply { init(SecretKeySpec(master, "HmacSHA512")) }.doFinal(msg)
        assertArrayEquals(mac.copyOfRange(0, 16), keys.kvmEncrypt)
        assertArrayEquals(mac.copyOfRange(16, 32), keys.kvmDecrypt)
        assertArrayEquals(mac.copyOfRange(32, 48), keys.cmdEncrypt)
        assertArrayEquals(mac.copyOfRange(48, 64), keys.cmdDecrypt)
    }
}
