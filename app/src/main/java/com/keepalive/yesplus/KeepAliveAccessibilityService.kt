package com.keepalive.yesplus

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
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
        private const val DIALOG_PRIORITY_PLAYBACK_SCAN_MS = 3_000L
        private const val PACKAGE_PROBE_INTERVAL_MS = 10_000L
        private const val DIALOG_POST_DISMISS_HEARTBEAT_COOLDOWN_MS = 6000L
        private const val PACKAGE_TRANSITION_HEARTBEAT_COOLDOWN_MS = 4000L
        private const val WINDOW_FINGERPRINT_COOLDOWN_MS = 3000L
        private const val DIALOG_ACTION_REPEAT_COOLDOWN_MS = 20_000L

        private const val DIALOG_BURST_DURATION_MS = 25_000L
        private const val DIALOG_BURST_SCAN_MS = 2_500L
        private const val DIALOG_BURST_INACTIVE_MS = 90_000L

        private const val MAX_WINDOW_LOG_TEXTS = 20
        private const val MAX_WINDOW_LOG_CLICKABLE_TEXTS = 12

        private const val MIN_HEARTBEAT_DELAY_MS = 45_000L
        private const val DOUBLE_ATTEMPT_DELAY_MS = 1400L
        private const val DOUBLE_ATTEMPT_COOLDOWN_MS = 60_000L

        private const val GESTURE_TAP_DURATION_MS = 100L
        private const val GESTURE_SWIPE_DURATION_MS = 250L
        private const val GESTURE_CANCEL_BACKOFF_THRESHOLD = 5

        private const val FOCUS_CLICK_DELAY_MS = 200L
        private const val DIALOG_PENDING_OVERRIDE_TIMEOUT_MS = 5L * 60L * 1000L

        private const val ANTI_SCREENSAVER_POKE_INTERVAL_MS = 75_000L
        private const val ANTI_SCREENSAVER_POKE_JITTER_MS = 15_000L

        private val SCREENSAVER_PACKAGES = listOf(
            "com.google.android.apps.tv.dreamx",
            "com.android.dreams",
            "com.google.android.screensaver",
            "com.android.screensaver"
        )
        private const val SCREENSAVER_ESCAPE_COOLDOWN_MS = 5_000L
        private const val SCREENSAVER_POST_ESCAPE_SCAN_DELAY_MS = 1_500L

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
            val lastGestureAttemptPackage: String = "",
            val lastGestureAttemptWhileForegroundPackage: String = "",
            val lastGestureWindowCount: Int = 0,
            val lastGestureWasDialogDriven: Boolean = false,
            val lastGestureWasHeartbeatDriven: Boolean = false,
            val lastGestureDispatchResult: String = "",
            val lastGestureDispatchReturned: Boolean = false,
            val lastGestureAction: String = "",
            val lastGestureCompletionAt: Long = 0L,
            val lastGestureCancelAt: Long = 0L,
            val lastGestureCancelReasonHint: String = "",
            val lastGesturePackage: String = "",
            val lastGestureZoneIndex: Int = -1,
            val lastGestureCoordinates: String = "",
            val gestureCancellationWarning: Boolean = false,
            val lastDialogDetectionAt: Long = 0L,
            val lastDialogDismissAt: Long = 0L,
            val lastDialogWindowCount: Int = 0,
            val lastDialogTargetText: String = "",
            val lastDialogTargetWindowIndex: Int = -1,
            val lastDialogClickMethod: String = "NONE",
            val lastDialogVisibleTextsSample: String = "",
            val lastDialogPackage: String = "",
            val lastDialogPositivePhrase: String = "",
            val lastDialogConfirmPhrase: String = "",
            val lastDialogNegativeMatch: String = "",
            val lastDialogNoTargetReason: String = "",
            val lastDialogNegativeBlockReason: String = "",
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
            val gestureEngineHealth: String = "OK",
            val wakeAcquires: Long = 0L,
            val wakeReleases: Long = 0L,
            val batteryOptimizationExempt: Boolean = true,
            val wakeLockHeld: Boolean = false,
            val writeSettingsGranted: Boolean = false,
            val screenTimeoutOverrideActive: Boolean = false,
            val originalScreenTimeoutMs: Long = -1L,
            val currentRequestedScreenTimeoutMs: Long = -1L,
            val lastScreenTimeoutApplyAt: Long = 0L,
            val lastScreenTimeoutRestoreAt: Long = 0L,
            val calibrationRecommendedMode: String = "",
            val calibrationRecommendedGesture: String = "",
            val calibrationRecommendedZone: Int = -1,
            val dialogPendingHeartbeatOverride: Boolean = false,
            val antiScreensaverPokeCount: Long = 0L,
            val lastAntiScreensaverPokeAt: Long = 0L,
            val antiScreensaverPokeActive: Boolean = false,
            val screensaverEscapeCount: Long = 0L,
            val lastScreensaverDetectedAt: Long = 0L,
            val lastScreensaverEscapeAt: Long = 0L
        )

        @Volatile
        private var telemetrySnapshot = TelemetrySnapshot()

        fun getTelemetrySnapshot(): TelemetrySnapshot = telemetrySnapshot
    }

    private data class HeartbeatGateDecision(
        val allow: Boolean,
        val reason: String
    )

    private data class DialogObserverGateDecision(
        val allow: Boolean,
        val reason: String
    )

    private data class WindowRootRef(
        val index: Int,
        val windowId: Int,
        val windowType: Int,
        val source: String,
        val root: AccessibilityNodeInfo
    )

    private data class DialogDismissOutcome(
        val strategy: String,
        val targetText: String = "",
        val windowIndex: Int = -1,
        val clickMethod: String = "NONE",
        val visibleSample: String = "",
        val positivePhrase: String = "",
        val confirmPhrase: String = "",
        val blockedReason: String = "",
        val packageName: String = ""
    )

    private data class ProfileDialogScanOutcome(
        val positiveMatchFound: Boolean,
        val dismissOutcome: DialogDismissOutcome? = null
    )

    private data class SafeZoneSelection(
        val index: Int,
        val zone: SafeZone
    )

    private var handler: Handler? = null
    private var heartbeatRunnable: Runnable? = null
    private var dialogScanRunnable: Runnable? = null
    private var packageDialogObserverRunnable: Runnable? = null
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
    private var lastDialogWindowCount = 0
    private var lastDialogTargetText = ""
    private var lastDialogTargetWindowIndex = -1
    private var lastDialogClickMethod = "NONE"
    private var lastDialogVisibleTextsSample = ""
    private var lastDialogPackage = ""
    private var lastDialogPositivePhrase = ""
    private var lastDialogConfirmPhrase = ""
    private var lastDialogNegativeMatch = ""
    private var lastDialogNoTargetReason = ""
    private var lastDialogNegativeBlockReason = ""
    private var lastDialogDismissStrategy = ""
    private var lastPackageTransitionTimestampMs = 0L

    private var dialogBurstModeActive = false
    private var dialogBurstUntilMs = 0L
    private var lastDialogActionFingerprint = ""
    private var lastDialogActionAtMs = 0L

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
    private var lastGestureCancelAtMs = 0L
    private var lastGestureDispatchResult = ""
    private var lastGestureDispatchReturned = false
    private var lastGestureCancelReasonHint = ""
    private var lastGesturePackage = ""
    private var lastGestureZoneIndex = -1
    private var lastGestureCoordinates = ""
    private var lastGestureAction = ""
    private var lastSecondaryAttemptAtMs = 0L

    private var lastGestureAttemptPackage = ""
    private var lastGestureAttemptWhileForegroundPackage = ""
    private var lastGestureWindowCount = 0
    private var lastGestureWasDialogDriven = false
    private var lastGestureWasHeartbeatDriven = false
    private var gestureEngineHealth = "OK"

    private var consecutiveGestureFailures = 0
    private var consecutiveGestureCancels = 0
    private var gestureCancellationWarning = false
    private var escalationStep = 0

    private var shouldRunHeartbeatNowFlag = false
    private var heartbeatSuppressedReason = ""

    // Anti-screensaver poke subsystem
    private var antiScreensaverPokeRunnable: Runnable? = null
    private var antiScreensaverPokeCount = 0L
    private var lastAntiScreensaverPokeAtMs = 0L
    private val antiScreensaverPokeZone = SafeZone(
        xPercent = 0.02f,
        yPercent = 0.02f,
        radiusPx = 4
    )

    // Screensaver detection & escape
    private var lastScreensaverDetectedAtMs = 0L
    private var lastScreensaverEscapeAtMs = 0L
    private var screensaverEscapeCount = 0L

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

        // Screensaver detection - escape before normal processing
        if (isProtectionSessionActive() && isScreensaverPackage(packageName)) {
            attemptScreensaverEscape(packageName)
            return
        }

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
        // Screensaver intercept - don't update currentPackage to a screensaver
        if (isProtectionSessionActive() && isScreensaverPackage(newPackage)) {
            Log.i(TAG, "[SCREENSAVER] package transition to screensaver=$newPackage")
            attemptScreensaverEscape(newPackage)
            return
        }

        val previousPackage = currentPackage
        currentPackage = newPackage
        currentProfile = PackagePolicy.profileForPackage(newPackage)
        lastPackageTransitionTimestampMs = System.currentTimeMillis()
        hybridSwipeToggle = false
        resetEscalationState()
        if (previousPackage != newPackage) {
            clearDialogObserverActionState("package_changed")
        }

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
                runWatchdogChecks()
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

    private fun isDialogPendingForHeartbeatOverride(): Boolean {
        if (dialogDetectedCount == 0L) return false
        if (lastDialogDetectionAtMs <= 0L) return false
        if (lastDialogDismissAtMs >= lastDialogDetectionAtMs) return false

        val pendingDurationMs = System.currentTimeMillis() - lastDialogDetectionAtMs
        if (pendingDurationMs > DIALOG_PENDING_OVERRIDE_TIMEOUT_MS) {
            Log.d(TAG, "[HB] dialog pending override expired after ${pendingDurationMs}ms")
            return false
        }

        Log.d(TAG, "[HB] dialog pending override active: pendingMs=$pendingDurationMs")
        return true
    }

    private fun shouldRunHeartbeatNow(): HeartbeatGateDecision {
        val sessionActive = isProtectionSessionActive()
        val supportedForeground = if (isSupportedActivePackage()) currentPackage else null
        val dialogPending = isDialogPendingForHeartbeatOverride()
        val decision = PlaybackStateManager.shouldRunHeartbeatNow(
            sessionActive,
            supportedForeground,
            dialogPendingOverride = dialogPending
        )
        shouldRunHeartbeatNowFlag = decision.first
        heartbeatSuppressedReason = if (decision.first) "" else decision.second
        Log.d(TAG, "[HB] gated_by_playback=${decision.first} reason=${decision.second} dialogPending=$dialogPending")
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
            syncPackageDialogObserver()
            stopAntiScreensaverPoke()
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
            syncPackageDialogObserver()
            stopAntiScreensaverPoke()
            releaseWakeLock("unsupported-package")
            publishTelemetrySnapshot()
            return
        }

        startPeriodicDialogScan()
        syncPackageDialogObserver()
        ensureWakeLockForSession()
        startAntiScreensaverPoke()

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
        if (!isDialogRelevantEvent(event.eventType)) return

        val packageProfile = PackagePolicy.profileForPackage(packageName)
        val profile = currentProfile ?: packageProfile ?: return

        if (packageProfile == null && !isSupportedActivePackage()) return

        val now = System.currentTimeMillis()
        val prioritizeProfile = shouldPrioritizeDialogProfile(profile, packageName)

        val quickDialogHint = DialogTextMatcher.eventLooksLikeDialog(
            className = event.className,
            eventText = event.text,
            contentDescription = event.contentDescription,
            additionalKeywords = profile.dialogKeywords + profile.dialogPositivePhrases
        )
        val cooldown = if (quickDialogHint || prioritizeProfile) DIALOG_HINT_COOLDOWN_MS else DIALOG_SCAN_COOLDOWN_MS
        if (now - lastDialogCheckTimeMs < cooldown) return

        scanAllWindowsForDialog(
            reason = "event:${event.eventType}",
            profile = profile,
            prioritizeProfile = prioritizeProfile,
            bypassFingerprint = false
        )
    }

    private fun isDialogRelevantEvent(eventType: Int): Boolean {
        return eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            eventType == AccessibilityEvent.TYPE_VIEW_CLICKED
    }

    private fun shouldPrioritizeDialogProfile(profile: StreamingAppProfile, packageName: String = currentPackage): Boolean {
        val playback = PlaybackStateManager.snapshot()
        val packageRelevant =
            currentPackage.startsWith(profile.packagePrefix) ||
                packageName.startsWith(profile.packagePrefix)
        val playbackRelevant =
            playback.activePlaybackPackage?.startsWith(profile.packagePrefix) == true ||
                playback.lastPackageReported?.startsWith(profile.packagePrefix) == true
        return profile.dialogPriority && (packageRelevant || playbackRelevant)
    }

    private fun activeDialogObserverProfile(): StreamingAppProfile? {
        val playback = PlaybackStateManager.snapshot()
        val candidates = buildList {
            if (currentProfile?.dialogPriority == true) add(currentProfile!!)
            val activePlaybackProfile = playback.activePlaybackPackage?.let { PackagePolicy.profileForPackage(it) }
            if (activePlaybackProfile?.dialogPriority == true) add(activePlaybackProfile)
            val recentProfile = playback.lastPackageReported?.let { PackagePolicy.profileForPackage(it) }
            if (recentProfile?.dialogPriority == true) add(recentProfile)
        }

        return candidates
            .distinctBy { it.packagePrefix }
            .maxByOrNull { it.packagePrefix.length }
    }

    private fun shouldRunPackageDialogObserver(): DialogObserverGateDecision {
        if (!isProtectionSessionActive()) return DialogObserverGateDecision(false, "session_inactive")
        val profile = activeDialogObserverProfile() ?: return DialogObserverGateDecision(false, "profile_not_relevant")
        val playback = PlaybackStateManager.snapshot()
        val packageRelevant =
            currentPackage.startsWith(profile.packagePrefix) ||
                playback.activePlaybackPackage?.startsWith(profile.packagePrefix) == true ||
                playback.lastPackageReported?.startsWith(profile.packagePrefix) == true
        if (!packageRelevant) return DialogObserverGateDecision(false, "package_not_relevant")

        val nowElapsed = SystemClock.elapsedRealtime()
        val playingActive = playback.friendlyState == PlaybackFriendlyState.PLAYING_ACTIVE &&
            (playback.activePlaybackPackage?.startsWith(profile.packagePrefix) == true ||
                currentPackage.startsWith(profile.packagePrefix))
        if (playingActive) return DialogObserverGateDecision(true, "playing_active:${profile.packagePrefix}")

        val shortGraceAfterPlayback = playback.lastPackageReported?.startsWith(profile.packagePrefix) == true &&
            playback.lastStateChangeAt > 0L &&
            nowElapsed - playback.lastStateChangeAt <= profile.dialogRecentPlaybackGraceMs

        return if (shortGraceAfterPlayback) {
            DialogObserverGateDecision(true, "short_grace_after_playback:${profile.packagePrefix}")
        } else {
            DialogObserverGateDecision(false, "playback_not_active:${profile.packagePrefix}")
        }
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

                val prioritizeProfile = shouldPrioritizeDialogProfile(profile)
                scanAllWindowsForDialog(
                    reason = "periodic",
                    profile = profile,
                    prioritizeProfile = prioritizeProfile,
                    bypassFingerprint = false
                )
                localHandler.postDelayed(this, computeDialogScanCadenceMs())
            }
        }

        localHandler.postDelayed(dialogScanRunnable!!, computeDialogScanCadenceMs())
    }

    private fun stopPeriodicDialogScan() {
        dialogScanRunnable?.let { handler?.removeCallbacks(it) }
        dialogScanRunnable = null
    }

    private fun syncPackageDialogObserver() {
        val gate = shouldRunPackageDialogObserver()
        if (gate.allow) {
            startPackageDialogObserver()
        } else {
            stopPackageDialogObserver(gate.reason)
        }
    }

    private fun startPackageDialogObserver() {
        if (packageDialogObserverRunnable != null) return
        val localHandler = handler ?: return
        val profile = activeDialogObserverProfile() ?: return
        val cadenceMs = profile.dialogObserverIntervalMs.coerceIn(1_000L, 5_000L)

        Log.i(TAG, "[DIALOG][SCAN] observer started package=${profile.packagePrefix} cadenceMs=$cadenceMs")
        packageDialogObserverRunnable = object : Runnable {
            override fun run() {
                val gate = shouldRunPackageDialogObserver()
                if (!gate.allow) {
                    Log.i(TAG, "[DIALOG][SCAN] observer stopped reason=${gate.reason}")
                    stopPackageDialogObserver(gate.reason)
                    return
                }

                val activeProfile = activeDialogObserverProfile() ?: run {
                    stopPackageDialogObserver("profile_missing")
                    return
                }
                scanAllWindowsForDialog(
                    reason = "observer:${activeProfile.packagePrefix}",
                    profile = activeProfile,
                    prioritizeProfile = true,
                    bypassFingerprint = false
                )

                localHandler.postDelayed(this, activeProfile.dialogObserverIntervalMs.coerceIn(1_000L, 5_000L))
            }
        }

        localHandler.post(packageDialogObserverRunnable!!)
    }

    private fun stopPackageDialogObserver(reason: String = "state_change") {
        packageDialogObserverRunnable?.let { handler?.removeCallbacks(it) }
        if (packageDialogObserverRunnable != null) {
            Log.i(TAG, "[DIALOG][SCAN] observer stopped reason=$reason")
        }
        packageDialogObserverRunnable = null
        clearDialogObserverActionState("observer_stopped:$reason")
    }

    private fun computeDialogScanCadenceMs(): Long {
        if (dialogBurstModeActive) return DIALOG_BURST_SCAN_MS

        val playback = PlaybackStateManager.snapshot()
        val profile = currentProfile
        if (profile != null &&
            profile.dialogPriority &&
            playback.friendlyState == PlaybackFriendlyState.PLAYING_ACTIVE &&
            (currentPackage.startsWith(profile.packagePrefix) ||
                playback.activePlaybackPackage?.startsWith(profile.packagePrefix) == true)
        ) {
            return min(DIALOG_PRIORITY_PLAYBACK_SCAN_MS, profile.dialogObserverIntervalMs.coerceAtLeast(1_000L))
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

    private fun scanAllWindowsForDialog(
        reason: String,
        profile: StreamingAppProfile,
        prioritizeProfile: Boolean = false,
        bypassFingerprint: Boolean = false
    ) {
        val roots = collectDialogWindowRoots()
        if (roots.isEmpty()) {
            lastDialogWindowCount = 0
            lastDialogClickMethod = "NONE"
            lastDialogNoTargetReason = "no_windows"
            publishTelemetrySnapshot()
            return
        }

        val now = System.currentTimeMillis()
        lastDialogWindowCount = roots.size
        lastDialogTargetText = ""
        lastDialogTargetWindowIndex = -1
        lastDialogClickMethod = "NONE"
        lastDialogVisibleTextsSample = ""
        lastDialogPackage = currentPackage
        lastDialogPositivePhrase = ""
        lastDialogConfirmPhrase = ""
        lastDialogNegativeMatch = ""
        lastDialogNoTargetReason = ""
        lastDialogNegativeBlockReason = ""

        if (shouldSkipDialogScanByFingerprint(roots, now, bypassFingerprint)) {
            releaseWindowRoots(roots)
            return
        }

        if (now - lastDialogDismissAtMs > DIALOG_BURST_INACTIVE_MS && escalationStep >= 2) {
            enterDialogBurstMode("escalation")
        }

        lastDialogCheckTimeMs = now
        dialogScanCount++
        Log.d(TAG, "[DIALOG][SCAN] reason=$reason package=$currentPackage windows=${roots.size}")

        val scanOutcome = dismissDialogForProfileAcrossWindows(roots, profile, prioritizeProfile, reason)
        if (!scanOutcome.positiveMatchFound) {
            releaseWindowRoots(roots)
            publishTelemetrySnapshot()
            return
        }

        dialogDetectedCount++
        lastDialogDetectionAtMs = now
        Log.i(TAG, "[DIALOG][MATCH] package=$currentPackage positive=true windows=${roots.size}")

        val dismissOutcome = scanOutcome.dismissOutcome
        if (dismissOutcome != null) {
            dialogDismissedCount++
            lastDialogDismissAtMs = System.currentTimeMillis()
            lastDialogDismissStrategy = dismissOutcome.strategy
            lastDialogTargetText = dismissOutcome.targetText
            lastDialogTargetWindowIndex = dismissOutcome.windowIndex
            lastDialogClickMethod = dismissOutcome.clickMethod
            lastDialogVisibleTextsSample = dismissOutcome.visibleSample
            lastDialogPackage = dismissOutcome.packageName.ifEmpty { currentPackage }
            lastDialogPositivePhrase = dismissOutcome.positivePhrase
            lastDialogConfirmPhrase = dismissOutcome.confirmPhrase
            lastDialogNegativeMatch = ""
            lastDialogNoTargetReason = ""
            lastDialogNegativeBlockReason = dismissOutcome.blockedReason
            dialogBurstModeActive = false
            Log.i(
                TAG,
                "[DIALOG][CLICK] package=${dismissOutcome.packageName.ifEmpty { currentPackage }} strategy=${dismissOutcome.strategy} method=${dismissOutcome.clickMethod} window=${dismissOutcome.windowIndex}"
            )
        } else {
            lastDialogClickMethod = "NONE"
            if (lastDialogNoTargetReason.isBlank()) {
                lastDialogNoTargetReason = "dismiss_action_failed"
            }
            Log.w(TAG, "[DIALOG][BLOCK] package=$currentPackage positive=true but no confirm action succeeded")
            enterDialogBurstMode("dismiss_failed")
        }

        releaseWindowRoots(roots)
        publishTelemetrySnapshot()
    }

    private fun shouldSkipDialogScanByFingerprint(
        roots: List<WindowRootRef>,
        now: Long,
        bypassFingerprint: Boolean
    ): Boolean {
        if (bypassFingerprint) return false

        val fingerprint = buildCombinedWindowFingerprint(roots)
        val isSameWindow = fingerprint == lastDialogWindowFingerprint
        val isCooldownActive = now - lastDialogWindowFingerprintMs < WINDOW_FINGERPRINT_COOLDOWN_MS
        if (isSameWindow && isCooldownActive) {
            lastDialogNoTargetReason = "same_fingerprint_cooldown"
            Log.d(TAG, "[DIALOG][BLOCK] package=$currentPackage reason=same_fingerprint_cooldown")
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

    private fun buildCombinedWindowFingerprint(roots: List<WindowRootRef>): String {
        return roots.joinToString(separator = "#") { ref ->
            "${ref.index}:${ref.windowType}:${buildWindowFingerprint(ref.root)}"
        }
    }

    private fun collectDialogWindowRoots(): List<WindowRootRef> {
        val refs = mutableListOf<WindowRootRef>()
        val seenFingerprints = mutableSetOf<String>()

        val activeRoot = rootInActiveWindow
        if (activeRoot != null) {
            val fingerprint = buildWindowFingerprint(activeRoot)
            if (seenFingerprints.add(fingerprint)) {
                refs.add(
                    WindowRootRef(
                        index = -1,
                        windowId = -1,
                        windowType = -1,
                        source = "activeRoot",
                        root = activeRoot
                    )
                )
            } else {
                safeRecycle(activeRoot)
            }
        }

        val winList = windows ?: emptyList()
        Log.d(TAG, "[DIALOG][WIN] windows available=${winList.size}")
        winList.forEachIndexed { index, window ->
            val root = try {
                window.root
            } catch (_: Exception) {
                null
            }
            Log.d(
                TAG,
                "[DIALOG][WIN] index=$index id=${window.id} type=${windowTypeName(window.type)} hasRoot=${root != null}"
            )

            if (root != null) {
                val fingerprint = buildWindowFingerprint(root)
                if (seenFingerprints.add(fingerprint)) {
                    refs.add(
                        WindowRootRef(
                            index = index,
                            windowId = window.id,
                            windowType = window.type,
                            source = "windows",
                            root = root
                        )
                    )
                } else {
                    safeRecycle(root)
                }
            }
        }

        return refs
    }

    private fun releaseWindowRoots(roots: List<WindowRootRef>) {
        roots.forEach { safeRecycle(it.root) }
    }

    private fun windowTypeName(type: Int): String {
        return when (type) {
            AccessibilityWindowInfo.TYPE_APPLICATION -> "APPLICATION"
            AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "INPUT_METHOD"
            AccessibilityWindowInfo.TYPE_SYSTEM -> "SYSTEM"
            AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "ACCESSIBILITY_OVERLAY"
            AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "SPLIT_SCREEN_DIVIDER"
            else -> type.toString()
        }
    }

    private fun dismissDialogForProfileAcrossWindows(
        roots: List<WindowRootRef>,
        profile: StreamingAppProfile,
        prioritizeProfile: Boolean,
        reason: String
    ): ProfileDialogScanOutcome {
        val packageName = currentPackage
        val isNetflixProfile = packageName.startsWith("com.netflix") || profile.packagePrefix.startsWith("com.netflix")
        val extraNegativePhrases = profile.dialogNegativePhrases + PackagePolicy.genericDialogNegativePhrases
        val confirmCandidates = (profile.dialogConfirmPhrases + profile.confirmKeywords + PackagePolicy.genericDialogConfirmPhrases)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        var positiveMatchFound = false
        var latestVisibleSample = ""
        var latestBlockedReason = ""

        if (prioritizeProfile) {
            Log.i(TAG, "[DIALOG][SCAN] priority profile=${profile.packagePrefix} reason=$reason")
        }

        for (ref in roots) {
            val visibleTexts = mutableListOf<String>()
            collectVisibleTexts(ref.root, visibleTexts, 0, MAX_WINDOW_LOG_TEXTS)
            val clickableTexts = mutableListOf<String>()
            collectClickableTexts(ref.root, clickableTexts, 0, MAX_WINDOW_LOG_CLICKABLE_TEXTS)

            latestVisibleSample = visibleTexts.joinToString(", ").take(320)
            lastDialogVisibleTextsSample = latestVisibleSample
            lastDialogPackage = packageName
            lastDialogNoTargetReason = ""

            Log.d(
                TAG,
                "[DIALOG][WIN] package=$packageName index=${ref.index} source=${ref.source} id=${ref.windowId} type=${windowTypeName(ref.windowType)} visible=${visibleTexts.take(12)} clickable=${clickableTexts.take(8)}"
            )

            val negativeMatch = findNegativePhraseInTexts(
                texts = visibleTexts + clickableTexts,
                extraNegativePhrases = extraNegativePhrases
            )
            if (negativeMatch.isNotEmpty()) {
                lastDialogNegativeMatch = negativeMatch
                Log.d(TAG, "[DIALOG][MATCH] package=$packageName window=${ref.index} negative=\"$negativeMatch\"")
            }

            val positivePhrase = DialogTextMatcher.findDialogPhrase(
                visibleTexts = visibleTexts,
                additionalKeywords = profile.dialogPositivePhrases + profile.dialogKeywords + PackagePolicy.genericDialogPositivePhrases
            )
            if (positivePhrase == null) {
                lastDialogNoTargetReason = "no_positive_phrase"
                Log.d(TAG, "[DIALOG][BLOCK] package=$packageName window=${ref.index} reason=no_positive_phrase")
                continue
            }

            positiveMatchFound = true
            lastDialogPositivePhrase = positivePhrase
            Log.i(TAG, "[DIALOG][MATCH] package=$packageName window=${ref.index} positive=\"$positivePhrase\"")

            for (phrase in confirmCandidates) {
                val exactNode = findNodeByExactText(ref.root, phrase, extraNegativePhrases)
                if (exactNode != null) {
                    Log.i(TAG, "[DIALOG][MATCH] package=$packageName window=${ref.index} exact_confirm=\"$phrase\"")
                }
                val exactOutcome = findAndActivateTargetNode(
                    node = exactNode,
                    windowIndex = ref.index,
                    windowFingerprint = buildWindowFingerprint(ref.root),
                    strategy = "profile-confirm-exact",
                    targetText = phrase,
                    visibleSample = latestVisibleSample,
                    positivePhrase = positivePhrase,
                    confirmPhrase = phrase,
                    packageName = packageName,
                    profile = profile
                )
                safeRecycle(exactNode)
                if (exactOutcome != null) return ProfileDialogScanOutcome(true, exactOutcome)
            }

            for (phrase in confirmCandidates) {
                val containsNode = findNodeContainingText(ref.root, phrase, extraNegativePhrases)
                if (containsNode != null) {
                    Log.i(TAG, "[DIALOG][MATCH] package=$packageName window=${ref.index} contains_confirm=\"$phrase\"")
                }
                val containsOutcome = findAndActivateTargetNode(
                    node = containsNode,
                    windowIndex = ref.index,
                    windowFingerprint = buildWindowFingerprint(ref.root),
                    strategy = "profile-confirm-contains",
                    targetText = phrase,
                    visibleSample = latestVisibleSample,
                    positivePhrase = positivePhrase,
                    confirmPhrase = phrase,
                    packageName = packageName,
                    profile = profile
                )
                safeRecycle(containsNode)
                if (containsOutcome != null) return ProfileDialogScanOutcome(true, containsOutcome)
            }

            val matcherNode = findFirstConfirmNode(
                node = ref.root,
                profile = profile,
                additionalConfirmKeywords = PackagePolicy.genericDialogConfirmPhrases,
                additionalNegativeKeywords = extraNegativePhrases
            )
            val matchedConfirmPhrase = matcherNode?.let {
                DialogTextMatcher.findConfirmPhrase(
                    text = it.text,
                    contentDesc = it.contentDescription,
                    additionalKeywords = profile.confirmKeywords + profile.dialogConfirmPhrases + PackagePolicy.genericDialogConfirmPhrases
                )
            }.orEmpty()
            val matcherOutcome = findAndActivateTargetNode(
                node = matcherNode,
                windowIndex = ref.index,
                windowFingerprint = buildWindowFingerprint(ref.root),
                strategy = "profile-confirm-keyword",
                targetText = matcherNode?.text?.toString().orEmpty(),
                visibleSample = latestVisibleSample,
                positivePhrase = positivePhrase,
                confirmPhrase = matchedConfirmPhrase,
                packageName = packageName,
                profile = profile
            )
            safeRecycle(matcherNode)
            if (matcherOutcome != null) return ProfileDialogScanOutcome(true, matcherOutcome)

            latestBlockedReason = "positive_without_confirm_target"
            lastDialogNoTargetReason = latestBlockedReason
            lastDialogNegativeBlockReason = latestBlockedReason
            Log.w(TAG, "[DIALOG][BLOCK] package=$packageName window=${ref.index} reason=$latestBlockedReason")
        }

        if (positiveMatchFound && profile.allowGenericFallbackAfterPositiveMatch) {
            val fallbackOutcome = dismissDialogGenericAfterPositiveMatch(
                roots = roots,
                profile = profile,
                packageName = packageName,
                visibleSample = latestVisibleSample
            )
            if (fallbackOutcome != null) {
                return ProfileDialogScanOutcome(true, fallbackOutcome)
            }
        } else if (positiveMatchFound) {
            lastDialogNoTargetReason = "generic_fallback_disabled"
            Log.d(TAG, "[DIALOG][BLOCK] package=$packageName generic_fallback_disabled=true")
        }

        if (positiveMatchFound && isNetflixProfile) {
            Log.w(
                TAG,
                "[DIALOG][BLOCK] package=$packageName netflix-specific strategy failed to find target; visible=${latestVisibleSample.take(320)}"
            )
        }

        if (prioritizeProfile && !positiveMatchFound) {
            lastDialogNoTargetReason = "observer_no_positive_phrase"
            Log.d(TAG, "[DIALOG][BLOCK] package=$packageName observer suppressed: no positive dialog phrase")
        }

        if (latestBlockedReason.isNotEmpty()) {
            lastDialogNegativeBlockReason = latestBlockedReason
        }

        return ProfileDialogScanOutcome(positiveMatchFound, null)
    }

    private fun dismissDialogGenericAfterPositiveMatch(
        roots: List<WindowRootRef>,
        profile: StreamingAppProfile,
        packageName: String,
        visibleSample: String
    ): DialogDismissOutcome? {
        for (ref in roots) {
            val node = findFirstConfirmNode(
                node = ref.root,
                profile = profile,
                additionalConfirmKeywords = PackagePolicy.genericDialogConfirmPhrases,
                additionalNegativeKeywords = profile.dialogNegativePhrases + PackagePolicy.genericDialogNegativePhrases
            )
            val confirmPhrase = node?.let {
                DialogTextMatcher.findConfirmPhrase(
                    text = it.text,
                    contentDesc = it.contentDescription,
                    additionalKeywords = profile.confirmKeywords + profile.dialogConfirmPhrases + PackagePolicy.genericDialogConfirmPhrases
                )
            }.orEmpty()
            val outcome = findAndActivateTargetNode(
                node = node,
                windowIndex = ref.index,
                windowFingerprint = buildWindowFingerprint(ref.root),
                strategy = "generic-after-positive",
                targetText = node?.text?.toString().orEmpty(),
                visibleSample = visibleSample,
                positivePhrase = lastDialogPositivePhrase,
                confirmPhrase = confirmPhrase,
                packageName = packageName,
                profile = profile
            )
            safeRecycle(node)
            if (outcome != null) return outcome
        }
        lastDialogNoTargetReason = "generic_fallback_no_confirm_target"
        Log.d(TAG, "[DIALOG][BLOCK] package=$packageName generic fallback skipped because no confirm target")
        return null
    }

    private fun findAndActivateTargetNode(
        node: AccessibilityNodeInfo?,
        windowIndex: Int,
        windowFingerprint: String,
        strategy: String,
        targetText: String,
        visibleSample: String,
        positivePhrase: String,
        confirmPhrase: String,
        packageName: String,
        profile: StreamingAppProfile
    ): DialogDismissOutcome? {
        if (node == null) return null
        val blockedPhrase = DialogTextMatcher.findNegativePhrase(
            text = node.text,
            contentDesc = node.contentDescription,
            additionalKeywords = profile.dialogNegativePhrases + PackagePolicy.genericDialogNegativePhrases
        )
        if (blockedPhrase != null) {
            val reason = "negative_target:$blockedPhrase"
            lastDialogNegativeMatch = blockedPhrase
            lastDialogNoTargetReason = reason
            lastDialogNegativeBlockReason = reason
            Log.w(TAG, "[DIALOG][BLOCK] package=$packageName strategy=$strategy reason=$reason")
            return null
        }

        val normalizedTarget = targetText.ifBlank { node.text?.toString().orEmpty() }.trim()
        val actionFingerprint = "$packageName|$windowIndex|$windowFingerprint|$normalizedTarget|$strategy"
        if (isDialogRepeatActionBlocked(actionFingerprint)) {
            lastDialogNoTargetReason = "anti_repeat_guard"
            Log.w(TAG, "[DIALOG][BLOCK] package=$packageName action blocked by anti-repeat guard")
            return null
        }

        val clickMethod = clickNodeOrClickableAncestorWithMethod(
            node = node,
            allowBoundsTap = profile.useBoundsTapFallback,
            additionalNegativeKeywords = profile.dialogNegativePhrases + PackagePolicy.genericDialogNegativePhrases
        )
        Log.i(
            TAG,
            "[DIALOG][CLICK] package=$packageName strategy=$strategy window=$windowIndex attempted=${clickMethod != null} succeeded=${clickMethod != null} method=${clickMethod ?: "NONE"}"
        )
        if (clickMethod == null) {
            lastDialogNoTargetReason = "click_method_failed"
            return null
        }
        rememberDialogAction(actionFingerprint)
        lastDialogNegativeBlockReason = ""

        return DialogDismissOutcome(
            strategy = strategy,
            targetText = normalizedTarget,
            windowIndex = windowIndex,
            clickMethod = clickMethod,
            visibleSample = visibleSample,
            positivePhrase = positivePhrase,
            confirmPhrase = confirmPhrase,
            blockedReason = "",
            packageName = packageName
        )
    }

    private fun isDialogRepeatActionBlocked(actionFingerprint: String): Boolean {
        val now = System.currentTimeMillis()
        val isSameAction = actionFingerprint == lastDialogActionFingerprint
        val inCooldown = now - lastDialogActionAtMs < DIALOG_ACTION_REPEAT_COOLDOWN_MS
        return isSameAction && inCooldown
    }

    private fun rememberDialogAction(actionFingerprint: String) {
        lastDialogActionFingerprint = actionFingerprint
        lastDialogActionAtMs = System.currentTimeMillis()
    }

    private fun clearDialogObserverActionState(reason: String) {
        lastDialogActionFingerprint = ""
        lastDialogActionAtMs = 0L
        dialogBurstModeActive = false
        dialogBurstUntilMs = 0L
        Log.i(TAG, "[DIALOG][SCAN] action state cleared reason=$reason")
    }

    private fun findNegativePhraseInTexts(texts: List<String>, extraNegativePhrases: List<String>): String {
        for (text in texts) {
            val match = DialogTextMatcher.findNegativePhrase(
                text = text,
                contentDesc = null,
                additionalKeywords = extraNegativePhrases
            )
            if (!match.isNullOrBlank()) return match
        }
        return ""
    }

    private fun collectVisibleTexts(
        node: AccessibilityNodeInfo?,
        out: MutableList<String>,
        depth: Int,
        limit: Int
    ) {
        if (node == null || depth > 5 || out.size >= limit) return

        val txt = node.text?.toString()?.trim().orEmpty()
        if (txt.isNotEmpty()) out.add(txt)

        for (i in 0 until node.childCount) {
            val child = safeGetChild(node, i)
            if (child != null) {
                collectVisibleTexts(child, out, depth + 1, limit)
                safeRecycle(child)
            }
        }
    }

    private fun collectClickableTexts(
        node: AccessibilityNodeInfo?,
        out: MutableList<String>,
        depth: Int,
        limit: Int
    ) {
        if (node == null || depth > 5 || out.size >= limit) return

        val text = node.text?.toString()?.trim().orEmpty()
        if (node.isClickable && text.isNotEmpty() && !DialogTextMatcher.containsNegativeKeyword(text, node.contentDescription)) {
            out.add(text)
        }

        for (i in 0 until node.childCount) {
            val child = safeGetChild(node, i)
            if (child != null) {
                collectClickableTexts(child, out, depth + 1, limit)
                safeRecycle(child)
            }
        }
    }

    private fun findNodeByExactText(
        node: AccessibilityNodeInfo?,
        target: String,
        additionalNegativeKeywords: List<String> = emptyList()
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        val text = node.text?.toString()?.trim().orEmpty()
        if (text == target &&
            !DialogTextMatcher.containsNegativeKeyword(text, node.contentDescription, additionalNegativeKeywords)
        ) {
            return AccessibilityNodeInfo.obtain(node)
        }

        for (i in 0 until node.childCount) {
            val child = safeGetChild(node, i)
            if (child != null) {
                val found = findNodeByExactText(child, target, additionalNegativeKeywords)
                safeRecycle(child)
                if (found != null) return found
            }
        }

        return null
    }

    private fun findNodeContainingText(
        node: AccessibilityNodeInfo?,
        target: String,
        additionalNegativeKeywords: List<String> = emptyList()
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        val text = node.text?.toString()?.trim().orEmpty()
        if (text.contains(target, ignoreCase = true) &&
            !DialogTextMatcher.containsNegativeKeyword(text, node.contentDescription, additionalNegativeKeywords)
        ) {
            return AccessibilityNodeInfo.obtain(node)
        }

        for (i in 0 until node.childCount) {
            val child = safeGetChild(node, i)
            if (child != null) {
                val found = findNodeContainingText(child, target, additionalNegativeKeywords)
                safeRecycle(child)
                if (found != null) return found
            }
        }

        return null
    }

    private fun findFirstConfirmNode(
        node: AccessibilityNodeInfo?,
        profile: StreamingAppProfile,
        additionalConfirmKeywords: List<String> = emptyList(),
        additionalNegativeKeywords: List<String> = emptyList()
    ): AccessibilityNodeInfo? {
        if (node == null) return null
        val isPositiveConfirm = DialogTextMatcher.containsConfirmKeyword(
            text = node.text,
            contentDesc = node.contentDescription,
            additionalKeywords = profile.confirmKeywords + profile.dialogConfirmPhrases + additionalConfirmKeywords
        )
        val isNegative = DialogTextMatcher.containsNegativeKeyword(
            node.text,
            node.contentDescription,
            profile.dialogNegativePhrases + additionalNegativeKeywords
        )
        if (isPositiveConfirm && !isNegative) {
            return AccessibilityNodeInfo.obtain(node)
        }

        for (i in 0 until node.childCount) {
            val child = safeGetChild(node, i)
            if (child != null) {
                val found = findFirstConfirmNode(
                    child,
                    profile,
                    additionalConfirmKeywords,
                    additionalNegativeKeywords
                )
                safeRecycle(child)
                if (found != null) return found
            }
        }

        return null
    }

    private fun clickNodeOrClickableAncestorWithMethod(
        node: AccessibilityNodeInfo,
        allowBoundsTap: Boolean,
        additionalNegativeKeywords: List<String> = emptyList()
    ): String? {
        if (isNegativeActionNode(node, additionalNegativeKeywords)) return null

        // Strategy 1: Direct ACTION_CLICK
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "ACTION_CLICK"

        // Strategy 2: CLICKABLE_ANCESTOR (walk up 4 levels)
        val ancestorResult = tryClickableAncestor(node, additionalNegativeKeywords)
        if (ancestorResult != null) return ancestorResult

        // Strategy 3: Focus then click (critical for Android TV leanback buttons)
        if (node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
            Log.d(TAG, "[DIALOG][CLICK] ACTION_FOCUS succeeded, attempting ACTION_CLICK")
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "FOCUS_THEN_CLICK"
        }

        // Strategy 4: Accessibility focus then click
        if (node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)) {
            Log.d(TAG, "[DIALOG][CLICK] ACTION_ACCESSIBILITY_FOCUS succeeded, attempting ACTION_CLICK")
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "A11Y_FOCUS_THEN_CLICK"
        }

        // Strategy 5: Focus + delayed click (some TV UIs need a brief pause between focus and click)
        if (node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)) {
            Log.d(TAG, "[DIALOG][CLICK] scheduling delayed FOCUS_THEN_CLICK")
            val nodeSnapshot = AccessibilityNodeInfo.obtain(node)
            handler?.postDelayed({
                try {
                    val clicked = nodeSnapshot.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.i(TAG, "[DIALOG][CLICK] delayed FOCUS_THEN_CLICK result=$clicked")
                    if (clicked) {
                        lastDialogClickMethod = "FOCUS_DELAYED_CLICK"
                        lastDialogDismissAtMs = System.currentTimeMillis()
                        dialogDismissedCount++
                        publishTelemetrySnapshot()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "[DIALOG][CLICK] delayed click failed: ${e.message}")
                } finally {
                    safeRecycle(nodeSnapshot)
                }
            }, FOCUS_CLICK_DELAY_MS)
            return "FOCUS_DELAYED_CLICK_PENDING"
        }

        // Strategy 6: BOUNDS_TAP (last resort gesture at node center)
        if (allowBoundsTap && tapBoundsCenterForNode(node, additionalNegativeKeywords)) return "BOUNDS_TAP"
        return null
    }

    private fun tryClickableAncestor(
        node: AccessibilityNodeInfo,
        additionalNegativeKeywords: List<String>
    ): String? {
        var parent = node.parent
        val toRecycle = mutableListOf<AccessibilityNodeInfo>()
        var depth = 0
        while (parent != null && depth < 4) {
            toRecycle.add(parent)
            if (!isNegativeActionNode(parent, additionalNegativeKeywords) && parent.isClickable &&
                parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ) {
                toRecycle.forEach { safeRecycle(it) }
                return "CLICKABLE_ANCESTOR"
            }
            parent = parent.parent
            depth++
        }
        toRecycle.forEach { safeRecycle(it) }
        return null
    }

    private fun tapBoundsCenterForNode(
        node: AccessibilityNodeInfo,
        additionalNegativeKeywords: List<String> = emptyList(),
        packageName: String = currentPackage,
        positiveDialogMatched: Boolean = true
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        if (DialogTextMatcher.containsNegativeKeyword(node.text, node.contentDescription, additionalNegativeKeywords)) {
            return false
        }

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return false

        val display = resources.displayMetrics
        val maxX = max(8f, display.widthPixels.toFloat() - 8f)
        val maxY = max(8f, display.heightPixels.toFloat() - 8f)
        val x = bounds.exactCenterX().coerceIn(8f, maxX)
        val y = bounds.exactCenterY().coerceIn(8f, maxY)

        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, GESTURE_TAP_DURATION_MS))
            .build()

        val coords = "${x.toInt()},${y.toInt()}"
        val windowsCount = windows?.size ?: 0
        val playback = PlaybackStateManager.snapshot()

        lastGestureAttemptPackage = packageName
        lastGestureAttemptWhileForegroundPackage = currentPackage
        lastGestureWindowCount = windowsCount
        lastGestureWasDialogDriven = true
        lastGestureWasHeartbeatDriven = false

        lastGestureAction = "DIALOG_BOUNDS_TAP"
        lastGesturePackage = packageName
        lastGestureZoneIndex = -2
        lastGestureCoordinates = coords
        lastGestureDispatchReturned = false
        gesturesDispatchedCount++
        Log.i(
            TAG,
            "[GESTURE] attempt type=dialog_bounds_tap package=$packageName playback=${playback.friendlyState} mode=${currentMode().name} action=DIALOG_BOUNDS_TAP zone=-2 coords=$coords windows=$windowsCount supported=${isSupportedActivePackage()} positiveDialogMatched=$positiveDialogMatched"
        )
        val accepted = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    gesturesCompletedCount++
                    lastGestureCompletionAtMs = System.currentTimeMillis()
                    lastGestureDispatchResult = "completed"
                    consecutiveGestureCancels = 0
                    updateGestureCancellationWarning()
                    Log.i(TAG, "[GESTURE] callback=completed type=dialog_bounds_tap package=$packageName coords=$coords")
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    gesturesCancelledCount++
                    lastGestureDispatchResult = "cancelled"
                    lastGestureCancelAtMs = System.currentTimeMillis()
                    lastGestureCancelReasonHint = "dialog_bounds_tap_cancelled"
                    consecutiveGestureCancels++
                    updateGestureCancellationWarning()
                    Log.w(
                        TAG,
                        "[GESTURE] callback=cancelled type=dialog_bounds_tap package=$packageName playback=${playback.friendlyState} mode=${currentMode().name} coords=$coords windows=$windowsCount supported=${isSupportedActivePackage()} positiveDialogMatched=$positiveDialogMatched"
                    )
                }
            },
            null
        )
        lastGestureDispatchReturned = accepted
        Log.i(TAG, "[GESTURE] dispatch_returned=$accepted type=dialog_bounds_tap package=$packageName coords=$coords")
        if (!accepted) {
            gesturesDispatchRejectedCount++
            lastGestureDispatchResult = "dispatch_returned_false"
            lastGestureCancelAtMs = System.currentTimeMillis()
            lastGestureCancelReasonHint = "dialog_bounds_tap_dispatch_false"
            updateGestureCancellationWarning()
        }
        return accepted
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

        val zoneSelection = pickSafeZone(profile)
        val action = resolveHeartbeatAction(profile, now)
        heartbeatSequence++

        val dispatched = dispatchHeartbeatGesture(
            action = action,
            zoneSelection = zoneSelection,
            reason = reason,
            secondary = false
        )
        if (!dispatched && escalationStep >= 3) {
            maybeScheduleSecondaryAttempt(profile, zoneSelection)
            enterDialogBurstMode("heartbeat_fail")
        }
    }

    private fun maybeScheduleSecondaryAttempt(profile: StreamingAppProfile, zoneSelection: SafeZoneSelection) {
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
            dispatchHeartbeatGesture(
                action = action,
                zoneSelection = zoneSelection,
                reason = "escalation-double",
                secondary = true
            )
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

    private fun pickSafeZone(profile: StreamingAppProfile): SafeZoneSelection {
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

        return SafeZoneSelection(index = selectedIndex, zone = zones[selectedIndex])
    }

    private fun dispatchHeartbeatGesture(
        action: HeartbeatAction,
        zoneSelection: SafeZoneSelection,
        reason: String,
        secondary: Boolean
    ): Boolean {
        val gestureBuild = when (action) {
            HeartbeatAction.MICRO_TAP -> buildMicroTapGesture(zoneSelection.zone)
            HeartbeatAction.MICRO_SWIPE -> buildMicroSwipeGesture(zoneSelection.zone)
            HeartbeatAction.HYBRID -> buildMicroTapGesture(zoneSelection.zone)
        }
        val gesture = gestureBuild.first
        val coordinates = gestureBuild.second

        val windowsCount = windows?.size ?: 0

        lastGestureAttemptPackage = currentPackage
        lastGestureAttemptWhileForegroundPackage = currentPackage
        lastGestureWindowCount = windowsCount
        lastGestureWasDialogDriven = false
        lastGestureWasHeartbeatDriven = true

        lastHeartbeatExecutedAtMs = System.currentTimeMillis()
        lastGestureAction = action.name
        lastGesturePackage = currentPackage
        lastGestureZoneIndex = zoneSelection.index
        lastGestureCoordinates = coordinates
        lastGestureDispatchReturned = false
        lastGestureCancelReasonHint = ""

        if (consecutiveGestureCancels >= GESTURE_CANCEL_BACKOFF_THRESHOLD) {
            Log.w(TAG, "[GESTURE] skipped type=heartbeat package=$currentPackage reason=backoff consecutiveCancels=$consecutiveGestureCancels")
            lastGestureCancelReasonHint = "backoff_threshold_reached_health_$gestureEngineHealth"
            publishTelemetrySnapshot()
            return false
        }

        if (gesture == null) {
            onGestureDispatchRejected("gesture_build_failed")
            return false
        }

        gesturesDispatchedCount++
        val playback = PlaybackStateManager.snapshot()
        Log.i(
            TAG,
            "[GESTURE] attempt type=heartbeat package=$currentPackage playback=${playback.friendlyState} mode=${currentMode().name} action=${action.name} zone=${zoneSelection.index} coords=$coordinates windows=$windowsCount supported=${isSupportedActivePackage()} positiveDialogMatched=false reason=$reason secondary=$secondary"
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
                    lastGestureCancelReasonHint = ""
                    updateGestureCancellationWarning()
                    Log.i(
                        TAG,
                        "[GESTURE] callback=completed type=heartbeat package=$currentPackage action=${action.name} zone=${zoneSelection.index} coords=$coordinates"
                    )
                    updateEscalationStep()
                    publishTelemetrySnapshot()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    gesturesCancelledCount++
                    lastGestureDispatchResult = "cancelled"
                    lastGestureCancelAtMs = System.currentTimeMillis()
                    lastGestureCancelReasonHint = "callback_cancelled"
                    consecutiveGestureCancels++
                    consecutiveGestureFailures++
                    updateGestureCancellationWarning()
                    Log.w(
                        TAG,
                        "[GESTURE] callback=cancelled type=heartbeat package=$currentPackage playback=${playback.friendlyState} mode=${currentMode().name} action=${action.name} zone=${zoneSelection.index} coords=$coordinates windows=$windowsCount supported=${isSupportedActivePackage()} positiveDialogMatched=false"
                    )
                    updateEscalationStep()
                    enterDialogBurstMode("gesture_cancelled")
                    publishTelemetrySnapshot()
                }
            },
            null
        )
        lastGestureDispatchReturned = accepted
        Log.i(
            TAG,
            "[GESTURE] dispatch_returned=$accepted type=heartbeat package=$currentPackage action=${action.name} zone=${zoneSelection.index} coords=$coordinates"
        )

        if (!accepted) {
            onGestureDispatchRejected("dispatch_returned_false")
            return false
        }

        publishTelemetrySnapshot()
        return true
    }

    private fun onGestureDispatchRejected(result: String) {
        gesturesDispatchRejectedCount++
        lastGestureDispatchResult = result
        lastGestureDispatchReturned = false
        lastGestureCancelAtMs = System.currentTimeMillis()
        lastGestureCancelReasonHint = result
        consecutiveGestureFailures++
        updateGestureCancellationWarning()
        Log.w(
            TAG,
            "[GESTURE] dispatch_failed package=$currentPackage mode=${currentMode().name} result=$result zone=$lastGestureZoneIndex coords=$lastGestureCoordinates"
        )
        updateEscalationStep()
        enterDialogBurstMode("dispatch_rejected")
        publishTelemetrySnapshot()
    }

    private fun updateGestureCancellationWarning() {
        gestureCancellationWarning = consecutiveGestureCancels >= GESTURE_CANCEL_BACKOFF_THRESHOLD
        gestureEngineHealth = when {
            consecutiveGestureCancels >= GESTURE_CANCEL_BACKOFF_THRESHOLD -> "BROKEN"
            consecutiveGestureCancels > 0 && gesturesCompletedCount == 0L -> "BROKEN"
            consecutiveGestureCancels > 0 -> "DEGRADED"
            else -> "OK"
        }
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
        gestureCancellationWarning = false
        escalationStep = 0
    }

    private fun buildMicroTapGesture(zone: SafeZone): Pair<GestureDescription?, String> {
        val point = toSafePoint(zone)
        val path = Path().apply { moveTo(point.x, point.y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, GESTURE_TAP_DURATION_MS))
            .build()
        return gesture to "${point.x.toInt()},${point.y.toInt()}"
    }

    private fun buildMicroSwipeGesture(zone: SafeZone): Pair<GestureDescription?, String> {
        val start = toSafePoint(zone)
        val delta = 3f
        val endX = clamp(start.x + delta, 8f, max(8f, resources.displayMetrics.widthPixels - 8f))
        val endY = clamp(start.y + delta, 8f, max(8f, resources.displayMetrics.heightPixels - 8f))

        val path = Path().apply {
            moveTo(start.x, start.y)
            lineTo(endX, endY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, GESTURE_SWIPE_DURATION_MS))
            .build()
        return gesture to "${start.x.toInt()},${start.y.toInt()} -> ${endX.toInt()},${endY.toInt()}"
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

    private fun isBatteryOptimizationExempt(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        } catch (_: Exception) {
            true
        }
    }

    // ==========================================
    // Anti-Screensaver Poke
    // ==========================================

    private fun startAntiScreensaverPoke() {
        if (antiScreensaverPokeRunnable != null) return
        val localHandler = handler ?: return

        Log.i(TAG, "[POKE] anti-screensaver poke started")

        antiScreensaverPokeRunnable = object : Runnable {
            override fun run() {
                if (!isProtectionSessionActive() || !isSupportedActivePackage()) {
                    Log.i(TAG, "[POKE] stopping: session or package no longer active")
                    stopAntiScreensaverPoke()
                    return
                }

                performAntiScreensaverPoke()

                val nextDelay = ANTI_SCREENSAVER_POKE_INTERVAL_MS +
                    Random.nextLong(-ANTI_SCREENSAVER_POKE_JITTER_MS, ANTI_SCREENSAVER_POKE_JITTER_MS + 1)
                localHandler.postDelayed(this, nextDelay.coerceAtLeast(45_000L))
            }
        }

        val initialDelay = ANTI_SCREENSAVER_POKE_INTERVAL_MS +
            Random.nextLong(-ANTI_SCREENSAVER_POKE_JITTER_MS, ANTI_SCREENSAVER_POKE_JITTER_MS + 1)
        localHandler.postDelayed(antiScreensaverPokeRunnable!!, initialDelay.coerceAtLeast(45_000L))
    }

    private fun stopAntiScreensaverPoke() {
        antiScreensaverPokeRunnable?.let { handler?.removeCallbacks(it) }
        if (antiScreensaverPokeRunnable != null) {
            Log.i(TAG, "[POKE] anti-screensaver poke stopped")
        }
        antiScreensaverPokeRunnable = null
    }

    private fun performAntiScreensaverPoke() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return

        val point = toSafePoint(antiScreensaverPokeZone)
        val path = Path().apply { moveTo(point.x, point.y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, GESTURE_TAP_DURATION_MS))
            .build()

        val coords = "${point.x.toInt()},${point.y.toInt()}"
        Log.d(TAG, "[POKE] dispatching anti-screensaver micro-tap coords=$coords")

        val accepted = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    antiScreensaverPokeCount++
                    lastAntiScreensaverPokeAtMs = System.currentTimeMillis()
                    Log.d(TAG, "[POKE] completed count=$antiScreensaverPokeCount coords=$coords")
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.w(TAG, "[POKE] cancelled coords=$coords")
                }
            },
            null
        )

        if (!accepted) {
            Log.w(TAG, "[POKE] dispatch rejected coords=$coords")
        }
    }

    // ==========================================
    // Screensaver Detection & Escape
    // ==========================================

    private fun isScreensaverPackage(packageName: String): Boolean {
        val lower = packageName.lowercase()
        return SCREENSAVER_PACKAGES.any { lower.startsWith(it) } ||
            lower.contains("dream") ||
            lower.contains("screensaver")
    }

    private fun attemptScreensaverEscape(triggerPackage: String): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastScreensaverEscapeAtMs < SCREENSAVER_ESCAPE_COOLDOWN_MS) {
            Log.d(TAG, "[SCREENSAVER] escape skipped: cooldown active")
            return false
        }

        Log.i(TAG, "[SCREENSAVER] detected package=$triggerPackage; performing GLOBAL_ACTION_BACK")
        lastScreensaverDetectedAtMs = now
        val result = performGlobalAction(GLOBAL_ACTION_BACK)
        if (result) {
            lastScreensaverEscapeAtMs = now
            screensaverEscapeCount++
            Log.i(TAG, "[SCREENSAVER] escape dispatched successfully count=$screensaverEscapeCount")

            handler?.postDelayed({
                Log.i(TAG, "[SCREENSAVER] post-escape scan triggered")
                updatePlaybackSignals("screensaver-escape")
                refreshSessionState(reason = "screensaver-escape")
                val profile = currentProfile
                if (profile != null && isSupportedActivePackage()) {
                    scanAllWindowsForDialog(
                        reason = "screensaver-escape",
                        profile = profile,
                        prioritizeProfile = true,
                        bypassFingerprint = true
                    )
                }
            }, SCREENSAVER_POST_ESCAPE_SCAN_DELAY_MS)
        } else {
            Log.w(TAG, "[SCREENSAVER] GLOBAL_ACTION_BACK returned false")
        }
        publishTelemetrySnapshot()
        return result
    }

    private fun runWatchdogChecks() {
        if (!isProtectionSessionActive()) return
        if (ProtectionSessionManager.isForegroundServiceRunning(this)) return

        Log.w(
            TAG,
            "[WATCHDOG] protection active but foreground companion not marked running; requesting refresh"
        )
        try {
            ContextCompat.startForegroundService(
                this,
                ProtectionSessionService.createRefreshIntent(this)
            )
        } catch (e: Exception) {
            Log.e(TAG, "[WATCHDOG] refresh request failed: ${e.message}", e)
        }
    }

    // ==========================================
    // Shared helpers
    // ==========================================

    private fun isNegativeActionNode(
        node: AccessibilityNodeInfo,
        additionalNegativeKeywords: List<String> = emptyList()
    ): Boolean {
        return DialogTextMatcher.containsNegativeKeyword(
            node.text,
            node.contentDescription,
            additionalNegativeKeywords
        )
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
        stopPackageDialogObserver()
        stopPackageProbe()
        stopAntiScreensaverPoke()
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
            lastDialogWindowCount = 0
            lastDialogTargetText = ""
            lastDialogTargetWindowIndex = -1
            lastDialogClickMethod = "NONE"
            lastDialogVisibleTextsSample = ""
            lastDialogPackage = ""
            lastDialogPositivePhrase = ""
            lastDialogConfirmPhrase = ""
            lastDialogNegativeMatch = ""
            lastDialogNoTargetReason = ""
            lastDialogNegativeBlockReason = ""
            lastGestureCancelReasonHint = ""
            lastGesturePackage = ""
            lastGestureZoneIndex = -1
            lastGestureCoordinates = ""
            lastAntiScreensaverPokeAtMs = 0L
            lastScreensaverDetectedAtMs = 0L
            lastScreensaverEscapeAtMs = 0L
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
            lastGestureAttemptPackage = lastGestureAttemptPackage,
            lastGestureAttemptWhileForegroundPackage = lastGestureAttemptWhileForegroundPackage,
            lastGestureWindowCount = lastGestureWindowCount,
            lastGestureWasDialogDriven = lastGestureWasDialogDriven,
            lastGestureWasHeartbeatDriven = lastGestureWasHeartbeatDriven,
            lastGestureDispatchResult = lastGestureDispatchResult,
            lastGestureDispatchReturned = lastGestureDispatchReturned,
            lastGestureAction = lastGestureAction,
            lastGestureCompletionAt = lastGestureCompletionAtMs,
            lastGestureCancelAt = lastGestureCancelAtMs,
            lastGestureCancelReasonHint = lastGestureCancelReasonHint,
            lastGesturePackage = lastGesturePackage,
            lastGestureZoneIndex = lastGestureZoneIndex,
            lastGestureCoordinates = lastGestureCoordinates,
            gestureCancellationWarning = gestureCancellationWarning,
            lastDialogDetectionAt = lastDialogDetectionAtMs,
            lastDialogDismissAt = lastDialogDismissAtMs,
            lastDialogWindowCount = lastDialogWindowCount,
            lastDialogTargetText = lastDialogTargetText,
            lastDialogTargetWindowIndex = lastDialogTargetWindowIndex,
            lastDialogClickMethod = lastDialogClickMethod,
            lastDialogVisibleTextsSample = lastDialogVisibleTextsSample,
            lastDialogPackage = lastDialogPackage,
            lastDialogPositivePhrase = lastDialogPositivePhrase,
            lastDialogConfirmPhrase = lastDialogConfirmPhrase,
            lastDialogNegativeMatch = lastDialogNegativeMatch,
            lastDialogNoTargetReason = lastDialogNoTargetReason,
            lastDialogNegativeBlockReason = lastDialogNegativeBlockReason,
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
            gestureEngineHealth = gestureEngineHealth,
            wakeAcquires = wakeLockAcquireCount,
            wakeReleases = wakeLockReleaseCount,
            batteryOptimizationExempt = isBatteryOptimizationExempt(),
            wakeLockHeld = wakeLock?.isHeld == true,
            writeSettingsGranted = ScreenTimeoutManager.canWriteSystemSettings(this),
            screenTimeoutOverrideActive = ScreenTimeoutManager.isSessionTimeoutApplied(this),
            originalScreenTimeoutMs = ScreenTimeoutManager.savedOriginalTimeoutMs(this) ?: -1L,
            currentRequestedScreenTimeoutMs = ScreenTimeoutManager.currentRequestedTimeoutMs(this) ?: -1L,
            lastScreenTimeoutApplyAt = ScreenTimeoutManager.lastApplyAt(this),
            lastScreenTimeoutRestoreAt = ScreenTimeoutManager.lastRestoreAt(this),
            calibrationRecommendedMode = calibration?.preferredMode?.name.orEmpty(),
            calibrationRecommendedGesture = calibration?.preferredAction?.name.orEmpty(),
            calibrationRecommendedZone = calibration?.preferredSafeZoneIndex ?: -1,
            dialogPendingHeartbeatOverride = isDialogPendingForHeartbeatOverride(),
            antiScreensaverPokeCount = antiScreensaverPokeCount,
            lastAntiScreensaverPokeAt = lastAntiScreensaverPokeAtMs,
            antiScreensaverPokeActive = antiScreensaverPokeRunnable != null,
            screensaverEscapeCount = screensaverEscapeCount,
            lastScreensaverDetectedAt = lastScreensaverDetectedAtMs,
            lastScreensaverEscapeAt = lastScreensaverEscapeAtMs
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
