package com.example.apnea

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class ApneaChartView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private var snoreData: List<Float> = emptyList()
    private var apneaData: List<Float> = emptyList()
    private var alarmIndices: List<Int> = emptyList()

    private val snorePaint = Paint().apply {
        color = Color.BLUE
        strokeWidth = 4f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val apneaPaint = Paint().apply {
        color = Color.RED
        strokeWidth = 5f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val alarmPaint = Paint().apply {
        color = Color.parseColor("#FFD700") // Gold
        style = Paint.Style.FILL
        alpha = 100
    }

    private val gridPaint = Paint().apply {
        color = Color.GRAY
        strokeWidth = 1f
        alpha = 50
    }

    fun setData(snore: List<Float>, apnea: List<Float>, alarms: List<Int> = emptyList()) {
        this.snoreData = snore
        this.apneaData = apnea
        this.alarmIndices = alarms
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (apneaData.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()

        // Draw Alarms (Background highlights)
        if (alarmIndices.isNotEmpty()) {
            for (idx in alarmIndices) {
                if (idx < apneaData.size) {
                    val x = (idx.toFloat() / apneaData.size) * w
                    canvas.drawRect(x - 5f, 0f, x + 5f, h, alarmPaint)
                }
            }
        }

        // Draw smoothed Apnea curve
        drawSmoothedPath(canvas, apneaData, h, apneaPaint)
        
        // Draw smoothed Snore curve
        drawSmoothedPath(canvas, snoreData, h, snorePaint)
        
        // Legends
        val textPaint = Paint().apply { color = Color.GRAY; textSize = 30f }
        canvas.drawText("Blau: Schnarch-Score", 20f, 40f, textPaint)
        canvas.drawText("Rot: Apnoe-Score", 20f, 80f, textPaint)
        if (alarmIndices.isNotEmpty()) canvas.drawText("Gelb: Alarm-Trigger", 20f, 120f, textPaint)
    }

    private fun drawSmoothedPath(canvas: Canvas, data: List<Float>, h: Float, paint: Paint) {
        if (data.size < 2) return
        val path = Path()
        val w = width.toFloat()
        val stepX = w / (data.size - 1)

        path.moveTo(0f, h - (data[0] * h))
        
        for (i in 1 until data.size) {
            val x1 = (i - 1) * stepX
            val y1 = h - (data[i - 1] * h)
            val x2 = i * stepX
            val y2 = h - (data[i] * h)
            
            // Cubic smoothing
            val cx = (x1 + x2) / 2
            path.cubicTo(cx, y1, cx, y2, x2, y2)
        }
        canvas.drawPath(path, paint)
    }
}
