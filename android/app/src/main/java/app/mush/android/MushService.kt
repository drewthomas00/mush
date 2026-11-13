package app.mush.android

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Rect
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Two ways in, one brain:
 *  - gated apps (Facebook, Instagram, …) get the prompt as soon as they open, like the iPhone;
 *  - everywhere else, an upright video filling the screen (short-form) gets it too.
 * Inside a gated app you've let yourself into, starting short-form video brings the brain back.
 *
 * Entering tells the server (/api/open) and shows the brain unless it answers "true" (free
 * minutes, or back inside the grace window). While you stay it asks /api/nudge and shows the
 * brain when a nudge is due, and sends /api/still so a long session isn't capped. Leaving tells
 * the server /api/close.
 */
class MushService : AccessibilityService() {
    private enum class Kind { APP, VIDEO }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var rules: RuleSet = RuleSet.parse("{}")
    private lateinit var overlay: Overlay

    private var activeApp: String? = null
    private var activeKind = Kind.APP
    private var activePkg: String? = null
    private var nudgesShown = 0
    private var screenOn = true

    // Coming back to the same app soon (a photo story between videos) keeps its nudge count.
    private var lastExitApp: String? = null
    private var lastExitAt = 0L
    private var lastExitNudges = 0

    // Short-form inside a gated app.
    private var insideShort = false
    private var stopShown = false // the goal's hard stop, once per session
    private var insideSnoozeUntil = 0L

    private var evalQueued = false
    private var lastEval = 0L
    private var exitPending: Runnable? = null

    // Things that look like short-form video but aren't.
    private val camerasInUse = HashSet<String>()
    private var cameraApps: Set<String> = emptySet()
    private val gameCache = HashMap<String, Boolean>()
    private val labelCache = HashMap<String, String>()

    private val lastCapture = HashMap<String, Long>()
    private val lastCaptureSig = HashMap<String, Int>()
    private val captureCount = HashMap<String, Int>()
    private var captureEpoch = 0L
    private var lastSeen: String? = null

    private val poll = object : Runnable {
        override fun run() {
            checkNudge()
            main.postDelayed(this, POLL_MS)
        }
    }
    // Tells the server you're still here, so a long session isn't ended by its 45-minute cap.
    private val still = object : Runnable {
        override fun run() {
            val app = activeApp ?: return
            Backend.get(this@MushService).record("still", app)
            main.postDelayed(this, STILL_MS)
        }
    }
    private var rulesFetched = false
    private var rulesTries = 0
    private val refreshRules = object : Runnable {
        override fun run() {
            RuleSet.refresh(this@MushService) { rules = it; rulesFetched = true; rulesTries = 0 }
            // Until one fetch works (offline, VPN still connecting), retry at 1, 2, 4 … 60 minutes.
            val retry = 60_000L * (1L shl minOf(rulesTries++, 6)).coerceAtMost(60L)
            main.postDelayed(this, if (rulesFetched) RULES_MS else retry)
        }
    }

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> { screenOn = false; exitNow() }
                Intent.ACTION_USER_PRESENT, Intent.ACTION_SCREEN_ON -> { screenOn = true; queueEval() }
            }
        }
    }

    private val cameraWatch = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) {
            camerasInUse.remove(cameraId)
            if (camerasInUse.isEmpty()) lastSeen = null // replace the "camera in use" status on the next look
        }
        override fun onCameraUnavailable(cameraId: String) { camerasInUse.add(cameraId) }
    }

    override fun onServiceConnected() {
        instance = this
        rules = RuleSet.load(this)
        overlay = Overlay(this)
        main.post(refreshRules)
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screen, f, Context.RECEIVER_NOT_EXPORTED)
        getSystemService(CameraManager::class.java)?.registerAvailabilityCallback(cameraWatch, main)
        cameraApps = findCameraApps()
        status("Watching")
    }

    override fun onDestroy() {
        exitNow()
        runCatching { unregisterReceiver(screen) }
        runCatching { getSystemService(CameraManager::class.java)?.unregisterAvailabilityCallback(cameraWatch) }
        main.removeCallbacksAndMessages(null)
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = queueEval()

    /** Events come in bursts; look at the screen at most every EVAL_MS. */
    private fun queueEval() {
        if (evalQueued) return
        evalQueued = true
        val wait = (EVAL_MS - (SystemClock.uptimeMillis() - lastEval)).coerceAtLeast(0)
        main.postDelayed({
            evalQueued = false
            lastEval = SystemClock.uptimeMillis()
            runCatching { evaluate() }.onFailure { status("Error: ${it.javaClass.simpleName} ${it.message ?: ""}".trim()) }
        }, wait)
    }

    private fun evaluate() {
        screenOn = getSystemService(PowerManager::class.java)?.isInteractive ?: true
        if (!screenOn) return
        val root = appRoot()
        if (root == null) { seen("(no app window)"); return }
        val pkg = root.packageName?.toString() ?: return
        // Our own screens, the notification shade and keyboards sit on top of the app; keep the state.
        if (pkg == packageName || pkg == "com.android.systemui" || pkg.contains("inputmethod")) {
            if (pkg != packageName) status("Paused under $pkg")
            lastSeen = null // so coming back shows the app again
            return
        }
        seen(pkg)
        maybeCapture(root, pkg)
        val h = resources.displayMetrics.heightPixels

        val gate = rules.gateFor(pkg)?.takeIf { it.app !in (Api.prefs(this).getStringSet("gateOff", null) ?: emptySet()) }
        if (gate != null) {
            enter(gate.app, pkg, Kind.APP, short = gate.short)
            val short = !gate.short && gate.scan && (matchesRule(root, pkg, h) || (videoCounts(pkg) && Detector.uprightVideo(root, h, rules.videoMinHeight)))
            if (short) shortInside(gate.app) else if (insideShort) {
                insideShort = false
                Backend.get(this).record("unshort", gate.app)
            }
            return
        }
        val app = when {
            matchesRule(root, pkg, h) -> rules.forPkg(pkg).first().app
            // Outside gated apps, also require sound: maps, previews and other full-screen surfaces are silent.
            videoCounts(pkg) && audioPlaying() && Detector.uprightVideo(root, h, rules.videoMinHeight) -> appName(pkg)
            else -> null
        }
        if (app != null) enter(app, pkg, Kind.VIDEO, short = true) else leaveSoon()
    }

    private fun matchesRule(root: AccessibilityNodeInfo, pkg: String, h: Int) =
        rules.forPkg(pkg).any { Detector.matches(root, it, h) }

    /** Would an upright full-screen surface in this app be short-form? Not in cameras, calls, maps, games. */
    private fun videoCounts(pkg: String): Boolean {
        if (pkg in rules.videoIgnore || pkg in cameraApps || isGame(pkg)) return false
        if (pkg in (Api.prefs(this).getStringSet("ignored", null) ?: emptySet())) return false
        if (camerasInUse.isNotEmpty()) { // a camera preview (stories, posting) looks just like a reel
            status("Video watch paused: a camera is in use")
            return false
        }
        val mode = getSystemService(AudioManager::class.java)?.mode ?: AudioManager.MODE_NORMAL
        return mode != AudioManager.MODE_IN_COMMUNICATION && mode != AudioManager.MODE_IN_CALL
    }

    private fun audioPlaying() = getSystemService(AudioManager::class.java)?.isMusicActive ?: true

    private fun findCameraApps(): Set<String> {
        val pm = packageManager
        return listOf(MediaStore.ACTION_IMAGE_CAPTURE, MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA, MediaStore.ACTION_VIDEO_CAPTURE)
            .flatMap { runCatching { pm.queryIntentActivities(Intent(it), PackageManager.ResolveInfoFlags.of(0)) }.getOrDefault(emptyList()) }
            .map { it.activityInfo.packageName }
            .toSet()
    }

    private fun isGame(pkg: String): Boolean = gameCache.getOrPut(pkg) {
        runCatching {
            packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0)).category == ApplicationInfo.CATEGORY_GAME
        }.getOrDefault(false)
    }

    /** The name Mush uses for an app it caught without a rule: its label, e.g. "reddit". */
    private fun appName(pkg: String): String = labelCache.getOrPut(pkg) {
        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))).toString()
        }.getOrDefault(pkg.substringAfterLast('.'))
        label.lowercase().replace(Regex("[^a-z0-9 ._-]"), "").trim().take(40).ifBlank { pkg.take(40) }
    }

    /**
     * The app the person is looking at. rootInActiveWindow can be null or point elsewhere while a
     * video player is up, so prefer the topmost application window and fall back to the active one.
     */
    private fun appRoot(): AccessibilityNodeInfo? {
        val apps = runCatching { windows }.getOrNull().orEmpty()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && !it.isInPictureInPictureMode }
        val pick = apps.firstOrNull { it.isActive || it.isFocused } ?: apps.maxByOrNull { it.layer }
        return pick?.root ?: rootInActiveWindow
    }

    /** Remembers which app Mush last saw (shown on its screen); in capture mode, tells the server about tracked ones. */
    private fun seen(pkg: String) {
        if (pkg == lastSeen) return
        lastSeen = pkg
        status(if (activeApp != null) "In $activeApp ($pkg)" else "Seeing $pkg")
        if (Api.serverConfigured(this) && Api.prefs(this).getBoolean("capture", false) && (pkg in rules.capture || pkg.startsWith("("))) {
            val wins = JSONArray()
            runCatching { windows }.getOrNull().orEmpty().forEach { w ->
                wins.put(JSONObject().put("type", w.type).put("pkg", w.root?.packageName?.toString() ?: JSONObject.NULL)
                    .put("active", w.isActive).put("layer", w.layer))
            }
            Api.post(this, "/api/android/dump", JSONObject().put("pkg", pkg).put("seen", true).put("windows", wins).toString())
        }
    }

    private fun enter(app: String, pkg: String, kind: Kind, short: Boolean) {
        // Back in the same app, unless it's right after a stop sent you home: that's a fresh open (and the stop again).
        val reopenAfterStop = activeApp == app && stopShown && exitPending != null
        exitPending?.let { main.removeCallbacks(it) }
        exitPending = null
        if (activeApp == app && !reopenAfterStop) { activePkg = pkg; return }
        if (activeApp != null) exitNow()
        activeApp = app
        activeKind = kind
        activePkg = pkg
        insideShort = false
        stopShown = false
        val now = SystemClock.uptimeMillis()
        nudgesShown = if (app == lastExitApp && now - lastExitAt < REENTER_MS) lastExitNudges else 0
        status("In $app ($pkg)")
        Backend.get(this).open(app, short) { d ->
            if (activeApp != app) return@open
            when {
                d == null -> gate(app, kind, "Mush can't reach your server (is your VPN on?), but it still saw you.")
                d.stop -> hardStop(app, kind, d.text ?: "⛔ Over today's goal.")
                !d.allow -> gate(app, kind, d.text ?: "")
            }
        }
        main.removeCallbacks(poll)
        main.postDelayed(poll, POLL_MS)
        main.removeCallbacks(still)
        main.postDelayed(still, STILL_MS)
    }

    /** A different screen for a moment (a comment sheet, a photo story) shouldn't end the session. */
    private fun leaveSoon() {
        if (activeApp == null || exitPending != null) return
        val r = Runnable { exitPending = null; exitNow() }
        exitPending = r
        main.postDelayed(r, if (activeKind == Kind.VIDEO) LEAVE_VIDEO_MS else LEAVE_MS)
    }

    private fun exitNow() {
        exitPending?.let { main.removeCallbacks(it) }
        exitPending = null
        main.removeCallbacks(poll)
        main.removeCallbacks(still)
        val app = activeApp ?: return
        activeApp = null
        activePkg = null
        lastExitApp = app
        lastExitAt = SystemClock.uptimeMillis()
        lastExitNudges = nudgesShown
        if (::overlay.isInitialized) overlay.hide()
        status("Watching")
        Backend.get(this).record("close", app)
    }

    private fun gate(app: String, kind: Kind, text: String) {
        val pkg = activePkg
        overlay.show(text, "Back away", "Rot anyway",
            onPrimary = { backAway(app) },
            onSecondary = { Backend.get(this).record("rot", app) },
            // Only the global watch can be wrong about what's on screen.
            tertiary = if (kind == Kind.VIDEO && pkg != null) "This isn't video" else null,
            onTertiary = { if (pkg != null) notVideo(pkg) },
        )
    }

    /** A goal is used up: Back away is the only way on (and "This isn't video" where the watch could be wrong). */
    private fun hardStop(app: String, kind: Kind, text: String) {
        stopShown = true
        val pkg = activePkg
        overlay.show(text, "Back away", null,
            onPrimary = { backAway(app) },
            onSecondary = {},
            tertiary = if (kind == Kind.VIDEO && pkg != null) "This isn't video" else null,
            onTertiary = { if (pkg != null) notVideo(pkg) },
        )
    }

    /** Short-form started inside a gated app you're already in (Reels inside Facebook). */
    private fun shortInside(app: String) {
        if (insideShort) return // already handled this stretch of video
        insideShort = true
        Backend.get(this).short(app) { stop ->
            if (activeApp != app || !insideShort) return@short
            if (stop != null) {
                stopShown = true
                overlay.show(stop, "Back away", null, onPrimary = { performGlobalAction(GLOBAL_ACTION_HOME) }, onSecondary = {})
            } else if (SystemClock.uptimeMillis() >= insideSnoozeUntil && !overlay.showing) {
                showInsidePrompt(app)
            }
        }
    }

    private fun showInsidePrompt(app: String) {
        overlay.show("Short-form video.\n\nThis is the part that rots you.", "Back away", "Keep watching",
            // You were already in the app: leaving records a close; the back-away refund is for the gate at the door.
            onPrimary = { performGlobalAction(GLOBAL_ACTION_HOME) },
            onSecondary = {
                insideSnoozeUntil = SystemClock.uptimeMillis() + INSIDE_SNOOZE_MS
                Backend.get(this).record("rot", app)
            },
        )
    }

    private fun backAway(app: String) {
        Backend.get(this).record("resist", app)
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /** "This isn't video": stop the global watch in this app (undo in Setup) and end the session. */
    private fun notVideo(pkg: String) {
        val p = Api.prefs(this)
        p.edit().putStringSet("ignored", (p.getStringSet("ignored", null) ?: emptySet()) + pkg).apply()
        Api.get(this, "/api/android/ignored?pkg=${Api.q(pkg)}") // tells your server, if you have one
        exitNow()
    }

    private fun checkNudge() {
        val app = activeApp ?: return
        Backend.get(this).nudge(app) { o ->
            if (o == null || activeApp != app) return@nudge
            if (o.optBoolean("stop")) { // past a goal mid-session: the only way on is out, every time you look
                stopShown = true
                if (!overlay.showing) {
                    overlay.show(o.optString("body"), "Get out", null, onPrimary = { performGlobalAction(GLOBAL_ACTION_HOME) }, onSecondary = {})
                }
                return@nudge
            }
            val due = o.optInt("due", 0)
            if (due <= nudgesShown) return@nudge
            nudgesShown = due
            if (overlay.showing) return@nudge
            overlay.show("${o.optString("title")}\n\n${o.optString("body")}", "Get out", "Keep rotting",
                onPrimary = { performGlobalAction(GLOBAL_ACTION_HOME) }, // leaving records the close
                onSecondary = { },
            )
        }
    }

    /** "Test the brain" from Setup. */
    fun showTest() {
        overlay.show("This is what you'll see when you open a gated app or short-form video starts.", "Looks good", "Close", {}, {})
    }

    /**
     * Capture mode: send the shape of a tracked app's screen (view ids, classes, sizes, and short
     * labels on buttons) so detection rules can be written from it. No text you read or type.
     */
    private fun maybeCapture(root: AccessibilityNodeInfo, pkg: String) {
        if (!Api.serverConfigured(this) || !Api.prefs(this).getBoolean("capture", false) || pkg !in rules.capture) return
        val now = SystemClock.uptimeMillis()
        if (now - (lastCapture[pkg] ?: 0L) < CAPTURE_MS) return
        val epoch = Api.prefs(this).getLong("captureOn", 0L)
        if (epoch != captureEpoch) { captureEpoch = epoch; captureCount.clear(); lastCaptureSig.clear() }
        if ((captureCount[pkg] ?: 0) >= MAX_CAPTURES) return
        val nodes = JSONArray()
        val r = Rect()
        // Some apps (Facebook) hide their view ids, so tell screens apart by ids, classes and labels together.
        val shape = StringBuilder()
        Tree.walk(root) { n ->
            n.getBoundsInScreen(r)
            val o = JSONObject()
            n.viewIdResourceName?.let { o.put("id", it) }
            o.put("cls", n.className?.toString()?.substringAfterLast('.'))
            shape.append(n.viewIdResourceName).append('|').append(n.className).append('|')
                .append(if (n.isClickable) n.contentDescription else "").append(';')
            o.put("b", JSONArray(listOf(r.left, r.top, r.right, r.bottom)))
            val desc = n.contentDescription?.toString()
            if (n.isClickable && desc != null && desc.length <= 24) o.put("desc", desc)
            if (n.isSelected) o.put("sel", true)
            nodes.put(o)
            false
        }
        val sig = shape.toString().hashCode()
        if (sig == lastCaptureSig[pkg]) return // same screen as last time
        lastCapture[pkg] = now
        lastCaptureSig[pkg] = sig
        captureCount[pkg] = (captureCount[pkg] ?: 0) + 1
        val dump = JSONObject()
            .put("pkg", pkg)
            .put("app", BuildConfig.VERSION_NAME)
            .put("screenH", resources.displayMetrics.heightPixels)
            .put("detected", activeApp ?: JSONObject.NULL)
            .put("cameraInUse", camerasInUse.isNotEmpty())
            .put("nodes", nodes)
        Api.post(this, "/api/android/dump", dump.toString()) { ok ->
            if (ok) Api.prefs(this).edit().putInt("captures", Api.prefs(this).getInt("captures", 0) + 1).apply()
        }
    }

    private var lastStatus: String? = null
    private fun status(s: String) {
        if (s == lastStatus) return
        lastStatus = s
        Api.prefs(this).edit().putString("status", s).putLong("statusAt", System.currentTimeMillis()).apply()
    }

    companion object {
        @Volatile var instance: MushService? = null
        private const val EVAL_MS = 700L
        private const val LEAVE_MS = 2500L                // a gated app: gone once you've been elsewhere this long
        private const val LEAVE_VIDEO_MS = 8000L          // short-form elsewhere: covers a photo story between videos
        private const val REENTER_MS = 3 * 60 * 1000L     // back to the same app this soon keeps its nudge count
        private const val INSIDE_SNOOZE_MS = 3 * 60 * 1000L
        private const val POLL_MS = 20_000L
        private const val STILL_MS = 5 * 60 * 1000L
        private const val RULES_MS = 6 * 60 * 60 * 1000L
        private const val CAPTURE_MS = 4000L
        private const val MAX_CAPTURES = 60 // per app, each time capture is switched on
    }
}
