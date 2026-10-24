import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { openPiExecutor } from '../src/pi-executor.ts';
import { openChordControl } from '../src/chord-control.ts';
import { JobStore, jobKey } from '../src/store.mjs';
import { makeGateway, Pump } from '../src/gateway.mjs';
import { createServer } from 'node:http';

test('Pi Durable: custom task persists and same job ID reuses the ledger on restart', async () => {
  const folder=await mkdtemp(join(tmpdir(),'rap-pi-'));
  const path=join(folder,'pi.sqlite');
  const job={job_id:'job-stable-id',operation:'analyze_ci_failures',
    args_json:JSON.stringify({failures:['timeout connecting to Cassandra','network unreachable','assert failed']})};
  try {
    let pi=await openPiExecutor(path);
    const first=await pi.execute(job);
    await pi.close();
    pi=await openPiExecutor(path);
    const second=await pi.execute(job);
    await pi.close();
    assert.equal(first,second);
    assert.deepEqual(JSON.parse(first),{total:3,groups:{network:1,other:1,timeout:1}});
  } finally {await rm(folder,{recursive:true,force:true});}
});

async function until(fn:()=>boolean, timeout=1000) {
  const end=Date.now()+timeout;
  while(!fn() && Date.now()<end) await new Promise(r=>setTimeout(r,10));
  assert.ok(fn(),'timed out waiting for Chord projection');
}

test('Chord: live projection reads SQLite state without claiming durability ownership', async()=>{
  const folder=await mkdtemp(join(tmpdir(),'rap-chord-'));
  const store=new JobStore(join(folder,'gateway.sqlite'));
  const chord=await openChordControl(store);
  try {
    const seen:string[]=[];
    const unsubscribe=chord.reference.jobs.subscribe(value=>{
      seen.push(Object.values(value).map(v=>v.status).join(','));
    });
    const invocation={operation:'analyze_ci_failures',id:'tool-chord',group_id:'thread-chord',
      callback_url:'http://127.0.0.1:4002/callback',arguments:{failures:[]}};
    const accepted=store.accept(invocation);
    assert.ok(accepted.created);
    const value=await chord.host.root(ctx=>chord.reference.getJob({jobId:jobKey('thread-chord','tool-chord')},ctx));
    assert.equal(value?.status,'accepted');
    await until(()=>chord.reference.jobs.value[jobKey('thread-chord','tool-chord')]?.status==='accepted');
    const cancelled=await chord.host.root(ctx=>chord.reference.cancelJob({groupId:'thread-chord',toolId:'tool-chord'},ctx));
    assert.equal(cancelled,true);
    await until(()=>chord.reference.jobs.value[jobKey('thread-chord','tool-chord')]?.status==='cancelled');
    assert.ok(seen.includes('accepted'));
    unsubscribe();
  } finally {await chord.close();store.close();await rm(folder,{recursive:true,force:true});}
});

test('End-to-end RAP callback using REAL Pi Durable worker and Chord status projection', async()=>{
  const folder=await mkdtemp(join(tmpdir(),'rap-e2e-'));
  const store=new JobStore(join(folder,'gateway.sqlite'));
  const pi=await openPiExecutor(join(folder,'pi.sqlite'));
  const chord=await openChordControl(store);
  const callbacks:any[]=[];
  const sink=createServer(async(req,res)=>{
    let s='';for await (const part of req)s+=part;
    callbacks.push(JSON.parse(s));res.writeHead(200).end('ok');
  });
  await new Promise<void>(r=>sink.listen(0,'127.0.0.1',r));
  const origin=`http://127.0.0.1:${(sink.address() as any).port}`;
  const server=makeGateway({store,callbackOrigins:new Set([origin]),endpoint:'http://127.0.0.1:3001'});
  await new Promise<void>(r=>server.listen(0,'127.0.0.1',r));
  const pump=new Pump({store,executor:pi,callbackOrigins:new Set([origin]),intervalMs:20,leaseMs:2000});
  try {
    const response=await fetch(`http://127.0.0.1:${(server.address() as any).port}/`,{
      method:'POST',headers:{'content-type':'application/json'},
      body:JSON.stringify({id:'e2e-1',group_id:'thread-e2e',operation:'analyze_ci_failures',
        arguments:{failures:['timeout','network disconnect']},callback_url:`${origin}/callback`}),
    });
    assert.equal(response.status,200);
    pump.start();
    const deadline=Date.now()+5000;
    while(callbacks.length===0 && Date.now()<deadline)await new Promise(r=>setTimeout(r,20));
    assert.equal(callbacks.length,1);
    assert.equal(callbacks[0].type,'tool_result');
    assert.deepEqual(JSON.parse(callbacks[0].text),{total:2,groups:{network:1,timeout:1}});
    const view=await chord.host.root(ctx=>chord.reference.getJob({jobId:jobKey('thread-e2e','e2e-1')},ctx));
    assert.equal(view?.status,'completed');
  } finally {
    await pump.stop();
    await new Promise(r=>server.close(r));await new Promise(r=>sink.close(r));
    await chord.close();await pi.close();store.close();
    await rm(folder,{recursive:true,force:true});
  }
});
