const http = require('http');
const fs = require('fs');
const path = require('path');

const PORT = Number(process.env.PORT || 3000);
const PUBLIC_DIR = path.join(__dirname, 'public');
const GEMINI_API_KEY = process.env.GEMINI_API_KEY || '';
const MODEL = process.env.GEMINI_LIVE_MODEL || 'gemini-3.8-live';

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.webmanifest': 'application/manifest+json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon'
};

function sendJson(res, status, body) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
  res.end(JSON.stringify(body));
}

async function requestAuthToken(payload) {
  const r = await fetch('https://generativelanguage.googleapis.com/v1beta/auth_tokens', {
    method: 'POST',
    headers: {
      'x-goog-api-key': GEMINI_API_KEY,
      'content-type': 'application/json'
    },
    body: JSON.stringify(payload)
  });

  const text = await r.text();
  let data;
  try { data = JSON.parse(text); } catch { data = { raw: text }; }
  return { r, data };
}

async function createEphemeralToken() {
  if (!GEMINI_API_KEY) {
    const err = new Error('GEMINI_API_KEY is not configured on the server.');
    err.code = 'NO_API_KEY';
    throw err;
  }

  const expireTime = new Date(Date.now() + 30 * 60 * 1000).toISOString();
  const newSessionExpireTime = new Date(Date.now() + 60 * 1000).toISOString();

  // Prefer a constrained token. Some Gemini projects currently reject
  // liveConnectConstraints even though the public v1beta docs expose it,
  // so we gracefully fall back to a short-lived unconstrained token.
  let tokenMode = 'constrained';
  let { r, data } = await requestAuthToken({
    uses: 1,
    expireTime,
    newSessionExpireTime,
    liveConnectConstraints: {
      model: `models/${MODEL}`,
      config: {
        responseModalities: ['AUDIO']
      }
    }
  });

  if (!r.ok && r.status === 400 && /liveConnectConstraints/i.test(data?.error?.message || '')) {
    console.warn('[live-token] project rejected liveConnectConstraints; retrying with short-lived token');
    tokenMode = 'short-lived';
    ({ r, data } = await requestAuthToken({
      uses: 1,
      expireTime,
      newSessionExpireTime
    }));
  }

  if (!r.ok) {
    const err = new Error(data?.error?.message || `Gemini token request failed (${r.status})`);
    err.status = r.status;
    err.details = data;
    throw err;
  }

  return { token: data.name, model: MODEL, expiresAt: expireTime, tokenMode };
}

function serveStatic(req, res) {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  let pathname = decodeURIComponent(url.pathname);
  if (pathname === '/') pathname = '/index.html';
  const filePath = path.normalize(path.join(PUBLIC_DIR, pathname));
  if (!filePath.startsWith(PUBLIC_DIR)) {
    res.writeHead(403); res.end('Forbidden'); return;
  }
  fs.readFile(filePath, (err, data) => {
    if (err) {
      res.writeHead(err.code === 'ENOENT' ? 404 : 500, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end(err.code === 'ENOENT' ? 'Not found' : 'Server error');
      return;
    }
    const ext = path.extname(filePath);
    res.writeHead(200, {
      'Content-Type': MIME[ext] || 'application/octet-stream',
      'Cache-Control': ext === '.html' ? 'no-store' : 'public, max-age=3600'
    });
    res.end(data);
  });
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);

  if (req.method === 'GET' && url.pathname === '/health') {
    return sendJson(res, 200, { ok: true, model: MODEL, apiKeyConfigured: Boolean(GEMINI_API_KEY) });
  }

  if (req.method === 'POST' && url.pathname === '/api/live-token') {
    try {
      const token = await createEphemeralToken();
      console.log(`[live-token] issued for model ${MODEL} (${token.tokenMode})`);
      return sendJson(res, 200, token);
    } catch (err) {
      console.error(`[live-token] failed: ${err.status || err.code || 'ERR'} ${err.message}`);
      return sendJson(res, err.code === 'NO_API_KEY' ? 400 : (err.status || 500), {
        ok: false,
        error: err.message,
        details: err.details || undefined
      });
    }
  }

  if (req.method === 'GET') return serveStatic(req, res);
  res.writeHead(405); res.end('Method Not Allowed');
});

server.listen(PORT, () => {
  console.log(`English AI Tutor running on http://localhost:${PORT}`);
  console.log(`Gemini model: ${MODEL}`);
  console.log(`API key configured: ${Boolean(GEMINI_API_KEY)}`);
});
