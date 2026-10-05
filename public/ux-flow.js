(() => {
  const byId = (id) => document.getElementById(id);
  const sourceCard = document.querySelector('.source-card');
  const tutorCard = document.querySelector('.tutor-card');
  const progressCard = document.querySelector('.progress-card');
  const layout = document.querySelector('.layout');
  if (!sourceCard || !tutorCard || !progressCard || !layout) return;

  const style = document.createElement('style');
  style.textContent = `
    .ux-hidden{display:none!important}
    body.ux-flow-active .layout{grid-template-columns:minmax(0,940px);justify-content:center;max-width:1180px}
    body.ux-session-active .layout{grid-template-columns:minmax(0,1120px);justify-content:center;max-width:1220px}
    body.ux-flow-active .source-card,body.ux-session-active .tutor-card{min-height:auto}
    .ux-flow-card{padding:22px!important}
    .ux-flow-hero{display:flex;align-items:flex-start;justify-content:space-between;gap:16px;margin-bottom:18px}
    .ux-flow-hero h2{font-size:20px;line-height:1.3;margin:0 0 6px;letter-spacing:-.02em}
    .ux-flow-hero p{margin:0;color:var(--muted);font-size:12px;line-height:1.8}
    .ux-path-grid{display:grid;grid-template-columns:1fr 1fr;gap:12px}
    .ux-path-card{position:relative;text-align:right;min-height:154px;padding:18px!important;border:1px solid var(--line)!important;background:linear-gradient(180deg,rgba(255,255,255,.045),rgba(255,255,255,.018))!important;overflow:hidden}
    .ux-path-card:before{content:"";position:absolute;inset:auto -28px -38px auto;width:120px;height:120px;border-radius:50%;background:rgba(139,92,246,.12);filter:blur(8px)}
    .ux-path-card:hover{border-color:rgba(139,92,246,.4)!important;background:linear-gradient(180deg,rgba(139,92,246,.09),rgba(255,255,255,.02))!important}
    .ux-path-icon{width:44px;height:44px;border-radius:14px;display:grid;place-items:center;background:var(--accent-soft);border:1px solid rgba(139,92,246,.18);font-size:20px;margin-bottom:18px}
    .ux-path-title{display:block;font-size:15px;font-weight:850;color:#f4f7fb;margin-bottom:5px}
    .ux-path-sub{display:block;font-size:11px;color:var(--muted);line-height:1.65;max-width:90%}
    .ux-flow-footer{display:flex;gap:8px;align-items:center;flex-wrap:wrap;margin-top:14px;padding-top:14px;border-top:1px solid var(--line)}
    .ux-flow-footer .ghost{font-size:10px;padding:8px 10px}
    .ux-stage-head{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:18px;padding-bottom:14px;border-bottom:1px solid var(--line)}
    .ux-stage-copy{display:flex;gap:11px;align-items:center;min-width:0}
    .ux-stage-index{width:38px;height:38px;flex:0 0 38px;display:grid;place-items:center;border-radius:13px;background:var(--accent-soft);border:1px solid rgba(139,92,246,.2);color:#c4b5fd;font-size:10px;font-weight:900}
    .ux-stage-title{font-size:15px;font-weight:850;color:#f5f7fb}
    .ux-stage-desc{font-size:10px;color:var(--muted);margin-top:3px;line-height:1.55}
    .ux-stage-actions{display:flex;gap:7px;align-items:center}
    .ux-stage-actions button{padding:8px 10px;font-size:10px}
    .ux-upload-only{grid-template-columns:1fr!important}
    .ux-upload-only > *:not(.file-btn){display:none!important}
    .ux-upload-only .file-btn{min-height:128px;border-radius:18px!important;font-size:14px;font-weight:800;flex-direction:column;gap:8px;background:linear-gradient(180deg,rgba(139,92,246,.09),rgba(139,92,246,.035));border-width:1px!important}
    .ux-upload-only .file-btn:before{content:"＋";display:grid;place-items:center;width:42px;height:42px;border-radius:14px;background:rgba(139,92,246,.15);color:#c4b5fd;font-size:24px}
    .ux-book-config .pdf-row{grid-template-columns:86px 86px 1fr;align-items:end}
    .ux-book-config .pdf-row .file-btn{display:none!important}
    .ux-book-config #extractPdfBtn{min-height:42px;background:linear-gradient(135deg,#7c3aed,var(--accent));font-weight:800}
    .ux-media-upload .media-box{padding:0!important;border:0!important;background:transparent!important;margin:0!important}
    .ux-media-upload .media-head,.ux-media-upload .audio-only-note,.ux-media-upload .media-grid > label:not(.media-file),.ux-media-upload .media-preview,.ux-media-upload .media-context,.ux-media-upload .media-actions,.ux-media-upload .media-result,.ux-media-upload .media-export,.ux-media-upload .dialogue-player{display:none!important}
    .ux-media-upload .media-grid{display:block!important;margin:0!important}
    .ux-media-upload .media-file{min-height:128px;justify-content:center;align-items:center;text-align:center;border:1px dashed rgba(139,92,246,.48);border-radius:18px;padding:18px;background:linear-gradient(180deg,rgba(139,92,246,.09),rgba(139,92,246,.035));font-size:14px;color:#ddd6fe}
    .ux-media-upload .media-file:before{content:"🎬";font-size:26px;margin-bottom:8px}
    .ux-media-upload .media-file input{margin-top:10px}
    .ux-media-upload .media-status{margin-top:10px!important;text-align:center!important}
    .ux-media-config .media-box{margin:0!important}
    .ux-media-config .media-head,.ux-media-config .media-result,.ux-media-config .media-export,.ux-media-config .dialogue-player{display:none!important}
    .ux-media-config #analyzeMediaBtn{display:none!important}
    .ux-media-config #analyzeMediaOnlyBtn{display:inline-flex!important;background:linear-gradient(135deg,#7c3aed,var(--accent))!important;color:#fff!important;border:0!important;font-weight:800!important;min-height:44px;align-items:center;justify-content:center;flex:1}
    .ux-media-config #analyzeMediaOnlyBtn::after{content:"  →"}
    .ux-media-config .media-actions{display:flex!important}
    .ux-media-config .audio-only-note{font-size:10px!important;padding:8px 10px!important;opacity:.82}
    .ux-session-summary{display:flex;align-items:center;justify-content:space-between;gap:12px;margin:0 0 14px;padding:11px 12px;border-radius:15px;border:1px solid var(--line);background:rgba(139,92,246,.055)}
    .ux-session-summary b{font-size:12px}.ux-session-summary span{display:block;color:var(--muted);font-size:10px;margin-top:3px}
    .ux-session-summary button{padding:7px 9px;font-size:10px}
    .ux-session-media{display:grid;gap:10px;margin:0 0 14px}
    .ux-session-media .media-preview{display:block!important;max-height:360px!important;margin:0!important;border-radius:18px!important}
    .ux-session-media .dialogue-player{display:block!important;margin:0!important;background:rgba(255,255,255,.025)!important;border-color:var(--line)!important}
    .ux-session-media .dialogue-text{color:#eef2f7!important}.ux-session-media .dialogue-meta{color:var(--muted)!important}.ux-session-media .dialogue-row{background:rgba(255,255,255,.025)!important;border-color:var(--line)!important}.ux-session-media .dialogue-row.current{background:rgba(139,92,246,.09)!important;border-color:rgba(139,92,246,.35)!important}
    .ux-progress-toggle{margin-inline-start:auto}
    .ux-progress-overlay{display:block!important;position:relative!important;top:auto!important;max-width:760px;margin:0 auto 24px}
    @media(max-width:720px){
      .ux-flow-card{padding:16px!important}.ux-path-grid{grid-template-columns:1fr}.ux-path-card{min-height:132px}.ux-flow-hero h2{font-size:18px}
      .ux-stage-head{align-items:flex-start}.ux-stage-actions{flex-direction:column;align-items:stretch}.ux-stage-actions button{white-space:nowrap}
      .ux-book-config .pdf-row{grid-template-columns:1fr 1fr}.ux-book-config #extractPdfBtn{grid-column:1/-1}
      .ux-session-summary{align-items:flex-start}.ux-session-media .media-preview{max-height:280px!important}
    }
  `;
  document.head.appendChild(style);

  const chooser = document.createElement('section');
  chooser.className = 'card ux-flow-card';
  chooser.innerHTML = `
    <div class="ux-flow-hero">
      <div>
        <h2>امروز با چی می‌خوای یاد بگیری؟</h2>
        <p>فقط یک مسیر را انتخاب کن. هر مرحله فقط همان کاری را نشان می‌دهد که الان لازم داری.</p>
      </div>
    </div>
    <div class="ux-path-grid">
      <button id="uxChooseBook" class="ux-path-card">
        <span class="ux-path-icon">📘</span>
        <span class="ux-path-title">کتاب PDF</span>
        <span class="ux-path-sub">درس را از کتاب انتخاب کن، صفحات موردنظر را مشخص کن و بعد وارد Teacher شو.</span>
      </button>
      <button id="uxChooseMovie" class="ux-path-card">
        <span class="ux-path-icon">🎬</span>
        <span class="ux-path-title">فیلم یا کلیپ</span>
        <span class="ux-path-sub">فیلم روی گوشی می‌ماند؛ فقط Audio بازه انتخابی پردازش می‌شود و بعد Teacher شروع می‌شود.</span>
      </button>
    </div>
    <div class="ux-flow-footer">
      <button id="uxContinue" class="ghost ux-hidden">ادامه آخرین درس</button>
      <button id="uxLibrary" class="ghost">کتابخانه منابع</button>
    </div>
  `;
  layout.insertBefore(chooser, sourceCard);

  const oldSourceTitle = sourceCard.querySelector(':scope > .card-title');
  const oldSourceNote = sourceCard.querySelector(':scope > .section-note');
  const libraryBox = sourceCard.querySelector('.library-box');
  const bookGrid = sourceCard.querySelector(':scope > .grid-2');
  const pdfRow = sourceCard.querySelector('.pdf-row');
  const pdfStatus = byId('pdfStatus');
  const lessonSource = byId('lessonSource');
  const methodBox = sourceCard.querySelector('.method-box');
  const mediaBox = sourceCard.querySelector('.media-box');

  const stageHead = document.createElement('div');
  stageHead.className = 'ux-stage-head ux-hidden';
  stageHead.innerHTML = `
    <div class="ux-stage-copy">
      <span id="uxStageIndex" class="ux-stage-index">1/3</span>
      <div><div id="uxStageTitle" class="ux-stage-title"></div><div id="uxStageDesc" class="ux-stage-desc"></div></div>
    </div>
    <div class="ux-stage-actions"><button id="uxBack" class="ghost">بازگشت</button></div>
  `;
  sourceCard.insertBefore(stageHead, sourceCard.firstChild);

  const sessionSummary = document.createElement('div');
  sessionSummary.className = 'ux-session-summary ux-hidden';
  sessionSummary.innerHTML = `
    <div><b id="uxSessionTitle">منبع آماده است</b><span id="uxSessionSub"></span></div>
    <button id="uxChangeSource" class="ghost">تغییر منبع</button>
  `;
  tutorCard.insertBefore(sessionSummary, tutorCard.querySelector(':scope > .section-note')?.nextSibling || tutorCard.firstChild);

  const sessionMedia = document.createElement('div');
  sessionMedia.className = 'ux-session-media ux-hidden';
  sessionSummary.insertAdjacentElement('afterend', sessionMedia);

  const tutorTitle = tutorCard.querySelector('.card-title');
  const progressToggle = document.createElement('button');
  progressToggle.className = 'ghost ux-progress-toggle';
  progressToggle.textContent = 'پیشرفت';
  tutorTitle?.appendChild(progressToggle);

  let currentPath = null;
  let stage = 'choose';
  let mediaHome = null;
  const mediaPreviewVideo = byId('mediaLearningPreview');
  const mediaPreviewAudio = byId('mediaLearningAudioPreview');
  const dialoguePlayer = byId('dialoguePlayer');
  if (mediaBox && dialoguePlayer) mediaHome = { box: mediaBox, before: dialoguePlayer.nextSibling };

  function hide(el) { if (el) el.classList.add('ux-hidden'); }
  function show(el) { if (el) el.classList.remove('ux-hidden'); }
  function setStageCopy(index, title, desc) {
    byId('uxStageIndex').textContent = index;
    byId('uxStageTitle').textContent = title;
    byId('uxStageDesc').textContent = desc;
  }

  function resetSourceVisibility() {
    [oldSourceTitle, oldSourceNote, libraryBox, bookGrid, pdfRow, pdfStatus, lessonSource, methodBox, mediaBox].forEach(hide);
    sourceCard.classList.remove('ux-media-upload', 'ux-media-config', 'ux-book-config');
    pdfRow?.classList.remove('ux-upload-only');
  }

  function restoreMediaHome() {
    if (!mediaBox) return;
    if (mediaPreviewVideo && mediaPreviewVideo.parentElement === sessionMedia) mediaBox.appendChild(mediaPreviewVideo);
    if (mediaPreviewAudio && mediaPreviewAudio.parentElement === sessionMedia) mediaBox.appendChild(mediaPreviewAudio);
    if (dialoguePlayer && dialoguePlayer.parentElement === sessionMedia) mediaBox.appendChild(dialoguePlayer);
    hide(sessionMedia);
  }

  function showChooser() {
    try { if (typeof stopLive === 'function') stopLive(); } catch {}
    restoreMediaHome();
    currentPath = null;
    stage = 'choose';
    document.body.classList.add('ux-flow-active');
    document.body.classList.remove('ux-session-active');
    show(chooser); hide(sourceCard); hide(tutorCard); hide(progressCard); hide(sessionSummary);
    progressCard.classList.remove('ux-progress-overlay');
    const canContinue = Boolean(lessonSource?.value?.trim());
    byId('uxContinue')?.classList.toggle('ux-hidden', !canContinue);
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  function showSourceShell() {
    hide(chooser); hide(tutorCard); hide(progressCard); show(sourceCard); show(stageHead); hide(sessionSummary);
    document.body.classList.add('ux-flow-active');
    document.body.classList.remove('ux-session-active');
    resetSourceVisibility();
  }

  function showBookUpload() {
    currentPath = 'book'; stage = 'book-upload'; showSourceShell();
    setStageCopy('1/3', 'کتاب را انتخاب کن', 'فعلاً فقط فایل PDF لازم است. تنظیمات بعد از انتخاب کتاب ظاهر می‌شوند.');
    show(pdfRow); show(pdfStatus); pdfRow?.classList.add('ux-upload-only');
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  function showBookConfig() {
    currentPath = 'book'; stage = 'book-config'; showSourceShell();
    setStageCopy('2/3', 'درس را مشخص کن', 'صفحات درس را انتخاب کن. تحلیل روش مؤلف در پس‌زمینه انجام می‌شود.');
    show(bookGrid); show(pdfRow); show(pdfStatus); sourceCard.classList.add('ux-book-config');
    pdfRow?.classList.remove('ux-upload-only');
  }

  function showMovieUpload() {
    currentPath = 'media'; stage = 'media-upload'; showSourceShell();
    setStageCopy('1/3', 'فیلم یا کلیپ را انتخاب کن', 'در این مرحله فقط فایل را انتخاب کن؛ بقیه تنظیمات بعداً می‌آیند.');
    show(mediaBox); sourceCard.classList.add('ux-media-upload');
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  function showMovieConfig() {
    currentPath = 'media'; stage = 'media-config'; showSourceShell();
    setStageCopy('2/3', 'بازه را آماده کن', 'بازه زمانی و در صورت نیاز توضیح موقعیت را مشخص کن، بعد «آماده‌سازی و ادامه» را بزن.');
    show(mediaBox); sourceCard.classList.add('ux-media-config');
    const onlyBtn = byId('analyzeMediaOnlyBtn');
    if (onlyBtn) onlyBtn.textContent = 'آماده‌سازی دیالوگ‌ها و ادامه';
  }

  function showLibrary() {
    currentPath = 'library'; stage = 'library'; showSourceShell();
    setStageCopy('کتابخانه', 'منبع ذخیره‌شده', 'فقط منبع موردنظر را باز کن؛ بعد مستقیم وارد Teacher می‌شوی.');
    show(libraryBox);
  }

  function moveMediaIntoSession() {
    if (!mediaBox) return;
    sessionMedia.replaceChildren();
    if (mediaPreviewVideo?.src) sessionMedia.appendChild(mediaPreviewVideo);
    if (mediaPreviewAudio?.src) sessionMedia.appendChild(mediaPreviewAudio);
    if (dialoguePlayer) sessionMedia.appendChild(dialoguePlayer);
    if (sessionMedia.children.length) show(sessionMedia); else hide(sessionMedia);
  }

  function showSession(kind = 'source') {
    stage = 'session';
    hide(chooser); hide(sourceCard); show(tutorCard); hide(progressCard); show(sessionSummary);
    document.body.classList.remove('ux-flow-active');
    document.body.classList.add('ux-session-active');

    const bookName = byId('bookName')?.value?.trim();
    const lessonName = byId('lessonName')?.value?.trim();
    byId('uxSessionTitle').textContent = kind === 'media' ? '🎬 فیلم آماده آموزش است' : '📘 منبع آماده آموزش است';
    byId('uxSessionSub').textContent = [bookName, lessonName].filter(Boolean).join(' · ') || 'Teacher آماده است';

    if (kind === 'media') moveMediaIntoSession(); else { restoreMediaHome(); hide(sessionMedia); }
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }

  byId('uxChooseBook').onclick = showBookUpload;
  byId('uxChooseMovie').onclick = showMovieUpload;
  byId('uxContinue').onclick = () => showSession('source');
  byId('uxLibrary').onclick = showLibrary;
  byId('uxBack').onclick = () => {
    if (stage === 'book-config') showBookUpload();
    else if (stage === 'media-config') showMovieUpload();
    else showChooser();
  };
  byId('uxChangeSource').onclick = showChooser;

  byId('pdfFile')?.addEventListener('change', () => {
    if (byId('pdfFile')?.files?.[0]) setTimeout(showBookConfig, 80);
  });

  byId('mediaLearningFile')?.addEventListener('change', () => {
    if (byId('mediaLearningFile')?.files?.[0]) setTimeout(showMovieConfig, 80);
  });

  byId('loadLibrarySourceBtn')?.addEventListener('click', () => {
    setTimeout(() => { if (lessonSource?.value?.trim()) showSession('source'); }, 250);
  });

  const pdfObserver = pdfStatus ? new MutationObserver(() => {
    const text = pdfStatus.textContent || '';
    if (stage === 'book-config' && /استخراج شد/.test(text) && lessonSource?.value?.trim()) showSession('book');
  }) : null;
  pdfObserver?.observe(pdfStatus, { childList: true, subtree: true, characterData: true });

  const mediaStatus = byId('mediaLearningStatus');
  const mediaObserver = mediaStatus ? new MutationObserver(() => {
    const text = mediaStatus.textContent || '';
    if (stage === 'media-config' && /آماده شد ✅/.test(text)) showSession('media');
  }) : null;
  mediaObserver?.observe(mediaStatus, { childList: true, subtree: true, characterData: true });

  progressToggle.onclick = () => {
    const open = !progressCard.classList.contains('ux-hidden');
    if (open) {
      hide(progressCard); progressCard.classList.remove('ux-progress-overlay'); progressToggle.textContent = 'پیشرفت';
    } else {
      show(progressCard); progressCard.classList.add('ux-progress-overlay'); progressToggle.textContent = 'بستن پیشرفت';
      progressCard.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
  };

  // Keep preparation controls out of the Teacher screen. The source textarea and author-method controls
  // still exist for the teaching engine, but are intentionally not shown in the normal flow.
  hide(oldSourceTitle); hide(oldSourceNote); hide(methodBox); hide(lessonSource);
  showChooser();
})();