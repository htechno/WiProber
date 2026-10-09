package com.example.wiprober

import android.content.res.ColorStateList
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.activity.addCallback
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import coil.load
import com.example.wiprober.databinding.ActivitySurveyBinding
import com.example.wiprober.databinding.DialogFloorNameBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SurveyActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySurveyBinding
    private val viewModel: MainViewModel by viewModels()
    private val projectRepository by lazy { ProjectRepository(applicationContext) }
    private val noteImageMetadataReader by lazy { NoteImageMetadataReader(contentResolver) }
    private lateinit var wifiScanner: WifiScanner
    private lateinit var androidCoordinator: SurveyAndroidCoordinator

    private var lastThrottlingToastTime: Long = 0
    private var lastTouchX: Float = 0f
    private var lastTouchY: Float = 0f
    private var tempPhotoUriForNote: Uri? = null
    private var activeNoteDialogPreview: ImageView? = null
    private var currentMapUri: Uri? = null
    private var currentProjectId: String? = null
    private var currentProjectTitle: String = ""
    private var currentFloorId: String? = null
    private var availableFloors: List<FloorOption> = emptyList()
    private var isProjectReady = false
    private var projectSaveJob: Job? = null
    private var trackingStatusJob: Job? = null
    private var stopAndGoScanJob: Job? = null
    private var continuousScanLoopJob: Job? = null

    //<editor-fold desc="Lifecycle & Setup">
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wifiScanner = WifiScanner(applicationContext, lifecycle)
        setupAndroidCoordinator()
        val projectId = intent.getStringExtra(EXTRA_PROJECT_ID)
        if (projectId.isNullOrBlank()) {
            finish()
            return
        }
        currentProjectId = projectId
        currentProjectTitle = savedInstanceState?.getString(STATE_PROJECT_TITLE).orEmpty()
        currentFloorId = savedInstanceState?.getString(STATE_CURRENT_FLOOR_ID)
        val restoredIds = savedInstanceState?.getStringArrayList(STATE_FLOOR_IDS).orEmpty()
        val restoredNames = savedInstanceState?.getStringArrayList(STATE_FLOOR_NAMES).orEmpty()
        if (restoredIds.size == restoredNames.size) {
            availableFloors = restoredIds.zip(restoredNames).map { FloorOption(it.first, it.second) }
        }
        lastTouchX = savedInstanceState?.getFloat(STATE_LAST_TOUCH_X) ?: lastTouchX
        lastTouchY = savedInstanceState?.getFloat(STATE_LAST_TOUCH_Y) ?: lastTouchY
        currentMapUri = savedInstanceState?.getString(STATE_CURRENT_MAP_URI)?.let(Uri::parse)
        binding = ActivitySurveyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupWindowInsets()
        setupWorkspaceChrome()
        setupClickListeners()
        onBackPressedDispatcher.addCallback(this) {
            val blockingProjectOperation = binding.projectOperationOverlay.visibility == View.VISIBLE &&
                binding.projectOperationProgress.visibility == View.VISIBLE
            if (!blockingProjectOperation && canChangeFloor()) finish()
        }
        setupObservers()
        showFirstTimeWarningIfNeeded()
        val interruptedOperation = if (savedInstanceState != null) {
            viewModel.recoverAfterActivityRecreation()
        } else {
            null
        }
        if (interruptedOperation != null || !restoreRetainedProject()) loadProject(projectId)
    }

    override fun onStop() {
        projectSaveJob?.cancel()
        if (isProjectReady) persistCurrentProject()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (isProjectReady && viewModel.screenState.value.isTracking) {
            startContinuousScanningLoop()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putFloat(STATE_LAST_TOUCH_X, lastTouchX)
        outState.putFloat(STATE_LAST_TOUCH_Y, lastTouchY)
        currentMapUri?.let { outState.putString(STATE_CURRENT_MAP_URI, it.toString()) }
        currentFloorId?.let { outState.putString(STATE_CURRENT_FLOOR_ID, it) }
        outState.putStringArrayList(STATE_FLOOR_IDS, ArrayList(availableFloors.map(FloorOption::id)))
        outState.putStringArrayList(STATE_FLOOR_NAMES, ArrayList(availableFloors.map(FloorOption::name)))
        outState.putString(STATE_PROJECT_TITLE, currentProjectTitle)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        trackingStatusJob?.cancel()
        stopAndGoScanJob?.cancel()
        continuousScanLoopJob?.cancel()
        wifiScanner.close()
        super.onDestroy()
    }

    private fun setupAndroidCoordinator() {
        androidCoordinator = SurveyAndroidCoordinator(
            activity = this,
            wifiScanner = wifiScanner,
            callbacks = object : SurveyAndroidCoordinator.Callbacks {
                override fun onNoteImageSelected(uri: Uri) {
                    tempPhotoUriForNote = uri
                    activeNoteDialogPreview?.apply {
                        load(uri) {
                            size(NOTE_PREVIEW_MAX_SIZE_PX)
                            crossfade(false)
                        }
                        visibility = View.VISIBLE
                    }
                }

                override fun onPictureCaptured(success: Boolean) {
                    if (success) {
                        activeNoteDialogPreview?.apply {
                            load(tempPhotoUriForNote) {
                                size(NOTE_PREVIEW_MAX_SIZE_PX)
                                crossfade(false)
                            }
                            visibility = View.VISIBLE
                        }
                    } else {
                        tempPhotoUriForNote = null
                    }
                }

                override fun onFloorMapSelected(uri: Uri?) {
                    if (uri == null) viewModel.finishProjectOperation(ProjectOperation.FLOOR_MAP_SELECTION)
                    else showAddFloorDialog(uri)
                }

                override fun onExportDestinationSelected(uri: Uri?) {
                    if (uri == null) viewModel.finishProjectOperation(ProjectOperation.EXPORT_DESTINATION)
                    else exportProject(uri)
                }

                override fun onScanAccessReady() = startPendingScanAction()

                override fun onScanAccessRejected(reason: ScanAccessRejection) {
                    viewModel.cancelPendingScanRequest()
                    val message = when (reason) {
                        ScanAccessRejection.WIFI_DISABLED -> R.string.wifi_not_enabled
                        ScanAccessRejection.LOCATION_DISABLED -> R.string.location_not_enabled
                        ScanAccessRejection.PERMISSION_DENIED -> R.string.permissions_required
                    }
                    Toast.makeText(this@SurveyActivity, message, Toast.LENGTH_LONG).show()
                }

                override fun onDeveloperSettingsUnavailable() {
                    Toast.makeText(
                        this@SurveyActivity,
                        R.string.developer_settings_unavailable,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    private fun setupObservers() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var previous = SurveyScreenState()
                viewModel.screenState.collect { state ->
                    val surveyChanged = state.scanPoints != previous.scanPoints ||
                        state.continuousScanSessions != previous.continuousScanSessions ||
                        state.notes != previous.notes ||
                        state.metersPerUnit != previous.metersPerUnit

                    binding.pointsOverlay.setScanPoints(state.scanPoints.map { Pair(it.x, it.y) })
                    binding.pointsOverlay.setCompletedTracks(state.continuousScanSessions)
                    binding.pointsOverlay.setActiveTrack(
                        state.activeTrackPoints.map { android.graphics.PointF(it.x, it.y) }
                    )
                    binding.pointsOverlay.setNoteMarkers(
                        state.notes.map { android.graphics.PointF(it.x, it.y) }
                    )
                    val calibration = state.interaction as? SurveyInteraction.Calibration
                    if (calibration == null) {
                        binding.pointsOverlay.clearCalibrationLine()
                    } else {
                        binding.pointsOverlay.setCalibrationLine(
                            calibration.points.map { android.graphics.PointF(it.x, it.y) }
                        )
                    }
                    if (!state.hasMap) binding.pointsOverlay.clearAll()

                    val summary = state.surveySummary()
                    val pointCount = quantityString(R.plurals.survey_point_count, summary.stopAndGoPoints)
                    val routeCount = quantityString(R.plurals.survey_route_count, summary.continuousRoutes)
                    val noteCount = quantityString(R.plurals.survey_note_count, summary.notes)
                    binding.surveySummaryText.text = getString(
                        R.string.survey_floor_summary,
                        pointCount,
                        routeCount,
                        noteCount
                    )
                    val floorName = availableFloors
                        .firstOrNull { it.id == currentFloorId }
                        ?.name
                        .orEmpty()
                    binding.mapImageView.contentDescription = getString(
                        R.string.survey_map_accessibility,
                        floorName,
                        pointCount,
                        routeCount,
                        noteCount
                    )

                    val expectedModeButton = if (state.scanMode == SurveyScanMode.CONTINUOUS) {
                        R.id.continuousModeButton
                    } else {
                        R.id.stopAndGoModeButton
                    }
                    if (binding.scanModeToggle.checkedButtonId != expectedModeButton) {
                        binding.scanModeToggle.check(expectedModeButton)
                    }

                    if (!previous.isTracking && state.isTracking) startTrackingStatusTicker()
                    if (previous.isTracking && !state.isTracking) trackingStatusJob?.cancel()
                    if (previous.interaction !is SurveyInteraction.Calibration &&
                        state.interaction is SurveyInteraction.Calibration
                    ) {
                        Toast.makeText(
                            this@SurveyActivity,
                            R.string.calibration_tap_first_point,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    if (previous.interaction !is SurveyInteraction.NotePlacement &&
                        state.interaction is SurveyInteraction.NotePlacement
                    ) {
                        Toast.makeText(
                            this@SurveyActivity,
                            R.string.note_tap_location,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    state.lastUndoneAction?.let { action ->
                        val message = when (action) {
                            LastAction.SCAN -> R.string.last_scan_undone
                            LastAction.NOTE -> R.string.last_note_undone
                            LastAction.SCAN_SESSION -> R.string.last_track_undone
                        }
                        Toast.makeText(this@SurveyActivity, message, Toast.LENGTH_SHORT).show()
                        viewModel.consumeLastUndoneAction()
                    }

                    updateSurveyModeUi(state)
                    updateButtonStates(state)
                    if (surveyChanged) scheduleProjectSave()
                    previous = state
                }
            }
        }
    }

    private fun setupWorkspaceChrome() {
        binding.surveyToolbar.inflateMenu(R.menu.menu_survey)
        binding.surveyToolbar.setNavigationOnClickListener {
            if (canChangeFloor()) finish()
        }
        binding.surveyToolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_export_esx -> {
                    requestProjectExport()
                    true
                }
                R.id.action_about -> {
                    showAboutDialog()
                    true
                }
                else -> false
            }
        }
        binding.floorDropdown.setOnItemClickListener { _, _, position, _ ->
            val activeFloor = availableFloors.firstOrNull { it.id == currentFloorId }
            if (position == availableFloors.size) {
                activeFloor?.let { binding.floorDropdown.setText(it.name, false) }
                if (canChangeFloor() && viewModel.beginProjectOperation(ProjectOperation.FLOOR_MAP_SELECTION)) {
                    androidCoordinator.selectFloorMap()
                }
            } else {
                val floor = availableFloors.getOrNull(position) ?: return@setOnItemClickListener
                if (floor.id != currentFloorId && canChangeFloor()) switchFloor(floor.id)
            }
        }
    }

    private fun setupClickListeners() {
        binding.scaleButton.setOnClickListener { viewModel.enterCalibrationMode() }
        binding.addNoteButton.setOnClickListener { viewModel.beginNotePlacement() }
        binding.undoButton.setOnClickListener { viewModel.undoLastAction() }
        binding.closeProjectButton.setOnClickListener { finish() }
        binding.retryProjectButton.setOnClickListener {
            currentProjectId?.let(::loadProject)
        }

        binding.scanModeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            if (checkedId == R.id.continuousModeButton) {
                if (androidCoordinator.isScanThrottlingEnabled()) {
                    binding.scanModeToggle.check(R.id.stopAndGoModeButton)
                    showThrottlingBlockDialog()
                } else {
                    viewModel.setScanMode(SurveyScanMode.CONTINUOUS)
                    Toast.makeText(this, R.string.scan_hint_continuous, Toast.LENGTH_SHORT).show()
                }
            } else {
                viewModel.setScanMode(SurveyScanMode.STOP_AND_GO)
                Toast.makeText(this, R.string.scan_hint_stop_and_go, Toast.LENGTH_SHORT).show()
            }
        }

        binding.continuousActionButton.setOnClickListener {
            val state = viewModel.screenState.value
            if (state.isTracking) {
                continuousScanLoopJob?.cancel()
                continuousScanLoopJob = null
                viewModel.stopContinuousTrack(lastTouchX, lastTouchY)
            } else if (state.interaction is SurveyInteraction.AwaitingContinuousStart) {
                viewModel.cancelContinuousStart()
            } else {
                viewModel.armContinuousStart()
            }
        }

        binding.mapImageView.setOnMatrixChangeListener { syncOverlayTransform() }

        binding.mapImageView.setOnViewTapListener { _, x, y ->
            val state = viewModel.screenState.value
            if (!state.hasMap) {
                Toast.makeText(this, R.string.select_map_first, Toast.LENGTH_SHORT).show()
                return@setOnViewTapListener
            }
            when (state.interaction) {
                SurveyInteraction.NotePlacement -> {
                    handleNoteTap(x, y)
                    viewModel.finishNotePlacement()
                }
                is SurveyInteraction.Calibration -> {
                    handleCalibrationTap(x, y)
                }
                else -> {
                    val originalCoords = getOriginalImageCoordinates(binding.mapImageView, x, y) ?: return@setOnViewTapListener
                    if (state.scanMode == SurveyScanMode.CONTINUOUS) {
                        lastTouchX = originalCoords[0]
                        lastTouchY = originalCoords[1]
                        if (state.isTracking ||
                            state.interaction is SurveyInteraction.AwaitingContinuousStart
                        ) {
                            handleContinuousTap(lastTouchX, lastTouchY)
                        } else {
                            Toast.makeText(this, R.string.continuous_ready, Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        if (prepareForScan()) {
                            lastTouchX = originalCoords[0]
                            lastTouchY = originalCoords[1]
                            if (viewModel.requestStopAndGoScan(lastTouchX, lastTouchY)) {
                                androidCoordinator.ensureWifiScanAccess()
                            }
                        }
                    }
                }
            }
        }

    }

    private fun setupWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.mainContainer) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }
    //</editor-fold>

    //<editor-fold desc="UI & State Logic">
    private fun updateButtonStates(state: SurveyScreenState = viewModel.screenState.value) {
        val hasActions = state.actionHistory.isNotEmpty()
        val hasMap = state.hasMap && isProjectReady
        val idle = state.interaction is SurveyInteraction.Idle
        val continuousActionAvailable = state.interaction is SurveyInteraction.Idle ||
            state.interaction is SurveyInteraction.AwaitingContinuousStart || state.isTracking

        binding.surveyToolbar.menu.findItem(R.id.action_export_esx)?.isEnabled = hasMap && idle
        binding.undoButton.isEnabled = hasActions && idle
        binding.scaleButton.isEnabled = hasMap && idle
        binding.addNoteButton.isEnabled = hasMap && idle
        binding.scanModeToggle.isEnabled = hasMap && idle
        binding.floorDropdown.isEnabled = hasMap && idle
        binding.continuousActionButton.isEnabled = hasMap && continuousActionAvailable
        binding.surveyDock.alpha = if (hasMap) 1f else 0.6f
    }

    private fun requestProjectExport() {
        if (!viewModel.screenState.value.hasMap || !canChangeFloor()) {
            Toast.makeText(this, R.string.no_map_loaded, Toast.LENGTH_SHORT).show()
            return
        }
        val safeTitle = currentProjectTitle
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .ifBlank { "WiProber-project" }
        if (viewModel.beginProjectOperation(ProjectOperation.EXPORT_DESTINATION)) {
            androidCoordinator.createEsxDocument("$safeTitle.esx")
        }
    }

    private fun exportProject(destination: Uri) {
        val activeSnapshot = createProjectSnapshot()
        val projectId = currentProjectId
        val floorId = currentFloorId
        if (activeSnapshot == null || projectId == null || floorId == null ||
            !viewModel.beginProjectOperation(ProjectOperation.EXPORT)
        ) {
            viewModel.finishProjectOperation()
            Toast.makeText(applicationContext, R.string.export_failed, Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            updateButtonStates()
            showProjectOperationProgress(R.string.exporting_project)
            projectSaveJob?.cancelAndJoin()
            val exportResult = withContext(Dispatchers.IO) {
                runCatching {
                    projectRepository.saveProject(projectId, floorId, activeSnapshot)
                    val projectSnapshot = projectRepository.snapshotProject(projectId)
                    EsxExportService(applicationContext).export(destination, projectSnapshot)
                    projectSnapshot.surveySummary()
                }
            }
            hideProjectOperation()
            viewModel.finishProjectOperation(ProjectOperation.EXPORT)
            updateButtonStates()
            exportResult.onSuccess(::showExportSuccess)
                .onFailure { error ->
                    Log.e("ArchiveCreation", "Cannot export project", error)
                    showExportFailure(error)
                }
        }
    }

    private fun showExportSuccess(summary: ProjectSurveySummary) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.export_complete_title)
            .setMessage(
                getString(
                    R.string.export_complete_summary,
                    quantityString(R.plurals.project_floor_count, summary.floors),
                    quantityString(R.plurals.survey_point_count, summary.stopAndGoPoints),
                    quantityString(R.plurals.survey_route_count, summary.continuousRoutes),
                    quantityString(R.plurals.survey_note_count, summary.notes)
                )
            )
            .setPositiveButton(R.string.action_ok, null)
            .show()
    }

    private fun showExportFailure(error: Throwable) {
        val detail = error.message?.takeIf(String::isNotBlank) ?: getString(R.string.unknown_error)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.export_failed_title)
            .setMessage(getString(R.string.export_failed_detail, detail))
            .setPositiveButton(R.string.action_ok, null)
            .show()
    }

    private fun showProjectOperationProgress(@StringRes messageResource: Int) {
        binding.projectOperationText.setText(messageResource)
        binding.projectOperationProgress.visibility = View.VISIBLE
        binding.projectOperationErrorActions.visibility = View.GONE
        binding.projectOperationOverlay.visibility = View.VISIBLE
    }

    private fun showProjectOperationError(message: String) {
        binding.projectOperationText.text = message
        binding.projectOperationProgress.visibility = View.GONE
        binding.projectOperationErrorActions.visibility = View.VISIBLE
        binding.projectOperationOverlay.visibility = View.VISIBLE
    }

    private fun hideProjectOperation() {
        binding.projectOperationOverlay.visibility = View.GONE
        binding.projectOperationErrorActions.visibility = View.GONE
    }

    private fun updateSurveyModeUi(state: SurveyScreenState = viewModel.screenState.value) {
        if (state.interaction is SurveyInteraction.StopAndGoScanning) {
            binding.surveyStatusText.setText(R.string.scan_in_progress)
            binding.continuousActionButton.visibility = View.GONE
            return
        }
        val elapsed = viewModel.activeContinuousElapsedMillis()
        val elapsedLabel = getString(
            R.string.continuous_elapsed,
            elapsed / 60_000L,
            (elapsed / 1_000L) % 60L
        )
        val presentation = presentSurveyControls(
            isContinuous = state.scanMode == SurveyScanMode.CONTINUOUS,
            isAwaitingContinuousStart = state.interaction is SurveyInteraction.AwaitingContinuousStart,
            isTracking = state.isTracking,
            elapsedLabel = elapsedLabel,
            scanCountLabel = quantityString(R.plurals.scan_count, state.activeContinuousScanCount)
        )
        binding.surveyStatusText.text = getString(
            presentation.statusTextRes,
            *presentation.statusArguments.toTypedArray()
        )
        binding.continuousActionButton.visibility = if (presentation.actionVisible) View.VISIBLE else View.GONE
        presentation.actionTextRes?.let(binding.continuousActionButton::setText)
        presentation.actionIconRes?.let(binding.continuousActionButton::setIconResource)
            ?: run { binding.continuousActionButton.icon = null }
        if (presentation.actionTone == SurveyActionTone.DESTRUCTIVE) {
            applyDestructiveContinuousActionColors()
        } else {
            applyPrimaryContinuousActionColors()
        }
    }

    private fun applyDestructiveContinuousActionColors() {
        binding.continuousActionButton.backgroundTintList = ColorStateList.valueOf(
            MaterialColors.getColor(this, com.google.android.material.R.attr.colorError, Color.RED)
        )
        val foreground = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOnError,
            Color.WHITE
        )
        binding.continuousActionButton.setTextColor(foreground)
        binding.continuousActionButton.iconTint = ColorStateList.valueOf(foreground)
    }

    private fun applyPrimaryContinuousActionColors() {
        binding.continuousActionButton.backgroundTintList = ColorStateList.valueOf(
            MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary, Color.BLUE)
        )
        val foreground = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOnPrimary,
            Color.WHITE
        )
        binding.continuousActionButton.setTextColor(foreground)
        binding.continuousActionButton.iconTint = ColorStateList.valueOf(foreground)
    }

    private fun startTrackingStatusTicker() {
        trackingStatusJob?.cancel()
        trackingStatusJob = lifecycleScope.launch {
            while (viewModel.screenState.value.isTracking) {
                updateSurveyModeUi()
                delay(1_000L)
            }
        }
    }

    private fun showFirstTimeWarningIfNeeded() {
        val prefs = getSharedPreferences("WiProberPrefs", MODE_PRIVATE)
        if (!prefs.getBoolean("has_shown_english_warning", false)) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.important_information_title)
                .setMessage(R.string.important_information_message)
                .setPositiveButton(R.string.action_got_it) { _, _ ->
                    prefs.edit().putBoolean("has_shown_english_warning", true).apply()
                }
                .setNeutralButton(R.string.action_developer_settings) { _, _ ->
                    androidCoordinator.openDeveloperSettings()
                    prefs.edit().putBoolean("has_shown_english_warning", true).apply()
                }
                .setCancelable(false)
                .show()
        }
    }

    private fun showThrottlingBlockDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.mode_unavailable_title)
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setMessage(R.string.continuous_requires_throttling_disabled)
            .setPositiveButton(R.string.action_developer_settings) { _, _ -> androidCoordinator.openDeveloperSettings() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun showThrottlingLimitDialog(secondsLeft: Int) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.scan_frequency_limit_title)
            .setIcon(R.drawable.ic_info)
            .setMessage(
                getString(
                    R.string.scan_frequency_limit_message,
                    quantityString(R.plurals.second_count, secondsLeft)
                )
            )
            .setPositiveButton(R.string.action_open_settings) { _, _ -> androidCoordinator.openDeveloperSettings() }
            .setNegativeButton(R.string.action_wait) { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun showAboutDialog() {
        val versionName = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: PackageManager.NameNotFoundException) {
            getString(R.string.version_unavailable)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.action_about)
            .setIcon(R.mipmap.ic_launcher)
            .setMessage(getString(R.string.about_message, versionName))
            .setPositiveButton(R.string.action_ok, null)
            .setNeutralButton(R.string.action_github) { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/htechno/WiProber"))
                startActivity(intent)
            }
            .show()
    }
    //</editor-fold>

    //<editor-fold desc="LOGIC: Continuous Mode">
    private fun handleContinuousTap(x: Float, y: Float) {
        val state = viewModel.screenState.value
        if (state.isTracking) {
            viewModel.addContinuousWaypoint(x, y)
            Toast.makeText(this, R.string.waypoint_added, Toast.LENGTH_SHORT).show()
        } else if (viewModel.requestContinuousStart(x, y)) {
            androidCoordinator.ensureWifiScanAccess()
        }
    }

    private fun startContinuousScanningLoop() {
        if (!viewModel.screenState.value.isTracking || continuousScanLoopJob?.isActive == true) {
            return
        }

        val scanLoopJob = lifecycleScope.launch {
            while (isActive && viewModel.screenState.value.isTracking) {
                if (!wifiScanner.isWifiEnabled()) {
                    stopContinuousTrackAfterFailure(R.string.continuous_stopped_wifi_disabled)
                    break
                }

                val attemptStartedAt = viewModel.beginContinuousScanAttempt() ?: break
                when (val outcome = wifiScanner.scan()) {
                    is WifiScanOutcome.Success -> {
                        val wifiInfoList = mapScanResults(outcome.results)
                        if (!viewModel.completeContinuousScanAttempt(
                                attemptStartedAt,
                                wifiInfoList
                            )
                        ) {
                            break
                        }
                    }

                    is WifiScanOutcome.Cancelled -> break
                    is WifiScanOutcome.Failure -> when (outcome.reason) {
                        WifiScanFailureReason.WIFI_DISABLED -> {
                            stopContinuousTrackAfterFailure(
                                R.string.continuous_stopped_wifi_disabled
                            )
                            break
                        }

                        WifiScanFailureReason.PERMISSION_DENIED -> {
                            stopContinuousTrackAfterFailure(R.string.permissions_required)
                            break
                        }

                        WifiScanFailureReason.SCAN_UNAVAILABLE -> {
                            stopContinuousTrackAfterFailure(
                                R.string.continuous_stopped_scan_unavailable
                            )
                            break
                        }

                        WifiScanFailureReason.SCANNER_NOT_STARTED,
                        WifiScanFailureReason.SCANNER_CLOSED -> break

                        WifiScanFailureReason.ALREADY_IN_PROGRESS,
                        WifiScanFailureReason.START_REJECTED,
                        WifiScanFailureReason.RESULTS_NOT_UPDATED,
                        WifiScanFailureReason.TIMEOUT,
                        WifiScanFailureReason.INTERNAL_ERROR -> delay(SCAN_RETRY_DELAY_MILLIS)
                    }
                }
            }
        }
        continuousScanLoopJob = scanLoopJob
        scanLoopJob.invokeOnCompletion {
            if (continuousScanLoopJob === scanLoopJob) continuousScanLoopJob = null
        }
    }

    private fun stopContinuousTrackAfterFailure(messageRes: Int) {
        if (viewModel.screenState.value.isTracking) {
            viewModel.stopContinuousTrack(lastTouchX, lastTouchY)
        }
        Toast.makeText(this, messageRes, Toast.LENGTH_LONG).show()
    }
    //</editor-fold>

    //<editor-fold desc="LOGIC: Stop-and-Go Mode & Setup">
    private fun prepareForScan(): Boolean =
        viewModel.screenState.value.interaction is SurveyInteraction.Idle && canScan()

    private fun canScan(): Boolean {
        val waitMillis = viewModel.stopAndGoThrottleDelayMillis(
            androidCoordinator.isScanThrottlingEnabled()
        )
        if (waitMillis <= 0L) return true
        val elapsedNow = android.os.SystemClock.elapsedRealtime()
        if (elapsedNow - lastThrottlingToastTime > 5_000L) {
            showThrottlingLimitDialog((waitMillis / 1_000L).toInt() + 1)
            lastThrottlingToastTime = elapsedNow
        }
        return false
    }

    private fun scanWifi() {
        if (stopAndGoScanJob?.isActive == true) return
        binding.surveyStatusText.setText(R.string.scan_in_progress)
        updateButtonStates()
        binding.scanProgressBar.visibility = View.VISIBLE
        val scanJob = lifecycleScope.launch {
            when (val outcome = wifiScanner.scan()) {
                is WifiScanOutcome.Success -> {
                    val wifiInfoList = mapScanResults(outcome.results)
                    viewModel.completeStopAndGoScan(wifiInfoList)
                    binding.surveyStatusText.text = getString(
                        R.string.scan_completed,
                        quantityString(R.plurals.network_count, outcome.results.size)
                    )
                }

                is WifiScanOutcome.Failure -> {
                    viewModel.failStopAndGoScan()
                    binding.surveyStatusText.setText(R.string.scan_failed)
                    val message = when (outcome.reason) {
                        WifiScanFailureReason.WIFI_DISABLED -> R.string.wifi_not_enabled
                        WifiScanFailureReason.PERMISSION_DENIED -> R.string.permissions_required
                        else -> R.string.scan_failed
                    }
                    Toast.makeText(this@SurveyActivity, message, Toast.LENGTH_SHORT).show()
                }

                is WifiScanOutcome.Cancelled -> {
                    viewModel.failStopAndGoScan()
                    binding.surveyStatusText.setText(R.string.scan_hint_stop_and_go)
                }
            }
            binding.scanProgressBar.visibility = View.GONE
            updateButtonStates()
        }
        stopAndGoScanJob = scanJob
        scanJob.invokeOnCompletion {
            if (stopAndGoScanJob === scanJob) stopAndGoScanJob = null
        }
    }

    private fun startPendingScanAction() {
        when (val action = viewModel.grantPendingScanAccess()) {
            is StartedScanAction.StopAndGo -> scanWifi()
            StartedScanAction.Continuous -> {
                Toast.makeText(this, R.string.track_started, Toast.LENGTH_SHORT).show()
                startContinuousScanningLoop()
            }
            null -> Unit
        }
    }

    private fun mapScanResults(results: List<android.net.wifi.ScanResult>): List<WifiNetworkInfo> {
        return results.map { scanResult ->
            val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                scanResult.wifiSsid?.toString()?.removeSurrounding("\"") ?: "<unknown ssid>"
            } else {
                @Suppress("DEPRECATION")
                scanResult.SSID
            }
            WifiNetworkInfo(
                ssid = ssid,
                bssid = scanResult.BSSID,
                level = scanResult.level,
                frequency = scanResult.frequency,
                security = parseSecurity(scanResult.capabilities),
                technologies = getWifiTechnologies(scanResult),
                informationElements = getInformationElementsAsBase64(scanResult)
            )
        }
    }
    //</editor-fold>

    //<editor-fold desc="Note & Calibration Logic">
    private fun handleNoteTap(x: Float, y: Float) {
        val originalCoords = getOriginalImageCoordinates(binding.mapImageView, x, y) ?: return
        showNoteCreationDialog(originalCoords)
    }

    private fun showNoteCreationDialog(noteCoords: FloatArray) {
        tempPhotoUriForNote = null
        val dialogView = layoutInflater.inflate(R.layout.dialog_create_note, null)
        val noteEditText = dialogView.findViewById<EditText>(R.id.noteEditText)
        val photoPreview = dialogView.findViewById<ImageView>(R.id.photoPreview)
        val addPhotoButton = dialogView.findViewById<Button>(R.id.addPhotoButton)
        activeNoteDialogPreview = photoPreview
        addPhotoButton.setOnClickListener { showImageSourceChooser() }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.create_note_title)
            .setView(dialogView)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_save, null)
            .create()
        dialog.setOnShowListener {
            val saveButton = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
            saveButton.setOnClickListener {
                val text = noteEditText.text.toString()
                if (text.isBlank() && tempPhotoUriForNote == null) {
                    Toast.makeText(this, R.string.note_content_required, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val photoUri = tempPhotoUriForNote
                saveButton.isEnabled = false
                lifecycleScope.launch {
                    val metadataResult = withContext(Dispatchers.IO) {
                        runCatching { photoUri?.let(::readNoteImageMetadata) }
                    }
                    metadataResult.onSuccess { metadata ->
                        val note = viewModel.addNote(
                            text = text,
                            photoUri = photoUri?.toString(),
                            photoWidth = metadata?.width,
                            photoHeight = metadata?.height,
                            x = noteCoords[0],
                            y = noteCoords[1],
                            photoFormat = metadata?.format
                        )
                        if (note != null) dialog.dismiss() else saveButton.isEnabled = true
                    }.onFailure { error ->
                        saveButton.isEnabled = true
                        Log.e("NoteImage", "Cannot read note image metadata", error)
                        Toast.makeText(
                            this@SurveyActivity,
                            R.string.note_image_read_failed,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
        dialog.setOnDismissListener { activeNoteDialogPreview = null }
        dialog.show()
    }

    private fun showImageSourceChooser() {
        val options = arrayOf(
            getString(R.string.take_photo),
            getString(R.string.choose_from_gallery)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.image_source_title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        val photoFile = File.createTempFile("note_photo_", ".jpg", cacheDir)
                        val photoUri = FileProvider.getUriForFile(this, "${applicationContext.packageName}.provider", photoFile)
                        tempPhotoUriForNote = photoUri
                        androidCoordinator.takePicture(photoUri)
                    }
                    1 -> androidCoordinator.selectNoteImage()
                }
            }
            .show()
    }

    private fun handleCalibrationTap(x: Float, y: Float) {
        val originalCoords = getOriginalImageCoordinates(binding.mapImageView, x, y) ?: return
        val points = viewModel.addCalibrationPoint(SurveyCoordinate(originalCoords[0], originalCoords[1]))
        binding.pointsOverlay.setCalibrationLine(points.map { android.graphics.PointF(it.x, it.y) })

        if (points.size == 2) {
            showDistanceInputDialog()
        }
    }

    private fun showDistanceInputDialog() {
        val calibration = viewModel.screenState.value.interaction as? SurveyInteraction.Calibration ?: return
        if (calibration.points.size < 2) return
        val editText = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = getString(R.string.distance_meters_hint)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.enter_distance_title)
            .setMessage(R.string.enter_distance_message)
            .setView(editText)
            .setPositiveButton(R.string.action_ok) { _, _ ->
                val distanceString = editText.text.toString()
                val distanceMeters = distanceString.toDoubleOrNull()
                if (distanceMeters == null || distanceMeters <= 0) {
                    Toast.makeText(this, R.string.invalid_distance_value, Toast.LENGTH_SHORT).show()
                    viewModel.clearCalibration()
                    binding.pointsOverlay.clearCalibrationLine()
                    return@setPositiveButton
                }
                if (!viewModel.applyCalibration(distanceMeters)) {
                    Toast.makeText(this, R.string.invalid_calibration_points, Toast.LENGTH_SHORT).show()
                    viewModel.clearCalibration()
                    return@setPositiveButton
                }
                Log.d("Calibration", "Scale: ${viewModel.screenState.value.metersPerUnit} m/px")
                Toast.makeText(this, R.string.scale_calibrated, Toast.LENGTH_LONG).show()
            }
            .setNegativeButton(R.string.action_cancel) { dialog, _ ->
                viewModel.clearCalibration()
                binding.pointsOverlay.clearCalibrationLine()
                dialog.cancel()
            }
            .show()
    }
    //</editor-fold>

    //<editor-fold desc="Helpers">
    private fun getOriginalImageCoordinates(
        photoView: com.github.chrisbanes.photoview.PhotoView,
        screenX: Float,
        screenY: Float
    ): FloatArray? {
        val matrix = android.graphics.Matrix()
        photoView.getDisplayMatrix(matrix)
        val inverseMatrix = android.graphics.Matrix()
        matrix.invert(inverseMatrix)
        val screenPoints = floatArrayOf(screenX, screenY)
        inverseMatrix.mapPoints(screenPoints)
        val mapper = currentCoordinateMapper() ?: return null
        if (!mapper.containsDrawablePoint(screenPoints[0], screenPoints[1])) return null
        return mapper.drawableToSource(screenPoints[0], screenPoints[1])
    }

    private fun currentCoordinateMapper(): ImageCoordinateMapper? {
        val mapInfo = viewModel.screenState.value.mapInfo ?: return null
        val drawable = binding.mapImageView.drawable ?: return null
        val drawableWidth = drawable.intrinsicWidth.takeIf { it > 0 } ?: return null
        val drawableHeight = drawable.intrinsicHeight.takeIf { it > 0 } ?: return null
        return ImageCoordinateMapper(
            sourceWidth = mapInfo.width,
            sourceHeight = mapInfo.height,
            drawableWidth = drawableWidth,
            drawableHeight = drawableHeight
        )
    }

    private fun syncOverlayTransform() {
        val mapper = currentCoordinateMapper() ?: return
        binding.pointsOverlay.updateTransform(binding.mapImageView.imageMatrix, mapper)
    }

    private fun parseSecurity(capabilities: String): String {
        return when {
            capabilities.contains("WPA3") -> "WPA3"
            capabilities.contains("WPA2") -> "WPA2"
            capabilities.contains("WPA") -> "WPA"
            capabilities.contains("WEP") -> "WEP"
            capabilities.contains("ESS") -> "Open"
            else -> "Unknown"
        }
    }

    private fun getWifiTechnologies(scanResult: android.net.wifi.ScanResult): List<String> {
        return WifiTechnologyMapper.map(scanResult.wifiStandard, scanResult.frequency)
    }

    private fun getInformationElementsAsBase64(scanResult: android.net.wifi.ScanResult): String {
        val informationElements = scanResult.informationElements ?: return ""
        val fullIEsBuffer = java.nio.ByteBuffer.allocate(512)
        informationElements.forEach { ie ->
            val valueByteArray = ByteArray(ie.bytes.remaining()).also { ie.bytes.get(it) }
            if (fullIEsBuffer.remaining() >= 2 + valueByteArray.size) {
                fullIEsBuffer.put(ie.id.toByte())
                fullIEsBuffer.put(valueByteArray.size.toByte())
                fullIEsBuffer.put(valueByteArray)
            } else {
                Log.w("IE_Builder", "Full IE buffer")
                return@forEach
            }
        }
        val finalByteArray = ByteArray(fullIEsBuffer.position()).also {
            fullIEsBuffer.rewind(); fullIEsBuffer.get(it)
        }
        return java.util.Base64.getEncoder().encodeToString(finalByteArray)
    }

    private fun readNoteImageMetadata(uri: Uri): NoteImageMetadata {
        return noteImageMetadataReader.read(uri)
    }

    private fun canChangeFloor(): Boolean {
        val blocked = viewModel.screenState.value.interaction !is SurveyInteraction.Idle
        if (blocked) {
            Toast.makeText(this, R.string.floor_switch_busy, Toast.LENGTH_SHORT).show()
        }
        return !blocked
    }

    private fun showAddFloorDialog(mapUri: Uri) {
        val operation = viewModel.screenState.value.interaction as? SurveyInteraction.ProjectBusy
        if (operation?.operation != ProjectOperation.FLOOR_MAP_SELECTION) return
        val dialogBinding = DialogFloorNameBinding.inflate(layoutInflater)
        dialogBinding.floorNameInput.setText(
            getString(R.string.default_added_floor_name, availableFloors.size + 1)
        )
        dialogBinding.floorNameInput.selectAll()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_floor_title)
            .setMessage(getString(R.string.add_floor_plan_selected, queryDisplayName(mapUri)))
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.action_add) { _, _ ->
                val name = dialogBinding.floorNameInput.text?.toString().orEmpty().trim()
                addFloor(mapUri, name.ifBlank { getString(R.string.default_floor_plan_name) })
            }
            .setNegativeButton(R.string.action_cancel) { _, _ ->
                viewModel.finishProjectOperation(ProjectOperation.FLOOR_MAP_SELECTION)
            }
            .setOnCancelListener {
                viewModel.finishProjectOperation(ProjectOperation.FLOOR_MAP_SELECTION)
            }
            .show()
    }

    private fun switchFloor(floorId: String) {
        val projectId = currentProjectId ?: return
        if (!canChangeFloor()) return
        val previousFloorId = currentFloorId
        val snapshot = createProjectSnapshot()
        if (!viewModel.beginProjectOperation(ProjectOperation.FLOOR_SWITCH)) return
        lifecycleScope.launch {
            isProjectReady = false
            updateButtonStates()
            showProjectOperationProgress(R.string.switching_floor)
            projectSaveJob?.cancelAndJoin()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (previousFloorId != null && snapshot != null) {
                        projectRepository.saveProject(projectId, previousFloorId, snapshot)
                    }
                    projectRepository.openProject(projectId, floorId)
                }
            }
            result.onSuccess(::displayProject)
                .onFailure { error ->
                    hideProjectOperation()
                    isProjectReady = true
                    viewModel.finishProjectOperation(ProjectOperation.FLOOR_SWITCH)
                    previousFloorId?.let(::renderFloorDropdown)
                    updateButtonStates()
                    Log.e("ProjectStorage", "Cannot switch floor", error)
                    Toast.makeText(
                        this@SurveyActivity,
                        getString(R.string.floor_switch_failed, error.message ?: getString(R.string.unknown_error)),
                        Toast.LENGTH_LONG
                    ).show()
                }
        }
    }

    private fun addFloor(mapUri: Uri, floorName: String) {
        val projectId = currentProjectId ?: return
        if (!viewModel.beginProjectOperation(ProjectOperation.FLOOR_ADD)) return
        val previousFloorId = currentFloorId
        val snapshot = createProjectSnapshot()
        val displayName = queryDisplayName(mapUri)
        lifecycleScope.launch {
            isProjectReady = false
            updateButtonStates()
            showProjectOperationProgress(R.string.adding_floor)
            projectSaveJob?.cancelAndJoin()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    if (previousFloorId != null && snapshot != null) {
                        projectRepository.saveProject(projectId, previousFloorId, snapshot)
                    }
                    val input = requireNotNull(contentResolver.openInputStream(mapUri)) {
                        getString(R.string.floor_plan_read_failed)
                    }
                    projectRepository.addFloor(projectId, input, displayName, floorName)
                }
            }
            result.onSuccess(::displayProject)
                .onFailure { error ->
                    hideProjectOperation()
                    isProjectReady = true
                    viewModel.finishProjectOperation(ProjectOperation.FLOOR_ADD)
                    updateButtonStates()
                    Log.e("ProjectStorage", "Cannot add floor", error)
                    Toast.makeText(
                        this@SurveyActivity,
                        getString(R.string.floor_add_failed, error.message ?: getString(R.string.unknown_error)),
                        Toast.LENGTH_LONG
                    ).show()
                }
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
        return getString(R.string.default_floor_plan_name)
    }

    private fun loadProject(projectId: String) {
        if (!viewModel.beginProjectOperation(ProjectOperation.INITIAL_LOAD)) return
        lifecycleScope.launch {
            showProjectOperationProgress(R.string.loading_project)
            val result = withContext(Dispatchers.IO) {
                runCatching { projectRepository.openProject(projectId) }
            }
            result.onSuccess(::displayProject).onFailure { error ->
                viewModel.finishProjectOperation(ProjectOperation.INITIAL_LOAD)
                Log.e("ProjectStorage", "Error opening project", error)
                showProjectOperationError(
                    getString(
                        R.string.project_open_error,
                        error.message ?: getString(R.string.unknown_error)
                    )
                )
            }
        }
    }

    private fun displayProject(project: LocalSurveyProject) {
        currentProjectTitle = project.title
        currentFloorId = project.activeFloorId
        availableFloors = project.floors.map { FloorOption(it.id, it.name) }
        binding.surveyToolbar.title = project.title
        renderFloorDropdown(project.activeFloorId)
        val floor = project.activeFloor
        currentMapUri = Uri.fromFile(floor.mapFile)
        isProjectReady = false
        updateButtonStates()
        if (binding.projectOperationOverlay.visibility != View.VISIBLE) {
            showProjectOperationProgress(R.string.loading_project)
        }
        binding.mapImageView.load(floor.mapFile) {
            listener(
                onSuccess = { _, _ ->
                    isProjectReady = true
                    hideProjectOperation()
                    binding.mapImageView.post(::syncOverlayTransform)
                    updateButtonStates()
                    if (viewModel.screenState.value.isTracking &&
                        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                    ) {
                        startContinuousScanningLoop()
                    }
                },
                onError = { _, result ->
                    isProjectReady = false
                    showProjectOperationError(
                        getString(
                            R.string.map_decode_error,
                            result.throwable.message ?: getString(R.string.unknown_error)
                        )
                    )
                    updateButtonStates()
                }
            )
        }
        binding.mapImageView.maximumScale = 10.0f
        viewModel.loadProject(
            mapInfo = floor.mapInfo,
            scale = floor.metersPerUnit,
            scanPoints = floor.scanPoints,
            continuousSessions = floor.continuousScanSessions,
            notes = floor.notes
        )
        binding.mapImageView.post(::syncOverlayTransform)
    }

    private fun restoreRetainedProject(): Boolean {
        val mapUri = currentMapUri ?: return false
        if (!viewModel.screenState.value.hasMap) return false
        isProjectReady = false
        showProjectOperationProgress(R.string.loading_project)
        binding.mapImageView.load(mapUri) {
            listener(
                onSuccess = { _, _ ->
                    isProjectReady = true
                    hideProjectOperation()
                    binding.mapImageView.post(::syncOverlayTransform)
                    updateButtonStates()
                    if (viewModel.screenState.value.isTracking &&
                        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                    ) {
                        startContinuousScanningLoop()
                    }
                },
                onError = { _, result ->
                    showProjectOperationError(
                        getString(
                            R.string.map_decode_error,
                            result.throwable.message ?: getString(R.string.unknown_error)
                        )
                    )
                }
            )
        }
        binding.mapImageView.maximumScale = 10.0f
        binding.surveyToolbar.title = currentProjectTitle.ifBlank { getString(R.string.survey_screen_title) }
        currentFloorId?.let(::renderFloorDropdown)
        return true
    }

    private fun renderFloorDropdown(activeFloorId: String) {
        val labels = availableFloors.map(FloorOption::name) + getString(R.string.add_floor_dropdown_item)
        binding.floorDropdown.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        )
        availableFloors.firstOrNull { it.id == activeFloorId }?.let { active ->
            binding.floorDropdown.setText(active.name, false)
        }
    }

    private fun scheduleProjectSave() {
        if (!isProjectReady) return
        projectSaveJob?.cancel()
        persistCurrentProject(SAVE_DEBOUNCE_MILLIS)
    }

    private fun persistCurrentProject(delayMillis: Long = 0L) {
        val projectId = currentProjectId ?: return
        val floorId = currentFloorId ?: return
        val snapshot = createProjectSnapshot() ?: return
        projectSaveJob = lifecycleScope.launch {
            if (delayMillis > 0) delay(delayMillis)
            val result = withContext(Dispatchers.IO) {
                runCatching { projectRepository.saveProject(projectId, floorId, snapshot) }
            }
            result.onFailure { error ->
                Log.e("ProjectStorage", "Cannot save project", error)
            }
        }
    }

    private fun createProjectSnapshot(): ProjectSnapshot? {
        return viewModel.projectSnapshot()
    }

    private fun quantityString(@PluralsRes resource: Int, quantity: Int): String =
        resources.getQuantityString(resource, quantity, quantity)
    //</editor-fold>

    companion object {
        private const val NOTE_PREVIEW_MAX_SIZE_PX = 1_024
        const val EXTRA_PROJECT_ID = "project_id"
        private const val SAVE_DEBOUNCE_MILLIS = 750L
        private const val SCAN_RETRY_DELAY_MILLIS = 1_000L
        private const val STATE_LAST_TOUCH_X = "last_touch_x"
        private const val STATE_LAST_TOUCH_Y = "last_touch_y"
        private const val STATE_CURRENT_MAP_URI = "current_map_uri"
        private const val STATE_CURRENT_FLOOR_ID = "current_floor_id"
        private const val STATE_FLOOR_IDS = "floor_ids"
        private const val STATE_FLOOR_NAMES = "floor_names"
        private const val STATE_PROJECT_TITLE = "project_title"
    }

    private data class FloorOption(val id: String, val name: String)

}
