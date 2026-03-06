package com.keepalive.yesplus

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Path
import android.graphics.PointF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationManagerCompat
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Accessibility service for Android TV keep-alive and still-watching dialog dismissal.
 *
 * Layer A: dialog detection/dismissal.
 * Layer B: package-aware heartbeat (active only during user-initiated protection session).
 */
class KeepAliveAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "StreamKeepAlive"

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
        private const val DOUBLE_ATTEMPT_DELAY_MS = 1400L
        private const val DOUBLE_ATTEMPT_COOLDOWN_MS = 60_000L

        // Partial wake lock is only a helper for process continuity during active protection.
        private const val USE_PARTIAL_WAKE_LOCK = true
        private const val WAKELOCK_SAFETY_TIMEOUT_MS = 90L * 60L * 1000L

        @Volatile
        var isRunning = false
            private set

        data class TelemetrySnapshot(
            val protectionSessionActive: Boolean = false,
            val protectionSessionStartedAt: Long = 0L,
            val foregroundServiceRunning: Boolean = false,
            val notificationPermissionGranted: Boolean = true,
            val currentPackage: String = "",
            val currentProfile: String = "",
            val currentMode: String = ServiceMode.NORMAL.name,
            val currentHeartbeatIntervalMs: Long = 0L,
            val currentEscalationStep: Int = 0,
            val lastHeartbeatScheduledAt: Long = 0L,
            val lastHeartbeatExecutedAt: Long = 0L,
            val lastGestureDispatchResult: String = "",
            val lastGestureAction: String = "",
            val lastGestureCompletionAt: Long = 0L,
            val lastDialogDetectionAt: Long = 0L,
            val lastDialogDismissAt: Long = 0L,
            val dialogScans: Long = 0L,
            val dialogsDetected: Long = 0L,
            val dialogsDismissed: Long = 0L,
            val lastDialogDismissStrategy: String = "",
            val gesturesDispatched: Long = 0L,
            val gesturesCompleted: Long = 0L,
            val gesturesCancelled: Long = 0L,
            val gesturesDispatchRejected: Long = 0L,
            val consecutiveGestureFailures: Int = 0,
            val consecutiveGestureCancels: Int = 0,
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
    private val sessionPrefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null) return@OnSharedPreferenceChangeListener
        if (key == "active" || key == "mode" || key == "foreground_running") {
            val active = isProtectionSessionActive()
            Log.i(TAG, "[SESSION] started/stopped update active=$active via pref key=$key")
            refreshSessionState(reason = "session-pref-change")
        }
    }

    private var currentPackage = ""
    private var currentProfile: StreamingAppProfile? = null
    private var hybridSwipeToggle = false
    private var heartbeatSequence = 0L
    private var sessionHeartbeatEnabled = false
    private var lastKnownMode: ServiceMode = ServiceMode.NORMAL

    private var lastDialogCheckTimeMs = 0L
    private var lastDialogWindowFingerprint = ""
    private var lastDialogWindowFingerprintMs = 0L
    private var lastDialogDetectionAtMs = 0L
    private var lastDialogDismissAtMs = 0L
    private var lastDialogDismissStrategy = ""
    private var lastPackageTransitionTimestampMs = 0L

    private var wakeLockLastTouchedAt = 0L
    private var wakeLockHeldSince = 0L
    private var wakeLockAcquireCount = 0L
    private var wakeLockReleaseCount = 0L

    private var dialogScanCount = 0L
    private var dialogDetectedCount = 0L
    private var dialogDismissedCount = 0L

    private var gesturesDispatchedCount = 0L
    private var gesturesCompletedCount = 0L
    private var gesturesCancelledCount = 0L
    private var gesturesDispatchRejectedCount = 0L
    private var lastHeartbeatScheduledAtMs = 0L
    private var lastHeartbeatExecutedAtMs = 0L
    private var lastGestureCompletionAtMs = 0L
    private var lastGestureDispatchResult = ""
    private var lastGestureAction = ""
    private var lastSecondaryAttemptAtMs = 0L

    private var consecutiveGestureFailures = 0
    private var consecutiveGestureCancels = 0
    private var escalationStep = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        handler = Handler(Looper.getMainLooper())
        ProtectionSessionManager.registerSessionListener(this, sessionPrefsListener)
        Log.i(TAG, "[SESSION] accessibility service connected")
        startPackageProbe()
        publishTelemetrySnapshot()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString() ?: return

        if (shouldHandlePackageTransition(packageName, event.eventType)) {
            onPackageChanged(packageName)
        }

        refreshSessionState(reason = "event")
        maybeProcessDialog(event, packageName)
    }

    override fun onInterrupt() {
        Log.w(TAG, "[SESSION] accessibility service interrupted")
        cleanupRuntimeResources(keepHandler = true, clearPackageState = true)
    }

    override fun onDestroy() {
        isRunning = false
        ProtectionSessionManager.unregisterSessionListener(this, sessionPrefsListener)
        cleanupRuntimeResources(keepHandler = false, clearPackageState = true)
        handler = null
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isRunning = false
        ProtectionSessionManager.unregisterSessionListener(this, sessionPrefsListener)
        cleanupRuntimeResources(keepHandler = false, clearPackageState = true)
        handler = null
        return super.onUnbind(intent)
    }

    private fun shouldHandlePackageTransition(packageName: String, eventType: Int): Boolean {
        if (packageName == currentPackage) return false
        if (currentPackage.isEmpty()) return true
        return eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
    }

    private fun onPackageChanged(newPackage: String) {
        currentPackage = newPackage
        currentProfile = PackagePolicy.profileForPackage(newPackage)
        lastPackageTransitionTimestampMs = System.currentTimeMillis()
        hybridSwipeToggle = false
        resetEscalationState()

        Log.i(
            TAG,
            "[PKG] transition package=$newPackage streaming=${currentProfile != null} ignored=${PackagePolicy.isIgnoredPackage(newPackage)}"
        )

        refreshSessionState(reason = "package-change")
        publishTelemetrySnapshot()
    }

    private fun startPackageProbe() {
        stopPackageProbe()
        val localHandler = handler ?: return

        packageProbeRunnable = object : Runnable {
            override fun run() {
                probePackageFromActiveWindow()
                refreshSessionState(reason = "probe")
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
            if (observedPackage != currentPackage) {
                Log.d(TAG, "[PKG] probe observed package=$observedPackage")
                onPackageChanged(observedPackage)
            }
        } finally {
            safeRecycle(rootNode)
        }
    }

    private fun isProtectionSessionActive(): Boolean {
        return ProtectionSessionManager.isProtectionActive(this)
    }

    private fun isSupportedActivePackage(): Boolean {
        val profile = currentProfile ?: return false
        if (PackagePolicy.isIgnoredPackage(currentPackage)) return false
        return currentPackage.startsWith(profile.packagePrefix)
    }

    private fun currentMode(): ServiceMode {
        val sessionMode = ProtectionSessionManager.currentMode(this)
        val override = CalibrationManager.overrideForPackage(this, currentPackage)
        return override?.preferredMode ?: sessionMode
    }

    private fun refreshSessionState(reason: String) {
        val protectionActive = isProtectionSessionActive()
        val mode = currentMode()

        if (!protectionActive) {
            if (sessionHeartbeatEnabled) {
                Log.i(TAG, "[SESSION] accessibility heartbeat disabled reason=session-off")
            }
            sessionHeartbeatEnabled = false
            stopHeartbeat()
            stopPeriodicDialogScan()
            releaseWakeLock("session-off")
            publishTelemetrySnapshot()
            return
        }

        if (!isSupportedActivePackage()) {
            if (sessionHeartbeatEnabled) {
                Log.i(TAG, "[SESSION] accessibility heartbeat disabled reason=unsupported-package")
            }
            sessionHeartbeatEnabled = false
            stopHeartbeat()
            stopPeriodicDialogScan()
            releaseWakeLock("unsupported-package")
            publishTelemetrySnapshot()
            return
        }

        if (!sessionHeartbeatEnabled) {
            Log.i(TAG, "[SESSION] accessibility heartbeat enabled")
        }
        sessionHeartbeatEnabled = true

        startPeriodicDialogScan()
        ensureWakeLockForSession()

        val modeChanged = mode != lastKnownMode
        lastKnownMode = mode

        if (mode == ServiceMode.DIALOG_ONLY) {
            stopHeartbeat()
            publishTelemetrySnapshot()
            return
        }

        if (modeChanged) {
            Log.i(TAG, "[SESSION] mode changed to ${mode.name}; rescheduling heartbeat")
            startHeartbeat(immediate = false)
        } else if (heartbeatRunnable == null) {
            startHeartbeat(immediate = true)
        }

        if (reason == "probe" || reason == "package-change") {
            touchWakeLockSafetyTimer()
        }

        publishTelemetrySnapshot()
    }

    // ==========================================
    // Layer A: Dialog Detection & Auto-Dismiss
    // ==========================================

    private fun maybeProcessDialog(event: AccessibilityEvent, packageName: String) {
        if (!PackagePolicy.isStreamingPackage(packageName)) return
        if (!isDialogRelevantEvent(event.eventType)) return

        val profile = PackagePolicy.profileForPackage(packageName) ?: return
        val now = System.currentTimeMillis()

        val quickDialogHint = DialogTextMatcher.eventLooksLikeDialog(
            className = event.className,
            eventText = event.text,
            contentDescription = event.contentDescription,
            additionalKeywords = profile.dialogKeywords
        )
        val cooldown = if (quickDialogHint) DIALOG_HINT_COOLDOWN_MS else DIALOG_SCAN_COOLDOWN_MS
        if (now - lastDialogCheckTimeMs < cooldown) return

        scanActiveWindowForDialog(reason = "event:${event.eventType}", profile = profile)
    }

    private fun isDialogRelevantEvent(eventType: Int): Boolean {
        return eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            eventType == AccessibilityEvent.TYPE_VIEW_CLICKED
    }

    private fun startPeriodicDialogScan() {
        if (dialogScanRunnable != null) return
        val localHandler = handler ?: return

        dialogScanRunnable = object : Runnable {
            override fun run() {
                if (!isProtectionSessionActive() || !isSupportedActivePackage()) {
                    return
                }
                val profile = currentProfile ?: return
                scanActiveWindowForDialog(reason = "periodic", profile = profile)
                localHandler.postDelayed(this, DIALOG_PERIODIC_SCAN_MS)
            }
        }

        localHandler.postDelayed(dialogScanRunnable!!, DIALOG_PERIODIC_SCAN_MS)
    }

    private fun stopPeriodicDialogScan() {
        dialogScanRunnable?.let { handler?.removeCallbacks(it) }
        dialogScanRunnable = null
    }

    private fun scanActiveWindowForDialog(reason: String, profile: StreamingAppProfile) {
        val rootNode = rootInActiveWindow ?: return
        val now = System.currentTimeMillis()

        if (shouldSkipDialogScanByFingerprint(rootNode, now)) {
            safeRecycle(rootNode)
            return
        }

        lastDialogCheckTimeMs = now
        dialogScanCount++
        Log.d(TAG, "[DIALOG] scan reason=$reason package=$currentPackage")

        val detected = containsDialogKeywords(rootNode, profile)
        if (!detected) {
            safeRecycle(rootNode)
            publishTelemetrySnapshot()
            return
        }

        dialogDetectedCount++
        lastDialogDetectionAtMs = now
        Log.i(TAG, "[DIALOG] dialog detected package=$currentPackage")

        val dismissStrategy = dismissDialog(rootNode, profile)
        if (dismissStrategy != null) {
            dialogDismissedCount++
            lastDialogDismissAtMs = System.currentTimeMillis()
            lastDialogDismissStrategy = dismissStrategy
            Log.i(TAG, "[DIALOG] dismissed strategy=$dismissStrategy")
        } else {
            Log.w(TAG, "[DIALOG] detected but no dismiss action succeeded")
        }

        publishTelemetrySnapshot()
        safeRecycle(rootNode)
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

    private fun dismissDialog(rootNode: AccessibilityNodeInfo, profile: StreamingAppProfile): String? {
        if (findAndClickConfirmButton(rootNode, profile)) return "confirm-keyword"
        if (clickFocusedElement(rootNode)) return "focused-element"
        if (clickFirstClickable(rootNode)) return "clickable-fallback"
        return if (performGlobalAction(GLOBAL_ACTION_BACK)) "global-back" else null
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
                val child = safeGetChild(node, i)
                if (child != null) {
                    val found = containsDialogKeywords(child, depth + 1, budget, profile)
                    safeRecycle(child)
                    if (found) return true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[DIALOG] keyword scan error: ${e.message}")
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
            val isPositiveConfirm = DialogTextMatcher.containsConfirmKeyword(
                text = node.text,
                contentDesc = node.contentDescription,
                additionalKeywords = profile.confirmKeywords
            )
            val isNegative = DialogTextMatcher.containsNegativeKeyword(node.text, node.contentDescription)
            if (node.isClickable && isPositiveConfirm && !isNegative) {
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }

            for (i in 0 until node.childCount) {
                val child = safeGetChild(node, i)
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
            Log.e(TAG, "[DIALOG] focused click error: ${e.message}")
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
                val child = safeGetChild(node, i)
                if (child != null) {
                    val found = clickFirstClickable(child, depth + 1, budget)
                    safeRecycle(child)
                    if (found) return true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[DIALOG] fallback click error: ${e.message}")
        }

        return false
    }

    // ==========================================
    // Layer B: Package-aware heartbeat
    // ==========================================

    private fun startHeartbeat(immediate: Boolean) {
        stopHeartbeat()

        if (!isProtectionSessionActive()) {
            Log.i(TAG, "[SESSION] heartbeat skipped: protection inactive")
            return
        }

        if (currentMode() == ServiceMode.DIALOG_ONLY) {
            Log.i(TAG, "[SESSION] heartbeat skipped: dialog-only mode")
            return
        }

        val profile = currentProfile ?: return
        if (!isSupportedActivePackage()) return

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
        lastHeartbeatScheduledAtMs = System.currentTimeMillis() + delay

        heartbeatRunnable = object : Runnable {
            override fun run() {
                if (!isProtectionSessionActive() || !isSupportedActivePackage()) return
                if (currentMode() == ServiceMode.DIALOG_ONLY) return

                val activeProfile = currentProfile ?: return
                performHeartbeat(activeProfile, reason = "periodic")
                scheduleNextHeartbeat(activeProfile)
            }
        }

        localHandler.postDelayed(heartbeatRunnable!!, delay)
        Log.d(
            TAG,
            "[HB] scheduled delayMs=$delay package=$currentPackage profile=${profile.packagePrefix}"
        )
    }

    private fun computeHeartbeatDelayMs(profile: StreamingAppProfile): Long {
        val base = PackagePolicy.intervalFor(profile, currentMode())
        val jitter = min(profile.heartbeatJitterMs, 15_000L)
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
        if (!isProtectionSessionActive() || !isSupportedActivePackage()) return
        if (currentMode() == ServiceMode.DIALOG_ONLY) return

        val now = System.currentTimeMillis()
        val sinceDialogDismiss = now - lastDialogDismissAtMs
        if (lastDialogDismissAtMs > 0 && sinceDialogDismiss < DIALOG_POST_DISMISS_HEARTBEAT_COOLDOWN_MS) {
            Log.d(TAG, "[HB] skipped by post-dialog cooldown ms=$sinceDialogDismiss")
            return
        }

        val zone = pickSafeZone(profile)
        val action = resolveHeartbeatAction(profile, now)
        heartbeatSequence++

        val dispatched = dispatchHeartbeatGesture(action, zone, reason, secondary = false)
        if (!dispatched && escalationStep >= 3) {
            maybeScheduleSecondaryAttempt(profile, zone)
        }
    }

    private fun maybeScheduleSecondaryAttempt(profile: StreamingAppProfile, zone: SafeZone) {
        val now = System.currentTimeMillis()
        if (now - lastSecondaryAttemptAtMs < DOUBLE_ATTEMPT_COOLDOWN_MS) return

        val localHandler = handler ?: return
        lastSecondaryAttemptAtMs = now
        localHandler.postDelayed({
            if (!isProtectionSessionActive() || !isSupportedActivePackage()) return@postDelayed
            if (lastGestureDispatchResult == "completed") return@postDelayed

            val action = alternateAction(resolveHeartbeatAction(profile, System.currentTimeMillis()))
            Log.i(TAG, "[HB] escalation double-attempt action=${action.name}")
            dispatchHeartbeatGesture(action, zone, "escalation-double", secondary = true)
        }, DOUBLE_ATTEMPT_DELAY_MS)
    }

    private fun resolveHeartbeatAction(profile: StreamingAppProfile, nowMs: Long): HeartbeatAction {
        val override = CalibrationManager.overrideForPackage(this, currentPackage)
        val preferred = override?.preferredAction ?: profile.preferredHeartbeatAction

        val inTransitionCooldown = nowMs - lastPackageTransitionTimestampMs < PACKAGE_TRANSITION_HEARTBEAT_COOLDOWN_MS
        if (inTransitionCooldown) {
            return HeartbeatAction.MICRO_TAP
        }

        var action = when (preferred) {
            HeartbeatAction.HYBRID -> {
                hybridSwipeToggle = !hybridSwipeToggle
                if (hybridSwipeToggle) HeartbeatAction.MICRO_SWIPE else HeartbeatAction.MICRO_TAP
            }
            else -> preferred
        }

        if (escalationStep >= 2) {
            action = alternateAction(action)
        }

        return action
    }

    private fun alternateAction(action: HeartbeatAction): HeartbeatAction {
        return when (action) {
            HeartbeatAction.MICRO_TAP -> HeartbeatAction.MICRO_SWIPE
            HeartbeatAction.MICRO_SWIPE -> HeartbeatAction.MICRO_TAP
            HeartbeatAction.HYBRID -> HeartbeatAction.MICRO_TAP
        }
    }

    private fun pickSafeZone(profile: StreamingAppProfile): SafeZone {
        val zones = if (profile.safeZones.isEmpty()) {
            listOf(SafeZone(0.08f, 0.10f, 10))
        } else {
            profile.safeZones
        }

        val override = CalibrationManager.overrideForPackage(this, currentPackage)
        val preferredIndex = override?.preferredSafeZoneIndex?.coerceIn(0, zones.lastIndex) ?: 0

        val selectedIndex = when {
            escalationStep == 0 -> preferredIndex
            escalationStep == 1 -> (preferredIndex + 1) % zones.size
            else -> (preferredIndex + (heartbeatSequence.toInt() % max(1, zones.size))) % zones.size
        }

        return zones[selectedIndex]
    }

    private fun dispatchHeartbeatGesture(
        action: HeartbeatAction,
        zone: SafeZone,
        reason: String,
        secondary: Boolean
    ): Boolean {
        val gesture = when (action) {
            HeartbeatAction.MICRO_TAP -> buildMicroTapGesture(zone)
            HeartbeatAction.MICRO_SWIPE -> buildMicroSwipeGesture(zone)
            HeartbeatAction.HYBRID -> buildMicroTapGesture(zone)
        }

        lastHeartbeatExecutedAtMs = System.currentTimeMillis()
        lastGestureAction = action.name

        if (gesture == null) {
            onGestureDispatchRejected("gesture_build_failed")
            return false
        }

        gesturesDispatchedCount++
        Log.i(
            TAG,
            "[HB] fire reason=$reason secondary=$secondary package=$currentPackage action=${action.name} zone=${zone.xPercent},${zone.yPercent}"
        )

        val accepted = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    gesturesCompletedCount++
                    lastGestureCompletionAtMs = System.currentTimeMillis()
                    lastGestureDispatchResult = "completed"
                    consecutiveGestureFailures = 0
                    consecutiveGestureCancels = 0
                    updateEscalationStep()
                    Log.d(TAG, "[GESTURE] completed action=${action.name}")
                    publishTelemetrySnapshot()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    gesturesCancelledCount++
                    lastGestureDispatchResult = "cancelled"
                    consecutiveGestureCancels++
                    consecutiveGestureFailures++
                    updateEscalationStep()
                    Log.w(TAG, "[GESTURE] cancelled action=${action.name}")
                    publishTelemetrySnapshot()
                }
            },
            null
        )

        if (!accepted) {
            onGestureDispatchRejected("dispatch_rejected")
            return false
        }

        publishTelemetrySnapshot()
        return true
    }

    private fun onGestureDispatchRejected(result: String) {
        gesturesDispatchRejectedCount++
        lastGestureDispatchResult = result
        consecutiveGestureFailures++
        updateEscalationStep()
        Log.w(TAG, "[GESTURE] $result")
        publishTelemetrySnapshot()
    }

    private fun updateEscalationStep() {
        val previous = escalationStep
        escalationStep = when {
            consecutiveGestureFailures >= 4 || consecutiveGestureCancels >= 3 -> 3
            consecutiveGestureFailures >= 3 || consecutiveGestureCancels >= 2 -> 2
            consecutiveGestureFailures >= 2 || consecutiveGestureCancels >= 1 -> 1
            else -> 0
        }

        if (previous != escalationStep) {
            Log.i(TAG, "[HB] escalation step changed $previous -> $escalationStep")
        }
    }

    private fun resetEscalationState() {
        consecutiveGestureFailures = 0
        consecutiveGestureCancels = 0
        escalationStep = 0
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

    private fun ensureWakeLockForSession() {
        if (!USE_PARTIAL_WAKE_LOCK) {
            Log.d(TAG, "[WAKE] skipped - disabled by policy")
            return
        }
        if (!isProtectionSessionActive() || !isSupportedActivePackage()) {
            releaseWakeLock("gate-failed")
            return
        }

        if (wakeLock?.isHeld == true) {
            touchWakeLockSafetyTimer()
            return
        }

        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "StreamKeepAlive::ProtectionSession"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }

            wakeLockHeldSince = System.currentTimeMillis()
            wakeLockAcquireCount++
            touchWakeLockSafetyTimer()
            Log.i(TAG, "[WAKE] acquired")
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
                releaseWakeLock("safety-timeout")
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

    private fun releaseWakeLock(reason: String) {
        try {
            wakeLockSafetyRunnable?.let { handler?.removeCallbacks(it) }
            wakeLockSafetyRunnable = null

            val lock = wakeLock
            if (lock?.isHeld == true) {
                lock.release()
                wakeLockReleaseCount++
                Log.i(TAG, "[WAKE] released reason=$reason")
            } else {
                Log.d(TAG, "[WAKE] skipped release reason=$reason")
            }
            wakeLock = null
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

    private fun safeGetChild(node: AccessibilityNodeInfo, index: Int): AccessibilityNodeInfo? {
        return try {
            node.getChild(index)
        } catch (_: Exception) {
            null
        }
    }

    private fun cleanupRuntimeResources(keepHandler: Boolean, clearPackageState: Boolean) {
        stopHeartbeat()
        stopPeriodicDialogScan()
        stopPackageProbe()
        releaseWakeLock("cleanup")

        if (!keepHandler) {
            handler?.removeCallbacksAndMessages(null)
        }

        if (clearPackageState) {
            currentPackage = ""
            currentProfile = null
            hybridSwipeToggle = false
            sessionHeartbeatEnabled = false
            resetEscalationState()
        }

        publishTelemetrySnapshot()
    }

    private fun publishTelemetrySnapshot() {
        val protectionActive = isProtectionSessionActive()
        val activeProfile = currentProfile?.packagePrefix.orEmpty()
        val mode = currentMode()
        val interval = currentProfile?.let { PackagePolicy.intervalFor(it, mode) } ?: 0L
        val notificationsGranted = NotificationManagerCompat.from(this).areNotificationsEnabled()

        telemetrySnapshot = TelemetrySnapshot(
            protectionSessionActive = protectionActive,
            protectionSessionStartedAt = ProtectionSessionManager.protectionStartedAt(this),
            foregroundServiceRunning = ProtectionSessionManager.isForegroundServiceRunning(this),
            notificationPermissionGranted = notificationsGranted,
            currentPackage = currentPackage,
            currentProfile = activeProfile,
            currentMode = mode.name,
            currentHeartbeatIntervalMs = interval,
            currentEscalationStep = escalationStep,
            lastHeartbeatScheduledAt = lastHeartbeatScheduledAtMs,
            lastHeartbeatExecutedAt = lastHeartbeatExecutedAtMs,
            lastGestureDispatchResult = lastGestureDispatchResult,
            lastGestureAction = lastGestureAction,
            lastGestureCompletionAt = lastGestureCompletionAtMs,
            lastDialogDetectionAt = lastDialogDetectionAtMs,
            lastDialogDismissAt = lastDialogDismissAtMs,
            dialogScans = dialogScanCount,
            dialogsDetected = dialogDetectedCount,
            dialogsDismissed = dialogDismissedCount,
            lastDialogDismissStrategy = lastDialogDismissStrategy,
            gesturesDispatched = gesturesDispatchedCount,
            gesturesCompleted = gesturesCompletedCount,
            gesturesCancelled = gesturesCancelledCount,
            gesturesDispatchRejected = gesturesDispatchRejectedCount,
            consecutiveGestureFailures = consecutiveGestureFailures,
            consecutiveGestureCancels = consecutiveGestureCancels,
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
