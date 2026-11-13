// Mush service worker: shows nudges and opens Mush when one is tapped.
self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (e) => e.waitUntil(self.clients.claim()));

self.addEventListener('push', (e) => {
  let msg = {};
  try { msg = e.data ? e.data.json() : {}; } catch { msg = { body: e.data?.text() }; }
  e.waitUntil(self.registration.showNotification(msg.title || 'Mush', {
    body: msg.body || '',
    icon: 'icon-180.png',
    badge: 'icon-180.png',
    tag: 'mush-nudge',
    renotify: true,
    data: { url: new URL(msg.url || './', self.registration.scope).href },
  }));
});

self.addEventListener('notificationclick', (e) => {
  e.notification.close();
  const url = e.notification.data?.url || self.registration.scope;
  e.waitUntil((async () => {
    const wins = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    for (const w of wins) {
      if ('navigate' in w) { await w.navigate(url).catch(() => {}); return w.focus(); }
    }
    return self.clients.openWindow(url);
  })());
});
