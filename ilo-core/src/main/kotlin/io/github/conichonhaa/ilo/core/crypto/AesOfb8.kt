package io.github.conichonhaa.ilo.core.crypto

/** AES in 8-bit output-feedback mode, as used by the iLO remote console channels. */
class AesOfb8(key: ByteArray, iv: ByteArray) : StreamCipher {
    private val aes = Aes(key)
    private val register = iv.copyOf(16)
    private val block = ByteArray(16)

    override fun process(buf: ByteArray, off: Int, len: Int) {
        for (i in off until off + len) {
            aes.encryptBlock(register, 0, block, 0)
            val k = block[0]
            System.arraycopy(register, 1, register, 0, 15)
            register[15] = k
            buf[i] = (buf[i].toInt() xor k.toInt()).toByte()
        }
    }
}
