# Multi-MCP dataflow and non-UI conversation

## New architecture

Two distinct response paths are supported, both typed and deterministic at the UI boundary.

```text
user message
  → constrained semantic intent parser (or approved semantic planner)
    → matching registry view exists:
       validate view recipe and capability permissions
       execute read-only MCP dataflow graph:
         ├─ appstore.details ──┐
         ├─ reviews.get        │ (parallel when independent)
         ├─ privacy.get        ├─> AppRichDetailView
         ├─ pricing.get        │
         └─ compatibility.check(app result, devices) [after app]
       bind typed outputs to nested OpenUI React components
    → no registered matching view:
       approved conversational LLM → plain text response
       or, if not configured → explicit unavailable message
```

### Registry workflow format

The `appstore.rich-detail.v1` recipe in `corpus/view-registrations.json` declares five operations. It uses:

- `dependsOn: ["app"]` for the device compatibility operation
- `{"$ref":"results.app.app.id"}` for a dependency's output as an input
- `required: false` plus a typed, explicit `fallback` for optional sections
- `{"$bind":"results.reviews"}` etc. for federated UI props

No React component imports an MCP client. A generic resolver chooses compatible view
recipes, validates dependencies and permissions, and produces the execution plan.

The execution engine runs independent nodes in parallel **in batches of at most four**.
Dependent nodes run in a later topological wave. The DAG is bounded by the normal
12-node plan budget; cycles and undeclared dependencies fail closed. For a failed
optional node, the engine records `failures[nodeId]` and inserts only the
registry-authored unavailable payload; the UI displays that data as unavailable.
A required-node failure aborts the rich view, rather than fabricating output.

This prototype does **not** yet persist each node's journal to Pi Durable; the
existing `Durable.run` integration seam checkpoints nodes only when a real
durable host implementation is injected. Retry/timeout policy, cancellation,
cross-turn node cache and incremental section hydration are future work.

### Mock services and provenance

The App Store catalog, Reviews, Privacy, Pricing and Device Compatibility
are separate MCP servers/clients connected via in-memory MCP transport.
They support real MCP tool discovery and calls, but **all data is fictional**.
The existing App Store server can also be run via MCP stdio.
Set `MOCK_FAIL_REVIEWS=1` in the API process to simulate optional failure.

### Arbitrary conversational turns

A request that does not match a registered view **must not** result in an
arbitrary LLM-generated MCP command or fabricated data card.

Use these environment variables with an organization-approved,
OpenAI-compatible HTTPS chat-completions gateway:

```bash
CHAT_REPLY_URL=https://your-approved-gateway.example/v1/chat/completions
CHAT_REPLY_TOKEN=YOUR_SECRET
CHAT_REPLY_MODEL=YOUR_APPROVED_MODEL
```

When configured, the model generates a **plain text** response, with a bounded
conversational history. This is a separate path from `PLANNER_URL` (semantic
intent planning). The model is informed it has not accessed tools and may not
pretend otherwise. If a semantic planner returns an unknown domain, the same
text-response path is used. Registered but **unauthorized** capabilities are
rejected instead of being silently executed or re-routed.

When no conversation model is configured, the UI returns an explicit
`kind:"unavailable"` message. A simple greeting is handled locally and
returns `kind:"text"`; the implementation does not claim an arbitrary
question was answered without a model.

The API returns one of:

```json
{"kind":"ui","openui":"root = ...","failures":{}}
```

```json
{"kind":"text","text":"A conversational explanation."}
```

```json
{"kind":"unavailable","text":"No registered UI handles this request..."}
```

All retain `turnId` and `revision`; retries of a previously saved turn return
the same response without executing MCP tools again.

### Verification

Run:

```bash
npm install
npm run catalog:sync
npm test
npm run typecheck:runtime
npm run typecheck:web
npm run build:web
```

The GitHub Actions Chromium flow now covers: App Store search → free filter →
rich detail from five mock MCPs → greeting → unmatched arbitrary request.
The artifact includes `app-rich-multi-mcp.png` and
`ordinary-chat-fallback.png`.

Limitations: this is a **mock**, does not connect to Apple's live App Store, and
production model access, auth, persistence, and Pi Durable replay remain host
integration tasks.
