package com.example.wiprober

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Activity-result and Android system coordination for the survey screen.
 * It owns no survey action: the pending Stop-and-Go/Continuous request remains in the ViewModel.
 */
internal class SurveyAndroidCoordinator(
    private val activity: AppCompatActivity,
    private val wifiScanner: WifiScanner,
    private val callbacks: Callbacks
) {
    private var accessDialog: AlertDialog? = null

    private val selectNoteImageLauncher = activity.registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let(callbacks::onNoteImageSelected) }

    private val takePictureLauncher = activity.registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { callbacks.onPictureCaptured(it) }

    private val selectFloorMapLauncher = activity.registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { callbacks.onFloorMapSelected(it) }

    private val saveEsxLauncher = activity.registerForActivityResult(
        ActivityResultContracts.CreateDocument(ESX_MIME_TYPE)
    ) { callbacks.onExportDestinationSelected(it) }

    private val enableWifiLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (!finishStep(ScanAccessStep.WIFI_SETTINGS)) return@registerForActivityResult
        if (wifiScanner.isWifiEnabled()) ensureWifiScanAccess()
        else callbacks.onScanAccessRejected(ScanAccessRejection.WIFI_DISABLED)
    }

    private val enableLocationLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (!finishStep(ScanAccessStep.LOCATION_SETTINGS)) return@registerForActivityResult
        if (isLocationEnabled()) ensureWifiScanAccess()
        else callbacks.onScanAccessRejected(ScanAccessRejection.LOCATION_DISABLED)
    }

    private val permissionLauncher = activity.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (!finishStep(ScanAccessStep.PERMISSION_REQUEST)) return@registerForActivityResult
        // Re-read Android's current grants, rather than trusting a stale result map.
        if (requiredPermissions().all(::isGranted)) {
            ensureWifiScanAccess()
        } else {
            explainPrecisePermission()
        }
    }

    private val appSettingsLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (!finishStep(ScanAccessStep.APP_SETTINGS)) return@registerForActivityResult
        if (requiredPermissions().all(::isGranted)) ensureWifiScanAccess()
        else explainPrecisePermission()
    }

    fun selectNoteImage() = selectNoteImageLauncher.launch("image/*")

    fun takePicture(uri: Uri) = takePictureLauncher.launch(uri)

    fun selectFloorMap() = selectFloorMapLauncher.launch("image/*")

    fun createEsxDocument(suggestedName: String) = saveEsxLauncher.launch(suggestedName)

    fun ensureWifiScanAccess() {
        if (callbacks.pendingScanAccess()?.step != ScanAccessStep.CHECKING) return
        val missingPermissions = requiredPermissions().filterNot(::isGranted)
        val requirement = WifiScanAccessPolicy.requirement(
            WifiScanAccessSnapshot(
                wifiEnabled = wifiScanner.isWifiEnabled(),
                permissionsGranted = missingPermissions.isEmpty(),
                locationEnabled = isLocationEnabled()
            )
        )
        when (requirement) {
            WifiScanAccessRequirement.ENABLE_WIFI -> {
                if (move(ScanAccessStep.CHECKING, ScanAccessStep.WIFI_SETTINGS)) {
                    enableWifiLauncher.launch(Intent(Settings.Panel.ACTION_WIFI))
                }
            }

            WifiScanAccessRequirement.GRANT_PERMISSIONS -> {
                if (isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                    explainPrecisePermission()
                } else if (move(ScanAccessStep.CHECKING, ScanAccessStep.PERMISSION_REQUEST)) {
                    // Android 12+ requires requesting both together, including precision upgrades.
                    permissionLauncher.launch(arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ))
                }
            }

            WifiScanAccessRequirement.ENABLE_LOCATION -> {
                if (move(ScanAccessStep.CHECKING, ScanAccessStep.LOCATION_EXPLANATION)) {
                    showLocationRequirement()
                }
            }
            WifiScanAccessRequirement.READY -> callbacks.onScanAccessReady()
        }
    }

    /** Restore our dialogs, but never re-launch a pending system permission/settings screen. */
    fun resumePendingScanAccess() {
        when (callbacks.pendingScanAccess()?.step) {
            ScanAccessStep.CHECKING -> ensureWifiScanAccess()
            ScanAccessStep.PERMISSION_EXPLANATION -> showPrecisePermissionRequirement()
            ScanAccessStep.LOCATION_EXPLANATION -> showLocationRequirement()
            else -> Unit
        }
    }

    fun close() {
        accessDialog?.setOnCancelListener(null)
        accessDialog?.dismiss()
        accessDialog = null
    }

    private fun move(expected: ScanAccessStep, next: ScanAccessStep): Boolean =
        callbacks.transitionScanAccess(expected, next)

    private fun finishStep(step: ScanAccessStep): Boolean = move(step, ScanAccessStep.CHECKING)

    private fun explainPrecisePermission() {
        if (move(ScanAccessStep.CHECKING, ScanAccessStep.PERMISSION_EXPLANATION)) {
            showPrecisePermissionRequirement()
        }
    }

    private fun showPrecisePermissionRequirement() {
        if (accessDialog?.isShowing == true) return
        accessDialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.precise_location_required_title)
            .setMessage(R.string.precise_location_required_message)
            .setPositiveButton(R.string.open_app_settings) { _, _ ->
                if (move(ScanAccessStep.PERMISSION_EXPLANATION, ScanAccessStep.APP_SETTINGS)) {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", activity.packageName, null))
                    runCatching { appSettingsLauncher.launch(intent) }.onFailure {
                        callbacks.onScanAccessRejected(ScanAccessRejection.SETTINGS_UNAVAILABLE)
                    }
                }
            }
            .setNegativeButton(R.string.action_cancel) { _, _ ->
                callbacks.onScanAccessRejected(ScanAccessRejection.PERMISSION_DENIED)
            }
            .setOnCancelListener {
                callbacks.onScanAccessRejected(ScanAccessRejection.PERMISSION_DENIED)
            }
            .show()
    }

    fun isScanThrottlingEnabled(): Boolean {
        val wifiManager = activity.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return true
        return wifiManager.isScanThrottleEnabled
    }

    fun openDeveloperSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        runCatching { activity.startActivity(intent) }
            .recoverCatching { activity.startActivity(Intent(Settings.ACTION_SETTINGS)) }
            .onFailure { callbacks.onDeveloperSettingsUnavailable() }
    }

    private fun showLocationRequirement() {
        if (accessDialog?.isShowing == true) return
        accessDialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.location_required_title)
            .setMessage(R.string.location_required_message)
            .setPositiveButton(R.string.action_enable) { _, _ ->
                if (move(ScanAccessStep.LOCATION_EXPLANATION, ScanAccessStep.LOCATION_SETTINGS)) {
                    enableLocationLauncher.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
            }
            .setNegativeButton(R.string.action_cancel) { _, _ ->
                callbacks.onScanAccessRejected(ScanAccessRejection.LOCATION_DISABLED)
            }
            .setOnCancelListener {
                callbacks.onScanAccessRejected(ScanAccessRejection.LOCATION_DISABLED)
            }
            .show()
    }

    private fun requiredPermissions(): List<String> = listOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.CHANGE_WIFI_STATE,
        Manifest.permission.ACCESS_WIFI_STATE
    )

    private fun isGranted(permission: String): Boolean =
        ActivityCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED

    private fun isLocationEnabled(): Boolean =
        (activity.getSystemService(Context.LOCATION_SERVICE) as LocationManager).isLocationEnabled

    interface Callbacks {
        fun onNoteImageSelected(uri: Uri)
        fun onPictureCaptured(success: Boolean)
        fun onFloorMapSelected(uri: Uri?)
        fun onExportDestinationSelected(uri: Uri?)
        fun pendingScanAccess(): SurveyInteraction.AwaitingScanAccess?
        fun transitionScanAccess(expected: ScanAccessStep, next: ScanAccessStep): Boolean
        fun onScanAccessReady()
        fun onScanAccessRejected(reason: ScanAccessRejection)
        fun onDeveloperSettingsUnavailable()
    }

    companion object {
        private const val ESX_MIME_TYPE = "application/vnd.ekahau.esx"
    }
}

internal enum class ScanAccessRejection {
    WIFI_DISABLED,
    LOCATION_DISABLED,
    PERMISSION_DENIED,
    SETTINGS_UNAVAILABLE
}
