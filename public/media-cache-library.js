(() => {
  const DB_NAME = 'englishTutorMediaLibraryV1';
  const DB_VERSION = 1;
  const LESSON_STORE = 'lessons';
  const AUDIO_STORE = 'audio';

  const mediaBox = document.querySelector('.media-box');
  const fileInput = document.getElementById('mediaLearningFile');
  const startInput = document.getElementById('mediaStartMinute');
  const endInput = document.getElementById('mediaEndMinute');
  const contextInput = document.getElementById('mediaSceneContext');
  const status = document.getElementById('mediaLearningStatus');
  const result = document.getElementById('mediaLearningResult');
  const videoPreview = document.getElementById('mediaLearningPreview');
  const audioPreview = document.getElementById('mediaLearningAudioPreview');
  const dialoguePlayer = document.getElementById('dialoguePlayer');
  const dialogueList = document.getElementById('dialogueList');
  if (!mediaBox || !fileInput || !status || !dialoguePlayer || !dialogueList) return;

  const originalMediaPlayer = window.EnglishTutorMediaPlayer || null;
  let cache = [];
  let cachedArchive = null;
  let cachedAudioUrl = null;
  let cachedDialogueIndex = 0;
  let cachedPlayEnd = null;
  let cachePlaybackMode = null;

  const style = document.createElement('style');
  style.textContent = `
    .media-cache-library{margin-top:12px;padding:12px;border:1px solid rgba(139,92,246,.22);border-radius:16px;background:rgba(139,92,246,.05)}
    .media-cache-head{display:flex;align-items:center;justify-content:space-between;gap:10px;margin-bottom:8px}
    .media-cache-head b{font-size:12px;color:#f5f3ff}.media-cache-head span{font-size:10px;color:var(--muted)}
    .media-cache-list{display:grid;gap:7px}.media-cache-empty{font-size:10px;color:var(--muted);padding:6px 0}
    .media-cache-row{display:grid;grid-template-columns:1fr auto;gap:8px;align-items:center;padding:9px 10px;border:1px solid var(--line);border-radius:12px;background:rgba(255,255,255,.025)}
    .media-cache-title{font-size:11px;font-weight:800;color:#f4f4f5;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.media-cache-meta{font-size:9px;color:var(--muted);margin-top:3px;line-height:1.5}
    .media-cache-actions{display:flex;gap:5px}.media-cache-actions button{padding:6px 8px;font-size:9px;min-height:30px}.media-cache-open{background:rgba(139,92,246,.16)!important;color:#ddd6fe!important;border-color:rgba(139,92,246,.28)!important}
    .media-cache-hit{margin-top:8px;padding:8px 10px;border-radius:11px;border:1px solid rgba(34,197,94,.24);background:rgba(34,197,94,.08);color:#bbf7d0;font-size:10px;line-height:1.6}
    @media(max-width:720px){.media-cache-row{grid-template-columns:1fr}.media-cache-actions{justify-content:flex-start}}
  `;
  document.head.appendChild(style);

  const panel = document.createElement('div');
  panel.className = 'media-cache-library';
  panel.innerHTML = `
    <div class="media-cache-head">
      <div><b>درس‌های آماده فیلم</b><span> متن + Audio روی همین دستگاه</span></div>
      <button id="mediaCacheRefresh" class="ghost">↻</button>
    </div>
    <div id="mediaCacheHit" class="media-cache-hit" style="display:none"></div>
    <div id="mediaCacheList" class="media-cache-list"></div>
  `;
  const mediaGrid = mediaBox.querySelector('.media-grid');
  if (mediaGrid) mediaGrid.insertAdjacentElement('afterend', panel);
  else mediaBox.appendChild(panel);

  const cacheList = panel.querySelector('#mediaCacheList');
  const cacheHit = panel.querySelector('#mediaCacheHit');
  panel.querySelector('#mediaCacheRefresh').onclick = () => refreshCache();

  function openDb() {
    return new Promise((resolve, reject) => {
      const req = indexedDB.open(DB_NAME, DB_VERSION);
      req.onupgradeneeded = () => {
        const db = req.result;
        if (!db.objectStoreNames.contains(LESSON_STORE)) db.createObjectStore(LESSON_STORE, { keyPath: 'id' });
        if (!db.objectStoreNames.contains(AUDIO_STORE)) db.createObjectStore(AUDIO_STORE);
      };
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
  }

  async function allLessons() {
    const db = await openDb();
    try {
      return await new Promise((resolve, reject) => {
        const req = db.transaction(LESSON_STORE, 'readonly').objectStore(LESSON_STORE).getAll();
        req.onsuccess = () => resolve(Array.isArray(req.result) ? req.result : []);
        req.onerror = () => reject(req.error);
      });
    } finally { db.close(); }
  }

  async function audioFor(id) {
    const db = await openDb();
    try {
      return await new Promise((resolve, reject) => {
        const req = db.transaction(AUDIO_STORE, 'readonly').objectStore(AUDIO_STORE).get(id);
        req.onsuccess = () => resolve(req.result || null);
        req.onerror = () => reject(req.error);
      });
    } finally { db.close(); }
  }

  async function deleteLesson(id) {
    const db = await openDb();
    try {
      await new Promise((resolve, reject) => {
        const tx = db.transaction([LESSON_STORE, AUDIO_STORE], 'readwrite');
        tx.objectStore(LESSON_STORE).delete(id);
        tx.objectStore(AUDIO_STORE).delete(id);
        tx.oncomplete = resolve;
        tx.onerror = () => reject(tx.error);
        tx.onabort = () => reject(tx.error || new Error('delete aborted'));
      });
    } finally { db.close(); }
  }

  function fmtTime(sec) {
    const n = Math.max(0, Number(sec || 0));
    const h = Math.floor(n / 3600), m = Math.floor((n % 3600) / 60), s = Math.floor(n % 60);
    return h ? `${h}:${String(m).padStart(2,'0')}:${String(s).padStart(2,'0')}` : `${m}:${String(s).padStart(2,'0')}`;
  }

  function parseTime(value) {
    const parts = String(value || '').trim().replace(',', '.').split(':').map(Number);
    if (!parts.length || parts.some(Number.isNaN)) return null;
    if (parts.length === 3) return parts[0] * 3600 + parts[1] * 60 + parts[2];
    if (parts.length === 2) return parts[0] * 60 + parts[1];
    return parts[0];
  }

  function exactMatch() {
    const file = fileInput.files?.[0];
    if (!file) return null;
    const startSec = Math.round(Math.max(0, Number(startInput?.value || 0)) * 60);
    const endRaw = Number(endInput?.value || 0);
    const endSec = endRaw > 0 ? Math.round(endRaw * 60) : null;
    return cache.find(x => x?.kind === 'media-dialogue' && x.originalFileName === file.name && Number(x.startSec || 0) === startSec && (x.endSec == null ? null : Number(x.endSec)) === endSec) || null;
  }

  function updateHit() {
    const hit = exactMatch();
    if (!hit) {
      cacheHit.style.display = 'none';
      cacheHit.textContent = '';
      return;
    }
    const count = Array.isArray(hit.lesson?.dialogues) ? hit.lesson.dialogues.length : 0;
    cacheHit.style.display = 'block';
    cacheHit.textContent = `✅ این بازه قبلاً آماده شده (${count} دیالوگ). با زدن «آماده‌سازی» دوباره Audio/Gemini اجرا نمی‌شود و همین نسخه ذخیره‌شده باز می‌شود.`;
  }

  function renderCacheList() {
    cacheList.replaceChildren();
    const lessons = cache.filter(x => x?.kind === 'media-dialogue').sort((a,b) => Number(b.createdAt || 0) - Number(a.createdAt || 0));
    if (!lessons.length) {
      const empty = document.createElement('div');
      empty.className = 'media-cache-empty';
      empty.textContent = 'هنوز درس آماده‌ای ذخیره نشده. اولین بار که فیلم پردازش شود، متن و Audio اینجا می‌ماند.';
      cacheList.appendChild(empty);
      return;
    }
    lessons.slice(0, 20).forEach(archive => {
      const row = document.createElement('div');
      row.className = 'media-cache-row';
      const info = document.createElement('div');
      const title = document.createElement('div');
      title.className = 'media-cache-title';
      title.textContent = archive.lesson?.title || archive.originalFileName || 'درس فیلم';
      const meta = document.createElement('div');
      meta.className = 'media-cache-meta';
      const count = Array.isArray(archive.lesson?.dialogues) ? archive.lesson.dialogues.length : 0;
      meta.textContent = `${archive.originalFileName || ''} • ${fmtTime(archive.startSec)} تا ${archive.endSec ? fmtTime(archive.endSec) : 'پایان'} • ${count} دیالوگ`;
      info.append(title, meta);
      const actions = document.createElement('div');
      actions.className = 'media-cache-actions';
      const open = document.createElement('button');
      open.className = 'ghost media-cache-open';
      open.textContent = 'باز کردن';
      open.onclick = () => activateArchive(archive, false);
      const del = document.createElement('button');
      del.className = 'ghost';
      del.textContent = 'حذف';
      del.onclick = async () => {
        await deleteLesson(archive.id);
        if (cachedArchive?.id === archive.id) clearCachedPlayback();
        await refreshCache();
      };
      actions.append(open, del);
      row.append(info, actions);
      cacheList.appendChild(row);
    });
  }

  async function refreshCache() {
    try {
      cache = await allLessons();
      renderCacheList();
      updateHit();
    } catch (err) {
      console.warn('Media cache list failed', err);
      cacheList.textContent = 'خواندن درس‌های ذخیره‌شده انجام نشد.';
    }
  }

  function sourceText(archive, contextOverride = '') {
    const lesson = archive.lesson || {};
    const lines = Array.isArray(lesson.dialogues) ? lesson.dialogues : [];
    const gaps = Array.isArray(lesson.contextGaps) ? lesson.contextGaps : [];
    const phrases = Array.isArray(lesson.phrases) ? lesson.phrases : [];
    const pronunciation = Array.isArray(lesson.pronunciation) ? lesson.pronunciation : [];
    const flow = Array.isArray(lesson.lessonFlow) ? lesson.lessonFlow : [];
    const userContext = contextOverride || archive.userContext || '';
    return [
      `TRUE AUDIO-ONLY MEDIA DIALOGUE LESSON: ${lesson.title || archive.originalFileName || 'Movie lesson'}`,
      `SOURCE FILE: ${archive.originalFileName || ''}`,
      `ORIGINAL TIME RANGE: ${fmtTime(archive.startSec)} - ${archive.endSec ? fmtTime(archive.endSec) : 'end'}`,
      userContext ? `LEARNER-PROVIDED SITUATION CONTEXT: ${userContext}` : 'LEARNER-PROVIDED SITUATION CONTEXT: none',
      lesson.summary ? `SUMMARY: ${lesson.summary}` : '',
      lesson.audioContext ? `AUDIO-INFERRED CONVERSATION CONTEXT: ${lesson.audioContext}` : '',
      '',
      'STRICT AUDIO-ONLY TEACHING RULES:',
      'Use ONLY the transcript, audio-derived context, and learner-provided context below as source evidence.',
      'No video frames were sent to the analysis model. Never invent visual facts, locations, gestures, actions, objects, or identities.',
      'Preserve conversational coherence and teach connected turns as an exchange, not as unrelated sentences.',
      'If a missing situation detail materially changes the meaning, ask the learner ONE short clarification question instead of guessing.',
      'Teach one original line at a time, keeping the original order inside each exchange.',
      'For each line: establish context → present the exact line → explain meaning/intent → learner repeats → pronunciation feedback → role-play reply → continue.',
      'Ask only one question or task at a time.',
      '',
      gaps.length ? 'CONTEXT GAPS — ASK LEARNER ONLY WHEN RELEVANT:' : '',
      ...gaps.map((g,i) => `${i+1}. ${g.exchangeId ? `[${g.exchangeId}] ` : ''}${g.question || ''}`),
      '',
      'COHERENT DIALOGUE TRANSCRIPT:',
      ...lines.map((d,i) => {
        const details = [d.respondsTo ? `RespondsTo: ${d.respondsTo}` : '', d.meaning ? `Meaning: ${d.meaning}` : '', d.context ? `Context: ${d.context}` : '', d.intent ? `Intent: ${d.intent}` : '', d.tone ? `Tone: ${d.tone}` : ''].filter(Boolean).join(' | ');
        return `${i+1}. ${d.exchangeId ? `[${d.exchangeId}] ` : ''}[${d.start || ''}${d.end ? `-${d.end}` : ''}] ${d.speaker || 'Speaker'}: ${d.text || ''}${details ? `\n   ${details}` : ''}`;
      }),
      '', 'USEFUL PHRASES:', ...phrases.map((p,i) => `${i+1}. ${p.phrase || ''} — ${p.meaning || ''}${p.usage ? ` | ${p.usage}` : ''}`),
      '', 'PRONUNCIATION / CONNECTED SPEECH:', ...pronunciation.map((p,i) => `${i+1}. ${p.text || ''} — ${p.tip || ''}`),
      '', 'SUGGESTED FLOW:', ...flow.map((x,i) => `${i+1}. ${x}`)
    ].filter(Boolean).join('\n');
  }

  function clearCachedPlayback() {
    cachedArchive = null;
    cachedDialogueIndex = 0;
    cachedPlayEnd = null;
    cachePlaybackMode = null;
    if (cachedAudioUrl) URL.revokeObjectURL(cachedAudioUrl);
    cachedAudioUrl = null;
  }

  function playerForArchive() {
    const selected = fileInput.files?.[0];
    if (selected && cachedArchive && selected.name === cachedArchive.originalFileName && videoPreview?.src) {
      cachePlaybackMode = 'video';
      return videoPreview;
    }
    cachePlaybackMode = 'audio';
    return audioPreview;
  }

  function toPlayerTime(originalSec) {
    if (cachePlaybackMode === 'video') return originalSec;
    return Math.max(0, originalSec - Number(cachedArchive?.startSec || 0));
  }

  async function playCachedDialogue(index) {
    const lines = cachedArchive?.lesson?.dialogues || [];
    if (!lines.length) return false;
    const safe = Math.max(0, Math.min(lines.length - 1, Number(index) || 0));
    const line = lines[safe];
    let start = parseTime(line.start);
    let end = parseTime(line.end);
    if (start == null) return false;
    if (end == null || end <= start) end = start + 3;
    const player = playerForArchive();
    if (!player?.src) return false;
    cachedDialogueIndex = safe;
    [...dialogueList.querySelectorAll('.dialogue-row')].forEach((row,i) => row.classList.toggle('current', i === safe));
    cachedPlayEnd = toPlayerTime(end);
    player.currentTime = Math.max(0, toPlayerTime(start) - 0.06);
    try {
      await player.play();
      status.textContent = `▶ دیالوگ ${safe + 1} از نسخه ذخیره‌شده در حال پخش است.`;
      return true;
    } catch (err) {
      status.textContent = `پخش Audio ذخیره‌شده انجام نشد: ${err.message}`;
      return false;
    }
  }

  function enforceCachedEnd() {
    if (!cachedArchive || cachedPlayEnd == null) return;
    const player = playerForArchive();
    if (player && player.currentTime >= cachedPlayEnd - 0.04) {
      player.pause();
      cachedPlayEnd = null;
    }
  }
  videoPreview?.addEventListener('timeupdate', enforceCachedEnd);
  audioPreview?.addEventListener('timeupdate', enforceCachedEnd);

  function renderCachedDialogues() {
    const lines = cachedArchive?.lesson?.dialogues || [];
    dialogueList.replaceChildren();
    dialoguePlayer.style.display = lines.length ? 'block' : 'none';
    lines.forEach((d,index) => {
      const row = document.createElement('div');
      row.className = `dialogue-row${index === cachedDialogueIndex ? ' current' : ''}`;
      const play = document.createElement('button');
      play.className = 'ghost dialogue-play';
      play.textContent = `▶ ${index + 1}`;
      play.onclick = () => playCachedDialogue(index);
      const body = document.createElement('div');
      const text = document.createElement('div');
      text.className = 'dialogue-text'; text.textContent = d.text || '';
      const meta = document.createElement('div');
      meta.className = 'dialogue-meta'; meta.textContent = `${d.start || ''}${d.end ? ` – ${d.end}` : ''} • ${d.speaker || 'Speaker'}`;
      body.append(text, meta); row.append(play, body); dialogueList.appendChild(row);
    });
  }

  function enterTeacherSession(archive) {
    const chooser = document.querySelector('.ux-flow-card');
    const sourceCard = document.querySelector('.source-card');
    const tutorCard = document.querySelector('.tutor-card');
    const progressCard = document.querySelector('.progress-card');
    const summary = document.querySelector('.ux-session-summary');
    const sessionMedia = document.querySelector('.ux-session-media');
    chooser?.classList.add('ux-hidden'); sourceCard?.classList.add('ux-hidden'); tutorCard?.classList.remove('ux-hidden'); progressCard?.classList.add('ux-hidden'); summary?.classList.remove('ux-hidden');
    document.body.classList.remove('ux-flow-active'); document.body.classList.add('ux-session-active');
    const title = document.getElementById('uxSessionTitle');
    const sub = document.getElementById('uxSessionSub');
    if (title) title.textContent = archive.lesson?.title || 'درس فیلم آماده';
    if (sub) sub.textContent = `${archive.originalFileName || ''} • از حافظه دستگاه`;
    if (sessionMedia) {
      if (videoPreview?.src && fileInput.files?.[0]?.name === archive.originalFileName) sessionMedia.appendChild(videoPreview);
      else if (audioPreview?.src) sessionMedia.appendChild(audioPreview);
      sessionMedia.appendChild(dialoguePlayer);
      sessionMedia.classList.remove('ux-hidden');
    }
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  async function activateArchive(archive, autoStart, contextOverride = '') {
    try {
      const audio = await audioFor(archive.id);
      if (!audio) throw new Error('Audio ذخیره‌شده پیدا نشد.');
      clearCachedPlayback();
      cachedArchive = archive;
      cachedAudioUrl = URL.createObjectURL(audio);
      audioPreview.pause();
      audioPreview.src = cachedAudioUrl;
      audioPreview.style.display = 'block';

      const book = document.getElementById('bookName');
      const lessonName = document.getElementById('lessonName');
      const source = document.getElementById('lessonSource');
      if (book) book.value = `🎬 ${(archive.originalFileName || 'Movie').replace(/\.[^.]+$/, '')}`;
      if (lessonName) lessonName.value = archive.lesson?.title || 'Saved movie lesson';
      if (source) source.value = sourceText(archive, contextOverride);
      try {
        if (typeof state !== 'undefined') { state.mode = 'teacher'; state.methodProfile = null; }
        document.querySelectorAll('#modePicker button').forEach(b => b.classList.toggle('active', b.dataset.mode === 'teacher'));
        if (typeof saveProgress === 'function') saveProgress();
      } catch {}
      window.dispatchEvent(new CustomEvent('englishTutorNewSource', { detail: { kind: 'media', mediaCacheId: archive.id, fileName: archive.originalFileName, reused: true } }));

      cachedDialogueIndex = 0;
      renderCachedDialogues();
      const count = Array.isArray(archive.lesson?.dialogues) ? archive.lesson.dialogues.length : 0;
      if (result) {
        result.style.display = 'block';
        result.textContent = `${archive.lesson?.title || 'Dialogue lesson'}\n${count} دیالوگ از حافظه دستگاه باز شد.\nهیچ استخراج Audio و هیچ درخواست Gemini دوباره انجام نشد.`;
      }
      status.textContent = 'آماده شد ✅ متن و Audio از حافظه دستگاه باز شد؛ پردازش دوباره انجام نشد.';
      enterTeacherSession(archive);
      window.EnglishTutorMediaPlayer = {
        playDialogue: n => playCachedDialogue(Math.max(0, Number(n || 1) - 1)),
        replayCurrent: () => playCachedDialogue(cachedDialogueIndex),
        seekBy: seconds => { const p = playerForArchive(); if (p) p.currentTime = Math.max(0, p.currentTime + Number(seconds || 0)); },
        currentDialogue: () => cachedDialogueIndex + 1
      };
      if (autoStart && typeof connectLive === 'function') {
        await new Promise(r => setTimeout(r, 200));
        await connectLive();
      }
    } catch (err) {
      console.error('Open cached media lesson failed', err);
      status.textContent = `باز کردن درس ذخیره‌شده انجام نشد: ${err.message}`;
    }
  }

  document.addEventListener('click', event => {
    const button = event.target?.closest?.('#analyzeMediaBtn,#analyzeMediaOnlyBtn');
    if (!button) return;
    const hit = exactMatch();
    if (!hit) return;
    event.preventDefault();
    event.stopImmediatePropagation();
    const override = contextInput?.value?.trim() || '';
    activateArchive(hit, button.id === 'analyzeMediaBtn', override);
  }, true);

  fileInput.addEventListener('change', () => {
    clearCachedPlayback();
    if (originalMediaPlayer) window.EnglishTutorMediaPlayer = originalMediaPlayer;
    setTimeout(updateHit, 80);
  });
  startInput?.addEventListener('input', updateHit);
  endInput?.addEventListener('input', updateHit);

  new MutationObserver(() => {
    const text = status.textContent || '';
    if (/آماده شد ✅/.test(text) && !/از حافظه دستگاه/.test(text)) {
      if (originalMediaPlayer) window.EnglishTutorMediaPlayer = originalMediaPlayer;
      setTimeout(refreshCache, 200);
    }
  }).observe(status, { childList: true, subtree: true, characterData: true });

  const chat = document.getElementById('chat');
  if (chat) {
    new MutationObserver(records => {
      if (!cachedArchive) return;
      for (const record of records) for (const node of record.addedNodes) {
        if (!(node instanceof HTMLElement) || !node.classList.contains('message')) continue;
        const text = String(node.textContent || '');
        if (node.classList.contains('user') && /(دوباره|مجدد|replay|play.*again)/i.test(text)) setTimeout(() => playCachedDialogue(cachedDialogueIndex), 200);
        if (node.classList.contains('ai')) {
          const normalized = text.toLowerCase();
          const lines = cachedArchive.lesson?.dialogues || [];
          const found = lines.findIndex(d => String(d.text || '').length > 5 && normalized.includes(String(d.text || '').toLowerCase()));
          if (found >= 0) cachedDialogueIndex = found;
        }
      }
    }).observe(chat, { childList: true });
  }

  window.addEventListener('beforeunload', clearCachedPlayback);
  refreshCache();
})();
