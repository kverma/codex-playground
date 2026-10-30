import test from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import {
  FileStore, MemoryStore, ToolRegistry, WorkflowEngine, validate,
  type Json, type Workflow,
} from "../src/core.ts";
import { WorkflowStudio, ImprovementGate } from "../src/studio.ts";

function fixture() {
  const seen = new Map<string, Json>();
  let sideEffects = 0;
  const tools = new ToolRegistry()
    .register({ name: "lookup", capability: "catalog.read", idempotent: true,
      execute: async (args, { idempotencyKey }) => {
        if (!seen.has(idempotencyKey)) { seen.set(idempotencyKey, { status: "ok", input: args }); sideEffects++; }
        return seen.get(idempotencyKey)!;
      },
    })
    .register({ name: "notify", capability: "slack.send", idempotent: true,
      execute: async (_args, { idempotencyKey }) => {
        if (!seen.has(idempotencyKey)) { seen.set(idempotencyKey, { sent: true }); sideEffects++; }
        return seen.get(idempotencyKey)!;
      },
    });
  const workflow: Workflow = {
    schema: 1, id: "pricing-review", version: 1, description: "Review and announce pricing changes",
    trigger: { kind: "manual" },
    steps: [
      { id: "fetch", kind: "tool", tool: "lookup", capability: "catalog.read", args: { sku: "sample-1" }, maxAttempts: 2 },
      { id: "review", kind: "approval", group: "pricing-reviewers" },
      { id: "send", kind: "tool", tool: "notify", capability: "slack.send",
        args: { change: { $ref: "fetch.status" } }, when: { step: "fetch", equals: { status: "ok", input: { sku: "sample-1" } } } },
    ],
  };
  return { tools, workflow, effects: () => sideEffects };
}
const all = new Set(["catalog.read", "slack.send"]);

test("authoring is data-only, versioned, and validates tool capabilities", async () => {
  const { tools, workflow } = fixture();
  const studio = new WorkflowStudio(tools, { generate: async () => workflow });
  const created = await studio.author("Create a pricing review workflow");
  assert.equal(created.id, "pricing-review");
  assert.match(studio.toTypeScript(created), /satisfies Workflow/);
  const next = structuredClone(workflow); next.version = 2;
  const revised = new WorkflowStudio(tools, { generate: async () => JSON.stringify(next) });
  assert.equal((await revised.author("refine", workflow)).version, 2);
  await assert.rejects(revised.author("invalid", next), /increment version/);
});

test("rejects forward references, unknown tools, duplicate steps and mismatched capability", () => {
  const { tools, workflow } = fixture();
  const bad = structuredClone(workflow);
  (bad.steps[0] as any).args = { $ref: "send.sent" };
  assert.throws(() => validate(bad, tools), /forward or invalid reference/);
  (bad.steps[0] as any).args = { sku: "sample-1" };
  (bad.steps[0] as any).capability = "slack.send";
  assert.throws(() => validate(bad, tools), /capability mismatch/);
  (bad.steps[0] as any).capability = "catalog.read";
  bad.steps[1].id = "fetch";
  assert.throws(() => validate(bad, tools), /duplicate/);
});

test("publication is human approved, capability checked and immutable", () => {
  const { tools, workflow } = fixture();
  const engine = new WorkflowEngine(new MemoryStore(), tools);
  assert.throws(() => engine.publish(workflow, "", all), /approver/);
  assert.throws(() => engine.publish(workflow, "reviewer", new Set(["catalog.read"])), /not authorized/);
  engine.publish(workflow, "reviewer", all);
  assert.throws(() => engine.publish(workflow, "reviewer", all), /immutable/);
});

test("published version is pinned; approval resumes without replaying completed tool", async () => {
  const { tools, workflow, effects } = fixture();
  const engine = new WorkflowEngine(new MemoryStore(), tools);
  engine.publish(workflow, "reviewer", all);
  engine.start("run-one", workflow.id, 1, [...all]);
  const pause = await engine.resume("run-one");
  assert.equal(pause.status, "waiting_approval");
  assert.equal(effects(), 1);
  assert.equal((await engine.resume("run-one")).status, "waiting_approval");
  assert.throws(() => engine.approve("run-one", "review", "not-authorized", "user"), /wrong approval/);
  engine.approve("run-one", "review", "pricing-reviewers", "user");
  assert.equal((await engine.resume("run-one")).status, "completed");
  assert.equal(effects(), 2);
  assert.equal((await engine.resume("run-one")).status, "completed");
  assert.equal(effects(), 2);
});

test("run grants enforced separately from publication authorization", async () => {
  const { tools, workflow, effects } = fixture();
  const engine = new WorkflowEngine(new MemoryStore(), tools);
  engine.publish(workflow, "reviewer", all);
  engine.start("limited", workflow.id, 1, []);
  const run = await engine.resume("limited");
  assert.equal(run.status, "failed");
  assert.match(run.error!, /missing run capability/);
  assert.equal(effects(), 0);
});

test("restart reloads checkpoint and waits for approval", async () => {
  const dir = mkdtempSync(join(tmpdir(), "workflow-"));
  try {
    const { tools, workflow, effects } = fixture();
    const path = join(dir, "state.json");
    const first = new WorkflowEngine(new FileStore(path), tools);
    first.publish(workflow, "reviewer", all);
    first.start("restart", workflow.id, 1, [...all]);
    assert.equal((await first.resume("restart")).status, "waiting_approval");
    const recovered = new WorkflowEngine(new FileStore(path), tools);
    recovered.approve("restart", "review", "pricing-reviewers", "human");
    assert.equal((await recovered.resume("restart")).status, "completed");
    assert.equal(effects(), 2);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test("conditional tool skips when previous output is not equal", async () => {
  const { tools, workflow, effects } = fixture();
  (workflow.steps[2] as any).when.equals = { not: "the-output" };
  const engine = new WorkflowEngine(new MemoryStore(), tools);
  engine.publish(workflow, "reviewer", all);
  engine.start("conditional", workflow.id, 1, [...all]);
  await engine.resume("conditional");
  engine.approve("conditional", "review", "pricing-reviewers", "human");
  const run = await engine.resume("conditional");
  assert.equal(run.status, "completed");
  assert.ok(run.events.some(e => e.action === "skipped"));
  assert.equal(effects(), 1);
});

test("candidate gate rejects regressions and capability escalation", async () => {
  const { tools, workflow } = fixture();
  const improved = structuredClone(workflow); improved.version = 2;
  const judge = { evaluate: async (wf: Workflow, testId: string) => ({
    passed: !(wf.version === 2 && testId === "failure"),
    quality: wf.version === 2 ? 0.9 : 0.5, safety: true,
  }) };
  const gate = new ImprovementGate(tools, judge);
  const result = await gate.compare(workflow, improved, ["happy", "failure"]);
  assert.equal(result.accepted, false);
  assert.equal(result.reason, "held-out regression");
  const safeJudge = { evaluate: async (wf: Workflow) => ({
    passed: true, quality: wf.version === 2 ? 0.9 : 0.5, safety: true,
  }) };
  assert.equal((await new ImprovementGate(tools, safeJudge).compare(workflow, improved, ["case"])).accepted, true);
});

test("candidate gate rejects escalation even with better scores", async () => {
  const { tools, workflow } = fixture();
  const original = structuredClone(workflow);
  original.steps = original.steps.filter(s => s.id !== "send");
  const candidate = structuredClone(workflow); candidate.version = 2;
  const judge = { evaluate: async (wf: Workflow) => ({
    passed: true, quality: wf.version === 2 ? 1 : 0.1, safety: true,
  }) };
  const result = await new ImprovementGate(tools, judge).compare(original, candidate, ["case"]);
  assert.equal(result.accepted, false);
  assert.equal(result.reason, "capability escalation");
});
