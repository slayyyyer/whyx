// Progressive enhancement: a copy button on every .code block.
(() => {
  for (const block of document.querySelectorAll('.code')) {
    const pre = block.querySelector('pre');
    const title = block.querySelector('.code-title');
    if (!pre || !title || !navigator.clipboard) continue;

    const button = document.createElement('button');
    button.type = 'button';
    button.className = 'copy';
    button.textContent = 'copy';
    button.setAttribute('aria-label', 'Copy this code');
    button.addEventListener('click', async () => {
      try {
        await navigator.clipboard.writeText(pre.innerText);
        button.textContent = 'copied';
      } catch {
        button.textContent = 'failed';
      }
      setTimeout(() => { button.textContent = 'copy'; }, 1400);
    });
    title.append(button);
  }
})();
