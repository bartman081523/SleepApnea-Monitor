package com.example.apnea

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class ApneaChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val snorePaint = Paint().apply {
        color = Color.BLUE
        strokeWidth = 5f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val apneaPaint = Paint().apply {
        color = Color.RED
        strokeWidth = 5f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val axisPaint = Paint().apply {
        color = Color.BLACK
        strokeWidth = 2f
        textSize = 30f
    }

    private var snoreData: List<Float> = emptyList()
    private var apneaData: List<Float> = emptyList()

    fun setData(snore: List<Float>, apnea: List<Float>) {
        this.snoreData = snore
        this.apneaData = apnea
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val w = width.toFloat()
        val h = height.toFloat()
        val padding = 50f
        
        // Draw Axis
        canvas.drawLine(padding, h - padding, w - padding, h - padding, axisPaint) // X
        canvas.drawLine(padding, padding, padding, h - padding, axisPaint) // Y
        
        if (snoreData.isEmpty()) return
        
        val stepX = (w - 2 * padding) / snoreData.size.coerceAtLeast(1)
        val scaleY = h - 2 * padding
        
        drawPath(canvas, snoreData, snorePaint, padding, h - padding, stepX, scaleY)
        drawPath(canvas, apneaData, apneaPaint, padding, h - padding, stepX, scaleY)
        
        canvas.drawText("Blau: Schnarch-Score", padding + 20, padding + 40, axisPaint)
        canvas.drawText("Rot: Apnoe-Score", padding + 20, padding + 80, axisPaint)
    }
    
    private fun drawPath(canvas: Canvas, data: List<Float>, paint: Paint, startX: Float, baseY: Float, stepX: Float, scaleY: Float) {
        if (data.size < 2) return
        for (i in 0 until data.size - 1) {
            val x1 = startX + i * stepX
            val y1 = baseY - data[i] * scaleY
            val x2 = startX + (i + 1) * stepX
            val y2 = baseY - data[i + 1] * scaleY
            canvas.drawLine(x1, y1, x2, y2, paint)
        }
    }
}
