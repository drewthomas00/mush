# Mush

A brain that rots while you doomscroll.

Mush gives you one brain with a health score. Every minute in a feed or a short-form video
drains it, every hour away heals it, and the brain on your screen shows it: pink and sparkly
when you're sharp, droopy and drippy as it goes, a puddle with flies when it's gone. Timers are
easy to swipe away; watching your own brain melt is harder.

- **Gated apps.** Facebook, Instagram, TikTok, YouTube, Reddit, X, Snapchat and LinkedIn get
  *Back away / Rot anyway* the moment they open.
- **Short-form video anywhere.** Any upright video filling the screen (Reels, Shorts, TikTok,
  Stories) gets the same prompt, in any app. Inside a gated app you've let yourself into,
  starting Reels brings the brain back.
- **Nudges.** Stay, and the brain covers the screen again at 5 minutes and every 3 after that,
  and from the first nudge every minute costs double.
- **Free minutes.** Give an app some minutes a day with no prompt, no cost and no nudges
  (handy for a daily puzzle).
- **Goals.** A daily budget shared by the apps you pick (say, 30 minutes of Facebook and
  LinkedIn together), and optionally no short-form video at all. When a goal is used up, the
  prompt only offers *Back away*.
- **Pace.** The brain page and the widget show today's scrolling against your 7-day average up
  to the same time of day: ahead of pace or behind it.
- **No account, no ads, no tracking.** On its own, everything stays on your phone.

## Android

Install from [GitHub releases](https://github.com/drewthomas00/mush/releases) (F-Droid listing
pending). Android 13 or newer.

1. Install the APK. Android asks to allow installs from your browser or file manager.
2. Android blocks Accessibility for downloaded apps until you allow it: **Settings → Apps →
   Mush → ⋮ → Allow restricted settings**.
3. Open Mush → **Setup** → **Turn on Mush**, then pick **Mush** in Accessibility and switch it on.
4. **Test the brain** in Setup. Add the **Mush** widget to your home screen if you like.

Mush uses Android's Accessibility service only to see which app is open and whether an upright
video fills the screen (the names and sizes of the screen's views). It never reads what you type
and nothing about you or your usage leaves the phone unless you connect your own server (the
only other network use is fetching the detection rules below). Cameras, video calls,
maps and games are skipped; if it gets an app wrong, tap **This isn't video** (undo in Setup).

What counts as short-form lives in [`rules/android.json`](rules/android.json). The app fetches
the latest copy from this repo's `rules` branch, so when an app changes its screens a fix doesn't
need a new release. Pull requests welcome (against `main`; rule fixes are then copied to `rules`).

## One brain across devices (optional server)

Run the Mush server and every device you connect shares one brain: the Android app (Setup →
**Mush server**) and an iPhone, through a Home Screen web app and Shortcuts automations (the
server's `/setup` page walks through them). The server is one dependency-free Node file.

```sh
node server.mjs            # listens on 127.0.0.1:8473; data in ./data
```

Put it behind HTTPS that only your devices can reach, for example on a Tailscale tailnet:
`tailscale serve --bg --https=8461 http://127.0.0.1:8473`. A systemd user unit is in
`deploy/mush.service`. Free minutes a day per app: `MUSH_FREE_MINUTES="linkedin=10,reddit=5"`.
Goals: `MUSH_GOALS="facebook+linkedin=30,short=0"` (a shared budget of paid minutes, and short-form
minutes allowed a day; 0 means none). Apps that are short-form throughout: `MUSH_SHORT_APPS=tiktok`.
Tuning (drain, healing, nudges) is `RULES` at the top of `server.mjs`; the phone's own brain
(`android/.../Brain.kt`) uses the same model.

## Layout

- `server.mjs`: the server; events in `data/events.jsonl`, health replayed from them.
  `push.mjs`: Web Push for the iPhone web app (RFC 8291, VAPID), no dependencies.
- `public/`: the brain page, setup pages, the overlay page; also bundled into the Android app.
- `android/`: the Kotlin app. `Brain.kt` is the on-phone brain, `MushService.kt` the watcher,
  `Widget.kt` the home-screen widget. No Google libraries.
- `rules/android.json`: what the Android app reacts to.
- `tools/check.mjs` and `android/app/src/test/`: checks for the health model.
  `tools/fetch-apk.sh`: serve a CI-built APK from your server at `/android/`.

## Building

CI builds and tests everything (`.github/workflows/`). Locally:

```sh
node tools/check.mjs
gradle -p android testDebugUnitTest assembleDebug   # JDK 17, Gradle 8.11, Android SDK 35
```

Release builds are signed in CI from repository secrets (`MUSH_KEYSTORE_B64`,
`MUSH_KEYSTORE_PASSWORD`); pushing a `vX.Y.Z` tag publishes a GitHub release with the APK.

## Support

Mush is free. If it gives you your time back, you can chip in on [Venmo](https://venmo.com/u/Andrew-Thomas-125) (US) or from
Setup → **Support Mush** in the app.

## License

[GPL-3.0-or-later](LICENSE).
