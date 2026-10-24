import { performance } from 'node:perf_hooks';
import { FixtureBackend } from './backends.ts';
import type { MemoryBackend, MemoryFact } from './contracts.ts';

const domains = ['cassandra','kafka','pricing','auth','worker','cache'];
const failures = ['stale-read','duplicate-delivery','wrong-term','token-expiry','fenced-owner','late-refresh'];
const actions = ['verify-read-quorum','inspect-offset','compare-offer-version','rotate-token','check-fence-epoch','inspect-refresh-pointer'];
export type Case = { id: string; entity: string; question: string; expected: string | null; category: 'known' | 'unknown' | 'corrected'; setup?: string };
export type CaseResult = { id: string; category: Case['category']; expected: string | null;
  observed: string[]; passed: boolean; elapsedMs: number; note: string };

/** 72 synthetic, labeled questions; no claimed real incident coverage. */
export function makeCases(): Case[] {
  return Array.from({length:72},(_,i) => {
    const d = domains[i%domains.length], f = failures[Math.floor(i / domains.length)%failures.length];
    const entity = `incidents/poc-${String(i+1).padStart(3,'0')}`;
    const expected = `INC-${String(i+1).padStart(3,'0')} ${d} ${f} validated action ${actions[i%actions.length]}`;
    const category = i >= 60 ? 'unknown' : i >= 48 ? 'corrected' : 'known';
    return { id:`Q${String(i+1).padStart(3,'0')}`, entity,
      question:`For ${entity}, which confirmed ${d} ${f} remediation is recorded?`,
      expected: category === 'unknown' ? null : expected, category,
      setup: category === 'corrected' ? `INC-${String(i+1).padStart(3,'0')} obsolete initial assumption` : undefined };
  });
}
export async function evaluate(backend: MemoryBackend, runId='fixture'): Promise<{schemaVersion:1; runId:string; method:string; metrics: Record<string,number>; cases: CaseResult[]}> {
  const data = makeCases(); const oldIds = new Map<string,string>();
  const fact = (c: Case, text: string, kind: string): MemoryFact => ({ entity:c.entity, text,
    provenance:`synthetic:${c.id}:${kind}`, visibility:'world', origin:'inferred', requestId:`${runId}:${c.id}:${kind}` });
  for (const c of data) {
    if (c.category === 'unknown') continue;
    if (c.setup) oldIds.set(c.id, (await backend.remember(fact(c,c.setup,'obsolete'))).id);
    await backend.remember(fact(c,c.expected!,'validated'));
  }
  for (const c of data) if (oldIds.has(c.id)) await backend.forget(oldIds.get(c.id)!, 'superseded by confirmed evidence');
  const results: CaseResult[]=[];
  for (const c of data) {
    const start=performance.now();
    const recall=await backend.recall(c.entity);
    const elapsedMs=+(performance.now()-start).toFixed(3);
    const observed=recall.facts.map(f=>f.text);
    const passed=c.expected === null ? observed.length === 0 : observed.includes(c.expected) && (!c.setup || !observed.includes(c.setup));
    results.push({id:c.id,category:c.category,expected:c.expected,observed,passed,elapsedMs,
      note: c.category === 'unknown' ? 'must abstain (zero facts)' : c.category === 'corrected' ? 'old fact withdrawn, new fact present' : 'exact entity recall'});
  }
  const ok=results.filter(c=>c.passed).length;
  const sorted=results.map(c=>c.elapsedMs).sort((a,b)=>a-b);
  return {schemaVersion:1,runId,method:'synthetic-entity-recall-with-correction-and-unknown-entities (not semantic search or answer grading)',
    metrics: {questions:results.length,passed:ok,failed:results.length-ok,accuracy:+(ok/results.length).toFixed(4),
      p50Ms:sorted[Math.floor(sorted.length*.5)],p95Ms:sorted[Math.min(sorted.length-1,Math.ceil(sorted.length*.95)-1)]}, cases:results};
}
export async function baseline() { return evaluate(new FixtureBackend()); }
