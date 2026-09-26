package io.github.conichonhaa.ilo.core.input

/** USB HID keyboard usage IDs (HID Usage Tables, page 0x07). */
object Hid {
    const val A = 0x04
    const val Z = 0x1D
    const val DIGIT_1 = 0x1E
    const val DIGIT_0 = 0x27
    const val ENTER = 0x28
    const val ESCAPE = 0x29
    const val BACKSPACE = 0x2A
    const val TAB = 0x2B
    const val SPACE = 0x2C
    const val MINUS = 0x2D
    const val EQUAL = 0x2E
    const val BRACKET_LEFT = 0x2F
    const val BRACKET_RIGHT = 0x30
    const val BACKSLASH = 0x31
    const val SEMICOLON = 0x33
    const val QUOTE = 0x34
    const val GRAVE = 0x35
    const val COMMA = 0x36
    const val PERIOD = 0x37
    const val SLASH = 0x38
    const val CAPS_LOCK = 0x39
    const val F1 = 0x3A
    const val F12 = 0x45
    const val PRINT_SCREEN = 0x46
    const val SCROLL_LOCK = 0x47
    const val PAUSE = 0x48
    const val INSERT = 0x49
    const val HOME = 0x4A
    const val PAGE_UP = 0x4B
    const val DELETE = 0x4C
    const val END = 0x4D
    const val PAGE_DOWN = 0x4E
    const val RIGHT = 0x4F
    const val LEFT = 0x50
    const val DOWN = 0x51
    const val UP = 0x52
    const val NUM_LOCK = 0x53
    const val KP_DIVIDE = 0x54
    const val KP_MULTIPLY = 0x55
    const val KP_SUBTRACT = 0x56
    const val KP_ADD = 0x57
    const val KP_ENTER = 0x58
    const val KP_1 = 0x59
    const val KP_0 = 0x62
    const val KP_DECIMAL = 0x63
    const val NON_US_BACKSLASH = 0x64
    const val APPLICATION = 0x65

    const val LEFT_CTRL = 0xE0
    const val LEFT_SHIFT = 0xE1
    const val LEFT_ALT = 0xE2
    const val LEFT_GUI = 0xE3
    const val RIGHT_CTRL = 0xE4
    const val RIGHT_SHIFT = 0xE5
    const val RIGHT_ALT = 0xE6
    const val RIGHT_GUI = 0xE7

    fun isModifier(usage: Int) = usage in LEFT_CTRL..RIGHT_GUI

    /** Bit of [usage] in the modifier byte of a keyboard report. */
    fun modifierBit(usage: Int) = 1 shl (usage and 7)

    fun letter(c: Char): Int = A + (c.lowercaseChar() - 'a')

    fun digit(d: Int): Int = if (d == 0) DIGIT_0 else DIGIT_1 + d - 1

    fun function(n: Int): Int = F1 + n - 1
}

/** One key press needed to produce a character. */
data class KeyStroke(val usage: Int, val shift: Boolean = false, val altGr: Boolean = false)
