// The link remains a server-rendered fallback when JavaScript is disabled.
document.querySelector('#staging-badge .staging-badge-close')?.addEventListener('click', event => {
  if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
  const url = new URL(window.location.href);
  url.searchParams.set('show_badge', '0');
  // Keep HTMX's history entry and the current document/theme intact.
  window.history.replaceState(window.history.state, '', url);
  document.getElementById('staging-badge')?.remove();
  event.preventDefault();
});
