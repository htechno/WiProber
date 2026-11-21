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
    private var changes: List<ApChange> = emptyList()
    private var now = 0L
    private var sessionStart = 0L
    private val density = resources.displayMetrics.density
    private val ticks = intArrayOf(-30, -50, -70, -90)
    private val ink get() = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    private val accent get() = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)

    internal fun submit(history: List<WifiSignalSample>, elapsedMillis: Long, startedAtMillis: Long?, changes: List<ApChange>) {
        this.changes = changes
        samples = history
        now = elapsedMillis
        sessionStart = startedAtMillis ?: elapsedMillis
        val current = history.lastOrNull()?.rssi?.let { context.getString(R.string.monitor_dbm, it) }
            ?: context.getString(R.string.monitor_not_available)
        val visible = changes.count { it.at in (now - 120_000)..now }
        contentDescription = context.getString(R.string.monitor_signal_accessibility, current) +
            if (visible > 0) context.getString(R.string.monitor_signal_markers, visible) else ""
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = 28 * density
        val right = width - 8 * density
        val top = 9 * density
        val bottom = height - 20 * density
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
        val axis = WifiTimeAxis.at(now - sessionStart)
        for (tick in axis.ticks) {
            val label = context.getString(R.string.monitor_chart_seconds, tick / 1000)
            val x = left + axis.fraction(tick) * (right - left)
            canvas.drawText(label, (x - paint.measureText(label) / 2).coerceIn(0f, width - paint.measureText(label)),
                height - 3 * density, paint)
        }
        canvas.save()
        canvas.clipRect(left, top - 4 * density, right, bottom)
        path.reset()
        var previous: WifiSignalSample? = null
        for (sample in samples) {
            val rssi = sample.rssi
            if (rssi == null) { previous = null; continue }
            val x = left + axis.fraction(sample.elapsedMillis - sessionStart) * (right - left)
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
        paint.style = Paint.Style.FILL
        paint.color = MaterialColors.getColor(this, com.google.android.material.R.attr.colorTertiary)
        paint.strokeWidth = density
        var lastLabelEnd = Float.NEGATIVE_INFINITY
        for (change in changes) {
            val elapsed = change.at - sessionStart
            if (elapsed !in axis.startMillis..axis.endMillis) continue
            val x = left + axis.fraction(elapsed) * (right - left)
            canvas.drawLine(x, top, x, bottom, paint)
            val label = context.getString(R.string.monitor_change_number, change.number)
            val labelX = (x + 2 * density).coerceAtMost(right - paint.measureText(label))
            if (labelX > lastLabelEnd) {
                canvas.drawText(label, labelX, top + paint.textSize, paint)
                lastLabelEnd = labelX + paint.measureText(label) + 2 * density
            }
        }
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
    private val plots = WifiChannelAxes.plots
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
        plots.forEachIndexed { index, (band, startMhz, endMhz, ticksMhz) ->
            val rowTop = index * height / plots.size.toFloat()
            val top = rowTop + 17 * density
            val bottom = (index + 1) * height / plots.size.toFloat() - 23 * density
            val left = 26 * density
            val right = width - 8 * density
            fun x(mhz: Float) = left + (mhz - startMhz) / (endMhz - startMhz) * (right - left)
            fun y(rssi: Int) = bottom - (rssi.coerceIn(-100, -30) + 100) / 70f * (bottom - top)
            paint.style = Paint.Style.FILL
            paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics)
            paint.color = ink
            canvas.drawText(formatter.band(band), left, rowTop + 11 * density, paint)
            paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 9f, resources.displayMetrics)
            for (rssi in rssiTicks) {
                paint.color = ink; paint.alpha = 140
                canvas.drawText(rssi.toString(), 0f, y(rssi) + 3 * density, paint)
                paint.alpha = 25; paint.strokeWidth = density
                canvas.drawLine(left, y(rssi), right, y(rssi), paint)
            }
            val labels = ticksMhz.map { WifiRadioModel.channel(it).toString() }
            val spacing = ticksMhz.zipWithNext { a, b -> x(b.toFloat()) - x(a.toFloat()) }.minOrNull() ?: Float.MAX_VALUE
            val stagger = labels.any { paint.measureText(it) + 2 * density > spacing }
            for ((tickIndex, frequency) in ticksMhz.withIndex()) {
                paint.color = ink; paint.alpha = 160
                val label = labels[tickIndex]
                canvas.drawText(label, x(frequency.toFloat()) - paint.measureText(label) / 2,
                    bottom + (if (stagger && tickIndex % 2 == 1) 21 else 11) * density, paint)
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
