package io.github.conichonhaa.ilo.core.rc

import io.github.conichonhaa.ilo.core.crypto.KeyDerivation
import io.github.conichonhaa.ilo.core.input.Hid
import io.github.conichonhaa.ilo.core.input.KeyboardLayout
import io.github.conichonhaa.ilo.core.net.IloClient
import io.github.conichonhaa.ilo.core.net.IloException
import io.github.conichonhaa.ilo.core.net.IloSession
import io.github.conichonhaa.ilo.core.net.RcInfo
import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Virtual power button actions available through the console channel. */
enum class PowerAction(val code: Int) {
    /** Momentary press: power on, or ask the OS to shut down. */
    PRESS(0),
    /** Press and hold: forced power off. */
    PRESS_AND_HOLD(1),
    /** Cold boot: power cycle. */
    COLD_BOOT(2),
    /** Warm reset. */
    RESET(3),
}

enum class CloseReason {
    USER,
    LOGIN_FAILED,
    RC_UNAVAILABLE,
    CONNECT_FAILED,
    DENIED,
    BUSY,
    NO_FREE_SESSION,
    NOT_LICENSED,
    HANDSHAKE_FAILED,
    CONNECTION_LOST,
    SEIZED,
    SERVER_CLOSED,
}

/**
 * A complete remote console session: JSON login, retrieval of the console key, KVM channel
 * (video in, keyboard/mouse/power out) and command channel (power/health/POST status).
 *
 * All callbacks of [Listener] are invoked from background threads.
 */
class KvmSession(
    private val client: IloClient,
    private val username: String,
    private val password: String,
    private val listener: Listener,
) {
    interface Listener {
        fun onConnecting(step: Step) {}
        fun onConnected() {}
        fun onVideoMode(width: Int, height: Int, hasSignal: Boolean) {}
        fun onFrameUpdated() {}
        fun onEncryption(type: Int) {}
        fun onLicense(licensed: Boolean) {}
        fun onPowerState(on: Boolean) {}
        fun onHealth(status: Int) {}
        fun onPostCode(code: Int) {}
        fun onNotice(notice: Notice) {}
        fun onClosed(reason: CloseReason, error: Throwable?) {}
    }

    enum class Step { LOGIN, RC_INFO, VIDEO_CHANNEL, COMMAND_CHANNEL }

    sealed class Notice {
        /** Another user asked to share the console; the request was declined. */
        data class ShareRequestDenied(val user: String, val address: String) : Notice()
        data class SeizedBy(val user: String, val address: String) : Notice()
        data class Unauthorized(val what: Int) : Notice()
        data object FirmwareUpdate : Notice()
    }

    val frameBuffer = FrameBuffer()

    var layout: KeyboardLayout = KeyboardLayout.US

    @Volatile private var closed = false
    @Volatile private var closeNotified = false
    private var session: IloSession? = null
    private var kvm: RcChannel? = null
    private var cmd: RcChannel? = null
    private var decoder: DvcDecoder? = null
    private var mainThread: Thread? = null
    private var cmdThread: Thread? = null
    private val sender: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "kvm-input").apply { isDaemon = true }
    }

    private val pressed = BooleanArray(256)
    private var mouseX = 0
    private var mouseY = 0

    fun start() {
        check(mainThread == null) { "Session already started" }
        mainThread = Thread({ runSession() }, "kvm-session").apply {
            isDaemon = true
            start()
        }
    }

    /** Closes the console and logs out. */
    fun stop() {
        close(CloseReason.USER, null)
    }

    private fun runSession() {
        try {
            listener.onConnecting(Step.LOGIN)
            val s = client.login(username, password)
            session = s
            if (closed) {
                client.logout(s)
                return
            }

            listener.onConnecting(Step.RC_INFO)
            val info = try {
                client.rcInfo(s)
            } catch (e: IloException) {
                close(if (e.kind == IloException.Kind.TLS) CloseReason.LOGIN_FAILED else CloseReason.RC_UNAVAILABLE, e)
                return
            }
            val keys = if (info.version2) KeyDerivation.derive(info.key) else null

            listener.onConnecting(Step.VIDEO_CHANNEL)
            val video = try {
                RcChannel(client.host, info.port)
            } catch (e: IOException) {
                close(CloseReason.CONNECT_FAILED, e)
                return
            }
            kvm = video
            if (keys != null) video.setKeys(keys.kvmEncrypt, keys.kvmDecrypt) else video.setKeys(info.key, info.key)
            val result = video.handshake(info, s.sessionKey, RcChannel.Kind.KVM)
            if (result != HandshakeResult.OK) {
                close(handshakeReason(result), null)
                return
            }
            if (closed) return

            listener.onConnecting(Step.COMMAND_CHANNEL)
            startCommandChannel(info, s, keys)

            video.setReadTimeout(1000)
            listener.onConnected()
            val d = DvcDecoder(VideoTransport(video), DecoderEvents(), frameBuffer)
            decoder = d
            d.run()
            close(CloseReason.SERVER_CLOSED, null)
        } catch (e: DvcDecoder.StoppedException) {
            close(CloseReason.USER, null)
        } catch (e: IloException) {
            close(CloseReason.LOGIN_FAILED, e)
        } catch (e: IOException) {
            close(CloseReason.CONNECTION_LOST, e)
        } catch (e: RuntimeException) {
            close(CloseReason.CONNECTION_LOST, e)
        }
    }

    private fun handshakeReason(r: HandshakeResult) = when (r) {
        HandshakeResult.DENIED -> CloseReason.DENIED
        HandshakeResult.BUSY -> CloseReason.BUSY
        HandshakeResult.NO_FREE_SESSION -> CloseReason.NO_FREE_SESSION
        HandshakeResult.NOT_LICENSED -> CloseReason.NOT_LICENSED
        else -> CloseReason.HANDSHAKE_FAILED
    }

    private fun startCommandChannel(info: RcInfo, s: IloSession, keys: KeyDerivation.SessionKeys?) {
        cmdThread = Thread({
            try {
                val c = RcChannel(client.host, info.port)
                cmd = c
                if (keys != null) c.setKeys(keys.cmdEncrypt, keys.cmdDecrypt) else c.setKeys(info.key, info.key)
                if (c.handshake(info, s.sessionKey, RcChannel.Kind.COMMAND) != HandshakeResult.OK) return@Thread
                c.setReadTimeout(1000)
                listenCommands(c)
            } catch (_: IOException) {
                // The status channel is optional: the console keeps working without it.
            }
        }, "kvm-command").apply {
            isDaemon = true
            start()
        }
    }

    private fun listenCommands(c: RcChannel) {
        val header = ByteArray(12)
        val payload = ByteArray(256)
        while (!closed) {
            try {
                readWithTimeouts(c, header, header.size)
            } catch (_: SocketTimeoutException) {
                continue
            }
            val command = header[0].toInt() and 0xff
            val size = header[4].toInt() and 0xff
            val flag = header[10].toInt() and 0xff
            if (size > 0) readWithTimeouts(c, payload, size)
            when (command) {
                3 -> listener.onPowerState(payload[0].toInt() != 0)
                4 -> listener.onHealth(payload[0].toInt() and 0xff)
                5 -> listener.onPostCode(((payload[1].toInt() and 0xff) shl 8) or (payload[0].toInt() and 0xff))
                6 -> {
                    val (user, addr) = readUserInfo(c)
                    c.write(byteArrayOf(4, 0, 0, 0)) // acknowledge: we give up the console
                    listener.onNotice(Notice.SeizedBy(user, addr))
                    close(CloseReason.SEIZED, null)
                    return
                }
                9 -> {
                    val (user, addr) = readUserInfo(c)
                    c.write(byteArrayOf(3, 0, 0, 0)) // decline the share request
                    listener.onNotice(Notice.ShareRequestDenied(user, addr))
                }
                10 -> listener.onNotice(Notice.FirmwareUpdate)
                11 -> listener.onNotice(Notice.Unauthorized(flag))
            }
        }
    }

    private fun readUserInfo(c: RcChannel): Pair<String, String> {
        val buf = ByteArray(128)
        return try {
            readWithTimeouts(c, buf, buf.size)
            fun field(from: Int) = String(buf, from, 64, Charsets.ISO_8859_1).trim { it <= ' ' }.ifEmpty { "?" }
            field(0) to field(64)
        } catch (_: IOException) {
            "?" to "?"
        }
    }

    /** readFully that tolerates the 1 s socket timeout, unless nothing at all was received. */
    private fun readWithTimeouts(c: RcChannel, buf: ByteArray, len: Int) {
        var done = 0
        var idle = 0
        while (done < len) {
            if (closed) throw EOFException()
            val n = try {
                c.read(buf, done, len - done)
            } catch (e: SocketTimeoutException) {
                if (done == 0) throw e
                if (++idle > 45) throw e
                continue
            }
            if (n < 0) throw EOFException()
            done += n
        }
    }

    private inner class VideoTransport(private val channel: RcChannel) : DvcTransport {
        override fun read(buf: ByteArray, max: Int): Int {
            var idle = 0
            while (true) {
                if (closed) throw DvcDecoder.StoppedException()
                val n = try {
                    channel.read(buf, 0, max)
                } catch (e: SocketTimeoutException) {
                    // The iLO sends keep-alives; 45 s of silence means the link is dead.
                    if (++idle >= 45) throw e
                    continue
                }
                if (n < 0) throw EOFException("Console stream closed by the iLO")
                if (n > 0) return n
            }
        }

        override fun setEncryption(type: Int) = channel.initCrypto(type)

        override fun send(data: ByteArray) = channel.write(data)
    }

    private inner class DecoderEvents : DvcListener {
        override fun onVideoMode(width: Int, height: Int, hasSignal: Boolean) = listener.onVideoMode(width, height, hasSignal)
        override fun onFrameUpdated() = listener.onFrameUpdated()
        override fun onEncryption(type: Int) = listener.onEncryption(type)
        override fun onStreamHeader(licensed: Boolean, osStarted: Boolean) {
            if (!licensed && osStarted) listener.onLicense(false)
        }
        override fun onPowerChanged(on: Boolean) = listener.onPowerState(on)
        override fun onSeized() = close(CloseReason.SEIZED, null)
    }

    // ---------------------------------------------------------------- input

    private fun post(block: () -> Unit) {
        if (closed) return
        runCatching { sender.execute { runCatching(block) } }
    }

    private fun sendKvm(data: ByteArray) {
        kvm?.write(data)
    }

    /** Presses a key (HID usage) until [keyUp] is called. */
    fun keyDown(usage: Int) = post {
        pressed[usage and 0xff] = true
        sendKeyboardReport(0, null, 0xff)
    }

    fun keyUp(usage: Int) = post {
        pressed[usage and 0xff] = false
        sendKeyboardReport(0, null, 0xff)
    }

    /** Presses and releases [usage] together with the given extra modifier usages. */
    fun tap(usage: Int, vararg modifiers: Int) = post {
        var mods = 0
        modifiers.forEach { mods = mods or Hid.modifierBit(it) }
        sendKeyboardReport(mods, usage, 0xff)
        sendKeyboardReport(0, null, 0xff)
    }

    /**
     * Types [text] using the configured remote [layout]. Characters that cannot be produced
     * with the layout are skipped. Returns the number of characters sent.
     */
    fun type(text: CharSequence): Int {
        var count = 0
        for (ch in text) {
            val stroke = layout.strokeFor(ch) ?: continue
            count++
            post {
                var mods = 0
                if (stroke.shift) mods = mods or Hid.modifierBit(Hid.LEFT_SHIFT)
                if (stroke.altGr) mods = mods or Hid.modifierBit(Hid.RIGHT_ALT)
                // Physically held Shift must not alter the character chosen by the layout.
                val keep = 0xff and (Hid.modifierBit(Hid.LEFT_SHIFT) or Hid.modifierBit(Hid.RIGHT_SHIFT)).inv()
                sendKeyboardReport(mods, stroke.usage, keep)
                sendKeyboardReport(0, null, keep)
                sendKeyboardReport(0, null, 0xff)
            }
        }
        return count
    }

    fun releaseAllKeys() = post {
        pressed.fill(false)
        sendKeyboardReport(0, null, 0xff)
    }

    fun ctrlAltDel() = tap(Hid.DELETE, Hid.LEFT_CTRL, Hid.LEFT_ALT)

    /**
     * Keyboard report: [1, 0, modifiers, 0, key1..key6]. [keepMods] masks the modifiers
     * currently held down.
     */
    private fun sendKeyboardReport(extraMods: Int, extraKey: Int?, keepMods: Int) {
        val report = ByteArray(10)
        report[0] = 1
        var mods = 0
        var slot = 4
        for (u in 1 until 256) {
            if (!pressed[u]) continue
            if (Hid.isModifier(u)) {
                mods = mods or Hid.modifierBit(u)
            } else if (slot < 10) {
                report[slot++] = u.toByte()
            }
        }
        if (extraKey != null && slot < 10) report[slot] = extraKey.toByte()
        report[2] = ((mods and keepMods) or extraMods).toByte()
        sendKvm(report)
    }

    /** Mouse button bits for [mouse]. */
    object Buttons {
        const val NONE = 0
        const val LEFT = 1
        const val RIGHT = 2
        const val MIDDLE = 4
    }

    /** Moves the pointer to ([x], [y]) in remote screen pixels with the given buttons held. */
    fun mouse(x: Int, y: Int, buttons: Int) = post {
        val d = decoder
        val w = d?.videoWidth ?: 1
        val h = d?.videoHeight ?: 1
        val cx = x.coerceIn(0, w - 1)
        val cy = y.coerceIn(0, h - 1)
        val relX = (cx - mouseX).coerceIn(-127, 127)
        val relY = (cy - mouseY).coerceIn(-127, 127)
        mouseX = cx
        mouseY = cy
        val absX = (3000.0 * cx / w).toInt()
        val absY = (3000.0 * cy / h).toInt()
        val p = ByteArray(10)
        p[0] = 2
        p[2] = absX.toByte()
        p[3] = (absX shr 8).toByte()
        p[4] = absY.toByte()
        p[5] = (absY shr 8).toByte()
        p[6] = (if (relX < 0) -relX + 128 else relX).toByte()
        p[7] = (if (relY < 0) relY + 128 else relY).toByte()
        p[8] = buttons.toByte()
        sendKvm(p)
    }

    fun click(x: Int, y: Int, buttons: Int = Buttons.LEFT) {
        mouse(x, y, Buttons.NONE)
        mouse(x, y, buttons)
        mouse(x, y, Buttons.NONE)
    }

    fun power(action: PowerAction) = post {
        sendKvm(byteArrayOf(0, 0, action.code.toByte(), 0))
        frameBuffer.clear()
        listener.onFrameUpdated()
    }

    fun requestRefresh() = post { sendKvm(byteArrayOf(5, 0)) }

    val trafficReceived: Long get() = (kvm?.bytesReceived ?: 0) + (cmd?.bytesReceived ?: 0)

    // ---------------------------------------------------------------- teardown

    private fun close(reason: CloseReason, error: Throwable?) {
        synchronized(this) {
            if (closeNotified) return
            closeNotified = true
            closed = true
        }
        decoder?.stop()
        Thread({
            runCatching { sender.shutdown(); sender.awaitTermination(1, TimeUnit.SECONDS) }
            runCatching { kvm?.close() }
            runCatching { cmd?.close() }
            session?.let { client.logout(it) }
            listener.onClosed(reason, error)
        }, "kvm-close").apply {
            isDaemon = true
            start()
        }
    }
}
