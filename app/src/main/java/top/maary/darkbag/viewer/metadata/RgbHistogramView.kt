package top.maary.darkbag.viewer.metadata

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors

/**
 * Compact Bento RGB + Luma histogram view with subtle 25/50/75% tonal guides.
 */
class RgbHistogramView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var data: HistogramCalculator.HistogramData? = null
    private val path = Path()

    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 0.75f
        color = ColorUtils.setAlphaComponent(
            MaterialColors.getColor(context, com.google.android.material.R.attr.colorOutlineVariant, Color.GRAY),
            90
        )
    }

    private val redFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x44EF5350
    }
    private val greenFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x4466BB6A
    }
    private val blueFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x4442A5F5
    }

    private val redStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.25f
        color = 0xCCEF5350.toInt()
    }
    private val greenStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.25f
        color = 0xCC66BB6A.toInt()
    }
    private val blueStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.25f
        color = 0xCC42A5F5.toInt()
    }
    private val lumaStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 1.1f
        color = ColorUtils.setAlphaComponent(
            MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, Color.WHITE),
            150
        )
    }

    fun setHistogramData(histogram: HistogramCalculator.HistogramData?) {
        data = histogram
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Tonal quarter guides
        for (fraction in floatArrayOf(0.25f, 0.5f, 0.75f)) {
            val x = w * fraction
            canvas.drawLine(x, 0f, x, h, guidePaint)
        }

        val hist = data ?: return
        drawChannel(canvas, hist.red, w, h, redFill, redStroke)
        drawChannel(canvas, hist.green, w, h, greenFill, greenStroke)
        drawChannel(canvas, hist.blue, w, h, blueFill, blueStroke)
        drawChannel(canvas, hist.luma, w, h, null, lumaStroke)
    }

    private fun drawChannel(
        canvas: Canvas,
        bins: FloatArray,
        w: Float,
        h: Float,
        fillPaint: Paint?,
        strokePaint: Paint
    ) {
        val topPad = h * 0.08f
        val usableH = h - topPad
        path.reset()
        path.moveTo(0f, h)
        for (i in 0 until 256) {
            val x = (i / 255f) * w
            val y = h - (bins[i].coerceIn(0f, 1f) * usableH)
            path.lineTo(x, y)
        }
        if (fillPaint != null) {
            path.lineTo(w, h)
            path.close()
            canvas.drawPath(path, fillPaint)
        }
        // Stroke only the top curve
        path.reset()
        for (i in 0 until 256) {
            val x = (i / 255f) * w
            val y = h - (bins[i].coerceIn(0f, 1f) * usableH)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, strokePaint)
    }
}
