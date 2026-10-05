const CACHE='english-ai-tutor-v7';
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

async function cachedShell(request, url) {
  const cache = await caches.open(CACHE);
  const cached = await cache.match(request) || await cache.match(url.pathname) || await cache.match('/index.html');

  const refresh = fetch(request)
    .then(response => {
      if (response && response.ok) cache.put(request, response.clone());
      return response;
    })
    .catch(() => null);

  if (cached) {
    // Do not make app startup wait for the free Render instance to wake up.
    // Refresh the cached shell quietly in the background for the next launch.
    refresh.catch(() => {});
    return cached;
  }

  const network = await refresh;
  if (network) return network;
  return new Response('App shell is not available offline yet.', {
    status: 503,
    headers: { 'Content-Type': 'text/plain; charset=utf-8' }
  });
}

self.addEventListener('fetch', event => {
  if (event.request.method !== 'GET') return;
  const url = new URL(event.request.url);
  if (url.origin !== self.location.origin) return;

  const isNavigation = event.request.mode === 'navigate';
  const isStaticAsset = /\.(?:js|css|html|webmanifest)$/.test(url.pathname);

  if (isNavigation || isStaticAsset) {
    event.respondWith(cachedShell(event.request, url));
    return;
  }

  // API, media and other GET requests keep their normal network behavior.
  event.respondWith(fetch(event.request).catch(() => caches.match(event.request)));
});
