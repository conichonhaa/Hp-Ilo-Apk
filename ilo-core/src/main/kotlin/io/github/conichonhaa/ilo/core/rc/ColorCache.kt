package io.github.conichonhaa.ilo.core.rc

/**
 * Small most-recently-used palette of the DVC video codec. Pixels can reference one of the
 * recently seen colours by rank instead of sending the full RGB value.
 */
internal class ColorCache {
    private val color = IntArray(SIZE)
    private val usage = IntArray(SIZE)
    private val blockCounter = IntArray(SIZE)

    /** Number of valid entries. */
    var active = 0
        private set

    /** Decoder state used to read a cached colour reference, depends on [active]. */
    var pixcode = DvcDecoder.S_LATCHED
        private set

    fun firstUsage(): Int = usage[0]

    fun reset() {
        active = 0
        usage.fill(0)
    }

    /** Invalidates the palette after a decoding error. */
    fun invalidate() {
        active = 0
    }

    /** Looks up the entry of rank [code]. Returns its colour, or -1 if there is none. */
    fun find(code: Int): Int {
        if (active > SIZE) return -1
        for (i in 0 until active) {
            if (usage[i] == code) {
                val c = color[i]
                for (j in 0 until active) if (usage[j] < code) usage[j]++
                usage[i] = 0
                blockCounter[i] = 1
                return c
            }
        }
        return -1
    }

    /**
     * Records colour [c] as most recently used. Returns true when it was already cached
     * (which the encoder never does for an explicitly transmitted colour).
     */
    fun touch(c: Int): Boolean {
        if (active > SIZE) return false
        var index = 0
        var found = false
        for (i in 0 until active) {
            if (color[i] == c) {
                index = i
                found = true
                break
            }
            if (usage[i] == active - 1) index = i
        }
        var rank = usage[index]
        if (!found) {
            if (active < SIZE) {
                index = active
                rank = active
                active++
                updatePixcode()
            }
            color[index] = c
        }
        blockCounter[index] = 1
        for (i in 0 until active) if (usage[i] < rank) usage[i]++
        usage[index] = 0
        return found
    }

    /** Called after each completed block: drops colours unused during the last two blocks. */
    fun prune() {
        if (active > SIZE) return
        var n = active
        var i = 0
        while (i < n) {
            if (blockCounter[i] == 0) {
                n--
                blockCounter[i] = blockCounter[n]
                color[i] = color[n]
                usage[i] = usage[n]
            } else {
                blockCounter[i]--
                i++
            }
        }
        active = n
        updatePixcode()
    }

    private fun updatePixcode() {
        pixcode = when {
            active < 2 -> DvcDecoder.S_LATCHED
            active == 2 -> DvcDecoder.S_PIXLRU0
            active == 3 -> DvcDecoder.S_PIXCODE1
            active < 6 -> DvcDecoder.S_PIXCODE2
            active < 10 -> DvcDecoder.S_PIXCODE3
            else -> DvcDecoder.S_PIXCODE4
        }
    }

    private companion object {
        const val SIZE = 17
    }
}
