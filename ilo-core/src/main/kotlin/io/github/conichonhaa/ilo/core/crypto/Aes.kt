package io.github.conichonhaa.ilo.core.crypto

/**
 * Minimal table-driven AES block encryptor (FIPS-197), encryption direction only.
 *
 * OFB mode needs a single AES call per stream byte; going through a JCE Cipher for every byte is
 * slow, and several Android releases do not ship an "AES/OFB8" transformation at all.
 */
class Aes(key: ByteArray) {
    private val rounds: Int
    private val rk: IntArray

    init {
        require(key.size == 16 || key.size == 24 || key.size == 32) { "Invalid AES key length ${key.size}" }
        val nk = key.size / 4
        rounds = nk + 6
        rk = IntArray(4 * (rounds + 1))
        for (i in 0 until nk) rk[i] = readInt(key, 4 * i)
        var rcon = 1
        for (i in nk until rk.size) {
            var t = rk[i - 1]
            if (i % nk == 0) {
                t = subWord((t shl 8) or (t ushr 24)) xor (rcon shl 24)
                rcon = xtime(rcon)
            } else if (nk > 6 && i % nk == 4) {
                t = subWord(t)
            }
            rk[i] = rk[i - nk] xor t
        }
    }

    /** Encrypts the 16 bytes at [input]/[inOff] into [output]/[outOff]. */
    fun encryptBlock(input: ByteArray, inOff: Int, output: ByteArray, outOff: Int) {
        var s0 = readInt(input, inOff) xor rk[0]
        var s1 = readInt(input, inOff + 4) xor rk[1]
        var s2 = readInt(input, inOff + 8) xor rk[2]
        var s3 = readInt(input, inOff + 12) xor rk[3]
        var k = 4
        for (r in 1 until rounds) {
            val t0 = T0[s0 ushr 24] xor T1[(s1 ushr 16) and 0xff] xor T2[(s2 ushr 8) and 0xff] xor T3[s3 and 0xff] xor rk[k]
            val t1 = T0[s1 ushr 24] xor T1[(s2 ushr 16) and 0xff] xor T2[(s3 ushr 8) and 0xff] xor T3[s0 and 0xff] xor rk[k + 1]
            val t2 = T0[s2 ushr 24] xor T1[(s3 ushr 16) and 0xff] xor T2[(s0 ushr 8) and 0xff] xor T3[s1 and 0xff] xor rk[k + 2]
            val t3 = T0[s3 ushr 24] xor T1[(s0 ushr 16) and 0xff] xor T2[(s1 ushr 8) and 0xff] xor T3[s2 and 0xff] xor rk[k + 3]
            s0 = t0; s1 = t1; s2 = t2; s3 = t3
            k += 4
        }
        writeInt(output, outOff, lastRound(s0, s1, s2, s3) xor rk[k])
        writeInt(output, outOff + 4, lastRound(s1, s2, s3, s0) xor rk[k + 1])
        writeInt(output, outOff + 8, lastRound(s2, s3, s0, s1) xor rk[k + 2])
        writeInt(output, outOff + 12, lastRound(s3, s0, s1, s2) xor rk[k + 3])
    }

    private fun lastRound(a: Int, b: Int, c: Int, d: Int): Int =
        (SBOX[a ushr 24] shl 24) or (SBOX[(b ushr 16) and 0xff] shl 16) or
            (SBOX[(c ushr 8) and 0xff] shl 8) or SBOX[d and 0xff]

    private companion object {
        val SBOX = IntArray(256)
        val T0 = IntArray(256)
        val T1 = IntArray(256)
        val T2 = IntArray(256)
        val T3 = IntArray(256)

        init {
            val exp = IntArray(256)
            val log = IntArray(256)
            var x = 1
            for (i in 0 until 255) {
                exp[i] = x
                log[x] = i
                x = x xor xtime(x) // multiply by the generator 3
            }
            for (i in 0 until 256) {
                val inv = if (i == 0) 0 else exp[(255 - log[i]) % 255]
                var s = inv
                var r = inv
                repeat(4) {
                    r = ((r shl 1) or (r ushr 7)) and 0xff
                    s = s xor r
                }
                SBOX[i] = s xor 0x63
            }
            for (i in 0 until 256) {
                val s = SBOX[i]
                val s2 = xtime(s)
                val t = (s2 shl 24) or (s shl 16) or (s shl 8) or (s2 xor s)
                T0[i] = t
                T1[i] = (t ushr 8) or (t shl 24)
                T2[i] = (t ushr 16) or (t shl 16)
                T3[i] = (t ushr 24) or (t shl 8)
            }
        }

        fun xtime(a: Int): Int = ((a shl 1) xor (if (a and 0x80 != 0) 0x1b else 0)) and 0xff

        fun subWord(w: Int): Int =
            (SBOX[w ushr 24] shl 24) or (SBOX[(w ushr 16) and 0xff] shl 16) or
                (SBOX[(w ushr 8) and 0xff] shl 8) or SBOX[w and 0xff]

        fun readInt(b: ByteArray, o: Int): Int =
            ((b[o].toInt() and 0xff) shl 24) or ((b[o + 1].toInt() and 0xff) shl 16) or
                ((b[o + 2].toInt() and 0xff) shl 8) or (b[o + 3].toInt() and 0xff)

        fun writeInt(b: ByteArray, o: Int, v: Int) {
            b[o] = (v ushr 24).toByte()
            b[o + 1] = (v ushr 16).toByte()
            b[o + 2] = (v ushr 8).toByte()
            b[o + 3] = v.toByte()
        }
    }
}
