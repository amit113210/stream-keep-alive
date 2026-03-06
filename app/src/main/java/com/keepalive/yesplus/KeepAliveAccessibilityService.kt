package com.keepalive.yesplus

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Path
import android.graphics.PointF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
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
 * Layer B: playback-aware heartbeat (requires session + supported foreground + playback gate).
 */
class KeepAliveAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "StreamKeepAlive"

        private const val DIALOG_SCAN_COOLDOWN_MS = 2500L
        private const val DIALOG_HINT_COOLDOWN_MS = 700L
        private const val DIALOG_PERIODIC_SCAN_MS = 20_000L
        private const val DIALOG_NETFLIX_PLAYBACK_SCAN_MS = 3_000L
        private const val PACKAGE_PROBE_INTERVAL_MS = 10_000L
        private const val DIALOG_POST_DISMISS_HEARTBEAT_COOLDOWN_MS = 6000L
        private const val PACKAGE_TRANSITION_HEARTBEAT_COOLDOWN_MS = 4000L
        private const val WINDOW_FINGERPRINT_COOLDOWN_MS = 3000L

        private const val DIALOG_BURST_DURATION_MS = 25_000L
        private const val DIALOG_BURST_SCAN_MS = 2_500L
        private const val DIALOG_BURST_INACTIVE_MS = 90_000L

        private const val MAX_SCAN_NODES = 260
        private const val MAX_SCAN_DEPTH = 16
        private const val SCAN_TIME_BUDGET_MS = 18L

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
            val notificationListenerEnabled: Boolean = false,
            val mediaSessionAccessAvailable: Boolean = false,
            val currentPackage: String = "",
            val currentProfile: String = "",
            val currentMode: String = ServiceMode.NORMAL.name,
            val activePlaybackPackage: String = "",
            val playbackStateFriendly: String = PlaybackFriendlyState.UNKNOWN.name,
            val playbackSignalSource: String = PlaybackSignalSource.NONE.name,
            val playbackConfidence: String = PlaybackConfidence.LOW.name,
            val lastPlaybackStateChangeAt: Long = 0L,
            val shouldRunHeartbeatNow: Boolean = false,
            val heartbeatSuppressedReason: String = "",
            val currentHeartbeatIntervalMs: Long = 0L,
            val currentEscalationStep: Int = 0,
            val lastHeartbeatScheduledAt: Long = 0L,
            val lastHeartbeatExecutedAt: Long = 0L,
            val lastGestureDispatchResult: String = "",
            val lastGestureAction: String = "",
            val lastGestureCompletionAt: Long = 0L,
            val lastDialogDetectionAt: Long = 0L,
            val lastDialogDismissAt: Long = 0L,
            val dialogBurstModeActive: Boolean = false,
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
            val wakeReleases: Long = 0L,
            val calibrationRecommendedMode: String = "",
            val calibrationRecommendedGesture: String = "",
            val calibrationRecommendedZone: Int = -1
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

    private data class HeartbeatGateDecision(
        val allow: Boolean,
        val reason: String
    )

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

    private var dialogBurstModeActive = false
    private var dialogBurstUntilMs = 0L

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

    private var shouldRunHeartbeatNowFlag = false
    private var heartbeatSuppressedReason = ""

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
        val safeEvent = event ?: return
        val packageName = safeEvent.packageName?.toString() ?: return

        if (shouldHandlePackageTransition(packageName, safeEvent.eventType)) {
            onPackageChanged(packageName)
        }

        updatePlaybackSignals("event")
        refreshSessionState(reason = "event")
        maybeProcessDialog(safeEvent, packageName)
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

        updatePlaybackSignals("package-change")
        refreshSessionState(reason = "package-change")
        publishTelemetrySnapshot()
    }

    private fun startPackageProbe() {
        stopPackageProbe()
        val localHandler = handler ?: return

        packageProbeRunnable = object : Runnable {
            override fun run() {
                probePackageFromActiveWindow()
                updatePlaybackSignals("probe")
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

    private fun updatePlaybackSignals(reason: String) {
        val resolver = PlaybackSignalResolver(this)
        val probe = resolver.probeFromMediaSession(listenerComponent())

        val changed = PlaybackStateManager.updateFromMediaSession(
            packageName = probe.packageName,
            friendlyState = probe.friendlyState,
            accessAvailable = probe.accessAvailable,
            nowElapsed = SystemClock.elapsedRealtime()
        )

        val notificationEnabled = PlaybackStateNotificationListenerService.isNotificationAccessEnabled(this)
        PlaybackStateManager.updateCapabilities(
            mediaSessionAccessAvailable = probe.accessAvailable,
            notificationListenerEnabled = notificationEnabled
        )

        if (probe.friendlyState == PlaybackFriendlyState.UNKNOWN && PackagePolicy.isStreamingPackage(currentPackage)) {
            PlaybackStateManager.updateFromFallback(currentPackage, SystemClock.elapsedRealtime())
        }

        PlaybackStateManager.clearStalePlaybackState(SystemClock.elapsedRealtime())

        if (changed || reason == "package-change") {
            val s = PlaybackStateManager.snapshot()
            Log.i(TAG, "[PLAYBACK] state=${s.friendlyState} source=${s.source} confidence=${s.confidence}")
        }
    }

    private fun listenerComponent(): ComponentName {
        return PlaybackStateNotificationListenerService.listenerComponent(this)
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

    private fun shouldRunHeartbeatNow(): HeartbeatGateDecision {
        val sessionActive = isProtectionSessionActive()
        val supportedForeground = if (isSupportedActivePackage()) currentPackage else null
        val decision = PlaybackStateManager.shouldRunHeartbeatNow(sessionActive, supportedForeground)
        shouldRunHeartbeatNowFlag = decision.first
        heartbeatSuppressedReason = if (decision.first) "" else decision.second
        Log.d(TAG, "[HB] gated_by_playback=${decision.first} reason=${decision.second}")
        return HeartbeatGateDecision(decision.first, decision.second)
    }

    private fun refreshSessionState(reason: String) {
        val mode = currentMode()
        val gate = shouldRunHeartbeatNow()

        if (!isProtectionSessionActive()) {
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

        startPeriodicDialogScan()
        ensureWakeLockForSession()

        val modeChanged = mode != lastKnownMode
        lastKnownMode = mode

        if (mode == ServiceMode.DIALOG_ONLY || !gate.allow) {
            if (!gate.allow) {
                Log.i(TAG, "[SESSION] heartbeat suppressed by playback gate reason=${gate.reason}")
            }
            stopHeartbeat()
            sessionHeartbeatEnabled = false
            publishTelemetrySnapshot()
            return
        }

        if (!sessionHeartbeatEnabled) {
            Log.i(TAG, "[SESSION] accessibility heartbeat enabled")
        }
        sessionHeartbeatEnabled = true

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
                if (!isProtectionSessionActive() || !isSupportedActivePackage()) return
                val profile = currentProfile ?: return

                if (dialogBurstModeActive && System.currentTimeMillis() > dialogBurstUntilMs) {
                    dialogBurstModeActive = false
                    Log.i(TAG, "[DIALOG] burst mode exited")
                }

                scanActiveWindowForDialog(reason = "periodic", profile = profile)
                localHandler.postDelayed(this, computeDialogScanCadenceMs())
            }
        }

        localHandler.postDelayed(dialogScanRunnable!!, computeDialogScanCadenceMs())
    }

    private fun stopPeriodicDialogScan() {
        dialogScanRunnable?.let { handler?.removeCallbacks(it) }
        dialogScanRunnable = null
    }

    private fun computeDialogScanCadenceMs(): Long {
        if (dialogBurstModeActive) return DIALOG_BURST_SCAN_MS

        val playback = PlaybackStateManager.snapshot()
        val isNetflix = currentPackage.startsWith("com.netflix")
        if (isNetflix && playback.friendlyState == PlaybackFriendlyState.PLAYING_ACTIVE) {
            return DIALOG_NETFLIX_PLAYBACK_SCAN_MS
        }

        return DIALOG_PERIODIC_SCAN_MS
    }

    private fun enterDialogBurstMode(reason: String) {
        val now = System.currentTimeMillis()
        if (!dialogBurstModeActive) {
            dialogBurstModeActive = true
            Log.i(TAG, "[DIALOG] burst mode entered reason=$reason")
        }
        dialogBurstUntilMs = now + DIALOG_BURST_DURATION_MS
    }

    private fun scanActiveWindowForDialog(reason: String, profile: StreamingAppProfile) {
        val rootNode = rootInActiveWindow ?: return
        val now = System.currentTimeMillis()

        if (shouldSkipDialogScanByFingerprint(rootNode, now)) {
            safeRecycle(rootNode)
            return
        }

        if (now - lastDialogDismissAtMs > DIALOG_BURST_INACTIVE_MS && escalationStep >= 2) {
            enterDialogBurstMode("escalation")
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
            dialogBurstModeActive = false
            Log.i(TAG, "[DIALOG] dismissed strategy=$dismissStrategy")
        } else {
            Log.w(TAG, "[DIALOG] detected but no dismiss action succeeded")
            enterDialogBurstMode("dismiss_failed")
        }

        publishTelemetrySnapshot()
        safeRecycle(rootNode)
    }

    private fun shouldSkipDialogScanByFingerprint(rootNode: AccessibilityNodeInfo, now: Long): Boolean {
        val fingerprint = buildWindowFingerprint(rootNode)
        val isSameWindow = fingerprint == lastDialogWindowFingerprint
        val isCooldownActive = now - lastDialogWindowFingerprintMs < WINDOW_FINGERPRINT_COOLDOWN_MS
        if (isSameWindow && isCooldownActive) return true

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
        if (currentPackage.startsWith("com.netflix")) {
            Log.i(TAG, "[DIALOG] Netflix-specific strategy entered")
            val netflixResult = dismissNetflixSpecificDialog(rootNode)
            if (netflixResult != null) return netflixResult
        }

        if (findAndClickConfirmButton(rootNode, profile)) return "confirm-keyword"
        if (clickFocusedElement(rootNode)) return "focused-element"
        if (clickFirstClickable(rootNode)) return "clickable-fallback"
        return if (performGlobalAction(GLOBAL_ACTION_BACK)) "global-back" else null
    }

    private fun dismissNetflixSpecificDialog(rootNode: AccessibilityNodeInfo): String? {
        val visibleTexts = mutableListOf<String>()
        collectVisibleTexts(rootNode, visibleTexts, 0)
        Log.i(TAG, "[DIALOG] visible texts dump=${visibleTexts.take(12)}")

        val exactNode = findNodeByExactText(rootNode, "הפעל בלי לשאול שוב")
        if (exactNode != null) {
            Log.i(TAG, "[DIALOG] exact target found")
            val clicked = clickNodeOrClickableAncestor(exactNode)
            safeRecycle(exactNode)
            if (clicked) {
                Log.i(TAG, "[DIALOG] clicked target")
                return "netflix-exact"
            }
        }

        val containsNode = findNodeContainingText(rootNode, "בלי לשאול שוב")
        if (containsNode != null) {
            Log.i(TAG, "[DIALOG] contains target found")
            val clicked = clickNodeOrClickableAncestor(containsNode)
            safeRecycle(containsNode)
            if (clicked) {
                Log.i(TAG, "[DIALOG] clicked target")
                return "netflix-contains"
            }
        }

        return null
    }

    private fun collectVisibleTexts(node: AccessibilityNodeInfo?, out: MutableList<String>, depth: Int) {
        if (node == null || depth > 4 || out.size > 20) return

        val txt = node.text?.toString()?.trim().orEmpty()
        if (txt.isNotEmpty()) out.add(txt)

        for (i in 0 until node.childCount) {
            val child = safeGetChild(node, i)
            if (child != null) {
                collectVisibleTexts(child, out, depth + 1)
                safeRecycle(child)
            }
        }
    }

    private fun findNodeByExactText(node: AccessibilityNodeInfo?, target: String): AccessibilityNodeInfo? {
        if (node == null) return null
        val text = node.text?.toString()?.trim().orEmpty()
        if (text == target && !DialogTextMatcher.containsNegativeKeyword(text, node.contentDescription)) {
            return AccessibilityNodeInfo.obtain(node)
        }

        for (i in 0 until node.childCount) {
            val child = safeGetChild(node, i)
            if (child != null) {
                val found = findNodeByExactText(child, target)
                safeRecycle(child)
                if (found != null) return found
            }
        }

        return null
    }

    private fun findNodeContainingText(node: AccessibilityNodeInfo?, target: String): AccessibilityNodeInfo? {
        if (node == null) return null
        val text = node.text?.toString()?.trim().orEmpty()
        if (text.contains(target) && !DialogTextMatcher.containsNegativeKeyword(text, node.contentDescription)) {
            return AccessibilityNodeInfo.obtain(node)
        }

        for (i in 0 until node.childCount) {
            val child = safeGetChild(node, i)
            if (child != null) {
                val found = findNodeContainingText(child, target)
                safeRecycle(child)
                if (found != null) return found
            }
        }

        return null
    }

    private fun clickNodeOrClickableAncestor(node: AccessibilityNodeInfo): Boolean {
        if (isNegativeActionNode(node)) return false
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true

        var parent = node.parent
        val toRecycle = mutableListOf<AccessibilityNodeInfo>()
        var depth = 0
        while (parent != null && depth < 4) {
            toRecycle.add(parent)
            if (!isNegativeActionNode(parent) && parent.isClickable &&
                parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ) {
                toRecycle.forEach { safeRecycle(it) }
                return true
            }
            parent = parent.parent
            depth++
        }

        toRecycle.forEach { safeRecycle(it) }
        return false
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
    // Layer B: Playback-aware heartbeat
    // ==========================================

    private fun startHeartbeat(immediate: Boolean) {
        stopHeartbeat()

        val gate = shouldRunHeartbeatNow()
        if (!gate.allow) {
            Log.i(TAG, "[HB] start skipped: ${gate.reason}")
            return
        }
        if (currentMode() == ServiceMode.DIALOG_ONLY) {
            Log.i(TAG, "[HB] start skipped: dialog-only mode")
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
                val gate = shouldRunHeartbeatNow()
                if (!gate.allow) return
                if (currentMode() == ServiceMode.DIALOG_ONLY) return
                if (!isSupportedActivePackage()) return

                val activeProfile = currentProfile ?: return
                performHeartbeat(activeProfile, reason = "periodic")
                scheduleNextHeartbeat(activeProfile)
            }
        }

        localHandler.postDelayed(heartbeatRunnable!!, delay)
        Log.d(TAG, "[HB] scheduled delayMs=$delay package=$currentPackage")
    }

    private fun computeHeartbeatDelayMs(profile: StreamingAppProfile): Long {
        val base = PackagePolicy.intervalFor(profile, currentMode())
        val playback = PlaybackStateManager.snapshot()

        val scaledBase = when (playback.confidence) {
            PlaybackConfidence.HIGH -> (base * 0.85f).toLong()
            PlaybackConfidence.MEDIUM -> base
            PlaybackConfidence.LOW -> (base * 1.25f).toLong()
        }

        val jitter = min(profile.heartbeatJitterMs, 12_000L)
        val withJitter = if (jitter > 0) {
            scaledBase + Random.nextLong(from = -jitter, until = jitter + 1)
        } else {
            scaledBase
        }

        return max(withJitter, MIN_HEARTBEAT_DELAY_MS)
    }

    private fun performHeartbeat(profile: StreamingAppProfile, reason: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.w(TAG, "[HB] gestures require API 24+")
            return
        }

        val gate = shouldRunHeartbeatNow()
        if (!gate.allow) return
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
            enterDialogBurstMode("heartbeat_fail")
        }
    }

    private fun maybeScheduleSecondaryAttempt(profile: StreamingAppProfile, zone: SafeZone) {
        val now = System.currentTimeMillis()
        if (now - lastSecondaryAttemptAtMs < DOUBLE_ATTEMPT_COOLDOWN_MS) return

        val localHandler = handler ?: return
        lastSecondaryAttemptAtMs = now
        localHandler.postDelayed({
            val gate = shouldRunHeartbeatNow()
            if (!gate.allow) return@postDelayed
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
        if (inTransitionCooldown) return HeartbeatAction.MICRO_TAP

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
            "[HB] fire reason=$reason secondary=$secondary package=$currentPackage action=${action.name}"
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
                    publishTelemetrySnapshot()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    gesturesCancelledCount++
                    lastGestureDispatchResult = "cancelled"
                    consecutiveGestureCancels++
                    consecutiveGestureFailures++
                    updateEscalationStep()
                    enterDialogBurstMode("gesture_cancelled")
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
        enterDialogBurstMode("dispatch_rejected")
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
            dialogBurstModeActive = false
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

        val playback = PlaybackStateManager.snapshot()
        val calibration = CalibrationManager.overrideForPackage(this, currentPackage)

        telemetrySnapshot = TelemetrySnapshot(
            protectionSessionActive = protectionActive,
            protectionSessionStartedAt = ProtectionSessionManager.protectionStartedAt(this),
            foregroundServiceRunning = ProtectionSessionManager.isForegroundServiceRunning(this),
            notificationPermissionGranted = notificationsGranted,
            notificationListenerEnabled = PlaybackStateNotificationListenerService.isNotificationAccessEnabled(this),
            mediaSessionAccessAvailable = playback.mediaSessionAccessAvailable,
            currentPackage = currentPackage,
            currentProfile = activeProfile,
            currentMode = mode.name,
            activePlaybackPackage = playback.activePlaybackPackage.orEmpty(),
            playbackStateFriendly = playback.friendlyState.name,
            playbackSignalSource = playback.source.name,
            playbackConfidence = playback.confidence.name,
            lastPlaybackStateChangeAt = playback.lastStateChangeAt,
            shouldRunHeartbeatNow = shouldRunHeartbeatNowFlag,
            heartbeatSuppressedReason = heartbeatSuppressedReason,
            currentHeartbeatIntervalMs = interval,
            currentEscalationStep = escalationStep,
            lastHeartbeatScheduledAt = lastHeartbeatScheduledAtMs,
            lastHeartbeatExecutedAt = lastHeartbeatExecutedAtMs,
            lastGestureDispatchResult = lastGestureDispatchResult,
            lastGestureAction = lastGestureAction,
            lastGestureCompletionAt = lastGestureCompletionAtMs,
            lastDialogDetectionAt = lastDialogDetectionAtMs,
            lastDialogDismissAt = lastDialogDismissAtMs,
            dialogBurstModeActive = dialogBurstModeActive,
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
            wakeReleases = wakeLockReleaseCount,
            calibrationRecommendedMode = calibration?.preferredMode?.name.orEmpty(),
            calibrationRecommendedGesture = calibration?.preferredAction?.name.orEmpty(),
            calibrationRecommendedZone = calibration?.preferredSafeZoneIndex ?: -1
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
