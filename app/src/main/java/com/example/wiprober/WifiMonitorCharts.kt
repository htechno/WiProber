package com.example.wiprober

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.google.android.material.color.MaterialColors

class WifiSignalChartView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var samples: List<WifiSignalSample> = emptyList()
    private var now = 0L
    private val density = resources.displayMetrics.density
    private val ticks = intArrayOf(-30, -50, -70, -90)
    private val ink get() = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    private val accent get() = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)

    internal fun submit(history: List<WifiSignalSample>, elapsedMillis: Long) {
        samples = history
        now = elapsedMillis
        val current = history.lastOrNull()?.rssi?.let { context.getString(R.string.monitor_dbm, it) }
            ?: context.getString(R.string.monitor_not_available)
        contentDescription = context.getString(R.string.monitor_signal_accessibility, current)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = 38 * density
        val right = width - 8 * density
        val top = 12 * density
        val bottom = height - 25 * density
        fun y(rssi: Int) = bottom - (rssi.coerceIn(-100, -30) + 100) / 70f * (bottom - top)
        paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
        paint.strokeWidth = density
        paint.style = Paint.Style.FILL
        for (rssi in ticks) {
            paint.color = ink; paint.alpha = 140
            canvas.drawText(rssi.toString(), 0f, y(rssi) + 4 * density, paint)
            paint.alpha = 25
            canvas.drawLine(left, y(rssi), right, y(rssi), paint)
        }
        paint.color = ink; paint.alpha = 140
        canvas.drawText(context.getString(R.string.monitor_chart_start), left, height - 3 * density, paint)
        val end = context.getString(R.string.monitor_chart_now)
        canvas.drawText(end, right - paint.measureText(end), height - 3 * density, paint)
        canvas.save()
        canvas.clipRect(left, top - 4 * density, right, bottom)
        path.reset()
        var previous: WifiSignalSample? = null
        for (sample in samples) {
            val rssi = sample.rssi
            if (rssi == null) { previous = null; continue }
            val x = right - (now - sample.elapsedMillis) / 120_000f * (right - left)
            if (previous != null && previous.bssid == sample.bssid && sample.elapsedMillis - previous.elapsedMillis <= 2500L) {
                path.lineTo(x, y(rssi))
            } else path.moveTo(x, y(rssi))
            if (sample === samples.lastOrNull()) {
                paint.color = accent
                canvas.drawCircle(x, y(rssi), 2 * density, paint)
            }
            previous = sample
        }
        paint.color = accent; paint.style = Paint.Style.STROKE; paint.strokeWidth = 2 * density
        canvas.drawPath(path, paint)
        canvas.restore()
        paint.style = Paint.Style.FILL
        if (samples.none { it.rssi != null && now - it.elapsedMillis <= 120_000 }) {
            paint.color = ink; paint.alpha = 150
            val message = context.getString(R.string.monitor_signal_empty)
            canvas.drawText(message, left + 8 * density, (top + bottom) / 2, paint)
        }
    }
}

class WifiChannelChartView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private var networks: List<WifiNetworkSnapshot> = emptyList()
    private var connectedBssid: String? = null
    private val formatter = WifiMonitorText(context)
    private val rssiTicks = intArrayOf(-40, -70, -100)
    private val plots = listOf(
        Triple(WifiBand.GHZ_2_4, 2395f..2500f, listOf(2412, 2437, 2462, 2484)),
        Triple(WifiBand.GHZ_5, 4890f..5925f, listOf(5180, 5320, 5500, 5660, 5825)),
        Triple(WifiBand.GHZ_6, 5925f..7125f, listOf(5955, 6115, 6275, 6435, 6595, 6755, 6915, 7115))
    )
    private data class Rectangle(val band: WifiBand, val bssid: String, val rssi: Int, val span: WifiChannelSpan, val color: Int)
    private var rectangles: List<Rectangle> = emptyList()
    internal fun submit(values: List<WifiNetworkSnapshot>, connected: String?) {
        if (values === networks && connected == connectedBssid) return
        if (values !== networks) {
            rectangles = values.sortedBy { it.rssi }.flatMap { network ->
                val color = Color.HSVToColor(floatArrayOf((network.bssid.hashCode() and 0x7fffffff) % 360f, .65f, .8f))
                WifiRadioModel.spans(network).map { Rectangle(network.band, network.bssid, network.rssi, it, color) }
            }
        }
        networks = values; connectedBssid = connected; invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val ink = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
        plots.forEachIndexed { index, (band, range, ticks) ->
            val rowTop = index * height / 3f
            val top = rowTop + 26 * density
            val bottom = (index + 1) * height / 3f - 22 * density
            val left = 32 * density
            val right = width - 14 * density
            fun x(mhz: Float) = left + (mhz - range.start) / (range.endInclusive - range.start) * (right - left)
            fun y(rssi: Int) = bottom - (rssi.coerceIn(-100, -30) + 100) / 70f * (bottom - top)
            paint.style = Paint.Style.FILL
            paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
            paint.color = ink
            canvas.drawText(formatter.band(band), left, rowTop + 15 * density, paint)
            paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics)
            for (rssi in rssiTicks) {
                paint.color = ink; paint.alpha = 140
                canvas.drawText(rssi.toString(), 0f, y(rssi) + 3 * density, paint)
                paint.alpha = 25; paint.strokeWidth = density
                canvas.drawLine(left, y(rssi), right, y(rssi), paint)
            }
            for (frequency in ticks) {
                paint.color = ink; paint.alpha = 160
                val label = WifiRadioModel.channel(frequency).toString()
                canvas.drawText(label, x(frequency.toFloat()) - paint.measureText(label) / 2,
                    bottom + 15 * density, paint)
            }
            canvas.save(); canvas.clipRect(left, top, right, bottom)
            for (rectangle in rectangles) {
                if (rectangle.band != band) continue
                val span = rectangle.span
                paint.style = Paint.Style.FILL; paint.color = rectangle.color; paint.alpha = 35
                canvas.drawRect(x(span.startMhz), y(rectangle.rssi), x(span.endMhz), bottom, paint)
                paint.style = Paint.Style.STROKE; paint.alpha = 200
                paint.strokeWidth = if (rectangle.bssid == connectedBssid) 2.5f * density else density
                canvas.drawRect(x(span.startMhz), y(rectangle.rssi), x(span.endMhz), bottom, paint)
            }
            canvas.restore(); paint.style = Paint.Style.FILL
        }
    }
}
