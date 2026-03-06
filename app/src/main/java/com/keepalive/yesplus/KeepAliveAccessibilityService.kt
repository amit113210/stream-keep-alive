package com.keepalive.yesplus

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.PointF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Accessibility service for Android TV keep-alive and still-watching dialog dismissal.
 *
 * Layer A: dialog detection/dismissal.
 * Layer B: periodic package-aware heartbeat.
 */
class KeepAliveAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "StreamKeepAlive"

        private const val PREFS_NAME = "stream_keep_alive_prefs"
        private const val PREF_SERVICE_MODE = "service_mode"

        private const val DIALOG_SCAN_COOLDOWN_MS = 2500L
        private const val DIALOG_HINT_COOLDOWN_MS = 700L
        private const val DIALOG_PERIODIC_SCAN_MS = 20_000L
        private const val PACKAGE_PROBE_INTERVAL_MS = 10_000L
        private const val DIALOG_POST_DISMISS_HEARTBEAT_COOLDOWN_MS = 6000L
        private const val PACKAGE_TRANSITION_HEARTBEAT_COOLDOWN_MS = 4000L
        private const val WINDOW_FINGERPRINT_COOLDOWN_MS = 3000L

        private const val MAX_SCAN_NODES = 220
        private const val MAX_SCAN_DEPTH = 14
        private const val SCAN_TIME_BUDGET_MS = 16L

        private const val MIN_HEARTBEAT_DELAY_MS = 45_000L

        // Partial wake lock can help service continuity on aggressive OEM devices,
        // but the real keep-alive mechanism is periodic gestures, not wake locks.
        private const val USE_PARTIAL_WAKE_LOCK = true
        private const val WAKELOCK_SAFETY_TIMEOUT_MS = 90L * 60L * 1000L

        @Volatile
        var isRunning = false
            private set

        data class TelemetrySnapshot(
            val activePackage: String = "",
            val activeProfilePrefix: String = "",
            val serviceMode: String = ServiceMode.NORMAL.name,
            val currentFixedIntervalMs: Long = 0L,
            val lastHeartbeatTimestampMs: Long = 0L,
            val lastHeartbeatPackage: String = "",
            val lastGestureSuccessTimestampMs: Long = 0L,
            val lastGestureFailureTimestampMs: Long = 0L,
            val lastGestureAction: String = "",
            val gesturesDispatched: Long = 0L,
            val gesturesCompleted: Long = 0L,
            val gesturesCancelled: Long = 0L,
            val gesturesDispatchRejected: Long = 0L,
            val dialogScans: Long = 0L,
            val dialogsDetected: Long = 0L,
            val dialogsDismissed: Long = 0L,
            val lastDialogDismissStrategy: String = "",
            val lastPackageTransitionTimestampMs: Long = 0L,
            val wakeAcquires: Long = 0L,
            val wakeReleases: Long = 0L
        )

        @Volatile
        private var telemetrySnapshot = TelemetrySnapshot()

        fun getTelemetrySnapshot(): TelemetrySnapshot = telemetrySnapshot
    }

    private data class ScanBudget(
        val deadlineMs: Long,
        var visitedNodes: Int = 0
    ) {
        fun canVisit(depth: Int): Boolean {
            if (depth > MAX_SCAN_DEPTH) return false
            if (visitedNodes >= MAX_SCAN_NODES) return false
            if (System.currentTimeMillis() > deadlineMs) return false
            visitedNodes++
            return true
        }
    }

    private var handler: Handler? = null
    private var heartbeatRunnable: Runnable? = null
    private var dialogScanRunnable: Runnable? = null
    private var packageProbeRunnable: Runnable? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockSafetyRunnable: Runnable? = null

    private var currentActivePackage = ""
    private var currentProfile: StreamingAppProfile? = null
    private var useSwipeOnHybrid = false

    private var lastDialogCheckTimeMs = 0L
    private var lastDialogWindowFingerprint = ""
    private var lastDialogWindowFingerprintMs = 0L
    private var lastDialogDismissTimestampMs = 0L
    private var lastPackageTransitionTimestampMs = 0L

    private var wakeLockLastTouchedAt = 0L
    private var wakeLockHeldSince = 0L
    private var wakeLockAcquireCount = 0L
    private var wakeLockReleaseCount = 0L

    private var dialogScanCount = 0L
    private var dialogDetectedCount = 0L
    private var dialogDismissedCount = 0L
    private var lastDialogDismissStrategy = ""

    private var gesturesDispatchedCount = 0L
    private var gesturesCompletedCount = 0L
    private var gesturesCancelledCount = 0L
    private var gesturesDispatchRejectedCount = 0L
    private var lastHeartbeatTimestampMs = 0L
    private var lastHeartbeatPackage = ""
    private var lastGestureSuccessTimestampMs = 0L
    private var lastGestureFailureTimestampMs = 0L
    private var lastGestureAction = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        handler = Handler(Looper.getMainLooper())
        Log.i(TAG, "[WAKE] Service connected")
        startPackageProbe()
        publishTelemetrySnapshot()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return
        val eventType = event.eventType

        if (shouldHandlePackageTransition(packageName, eventType)) {
            onPackageChanged(packageName)
        }

        maybeProcessDialog(event, packageName)
    }

    override fun onInterrupt() {
        Log.w(TAG, "[WAKE] Service interrupted")
        cleanupRuntimeResources(keepHandler = true, clearPackageState = true)
    }

    override fun onDestroy() {
        isRunning = false
        cleanupRuntimeResources(keepHandler = false, clearPackageState = true)
        handler = null
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isRunning = false
        cleanupRuntimeResources(keepHandler = false, clearPackageState = true)
        handler = null
        return super.onUnbind(intent)
    }

    private fun shouldHandlePackageTransition(packageName: String, eventType: Int): Boolean {
        if (packageName == currentActivePackage) return false
        if (currentActivePackage.isEmpty()) return true
        return eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
    }

    private fun onPackageChanged(newPackage: String) {
        currentActivePackage = newPackage
        lastPackageTransitionTimestampMs = System.currentTimeMillis()
        val profile = PackagePolicy.profileForPackage(newPackage)
        currentProfile = profile
        useSwipeOnHybrid = false

        Log.i(
            TAG,
            "[PKG] transition package=$newPackage streaming=${profile != null} ignored=${PackagePolicy.isIgnoredPackage(newPackage)}"
        )

        if (profile == null || PackagePolicy.isIgnoredPackage(newPackage)) {
            stopHeartbeat()
            stopPeriodicDialogScan()
            releaseWakeLock()
            publishTelemetrySnapshot()
            return
        }

        if (USE_PARTIAL_WAKE_LOCK) {
            acquireWakeLock()
            touchWakeLockSafetyTimer()
        }

        startPeriodicDialogScan()
        startHeartbeat(immediate = true)
        publishTelemetrySnapshot()
    }

    private fun startPackageProbe() {
        stopPackageProbe()
        val localHandler = handler ?: return

        packageProbeRunnable = object : Runnable {
            override fun run() {
                probePackageFromActiveWindow()
                localHandler.postDelayed(this, PACKAGE_PROBE_INTERVAL_MS)
            }
        }
        localHandler.postDelayed(packageProbeRunnable!!, 1000L)
    }

    private fun stopPackageProbe() {
        packageProbeRunnable?.let { handler?.removeCallbacks(it) }
        packageProbeRunnable = null
    }

    private fun probePackageFromActiveWindow() {
        val rootNode = rootInActiveWindow ?: return
        try {
            val observedPackage = rootNode.packageName?.toString() ?: return
            if (observedPackage != currentActivePackage) {
                Log.d(TAG, "[PKG] probe observed package=$observedPackage")
                onPackageChanged(observedPackage)
            }
        } finally {
            safeRecycle(rootNode)
        }
    }

    private fun isSupportedActivePackage(): Boolean {
        val profile = currentProfile ?: return false
        return currentActivePackage.startsWith(profile.packagePrefix) &&
            PackagePolicy.isStreamingPackage(currentActivePackage)
    }

    private fun currentProfileOrNull(): StreamingAppProfile? {
        if (!isSupportedActivePackage()) return null
        return currentProfile
    }

    private fun currentMode(): ServiceMode {
        val value = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_SERVICE_MODE, ServiceMode.NORMAL.name)
            ?: ServiceMode.NORMAL.name
        return ServiceMode.entries.firstOrNull { it.name == value } ?: ServiceMode.NORMAL
    }

    // ==========================================
    // Layer A: Dialog Detection & Auto-Dismiss
    // ==========================================

    private fun maybeProcessDialog(event: AccessibilityEvent, packageName: String) {
        if (!PackagePolicy.isStreamingPackage(packageName)) return
        if (!isDialogRelevantEvent(event.eventType)) return

        val profile = currentProfileOrNull() ?: return
        val now = System.currentTimeMillis()

        val quickDialogHint = DialogTextMatcher.eventLooksLikeDialog(
            className = event.className,
            eventText = event.text,
            contentDescription = event.contentDescription,
            additionalKeywords = profile.dialogKeywords
        )
        val cooldown = if (quickDialogHint) DIALOG_HINT_COOLDOWN_MS else DIALOG_SCAN_COOLDOWN_MS
        if (now - lastDialogCheckTimeMs < cooldown) return

        scanActiveWindowForDialog(reason = "event:${event.eventType}")
    }

    private fun isDialogRelevantEvent(eventType: Int): Boolean {
        return eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            eventType == AccessibilityEvent.TYPE_VIEW_CLICKED
    }

    private fun startPeriodicDialogScan() {
        stopPeriodicDialogScan()
        val localHandler = handler ?: return

        dialogScanRunnable = object : Runnable {
            override fun run() {
                if (isSupportedActivePackage()) {
                    scanActiveWindowForDialog(reason = "periodic")
                    localHandler.postDelayed(this, DIALOG_PERIODIC_SCAN_MS)
                }
            }
        }

        localHandler.postDelayed(dialogScanRunnable!!, DIALOG_PERIODIC_SCAN_MS)
    }

    private fun stopPeriodicDialogScan() {
        dialogScanRunnable?.let { handler?.removeCallbacks(it) }
        dialogScanRunnable = null
    }

    private fun scanActiveWindowForDialog(reason: String) {
        val profile = currentProfileOrNull() ?: return
        val rootNode = rootInActiveWindow ?: return
        val now = System.currentTimeMillis()

        if (shouldSkipDialogScanByFingerprint(rootNode, now)) {
            safeRecycle(rootNode)
            return
        }

        lastDialogCheckTimeMs = now
        dialogScanCount++
        Log.d(TAG, "[DIALOG] scan reason=$reason package=$currentActivePackage")

        val detected = containsDialogKeywords(rootNode, profile)
        if (!detected) {
            safeRecycle(rootNode)
            publishTelemetrySnapshot()
            return
        }

        dialogDetectedCount++
        Log.i(TAG, "[DIALOG] dialog detected package=$currentActivePackage")

        val dismissStrategy = dismissDialog(rootNode, profile)
        if (dismissStrategy != null) {
            dialogDismissedCount++
            lastDialogDismissTimestampMs = System.currentTimeMillis()
            lastDialogDismissStrategy = dismissStrategy
            Log.i(TAG, "[DIALOG] dismissed strategy=$dismissStrategy")
        } else {
            Log.w(TAG, "[DIALOG] detected but no dismiss action succeeded")
        }

        publishTelemetrySnapshot()
        safeRecycle(rootNode)
    }

    private fun dismissDialog(rootNode: AccessibilityNodeInfo, profile: StreamingAppProfile): String? {
        if (findAndClickConfirmButton(rootNode, profile)) return "confirm-keyword"
        if (clickFocusedElement(rootNode)) return "focused-element"
        if (clickFirstClickable(rootNode)) return "clickable-fallback"

        return if (performGlobalAction(GLOBAL_ACTION_BACK)) {
            "global-back"
        } else {
            null
        }
    }

    private fun shouldSkipDialogScanByFingerprint(rootNode: AccessibilityNodeInfo, now: Long): Boolean {
        val fingerprint = buildWindowFingerprint(rootNode)
        val isSameWindow = fingerprint == lastDialogWindowFingerprint
        val isCooldownActive = now - lastDialogWindowFingerprintMs < WINDOW_FINGERPRINT_COOLDOWN_MS
        if (isSameWindow && isCooldownActive) {
            return true
        }
        lastDialogWindowFingerprint = fingerprint
        lastDialogWindowFingerprintMs = now
        return false
    }

    private fun buildWindowFingerprint(rootNode: AccessibilityNodeInfo): String {
        val pkg = rootNode.packageName?.toString().orEmpty()
        val cls = rootNode.className?.toString().orEmpty()
        val childCount = rootNode.childCount
        val rootText = rootNode.text?.toString()?.take(40).orEmpty()
        val desc = rootNode.contentDescription?.toString()?.take(40).orEmpty()
        return "$pkg|$cls|$childCount|$rootText|$desc"
    }

    private fun containsDialogKeywords(node: AccessibilityNodeInfo?, profile: StreamingAppProfile): Boolean {
        val budget = ScanBudget(deadlineMs = System.currentTimeMillis() + SCAN_TIME_BUDGET_MS)
        return containsDialogKeywords(node, depth = 0, budget = budget, profile = profile)
    }

    private fun containsDialogKeywords(
        node: AccessibilityNodeInfo?,
        depth: Int,
        budget: ScanBudget,
        profile: StreamingAppProfile
    ): Boolean {
        if (node == null || !budget.canVisit(depth)) return false

        try {
            if (DialogTextMatcher.containsDialogKeyword(node.text, profile.dialogKeywords)) return true
            if (DialogTextMatcher.containsDialogKeyword(node.contentDescription, profile.dialogKeywords)) return true

            for (i in 0 until node.childCount) {
                val child = try {
                    node.getChild(i)
                } catch (_: Exception) {
                    null
                }
                if (child != null) {
                    val found = containsDialogKeywords(child, depth + 1, budget, profile)
                    safeRecycle(child)
                    if (found) return true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[DIALOG] contains scan error: ${e.message}")
        }

        return false
    }

    private fun findAndClickConfirmButton(node: AccessibilityNodeInfo?, profile: StreamingAppProfile): Boolean {
        val budget = ScanBudget(deadlineMs = System.currentTimeMillis() + SCAN_TIME_BUDGET_MS)
        return findAndClickConfirmButton(node, depth = 0, budget = budget, profile = profile)
    }

    private fun findAndClickConfirmButton(
        node: AccessibilityNodeInfo?,
        depth: Int,
        budget: ScanBudget,
        profile: StreamingAppProfile
    ): Boolean {
        if (node == null || !budget.canVisit(depth)) return false

        try {
            if (
                node.isClickable &&
                DialogTextMatcher.containsConfirmKeyword(
                    text = node.text,
                    contentDesc = node.contentDescription,
                    additionalKeywords = profile.confirmKeywords
                ) &&
                !DialogTextMatcher.containsNegativeKeyword(node.text, node.contentDescription)
            ) {
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }

            for (i in 0 until node.childCount) {
                val child = try {
                    node.getChild(i)
                } catch (_: Exception) {
                    null
                }
                if (child != null) {
                    val found = findAndClickConfirmButton(child, depth + 1, budget, profile)
                    safeRecycle(child)
                    if (found) return true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[DIALOG] confirm click error: ${e.message}")
        }

        return false
    }

    private fun clickFocusedElement(rootNode: AccessibilityNodeInfo?): Boolean {
        if (rootNode == null) return false

        val toRecycle = mutableListOf<AccessibilityNodeInfo>()
        try {
            val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: rootNode.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
                ?: return false
            toRecycle.add(focusedNode)

            if (!isNegativeActionNode(focusedNode) &&
                focusedNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ) {
                return true
            }

            var parent = focusedNode.parent
            var depth = 0
            while (parent != null && depth < 3) {
                toRecycle.add(parent)
                if (parent.isClickable && !isNegativeActionNode(parent) &&
                    parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                ) {
                    return true
                }
                parent = parent.parent
                depth++
            }
        } catch (e: Exception) {
            Log.e(TAG, "[DIALOG] focus click error: ${e.message}")
        } finally {
            toRecycle.forEach { safeRecycle(it) }
        }

        return false
    }

    private fun clickFirstClickable(node: AccessibilityNodeInfo?): Boolean {
        val budget = ScanBudget(deadlineMs = System.currentTimeMillis() + SCAN_TIME_BUDGET_MS)
        return clickFirstClickable(node, depth = 0, budget = budget)
    }

    private fun clickFirstClickable(node: AccessibilityNodeInfo?, depth: Int, budget: ScanBudget): Boolean {
        if (node == null || !budget.canVisit(depth)) return false

        try {
            val className = node.className?.toString().orEmpty()
            val looksClickableClass = className.contains("Button", ignoreCase = true) ||
                className == "android.view.View" ||
                className == "android.view.ViewGroup"

            if (node.isClickable && looksClickableClass && !isNegativeActionNode(node)) {
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }

            for (i in 0 until node.childCount) {
                val child = try {
                    node.getChild(i)
                } catch (_: Exception) {
                    null
                }
                if (child != null) {
                    val found = clickFirstClickable(child, depth + 1, budget)
                    safeRecycle(child)
                    if (found) return true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[DIALOG] clickable fallback error: ${e.message}")
        }

        return false
    }

    // ==========================================
    // Layer B: Package-aware heartbeat
    // ==========================================

    private fun startHeartbeat(immediate: Boolean) {
        stopHeartbeat()

        if (currentMode() == ServiceMode.DIALOG_ONLY) {
            Log.i(TAG, "[HB] mode=DIALOG_ONLY heartbeat disabled")
            publishTelemetrySnapshot()
            return
        }

        val profile = currentProfileOrNull() ?: return

        if (immediate) {
            performHeartbeat(profile, reason = "package-enter")
        }

        scheduleNextHeartbeat(profile)
    }

    private fun stopHeartbeat() {
        heartbeatRunnable?.let { handler?.removeCallbacks(it) }
        heartbeatRunnable = null
    }

    private fun scheduleNextHeartbeat(profile: StreamingAppProfile) {
        val localHandler = handler ?: return
        val delay = computeHeartbeatDelayMs(profile)

        heartbeatRunnable = object : Runnable {
            override fun run() {
                val activeProfile = currentProfileOrNull() ?: return
                if (currentMode() != ServiceMode.DIALOG_ONLY) {
                    performHeartbeat(activeProfile, reason = "periodic")
                    scheduleNextHeartbeat(activeProfile)
                }
            }
        }

        localHandler.postDelayed(heartbeatRunnable!!, delay)
        Log.d(
            TAG,
            "[HB] scheduled next heartbeat delayMs=$delay package=$currentActivePackage profile=${profile.packagePrefix}"
        )
    }

    private fun computeHeartbeatDelayMs(profile: StreamingAppProfile): Long {
        val base = PackagePolicy.intervalFor(profile, currentMode())
        val jitter = profile.heartbeatJitterMs
        if (jitter <= 0L) return max(base, MIN_HEARTBEAT_DELAY_MS)

        val delta = Random.nextLong(from = -jitter, until = jitter + 1)
        val delay = base + delta
        return max(delay, MIN_HEARTBEAT_DELAY_MS)
    }

    private fun performHeartbeat(profile: StreamingAppProfile, reason: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.w(TAG, "[HB] gestures require API 24+")
            return
        }
        if (!isSupportedActivePackage()) return

        val now = System.currentTimeMillis()
        val sinceDialogDismiss = now - lastDialogDismissTimestampMs
        if (lastDialogDismissTimestampMs > 0 && sinceDialogDismiss < DIALOG_POST_DISMISS_HEARTBEAT_COOLDOWN_MS) {
            Log.d(TAG, "[HB] skipped due to post-dialog cooldown ms=$sinceDialogDismiss")
            return
        }

        val action = resolveHeartbeatAction(profile, now)
        val zone = pickSafeZone(profile)

        val gesture = when (action) {
            HeartbeatAction.MICRO_TAP -> buildMicroTapGesture(zone)
            HeartbeatAction.MICRO_SWIPE -> buildMicroSwipeGesture(zone)
            HeartbeatAction.HYBRID -> buildMicroTapGesture(zone)
        }

        if (gesture == null) {
            lastGestureFailureTimestampMs = now
            gesturesDispatchRejectedCount++
            publishTelemetrySnapshot()
            return
        }

        gesturesDispatchedCount++
        lastHeartbeatTimestampMs = now
        lastHeartbeatPackage = currentActivePackage
        lastGestureAction = action.name

        Log.i(
            TAG,
            "[HB] fire reason=$reason package=$currentActivePackage action=${action.name} zone=${zone.xPercent},${zone.yPercent}"
        )

        val accepted = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    gesturesCompletedCount++
                    lastGestureSuccessTimestampMs = System.currentTimeMillis()
                    Log.d(TAG, "[GESTURE] completed action=${action.name}")
                    publishTelemetrySnapshot()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    gesturesCancelledCount++
                    lastGestureFailureTimestampMs = System.currentTimeMillis()
                    Log.w(TAG, "[GESTURE] cancelled action=${action.name}")
                    publishTelemetrySnapshot()
                }
            },
            null
        )

        if (!accepted) {
            gesturesDispatchRejectedCount++
            lastGestureFailureTimestampMs = System.currentTimeMillis()
            Log.w(TAG, "[GESTURE] dispatch rejected action=${action.name}")
        }

        publishTelemetrySnapshot()
    }

    private fun resolveHeartbeatAction(profile: StreamingAppProfile, nowMs: Long): HeartbeatAction {
        val inTransitionCooldown = nowMs - lastPackageTransitionTimestampMs < PACKAGE_TRANSITION_HEARTBEAT_COOLDOWN_MS
        if (inTransitionCooldown) {
            return HeartbeatAction.MICRO_TAP
        }

        if (profile.preferredHeartbeatAction != HeartbeatAction.HYBRID) {
            return profile.preferredHeartbeatAction
        }

        useSwipeOnHybrid = !useSwipeOnHybrid
        return if (useSwipeOnHybrid) HeartbeatAction.MICRO_SWIPE else HeartbeatAction.MICRO_TAP
    }

    private fun pickSafeZone(profile: StreamingAppProfile): SafeZone {
        val zones = if (profile.safeZones.isEmpty()) {
            listOf(SafeZone(0.08f, 0.10f, 10))
        } else {
            profile.safeZones
        }
        return zones.random()
    }

    private fun buildMicroTapGesture(zone: SafeZone): GestureDescription? {
        val point = toSafePoint(zone)
        val path = Path().apply { moveTo(point.x, point.y) }
        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 45))
            .build()
    }

    private fun buildMicroSwipeGesture(zone: SafeZone): GestureDescription? {
        val start = toSafePoint(zone)
        val delta = 3f
        val endX = clamp(start.x + delta, 8f, max(8f, resources.displayMetrics.widthPixels - 8f))
        val endY = clamp(start.y + delta, 8f, max(8f, resources.displayMetrics.heightPixels - 8f))

        val path = Path().apply {
            moveTo(start.x, start.y)
            lineTo(endX, endY)
        }

        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
    }

    private fun toSafePoint(zone: SafeZone): PointF {
        val metrics = resources.displayMetrics
        val maxX = max(1f, metrics.widthPixels.toFloat() - 1f)
        val maxY = max(1f, metrics.heightPixels.toFloat() - 1f)

        val safePadding = max(8, zone.radiusPx)
        val baseX = metrics.widthPixels * zone.xPercent
        val baseY = metrics.heightPixels * zone.yPercent
        val jitterX = Random.nextInt(-zone.radiusPx, zone.radiusPx + 1).toFloat()
        val jitterY = Random.nextInt(-zone.radiusPx, zone.radiusPx + 1).toFloat()

        val x = clamp(baseX + jitterX, safePadding.toFloat(), maxX - safePadding)
        val y = clamp(baseY + jitterY, safePadding.toFloat(), maxY - safePadding)
        return PointF(x, y)
    }

    private fun clamp(value: Float, min: Float, max: Float): Float = value.coerceIn(min, max)

    // ==========================================
    // WakeLock Management
    // ==========================================

    private fun acquireWakeLock() {
        if (!USE_PARTIAL_WAKE_LOCK) return
        try {
            if (wakeLock?.isHeld == true) return

            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "StreamKeepAlive::CpuHold"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }

            wakeLockHeldSince = System.currentTimeMillis()
            wakeLockAcquireCount++
            Log.i(TAG, "[WAKE] acquired count=$wakeLockAcquireCount")
            publishTelemetrySnapshot()
        } catch (e: Exception) {
            Log.e(TAG, "[WAKE] acquire failed: ${e.message}", e)
        }
    }

    private fun touchWakeLockSafetyTimer() {
        if (!USE_PARTIAL_WAKE_LOCK) return

        wakeLockLastTouchedAt = System.currentTimeMillis()
        wakeLockSafetyRunnable?.let { handler?.removeCallbacks(it) }

        lateinit var runnable: Runnable
        runnable = Runnable {
            val inactiveForMs = System.currentTimeMillis() - wakeLockLastTouchedAt
            if (wakeLock?.isHeld == true && inactiveForMs >= WAKELOCK_SAFETY_TIMEOUT_MS) {
                Log.w(TAG, "[WAKE] safety timeout reached, releasing lock")
                releaseWakeLock()
                return@Runnable
            }

            val remaining = WAKELOCK_SAFETY_TIMEOUT_MS - inactiveForMs
            if (remaining > 0L) {
                handler?.postDelayed(runnable, remaining)
            }
        }

        wakeLockSafetyRunnable = runnable
        handler?.postDelayed(runnable, WAKELOCK_SAFETY_TIMEOUT_MS)
    }

    private fun releaseWakeLock() {
        try {
            wakeLockSafetyRunnable?.let { handler?.removeCallbacks(it) }
            wakeLockSafetyRunnable = null

            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    wakeLockReleaseCount++
                    val heldMs = System.currentTimeMillis() - wakeLockHeldSince
                    Log.i(TAG, "[WAKE] released count=$wakeLockReleaseCount heldMs=$heldMs")
                }
            }
            wakeLock = null
            publishTelemetrySnapshot()
        } catch (e: Exception) {
            Log.e(TAG, "[WAKE] release failed: ${e.message}", e)
        }
    }

    // ==========================================
    // Shared helpers
    // ==========================================

    private fun isNegativeActionNode(node: AccessibilityNodeInfo): Boolean {
        return DialogTextMatcher.containsNegativeKeyword(node.text, node.contentDescription)
    }

    private fun cleanupRuntimeResources(keepHandler: Boolean, clearPackageState: Boolean) {
        stopHeartbeat()
        stopPeriodicDialogScan()
        stopPackageProbe()
        releaseWakeLock()

        if (!keepHandler) {
            handler?.removeCallbacksAndMessages(null)
        }

        if (clearPackageState) {
            currentActivePackage = ""
            currentProfile = null
            useSwipeOnHybrid = false
        }

        publishTelemetrySnapshot()
    }

    private fun publishTelemetrySnapshot() {
        telemetrySnapshot = TelemetrySnapshot(
            activePackage = currentActivePackage,
            activeProfilePrefix = currentProfile?.packagePrefix.orEmpty(),
            serviceMode = currentMode().name,
            currentFixedIntervalMs = currentProfile?.let { PackagePolicy.intervalFor(it, currentMode()) } ?: 0L,
            lastHeartbeatTimestampMs = lastHeartbeatTimestampMs,
            lastHeartbeatPackage = lastHeartbeatPackage,
            lastGestureSuccessTimestampMs = lastGestureSuccessTimestampMs,
            lastGestureFailureTimestampMs = lastGestureFailureTimestampMs,
            lastGestureAction = lastGestureAction,
            gesturesDispatched = gesturesDispatchedCount,
            gesturesCompleted = gesturesCompletedCount,
            gesturesCancelled = gesturesCancelledCount,
            gesturesDispatchRejected = gesturesDispatchRejectedCount,
            dialogScans = dialogScanCount,
            dialogsDetected = dialogDetectedCount,
            dialogsDismissed = dialogDismissedCount,
            lastDialogDismissStrategy = lastDialogDismissStrategy,
            lastPackageTransitionTimestampMs = lastPackageTransitionTimestampMs,
            wakeAcquires = wakeLockAcquireCount,
            wakeReleases = wakeLockReleaseCount
        )
    }

    private fun safeRecycle(node: AccessibilityNodeInfo?) {
        try {
            node?.recycle()
        } catch (_: Exception) {
            // no-op
        }
    }
}
