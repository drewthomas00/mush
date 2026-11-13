// Quick checks of the health model: node tools/check.mjs
import assert from 'node:assert/strict';
import { replay, nudgesDue, RULES, summarize, decide } from '../server.mjs';

const M = 60_000, H = 3_600_000, t0 = Date.UTC(2026, 0, 1, 12);
const near = (a, b) => assert.ok(Math.abs(a - b) < 1e-6, `${a} != ${b}`);
const ev = (min, type, app = 'ig') => ({ t: t0 + min * M, type, app });
// The checks down to the nudge section use a flat drain (no overstay, no free apps) to keep the arithmetic readable.
const FLAT = { ...RULES, overstayDrainPerMin: RULES.drainPerMin, freeMinPerDay: {} };
const flat = (evs, now) => replay(evs, now, FLAT);
// The free-minutes cases use LinkedIn with 10 a day (MUSH_FREE_MINUTES=linkedin=10 on a server).
const LI = { ...RULES, freeMinPerDay: { linkedin: 10 } };

// 30 min in instagram: -1 open, -30 drain
let r = flat([ev(0, 'open'), ev(30, 'close')], t0 + 30 * M);
near(r.health, 69); assert.equal(r.sessions.length, 1);

// healing: 5 hours later +20
r = flat([ev(0, 'open'), ev(30, 'close')], t0 + 30 * M + 5 * H);
near(r.health, 89);

// no close: drain stops at the 45 min cap, then it heals, and the session is no longer live
r = flat([ev(0, 'open')], t0 + 45 * M + H);
near(r.health, 100 - 1 - 45 + 4);
assert.equal(r.session, null);
assert.equal(r.sessions.at(-1).end - r.sessions.at(-1).start, 45 * M);
r = flat([ev(0, 'open')], t0 + 10 * M);
assert.equal(r.session.live, true);

// back away: refunds the open and adds the bonus once per 30 min, so reflex opens never pay
const start = [ev(-200, 'open'), ev(-150, 'close')]; // 51% then 50 min of healing
const base = flat(start, t0).health;
r = flat([...start, ev(0, 'open'), ev(0, 'resist')], t0);
near(r.health, base + 2); assert.equal(r.sessions.at(-1).resisted, true); assert.equal(r.session, null);
const loop = [...start];
for (let i = 0; i < 10; i++) loop.push(ev(i, 'open'), ev(i, 'resist'));
near(flat(loop, t0 + 9 * M).health, flat(start, t0 + 9 * M).health + 2);

// iOS fires "closed" while the prompt is up: back away still counts
r = flat([...start, ev(0, 'open'), ev(0.1, 'close'), ev(0.2, 'resist')], t0 + 0.2 * M);
assert.equal(r.sessions.at(-1).resisted, true);
assert.ok(Math.abs(r.health - (flat(start, t0 + 0.2 * M).health + 2)) < 0.2); // minus the 6 s spent in the app

// a stray resist with nothing to back away from does nothing
near(flat([...start, ev(0, 'resist')], t0).health, base);

// out-of-order app switch: "tiktok opened" then "ig closed" must not close tiktok
r = flat([ev(0, 'open'), ev(10, 'open', 'tiktok'), ev(10, 'close')], t0 + 20 * M);
assert.equal(r.session.app, 'tiktok'); near(r.health, 100 - 2 - 20);

// grace runs from leaving a session you chose to rot in, and those reopens are free
r = flat([ev(0, 'open'), ev(0, 'rot'), ev(10, 'close'), ev(12, 'open')], t0 + 12 * M);
assert.equal(r.session.cont, true); near(r.health, 100 - 1 - 10 + (2 / 60) * 4); // no open cost, 2 min healed
r = flat([ev(0, 'open'), ev(0, 'rot'), ev(10, 'close'), ev(14, 'open')], t0 + 14 * M);
assert.equal(r.session.cont, false);
// ...and it keeps working across several pop-outs
r = flat([ev(0, 'open'), ev(0, 'rot'), ev(5, 'close'), ev(6, 'open'), ev(20, 'close'), ev(22, 'open')], t0 + 22 * M);
assert.equal(r.session.cont, true);
// a rotted session whose close never arrives does not wave the next open through hours later
r = flat([ev(0, 'open'), ev(0, 'rot'), ev(540, 'open')], t0 + 540 * M);
assert.equal(r.session.cont, false);
// grace is per app
r = flat([ev(0, 'open'), ev(0, 'rot'), ev(1, 'close'), ev(2, 'open', 'tiktok')], t0 + 2 * M);
assert.equal(r.session.cont, false);

// names that are Object.prototype keys behave like any other name
r = flat([ev(0, 'open', 'constructor'), ev(0, 'rot', 'constructor'), ev(1, 'close', 'constructor'), ev(2, 'open', '__proto__')], t0 + 2 * M);
assert.equal(r.session.cont, false); assert.equal(r.session.app, '__proto__');

// past the nudge mark every minute costs double: 5 min at 1, then 5 at 2
r = replay([ev(0, 'open'), ev(10, 'close')], t0 + 10 * M);
near(r.health, 100 - 1 - 5 - 10);
// popping out inside grace does not restart the nudge clock
r = replay([ev(0, 'open'), ev(0, 'rot'), ev(4, 'close'), ev(5, 'open'), ev(8, 'close')], t0 + 8 * M);
near(r.health, 100 - 1 - 4 - 3 * 2 + (1 / 60) * 4); // 4 min at 1, out for 1, back at min 5: the 3 min after that at 2
assert.equal(nudgesDue({ ...r.sessions.at(-1), paidFrom: r.sessions.at(-1).start, origin: t0 }, t0 + 5 * M).due, 1);

// nudges: 5 min in, then every 3
const live = (start, extra = {}) => ({ app: 'ig', start: t0 + start * M, origin: t0 + start * M, paidFrom: t0 + start * M, ...extra });
assert.equal(nudgesDue(live(0), t0 + 4.9 * M).due, 0);
assert.equal(nudgesDue(live(0), t0 + 5 * M).due, 1);
assert.equal(nudgesDue(live(0), t0 + 7.9 * M).due, 1);
assert.equal(nudgesDue(live(0), t0 + 8 * M).due, 2);

// linkedin: 10 free minutes a day, no open cost, then normal rules from where the free time ran out
r = replay([ev(0, 'open', 'linkedin')], t0 + 10 * M, LI);
near(r.health, 100); assert.equal(r.session.free, true);
r = replay([ev(0, 'open', 'linkedin')], t0 + 20 * M, LI);
near(r.health, 100 - 5 - 5 * 2); // the open was free; 5 paid min at 1, then 5 at 2
// spread over several opens, it is 10 minutes a day, not per open
r = replay([ev(0, 'open', 'linkedin'), ev(6, 'close', 'linkedin'), ev(60, 'open', 'linkedin'), ev(70, 'close', 'linkedin')], t0 + 70 * M, LI);
near(r.health, 100 - 5 - 2); // second open: free to open (4 free min left), then 5 paid min at 1 and the 6th at 2
r = replay([ev(0, 'open', 'linkedin'), ev(10, 'close', 'linkedin'), ev(60, 'open', 'linkedin')], t0 + 60 * M, LI);
assert.equal(r.session.free, false); // used up: this open gets the prompt and costs
// nudges for a free session: one "free time's up" at 10 min, then the normal ones from there
const li = { app: 'linkedin', start: t0, origin: t0, paidFrom: t0 + 10 * M };
assert.equal(nudgesDue(li, t0 + 9.9 * M).due, 0);
assert.deepEqual([nudgesDue(li, t0 + 10 * M).due, nudgesDue(li, t0 + 10 * M).normal], [1, 0]);
assert.deepEqual([nudgesDue(li, t0 + 15 * M).due, nudgesDue(li, t0 + 15 * M).normal], [2, 1]);
// other apps get nothing free
assert.equal(replay([ev(0, 'open', 'facebook')], t0).session.free, false);

// iOS fires "closed" while the prompt is up, then rot, then the app comes back: a normal grace return
r = replay([ev(0, 'open'), ev(0.1, 'close'), ev(0.2, 'rot'), ev(0.3, 'open')], t0 + 10 * M);
assert.ok(Number.isFinite(r.health)); assert.equal(r.session.cont, true); assert.equal(r.session.origin, t0);
assert.equal(nudgesDue(r.session, t0 + 10 * M).due, 2);
// ...and an old rotted run of the same app does not lend it its origin
r = replay([ev(-1440, 'open'), ev(-1440, 'rot'), ev(-1430, 'close'), ev(0, 'open'), ev(0.1, 'close'), ev(0.2, 'rot'), ev(1.7, 'open')], t0 + 2 * M);
assert.equal(r.session.origin, t0); assert.equal(nudgesDue(r.session, t0 + 2 * M).due, 0);
// a rot with no recent session starts its run at the rot
r = replay([ev(0, 'rot'), ev(1, 'open')], t0 + 1 * M);
assert.ok(Number.isFinite(r.health)); assert.equal(r.session.origin, t0);
// replay itself carries the origin across a pop-out to the nudge schedule
r = replay([ev(0, 'open'), ev(0, 'rot'), ev(4, 'close'), ev(5, 'open')], t0 + 5 * M);
assert.equal(r.session.origin, t0); assert.equal(nudgesDue(r.session, t0 + 5 * M).due, 1);

// the device that opened a session is carried on it (the Android app nudges itself; push skips it)
assert.equal(replay([{ ...ev(0, 'open', 'instagram'), dev: 'android' }], t0 + M).session.dev, 'android');
assert.equal(replay([ev(0, 'open', 'instagram')], t0 + M).session.dev, 'ios');

// a "still here" heartbeat keeps a long session live past the cap (Android knows you're there)
const hb = [ev(0, 'open'), ev(0, 'rot')];
for (let m = 5; m <= 55; m += 5) hb.push(ev(m, 'still'));
r = replay(hb, t0 + 60 * M);
assert.equal(r.session.live, true); assert.equal(nudgesDue(r.session, t0 + 60 * M).due, 19);
near(r.health, 0);
// ...and without heartbeats the cap still ends it 45 min after the last one
r = replay([ev(0, 'open'), ev(30, 'still')], t0 + 80 * M);
assert.equal(r.session, null); assert.equal(r.sessions.at(-1).end, t0 + 75 * M);
// a heartbeat after the cap does not revive the session (e.g. a missed iPhone close hours earlier)
r = replay([ev(0, 'open', 'instagram'), ev(365, 'still', 'instagram')], t0 + 366 * M);
assert.equal(r.session, null); assert.equal(r.sessions.at(-1).end, t0 + 45 * M);
// a heartbeat for another app does nothing
r = replay([ev(0, 'open'), ev(30, 'still', 'tiktok')], t0 + 50 * M);
assert.equal(r.session, null);

// ---- goals, short-form, pace (MUSH_GOALS="facebook+linkedin=30,short=0" on a server)
const G = { ...RULES, freeMinPerDay: { linkedin: 10 }, goals: [{ apps: ['facebook', 'linkedin'], max: 30 }], shortMax: 0, shortApps: ['tiktok'] };
// the budget is shared, counts paid minutes only, and gates normally until it's used up
let sm = summarize([ev(0, 'open', 'facebook'), ev(20, 'close', 'facebook'), ev(30, 'open', 'linkedin'), ev(45, 'close', 'linkedin')], t0 + 46 * M, G);
assert.equal(Math.round(sm.goals[0].used), 25); // 20 facebook + 5 linkedin (its first 10 are free)
let d = decide(summarize([ev(0, 'open', 'facebook'), ev(20, 'close', 'facebook'), ev(30, 'open', 'facebook')], t0 + 30 * M, G), 'facebook');
assert.equal(d.stop, false); assert.ok(d.text.includes('Goal: 10 of 30 min left'));
// used up: a hard stop, even coming back inside the grace window
const over = [ev(0, 'open', 'facebook'), ev(0, 'rot', 'facebook'), ev(31, 'close', 'facebook'), ev(32, 'open', 'facebook')];
d = decide(summarize(over, t0 + 32 * M, G), 'facebook');
assert.equal(d.stop, true); assert.ok(d.text.startsWith('⛔ Over today'));
// ...but free puzzle minutes still let you in past the goal
d = decide(summarize([ev(0, 'open', 'facebook'), ev(31, 'close', 'facebook'), ev(40, 'open', 'linkedin')], t0 + 40 * M, G), 'linkedin');
assert.equal(d.allow, true);
// no goal for an app: the normal prompt
d = decide(summarize([ev(0, 'open', 'instagram')], t0, G), 'instagram');
assert.equal(d.stop, false); assert.equal(d.allow, false);
// short-form with a goal of 0: always a stop, even in free minutes or grace; TikTok counts as short-form throughout
assert.equal(decide(summarize([ev(0, 'open', 'tiktok')], t0, G), 'tiktok').stop, true);
assert.equal(decide(summarize([ev(0, 'open', 'linkedin'), ev(0, 'short', 'linkedin')], t0, G), 'linkedin', true).stop, true);
// short-form minutes are counted from 'short' to 'unshort' (or the end of the session)
sm = summarize([ev(0, 'open', 'facebook'), ev(5, 'short', 'facebook'), ev(9, 'unshort', 'facebook'), ev(12, 'short', 'facebook'), ev(15, 'close', 'facebook')], t0 + 16 * M, G);
near(sm.short.used, 7);
sm = summarize([ev(0, 'open', 'facebook'), ev(5, 'short', 'facebook')], t0 + 8 * M, G);
near(sm.short.used, 3); assert.equal(sm.session.shortOn, true);
// pace: today so far against the last 7 days up to the same time of day
const pace = [];
for (let dd = 1; dd <= 7; dd++) pace.push(ev(-dd * 1440 - 60, 'open', 'facebook'), ev(-dd * 1440 - 40, 'close', 'facebook'), ev(-dd * 1440 + 60, 'open', 'facebook'), ev(-dd * 1440 + 90, 'close', 'facebook'));
pace.push(ev(-30, 'open', 'facebook'), ev(-20, 'close', 'facebook'));
pace.sort((a, b) => a.t - b.t); // the log is always in time order
sm = summarize(pace, t0, G);
near(sm.pace.avg, 20); assert.equal(sm.pace.days, 7); // 20 min before noon each day; the afternoon sessions are later than now
near(sm.pace.today, 10);

// floor
r = flat([ev(0, 'open'), ev(44, 'close'), ev(44, 'open'), ev(88, 'close'), ev(88, 'open'), ev(130, 'close')], t0 + 130 * M);
near(r.health, 0);

console.log('all checks passed');
