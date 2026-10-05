const http = require('http');

const originalCreateServer = http.createServer.bind(http);
const API_KEYS = [process.env.GEMINI_API_KEY || '', process.env.GEMINI_API_KEY_2 || ''].filter(Boolean);

function sendJson(res, status, body) {
  if (res.headersSent) return;
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store'
  });
  res.end(JSON.stringify(body));
}

function safeFileName(name) {
  try { name = decodeURIComponent(String(name || 'media')); } catch {}
  return String(name || 'media').replace(/[\r\n\t]/g, ' ').slice(0, 180);
}

async function startGeminiUpload(key, { fileName, mimeType, size }) {
  const r = await fetch('https://generativelanguage.googleapis.com/upload/v1beta/files', {
    method: 'POST',
    headers: {
      'x-goog-api-key': key,
      'X-Goog-Upload-Protocol': 'resumable',
      'X-Goog-Upload-Command': 'start',
      'X-Goog-Upload-Header-Content-Length': String(size),
      'X-Goog-Upload-Header-Content-Type': mimeType,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({ file: { display_name: fileName } })
  });
  const text = await r.text();
  if (!r.ok) throw new Error(`Gemini upload start failed (${r.status}): ${text.slice(0, 400)}`);
  const uploadUrl = r.headers.get('x-goog-upload-url');
  if (!uploadUrl) throw new Error('Gemini did not return an upload URL');
  return uploadUrl;
}

async function handleProxyUpload(req, res) {
  if (!API_KEYS.length) return sendJson(res, 500, { ok: false, error: 'Gemini API key is not configured' });

  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  const requestedSlot = Math.max(1, Math.min(API_KEYS.length, Number(url.searchParams.get('slot') || 1)));
  const key = API_KEYS[requestedSlot - 1];
  const fileName = safeFileName(req.headers['x-file-name']);
  const mimeType = String(req.headers['content-type'] || 'video/mp4').split(';')[0];
  const size = Number(req.headers['x-file-size'] || req.headers['content-length'] || 0);

  if (!size || size < 1) return sendJson(res, 400, { ok: false, error: 'File size is required' });
  if (!/^(video|audio)\//i.test(mimeType)) return sendJson(res, 400, { ok: false, error: 'Only audio/video files are supported' });

  try {
    console.log(`[media-proxy] upload fallback started key#${requestedSlot} file=${JSON.stringify(fileName)} size=${size}`);
    const uploadUrl = await startGeminiUpload(key, { fileName, mimeType, size });
    const r = await fetch(uploadUrl, {
      method: 'POST',
      headers: {
        'Content-Length': String(size),
        'X-Goog-Upload-Offset': '0',
        'X-Goog-Upload-Command': 'upload, finalize'
      },
      body: req,
      duplex: 'half'
    });
    const text = await r.text();
    let data = {};
    try { data = JSON.parse(text); } catch {}
    if (!r.ok) throw new Error(data?.error?.message || `Gemini upload failed (${r.status})`);
    const info = data.file || data;
    console.log(`[media-proxy] upload fallback complete key#${requestedSlot} geminiFile=${JSON.stringify(info?.name || '')}`);
    return sendJson(res, 200, { ok: true, file: info, keySlot: requestedSlot });
  } catch (err) {
    console.error(`[media-proxy] upload fallback failed key#${requestedSlot}: ${err.message}`);
    return sendJson(res, 502, { ok: false, error: err.message });
  }
}

http.createServer = function patchedCreateServer(listener) {
  return originalCreateServer((req, res) => {
    let pathname = '/';
    try { pathname = new URL(req.url, `http://${req.headers.host || 'localhost'}`).pathname; } catch {}
    if (req.method === 'POST' && pathname === '/api/media-upload-proxy') {
      handleProxyUpload(req, res).catch(err => sendJson(res, 500, { ok: false, error: err.message }));
      return;
    }
    listener(req, res);
  });
};

console.log('Media upload proxy fallback preload active');
