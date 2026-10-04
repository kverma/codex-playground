# RevealSwift Architecture Decisions and Guardrails

This document records decisions that are easy for a future agent to accidentally reverse.

---

## ADR-001 — Reveal.js HTML is the authoring source of truth

**Status:** Accepted

Agents already know Reveal.js. A proprietary deck schema would duplicate Reveal's feature model and require constant compiler maintenance for vertical slides, fragments, auto-animate, backgrounds, plugins, speaker notes, custom attributes, and future Reveal functionality.

**Decision:** RevealSwift edits ordinary Reveal HTML directly.

**Rejected direction:** `.rsdeck.json` or another durable presentation DSL.

---

## ADR-002 — Native WKWebView instead of Chrome/Playwright

**Status:** Accepted

The motivating deployment environment is a restrictive macOS sandbox where Chromium may fail while creating SingletonSocket/Unix sockets.

**Decision:** Native offscreen `WKWebView` is the production rendering engine.

Chrome/Playwright may be useful externally for comparison, but may not become a required runtime dependency.

---

## ADR-003 — Embedded private-scheme runtime

**Status:** Accepted

RevealSwift must run without Node/npm/CDN/local HTTP serving.

**Decision:** Pin runtime assets in the Swift binary and expose them through `revealswift://runtime/`.

**Guardrail:** Preserve third-party notices and pin versions.

---

## ADR-004 — DOM authoring is script-inert

**Status:** Accepted

Editing should not unexpectedly execute arbitrary scripts from a deck.

**Decision:** Authoring uses browser DOM parsing over HTML strings, not navigation/render execution.

Rendering is a separate phase where deck scripts may execute.

---

## ADR-005 — Stable semantic IDs over positional identity

**Status:** Accepted

Slide numbers change when an agent inserts/reorders content.

**Decision:** Stable `section id` values are the preferred authoring/review identity. Numeric indices remain a convenience interface.

---

## ADR-006 — Two review layers

**Status:** Accepted

DOM geometry can prove clipping but cannot prove good visual hierarchy or aesthetics. Conversely, a vision model should not be the sole authority for objective bounds/rules.

**Decision:**

- deterministic WebKit/DOM QA handles objective rules;
- independent multimodal review handles subjective quality.

Both results must be available to the authoring loop.

---

## ADR-007 — RevealSwift is model/provider-neutral

**Status:** Accepted

Embedding model APIs would add credentials, networking, vendor coupling, and hidden reviewer behavior.

**Decision:** RevealSwift emits `review-manifest.json` and `visual-review-prompt.md`; an external harness invokes any suitable vision-capable model.

Reviewer output is returned as validated `visual-review.json`.

---

## ADR-008 — Reviewer role is independent from author role

**Status:** Accepted

A model approving its own work in the same context creates weak review pressure.

**Decision:** Treat visual review as an independent invocation/context. It may use the same underlying model family, but the reviewer contract explicitly says not to edit the deck during the review pass.

---

## ADR-009 — Exact rendered state is review identity

**Status:** Accepted

Fragments and animation can make intermediate states fail even if a final state looks correct.

**Decision:** Screenshots, diagnostics, and visual-review items encode horizontal/vertical indices, fragment state, and animation sample where applicable.

---

## ADR-010 — Diagnostics are actionable machine contracts

**Status:** Accepted

An image alone is not enough for an agent to correct a problem efficiently.

**Decision:** Diagnostic findings contain stable ID, rule, severity, slide identity, selector, visible text, bounds, suggested fix, agent feedback, and matching annotated screenshot.

---

## ADR-011 — Strict QA remains strict

**Status:** Accepted

Conformance fixtures can intentionally exercise transitions/stretch/framework mutations that look suspicious to QA.

**Decision:** Never globally weaken QA to make a fixture pass. Use a narrow existing exemption on the intentional element.

Examples:

- `data-rs-inline-ok`
- `data-rs-bleed="allow"`
- `data-rs-overlap="allow"`

---

## ADR-012 — Density measures visible coverage, not DOM complexity

**Status:** Accepted

Highlight.js and other renderers create deeply nested elements; summing every child rectangle grossly inflates density.

**Decision:** Estimate density from union-like grid coverage of meaningful visual boxes.

---

## ADR-013 — Fidelity PDF is the current default

**Status:** Accepted

Screenshot-based PDF pages reproduce the tested WebKit state reliably.

**Tradeoff:** Text is not necessarily selectable/vector.

A future vector mode may be additive.

---

## ADR-014 — Theme owns presentation semantics

**Status:** Accepted

Agents should generate semantic Reveal structure while theme CSS owns brand/presentation styling.

Use shared classes/components rather than large amounts of per-slide inline styling.

Brand-inspired bundled themes use no proprietary logos/assets.

---

## ADR-015 — Math engines are intentionally out of scope

**Status:** Accepted

KaTeX/MathJax/LaTeX rendering adds a separate runtime/font/dependency surface that is not required for RevealSwift's current goal.

**Decision:** Do not bundle or enable math engines.

The Reveal Math plugin file may remain in the embedded runtime for compatibility but math rendering is an intentional conformance skip.

---

## ADR-016 — Conformance is executable product documentation

**Status:** Accepted

README claims can drift.

**Decision:** `revealswift conformance` plus CI's named assertion gate is the authoritative proof that supported Reveal behaviors work in native WebKit.

When adding/removing support, update both conformance and the feature map.

---

## ADR-017 — CI is a release gate

**Status:** Accepted

A change is not complete merely because it compiles locally.

The macOS pipeline must exercise:

- rendering;
- deterministic QA;
- diagnostics;
- multimodal handoff contract;
- behavioral conformance;
- authoring;
- themes;
- Universal packaging.

Linux continues to cover portable/core Swift behavior.

---

## ADR-018 — Preserve agent discoverability

**Status:** Accepted

Agents should not have to reverse-engineer the repository to learn capabilities.

**Decision:** Maintain:

- `AGENTS.md` — how to work on the project;
- `ARCHITECTURE.md` — how it works;
- `ARCHITECTURE_DECISIONS.md` — why important constraints exist;
- `FEATURE_MAP.md` — what users/agents can do;
- `README.md` — common workflows.

A future `capabilities --json` command should make the same feature surface machine-discoverable at runtime.
