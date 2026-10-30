# AGENTS.md — Pi Workflow Studio PoC

This directory is an isolated experiment. It is not RevealSwift and it is not
Marvin's deployed production harness. Read README.md and ARCHITECTURE.md first.

## Invariants

1. Published workflow versions are immutable. Runs pin id, version and digest.
2. Run only validated JSON IR. NEVER execute generated TypeScript or shell.
3. Only registered tools may execute. Verify capabilities at publish, run,
   and (in a production gateway) immediately before the external action.
4. Never treat Pi Durable checkpoints as exactly-once external side effects.
   Use stable idempotency keys, external dedup and recovery reconciliation.
5. Persist approval boundaries. The model cannot self-approve releases.
6. Keep model authoring and workflow execution separate. The builder can call
   Pi Code Mode and MCP discovery; the runtime only uses approved tool adapters.
7. Keep independent held-out evaluation. A refiner cannot change the judge,
   validation dataset or release policy while proposing an improvement.
8. Preserve full provenance: origin, model/adapter version, tests, reviewer and
   event/run identity. Scrub secret and sensitive payloads from learner traces.
9. Allow changes to policies, skills, tools and workflows only through reviewed
   candidate promotion, not direct in-place self-modification.
10. Limit this PoC to one writer. Do not silently claim multi-process, multi-DC
    or SSO correctness from the FileStore tests.

## Working commands

    npm install
    npm run check
    npm test

The GitHub Actions gate is .github/workflows/pi-workflow-studio-ci.yml.
All new user-visible workflow capabilities require an E2E test. Crash, replay,
permissions and approval changes need adversarial boundary tests.

## Source map

- src/core.ts — workflow contract, registry, publication, run, receipts.
- src/studio.ts — model-independent authoring, TypeScript export, evaluation gate.
- src/pi-author.ts — native Pi Durable conversation to authoring adapter.
- src/pi-adapter.ts — native Pi Durable workflow task bridge.
- test/ — fake-model integration, real Pi tasks, boundary/failure injections.
- ARCHITECTURE.md — ownership and long-term work plan.
- TASKS.md — prioritized follow-up with acceptance criteria.

## Do not

- Merge an untested candidate or invoke a real side-effecting API during CI.
- Grant additional capabilities because an LLM requested them.
- Put API keys into workflow JSON or logs.
- Replace tool gateway authorization with prompt-only policy.
- Execute model-produced TS/JS using eval, Node VM, QuickJS or bash.
- Conflate passing fake-model tests with real-model reliability.
- Conflate one-writer file persistence with distributed durability.
