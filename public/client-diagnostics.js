(() => {
  function safeMediaInfo() {
    try {
      if (!window.AndroidMedia?.selectedMediaInfo) return {};
      const raw = window.AndroidMedia.selectedMediaInfo();
      return JSON.parse(raw || '{}');
    } catch {
      return {};
    }
  }

  function report(type, message, detail = {}) {
    try {
      fetch('/api/client-diagnostic', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          type,
          message: String(message || '').slice(0, 5000),
          detail: {
            ...detail,
            media: safeMediaInfo(),
            userAgent: navigator.userAgent
          }
        }),
        keepalive: true
      }).catch(() => {});
    } catch {}
  }

  const originalNativeError = window.__englishTutorNativeMediaError;
  if (typeof originalNativeError === 'function') {
    window.__englishTutorNativeMediaError = (requestId, message) => {
      report('native-audio-error', message, { requestId });
      return originalNativeError(requestId, message);
    };
  }

  window.addEventListener('unhandledrejection', event => {
    const reason = event.reason;
    const message = reason?.stack || reason?.message || String(reason || 'unhandled rejection');
    if (/media|audio|transform|extract|gemini/i.test(message)) {
      report('client-unhandled-rejection', message);
    }
  });

  window.EnglishTutorDiagnostics = { report };
})();
