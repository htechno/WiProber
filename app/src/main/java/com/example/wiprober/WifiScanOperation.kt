package com.example.wiprober

/** Android-free ownership and freshness state for one platform Wi-Fi scan request. */
internal class WifiScanOperationTracker(
    private val emptyResultMinimumAgeMicros: Long = 750_000L
) {
    private var nextOperationId = 1L
    private var active: ActiveOperation? = null

    fun begin(baselineNewestResultMicros: Long?): Long? {
        if (active != null) return null
        val operationId = nextOperationId++
        active = ActiveOperation(
            id = operationId,
            baselineNewestResultMicros = baselineNewestResultMicros
        )
        return operationId
    }

    fun markSystemScanRequested(operationId: Long, requestedAtMicros: Long): Boolean {
        val operation = active ?: return false
        if (operation.id != operationId || operation.requestedAtMicros != null) return false
        active = operation.copy(requestedAtMicros = requestedAtMicros)
        return true
    }

    fun acceptSuccessfulBroadcast(
        newestResultMicros: Long?,
        receivedAtMicros: Long
    ): WifiScanBroadcastDecision {
        val operation = active ?: return WifiScanBroadcastDecision.Ignored
        val requestedAt = operation.requestedAtMicros ?: return WifiScanBroadcastDecision.Ignored
        if (newestResultMicros == null) {
            return if (receivedAtMicros - requestedAt >= emptyResultMinimumAgeMicros) {
                WifiScanBroadcastDecision.Accepted(operation.id)
            } else {
                WifiScanBroadcastDecision.Ignored
            }
        }

        val isNewerThanBaseline = operation.baselineNewestResultMicros?.let {
            newestResultMicros > it
        } ?: true
        val isFromCurrentWindow = newestResultMicros >= requestedAt
        return if (isNewerThanBaseline && isFromCurrentWindow) {
            WifiScanBroadcastDecision.Accepted(operation.id)
        } else {
            WifiScanBroadcastDecision.Ignored
        }
    }

    fun activeOperationId(): Long? = active?.id

    fun isSystemScanRequested(operationId: Long): Boolean =
        active?.let { it.id == operationId && it.requestedAtMicros != null } == true

    fun finish(operationId: Long): Boolean {
        if (active?.id != operationId) return false
        active = null
        return true
    }

    private data class ActiveOperation(
        val id: Long,
        val baselineNewestResultMicros: Long?,
        val requestedAtMicros: Long? = null
    )
}

internal sealed interface WifiScanBroadcastDecision {
    data object Ignored : WifiScanBroadcastDecision
    data class Accepted(val operationId: Long) : WifiScanBroadcastDecision
}

internal data class WifiScanAccessSnapshot(
    val wifiEnabled: Boolean,
    val permissionsGranted: Boolean,
    val locationEnabled: Boolean
)

internal enum class WifiScanAccessRequirement {
    READY,
    ENABLE_WIFI,
    GRANT_PERMISSIONS,
    ENABLE_LOCATION
}

internal object WifiScanAccessPolicy {
    fun requirement(snapshot: WifiScanAccessSnapshot): WifiScanAccessRequirement = when {
        !snapshot.wifiEnabled -> WifiScanAccessRequirement.ENABLE_WIFI
        !snapshot.permissionsGranted -> WifiScanAccessRequirement.GRANT_PERMISSIONS
        !snapshot.locationEnabled -> WifiScanAccessRequirement.ENABLE_LOCATION
        else -> WifiScanAccessRequirement.READY
    }
}
