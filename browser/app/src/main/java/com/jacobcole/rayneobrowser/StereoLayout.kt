package com.jacobcole.rayneobrowser

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

/**
 * Wraps a single child that occupies the left half of the screen (width/2).
 * Draws the child twice side-by-side so each eye of an AR stereo display sees
 * the same UI. Remaps touches from the right half back into the left half so
 * interactions from either eye work.
 *
 * Child layout: place content at (0,0) with layout_width=halfWidth (or let
 * StereoLayout enforce that in onMeasure).
 */
class StereoLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle) {

    private val halfWidth: Int
        get() = width / 2

    init {
        // We draw children manually in dispatchDraw; no need for background
        setWillNotDraw(false)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val totalWidth = MeasureSpec.getSize(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)

        val childWidthSpec = MeasureSpec.makeMeasureSpec(totalWidth / 2, MeasureSpec.EXACTLY)
        val childHeightSpec = MeasureSpec.makeMeasureSpec(heightSize, heightMode)

        for (i in 0 until childCount) {
            measureChild(getChildAt(i), childWidthSpec, childHeightSpec)
        }
        setMeasuredDimension(totalWidth, heightSize)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val half = (right - left) / 2
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            c.layout(0, 0, half, bottom - top)
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        // Left eye — children at native x=0..halfWidth
        super.dispatchDraw(canvas)
        // Right eye — same children translated to x=halfWidth..width
        val save = canvas.save()
        canvas.translate(halfWidth.toFloat(), 0f)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(save)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // If the touch lands on the right eye, translate it back to the left-eye coords.
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
}
