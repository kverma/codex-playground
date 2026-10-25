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
