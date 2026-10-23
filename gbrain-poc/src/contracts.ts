/** Stable, provider-neutral contract; no raw tool outputs are stored as memory. */
export type Evidence = { uri: string; excerpt: string; observedAt: string; trusted: boolean };
export type MemoryFact = {
  entity: string; text: string; provenance: string; visibility: 'world' | 'private';
  origin: 'user_said' | 'tool_output' | 'inferred'; requestId: string;
};
export type MemoryReceipt = { id: string; status: 'inserted' | 'duplicate' | 'superseded' };
export type RecallResult = { facts: Array<{ id: string; text: string; provenance: string }>;
  degraded?: string; elapsedMs?: number };
export interface MemoryBackend {
  remember(fact: MemoryFact): Promise<MemoryReceipt>;
  recall(entity: string): Promise<RecallResult>;
  forget(id: string, reason: string): Promise<boolean>;
}
export type Candidate = {
  id: string; proposer: string; entity: string; lesson: string; evidence: Evidence[];
  regression: { caseId: string; passed: boolean; checkedAt: string };
};
export type Promotion = { candidateId: string; accepted: boolean; reason: string; reviewer: string };
