// Mush: a brain that rots while you doomscroll.
//
// iOS Shortcuts automations call /api/open and /api/close when a tracked app
// opens or closes. The brain's health is replayed from that event log: it
// drains while you are in a tracked app and heals while you are away.
// No dependencies; Node 20+.

import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { Push, validSubscription } from './push.mjs';

const ROOT = path.dirname(fileURLToPath(import.meta.url));
const PUBLIC = path.join(ROOT, 'public');
const PORT = Number(process.env.PORT || 8473);
const HOST = process.env.HOST || '127.0.0.1';
const DATA = process.env.MUSH_DATA || path.join(ROOT, 'data', 'events.jsonl');
const PUSH_FILE = process.env.MUSH_PUSH || path.join(path.dirname(DATA), 'push.json');
const ANDROID_DIR = path.join(path.dirname(DATA), 'android'); // the built APK and its version.json, served at /android/
const DUMP_DIR = path.join(path.dirname(DATA), 'dumps');      // Android screen captures for tuning detection rules
const ANDROID_RULES = path.join(ROOT, 'rules', 'android.json');

function parseFree(spec = '') {
  const out = {};
  for (const part of spec.split(',')) {
    const [app, min] = part.split('=').map((x) => x?.trim());
    if (app && Number(min) > 0) out[app.toLowerCase()] = Number(min);
  }
  return out;
}

// MUSH_GOALS="facebook+linkedin=30,short=0": a daily budget of paid minutes (free minutes don't
// count) shared by the apps joined with '+', and short=N for short-form video (0: none at all).
function parseGoals(spec = '') {
  const groups = [];
  let shortMax = null;
  for (const part of spec.split(',')) {
    const [lhs, rhs] = part.split('=').map((x) => x?.trim());
    const max = Number(rhs);
    if (!lhs || rhs === undefined || rhs === '' || !Number.isFinite(max) || max < 0) continue;
    if (lhs.toLowerCase() === 'short') shortMax = max;
    else groups.push({ apps: lhs.toLowerCase().split('+').map((a) => a.trim()).filter(Boolean), max });
  }
  return { groups, shortMax };
}
const GOALS = parseGoals(process.env.MUSH_GOALS);

export const RULES = {
  drainPerMin: 1,          // health lost per minute inside a tracked app
  healPerHour: 4,          // health regained per hour away (break-even is ~90 min of scrolling a day)
  openCost: 1,             // every open of a tracked app (opens inside the grace window are free)
  resistBonus: 2,          // backing away refunds the open and adds this...
  resistBonusEveryMin: 30, // ...at most once per this many minutes, so reflex opens never pay
  sessionCapMin: 45,       // an open with no close counts at most this long
  graceMin: 3,             // after "rot anyway", leaving and coming back within this skips the prompt
  nudgeAfterMin: 5,        // first nudge this far into a session (a quick pop-out does not restart it)...
  nudgeEveryMin: 3,        // ...then again this often while you stay
  overstayDrainPerMin: 2,  // and from the first nudge on, every minute costs this much
  // free minutes a day per app: no prompt, no cost, no nudges. MUSH_FREE_MINUTES="linkedin=10,reddit=5"
  freeMinPerDay: parseFree(process.env.MUSH_FREE_MINUTES),
  goals: GOALS.groups,       // [{ apps: ['facebook', 'linkedin'], max: 30 }]: at the max, the gate is a hard stop
  shortMax: GOALS.shortMax,  // daily minutes of short-form video allowed; 0 = none; null = no goal
  // apps that are short-form through and through (the iPhone can't see inside apps)
  shortApps: (process.env.MUSH_SHORT_APPS ?? 'tiktok').split(',').map((a) => a.trim().toLowerCase()).filter(Boolean),
};

const MIN = 60_000;
const HOUR = 3_600_000;
const clamp = (h) => Math.max(0, Math.min(100, h));

// ---------- event log ----------

function loadEvents(file) {
  if (!fs.existsSync(file)) return [];
  const text = fs.readFileSync(file, 'utf8');
  // A crash mid-append leaves a line with no newline; end it so the next event starts clean.
  if (text && !text.endsWith('\n')) fs.appendFileSync(file, '\n');
  const out = [];
  for (const line of text.split('\n')) {
    if (!line.trim()) continue;
    try { out.push(JSON.parse(line)); } catch { /* skip a torn line */ }
  }
  return out.sort((a, b) => a.t - b.t);
}

const events = loadEvents(DATA);

// Never earlier than the last event, so the log stays ordered if the clock steps back.
const clock = () => Math.max(Date.now(), events.at(-1)?.t ?? 0);

function record(type, app, t, dev) {
  const ev = dev ? { t, type, app, dev } : { t, type, app };
  fs.mkdirSync(path.dirname(DATA), { recursive: true });
  fs.appendFileSync(DATA, JSON.stringify(ev) + '\n'); // disk first: a failed write must not count
  events.push(ev);
  return ev;
}

// ---------- the brain ----------

export function replay(evs, now, rules = RULES) {
  const cap = rules.sessionCapMin * MIN;
  const grace = rules.graceMin * MIN;
  let h = 100;
  let last = evs[0]?.t ?? now;
  // origin: when this run began (survives pop-outs inside grace). paidFrom: when its free minutes run out.
  // capBase: the cap runs from here, the open or the latest "still here" from a device that knows (Android).
  // shortFrom: when the current stretch of short-form video began (null when there's none); shortMs: total so far.
  let session = null; // { app, start, origin, paidFrom, capBase, rot, cont, free, dev, shortFrom, shortMs }
  const sessions = []; // { app, start, end, resisted, cont, free }
  const graceFrom = new Map(); // app -> the rot, or the close of a session you chose to rot in
  const graceOrigin = new Map(); // app -> when that run of sessions began
  const freeUsed = new Map(); // `${app}|${day}` -> free ms used
  let lastBonus = -Infinity;

  const allowance = (app) => (Object.hasOwn(rules.freeMinPerDay, app) ? rules.freeMinPerDay[app] * MIN : 0);
  const freeLeft = (app, t) => Math.max(0, allowance(app) - (freeUsed.get(`${app}|${dayKey(t)}`) ?? 0));

  const advance = (to) => {
    if (to <= last) return;
    if (session) {
      const capEnd = session.capBase + cap;
      const drainEnd = Math.min(to, capEnd);
      const from = Math.max(last, session.paidFrom); // free minutes neither drain nor heal
      if (drainEnd > from) {
        const overMark = (session.free ? Math.max(session.origin, session.paidFrom) : session.origin) + rules.nudgeAfterMin * MIN;
        const overAt = Math.max(from, Math.min(drainEnd, overMark));
        h = clamp(h - ((overAt - from) / MIN) * rules.drainPerMin - ((drainEnd - overAt) / MIN) * rules.overstayDrainPerMin);
      }
      if (to > capEnd) h = clamp(h + ((to - Math.max(last, capEnd)) / HOUR) * rules.healPerHour);
    } else {
      h = clamp(h + ((to - last) / HOUR) * rules.healPerHour);
    }
    last = to;
  };

  const endSession = (t, resisted = false) => {
    const { app, start, origin, paidFrom, capBase, cont, rot, free } = session;
    const end = Math.min(t, capBase + cap);
    const shortMs = session.shortMs + (session.shortFrom === null ? 0 : Math.max(0, end - session.shortFrom));
    sessions.push({ app, start, origin, end, resisted, cont, free, paidFrom, shortMs });
    const usedFree = Math.min(end, paidFrom) - start;
    if (usedFree > 0) {
      const k = `${app}|${dayKey(start)}`;
      freeUsed.set(k, (freeUsed.get(k) ?? 0) + usedFree);
    }
    if (rot) { graceFrom.set(app, end); graceOrigin.set(app, origin); } // a missed close ended at the cap, not now
    session = null;
  };
  const inGrace = (app, t) => graceFrom.has(app) && t - graceFrom.get(app) < grace;

  for (const ev of evs) {
    advance(ev.t);
    switch (ev.type) {
      case 'open': {
        if (session) endSession(ev.t);
        const cont = inGrace(ev.app, ev.t); // coming back to a session you chose: no prompt, no cost
        const left = freeLeft(ev.app, ev.t);
        session = {
          app: ev.app, start: ev.t, origin: cont ? graceOrigin.get(ev.app) ?? ev.t : ev.t,
          paidFrom: ev.t + left, capBase: ev.t, rot: cont, cont, free: left > 0, dev: ev.dev ?? 'ios',
          shortFrom: rules.shortApps.includes(ev.app) ? ev.t : null, shortMs: 0,
        };
        if (!cont && left === 0) h = clamp(h - rules.openCost);
        break;
      }
      case 'still':
        // Only while the session is still within its cap: a late heartbeat must not revive one that ended.
        if (session && session.app === ev.app && ev.t < session.capBase + cap) session.capBase = Math.max(session.capBase, ev.t);
        break;
      case 'short':
        if (session && session.app === ev.app && session.shortFrom === null && ev.t < session.capBase + cap) session.shortFrom = ev.t;
        break;
      case 'unshort':
        if (session && session.app === ev.app && session.shortFrom !== null) {
          session.shortMs += Math.max(0, Math.min(ev.t, session.capBase + cap) - session.shortFrom);
          session.shortFrom = null;
        }
        break;
      case 'close':
        // App switches can deliver "B opened" before "A closed"; only close the app we are in.
        if (session && session.app === ev.app) endSession(ev.t);
        break;
      case 'resist': {
        let target = null;
        if (session && session.app === ev.app) {
          endSession(ev.t, true);
          target = sessions.at(-1);
        } else {
          // iOS can fire "closed" while the prompt is up; back away from that session instead.
          const prev = sessions.findLast((x) => x.app === ev.app);
          if (prev && !prev.resisted && ev.t - prev.start < 5 * MIN) {
            prev.resisted = true;
            prev.end = prev.start;
            prev.shortMs = 0;
            target = prev;
          }
        }
        if (!target) break;
        if (!target.cont && !target.free) h = clamp(h + rules.openCost);
        if (ev.t - lastBonus >= rules.resistBonusEveryMin * MIN) {
          h = clamp(h + rules.resistBonus);
          lastBonus = ev.t;
        }
        break;
      }
      case 'rot': {
        if (session && session.app === ev.app) {
          session.rot = true;
          graceOrigin.set(ev.app, session.origin);
        } else {
          // iOS can fire "closed" while the prompt is up; the run began with that session.
          const prev = sessions.findLast((x) => x.app === ev.app);
          graceOrigin.set(ev.app, prev && ev.t - prev.start < 5 * MIN ? prev.origin : ev.t);
        }
        graceFrom.set(ev.app, ev.t);
        break;
      }
    }
  }
  advance(now);

  let live = null;
  if (session) {
    const { app, start, origin, paidFrom, capBase, cont, free, dev } = session;
    const end = Math.min(now, capBase + cap);
    const shortOn = session.shortFrom !== null && now < capBase + cap;
    const shortMs = session.shortMs + (session.shortFrom === null ? 0 : Math.max(0, end - session.shortFrom));
    const capped = { app, start, origin, paidFrom, end, resisted: false, cont, free, dev, shortMs, shortOn };
    if (now < capBase + cap) live = { ...capped, live: true };
    sessions.push(live ?? capped); // past the cap it is over, whether or not a close ever arrives
  }
  return { health: h, session: live, sessions };
}

export function mood(h) {
  if (h >= 85) return 'sharp';
  if (h >= 65) return 'okay';
  if (h >= 45) return 'foggy';
  if (h >= 25) return 'mushy';
  if (h > 0) return 'soup';
  return 'liquefied';
}

const dayKey = (t) => new Date(t).toLocaleDateString('en-CA'); // YYYY-MM-DD, server's time zone

export function summarize(evs, now, rules = RULES) {
  const r = replay(evs, now, rules);
  const days = new Map();
  const day = (k) => {
    if (!days.has(k)) days.set(k, { date: k, minutes: 0, opens: 0, resisted: 0 });
    return days.get(k);
  };
  // Free minutes count nowhere: not in today's minutes, the chart, pace or goals (only appsFree shows them).
  const paidStart = (s) => Math.min(Math.max(s.paidFrom ?? s.start, s.start), s.end);
  for (const s of r.sessions) {
    const d = day(dayKey(s.start));
    if (!s.cont) d.opens += 1;
    d.minutes += (s.end - paidStart(s)) / MIN;
    if (s.resisted) d.resisted += 1;
  }
  const last14 = [];
  const n = new Date(now);
  for (let i = 13; i >= 0; i--) {
    // Step by calendar day, not 24 h, so the day the clocks change is not doubled or dropped.
    const k = dayKey(new Date(n.getFullYear(), n.getMonth(), n.getDate() - i, 12).getTime());
    last14.push(days.get(k) ?? { date: k, minutes: 0, opens: 0, resisted: 0 });
  }
  const today = last14.at(-1);
  const minutesByApp = new Map(); // paid minutes, which is also what goals count
  const freeByApp = new Map();
  const opensByApp = new Map();
  let shortToday = 0;
  for (const s of r.sessions) {
    if (dayKey(s.start) !== today.date) continue;
    minutesByApp.set(s.app, (minutesByApp.get(s.app) ?? 0) + (s.end - paidStart(s)) / MIN);
    if (paidStart(s) > s.start) freeByApp.set(s.app, (freeByApp.get(s.app) ?? 0) + (paidStart(s) - s.start) / MIN);
    if (!s.cont) opensByApp.set(s.app, (opensByApp.get(s.app) ?? 0) + 1);
    shortToday += (s.shortMs ?? 0) / MIN;
  }
  const goals = rules.goals.map((g) => ({
    apps: g.apps,
    max: g.max,
    used: g.apps.reduce((sum, a) => sum + (minutesByApp.get(a) ?? 0), 0),
  }));

  // Pace: today so far against the average of the last 7 days up to the same time of day.
  const midnight = new Date(n.getFullYear(), n.getMonth(), n.getDate()).getTime();
  const sinceMidnight = now - midnight;
  const first = evs[0]?.t ?? now;
  let paceSum = 0;
  let paceDays = 0;
  for (let d = 1; d <= 7; d++) {
    const from = new Date(n.getFullYear(), n.getMonth(), n.getDate() - d).getTime();
    const to = from + sinceMidnight;
    if (to <= first) break; // Mush wasn't in use yet by this time that day
    for (const s of r.sessions) paceSum += Math.max(0, Math.min(s.end, to) - Math.max(paidStart(s), from)) / MIN;
    paceDays++;
  }
  return {
    health: Math.round(r.health * 10) / 10,
    mood: mood(r.health),
    session: r.session,
    today,
    appsToday: Object.fromEntries(minutesByApp),
    appsFree: Object.fromEntries(freeByApp),
    opensByApp,
    days: last14,
    recent: r.sessions.slice(-12).reverse(),
    goals,
    short: { used: shortToday, max: rules.shortMax },
    pace: { today: today.minutes, avg: paceDays ? paceSum / paceDays : null, days: paceDays },
    rules,
    now,
  };
}

/**
 * The gate's decision for the session that was just opened in `app`. `short`: the device knows
 * it's short-form video. Short-form past its goal stops even inside free minutes and grace; free
 * minutes let you in even past a goal (they don't count toward it); a used-up goal stops a
 * grace return too.
 */
export function decide(s, app, short = false) {
  const live = s.session;
  const shortGoal = s.short.max;
  const isShort = short || Boolean(live?.shortOn);
  if (isShort && shortGoal !== null && s.short.used >= shortGoal) {
    return { allow: false, stop: true, text: stopText(s, app, 'short') };
  }
  if (live?.free) return { allow: true, stop: false };
  const goal = s.goals.find((g) => g.apps.includes(app) && g.used >= g.max); // any used-up goal it's part of
  if (goal) return { allow: false, stop: true, text: stopText(s, app, 'goal', goal) };
  if (live?.cont) return { allow: true, stop: false };
  return { allow: false, stop: false, text: gateText(s, app) };
}

const goalName = (g) => g.apps.map(pretty).join(' + ');

function stopText(s, app, why, goal) {
  const head = why === 'short'
    ? (s.short.max === 0 ? '⛔ No short-form video. That\'s your goal.' : `⛔ Short-form goal used up: ${Math.round(s.short.used)} of ${s.short.max} min today.`)
    : `⛔ Over today's goal: ${goalName(goal)} ${Math.round(goal.used)} of ${goal.max} min.`;
  return [head, `🧠 Mush ${Math.round(s.health)}% · ${s.mood}`, 'Back away. Tomorrow is a new budget.'].join('\n');
}

// ---------- the prompt text the Shortcut shows ----------

const NAMES = { tiktok: 'TikTok', youtube: 'YouTube', x: 'X', instagram: 'Instagram', reddit: 'Reddit', snapchat: 'Snapchat', facebook: 'Facebook', threads: 'Threads', linkedin: 'LinkedIn' };
const pretty = (app) => (!app ? 'That app' : Object.hasOwn(NAMES, app) ? NAMES[app] : app.charAt(0).toUpperCase() + app.slice(1));

const LINES = {
  sharp: ['It is sharp right now. Keep it that way?', 'Clear head. Do you really need this?'],
  okay: ['Still doing okay. This is how it slips.', 'Fine for now. One more scroll is never one.'],
  foggy: ['It is getting foggy in here.', 'Your brain is asking for a break.'],
  mushy: ['It is going soft. Please.', 'Mush is losing its shape.'],
  soup: ['It is soup. Put the phone down.', 'There is barely a brain left.'],
  liquefied: ['It has liquefied. Go outside.', 'Nothing left to rot. Go touch grass.'],
};

function gateText(s, app) {
  const opens = s.opensByApp.get(app) ?? 1; // includes this open, which was just recorded
  const mins = Math.round(s.today.minutes);
  const pick = LINES[s.mood][s.today.opens % LINES[s.mood].length];
  return [
    `🧠 Mush ${Math.round(s.health)}% · ${s.mood}`,
    `${pretty(app)} open #${opens} today. ${mins} min scrolled across all apps.`,
    ...(Object.hasOwn(RULES.freeMinPerDay, app) ? [`Your ${RULES.freeMinPerDay[app]} free minutes are used up for today.`] : []),
    ...s.goals.filter((g) => g.apps.includes(app)).map((g) => `Goal: ${Math.max(0, Math.round(g.max - g.used))} of ${g.max} min left today (${goalName(g)}).`),
    pick,
    `Rot anyway: nudges at ${RULES.nudgeAfterMin} min; after that each minute costs ${RULES.overstayDrainPerMin}.`,
  ].join('\n');
}

// ---------- nudges ----------

let push = null;
const nudged = new Map(); // `${app}@${origin}` -> nudges sent for that run

function nudgeText(s, n, mins) {
  const name = pretty(s.session.app);
  const pct = Math.round(s.health);
  const lines = [
    `${mins} min in ${name}. From here every minute costs ${RULES.overstayDrainPerMin}%.`,
    `${mins} min in ${name}. Mush is at ${pct}% and it's sliding. Is this still a choice?`,
    `${mins} min. You said you wanted to stop this. Put it down.`,
    `${mins} min in ${name}. Mush is melting. Get out now.`,
  ];
  return { title: `🧠 Mush ${pct}% · ${s.mood}`, body: lines[Math.min(n - 1, lines.length - 1)] };
}

/** How many nudges a live session is owed by `now`, and what the latest one should say. */
export function nudgesDue(live, now, rules = RULES) {
  const hadFree = live.paidFrom > live.start;
  const freeDue = hadFree && now >= live.paidFrom ? 1 : 0;
  const clockStart = hadFree ? Math.max(live.origin, live.paidFrom) : live.origin;
  const paidMin = (now - clockStart) / MIN;
  const normal = paidMin >= rules.nudgeAfterMin ? 1 + Math.floor((paidMin - rules.nudgeAfterMin) / rules.nudgeEveryMin) : 0;
  return { due: freeDue + normal, normal, mins: Math.floor((now - live.origin) / MIN) };
}

/** Has the live session run past a goal (so it should end now)? Returns the stop text, or null. */
export function stopNow(s, live, now) {
  if (live.shortOn && s.short.max !== null && s.short.used >= s.short.max) return stopText(s, live.app, 'short');
  if (now < live.paidFrom) return null; // still in free minutes
  const goal = s.goals.find((g) => g.apps.includes(live.app) && g.used >= g.max);
  return goal ? stopText(s, live.app, 'goal', goal) : null;
}

export function nudgeMessage(s, live, now, { ignoreStop = false } = {}) {
  const stop = ignoreStop ? null : stopNow(s, live, now);
  const { due, normal, mins } = nudgesDue(live, now);
  if (stop) return { due, stop: true, title: '⛔ Goal reached', body: stop };
  if (due === 0) return { due: 0 };
  const msg = normal === 0
    ? { title: `🧠 Free ${pretty(live.app)} time's up`, body: `That was your ${RULES.freeMinPerDay[live.app]} free minutes for today. From now on it costs.` }
    : nudgeText(s, normal, mins);
  return { due, ...msg };
}

async function nudgeTick() {
  if (!push?.count) return;
  const now = clock();
  const s = summarize(events, now);
  const live = s.session;
  if (!live || live.dev === 'android') return; // the Android app nudges on the phone itself
  const msg = nudgeMessage(s, live, now);
  const { due } = msg;
  const key = `${live.app}@${live.origin}`;
  if (msg.stop && !nudged.get(`${key}:stop`)) { // the moment a goal is crossed: one stop push...
    nudged.set(`${key}:stop`, 1);
    const results = await push.send({ title: msg.title, body: msg.body.split('\n')[0] + ' Tap to get out.', url: `./?nudge=${encodeURIComponent(live.app ?? '')}` });
    console.log(`goal stop ${live.app} ->`, results.map((r) => `${r.host} ${r.status}`).join('; '));
    return;
  }
  if (msg.stop) { // ...then the normal nudges carry on, marked as over the goal
    const normal = nudgeMessage(s, live, now, { ignoreStop: true });
    if (normal.due === 0 || (nudged.get(key) ?? 0) >= normal.due) return;
    nudged.set(key, normal.due);
    const results = await push.send({ title: `⛔ Over your goal · ${normal.title}`, body: `${normal.body} Tap to get out.`, url: `./?nudge=${encodeURIComponent(live.app ?? '')}` });
    console.log(`nudge #${normal.due} (over goal) ${live.app} ->`, results.map((r) => `${r.host} ${r.status}`).join('; '));
    return;
  }
  if (due === 0 || (nudged.get(key) ?? 0) >= due) return;
  nudged.set(key, due); // after a restart, this sends the current nudge once rather than every missed one
  if (nudged.size > 50) nudged.delete(nudged.keys().next().value);
  const results = await push.send({ title: msg.title, body: `${msg.body} Tap to get out.`, url: `./?nudge=${encodeURIComponent(live.app ?? '')}` });
  console.log(`nudge #${due} ${live.app} ->`, results.map((r) => `${r.host} ${r.status} ${r.detail}`).join('; '));
}

// ---------- http ----------

const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.webmanifest': 'application/manifest+json',
  '.png': 'image/png',
  '.svg': 'image/svg+xml',
  '.json': 'application/json',
  '.apk': 'application/vnd.android.package-archive',
};

const cleanApp = (v) => (v ?? '').toString().trim().toLowerCase().replace(/[^a-z0-9 ._-]/g, '').slice(0, 40) || null;

function send(res, code, body, type = 'text/plain; charset=utf-8') {
  res.writeHead(code, { 'content-type': type, 'cache-control': 'no-store' });
  res.end(body);
}

function readJson(req, limit = 8192) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > limit) { reject(new Error('too large')); req.destroy(); } else chunks.push(c);
    });
    req.on('end', () => { try { resolve(JSON.parse(Buffer.concat(chunks).toString('utf8'))); } catch (e) { reject(e); } });
    req.on('error', reject);
  });
}

const PAGES = { '/': 'index.html', '/setup': 'setup.html', '/android': 'android.html' };

function serveStatic(req, res, pathname) {
  let base = PUBLIC;
  let rel = PAGES[pathname] ?? pathname.slice(1);
  if (pathname.startsWith('/android/')) { base = ANDROID_DIR; rel = pathname.slice('/android/'.length); }
  const file = path.resolve(base, rel);
  if (!file.startsWith(base + path.sep)) return send(res, 404, 'not found');
  fs.readFile(file, (err, buf) => {
    if (err) return send(res, 404, 'not found');
    res.writeHead(200, {
      'content-type': TYPES[path.extname(file)] ?? 'application/octet-stream',
      'cache-control': 'no-cache',
    });
    res.end(buf);
  });
}

function handle(req, res) {
  const url = new URL(req.url, 'http://x');
  const { pathname } = url;
  const app = cleanApp(url.searchParams.get('app'));
  const dev = url.searchParams.get('dev') === 'android' ? 'android' : undefined;
  const now = clock();

  if (!pathname.startsWith('/api/')) {
    if (req.method !== 'GET' && req.method !== 'HEAD') return send(res, 405, 'method not allowed');
    return serveStatic(req, res, pathname);
  }
  if (req.method !== 'GET' && req.method !== 'POST') return send(res, 405, 'method not allowed');
  // One line per API call in the journal (journalctl --user -u mush), to debug the phone's Shortcuts.
  if (pathname !== '/api/state') console.log(`${pathname} app=${app ?? '(none)'} ua=${req.headers['user-agent'] ?? '-'}`);

  switch (pathname) {
    case '/api/state':
      return send(res, 200, JSON.stringify({ ...summarize(events, now), opensByApp: undefined }), 'application/json');

    case '/api/open': {
      const short = url.searchParams.get('short') === '1';
      record('open', app, now, dev);
      if (short) record('short', app, now, dev);
      const d = decide(summarize(events, now), app, short);
      // The Android app asks for JSON (it can hard-stop); the iPhone Shortcut gets text: 'true' to let
      // you in (a bare "If Contents of URL" reads it as true on iOS 26), else the prompt.
      if (url.searchParams.get('fmt') === 'json') return send(res, 200, JSON.stringify(d), 'application/json');
      return send(res, 200, d.allow ? 'true' : d.text);
    }

    case '/api/short': {
      // Short-form video started inside the session (the Android app sees it): may stop at once.
      record('short', app, now, dev);
      const s = summarize(events, now);
      const live = s.session?.app === app ? s.session : null;
      const stop = live && s.short.max !== null && s.short.used >= s.short.max;
      return send(res, 200, JSON.stringify(stop ? { stop: true, text: stopText(s, app, 'short') } : { stop: false }), 'application/json');
    }

    case '/api/unshort':
      record('unshort', app, now, dev);
      return send(res, 200, 'ok');

    case '/api/still':
      // The Android app is sure you're still in it; keeps a long session from hitting the 45-min cap.
      record('still', app, now, dev);
      return send(res, 200, 'ok');

    case '/api/close':
      record('close', app, now);
      return send(res, 200, 'ok');

    case '/api/rot':
      record('rot', app, now);
      return send(res, 200, 'ok');

    case '/api/resist': {
      record('resist', app, now);
      const s = summarize(events, now);
      return send(res, 200, `Good call. Mush is at ${Math.round(s.health)}%.`);
    }

    case '/api/push/key':
      return send(res, 200, push.publicKey);

    case '/api/push/subscribe':
      if (req.method !== 'POST') return send(res, 405, 'method not allowed');
      readJson(req).then((sub) => {
        if (!validSubscription(sub)) return send(res, 400, 'bad subscription');
        push.subscribe(sub);
        console.log(`push: subscribed ${new URL(sub.endpoint).hostname} (${push.count} total)`);
        send(res, 200, 'ok');
      }, () => send(res, 400, 'bad request'))
        .catch((e) => { console.error('subscribe failed', e); send(res, 500, 'error'); });
      return;

    case '/api/push/test': {
      if (req.method !== 'POST') return send(res, 405, 'method not allowed');
      const s = summarize(events, now);
      push.send({ title: `🧠 Mush ${Math.round(s.health)}% · ${s.mood}`, body: 'This is what a nudge looks like. Tap it to come here.', url: './?nudge=' })
        .then((results) => {
          console.log('push test ->', results.map((r) => `${r.host} ${r.status} ${r.detail}`).join('; '));
          send(res, 200, JSON.stringify(results), 'application/json');
        })
        .catch((e) => { console.error('push test failed', e); send(res, 500, 'error'); });
      return;
    }

    case '/api/nudge': {
      // The Android app polls this during a session and shows the brain when `due` goes up.
      const s = summarize(events, now);
      const live = s.session;
      if (!live || live.app !== app) return send(res, 200, JSON.stringify({ due: 0 }), 'application/json');
      return send(res, 200, JSON.stringify(nudgeMessage(s, live, now)), 'application/json');
    }

    case '/api/rules':
      // Detection rules for the Android app; edit rules/android.json, no rebuild needed.
      return fs.readFile(ANDROID_RULES, (err, buf) => (err ? send(res, 500, 'no rules') : send(res, 200, buf, 'application/json')));

    case '/api/android/ignored': {
      // "This isn't video" on the phone; logged so a wrong guess can be fixed in rules/android.json.
      const pkg = String(url.searchParams.get('pkg') ?? '').replace(/[^a-zA-Z0-9._]/g, '').slice(0, 80);
      console.log(`android: marked not-video ${pkg}`);
      return send(res, 200, 'ok');
    }

    case '/api/android/dump': {
      if (req.method !== 'POST') return send(res, 405, 'method not allowed');
      readJson(req, 512 * 1024).then((dump) => {
        const pkg = String(dump.pkg ?? 'unknown').replace(/[^a-zA-Z0-9._]/g, '').slice(0, 80) || 'unknown';
        fs.mkdirSync(DUMP_DIR, { recursive: true });
        const old = fs.readdirSync(DUMP_DIR).sort();
        for (const f of old.slice(0, Math.max(0, old.length - 199))) fs.rmSync(path.join(DUMP_DIR, f)); // keep the last 200
        fs.writeFileSync(path.join(DUMP_DIR, `${now}-${pkg}.json`), JSON.stringify(dump));
        console.log(dump.seen ? `android seen ${pkg}` : `android dump ${pkg} (${Array.isArray(dump.nodes) ? dump.nodes.length : 0} nodes)`);
        send(res, 200, 'ok');
      }, () => send(res, 400, 'bad request'))
        .catch((e) => { console.error('dump failed', e); send(res, 500, 'error'); });
      return;
    }

    default:
      return send(res, 404, 'not found');
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  push = new Push(PUSH_FILE);
  setInterval(() => nudgeTick().catch((e) => console.error('nudge failed', e)), 20_000);
  http.createServer((req, res) => {
    try { handle(req, res); } catch (e) { console.error(e); send(res, 500, 'error'); }
  }).listen(PORT, HOST, () => console.log(`mush on http://${HOST}:${PORT} (data ${DATA})`));
}
