const http = require('http');

const originalCreateServer = http.createServer.bind(http);
const MEDIA_MODEL = process.env.GEMINI_MEDIA_MODEL || 'gemini-3.8-flash';
const API_KEYS = [process.env.GEMINI_API_KEY || '', process.env.GEMINI_API_KEY_2 || ''].filter(Boolean);

function sendJson(res, status, body) {
  if (res.headersSent) return;
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store'
  });
  res.end(JSON.stringify(body));
}

function readJson(req, maxBytes = 1024 * 1024) {
  return new Promise((resolve, reject) => {
    let total = 0;
    const chunks = [];
    req.on('data', chunk => {
      total += chunk.length;
      if (total > maxBytes) {
        reject(new Error('Request body is too large'));
        req.destroy();
        return;
      }
      chunks.push(chunk);
    });
    req.on('end', () => {
      try {
        const text = Buffer.concat(chunks).toString('utf8') || '{}';
        resolve(JSON.parse(text));
      } catch (err) {
        reject(new Error('Invalid JSON body'));
      }
    });
    req.on('error', reject);
  });
}

function safeFileName(name) {
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
    body: JSON.stringify({ file: { display_name: safeFileName(fileName) } })
  });

  if (!r.ok) {
    const text = await r.text();
    throw new Error(`Gemini upload start failed (${r.status}): ${text.slice(0, 500)}`);
  }

  const uploadUrl = r.headers.get('x-goog-upload-url');
  if (!uploadUrl) throw new Error('Gemini did not return an upload URL');
  return uploadUrl;
}

async function getGeminiFile(key, fileName) {
  const clean = String(fileName || '').replace(/^\/+/, '');
  const r = await fetch(`https://generativelanguage.googleapis.com/v1beta/${clean}`, {
    headers: { 'x-goog-api-key': key }
  });
  const text = await r.text();
  let data = {};
  try { data = JSON.parse(text); } catch {}
  if (!r.ok) throw new Error(data?.error?.message || `File status failed (${r.status})`);
  return data;
}

async function waitForActiveFile(key, fileName) {
  const started = Date.now();
  let info = await getGeminiFile(key, fileName);
  while (String(info.state || '').toUpperCase() === 'PROCESSING') {
    if (Date.now() - started > 180000) throw new Error('Video processing timed out after 3 minutes');
    await new Promise(r => setTimeout(r, 2500));
    info = await getGeminiFile(key, fileName);
  }
  if (String(info.state || '').toUpperCase() === 'FAILED') throw new Error('Gemini could not process this media file');
  return info;
}

function extractText(data) {
  const parts = data?.candidates?.[0]?.content?.parts || [];
  return parts.map(p => p?.text || '').join('').trim();
}

function parseJsonText(text) {
  const cleaned = String(text || '')
    .replace(/^```json\s*/i, '')
    .replace(/^```\s*/i, '')
    .replace(/```\s*$/i, '')
    .trim();
  return JSON.parse(cleaned);
}

function mediaPrompt({ fileName, startSec, endSec, language }) {
  const guidanceLanguage = language === 'persian' ? 'Persian' : 'English';
  const range = Number.isFinite(startSec) || Number.isFinite(endSec)
    ? `Analyze only the requested interval ${Number.isFinite(startSec) ? `${startSec}s` : 'from the beginning'} to ${Number.isFinite(endSec) ? `${endSec}s` : 'the end'}.`
    : 'Analyze the media content provided.';

  return `You are preparing a dialogue-based English lesson from a user-provided movie, video clip, or audio clip named "${safeFileName(fileName)}".
${range}

Your job is to extract the spoken English dialogue as accurately as possible and turn it into a practical speaking lesson. Do not invent dialogue that is not audible. If a line is unclear, mark it as [unclear] instead of guessing. Preserve contractions and natural spoken wording. Include timestamps and speaker labels when reasonably inferable.

Return ONLY valid JSON with this exact top-level shape:
{
  "title": "short lesson title",
  "summary": "brief summary in ${guidanceLanguage}",
  "dialogues": [
    {"start":"00:00","end":"00:04","speaker":"Speaker 1","text":"exact spoken line","meaning":"short meaning/explanation in ${guidanceLanguage}"}
  ],
  "phrases": [
    {"phrase":"useful phrase from the dialogue","meaning":"meaning in ${guidanceLanguage}","usage":"very short usage note in ${guidanceLanguage}"}
  ],
  "pronunciation": [
    {"text":"word or phrase from the dialogue","tip":"brief pronunciation / connected-speech tip in ${guidanceLanguage}"}
  ],
  "lessonFlow": [
    "Listen to one line",
    "Understand its meaning in context",
    "Repeat the exact line",
    "Receive pronunciation feedback",
    "Role-play the line in context",
    "Move to the next line"
  ]
}

Keep the transcript focused on dialogue useful for language learning. For a long interval, prioritize the most teachable spoken exchanges while keeping their original order.`;
}

async function analyzeWithKey(key, payload) {
  const fileInfo = await waitForActiveFile(key, payload.fileNameOnGemini);
  const mimeType = payload.mimeType || fileInfo.mimeType || 'video/mp4';
  const fileUri = payload.fileUri || fileInfo.uri;
  if (!fileUri) throw new Error('Gemini file URI is missing');

  const mediaPart = {
    fileData: {
      mimeType,
      fileUri
    }
  };

  if (String(mimeType).startsWith('video/')) {
    const vm = {};
    if (Number.isFinite(payload.startSec) && payload.startSec > 0) vm.startOffset = `${payload.startSec}s`;
    if (Number.isFinite(payload.endSec) && payload.endSec > 0) vm.endOffset = `${payload.endSec}s`;
    if (Object.keys(vm).length) mediaPart.videoMetadata = vm;
  }

  const r = await fetch(`https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(MEDIA_MODEL)}:generateContent`, {
    method: 'POST',
    headers: {
      'x-goog-api-key': key,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({
      contents: [{
        role: 'user',
        parts: [
          mediaPart,
          { text: mediaPrompt(payload) }
        ]
      }],
      generationConfig: {
        temperature: 0.1,
        responseMimeType: 'application/json'
      }
    })
  });

  const text = await r.text();
  let data = {};
  try { data = JSON.parse(text); } catch {}
  if (!r.ok) throw new Error(data?.error?.message || `Gemini media analysis failed (${r.status})`);

  const modelText = extractText(data);
  if (!modelText) throw new Error('Gemini returned no dialogue analysis');
  return parseJsonText(modelText);
}

async function handleUploadStart(req, res) {
  if (!API_KEYS.length) return sendJson(res, 500, { ok: false, error: 'Gemini API key is not configured' });
  const body = await readJson(req, 128 * 1024);
  const fileName = safeFileName(body.fileName);
  const mimeType = String(body.mimeType || 'video/mp4');
  const size = Number(body.size || 0);
  if (!size || size < 1) return sendJson(res, 400, { ok: false, error: 'File size is required' });
  if (!/^(video|audio)\//i.test(mimeType)) return sendJson(res, 400, { ok: false, error: 'Only audio/video files are supported' });

  let lastError = null;
  for (let i = 0; i < API_KEYS.length; i++) {
    try {
      const uploadUrl = await startGeminiUpload(API_KEYS[i], { fileName, mimeType, size });
      console.log(`[media] resumable upload started key#${i + 1} file=${JSON.stringify(fileName)} size=${size} mime=${mimeType}`);
      return sendJson(res, 200, { ok: true, uploadUrl, keySlot: i + 1, model: MEDIA_MODEL });
    } catch (err) {
      lastError = err;
      console.error(`[media] upload start failed key#${i + 1}: ${err.message}`);
    }
  }
  return sendJson(res, 502, { ok: false, error: lastError?.message || 'Could not start media upload' });
}

async function handleAnalyze(req, res) {
  if (!API_KEYS.length) return sendJson(res, 500, { ok: false, error: 'Gemini API key is not configured' });
  const body = await readJson(req, 512 * 1024);
  const slot = Math.max(1, Math.min(API_KEYS.length, Number(body.keySlot || 1))) - 1;
  const payload = {
    fileName: safeFileName(body.originalFileName || body.fileNameOnGemini || 'media'),
    fileNameOnGemini: String(body.fileNameOnGemini || ''),
    fileUri: String(body.fileUri || ''),
    mimeType: String(body.mimeType || ''),
    language: body.language === 'persian' ? 'persian' : 'english',
    startSec: Number.isFinite(Number(body.startSec)) ? Math.max(0, Number(body.startSec)) : undefined,
    endSec: Number.isFinite(Number(body.endSec)) && Number(body.endSec) > 0 ? Number(body.endSec) : undefined
  };

  if (!payload.fileNameOnGemini) return sendJson(res, 400, { ok: false, error: 'Gemini file name is required' });
  if (payload.endSec && payload.startSec !== undefined && payload.endSec <= payload.startSec) {
    return sendJson(res, 400, { ok: false, error: 'End time must be after start time' });
  }

  try {
    console.log(`[media] analysis started key#${slot + 1} file=${JSON.stringify(payload.fileName)} range=${payload.startSec || 0}-${payload.endSec || 'end'}`);
    const lesson = await analyzeWithKey(API_KEYS[slot], payload);
    console.log(`[media] analysis complete key#${slot + 1} dialogues=${Array.isArray(lesson.dialogues) ? lesson.dialogues.length : 0}`);
    return sendJson(res, 200, { ok: true, lesson, model: MEDIA_MODEL });
  } catch (err) {
    console.error(`[media] analysis failed key#${slot + 1}: ${err.message}`);
    return sendJson(res, 502, { ok: false, error: err.message });
  }
}

http.createServer = function patchedCreateServer(listener) {
  return originalCreateServer((req, res) => {
    let pathname = '/';
    try { pathname = new URL(req.url, `http://${req.headers.host || 'localhost'}`).pathname; } catch {}

    if (req.method === 'POST' && pathname === '/api/media-upload-start') {
      handleUploadStart(req, res).catch(err => sendJson(res, 500, { ok: false, error: err.message }));
      return;
    }
    if (req.method === 'POST' && pathname === '/api/analyze-media') {
      handleAnalyze(req, res).catch(err => sendJson(res, 500, { ok: false, error: err.message }));
      return;
    }
    listener(req, res);
  });
};

console.log(`Media learning API preload active (${MEDIA_MODEL})`);
