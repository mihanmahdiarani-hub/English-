const http = require('http');
const fs = require('fs');
const path = require('path');
const { WebSocketServer, WebSocket } = require('ws');

const PORT = Number(process.env.PORT || 3000);
const PUBLIC_DIR = path.join(__dirname, 'public');
const MODEL = process.env.GEMINI_LIVE_MODEL || 'gemini-3.8-live';
const ANALYSIS_MODEL = process.env.GEMINI_ANALYSIS_MODEL || 'gemini-3.8-flash';
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

function readJsonBody(req, maxBytes = 300000) {
  return new Promise((resolve, reject) => {
    let body = '';
    let bytes = 0;
    req.on('data', chunk => {
      bytes += chunk.length;
      if (bytes > maxBytes) {
        reject(new Error('Request body too large'));
        req.destroy();
        return;
      }
      body += chunk.toString('utf8');
    });
    req.on('end', () => {
      try { resolve(body ? JSON.parse(body) : {}); }
      catch { reject(new Error('Invalid JSON body')); }
    });
    req.on('error', reject);
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

function normalizeMethodProfile(raw) {
  const p = raw && typeof raw === 'object' ? raw : {};
  const features = Array.isArray(p.features) ? p.features.slice(0, 12).map((f, i) => ({
    id: String(f?.id || `feature_${i + 1}`).slice(0, 60),
    kind: String(f?.kind || 'other').slice(0, 40),
    titleFa: String(f?.titleFa || f?.title || `مرحله ${i + 1}`).slice(0, 160),
    behavior: String(f?.behavior || '').slice(0, 700),
    passCondition: String(f?.passCondition || '').slice(0, 500),
    sourceBasis: String(f?.sourceBasis || '').slice(0, 500),
    explicit: f?.explicit !== false,
    enabled: f?.enabled !== false
  })) : [];

  const rules = Array.isArray(p.rules) ? p.rules.slice(0, 16).map((r, i) => ({
    order: Number(r?.order || i + 1),
    titleFa: String(r?.titleFa || r?.title || `قاعده ${i + 1}`).slice(0, 160),
    action: String(r?.action || '').slice(0, 700),
    gate: String(r?.gate || '').slice(0, 500),
    nextWhen: String(r?.nextWhen || '').slice(0, 500),
    sourceBasis: String(r?.sourceBasis || '').slice(0, 500),
    explicit: r?.explicit !== false
  })) : [];

  return {
    found: Boolean(p.found),
    confidence: Math.max(0, Math.min(1, Number(p.confidence || 0))),
    methodSummaryFa: String(p.methodSummaryFa || '').slice(0, 1600),
    rules,
    features,
    runtimeProtocol: String(p.runtimeProtocol || '').slice(0, 3000),
    warnings: Array.isArray(p.warnings) ? p.warnings.slice(0, 8).map(x => String(x).slice(0, 500)) : []
  };
}

async function analyzeBookMethodWithKey(apiKey, introText, bookName, keyIndex) {
  const systemInstruction = `You analyze the front matter of an English-learning textbook to identify the AUTHOR'S OR PUBLISHER'S EXPLICIT TEACHING/USAGE METHOD and translate it into app behavior.

Rules:
1. Use ONLY instructions actually supported by the supplied front-matter text. Do not invent a pedagogy because it seems educationally reasonable.
2. Separate explicit author instructions from inference. Product features should normally be created only from explicit instructions.
3. The app is a live AI tutor. Convert explicit pedagogy into executable teaching features such as read-first, listen-N-times, repeat/shadow, memorize-before-unlock, comprehension check, exercise gate, review cycle, speaking test, writing step, pronunciation checkpoint, or another clearly justified behavior.
4. Preserve the AUTHOR'S ORDER. If the text says "read until memorized, then do exercise 1", create a reading/memorization stage and a gate that blocks exercise 1 until the learner demonstrates recall.
5. Do not quote long passages. sourceBasis must be a short paraphrase of the supporting instruction, not a long quotation.
6. If no explicit teaching method is found, set found=false, features=[], rules=[], and explain that in methodSummaryFa.
7. Respond only as JSON.

JSON shape:
{
  "found": true,
  "confidence": 0.0,
  "methodSummaryFa": "خلاصه فارسی روش مؤلف",
  "rules": [
    {"order":1,"titleFa":"...","action":"...","gate":"...","nextWhen":"...","sourceBasis":"...","explicit":true}
  ],
  "features": [
    {"id":"short_id","kind":"read|listen|repeat|memorize_gate|exercise|review|test|write|speak|other","titleFa":"...","behavior":"رفتار دقیق اپ","passCondition":"شرط عبور به مرحله بعد","sourceBasis":"مبنای کوتاه از مقدمه","explicit":true,"enabled":true}
  ],
  "runtimeProtocol":"A concise English instruction block that a live tutor can follow step-by-step, enforcing gates and not skipping ahead.",
  "warnings":["..." ]
}`;

  const prompt = `BOOK NAME: ${bookName || 'Unknown'}\n\nFRONT-MATTER / INTRODUCTORY PAGES:\n${introText}`;
  const url = `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(ANALYSIS_MODEL)}:generateContent`;
  const response = await fetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'x-goog-api-key': apiKey
    },
    body: JSON.stringify({
      systemInstruction: { parts: [{ text: systemInstruction }] },
      contents: [{ role: 'user', parts: [{ text: prompt }] }],
      generationConfig: {
        temperature: 0.1,
        responseMimeType: 'application/json'
      }
    })
  });

  const rawText = await response.text();
  if (!response.ok) {
    throw new Error(`HTTP ${response.status}: ${rawText.slice(0, 300)}`);
  }

  let envelope;
  try { envelope = JSON.parse(rawText); }
  catch { throw new Error('Gemini returned a non-JSON API envelope'); }

  const modelText = envelope?.candidates?.[0]?.content?.parts?.map(p => p.text || '').join('') || '';
  if (!modelText) throw new Error('Gemini returned no analysis text');

  let parsed;
  try { parsed = JSON.parse(modelText); }
  catch {
    const cleaned = modelText.replace(/^```(?:json)?\s*/i, '').replace(/\s*```$/i, '');
    parsed = JSON.parse(cleaned);
  }
  const profile = normalizeMethodProfile(parsed);
  console.log(`[method-analysis] key#${keyIndex + 1} success found=${profile.found} confidence=${profile.confidence} features=${profile.features.length}`);
  return profile;
}

async function analyzeBookMethod(introText, bookName) {
  if (!API_KEYS.length) throw new Error('Gemini API key is not configured');
  let lastError = null;
  for (let i = 0; i < API_KEYS.length; i++) {
    try {
      return await analyzeBookMethodWithKey(API_KEYS[i], introText, bookName, i);
    } catch (err) {
      lastError = err;
      console.error(`[method-analysis] key#${i + 1} failed: ${err.message}`);
    }
  }
  throw lastError || new Error('Book-method analysis failed');
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

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);

  if (req.method === 'GET' && url.pathname === '/health') {
    return sendJson(res, 200, {
      ok: true,
      model: MODEL,
      analysisModel: ANALYSIS_MODEL,
      apiKeysConfigured: API_KEYS.length,
      primaryConfigured: Boolean(process.env.GEMINI_API_KEY),
      backupConfigured: Boolean(process.env.GEMINI_API_KEY_2),
      transport: 'server-websocket-proxy',
      failover: API_KEYS.length > 1,
      diagnostics: 'v4'
    });
  }

  if (req.method === 'POST' && url.pathname === '/api/analyze-book-method') {
    try {
      const body = await readJsonBody(req);
      const introText = String(body.introText || '').trim().slice(0, 180000);
      const bookName = String(body.bookName || '').trim().slice(0, 300);
      if (introText.length < 80) return sendJson(res, 400, { error: 'Not enough introductory text to analyze.' });
      const profile = await analyzeBookMethod(introText, bookName);
      return sendJson(res, 200, { ok: true, model: ANALYSIS_MODEL, profile });
    } catch (err) {
      console.error(`[method-analysis] request failed: ${err.message}`);
      return sendJson(res, 502, { error: `Book-method analysis failed: ${err.message}` });
    }
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
        // Setup is sent by connectUpstream when the upstream opens.
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
  console.log(`Gemini Live model: ${MODEL}`);
  console.log(`Gemini analysis model: ${ANALYSIS_MODEL}`);
  console.log(`Gemini API keys configured: ${API_KEYS.length}`);
  console.log(`Backup failover: ${API_KEYS.length > 1}`);
  console.log('Live transport: server-side WebSocket proxy diagnostics-v4');

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
