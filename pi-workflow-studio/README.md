# Pi Durable Workflow Studio — isolated PoC

A Serval-style conversational workflow builder paired with a Hermit-inspired,
**evaluated** improvement gate. The host is Pi Durable; this is an experimental
subproject in the codex-playground repository, not a change to RevealSwift or a
production deployment of Marvin.

## What is implemented

- Typed, schema-versioned JSON workflow IR with approval, tool, condition,
  ordered data references, retries and manual/event trigger metadata.
- Model-neutral authoring port accepting data-only candidate JSON; a
  conversational Pi agent / enterprise model gateway supplies the model.
- Restricted TypeScript export for review. **Do not execute model-authored TS.**
  The runtime interprets validated data and calls only registered tools.
- Immutable, hash-pinned published workflow versions; permission checks at
  publish time **and** run time; human approval before publication.
- Single-writer checkpoint store (memory/file), idempotency-key propagation,
  recovery after restart, approval pause and resumption, event trace.
- GEPA/Hermit-compatible conceptual gate: versioned challenger, held-out
  evaluations, regression checks, safety checks, no capability escalation,
  independent human promotion.
- Pi Durable 1.4 task/extension bridge in src/pi-adapter.ts. The bridge calls
  the engine for one durable wakeup; approvals trigger the host to create a
  new wakeup. It does not implicitly publish or execute on a schedule.

## Run the tests

    cd pi-workflow-studio
    npm install
    npm run check
    npm test

Tests run with a deterministic local fake tool gateway and no model API keys.

## Pi integration

    import { createRegistry } from "@earendil-works/pi-durable";
    import { createWorkflowExtension } from "./src/pi-adapter.ts";
    const registry = createRegistry();
    registry.install(createWorkflowExtension(engine));

The host must create a task of kind \`marvin.workflow.resume\` with
\`{runId}\` after an approved workflow starts or receives approval; it also
needs an authenticated endpoint invoking engine.approve(). Attach your
enterprise SSO, policy engine, Pi Code Mode/MCP catalog and LLM adapter there.

## Trust and replay boundaries

- **Single writer only.** FileStore is a PoC and is NOT safe for concurrent
  agents, cross-process mutation, multi-DC or untrusted shared storage. Replace
  it with a transactional CAS store or Pi/Chord documents for production.
- **Idempotency keys are not magic.** Every external side-effecting tool must
  enforce deduplication using the supplied key; uncertain outcomes need manual
  reconciliation. Exactly-once outcomes are NOT guaranteed.
- **No model-authored code execution.** Generated TS is an audited export of the
  validated declarative representation. Steps can call only registered tools.
- **Approvals must be authenticated by the host.** The PoC accepts a group and
  human string but does not verify identity or group membership itself.
- **No authorization lease / revocation yet.** Run grants are snapshots. The
  production gateway must check authorization immediately before each action.
- **No full event scheduler or multi-writer orchestration yet.** Trigger
  metadata does not automatically subscribe to external events.
- **No real Hermit Python training integration yet.** The gate supports a
  future Hermit/GEPA optimizer via the Judge and AuthoringModel contracts.
- **No real LLM connected.** Use your approved enterprise model proxy to
  implement AuthoringModel.generate().
- **Outputs can contain sensitive data.** Add encryption, redaction and
  retention policy before production.
- **No isolated execution/sandbox of arbitrary TypeScript.** This is
  intentionally a declarative runtime.

See ARCHITECTURE.md for deployment boundaries and the next implementation phases.
