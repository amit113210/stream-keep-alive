package com.keepalive.yesplus

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Main Activity for Stream Keep Alive.
 *
 * Supports ALL streaming apps: Yes Plus, Netflix, YouTube, Disney+, Prime, and more.
 *
 * Two-layer strategy:
 * 1. Detects "Are you still watching?" dialogs and auto-dismisses them
 * 2. Periodically simulates activity to prevent dialogs from appearing
 */
class KeepAliveAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "StreamKeepAlive"

        // Interval for periodic activity simulation (25 minutes in milliseconds)
        // Reduced from 45 to 25 min to stay well within all streaming apps' timeout windows
        private const val PERIODIC_INTERVAL_MS = 25L * 60L * 1000L

        // Keywords that indicate a "still watching?" dialog (Hebrew + English)
        private val DIALOG_KEYWORDS = listOf(
            // Hebrew
            "עדיין צופים",
            "האם אתם עדיין צופים",
            "ממשיכים לצפות",
            "האם את/ה עדיין צופה",
            "עדיין כאן",
            "עדיין פה",
            "להמשיך בצפייה",
            "האם להמשיך",
            // English — Netflix, YouTube, Disney+, Prime, etc.
            "still watching",
            "are you still watching",
            "are you still there",
            "continue watching",
            "continue playing",
            "playing without asking",
            "video paused",
            "still there",
            "are you still listening",
            "inactivity"
        )

        // Keywords for the confirmation/dismiss button (Hebrew + English)
        private val CONFIRM_KEYWORDS = listOf(
            // Hebrew
            "כן",
            "המשך",
            "המשך צפייה",
            "אישור",
            "אוקיי",
            "המשך לצפות",
            "נגן מבלי לשאול שוב",
            // English
            "yes",
            "continue",
            "continue watching",
            "keep watching",
            "ok",
            "i'm here",
            "yes, continue",
            "resume",
            "play",
            "still here",
            "play without asking again"
        )

        // Track if service is running
        @Volatile
        var isRunning = false
            private set
    }

    private var handler: Handler? = null
    private var periodicRunnable: Runnable? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onServiceConnected() {
        try {
            super.onServiceConnected()
            isRunning = true
            handler = Handler(Looper.getMainLooper())
            Log.i(TAG, "Accessibility Service connected - Stream Keep Alive is active!")

            // Layer 2: Acquire PARTIAL_WAKE_LOCK to keep CPU alive
            // This ensures our timers fire even when the screen dims
            acquireWakeLock()

            startPeriodicActivity()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onServiceConnected: ${e.message}", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event == null) return

            // Only process events from streaming apps
            val packageName = event.packageName?.toString() ?: return

            // Streaming app packages to monitor
            val streamingPackages = listOf(
                // Yes Plus
                "il.co.yes",
                // Netflix
                "com.netflix",
                // YouTube / YouTube TV
                "com.google.android.youtube",
                "com.google.android.apps.youtube",
                // Disney+
                "com.disney",
                // Amazon Prime Video
                "com.amazon.avod",
                "com.amazon.firetv",
                // Apple TV+
                "com.apple.atve",
                // HBO Max
                "com.hbo",
                "com.wbd.stream",
                // Hulu
                "com.hulu",
                // Cellcom TV
                "com.cellcom",
                "il.co.cellcom",
                // Partner TV
                "com.partner",
                "il.co.partner",
                // HOT
                "il.co.hot",
                "com.hot",
                // Spotify (audio)
                "com.spotify",
                // Plex
                "com.plexapp",
                // VLC
                "org.videolan",
                // Kodi
                "org.xbmc"
            )

            // Ignore our own app and system packages
            val ignoredPackages = listOf(
                "com.keepalive.yesplus",
                "com.android.settings",
                "com.android.tv.settings",
                "com.droidlogic.tv.settings",
                "com.android.systemui",
                "com.google.android.tvlauncher",
                "com.google.android.leanbacklauncher"
            )

            if (ignoredPackages.any { packageName.startsWith(it) }) return

            // Only process if from a known streaming app
            val isFromStreamingApp = streamingPackages.any { packageName.startsWith(it) }
            if (!isFromStreamingApp) return

            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                    Log.d(TAG, "Event from package: $packageName")
                    checkAndDismissDialog()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onAccessibilityEvent: ${e.message}", e)
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility Service interrupted")
    }

    override fun onDestroy() {
        isRunning = false
        stopPeriodicActivity()
        releaseWakeLock()
        Log.i(TAG, "Accessibility Service destroyed")
        super.onDestroy()
    }

    // ==========================================
    // Layer 1: Dialog Detection & Auto-Dismiss
    // ==========================================

    /**
     * Check if the "still watching?" dialog is visible and dismiss it.
     */
    private fun checkAndDismissDialog() {
        try {
            val rootNode = rootInActiveWindow ?: return

            // Search for dialog keywords in the accessibility tree
            if (containsDialogKeywords(rootNode)) {
                Log.i(TAG, "Detected 'still watching' dialog! Attempting to dismiss...")

                // Strategy 1: Find and click the confirmation button
                if (findAndClickConfirmButton(rootNode)) {
                    Log.i(TAG, "Successfully clicked confirm button!")
                    safeRecycle(rootNode)
                    return
                }

                // Strategy 2: Click the focused element (highly reliable on TV interfaces for dialogs)
                if (clickFocusedElement(rootNode)) {
                    Log.i(TAG, "Clicked the focused element in the dialog!")
                    safeRecycle(rootNode)
                    return
                }

                // Strategy 3: Click any clickable button in the dialog
                if (clickFirstButton(rootNode)) {
                    Log.i(TAG, "Clicked a generic button in the dialog!")
                    safeRecycle(rootNode)
                    return
                }

                // Strategy 4: Perform BACK action to dismiss the dialog
                Log.i(TAG, "No button found, trying BACK action...")
                performGlobalAction(GLOBAL_ACTION_BACK)
            }

            safeRecycle(rootNode)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking dialog: ${e.message}", e)
        }
    }

    /**
     * Recursively search the node tree for dialog keywords.
     */
    private fun containsDialogKeywords(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        try {
            val text = node.text?.toString()?.lowercase() ?: ""
            val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""

            for (keyword in DIALOG_KEYWORDS) {
                val kw = keyword.lowercase()
                if (text.contains(kw) || contentDesc.contains(kw)) {
                    return true
                }
            }

            // Recurse into children
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (e: Exception) { null }
                if (child != null) {
                    val found = containsDialogKeywords(child)
                    safeRecycle(child)
                    if (found) return true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning node: ${e.message}")
        }

        return false
    }

    /**
     * Find and click a confirmation button by matching known keywords.
     */
    private fun findAndClickConfirmButton(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        try {
            val text = node.text?.toString()?.lowercase() ?: ""
            val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""

            // Check if this node matches a confirm keyword and is clickable
            for (keyword in CONFIRM_KEYWORDS) {
                val kw = keyword.lowercase()
                if ((text.contains(kw) || contentDesc.contains(kw)) && node.isClickable) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.i(TAG, "Clicked confirm button with text: '$text'")
                    return true
                }
            }

            // Recurse into children
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (e: Exception) { null }
                if (child != null) {
                    val found = findAndClickConfirmButton(child)
                    if (found) {
                        safeRecycle(child)
                        return true
                    }
                    safeRecycle(child)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding confirm button: ${e.message}")
        }

        return false
    }

    /**
     * Click the first clickable button found in the node tree.
     * Fallback when specific button text isn't matched.
     */
    private fun clickFirstButton(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false

        try {
            val className = node.className?.toString() ?: ""
            // Include generic View and ViewGroup as long as it's clickable and we are in a dialog context
            if (node.isClickable && (className.contains("Button", ignoreCase = true) || 
                                     className == "android.view.View" || 
                                     className == "android.view.ViewGroup")) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.i(TAG, "Clicked first available button/view ($className)")
                return true
            }

            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (e: Exception) { null }
                if (child != null) {
                    val found = clickFirstButton(child)
                    if (found) {
                        safeRecycle(child)
                        return true
                    }
                    safeRecycle(child)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error clicking button: ${e.message}")
        }

        return false
    }

    /**
     * Click the currently focused element, assuming the TV OS focused the confirm button automatically.
     */
    private fun clickFocusedElement(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        try {
            // Check accessibility focus or input focus
            val focusedNode = node.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) 
                ?: node.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            
            if (focusedNode != null) {
                if (focusedNode.isClickable) {
                    focusedNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.i(TAG, "Clicked focused element")
                    safeRecycle(focusedNode)
                    return true
                }
                safeRecycle(focusedNode)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error clicking focused element: ${e.message}")
        }
        return false
    }

    // ==========================================
    // Layer 2: Periodic Activity Simulation
    // ==========================================

    /**
     * Start periodic activity simulation.
     * Every ~25 minutes, simulate a gentle interaction to prevent
     * the "still watching" timeout.
     */
    private fun startPeriodicActivity() {
        try {
            periodicRunnable = object : Runnable {
                override fun run() {
                    simulateActivity()
                    handler?.postDelayed(this, PERIODIC_INTERVAL_MS)
                }
            }
            handler?.postDelayed(periodicRunnable!!, PERIODIC_INTERVAL_MS)
            Log.i(TAG, "Periodic activity started (every ${PERIODIC_INTERVAL_MS / 60000} minutes)")
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
            Log.e(TAG, "Error stopping periodic: ${e.message}")
        }
    }

    /**
     * Simulate user activity — TRIPLE DEFENSE.
     * 1. PowerManager.userActivity() — resets OS screen-off timer (like touching the remote)
     * 2. dispatchGesture — sends invisible touch to reset app-level inactivity timers
     * 3. Benign accessibility tree query as last resort
     */
    private fun simulateActivity() {
        try {
            // === Defense 1: OS-level screen-on reset ===
            pokeUserActivity()

            // === Defense 2: App-level gesture simulation ===
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                simulateGesture()
            }

            Log.i(TAG, "Periodic activity simulation completed (interval: ${PERIODIC_INTERVAL_MS / 60000} min)")
        } catch (e: Exception) {
            Log.e(TAG, "Error simulating activity: ${e.message}", e)
        }
    }

    /**
     * Poke the screen to reset the screen-off countdown.
     * Briefly acquires and releases a SCREEN_DIM_WAKE_LOCK which resets
     * the system's screen-off timer — same effect as touching the remote.
     * Available to all apps (no system permission needed).
     */
    @Suppress("DEPRECATION")
    private fun pokeUserActivity() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val screenLock = pm.newWakeLock(
                PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "StreamKeepAlive::ScreenPoke"
            )
            screenLock.acquire(1000L) // Hold for 1 second then auto-release
            Log.i(TAG, "Screen poke — screen-off timer reset")
        } catch (e: Exception) {
            Log.e(TAG, "Error poking screen: ${e.message}", e)
        }
    }

    /**
     * Simulate a gentle touch gesture at coordinates (1,1) — invisible corner tap.
     * This registers as real user input at the app level, resetting per-app inactivity timers
     * (Netflix, Yes Plus, etc.) without visually affecting the UI.
     * API 24+ only.
     */
    private fun simulateGesture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return

        try {
            val path = Path()
            // Touch at the very top-left corner — invisible and non-interactive
            path.moveTo(1f, 1f)

            val gestureBuilder = GestureDescription.Builder()
            gestureBuilder.addStroke(
                GestureDescription.StrokeDescription(path, 0, 50)
            )

            val dispatched = dispatchGesture(
                gestureBuilder.build(),
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        Log.i(TAG, "Gesture dispatch completed — app-level activity registered")
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        Log.w(TAG, "Gesture dispatch cancelled")
                    }
                },
                null
            )

            if (!dispatched) {
                Log.w(TAG, "Gesture dispatch failed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in gesture simulation: ${e.message}", e)
        }
    }

    // ==========================================
    // WakeLock Management
    // ==========================================

    /**
     * Acquire a PARTIAL_WAKE_LOCK to keep the CPU running.
     * This ensures our periodic timers fire even when the screen dims.
     * Without this, Android may suspend the CPU and our Handler stops posting.
     */
    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "StreamKeepAlive::KeepAliveLock"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "WakeLock acquired — CPU will stay alive")
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring WakeLock: ${e.message}", e)
        }
    }

    /**
     * Release the WakeLock when the service is destroyed.
     */
    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.i(TAG, "WakeLock released")
                }
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WakeLock: ${e.message}", e)
        }
    }

    /**
     * Safely recycle an AccessibilityNodeInfo without crashing.
     */
    private fun safeRecycle(node: AccessibilityNodeInfo?) {
        try {
            node?.recycle()
        } catch (e: Exception) {
            // Ignore - node may already be recycled
        }
    }
}
