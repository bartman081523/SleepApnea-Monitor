package com.example.apnea

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class ApneaChartView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private var snoreData: List<Float> = emptyList()
    private var apneaData: List<Float> = emptyList()
    private var pulseData: List<Float> = emptyList()
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

    private val pulsePaint = Paint().apply {
        color = Color.parseColor("#4CAF50") // Green
        strokeWidth = 4f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val alarmPaint = Paint().apply {
        color = Color.parseColor("#FFD700") // Gold
        style = Paint.Style.FILL
        alpha = 100
    }

    fun setData(snore: List<Float>, apnea: List<Float>, pulse: List<Float> = emptyList(), alarms: List<Int> = emptyList()) {
        this.snoreData = snore
        this.apneaData = apnea
        this.pulseData = pulse
        this.alarmIndices = alarms
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // Background color based on theme is already handled by android:background in layout
        // if we want to force it, we can use: canvas.drawColor(Color.TRANSPARENT)

        if (apneaData.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()

        // Draw Alarms (Background highlights)
        if (alarmIndices.isNotEmpty()) {
            for (idx in alarmIndices) {
                val x = (idx.toFloat() / Math.max(1, apneaData.size)) * w
                canvas.drawRect(x - 10f, 0f, x + 10f, h, alarmPaint)
            }
        }

        // Draw smoothed curves
        drawSmoothedPath(canvas, apneaData, h, apneaPaint)
        drawSmoothedPath(canvas, snoreData, h, snorePaint)
        
        if (pulseData.isNotEmpty()) {
            // Normalize pulse (expecting 40-120 range roughly, but it's pre-normalized 0-1)
            drawSmoothedPath(canvas, pulseData, h, pulsePaint)
        }
        
        // Legends
        val textPaint = Paint().apply { 
            color = if (isDarkTheme()) Color.WHITE else Color.BLACK
            textSize = 34f 
            isFakeBoldText = true
        }
        canvas.drawText("Blau: Schnarchen", 20f, 50f, textPaint)
        canvas.drawText("Rot: Apnoe", 20f, 100f, textPaint)
        if (pulseData.isNotEmpty()) canvas.drawText("Grün: Puls", 20f, 150f, textPaint)
        if (alarmIndices.isNotEmpty()) canvas.drawText("Gold: Alarme", 20f, 200f, textPaint)
    }

    private fun isDarkTheme(): Boolean {
        return (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
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
