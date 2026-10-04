# AGENTS.md

This repository currently contains one active project: **RevealSwift** under `revealswift/`.

This file is the operating guide for coding agents working in this repository. Read it before changing code, then read:

1. `revealswift/ARCHITECTURE.md`
2. `revealswift/docs/ARCHITECTURE_DECISIONS.md`
3. `revealswift/docs/FEATURE_MAP.md`
4. `revealswift/README.md`
5. `.github/workflows/revealswift-ci.yml`

When these documents disagree with an abandoned prototype or an old commit, the current documents and tests win.

---

## Mission

RevealSwift is a **native macOS Swift CLI for agent-authored Reveal.js presentations**.

It lets an agent:

- create and structurally edit ordinary Reveal.js HTML;
- render it headlessly with native `WKWebView`;
- exercise fragments, vertical stacks, animation states, charts, backgrounds, and supported plugins;
- run deterministic layout/theme QA;
- capture screenshots, contact sheets, annotated diagnostics, and PDF;
- hand rendered states to an independent multimodal reviewer;
- validate structured visual-review feedback;
- iterate until deterministic QA and visual review both pass.

The target environment may be a restrictive macOS sandbox where Chrome/Playwright cannot create the sockets they require.

---

## Critical invariants

### Reveal.js HTML is the source of truth

Do **not** introduce a proprietary deck DSL or revive `.rsdeck.json`.

Agents already know Reveal.js. RevealSwift should expose safe operations over normal Reveal HTML.

### Reveal.js remains JavaScript

Do not port Reveal.js to Swift.

Swift owns packaging, native WebKit hosting, authoring operations, deterministic QA, screenshots, diagnostics, review handoff, PDF export, and CLI behavior.

### Runtime must remain offline and socketless

The target runtime must not require:

- Node;
- npm;
- Playwright;
- Chrome;
- a localhost HTTP server;
- a runtime CDN;
- network access.

Supported JavaScript assets are embedded in the executable and served through `revealswift://runtime/`.

### Native WebKit is intentional

Rendering uses `WKWebView`.

Do not replace it with Chrome/Playwright as the primary renderer. Avoid adding a browser-launch fallback that changes runtime assumptions.

### Editing must not execute deck JavaScript

Structural authoring uses `DOMParser` on inert HTML text. Preserve scripts and custom Reveal content without executing them during edits.

### Stable IDs are the preferred agent address

Prefer:

```html
<section id="architecture">
```

over “slide 17”.

Numeric 1-based selectors are supported for convenience, but stable IDs are the cross-system identity used by authoring, diagnostics, and reviewer feedback.

### Deterministic QA and subjective visual review stay separate

RevealSwift deterministic QA handles objective rendered rules.

An independent multimodal reviewer handles subjective visual quality.

RevealSwift emits `review-manifest.json` and `visual-review-prompt.md`; the surrounding agent harness chooses the reviewer model. RevealSwift itself must remain model/provider-neutral.

### Math rendering is out of scope

The Reveal Math plugin bundle may remain for compatibility, but KaTeX, MathJax, LaTeX rendering, and math-engine dependencies are intentionally out of scope.

Do not add them back unless the product decision is explicitly revisited.

---

## Do

- **Do read the architecture docs before changing runtime behavior.**
- **Do use ordinary Reveal.js semantics** whenever Reveal already has a feature.
- **Do prefer stable slide and element IDs** in examples and fixtures.
- **Do keep CLI mutations deterministic and script-inert.**
- **Do preserve the offline/private-scheme runtime.**
- **Do add an E2E test for user-visible CLI capabilities.**
- **Do test behavior in native WebKit** when changing Reveal integration.
- **Do keep deterministic diagnostics machine-readable** with stable IDs, selectors, visible text, bounds, and correction guidance.
- **Do keep reviewer feedback structured** and validate `reviewItemID` / slide identity.
- **Do keep visual-review author and reviewer roles separate.**
- **Do preserve exact render-state identity** for fragments, vertical slides, and animation samples.
- **Do use theme semantic classes** such as `.card`, `.metric`, `.two-column`, `.chart`.
- **Do add narrow QA escape hatches only for intentional framework behavior**, e.g. `data-rs-inline-ok`, `data-rs-bleed="allow"`, `data-rs-overlap="allow"`.
- **Do keep escape hatches local to the intended element**; never weaken a global rule to make one fixture pass.
- **Do preserve third-party licenses and pinned versions** when changing embedded runtime assets.
- **Do run Linux-compatible core tests and native macOS rendering tests** for WebKit/runtime changes.
- **Do keep CI as a release gate**, not a sample workflow.
- **Do update AGENTS.md / ARCHITECTURE.md / decisions / FEATURE_MAP.md** when changing an architectural invariant or user-visible capability.
- **Do keep README focused on workflows**; put deep implementation rationale in architecture docs.
- **Do version JSON contracts** consumed by agents or automation.
- **Do prefer partial, composable CLI operations** over forcing agents to rewrite whole decks.

---

## Don't

- **Don't create a second presentation language.**
- **Don't make `.rsdeck.json` the source of truth.**
- **Don't require Node/npm at runtime.**
- **Don't start localhost servers for Reveal assets.**
- **Don't add Playwright/Chrome as a required rendering dependency.**
- **Don't depend on runtime CDN access.**
- **Don't execute arbitrary deck JavaScript during structural editing.**
- **Don't make the renderer call OpenAI, Anthropic, Google, or another model API directly.**
- **Don't conflate deterministic QA with visual taste.**
- **Don't claim a screenshot was visually reviewed merely because it was rendered or DOM-inspected.**
- **Don't let the authoring agent silently self-approve visual quality when visual review is required.**
- **Don't remove or relax a QA rule just because a conformance fixture triggers it.** Fix the fixture or use a narrow intentional exemption.
- **Don't identify slides only by mutable ordinal numbers when stable IDs are available.**
- **Don't rewrite an entire slide when a scoped element or append/prepend operation is sufficient.**
- **Don't mutate top-level stack structure with child-slide commands or vice versa.**
- **Don't assume Chromium behavior when validating Reveal behavior.** The production renderer is WebKit.
- **Don't add math-engine dependencies** unless the explicit out-of-scope decision is reversed.
- **Don't embed proprietary brand assets** in the Apple/Google/Amazon-inspired themes.
- **Don't add binary/font assets without explicit licensing and provenance review.**
- **Don't merge a runtime feature without E2E coverage and, where visual, rendered artifact coverage.**
- **Don't treat a canceled/stale GitHub Actions run as a product failure.** Check the current branch head and current run.
- **Don't make many rapid architecture commits while waiting on CI unless necessary.** Workflow concurrency can cancel useful signal.

---

## Repository map

```text
/
├── AGENTS.md
├── .github/workflows/revealswift-ci.yml
└── revealswift/
    ├── ARCHITECTURE.md
    ├── docs/
    │   ├── ARCHITECTURE_DECISIONS.md
    │   └── FEATURE_MAP.md
    ├── Package.swift
    ├── README.md
    ├── THIRD_PARTY_NOTICES.md
    ├── Sources/
    │   ├── RevealSwiftCore/
    │   │   ├── Embedded/
    │   │   ├── EmbeddedRuntime.swift
    │   │   ├── HTMLInjector.swift
    │   │   ├── InspectorScript.swift
    │   │   ├── Models.swift
    │   │   ├── ReportScoring.swift
    │   │   ├── RevealDeckFactory.swift
    │   │   └── ThemeLoader.swift
    │   └── revealswift/
    │       ├── Authoring.swift
    │       ├── CLI.swift
    │       ├── Conformance.swift
    │       └── VisualReview.swift
    ├── Tests/
    ├── Examples/
    ├── Themes/
    └── Scripts/
```

---

## Subsystem ownership

### RevealSwiftCore

- `Models.swift` — machine-readable contracts: reports, diagnostics, reviewer handoff.
- `EmbeddedRuntime.swift` — private-scheme runtime asset lookup.
- `HTMLInjector.swift` — injects embedded Reveal runtime and runtime configuration into deck HTML.
- `InspectorScript.swift` — deterministic DOM/render QA.
- `ReportScoring.swift` — review scoring and summary logic.
- `RevealDeckFactory.swift` — minimal Reveal-native HTML deck creation.
- `ThemeLoader.swift` — theme manifests, CSS, appearance and QA rules.
- `Embedded/` — generated/pinned embedded JavaScript and CSS payloads.

### revealswift executable

- `CLI.swift` — command routing, WebKit runner, screenshots, contact sheet, PDF, diagnostics.
- `Authoring.swift` — deck/slide/element/vertical-stack editing.
- `Conformance.swift` — executable Reveal.js behavioral conformance suite.
- `VisualReview.swift` — independent multimodal reviewer manifest, prompt, result validation.

---

## Development workflow

From `revealswift/`:

```bash
swift test
swift build -c release
.build/release/revealswift version
```

Typical renderer smoke test:

```bash
.build/release/revealswift review Examples/demo.html \
  --theme Themes/minimal-dark \
  --output /tmp/revealswift-review \
  --strict
```

Conformance:

```bash
.build/release/revealswift conformance Examples/reveal-conformance.html \
  --theme Themes/minimal-dark
```

Agent authoring smoke:

```bash
.build/release/revealswift deck create /tmp/deck.html \
  --title "Agent Deck" \
  --theme dark-mode \
  --outline title,architecture,conclusion
```

Before declaring a runtime change complete, inspect the latest current-head GitHub Actions run and make sure both Linux and macOS jobs are green.

---

## Testing expectations

Changes should be tested at the lowest useful layer **and** at the agent-visible layer.

Examples:

- parsing/model change → unit test;
- embedded runtime change → unit test + WebKit conformance;
- authoring command → CLI E2E smoke;
- QA rule → deterministic fixture + report assertion;
- visual diagnostic → `diagnostics.json` + annotated image assertion;
- reviewer contract → manifest checker + structured reviewer response validation;
- theme change → strict rendered theme deck;
- Reveal behavior → named conformance assertion.

Never replace a broad integration test with only a unit test.

---

## Agent correction loop

The intended production loop is:

```text
Authoring agent
    ↓
RevealSwift authoring commands / Reveal HTML
    ↓
revealswift review
    ↓
report.json + diagnostics.json + screenshots
    ↓
review-manifest.json + visual-review-prompt.md
    ↓
independent multimodal reviewer
    ↓
visual-review.json
    ↓
revealswift visual-review validate --strict
    ↓
authoring agent fixes
    ↓
repeat until deterministic + visual review pass
```

RevealSwift prepares and validates this loop; the surrounding agent harness performs the model invocation.

---

## When uncertain

Prefer the smallest change that:

1. keeps Reveal HTML native;
2. preserves offline WebKit rendering;
3. preserves deterministic output;
4. keeps machine contracts explicit;
5. adds observable test coverage;
6. does not weaken existing QA.

If a proposed change conflicts with a critical invariant, treat it as an architectural decision and update `ARCHITECTURE_DECISIONS.md` rather than silently changing the implementation.
