package com.example.wiprober

/** Axis values are elapsed milliseconds from the first active sample, never device uptime. */
internal data class WifiTimeAxis(val startMillis: Long, val endMillis: Long, val ticks: List<Long>) {
    fun fraction(elapsedMillis: Long) = (elapsedMillis - startMillis).toFloat() / (endMillis - startMillis)

    companion object {
        fun at(elapsedMillis: Long): WifiTimeAxis {
            val end = maxOf(30_000L, ((elapsedMillis.coerceAtLeast(0) + 29_999L) / 30_000L) * 30_000L)
            val start = (end - 120_000L).coerceAtLeast(0)
            val step = if (end <= 30_000L) 10_000L else 30_000L
            return WifiTimeAxis(start, end, (start..end step step).toList())
        }
    }
}

internal data class WifiChannelPlot(val band: WifiBand, val startMhz: Float, val endMhz: Float, val ticksMhz: List<Int>) {
    fun intersects(span: WifiChannelSpan) = span.endMhz > startMhz && span.startMhz < endMhz
}

internal object WifiChannelAxes {
    // Split 5 GHz around the unused 68–96 interval; frequency remains linear within each plot.
    val plots = listOf(
        WifiChannelPlot(WifiBand.GHZ_2_4, 2402f, 2494f, (1..13).map { 2407 + it * 5 } + 2484),
        WifiChannelPlot(WifiBand.GHZ_5, 5170f, 5330f, (36..64 step 4).map { 5000 + it * 5 }),
        WifiChannelPlot(WifiBand.GHZ_5, 5490f, 5895f,
            ((100..144 step 4) + (149..177 step 4)).map { 5000 + it * 5 }),
        WifiChannelPlot(WifiBand.GHZ_6, 5925f, 7125f, (1..225 step 32).map { 5950 + it * 5 } + 7115)
    )

    fun displays(network: WifiNetworkSnapshot) = plots.any { plot ->
        plot.band == network.band && WifiRadioModel.spans(network).any(plot::intersects)
    }
}
