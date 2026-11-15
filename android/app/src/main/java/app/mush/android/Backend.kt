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
    /** Records the open; the gate's decision (allow, hard stop, or the prompt), null if unreachable. */
    fun open(app: String, short: Boolean, done: (Decision?) -> Unit)
    /** Short-form video started inside the live session; the stop text if that's past the goal, else null. */
    fun short(app: String, done: (String?) -> Unit)
    fun record(type: String, app: String) // close, rot, resist, still, unshort
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
    override fun open(app: String, short: Boolean, done: (Decision?) -> Unit) =
        Api.get(ctx, "/api/open?app=${Api.q(app)}&dev=android&fmt=json${if (short) "&short=1" else ""}") { body ->
            done(body?.let { b ->
                runCatching { JSONObject(b) }.getOrNull()?.let { Decision(it.optBoolean("allow"), it.optBoolean("stop"), it.optString("text").ifEmpty { null }) }
                    // a server older than 1.1 answers in text: "true" lets you in, anything else is the prompt
                    ?: if (b.trim() == "true") Decision(true, false, null) else Decision(false, false, b.trim())
            })
            Widget.refresh(ctx)
        }
    override fun short(app: String, done: (String?) -> Unit) =
        Api.get(ctx, "/api/short?app=${Api.q(app)}&dev=android") { body ->
            val o = body?.let { runCatching { JSONObject(it) }.getOrNull() }
            done(if (o?.optBoolean("stop") == true) o.optString("text") else null)
        }
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

    fun rules(): BrainRules {
        val g = Goals.current(ctx)
        return BrainRules(freeMinPerDay = FreeMinutes.all(ctx), goals = g.goals, shortMax = g.shortMax)
    }

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

    override fun open(app: String, short: Boolean, done: (Decision?) -> Unit) {
        val t = now()
        add("open", app, t)
        if (short) add("short", app, t)
        val d = Brain.decide(summary(t), app, short, rules())
        main.post { done(d) }
    }

    override fun short(app: String, done: (String?) -> Unit) {
        val t = now()
        add("short", app, t)
        val stop = Brain.shortStop(summary(t), app)
        main.post { done(stop) }
    }

    override fun record(type: String, app: String) { add(type, app, now()) }

    override fun nudge(app: String, done: (JSONObject?) -> Unit) {
        val t = now()
        val s = summary(t)
        val live = s.session
        val out = if (live == null || live.app != app) JSONObject().put("due", 0) else {
            val (due, msg) = Brain.nudgeMessage(s, live, t, rules())
            val stop = Brain.stopNow(s, live, t)
            if (stop != null) JSONObject().put("due", due).put("stop", true).put("title", "⛔ Goal reached").put("body", stop)
            else JSONObject().put("due", due).apply { msg?.let { put("title", it.first).put("body", it.second) } }
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
            .put("free", x.free).put("live", x.live).put("dev", "android").put("shortMs", x.shortMs).put("shortOn", x.shortOn)
        fun day(d: Day) = JSONObject().put("date", d.date).put("minutes", d.minutes).put("opens", d.opens).put("resisted", d.resisted).put("over", d.over)
        return JSONObject()
            .put("health", s.health)
            .put("mood", s.mood)
            .put("session", s.session?.let { sess(it) } ?: JSONObject.NULL)
            .put("today", day(s.today))
            .put("appsToday", JSONObject().apply { s.appsToday.forEach { (k, v) -> put(k ?: "unknown", v) } })
            .put("appsFree", JSONObject().apply { s.appsFree.forEach { (k, v) -> put(k ?: "unknown", v) } })
            .put("days", JSONArray().apply { s.days.forEach { put(day(it)) } })
            .put("recent", JSONArray().apply { s.recent.forEach { put(sess(it)) } })
            .put("rules", JSONObject()
                .put("drainPerMin", r.drainPerMin).put("healPerHour", r.healPerHour).put("openCost", r.openCost)
                .put("resistBonus", r.resistBonus).put("resistBonusEveryMin", r.resistBonusEveryMin)
                .put("sessionCapMin", r.sessionCapMin).put("graceMin", r.graceMin).put("nudgeAfterMin", r.nudgeAfterMin)
                .put("nudgeEveryMin", r.nudgeEveryMin).put("overstayDrainPerMin", r.overstayDrainPerMin)
                .put("freeMinPerDay", JSONObject().apply { r.freeMinPerDay.forEach { (k, v) -> put(k, v) } }))
            .put("goals", JSONArray().apply {
                s.goals.forEach { g -> put(JSONObject().put("apps", JSONArray(g.goal.apps.sorted())).put("max", g.goal.max).put("used", g.used)) }
            })
            .put("short", JSONObject().put("used", s.shortUsed).put("max", s.shortMax ?: JSONObject.NULL))
            .put("pace", JSONObject().put("today", s.pace.today).put("avg", s.pace.avg ?: JSONObject.NULL).put("days", s.pace.days))
            .put("now", t)
    }

    companion object {
        @Volatile private var instance: Local? = null
        fun get(ctx: Context): Local = instance ?: synchronized(this) {
            instance ?: Local(ctx.applicationContext).also { instance = it }
        }
    }
}

/** Daily goals, set in Setup (on its own; a server has its own, MUSH_GOALS). */
/**
 * Goals for the phone on its own, edited on the goals page (goals.html, through MainActivity's
 * bridge). A server keeps its own. Stored as the server's settings.json shape.
 */
object Goals {
    private const val KEY = "goals"

    /** The goals in force, with a waiting change applied once its day has come. */
    @Synchronized fun current(ctx: Context, now: Long = System.currentTimeMillis()): GoalSet {
        val p = Api.prefs(ctx)
        val stored = p.getString(KEY, null)?.let { runCatching { parse(JSONObject(it)) }.getOrNull() } ?: fromOldSetup(ctx)
        val settled = stored.settle(now)
        if (settled != stored || !p.contains(KEY)) p.edit().putString(KEY, json(settled).toString()).apply()
        return settled
    }

    /** Saves goals sent by the page; null if they don't look right. Returns what the page shows. */
    @Synchronized fun save(ctx: Context, body: String, now: Long = System.currentTimeMillis()): JSONObject? {
        val next = runCatching { parse(JSONObject(body), pending = false) }.getOrNull()?.takeIf { it.valid() } ?: return null
        val st = current(ctx, now).adopt(next, now)
        Api.prefs(ctx).edit().putString(KEY, json(st).toString()).apply()
        return view(ctx)
    }

    /** The same shape as the server's /api/settings. */
    fun view(ctx: Context): JSONObject {
        val st = current(ctx)
        val apps = RuleSet.load(ctx).gates.map { it.app } + st.goals.flatMap { it.apps } + st.pending?.goals.orEmpty().flatMap { it.apps }
        return json(st).put("apps", JSONArray(apps.distinct().sorted()))
    }

    /** Before 1.2 Setup had one goal (goalApps, goalMin) and a no-short-form switch. */
    private fun fromOldSetup(ctx: Context): GoalSet {
        val p = Api.prefs(ctx)
        val apps = p.getStringSet("goalApps", null).orEmpty()
        val max = p.getInt("goalMin", 0)
        val goals = if (apps.isNotEmpty() && max > 0) listOf(Goal(apps, max.toDouble())) else emptyList()
        return GoalSet(goals, if (p.getBoolean("noShort", false)) 0.0 else null)
    }

    private fun cleanApp(a: String) = a.trim().lowercase().filter { it in 'a'..'z' || it in '0'..'9' || it in " ._-" }.take(40)

    private fun parseGoals(a: JSONArray): List<Goal> = (0 until a.length()).map { i ->
        val g = a.getJSONObject(i)
        val list = g.getJSONArray("apps")
        Goal((0 until list.length()).map { cleanApp(list.getString(it)) }.filter { it.isNotEmpty() }.toSet(), g.getDouble("max"))
    }

    private fun short(o: JSONObject): Double? {
        require(o.has("shortMax"))
        return if (o.isNull("shortMax")) null else o.getDouble("shortMax")
    }

    private fun parse(o: JSONObject, pending: Boolean = true): GoalSet {
        val waiting = if (!pending || o.isNull("pending")) null else o.getJSONObject("pending").let {
            GoalSet.Pending(parseGoals(it.getJSONArray("goals")), short(it), it.getLong("from"))
        }
        return GoalSet(parseGoals(o.getJSONArray("goals")), short(o), waiting)
    }

    private fun goalsJson(gs: List<Goal>) = JSONArray().apply {
        gs.forEach { put(JSONObject().put("apps", JSONArray(it.apps.sorted())).put("max", it.max.toInt())) }
    }

    private fun json(st: GoalSet): JSONObject = JSONObject()
        .put("goals", goalsJson(st.goals))
        .put("shortMax", st.shortMax?.toInt() ?: JSONObject.NULL)
        .put("pending", st.pending?.let {
            JSONObject().put("goals", goalsJson(it.goals)).put("shortMax", it.shortMax?.toInt() ?: JSONObject.NULL).put("from", it.from)
        } ?: JSONObject.NULL)
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
