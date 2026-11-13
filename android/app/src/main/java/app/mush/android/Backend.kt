package app.mush.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Where the brain lives. On its own (the default), Mush keeps the event log on the phone and
 * replays it with Brain. Connected to a Mush server (Setup), the server keeps it, so several
 * devices (an iPhone too) share one brain. Callbacks always come back on the main thread.
 */
interface Backend {
    /** "true" to let you in (free minutes, grace), the prompt text otherwise, null if unreachable. */
    fun open(app: String, done: (String?) -> Unit)
    fun record(type: String, app: String) // close, rot, resist, still
    /** {due, title, body} for the live session in `app`, or null. */
    fun nudge(app: String, done: (JSONObject?) -> Unit)
    fun state(done: (JSONObject?) -> Unit)
    val shared: Boolean // true when a server is keeping the brain

    companion object {
        fun get(ctx: Context): Backend = if (Api.serverConfigured(ctx)) Remote(ctx) else Local.get(ctx)
    }
}

/** The Mush server, over the tailnet or wherever it runs. */
class Remote(private val ctx: Context) : Backend {
    override val shared = true
    override fun open(app: String, done: (String?) -> Unit) =
        Api.get(ctx, "/api/open?app=${Api.q(app)}&dev=android") { done(it?.trim()); Widget.refresh(ctx) }
    override fun record(type: String, app: String) =
        Api.get(ctx, "/api/$type?app=${Api.q(app)}&dev=android") { Widget.refresh(ctx) }
    override fun nudge(app: String, done: (JSONObject?) -> Unit) =
        Api.get(ctx, "/api/nudge?app=${Api.q(app)}") { b -> done(b?.let { runCatching { JSONObject(it) }.getOrNull() }) }
    override fun state(done: (JSONObject?) -> Unit) =
        Api.get(ctx, "/api/state") { b -> done(b?.let { runCatching { JSONObject(it) }.getOrNull() }) }
}

/** The brain on the phone: an append-only event log in app storage, replayed on demand. */
class Local private constructor(private val ctx: Context) : Backend {
    override val shared = false
    private val main = Handler(Looper.getMainLooper())
    private val file = File(ctx.filesDir, "events.jsonl")
    private val events = mutableListOf<Event>()
    private val lock = Any()

    init {
        if (file.exists()) {
            file.forEachLine { line ->
                runCatching { JSONObject(line) }.getOrNull()?.let { o ->
                    events += Event(o.getLong("t"), o.getString("type"), o.optString("app").ifEmpty { null })
                }
            }
            events.sortBy { it.t }
        }
    }

    fun rules(): BrainRules = BrainRules(freeMinPerDay = FreeMinutes.all(ctx))

    private fun now() = synchronized(lock) { maxOf(System.currentTimeMillis(), events.lastOrNull()?.t ?: 0L) }

    private fun add(type: String, app: String, t: Long) {
        synchronized(lock) {
            val ev = Event(t, type, app)
            val line = JSONObject().put("t", ev.t).put("type", ev.type).put("app", ev.app).toString() + "\n"
            file.appendText(line) // disk first: a failed write must not count
            events += ev
        }
        main.post { Widget.refresh(ctx) }
    }

    private fun summary(now: Long) = synchronized(lock) { Brain.summarize(events.toList(), now, rules()) }

    override fun open(app: String, done: (String?) -> Unit) {
        val t = now()
        add("open", app, t)
        val s = summary(t)
        val answer = if (s.session?.cont == true || s.session?.free == true) "true" else Brain.gateText(s, app, rules())
        main.post { done(answer) }
    }

    override fun record(type: String, app: String) { add(type, app, now()) }

    override fun nudge(app: String, done: (JSONObject?) -> Unit) {
        val t = now()
        val s = summary(t)
        val live = s.session
        val out = if (live == null || live.app != app) JSONObject().put("due", 0) else {
            val (due, msg) = Brain.nudgeMessage(s, live, t, rules())
            JSONObject().put("due", due).apply { msg?.let { put("title", it.first).put("body", it.second) } }
        }
        main.post { done(out) }
    }

    override fun state(done: (JSONObject?) -> Unit) {
        val json = stateJson()
        main.post { done(json) }
    }

    /** The same shape as the server's /api/state, so the brain page works unchanged. Any thread. */
    fun stateJson(): JSONObject {
        val t = now()
        val s = summary(t)
        val r = rules()
        fun sess(x: Session) = JSONObject().put("app", x.app ?: JSONObject.NULL).put("start", x.start).put("origin", x.origin)
            .put("end", x.end).put("paidFrom", x.paidFrom).put("resisted", x.resisted).put("cont", x.cont)
            .put("free", x.free).put("live", x.live).put("dev", "android")
        fun day(d: Day) = JSONObject().put("date", d.date).put("minutes", d.minutes).put("opens", d.opens).put("resisted", d.resisted)
        return JSONObject()
            .put("health", s.health)
            .put("mood", s.mood)
            .put("session", s.session?.let { sess(it) } ?: JSONObject.NULL)
            .put("today", day(s.today))
            .put("appsToday", JSONObject().apply { s.appsToday.forEach { (k, v) -> put(k ?: "unknown", v) } })
            .put("days", JSONArray().apply { s.days.forEach { put(day(it)) } })
            .put("recent", JSONArray().apply { s.recent.forEach { put(sess(it)) } })
            .put("rules", JSONObject()
                .put("drainPerMin", r.drainPerMin).put("healPerHour", r.healPerHour).put("openCost", r.openCost)
                .put("resistBonus", r.resistBonus).put("resistBonusEveryMin", r.resistBonusEveryMin)
                .put("sessionCapMin", r.sessionCapMin).put("graceMin", r.graceMin).put("nudgeAfterMin", r.nudgeAfterMin)
                .put("nudgeEveryMin", r.nudgeEveryMin).put("overstayDrainPerMin", r.overstayDrainPerMin)
                .put("freeMinPerDay", JSONObject().apply { r.freeMinPerDay.forEach { (k, v) -> put(k, v) } }))
            .put("now", t)
    }

    companion object {
        @Volatile private var instance: Local? = null
        fun get(ctx: Context): Local = instance ?: synchronized(this) {
            instance ?: Local(ctx.applicationContext).also { instance = it }
        }
    }
}

/** Free minutes a day per gated app, set in Setup (on its own; a server has its own list). */
object FreeMinutes {
    fun all(ctx: Context): Map<String, Double> {
        val p = Api.prefs(ctx)
        return p.all.filterKeys { it.startsWith("free_") }
            .mapNotNull { (k, v) -> (v as? Int)?.takeIf { it > 0 }?.let { k.removePrefix("free_") to it.toDouble() } }
            .toMap()
    }
    fun get(ctx: Context, app: String) = Api.prefs(ctx).getInt("free_$app", 0)
    fun set(ctx: Context, app: String, minutes: Int) = Api.prefs(ctx).edit().putInt("free_$app", minutes.coerceIn(0, 240)).apply()
}
