# Pi Code Mode × Durable × OpenUI integration

This is an isolated integration branch in codex-playground; the existing RevealSwift project is unchanged.

## Implemented
- Inherit **all** native OpenUI components from `openuiLibrary`; `catalog:sync` generates every upstream signature in `corpus/base.json`.
- Register 16 custom Zod/React components and validate their JSON descriptions at build time.
- Render assistant replies as **real OpenUI Lang** using its `Renderer`, not an HTML mock.
- Chat turn → deterministic routing or opt-in planner → typed JSON plan → allowlisted tool call → deterministic prop binding → OpenUI.
- Follow-up slots, idempotent in-process turns, rejection of invented facts/unsafe bindings, HTTP/MCP adapters.
- `src/pi-adapter.ts` provides a native host adapter for Pi tool invocation and Pi Durable's persisted task boundary. It **must be wired to the real agent harness** for production replay; no false durability claims.

## Run locally

```bash
npm install
npm run catalog:sync
npm test
npm run typecheck:web
npm run build:web
npm run api         # another terminal: npm run dev:web
```

Try "Show failed Atlas builds in the last 24 hours", then "Only for main branch".

The default UI uses **sample fixtures**, not real production data. Set `PLANNER_URL` (HTTPS), `PLANNER_MODEL`, and `PLANNER_TOKEN` for approved open-ended planning. The planner only emits plans, never UI prop values or code. Real access requires configured API/MCP gateways and server-side identity enforcement.

## GitHub Actions

The root workflow `.github/workflows/pi-openui-integration.yml` installs dependencies, exports the full OpenUI catalog, runs tests and TypeScript checks, and builds the production React UI. Artifacts contain the generated catalog and browser build.

## Security and scope

This is **not** a production-ready Pi Durable integration: storage is in-memory, demo permissions are fixed, Pi Code Mode's real journaled nested calls are intentionally not simulated, and live API/MCP access requires enterprise approval. The typed `fromPiHarness` / `fromPiDurable` seams let your existing trusted harness provide those features.

## Mock Apple App Store MCP + OpenUI multi-turn demo

This module contains a genuine MCP v2 server backed by **synthetic** App Store-like app listings. It is not an Apple API integration and its apps, prices, reviews, release notes, and screenshot placeholders are invented demo data.

MCP tools are discoverable via `tools/list` and validated via Zod input/output schemas:

| MCP tool | Data output | UI binding |
| --- | --- | --- |
| `search_apps(query,price,limit)` | summary list + stable IDs | `AppSearchGrid`, `AppSummaryCard` |
| `get_app(id)` | full detail, screenshots, privacy and features | `AppDetailView` |
| `compare_apps(ids)` | typed comparison list | `AppComparisonView` |

You can also run the MCP server independently over standard **stdio**:

```bash
npm run mcp:appstore
```

The integrated chat demo uses a real MCP client and in-memory transport. This allows fast no-network tests of the full MCP protocol, without claiming the production Pi Durable host has been wired up.

**Try this multi-turn conversation in the browser:**

1. `Find drawing apps`
2. `Show details for the first one` (or press *View details* on a search card, then Send)
3. `Tell me more about the second app`
4. `Compare the first two apps`
5. `Only free ones`
6. `Open the first one`

The engine stores returned stable IDs and names in turn state via **validated** `stateCapture` bindings. Follow-up turns resolve ordinals and comparison sets without using an LLM. All app prop values are bound from validated MCP `structuredContent`, never supplied as factual constants by the planner.

Tests: `npm test` includes MCP discovery, invalid arguments, stdio interoperability, unauthorized capability rejection, and a six-turn state-machine test. Chromium tests exercise the actual OpenUI React renderer and upload screenshots for search/detail/compare/free-filter states.

**Scope:** This is a mock App Store capability for deterministic UI development. Real App Store service integration would require a separate authorized provider, data contract, rate-limit and credential handling, and verified rights to use imagery/branding.


## Registry-driven component selection

**No chat parser chooses an OpenUI component anymore.** `src/intent-parsers.ts`
produces semantic intents only. `src/registry-resolver.ts` matches the intent
against `corpus/view-registrations.json`, checks caller permissions and
argument/prop contracts, rejects ambiguous candidates, and compiles an
approved execution/UI plan.

This JSON registry currently defines four task views (App Store search, app
detail, app comparison, and CI failure summary). You can add a new view from
any of the 82 upstream OpenUI base components or the 20 custom ones **without
editing routing code**, provided its data capability is already registered.

See [docs/COMPONENT_DISCOVERY.md](docs/COMPONENT_DISCOVERY.md) for the
registry format, trust boundaries, selection scoring, and limits.

`GET /api/views` lists the published task recipes;
`GET /api/views?domain=appstore` filters them. Arbitrary new natural-language
intents still require a configured semantic planner, and this is not an
unrestricted automatic layout generator.


## Multi-MCP rich detail and non-UI conversation

The new `appstore.rich-detail.v1` JSON view recipe fans out to five separate
MCP clients: App Store, Reviews, Privacy, Pricing, and Device Compatibility.
The DAG executor runs independent calls concurrently (max four per batch),
resolves typed `$ref` dependencies, and uses explicitly authored *unavailable*
payloads when an optional source fails. A required source failure rejects the
rich-detail view rather than fabricating data.

For unmatched turns, the API returns either **plain conversational text** when
`CHAT_REPLY_URL`, `CHAT_REPLY_TOKEN` and optionally `CHAT_REPLY_MODEL`
configure an approved HTTPS LLM, or an **unavailable** response otherwise.
It never maps a random question to a fabricated UI or executes unregistered
MCP tools. Existing UI turns remain unchanged.

See [docs/MULTISOURCE_AND_CHAT.md](docs/MULTISOURCE_AND_CHAT.md) for the
complete DAG contract, fallback behavior and test coverage.
