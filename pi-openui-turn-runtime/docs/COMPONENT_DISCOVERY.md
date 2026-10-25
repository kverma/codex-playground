# Generic OpenUI Component Discovery and Composition

## Goal
Move UI choice out of domain-specific TypeScript. The request interpreter knows
**what the user wants**; the registry resolver decides **which component and
which authorized data capability** satisfy that request.

The corpus remains the complete upstream OpenUI base library (82 components
in CI) plus all custom React/Zod cards (20 in CI). The selected application
views are described in `corpus/view-registrations.json`. Creating a new
rendering route is a JSON change, not a TypeScript `if/else` change, as long as
the component and backend capability already exist.

## Execution

```text
chat text + typed prior slots
   |
   v
parseIntent() OR optional model-generated semantic intent
  {domain, action, entity, cardinality, params, presentation?}
   |
   v
discover(intent, registry, caller_permissions)
   + semantic exact match
   + capability ACL
   + required parameter slots
   + priority/presentation tags
   + reject tie (never silently choose)
   |
   v
resolveView(): expand a JSON recipe into an immutable typed plan
   operations[]      (registered MCP/API capabilities only)
   view               (registered OpenUI components + nested composition)
   stateCapture       (allowlisted result field paths)
   |
   v
validate(plan) -> Pi Durable/tool adapter -> result-contract validation
   |
   v
deterministic prop resolution -> real OpenUI Lang -> React Renderer
```

## Where Code Mode fits

Pi Code Mode is an **execution surface**, not the semantic UI selector.
Code Mode may run approved scripts exposing the resolver as a typed function,
but the LLM should never author arbitrary tool JavaScript or fabricate app
data. Model calls are optional for interpreting chat requests that deterministic
parsers do not recognize; if enabled, the model emits a semantic intent,
**not component names or MCP commands**.

For any new domain, supply:
1. A capability and its permissions, input args, output contract.
2. Reusable OpenUI component definitions and JSON prop metadata.
3. One or more view registrations declaring semantic compatibility and
   composition with `$param` and `$bind` placeholders.
4. A deterministic intent parser, or a semantically constrained external planner
   for genuinely arbitrary language.
5. A test proving the request→MCP→render result and authorization boundary.

Adding an alternate view for an existing domain needs **only a JSON recipe**.

## Example registry entry

```json
{
  "id": "appstore.detail.v1",
  "domain": "appstore",
  "action": "inspect",
  "entity": "app",
  "cardinality": "one",
  "priority": 10,
  "description": "Inspect a selected app",
  "dataContract": "AppDetails.v1",
  "tags": ["details", "inspect", "app"],
  "operations": [
    {
      "id": "detail",
      "capability": "appstore.details",
      "args": {"id": {"$param": "id"}}
    }
  ],
  "view": {
    "component": "Stack",
    "props": {
      "children": [
        {
          "component": "TextContent",
          "props": {"text": "App Store · app details (MOCK)"}
        },
        {
          "component": "AppDetailView",
          "props": {"app": {"$bind": "results.detail.app"}}
        }
      ]
    }
  }
}
```

The same `inspect/app/one` intent can select a different presentation using a
new recipe with higher priority or an explicit `presentation` tag. The resolver
rejects tied candidates instead of picking one arbitrarily.

## Reproducible tests

```bash
npm install
npm run catalog:sync
npm run typecheck:runtime
npm test
npm run typecheck:web
npm run build:web
```

The registry tests verify:
- all JSON recipes refer only to registered components and authorized tools;
- parser results contain no names of components or MCP methods;
- changing **only JSON view entries** changes the selected OpenUI component;
- two equally ranked compatible registrations fail as ambiguous;
- missing input slots and missing permissions fail closed;
- registered response field types are checked before binding;
- existing CI build and mock App Store multi-turn conversations still work.

The GitHub Actions workflow also runs Chromium against the actual OpenUI
Renderer and uploads screenshots for search, detail, comparison, and
conversation follow-ups.

## Current limitations

- The registry contains **all renderable OpenUI primitives** but only a small
  **curated set of task-level view recipes**. It does not yet synthesize every
  possible arbitrary layout from 102 components.
- Output checking for JSON capabilities is shallow typed-property validation;
  the mock MCP SDK also validates its structured input/output schemas. A
  production data-contract registry should support full JSON Schema/Zod
  validation and provenance tracking for arbitrary connectors.
- Complex arbitrary-chat intent extraction requires a connected and approved
  semantic planner; the zero-LLM rule path recognizes a bounded grammar.
- The standalone store is in-memory. Production concurrency, durable journal,
  exactly-once-like idempotency, and enterprise identity must come from the Pi
  Durable host implementation.
- Existing registry.json updates take effect when the service loads the
  catalog. Live atomic hot reload/version pinning are not yet implemented.
