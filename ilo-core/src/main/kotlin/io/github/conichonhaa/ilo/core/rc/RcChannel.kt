package io.github.conichonhaa.ilo.core.rc

import io.github.conichonhaa.ilo.core.crypto.AesOfb8
import io.github.conichonhaa.ilo.core.crypto.CipherType
import io.github.conichonhaa.ilo.core.crypto.Rc4
import io.github.conichonhaa.ilo.core.crypto.StreamCipher
import io.github.conichonhaa.ilo.core.net.RcInfo
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

/** Outcome of the console channel handshake. */
enum class HandshakeResult {
    OK,
    DENIED,
    BUSY,
    NO_FREE_SESSION,
    NOT_LICENSED,
    BAD_RESPONSE,
    FAILED,
}

/**
 * One TCP connection to the iLO remote console port (17990 by default): either the video/KVM
 * stream or the command (status) channel. Traffic may be encrypted with RC4 or AES-OFB8.
 */
class RcChannel(host: String, port: Int) : Closeable {
    enum class Kind { KVM, COMMAND }

    private val socket = Socket()
    private val input: InputStream
    private val output: OutputStream

    private var encryptKey = ByteArray(16)
    private var decryptKey = ByteArray(16)
    private val encryptIv = ByteArray(16)
    private val decryptIv = ByteArray(16)

    private val encrypters = HashMap<Int, StreamCipher>()
    private val decrypters = HashMap<Int, StreamCipher>()
    private var encrypter: StreamCipher? = null
    private var decrypter: StreamCipher? = null

    /** Currently selected [CipherType]. */
    var cipher: Int = CipherType.NONE
        private set

    var bytesReceived = 0L
        private set
    var bytesSent = 0L
        private set

    init {
        socket.connect(InetSocketAddress(host, port), 15_000)
        socket.tcpNoDelay = true
        socket.soTimeout = 45_000
        input = socket.getInputStream()
        output = socket.getOutputStream()
    }

    fun setReadTimeout(ms: Int) {
        socket.soTimeout = ms
    }

    fun setKeys(encrypt: ByteArray, decrypt: ByteArray) {
        encryptKey = encrypt.copyOf()
        decryptKey = decrypt.copyOf()
    }

    /**
     * Switches to the cipher announced by the iLO. Like the reference client, cipher states are
     * only rebuilt (from the stored keys and IVs) when the cipher actually changes.
     */
    @Synchronized
    fun initCrypto(type: Int) {
        if (type != cipher) {
            buildEncrypters(type)
            buildDecrypters(type)
        }
        select(type)
    }

    private fun buildEncrypters(type: Int) {
        encrypters.clear()
        encrypters[CipherType.RC4] = Rc4(encryptKey)
        encrypters[CipherType.AES128] = AesOfb8(encryptKey, encryptIv)
        encrypters[CipherType.AES256] = AesOfb8(encryptKey, encryptIv)
    }

    private fun buildDecrypters(type: Int) {
        decrypters.clear()
        decrypters[CipherType.RC4] = Rc4(decryptKey)
        decrypters[CipherType.AES128] = AesOfb8(decryptKey, decryptIv)
        decrypters[CipherType.AES256] = AesOfb8(decryptKey, decryptIv)
    }

    private fun select(type: Int) {
        cipher = if (type in CipherType.RC4..CipherType.AES256) type else CipherType.NONE
        encrypter = encrypters[cipher]
        decrypter = decrypters[cipher]
    }

    /** Reads up to [len] bytes and decrypts them. Returns -1 at end of stream. */
    fun read(buf: ByteArray, off: Int = 0, len: Int = buf.size - off): Int {
        val n = input.read(buf, off, len)
        if (n > 0) {
            bytesReceived += n
            decrypt(buf, off, n)
        }
        return n
    }

    /** Reads exactly [len] bytes (decrypted). */
    fun readFully(buf: ByteArray, off: Int = 0, len: Int = buf.size - off) {
        var done = 0
        while (done < len) {
            val n = input.read(buf, off + done, len - done)
            if (n < 0) throw EOFException()
            done += n
        }
        bytesReceived += len
        decrypt(buf, off, len)
    }

    @Synchronized
    fun decrypt(buf: ByteArray, off: Int, len: Int) {
        decrypter?.process(buf, off, len)
    }

    /** Encrypts (a copy of) [data] with the current cipher and sends it. */
    @Synchronized
    fun write(data: ByteArray) {
        val copy = data.copyOf()
        encrypter?.process(copy, 0, copy.size)
        output.write(copy)
        output.flush()
        bytesSent += copy.size
    }

    private fun writeRaw(data: ByteArray) {
        output.write(data)
        output.flush()
        bytesSent += data.size
    }

    /** Performs the session request on this channel. */
    fun handshake(info: RcInfo, sessionKey: String, kind: Kind): HandshakeResult =
        if (info.version2) handshakeV2(sessionKey, kind) else handshakeV1(info, sessionKey, kind)

    private fun handshakeV1(info: RcInfo, sessionKey: String, kind: Kind): HandshakeResult {
        val one = ByteArray(1)
        if (input.read(one) != 1) return HandshakeResult.FAILED
        if (one[0].toInt() != V1_AUTHENTICATE) return HandshakeResult.BAD_RESPONSE

        var command = if (kind == Kind.KVM) V1_REQ_KVM else V1_REQ_COMMAND
        if (info.obfuscateToken) command = command or V1_REQ_TOKEN_OBFUSCATED
        val token = sessionKey.toByteArray(Charsets.ISO_8859_1)
        if (info.obfuscateToken) {
            for (i in token.indices) {
                token[i] = (token[i].toInt() xor info.keyHex[i % info.keyHex.length].code).toByte()
            }
        }
        val request = ByteArray(2 + token.size)
        request[0] = command.toByte()
        request[1] = (command shr 8).toByte()
        token.copyInto(request, 2)
        writeRaw(request)

        if (input.read(one) != 1) return HandshakeResult.FAILED
        return when (one[0].toInt()) {
            0x52 -> HandshakeResult.OK
            0x51 -> HandshakeResult.DENIED
            0x53, 0x59 -> HandshakeResult.BUSY
            0x58 -> HandshakeResult.NO_FREE_SESSION
            else -> HandshakeResult.FAILED
        }
    }

    private fun handshakeV2(sessionKey: String, kind: Kind): HandshakeResult {
        val hello = ByteBuffer.allocate(V2_CLIENT_HELLO_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        hello.put(V2_COMMAND_NEW)
        hello.put(if (kind == Kind.KVM) 0 else 1)
        hello.putInt(0)
        val token = sessionKey.toByteArray(Charsets.ISO_8859_1)
        hello.put(token, 0, minOf(token.size, hello.remaining()))
        val helloBytes = hello.array()

        SecureRandom().nextBytes(encryptIv)
        buildEncrypters(CipherType.AES128)
        select(CipherType.AES128)
        encrypter!!.process(helloBytes, 0, helloBytes.size)
        writeRaw(encryptIv + helloBytes)

        readRawFully(decryptIv)
        buildDecrypters(CipherType.AES128)
        select(CipherType.AES128)
        val serverHello = ByteArray(V2_SERVER_HELLO_SIZE)
        readRawFully(serverHello)
        decrypter!!.process(serverHello, 0, serverHello.size)
        return when (serverHello[0].toInt()) {
            0 -> HandshakeResult.OK
            1 -> HandshakeResult.DENIED
            2 -> HandshakeResult.BUSY
            6 -> HandshakeResult.NO_FREE_SESSION
            7 -> HandshakeResult.NOT_LICENSED
            else -> HandshakeResult.FAILED
        }
    }

    private fun readRawFully(buf: ByteArray) {
        var done = 0
        while (done < buf.size) {
            val n = input.read(buf, done, buf.size - done)
            if (n < 0) throw EOFException()
            done += n
        }
        bytesReceived += buf.size
    }

    override fun close() {
        encryptKey.fill(0)
        decryptKey.fill(0)
        runCatching { socket.close() }
    }

    private companion object {
        const val V1_AUTHENTICATE = 0x50
        const val V1_REQ_KVM = 0x2001
        const val V1_REQ_COMMAND = 0x2002
        const val V1_REQ_TOKEN_OBFUSCATED = 0x8000
        const val V2_COMMAND_NEW: Byte = 0
        const val V2_CLIENT_HELLO_SIZE = 38
        const val V2_SERVER_HELLO_SIZE = 37
    }
}
