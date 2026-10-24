import test from 'node:test';
import assert from 'node:assert/strict';
import { isChordError, memoryLinkPair, createHost } from '@earendil-works/chord';
import { FixtureBackend } from '../src/backends.ts';
import { createKnowledgeHost, KnowledgeService, knowledgeFacet } from '../src/chord-knowledge.ts';
import type { MemoryBackend, MemoryFact, RecallResult } from '../src/contracts.ts';

const fact = (entity: string, text: string, suffix: string): MemoryFact => ({
  entity, text, provenance:'fixture:'+suffix, visibility:'world', origin:'inferred',requestId:'req-'+suffix,
});
const space = {space:'diagnostics'};
async function seededBackends() {
  const gb = new FixtureBackend(), kg = new FixtureBackend();
  const shared = fact('incidents/001','verify quorum','same');
  await gb.remember(shared); await kg.remember(shared);
  return {gbrain:gb, oxigraph:kg};
}

test('Chord contract, operator routing and retained state survive code reload', async () => {
  const engines=await seededBackends(), host=createKnowledgeHost();
  try {
    await host.load([knowledgeFacet(engines,'oxigraph','v1')]);
    const operator=await host.get(KnowledgeService,space,{principal:'owner'});
    try {
      assert.equal(operator.variant,'operator');
      assert.equal((await operator.recall({entity:'incidents/001'})).backend,'oxigraph');
      assert.equal(await operator.implementation(),'v1');
      const switched=await operator.switchReadBackend({to:'gbrain',expectedGeneration:0,probeEntities:['incidents/001']});
      assert.deepEqual(switched,{readBackend:'gbrain',generation:1,proof:'provenance'});
      assert.equal((await operator.recall({entity:'incidents/001'})).backend,'gbrain');
      await assert.rejects(operator.switchReadBackend({to:'oxigraph',expectedGeneration:0,probeEntities:['incidents/001']}),
        /STALE_ROUTING_GENERATION/);
      await host.reload([knowledgeFacet(engines,'oxigraph','v2')]);
      assert.equal(await operator.implementation(),'v2');
      assert.deepEqual(operator.routing.value,{readBackend:'gbrain',generation:1,proof:'provenance'});
      assert.equal((await operator.recall({entity:'incidents/001'})).backend,'gbrain');
    } finally { operator.release(); }
  } finally { await host.close(); }
});

test('read routing cutover rejects divergent candidate facts and preserves active backend', async () => {
  const engines=await seededBackends();
  await engines.gbrain.remember(fact('incidents/001','unverified contradictory action','other'));
  const host=createKnowledgeHost();
  try {
    await host.load([knowledgeFacet(engines)]);
    const op=await host.get(KnowledgeService,space,{principal:'owner'});
    try {
      await assert.rejects(op.switchReadBackend({to:'gbrain',expectedGeneration:0,probeEntities:['incidents/001']}),
        /PARITY_MISMATCH/);
      assert.deepEqual(op.routing.value,{readBackend:'oxigraph',generation:0,proof:'initial'});
      assert.equal((await op.recall({entity:'incidents/001'})).facts.length,1);
      await assert.rejects(op.recall({entity:'../escape'}), /ENTITY_SCOPE_INVALID/);
    } finally {op.release();}
  } finally {await host.close();}
});

test('an in-flight recall pins the engine and generation despite concurrent cutover',async()=>{
  const entered=Promise.withResolvers<void>(), release=Promise.withResolvers<void>();
  const actual=await seededBackends();
  const slow:MemoryBackend={...actual.oxigraph,
    remember: f=>actual.oxigraph.remember(f), forget:(id,reason)=>actual.oxigraph.forget(id,reason),
    recall:async entity=>{entered.resolve();await release.promise;return actual.oxigraph.recall(entity);}};
  const host=createKnowledgeHost();
  try {
    await host.load([knowledgeFacet({oxigraph:slow,gbrain:actual.gbrain})]);
    const op=await host.get(KnowledgeService,space,{principal:'owner'});
    try {
      const pending=op.recall({entity:'incidents/001'});
      await entered.promise;
      // Parity validation also invokes slow backend; release it before awaiting switch.
      const cutover=op.switchReadBackend({to:'gbrain',expectedGeneration:0,probeEntities:['incidents/001']});
      release.resolve();
      await cutover;
      assert.deepEqual({backend:(await pending).backend,generation:(await pending).generation},
        {backend:'oxigraph',generation:0});
      assert.deepEqual({backend:(await op.recall({entity:'incidents/001'})).backend,generation:op.routing.value.generation},
        {backend:'gbrain',generation:1});
    } finally {op.release();}
  } finally {await host.close();}
});

test('viewer has no operator methods across actual Chord link and state replicates',async()=>{
  const server=createKnowledgeHost();
  const [down,up]=memoryLinkPair();
  server.accept(down,{principal:'viewer',kind:'client'});
  const client=createHost({name:'chord-client',self:'viewer',kind:'client',upstream:{link:up,principal:'owner'}});
  try {
    await server.load([knowledgeFacet(await seededBackends())]);
    const user=await client.get(KnowledgeService,space);
    try {
      assert.equal(user.variant,'reader');
      assert.equal((await user.recall({entity:'incidents/001'})).facts.length,1);
      await assert.rejects((user as any).switchReadBackend({to:'gbrain',expectedGeneration:0,probeEntities:['incidents/001']}),
        (e:unknown)=>isChordError(e,'not_in_variant'));
      assert.deepEqual(user.routing.value,{readBackend:'oxigraph',generation:0,proof:'initial'});
    }finally {user.release();}
  }finally {await client.close();await server.close();}
});

test('Chord route switching does not modify independent simulated checkpoint state',async()=>{
  const checkpoint={task:'incident-42',phase:'await-verifier',attempt:3,sequence:29};
  const before=structuredClone(checkpoint);
  const host=createKnowledgeHost();
  try {
    await host.load([knowledgeFacet(await seededBackends())]);
    const op=await host.get(KnowledgeService,space,{principal:'owner'});
    try {await op.switchReadBackend({to:'gbrain',expectedGeneration:0,probeEntities:['incidents/001']});
      assert.deepEqual(checkpoint,before);
    } finally {op.release();}
  } finally {await host.close();}
});

test('Chord code hot reload retains cell and pins in-flight root to previous code version',async()=>{
  const engines=await seededBackends(),host=createKnowledgeHost();
  try {
    await host.load([knowledgeFacet(engines,'oxigraph','before')]);
    const op=await host.get(KnowledgeService,space,{principal:'owner'});
    const entered=Promise.withResolvers<void>(),proceed=Promise.withResolvers<void>();
    try {
      await op.switchReadBackend({to:'gbrain',expectedGeneration:0,probeEntities:['incidents/001']});
      const oldRoot=host.root(async ctx=>{
        const first=await op.implementation(ctx);
        entered.resolve(); await proceed.promise;
        const second=await op.implementation(ctx);
        return [first,second];
      });
      await entered.promise;
      await host.reload([knowledgeFacet(engines,'oxigraph','after')]);
      assert.equal(await op.implementation(),'after');
      proceed.resolve();
      assert.deepEqual(await oldRoot,['before','before']);
      assert.deepEqual(op.routing.value,{readBackend:'gbrain',generation:1,proof:'provenance'});
    }finally {op.release();}
  }finally {await host.close();}
});

test('content-only cutover is opt-in and the assurance mode is visible',async()=>{
  const engines=await seededBackends();
  // Model the real GBrain CLI recall that omits stored provenance.
  const original=engines.gbrain;
  const lossy:MemoryBackend={
    remember: f=>original.remember(f), forget:(id,why)=>original.forget(id,why),
    recall:async entity=>({ facts:(await original.recall(entity)).facts.map(f=>({...f,provenance:''})) }),
  };
  const host=createKnowledgeHost();
  try {
    await host.load([knowledgeFacet({...engines,gbrain:lossy},'oxigraph','v1',{allowContentOnlyParityForSyntheticTests:true})]);
    const op=await host.get(KnowledgeService,space,{principal:'owner'});
    try {
      await assert.rejects(op.switchReadBackend({to:'gbrain',expectedGeneration:0,probeEntities:['incidents/001']}),/PARITY_MISMATCH/);
      const route=await op.switchReadBackend({to:'gbrain',expectedGeneration:0,probeEntities:['incidents/001'],parity:'content-only'});
      assert.deepEqual(route,{readBackend:'gbrain',generation:1,proof:'content-only'});
      assert.equal((await op.recall({entity:'incidents/001'})).backend,'gbrain');
    } finally {op.release();}
  } finally {await host.close();}
});
