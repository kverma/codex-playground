/** Real Pi Durable SQLite task checkpoint vs Chord read routing isolation.
 * Keyless: no model calls or hosted providers are used. Process CLOSE/REOPEN
 * is tested, not OS kill -9 / distributed durability or backend write cutover.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { BACKGROUND_CONTEXT } from '@earendil-works/chord/context';
import { createModels } from '@earendil-works/pi-ai/models';
import { createRegistry, defineExtension, defineTask, Harness } from '@earendil-works/pi-durable';
import { openNodeSqliteStorage } from '@earendil-works/pi-durable/storage/sqlite/node';
import { FixtureBackend } from '../src/backends.ts';
import { createKnowledgeHost, knowledgeFacet, KnowledgeService } from '../src/chord-knowledge.ts';

const context=BACKGROUND_CONTEXT;
const Parking = defineTask<{id:string}, {phase:'park'} | {phase:'finish',marker:string}, string>({
  name:'poc.parking-task',version:1,
  initial:()=>({phase:'park'}),
  phases:{
    park: async (_task,runtime,ctx)=>runtime.sleepUntil(runtime.now()+2000,
      {phase:'finish',marker:'durable-not-chord'},ctx),
    finish:async (task,runtime,ctx)=>runtime.commit(
      ()=>({status:'terminal',outcome:{status:'completed',result:task.state.checkpoint.marker}}),ctx),
  },
  abort:async (_task,runtime,ctx)=>runtime.commit(()=>({status:'terminal',outcome:{status:'aborted'}}),ctx),
});
const registry=createRegistry();registry.install(defineExtension({name:'poc-parking',tasks:[Parking]}));

async function untilSleeping(harness:Harness,id:string) {
  for(let tries=0;tries<100;tries++) {
    const task=await harness.getTask(id,context);
    if(task?.state.status==='sleeping')return task;
    await new Promise(resolve=>setTimeout(resolve,10));
  }
  throw Error('DURABLE_TASK_DID_NOT_CHECKPOINT');
}

test('native Pi Durable SQLite task checkpoint survives Chord routing change and harness reopen', {timeout:15000},async()=>{
  const folder=await mkdtemp(join(tmpdir(),'pi-chord-memory-'));
  const db=join(folder,'session.sqlite');
  const open=async()=>Harness.open(await openNodeSqliteStorage(db),{models:createModels(),registry},context);
  const graph=new FixtureBackend(), gb=new FixtureBackend();
  const fact={entity:'incidents/010',text:'verified diagnosis',provenance:'synthetic:010',
    visibility:'world' as const,origin:'inferred' as const,requestId:'checkpoint-010'};
  await Promise.all([graph.remember(fact),gb.remember(fact)]);
  let first:Harness|undefined, second:Harness|undefined;
  const host=createKnowledgeHost();
  try {
    first=await open();
    const taskId=await (await first.root(context)).commit(
      tx=>tx.createTask(Parking,{id:'incident-010'},{ownership:{kind:'conversation'}}),context);
    first.resume();
    const parked=await untilSleeping(first,taskId);
    assert.equal(parked.state.checkpoint.phase,'finish');
    await host.load([knowledgeFacet({oxigraph:graph,gbrain:gb})]);
    const ref=await host.get(KnowledgeService,{space:'durable'},{principal:'owner'});
    try {
      await ref.switchReadBackend({to:'gbrain',expectedGeneration:0,probeEntities:['incidents/010']});
      assert.equal((await ref.recall({entity:'incidents/010'})).backend,'gbrain');
      const unchanged=(await first.getTask(taskId,context))!;
      assert.deepEqual(unchanged.state,parked.state);
    }finally{ref.release();}
    await first.close(context);first=undefined;
    second=await open();
    const afterReopen=await second.getTask(taskId,context);
    assert.deepEqual(afterReopen?.state,parked.state);
    const result=await second.waitForTask(taskId,context);
    assert.equal(result.state.outcome?.status,'completed');
    if(result.state.outcome?.status==='completed')assert.equal(result.state.outcome.result,'durable-not-chord');
  }finally{
    try{if(first)await first.close(context);if(second)await second.close(context);}finally{
      await host.close();await rm(folder,{recursive:true,force:true});
    }
  }
});
