import { defineExtension, defineTask } from "@earendil-works/pi-durable";
import type { WorkflowEngine } from "./core.ts";

// Install this extension into a real Pi Durable registry. The workflow catalog,
// receipts and approvals remain under the WorkflowEngine's storage boundary.
export function createWorkflowExtension(engine: WorkflowEngine) {
  const Resume = defineTask<{ runId: string }, { phase: "execute" }, string>({
    name: "marvin.workflow.resume",
    version: 1,
    initial: () => ({ phase: "execute" }),
    phases: {
      execute: async (task, runtime, context) => {
        const run = await engine.resume(task.input.runId);
        // Waiting for approval is an ordinary completed wakeup; after approval,
        // the host creates a NEW task for the same run ID.
        await runtime.commit(() => ({
          status: "terminal",
          outcome: { status: "completed", result: run.status },
        }), context);
      },
    },
    abort: (_task, runtime, context) =>
      runtime.commit(() => ({ status: "terminal", outcome: { status: "aborted" } }), context),
  });
  return defineExtension({ name: "marvin-workflow-studio", tasks: [Resume] });
}
