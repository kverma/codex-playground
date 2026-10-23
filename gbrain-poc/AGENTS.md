# GBrain PoC agent guide

Scope all changes to `gbrain-poc/` and `.github/workflows/gbrain-poc.yml`. Follow root `AGENTS.md` for shared repository etiquette, but do not modify RevealSwift or its workflows for memory experiments.

Always run `npm test` and `npm run benchmark`. Preserve JSON report schema versions. Treat factual correctness, retrieval ranking, and agent success as separate metrics. Do not use real incident data or expose keys in experiments. Do not enable external model APIs, auto-writeback, autopilot, or Memorable without explicit owner approval. Never promote a learned skill without independent approval and a passing replay regression. Never claim a fixture run proved a real GBrain/Pi integration.
