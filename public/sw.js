const CACHE='english-ai-tutor-v8';
const ASSETS=['/','/index.html','/styles.css','/app.js','/source-library.js','/media-learning.js','/manifest.webmanifest'];
const API_RETRY_DELAYS=[0,1500,3000,5000,8000,12000];

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

function sleep(ms){
  return new Promise(resolve => setTimeout(resolve, ms));
}

async function fetchApiWithRetry(request){
  let lastError = null;
  let lastResponse = null;

  for (let i = 0; i < API_RETRY_DELAYS.length; i += 1) {
    const delay = API_RETRY_DELAYS[i];
    if (delay) await sleep(delay);

    try {
      const response = await fetch(request.clone());
      if (![502,503,504].includes(response.status)) return response;
      lastResponse = response;
      lastError = null;
    } catch (err) {
      lastError = err;
    }
  }

  if (lastResponse) return lastResponse;
  throw lastError || new TypeError('Network request failed');
}

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
    // Open the app immediately from cache, while this network request quietly wakes Render.
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
  const url = new URL(event.request.url);
  if (url.origin !== self.location.origin) return;

  // Cached startup can finish before a free Render instance wakes up.
  // Retry same-origin API requests instead of surfacing a transient "Failed to fetch".
  if (url.pathname.startsWith('/api/')) {
    event.respondWith(fetchApiWithRetry(event.request));
    return;
  }

  if (event.request.method !== 'GET') return;

  const isNavigation = event.request.mode === 'navigate';
  const isStaticAsset = /\.(?:js|css|html|webmanifest)$/.test(url.pathname);

  if (isNavigation || isStaticAsset) {
    event.respondWith(cachedShell(event.request, url));
    return;
  }

  event.respondWith(fetch(event.request).catch(() => caches.match(event.request)));
});
