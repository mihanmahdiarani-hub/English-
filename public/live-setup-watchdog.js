(() => {
  const BaseWebSocket = window.WebSocket;
  if (!BaseWebSocket || BaseWebSocket.__englishTutorSetupWatchdog) return;

  function compactInstruction(text) {
    const original = String(text || '');
    if (original.length <= 7000) return original;

    const sourceMatch = original.match(/LESSON SOURCE START\s*([\s\S]*?)\s*LESSON SOURCE END/i);
    const methodMatch = original.match(/AUTHOR METHOD PROFILE[\s\S]*?(?=\nBOOK:|\nLESSON:|\nPROGRESS:|$)/i);
    const sessionRuleMatch = original.match(/SESSION RULE:\s*([^\n]+)/i);
    const source = String(sourceMatch?.[1] || '').trim().slice(0, 4800);
    const method = String(methodMatch?.[0] || '').trim().slice(0, 1200);
    const sessionRule = String(sessionRuleMatch?.[1] || '').trim().slice(0, 300);
    const mode = document.querySelector('#modePicker button.active')?.dataset?.mode || 'teacher';
    const language = document.querySelector('#languagePicker button.active')?.dataset?.language === 'persian' ? 'persian' : 'english';

    const compact = `You are a source-grounded live English tutor.\nMODE: ${mode}\nTEACHER LANGUAGE: ${language}\nRules:\n- In Teacher and Shadowing, use ONLY the supplied lesson source as curriculum authority. Never invent missing lesson content.\n- Ask only ONE question at a time and wait for the learner.\n- Correct important grammar, vocabulary, word order, and pronunciation briefly.\n- In Shadowing, say one short English sentence, wait for repetition, give concise feedback, then continue.\n- In Free Talk, converse naturally in English while using the learner's studied language when possible.\n- For movie/audio lessons, never invent visual facts. If audible context is insufficient, ask one short clarifying question.\n- Keep English clear and slightly slower than native speed.\n${language === 'persian' ? '- Use Persian for teacher guidance and feedback, but keep target English practice in English.\n' : '- Use English for teacher guidance and feedback unless the learner explicitly asks for Persian help.\n'}${sessionRule ? `SESSION RULE: ${sessionRule}\n` : ''}${method ? `\n${method}\n` : ''}\nLESSON SOURCE START\n${source || '[No source text supplied]'}\nLESSON SOURCE END\nStart the session now and follow the selected mode.`;

    console.log(`[live-watchdog] compacted setup instruction ${original.length} -> ${compact.length} chars`);
    return compact;
  }

  class LiveSetupWatchdogWebSocket extends BaseWebSocket {
    constructor(url, protocols) {
      protocols === undefined ? super(url) : super(url, protocols);
      this.__etLive = false;
      this.__etSetupDone = false;
      this.__etWatchdog = null;
      try {
        const u = new URL(String(url), location.href);
        this.__etLive = u.pathname === '/live';
      } catch {}

      if (this.__etLive) {
        this.addEventListener('message', (event) => {
          try {
            const msg = JSON.parse(String(event.data || ''));
            if (msg?.setupComplete) {
              this.__etSetupDone = true;
              if (this.__etWatchdog) clearTimeout(this.__etWatchdog);
              this.__etWatchdog = null;
            }
          } catch {}
        });
        const stop = () => {
          if (this.__etWatchdog) clearTimeout(this.__etWatchdog);
          this.__etWatchdog = null;
        };
        this.addEventListener('close', stop);
        this.addEventListener('error', stop);
      }
    }

    send(data) {
      let outgoing = data;
      let isSetup = false;
      if (this.__etLive && typeof data === 'string') {
        try {
          const obj = JSON.parse(data);
          if (obj?.setup) {
            isSetup = true;
            const part = obj.setup?.systemInstruction?.parts?.[0];
            if (part && typeof part.text === 'string') part.text = compactInstruction(part.text);
            outgoing = JSON.stringify(obj);
          }
        } catch {}
      }

      const result = super.send(outgoing);
      if (!isSetup || this.__etSetupDone || this.__etWatchdog) return result;

      this.__etWatchdog = setTimeout(() => {
        if (this.__etSetupDone || this.readyState !== BaseWebSocket.OPEN) return;
        console.warn('[live-watchdog] setupComplete not received within 12s; closing stalled browser socket');
        if (typeof addMessage === 'function') {
          addMessage('error', 'Gemini پاسخ شروع کلاس را نداد. اتصال بسته شد؛ دوباره «شروع کلاس زنده» را بزن.');
        }
        try { this.close(1011, 'Live setup timeout'); } catch {}
      }, 12000);
    }
  }

  LiveSetupWatchdogWebSocket.__englishTutorSetupWatchdog = true;
  window.WebSocket = LiveSetupWatchdogWebSocket;
})();
