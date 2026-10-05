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
    .media-result{margin-top:10px;padding:10px;border-radius:12px;background:#fff;border:1px solid #dbeafe;font-size:12px;line-height:1.7;display:none;white-space:pre-wrap}
    .audio-only-note{margin-top:8px;padding:8px 10px;border-radius:11px;background:#dbeafe;color:#1e3a8a;font-size:11px;line-height:1.6}
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
        <div class="hint tiny">فایل را بده؛ تحلیل آموزشی بر پایه صدای فیلم انجام می‌شود و انسجام مکالمه از ترتیب جواب‌ها، صداها، لحن، مکث‌ها و نشانه‌های شنیداری فهمیده می‌شود.</div>
      </div>
      <span class="library-badge">Audio-only Dialogue</span>
    </div>
    <div class="audio-only-note">تصویر مبنای تحلیل نیست. اگر چیزی فقط از تصویر قابل فهم باشد، AI حق ندارد آن را حدس بزند.</div>
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
      <button id="analyzeMediaBtn" class="primary">تحلیل صوت و شروع آموزش</button>
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
      `AUDIO-ONLY MEDIA DIALOGUE LESSON: ${lesson.title || fileName}`,
      `SOURCE FILE: ${fileName}`,
      `TIME RANGE: ${Math.round(startSec)}s - ${endSec ? Math.round(endSec) + 's' : 'end'}`,
      lesson.summary ? `AUDIO SUMMARY: ${lesson.summary}` : '',
      lesson.audioContext ? `AUDIO-INFERRED CONVERSATION CONTEXT: ${lesson.audioContext}` : '',
      '',
      'STRICT AUDIO-ONLY TEACHING RULES:',
      'Use ONLY the dialogue, meaning, context, phrases, and pronunciation evidence below as lesson content.',
      'The source was analyzed from soundtrack context. Never add visual facts, actions, locations, objects, or character identities that are not supported by the audio.',
      'Preserve conversational coherence. Teach connected turns as an exchange, not as unrelated isolated sentences.',
      'Before a line, use only the supplied prior-turn context needed to understand why the speaker says it.',
      'Teach one original line at a time and keep the original order inside each exchange.',
      'For each line: 1) establish its conversational context, 2) present/listen, 3) explain meaning and intent, 4) ask learner to repeat, 5) correct pronunciation/connected speech, 6) role-play the reply, then continue.',
      'Never invent a missing movie line. If a line is marked [unclear], say it was unclear and skip exact-word correction for that span.',
      'Ask only one question or task at a time.',
      '',
      'COHERENT DIALOGUE TRANSCRIPT:',
      ...lines.map((d, i) => {
        const details = [
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

  function applyLessonToTutor(lesson, file, startSec, endSec) {
    const book = document.getElementById('bookName');
    const lessonName = document.getElementById('lessonName');
    const source = document.getElementById('lessonSource');
    if (book) book.value = `🎧 ${file.name.replace(/\.[^.]+$/, '')}`;
    if (lessonName) lessonName.value = lesson.title || `Audio Dialogue ${Math.round(startSec / 60)}-${endSec ? Math.round(endSec / 60) : 'end'} min`;
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
    setStatus(`آپلود مستقیم مرورگر در دسترس نبود؛ فایل از مسیر امن Render به Gemini فرستاده می‌شود…\n${fmtBytes(file.size)}`);
    const r = await fetch(`/api/media-upload-proxy?slot=${encodeURIComponent(keySlot)}`, {
      method: 'POST',
      headers: {
        'Content-Type': file.type || 'video/mp4',
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

  async function uploadToGemini(file) {
    setStatus(`در حال آماده‌سازی فایل برای تحلیل صوت‌محور: ${file.name} (${fmtBytes(file.size)})…`);
    const startResp = await fetch('/api/media-upload-start', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ fileName: file.name, mimeType: file.type || 'video/mp4', size: file.size })
    });
    const startData = await startResp.json().catch(() => ({}));
    if (!startResp.ok) throw new Error(startData.error || `Upload start HTTP ${startResp.status}`);

    setStatus(`در حال آپلود فایل… بعد از آپلود، تحلیل فقط روی صدای مکالمه انجام می‌شود.\n${fmtBytes(file.size)} — کلید Gemini #${startData.keySlot}`);
    try {
      const info = await uploadDirect(startData.uploadUrl, file);
      return { info, keySlot: startData.keySlot };
    } catch (directErr) {
      console.warn('Direct Gemini upload failed; using Render proxy fallback', directErr);
      return uploadViaRender(file, startData.keySlot);
    }
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
      setStatus('آپلود کامل شد. حالت Audio-only فعال است؛ در حال فهم مکالمه از روی صدا، ترتیب جواب‌ها، لحن و نشانه‌های شنیداری…');

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
      result.textContent = `${lesson.title || 'Audio dialogue lesson'}\n${lesson.audioContext || lesson.summary || ''}\n\n${count} خط دیالوگ پیوسته استخراج شد و به منبع درس اضافه شد.`;
      setStatus(`آماده شد ✅ ${count} خط دیالوگ با حفظ ارتباط مکالمه استخراج شد.${autoStart ? ' کلاس زنده در حال شروع است…' : ' برای شروع، Teacher را اجرا کن.'}`);

      if (autoStart) {
        await new Promise(r => setTimeout(r, 250));
        if (typeof connectLive === 'function') await connectLive();
      }
    } catch (err) {
      console.error('Media learning error', err);
      setStatus(`تحلیل صوت فیلم/کلیپ انجام نشد: ${err.message}`, true);
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
    setStatus(`${selectedFile.name} انتخاب شد — ${fmtBytes(selectedFile.size)}.\nتحلیل آموزشی از صدای فایل انجام می‌شود؛ بازه موردنظر را مشخص کن.`);
  });

  analyzeBtn.addEventListener('click', () => analyzeMedia(true));
  analyzeOnlyBtn.addEventListener('click', () => analyzeMedia(false));
})();
