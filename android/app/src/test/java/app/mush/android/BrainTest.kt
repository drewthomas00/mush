package app.mush.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/** The same cases as tools/check.mjs, so the phone's brain and the server's agree. */
class BrainTest {
    private val M = 60_000L
    private val H = 3_600_000L
    private val t0 = 1_767_268_800_000L // 2026-01-01 12:00 UTC
    private val utc = ZoneId.of("UTC")
    private val flat = BrainRules(overstayDrainPerMin = 1.0)
    private val linkedin = BrainRules(freeMinPerDay = mapOf("linkedin" to 10.0))

    private fun ev(min: Double, type: String, app: String = "ig") = Event(t0 + (min * M).toLong(), type, app)
    private fun replay(evs: List<Event>, now: Long, rules: BrainRules = BrainRules()) = Brain.replay(evs, now, rules, utc)
    private fun near(expected: Double, actual: Double) = assertEquals(expected, actual, 1e-6)

    @Test fun drainsAndHeals() {
        near(69.0, replay(listOf(ev(0.0, "open"), ev(30.0, "close")), t0 + 30 * M, flat).health)
        near(89.0, replay(listOf(ev(0.0, "open"), ev(30.0, "close")), t0 + 30 * M + 5 * H, flat).health)
    }

    @Test fun capEndsAMissingClose() {
        val r = replay(listOf(ev(0.0, "open")), t0 + 45 * M + H, flat)
        near(100.0 - 1 - 45 + 4, r.health)
        assertNull(r.session)
        assertEquals(45 * M, r.sessions.last().end - r.sessions.last().start)
    }

    @Test fun backingAwayRefundsOnceAndNeverPays() {
        val start = listOf(ev(-200.0, "open"), ev(-150.0, "close"))
        val base = replay(start, t0, flat).health
        val r = replay(start + listOf(ev(0.0, "open"), ev(0.0, "resist")), t0, flat)
        near(base + 2, r.health)
        assertTrue(r.sessions.last().resisted)
        val loop = start.toMutableList()
        for (i in 0 until 10) { loop += ev(i.toDouble(), "open"); loop += ev(i.toDouble(), "resist") }
        near(replay(start, t0 + 9 * M, flat).health + 2, replay(loop, t0 + 9 * M, flat).health)
    }

    @Test fun outOfOrderSwitchKeepsTheNewApp() {
        val r = replay(listOf(ev(0.0, "open"), ev(10.0, "open", "tiktok"), ev(10.0, "close")), t0 + 20 * M, flat)
        assertEquals("tiktok", r.session?.app)
        near(100.0 - 2 - 20, r.health)
    }

    @Test fun graceAfterRotIsFreeAndPerApp() {
        var r = replay(listOf(ev(0.0, "open"), ev(0.0, "rot"), ev(10.0, "close"), ev(12.0, "open")), t0 + 12 * M, flat)
        assertTrue(r.session!!.cont)
        r = replay(listOf(ev(0.0, "open"), ev(0.0, "rot"), ev(10.0, "close"), ev(14.0, "open")), t0 + 14 * M, flat)
        assertFalse(r.session!!.cont)
        r = replay(listOf(ev(0.0, "open"), ev(0.0, "rot"), ev(1.0, "close"), ev(2.0, "open", "tiktok")), t0 + 2 * M, flat)
        assertFalse(r.session!!.cont)
        // a rotted session whose close never arrives does not wave the next open through hours later
        r = replay(listOf(ev(0.0, "open"), ev(0.0, "rot"), ev(540.0, "open")), t0 + 540 * M)
        assertFalse(r.session!!.cont)
    }

    @Test fun rotAfterTheCloseIsANormalGraceReturn() {
        val r = replay(listOf(ev(0.0, "open"), ev(0.1, "close"), ev(0.2, "rot"), ev(0.3, "open")), t0 + 10 * M)
        assertFalse(r.health.isNaN())
        assertTrue(r.session!!.cont)
        assertEquals(t0, r.session!!.origin)
        assertEquals(2, Brain.nudgesDue(r.session!!, t0 + 10 * M).due)
    }

    @Test fun overstayDrainsDoubleAndNudges() {
        near(100.0 - 1 - 5 - 10, replay(listOf(ev(0.0, "open"), ev(10.0, "close")), t0 + 10 * M).health)
        val live = Session("ig", t0, t0, t0)
        assertEquals(0, Brain.nudgesDue(live, t0 + (4.9 * M).toLong()).due)
        assertEquals(1, Brain.nudgesDue(live, t0 + 5 * M).due)
        assertEquals(2, Brain.nudgesDue(live, t0 + 8 * M).due)
    }

    @Test fun freeMinutesAreADailyAllowance() {
        near(100.0, replay(listOf(ev(0.0, "open", "linkedin")), t0 + 10 * M, linkedin).health)
        near(100.0 - 5 - 5 * 2, replay(listOf(ev(0.0, "open", "linkedin")), t0 + 20 * M, linkedin).health)
        val used = replay(listOf(ev(0.0, "open", "linkedin"), ev(10.0, "close", "linkedin"), ev(60.0, "open", "linkedin")), t0 + 60 * M, linkedin)
        assertFalse(used.session!!.free)
        val li = Session("linkedin", t0, t0, t0, paidFrom = t0 + 10 * M)
        assertEquals(0, Brain.nudgesDue(li, t0 + (9.9 * M).toLong(), linkedin).due)
        assertEquals(1, Brain.nudgesDue(li, t0 + 10 * M, linkedin).due)
        assertEquals(2, Brain.nudgesDue(li, t0 + 15 * M, linkedin).due)
    }

    @Test fun heartbeatsKeepALongSessionLiveButCantReviveOne() {
        val hb = mutableListOf(ev(0.0, "open"), ev(0.0, "rot"))
        for (m in 5..55 step 5) hb += ev(m.toDouble(), "still")
        val r = replay(hb, t0 + 60 * M)
        assertTrue(r.session!!.live)
        near(0.0, r.health)
        assertNull(replay(listOf(ev(0.0, "open", "instagram"), ev(365.0, "still", "instagram")), t0 + 366 * M).session)
    }

    private val goals = BrainRules(
        freeMinPerDay = mapOf("linkedin" to 10.0),
        goals = listOf(Goal(setOf("facebook", "linkedin"), 30.0)),
        shortMax = 0.0,
    )
    private fun sum(evs: List<Event>, now: Long) = Brain.summarize(evs.sortedBy { it.t }, now, goals, utc)

    @Test fun goalsShareABudgetOfPaidMinutesAndHardStop() {
        val s = sum(listOf(ev(0.0, "open", "facebook"), ev(20.0, "close", "facebook"), ev(30.0, "open", "linkedin"), ev(45.0, "close", "linkedin")), t0 + 46 * M)
        near(25.0, s.goals[0].used) // 20 facebook + 5 linkedin (its first 10 are free)
        var d = Brain.decide(sum(listOf(ev(0.0, "open", "facebook"), ev(20.0, "close", "facebook"), ev(30.0, "open", "facebook")), t0 + 30 * M), "facebook", false, goals)
        assertFalse(d.stop)
        assertTrue(d.text!!.contains("Goal: 10 of 30 min left"))
        d = Brain.decide(sum(listOf(ev(0.0, "open", "facebook"), ev(0.0, "rot", "facebook"), ev(31.0, "close", "facebook"), ev(32.0, "open", "facebook")), t0 + 32 * M), "facebook", false, goals)
        assertTrue(d.stop) // even coming back inside grace
        assertTrue(d.text!!.startsWith("⛔ Over today"))
        d = Brain.decide(sum(listOf(ev(0.0, "open", "facebook"), ev(31.0, "close", "facebook"), ev(40.0, "open", "linkedin")), t0 + 40 * M), "linkedin", false, goals)
        assertTrue(d.allow) // free puzzle minutes still let you in
        d = Brain.decide(sum(listOf(ev(0.0, "open", "instagram")), t0), "instagram", false, goals)
        assertFalse(d.stop); assertFalse(d.allow)
    }

    @Test fun shortFormIsCountedAndStopsAtZero() {
        assertTrue(Brain.decide(sum(listOf(ev(0.0, "open", "tiktok")), t0), "tiktok", false, goals).stop)
        assertTrue(Brain.decide(sum(listOf(ev(0.0, "open", "linkedin"), ev(0.0, "short", "linkedin")), t0), "linkedin", true, goals).stop)
        near(7.0, sum(listOf(ev(0.0, "open", "facebook"), ev(5.0, "short", "facebook"), ev(9.0, "unshort", "facebook"),
            ev(12.0, "short", "facebook"), ev(15.0, "close", "facebook")), t0 + 16 * M).shortUsed)
        val live = sum(listOf(ev(0.0, "open", "facebook"), ev(5.0, "short", "facebook")), t0 + 8 * M)
        near(3.0, live.shortUsed)
        assertTrue(live.session!!.shortOn)
        assertTrue(Brain.stopNow(live, live.session!!, t0 + 8 * M)!!.startsWith("⛔ No short-form"))
    }

    @Test fun paceComparesTheSameTimeOfDay() {
        val evs = mutableListOf<Event>()
        for (d in 1..7) {
            val day = -d * 1440.0
            evs += listOf(ev(day - 60, "open", "facebook"), ev(day - 40, "close", "facebook"), ev(day + 60, "open", "facebook"), ev(day + 90, "close", "facebook"))
        }
        evs += listOf(ev(-30.0, "open", "facebook"), ev(-20.0, "close", "facebook"))
        val s = sum(evs, t0)
        near(20.0, s.pace.avg!!)
        assertEquals(7, s.pace.days)
        near(10.0, s.pace.today)
    }

    @Test fun summaryCountsDaysAndGateText() {
        val s = Brain.summarize(listOf(ev(0.0, "open"), ev(30.0, "close")), t0 + 31 * M, BrainRules(), utc)
        assertEquals(14, s.days.size)
        assertEquals("2026-01-01", s.today.date)
        assertEquals(1, s.today.opens)
        assertTrue(Brain.gateText(s, "ig").startsWith("🧠 Mush "))
    }
}
