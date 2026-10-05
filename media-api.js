const http = require('http');

const previousCreateServer = http.createServer.bind(http);
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
        resolve(JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}'));
      } catch {
        reject(new Error('Invalid JSON body'));
      }
    });
    req.on('error', reject);
  });
}

function safeText(value, max = 4000) {
  return String(value || '').replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/g, '').slice(0, max);
}

function safeFileName(name) {
  return safeText(name || 'audio', 180).replace(/[\r\n\t]/g, ' ');
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
  const text = await r.text();
  if (!r.ok) throw new Error(`Gemini upload start failed (${r.status}): ${text.slice(0, 500)}`);
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
    if (Date.now() - started > 180000) throw new Error('Audio processing timed out after 3 minutes');
    await new Promise(resolve => setTimeout(resolve, 2000));
    info = await getGeminiFile(key, fileName);
  }
  if (String(info.state || '').toUpperCase() === 'FAILED') throw new Error('Gemini could not process this audio file');
  return info;
}

function extractText(data) {
  return (data?.candidates?.[0]?.content?.parts || []).map(p => p?.text || '').join('').trim();
}

function parseJsonText(text) {
  const cleaned = String(text || '')
    .replace(/^```json\s*/i, '')
    .replace(/^```\s*/i, '')
    .replace(/```\s*$/i, '')
    .trim();
  return JSON.parse(cleaned);
}

function mediaPrompt({ fileName, language, userContext }) {
  const guidanceLanguage = language === 'persian' ? 'Persian' : 'English';
  const suppliedContext = safeText(userContext, 2500).trim();

  return `You are preparing a dialogue-based English lesson from an AUDIO-ONLY soundtrack extracted from a user-provided movie or clip named "${safeFileName(fileName)}".

AUDIO-ONLY EVIDENCE — MANDATORY:
- You receive audio only. No video frames are available.
- Base transcription and inference only on spoken words, voice continuity, turn-taking, pauses, prosody, emotion in the voice, and audible background/sound cues.
- Never invent visual facts, actions, locations, objects, faces, gestures, on-screen text, or character identities.
- If situational meaning depends on missing visual information, keep it uncertain and put a short clarification question in contextGaps.
- Timestamps MUST be relative to the beginning of this supplied audio clip: 00:00 is the first moment of the supplied audio.

DIALOGUE COHERENCE — MANDATORY:
- First understand each spoken exchange as a whole, then split it into teachable lines.
- Preserve original order and contiguous turns. Do not cherry-pick isolated lines that break the conversation.
- Keep speaker labels consistent using voice continuity. If identity is uncertain, use Speaker A / Speaker B / Speaker C; never invent names.
- For every line, identify how it relates to nearby turns when supported: what it responds to, intent, tone/prosody, and supported pronoun/ellipsis references.
- Never invent missing words. Mark only an unclear span as [unclear].
- Approximate timestamps are acceptable, but keep them monotonic and aligned with the audio.

USER-PROVIDED OPTIONAL SITUATION CONTEXT:
${suppliedContext ? suppliedContext : '(none provided)'}
This context was supplied by the learner. You may use it to disambiguate meaning, but do not expand it with unsupported visual assumptions.

Return ONLY valid JSON with this exact top-level shape:
{
  "title": "short lesson title",
  "summary": "brief coherent summary in ${guidanceLanguage}",
  "audioContext": "conversation context inferable from audio plus clearly marked learner-provided context, in ${guidanceLanguage}",
  "contextGaps": [
    {"exchangeId":"E1","question":"one short clarification question in ${guidanceLanguage} only when missing situation context materially changes interpretation"}
  ],
  "dialogues": [
    {
      "start":"00:00",
      "end":"00:04",
      "speaker":"Speaker A",
      "exchangeId":"E1",
      "respondsTo":"",
      "text":"exact spoken line",
      "meaning":"meaning in ${guidanceLanguage}",
      "context":"connection to nearby spoken turns in ${guidanceLanguage}",
      "intent":"communicative intent in ${guidanceLanguage}",
      "tone":"audible tone/prosody in ${guidanceLanguage}"
    }
  ],
  "phrases": [
    {"phrase":"useful phrase that actually occurs in the dialogue","meaning":"meaning in ${guidanceLanguage}","usage":"short contextual usage note in ${guidanceLanguage}"}
  ],
  "pronunciation": [
    {"text":"word or phrase that actually occurs in the dialogue","tip":"brief pronunciation / connected-speech tip in ${guidanceLanguage}"}
  ],
  "lessonFlow": [
    "Establish the exchange context",
    "Listen to one exact line",
    "Understand why it was said",
    "Repeat the exact line",
    "Receive pronunciation and connected-speech feedback",
    "Role-play the reply in the same context",
    "Continue to the next connected turn"
  ]
}

Return an empty contextGaps array when no clarification is genuinely needed. Conversation coherence is more important than maximizing the number of extracted lines.`;
}

function normalizeLesson(raw) {
  const lesson = raw && typeof raw === 'object' ? raw : {};
  const dialogues = Array.isArray(lesson.dialogues) ? lesson.dialogues.slice(0, 500) : [];
  const gaps = Array.isArray(lesson.contextGaps) ? lesson.contextGaps.slice(0, 30) : [];
  return {
    title: safeText(lesson.title, 240),
    summary: safeText(lesson.summary, 3000),
    audioContext: safeText(lesson.audioContext, 3000),
    contextGaps: gaps.map(g => ({
      exchangeId: safeText(g?.exchangeId, 40),
      question: safeText(g?.question, 500)
    })).filter(g => g.question),
    dialogues: dialogues.map(d => ({
      start: safeText(d?.start, 30),
      end: safeText(d?.end, 30),
      speaker: safeText(d?.speaker || 'Speaker', 80),
      exchangeId: safeText(d?.exchangeId, 40),
      respondsTo: safeText(d?.respondsTo, 40),
      text: safeText(d?.text, 1500),
      meaning: safeText(d?.meaning, 1200),
      context: safeText(d?.context, 1200),
      intent: safeText(d?.intent, 600),
      tone: safeText(d?.tone, 400)
    })).filter(d => d.text),
    phrases: (Array.isArray(lesson.phrases) ? lesson.phrases : []).slice(0, 100).map(p => ({
      phrase: safeText(p?.phrase, 300),
      meaning: safeText(p?.meaning, 600),
      usage: safeText(p?.usage, 600)
    })).filter(p => p.phrase),
    pronunciation: (Array.isArray(lesson.pronunciation) ? lesson.pronunciation : []).slice(0, 100).map(p => ({
      text: safeText(p?.text, 300),
      tip: safeText(p?.tip, 800)
    })).filter(p => p.text),
    lessonFlow: (Array.isArray(lesson.lessonFlow) ? lesson.lessonFlow : []).slice(0, 20).map(x => safeText(x, 400)).filter(Boolean)
  };
}

async function analyzeWithKey(key, payload) {
  const fileInfo = await waitForActiveFile(key, payload.fileNameOnGemini);
  const mimeType = payload.mimeType || fileInfo.mimeType || 'audio/aac';
  const fileUri = payload.fileUri || fileInfo.uri;
  if (!fileUri) throw new Error('Gemini file URI is missing');
  if (!/^audio\//i.test(mimeType)) {
    throw new Error('Audio-only mode rejected a non-audio file. Refresh the app and try again.');
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
          { fileData: { mimeType, fileUri } },
          { text: mediaPrompt(payload) }
        ]
      }],
      generationConfig: {
        temperature: 0.05,
        responseMimeType: 'application/json'
      }
    })
  });

  const text = await r.text();
  let data = {};
  try { data = JSON.parse(text); } catch {}
  if (!r.ok) throw new Error(data?.error?.message || `Gemini audio analysis failed (${r.status})`);

  const modelText = extractText(data);
  if (!modelText) throw new Error('Gemini returned no dialogue analysis');
  return normalizeLesson(parseJsonText(modelText));
}

async function handleUploadStart(req, res) {
  if (!API_KEYS.length) return sendJson(res, 500, { ok: false, error: 'Gemini API key is not configured' });
  const body = await readJson(req, 128 * 1024);
  const fileName = safeFileName(body.fileName);
  const mimeType = String(body.mimeType || 'audio/aac');
  const size = Number(body.size || 0);
  if (!size || size < 1) return sendJson(res, 400, { ok: false, error: 'File size is required' });
  if (!/^audio\//i.test(mimeType)) return sendJson(res, 400, { ok: false, error: 'Audio-only mode accepts only extracted audio files' });

  let lastError = null;
  for (let i = 0; i < API_KEYS.length; i++) {
    try {
      const uploadUrl = await startGeminiUpload(API_KEYS[i], { fileName, mimeType, size });
      console.log(`[media] audio upload started key#${i + 1} file=${JSON.stringify(fileName)} size=${size} mime=${mimeType}`);
      return sendJson(res, 200, { ok: true, uploadUrl, keySlot: i + 1, model: MEDIA_MODEL, mode: 'true-audio-only' });
    } catch (err) {
      lastError = err;
      console.error(`[media] audio upload start failed key#${i + 1}: ${err.message}`);
    }
  }
  return sendJson(res, 502, { ok: false, error: lastError?.message || 'Could not start audio upload' });
}

async function handleAnalyze(req, res) {
  if (!API_KEYS.length) return sendJson(res, 500, { ok: false, error: 'Gemini API key is not configured' });
  const body = await readJson(req, 512 * 1024);
  const slot = Math.max(1, Math.min(API_KEYS.length, Number(body.keySlot || 1))) - 1;
  const payload = {
    fileName: safeFileName(body.originalFileName || body.fileNameOnGemini || 'audio'),
    fileNameOnGemini: String(body.fileNameOnGemini || ''),
    fileUri: String(body.fileUri || ''),
    mimeType: String(body.mimeType || ''),
    language: body.language === 'persian' ? 'persian' : 'english',
    userContext: safeText(body.userContext, 2500)
  };

  if (!payload.fileNameOnGemini) return sendJson(res, 400, { ok: false, error: 'Gemini audio file name is required' });
  if (!/^audio\//i.test(payload.mimeType)) return sendJson(res, 400, { ok: false, error: 'Audio-only analysis requires an audio file' });

  try {
    console.log(`[media] true audio-only analysis started key#${slot + 1} file=${JSON.stringify(payload.fileName)} contextChars=${payload.userContext.length}`);
    const lesson = await analyzeWithKey(API_KEYS[slot], payload);
    console.log(`[media] true audio-only analysis complete key#${slot + 1} dialogues=${lesson.dialogues.length} contextGaps=${lesson.contextGaps.length}`);
    return sendJson(res, 200, { ok: true, lesson, model: MEDIA_MODEL, analysisMode: 'true-audio-only' });
  } catch (err) {
    console.error(`[media] true audio-only analysis failed key#${slot + 1}: ${err.message}`);
    return sendJson(res, 502, { ok: false, error: err.message });
  }
}

http.createServer = function patchedCreateServer(listener) {
  return previousCreateServer((req, res) => {
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

console.log(`Media learning API preload active (${MEDIA_MODEL}) — TRUE audio-only, no video input to Gemini`);
