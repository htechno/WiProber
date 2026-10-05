package com.example.wiprober

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Settings/permission round trips belong to this screen, never to a pending survey action. */
internal class WifiMonitorAndroidCoordinator(
    private val activity: AppCompatActivity,
    private val source: WifiMonitorSource
) : DefaultLifecycleObserver {
    private var dialog: AlertDialog? = null
    init { activity.lifecycle.addObserver(this) }
    private var launchedRequirement: WifiScanAccessRequirement? = null
    private var openedDeveloperSettings = false
    private val preferences = activity.getSharedPreferences("wifi_monitor", 0)
    private val settings = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val resolved = if (openedDeveloperSettings) !source.throttled()
            else source.access() != launchedRequirement
        openedDeveloperSettings = false
        if (resolved) ensureAccess()
    }
    private val permissions = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (it[Manifest.permission.ACCESS_FINE_LOCATION] == true) ensureAccess()
    }

    fun ensureAccess() {
        if (source.throttled()) {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.mode_unavailable_title)
                .setMessage(R.string.monitor_throttling_required)
                .setPositiveButton(R.string.action_developer_settings) { _, _ ->
                    openedDeveloperSettings = true
                    launchSettings(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                }
                .setNegativeButton(R.string.action_cancel, null).showOwned()
            return
        }
        launchedRequirement = source.access()
        when (launchedRequirement) {
            WifiScanAccessRequirement.ENABLE_WIFI -> launchSettings(Intent(Settings.Panel.ACTION_WIFI))
            WifiScanAccessRequirement.ENABLE_LOCATION -> MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.location_required_title).setMessage(R.string.location_required_message)
                .setPositiveButton(R.string.action_enable) { _, _ ->
                    launchSettings(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }.setNegativeButton(R.string.action_cancel, null).showOwned()
            WifiScanAccessRequirement.GRANT_PERMISSIONS -> {
                val previouslyAsked = preferences.getBoolean("asked_location", false)
                if (previouslyAsked && !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION)) {
                    MaterialAlertDialogBuilder(activity).setTitle(R.string.monitor_permissions_title)
                        .setMessage(R.string.monitor_permissions_message)
                        .setPositiveButton(R.string.monitor_app_settings) { _, _ ->
                            launchSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${activity.packageName}")))
                        }.setNegativeButton(R.string.action_cancel, null).showOwned()
                } else {
                    MaterialAlertDialogBuilder(activity).setTitle(R.string.monitor_permissions_title)
                        .setMessage(R.string.monitor_permissions_message)
                        .setPositiveButton(R.string.action_enable) { _, _ ->
                            preferences.edit().putBoolean("asked_location", true).apply()
                            permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                        }.setNegativeButton(R.string.action_cancel, null).showOwned()
                }
            }
            else -> Unit
        }
    }

    private fun launchSettings(intent: Intent) {
        runCatching { settings.launch(intent) }.onFailure {
            MaterialAlertDialogBuilder(activity).setMessage(R.string.developer_settings_unavailable)
                .setPositiveButton(android.R.string.ok, null).showOwned()
        }
    }

    private fun MaterialAlertDialogBuilder.showOwned() {
        dialog?.dismiss()
        dialog = show()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        dialog?.dismiss()
        dialog = null
    }
}
