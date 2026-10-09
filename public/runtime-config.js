(() => {
  'use strict';
  const android = location.hostname === 'appassets.androidplatform.net';
  if (!android) return;

  const STORAGE_KEY = 'english-ai-tutor-liara-server-v1';
  const localHost = location.origin;
  const valid = raw => {
    try {
      const u = new URL(String(raw || '').trim());
      if (u.protocol !== 'https:' || !u.hostname.endsWith('.liara.run') ||
          u.hostname === 'liara.run' || u.port || u.username || u.password ||
          (u.pathname !== '/' && u.pathname !== '') || u.search || u.hash) return '';
      return u.origin;
    } catch (_) { return ''; }
  };

  let origin = '';
  try { origin = valid(localStorage.getItem(STORAGE_KEY)); } catch (_) {}
  const config = { localShell: true, get apiOrigin() { return origin; }, shellVersion: 'liara-configurable' };
  window.EnglishTutorConfig = Object.freeze(config);

  const originalFetch = window.fetch.bind(window);
  function remotePath(path) {
    return path === '/health' || path === '/android/latest.apk' || path.startsWith('/api/');
  }
  window.fetch = function(input, init) {
    if (origin) {
      try {
        if (typeof input === 'string' && remotePath(input.split('?')[0])) input = origin + input;
        else if (input instanceof URL && input.origin === localHost && remotePath(input.pathname))
          input = new URL(input.pathname + input.search, origin);
        else if (typeof Request !== 'undefined' && input instanceof Request) {
          const u = new URL(input.url);
          if (u.origin === localHost && remotePath(u.pathname))
            input = new Request(origin + u.pathname + u.search, input);
        }
      } catch (_) {}
    }
    return originalFetch(input, init);
  };

  const OriginalWebSocket = window.WebSocket;
  window.WebSocket = class LiaraWebSocket extends OriginalWebSocket {
    constructor(url, protocols) {
      let target = url;
      if (origin) {
        try {
          const u = new URL(String(url), localHost);
          if (u.hostname === location.hostname && u.pathname === '/live')
            target = 'wss://' + new URL(origin).host + '/live';
        } catch (_) {}
      }
      if (protocols === undefined) super(target);
      else super(target, protocols);
    }
  };

  function configUI() {
    const bar = document.querySelector('.topbar') || document.body;
    const button = document.createElement('button');
    button.type = 'button';
    button.textContent = origin ? '☁ لیارا' : '⚠ اتصال لیارا';
    button.style.cssText = 'border:1px solid #7250a4;border-radius:9px;background:#201632;color:#e5d2ff;padding:9px;font:inherit;font-size:12px;white-space:nowrap';
    button.title = 'تنظیم نشانی سرور English AI Tutor در لیارا';
    button.onclick = () => openDialog();
    bar.appendChild(button);

    const overlay = document.createElement('div');
    overlay.style.cssText = 'position:fixed;inset:0;z-index:10000;background:#060710ed;color:white;display:none;align-items:center;justify-content:center;padding:16px;font-family:Tahoma,Arial,sans-serif';
    const panel = document.createElement('div');
    panel.style.cssText = 'width:min(100%,440px);background:#161725;border:1px solid #585077;border-radius:18px;padding:24px;text-align:right;direction:rtl;box-shadow:0 18px 60px #000b';
    const h = document.createElement('h2');
    h.textContent = 'اتصال اپ به لیارا'; h.style.marginTop = '0';
    const note = document.createElement('p');
    note.textContent = 'آدرس برنامه English AI Tutor در لیارا را وارد کن. مثال: https://your-app.liara.run';
    note.style.cssText = 'font-size:13px;color:#bbc2cf;line-height:1.9';
    const field = document.createElement('input');
    field.type = 'url'; field.dir = 'ltr'; field.autocomplete = 'url';
    field.placeholder = 'https://your-app.liara.run';
    field.value = origin;
    field.style.cssText = 'width:100%;padding:13px;border-radius:10px;background:#090a12;border:1px solid #55516e;color:white;font-size:14px';
    const message = document.createElement('p');
    message.setAttribute('role', 'status');
    message.style.cssText = 'min-height:22px;color:#fa9aad;font-size:12px';
    const actions = document.createElement('div');
    actions.style.cssText = 'display:flex;gap:10px;justify-content:flex-end;flex-wrap:wrap';
    const save = document.createElement('button');
    save.textContent = 'ذخیره و اتصال'; save.type = 'button';
    save.style.cssText = 'border:0;border-radius:10px;background:#8b5cf6;color:white;padding:12px;font:inherit';
    const close = document.createElement('button');
    close.textContent = 'بستن'; close.type = 'button';
    close.style.cssText = 'border:1px solid #55516e;border-radius:10px;background:#202131;color:white;padding:12px;font:inherit';
    close.onclick = () => { if (origin) overlay.style.display = 'none'; else message.textContent = 'برای استفاده آنلاین، نشانی لیارا لازم است.'; };
    save.onclick = () => {
      const next = valid(field.value);
      if (!next) { message.textContent = 'لطفاً آدرس معتبر https://APP.liara.run وارد کن.'; return; }
      try { localStorage.setItem(STORAGE_KEY, next); } catch (_) {
        message.textContent = 'ذخیره تنظیمات دستگاه ممکن نشد.'; return;
      }
      location.reload();
    };
    actions.append(save,close);
    panel.append(h,note,field,message,actions);
    overlay.append(panel);document.body.append(overlay);
    window.openEnglishTutorLiaraSettings = () => openDialog();
    function openDialog() { field.value = origin; message.textContent = ''; overlay.style.display = 'flex'; field.focus(); }
    if (!origin) openDialog();
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded',configUI,{once:true});
  else configUI();
})();
