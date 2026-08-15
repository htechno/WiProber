package com.example.wiprober

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine

internal enum class WifiScanKind {
    STOP_AND_GO,
    CONTINUOUS
}

internal data class WifiScanRequest(val kind: WifiScanKind)

internal enum class WifiScanFailureReason {
    SCANNER_NOT_STARTED,
    SCANNER_CLOSED,
    ALREADY_IN_PROGRESS,
    WIFI_DISABLED,
    PERMISSION_DENIED,
    START_REJECTED,
    RESULTS_NOT_UPDATED,
    SCAN_UNAVAILABLE,
    TIMEOUT,
    INTERNAL_ERROR
}

internal enum class WifiScanCancellationReason {
    LIFECYCLE_STOPPED,
    SCANNER_CLOSED
}

internal sealed interface WifiScanOutcome {
    val operationId: Long?

    data class Success(
        override val operationId: Long,
        val results: List<ScanResult>
    ) : WifiScanOutcome

    data class Failure(
        override val operationId: Long?,
        val reason: WifiScanFailureReason
    ) : WifiScanOutcome

    data class Cancelled(
        override val operationId: Long,
        val reason: WifiScanCancellationReason
    ) : WifiScanOutcome
}

/**
 * Owns one Activity-scoped Wi-Fi scan at a time.
 *
 * Android 10+ scan broadcasts may describe scans started by the platform or another app.
 * The operation tracker therefore accepts only a result set that is newer than the one
 * captured before this request and whose newest timestamp belongs to this request window.
 */
internal class WifiScanner(
    context: Context,
    lifecycle: Lifecycle? = null,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val elapsedRealtimeMicros: () -> Long = {
        SystemClock.elapsedRealtimeNanos() / NANOS_PER_MICROSECOND
    }
) : DefaultLifecycleObserver {

    private val applicationContext = context.applicationContext
    private val wifiManager =
        applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val lock = Any()
    private val operationTracker = WifiScanOperationTracker()
    private var activeContinuation: CancellableContinuation<WifiScanOutcome>? = null
    private var activeRequestRunnable: Runnable? = null
    private var activeTimeoutRunnable: Runnable? = null
    private var receiverRegistered = false
    private var closed = false
    private var lifecycleStopped = false

    init {
        lifecycle?.addObserver(this)
    }

    fun isWifiEnabled(): Boolean = wifiManager.isWifiEnabled

    override fun onStart(owner: LifecycleOwner) {
        start()
    }

    override fun onStop(owner: LifecycleOwner) {
        stop(WifiScanCancellationReason.LIFECYCLE_STOPPED)
    }

    override fun onDestroy(owner: LifecycleOwner) {
        close()
    }

    fun start() {
        synchronized(lock) {
            if (closed || receiverRegistered) return
            val intentFilter = IntentFilter().apply {
                addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    addAction(WifiManager.ACTION_WIFI_SCAN_AVAILABILITY_CHANGED)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                applicationContext.registerReceiver(
                    wifiScanReceiver,
                    intentFilter,
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("DEPRECATION")
                applicationContext.registerReceiver(wifiScanReceiver, intentFilter)
            }
            receiverRegistered = true
            lifecycleStopped = false
        }
    }

    fun stop(
        cancellationReason: WifiScanCancellationReason =
            WifiScanCancellationReason.LIFECYCLE_STOPPED
    ) {
        val completion = synchronized(lock) {
            lifecycleStopped = true
            val current = operationTracker.activeOperationId()?.let { operationId ->
                prepareCompletionLocked(
                    operationId,
                    WifiScanOutcome.Cancelled(operationId, cancellationReason)
                )
            }
            unregisterReceiverLocked()
            current
        }
        completion?.resume()
    }

    fun close() {
        val completion = synchronized(lock) {
            if (closed) return
            closed = true
            val current = operationTracker.activeOperationId()?.let { operationId ->
                prepareCompletionLocked(
                    operationId,
                    WifiScanOutcome.Cancelled(
                        operationId,
                        WifiScanCancellationReason.SCANNER_CLOSED
                    )
                )
            }
            unregisterReceiverLocked()
            current
        }
        completion?.resume()
    }

    suspend fun scan(request: WifiScanRequest): WifiScanOutcome =
        suspendCancellableCoroutine { continuation ->
            val immediateOutcome = synchronized(lock) {
                when {
                    closed -> WifiScanOutcome.Failure(
                        operationId = null,
                        reason = WifiScanFailureReason.SCANNER_CLOSED
                    )

                    !receiverRegistered || lifecycleStopped -> WifiScanOutcome.Failure(
                        operationId = null,
                        reason = WifiScanFailureReason.SCANNER_NOT_STARTED
                    )

                    operationTracker.activeOperationId() != null -> WifiScanOutcome.Failure(
                        operationId = null,
                        reason = WifiScanFailureReason.ALREADY_IN_PROGRESS
                    )

                    !wifiManager.isWifiEnabled -> WifiScanOutcome.Failure(
                        operationId = null,
                        reason = WifiScanFailureReason.WIFI_DISABLED
                    )

                    !hasScanPermission() -> WifiScanOutcome.Failure(
                        operationId = null,
                        reason = WifiScanFailureReason.PERMISSION_DENIED
                    )

                    else -> {
                        try {
                            val baselineNewestResultMicros = readScanResults()
                                .maxOfOrNull { it.timestamp }
                            val operationId = operationTracker.begin(baselineNewestResultMicros)
                            if (operationId == null) {
                                WifiScanOutcome.Failure(
                                    operationId = null,
                                    reason = WifiScanFailureReason.ALREADY_IN_PROGRESS
                                )
                            } else {
                                activeContinuation = continuation
                                scheduleScanRequestLocked(operationId, request)
                                null
                            }
                        } catch (securityException: SecurityException) {
                            WifiScanOutcome.Failure(
                                operationId = null,
                                reason = WifiScanFailureReason.PERMISSION_DENIED
                            )
                        } catch (runtimeException: RuntimeException) {
                            Log.w(TAG, "Cannot read the pre-scan result baseline", runtimeException)
                            WifiScanOutcome.Failure(
                                operationId = null,
                                reason = WifiScanFailureReason.INTERNAL_ERROR
                            )
                        }
                    }
                }
            }

            if (immediateOutcome != null) {
                continuation.resume(immediateOutcome)
                return@suspendCancellableCoroutine
            }

            continuation.invokeOnCancellation {
                val operationId = synchronized(lock) {
                    val current = operationTracker.activeOperationId() ?: return@synchronized null
                    clearActiveOperationLocked(current)
                    current
                }
                if (operationId != null) {
                    Log.d(TAG, "Wi-Fi scan $operationId cancelled by caller")
                }
            }
        }

    private fun scheduleScanRequestLocked(operationId: Long, request: WifiScanRequest) {
        val requestRunnable = Runnable { requestSystemScan(operationId) }
        activeRequestRunnable = requestRunnable
        val shouldDisconnect =
            request.kind == WifiScanKind.STOP_AND_GO &&
                Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
        if (shouldDisconnect) {
            @Suppress("DEPRECATION")
            wifiManager.disconnect()
            mainHandler.postDelayed(requestRunnable, DISCONNECT_DELAY_MILLIS)
        } else {
            mainHandler.post(requestRunnable)
        }
    }

    private fun requestSystemScan(operationId: Long) {
        val failure = synchronized(lock) {
            if (operationTracker.activeOperationId() != operationId) return
            activeRequestRunnable = null
            if (!receiverRegistered || lifecycleStopped) {
                return@synchronized WifiScanFailureReason.SCANNER_NOT_STARTED
            }
            if (!wifiManager.isWifiEnabled) {
                return@synchronized WifiScanFailureReason.WIFI_DISABLED
            }
            if (!hasScanPermission()) {
                return@synchronized WifiScanFailureReason.PERMISSION_DENIED
            }

            val requestedAtMicros = elapsedRealtimeMicros()
            if (!operationTracker.markSystemScanRequested(operationId, requestedAtMicros)) return
            val started = try {
                @Suppress("DEPRECATION")
                wifiManager.startScan()
            } catch (securityException: SecurityException) {
                Log.w(TAG, "Wi-Fi scan permission changed while requesting a scan", securityException)
                return@synchronized WifiScanFailureReason.PERMISSION_DENIED
            } catch (runtimeException: RuntimeException) {
                Log.w(TAG, "WifiManager.startScan failed", runtimeException)
                return@synchronized WifiScanFailureReason.INTERNAL_ERROR
            }
            if (!started) {
                return@synchronized WifiScanFailureReason.START_REJECTED
            }

            val timeoutRunnable = Runnable {
                complete(
                    operationId,
                    WifiScanOutcome.Failure(operationId, WifiScanFailureReason.TIMEOUT)
                )
            }
            activeTimeoutRunnable = timeoutRunnable
            mainHandler.postDelayed(timeoutRunnable, SCAN_TIMEOUT_MILLIS)
            null
        }

        if (failure != null) {
            complete(operationId, WifiScanOutcome.Failure(operationId, failure))
        }
    }

    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                handleScanResultsBroadcast(intent)
            } else if (intent.action == WifiManager.WIFI_STATE_CHANGED_ACTION) {
                if (!wifiManager.isWifiEnabled) {
                    failActiveOperation(WifiScanFailureReason.WIFI_DISABLED)
                }
            } else if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                intent.action == WifiManager.ACTION_WIFI_SCAN_AVAILABILITY_CHANGED
            ) {
                val scanAvailable = intent.getBooleanExtra(
                    WifiManager.EXTRA_SCAN_AVAILABLE,
                    true
                )
                if (!scanAvailable) {
                    failActiveOperation(WifiScanFailureReason.SCAN_UNAVAILABLE)
                }
            }
        }
    }

    private fun handleScanResultsBroadcast(intent: Intent) {
        val resultsUpdated = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
        if (!resultsUpdated) {
            when (val decision = synchronized(lock) {
                operationTracker.acceptFailedBroadcast(sdkInt)
            }) {
                WifiScanBroadcastDecision.Ignored -> Unit
                is WifiScanBroadcastDecision.Accepted -> complete(
                    decision.operationId,
                    WifiScanOutcome.Failure(
                        decision.operationId,
                        WifiScanFailureReason.RESULTS_NOT_UPDATED
                    )
                )
            }
            return
        }

        if (!hasScanPermission()) {
            failActiveOperation(WifiScanFailureReason.PERMISSION_DENIED)
            return
        }
        val results = try {
            readScanResults()
        } catch (securityException: SecurityException) {
            Log.w(TAG, "Cannot read Wi-Fi results", securityException)
            failActiveOperation(WifiScanFailureReason.PERMISSION_DENIED)
            return
        } catch (runtimeException: RuntimeException) {
            Log.w(TAG, "Cannot read Wi-Fi results", runtimeException)
            failActiveOperation(WifiScanFailureReason.INTERNAL_ERROR)
            return
        }
        val newestResultMicros = results.maxOfOrNull { it.timestamp }
        when (val decision = synchronized(lock) {
            operationTracker.acceptSuccessfulBroadcast(
                newestResultMicros = newestResultMicros,
                receivedAtMicros = elapsedRealtimeMicros()
            )
        }) {
            WifiScanBroadcastDecision.Ignored -> Unit
            is WifiScanBroadcastDecision.Accepted -> complete(
                decision.operationId,
                WifiScanOutcome.Success(decision.operationId, results)
            )
        }
    }

    private fun failActiveOperation(reason: WifiScanFailureReason) {
        val operationId = synchronized(lock) { operationTracker.activeOperationId() } ?: return
        complete(operationId, WifiScanOutcome.Failure(operationId, reason))
    }

    private fun complete(operationId: Long, outcome: WifiScanOutcome) {
        val completion = synchronized(lock) {
            prepareCompletionLocked(operationId, outcome)
        } ?: return
        completion.resume()
    }

    private fun prepareCompletionLocked(
        operationId: Long,
        outcome: WifiScanOutcome
    ): PendingCompletion? {
        if (!operationTracker.finish(operationId)) return null
        activeRequestRunnable?.let(mainHandler::removeCallbacks)
        activeTimeoutRunnable?.let(mainHandler::removeCallbacks)
        activeRequestRunnable = null
        activeTimeoutRunnable = null
        val continuation = activeContinuation
        activeContinuation = null
        return if (continuation?.isActive == true) {
            PendingCompletion(continuation, outcome)
        } else {
            null
        }
    }

    private fun clearActiveOperationLocked(operationId: Long) {
        if (!operationTracker.finish(operationId)) return
        activeRequestRunnable?.let(mainHandler::removeCallbacks)
        activeTimeoutRunnable?.let(mainHandler::removeCallbacks)
        activeRequestRunnable = null
        activeTimeoutRunnable = null
        activeContinuation = null
    }

    private fun unregisterReceiverLocked() {
        if (!receiverRegistered) return
        runCatching { applicationContext.unregisterReceiver(wifiScanReceiver) }
            .onFailure { Log.w(TAG, "Cannot unregister Wi-Fi receiver", it) }
        receiverRegistered = false
    }

    @SuppressLint("MissingPermission")
    private fun readScanResults(): List<ScanResult> {
        if (!hasScanPermission()) throw SecurityException("Wi-Fi scan permission is missing")
        return wifiManager.scanResults.orEmpty().toList()
    }

    private fun hasScanPermission(): Boolean =
        ActivityCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    private data class PendingCompletion(
        val continuation: CancellableContinuation<WifiScanOutcome>,
        val outcome: WifiScanOutcome
    ) {
        fun resume() {
            if (continuation.isActive) continuation.resume(outcome)
        }
    }

    private companion object {
        const val TAG = "WifiScanner"
        const val DISCONNECT_DELAY_MILLIS = 300L
        const val SCAN_TIMEOUT_MILLIS = 15_000L
        const val NANOS_PER_MICROSECOND = 1_000L
    }
}
