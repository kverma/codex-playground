# GBrain PoC agent guide

Scope all changes to `gbrain-poc/` and `.github/workflows/gbrain-poc.yml`. Follow root `AGENTS.md` for shared repository etiquette, but do not modify RevealSwift or its workflows for memory experiments.

Always run `npm test` and `npm run benchmark`. Preserve JSON report schema versions. Treat factual correctness, retrieval ranking, and agent success as separate metrics. Do not use real incident data or expose keys in experiments. Do not enable external model APIs, auto-writeback, autopilot, or Memorable without explicit owner approval. Never promote a learned skill without independent approval and a passing replay regression. Never claim a fixture run proved a real GBrain/Pi integration.

## Continual-learning tests (v0.2)

Read `docs/FRONTIERMEMORY.md`. Keep training and evaluation identifiers separated and tests strict about unknown/ambiguous abstention. Learning requires both an independent trusted PASS label and linked tool receipt; do not infer from assistant prose. Never put FrontierMemory's official tasks/corpus/verifiers into a memory generation environment or GitHub artifacts. The deterministic policy runner is **not an LLM**; never advertise these scores as agent task success. Changing the fixture signal taxonomy requires reviewing leakage risk and expected-vs-actual traces.
