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
  let session = null; // { app, start, origin, paidFrom, capBase, rot, cont, free, dev }
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
    sessions.push({ app, start, origin, end, resisted, cont, free });
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
        };
        if (!cont && left === 0) h = clamp(h - rules.openCost);
        break;
      }
      case 'still':
        // Only while the session is still within its cap: a late heartbeat must not revive one that ended.
        if (session && session.app === ev.app && ev.t < session.capBase + cap) session.capBase = Math.max(session.capBase, ev.t);
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
    const capped = { app, start, origin, paidFrom, end: Math.min(now, capBase + cap), resisted: false, cont, free, dev };
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

function summarize(evs, now) {
  const r = replay(evs, now);
  const days = new Map();
  const day = (k) => {
    if (!days.has(k)) days.set(k, { date: k, minutes: 0, opens: 0, resisted: 0 });
    return days.get(k);
  };
  for (const s of r.sessions) {
    const d = day(dayKey(s.start));
    if (!s.cont) d.opens += 1;
    d.minutes += (s.end - s.start) / MIN;
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
  const minutesByApp = new Map();
  const opensByApp = new Map();
  for (const s of r.sessions) {
    if (dayKey(s.start) !== today.date) continue;
    minutesByApp.set(s.app, (minutesByApp.get(s.app) ?? 0) + (s.end - s.start) / MIN);
    if (!s.cont) opensByApp.set(s.app, (opensByApp.get(s.app) ?? 0) + 1);
  }
  return {
    health: Math.round(r.health * 10) / 10,
    mood: mood(r.health),
    session: r.session,
    today,
    appsToday: Object.fromEntries(minutesByApp),
    opensByApp,
    days: last14,
    recent: r.sessions.slice(-12).reverse(),
    rules: RULES,
    now,
  };
}

// ---------- the prompt text the Shortcut shows ----------

const NAMES = { tiktok: 'TikTok', youtube: 'YouTube', x: 'X', instagram: 'Instagram', reddit: 'Reddit', snapchat: 'Snapchat', facebook: 'Facebook', threads: 'Threads' };
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

function nudgeMessage(s, live, now) {
  const { due, normal, mins } = nudgesDue(live, now);
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
      record('open', app, now, dev);
      const s = summarize(events, now);
      // Grace or free minutes: 'true' reads as true to a bare "If Contents of URL" (iOS 26) and matches "is true" on older iOS.
      if (s.session?.cont || s.session?.free) return send(res, 200, 'true');
      return send(res, 200, gateText(s, app));
    }

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
