import test from "node:test";
import assert from "node:assert/strict";
import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";
import { createModels } from "@earendil-works/pi-ai/models";
import { fauxAssistantMessage, fauxProvider, fauxText } from "@earendil-works/pi-ai/providers/faux";
import { createRegistry, Harness, MemoryStorage } from "@earendil-works/pi-durable";
import { PiConversationAuthor } from "../src/pi-author.ts";
import { WorkflowStudio } from "../src/studio.ts";
import { ToolRegistry, WorkflowEngine, MemoryStore, type Workflow } from "../src/core.ts";

test("Pi Durable conversation proposes JSON, Studio validates, runtime publishes and executes", async () => {
  const tools = new ToolRegistry().register({
    name: "lookup", capability: "catalog.read", idempotent: true,
    execute: async () => ({ price: 10 }),
  });
  const proposal: Workflow = {
    schema: 1, id: "quote-check", version: 1, description: "Read quote",
    trigger: { kind: "manual" },
    steps: [{ kind: "tool", id: "read", tool: "lookup", capability: "catalog.read",
      args: { sku: "sku-123" } }],
  };
  const faux = fauxProvider();
  faux.setResponses([fauxAssistantMessage([fauxText(JSON.stringify(proposal))])]);
  const models = createModels();
  models.setProvider(faux.provider);
  const context = BACKGROUND_CONTEXT;
  const harness = await Harness.open(new MemoryStorage(), { models, registry: createRegistry() }, context);
  try {
    const conversation = await harness.root(context, {
      agent: { model: { provider: "faux", modelId: "faux-1" } },
    });
    const studio = new WorkflowStudio(tools, new PiConversationAuthor(conversation));
    const workflow = await studio.author("Look up the price for sku-123");
    assert.equal(workflow.id, "quote-check");
    const runtime = new WorkflowEngine(new MemoryStore(), tools);
    runtime.publish(workflow, "human", new Set(["catalog.read"]));
    runtime.start("sample-run", "quote-check", 1, ["catalog.read"]);
    const result = await runtime.resume("sample-run");
    assert.equal(result.status, "completed");
    assert.deepEqual(result.outputs.read, { price: 10 });
  } finally { await harness.close(context); }
});
