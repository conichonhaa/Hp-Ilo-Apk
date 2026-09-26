package io.github.conichonhaa.ilo.core.rc

/** ARGB_8888 pixels of the remote screen. Guard reads with `synchronized(frameBuffer)`. */
class FrameBuffer {
    var width = 0
        private set
    var height = 0
        private set
    var pixels = IntArray(0)
        private set

    /** Incremented on every change of geometry. */
    @Volatile
    var generation = 0
        private set

    @Synchronized
    fun resize(w: Int, h: Int) {
        if (w == width && h == height) return
        width = w
        height = h
        pixels = IntArray(w * h) { OPAQUE_BLACK }
        generation++
    }

    @Synchronized
    fun clear() {
        pixels.fill(OPAQUE_BLACK)
    }

    /** Copies a [bw]x[bh] block, clipped to the screen. */
    @Synchronized
    fun blit(block: IntArray, bw: Int, bh: Int, x: Int, y: Int) {
        if (x >= width || y >= height) return
        val w = minOf(bw, width - x)
        val h = minOf(bh, height - y)
        for (row in 0 until h) {
            System.arraycopy(block, row * bw, pixels, (y + row) * width + x, w)
        }
    }

    companion object {
        const val OPAQUE_BLACK = 0xFF000000.toInt()
    }
}
