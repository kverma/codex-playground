import { BACKGROUND_CONTEXT } from "@earendil-works/chord/context";
import { AssistantEntry, type Conversation } from "@earendil-works/pi-durable";
import type { AuthoringModel } from "./studio.ts";
import type { Workflow } from "./core.ts";

// A dedicated Pi Durable conversation hosts the builder. The host supplies its
// model/provider through Harness; this adapter never reads API keys or secrets.
export class PiConversationAuthor implements AuthoringModel {
  readonly conversation: Conversation;
  constructor(conversation: Conversation) { this.conversation = conversation; }

  async generate(request: {
    instruction: string;
    prior?: Workflow;
    tools: Array<{ name: string; capability: string }>;
  }): Promise<unknown> {
    const prompt = [
      "You are a workflow compiler, not the workflow executor.",
      "Return exactly one JSON object, no markdown, no JavaScript and no prose.",
      "Required fields: schema=1, id, version, description, trigger, steps.",
      "Step kinds: tool {id,kind,tool,capability,args,maxAttempts?,when?};",
      "approval {id,kind,group,when?}. Conditions: {step,equals}.",
      "An output binding uses {$ref:'earlier-step.field'} as a JSON object.",
      "Never invent tool names or permissions. Ask for clarification separately",
      "if missing essential information; do not silently invent privileged steps.",
      "Available tools:\n" + JSON.stringify(request.tools),
      "Existing workflow (if refining):\n" + JSON.stringify(request.prior ?? null),
      "User instruction:\n" + request.instruction,
    ].join("\n\n");
    const submission = await this.conversation.submit(
      { type: "input", content: prompt }, BACKGROUND_CONTEXT,
    );
    const settled = await submission.wait(BACKGROUND_CONTEXT);
    if (settled.status !== "done" || settled.type !== "input")
      throw new Error("builder conversation failed: " + settled.status);

    const entry = await this.conversation.commit(
      tx => tx.entry(AssistantEntry, settled.answer), BACKGROUND_CONTEXT,
    );
    const fragments: string[] = [];
    for (const message of entry?.model ?? []) {
      if (message.role !== "assistant") continue;
      for (const item of message.content) if (item.type === "text") fragments.push(item.text);
    }
    const output = fragments.join("\n").trim();
    if (!output) throw new Error("builder returned no JSON");
    return JSON.parse(output) as unknown;
  }
}
