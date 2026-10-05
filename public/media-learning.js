(() => {
  const MEDIA_DB = 'englishTutorMediaLibraryV1';
  const MEDIA_DB_VERSION = 1;
  const LESSON_STORE = 'lessons';
  const AUDIO_STORE = 'audio';
  const NATIVE_CHUNK_BYTES = 192 * 1024;

  const style = document.createElement('style');
  style.textContent = `
    .media-box{margin-top:16px;padding:14px;border:1px solid #dbeafe;border-radius:16px;background:#eff6ff}
    .media-head{display:flex;justify-content:space-between;gap:12px;align-items:flex-start}
    .media-title{font-size:14px;font-weight:800;color:#1e3a8a}
    .media-grid{display:grid;grid-template-columns:1fr 110px 110px;gap:8px;align-items:end;margin-top:10px}
    .media-preview{width:100%;max-height:320px;background:#111827;border-radius:14px;margin-top:10px;display:none}
    .media-actions{display:flex;flex-wrap:wrap;gap:8px;margin-top:10px}
    .media-status{font-size:12px;line-height:1.7;color:#475569;margin-top:8px;white-space:pre-wrap}
    .media-result{margin-top:10px;padding:10px;border-radius:12px;background:#fff;border:1px solid #dbeafe;font-size:12px;line-height:1.7;display:none;white-space:pre-wrap}
    .audio-only-note{margin-top:8px;padding:8px 10px;border-radius:11px;background:#dbeafe;color:#1e3a8a;font-size:11px;line-height:1.6}
    .media-context{margin-top:8px}
    .media-export{display:none;gap:8px;flex-wrap:wrap;margin-top:8px}
    .media-local-badge{font-size:10px;padding:3px 7px;border-radius:999px;background:#dcfce7;color:#166534}
    .dialogue-player{display:none;margin-top:12px;padding:10px;border-radius:14px;background:#fff;border:1px solid #bfdbfe}
    .dialogue-player-head{display:flex;justify-content:space-between;gap:8px;align-items:center;flex-wrap:wrap}
    .dialogue-player-tools{display:flex;gap:6px;flex-wrap:wrap}
    .dialogue-list{display:grid;gap:6px;margin-top:9px;max-height:320px;overflow:auto}
    .dialogue-row{display:grid;grid-template-columns:auto 1fr;gap:8px;align-items:start;padding:8px;border:1px solid #e2e8f0;border-radius:10px;background:#f8fafc;text-align:start}
    .dialogue-row.current{border-color:#2563eb;background:#eff6ff}
    .dialogue-play{white-space:nowrap}
    .dialogue-text{font-size:12px;line-height:1.55;color:#0f172a}
    .dialogue-meta{font-size:10px;color:#64748b;margin-top:2px}
    @media(max-width:720px){.media-grid{grid-template-columns:1fr 1fr}.media-grid .media-file{grid-column:1/-1}.media-head{flex-direction:column}}
  `;
  document.head.appendChild(style);

  const sourceCard = document.querySelector('.source-card');
  if (!sourceCard) return;

  function hasNativeMedia3() {
    try {
      return Boolean(window.AndroidMedia && typeof window.AndroidMedia.isAvailable === 'function' && window.AndroidMedia.isAvailable());
    } catch {
      return false;
    }
  }

  const nativeMode = hasNativeMedia3();
  const box = document.createElement('div');
  box.className = 'media-box';
  box.innerHTML = `
    <div class="media-head">
      <div>
        <div class="media-title">🎬 آموزش انگلیسی با فیلم / کلیپ</div>
        <div class="hint tiny">${nativeMode
          ? 'در APK، فیلم روی خود گوشی می‌ماند؛ Google Media3 فقط صدای بازه انتخابی را داخل گوشی جدا می‌کند و فقط Audio ارسال می‌شود.'
          : 'در نسخه وب، بازه انتخابی برای استخراج Audio پردازش می‌شود. برای اینکه فیلم کامل از گوشی خارج نشود از APK استفاده کن.'}</div>
      </div>
      <span class="library-badge">${nativeMode ? '📱 Local Media3' : 'True Audio-only'}</span>
    </div>
    <div class="audio-only-note">هیچ فریم تصویری برای AI ارسال نمی‌شود. تصویر فقط در Media Player خودت می‌ماند. بعد از استخراج دیالوگ می‌توانی روی هر خط بزنی یا در کلاس بگویی «دوباره این دیالوگ رو پخش کن» تا همان بازه فیلم پخش شود.</div>
    <div class="media-grid">
      <label class="media-file">انتخاب فیلم / کلیپ / صوت
        <input id="mediaLearningFile" type="file" accept="video/*,audio/*" />
      </label>
      <label>از دقیقه
        <input id="mediaStartMinute" type="number" min="0" step="0.5" value="0" />
      </label>
      <label>تا دقیقه
        <input id="mediaEndMinute" type="number" min="0" step="0.5" value="5" />
      </label>
    </div>
    <video id="mediaLearningPreview" class="media-preview" controls playsinline></video>
    <audio id="mediaLearningAudioPreview" class="media-preview" controls></audio>
    <label class="media-context">توضیح اختیاری موقعیت
      <textarea id="mediaSceneContext" rows="2" placeholder="مثلاً: دو نفر داخل ماشین هستند و یکی از آن‌ها از حرف قبلی ناراحت شده. اگر لازم نیست خالی بگذار."></textarea>
    </label>
    <div class="media-actions">
      <button id="analyzeMediaBtn" class="primary">ساخت درس و شروع آموزش</button>
      <button id="analyzeMediaOnlyBtn" class="secondary">فقط استخراج و ذخیره دیالوگ‌ها</button>
    </div>
    <div id="mediaLearningStatus" class="media-status">هنوز فایل رسانه‌ای انتخاب نشده.</div>
    <div id="mediaLearningResult" class="media-result"></div>
    <div id="mediaExportActions" class="media-export">
      <button id="exportTranscriptJsonBtn" class="ghost">خروجی JSON</button>
      <button id="exportTranscriptSrtBtn" class="ghost">خروجی SRT</button>
      <span class="media-local-badge">ذخیره در حافظه دستگاه</span>
    </div>
    <div id="dialoguePlayer" class="dialogue-player">
      <div class="dialogue-player-head">
        <div>
          <b>🎞️ Media Player دیالوگ‌ها</b>
          <div class="hint tiny">فیلم را عادی عقب/جلو کن یا یک دیالوگ را دقیقاً از Timestamp خودش پخش کن.</div>
        </div>
        <div class="dialogue-player-tools">
          <button id="mediaBack5Btn" class="ghost">−5s</button>
          <button id="mediaReplayBtn" class="secondary">↻ همین دیالوگ</button>
          <button id="mediaForward5Btn" class="ghost">+5s</button>
        </div>
      </div>
      <div id="dialogueList" class="dialogue-list"></div>
    </div>
  `;

  const methodBox = sourceCard.querySelector('.method-box');
  if (methodBox) sourceCard.insertBefore(box, methodBox);
  else sourceCard.appendChild(box);

  const fileInput = document.getElementById('mediaLearningFile');
  const videoPreview = document.getElementById('mediaLearningPreview');
  const audioPreview = document.getElementById('mediaLearningAudioPreview');
  const status = document.getElementById('mediaLearningStatus');
  const result = document.getElementById('mediaLearningResult');
  const exportActions = document.getElementById('mediaExportActions');
  const analyzeBtn = document.getElementById('analyzeMediaBtn');
  const analyzeOnlyBtn = document.getElementById('analyzeMediaOnlyBtn');
  const contextInput = document.getElementById('mediaSceneContext');
  const exportJsonBtn = document.getElementById('exportTranscriptJsonBtn');
  const exportSrtBtn = document.getElementById('exportTranscriptSrtBtn');
  const dialoguePlayer = document.getElementById('dialoguePlayer');
  const dialogueList = document.getElementById('dialogueList');
  const mediaBack5Btn = document.getElementById('mediaBack5Btn');
  const mediaReplayBtn = document.getElementById('mediaReplayBtn');
  const mediaForward5Btn = document.getElementById('mediaForward5Btn');

  let selectedFile = null;
  let objectUrl = null;
  let latestArchive = null;
  let currentDialogueIndex = 0;
  let playEndSec = null;
  let mutedBeforeMovie = null;
  const nativePending = new Map();

  function setStatus(text, isError = false) {
    status.textContent = text;
    status.style.color = isError ? '#991b1b' : '#475569';
  }

  function fmtBytes(n) {
    const units = ['B', 'KB', 'MB', 'GB'];
    let i = 0;
    let value = Number(n || 0);
    while (value >= 1024 && i < units.length - 1) { value /= 1024; i += 1; }
    return `${value.toFixed(i ? 1 : 0)} ${units[i]}`;
  }

  function currentLanguage() {
    try {
      return state?.conversationLanguage === 'persian' ? 'persian' : 'english';
    } catch {
      const active = document.querySelector('#languagePicker button.active');
      return active?.dataset?.language === 'persian' ? 'persian' : 'english';
    }
  }

  function makeId() {
    return globalThis.crypto?.randomUUID?.() || `media-${Date.now()}-${Math.random().toString(16).slice(2)}`;
  }

  function parseTimestamp(value) {
    const text = String(value || '').trim().replace(',', '.');
    if (!text) return null;
    const parts = text.split(':').map(Number);
    if (parts.some(Number.isNaN)) return null;
    if (parts.length === 3) return parts[0] * 3600 + parts[1] * 60 + parts[2];
    if (parts.length === 2) return parts[0] * 60 + parts[1];
    if (parts.length === 1) return parts[0];
    return null;
  }

  function formatTimestamp(seconds) {
    const value = Math.max(0, Number(seconds || 0));
    const hours = Math.floor(value / 3600);
    const minutes = Math.floor((value % 3600) / 60);
    const secs = value % 60;
    const secText = secs.toFixed(secs % 1 ? 3 : 0).padStart(2, '0');
    return hours > 0
      ? `${String(hours).padStart(2, '0')}:${String(minutes).padStart(2, '0')}:${secText}`
      : `${String(minutes).padStart(2, '0')}:${secText}`;
  }

  function formatSrtTimestamp(seconds) {
    const ms = Math.max(0, Math.round(Number(seconds || 0) * 1000));
    const h = Math.floor(ms / 3600000);
    const m = Math.floor((ms % 3600000) / 60000);
    const s = Math.floor((ms % 60000) / 1000);
    const millis = ms % 1000;
    return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')},${String(millis).padStart(3, '0')}`;
  }

  function offsetLessonTimestamps(lesson, offsetSec) {
    if (!offsetSec || !Array.isArray(lesson?.dialogues)) return lesson;
    lesson.dialogues = lesson.dialogues.map(d => {
      const start = parseTimestamp(d.start);
      const end = parseTimestamp(d.end);
      return {
        ...d,
        start: start == null ? d.start : formatTimestamp(start + offsetSec),
        end: end == null ? d.end : formatTimestamp(end + offsetSec)
      };
    });
    return lesson;
  }

  function lessonToSrt(lesson) {
    const lines = Array.isArray(lesson?.dialogues) ? lesson.dialogues : [];
    return lines.map((d, index) => {
      const start = parseTimestamp(d.start) ?? 0;
      let end = parseTimestamp(d.end);
      if (end == null || end <= start) end = start + 2.5;
      const speaker = d.speaker ? `${d.speaker}: ` : '';
      return `${index + 1}\n${formatSrtTimestamp(start)} --> ${formatSrtTimestamp(end)}\n${speaker}${d.text || ''}`;
    }).join('\n\n');
  }

  function openMediaDb() {
    return new Promise((resolve, reject) => {
      const req = indexedDB.open(MEDIA_DB, MEDIA_DB_VERSION);
      req.onupgradeneeded = () => {
        const db = req.result;
        if (!db.objectStoreNames.contains(LESSON_STORE)) db.createObjectStore(LESSON_STORE, { keyPath: 'id' });
        if (!db.objectStoreNames.contains(AUDIO_STORE)) db.createObjectStore(AUDIO_STORE);
      };
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
  }

  async function saveArchiveOnDevice(archive, audioBlob) {
    const db = await openMediaDb();
    try {
      await new Promise((resolve, reject) => {
        const tx = db.transaction([LESSON_STORE, AUDIO_STORE], 'readwrite');
        tx.objectStore(LESSON_STORE).put(archive);
        tx.objectStore(AUDIO_STORE).put(audioBlob, archive.id);
        tx.oncomplete = resolve;
        tx.onerror = () => reject(tx.error);
        tx.onabort = () => reject(tx.error || new Error('Local media save aborted'));
      });
      return true;
    } finally {
      db.close();
    }
  }

  function downloadText(fileName, text, mimeType) {
    const blob = new Blob([text], { type: `${mimeType};charset=utf-8` });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = fileName;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  function dialogueSource(lesson, fileName, startSec, endSec, userContext) {
    const lines = Array.isArray(lesson.dialogues) ? lesson.dialogues : [];
    const gaps = Array.isArray(lesson.contextGaps) ? lesson.contextGaps : [];
    const phrases = Array.isArray(lesson.phrases) ? lesson.phrases : [];
    const pronunciation = Array.isArray(lesson.pronunciation) ? lesson.pronunciation : [];
    const flow = Array.isArray(lesson.lessonFlow) ? lesson.lessonFlow : [];

    return [
      `TRUE AUDIO-ONLY MEDIA DIALOGUE LESSON: ${lesson.title || fileName}`,
      `SOURCE FILE: ${fileName}`,
      `ORIGINAL TIME RANGE: ${formatTimestamp(startSec)} - ${endSec ? formatTimestamp(endSec) : 'end'}`,
      userContext ? `LEARNER-PROVIDED SITUATION CONTEXT: ${userContext}` : 'LEARNER-PROVIDED SITUATION CONTEXT: none',
      lesson.summary ? `SUMMARY: ${lesson.summary}` : '',
      lesson.audioContext ? `AUDIO-INFERRED CONVERSATION CONTEXT: ${lesson.audioContext}` : '',
      '',
      'STRICT AUDIO-ONLY TEACHING RULES:',
      'Use ONLY the transcript, audio-derived context, and learner-provided context below as source evidence.',
      'No video frames were sent to the analysis model. Never invent visual facts, locations, gestures, actions, objects, or identities.',
      'Preserve conversational coherence and teach connected turns as an exchange, not as unrelated sentences.',
      'If a missing situation detail materially changes the meaning, ask the learner ONE short clarification question instead of guessing.',
      'The learner may answer that context question by voice during the live class; use that answer for the ongoing lesson.',
      'Teach one original line at a time, keeping the original order inside each exchange.',
      'For each line: establish context → present the exact line → explain meaning/intent → learner repeats → pronunciation feedback → role-play reply → continue.',
      'If the learner asks to play/replay the current dialogue, acknowledge very briefly and stop talking; the app media player will replay the original movie segment automatically.',
      'Never imitate the movie audio as a substitute for playback.',
      'Never invent a missing movie line. If [unclear] appears, do not pretend to know the missing words.',
      'Ask only one question or task at a time.',
      '',
      gaps.length ? 'CONTEXT GAPS — ASK LEARNER ONLY WHEN RELEVANT:' : '',
      ...gaps.map((g, i) => `${i + 1}. ${g.exchangeId ? `[${g.exchangeId}] ` : ''}${g.question || ''}`),
      '',
      'COHERENT DIALOGUE TRANSCRIPT:',
      ...lines.map((d, i) => {
        const details = [
          d.respondsTo ? `RespondsTo: ${d.respondsTo}` : '',
          d.meaning ? `Meaning: ${d.meaning}` : '',
          d.context ? `Context: ${d.context}` : '',
          d.intent ? `Intent: ${d.intent}` : '',
          d.tone ? `Tone: ${d.tone}` : ''
        ].filter(Boolean).join(' | ');
        return `${i + 1}. ${d.exchangeId ? `[${d.exchangeId}] ` : ''}[${d.start || ''}${d.end ? `-${d.end}` : ''}] ${d.speaker || 'Speaker'}: ${d.text || ''}${details ? `\n   ${details}` : ''}`;
      }),
      '',
      'USEFUL PHRASES:',
      ...phrases.map((p, i) => `${i + 1}. ${p.phrase || ''} — ${p.meaning || ''}${p.usage ? ` | ${p.usage}` : ''}`),
      '',
      'PRONUNCIATION / CONNECTED SPEECH:',
      ...pronunciation.map((p, i) => `${i + 1}. ${p.text || ''} — ${p.tip || ''}`),
      '',
      'SUGGESTED FLOW:',
      ...flow.map((x, i) => `${i + 1}. ${x}`)
    ].filter(Boolean).join('\n');
  }

  function prepareNewLibrarySource() {
    window.dispatchEvent(new CustomEvent('englishTutorPrepareNewSource', { detail: { kind: 'media' } }));
  }

  function applyLessonToTutor(lesson, file, startSec, endSec, userContext, mediaCacheId) {
    prepareNewLibrarySource();
    const book = document.getElementById('bookName');
    const lessonName = document.getElementById('lessonName');
    const source = document.getElementById('lessonSource');
    if (book) book.value = `🎬 ${file.name.replace(/\.[^.]+$/, '')}`;
    if (lessonName) lessonName.value = lesson.title || `Dialogue ${Math.round(startSec / 60)}-${endSec ? Math.round(endSec / 60) : 'end'} min`;
    if (source) source.value = dialogueSource(lesson, file.name, startSec, endSec, userContext);

    try {
      if (typeof state !== 'undefined') {
        state.mode = 'teacher';
        state.methodProfile = null;
      }
    } catch {}

    document.querySelectorAll('#modePicker button').forEach(b => {
      b.classList.toggle('active', b.dataset.mode === 'teacher');
    });

    try { if (typeof saveProgress === 'function') saveProgress(); } catch {}
    window.dispatchEvent(new CustomEvent('englishTutorNewSource', {
      detail: { kind: 'media', mediaCacheId, fileName: file.name }
    }));
  }

  function base64ToBytes(b64) {
    const bin = atob(b64);
    const out = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  }

  async function readNativeAudioFile(requestId, meta) {
    const total = Number(meta?.size || 0);
    if (!total) throw new Error('Media3 خروجی صوتی معتبری نساخت.');
    const parts = [];
    let offset = 0;
    let chunkCount = 0;
    while (offset < total) {
      const wanted = Math.min(NATIVE_CHUNK_BYTES, total - offset);
      const b64 = window.AndroidMedia.readAudioChunk(requestId, offset, wanted);
      if (!b64) throw new Error('خواندن Audio استخراج‌شده از حافظه Android متوقف شد.');
      const bytes = base64ToBytes(b64);
      parts.push(bytes);
      offset += bytes.byteLength;
      chunkCount += 1;
      if (chunkCount % 6 === 0) await new Promise(resolve => setTimeout(resolve, 0));
    }
    return new File(parts, meta.name || 'movie-audio.m4a', { type: meta.mimeType || 'audio/m4a' });
  }

  window.__englishTutorNativeMediaReady = async (requestId, meta) => {
    const pending = nativePending.get(requestId);
    if (!pending) return;
    try {
      setStatus(`صدا روی خود گوشی جدا شد ✅ ${fmtBytes(meta?.size || 0)}\nدر حال آماده‌سازی Audio محلی…`);
      const file = await readNativeAudioFile(requestId, meta || {});
      pending.resolve(file);
    } catch (err) {
      pending.reject(err);
    } finally {
      nativePending.delete(requestId);
      try { window.AndroidMedia.releaseAudio(requestId); } catch {}
    }
  };

  window.__englishTutorNativeMediaError = (requestId, message) => {
    const pending = nativePending.get(requestId);
    if (!pending) return;
    nativePending.delete(requestId);
    pending.reject(new Error(message || 'Media3 نتوانست صدا را روی گوشی جدا کند.'));
  };

  function extractAudioNative(startSec, endSec) {
    return new Promise((resolve, reject) => {
      const requestId = makeId().replace(/[^A-Za-z0-9_-]/g, '');
      nativePending.set(requestId, { resolve, reject });
      setStatus(`فیلم از گوشی خارج نمی‌شود ✅\nدر حال جدا کردن صدای ${formatTimestamp(startSec)} تا ${endSec ? formatTimestamp(endSec) : 'پایان'} با Google Media3 روی خود گوشی…`);
      try {
        if (!window.AndroidMedia.hasSelectedMedia()) {
          nativePending.delete(requestId);
          reject(new Error('Android فایل فیلم را پیدا نکرد؛ یک‌بار دیگر فیلم را انتخاب کن.'));
          return;
        }
        window.AndroidMedia.extractAudio(startSec, endSec || 0, requestId);
      } catch (err) {
        nativePending.delete(requestId);
        reject(err);
      }
    });
  }

  async function extractAudioServer(file, startSec, endSec) {
    setStatus(`نسخه وب: در حال جدا کردن Audio Track با FFmpeg روی سرور…\nبازه ${formatTimestamp(startSec)} تا ${endSec ? formatTimestamp(endSec) : 'پایان'}`);
    const response = await fetch('/api/extract-audio', {
      method: 'POST',
      headers: {
        'Content-Type': file.type || 'video/mp4',
        'X-File-Name': encodeURIComponent(file.name),
        'X-File-Size': String(file.size),
        'X-Start-Sec': String(startSec),
        'X-End-Sec': endSec ? String(endSec) : ''
      },
      body: file
    });

    if (!response.ok) {
      const data = await response.json().catch(() => ({}));
      throw new Error(data.error || `Audio extraction HTTP ${response.status}`);
    }

    const blob = await response.blob();
    if (!blob.size) throw new Error('Audio extraction returned an empty file');
    const headerName = response.headers.get('x-audio-file-name');
    let name = `${file.name.replace(/\.[^.]+$/, '')}.aac`;
    if (headerName) {
      try { name = decodeURIComponent(headerName); } catch { name = headerName; }
    }
    const type = response.headers.get('content-type')?.split(';')[0] || blob.type || 'audio/aac';
    return new File([blob], name, { type });
  }

  async function extractAudio(file, startSec, endSec) {
    if (hasNativeMedia3()) return extractAudioNative(startSec, endSec);
    return extractAudioServer(file, startSec, endSec);
  }

  async function uploadDirect(uploadUrl, file) {
    const uploadResp = await fetch(uploadUrl, {
      method: 'POST',
      headers: {
        'X-Goog-Upload-Offset': '0',
        'X-Goog-Upload-Command': 'upload, finalize'
      },
      body: file
    });
    const text = await uploadResp.text();
    let uploadData = {};
    try { uploadData = JSON.parse(text); } catch {}
    if (!uploadResp.ok) throw new Error(uploadData?.error?.message || `Upload HTTP ${uploadResp.status}`);
    const info = uploadData.file || uploadData;
    if (!info?.name) throw new Error('Gemini upload finished but file information was missing');
    return info;
  }

  async function uploadViaRender(file, keySlot) {
    setStatus(`آپلود مستقیم Audio به Gemini در دسترس نبود؛ فقط Audio از مسیر امن Render فرستاده می‌شود…\n${fmtBytes(file.size)}`);
    const r = await fetch(`/api/media-upload-proxy?slot=${encodeURIComponent(keySlot)}`, {
      method: 'POST',
      headers: {
        'Content-Type': file.type || 'audio/m4a',
        'X-File-Name': encodeURIComponent(file.name),
        'X-File-Size': String(file.size)
      },
      body: file
    });
    const data = await r.json().catch(() => ({}));
    if (!r.ok) throw new Error(data.error || `Proxy upload HTTP ${r.status}`);
    const info = data.file || {};
    if (!info?.name) throw new Error('Render proxy upload completed without Gemini file information');
    return { info, keySlot: data.keySlot || keySlot };
  }

  async function uploadAudioToGemini(audioFile) {
    setStatus(`صدا آماده شد ✅ ${fmtBytes(audioFile.size)}\nدر حال ارسال فقط Audio برای استخراج دیالوگ…`);
    const startResp = await fetch('/api/media-upload-start', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ fileName: audioFile.name, mimeType: audioFile.type || 'audio/m4a', size: audioFile.size })
    });
    const startData = await startResp.json().catch(() => ({}));
    if (!startResp.ok) throw new Error(startData.error || `Upload start HTTP ${startResp.status}`);

    try {
      const info = await uploadDirect(startData.uploadUrl, audioFile);
      return { info, keySlot: startData.keySlot };
    } catch (directErr) {
      console.warn('Direct Gemini audio upload failed; using Render proxy fallback', directErr);
      return uploadViaRender(audioFile, startData.keySlot);
    }
  }

  async function analyzeAudio(uploaded, originalName, userContext) {
    setStatus('Audio آماده شد. در حال استخراج دیالوگ، Timestamp، Speaker، لحن و ارتباط بین جمله‌ها…');
    const response = await fetch('/api/analyze-media', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        originalFileName: originalName,
        fileNameOnGemini: uploaded.info.name,
        fileUri: uploaded.info.uri,
        mimeType: uploaded.info.mimeType || 'audio/m4a',
        keySlot: uploaded.keySlot,
        language: currentLanguage(),
        userContext
      })
    });
    const data = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(data.error || `Analyze HTTP ${response.status}`);
    return data.lesson || {};
  }

  function activeMediaElement() {
    if (selectedFile?.type?.startsWith('video/') && videoPreview.src) return videoPreview;
    if (audioPreview.src) return audioPreview;
    if (videoPreview.src) return videoPreview;
    return null;
  }

  function setCurrentDialogue(index) {
    const lines = latestArchive?.lesson?.dialogues || [];
    if (!lines.length) return;
    currentDialogueIndex = Math.max(0, Math.min(lines.length - 1, Number(index) || 0));
    [...dialogueList.querySelectorAll('.dialogue-row')].forEach((row, i) => {
      row.classList.toggle('current', i === currentDialogueIndex);
    });
  }

  function restoreMicAfterMovie() {
    if (mutedBeforeMovie == null) return;
    try {
      if (typeof state !== 'undefined') state.muted = mutedBeforeMovie;
    } catch {}
    mutedBeforeMovie = null;
  }

  function stopRangePlayback() {
    playEndSec = null;
    restoreMicAfterMovie();
  }

  function enforcePlayEnd() {
    const player = activeMediaElement();
    if (!player || playEndSec == null) return;
    if (player.currentTime >= playEndSec - 0.04) {
      player.pause();
      stopRangePlayback();
    }
  }

  videoPreview.addEventListener('timeupdate', enforcePlayEnd);
  audioPreview.addEventListener('timeupdate', enforcePlayEnd);
  videoPreview.addEventListener('pause', () => { if (playEndSec == null) restoreMicAfterMovie(); });
  audioPreview.addEventListener('pause', () => { if (playEndSec == null) restoreMicAfterMovie(); });

  async function playDialogue(index) {
    const player = activeMediaElement();
    const lines = latestArchive?.lesson?.dialogues || [];
    if (!player || !selectedFile) {
      setStatus('برای پخش دیالوگ، فایل اصلی فیلم باید در همین جلسه انتخاب شده باشد.', true);
      return false;
    }
    if (!lines.length) return false;
    const safeIndex = Math.max(0, Math.min(lines.length - 1, Number(index) || 0));
    const line = lines[safeIndex];
    const start = parseTimestamp(line.start);
    let end = parseTimestamp(line.end);
    if (start == null) return false;
    if (end == null || end <= start) end = start + 3;

    try {
      if (typeof stopPlayback === 'function') stopPlayback();
    } catch {}
    try {
      if (typeof state !== 'undefined') {
        mutedBeforeMovie = state.muted;
        state.muted = true;
      }
    } catch {}

    setCurrentDialogue(safeIndex);
    playEndSec = end;
    player.currentTime = Math.max(0, start - 0.08);
    try {
      await player.play();
      setStatus(`🎬 در حال پخش دیالوگ ${safeIndex + 1}: ${formatTimestamp(start)} تا ${formatTimestamp(end)}\nهنگام پخش فیلم، میکروفن Live موقتاً ساکت می‌شود تا صدای فیلم دوباره وارد Gemini نشود.`);
      return true;
    } catch (err) {
      stopRangePlayback();
      setStatus(`پخش فیلم شروع نشد: ${err.message}`, true);
      return false;
    }
  }

  function seekBy(seconds) {
    const player = activeMediaElement();
    if (!player) return;
    playEndSec = null;
    restoreMicAfterMovie();
    const duration = Number.isFinite(player.duration) ? player.duration : Infinity;
    player.currentTime = Math.max(0, Math.min(duration, player.currentTime + seconds));
  }

  function renderDialoguePlayer() {
    const lines = latestArchive?.lesson?.dialogues || [];
    dialogueList.replaceChildren();
    if (!lines.length) {
      dialoguePlayer.style.display = 'none';
      return;
    }
    dialoguePlayer.style.display = 'block';
    lines.forEach((d, index) => {
      const row = document.createElement('div');
      row.className = `dialogue-row${index === currentDialogueIndex ? ' current' : ''}`;

      const play = document.createElement('button');
      play.className = 'ghost dialogue-play';
      play.textContent = `▶ ${index + 1}`;
      play.onclick = () => playDialogue(index);

      const body = document.createElement('div');
      const text = document.createElement('div');
      text.className = 'dialogue-text';
      text.textContent = d.text || '';
      const meta = document.createElement('div');
      meta.className = 'dialogue-meta';
      meta.textContent = `${d.start || ''}${d.end ? ` – ${d.end}` : ''} • ${d.speaker || 'Speaker'}${d.exchangeId ? ` • ${d.exchangeId}` : ''}`;
      body.append(text, meta);
      row.append(play, body);
      row.onclick = (event) => {
        if (event.target === play) return;
        setCurrentDialogue(index);
      };
      dialogueList.appendChild(row);
    });
  }

  function normalizeForMatch(text) {
    return String(text || '').toLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ').replace(/\s+/g, ' ').trim();
  }

  function syncCurrentDialogueFromTeacher(aiText) {
    const lines = latestArchive?.lesson?.dialogues || [];
    const hay = normalizeForMatch(aiText);
    if (!hay) return;
    let bestIndex = -1;
    let bestLength = 0;
    lines.forEach((line, index) => {
      const needle = normalizeForMatch(line.text);
      if (needle.length >= 6 && hay.includes(needle) && needle.length > bestLength) {
        bestLength = needle.length;
        bestIndex = index;
      }
    });
    if (bestIndex >= 0) setCurrentDialogue(bestIndex);
  }

  function replayCommandIndex(userText) {
    const text = String(userText || '');
    const normalized = normalizeForMatch(text);
    if (!normalized) return null;

    const numbered = text.match(/(?:دیالوگ|جمله|خط|line|dialogue)\s*(\d{1,3})/i);
    const replayWords =
      /(دوباره|مجدد).*(پخش|بزن|بذار|بگذار)|(?:این|همین).*(?:دیالوگ|جمله).*(?:پخش|بزن)|(?:پخش|بزن).*(?:دوباره|همین)|\breplay\b|play\s+(?:it|this|that|the\s+(?:line|dialogue))\s+again|play\s+this\s+(?:line|dialogue)/i;
    if (!replayWords.test(text)) return null;

    if (numbered) {
      const n = Number(numbered[1]);
      if (Number.isFinite(n) && n > 0) return n - 1;
    }
    return currentDialogueIndex;
  }

  const chat = document.getElementById('chat');
  if (chat) {
    new MutationObserver(records => {
      for (const record of records) {
        for (const node of record.addedNodes) {
          if (!(node instanceof HTMLElement) || !node.classList.contains('message')) continue;
          const text = node.textContent || '';
          if (node.classList.contains('ai')) syncCurrentDialogueFromTeacher(text);
          if (node.classList.contains('user')) {
            const replayIndex = replayCommandIndex(text);
            if (replayIndex != null) setTimeout(() => playDialogue(replayIndex), 250);
          }
        }
      }
    }).observe(chat, { childList: true });
  }

  window.EnglishTutorMediaPlayer = {
    playDialogue: (oneBasedIndex) => playDialogue(Math.max(0, Number(oneBasedIndex || 1) - 1)),
    replayCurrent: () => playDialogue(currentDialogueIndex),
    seekBy,
    currentDialogue: () => currentDialogueIndex + 1
  };

  async function analyzeMedia(autoStart) {
    if (!selectedFile) {
      setStatus('اول یک فیلم، کلیپ یا فایل صوتی انتخاب کن.', true);
      return;
    }

    const startMin = Math.max(0, Number(document.getElementById('mediaStartMinute').value || 0));
    const endMinRaw = Number(document.getElementById('mediaEndMinute').value || 0);
    const endMin = endMinRaw > 0 ? endMinRaw : null;
    const startSec = Math.round(startMin * 60);
    const endSec = endMin ? Math.round(endMin * 60) : null;
    const userContext = contextInput.value.trim();

    if (endSec && endSec <= startSec) {
      setStatus('دقیقه پایان باید بعد از دقیقه شروع باشد.', true);
      return;
    }

    analyzeBtn.disabled = true;
    analyzeOnlyBtn.disabled = true;
    exportActions.style.display = 'none';
    dialoguePlayer.style.display = 'none';
    result.style.display = 'none';
    result.textContent = '';

    try {
      if (autoStart) {
        try { if (typeof state !== 'undefined' && state.ws) stopLive(); } catch {}
      }

      const audioFile = await extractAudio(selectedFile, startSec, endSec);
      const uploaded = await uploadAudioToGemini(audioFile);
      let lesson = await analyzeAudio(uploaded, selectedFile.name, userContext);
      const count = Array.isArray(lesson.dialogues) ? lesson.dialogues.length : 0;
      if (!count) throw new Error('هیچ دیالوگ قابل آموزشی از این بازه پیدا نشد.');

      lesson = offsetLessonTimestamps(lesson, startSec);
      const archiveId = makeId();
      const srt = lessonToSrt(lesson);
      const archive = {
        id: archiveId,
        kind: 'media-dialogue',
        extractionMode: hasNativeMedia3() ? 'android-media3-local' : 'server-ffmpeg',
        originalFileName: selectedFile.name,
        originalMimeType: selectedFile.type || '',
        startSec,
        endSec,
        userContext,
        createdAt: Date.now(),
        audioFileName: audioFile.name,
        audioMimeType: audioFile.type || 'audio/m4a',
        audioSize: audioFile.size,
        lesson,
        srt
      };

      let savedLocally = false;
      try {
        savedLocally = await saveArchiveOnDevice(archive, audioFile);
      } catch (storageErr) {
        console.warn('Local media archive save failed', storageErr);
      }

      latestArchive = archive;
      currentDialogueIndex = 0;
      applyLessonToTutor(lesson, selectedFile, startSec, endSec, userContext, archiveId);
      renderDialoguePlayer();

      const gapCount = Array.isArray(lesson.contextGaps) ? lesson.contextGaps.length : 0;
      result.style.display = 'block';
      result.textContent = `${lesson.title || 'Dialogue lesson'}\n${lesson.audioContext || lesson.summary || ''}\n\n${count} خط دیالوگ با Timestamp و Speaker استخراج شد.${gapCount ? `\n${gapCount} نقطه ابهام موقعیتی ثبت شد تا Teacher در صورت نیاز از تو بپرسد.` : ''}${savedLocally ? '\nAudio + JSON + SRT در حافظه دستگاه ذخیره شد.' : '\nTranscript آماده است؛ ذخیره Audio در حافظه دستگاه موفق نشد.'}`;
      exportActions.style.display = 'flex';

      const localMessage = hasNativeMedia3()
        ? 'فیلم کامل از گوشی خارج نشد؛ Media3 فقط Audio بازه انتخابی را روی خود گوشی ساخت.'
        : 'در نسخه وب استخراج Audio روی سرور انجام شد.';
      setStatus(`آماده شد ✅ ${localMessage}\nهیچ تصویر یا فریمی برای AI ارسال نشد.${autoStart ? ' کلاس زنده در حال شروع است…' : ''}`);

      if (autoStart) {
        await new Promise(resolve => setTimeout(resolve, 250));
        if (typeof connectLive === 'function') await connectLive();
      }
    } catch (err) {
      console.error('Media audio pipeline error', err);
      setStatus(`پردازش فیلم/صوت انجام نشد: ${err.message}`, true);
    } finally {
      analyzeBtn.disabled = false;
      analyzeOnlyBtn.disabled = false;
    }
  }

  fileInput.addEventListener('change', () => {
    selectedFile = fileInput.files?.[0] || null;
    latestArchive = null;
    currentDialogueIndex = 0;
    playEndSec = null;
    restoreMicAfterMovie();
    dialogueList.replaceChildren();
    dialoguePlayer.style.display = 'none';
    exportActions.style.display = 'none';

    if (objectUrl) URL.revokeObjectURL(objectUrl);
    objectUrl = null;
    videoPreview.pause();
    audioPreview.pause();
    videoPreview.style.display = 'none';
    audioPreview.style.display = 'none';
    videoPreview.removeAttribute('src');
    audioPreview.removeAttribute('src');

    if (!selectedFile) {
      setStatus('هنوز فایل رسانه‌ای انتخاب نشده.');
      return;
    }

    objectUrl = URL.createObjectURL(selectedFile);
    const isVideo = (selectedFile.type || '').startsWith('video/');
    const preview = isVideo ? videoPreview : audioPreview;
    preview.src = objectUrl;
    preview.style.display = 'block';

    setStatus(hasNativeMedia3()
      ? `${selectedFile.name} انتخاب شد — ${fmtBytes(selectedFile.size)}.\nفیلم روی گوشی می‌ماند. بعد از شروع، Media3 فقط Audio بازه انتخابی را داخل همین گوشی جدا می‌کند.`
      : `${selectedFile.name} انتخاب شد — ${fmtBytes(selectedFile.size)}.\nنسخه وب فعال است؛ برای استخراج کاملاً محلی از APK استفاده کن.`);
  });

  exportJsonBtn.addEventListener('click', () => {
    if (!latestArchive) return;
    const base = latestArchive.originalFileName.replace(/\.[^.]+$/, '') || 'dialogue';
    downloadText(`${base}-dialogue.json`, JSON.stringify(latestArchive, null, 2), 'application/json');
  });

  exportSrtBtn.addEventListener('click', () => {
    if (!latestArchive) return;
    const base = latestArchive.originalFileName.replace(/\.[^.]+$/, '') || 'dialogue';
    downloadText(`${base}-dialogue.srt`, latestArchive.srt || '', 'text/plain');
  });

  mediaBack5Btn.addEventListener('click', () => seekBy(-5));
  mediaForward5Btn.addEventListener('click', () => seekBy(5));
  mediaReplayBtn.addEventListener('click', () => playDialogue(currentDialogueIndex));
  analyzeBtn.addEventListener('click', () => analyzeMedia(true));
  analyzeOnlyBtn.addEventListener('click', () => analyzeMedia(false));

  window.addEventListener('beforeunload', () => {
    for (const [requestId, pending] of nativePending.entries()) {
      try { pending.reject(new Error('Page closed')); } catch {}
      try { window.AndroidMedia?.releaseAudio?.(requestId); } catch {}
    }
    nativePending.clear();
    if (objectUrl) URL.revokeObjectURL(objectUrl);
  });
})();
