import { digest, validate, type Json, type ToolRegistry, type Workflow } from "./core.ts";

export interface AuthoringModel {
  // Implement via the trusted LLM gateway / Pi Code Mode. Never execute generated code.
  generate(request: {
    instruction: string; prior?: Workflow; tools: Array<{ name: string; capability: string }>;
  }): Promise<unknown>;
}
export class WorkflowStudio {
  constructor(readonly tools: ToolRegistry, readonly model: AuthoringModel) {}
  async author(instruction: string, prior?: Workflow): Promise<Workflow> {
    const raw = await this.model.generate({ instruction, prior, tools: this.tools.describe() });
    // Models return JSON data, not JavaScript source.
    const candidate = typeof raw === "string" ? JSON.parse(raw) as Workflow : raw as Workflow;
    validate(candidate, this.tools);
    if (prior) {
      if (candidate.id !== prior.id || candidate.version !== prior.version + 1)
        throw new Error("revision must preserve workflow id and increment version");
    }
    return structuredClone(candidate);
  }
  // Human-readable artifact only: no eval(), Function(), dynamic import, or shell execution.
  toTypeScript(workflow: Workflow): string {
    validate(workflow, this.tools);
    return 'import type { Workflow } from "./core.ts";\n\nexport default ' +
      JSON.stringify(workflow, null, 2) + " satisfies Workflow;\n";
  }
}
export type Score = { passed: boolean; quality: number; safety: boolean };
export type Evaluation = {
  baseline: Score[]; candidate: Score[]; accepted: boolean; reason: string;
  baselineDigest: string; candidateDigest: string;
};
export interface Judge {
  evaluate(workflow: Workflow, testId: string): Promise<Score>;
}
export class ImprovementGate {
  constructor(readonly tools: ToolRegistry, readonly judge: Judge) {}
  async compare(
    original: Workflow, candidate: Workflow, heldOut: string[],
  ): Promise<Evaluation> {
    validate(original, this.tools); validate(candidate, this.tools);
    if (original.id !== candidate.id || candidate.version !== original.version + 1)
      throw new Error("candidate must be next version of same workflow");
    if (!heldOut.length) throw new Error("held-out cases required");
    const oldCaps = new Set(original.steps.filter(s => s.kind === "tool").map(s => s.capability));
    const newCaps = candidate.steps.filter(s => s.kind === "tool").map(s => s.capability);
    const escalated = newCaps.some(c => !oldCaps.has(c));
    const baseline: Score[] = [], challenger: Score[] = [];
    for (const testId of heldOut) {
      baseline.push(await this.judge.evaluate(original, testId));
      challenger.push(await this.judge.evaluate(candidate, testId));
    }
    const regression = baseline.some((s, i) => s.passed && !challenger[i].passed);
    const unsafe = challenger.some(s => !s.safety);
    const baselineQ = baseline.reduce((a, s) => a + s.quality, 0) / baseline.length;
    const candidateQ = challenger.reduce((a, s) => a + s.quality, 0) / challenger.length;
    const accepted = !escalated && !regression && !unsafe && candidateQ > baselineQ;
    return {
      baseline, candidate: challenger, accepted,
      reason: escalated ? "capability escalation" : regression ? "held-out regression" :
        unsafe ? "safety failure" : candidateQ <= baselineQ ? "no quality improvement" : "candidate passed",
      baselineDigest: digest(original), candidateDigest: digest(candidate),
    };
  }
}
// Promotion is deliberately separate from testing: only WorkflowEngine.publish()
// can register a release, and it requires an approver and permission allowlist.
export function improvementTrace(e: Evaluation): Json {
  return { accepted: e.accepted, reason: e.reason,
    baselineDigest: e.baselineDigest, candidateDigest: e.candidateDigest,
    cases: e.candidate.length };
}
