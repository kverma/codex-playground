# RevealSwift

RevealSwift is a macOS Swift CLI for rendering, validating, screenshotting, reviewing and exporting Reveal.js decks without requiring Node, npm, Playwright, Chrome, or a network connection on the target machine.

## What v0.1 does

- embeds pinned Reveal.js 6.0.2, Chart.js 4.5.1, and common Reveal plugins inside the Swift executable
- loads the browser runtime through a private `revealswift://` WebKit scheme; no CDN is required
- applies shared CSS themes with machine-readable viewport and quality rules
- renders with native `WKWebView`
- captures every Reveal fragment state and optionally samples Web Animation timelines
- detects out-of-bounds content, clipping, sibling overlaps, high density, undersized type, and inline-style theme escapes
- emits agent-readable `report.json`
- produces a whole-deck `contact-sheet.png` for multimodal review
- exports screenshot-fidelity PDF pages

## Build

```bash
cd revealswift
swift build -c release
```

## Commands

```bash
revealswift version
revealswift themes validate Themes/minimal-dark
revealswift inspect Examples/demo.html --theme Themes/minimal-dark
revealswift screenshots Examples/demo.html --theme Themes/minimal-dark --output screenshots
revealswift screenshots Examples/demo.html --theme Themes/minimal-dark --animation-frames 0,250,500,1000
revealswift pdf Examples/demo.html --theme Themes/minimal-dark --output deck.pdf
revealswift review Examples/demo.html --theme Themes/minimal-dark --output .review --animation-frames 0,250,500 --pdf
```

`review` writes `report.json`, a contact sheet, PNGs for slide/fragment/animation states, and optionally a PDF. Add `--fail-on-errors` to exit non-zero for structural failures, or `--strict` to fail on either errors or warnings after the artifacts are written.

## Theme contract

A theme directory contains `theme.json` plus CSS files. The manifest defines the logical slide width/height and quality thresholds. RevealSwift passes that exact viewport into Reveal so screenshots, QA geometry, and PDF export use the same coordinate system.

Agents should prefer semantic classes such as `.card`, `.metric`, `.two-column`, and `.chart` over inline visual styling.

## Runtime packaging

Reveal.js, Chart.js, Markdown, Highlight, Notes, Search and Zoom are vendored into generated Swift source and decoded by the runtime. The macOS renderer serves those bytes directly to WKWebView through `revealswift://runtime/*`.

Math.js is vendored as well, but offline equation rendering still requires bundling a math engine (KaTeX/MathJax) before the math plugin is enabled by default.
