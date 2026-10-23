# FrontierMemory-style learning evaluation — PoC v0.2

## Goal and epistemic boundary

The original 72 memory-retrieval cases show exact `entity -> fact` behavior. The new independent evaluation measures whether a **validated, reusable diagnostic procedure** selected from earlier synthetic Pi sessions changes a fresh, deterministic task runner's decisions on **unseen service identifiers**.

This is **not** FrontierMemory's released benchmark, a Letta Dream evaluation, a model-based success rate, an actual Pi Durable extension, or empirical evidence of agent intelligence. It is a reproducible evaluation-harness check and a potential control condition in a later real bake-off.

## Flow

1. Generate 18 synthetic Pi SessionManager JSONL transcripts with user message, assistant `verify_remediation` tool call, and tool result. Label each episode with a **trusted, separately recorded** PASS/FAIL verdict. The only reliable success signal is the external verifier; assistant prose is not evidence.
2. Run `@letta-ai/trajectory` version 0.4.3 to normalize Pi records. Reject malformed transcripts, unmatched tool receipts, mismatched verifier verdicts, and failures. Require **two distinct independent positive receipts** per signal and a single consistent procedure.
3. Propose a versioned `runbooks/<signal>` candidate with two provenance markers. A deterministic simulated independent reviewer checks the existing evidence/receipt/regression policy and writes accepted procedures to `MemoryBackend` (`FixtureBackend` or isolated GBrain CLI).
4. Solve 42 held-out incident selections. The task runner sees only `resource`, `signals`, and `narrative`, not `expected`. Controls: two-rule no-memory default, accumulated verified memory, oracle.
5. Grade exact diagnostic selection, with **abstention as correct** on unknown or ambiguous signals. Record per-case observed behavior and split hashes. Fail CI if the memory run regresses from baseline, unsafe answers occur, or accepted memories are missing.
6. Export plain-text `MEMORY.md` (≤8 MiB) to demonstrate the output layout required by FrontierMemory. This is not a submitted memory folder and does not use its official data.

## Data partitions and leakage prevention

- **Train:** 6 signal families × (2 PASS + 1 FAIL) = 18 episodes, service IDs `train-svc-*`.
- **Held out:** 6 signal families × 5 unfamiliar services = 30 transfer cases, plus 6 unknown cases and 6 two-signal ambiguous cases; evaluation service IDs `eval-*`.
- Every training and held-out resource ID is distinct; test answers are only loaded into the grader and the oracle, never into the memory generator.
- Same abstract signal vocabulary occurs in training and evaluation by design. This tests procedural re-use conditioned on pre-classified signals, **not semantic generalization** or agent ability to discover the signals from production evidence.
- Do **not** use FrontierMemory official corpus, task specifications, answers, verifiers, leaderboard runs, or unseen fixtures to generate memory. Do not submit artificial scores to the official leaderboard.

## Safety, privacy and trust

- Never ingest raw company logs, Slack, tokens, or incident records into a public CI job. Fixture content is fully synthetic.
- Memory is never executable authority. Model-generated lessons require approved source grants, privacy filtering, evidence links, and a real independent evaluation gate in production. Here only a simulated gate runs.
- Do not interpret a synthetic verifier PASS as independent production validation. A model claiming success in natural language has no standing.
- Deny promotion on conflicting positive actions, missing linked receipts, absent provenance, weak evidence or failed verdicts.
- If a held-out case contains competing signals or no known procedure, abstain rather than guess.
- Inspect per-case false positives and negative transfer, not just mean score.

## Toward actual FrontierMemory and Pi Durable

The official `letta-ai/frontiermemory` benchmark requires Python 3.12+, Harbor, Docker/Modal, gated HF corpus access, approved inference endpoints, and a Harbor agent adapter for memory generation. A custom Pi memory-generator adapter can export this text format and load it into Codex/Claude/Hermes task agents; to claim Pi-as-task-harness, provide a separate native loading/execution adapter. Pin model, effort, context length and budget; compare with the no-memory control on the same task attempts. Do not call this harness official unless rules, task verifiers, three independent runs, trace audits and submitted memory digests are validated.

Pi Durable production integration also requires trusted event/checkpoint hooks, resumable batched ingestion with bounded contexts, provenance-source grants, a sandboxed evaluator, authorization, and an explicit promote/rollback state machine. The current PoC never changes running agent instructions or skills autonomously.

## Audit commands

```sh
cd gbrain-poc
npm install --ignore-scripts
npm test
npm run benchmark:transfer -- --out reports/transfer.json --memory-folder reports/portable-memory
# Optional only after separate `GBRAIN_HOME=$(mktemp -d); gbrain init --pglite --no-embedding`:
npm run benchmark:transfer -- --gbrain --out reports/live-transfer.json
```

Reproducibility: reports include per-case expected vs actual, train/test SHA-256, accepted promotion count and all three control scores. CI publishes the complete synthetic report.
