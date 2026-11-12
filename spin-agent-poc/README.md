# SPIN/Promela → counterexample → real HTTP test (agentic coding PoC)

## Goal and scope

Verify that a model checker can discover a distributed publication race, translate its **actual** error trail into a canonical action trace, and generate a reproducible TypeScript test against an HTTP API. Deliberately simple protocol: two workers first preflight a revision, then commit it. A commit that fails to recheck the stored revision can cause publication to regress.

**Important scope limit:** This tests an in-memory TypeScript HTTP service, not Cassandra, multiple DCs, real networking failures, or Java. The execution order is replayed deterministically; it is not a probabilistic load test. Strong correctness of a real Cassandra protocol requires atomic server-side fencing/CAS semantics and fault injection.

## Files

- `models/buggy.pml` — Promela model with stale commit race and safety assertion.
- `models/fixed.pml` — commit-time monotonic check and safety assertion.
- `scripts/verify.mjs` — real SPIN verifier, guided-trail decoder, invariant validation, and TypeScript test generator.
- `src/service.ts` — deliberately unsafe and corrected implementations of a tiny HTTP publication API.
- `src/replay.ts` — adapter for replaying model actions as HTTP requests and checking the actual API state.
- `tests/replay.test.ts` / `fixtures/manual-seed.json` — test the HTTP adapter locally without SPIN; fixture is explicitly **not** model-checker output.
- `generated/*` and `reports/*` — created only after running SPIN; never seeded with fictitious tool output.
- `.github/workflows/spin-agent-poc.yml` — GitHub Actions job that installs SPIN and runs the full pipeline.

## Local setup

Requires Node.js >=22.16, SPIN (`brew install spin` on macOS or `apt-get install spin` on Ubuntu/Debian), and a C compiler.

```bash
cd spin-agent-poc
npm test                 # runs manual seed regression tests; SPIN not needed
npm run verify:spin      # actually checks both Promela models and converts SPIN trail
npm run test:generated   # runs generated test against HTTP services
npm run verify:all       # complete end-to-end pipeline
```

Expected verifier outcome: **buggy model: `errors: 1`, fixed model: `errors: 0`**. For the negative control, the generated test passes only if the HTTP implementation rejects the monotonicity property (i.e., the test detects the bug), while corrected service must pass the same replay.

## Invariant and sample failing sequence

*Invariant:* `publishedRevision` never decreases. An example possible trace:

1. `preflight(1)` — worker A approves revision 1.
2. `preflight(2)` — worker B approves revision 2.
3. `commit(2)` — revision 2 becomes published.
4. `commit(1)` — stale worker A overwrites publication to revision 1.

The above sequence is a **hand-authored illustration**. The script extracts the actual sequence from `spin -t`, and fails if SPIN does not identify a descending-commit counterexample. For model checking, `pan` explores all reachable interleavings (subject to its bounds). It does not prove that the production Java code implements the model.

## Agent contract (for production integration)

1. Agent submits a model, executable checks, explicit invariants, and environment assumptions. Check invariants independently against requirements rather than changing them on failure.
2. Verifier runner reports `PASS`, `COUNTEREXAMPLE`, `MODEL_INVALID`, or `INCONCLUSIVE` along with SPIN version, model digest, bounds, stats, and raw replay trail.
3. Only an **actual** counterexample can be converted into `Trace` JSON; no synthetic fallback.
4. Typed adapter translates model operations into actual API calls; waits for observable completion and captures state after each operation. Detect/prevent semantic mismatches.
5. Generated test must demonstrate `buggy fails` and `fixed passes`, otherwise pipeline fails.
6. Preserve model, trail, generated test and replay output as GitHub Actions artifacts; production regression must remain as an ordinary tracked test.

## Real-world extension to Atlas

Replace the in-memory HTTP service with a test deployment: launch two publisher workers; independently pause them after preflight using controllable barriers; commit newer revision first; release stale worker; query ServingState/revision from all DCs and assert no visible regression. Model publication attempt ID, WITH TIMESTAMP fencing, multi-DC EACH_QUORUM behavior, outbox attempts and crash/recovery. The Cassandra test harness—not the Promela model—verifies those actual storage semantics.

## Caveats

- The two-worker finite model checks safety only. Liveness/fairness, partitions, durability, idempotency, duplicate messages and clock/timestamp conflicts are not addressed.
- `atomic` in the fixed model is a **specification assumption**. Your persistence system must implement an equivalent atomic guard; an application-level read followed by write is not enough.
- Agent-generated model faithfulness is not established just because SPIN returns `errors: 0`. Reviewed assumptions and implementation conformance are required.
- Tests execute within a local HTTP server and demonstrate a controlled interleaving, not actual simultaneous requests or production-level concurrency.

## Independent references

- SPIN documentation: https://spinroot.com/spin/Man/Manual.html
- SPIN source: https://github.com/nimble-code/Spin
- SPIN guided replay options: https://spinroot.com/spin/Man/Spin.html