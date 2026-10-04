# RevealSwift Architecture

## 1. Purpose

RevealSwift is an agent-oriented presentation runtime and toolchain for Reveal.js on macOS.

Its key property is that an agent can create, inspect, render, review, correct, and export a Reveal.js deck **without requiring Node, npm, Playwright, Chrome, a local web server, or runtime network access**.

Reveal.js HTML remains the presentation language.

---

## 2. System context

```text
                    ┌───────────────────────┐
                    │    Authoring Agent     │
                    │ writes Reveal.js HTML  │
                    └───────────┬───────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────┐
│                      RevealSwift                         │
│                                                         │
│  Authoring ──► WebKit Runtime ──► Deterministic QA      │
│                     │                    │               │
│                     ├── screenshots      ├── report      │
│                     ├── contact sheet    ├── diagnostics │
│                     └── PDF              └── annotations │
└───────────────────────────┬─────────────────────────────┘
                            │
                            ▼
                 review-manifest.json
                 visual-review-prompt.md
                 rendered images
                            │
                            ▼
              ┌─────────────────────────┐
              │ Multimodal Reviewer     │
              │ independent invocation  │
              └────────────┬────────────┘
                           │
                           ▼
                  visual-review.json
                           │
                           ▼
              RevealSwift validation
                           │
                           ▼
                    Authoring Agent
```

---

## 3. Architectural principles

### Reveal-native authoring

Deck HTML is the durable source representation.

RevealSwift does not compile a proprietary presentation schema into Reveal HTML.

### Offline execution

Runtime dependencies are embedded in the executable and exposed inside WebKit through:

```text
revealswift://runtime/...
```

No socket or HTTP listener is required.

### Deterministic before subjective

Objective problems are found by deterministic DOM/render inspection.

Subjective visual quality is delegated to an independent multimodal reviewer through an explicit artifact contract.

### Exact render-state identity

A “slide” is not enough to identify a rendered state. Review identity can include:

- horizontal index;
- vertical index;
- fragment state;
- animation sample time.

Diagnostics and visual-review items retain that identity.

---

## 4. Components

### 4.1 RevealSwiftCore

Portable/shared logic where practical.

#### EmbeddedRuntime

Maps private runtime paths to embedded byte payloads and MIME types.

Current runtime includes pinned Reveal.js and Chart.js assets plus supported Reveal plugins.

#### HTMLInjector

Transforms the user's Reveal-native HTML into a self-contained runtime document by injecting:

- Reveal CSS;
- Highlight CSS;
- embedded runtime scripts;
- theme CSS;
- initialization logic;
- runtime readiness/diagnostic globals.

Injection targets the **outer document closing tags**. This is important because iframe `srcdoc` strings can themselves contain `</head>` or `</body>`.

#### ThemeLoader

Loads `theme.json` and theme CSS.

Theme packages define:

- viewport dimensions;
- appearance;
- QA thresholds;
- optional semantic components.

#### InspectorScript

Runs inside the rendered page and returns deterministic findings.

It evaluates:

- bounds;
- clipping;
- sibling overlap;
- minimum font sizes;
- safe margins;
- density;
- inline styling;
- allowed semantic components;
- theme appearance;
- title presence;
- grid-column limits.

Each issue can include:

- target selector;
- rendered visible text;
- screenshot-space bounds.

#### Models

Versionable machine contracts for:

- review results;
- diagnostics;
- review targets;
- visual-review manifests;
- visual-review results.

#### ReportScoring

Aggregates slide metrics/findings into review summaries and scores.

---

### 4.2 Executable layer

#### CLI / WebKitRunner

Owns native rendering and artifacts.

Responsibilities include:

- create `WKWebView`;
- register the private runtime scheme;
- inject and load deck HTML;
- wait for Reveal initialization;
- enumerate Reveal render states;
- control fragments;
- sample web animations;
- inspect deterministic QA;
- capture PNGs;
- build contact sheets;
- build screenshot-fidelity PDF;
- create annotated diagnostic images;
- write reports/manifests.

#### Authoring

Uses an inert DOM parser to edit Reveal HTML without executing deck scripts.

Supported operation families:

- deck create/info/outline/validate/set-theme;
- slide get/set/add/remove/move/duplicate/rename;
- slide append/prepend;
- element get/set-text/set-html/remove/add-class/remove-class;
- vertical stack create/list/add/move/remove.

Top-level slide structure and vertical-stack child structure use separate mutation commands intentionally.

#### Conformance

Executable browser-level assertions proving Reveal behavior in the actual production renderer.

The suite covers core Reveal behavior, supported plugins, navigation, fragments, vertical stacks, animation, backgrounds, Markdown, Highlight, Notes, Search, Zoom, Chart.js, media, layout helpers, visibility, lazy media, public APIs, and custom plugins.

Math rendering is intentionally excluded.

#### VisualReview

Creates the model-neutral multimodal reviewer handoff.

It emits:

- reviewer role;
- instructions;
- review criteria;
- queue of images;
- exact render-state identity;
- deterministic diagnostic references;
- required structured output contract.

It also validates reviewer-produced `visual-review.json`.

---

## 5. Runtime lifecycle

```text
input Reveal HTML
      │
      ▼
ThemeLoader
      │
      ▼
HTMLInjector
      │
      ├── embedded Reveal runtime URLs
      ├── theme CSS
      └── initialization bridge
      │
      ▼
WKWebView
      │
      ▼
Reveal.initialize()
      │
      ▼
__revealswiftReady
      │
      ├── enumerate slides/fragments
      ├── set animation sample
      ├── run InspectorScript
      ├── snapshot
      └── export
```

Reveal initialization exposes runtime diagnostics including Reveal/Chart versions and slide counts.

---

## 6. Render-state model

A deck can produce multiple states per conceptual slide.

For a state:

```text
(index, h, v, fragmentState, animationTimeMs?)
```

RevealSwift navigates by actual Reveal indices rather than synthesizing mouse clicks.

Fragments:

- initial state maps to Reveal fragment index `-1`;
- subsequent states advance through Reveal fragment indices.

Animation samples are explicitly paused/seeked through the Web Animations API.

This makes state capture deterministic.

---

## 7. Authoring model

### Creation

```bash
revealswift deck create deck.html \
  --title "Architecture Review" \
  --theme dark-mode \
  --outline title,context,architecture,conclusion
```

The result is ordinary HTML with stable `<section id="...">` elements.

### Mutation

Agents can replace a whole slide or make surgical edits.

```bash
revealswift slide set deck.html architecture --stdin
revealswift slide append deck.html architecture --html '<p>...</p>'
revealswift element set-text deck.html architecture 'h2' --text 'New title'
```

### Vertical stacks

Reveal nested sections are supported directly:

```html
<section id="deep-dive">
  <section id="deep-dive-overview">...</section>
  <section id="deep-dive-details">...</section>
</section>
```

`stack` commands preserve this structural distinction.

---

## 8. Theme architecture

A theme is a directory such as:

```text
Themes/dark-mode/
├── theme.json
└── theme.css
```

Themes may define semantic component vocabulary and deterministic QA limits.

Current built-ins include:

- minimal-dark;
- dark-mode;
- apple-inspired;
- google-inspired;
- amazon-inspired.

Brand-inspired themes intentionally contain no proprietary brand assets.

A deck can store its theme name in:

```html
<meta name="revealswift-theme" content="dark-mode">
```

The CLI resolves that theme automatically from supported theme locations; explicit `--theme` overrides it.

---

## 9. Deterministic QA

Each rendered state produces `SlideMetrics`.

Findings include rule, severity, message, optional measured amount, and target metadata.

Important rules include:

- `layout.outOfBounds`
- `layout.clipped`
- `layout.overlap`
- `theme.fontTooSmall`
- `theme.safeMargin`
- `theme.inlineStyle`
- `theme.tooManyColumns`
- `theme.unknownComponent`
- `theme.missingTitle`
- `theme.appearanceMismatch`
- `density.high`
- `density.excessive`

Density uses union-like grid coverage of meaningful visual boxes rather than naively summing all DOM descendants. This avoids treating nested Highlight.js/Chart/SVG structure as visual crowding.

Intentional framework behavior can be locally exempted with narrowly scoped attributes such as:

- `data-rs-inline-ok`
- `data-rs-bleed="allow"`
- `data-rs-overlap="allow"`

---

## 10. Diagnostics

A review output includes:

```text
review/
├── report.json
├── diagnostics.json
├── diagnostics/
├── screenshots/
├── contact-sheet.png
├── review-manifest.json
└── visual-review-prompt.md
```

A diagnostic finding has a stable ID encoding the render state and rule.

Example:

```text
diag-s005-h04-v00-state-02-t0250-layout-overlap-01
```

A finding contains:

- slide identity;
- fragment/animation identity;
- rule/severity/message;
- selector(s);
- visible text;
- bounds;
- suggested fix;
- agent-oriented feedback;
- annotated screenshot path.

Annotated diagnostic PNGs are derived from the same state screenshot used by QA.

---

## 11. Multimodal visual review

RevealSwift does **not** decide whether a deck is aesthetically good.

Instead it emits a reviewer queue.

### review-manifest.json

Includes:

- `schemaVersion`;
- reviewer role;
- instructions;
- visual criteria;
- every captured render state;
- contact sheet;
- annotated diagnostic states;
- expected output schema.

### visual-review-prompt.md

Human/model-readable instructions generated from the same contract.

### visual-review.json

Written by an independent multimodal reviewer.

Each visual finding references an exact `reviewItemID` and includes:

- severity;
- category;
- slide identity;
- description;
- visible evidence;
- suggested fix.

### Validation

```bash
revealswift visual-review validate review/visual-review.json --strict
```

Validation rejects unknown review items, mismatched slide identity, duplicate IDs, empty evidence/fixes, unsupported categories, and logically inconsistent decisions.

`--strict` fails until the reviewer returns `pass`.

---

## 12. Export

### Screenshots

PNG per slide/fragment/animation sample.

### Contact sheet

Native AppKit composition of canonical slide images for deck-level visual inspection.

### PDF

Current PDF mode is **fidelity-first raster PDF**: canonical screenshots are placed on PDF pages using CoreGraphics.

This preserves WebKit appearance but does not guarantee selectable/vector text.

A future vector mode can be additive; do not replace fidelity mode without equivalent regression coverage.

---

## 13. Plugin/runtime support

Behavioral coverage currently includes supported Reveal features/plugins such as:

- Markdown;
- Highlight;
- Notes;
- Search;
- Zoom;
- custom plugins;
- Chart.js;
- fragments;
- vertical stacks;
- auto-animate;
- backgrounds;
- embedded media;
- state classes;
- overview;
- pause;
- scroll view;
- lazy media;
- layout helpers;
- public Reveal APIs.

Reveal Math engine rendering is intentionally excluded.

The conformance suite is the authoritative executable support map; `docs/FEATURE_MAP.md` is the human/agent discoverability map.

---

## 14. CI architecture

`.github/workflows/revealswift-ci.yml` is a release gate.

Linux:

- Swift tests for portable/core code.

macOS:

- Swift tests;
- release build;
- theme validation;
- demo review + PDF;
- plugin smoke;
- deterministic diagnostics contract;
- multimodal review-manifest contract;
- visual-review result validation;
- Reveal behavioral conformance;
- strict full conformance render;
- agent authoring workflow;
- reference-theme reviews;
- Universal arm64+x86_64 packaging;
- artifact upload.

The conformance checker requires named behaviors rather than only a total pass count.

---

## 15. Failure boundaries

### Browser-runtime failure

Examples:

- Reveal never initializes;
- private runtime asset cannot load;
- WebKit JavaScript error.

Fail command execution.

### QA failure

Artifacts are written first. Strict modes then return nonzero.

### Visual-review failure

The external reviewer can return `needs_changes`; `visual-review validate --strict` then returns nonzero.

### Unsupported feature

Prefer explicit documentation/validation over silent degradation.

Math rendering is the current intentional example.

---

## 16. Security and trust boundaries

- Structural authoring treats deck HTML as inert text.
- Rendering necessarily executes deck JavaScript inside WKWebView.
- Runtime assets are local/pinned.
- Reviewer model invocation is outside the RevealSwift binary.
- Reviewer output is treated as untrusted structured input and validated against the manifest.
- External URLs inside a user deck may still carry their own network/security implications; core RevealSwift runtime must not require them.

---

## 17. Extension rules

When adding a feature:

1. Prefer native Reveal.js behavior.
2. Keep HTML as source of truth.
3. Expose a small composable CLI surface if agents benefit.
4. Add deterministic identifiers.
5. Add unit tests where useful.
6. Add native WebKit E2E assertions for rendered behavior.
7. Add visual artifact coverage if appearance can regress.
8. Add the capability to `docs/FEATURE_MAP.md`.
9. Update architecture decisions if an invariant changes.
