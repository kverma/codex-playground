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

## Reveal-native authoring

RevealSwift uses ordinary Reveal.js HTML as the authoring source of truth. Agents do not need to learn a second deck schema.

Create a deck structure:

```bash
revealswift deck create atlas.html \
  --title "Atlas Architecture Review" \
  --theme dark-mode \
  --outline title,context,architecture,metrics,risks,conclusion
```

Or create a fixed number of slides:

```bash
revealswift deck create deck.html --title "Quarterly Review" --theme apple-inspired --slides 8
```

Inspect the structure:

```bash
revealswift deck info atlas.html
revealswift deck outline atlas.html
revealswift slide list atlas.html
revealswift slide get atlas.html architecture
```

Fill a slide with normal Reveal.js HTML:

```bash
revealswift slide set atlas.html architecture --stdin <<'HTML'
<h2>Architecture</h2>
<div class="two-column">
  <div class="card fragment" data-rs-component="card">
    <h3>CompiledState</h3>
    <p>Normalized commercial intent.</p>
  </div>
  <div class="card fragment" data-rs-component="card">
    <h3>ServingState</h3>
    <p>Materialized storefront representation.</p>
  </div>
</div>
HTML
```

Structural editing:

```bash
revealswift slide add atlas.html --id rollout --after risks --html '<h2>Rollout</h2>'
revealswift slide move atlas.html rollout --before conclusion
revealswift slide duplicate atlas.html rollout --id rollout-backup
revealswift slide rename atlas.html rollout-backup --id appendix
revealswift slide remove atlas.html appendix
revealswift deck validate atlas.html
```

Incremental edits avoid replacing a whole slide:

```bash
revealswift slide prepend atlas.html architecture --html '<p class="eyebrow">System design</p>'
revealswift slide append atlas.html architecture --html '<p class="caption">Updated by the agent</p>'

revealswift element get atlas.html architecture '.two-column' --outer
revealswift element set-text atlas.html architecture 'h2' --text 'System Architecture'
revealswift element set-html atlas.html architecture '.callout' --html '<strong>New callout</strong>'
revealswift element add-class atlas.html architecture '.two-column' --class 'review-grid'
revealswift element remove-class atlas.html architecture '.two-column' --class 'review-grid'
revealswift element remove atlas.html architecture '.obsolete-note'
```

Element selectors are always scoped to the selected slide. The slide selector may be a top-level slide ID/index or a vertical child slide ID.

Reveal vertical stacks are first-class:

```bash
revealswift stack create atlas.html --id architecture-deep-dive --after architecture

revealswift stack add atlas.html architecture-deep-dive \
  --id architecture-overview \
  --html '<h2>Overview</h2><p>High-level architecture.</p>'

revealswift stack add atlas.html architecture-deep-dive \
  --id architecture-details \
  --html '<h2>Details</h2><p>Implementation detail.</p>'

revealswift stack list atlas.html architecture-deep-dive

revealswift stack move atlas.html architecture-deep-dive architecture-details \
  --before architecture-overview

revealswift stack remove atlas.html architecture-deep-dive architecture-overview
```

Vertical children retain normal Reveal.js nested-`<section>` semantics. Content-oriented commands such as `slide get`, `slide set`, `slide append`, and `slide prepend` can address a vertical child by stable ID, while top-level structural move/remove commands remain stack-safe.

Stable section IDs are recommended because they let agents edit by meaning instead of by slide number. Numeric 1-based slide selectors are also accepted.

A theme selected with `deck create --theme` is stored in the HTML as a `revealswift-theme` meta tag. Review/export commands automatically resolve that theme from `./Themes/<name>`, a deck-local themes directory, or the themes directory packaged beside the binary. An explicit `--theme <dir>` still overrides it.

Then run the existing QA/render pipeline without converting formats:

```bash
revealswift review atlas.html --output review --strict --pdf
```

The authoring DOM parser treats deck HTML as inert text while making structural edits, so scripts inside the deck are preserved but not executed by edit commands.

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


## Included reference themes

The repository and CI package include these ready-to-use themes:

- `minimal-dark` — original minimal technical dark theme
- `dark-mode` — higher-contrast general dark mode
- `apple-inspired` — spacious, restrained, system-typography presentation style
- `google-inspired` — Material-like cards, bright accents, and information-forward layouts
- `amazon-inspired` — navy/orange operational and commerce-oriented presentation style

The Apple, Google, and Amazon themes are visual-style references only; they do not embed company logos or proprietary brand assets.

CI renders representative decks from `Examples/themes/` with `--strict`, so theme changes are checked for clipping, overlap, density, safe margins, typography, semantic components, and screenshot generation.

## Theme contract

A theme directory contains `theme.json` plus CSS files. The manifest defines the logical slide width/height and quality thresholds. RevealSwift passes that exact viewport into Reveal so screenshots, QA geometry, and PDF export use the same coordinate system.

Agents should prefer semantic classes such as `.card`, `.metric`, `.two-column`, and `.chart` over inline visual styling.

## Runtime packaging

Reveal.js, Chart.js, Markdown, Highlight, Notes, Search and Zoom are vendored into generated Swift source and decoded by the runtime. The macOS renderer serves those bytes directly to WKWebView through `revealswift://runtime/*`.

Reveal Math's plugin bundle is retained for compatibility, but KaTeX/MathJax engines and LaTeX equation rendering are intentionally out of scope.
