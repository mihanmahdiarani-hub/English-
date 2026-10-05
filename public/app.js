const $ = (id) => document.getElementById(id);

const state = {
  mode: 'teacher',
  ws: null,
  mediaStream: null,
  audioCtx: null,
  sourceNode: null,
  processor: null,
  muted: false,
  ready: false,
  playCtx: null,
  playCursor: 0,
  scheduledSources: [],
  transcriptUser: '',
  transcriptAi: '',
  pdfDoc: null
};

const TEACHER_RULES = `You are a strict, source-grounded English teacher.
The lesson source below is the ONLY curriculum authority in Teacher and Shadowing modes.
Never invent, reorder, skip, or introduce future curriculum content.
If requested material is not present, say in Persian: «این بخش را در منبع پیدا نکردم؛ از خودم ادامه نمی‌دهم.»
Teach interactively: give a short Persian explanation only when needed, then ask ONE English question and wait.
Correct important grammar, vocabulary, word order, and pronunciation mistakes briefly. Ask the learner to repeat when useful.
Do not lecture. Prioritize speaking and sentence building.
Any extra example must be explicitly introduced in Persian as «مثال اضافه برای تمرین».
If the learner says «فقط کتاب», create no extra examples.
In Free Talk mode, converse naturally while preferring grammar and vocabulary already studied.
In Shadowing mode, say one short sentence, wait for repetition, give concise feedback, then continue.
Speak a little slower than native speed unless the learner asks otherwise.
Use Persian only for short explanations; speaking practice should mainly be English.`;

function addMessage(type, text) {
  if (!text || !String(text).trim()) return;
  const div = document.createElement('div');
  div.className = `message ${type}`;
  div.textContent = String(text).trim();
  $('chat').appendChild(div);
  $('chat').scrollTop = $('chat').scrollHeight;
}

function setStatus(kind, text) {
  $('statusPill').className = `pill ${kind}`;
  $('statusPill').textContent = text;
}

function setControls(connected) {
  $('connectBtn').disabled = connected;
  $('muteBtn').disabled = !connected;
  $('stopBtn').disabled = !connected;
}

function loadProgress() {
  try {
    const p = JSON.parse(localStorage.getItem('englishTutorProgress') || '{}');
    ['bookName','lessonName','lastExercise','nextStart','mistakes','reviewItems','lessonSource'].forEach(k => {
      if (p[k]) $(k).value = p[k];
    });
  } catch {}
}

function saveProgress() {
  const p = {};
  ['bookName','lessonName','lastExercise','nextStart','mistakes','reviewItems','lessonSource'].forEach(k => p[k] = $(k).value);
  localStorage.setItem('englishTutorProgress', JSON.stringify(p));
  $('progressSaved').textContent = `ذخیره شد — ${new Date().toLocaleTimeString('fa-IR')}`;
}

$('saveProgressBtn').onclick = saveProgress;

$('sampleBtn').onclick = () => {
  $('bookName').value = 'American English File 2 (sample)';
  $('lessonName').value = 'Sample: Present Simple';
  $('lessonSource').value = `Lesson focus: Present simple for routines.\nVocabulary: get up, have breakfast, go to work, start work, finish work, go home.\nGrammar examples:\nI get up at seven.\nShe starts work at nine.\nDo you have breakfast at home?\nWhat time do you finish work?\nSpeaking task: Ask and answer about a typical weekday.\nCorrection focus: third-person -s and do/does questions.`;
};

document.querySelectorAll('#modePicker button').forEach(btn => {
  btn.onclick = () => {
    document.querySelectorAll('#modePicker button').forEach(b => b.classList.remove('active'));
    btn.classList.add('active');
    state.mode = btn.dataset.mode;
  };
});

$('pdfFile').onchange = async (e) => {
  const file = e.target.files?.[0];
  if (!file) return;
  $('pdfStatus').textContent = `فایل انتخاب شد: ${file.name}`;
  try {
    const pdfjs = await import('https://cdn.jsdelivr.net/npm/pdfjs-dist@4.10.38/build/pdf.min.mjs');
    pdfjs.GlobalWorkerOptions.workerSrc = 'https://cdn.jsdelivr.net/npm/pdfjs-dist@4.10.38/build/pdf.worker.min.mjs';
    const bytes = new Uint8Array(await file.arrayBuffer());
    state.pdfDoc = await pdfjs.getDocument({ data: bytes }).promise;
    $('pageEnd').value = Math.min(2, state.pdfDoc.numPages);
    $('pdfStatus').textContent = `${file.name} — ${state.pdfDoc.numPages} صفحه آماده استخراج`;
  } catch (err) {
    $('pdfStatus').textContent = `خطا در خواندن PDF: ${err.message}`;
  }
};

$('extractPdfBtn').onclick = async () => {
  if (!state.pdfDoc) {
    $('pdfStatus').textContent = 'اول PDF را انتخاب کن.';
    return;
  }
  const start = Math.max(1, Number($('pageStart').value || 1));
  const end = Math.min(state.pdfDoc.numPages, Number($('pageEnd').value || start));
  if (end < start) {
    $('pdfStatus').textContent = 'بازه صفحه درست نیست.';
    return;
  }
  let out = '';
  for (let p = start; p <= end; p++) {
    $('pdfStatus').textContent = `در حال استخراج صفحه ${p} از ${end}...`;
    const page = await state.pdfDoc.getPage(p);
    const content = await page.getTextContent();
    out += `\n\n--- PAGE ${p} ---\n` + content.items.map(i => i.str).join(' ');
  }
  $('lessonSource').value = out.trim();
  $('pdfStatus').textContent = `صفحه‌های ${start} تا ${end} استخراج شد.`;
};

function buildInstruction() {
  const source = $('lessonSource').value.trim();
  const extra = $('extraRule').value.trim();
  const book = $('bookName').value.trim() || 'Unknown book';
  const lesson = $('lessonName').value.trim() || 'Unknown lesson';
  const progress = `Last exercise: ${$('lastExercise').value || '-'}; Next start: ${$('nextStart').value || '-'}; Important mistakes: ${$('mistakes').value || '-'}; Review items: ${$('reviewItems').value || '-'}`;
  return `${TEACHER_RULES}\n\nCURRENT MODE: ${state.mode.toUpperCase()}\nBOOK: ${book}\nLESSON: ${lesson}\nPROGRESS: ${progress}\n${extra ? `SESSION RULE: ${extra}\n` : ''}\nLESSON SOURCE START\n${source}\nLESSON SOURCE END\n\nStart the session now. In Teacher mode, briefly say in Persian where we are, then ask ONE question from this source.`;
}

function base64FromBytes(bytes) {
  let binary = '';
  const chunk = 0x8000;
  for (let i = 0; i < bytes.length; i += chunk) {
    binary += String.fromCharCode(...bytes.subarray(i, i + chunk));
  }
  return btoa(binary);
}

function bytesFromBase64(b64) {
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

function downsampleTo16k(float32, inputRate) {
  if (inputRate === 16000) return float32;
  const ratio = inputRate / 16000;
  const len = Math.round(float32.length / ratio);
  const out = new Float32Array(len);
  let offset = 0;
  for (let i = 0; i < len; i++) {
    const next = Math.round((i + 1) * ratio);
    let sum = 0, count = 0;
    for (let j = offset; j < next && j < float32.length; j++) {
      sum += float32[j]; count++;
    }
    out[i] = count ? sum / count : 0;
    offset = next;
  }
  return out;
}

function floatToPCM16Bytes(float32) {
  const buf = new ArrayBuffer(float32.length * 2);
  const view = new DataView(buf);
  for (let i = 0; i < float32.length; i++) {
    const s = Math.max(-1, Math.min(1, float32[i]));
    view.setInt16(i * 2, s < 0 ? s * 0x8000 : s * 0x7fff, true);
  }
  return new Uint8Array(buf);
}

async function startMic() {
  state.mediaStream = await navigator.mediaDevices.getUserMedia({
    audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true },
    video: false
  });
  state.audioCtx = new (window.AudioContext || window.webkitAudioContext)();
  await state.audioCtx.resume();
  state.sourceNode = state.audioCtx.createMediaStreamSource(state.mediaStream);
  state.processor = state.audioCtx.createScriptProcessor(2048, 1, 1);
  const zeroGain = state.audioCtx.createGain();
  zeroGain.gain.value = 0;
  state.sourceNode.connect(state.processor);
  state.processor.connect(zeroGain);
  zeroGain.connect(state.audioCtx.destination);
  state.processor.onaudioprocess = (e) => {
    if (!state.ready || state.muted || !state.ws || state.ws.readyState !== WebSocket.OPEN) return;
    const samples = e.inputBuffer.getChannelData(0);
    let peak = 0;
    for (const s of samples) peak = Math.max(peak, Math.abs(s));
    $('meter').style.width = `${Math.min(100, peak * 220)}%`;
    const pcm = floatToPCM16Bytes(downsampleTo16k(samples, state.audioCtx.sampleRate));
    state.ws.send(JSON.stringify({
      realtimeInput: {
        audio: { data: base64FromBytes(pcm), mimeType: 'audio/pcm;rate=16000' }
      }
    }));
  };
}

function stopPlayback() {
  state.scheduledSources.forEach(s => { try { s.stop(); } catch {} });
  state.scheduledSources = [];
  if (state.playCtx) state.playCursor = state.playCtx.currentTime;
}

async function playPcm24k(base64) {
  if (!state.playCtx) state.playCtx = new (window.AudioContext || window.webkitAudioContext)({ sampleRate: 24000 });
  if (state.playCtx.state === 'suspended') await state.playCtx.resume();
  const bytes = bytesFromBase64(base64);
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  const n = Math.floor(bytes.byteLength / 2);
  const floats = new Float32Array(n);
  for (let i = 0; i < n; i++) floats[i] = view.getInt16(i * 2, true) / 32768;
  const buffer = state.playCtx.createBuffer(1, n, 24000);
  buffer.getChannelData(0).set(floats);
  const src = state.playCtx.createBufferSource();
  src.buffer = buffer;
  src.connect(state.playCtx.destination);
  const now = state.playCtx.currentTime;
  const start = Math.max(now + 0.02, state.playCursor || 0);
  src.start(start);
  state.playCursor = start + buffer.duration;
  state.scheduledSources.push(src);
  src.onended = () => state.scheduledSources = state.scheduledSources.filter(x => x !== src);
}

function handleServerMessage(resp) {
  if (resp.setupComplete) {
    state.ready = true;
    setStatus('online', 'کلاس زنده');
    addMessage('system', 'اتصال واقعی به Gemini Live برقرار شد. کلاس شروع می‌شود…');
    state.ws.send(JSON.stringify({
      clientContent: {
        turns: [{ role: 'user', parts: [{ text: 'Begin the lesson now according to the system instruction. Ask only one question at a time.' }] }],
        turnComplete: true
      }
    }));
    return;
  }

  const sc = resp.serverContent;
  if (!sc) return;
  if (sc.interrupted) stopPlayback();
  if (sc.inputTranscription?.text) state.transcriptUser += sc.inputTranscription.text;
  if (sc.outputTranscription?.text) state.transcriptAi += sc.outputTranscription.text;

  if (sc.modelTurn?.parts) {
    for (const part of sc.modelTurn.parts) {
      if (part.inlineData?.data) playPcm24k(part.inlineData.data);
    }
  }

  if (sc.turnComplete) {
    if (state.transcriptUser.trim()) addMessage('user', state.transcriptUser);
    if (state.transcriptAi.trim()) addMessage('ai', state.transcriptAi);
    state.transcriptUser = '';
    state.transcriptAi = '';
  }
}

async function connectLive() {
  if (!$('lessonSource').value.trim() && state.mode !== 'free') {
    addMessage('error', 'برای Teacher/Shadowing ابتدا منبع درس را وارد کن.');
    return;
  }

  try {
    setStatus('connecting', 'در حال اتصال…');
    $('connectBtn').disabled = true;
    await startMic();

    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const ws = new WebSocket(`${proto}//${location.host}/live`);
    state.ws = ws;

    ws.onopen = () => {
      addMessage('system', 'سرور وصل شد؛ در حال اتصال امن به Gemini…');
      ws.send(JSON.stringify({
        setup: {
          model: 'models/gemini-3.8-live',
          generationConfig: { responseModalities: ['AUDIO'] },
          systemInstruction: { parts: [{ text: buildInstruction() }] },
          inputAudioTranscription: {},
          outputAudioTranscription: {}
        }
      }));
    };

    ws.onmessage = (e) => {
      try { handleServerMessage(JSON.parse(e.data)); }
      catch (err) { console.error('Live message parse error', err); }
    };

    ws.onerror = () => {
      addMessage('error', 'خطای WebSocket بین مرورگر و سرور رخ داد.');
    };

    ws.onclose = (e) => {
      state.ready = false;
      setStatus('offline', 'آفلاین');
      setControls(false);
      $('connectBtn').disabled = false;
      if (e.code !== 1000) {
        addMessage('error', `اتصال بسته شد (کد ${e.code})${e.reason ? `: ${e.reason}` : ''}`);
      } else if (e.reason) {
        addMessage('system', `اتصال بسته شد: ${e.reason}`);
      }
    };

    setControls(true);
  } catch (err) {
    cleanupMedia();
    setStatus('offline', 'آفلاین');
    setControls(false);
    $('connectBtn').disabled = false;
    addMessage('error', err.message || 'خطا در شروع کلاس زنده');
  }
}

function cleanupMedia() {
  state.ready = false;
  stopPlayback();
  if (state.processor) { try { state.processor.disconnect(); } catch {} state.processor = null; }
  if (state.sourceNode) { try { state.sourceNode.disconnect(); } catch {} state.sourceNode = null; }
  if (state.mediaStream) { state.mediaStream.getTracks().forEach(t => t.stop()); state.mediaStream = null; }
  if (state.audioCtx) { try { state.audioCtx.close(); } catch {} state.audioCtx = null; }
}

function stopLive() {
  cleanupMedia();
  if (state.ws) {
    try { state.ws.close(1000, 'User ended session'); } catch {}
    state.ws = null;
  }
  setStatus('offline', 'آفلاین');
  setControls(false);
  saveProgress();
}

$('connectBtn').onclick = connectLive;
$('stopBtn').onclick = stopLive;
$('muteBtn').onclick = () => {
  state.muted = !state.muted;
  $('muteBtn').textContent = state.muted ? 'وصل میکروفن' : 'قطع میکروفن';
};

window.addEventListener('beforeunload', () => {
  if (state.ws) try { state.ws.close(); } catch {}
});

loadProgress();
if ('serviceWorker' in navigator) navigator.serviceWorker.register('/sw.js').catch(() => {});
