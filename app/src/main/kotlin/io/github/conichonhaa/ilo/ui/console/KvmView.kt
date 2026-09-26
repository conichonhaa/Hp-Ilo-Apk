package io.github.conichonhaa.ilo.ui.console

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.text.InputType
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import io.github.conichonhaa.ilo.core.rc.KvmSession

/**
 * Displays the remote screen and turns touches, hardware keys and soft keyboard input into
 * console events.
 *
 * Gestures: tap = left click, double tap = double click, long press = right click,
 * one-finger drag = move the pointer, two fingers = pan, pinch = zoom.
 */
@SuppressLint("ViewConstructor")
class KvmView(context: Context, private val keyboard: RemoteKeyboard) : View(context) {
    var session: KvmSession? = null
        set(value) {
            field = value
            postInvalidate()
        }

    private var bitmap: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val drawMatrix = Matrix()

    private var zoom = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var scale = 1f
    private var contentW = 0
    private var contentH = 0

    /** Composing text currently shown by the IME, already typed on the server. */
    private var composing = ""

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Color.BLACK)
    }

    fun resetZoom() {
        zoom = 1f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val fb = session?.frameBuffer ?: return
        val bmp = synchronized(fb) {
            if (fb.width == 0 || fb.height == 0) return
            var b = bitmap
            if (b == null || b.width != fb.width || b.height != fb.height) {
                b = Bitmap.createBitmap(fb.width, fb.height, Bitmap.Config.ARGB_8888)
                bitmap = b
            }
            b.setPixels(fb.pixels, 0, fb.width, 0, 0, fb.width, fb.height)
            b
        }
        layoutContent(bmp.width, bmp.height)
        drawMatrix.setScale(scale, scale)
        drawMatrix.postTranslate(offsetX, offsetY)
        canvas.drawBitmap(bmp, drawMatrix, paint)
    }

    private fun layoutContent(w: Int, h: Int) {
        if (width == 0 || height == 0) return
        contentW = w
        contentH = h
        val fit = minOf(width.toFloat() / w, height.toFloat() / h)
        scale = fit * zoom
        offsetX = clampOffset(offsetX, w * scale, width.toFloat())
        offsetY = clampOffset(offsetY, h * scale, height.toFloat())
    }

    private fun clampOffset(offset: Float, content: Float, view: Float): Float =
        if (content <= view) (view - content) / 2 else offset.coerceIn(view - content, 0f)

    private fun toRemoteX(x: Float) = ((x - offsetX) / scale).toInt()
    private fun toRemoteY(y: Float) = ((y - offsetY) / scale).toInt()

    private fun insideContent(e: MotionEvent): Boolean {
        val x = (e.x - offsetX) / scale
        val y = (e.y - offsetY) / scale
        return contentW > 0 && x >= 0 && y >= 0 && x < contentW && y < contentH
    }

    // ------------------------------------------------------------------ touch

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val newZoom = (zoom * detector.scaleFactor).coerceIn(1f, 8f)
            val ratio = newZoom / zoom
            offsetX = detector.focusX - (detector.focusX - offsetX) * ratio
            offsetY = detector.focusY - (detector.focusY - offsetY) * ratio
            zoom = newZoom
            invalidate()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (insideContent(e)) session?.click(toRemoteX(e.x), toRemoteY(e.y), KvmSession.Buttons.LEFT)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (insideContent(e)) {
                val x = toRemoteX(e.x)
                val y = toRemoteY(e.y)
                session?.click(x, y, KvmSession.Buttons.LEFT)
                session?.click(x, y, KvmSession.Buttons.LEFT)
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (insideContent(e)) {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                session?.click(toRemoteX(e.x), toRemoteY(e.y), KvmSession.Buttons.RIGHT)
            }
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (e2.pointerCount >= 2) {
                offsetX -= distanceX
                offsetY -= distanceY
                invalidate()
            } else if (!scaleDetector.isInProgress) {
                session?.mouse(toRemoteX(e2.x), toRemoteY(e2.y), KvmSession.Buttons.NONE)
            }
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        // Physical mouse / trackpad hovering.
        if (event.action == MotionEvent.ACTION_HOVER_MOVE && insideContent(event)) {
            session?.mouse(toRemoteX(event.x), toRemoteY(event.y), mouseButtons(event))
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun mouseButtons(e: MotionEvent): Int {
        var b = 0
        if (e.buttonState and MotionEvent.BUTTON_PRIMARY != 0) b = b or KvmSession.Buttons.LEFT
        if (e.buttonState and MotionEvent.BUTTON_SECONDARY != 0) b = b or KvmSession.Buttons.RIGHT
        if (e.buttonState and MotionEvent.BUTTON_TERTIARY != 0) b = b or KvmSession.Buttons.MIDDLE
        return b
    }

    // --------------------------------------------------------------- keyboard

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        keyboard.onKeyEvent(event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        keyboard.onKeyEvent(event) || super.onKeyUp(keyCode, event)

    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean =
        keyboard.onKeyEvent(event) || super.onKeyMultiple(keyCode, repeatCount, event)

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_ACTION_NONE
        composing = ""
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                replaceComposing(text.toString())
                composing = ""
                return true
            }

            override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
                replaceComposing(text.toString())
                return true
            }

            override fun finishComposingText(): Boolean {
                composing = ""
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                keyboard.backspace(maxOf(beforeLength, 1))
                return true
            }
        }
    }

    /** Some IMEs compose words: mirror each change with backspaces + the new suffix. */
    private fun replaceComposing(text: String) {
        val common = composing.commonPrefixWith(text).length
        val remove = composing.length - common
        if (remove > 0) keyboard.backspace(remove)
        keyboard.type(text.substring(common))
        composing = text
    }

    fun showSoftKeyboard() {
        requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideSoftKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(windowToken, 0)
    }
}
