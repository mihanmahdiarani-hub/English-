const CACHE='english-ai-tutor-v6';
const ASSETS=['/','/index.html','/styles.css','/app.js','/source-library.js','/media-learning.js','/manifest.webmanifest'];

self.addEventListener('install', event => {
  self.skipWaiting();
  event.waitUntil(caches.open(CACHE).then(cache => cache.addAll(ASSETS)));
});

self.addEventListener('activate', event => {
  event.waitUntil(
    caches.keys()
      .then(keys => Promise.all(keys.filter(key => key !== CACHE).map(key => caches.delete(key))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', event => {
  if (event.request.method !== 'GET') return;
  const url = new URL(event.request.url);
  const localAsset = url.origin === self.location.origin && /\.(?:js|css|html|webmanifest)$/.test(url.pathname);
  const networkFirst = event.request.mode === 'navigate' || localAsset;

  if (networkFirst) {
    event.respondWith(
      fetch(event.request)
        .then(response => {
          const copy = response.clone();
          caches.open(CACHE).then(cache => cache.put(event.request, copy));
          return response;
        })
        .catch(() => caches.match(event.request).then(cached => cached || caches.match(url.pathname)))
    );
    return;
  }

  event.respondWith(caches.match(event.request).then(cached => cached || fetch(event.request)));
});
