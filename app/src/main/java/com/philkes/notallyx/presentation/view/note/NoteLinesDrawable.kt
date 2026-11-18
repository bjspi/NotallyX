package com.philkes.notallyx.presentation.view.note

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.widget.TextView
import com.philkes.notallyx.presentation.withAlpha

internal class NoteLinesDrawable(private val textView: TextView) : Drawable() {
    private val density = textView.resources.displayMetrics.density
    private val paint = Paint().apply { strokeWidth = (0.6f * density).coerceAtLeast(1f) }
    private val clip = Rect()

    override fun draw(canvas: Canvas) {
        val textLayout = textView.layout ?: return
        if (!canvas.getClipBounds(clip) || !clip.intersect(bounds)) return

        // The background is fixed to the view. The text can scroll inside its padding.
        val textTop = textView.totalPaddingTop - textView.scrollY
        val top = maxOf(clip.top, textView.extendedPaddingTop)
        val bottom = minOf(clip.bottom, textView.height - textView.extendedPaddingBottom)
        if (top >= bottom) return

        val left = textView.compoundPaddingLeft.toFloat()
        val right = (textView.width - textView.compoundPaddingRight).toFloat()
        val firstLine = textLayout.getLineForVertical(top - textTop)
        val lastLine = textLayout.getLineForVertical(bottom - textTop)
        paint.color = textView.currentTextColor.withAlpha(0.22f)
        for (line in firstLine..lastLine) {
            val y = textTop + textLayout.getLineBaseline(line) + density
            if (y >= top && y < bottom) canvas.drawLine(left, y, right, y, paint)
        }
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(colorFilter: ColorFilter?) {}

    @Deprecated("Required by Drawable") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
