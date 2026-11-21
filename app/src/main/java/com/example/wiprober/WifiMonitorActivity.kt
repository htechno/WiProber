package com.example.wiprober

import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.wiprober.databinding.ActivityWifiMonitorBinding
import com.google.android.material.tabs.TabLayout
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WifiMonitorActivity : AppCompatActivity() {
    private lateinit var binding: ActivityWifiMonitorBinding
    private val model: WifiMonitorViewModel by viewModels()
    private val source by lazy { WifiMonitorSource(applicationContext) }
    private lateinit var coordinator: WifiMonitorAndroidCoordinator
    private lateinit var scanner: WifiScanner
    private lateinit var scanThread: HandlerThread
    private lateinit var adapter: WifiNetworkAdapter
    private lateinit var logAdapter: WifiLogAdapter
    private var changesForHandovers: List<ApChange>? = null
    private var handoverRows: List<ApChange> = emptyList()
    private var displayedLog: List<MonitorLogRow>? = null
    private var telemetryJob: Job? = null
    private var scanJob: Job? = null
    private var detailJob: Job? = null
    private var displayedSelection: WifiNetworkSelection? = null
    private val detailsBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = model.closeDetails()
    }
    private var dialog: AlertDialog? = null
    private var requestInitialAccess = false
    private var displayedNetworks: List<WifiNetworkSnapshot>? = null
    private var displayedFilter: WifiMonitorFilter? = null
    private var displayedSplit = false
    private var resetTableScroll = false
    private var filteredNetworks: List<WifiNetworkSnapshot> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWifiMonitorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        coordinator = WifiMonitorAndroidCoordinator(this, source)
        requestInitialAccess = savedInstanceState == null
        scanThread = HandlerThread("wifi-monitor-requests").apply { start() }
        scanner = WifiScanner(applicationContext, mainHandler = Handler(scanThread.looper))
        adapter = WifiNetworkAdapter(::showDetails)
        binding.networks.layoutManager = LinearLayoutManager(this)
        logAdapter = WifiLogAdapter()
        binding.logRows.layoutManager = LinearLayoutManager(this)
        binding.logRows.adapter = logAdapter
        binding.logRows.itemAnimator = null
        binding.panes.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = model.selectPane(MonitorPane.entries[tab.position])
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        binding.networks.adapter = adapter
        binding.networks.itemAnimator = null
        binding.networks.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        val minTableWidth = resources.getDimensionPixelSize(R.dimen.monitor_table_min_width)
        binding.tableScroll.addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
            val tableWidth = maxOf(minTableWidth, right - left)
            if (binding.networks.layoutParams.width != tableWidth) binding.networks.updateLayoutParams { width = tableWidth }
        }
        binding.tableHeader.root.setBackgroundColor(MaterialColors.getColor(binding.root,
            com.google.android.material.R.attr.colorSurfaceVariant))
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.detailsToolbar.setNavigationOnClickListener { model.closeDetails() }
        onBackPressedDispatcher.addCallback(this, detailsBack)
        binding.pauseButton.setOnClickListener {
            model.toggleRunning(SystemClock.elapsedRealtime())
            if (!model.state.value.running) {
                scanJob?.cancel()
                telemetryJob?.cancel()
            }
        }
        binding.channelsButton.setOnClickListener { model.toggleChannels() }
        binding.splitBands.setOnClickListener { model.setSplitBands(binding.splitBands.isChecked) }
        binding.setupButton.setOnClickListener { coordinator.ensureAccess() }
        binding.filterButton.setOnClickListener {
            dialog?.dismiss()
            dialog = WifiMonitorDialogs.filter(this, model.state.value.filter, model::setFilter)
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                scanner.start()
                try {
                    launch { model.state.collect(::render) }
                    while (isActive) {
                        val sampledAt = SystemClock.elapsedRealtime()
                        val readings = withContext(Dispatchers.IO) {
                            val access = source.access()
                            val radio = if (access == WifiScanAccessRequirement.READY) source.radio() else MonitorRadioReading(null, null)
                            Triple(radio, access, source.throttled())
                        }
                        model.tick(sampledAt, readings.first.connection, readings.second, readings.third, readings.first.phase)
                        val state = model.state.value
                        if (!state.canScan) {
                            scanJob?.cancel()
                            telemetryJob?.cancel()
                        } else if (telemetryJob?.isCompleted != false) {
                            telemetryJob = launch { WifiMonitorTelemetry(applicationContext, source).observe(model) }
                        }
                        if (state.canScan && scanJob?.isActive != true && state.nowMillis >= state.nextScanMillis) {
                            scanJob = launch { performScan() }
                        }
                        delay(1_000)
                    }
                } finally {
                    scanJob?.cancel()
                    telemetryJob?.cancel()
                    model.endObservation(SystemClock.elapsedRealtime())
                    scanner.stop()
                    model.scanCancelled()
                }
            }
        }
    }

    override fun onPostResume() {
        super.onPostResume()
        if (requestInitialAccess) {
            requestInitialAccess = false
            coordinator.ensureAccess()
        }
    }

    private suspend fun performScan() {
        // Recheck immediately before every request, including after a settings round trip.
        val ready = withContext(Dispatchers.IO) { !source.throttled() && source.access() == WifiScanAccessRequirement.READY }
        if (!ready || !model.state.value.running) return
        val started = SystemClock.elapsedRealtime()
        model.scanStarted(started)
        try {
            when (val outcome = withContext(Dispatchers.IO) { scanner.scan() }) {
                is WifiScanOutcome.Success -> {
                    val networks = withContext(Dispatchers.Default) {
                        outcome.results.mapNotNull(source::snapshot)
                            .groupBy { it.bssid }.values.map { results -> results.maxBy { it.seenAtMicros } }
                    }
                    model.scanFinished(networks, WifiMonitorStatus.UPDATED, started, SystemClock.elapsedRealtime())
                }
                is WifiScanOutcome.Failure -> model.scanFinished(null, when (outcome.reason) {
                    WifiScanFailureReason.START_REJECTED -> WifiMonitorStatus.REJECTED
                    WifiScanFailureReason.TIMEOUT, WifiScanFailureReason.RESULTS_NOT_UPDATED -> WifiMonitorStatus.TIMEOUT
                    else -> WifiMonitorStatus.UNAVAILABLE
                }, started, SystemClock.elapsedRealtime())
                is WifiScanOutcome.Cancelled -> model.scanCancelled()
            }
        } catch (cancelled: CancellationException) {
            model.scanCancelled()
            throw cancelled
        } catch (_: RuntimeException) {
            model.scanFinished(null, WifiMonitorStatus.UNAVAILABLE, started, SystemClock.elapsedRealtime())
        }
    }

    private fun render(state: WifiMonitorState) {
        renderDetails(state.selectedNetwork)
        renderPane(state)
        val text = WifiMonitorText(this)
        val wait = ((state.nextScanMillis - state.nowMillis + 999) / 1000).coerceAtLeast(0)
        binding.status.text = when {
            state.throttled -> getString(R.string.monitor_throttled)
            state.access == WifiScanAccessRequirement.ENABLE_WIFI -> getString(R.string.monitor_wifi_disabled)
            state.access == WifiScanAccessRequirement.ENABLE_LOCATION -> getString(R.string.monitor_location_disabled)
            state.access == WifiScanAccessRequirement.GRANT_PERMISSIONS -> getString(R.string.monitor_permission_required)
            !state.running -> getString(R.string.monitor_paused)
            else -> when (state.status) {
                WifiMonitorStatus.WAITING -> getString(R.string.monitor_waiting)
                WifiMonitorStatus.SCANNING -> getString(R.string.monitor_scanning)
                WifiMonitorStatus.UPDATED -> getString(R.string.monitor_updated, wait)
                WifiMonitorStatus.REJECTED -> getString(R.string.monitor_rejected, wait)
                WifiMonitorStatus.TIMEOUT -> getString(R.string.monitor_timeout, wait)
                WifiMonitorStatus.UNAVAILABLE -> getString(R.string.monitor_unavailable, wait)
            }
        }
        binding.setupButton.isVisible = state.throttled || state.access != WifiScanAccessRequirement.READY
        binding.pauseButton.setText(if (state.running) R.string.monitor_pause else R.string.monitor_resume)
        binding.signalChart.submit(state.history, if (state.canScan) state.nowMillis else state.history.lastOrNull()?.elapsedMillis ?: state.nowMillis, state.sessionStartedAtMillis, state.journal.changes)
        binding.channelsGroup.isVisible = state.channelsExpanded
        binding.channelsButton.setText(if (state.channelsExpanded) R.string.monitor_channels_hide else R.string.monitor_channels_show)
        binding.connection.text = state.connection?.let {
            "${text.ssid(it.ssid)} · ${getString(R.string.monitor_dbm, it.rssi)} · ${text.standard(it.standard)}\n${it.bssid} · " +
                getString(R.string.monitor_connection_radio, text.band(WifiRadioModel.band(it.frequencyMhz)),
                    WifiRadioModel.channel(it.frequencyMhz)?.toString() ?: text.unknown(), it.frequencyMhz) +
                "\n" + getString(R.string.monitor_connection_speed, text.speed(it.linkMbps), text.speed(it.txMbps), text.speed(it.rxMbps))
        } ?: getString(R.string.monitor_disconnected)
        binding.lastScan.text = state.lastScanMillis?.let {
            getString(R.string.monitor_last_scan, ((state.nowMillis - it) / 1000).coerceAtLeast(0))
        } ?: getString(R.string.monitor_no_scan)
        adapter.setConnectedBssid(state.connection?.bssid)
        val hadTable = displayedNetworks != null
        val filterChanged = displayedFilter != state.filter
        val filterResultsChanged = displayedNetworks !== state.networks || filterChanged
        if (filterResultsChanged) {
            displayedNetworks = state.networks
            displayedFilter = state.filter
            filteredNetworks = state.filter.apply(state.networks)
            binding.networkCount.text = getString(R.string.monitor_network_count, filteredNetworks.size, state.networks.size)
            binding.filterButton.setText(if (state.filter.isActive) R.string.monitor_filter_active else R.string.monitor_filter)
            binding.channelSummary.text = getString(R.string.monitor_channels_summary,
                filteredNetworks.count(WifiChannelAxes::displays), filteredNetworks.size)
        }
        if (filterResultsChanged || displayedSplit != state.splitBands) {
            if (hadTable && (displayedSplit != state.splitBands || filterChanged || !binding.networks.canScrollVertically(-1))) {
                resetTableScroll = true
            }
            displayedSplit = state.splitBands
            binding.splitBands.isChecked = state.splitBands
            val rows = wifiTableRows(filteredNetworks, split = state.splitBands)
            adapter.submitList(rows) {
                binding.tableHeader.band.isVisible = !state.splitBands
                if (resetTableScroll) {
                    (binding.networks.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(0, 0)
                    resetTableScroll = false
                }
            }
            binding.empty.isVisible = rows.isEmpty()
            binding.tableScroll.isVisible = rows.isNotEmpty()
        }
        if (state.channelsExpanded) binding.channelChart.submit(filteredNetworks, state.connection?.bssid)
    }

    private fun renderPane(state: WifiMonitorState) {
        val pane = state.pane
        if (binding.panes.selectedTabPosition != pane.ordinal) binding.panes.getTabAt(pane.ordinal)?.select()
        binding.bssidPane.isVisible = pane == MonitorPane.BSSIDS
        binding.logPane.isVisible = pane != MonitorPane.BSSIDS
        if (pane == MonitorPane.BSSIDS) return

        binding.probeStatus.text = if (state.canScan) WifiLogText(this).probe(state.journal.probe) else getString(R.string.monitor_probe_stopped)
        val rows = if (pane == MonitorPane.EVENTS) state.journal.events else {
            if (changesForHandovers !== state.journal.changes) {
                changesForHandovers = state.journal.changes
                handoverRows = state.journal.changes.filter { it.kind == ChangeKind.HANDOVER }
            }
            handoverRows
        }
        binding.logEmpty.isVisible = rows.isEmpty()
        binding.logEmpty.setText(if (pane == MonitorPane.EVENTS) R.string.monitor_no_events else R.string.monitor_no_handovers)
        binding.logRows.isVisible = rows.isNotEmpty()
        if (displayedLog !== rows) {
            val followNewest = !binding.logRows.canScrollVertically(-1)
            displayedLog = rows
            logAdapter.origin = state.journal.startedAt ?: 0
            logAdapter.submitList(rows.asReversed()) { if (followNewest) binding.logRows.scrollToPosition(0) }
        }
    }

    private fun showDetails(network: WifiNetworkSnapshot) {
        model.selectNetwork(network, SystemClock.elapsedRealtime())
    }

    private fun renderDetails(selection: WifiNetworkSelection?) {
        detailsBack.isEnabled = selection != null
        binding.scannerContent.isVisible = selection == null
        binding.detailsContent.isVisible = selection != null
        if (selection == displayedSelection) return
        displayedSelection = selection
        detailJob?.cancel()
        binding.detailsText.text = ""
        if (selection == null) return
        detailJob = lifecycleScope.launch {
            val details = withContext(Dispatchers.Default) {
                WifiNetworkDetailsText.format(applicationContext, selection.network, selection.selectedAtMillis)
            }
            binding.detailsText.text = details
            binding.detailsScroll.scrollTo(0, 0)
        }
    }

    override fun onDestroy() {
        dialog?.dismiss()
        scanner.close()
        scanThread.quitSafely()
        super.onDestroy()
    }
}
