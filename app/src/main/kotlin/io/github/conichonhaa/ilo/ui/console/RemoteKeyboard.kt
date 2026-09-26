package io.github.conichonhaa.ilo.ui.console

import android.view.KeyCharacterMap
import android.view.KeyEvent
import io.github.conichonhaa.ilo.core.input.Hid
import io.github.conichonhaa.ilo.core.rc.KvmSession

/**
 * Translates Android key events and IME text into HID keyboard reports for the server.
 *
 * Printable characters are sent through the remote keyboard layout (so typing "a" produces an
 * "a" on an AZERTY server too); other keys are mapped by function. Modifiers selected on the
 * on-screen toolbar are "sticky": they apply to the next key only.
 */
class RemoteKeyboard(private val session: () -> KvmSession?) {
    private val sticky = linkedSetOf<Int>()

    /** Called when sticky modifiers change, with the set of active ones. */
    var onStickyChanged: (Set<Int>) -> Unit = {}

    fun toggleSticky(usage: Int) {
        val s = session() ?: return
        if (sticky.remove(usage)) {
            s.keyUp(usage)
        } else {
            sticky += usage
            s.keyDown(usage)
        }
        onStickyChanged(sticky.toSet())
    }

    private fun afterStroke() {
        if (sticky.isEmpty()) return
        val s = session()
        sticky.forEach { s?.keyUp(it) }
        sticky.clear()
        onStickyChanged(emptySet())
    }

    fun type(text: CharSequence) {
        if (text.isEmpty()) return
        session()?.type(text)
        afterStroke()
    }

    /** Presses and releases a key (with any sticky modifiers). */
    fun tap(usage: Int, vararg modifiers: Int) {
        session()?.tap(usage, *modifiers)
        afterStroke()
    }

    fun backspace(count: Int = 1) {
        repeat(count) { session()?.tap(Hid.BACKSPACE) }
        afterStroke()
    }

    fun onKeyEvent(event: KeyEvent): Boolean {
        val s = session() ?: return false
        val code = event.keyCode
        if (code in PASS_THROUGH) return false

        if (event.action == KeyEvent.ACTION_MULTIPLE) {
            if (code == KeyEvent.KEYCODE_UNKNOWN) {
                @Suppress("DEPRECATION")
                event.characters?.let(::type)
            }
            return true
        }
        val down = event.action == KeyEvent.ACTION_DOWN

        FUNCTION_KEYS[code]?.let { usage ->
            if (Hid.isModifier(usage)) {
                if (down) s.keyDown(usage) else s.keyUp(usage)
            } else if (down && event.repeatCount == 0) {
                s.keyDown(usage)
            } else if (!down) {
                s.keyUp(usage)
                afterStroke()
            }
            return true
        }

        if (!down) return true
        val shortcut = event.isCtrlPressed || event.isMetaPressed || (event.isAltPressed && !isAltGr(event))
        if (!shortcut && sticky.isEmpty()) {
            val ch = event.unicodeChar
            if (ch != 0 && ch and KeyCharacterMap.COMBINING_ACCENT == 0) {
                type(ch.toChar().toString())
                return true
            }
        }
        // Shortcuts (Ctrl+C...): send the key that carries this letter on the remote layout.
        val usage = when (code) {
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> s.layout.letterUsage('a' + (code - KeyEvent.KEYCODE_A))
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> s.layout.strokeFor('0' + (code - KeyEvent.KEYCODE_0))?.usage
            else -> null
        }
        if (usage != null) {
            tap(usage)
            return true
        }
        val ch = event.unicodeChar
        if (ch != 0) type(ch.toChar().toString())
        return true
    }

    private fun isAltGr(event: KeyEvent) = event.metaState and KeyEvent.META_ALT_RIGHT_ON != 0

    companion object {
        private val PASS_THROUGH = setOf(
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_HOME,
            KeyEvent.KEYCODE_APP_SWITCH,
            KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE,
        )

        /** Keys whose meaning does not depend on the keyboard layout. */
        val FUNCTION_KEYS: Map<Int, Int> = buildMap {
            put(KeyEvent.KEYCODE_ENTER, Hid.ENTER)
            put(KeyEvent.KEYCODE_DEL, Hid.BACKSPACE)
            put(KeyEvent.KEYCODE_FORWARD_DEL, Hid.DELETE)
            put(KeyEvent.KEYCODE_TAB, Hid.TAB)
            put(KeyEvent.KEYCODE_ESCAPE, Hid.ESCAPE)
            put(KeyEvent.KEYCODE_SPACE, Hid.SPACE)
            put(KeyEvent.KEYCODE_DPAD_UP, Hid.UP)
            put(KeyEvent.KEYCODE_DPAD_DOWN, Hid.DOWN)
            put(KeyEvent.KEYCODE_DPAD_LEFT, Hid.LEFT)
            put(KeyEvent.KEYCODE_DPAD_RIGHT, Hid.RIGHT)
            put(KeyEvent.KEYCODE_MOVE_HOME, Hid.HOME)
            put(KeyEvent.KEYCODE_MOVE_END, Hid.END)
            put(KeyEvent.KEYCODE_PAGE_UP, Hid.PAGE_UP)
            put(KeyEvent.KEYCODE_PAGE_DOWN, Hid.PAGE_DOWN)
            put(KeyEvent.KEYCODE_INSERT, Hid.INSERT)
            put(KeyEvent.KEYCODE_CAPS_LOCK, Hid.CAPS_LOCK)
            put(KeyEvent.KEYCODE_NUM_LOCK, Hid.NUM_LOCK)
            put(KeyEvent.KEYCODE_SCROLL_LOCK, Hid.SCROLL_LOCK)
            put(KeyEvent.KEYCODE_SYSRQ, Hid.PRINT_SCREEN)
            put(KeyEvent.KEYCODE_BREAK, Hid.PAUSE)
            put(KeyEvent.KEYCODE_MENU, Hid.APPLICATION)
            for (i in 0 until 12) put(KeyEvent.KEYCODE_F1 + i, Hid.F1 + i)
            put(KeyEvent.KEYCODE_CTRL_LEFT, Hid.LEFT_CTRL)
            put(KeyEvent.KEYCODE_CTRL_RIGHT, Hid.RIGHT_CTRL)
            put(KeyEvent.KEYCODE_SHIFT_LEFT, Hid.LEFT_SHIFT)
            put(KeyEvent.KEYCODE_SHIFT_RIGHT, Hid.RIGHT_SHIFT)
            put(KeyEvent.KEYCODE_ALT_LEFT, Hid.LEFT_ALT)
            put(KeyEvent.KEYCODE_ALT_RIGHT, Hid.RIGHT_ALT)
            put(KeyEvent.KEYCODE_META_LEFT, Hid.LEFT_GUI)
            put(KeyEvent.KEYCODE_META_RIGHT, Hid.RIGHT_GUI)
            put(KeyEvent.KEYCODE_NUMPAD_0, Hid.KP_0)
            for (i in 1..9) put(KeyEvent.KEYCODE_NUMPAD_1 + i - 1, Hid.KP_1 + i - 1)
            put(KeyEvent.KEYCODE_NUMPAD_DIVIDE, Hid.KP_DIVIDE)
            put(KeyEvent.KEYCODE_NUMPAD_MULTIPLY, Hid.KP_MULTIPLY)
            put(KeyEvent.KEYCODE_NUMPAD_SUBTRACT, Hid.KP_SUBTRACT)
            put(KeyEvent.KEYCODE_NUMPAD_ADD, Hid.KP_ADD)
            put(KeyEvent.KEYCODE_NUMPAD_DOT, Hid.KP_DECIMAL)
            put(KeyEvent.KEYCODE_NUMPAD_ENTER, Hid.KP_ENTER)
        }
    }
}
