import type { Candidate, MemoryFact, Promotion } from './contracts.ts';

const SECRET = /(?:\b(?:api[_-]?key|secret|password|authorization|bearer|private[_-]?key)\b\s*[:=]\s*\S+|\bsk-[A-Za-z0-9_-]{12,}\b)/i;
const PATH = /^(?:projects|services|incidents|runbooks)\/[a-z0-9][a-z0-9/_-]{0,120}$/;
export function assertMemorySafe(value: MemoryFact): void {
  if (!PATH.test(value.entity)) throw new Error('ENTITY_SCOPE_INVALID');
  if (!value.text.trim() || value.text.length > 4000) throw new Error('FACT_INVALID');
  if (!value.provenance.trim() || value.provenance.length > 500) throw new Error('PROVENANCE_REQUIRED');
  if (!value.requestId.trim()) throw new Error('REQUEST_ID_REQUIRED');
  if (SECRET.test(value.text) || SECRET.test(value.provenance)) throw new Error('SECRET_DETECTED');
  if (value.origin === 'tool_output') throw new Error('RAW_TOOL_OUTPUT_NOT_MEMORY');
}
/** A self-reflection is a proposal, never executable authority. */
export function reviewCandidate(candidate: Candidate, reviewer: string): Promotion {
  const reject = (reason: string): Promotion => ({ candidateId: candidate.id, accepted: false, reason, reviewer });
  if (!reviewer || reviewer === candidate.proposer) return reject('INDEPENDENT_REVIEW_REQUIRED');
  if (candidate.evidence.length < 2 || candidate.evidence.some(e => !e.trusted || !e.uri || !e.excerpt || Number.isNaN(Date.parse(e.observedAt)))) return reject('INSUFFICIENT_TRUSTED_EVIDENCE');
  if (!candidate.regression.passed || !candidate.regression.caseId || Number.isNaN(Date.parse(candidate.regression.checkedAt))) return reject('REGRESSION_NOT_VERIFIED');
  try { assertMemorySafe({ entity: candidate.entity, text: candidate.lesson,
    provenance: candidate.evidence.map(e => e.uri).join(' ').slice(0, 500), visibility: 'world', origin: 'inferred', requestId: candidate.id }); }
  catch { return reject('CONTENT_POLICY_REJECTED'); }
  return { candidateId: candidate.id, accepted: true, reason: 'GATED_AND_VERIFIED', reviewer };
}
