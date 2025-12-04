package com.example.wiprober

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View

class PointsOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // --- Свойства для точек сканирования ---
    private val pointsToDraw = mutableListOf<Pair<Float, Float>>()
    private val pointPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val strokePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
        isAntiAlias = true
    }

    // --- Свойства для линии калибровки ---
    private val calibrationLinePoints = mutableListOf<PointF>()
    private val linePaint = Paint().apply {
        color = Color.CYAN
        strokeWidth = 8f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    // --- Общие свойства ---
    private var imageMatrix: Matrix? = null
    private val radius = 15f // Радиус точки сканирования

    private val noteMarkers = mutableListOf<PointF>()
    private val notePaint = Paint().apply {
        color = Color.BLUE // Другой цвет для заметок
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    fun setNoteMarkers(points: List<PointF>) {
        noteMarkers.clear()
        noteMarkers.addAll(points)
        invalidate()
    }

    /**
     * Обновляет матрицу преобразований, полученную от PhotoView.
     * Это нужно, чтобы точки и линии двигались вместе с картой.
     */
    fun updateMatrix(matrix: Matrix) {
        this.imageMatrix = matrix
        invalidate() // Говорим View, что нужно перерисоваться с учетом новой матрицы
    }


    // ==================================================================
    //  МЕТОДЫ ДЛЯ ТОЧЕК СКАНИРОВАНИЯ
    // ==================================================================

    /**
     * Добавляет новую точку сканирования для отрисовки.
     * @param x Координата X на оригинальной карте.
     * @param y Координата Y на оригинальной карте.
     */
    fun setScanPoints(points: List<Pair<Float, Float>>) {
        pointsToDraw.clear()
        pointsToDraw.addAll(points)
        invalidate()
    }

    // ==================================================================
    //  МЕТОДЫ ДЛЯ ЛИНИИ КАЛИБРОВКИ
    // ==================================================================

    /**
     * Устанавливает точки для отрисовки линии калибровки.
     */
    fun setCalibrationLine(points: List<PointF>) {
        calibrationLinePoints.clear()
        calibrationLinePoints.addAll(points)
        invalidate()
    }

    /**
     * Очищает линию калибровки с экрана.
     */
    fun clearCalibrationLine() {
        calibrationLinePoints.clear()
        invalidate()
    }

    fun clearAll() {
        pointsToDraw.clear()
        noteMarkers.clear()
        calibrationLinePoints.clear()
        invalidate()
    }

    //  ГЛАВНЫЙ МЕТОД ОТРИСОВКИ

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val currentMatrix = imageMatrix ?: return

        // Рисуем точки сканирования
        for (point in pointsToDraw) {
            val originalCoords = floatArrayOf(point.first, point.second)
            val transformedCoords = floatArrayOf(0f, 0f)

            // Преобразуем координаты с карты в координаты на экране
            currentMatrix.mapPoints(transformedCoords, originalCoords)

            val x = transformedCoords[0]
            val y = transformedCoords[1]

            // Рисуем белую обводку и красную точку
            canvas.drawCircle(x, y, radius, strokePaint)
            canvas.drawCircle(x, y, radius, pointPaint)
        }

        // Рисуем маркеры заметок
        for (marker in noteMarkers) {
            val transformed = floatArrayOf(0f, 0f)
            currentMatrix.mapPoints(transformed, floatArrayOf(marker.x, marker.y))

            // Рисуем синий кружок с белой обводкой
            canvas.drawCircle(transformed[0], transformed[1], radius, strokePaint)
            canvas.drawCircle(transformed[0], transformed[1], radius, notePaint)
        }

        // Рисуем линию калибровки
        if (calibrationLinePoints.isNotEmpty()) {
            calibrationLinePoints.forEach { point ->
                val transformed = floatArrayOf(0f, 0f)
                currentMatrix.mapPoints(transformed, floatArrayOf(point.x, point.y))
                canvas.drawCircle(transformed[0], transformed[1], radius + 5f, linePaint)
            }

            if (calibrationLinePoints.size == 2) {
                val p1 = calibrationLinePoints[0]
                val p2 = calibrationLinePoints[1]
                val t1 = floatArrayOf(0f, 0f)
                val t2 = floatArrayOf(0f, 0f)
                currentMatrix.mapPoints(t1, floatArrayOf(p1.x, p1.y))
                currentMatrix.mapPoints(t2, floatArrayOf(p2.x, p2.y))
                canvas.drawLine(t1[0], t1[1], t2[0], t2[1], linePaint)
            }
        }
    }
}
