import test from 'node:test';
import assert from 'node:assert/strict';
import {OxigraphMemoryBackend} from '../src/oxigraph.ts';
import {evaluate} from '../src/benchmark.ts';
import {loadPiNormalizer,runTransferProbe} from '../src/continual.ts';
import type {MemoryFact} from '../src/contracts.ts';
const fact=(requestId:string,text:string,entity='incidents/temporal'):MemoryFact=>({requestId,entity,text,provenance:'fixture:verified-case',visibility:'world',origin:'inferred'});
const clock=()=>{let i=0;return ()=>new Date(Date.parse('2026-01-10T00:00:00.000Z')+i++*86_400_000).toISOString();};

test('Oxigraph RDF/WASM satisfies the same 72 deterministic recall, correction and unknown cases',async()=>{
  const results=await evaluate(new OxigraphMemoryBackend());
  assert.equal(results.metrics.passed,72);
  assert.equal(results.metrics.failed,0);
});
test('Oxigraph satisfies identical 18-training/42-heldout Pi procedure transfer',async()=>{
  const result=await runTransferProbe(await loadPiNormalizer(),new OxigraphMemoryBackend());
  assert.equal(result.promotion.accepted,6);
  assert.equal(result.metrics.baselinePassed,22);
  assert.equal(result.metrics.memoryPassed,42);
  assert.equal(result.metrics.unsafeUnknownOrAmbiguous,0);
});
test('Oxigraph stores valid and knowledge times independently, including historical corrections',async()=>{
  const kg=new OxigraphMemoryBackend(clock());
  const first=await kg.rememberTemporal(fact('a','initial inference'),{validFrom:'2025-12-01T00:00:00Z'});
  const t1={knowledgeAt:'2026-01-10T12:00:00Z',validAt:'2025-12-20T00:00:00Z'};
  assert.deepEqual((await kg.recallAsOf('incidents/temporal',t1)).facts.map(f=>f.text),['initial inference']);
  assert.equal(await kg.forget(first.id,'new evidence'),true);
  await kg.rememberTemporal(fact('b','corrected finding'),{validFrom:'2025-12-01T00:00:00Z'});
  assert.deepEqual((await kg.recallAsOf('incidents/temporal',t1)).facts.map(f=>f.text),['initial inference']);
  assert.deepEqual((await kg.recallAsOf('incidents/temporal',{knowledgeAt:'2026-01-14T00:00:00Z',validAt:'2025-12-20T00:00:00Z'})).facts.map(f=>f.text),['corrected finding']);
  assert.equal(await kg.forget(first.id,'already withdrawn'),false);
  assert.equal((await kg.recallAsOf('incidents/temporal',{knowledgeAt:'2026-01-14T00:00:00Z',validAt:'2025-11-30T00:00:00Z'})).facts.length,0);
});
test('Graph interval boundary, request idempotency, and replay preserve evidence history',async()=>{
  const kg=new OxigraphMemoryBackend(clock());
  const f=fact('future','windowed assertion');
  await kg.rememberTemporal(f,{validFrom:'2026-02-01T00:00:00Z',validUntil:'2026-03-01T00:00:00Z'});
  assert.equal((await kg.recallAsOf(f.entity,{knowledgeAt:'2026-01-30T00:00:00Z',validAt:'2026-01-31T23:59:59Z'})).facts.length,0);
  assert.equal((await kg.recallAsOf(f.entity,{knowledgeAt:'2026-01-30T00:00:00Z',validAt:'2026-02-01T00:00:00Z'})).facts.length,1);
  assert.equal((await kg.recallAsOf(f.entity,{knowledgeAt:'2026-01-30T00:00:00Z',validAt:'2026-03-01T00:00:00Z'})).facts.length,0);
  assert.equal((await kg.remember(f)).status,'duplicate');
  await assert.rejects(()=>kg.remember({...f,text:'different content'}),/REQUEST_ID_PAYLOAD_CONFLICT/);
  await assert.rejects(()=>kg.rememberTemporal(fact('invalid','bad'),{validFrom:'2026-03-01',validUntil:'2026-02-01'}),/INVALID_VALID_INTERVAL/);
  const restored=OxigraphMemoryBackend.fromLedger(JSON.parse(JSON.stringify(kg.exportLedger())));
  assert.deepEqual(await restored.recallAsOf(f.entity,{knowledgeAt:'2026-01-30T00:00:00Z',validAt:'2026-02-01T00:00:00Z'}),
    await kg.recallAsOf(f.entity,{knowledgeAt:'2026-01-30T00:00:00Z',validAt:'2026-02-01T00:00:00Z'}));
});
test('Typed evidenced graph relations traverse two hops and SPARQL really queries the RDF engine',async()=>{
  const kg=new OxigraphMemoryBackend(clock());
  await kg.addLink({from:'incidents/101',type:'caused-by',to:'services/kafka',provenance:'synthetic:incident-101'});
  await kg.addLink({from:'services/kafka',type:'depends-on',to:'services/cassandra',provenance:'synthetic:arch-52'});
  assert.equal(kg.walk('incidents/101',1).length,1);
  assert.deepEqual(kg.walk('incidents/101',2).map(x=>[x.depth,x.link.to]),[[1,'services/kafka'],[2,'services/cassandra']]);
  const r=kg.queryTrusted('SELECT ?service WHERE { <urn:pi-memory:2026:entity/incidents%2F101> <urn:pi-memory:2026:relation/caused-by> ?service . }');
  assert.equal(r.length,1);
  assert.ok(r[0].service.includes('services%2Fkafka'));
  assert.equal(OxigraphMemoryBackend.fromLedger(kg.exportLedger()).walk('incidents/101',2).length,2);
});
test('Untrusted, raw, malformed facts cannot enter KG',async()=>{
  const kg=new OxigraphMemoryBackend();
  await assert.rejects(()=>kg.remember({...fact('p','password=verysecret'),origin:'inferred'}),/SECRET_DETECTED/);
  await assert.rejects(()=>kg.remember({...fact('raw','data'),origin:'tool_output'}),/RAW_TOOL_OUTPUT_NOT_MEMORY/);
  await assert.rejects(()=>kg.addLink({from:'../../secret',to:'services/a',type:'depends-on',provenance:'fixture'}),/ENTITY_SCOPE_INVALID/);
  assert.equal((await kg.recall('incidents/temporal')).facts.length,0);
});
