package io.github.conichonhaa.ilo.core.crypto

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Session keys for the "version 2" console protocol (iLO 5 and later): a single-block
 * SP 800-108 style HMAC-SHA512 derivation from the MasterKey returned by Redfish.
 */
object KeyDerivation {
    class SessionKeys(
        val kvmEncrypt: ByteArray,
        val kvmDecrypt: ByteArray,
        val cmdEncrypt: ByteArray,
        val cmdDecrypt: ByteArray,
    )

    fun derive(masterKey: ByteArray): SessionKeys {
        val material = deriveMaterial(masterKey, 64)
        return SessionKeys(
            material.copyOfRange(0, 16),
            material.copyOfRange(16, 32),
            material.copyOfRange(32, 48),
            material.copyOfRange(48, 64),
        )
    }

    fun deriveMaterial(masterKey: ByteArray, length: Int): ByteArray {
        val label = "iLO IRC".toByteArray(Charsets.US_ASCII)
        val context = "key derivation".toByteArray(Charsets.US_ASCII)
        val message = ByteBuffer.allocate(4 + label.size + 1 + context.size + 4).order(ByteOrder.LITTLE_ENDIAN)
        message.putInt(1)
        message.put(label)
        message.put(0)
        message.put(context)
        message.putInt(length * 8)
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(masterKey, "HmacSHA512"))
        val out = mac.doFinal(message.array())
        return out.copyOf(length)
    }
}
