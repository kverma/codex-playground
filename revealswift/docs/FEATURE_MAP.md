# RevealSwift Feature Map

This is the discoverability map for **end users, authoring agents, reviewer agents, and coding agents**.

Use it to answer:

- What can RevealSwift do?
- Which command exposes the capability?
- Which output artifact contains the result?
- Where is the implementation?
- How is it tested?
- What is intentionally unsupported?

For architecture rationale, see `../ARCHITECTURE.md` and `ARCHITECTURE_DECISIONS.md`.

---

## Quick capability index

| Area | Capability | Primary command / interface | Status |
|---|---|---|---|
| Authoring | Create Reveal-native deck | `deck create` | Supported |
| Authoring | Inspect deck/outline | `deck info`, `deck outline` | Supported |
| Authoring | Validate structure | `deck validate` | Supported |
| Authoring | Theme selection | `deck set-theme`, embedded meta | Supported |
| Slides | Get/set/add/remove/move/duplicate/rename | `slide ...` | Supported |
| Slides | Incremental prepend/append | `slide prepend/append` | Supported |
| Elements | Scoped CSS-selector editing | `element ...` | Supported |
| Vertical slides | Create/list/add/move/remove stacks | `stack ...` | Supported |
| Rendering | Native headless macOS rendering | `inspect`, `screenshots`, `review` | Supported |
| Reveal | Horizontal + vertical navigation | runtime/conformance | Supported |
| Reveal | Fragments | runtime/conformance | Supported |
| Reveal | Auto-animate | runtime/conformance | Supported |
| Reveal | Background color/gradient/image/iframe | runtime/conformance | Supported |
| Reveal | State, overview, pause, scroll view | runtime/conformance | Supported |
| Reveal | Layout helpers/stretch/fit text | runtime/conformance | Supported |
| Reveal | Lazy media | runtime/conformance | Supported |
| Reveal | Custom plugins | `RevealSwiftPlugins` | Supported |
| Plugins | Markdown | embedded runtime | Supported |
| Plugins | Highlight | embedded runtime | Supported |
| Plugins | Notes | embedded runtime | Supported |
| Plugins | Search | embedded runtime | Supported |
| Plugins | Zoom | embedded runtime | Supported |
| Charts | Chart.js | embedded runtime | Supported |
| Math | KaTeX/MathJax/LaTeX rendering | — | **Out of scope** |
| QA | Bounds/clipping/overlap | `inspect`, `review` | Supported |
| QA | Theme/font/margin/density rules | `inspect`, `review` | Supported |
| Diagnostics | Machine-readable findings | `diagnostics.json` | Supported |
| Diagnostics | Annotated screenshots | `diagnostics/*.png` | Supported |
| Visual review | Reviewer queue + prompt | `review-manifest.json`, prompt | Supported |
| Visual review | Validate reviewer result | `visual-review validate` | Supported |
| Export | Screenshots | `screenshots`, `review` | Supported |
| Export | Contact sheet | `review` | Supported |
| Export | PDF | `pdf`, `review --pdf` | Supported |
| Themes | Minimal dark | bundled theme | Supported |
| Themes | Dark mode | bundled theme | Supported |
| Themes | Apple-inspired | bundled theme | Supported |
| Themes | Google-inspired | bundled theme | Supported |
| Themes | Amazon-inspired | bundled theme | Supported |
| Portability | arm64 macOS | packaged binary | Supported |
| Portability | x86_64 macOS | Universal package | Supported |
| Runtime | No Node/npm/Chrome/server/CDN | architectural invariant | Supported |

---

# 1. Deck authoring

## Create a deck

```bash
revealswift deck create deck.html \
  --title "Product Architecture" \
  --theme dark-mode \
  --outline title,context,architecture,metrics,conclusion
```

Alternative fixed slide count:

```bash
revealswift deck create deck.html --title "Review" --slides 8
```

**Output:** normal Reveal.js HTML.

**Implementation:** `RevealDeckFactory.swift`, `Authoring.swift`.

---

## Inspect deck structure

```bash
revealswift deck info deck.html
revealswift deck outline deck.html
revealswift slide list deck.html
```

Use this when an agent needs to rediscover current structure after edits.

---

## Validate structural authoring

```bash
revealswift deck validate deck.html
```

Checks Reveal root/slides structure and stable-ID problems before visual rendering.

---

# 2. Slide editing

## Read slide content

```bash
revealswift slide get deck.html architecture
revealswift slide get deck.html 3 --outer
```

Selectors can be stable IDs or 1-based top-level indices.

## Replace slide body

```bash
revealswift slide set deck.html architecture --stdin
```

## Incremental edits

```bash
revealswift slide prepend deck.html architecture --html '<p class="eyebrow">Architecture</p>'
revealswift slide append deck.html architecture --html '<p class="caption">Updated</p>'
```

## Structural operations

```bash
revealswift slide add deck.html --id rollout --after architecture --html '<h2>Rollout</h2>'
revealswift slide move deck.html rollout --before conclusion
revealswift slide duplicate deck.html rollout --id rollout-copy
revealswift slide rename deck.html rollout-copy --id appendix
revealswift slide remove deck.html appendix
```

---

# 3. Element-level editing

Scoped element operations avoid rewriting an entire slide.

```bash
revealswift element get deck.html architecture 'h2'
revealswift element set-text deck.html architecture 'h2' --text 'System Architecture'
revealswift element set-html deck.html architecture '#note' --html '<strong>Updated</strong>'
revealswift element add-class deck.html architecture '.card' --class emphasized
revealswift element remove-class deck.html architecture '.card' --class emphasized
revealswift element remove deck.html architecture '.obsolete'
```

CSS selectors are resolved **inside the selected slide/vertical child**.

---

# 4. Vertical Reveal stacks

Create Reveal-native nested sections.

```bash
revealswift stack create deck.html --id deep-dive --after architecture
revealswift stack add deck.html deep-dive --id deep-overview --html '<h2>Overview</h2>'
revealswift stack add deck.html deep-dive --id deep-details --html '<h2>Details</h2>'
revealswift stack move deck.html deep-dive deep-details --before deep-overview
revealswift stack list deck.html deep-dive
revealswift stack remove deck.html deep-dive deep-details
```

Vertical child IDs can also be targeted by content/element editing commands.

---

# 5. Reveal.js feature support

The executable conformance suite is authoritative.

```bash
revealswift conformance Examples/reveal-conformance.html \
  --theme Themes/minimal-dark
```

Current tested categories include:

- horizontal slides;
- vertical stacks;
- fragment progression and fragment events;
- slide-change events;
- overview mode;
- pause/resume;
- `data-state`;
- slide transitions;
- background transitions;
- background colors;
- gradients;
- background images;
- background iframes;
- auto-animate;
- controls/progress/slide-number behavior;
- internal navigation links;
- state save/restore;
- auto-slide start/stop;
- scroll view;
- hidden/uncounted visibility semantics;
- lazy images;
- lazy iframes;
- layout helpers;
- computed slide size and scale;
- slide/background lookup APIs;
- first/last-slide detection;
- horizontal/vertical helper APIs;
- plugin registry/public APIs;
- custom plugin registration.

---

# 6. Plugin and library support

## Markdown

Reveal Markdown is embedded and behaviorally tested.

Supports Reveal-native `data-markdown` usage and speaker notes in Markdown.

## Highlight

Reveal Highlight + Highlight.js CSS are embedded.

Tested capabilities include:

- language highlighting;
- generated tokens;
- line numbers;
- line-step fragments.

## Notes

Speaker-note content and Notes plugin API are tested.

## Search

Search open/navigation/close behavior is tested.

## Zoom

Zoom activation and reset behavior are tested.

## Chart.js

Chart.js is embedded and available offline to deck scripts.

Charts are tested as actual Chart.js instances with data.

## Custom plugins

A deck may provide:

```js
window.RevealSwiftPlugins = [myPlugin]
```

Custom plugin registration/API behavior is covered by conformance.

## Math

**Not supported as an enabled rendering feature.**

KaTeX, MathJax, and LaTeX-dependent rendering are intentionally out of scope.

---

# 7. Themes

Bundled themes:

- `minimal-dark`
- `dark-mode`
- `apple-inspired`
- `google-inspired`
- `amazon-inspired`

Theme selection can be embedded in the deck:

```html
<meta name="revealswift-theme" content="dark-mode">
```

Then:

```bash
revealswift review deck.html --output review
```

can resolve the theme without repeating its path.

Brand-inspired themes are visual references only and do not bundle proprietary logos/assets.

---

# 8. Deterministic QA

Use:

```bash
revealswift inspect deck.html --theme Themes/dark-mode
revealswift review deck.html --output review --strict
```

Important rule families:

| Rule | Meaning |
|---|---|
| `layout.outOfBounds` | element exceeds slide viewport |
| `layout.clipped` | overflowing content is clipped |
| `layout.overlap` | sibling content visibly overlaps |
| `theme.fontTooSmall` | rendered type below theme minimum |
| `theme.safeMargin` | content enters protected edge area |
| `theme.inlineStyle` | inline presentation bypasses theme |
| `theme.tooManyColumns` | grid exceeds theme column limit |
| `theme.unknownComponent` | semantic component is not declared |
| `theme.missingTitle` | required heading absent |
| `theme.appearanceMismatch` | rendered light/dark appearance conflicts with theme |
| `density.high` | content is visually dense |
| `density.excessive` | density exceeds maximum |

### Narrow intentional exemptions

```html
data-rs-inline-ok
data-rs-bleed="allow"
data-rs-overlap="allow"
```

Use only for intentional framework/layout behavior.

---

# 9. Diagnostics for agents

`review` creates:

```text
review/
├── diagnostics.json
└── diagnostics/
    └── diag-....png
```

A finding gives the authoring agent:

- stable diagnostic ID;
- exact slide/render state;
- deterministic rule;
- severity;
- CSS selector(s);
- rendered visible text;
- target bounds;
- rule-specific suggested fix;
- generated agent-feedback sentence;
- annotated screenshot.

This is the preferred correction input for objective layout/theme problems.

---

# 10. Multimodal visual review

RevealSwift prepares an independent reviewer rather than calling a model itself.

Generated files:

```text
review-manifest.json
visual-review-prompt.md
```

The manifest queues:

- contact sheet;
- **every captured slide/fragment/animation state**;
- diagnostic overlays.

The external reviewer writes:

```text
visual-review.json
```

Validate it:

```bash
revealswift visual-review validate review/visual-review.json
```

Gate iterative authoring:

```bash
revealswift visual-review validate review/visual-review.json --strict
```

Visual categories include:

- hierarchy;
- readability;
- composition;
- alignment;
- whitespace;
- typography;
- chart;
- diagram;
- imagery;
- consistency;
- narrative;
- accessibility;
- branding;
- animation;
- other.

---

# 11. Render/export commands

## Inspect

```bash
revealswift inspect deck.html --theme Themes/minimal-dark
```

Runs render + deterministic inspection.

## Screenshots

```bash
revealswift screenshots deck.html --output screenshots
```

Captures Reveal states.

## Review

```bash
revealswift review deck.html \
  --output review \
  --animation-frames 0,250,500 \
  --pdf \
  --strict
```

The “hero” workflow. Produces the full QA/reviewer/export bundle.

## PDF

```bash
revealswift pdf deck.html --output deck.pdf
```

Current PDF is fidelity-first raster output derived from WebKit screenshots.

---

# 12. Review artifacts

Typical review output:

```text
review/
├── report.json
├── diagnostics.json
├── review-manifest.json
├── visual-review-prompt.md
├── contact-sheet.png
├── screenshots/
├── diagnostics/
└── deck.pdf
```

External reviewer may add:

```text
visual-review.json
```

### report.json

Aggregate deterministic review status, metrics, artifacts.

### diagnostics.json

Agent-correctable deterministic findings.

### review-manifest.json

Machine contract for the multimodal reviewer.

### visual-review-prompt.md

Reviewer instructions generated from the same contract.

### screenshots/

Every captured Reveal state requested by review.

### diagnostics/

Annotated versions of states associated with deterministic findings.

---

# 13. Agent workflows

## Author-only deterministic loop

```text
create/edit
   ↓
review --strict
   ↓
read diagnostics.json
   ↓
fix
   ↓
repeat
```

## Full visual-quality loop

```text
Author agent
   ↓
RevealSwift review
   ↓
deterministic diagnostics
   +
reviewer manifest/images
   ↓
Independent multimodal reviewer
   ↓
visual-review.json
   ↓
RevealSwift validate --strict
   ↓
Author fixes
   ↓
repeat until both pass
```

---

# 14. Conformance and test fixtures

Important examples:

- `Examples/demo.html` — representative basic deck;
- `Examples/plugins.html` — embedded plugin smoke;
- `Examples/reveal-conformance.html` — broad Reveal behavior;
- `Examples/diagnostics.html` — intentionally broken deck for agent-diagnostic tests;
- `Examples/themes/*.html` — theme-specific visual fixtures.

Important CI contract scripts:

- `Scripts/check_conformance.py`
- `Scripts/check_diagnostics.py`
- `Scripts/check_visual_review_manifest.py`
- `Scripts/mock_visual_reviewer.py`

---

# 15. Platform and packaging

- Swift package;
- macOS 14+ target;
- native arm64 build;
- x86_64 cross-build;
- Universal macOS binary;
- ad-hoc signing in CI;
- bundled themes;
- SHA256 sums;
- third-party notices.

Developer-ID signing/notarization is a distribution/productization task rather than a runtime architecture requirement.

---

# 16. Intentional exclusions / boundaries

Current intentional boundaries:

- no proprietary deck DSL;
- no required Node/npm runtime;
- no Chrome/Playwright runtime;
- no localhost server;
- no runtime CDN dependency;
- no embedded model-provider API;
- no KaTeX/MathJax/LaTeX rendering;
- no proprietary assets in brand-inspired themes.

---

# 17. Where to add a new feature

| Feature type | Primary location |
|---|---|
| shared model/JSON contract | `Sources/RevealSwiftCore/Models.swift` |
| embedded JS/CSS asset | `Sources/RevealSwiftCore/Embedded/` + `EmbeddedRuntime.swift` |
| runtime injection | `HTMLInjector.swift` |
| deterministic QA | `InspectorScript.swift` |
| scoring | `ReportScoring.swift` |
| deck creation | `RevealDeckFactory.swift` |
| CLI/render/export | `Sources/revealswift/CLI.swift` |
| DOM authoring | `Authoring.swift` |
| Reveal behavior proof | `Conformance.swift` |
| visual reviewer handoff | `VisualReview.swift` |
| theme | `Themes/<name>/` |
| reusable CI contract | `Scripts/` |
| end-to-end gate | `.github/workflows/revealswift-ci.yml` |

---

# 18. Discoverability rule

When a user-visible capability is added:

1. expose it through a coherent CLI/API or artifact contract;
2. add executable coverage;
3. update this feature map;
4. update README if it is a common workflow;
5. update architecture/ADR docs if the feature changes a system boundary.

The long-term machine-readable companion to this document should be a `revealswift capabilities --json` command.
