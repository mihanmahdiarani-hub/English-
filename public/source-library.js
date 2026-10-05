(() => {
  const META_KEY = 'englishTutorSourceLibraryV1';
  const PROGRESS_KEY = 'englishTutorProgress';
  const SESSION_KEY = 'englishTutorSessionSourceId';
  const DB_NAME = 'englishTutorSourceFiles';
  const DB_STORE = 'pdfs';

  let activeId = null;
  let restoringPdf = false;

  function readJson(key, fallback) {
    try {
      const value = JSON.parse(localStorage.getItem(key) || 'null');
      return value ?? fallback;
    } catch {
      return fallback;
    }
  }

  function readMeta() {
    const meta = readJson(META_KEY, { items: [], defaultId: null });
    if (!Array.isArray(meta.items)) meta.items = [];
    if (!('defaultId' in meta)) meta.defaultId = null;
    return meta;
  }

  let meta = readMeta();

  function saveMeta() {
    try {
      localStorage.setItem(META_KEY, JSON.stringify(meta));
      return true;
    } catch (err) {
      setLibraryStatus(`فضای ذخیره‌سازی مرورگر کافی نیست: ${err.message}`, true);
      return false;
    }
  }

  function makeId() {
    return globalThis.crypto?.randomUUID?.() || `src-${Date.now()}-${Math.random().toString(16).slice(2)}`;
  }

  function textHash(text) {
    let h = 2166136261;
    for (let i = 0; i < text.length; i++) {
      h ^= text.charCodeAt(i);
      h = Math.imul(h, 16777619);
    }
    return (h >>> 0).toString(36);
  }

  function sourceFingerprint(progress) {
    const source = String(progress?.lessonSource || '');
    const compact = source.length > 12000
      ? `${source.slice(0, 6000)}|${source.slice(-6000)}|${source.length}`
      : source;
    return textHash(`${progress?.bookName || ''}|${progress?.lessonName || ''}|${compact}`);
  }

  function sourceTitle(progress, fileName = '') {
    const book = String(progress?.bookName || '').trim();
    const lesson = String(progress?.lessonName || '').trim();
    if (book && lesson) return `${book} — ${lesson}`;
    if (book) return book;
    if (fileName) return fileName.replace(/\.pdf$/i, '');
    return 'منبع بدون نام';
  }

  function captureProgress() {
    const progress = readJson(PROGRESS_KEY, {});
    const ids = ['bookName', 'lessonName', 'lastExercise', 'nextStart', 'mistakes', 'reviewItems', 'lessonSource'];
    ids.forEach(id => {
      const el = document.getElementById(id);
      if (el) progress[id] = el.value;
    });
    const methodEnd = document.getElementById('methodPageEnd');
    if (methodEnd) progress.methodPageEnd = Number(methodEnd.value || 20);
    return progress;
  }

  function itemById(id) {
    return meta.items.find(item => item.id === id) || null;
  }

  function setLibraryStatus(text, isError = false) {
    const el = document.getElementById('sourceLibraryStatus');
    if (!el) return;
    el.textContent = text || '';
    el.classList.toggle('library-error', Boolean(isError));
  }

  function renderLibrary() {
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
        const pdf = item.hasPdf ? ' • PDF' : '';
        option.textContent = `${star}${item.title || 'منبع بدون نام'}${pdf}`;
        select.appendChild(option);
      });

    const wanted = itemById(selectedBefore) ? selectedBefore : (itemById(activeId) ? activeId : '');
    select.value = wanted;

    const defaultItem = itemById(meta.defaultId);
    const badge = document.getElementById('defaultSourceBadge');
    if (badge) badge.textContent = defaultItem ? `پیش‌فرض: ${defaultItem.title}` : 'پیش‌فرض تعیین نشده';

    updateDefaultButton();
  }

  function updateDefaultButton() {
    const select = document.getElementById('sourceLibrarySelect');
    const btn = document.getElementById('setDefaultSourceBtn');
    if (!select || !btn) return;
    const selected = select.value;
    btn.disabled = !selected;
    btn.textContent = selected && selected === meta.defaultId ? 'لغو پیش‌فرض' : 'پیش‌فرض کن';
  }

  function migrateCurrentProgress() {
    const progress = readJson(PROGRESS_KEY, {});
    if (!String(progress.lessonSource || '').trim()) return;

    const fingerprint = sourceFingerprint(progress);
    let existing = meta.items.find(item => item.fingerprint === fingerprint);
    if (!existing) {
      existing = {
        id: makeId(),
        title: sourceTitle(progress),
        bookName: progress.bookName || '',
        lessonName: progress.lessonName || '',
        progress,
        fingerprint,
        fileName: '',
        hasPdf: false,
        savedAt: Date.now()
      };
      meta.items.push(existing);
      if (!meta.defaultId) meta.defaultId = existing.id;
      saveMeta();
    }

    if (!activeId && !sessionStorage.getItem(SESSION_KEY) && meta.defaultId === existing.id) {
      activeId = existing.id;
    }
  }

  function applyStartupSource() {
    const sessionId = sessionStorage.getItem(SESSION_KEY);
    const sessionItem = itemById(sessionId);
    const defaultItem = itemById(meta.defaultId);
    const startupItem = sessionItem || defaultItem;
    if (!startupItem) return;

    try {
      localStorage.setItem(PROGRESS_KEY, JSON.stringify(startupItem.progress || {}));
      activeId = startupItem.id;
      if (sessionItem) sessionStorage.setItem(SESSION_KEY, startupItem.id);
    } catch {}
  }

  // First preserve the source that existed before the library feature, then choose
  // the active/default source before app.js runs loadProgress().
  migrateCurrentProgress();
  meta = readMeta();
  applyStartupSource();

  function upsertCurrent({ createIfMissing = true } = {}) {
    const progress = captureProgress();
    if (!String(progress.lessonSource || '').trim() && !String(progress.bookName || '').trim()) return null;

    let item = itemById(activeId);
    if (!item && !createIfMissing) return null;

    if (!item) {
      item = { id: makeId(), fileName: '', hasPdf: false };
      meta.items.push(item);
      activeId = item.id;
      sessionStorage.setItem(SESSION_KEY, activeId);
    }

    item.progress = progress;
    item.bookName = progress.bookName || '';
    item.lessonName = progress.lessonName || '';
    item.title = sourceTitle(progress, item.fileName || '');
    item.fingerprint = sourceFingerprint(progress);
    item.savedAt = Date.now();

    if (!meta.defaultId) meta.defaultId = item.id;
    saveMeta();
    renderLibrary();
    return item;
  }

  function openDb() {
    return new Promise((resolve, reject) => {
      const req = indexedDB.open(DB_NAME, 1);
      req.onupgradeneeded = () => {
        const db = req.result;
        if (!db.objectStoreNames.contains(DB_STORE)) db.createObjectStore(DB_STORE);
      };
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
  }

  async function storePdf(id, file) {
    const db = await openDb();
    await new Promise((resolve, reject) => {
      const tx = db.transaction(DB_STORE, 'readwrite');
      tx.objectStore(DB_STORE).put(file, id);
      tx.oncomplete = resolve;
      tx.onerror = () => reject(tx.error);
    });
    db.close();
  }

  async function getPdf(id) {
    const db = await openDb();
    const value = await new Promise((resolve, reject) => {
      const tx = db.transaction(DB_STORE, 'readonly');
      const req = tx.objectStore(DB_STORE).get(id);
      req.onsuccess = () => resolve(req.result || null);
      req.onerror = () => reject(req.error);
    });
    db.close();
    return value;
  }

  async function deletePdf(id) {
    try {
      const db = await openDb();
      await new Promise((resolve, reject) => {
        const tx = db.transaction(DB_STORE, 'readwrite');
        tx.objectStore(DB_STORE).delete(id);
        tx.oncomplete = resolve;
        tx.onerror = () => reject(tx.error);
      });
      db.close();
    } catch {}
  }

  async function attachUploadedPdf(file) {
    const previous = itemById(activeId);
    if (previous) upsertCurrent({ createIfMissing: false });

    const oldBook = previous?.bookName || '';
    const oldLesson = previous?.lessonName || '';
    const bookEl = document.getElementById('bookName');
    const lessonEl = document.getElementById('lessonName');
    const sourceEl = document.getElementById('lessonSource');

    if (bookEl && (!bookEl.value.trim() || bookEl.value.trim() === oldBook.trim())) {
      bookEl.value = file.name.replace(/\.pdf$/i, '');
    }
    if (lessonEl && lessonEl.value.trim() === oldLesson.trim()) lessonEl.value = '';
    if (sourceEl && previous) sourceEl.value = '';

    const id = makeId();
    activeId = id;
    sessionStorage.setItem(SESSION_KEY, id);

    const progress = captureProgress();
    const item = {
      id,
      title: sourceTitle(progress, file.name),
      bookName: progress.bookName || '',
      lessonName: progress.lessonName || '',
      progress,
      fingerprint: sourceFingerprint(progress),
      fileName: file.name,
      hasPdf: true,
      savedAt: Date.now()
    };
    meta.items.push(item);
    if (!meta.defaultId) meta.defaultId = id;
    saveMeta();
    renderLibrary();

    try {
      await storePdf(id, file);
      setLibraryStatus(`«${item.title}» با فایل PDF در کتابخانه ذخیره شد.`);
    } catch (err) {
      item.hasPdf = false;
      saveMeta();
      renderLibrary();
      setLibraryStatus(`متن منبع ذخیره شد، اما ذخیره PDF ناموفق بود: ${err.message}`, true);
    }
  }

  async function restoreActivePdf() {
    const item = itemById(activeId);
    if (!item?.hasPdf) return;
    const input = document.getElementById('pdfFile');
    if (!input) return;

    try {
      const stored = await getPdf(item.id);
      if (!stored) {
        item.hasPdf = false;
        saveMeta();
        renderLibrary();
        return;
      }

      const file = stored instanceof File
        ? stored
        : new File([stored], item.fileName || `${item.title || 'source'}.pdf`, { type: 'application/pdf' });

      if (typeof DataTransfer === 'undefined') {
        setLibraryStatus('منبع پیش‌فرض بارگذاری شد؛ مرورگر اجازه اتصال خودکار PDF را نداد. متن ذخیره‌شده همچنان آماده است.');
        return;
      }

      const dt = new DataTransfer();
      dt.items.add(file);
      restoringPdf = true;
      input.files = dt.files;
      input.dispatchEvent(new Event('change', { bubbles: true }));
      setTimeout(() => { restoringPdf = false; }, 1000);
      setLibraryStatus(`منبع «${item.title}» از کتابخانه بارگذاری شد.`);
    } catch (err) {
      restoringPdf = false;
      setLibraryStatus(`PDF کتابخانه باز نشد: ${err.message}`, true);
    }
  }

  function bindUi() {
    renderLibrary();

    const select = document.getElementById('sourceLibrarySelect');
    const loadBtn = document.getElementById('loadLibrarySourceBtn');
    const saveBtn = document.getElementById('saveLibrarySourceBtn');
    const defaultBtn = document.getElementById('setDefaultSourceBtn');
    const deleteBtn = document.getElementById('deleteLibrarySourceBtn');
    const pdfInput = document.getElementById('pdfFile');

    select?.addEventListener('change', updateDefaultButton);

    loadBtn?.addEventListener('click', () => {
      const id = select?.value;
      const item = itemById(id);
      if (!item) return;
      upsertCurrent({ createIfMissing: false });
      sessionStorage.setItem(SESSION_KEY, item.id);
      localStorage.setItem(PROGRESS_KEY, JSON.stringify(item.progress || {}));
      location.reload();
    });

    saveBtn?.addEventListener('click', () => {
      const item = upsertCurrent({ createIfMissing: true });
      if (item) setLibraryStatus(`«${item.title}» در کتابخانه به‌روز شد.`);
      else setLibraryStatus('برای ذخیره، اول یک منبع یا نام کتاب وارد کن.', true);
    });

    defaultBtn?.addEventListener('click', () => {
      const id = select?.value || activeId;
      const item = itemById(id);
      if (!item) return;
      if (meta.defaultId === id) {
        meta.defaultId = null;
        setLibraryStatus('منبع پیش‌فرض برداشته شد.');
      } else {
        meta.defaultId = id;
        setLibraryStatus(`«${item.title}» منبع پیش‌فرض شد. از جلسه جدید اپ با همین منبع باز می‌شود.`);
      }
      saveMeta();
      renderLibrary();
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
      saveMeta();
      await deletePdf(id);
      renderLibrary();
      setLibraryStatus('منبع از کتابخانه حذف شد.');
    });

    pdfInput?.addEventListener('change', (e) => {
      if (restoringPdf) return;
      const file = e.target.files?.[0];
      if (!file) return;
      attachUploadedPdf(file);
    });

    document.getElementById('saveProgressBtn')?.addEventListener('click', () => {
      setTimeout(() => upsertCurrent({ createIfMissing: false }), 60);
    });
    document.getElementById('stopBtn')?.addEventListener('click', () => {
      setTimeout(() => upsertCurrent({ createIfMissing: false }), 60);
    });

    ['bookName', 'lessonName'].forEach(id => {
      document.getElementById(id)?.addEventListener('change', () => upsertCurrent({ createIfMissing: false }));
    });

    const pdfStatus = document.getElementById('pdfStatus');
    if (pdfStatus) {
      new MutationObserver(() => {
        if (/استخراج شد/.test(pdfStatus.textContent || '')) {
          setTimeout(() => upsertCurrent({ createIfMissing: false }), 80);
        }
      }).observe(pdfStatus, { childList: true, subtree: true, characterData: true });
    }

    const methodStatus = document.getElementById('methodStatus');
    if (methodStatus) {
      new MutationObserver(() => {
        const t = methodStatus.textContent || '';
        if (/اطمینان تحلیل|انجام نشد/.test(t)) {
          setTimeout(() => upsertCurrent({ createIfMissing: false }), 100);
        }
      }).observe(methodStatus, { childList: true, subtree: true, characterData: true });
    }

    window.addEventListener('beforeunload', () => {
      upsertCurrent({ createIfMissing: false });
    });

    window.addEventListener('load', () => {
      setTimeout(restoreActivePdf, 120);
    });

    const active = itemById(activeId);
    if (active) setLibraryStatus(`منبع فعال: «${active.title}»${active.id === meta.defaultId ? ' — پیش‌فرض' : ''}`);
  }

  bindUi();
})();