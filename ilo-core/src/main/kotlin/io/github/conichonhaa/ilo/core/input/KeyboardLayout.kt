package io.github.conichonhaa.ilo.core.input

/**
 * The keyboard layout configured in the *remote* operating system. HID reports carry key
 * positions, not characters, so text typed on the phone must be translated with the layout
 * the server expects.
 */
enum class KeyboardLayout(private val table: Map<Char, KeyStroke>) {
    US(buildUs()),
    FR(buildFr());

    fun strokeFor(c: Char): KeyStroke? = table[c]

    /** Usage of the key that produces the (unshifted) letter [c] — used for Ctrl+letter shortcuts. */
    fun letterUsage(c: Char): Int = table[c.lowercaseChar()]?.usage ?: Hid.letter(c)

    companion object {
        fun fromId(id: String?): KeyboardLayout = entries.firstOrNull { it.name == id } ?: US
    }
}

private fun MutableMap<Char, KeyStroke>.common() {
    put(' ', KeyStroke(Hid.SPACE))
    put('\n', KeyStroke(Hid.ENTER))
    put('\r', KeyStroke(Hid.ENTER))
    put('\t', KeyStroke(Hid.TAB))
}

private fun buildUs(): Map<Char, KeyStroke> = buildMap {
    common()
    for (c in 'a'..'z') {
        put(c, KeyStroke(Hid.letter(c)))
        put(c.uppercaseChar(), KeyStroke(Hid.letter(c), shift = true))
    }
    for (d in 0..9) put('0' + d, KeyStroke(Hid.digit(d)))
    ")!@#$%^&*(".forEachIndexed { d, c -> put(c, KeyStroke(Hid.digit(d), shift = true)) }
    fun pair(usage: Int, normal: Char, shifted: Char) {
        put(normal, KeyStroke(usage))
        put(shifted, KeyStroke(usage, shift = true))
    }
    pair(Hid.MINUS, '-', '_')
    pair(Hid.EQUAL, '=', '+')
    pair(Hid.BRACKET_LEFT, '[', '{')
    pair(Hid.BRACKET_RIGHT, ']', '}')
    pair(Hid.BACKSLASH, '\\', '|')
    pair(Hid.SEMICOLON, ';', ':')
    pair(Hid.QUOTE, '\'', '"')
    pair(Hid.GRAVE, '`', '~')
    pair(Hid.COMMA, ',', '<')
    pair(Hid.PERIOD, '.', '>')
    pair(Hid.SLASH, '/', '?')
}

/** French AZERTY (fr-FR) as configured on Windows and Linux. */
private fun buildFr(): Map<Char, KeyStroke> = buildMap {
    common()
    val swapped = mapOf('a' to 'q', 'q' to 'a', 'z' to 'w', 'w' to 'z')
    for (c in 'a'..'z') {
        if (c == 'm') continue
        val usage = Hid.letter(swapped[c] ?: c)
        put(c, KeyStroke(usage))
        put(c.uppercaseChar(), KeyStroke(usage, shift = true))
    }
    put('m', KeyStroke(Hid.SEMICOLON))
    put('M', KeyStroke(Hid.SEMICOLON, shift = true))

    // Number row: symbols unshifted, digits with Shift, extra symbols with AltGr.
    val unshifted = "&é\"'(-è_çà"
    val altGr = mapOf(2 to '~', 3 to '#', 4 to '{', 5 to '[', 6 to '|', 7 to '`', 8 to '\\', 9 to '^', 10 to '@')
    for (i in 1..10) {
        val usage = Hid.DIGIT_1 + i - 1
        put(unshifted[i - 1], KeyStroke(usage))
        put('0' + (i % 10), KeyStroke(usage, shift = true))
        altGr[i]?.let { put(it, KeyStroke(usage, altGr = true)) }
    }
    fun key(usage: Int, normal: Char?, shifted: Char?, alt: Char? = null) {
        normal?.let { put(it, KeyStroke(usage)) }
        shifted?.let { put(it, KeyStroke(usage, shift = true)) }
        alt?.let { put(it, KeyStroke(usage, altGr = true)) }
    }
    key(Hid.MINUS, ')', '°', ']')
    key(Hid.EQUAL, '=', '+', '}')
    key(Hid.BRACKET_RIGHT, '$', '£', '¤')
    key(Hid.BACKSLASH, '*', 'µ')
    key(Hid.QUOTE, 'ù', '%')
    key(Hid.GRAVE, '²', null)
    key(Hid.letter('m'), ',', '?')
    key(Hid.COMMA, ';', '.')
    key(Hid.PERIOD, ':', '/')
    key(Hid.SLASH, '!', '§')
    key(Hid.NON_US_BACKSLASH, '<', '>')
    put('€', KeyStroke(Hid.letter('e'), altGr = true))
}
