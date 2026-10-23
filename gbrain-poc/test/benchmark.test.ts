import test from 'node:test';
import assert from 'node:assert/strict';
import { FixtureBackend } from '../src/backends.ts';
import { evaluate, makeCases } from '../src/benchmark.ts';
import { assertMemorySafe, reviewCandidate } from '../src/safety.ts';
import type { Candidate, MemoryFact } from '../src/contracts.ts';

test('72 benchmark cases are labeled: 48 known, 12 corrected, 12 unknown', () => {
  const cases=makeCases(); assert.equal(cases.length,72); assert.equal(cases.filter(c=>c.category==='known').length,48);
  assert.equal(cases.filter(c=>c.category==='corrected').length,12);
  assert.equal(cases.filter(c=>c.category==='unknown').length,12);
  assert.equal(new Set(cases.map(c=>c.entity)).size,72);
});
test('fixture round-trip, correction, abstention, JSON metrics',async()=>{
  const b=new FixtureBackend(); const result=await evaluate(b);
  assert.equal(result.metrics.passed,72);assert.equal(result.metrics.accuracy,1);
  assert.equal(result.schemaVersion,1);
});
test('duplicate request is idempotent and forgetting does not leak a fact',async()=>{
  const b=new FixtureBackend(); const f:MemoryFact={entity:'incidents/test',text:'recorded root cause',provenance:'fixture:1',requestId:'req-1',visibility:'world',origin:'inferred'};
  const first=await b.remember(f),second=await b.remember(f);
  assert.equal(first.id,second.id);assert.equal(second.status,'duplicate');
  assert.equal((await b.recall(f.entity)).facts.length,1);
  assert.equal(await b.forget(first.id,'wrong'),true);
  assert.equal((await b.recall(f.entity)).facts.length,0);
});
test('memory guard rejects secrets, missing provenance and raw output',()=>{
  const f:MemoryFact={entity:'services/catalog',text:'checked state',provenance:'incident:42',requestId:'test',visibility:'world',origin:'inferred'};
  assert.doesNotThrow(()=>assertMemorySafe(f));
  assert.throws(()=>assertMemorySafe({...f,text:'password = unacceptable'}),/SECRET_DETECTED/);
  assert.throws(()=>assertMemorySafe({...f,provenance:''}),/PROVENANCE_REQUIRED/);
  assert.throws(()=>assertMemorySafe({...f,origin:'tool_output'}),/RAW_TOOL_OUTPUT/);
  assert.throws(()=>assertMemorySafe({...f,entity:'../outside'}),/ENTITY_SCOPE_INVALID/);
});
const candidate:Candidate={id:'proposal-1',entity:'incidents/42',proposer:'marvin',lesson:'Confirm quorum before assuming stale-read root cause',
  evidence:[{uri:'log://incident/42',excerpt:'read mismatch',observedAt:'2026-10-23T00:00:00Z',trusted:true},
            {uri:'trace://incident/42',excerpt:'quorum diagnostic',observedAt:'2026-10-23T00:01:00Z',trusted:true}],
  regression:{caseId:'case-42',passed:true,checkedAt:'2026-10-23T01:00:00Z'}};
test('self approval, failed regressions and weak evidence cannot promote',()=>{
  assert.equal(reviewCandidate(candidate,'marvin').accepted,false);
  assert.equal(reviewCandidate({...candidate, regression:{...candidate.regression,passed:false}},'reviewer').accepted,false);
  assert.equal(reviewCandidate({...candidate,evidence:candidate.evidence.slice(0,1)},'reviewer').accepted,false);
  assert.equal(reviewCandidate(candidate,'reviewer').accepted,true);
});
