import test from "node:test";
import assert from "node:assert/strict";
import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";
import { createModels } from "@earendil-works/pi-ai/models";
import { createRegistry, Harness, MemoryStorage } from "@earendil-works/pi-durable";
import { createWorkflowIntegration } from "../src/pi-adapter.ts";
import { MemoryStore, ToolRegistry, WorkflowEngine, type Workflow } from "../src/core.ts";

test("real Pi Durable task bridge executes, pauses and resumes published workflow", async () => {
  const tools = new ToolRegistry().register({
    name: "note", capability: "notes.write", idempotent: true,
    execute: async () => ({ stored: true }),
  });
  const engine = new WorkflowEngine(new MemoryStore(), tools);
  const wf: Workflow = {
    schema: 1, id: "native-pi", version: 1, description: "Pi durable lifecycle",
    trigger: { kind: "manual" },
    steps: [
      { kind: "tool", id: "write", tool: "note", capability: "notes.write", args: { note: "test" } },
      { kind: "approval", id: "gate", group: "reviewers" },
    ],
  };
  engine.publish(wf, "human", new Set(["notes.write"]));
  engine.start("pi-run", wf.id, 1, ["notes.write"]);
  const { extension, Resume } = createWorkflowIntegration(engine);
  const registry = createRegistry();
  registry.install(extension);
  const context = BACKGROUND_CONTEXT;
  const harness = await Harness.open(new MemoryStorage(), { models: createModels(), registry }, context);
  try {
    const root = await harness.root(context);
    const taskOne = await root.commit(
      (tx) => tx.createTask(Resume, { runId: "pi-run" }, { ownership: { kind: "conversation" } }), context,
    );
    const one = await harness.waitForTask(taskOne, context);
    assert.equal(one.state.status, "terminal");
    assert.equal(engine.inspect("pi-run").status, "waiting_approval");
    engine.approve("pi-run", "gate", "reviewers", "human");
    const taskTwo = await root.commit(
      (tx) => tx.createTask(Resume, { runId: "pi-run" }, { ownership: { kind: "conversation" } }), context,
    );
    const two = await harness.waitForTask(taskTwo, context);
    assert.equal(two.state.status, "terminal");
    assert.equal(engine.inspect("pi-run").status, "completed");
  } finally {
    await harness.close(context);
  }
});
