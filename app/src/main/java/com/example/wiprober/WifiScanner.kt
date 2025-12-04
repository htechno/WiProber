package com.example.wiprober

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.util.Log
import androidx.core.app.ActivityCompat
import android.Manifest
import android.os.Build

/**
 * Синглтон-объект, инкапсулирующий всю логику работы с Wi-Fi API Android.
 * Отвечает за инициализацию, запуск сканирования и получение результатов
 * через BroadcastReceiver.
 */
object WifiScanner {

    private lateinit var wifiManager: WifiManager
    private var onScanResults: ((List<ScanResult>) -> Unit)? = null
    private var onScanFailure: (() -> Unit)? = null

    fun isWifiEnabled(): Boolean {
        if (!this::wifiManager.isInitialized) {
            return false
        }
        return wifiManager.isWifiEnabled
    }

    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
            if (success) {
                if (ActivityCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    onScanResults?.invoke(wifiManager.scanResults)
                } else {
                    Log.e("WifiScanner", "Попытка получить результаты сканирования без разрешения!")
                    onScanFailure?.invoke()
                }
            } else {
                onScanFailure?.invoke()
            }
        }
    }

    /**
     * Инициализация сканера. Нужно вызвать один раз, например, в Application.onCreate или MainActivity.onCreate
     */
    fun initialize(context: Context) {
        wifiManager =
            context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val intentFilter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // TIRAMISU это API 33
            context.registerReceiver(
                wifiScanReceiver,
                intentFilter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(wifiScanReceiver, intentFilter)
        }
    }

    /**
     * Запускает сканирование.
     * @param onResults - функция, которая будет вызвана с результатами сканирования.
     * @param onFailure - функция, которая будет вызвана в случае ошибки.
     */
    fun startScan(
        onResults: (List<ScanResult>) -> Unit,
        onFailure: () -> Unit
    ) {
        this.onScanResults = onResults
        this.onScanFailure = onFailure

        Log.d("WifiScanner", "Принудительное отключение от текущей сети перед сканированием...")
        @Suppress("DEPRECATION")
        wifiManager.disconnect()
        try {
            Thread.sleep(300)
        } catch (_: InterruptedException) {
        }

        Log.d("WifiScanner", "Запрос на запуск сканирования...")
        @Suppress("DEPRECATION")
        val success = wifiManager.startScan()
        if (!success) {
            Log.e("WifiScanner", "Не удалось инициировать сканирование на уровне WifiManager.")
            onFailure()
        }
    }
}
