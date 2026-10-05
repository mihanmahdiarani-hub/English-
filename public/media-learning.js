(() => {
  const style = document.createElement('style');
  style.textContent = `
    .media-box{margin-top:16px;padding:14px;border:1px solid #dbeafe;border-radius:16px;background:#eff6ff}
    .media-head{display:flex;justify-content:space-between;gap:12px;align-items:flex-start}
    .media-title{font-size:14px;font-weight:800;color:#1e3a8a}
    .media-grid{display:grid;grid-template-columns:1fr 110px 110px;gap:8px;align-items:end;margin-top:10px}
    .media-preview{width:100%;max-height:280px;background:#111827;border-radius:14px;margin-top:10px;display:none}
    .media-actions{display:flex;flex-wrap:wrap;gap:8px;margin-top:10px}
    .media-status{font-size:12px;line-height:1.7;color:#475569;margin-top:8px;white-space:pre-wrap}
    .media-result{margin-top:10px;padding:10px;border-radius:12px;background:#fff;border:1px solid #dbeafe;font-size:12px;line-height:1.7;display:none}
    @media(max-width:720px){.media-grid{grid-template-columns:1fr 1fr}.media-grid .media-file{grid-column:1/-1}.media-head{flex-direction:column}}
  `;
  document.head.appendChild(style);

  const sourceCard = document.querySelector('.source-card');
  if (!sourceCard) return;

  const box = document.createElement('div');
  box.className = 'media-box';
  box.innerHTML = `
    <div class="media-head">
      <div>
        <div class="media-title">🎬 آموزش انگلیسی با فیلم / کلیپ</div>
        <div class="hint tiny">فیلم، کلیپ یا فایل صوتی را بده؛ Gemini دیالوگ‌ها را استخراج می‌کند و همان‌ها را تبدیل به درس مکالمه، Shadowing و Role-play می‌کند.</div>
      </div>
      <span class="library-badge">Dialogue Mode</span>
    </div>
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
    <div class="media-actions">
      <button id="analyzeMediaBtn" class="primary">تحلیل دیالوگ‌ها و شروع آموزش</button>
      <button id="analyzeMediaOnlyBtn" class="secondary">فقط استخراج دیالوگ‌ها</button>
    </div>
    <div id="mediaLearningStatus" class="media-status">هنوز فایل رسانه‌ای انتخاب نشده.</div>
    <div id="mediaLearningResult" class="media-result"></div>
  `;

  const methodBox = sourceCard.querySelector('.method-box');
  if (methodBox) sourceCard.insertBefore(box, methodBox);
  else sourceCard.appendChild(box);

  const fileInput = document.getElementById('mediaLearningFile');
  const videoPreview = document.getElementById('mediaLearningPreview');
  const audioPreview = document.getElementById('mediaLearningAudioPreview');
  const status = document.getElementById('mediaLearningStatus');
  const result = document.getElementById('mediaLearningResult');
  const analyzeBtn = document.getElementById('analyzeMediaBtn');
  const analyzeOnlyBtn = document.getElementById('analyzeMediaOnlyBtn');
  let selectedFile = null;
  let objectUrl = null;

  function setStatus(text, isError = false) {
    status.textContent = text;
    status.style.color = isError ? '#991b1b' : '#475569';
  }

  function fmtBytes(n) {
    const units = ['B','KB','MB','GB'];
    let i = 0, v = Number(n || 0);
    while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
    return `${v.toFixed(i ? 1 : 0)} ${units[i]}`;
  }

  function currentLanguage() {
    try {
      return state?.conversationLanguage === 'persian' ? 'persian' : 'english';
    } catch {
      const active = document.querySelector('#languagePicker button.active');
      return active?.dataset?.language === 'persian' ? 'persian' : 'english';
    }
  }

  function dialogueSource(lesson, fileName, startSec, endSec) {
    const lines = Array.isArray(lesson.dialogues) ? lesson.dialogues : [];
    const phrases = Array.isArray(lesson.phrases) ? lesson.phrases : [];
    const pronunciation = Array.isArray(lesson.pronunciation) ? lesson.pronunciation : [];
    const flow = Array.isArray(lesson.lessonFlow) ? lesson.lessonFlow : [];

    return [
      `MEDIA DIALOGUE LESSON: ${lesson.title || fileName}`,
      `SOURCE FILE: ${fileName}`,
      `TIME RANGE: ${Math.round(startSec)}s - ${endSec ? Math.round(endSec) + 's' : 'end'}`,
      lesson.summary ? `SCENE SUMMARY: ${lesson.summary}` : '',
      '',
      'STRICT MEDIA TEACHING RULES:',
      'Use ONLY the dialogue and phrases below as lesson content.',
      'Teach one original line at a time, in the exact order shown.',
      'For each line: 1) present/listen, 2) explain meaning in context, 3) ask learner to repeat, 4) correct pronunciation/connected speech, 5) role-play the exchange, then continue.',
      'Never invent a missing movie line. If a line is marked [unclear], say it was unclear and skip exact-word correction for that part.',
      'Ask only one question or task at a time.',
      '',
      'DIALOGUE TRANSCRIPT:',
      ...lines.map((d, i) => `${i + 1}. [${d.start || ''}${d.end ? `-${d.end}` : ''}] ${d.speaker || 'Speaker'}: ${d.text || ''}${d.meaning ? `\n   Meaning: ${d.meaning}` : ''}`),
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

  function applyLessonToTutor(lesson, file, startSec, endSec) {
    const book = document.getElementById('bookName');
    const lessonName = document.getElementById('lessonName');
    const source = document.getElementById('lessonSource');
    if (book) book.value = `🎬 ${file.name.replace(/\.[^.]+$/, '')}`;
    if (lessonName) lessonName.value = lesson.title || `Dialogue ${Math.round(startSec / 60)}-${endSec ? Math.round(endSec / 60) : 'end'} min`;
    if (source) source.value = dialogueSource(lesson, file.name, startSec, endSec);

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
  }

  async function uploadToGemini(file) {
    setStatus(`در حال ساخت مسیر امن آپلود برای ${file.name} (${fmtBytes(file.size)})…`);
    const startResp = await fetch('/api/media-upload-start', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ fileName: file.name, mimeType: file.type || 'video/mp4', size: file.size })
    });
    const startData = await startResp.json().catch(() => ({}));
    if (!startResp.ok) throw new Error(startData.error || `Upload start HTTP ${startResp.status}`);

    setStatus(`در حال آپلود مستقیم فایل به Gemini…\n${fmtBytes(file.size)} — کلید Gemini #${startData.keySlot}`);
    const uploadResp = await fetch(startData.uploadUrl, {
      method: 'POST',
      headers: {
        'Content-Length': String(file.size),
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
    return { info, keySlot: startData.keySlot };
  }

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
    if (endSec && endSec <= startSec) {
      setStatus('دقیقه پایان باید بعد از دقیقه شروع باشد.', true);
      return;
    }

    analyzeBtn.disabled = true;
    analyzeOnlyBtn.disabled = true;
    result.style.display = 'none';
    result.textContent = '';

    try {
      if (autoStart) {
        try { if (typeof state !== 'undefined' && state.ws) stopLive(); } catch {}
      }

      const uploaded = await uploadToGemini(selectedFile);
      setStatus('آپلود کامل شد. Gemini در حال پردازش صدا/ویدیو و استخراج دیالوگ‌هاست…');

      const r = await fetch('/api/analyze-media', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          originalFileName: selectedFile.name,
          fileNameOnGemini: uploaded.info.name,
          fileUri: uploaded.info.uri,
          mimeType: uploaded.info.mimeType || selectedFile.type,
          keySlot: uploaded.keySlot,
          language: currentLanguage(),
          startSec,
          endSec
        })
      });
      const data = await r.json().catch(() => ({}));
      if (!r.ok) throw new Error(data.error || `Analyze HTTP ${r.status}`);
      const lesson = data.lesson || {};
      const count = Array.isArray(lesson.dialogues) ? lesson.dialogues.length : 0;
      if (!count) throw new Error('هیچ دیالوگ قابل آموزشی از این بازه پیدا نشد.');

      applyLessonToTutor(lesson, selectedFile, startSec, endSec);
      result.style.display = 'block';
      result.textContent = `${lesson.title || 'Dialogue lesson'}\n${lesson.summary || ''}\n\n${count} خط دیالوگ استخراج شد و به منبع درس اضافه شد.`;
      setStatus(`آماده شد ✅ ${count} خط دیالوگ استخراج شد.${autoStart ? ' کلاس زنده در حال شروع است…' : ' برای شروع، Teacher را اجرا کن.'}`);

      if (autoStart) {
        await new Promise(r => setTimeout(r, 250));
        if (typeof connectLive === 'function') await connectLive();
      }
    } catch (err) {
      console.error('Media learning error', err);
      setStatus(`تحلیل فیلم/کلیپ انجام نشد: ${err.message}`, true);
    } finally {
      analyzeBtn.disabled = false;
      analyzeOnlyBtn.disabled = false;
    }
  }

  fileInput.addEventListener('change', () => {
    selectedFile = fileInput.files?.[0] || null;
    if (objectUrl) URL.revokeObjectURL(objectUrl);
    objectUrl = null;
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
    setStatus(`${selectedFile.name} انتخاب شد — ${fmtBytes(selectedFile.size)}.\nبازه موردنظر را مشخص کن و تحلیل را بزن.`);
  });

  analyzeBtn.addEventListener('click', () => analyzeMedia(true));
  analyzeOnlyBtn.addEventListener('click', () => analyzeMedia(false));
})();
