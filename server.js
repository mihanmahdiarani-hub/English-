const http = require('http');
const fs = require('fs');
const path = require('path');
const { WebSocketServer, WebSocket } = require('ws');

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
      'Cache-Control': ext === '.html' || ext === '.js' ? 'no-store' : 'public, max-age=3600'
    });
    res.end(data);
  });
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  if (req.method === 'GET' && url.pathname === '/health') {
    return sendJson(res, 200, {
      ok: true,
      model: MODEL,
      apiKeyConfigured: Boolean(GEMINI_API_KEY),
      transport: 'server-websocket-proxy'
    });
  }
  if (req.method === 'GET') return serveStatic(req, res);
  res.writeHead(405); res.end('Method Not Allowed');
});

const wss = new WebSocketServer({ noServer: true });

server.on('upgrade', (req, socket, head) => {
  let pathname = '/';
  try { pathname = new URL(req.url, `http://${req.headers.host || 'localhost'}`).pathname; } catch {}
  if (pathname !== '/live') {
    socket.destroy();
    return;
  }
  wss.handleUpgrade(req, socket, head, client => wss.emit('connection', client, req));
});

wss.on('connection', (client) => {
  console.log('[live-proxy] browser connected');

  if (!GEMINI_API_KEY) {
    client.close(1011, 'Gemini API key is not configured');
    return;
  }

  const upstreamUrl = `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=${encodeURIComponent(GEMINI_API_KEY)}`;
  const upstream = new WebSocket(upstreamUrl);
  const pending = [];
  let upstreamReady = false;

  upstream.on('open', () => {
    upstreamReady = true;
    console.log(`[live-proxy] Gemini connected (${MODEL})`);
    while (pending.length && upstream.readyState === WebSocket.OPEN) upstream.send(pending.shift());
  });

  client.on('message', (data) => {
    if (upstreamReady && upstream.readyState === WebSocket.OPEN) upstream.send(data);
    else pending.push(Buffer.from(data));
  });

  upstream.on('message', (data, isBinary) => {
    if (client.readyState === WebSocket.OPEN) client.send(data, { binary: isBinary });
  });

  upstream.on('close', (code, reason) => {
    const text = reason?.toString() || '';
    console.log(`[live-proxy] Gemini closed code=${code} reason=${text}`);
    if (client.readyState === WebSocket.OPEN || client.readyState === WebSocket.CONNECTING) {
      const safeCode = code >= 1000 && code <= 4999 ? code : 1011;
      try { client.close(safeCode, text.slice(0, 120)); } catch { client.terminate(); }
    }
  });

  upstream.on('error', (err) => {
    console.error(`[live-proxy] Gemini error: ${err.message}`);
    if (client.readyState === WebSocket.OPEN || client.readyState === WebSocket.CONNECTING) {
      try { client.close(1011, 'Gemini upstream connection failed'); } catch { client.terminate(); }
    }
  });

  client.on('close', (code, reason) => {
    console.log(`[live-proxy] browser closed code=${code} reason=${reason?.toString() || ''}`);
    if (upstream.readyState === WebSocket.OPEN || upstream.readyState === WebSocket.CONNECTING) {
      try { upstream.close(1000, 'Browser disconnected'); } catch { upstream.terminate(); }
    }
  });

  client.on('error', (err) => {
    console.error(`[live-proxy] browser error: ${err.message}`);
  });
});

server.listen(PORT, () => {
  console.log(`English AI Tutor running on http://localhost:${PORT}`);
  console.log(`Gemini model: ${MODEL}`);
  console.log(`API key configured: ${Boolean(GEMINI_API_KEY)}`);
  console.log('Live transport: server-side WebSocket proxy');
});
