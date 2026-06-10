# Showcase / user-guide site

The static site published to GitHub Pages from this folder
(`.github/workflows/pages.yml`). It doubles as a feature showcase and a short
user guide.

- `index.html` — the page (hand-written, no build step).
- `style.css` — all styling.
- `assets/` — images. `lvgl-completion.png` is a real editor capture; the other
  capability panes are CSS-rendered mock editors so the look stays consistent
  without a screenshot for every feature.

## Previewing locally

```bash
python3 -m http.server -d site 8000   # then open http://localhost:8000
```

## Swapping in real screenshots

To replace a CSS mock with a real capture, drop a PNG in `assets/` and replace
the matching `<div class="editor editor--flat">…</div>` block in `index.html`
with:

```html
<figure class="shot">
  <img src="assets/your-shot.png" alt="describe it" loading="lazy">
  <figcaption>Short caption</figcaption>
</figure>
```

Capture from a clean sandbox IDE (`./gradlew :plugin:runIde`) at a high DPI,
crop tight to the editor, and keep widths ≥ 1000 px. The Marketplace **Install**
button is wired to plugin id `32186` via JetBrains' widget script with a plain
link fallback.
