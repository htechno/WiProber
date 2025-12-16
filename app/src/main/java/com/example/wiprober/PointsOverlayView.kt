package com.example.wiprober

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View

class PointsOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // --- Свойства для точек Stop-and-Go ---
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

    // --- Свойства для Continuous (Треки) ---
    private val activeTrackPoints = mutableListOf<PointF>() // То, что рисуем прямо сейчас
    private val completedTracks = mutableListOf<List<PointF>>() // Старые треки

    private val trackPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val activeTrackPaint = Paint().apply {
        color = Color.MAGENTA // Активный трек рисуем другим цветом
        style = Paint.Style.STROKE
        strokeWidth = 8f
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
    }

    private val waypointPaint = Paint().apply {
        color = Color.MAGENTA
        style = Paint.Style.FILL
        isAntiAlias = true
    }


    // --- Свойства для линии калибровки ---
    private val calibrationLinePoints = mutableListOf<PointF>()
    private val calibrationPaint = Paint().apply {
        color = Color.CYAN
        strokeWidth = 8f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    // --- Общие свойства ---
    private var imageMatrix: Matrix? = null
    private val radius = 15f
    private val noteMarkers = mutableListOf<PointF>()
    private val notePaint = Paint().apply {
        color = Color.BLUE
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    fun updateMatrix(matrix: Matrix) {
        this.imageMatrix = matrix
        invalidate()
    }

    // ================== DATA SETTERS ==================

    fun setScanPoints(points: List<Pair<Float, Float>>) {
        pointsToDraw.clear()
        pointsToDraw.addAll(points)
        invalidate()
    }

    fun setNoteMarkers(points: List<PointF>) {
        noteMarkers.clear()
        noteMarkers.addAll(points)
        invalidate()
    }

    // НОВОЕ: Установка активного трека
    fun setActiveTrack(points: List<PointF>) {
        activeTrackPoints.clear()
        activeTrackPoints.addAll(points)
        invalidate()
    }

    // НОВОЕ: Установка завершенных треков
    fun setCompletedTracks(sessions: List<ContinuousScanSession>) {
        completedTracks.clear()
        sessions.forEach { session ->
            val track = session.waypoints.map { wp -> PointF(wp.x, wp.y) }
            completedTracks.add(track)
        }
        invalidate()
    }

    fun setCalibrationLine(points: List<PointF>) {
        calibrationLinePoints.clear()
        calibrationLinePoints.addAll(points)
        invalidate()
    }

    fun clearCalibrationLine() {
        calibrationLinePoints.clear()
        invalidate()
    }

    fun clearAll() {
        pointsToDraw.clear()
        noteMarkers.clear()
        calibrationLinePoints.clear()
        activeTrackPoints.clear()
        completedTracks.clear()
        invalidate()
    }

    // ================== DRAWING ==================

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val currentMatrix = imageMatrix ?: return

        // 1. Рисуем ЗАВЕРШЕННЫЕ треки (Зеленые)
        for (track in completedTracks) {
            drawPath(canvas, track, trackPaint, currentMatrix)
            // Рисуем точки на поворотах
            drawPointsFromList(canvas, track, trackPaint, currentMatrix, 8f)
        }

        // 2. Рисуем АКТИВНЫЙ трек (Маджента)
        if (activeTrackPoints.isNotEmpty()) {
            drawPath(canvas, activeTrackPoints, activeTrackPaint, currentMatrix)
            drawPointsFromList(canvas, activeTrackPoints, waypointPaint, currentMatrix, 12f)
        }

        // 3. Рисуем точки Stop-and-Go (Красные)
        for (point in pointsToDraw) {
            val t = transformPoint(point.first, point.second, currentMatrix)
            canvas.drawCircle(t[0], t[1], radius, strokePaint)
            canvas.drawCircle(t[0], t[1], radius, pointPaint)
        }

        // 4. Рисуем маркеры заметок (Синие)
        for (marker in noteMarkers) {
            val t = transformPoint(marker.x, marker.y, currentMatrix)
            canvas.drawCircle(t[0], t[1], radius, strokePaint)
            canvas.drawCircle(t[0], t[1], radius, notePaint)
        }

        // 5. Линия калибровки
        if (calibrationLinePoints.isNotEmpty()) {
            val transformedPoints = calibrationLinePoints.map {
                val t = transformPoint(it.x, it.y, currentMatrix)
                PointF(t[0], t[1])
            }

            transformedPoints.forEach { p ->
                canvas.drawCircle(p.x, p.y, radius + 5f, calibrationPaint)
            }

            if (transformedPoints.size == 2) {
                canvas.drawLine(transformedPoints[0].x, transformedPoints[0].y, transformedPoints[1].x, transformedPoints[1].y, calibrationPaint)
            }
        }
    }

    // Helper: Рисует линию по списку точек
    private fun drawPath(canvas: Canvas, points: List<PointF>, paint: Paint, matrix: Matrix) {
        if (points.size < 2) return

        val path = Path()
        val start = transformPoint(points[0].x, points[0].y, matrix)
        path.moveTo(start[0], start[1])

        for (i in 1 until points.size) {
            val p = transformPoint(points[i].x, points[i].y, matrix)
            path.lineTo(p[0], p[1])
        }
        canvas.drawPath(path, paint)
    }

    // Helper: Рисует просто точки (узлы)
    private fun drawPointsFromList(canvas: Canvas, points: List<PointF>, paint: Paint, matrix: Matrix, r: Float) {
        points.forEach { p ->
            val t = transformPoint(p.x, p.y, matrix)
            canvas.drawCircle(t[0], t[1], r, paint)
        }
    }

    private fun transformPoint(x: Float, y: Float, matrix: Matrix): FloatArray {
        val arr = floatArrayOf(x, y)
        matrix.mapPoints(arr)
        return arr
    }
}
