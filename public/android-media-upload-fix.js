(() => {
  const cfg = window.EnglishTutorConfig;
  if (!cfg?.localShell) return;

  const previousFetch = window.fetch.bind(window);
  const pipeline = window.EnglishTutorMediaPipeline = window.EnglishTutorMediaPipeline || {
    busy: false,
    stage: 'idle',
    keySlot: 1,
    lastError: ''
  };

  function apiUrl(path) {
    const origin = String(cfg.apiOrigin || '').replace(/\/$/, '');
    return `${origin}${path}`;
  }

  function headerValue(headers, name) {
    try {
      if (headers instanceof Headers) return headers.get(name) || '';
      if (Array.isArray(headers)) {
        const found = headers.find(([k]) => String(k).toLowerCase() === name.toLowerCase());
        return found ? String(found[1] || '') : '';
      }
      if (headers && typeof headers === 'object') {
        const key = Object.keys(headers).find(k => k.toLowerCase() === name.toLowerCase());
        return key ? String(headers[key] || '') : '';
      }
    } catch (_) {}
    return '';
  }

  function isGeminiUploadUrl(value) {
    try {
      const url = new URL(String(value));
      return url.hostname === 'generativelanguage.googleapis.com' && url.pathname.startsWith('/upload/');
    } catch (_) {
      return false;
    }
  }

  async function proxyAudioUpload(file, slot) {
    pipeline.busy = true;
    pipeline.stage = 'uploading-audio';
    pipeline.lastError = '';

    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 120000);
    try {
      const response = await previousFetch(apiUrl(`/api/media-upload-proxy?slot=${encodeURIComponent(slot || 1)}`), {
        method: 'POST',
        headers: {
          'Content-Type': file?.type || 'audio/m4a',
          'X-File-Name': encodeURIComponent(file?.name || 'movie-audio.m4a'),
          'X-File-Size': String(file?.size || 0)
        },
        body: file,
        signal: controller.signal
      });
      const text = await response.text();
      if (!response.ok) {
        let message = `Audio proxy upload HTTP ${response.status}`;
        try { message = JSON.parse(text)?.error || message; } catch (_) {}
        throw new Error(message);
      }
      // media-learning.js expects the same shape Gemini returns: { file: {...} }.
      return new Response(text, {
        status: 200,
        headers: { 'Content-Type': 'application/json' }
      });
    } catch (err) {
      pipeline.lastError = err?.name === 'AbortError'
        ? 'ارسال Audio بیش از دو دقیقه طول کشید و متوقف شد.'
        : String(err?.message || err || 'Audio upload failed');
      throw new Error(pipeline.lastError);
    } finally {
      clearTimeout(timeout);
    }
  }

  window.fetch = async function androidMediaSafeFetch(input, init = {}) {
    const rawUrl = typeof input === 'string' || input instanceof URL
      ? String(input)
      : String(input?.url || '');
    const method = String(init?.method || input?.method || 'GET').toUpperCase();

    // Record the Gemini API-key slot selected by the server. The next resumable
    // upload request is redirected through our already-running Render proxy.
    if (method === 'POST' && /\/api\/media-upload-start(?:\?|$)/.test(rawUrl)) {
      pipeline.busy = true;
      pipeline.stage = 'starting-upload';
      pipeline.lastError = '';
      const response = await previousFetch(input, init);
      try {
        const data = await response.clone().json();
        if (data?.keySlot) pipeline.keySlot = Number(data.keySlot) || 1;
      } catch (_) {}
      if (!response.ok) {
        pipeline.busy = false;
        pipeline.stage = 'error';
      }
      return response;
    }

    // Android WebView can leave the direct Google resumable upload pending
    // indefinitely. Stream the extracted AUDIO (never the movie) through the
    // existing Render proxy instead. This does not require a backend deploy.
    if (method === 'POST' && isGeminiUploadUrl(rawUrl)) {
      const command = headerValue(init?.headers, 'X-Goog-Upload-Command');
      const body = init?.body;
      if (/upload/i.test(command) && body && typeof body.size === 'number') {
        console.log('[android-media-upload-fix] routing extracted audio through Render proxy');
        return proxyAudioUpload(body, pipeline.keySlot);
      }
    }

    if (method === 'POST' && /\/api\/analyze-media(?:\?|$)/.test(rawUrl)) {
      pipeline.busy = true;
      pipeline.stage = 'analyzing-dialogue';
      try {
        const response = await previousFetch(input, init);
        pipeline.busy = false;
        pipeline.stage = response.ok ? 'ready' : 'error';
        return response;
      } catch (err) {
        pipeline.busy = false;
        pipeline.stage = 'error';
        pipeline.lastError = String(err?.message || err || 'analysis failed');
        throw err;
      }
    }

    return previousFetch(input, init);
  };

  // Never let Teacher report the misleading "source missing" message while a
  // movie is still becoming a lesson. The click is allowed as soon as analysis
  // has finished and media-learning.js has populated lessonSource.
  document.addEventListener('click', (event) => {
    const button = event.target?.closest?.('#connectBtn');
    if (!button || !pipeline.busy) return;
    event.preventDefault();
    event.stopImmediatePropagation();
    const stageFa = pipeline.stage === 'uploading-audio'
      ? 'Audio در حال ارسال است'
      : pipeline.stage === 'analyzing-dialogue'
        ? 'دیالوگ‌ها در حال تحلیل هستند'
        : 'منبع فیلم هنوز در حال آماده‌سازی است';
    try {
      if (typeof addMessage === 'function') {
        addMessage('system', `${stageFa}. وقتی متن درس آماده شود، دکمه کلاس زنده قابل استفاده است.`);
      }
    } catch (_) {}
  }, true);

  console.log('[android-media-upload-fix] active');
})();
