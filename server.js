const http = require('http');
const fs = require('fs');
const path = require('path');
const { WebSocketServer, WebSocket } = require('ws');

const PORT = Number(process.env.PORT || 3000);
const PUBLIC_DIR = path.join(__dirname, 'public');
const MODEL = process.env.GEMINI_LIVE_MODEL || 'gemini-3.8-live';
const API_KEYS = [
  process.env.GEMINI_API_KEY || '',
  process.env.GEMINI_API_KEY_2 || ''
].filter(Boolean);

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

function geminiWsUrl(apiKey) {
  return `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=${encodeURIComponent(apiKey)}`;
}

function summarizeJsonFrame(text) {
  try {
    const obj = JSON.parse(text);
    const keys = Object.keys(obj);
    if (obj.setup) return `keys=${keys.join(',')} setupKeys=${Object.keys(obj.setup).join(',')}`;
    if (obj.setupComplete) return 'keys=setupComplete';
    if (obj.serverContent) return `keys=serverContent serverContentKeys=${Object.keys(obj.serverContent).join(',')}`;
    if (obj.clientContent) return 'keys=clientContent';
    if (obj.realtimeInput) return 'keys=realtimeInput';
    return `keys=${keys.join(',')}`;
  } catch {
    return 'non-json';
  }
}

function probeGeminiLiveDirect(apiKey, label) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(geminiWsUrl(apiKey));
    const timer = setTimeout(() => {
      try { ws.terminate(); } catch {}
      reject(new Error(`${label} timed out`));
    }, 12000);

    ws.on('open', () => {
      ws.send(JSON.stringify({
        setup: {
          model: `models/${MODEL}`,
          generationConfig: { responseModalities: ['AUDIO'] },
          systemInstruction: { parts: [{ text: 'Reply briefly.' }] },
          inputAudioTranscription: {},
          outputAudioTranscription: {}
        }
      }));
    });

    ws.on('message', (data) => {
      let msg;
      try { msg = JSON.parse(data.toString()); } catch { return; }
      if (msg.setupComplete) {
        clearTimeout(timer);
        try { ws.close(1000, 'probe complete'); } catch {}
        resolve();
      }
    });

    ws.on('error', err => {
      clearTimeout(timer);
      reject(err);
    });

    ws.on('close', (code, reason) => {
      if (code !== 1000) {
        clearTimeout(timer);
        reject(new Error(`${label} closed code=${code} reason=${reason?.toString() || ''}`));
      }
    });
  });
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  if (req.method === 'GET' && url.pathname === '/health') {
    return sendJson(res, 200, {
      ok: true,
      model: MODEL,
      apiKeysConfigured: API_KEYS.length,
      primaryConfigured: Boolean(process.env.GEMINI_API_KEY),
      backupConfigured: Boolean(process.env.GEMINI_API_KEY_2),
      transport: 'server-websocket-proxy',
      failover: API_KEYS.length > 1,
      diagnostics: 'v3'
    });
  }
  if (req.method === 'GET') return serveStatic(req, res);
  res.writeHead(405, { 'Content-Type': 'text/plain; charset=utf-8' });
  res.end('Method Not Allowed');
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

wss.on('connection', (client, req) => {
  const started = Date.now();
  console.log(`[live-proxy] browser connected ua=${JSON.stringify(req.headers['user-agent'] || '')}`);

  if (!API_KEYS.length) {
    client.close(1011, 'Gemini API key is not configured');
    return;
  }

  let upstream = null;
  let upstreamReady = false;
  let setupCompleteSeen = false;
  let setupFrame = '';
  let firstClientFrame = true;
  let firstUpstreamFrame = true;
  let activeKeyIndex = -1;
  const attempted = new Set();
  const pending = [];

  function nextUntriedKey() {
    for (let i = 0; i < API_KEYS.length; i++) {
      if (!attempted.has(i)) return i;
    }
    return -1;
  }

  function connectUpstream(keyIndex, why = 'initial') {
    activeKeyIndex = keyIndex;
    attempted.add(keyIndex);
    upstreamReady = false;
    firstUpstreamFrame = true;

    const ws = new WebSocket(geminiWsUrl(API_KEYS[keyIndex]));
    upstream = ws;
    const label = `key#${keyIndex + 1}`;
    console.log(`[live-proxy] connecting Gemini with ${label} (${why})`);

    ws.on('open', () => {
      if (upstream !== ws) return;
      upstreamReady = true;
      console.log(`[live-proxy] Gemini connected ${label} (${MODEL}) after ${Date.now() - started}ms`);

      if (setupFrame) {
        ws.send(setupFrame);
      } else {
        while (pending.length && ws.readyState === WebSocket.OPEN) ws.send(pending.shift());
      }
    });

    ws.on('message', (data) => {
      if (upstream !== ws) return;
      const text = data.toString();
      const summary = summarizeJsonFrame(text);
      if (firstUpstreamFrame) {
        firstUpstreamFrame = false;
        console.log(`[live-proxy] first Gemini frame ${label} bytes=${Buffer.byteLength(text)} ${summary}`);
      }
      try {
        const msg = JSON.parse(text);
        if (msg.setupComplete && !setupCompleteSeen) {
          setupCompleteSeen = true;
          console.log(`[live-proxy] Gemini setupComplete ${label} after ${Date.now() - started}ms`);
        }
        if (msg.goAway) console.log(`[live-proxy] Gemini goAway ${label} ${JSON.stringify(msg.goAway)}`);
      } catch {}
      if (client.readyState === WebSocket.OPEN) client.send(text);
    });

    ws.on('error', (err) => {
      if (upstream !== ws) return;
      console.error(`[live-proxy] Gemini error ${label} setupComplete=${setupCompleteSeen}: ${err.message}`);
    });

    ws.on('close', (code, reason) => {
      if (upstream !== ws) return;
      const reasonText = reason?.toString() || '';
      console.log(`[live-proxy] Gemini closed ${label} code=${code} setupComplete=${setupCompleteSeen} ageMs=${Date.now() - started} reason=${JSON.stringify(reasonText)}`);

      if (!setupCompleteSeen) {
        const fallbackIndex = nextUntriedKey();
        if (fallbackIndex >= 0 && client.readyState === WebSocket.OPEN) {
          console.warn(`[live-proxy] failover ${label} -> key#${fallbackIndex + 1}`);
          connectUpstream(fallbackIndex, `fallback after code ${code}`);
          return;
        }
      }

      if (client.readyState === WebSocket.OPEN || client.readyState === WebSocket.CONNECTING) {
        const safeCode = code >= 1000 && code <= 4999 ? code : 1011;
        try { client.close(safeCode, reasonText.slice(0, 120)); } catch { client.terminate(); }
      }
    });
  }

  client.on('message', (data) => {
    const text = data.toString();
    if (firstClientFrame) {
      firstClientFrame = false;
      console.log(`[live-proxy] first browser frame bytes=${Buffer.byteLength(text)} ${summarizeJsonFrame(text)}`);
      try {
        const obj = JSON.parse(text);
        if (obj.setup) setupFrame = text;
      } catch {}
    }

    if (setupFrame && !setupCompleteSeen) {
      if (upstreamReady && upstream?.readyState === WebSocket.OPEN && activeKeyIndex >= 0) {
        // The setup frame is sent by connectUpstream when the upstream opens.
      }
      return;
    }

    if (upstreamReady && upstream?.readyState === WebSocket.OPEN) {
      upstream.send(text);
    } else {
      pending.push(text);
    }
  });

  client.on('close', (code, reason) => {
    console.log(`[live-proxy] browser closed code=${code} setupComplete=${setupCompleteSeen} activeKey=${activeKeyIndex + 1} ageMs=${Date.now() - started} reason=${JSON.stringify(reason?.toString() || '')}`);
    if (upstream && (upstream.readyState === WebSocket.OPEN || upstream.readyState === WebSocket.CONNECTING)) {
      try { upstream.close(1000, 'Browser disconnected'); } catch { upstream.terminate(); }
    }
  });

  client.on('error', (err) => {
    console.error(`[live-proxy] browser error setupComplete=${setupCompleteSeen}: ${err.message}`);
  });

  connectUpstream(0);
});

function probeLocalProxy() {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`ws://127.0.0.1:${PORT}/live`);
    const timer = setTimeout(() => {
      try { ws.terminate(); } catch {}
      reject(new Error('Local proxy probe timed out'));
    }, 12000);

    ws.on('open', () => {
      ws.send(JSON.stringify({
        setup: {
          model: `models/${MODEL}`,
          generationConfig: { responseModalities: ['AUDIO'] },
          systemInstruction: { parts: [{ text: 'Reply briefly.' }] },
          inputAudioTranscription: {},
          outputAudioTranscription: {}
        }
      }));
    });

    ws.on('message', data => {
      let msg;
      try { msg = JSON.parse(data.toString()); } catch { return; }
      if (msg.setupComplete) {
        clearTimeout(timer);
        try { ws.close(1000, 'local proxy probe complete'); } catch {}
        resolve();
      }
    });
    ws.on('error', err => { clearTimeout(timer); reject(err); });
    ws.on('close', (code, reason) => {
      if (code !== 1000) {
        clearTimeout(timer);
        reject(new Error(`Local proxy closed code=${code} reason=${reason?.toString() || ''}`));
      }
    });
  });
}

server.listen(PORT, () => {
  console.log(`English AI Tutor running on http://localhost:${PORT}`);
  console.log(`Gemini model: ${MODEL}`);
  console.log(`Gemini API keys configured: ${API_KEYS.length}`);
  console.log(`Backup failover: ${API_KEYS.length > 1}`);
  console.log('Live transport: server-side WebSocket proxy diagnostics-v3');

  API_KEYS.forEach((key, index) => {
    probeGeminiLiveDirect(key, `key#${index + 1}`)
      .then(() => console.log(`[startup-check] Gemini key#${index + 1} direct Live setup OK`))
      .catch(err => console.error(`[startup-check] Gemini key#${index + 1} direct Live FAILED: ${err.message}`));
  });

  if (API_KEYS.length) {
    setTimeout(() => {
      probeLocalProxy()
        .then(() => console.log('[startup-check] Local proxy end-to-end setup OK'))
        .catch(err => console.error(`[startup-check] Local proxy end-to-end FAILED: ${err.message}`));
    }, 800);
  }
});
