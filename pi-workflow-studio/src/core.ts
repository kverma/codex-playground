import { createHash } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";

export type Json = null | string | number | boolean | Json[] | { [key: string]: Json };
export type Condition = { step: string; equals: Json };
export type ToolStep = {
  kind: "tool"; id: string; tool: string; capability: string; args: Json;
  when?: Condition; maxAttempts?: number;
};
export type ApprovalStep = { kind: "approval"; id: string; group: string; when?: Condition };
export type Step = ToolStep | ApprovalStep;
export type Workflow = {
  schema: 1; id: string; version: number; description: string;
  trigger: { kind: "manual" } | { kind: "event"; source: string };
  steps: Step[];
};
export type Tool = {
  name: string; capability: string;
  // This is a real gateway contract, not a promise of exactly-once execution.
  idempotent: true;
  execute: (args: Json, meta: { idempotencyKey: string; runId: string }) => Promise<Json>;
};
export type Published = { workflow: Workflow; digest: string; approvedBy: string };
export type RunStatus = "ready" | "running" | "waiting_approval" | "completed" | "failed";
export type Run = {
  id: string; workflowKey: string; digest: string; status: RunStatus; next: number;
  grants: string[]; outputs: Record<string, Json>; approvals: Record<string, string>;
  attempts: Record<string, number>; error?: string;
  events: Array<{ step: string; action: string; at: number }>;
};
export type Database = { published: Record<string, Published>; runs: Record<string, Run> };
export interface Store { read(): Database; write(value: Database): void }
const empty = (): Database => ({ published: {}, runs: {} });
const copy = <T>(value: T): T => structuredClone(value);
const slug = /^[a-z][a-z0-9-]{0,62}$/;
const keyOf = (w: Workflow) => w.id + "@" + w.version;
const has = (o: object, k: string) => Object.prototype.hasOwnProperty.call(o, k);
const assert = (condition: unknown, message: string): asserts condition => {
  if (!condition) throw new Error(message);
};
function ordered(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(ordered);
  if (value && typeof value === "object")
    return Object.fromEntries(Object.entries(value).sort(([a], [b]) => a.localeCompare(b))
      .map(([k, v]) => [k, ordered(v)]));
  return value;
}
export function digest(workflow: Workflow): string {
  return createHash("sha256").update(JSON.stringify(ordered(workflow))).digest("hex");
}
export class MemoryStore implements Store {
  private state: Database = empty();
  read(): Database { return copy(this.state); }
  write(value: Database): void { this.state = copy(value); }
}
// Single-process, single-writer PoC only. File rename is atomic, not a distributed CAS.
export class FileStore implements Store {
  constructor(readonly path: string) {}
  read(): Database {
    if (!existsSync(this.path)) return empty();
    return JSON.parse(readFileSync(this.path, "utf8")) as Database;
  }
  write(value: Database): void {
    mkdirSync(dirname(this.path), { recursive: true });
    const temp = this.path + "." + process.pid + ".tmp";
    writeFileSync(temp, JSON.stringify(value), { mode: 0o600 });
    renameSync(temp, this.path);
  }
}
export class ToolRegistry {
  private tools = new Map<string, Tool>();
  register(tool: Tool): this {
    assert(slug.test(tool.name), "invalid tool name");
    assert(Boolean(tool.capability), "missing capability");
    assert(tool.idempotent === true, "gateway must enforce idempotency");
    assert(!this.tools.has(tool.name), "duplicate tool");
    this.tools.set(tool.name, tool);
    return this;
  }
  get(name: string): Tool {
    const tool = this.tools.get(name);
    assert(tool, "unregistered tool: " + name);
    return tool;
  }
  describe(): Array<{ name: string; capability: string }> {
    return [...this.tools.values()].map(t => ({ name: t.name, capability: t.capability }));
  }
}
function references(value: Json, prior: Set<string>): void {
  if (Array.isArray(value)) { for (const v of value) references(v, prior); return; }
  if (value === null || typeof value !== "object") return;
  if (has(value, "$ref")) {
    assert(Object.keys(value).length === 1, "reference may not contain other keys");
    const ref = value.$ref;
    assert(typeof ref === "string" && prior.has(ref.split(".")[0]), "forward or invalid reference");
    assert(ref.split(".").every(k => slug.test(k)), "invalid reference path");
    return;
  }
  for (const [k, v] of Object.entries(value)) {
    assert(k !== "__proto__" && k !== "constructor" && k !== "prototype", "unsafe key");
    references(v, prior);
  }
}
export function validate(workflow: Workflow, registry: ToolRegistry): void {
  assert(workflow && workflow.schema === 1, "unsupported schema");
  assert(slug.test(workflow.id), "invalid workflow id");
  assert(Number.isSafeInteger(workflow.version) && workflow.version >= 1, "invalid workflow version");
  assert(typeof workflow.description === "string" && workflow.description.length <= 2048, "invalid description");
  assert(workflow.trigger?.kind === "manual" ||
    (workflow.trigger?.kind === "event" && Boolean(workflow.trigger.source)), "invalid trigger");
  assert(Array.isArray(workflow.steps) && workflow.steps.length > 0 && workflow.steps.length <= 64, "invalid steps");
  const prior = new Set<string>();
  for (const step of workflow.steps) {
    assert(slug.test(step.id) && !prior.has(step.id), "duplicate or invalid step id");
    if (step.when) {
      assert(prior.has(step.when.step), "condition must refer to earlier step");
    }
    if (step.kind === "tool") {
      const tool = registry.get(step.tool);
      assert(tool.capability === step.capability, "capability mismatch");
      assert(Number.isSafeInteger(step.maxAttempts ?? 1) &&
        (step.maxAttempts ?? 1) >= 1 && (step.maxAttempts ?? 1) <= 3, "invalid attempts");
      references(step.args, prior);
    } else if (step.kind === "approval") {
      assert(typeof step.group === "string" && slug.test(step.group), "invalid approval group");
    } else {
      throw new Error("unknown step kind");
    }
    prior.add(step.id);
  }
}
function bind(value: Json, outputs: Record<string, Json>): Json {
  if (Array.isArray(value)) return value.map(v => bind(v, outputs));
  if (value === null || typeof value !== "object") return value;
  if (has(value, "$ref")) {
    const parts = (value.$ref as string).split(".");
    let current: Json | undefined = outputs[parts[0]];
    for (const part of parts.slice(1)) {
      if (current === null || typeof current !== "object" || !has(current, part))
        throw new Error("missing output reference: " + value.$ref);
      current = (current as Record<string, Json>)[part];
    }
    if (current === undefined) throw new Error("missing output: " + value.$ref);
    return copy(current);
  }
  return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, bind(v, outputs)]));
}
function applies(step: Step, run: Run): boolean {
  if (!step.when) return true;
  return JSON.stringify(ordered(run.outputs[step.when.step])) === JSON.stringify(ordered(step.when.equals));
}
export class WorkflowEngine {
  constructor(readonly store: Store, readonly tools: ToolRegistry) {}
  publish(workflow: Workflow, approvedBy: string, allowedCapabilities: ReadonlySet<string>): Published {
    validate(workflow, this.tools);
    assert(Boolean(approvedBy), "human approver required");
    for (const step of workflow.steps) if (step.kind === "tool")
      assert(allowedCapabilities.has(step.capability), "capability not authorized for publication: " + step.capability);
    const state = this.store.read();
    assert(!has(state.published, keyOf(workflow)), "published version is immutable");
    const published = { workflow: copy(workflow), digest: digest(workflow), approvedBy };
    state.published[keyOf(workflow)] = published;
    this.store.write(state);
    return copy(published);
  }
  start(runId: string, workflowId: string, version: number, grants: readonly string[]): Run {
    assert(slug.test(runId), "invalid run id");
    const state = this.store.read();
    assert(!has(state.runs, runId), "run id already exists");
    const key = workflowId + "@" + version;
    const published = state.published[key];
    assert(published, "workflow version is not published");
    const run: Run = {
      id: runId, workflowKey: key, digest: published.digest, status: "ready", next: 0,
      grants: [...new Set(grants)], outputs: {}, approvals: {}, attempts: {}, events: [],
    };
    state.runs[runId] = run;
    this.store.write(state);
    return copy(run);
  }
  inspect(runId: string): Run {
    const run = this.store.read().runs[runId];
    assert(run, "unknown run id");
    return copy(run);
  }
  approve(runId: string, stepId: string, group: string, human: string): Run {
    assert(Boolean(human), "human approver required");
    const state = this.store.read();
    const run = state.runs[runId];
    assert(run?.status === "waiting_approval", "run is not awaiting approval");
    const pub = state.published[run.workflowKey];
    const step = pub?.workflow.steps[run.next];
    assert(step?.kind === "approval" && step.id === stepId && step.group === group, "wrong approval target");
    run.approvals[stepId] = human;
    run.events.push({ step: stepId, action: "approved", at: Date.now() });
    run.next++;
    run.status = "ready";
    this.store.write(state);
    return copy(run);
  }
  async resume(runId: string): Promise<Run> {
    let state = this.store.read();
    let run = state.runs[runId];
    assert(run, "unknown run id");
    if (run.status === "completed" || run.status === "waiting_approval" || run.status === "failed")
      return copy(run);
    const published = state.published[run.workflowKey];
    assert(published && published.digest === run.digest, "pinned workflow changed");
    const workflow = published.workflow;
    validate(workflow, this.tools);
    while (run.next < workflow.steps.length) {
      const step = workflow.steps[run.next];
      if (!applies(step, run)) {
        run.events.push({ step: step.id, action: "skipped", at: Date.now() });
        run.next++; this.store.write(state); continue;
      }
      if (step.kind === "approval") {
        run.status = "waiting_approval";
        run.events.push({ step: step.id, action: "approval_requested", at: Date.now() });
        this.store.write(state);
        return copy(run);
      }
      if (!run.grants.includes(step.capability)) {
        run.status = "failed"; run.error = "missing run capability: " + step.capability;
        this.store.write(state);
        return copy(run);
      }
      const tool = this.tools.get(step.tool);
      const idempotencyKey = run.id + "/" + run.workflowKey + "/" + step.id;
      const max = step.maxAttempts ?? 1;
      while ((run.attempts[step.id] ?? 0) < max) {
        run.status = "running";
        run.attempts[step.id] = (run.attempts[step.id] ?? 0) + 1;
        run.events.push({ step: step.id, action: "attempt", at: Date.now() });
        this.store.write(state); // checkpoint BEFORE external effect
        try {
          const result = await tool.execute(bind(step.args, run.outputs), { idempotencyKey, runId });
          run.outputs[step.id] = copy(result);
          run.next++; run.status = "ready";
          run.events.push({ step: step.id, action: "succeeded", at: Date.now() });
          this.store.write(state); // checkpoint AFTER external effect
          break;
        } catch (error) {
          run.error = String(error);
          run.events.push({ step: step.id, action: "attempt_failed", at: Date.now() });
          if (run.attempts[step.id] >= max) run.status = "failed";
          this.store.write(state);
        }
      }
      if (run.status === "failed") return copy(run);
      // A recovered running step may have exhausted its attempt budget before finishing.
      if (run.status === "running" || run.next < workflow.steps.findIndex(s => s.id === step.id) + 1) {
        run.status = "failed";
        run.error = "uncertain tool outcome; manual reconciliation required";
        this.store.write(state);
        return copy(run);
      }
      state = this.store.read(); run = state.runs[runId];
    }
    run.status = "completed";
    this.store.write(state);
    return copy(run);
  }
}
