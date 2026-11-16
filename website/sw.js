// Bautrupp als App: hält die Seite und die Schriften bereit, damit sie auch ohne Netz startet.
// Die Seite selbst wird immer zuerst frisch geladen (so kommen Updates sofort an);
// nur wenn das nicht geht, kommt die gespeicherte Version. Die Datenbank läuft nie über den Cache.
const CACHE = "bautrupp-v1";
const SHELL = ["./", "./manifest.webmanifest", "./icon-192.png", "./icon-512.png", "./icon-maskable-512.png", "./apple-touch-icon.png", "./favicon.png"];
const FONTS = ["fonts.googleapis.com", "fonts.gstatic.com"];

self.addEventListener("install", e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener("activate", e => {
  e.waitUntil(caches.keys()
    .then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k))))
    .then(() => self.clients.claim()));
});

self.addEventListener("fetch", e => {
  const req = e.request, url = new URL(req.url);
  if (req.method !== "GET") return;
  const own = url.origin === self.location.origin, font = FONTS.includes(url.hostname);
  if (!own && !font) return; // Firebase, Discord, GitHub: immer direkt
  if (req.mode === "navigate") {
    // Seite: erst Netz, sonst gespeicherte Version (Gruppen-Link mit ?db=... findet sie auch)
    e.respondWith(fetch(req).then(res => {
      if (res.ok) caches.open(CACHE).then(c => c.put("./", res.clone()));
      return res;
    }).catch(() => caches.match("./")));
    return;
  }
  // Symbole und Schriften: aus dem Speicher, im Hintergrund auffrischen
  e.respondWith(caches.match(req).then(hit => {
    const net = fetch(req).then(res => {
      if (res.ok || res.type === "opaque") caches.open(CACHE).then(c => c.put(req, res.clone()));
      return res;
    }).catch(() => hit);
    return hit || net;
  }));
});
