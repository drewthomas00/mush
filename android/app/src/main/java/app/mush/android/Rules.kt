package app.mush.android

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * What Mush reacts to. The rules live on the server (rules/android.json), so they can change
 * without reinstalling: which apps get the prompt as soon as they open (`gate`), the global watch
 * for short-form video in every other app (`video`), and extra per-app screen matches (`rules`).
 */
data class Gate(
    val pkg: String,
    val app: String,
    val short: Boolean, // the whole app is short-form, so no second prompt inside it
    val scan: Boolean,  // watch for short-form video inside it (off for apps that open on a camera)
)

data class Rule(
    val pkg: String,
    val app: String,
    val mode: String,          // "app": the whole app; "ids": a matching view on screen
    val ids: List<String>,     // substrings of a view's resource id, e.g. "reel_recycler"
    val descs: List<String>,   // exact content descriptions (case-insensitive)
    val minHeight: Double,     // the match must be at least this share of the screen's height (a player, not a thumbnail)
)

class RuleSet(
    val version: Int,
    val gates: List<Gate>,
    val videoMinHeight: Double,
    val videoIgnore: Set<String>,
    val rules: List<Rule>,
    val capture: Set<String>,
) {
    fun gateFor(pkg: String) = gates.firstOrNull { it.pkg == pkg }
    fun forPkg(pkg: String) = rules.filter { it.pkg == pkg && it.mode != "video" }

    companion object {
        fun parse(json: String): RuleSet {
            val o = JSONObject(json)
            val gates = mutableListOf<Gate>()
            val ga = o.optJSONArray("gate") ?: JSONArray()
            for (i in 0 until ga.length()) {
                val g = ga.getJSONObject(i)
                gates += Gate(g.getString("pkg"), g.getString("app"), g.optBoolean("short", false), g.optBoolean("scan", true))
            }
            val list = mutableListOf<Rule>()
            val arr = o.optJSONArray("rules") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                list += Rule(
                    pkg = r.getString("pkg"),
                    app = r.getString("app"),
                    mode = r.optString("mode", "ids"),
                    ids = strings(r.optJSONArray("ids")),
                    descs = strings(r.optJSONArray("descs")).map { it.lowercase() },
                    minHeight = r.optDouble("minHeight", 0.5),
                )
            }
            val v = o.optJSONObject("video") ?: JSONObject()
            return RuleSet(
                version = o.optInt("version", 0),
                gates = gates,
                videoMinHeight = v.optDouble("minHeight", 0.7),
                videoIgnore = strings(v.optJSONArray("ignore")).toSet(),
                rules = list,
                capture = strings(o.optJSONArray("capture")).toSet(),
            )
        }

        private fun strings(a: JSONArray?): List<String> =
            if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }

        /** The copy shipped in the app (rules/android.json at build time). */
        private fun bundled(ctx: Context): String = ctx.assets.open("android.json").bufferedReader().use { it.readText() }

        fun load(ctx: Context): RuleSet {
            val saved = Api.prefs(ctx).getString("rules", null)
            return runCatching { parse(saved ?: bundled(ctx)) }.getOrElse { parse(bundled(ctx)) }
        }

        /** Fetches the latest rules (your server's, or the public ones); keeps the saved ones if that fails. */
        fun refresh(ctx: Context, done: (RuleSet) -> Unit) {
            val from = if (Api.serverConfigured(ctx)) "/api/rules" else Api.PUBLIC_RULES
            Api.get(ctx, from) { body ->
                if (body == null) return@get
                val set = try { parse(body) } catch (e: Exception) { return@get }
                Api.prefs(ctx).edit().putString("rules", body).putLong("rulesAt", System.currentTimeMillis()).apply()
                done(set)
            }
        }
    }
}

/** Walks an app's view tree, skipping hidden branches, with a cap so a huge screen can't stall the service. */
object Tree {
    fun walk(root: AccessibilityNodeInfo, max: Int = 3000, visit: (AccessibilityNodeInfo) -> Boolean) {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var seen = 0
        while (queue.isNotEmpty() && seen < max) {
            val n = queue.removeFirst()
            seen++
            if (!n.isVisibleToUser) continue
            if (visit(n)) return
            for (i in 0 until n.childCount) n.getChild(i)?.let { queue.add(it) }
        }
    }
}

object Detector {
    /** Does this screen match a per-app rule? */
    fun matches(root: AccessibilityNodeInfo, rule: Rule, screenHeight: Int): Boolean {
        if (rule.mode == "app") return true
        if (rule.ids.isEmpty() && rule.descs.isEmpty()) return false
        val minPx = (screenHeight * rule.minHeight).toInt()
        val r = Rect()
        var found = false
        Tree.walk(root) { n ->
            val id = n.viewIdResourceName
            val desc = n.contentDescription?.toString()?.lowercase()
            val hit = (id != null && rule.ids.any { id.contains(it) }) || (desc != null && desc in rule.descs)
            if (hit) {
                n.getBoundsInScreen(r)
                if (r.height() >= minPx) found = true
            }
            found
        }
        return found
    }

    /**
     * Short-form video looks the same in every app: an upright video surface filling most of the
     * screen. Landscape videos and videos inside a feed don't qualify. Cameras and calls look the
     * same too, so the service checks those separately before trusting this.
     */
    fun uprightVideo(root: AccessibilityNodeInfo, screenHeight: Int, minHeight: Double): Boolean {
        val minPx = (screenHeight * minHeight).toInt()
        val r = Rect()
        var found = false
        Tree.walk(root, max = 1500) { n ->
            val cls = n.className?.toString() ?: ""
            if (cls.endsWith("SurfaceView") || cls.endsWith("TextureView")) {
                n.getBoundsInScreen(r)
                if (r.height() >= minPx && r.height() > r.width() * 1.2) found = true
            }
            found
        }
        return found
    }
}
