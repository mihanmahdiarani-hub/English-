(() => {
  const bridge = window.AndroidMedia;
  if (!bridge || typeof bridge.startLiveMic !== 'function') return;
  if (typeof startMic !== 'function' || typeof cleanupMedia !== 'function' || typeof state !== 'object') return;

  const browserStartMic = startMic;
  const browserCleanupMedia = cleanupMedia;
  const muteButton = document.getElementById('muteBtn');
  const browserMuteHandler = muteButton ? muteButton.onclick : null;

  state.nativeMicActive = false;

  window.__englishTutorNativeMicPcm = (base64Pcm, peak) => {
    if (!state.nativeMicActive || state.muted || !state.ready || !state.ws || state.ws.readyState !== WebSocket.OPEN) return;
    const meter = document.getElementById('meter');
    if (meter) meter.style.width = `${Math.min(100, Math.max(0, Number(peak) || 0) * 220)}%`;
    try {
      state.ws.send(JSON.stringify({
        realtimeInput: {
          audio: { data: String(base64Pcm || ''), mimeType: 'audio/pcm;rate=16000' }
        }
      }));
    } catch (err) {
      console.error('Native mic send failed', err);
    }
  };

  window.__englishTutorNativeMicError = (message) => {
    if (!state.nativeMicActive) return;
    state.nativeMicActive = false;
    try { bridge.stopLiveMic(); } catch (_) {}
    const text = String(message || 'میکروفن Native متوقف شد.');
    if (typeof addMessage === 'function') addMessage('error', text);
    if (state.ws && state.ws.readyState === WebSocket.OPEN) {
      try { state.ws.close(1011, 'Native microphone failed'); } catch (_) {}
    }
  };

  startMic = async function startNativeAndroidMic() {
    let raw;
    try {
      raw = bridge.startLiveMic();
    } catch (err) {
      throw new Error(`Native microphone bridge failed: ${err?.message || err}`);
    }

    let info = {};
    try { info = JSON.parse(String(raw || '{}')); } catch (_) {}
    if (!info.ok) {
      throw new Error(info.error || 'Android نتوانست میکروفن Native را شروع کند.');
    }

    state.nativeMicActive = true;
    state.mediaStream = null;
    state.audioCtx = null;
    state.sourceNode = null;
    state.processor = null;
    console.log(`Native Android microphone active at ${info.sampleRate || 16000} Hz`);
  };

  cleanupMedia = function cleanupNativeAndroidMic() {
    if (state.nativeMicActive) {
      try { bridge.stopLiveMic(); } catch (_) {}
      state.nativeMicActive = false;
    }
    browserCleanupMedia();
  };

  if (muteButton) {
    muteButton.onclick = () => {
      if (!state.nativeMicActive) {
        if (typeof browserMuteHandler === 'function') return browserMuteHandler.call(muteButton);
        return;
      }
      state.muted = !state.muted;
      try { bridge.setLiveMicMuted(state.muted); } catch (_) {}
      muteButton.textContent = state.muted ? 'وصل میکروفن' : 'قطع میکروفن';
    };
  }

  window.addEventListener('beforeunload', () => {
    try { bridge.stopLiveMic(); } catch (_) {}
  });
})();
