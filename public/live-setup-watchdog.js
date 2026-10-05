(() => {
  const BaseWebSocket = window.WebSocket;
  if (!BaseWebSocket || BaseWebSocket.__englishTutorSetupWatchdog) return;

  class LiveSetupWatchdogWebSocket extends BaseWebSocket {
    constructor(url, protocols) {
      protocols === undefined ? super(url) : super(url, protocols);
      this.__etLive = false;
      this.__etSetupDone = false;
      this.__etWatchdog = null;
      this.__etKickCount = 0;
      try {
        const u = new URL(String(url), location.href);
        this.__etLive = u.pathname === '/live' || /onrender\.com$/i.test(u.hostname) && u.pathname === '/live';
      } catch {}

      if (this.__etLive) {
        this.addEventListener('message', (event) => {
          try {
            const msg = JSON.parse(String(event.data || ''));
            if (msg?.setupComplete) {
              this.__etSetupDone = true;
              if (this.__etWatchdog) clearInterval(this.__etWatchdog);
              this.__etWatchdog = null;
            }
          } catch {}
        });
        const stop = () => {
          if (this.__etWatchdog) clearInterval(this.__etWatchdog);
          this.__etWatchdog = null;
        };
        this.addEventListener('close', stop);
        this.addEventListener('error', stop);
      }
    }

    send(data) {
      const result = super.send(data);
      if (!this.__etLive || this.__etSetupDone || this.__etWatchdog) return result;
      let isSetup = false;
      try {
        const obj = typeof data === 'string' ? JSON.parse(data) : null;
        isSetup = Boolean(obj?.setup);
      } catch {}
      if (!isSetup) return result;

      this.__etWatchdog = setInterval(() => {
        if (this.__etSetupDone || this.readyState !== BaseWebSocket.OPEN) return;
        this.__etKickCount += 1;
        if (this.__etKickCount === 1 && typeof addMessage === 'function') {
          addMessage('system', 'Gemini دیر پاسخ داد؛ در حال سوییچ خودکار به اتصال پشتیبان…');
        }
        console.warn(`[live-watchdog] no setupComplete after ${this.__etKickCount * 8}s; forcing upstream protocol close to activate server key failover`);
        try {
          // server.js preserves the original setup frame and retries it on the next key.
          // This intentionally malformed second frame makes a stuck Gemini upstream close.
          super.send('{');
        } catch {}
      }, 8000);
    }
  }

  LiveSetupWatchdogWebSocket.__englishTutorSetupWatchdog = true;
  window.WebSocket = LiveSetupWatchdogWebSocket;
})();
