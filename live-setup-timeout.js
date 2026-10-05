'use strict';

// Force the existing server-side key failover to activate when Gemini opens a
// WebSocket but never answers the setup frame. server.js already switches to
// the next API key when an upstream socket closes before setupComplete.
const wsModule = require('ws');
const OriginalWebSocket = wsModule.WebSocket || wsModule;

class SetupTimeoutWebSocket extends OriginalWebSocket {
  constructor(address, protocols, options) {
    if (options !== undefined) super(address, protocols, options);
    else if (protocols !== undefined) super(address, protocols);
    else super(address);

    const url = String(address || '');
    if (!url.includes('generativelanguage.googleapis.com/ws/') || !url.includes('BidiGenerateContent')) return;

    let timer = null;
    let forced = false;
    const clear = () => {
      if (timer) clearTimeout(timer);
      timer = null;
    };

    this.on('open', () => {
      clear();
      timer = setTimeout(() => {
        if (this.readyState !== OriginalWebSocket.OPEN) return;
        forced = true;
        console.warn('[live-timeout] Gemini connected but setupComplete was not received within 8s; forcing upstream close so server failover can try the next key');
        try {
          this.close(1011, 'Gemini setup timeout');
        } catch {
          try { this.terminate(); } catch {}
        }
        setTimeout(() => {
          if (forced && this.readyState !== OriginalWebSocket.CLOSED) {
            try { this.terminate(); } catch {}
          }
        }, 1200).unref?.();
      }, 8000);
      timer.unref?.();
    });

    this.on('message', data => {
      if (!timer) return;
      try {
        const msg = JSON.parse(data.toString());
        if (msg && msg.setupComplete) {
          forced = false;
          clear();
        }
      } catch {}
    });

    this.on('close', clear);
    this.on('error', clear);
  }
}

// server.js destructures the WebSocket property from require('ws').
wsModule.WebSocket = SetupTimeoutWebSocket;
console.log('Gemini Live setup timeout guard active (8s -> existing key failover)');
