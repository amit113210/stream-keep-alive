package com.keepalive.yesplus

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
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
 * Accessibility service that keeps streaming apps alive on Android TV devices.
 *
 * Layer 1: Detect and dismiss "still watching" dialogs.
 * Layer 2: Periodically simulate low-impact activity to prevent inactivity prompts.
 */
class KeepAliveAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "StreamKeepAlive"

        private const val CHECK_COOLDOWN_MS = 2500L
        private const val CHECK_COOLDOWN_HINT_MS = 700L
        private const val WINDOW_FINGERPRINT_COOLDOWN_MS = 3000L

        private const val MAX_SCAN_NODES = 220
        private const val MAX_SCAN_DEPTH = 14
        private const val SCAN_TIME_BUDGET_MS = 15L

        private const val PLAYBACK_SIGNAL_STALE_MS = 2L * 60L * 1000L
        private const val MAX_IDLE_BACKOFF_LEVEL = 3

        private const val WAKELOCK_SAFETY_TIMEOUT_MS = 3L * 60L * 60L * 1000L

        @Volatile
        var isRunning = false
            private set

        data class TelemetrySnapshot(
            val activePackage: String = "",
            val baseIntervalMinutes: Long = 0L,
            val nextIntervalMinutes: Long = 0L,
            val dialogScans: Long = 0L,
            val dialogsDetected: Long = 0L,
            val dialogsDismissed: Long = 0L,
            val gesturesOk: Long = 0L,
            val gesturesFail: Long = 0L,
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
    private var periodicRunnable: Runnable? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockSafetyRunnable: Runnable? = null

    private var currentActivePackage = ""
    private var currentBaseIntervalMs = PackagePolicy.DEFAULT_INTERVAL_MS
    private var currentScheduledIntervalMs = PackagePolicy.DEFAULT_INTERVAL_MS
    private var idleBackoffLevel = 0

    private var lastDialogCheckTime = 0L
    private var lastDialogWindowFingerprint = ""
    private var lastDialogWindowFingerprintTime = 0L
    private var lastPlaybackSignalTime = 0L
    private var wakeLockLastTouchedAt = 0L
    private var wakeLockHeldSince = 0L
    private var wakeLockAcquireCount = 0
    private var wakeLockReleaseCount = 0
    private var dialogScanCount = 0L
    private var dialogDetectedCount = 0L
    private var dialogDismissedCount = 0L
    private var gestureDispatchCount = 0L
    private var gestureDispatchFailureCount = 0L
    private var simulateActivityCount = 0L

    override fun onServiceConnected() {
        try {
            super.onServiceConnected()
            isRunning = true
            handler = Handler(Looper.getMainLooper())
            Log.i(TAG, "Accessibility Service connected - Stream Keep Alive is active")
        } catch (e: Exception) {
            Log.e(TAG, "Error in onServiceConnected: ${e.message}", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event == null) return
            val packageName = event.packageName?.toString() ?: return
            val eventType = event.eventType

            val shouldHandlePackageTransition =
                packageName != currentActivePackage &&
                    (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || currentActivePackage.isEmpty())
            if (shouldHandlePackageTransition) {
                currentActivePackage = packageName
                handleActivePackageChanged(packageName)
            }

            if (!PackagePolicy.isStreamingPackage(packageName)) return

            touchWakeLockSafetyTimer()
            updatePlaybackSignal(event)

            if (!isDialogRelevantEvent(eventType)) return

            val now = System.currentTimeMillis()
            val quickDialogHint = DialogTextMatcher.eventLooksLikeDialog(
                className = event.className,
                eventText = event.text,
                contentDescription = event.contentDescription
            )
            val cooldownMs = if (quickDialogHint) CHECK_COOLDOWN_HINT_MS else CHECK_COOLDOWN_MS
            if (now - lastDialogCheckTime < cooldownMs) return

            val rootNode = rootInActiveWindow ?: return
            if (shouldSkipDialogScanByFingerprint(rootNode, now)) {
                safeRecycle(rootNode)
                return
            }

            lastDialogCheckTime = now
            dialogScanCount++
            checkAndDismissDialog(rootNode)
            publishTelemetrySnapshot()
            safeRecycle(rootNode)
        } catch (e: Exception) {
            Log.e(TAG, "Error in onAccessibilityEvent: ${e.message}", e)
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility Service interrupted")
        cleanupRuntimeResources(keepHandler = true, clearPackageState = true)
    }

    override fun onDestroy() {
        isRunning = false
        cleanupRuntimeResources(keepHandler = false, clearPackageState = true)
        handler = null
        Log.i(TAG, "Accessibility Service destroyed")
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isRunning = false
        cleanupRuntimeResources(keepHandler = false, clearPackageState = true)
        handler = null
        Log.i(TAG, "Accessibility Service unbound")
        return super.onUnbind(intent)
    }

    // ==========================================
    // Layer 1: Dialog Detection & Auto-Dismiss
    // ==========================================

    private fun isDialogRelevantEvent(eventType: Int): Boolean {
        return eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
    }

    private fun updatePlaybackSignal(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_VIEW_CLICKED -> lastPlaybackSignalTime = System.currentTimeMillis()
        }
    }

    private fun shouldSkipDialogScanByFingerprint(rootNode: AccessibilityNodeInfo, now: Long): Boolean {
        val fingerprint = buildWindowFingerprint(rootNode)
        val isSameWindow = fingerprint == lastDialogWindowFingerprint
        val isCooldownActive = now - lastDialogWindowFingerprintTime < WINDOW_FINGERPRINT_COOLDOWN_MS
        if (isSameWindow && isCooldownActive) {
            return true
        }
        lastDialogWindowFingerprint = fingerprint
        lastDialogWindowFingerprintTime = now
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

    private fun checkAndDismissDialog(rootNode: AccessibilityNodeInfo) {
        val detectionBudget = ScanBudget(deadlineMs = System.currentTimeMillis() + SCAN_TIME_BUDGET_MS)
        if (!containsDialogKeywords(rootNode, depth = 0, budget = detectionBudget)) return

        dialogDetectedCount++
        logScreenInteractiveState()
        Log.i(TAG, "Detected 'still watching' dialog - dismissing")

        val clickBudget = ScanBudget(deadlineMs = System.currentTimeMillis() + SCAN_TIME_BUDGET_MS)
        if (findAndClickConfirmButton(rootNode, depth = 0, budget = clickBudget)) {
            dialogDismissedCount++
            Log.i(TAG, "Dialog dismissed with confirm keyword")
            publishTelemetrySnapshot()
            return
        }

        if (clickFocusedElement(rootNode)) {
            dialogDismissedCount++
            Log.i(TAG, "Dialog dismissed via focused element")
            publishTelemetrySnapshot()
            return
        }

        val fallbackBudget = ScanBudget(deadlineMs = System.currentTimeMillis() + SCAN_TIME_BUDGET_MS)
        if (clickFirstButton(rootNode, depth = 0, budget = fallbackBudget)) {
            dialogDismissedCount++
            Log.i(TAG, "Dialog dismissed via fallback clickable node")
            publishTelemetrySnapshot()
            return
        }

        Log.i(TAG, "No actionable node found, trying BACK")
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    private fun containsDialogKeywords(
        node: AccessibilityNodeInfo?,
        depth: Int,
        budget: ScanBudget
    ): Boolean {
        if (node == null || !budget.canVisit(depth)) return false

        try {
            if (DialogTextMatcher.containsDialogKeyword(node.text)) return true
            if (DialogTextMatcher.containsDialogKeyword(node.contentDescription)) return true

            for (i in 0 until node.childCount) {
                val child = try {
                    node.getChild(i)
                } catch (_: Exception) {
                    null
                }
                if (child != null) {
                    val found = containsDialogKeywords(child, depth + 1, budget)
                    safeRecycle(child)
                    if (found) return true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning dialog keywords: ${e.message}")
        }

        return false
    }

    private fun findAndClickConfirmButton(
        node: AccessibilityNodeInfo?,
        depth: Int,
        budget: ScanBudget
    ): Boolean {
        if (node == null || !budget.canVisit(depth)) return false

        try {
            if (
                node.isClickable &&
                DialogTextMatcher.containsConfirmKeyword(node.text, node.contentDescription) &&
                !DialogTextMatcher.containsNegativeKeyword(node.text, node.contentDescription)
            ) {
                if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
            }

            for (i in 0 until node.childCount) {
                val child = try {
                    node.getChild(i)
                } catch (_: Exception) {
                    null
                }
                if (child != null) {
                    val found = findAndClickConfirmButton(child, depth + 1, budget)
                    safeRecycle(child)
                    if (found) return true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding confirm button: ${e.message}")
        }
        return false
    }

    private fun clickFirstButton(
        node: AccessibilityNodeInfo?,
        depth: Int,
        budget: ScanBudget
    ): Boolean {
        if (node == null || !budget.canVisit(depth)) return false

        try {
            val className = node.className?.toString().orEmpty()
            val looksClickableClass = className.contains("Button", ignoreCase = true) ||
                className == "android.view.View" ||
                className == "android.view.ViewGroup"
            if (node.isClickable && looksClickableClass && !isNegativeActionNode(node)) {
                if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
            }

            for (i in 0 until node.childCount) {
                val child = try {
                    node.getChild(i)
                } catch (_: Exception) {
                    null
                }
                if (child != null) {
                    val found = clickFirstButton(child, depth + 1, budget)
                    safeRecycle(child)
                    if (found) return true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error clicking fallback button: ${e.message}")
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

            if (isNegativeActionNode(focusedNode)) return false

            if (focusedNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            focusedNode.performAction(AccessibilityNodeInfo.ACTION_SELECT)
            focusedNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            if (focusedNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true

            // TV UIs often wrap buttons in clickable parents.
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
            Log.e(TAG, "Error clicking focused element: ${e.message}")
        } finally {
            toRecycle.forEach { safeRecycle(it) }
        }
        return false
    }

    // ==========================================
    // Layer 2: Periodic Activity Simulation
    // ==========================================

    private fun handleActivePackageChanged(packageName: String) {
        val isStreaming = PackagePolicy.isStreamingPackage(packageName)
        if (!isStreaming) {
            idleBackoffLevel = 0
            stopPeriodicActivity()
            releaseWakeLock()
            publishTelemetrySnapshot()
            return
        }

        val desiredInterval = PackagePolicy.intervalForPackage(packageName)
        val intervalChanged = desiredInterval != currentBaseIntervalMs
        currentBaseIntervalMs = desiredInterval
        currentScheduledIntervalMs = desiredInterval
        idleBackoffLevel = 0

        if (wakeLock?.isHeld != true) acquireWakeLock()
        touchWakeLockSafetyTimer()

        if (periodicRunnable == null || intervalChanged) {
            startPeriodicActivity(currentBaseIntervalMs)
        }
        publishTelemetrySnapshot()
    }

    private fun startPeriodicActivity(initialIntervalMs: Long) {
        try {
            stopPeriodicActivity()
            currentScheduledIntervalMs = initialIntervalMs

            periodicRunnable = object : Runnable {
                override fun run() {
                    if (wakeLock?.isHeld != true && PackagePolicy.isStreamingPackage(currentActivePackage)) {
                        acquireWakeLock()
                    }

                    simulateActivity()

                    val nextInterval = computeNextIntervalMs()
                    currentScheduledIntervalMs = nextInterval
                    handler?.postDelayed(this, nextInterval)
                }
            }

            handler?.postDelayed(periodicRunnable!!, initialIntervalMs)
            Log.i(TAG, "Periodic activity started (${initialIntervalMs / 60000} min)")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting periodic activity: ${e.message}", e)
        }
    }

    private fun stopPeriodicActivity() {
        try {
            periodicRunnable?.let { handler?.removeCallbacks(it) }
            periodicRunnable = null
            Log.i(TAG, "Periodic activity stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping periodic activity: ${e.message}")
        }
    }

    private fun computeNextIntervalMs(): Long {
        val now = System.currentTimeMillis()
        val hasRecentPlaybackSignal = now - lastPlaybackSignalTime <= PLAYBACK_SIGNAL_STALE_MS
        if (hasRecentPlaybackSignal) {
            idleBackoffLevel = 0
            return currentBaseIntervalMs
        }

        idleBackoffLevel = min(idleBackoffLevel + 1, MAX_IDLE_BACKOFF_LEVEL)
        val multiplier = 1L shl idleBackoffLevel
        val backedOff = currentBaseIntervalMs * multiplier
        return min(backedOff, PackagePolicy.MAX_BACKOFF_INTERVAL_MS)
    }

    private fun simulateActivity() {
        try {
            if (!PackagePolicy.isStreamingPackage(currentActivePackage)) return

            simulateActivityCount++
            pokeUserActivity()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                simulateGesture()
            }

            Log.i(
                TAG,
                "Activity simulated (base=${currentBaseIntervalMs / 60000}m, next=${currentScheduledIntervalMs / 60000}m)"
            )
            if (simulateActivityCount % 5L == 0L) {
                logTelemetrySnapshot("periodic")
            }
            publishTelemetrySnapshot()
        } catch (e: Exception) {
            Log.e(TAG, "Error simulating activity: ${e.message}", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun pokeUserActivity() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val screenLock = pm.newWakeLock(
                PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "StreamKeepAlive::ScreenPoke"
            )
            screenLock.acquire(1000L)
        } catch (e: Exception) {
            Log.e(TAG, "Error poking screen: ${e.message}", e)
        }
    }

    private fun simulateGesture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return

        try {
            val metrics = resources.displayMetrics
            val maxX = max(1, metrics.widthPixels - 1).toFloat()
            val maxY = max(1, metrics.heightPixels - 1).toFloat()
            val safeMinX = min(50f, maxX)
            val safeMinY = min(50f, maxY)

            val baseX = max(metrics.widthPixels * 0.02f, safeMinX)
            val baseY = max(metrics.heightPixels * 0.02f, safeMinY)
            val jitterX = Random.nextFloat() * 2f - 1f
            val jitterY = Random.nextFloat() * 2f - 1f

            val startX = clamp(baseX + jitterX, safeMinX, maxX)
            val startY = clamp(baseY + jitterY, safeMinY, maxY)
            val endX = clamp(startX + 2f, safeMinX, maxX)
            val endY = clamp(startY + 2f, safeMinY, maxY)

            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }

            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
                .build()

            val dispatched = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        Log.d(TAG, "Gesture dispatch completed")
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        Log.w(TAG, "Gesture dispatch cancelled")
                    }
                },
                null
            )

            if (dispatched) {
                gestureDispatchCount++
            } else {
                gestureDispatchFailureCount++
                Log.w(TAG, "Gesture dispatch failed")
            }
        } catch (e: Exception) {
            gestureDispatchFailureCount++
            Log.e(TAG, "Error simulating gesture: ${e.message}", e)
        }
    }

    // ==========================================
    // WakeLock Management
    // ==========================================

    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        try {
            if (wakeLock?.isHeld == true) return
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "StreamKeepAlive::KeepAliveLock"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }

            wakeLockHeldSince = System.currentTimeMillis()
            wakeLockAcquireCount++
            touchWakeLockSafetyTimer()

            Log.i(
                TAG,
                "WakeLock acquired (#$wakeLockAcquireCount, held=${wakeLockHeldSince})"
            )
            publishTelemetrySnapshot()
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring WakeLock: ${e.message}", e)
        }
    }

    private fun touchWakeLockSafetyTimer() {
        wakeLockLastTouchedAt = System.currentTimeMillis()
        wakeLockSafetyRunnable?.let { handler?.removeCallbacks(it) }

        lateinit var runnable: Runnable
        runnable = Runnable {
            val inactiveForMs = System.currentTimeMillis() - wakeLockLastTouchedAt
            if (wakeLock?.isHeld == true && inactiveForMs >= WAKELOCK_SAFETY_TIMEOUT_MS) {
                Log.w(TAG, "WakeLock safety timeout reached; releasing")
                releaseWakeLock()
                return@Runnable
            }

            val remaining = WAKELOCK_SAFETY_TIMEOUT_MS - inactiveForMs
            if (remaining > 0) {
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
                    Log.i(TAG, "WakeLock released (#$wakeLockReleaseCount, heldMs=$heldMs)")
                }
            }
            wakeLock = null
            publishTelemetrySnapshot()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WakeLock: ${e.message}", e)
        }
    }

    private fun clamp(value: Float, min: Float, max: Float): Float {
        return value.coerceIn(min, max)
    }

    private fun isNegativeActionNode(node: AccessibilityNodeInfo): Boolean {
        return DialogTextMatcher.containsNegativeKeyword(node.text, node.contentDescription)
    }

    private fun cleanupRuntimeResources(keepHandler: Boolean, clearPackageState: Boolean) {
        try {
            stopPeriodicActivity()
            releaseWakeLock()
            if (!keepHandler) {
                handler?.removeCallbacksAndMessages(null)
            }
            if (clearPackageState) {
                currentActivePackage = ""
                currentScheduledIntervalMs = PackagePolicy.DEFAULT_INTERVAL_MS
                idleBackoffLevel = 0
            }
            publishTelemetrySnapshot()
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup: ${e.message}", e)
        }
    }

    private fun logScreenInteractiveState() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val interactive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
                pm.isInteractive
            } else {
                @Suppress("DEPRECATION")
                pm.isScreenOn
            }
            Log.i(TAG, "Dialog detected while screenInteractive=$interactive")
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading screen state: ${e.message}")
        }
    }

    private fun logTelemetrySnapshot(reason: String) {
        Log.i(
            TAG,
            "Telemetry[$reason] scans=$dialogScanCount detected=$dialogDetectedCount " +
                "dismissed=$dialogDismissedCount gesturesOk=$gestureDispatchCount " +
                "gesturesFail=$gestureDispatchFailureCount wakeAcquire=$wakeLockAcquireCount " +
                "wakeRelease=$wakeLockReleaseCount"
        )
    }

    private fun publishTelemetrySnapshot() {
        telemetrySnapshot = TelemetrySnapshot(
            activePackage = currentActivePackage,
            baseIntervalMinutes = currentBaseIntervalMs / 60000L,
            nextIntervalMinutes = currentScheduledIntervalMs / 60000L,
            dialogScans = dialogScanCount,
            dialogsDetected = dialogDetectedCount,
            dialogsDismissed = dialogDismissedCount,
            gesturesOk = gestureDispatchCount,
            gesturesFail = gestureDispatchFailureCount,
            wakeAcquires = wakeLockAcquireCount.toLong(),
            wakeReleases = wakeLockReleaseCount.toLong()
        )
    }

    private fun safeRecycle(node: AccessibilityNodeInfo?) {
        try {
            node?.recycle()
        } catch (_: Exception) {
            // Node may already be recycled by framework.
        }
    }
}
