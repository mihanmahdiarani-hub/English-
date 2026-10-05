(() => {
  const META_KEY = 'englishTutorSourceLibraryV1';
  const PROGRESS_KEY = 'englishTutorProgress';
  const SESSION_KEY = 'englishTutorSessionSourceId';
  const DB_NAME = 'englishTutorSourceFiles';
  const PDF_STORE = 'pdfs';

  let activeId = null;
  let restoringPdf = false;
  let preparingExternalSource = false;

  function readJson(key, fallback) {
    try {
      const value = JSON.parse(localStorage.getItem(key) || 'null');
      return value ?? fallback;
    } catch {
      return fallback;
    }
  }

  function readMeta() {
    const value = readJson(META_KEY, { items: [], defaultId: null });
    if (!Array.isArray(value.items)) value.items = [];
    if (!('defaultId' in value)) value.defaultId = null;
    return value;
  }

  let meta = readMeta();

  function persistMeta() {
    localStorage.setItem(META_KEY, JSON.stringify(meta));
  }

  function makeId() {
    return globalThis.crypto?.randomUUID?.() || `src-${Date.now()}-${Math.random().toString(16).slice(2)}`;
  }

  function hashText(text) {
    let hash = 2166136261;
    for (let i = 0; i < text.length; i += 1) {
      hash ^= text.charCodeAt(i);
      hash = Math.imul(hash, 16777619);
    }
    return (hash >>> 0).toString(36);
  }

  function captureProgress() {
    const progress = readJson(PROGRESS_KEY, {});
    ['bookName', 'lessonName', 'lastExercise', 'nextStart', 'mistakes', 'reviewItems', 'lessonSource'].forEach(id => {
      const el = document.getElementById(id);
      if (el) progress[id] = el.value;
    });
    const methodEnd = document.getElementById('methodPageEnd');
    if (methodEnd) progress.methodPageEnd = Number(methodEnd.value || 20);
    return progress;
  }

  function fingerprint(progress) {
    const source = String(progress?.lessonSource || '');
    const compact = source.length > 12000
      ? `${source.slice(0, 6000)}|${source.slice(-6000)}|${source.length}`
      : source;
    return hashText(`${progress?.bookName || ''}|${progress?.lessonName || ''}|${compact}`);
  }

  function titleFor(progress, fileName = '') {
    const book = String(progress?.bookName || '').trim();
    const lesson = String(progress?.lessonName || '').trim();
    if (book && lesson) return `${book} — ${lesson}`;
    if (book) return book;
    if (fileName) return fileName.replace(/\.(pdf|mp4|mkv|mov|avi|mp3|m4a|aac|wav)$/i, '');
    return 'منبع بدون نام';
  }

  function itemById(id) {
    return meta.items.find(item => item.id === id) || null;
  }

  function setStatus(text, isError = false) {
    const el = document.getElementById('sourceLibraryStatus');
    if (!el) return;
    el.textContent = text || '';
    el.classList.toggle('library-error', Boolean(isError));
  }

  function render() {
    const select = document.getElementById('sourceLibrarySelect');
    if (!select) return;
    const selectedBefore = select.value || activeId || meta.defaultId || '';
    select.replaceChildren();

    const empty = document.createElement('option');
    empty.value = '';
    empty.textContent = meta.items.length ? 'انتخاب منبع…' : 'کتابخانه هنوز خالی است';
    select.appendChild(empty);

    [...meta.items]
      .sort((a, b) => Number(b.savedAt || 0) - Number(a.savedAt || 0))
      .forEach(item => {
        const option = document.createElement('option');
        option.value = item.id;
        const star = item.id === meta.defaultId ? '⭐ ' : '';
        const type = item.kind === 'media' ? ' • 🎧' : item.hasPdf ? ' • PDF' : '';
        option.textContent = `${star}${item.title || 'منبع بدون نام'}${type}`;
        select.appendChild(option);
      });

    const wanted = itemById(selectedBefore) ? selectedBefore : (itemById(activeId) ? activeId : '');
    select.value = wanted;

    const defaultItem = itemById(meta.defaultId);
    const badge = document.getElementById('defaultSourceBadge');
    if (badge) badge.textContent = defaultItem ? `پیش‌فرض: ${defaultItem.title}` : 'پیش‌فرض تعیین نشده';

    const defaultBtn = document.getElementById('setDefaultSourceBtn');
    if (defaultBtn) {
      defaultBtn.disabled = !select.value;
      defaultBtn.textContent = select.value && select.value === meta.defaultId ? 'لغو پیش‌فرض' : 'پیش‌فرض کن';
    }
  }

  function upsertCurrent({ createIfMissing = true, kind, mediaCacheId, fileName } = {}) {
    const progress = captureProgress();
    if (!String(progress.lessonSource || '').trim() && !String(progress.bookName || '').trim()) return null;

    let item = itemById(activeId);
    if (!item && !createIfMissing) return null;
    if (!item) {
      item = { id: makeId(), fileName: '', hasPdf: false, savedAt: Date.now() };
      meta.items.push(item);
      activeId = item.id;
      sessionStorage.setItem(SESSION_KEY, activeId);
    }

    item.progress = progress;
    item.bookName = progress.bookName || '';
    item.lessonName = progress.lessonName || '';
    item.title = titleFor(progress, fileName || item.fileName || '');
    item.fingerprint = fingerprint(progress);
    item.savedAt = Date.now();
    if (kind) item.kind = kind;
    if (mediaCacheId) item.mediaCacheId = mediaCacheId;
    if (fileName) item.mediaFileName = fileName;

    if (!meta.defaultId) meta.defaultId = item.id;
    persistMeta();
    render();
    return item;
  }

  function migrateLegacyProgress() {
    const progress = readJson(PROGRESS_KEY, {});
    if (!String(progress.lessonSource || '').trim()) return;
    const fp = fingerprint(progress);
    let item = meta.items.find(x => x.fingerprint === fp);
    if (!item) {
      item = {
        id: makeId(),
        title: titleFor(progress),
        bookName: progress.bookName || '',
        lessonName: progress.lessonName || '',
        progress,
        fingerprint: fp,
        fileName: '',
        hasPdf: false,
        savedAt: Date.now()
      };
      meta.items.push(item);
      if (!meta.defaultId) meta.defaultId = item.id;
      persistMeta();
    }
  }

  function applyStartupSource() {
    const sessionItem = itemById(sessionStorage.getItem(SESSION_KEY));
    const defaultItem = itemById(meta.defaultId);
    const startup = sessionItem || defaultItem;
    if (!startup) return;
    localStorage.setItem(PROGRESS_KEY, JSON.stringify(startup.progress || {}));
    activeId = startup.id;
    sessionStorage.setItem(SESSION_KEY, startup.id);
  }

  migrateLegacyProgress();
  meta = readMeta();
  applyStartupSource();

  function openDb() {
    return new Promise((resolve, reject) => {
      const req = indexedDB.open(DB_NAME, 1);
      req.onupgradeneeded = () => {
        const db = req.result;
        if (!db.objectStoreNames.contains(PDF_STORE)) db.createObjectStore(PDF_STORE);
      };
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
  }

  async function putPdf(id, file) {
    const db = await openDb();
    try {
      await new Promise((resolve, reject) => {
        const tx = db.transaction(PDF_STORE, 'readwrite');
        tx.objectStore(PDF_STORE).put(file, id);
        tx.oncomplete = resolve;
        tx.onerror = () => reject(tx.error);
      });
    } finally {
      db.close();
    }
  }

  async function getPdf(id) {
    const db = await openDb();
    try {
      return await new Promise((resolve, reject) => {
        const tx = db.transaction(PDF_STORE, 'readonly');
        const req = tx.objectStore(PDF_STORE).get(id);
        req.onsuccess = () => resolve(req.result || null);
        req.onerror = () => reject(req.error);
      });
    } finally {
      db.close();
    }
  }

  async function removePdf(id) {
    try {
      const db = await openDb();
      await new Promise((resolve, reject) => {
        const tx = db.transaction(PDF_STORE, 'readwrite');
        tx.objectStore(PDF_STORE).delete(id);
        tx.oncomplete = resolve;
        tx.onerror = () => reject(tx.error);
      });
      db.close();
    } catch {}
  }

  async function attachPdf(file) {
    const previous = itemById(activeId);
    if (previous) upsertCurrent({ createIfMissing: false });

    const oldBook = previous?.bookName || '';
    const oldLesson = previous?.lessonName || '';
    const bookEl = document.getElementById('bookName');
    const lessonEl = document.getElementById('lessonName');
    const sourceEl = document.getElementById('lessonSource');
    if (bookEl && (!bookEl.value.trim() || bookEl.value.trim() === oldBook.trim())) bookEl.value = file.name.replace(/\.pdf$/i, '');
    if (lessonEl && lessonEl.value.trim() === oldLesson.trim()) lessonEl.value = '';
    if (sourceEl && previous) sourceEl.value = '';

    activeId = makeId();
    sessionStorage.setItem(SESSION_KEY, activeId);
    const progress = captureProgress();
    const item = {
      id: activeId,
      title: titleFor(progress, file.name),
      bookName: progress.bookName || '',
      lessonName: progress.lessonName || '',
      progress,
      fingerprint: fingerprint(progress),
      fileName: file.name,
      hasPdf: true,
      kind: 'pdf',
      savedAt: Date.now()
    };
    meta.items.push(item);
    if (!meta.defaultId) meta.defaultId = item.id;
    persistMeta();
    render();

    try {
      await putPdf(item.id, file);
      setStatus(`«${item.title}» با فایل PDF در کتابخانه ذخیره شد.`);
    } catch (err) {
      item.hasPdf = false;
      persistMeta();
      render();
      setStatus(`متن منبع ذخیره شد، اما ذخیره PDF ناموفق بود: ${err.message}`, true);
    }
  }

  async function restorePdf() {
    const item = itemById(activeId);
    if (!item?.hasPdf) return;
    const input = document.getElementById('pdfFile');
    if (!input) return;
    try {
      const stored = await getPdf(item.id);
      if (!stored) {
        item.hasPdf = false;
        persistMeta();
        render();
        return;
      }
      const file = stored instanceof File
        ? stored
        : new File([stored], item.fileName || `${item.title || 'source'}.pdf`, { type: 'application/pdf' });
      if (typeof DataTransfer === 'undefined') {
        setStatus('متن منبع پیش‌فرض بارگذاری شد؛ مرورگر اجازه اتصال خودکار PDF را نداد.');
        return;
      }
      const dt = new DataTransfer();
      dt.items.add(file);
      restoringPdf = true;
      input.files = dt.files;
      input.dispatchEvent(new Event('change', { bubbles: true }));
      setTimeout(() => { restoringPdf = false; }, 1200);
      setStatus(`منبع «${item.title}» از کتابخانه بارگذاری شد.`);
    } catch (err) {
      restoringPdf = false;
      setStatus(`PDF کتابخانه باز نشد: ${err.message}`, true);
    }
  }

  function prepareExternalSource() {
    if (preparingExternalSource) return;
    preparingExternalSource = true;
    upsertCurrent({ createIfMissing: false });
    activeId = null;
    sessionStorage.removeItem(SESSION_KEY);
  }

  function commitExternalSource(detail = {}) {
    const item = upsertCurrent({
      createIfMissing: true,
      kind: detail.kind || 'media',
      mediaCacheId: detail.mediaCacheId || '',
      fileName: detail.fileName || ''
    });
    preparingExternalSource = false;
    if (item) setStatus(`«${item.title}» به کتابخانه منابع اضافه شد.`);
  }

  function bindUi() {
    render();
    const select = document.getElementById('sourceLibrarySelect');
    const loadBtn = document.getElementById('loadLibrarySourceBtn');
    const saveBtn = document.getElementById('saveLibrarySourceBtn');
    const defaultBtn = document.getElementById('setDefaultSourceBtn');
    const deleteBtn = document.getElementById('deleteLibrarySourceBtn');
    const pdfInput = document.getElementById('pdfFile');

    select?.addEventListener('change', render);

    loadBtn?.addEventListener('click', () => {
      const item = itemById(select?.value);
      if (!item) return;
      upsertCurrent({ createIfMissing: false });
      localStorage.setItem(PROGRESS_KEY, JSON.stringify(item.progress || {}));
      sessionStorage.setItem(SESSION_KEY, item.id);
      location.reload();
    });

    saveBtn?.addEventListener('click', () => {
      const item = upsertCurrent({ createIfMissing: true });
      setStatus(item ? `«${item.title}» در کتابخانه به‌روز شد.` : 'برای ذخیره، اول یک منبع یا نام کتاب وارد کن.', !item);
    });

    defaultBtn?.addEventListener('click', () => {
      const id = select?.value || activeId;
      const item = itemById(id);
      if (!item) return;
      if (meta.defaultId === id) {
        meta.defaultId = null;
        setStatus('منبع پیش‌فرض برداشته شد.');
      } else {
        meta.defaultId = id;
        setStatus(`«${item.title}» منبع پیش‌فرض شد. جلسه جدید با همین منبع باز می‌شود.`);
      }
      persistMeta();
      render();
    });

    deleteBtn?.addEventListener('click', async () => {
      const id = select?.value;
      const item = itemById(id);
      if (!item) return;
      if (!confirm(`منبع «${item.title}» از کتابخانه حذف شود؟`)) return;
      meta.items = meta.items.filter(x => x.id !== id);
      if (meta.defaultId === id) meta.defaultId = null;
      if (activeId === id) {
        activeId = null;
        sessionStorage.removeItem(SESSION_KEY);
      }
      persistMeta();
      await removePdf(id);
      render();
      setStatus('منبع از کتابخانه حذف شد.');
    });

    pdfInput?.addEventListener('change', event => {
      if (restoringPdf) return;
      const file = event.target.files?.[0];
      if (file) attachPdf(file);
    });

    document.getElementById('saveProgressBtn')?.addEventListener('click', () => setTimeout(() => upsertCurrent({ createIfMissing: false }), 60));
    document.getElementById('stopBtn')?.addEventListener('click', () => setTimeout(() => upsertCurrent({ createIfMissing: false }), 60));
    ['bookName', 'lessonName'].forEach(id => document.getElementById(id)?.addEventListener('change', () => upsertCurrent({ createIfMissing: false })));

    const pdfStatus = document.getElementById('pdfStatus');
    if (pdfStatus) {
      new MutationObserver(() => {
        if (/استخراج شد/.test(pdfStatus.textContent || '')) setTimeout(() => upsertCurrent({ createIfMissing: false }), 80);
      }).observe(pdfStatus, { childList: true, subtree: true, characterData: true });
    }

    const methodStatus = document.getElementById('methodStatus');
    if (methodStatus) {
      new MutationObserver(() => {
        const text = methodStatus.textContent || '';
        if (/اطمینان تحلیل|انجام نشد/.test(text)) setTimeout(() => upsertCurrent({ createIfMissing: false }), 100);
      }).observe(methodStatus, { childList: true, subtree: true, characterData: true });
    }

    window.addEventListener('englishTutorPrepareNewSource', prepareExternalSource);
    window.addEventListener('englishTutorNewSource', event => commitExternalSource(event.detail || {}));

    window.addEventListener('beforeunload', () => {
      if (!preparingExternalSource) upsertCurrent({ createIfMissing: false });
    });
    window.addEventListener('load', () => setTimeout(restorePdf, 120));

    const active = itemById(activeId);
    if (active) setStatus(`منبع فعال: «${active.title}»${active.id === meta.defaultId ? ' — پیش‌فرض' : ''}`);
  }

  bindUi();
})();
