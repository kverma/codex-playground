# RevealSwift

RevealSwift is a macOS Swift CLI for rendering, validating, screenshotting, reviewing and exporting Reveal.js decks without requiring Node/npm/Playwright on the target machine.

## What v0.1 does

- injects pinned Reveal.js 6.0.2 and Chart.js 4.5.1 into inline HTML decks
- applies shared CSS themes with machine-readable theme rules
- renders with native `WKWebView`
- captures every Reveal fragment state
- optionally samples Web Animation timelines with `--animation-frames`
- detects out-of-bounds content, clipping, sibling overlaps, high density, undersized type and inline-style theme escapes
- emits agent-readable `report.json`
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
revealswift review Examples/demo.html --theme Themes/minimal-dark --output .review --pdf
```

`review` writes a JSON report plus PNGs for each slide/fragment/animation state and optionally a PDF.

## Theme contract

A theme directory contains `theme.json` and CSS files. Agents should prefer semantic classes such as `.card`, `.metric`, `.two-column`, and `.chart` over inline visual styling.

## Runtime packaging note

The current source implementation loads pinned browser builds of Reveal.js/Chart.js from jsDelivr at runtime. This removes Node/npm but still requires network access. The renderer boundary is isolated so the next release can replace those URLs with generated embedded Swift assets and produce a fully offline single-file binary without changing deck HTML or CLI commands.
