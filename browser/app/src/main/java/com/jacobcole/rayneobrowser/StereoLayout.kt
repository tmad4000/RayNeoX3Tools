package com.jacobcole.rayneobrowser

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.widget.FrameLayout

/**
 * Stereoscopic wrapper for AR glasses whose 1280-wide framebuffer is split
 * into a left-eye and right-eye half. A single child takes the full left
 * half and the right half is a live PixelCopy mirror of that content.
 *
 * Using PixelCopy instead of view.draw() lets us keep the WebView in
 * hardware-accelerated rendering mode, which is required for video
 * decoding (YouTube). view.draw() cannot capture hardware video surfaces,
 * so the previous software-layer approach produced black video.
 *
 * Touch events in the right half are remapped onto the left-half child.
 */
class StereoLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle) {

    var stereoEnabled: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            if (value) scheduleMirror() else stopMirror()
            requestLayout()
            invalidate()
        }

    private val halfWidth: Int get() = width / 2
    private val uiHandler = Handler(Looper.getMainLooper())
    private var mirror: Bitmap? = null
    private val mirrorPaint = Paint()
    private val sourceRect = Rect()
    private val locationInWindow = IntArray(2)
    private var pendingCopy = false

    init { setWillNotDraw(false) }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (!stereoEnabled) return super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val totalWidth = MeasureSpec.getSize(widthMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val childW = MeasureSpec.makeMeasureSpec(totalWidth / 2, MeasureSpec.EXACTLY)
        val childH = MeasureSpec.makeMeasureSpec(heightSize, heightMode)
        for (i in 0 until childCount) measureChild(getChildAt(i), childW, childH)
        setMeasuredDimension(totalWidth, heightSize)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (!stereoEnabled) return super.onLayout(changed, left, top, right, bottom)
        val half = (right - left) / 2
        for (i in 0 until childCount) getChildAt(i).layout(0, 0, half, bottom - top)
    }

    override fun dispatchDraw(canvas: Canvas) {
        // Always draw real children on the left half
        super.dispatchDraw(canvas)
        if (!stereoEnabled) return
        // Draw the mirror bitmap on the right half
        mirror?.let { canvas.drawBitmap(it, halfWidth.toFloat(), 0f, mirrorPaint) }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!stereoEnabled) return super.dispatchTouchEvent(ev)
        val half = halfWidth
        if (ev.x >= half && half > 0) {
            val copy = MotionEvent.obtain(ev)
            copy.setLocation(ev.x - half, ev.y)
            val handled = super.dispatchTouchEvent(copy)
            copy.recycle()
            return handled
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (stereoEnabled) scheduleMirror()
    }

    override fun onDetachedFromWindow() {
        stopMirror()
        mirror?.recycle(); mirror = null
        super.onDetachedFromWindow()
    }

    private fun scheduleMirror() {
        if (pendingCopy) return
        uiHandler.post(mirrorRunnable)
    }

    private fun stopMirror() {
        uiHandler.removeCallbacks(mirrorRunnable)
        pendingCopy = false
    }

    private val mirrorRunnable = object : Runnable {
        override fun run() {
            if (!stereoEnabled || !isAttachedToWindow) { pendingCopy = false; return }
            val w = halfWidth; val h = height
            if (w <= 0 || h <= 0) {
                uiHandler.postDelayed(this, 100); return
            }
            val activity = context as? Activity
            val window = activity?.window
            if (window == null) { uiHandler.postDelayed(this, 200); return }

            if (mirror == null || mirror!!.width != w || mirror!!.height != h) {
                mirror?.recycle()
                mirror = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            }

            getLocationInWindow(locationInWindow)
            sourceRect.set(
                locationInWindow[0],
                locationInWindow[1],
                locationInWindow[0] + w,
                locationInWindow[1] + h
            )

            pendingCopy = true
            try {
                PixelCopy.request(
                    window, sourceRect, mirror!!,
                    { result ->
                        pendingCopy = false
                        if (result == PixelCopy.SUCCESS) invalidate()
                        if (stereoEnabled && isAttachedToWindow) {
                            // Target ~30fps; callback is on uiHandler
                            uiHandler.postDelayed(this, 33)
                        }
                    },
                    uiHandler
                )
            } catch (e: Exception) {
                pendingCopy = false
                uiHandler.postDelayed(this, 200)
            }
        }
    }
}
