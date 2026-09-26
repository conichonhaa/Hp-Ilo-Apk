package io.github.conichonhaa.ilo.core.crypto

/** RC4, still announced by iLO 2/3 firmware for the legacy console stream. */
class Rc4(key: ByteArray) : StreamCipher {
    private val s = IntArray(256) { it }
    private var i = 0
    private var j = 0

    init {
        require(key.isNotEmpty())
        var jj = 0
        for (ii in 0 until 256) {
            jj = (jj + s[ii] + (key[ii % key.size].toInt() and 0xff)) and 0xff
            val t = s[ii]; s[ii] = s[jj]; s[jj] = t
        }
    }

    override fun process(buf: ByteArray, off: Int, len: Int) {
        for (n in off until off + len) {
            i = (i + 1) and 0xff
            j = (j + s[i]) and 0xff
            val t = s[i]; s[i] = s[j]; s[j] = t
            buf[n] = (buf[n].toInt() xor s[(s[i] + s[j]) and 0xff]).toByte()
        }
    }
}
