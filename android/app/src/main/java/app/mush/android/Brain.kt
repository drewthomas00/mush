package app.mush.android

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The brain, on the phone. A port of server.mjs's replay(): health is replayed from an event log.
 * It drains while you're in a gated app or short-form video and heals while you're away.
 * Pure Kotlin with no Android imports, so BrainTest runs it on the JVM. Keep it in step with
 * server.mjs (tools/check.mjs and BrainTest cover the same cases).
 */
data class BrainRules(
    val drainPerMin: Double = 1.0,
    val healPerHour: Double = 4.0,
    val openCost: Double = 1.0,
    val resistBonus: Double = 2.0,
    val resistBonusEveryMin: Double = 30.0,
    val sessionCapMin: Double = 45.0,
    val graceMin: Double = 3.0,
    val nudgeAfterMin: Double = 5.0,
    val nudgeEveryMin: Double = 3.0,
    val overstayDrainPerMin: Double = 2.0,
    val freeMinPerDay: Map<String, Double> = emptyMap(),
)

data class Event(val t: Long, val type: String, val app: String?)

data class Session(
    val app: String?,
    val start: Long,
    val origin: Long,
    var end: Long,
    var resisted: Boolean = false,
    val cont: Boolean = false,
    val free: Boolean = false,
    val paidFrom: Long = start,
    val live: Boolean = false,
)

class Replayed(val health: Double, val session: Session?, val sessions: List<Session>)

data class Day(val date: String, var minutes: Double = 0.0, var opens: Int = 0, var resisted: Int = 0)

class Summary(
    val health: Double,
    val mood: String,
    val session: Session?,
    val today: Day,
    val appsToday: Map<String?, Double>,
    val opensByApp: Map<String?, Int>,
    val days: List<Day>,
    val recent: List<Session>,
    val now: Long,
)

data class Nudge(val due: Int, val normal: Int, val mins: Int)

object Brain {
    private const val MIN = 60_000.0
    private const val HOUR = 3_600_000.0
    private fun clamp(h: Double) = h.coerceIn(0.0, 100.0)

    fun dayKey(t: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(t).atZone(zone).toLocalDate().toString() // YYYY-MM-DD

    private class Live(
        val app: String?, val start: Long, val origin: Long, val paidFrom: Long, var capBase: Long,
        var rot: Boolean, val cont: Boolean, val free: Boolean,
    )

    fun replay(evs: List<Event>, now: Long, rules: BrainRules = BrainRules(), zone: ZoneId = ZoneId.systemDefault()): Replayed {
        val cap = (rules.sessionCapMin * MIN).toLong()
        val grace = (rules.graceMin * MIN).toLong()
        var h = 100.0
        var last = evs.firstOrNull()?.t ?: now
        var session: Live? = null
        val sessions = mutableListOf<Session>()
        val graceFrom = HashMap<String?, Long>()
        val graceOrigin = HashMap<String?, Long>()
        val freeUsed = HashMap<String, Long>()
        var lastBonus = Long.MIN_VALUE / 2

        fun allowance(app: String?) = ((rules.freeMinPerDay[app] ?: 0.0) * MIN).toLong()
        fun freeLeft(app: String?, t: Long) = maxOf(0L, allowance(app) - (freeUsed["$app|${dayKey(t, zone)}"] ?: 0L))

        fun advance(to: Long) {
            if (to <= last) return
            val s = session
            if (s != null) {
                val capEnd = s.capBase + cap
                val drainEnd = minOf(to, capEnd)
                val from = maxOf(last, s.paidFrom) // free minutes neither drain nor heal
                if (drainEnd > from) {
                    val overMark = (if (s.free) maxOf(s.origin, s.paidFrom) else s.origin) + (rules.nudgeAfterMin * MIN).toLong()
                    val overAt = maxOf(from, minOf(drainEnd, overMark))
                    h = clamp(h - ((overAt - from) / MIN) * rules.drainPerMin - ((drainEnd - overAt) / MIN) * rules.overstayDrainPerMin)
                }
                if (to > capEnd) h = clamp(h + ((to - maxOf(last, capEnd)) / HOUR) * rules.healPerHour)
            } else {
                h = clamp(h + ((to - last) / HOUR) * rules.healPerHour)
            }
            last = to
        }

        fun endSession(t: Long, resisted: Boolean = false) {
            val s = session ?: return
            val end = minOf(t, s.capBase + cap)
            sessions += Session(s.app, s.start, s.origin, end, resisted, s.cont, s.free, s.paidFrom)
            val usedFree = minOf(end, s.paidFrom) - s.start
            if (usedFree > 0) {
                val k = "${s.app}|${dayKey(s.start, zone)}"
                freeUsed[k] = (freeUsed[k] ?: 0L) + usedFree
            }
            if (s.rot) { graceFrom[s.app] = end; graceOrigin[s.app] = s.origin } // a missed close ended at the cap
            session = null
        }

        fun inGrace(app: String?, t: Long) = graceFrom[app]?.let { t - it < grace } ?: false

        for (ev in evs) {
            advance(ev.t)
            when (ev.type) {
                "open" -> {
                    if (session != null) endSession(ev.t)
                    val cont = inGrace(ev.app, ev.t) // coming back to a session you chose: no prompt, no cost
                    val left = freeLeft(ev.app, ev.t)
                    session = Live(ev.app, ev.t, if (cont) graceOrigin[ev.app] ?: ev.t else ev.t, ev.t + left, ev.t, cont, cont, left > 0)
                    if (!cont && left == 0L) h = clamp(h - rules.openCost)
                }
                "still" -> {
                    // Only while the session is still within its cap: a late heartbeat must not revive one that ended.
                    val s = session
                    if (s != null && s.app == ev.app && ev.t < s.capBase + cap) s.capBase = maxOf(s.capBase, ev.t)
                }
                "close" -> if (session?.app == ev.app && session != null) endSession(ev.t)
                "resist" -> {
                    var target: Session? = null
                    if (session != null && session!!.app == ev.app) {
                        endSession(ev.t, true)
                        target = sessions.last()
                    } else {
                        // The app can report "closed" while the prompt is up; back away from that session instead.
                        val prev = sessions.lastOrNull { it.app == ev.app }
                        if (prev != null && !prev.resisted && ev.t - prev.start < 5 * MIN) {
                            prev.resisted = true
                            prev.end = prev.start
                            target = prev
                        }
                    }
                    if (target != null) {
                        if (!target.cont && !target.free) h = clamp(h + rules.openCost)
                        if (ev.t - lastBonus >= rules.resistBonusEveryMin * MIN) {
                            h = clamp(h + rules.resistBonus)
                            lastBonus = ev.t
                        }
                    }
                }
                "rot" -> {
                    val s = session
                    if (s != null && s.app == ev.app) {
                        s.rot = true
                        graceOrigin[ev.app] = s.origin
                    } else {
                        val prev = sessions.lastOrNull { it.app == ev.app }
                        graceOrigin[ev.app] = if (prev != null && ev.t - prev.start < 5 * MIN) prev.origin else ev.t
                    }
                    graceFrom[ev.app] = ev.t
                }
            }
        }
        advance(now)

        var live: Session? = null
        session?.let { s ->
            val capEnd = s.capBase + cap
            val capped = Session(s.app, s.start, s.origin, minOf(now, capEnd), false, s.cont, s.free, s.paidFrom, live = now < capEnd)
            if (now < capEnd) live = capped
            sessions += capped // past the cap it is over, whether or not a close ever arrives
        }
        return Replayed(h, live, sessions)
    }

    fun mood(h: Double) = when {
        h >= 85 -> "sharp"
        h >= 65 -> "okay"
        h >= 45 -> "foggy"
        h >= 25 -> "mushy"
        h > 0 -> "soup"
        else -> "liquefied"
    }

    fun summarize(evs: List<Event>, now: Long, rules: BrainRules = BrainRules(), zone: ZoneId = ZoneId.systemDefault()): Summary {
        val r = replay(evs, now, rules, zone)
        val days = HashMap<String, Day>()
        for (s in r.sessions) {
            val d = days.getOrPut(dayKey(s.start, zone)) { Day(dayKey(s.start, zone)) }
            if (!s.cont) d.opens += 1
            d.minutes += (s.end - s.start) / MIN
            if (s.resisted) d.resisted += 1
        }
        // Step by calendar day, not 24 h, so the day the clocks change is not doubled or dropped.
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val last14 = (13 downTo 0).map { i ->
            val k = today.minusDays(i.toLong()).toString()
            days[k] ?: Day(k)
        }
        val minutesByApp = LinkedHashMap<String?, Double>()
        val opensByApp = LinkedHashMap<String?, Int>()
        for (s in r.sessions) {
            if (dayKey(s.start, zone) != today.toString()) continue
            minutesByApp[s.app] = (minutesByApp[s.app] ?: 0.0) + (s.end - s.start) / MIN
            if (!s.cont) opensByApp[s.app] = (opensByApp[s.app] ?: 0) + 1
        }
        return Summary(
            health = Math.round(r.health * 10) / 10.0,
            mood = mood(r.health),
            session = r.session,
            today = last14.last(),
            appsToday = minutesByApp,
            opensByApp = opensByApp,
            days = last14,
            recent = r.sessions.takeLast(12).reversed(),
            now = now,
        )
    }

    /** How many nudges a live session is owed by `now`. */
    fun nudgesDue(live: Session, now: Long, rules: BrainRules = BrainRules()): Nudge {
        val hadFree = live.paidFrom > live.start
        val freeDue = if (hadFree && now >= live.paidFrom) 1 else 0
        val clockStart = if (hadFree) maxOf(live.origin, live.paidFrom) else live.origin
        val paidMin = (now - clockStart) / MIN
        val normal = if (paidMin >= rules.nudgeAfterMin) 1 + Math.floor((paidMin - rules.nudgeAfterMin) / rules.nudgeEveryMin).toInt() else 0
        return Nudge(freeDue + normal, normal, Math.floor((now - live.origin) / MIN).toInt())
    }

    private val NAMES = mapOf(
        "tiktok" to "TikTok", "youtube" to "YouTube", "x" to "X", "instagram" to "Instagram", "reddit" to "Reddit",
        "snapchat" to "Snapchat", "facebook" to "Facebook", "threads" to "Threads", "linkedin" to "LinkedIn",
    )

    fun pretty(app: String?) = when {
        app.isNullOrEmpty() -> "That app"
        else -> NAMES[app] ?: app.replaceFirstChar { it.uppercase() }
    }

    private val LINES = mapOf(
        "sharp" to listOf("It is sharp right now. Keep it that way?", "Clear head. Do you really need this?"),
        "okay" to listOf("Still doing okay. This is how it slips.", "Fine for now. One more scroll is never one."),
        "foggy" to listOf("It is getting foggy in here.", "Your brain is asking for a break."),
        "mushy" to listOf("It is going soft. Please.", "Mush is losing its shape."),
        "soup" to listOf("It is soup. Put the phone down.", "There is barely a brain left."),
        "liquefied" to listOf("It has liquefied. Go outside.", "Nothing left to rot. Go touch grass."),
    )

    /** The prompt text when you open a gated app or short-form video starts. */
    fun gateText(s: Summary, app: String?, rules: BrainRules = BrainRules()): String {
        val opens = s.opensByApp[app] ?: 1
        val lines = LINES.getValue(s.mood)
        val out = mutableListOf(
            "🧠 Mush ${Math.round(s.health)}% · ${s.mood}",
            "${pretty(app)} open #$opens today. ${Math.round(s.today.minutes)} min scrolled across all apps.",
        )
        rules.freeMinPerDay[app]?.let { out += "Your ${it.toInt()} free minutes are used up for today." }
        out += lines[s.today.opens % lines.size]
        out += "Rot anyway: nudges at ${rules.nudgeAfterMin.toInt()} min; after that each minute costs ${rules.overstayDrainPerMin.toInt()}."
        return out.joinToString("\n")
    }

    /** Title and body of the nudge a live session is owed, or null when none is. */
    fun nudgeMessage(s: Summary, live: Session, now: Long, rules: BrainRules = BrainRules()): Pair<Int, Pair<String, String>?> {
        val n = nudgesDue(live, now, rules)
        if (n.due == 0) return 0 to null
        val name = pretty(live.app)
        val pct = Math.round(s.health)
        if (n.normal == 0) {
            val free = rules.freeMinPerDay[live.app]?.toInt() ?: 0
            return n.due to ("🧠 Free $name time's up" to "That was your $free free minutes for today. From now on it costs.")
        }
        val lines = listOf(
            "${n.mins} min in $name. From here every minute costs ${rules.overstayDrainPerMin.toInt()}%.",
            "${n.mins} min in $name. Mush is at $pct% and it's sliding. Is this still a choice?",
            "${n.mins} min. You said you wanted to stop this. Put it down.",
            "${n.mins} min in $name. Mush is melting. Get out now.",
        )
        return n.due to ("🧠 Mush $pct% · ${s.mood}" to lines[minOf(n.normal - 1, lines.size - 1)])
    }
}
