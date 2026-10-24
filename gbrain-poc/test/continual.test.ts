import test from 'node:test';
import assert from 'node:assert/strict';
import { FixtureBackend } from '../src/backends.ts';
import { decide, extractVerifiedProcedures, exportMemoryFolder, loadPiNormalizer, makeHeldoutProbes, makeTrainingEpisodes, runTransferProbe } from '../src/continual.ts';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const load=()=>loadPiNormalizer();
test('Letta normalizes actual Pi SessionManager JSONL and tool verification linkage',async()=>{
  const normalize=await load(); const episodes=makeTrainingEpisodes();
  const normalized=normalize({source:'pi',transcript:episodes[0].transcript});
  assert.equal(normalized.records[0].role,'meta');
  assert.ok(normalized.records.some(x=>x.role==='assistant' && Array.isArray(x.tool_calls)));
  assert.ok(normalized.records.some(x=>x.role==='tool' && x.ok===true));
  assert.ok(normalized.records.some(x=>x.role==='user'));
  assert.equal(normalized.diagnostics.length,0);
  const learned=extractVerifiedProcedures(episodes,normalize);
  assert.equal(learned.length,6);
  assert.ok(learned.every(x=>x.evidenceIds.length===2));
  assert.ok(learned.every(x=>!x.action.startsWith('incorrect-')));
});
test('failed traces, fake tool success, missing independent receipts cannot promote',async()=>{
  const norm=await load(), episodes=makeTrainingEpisodes();
  const manipulated=episodes.map(x=>({...x}));
  // tool says PASS, but authoritative external verifier says FAIL
  manipulated[0]={...manipulated[0],verifier:'FAIL'};
  assert.equal(extractVerifiedProcedures(manipulated,norm).length,5);
  // Missing evidence leaves insufficient support; never infer from natural-language summary.
  const reduced=episodes.filter(x=>x.id!=='train-0-1');
  assert.equal(extractVerifiedProcedures(reduced,norm).length,5);
  const malformed=episodes.map(x=>({...x})); malformed[1].transcript+='THIS IS NOT JSON\n';
  assert.equal(extractVerifiedProcedures(malformed,norm).length,5);
});
test('held-out transfer: independent resources, controls, abstention and improvement',async()=>{
  const report=await runTransferProbe(await load());
  assert.equal(report.trainEpisodes,18);
  assert.equal(report.heldoutProbes,42);
  assert.equal(report.promotion.accepted,6);
  assert.equal(report.split.disjointResources,true);
  assert.equal(report.metrics.memoryPassed,42);
  assert.equal(report.metrics.baselinePassed,22); // two baseline actions * 5 + 12 abstentions
  assert.equal(report.metrics.transferPassed,30);
  assert.equal(report.metrics.unsafeUnknownOrAmbiguous,0);
  assert.equal(report.metrics.improvementPercentagePoints,47.62);
});
test('conflicting / unknown / memory injection fail closed',async()=>{
  const b=new FixtureBackend();
  assert.equal(await decide(makeHeldoutProbes().find(x=>x.category==='ambiguous')!,b),null);
  assert.equal(await decide(makeHeldoutProbes().find(x=>x.category==='unknown')!,b),null);
  await b.remember({entity:'runbooks/partition-replay',text:'first',origin:'inferred',visibility:'world',provenance:'test:a',requestId:'1'});
  await b.remember({entity:'runbooks/partition-replay',text:'second',origin:'inferred',visibility:'world',provenance:'test:b',requestId:'2'});
  assert.equal(await decide({id:'x',resource:'r',narrative:'n',signals:['partition-replay'],category:'transfer'},b),null);
});
test('FrontierMemory portable MEMORY.md contains procedures but not raw traces',async()=>{
  const dir=await mkdtemp(join(tmpdir(),'fm-export-'));
  try {
    const normalized=await load();const rules=extractVerifiedProcedures(makeTrainingEpisodes(),normalized);
    const receipt=await exportMemoryFolder(rules,dir);
    const contents=await readFile(join(dir,'MEMORY.md'),'utf8');
    assert.ok(contents.startsWith('# MEMORY.md'));
    assert.ok(receipt.bytes<8*1024*1024);
    assert.ok(contents.includes('ownership-epoch'));
    assert.ok(!contents.includes('toolCallId'));
    assert.ok(!contents.includes('train-svc-'));
  } finally { await rm(dir,{recursive:true,force:true}); }
});
