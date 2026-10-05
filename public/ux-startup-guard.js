(() => {
  // ux-flow.js builds the wizard UI. This guard makes the chooser the only
  // visible initial state, even when old localStorage progress exists.
  const chooser = document.querySelector('.ux-flow-card');
  const source = document.querySelector('.source-card');
  const tutor = document.querySelector('.tutor-card');
  const progress = document.querySelector('.progress-card');
  if (!chooser || !source || !tutor || !progress) return;

  document.body.classList.add('ux-flow-active');
  document.body.classList.remove('ux-session-active');
  chooser.classList.remove('ux-hidden');
  source.classList.add('ux-hidden');
  tutor.classList.add('ux-hidden');
  progress.classList.add('ux-hidden');
  document.querySelector('.ux-session-summary')?.classList.add('ux-hidden');
  window.scrollTo({ top: 0, behavior: 'auto' });
})();
