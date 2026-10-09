/* UI preferences only. No lesson, media, Gemini, or update logic is changed. */
(() => {
  'use strict';
  const storageKey = 'english-ai-tutor-ui-theme';
  const html = document.documentElement;
  const systemDark = window.matchMedia('(prefers-color-scheme: dark)');
  const choices = ['system', 'light', 'dark'];
  let preference = 'system';
  try {
    const saved = localStorage.getItem(storageKey);
    if (choices.includes(saved)) preference = saved;
  } catch (_) { /* private mode / unavailable storage */ }

  const actualTheme = () => preference === 'system'
    ? (systemDark.matches ? 'dark' : 'light') : preference;

  function applyTheme() {
    const resolved = actualTheme();
    html.dataset.eatTheme = resolved;
    html.dataset.eatPreference = preference;
    html.style.colorScheme = resolved;
    const meta = document.querySelector('meta[name="theme-color"]');
    if (meta) meta.setAttribute('content', resolved === 'dark' ? '#100d27' : '#f7f7ff');
    document.querySelectorAll('[data-eat-choice]').forEach(button => {
      const selected = button.dataset.eatChoice === preference;
      button.setAttribute('aria-pressed', String(selected));
      button.classList.toggle('is-selected', selected);
    });
    document.querySelectorAll('.eat-theme-label').forEach(label => {
      label.textContent = preference === 'system' ? 'تم خودکار' : preference === 'light' ? 'تم روشن' : 'تم تاریک';
    });
  }

  function setPreference(next) {
    if (!choices.includes(next)) return;
    preference = next;
    try { localStorage.setItem(storageKey, preference); } catch (_) {}
    applyTheme();
  }

  // Apply the theme before the page starts rendering.
  applyTheme();
  if (systemDark.addEventListener) systemDark.addEventListener('change', applyTheme);
  else if (systemDark.addListener) systemDark.addListener(applyTheme);

  function createThemeMenu() {
    const element = document.createElement('details');
    element.className = 'eat-theme-menu';
    element.innerHTML = `
      <summary aria-label="انتخاب تم برنامه" title="تنظیم ظاهر">
        <span class="eat-theme-sun" aria-hidden="true">◐</span>
        <span class="eat-theme-label">تم خودکار</span>
        <span class="eat-theme-chevron" aria-hidden="true">⌄</span>
      </summary>
      <div class="eat-theme-options" role="group" aria-label="حالت نمایش">
        <div class="eat-menu-title">ظاهر برنامه</div>
        <button type="button" data-eat-choice="system" aria-pressed="true"><span aria-hidden="true">◉</span><span>هماهنگ با گوشی</span></button>
        <button type="button" data-eat-choice="light" aria-pressed="false"><span aria-hidden="true">☀</span><span>روشن</span></button>
        <button type="button" data-eat-choice="dark" aria-pressed="false"><span aria-hidden="true">☾</span><span>تاریک</span></button>
      </div>`;
    element.querySelectorAll('[data-eat-choice]').forEach(button => {
      button.addEventListener('click', () => {
        setPreference(button.dataset.eatChoice);
        element.open = false;
      });
    });
    return element;
  }

  function enhanceHome() {
    const chooser = document.querySelector('.ux-flow-card');
    if (!chooser || chooser.querySelector('.eat-path-chat')) return;
    const hero = chooser.querySelector('.ux-flow-hero');
    if (hero) {
      const art = document.createElement('div');
      art.className = 'eat-hero-art';
      art.setAttribute('aria-hidden', 'true');
      art.innerHTML = '<span class="eat-orbit eat-orbit-one"></span><span class="eat-orbit eat-orbit-two"></span><span class="eat-bot">✦</span><span class="eat-spark">✧</span>';
      hero.appendChild(art);
    }
    const grid = chooser.querySelector('.ux-path-grid');
    if (grid) {
      const chat = document.createElement('a');
      chat.className = 'ux-path-card eat-path-chat';
      chat.href = 'gemini-chat.html';
      chat.innerHTML = '<span class="ux-path-icon" aria-hidden="true">✦</span><span class="ux-path-title">گفتگو با Gemini</span><span class="ux-path-sub">مکالمه آزاد و تمرین انگلیسی با هوش مصنوعی</span><span class="eat-path-arrow" aria-hidden="true">↗</span>';
      grid.appendChild(chat);
    }
  }

  function setup() {
    const host = document.querySelector('.topbar') || document.querySelector('.gem-options');
    if (host && !host.querySelector('.eat-theme-menu')) host.appendChild(createThemeMenu());
    enhanceHome();
    applyTheme();
    document.addEventListener('click', event => {
      const menu = document.querySelector('.eat-theme-menu');
      if (menu && !menu.contains(event.target)) menu.open = false;
    });
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', setup, {once:true});
  else setup();
})();