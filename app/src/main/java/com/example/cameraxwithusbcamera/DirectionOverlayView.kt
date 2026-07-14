package com.example.cameraxwithusbcamera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View

class DirectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var trackingResult: TrackingResult? = null

    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN
        strokeWidth = 8f
        style = Paint.Style.FILL
    }

    private val arrowStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00FF00")
        strokeWidth = 4f
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00FF00")
        textSize = 80f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 0, 0, 0)
        style = Paint.Style.FILL
    }

    private val boundingBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00FF00")
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00FF00")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    fun setResult(result: TrackingResult?) {
        trackingResult = result
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val result = trackingResult ?: return

        val cx = width / 2f
        val arrowBaseY = height - 200f

        val dir = result.direction

        if (dir != null) {
            val arrowTipX: Float
            val label: String
            when (dir) {
                Direction.IN -> {
                    arrowTipX = cx
                    label = "IN"
                    arrowPaint.color = Color.GREEN
                    arrowStrokePaint.color = Color.parseColor("#00FF00")
                    textPaint.color = Color.parseColor("#00FF00")
                }
                Direction.OUT -> {
                    arrowTipX = if (result.centroid?.x ?: cx > cx) width + 100f else -100f
                    label = "OUT"
                    arrowPaint.color = Color.RED
                    arrowStrokePaint.color = Color.parseColor("#FF4444")
                    textPaint.color = Color.parseColor("#FF4444")
                }
            }
            drawArrow(canvas, cx, arrowBaseY, arrowTipX, arrowBaseY)
            drawLabel(canvas, label, cx, arrowBaseY - 120f)
        } else {
            drawLabel(canvas, "—", cx, arrowBaseY - 120f)
        }

        result.centroid?.let { centroid ->
            val fw = if (result.frameWidth > 0) result.frameWidth.toFloat() else width.toFloat()
            val fh = if (result.frameHeight > 0) result.frameHeight.toFloat() else height.toFloat()
            val scaleX = width.toFloat() / fw
            val scaleY = height.toFloat() / fh
            val bx = centroid.x * scaleX
            val by = centroid.y * scaleY
            drawCrosshair(canvas, bx, by, 20f)

            result.boundingRect?.let { rect ->
                val rx = rect.left * scaleX
                val ry = rect.top * scaleY
                val rw = (rect.right - rect.left) * scaleX
                val rh = (rect.bottom - rect.top) * scaleY
                canvas.drawRect(rx, ry, rx + rw, ry + rh, boundingBoxPaint)
            }
        }
    }

    private fun drawArrow(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
        val dx = x2 - x1
        val dy = y2 - y1
        val len = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (len < 1f) return

        val headLen = 50f
        val headAngle = Math.toRadians(25.0)

        val ux = dx / len
        val uy = dy / len

        canvas.drawLine(x1, y1, x2, y2, arrowStrokePaint)

        val path = Path()
        val tipX = x2
        val tipY = y2
        val leftX = tipX - headLen * (ux * Math.cos(headAngle).toFloat() - uy * Math.sin(headAngle).toFloat())
        val leftY = tipY - headLen * (uy * Math.cos(headAngle).toFloat() + ux * Math.sin(headAngle).toFloat())
        val rightX = tipX - headLen * (ux * Math.cos(headAngle).toFloat() + uy * Math.sin(headAngle).toFloat())
        val rightY = tipY - headLen * (uy * Math.cos(headAngle).toFloat() - ux * Math.sin(headAngle).toFloat())

        path.moveTo(tipX, tipY)
        path.lineTo(leftX, leftY)
        path.lineTo(rightX, rightY)
        path.close()
        canvas.drawPath(path, arrowPaint)
        canvas.drawPath(path, arrowStrokePaint)
    }

    private fun drawLabel(canvas: Canvas, text: String, x: Float, y: Float) {
        val textWidth = textPaint.measureText(text)
        val padding = 24f
        canvas.drawRoundRect(
            x - textWidth / 2f - padding,
            y - textPaint.textSize - padding / 2f,
            x + textWidth / 2f + padding,
            y + padding / 2f,
            16f, 16f, labelBgPaint
        )
        canvas.drawText(text, x, y, textPaint)
    }

    private fun drawCrosshair(canvas: Canvas, x: Float, y: Float, size: Float) {
        canvas.drawLine(x - size, y, x + size, y, crosshairPaint)
        canvas.drawLine(x, y - size, x, y + size, crosshairPaint)
        canvas.drawCircle(x, y, size, crosshairPaint)
    }
}
