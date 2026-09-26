package io.github.conichonhaa.ilo.core.crypto

/** A byte-oriented stream cipher that transforms data in place. */
interface StreamCipher {
    fun process(buf: ByteArray, off: Int = 0, len: Int = buf.size - off)
}

/** Cipher identifiers as announced by the iLO remote console stream. */
object CipherType {
    const val NONE = 0
    const val RC4 = 1
    const val AES128 = 2
    const val AES256 = 3
}
