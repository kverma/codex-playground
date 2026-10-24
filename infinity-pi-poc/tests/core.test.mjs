import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createServer } from 'node:http';
import { JobStore, jobKey } from '../src/store.mjs';
import { makeGateway, Pump } from '../src/gateway.mjs';
import { analyzeFailures } from '../src/analysis.mjs';

async function fixture(fn) {
  const dir=await mkdtemp(join(tmpdir(),'infinity-pi-'));
  const db=join(dir,'jobs.sqlite');
  const sinkMessages=[]; let errorCount=0;
  const sink=createServer(async(req,res)=>{
    let s='';for await(const part of req)s+=part;
    sinkMessages.push(JSON.parse(s));
    if(errorCount++===0 && sink.failFirst) {res.writeHead(503).end();return;}
    res.writeHead(200).end('ok');
  });
  await new Promise(r=>sink.listen(0,'127.0.0.1',r));
  const sinkPort=sink.address().port;
  const origin=`http://127.0.0.1:${sinkPort}`;
  const makeInvocation=(id='call-1')=>({id,group_id:'thread-1',call_id:null,
    operation:'analyze_ci_failures',arguments:{failures:['DB timeout','Network disconnected','assertion']},
    callback_url:`${origin}/callback`});
  let store=new JobStore(db); let gateway, pump;
  try {
    const startGateway=async()=>{
      gateway=makeGateway({store,callbackOrigins:new Set([origin]),endpoint:'http://127.0.0.1:3001'});
      await new Promise(r=>gateway.listen(0,'127.0.0.1',r));
    };
    const post=async (obj,route='/') => fetch(`http://127.0.0.1:${gateway.address().port}${route}`,{
      method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify(obj)});
    const stopGateway=async()=>{if(gateway)await new Promise(r=>gateway.close(r));gateway=null;};
    await startGateway();
    await fn({db,origin,sink,sinkMessages,makeInvocation,post,stopGateway,startGateway,
      store:()=>store,reopen:()=>{store.close();store=new JobStore(db);},
      pump: executor => {pump=new Pump({store,executor,callbackOrigins:new Set([origin]),
        leaseMs:150,maxAttempts:2,intervalMs:10});return pump;},
    });
  } finally {
    if(pump)await pump.stop();
    if(gateway)await new Promise(r=>gateway.close(r));
    store.close();await new Promise(r=>sink.close(r));
    await rm(dir,{recursive:true,force:true});
  }
}
const waitFor=async(fn,ms=2000)=>{const deadline=Date.now()+ms;while(Date.now()<deadline){if(fn())return;await new Promise(r=>setTimeout(r,12));}assert.fail('timed out');};

test('RAP manifest and ack after SQLite commit; exact duplicate does not create new job',()=>fixture(async f=>{
  assert.equal((await f.post(f.makeInvocation())).status,200);
  const first=f.store().get(jobKey('thread-1','call-1'));
  assert.equal(first.status,'accepted');
  const dupe=await f.post(f.makeInvocation());assert.equal(dupe.status,200);
  assert.equal((await dupe.json()).duplicate,true);
  const conflict=f.makeInvocation();conflict.arguments={failures:[]};
  assert.equal((await f.post(conflict)).status,409);
  assert.equal(f.store().list().length,1);
}));

test('restarts after durable admission, then Pi-like worker completion reaches RAP callback',()=>fixture(async f=>{
  assert.equal((await f.post(f.makeInvocation())).status,200);
  await f.stopGateway(); f.reopen(); await f.startGateway();
  let executions=0;
  const pump=f.pump({execute:async j=>{executions++;return analyzeFailures(JSON.parse(j.args_json));}});
  pump.start();
  const key=jobKey('thread-1','call-1');
  await waitFor(()=>f.store().outbox(key)?.delivered_at!==null && f.store().outbox(key)?.delivered_at!==undefined);
  assert.equal(executions,1);
  assert.deepEqual(f.sinkMessages[0],{type:'tool_result',id:'call-1',call_id:null,group_id:'thread-1',
    text:JSON.stringify({total:3,groups:{network:1,other:1,timeout:1}})});
}));

test('callback errors retry from SQLite outbox without rerunning a completed worker',()=>fixture(async f=>{
  f.sink.failFirst=true;
  assert.equal((await f.post(f.makeInvocation())).status,200);
  let executions=0;
  const pump=f.pump({execute:async j=>{executions++;return analyzeFailures(JSON.parse(j.args_json));}});
  pump.start();
  const key=jobKey('thread-1','call-1');
  await waitFor(()=>f.store().outbox(key)?.delivered_at!==null && f.store().outbox(key)?.delivered_at!==undefined,4000);
  assert.equal(executions,1); assert.equal(f.sinkMessages.length,2); assert.ok(f.store().outbox(key).attempts>=2);
}));

test('after worker completion but before callback, restart dispatches ONLY callback',()=>fixture(async f=>{
  assert.equal((await f.post(f.makeInvocation())).status,200);
  const store=f.store();const claim=store.claimWork();assert.ok(claim);
  assert.equal(store.finish(claim.job.job_id,claim.owner,'done'),true);
  assert.equal(store.outbox(claim.job.job_id).delivered_at,null);
  await f.stopGateway(); f.reopen(); await f.startGateway();
  let executed=0;const pump=f.pump({execute:()=>{executed++;return 'again';}});pump.start();
  await waitFor(()=>f.store().outbox(claim.job.job_id)?.delivered_at!==null);
  assert.equal(executed,0);assert.equal(f.sinkMessages[0].text,'done');
}));

test('fencing rejects stale completion after lease expiry and later claimant takeover',()=>fixture(async f=>{
  await f.post(f.makeInvocation()); const store=f.store();
  const a=store.claimWork({now:100,leaseMs:10});assert.ok(a);
  const b=store.claimWork({now:120,leaseMs:10});assert.ok(b);
  assert.notEqual(a.owner,b.owner);
  assert.equal(store.finish(a.job.job_id,a.owner,'STALE'),false);
  assert.equal(store.finish(b.job.job_id,b.owner,'current'),true);
  assert.equal(store.outbox(b.job.job_id).payload_json.includes('current'),true);
}));

test('cancellation is idempotent, prevents queued work, and unknown calls return HTTP 200',()=>fixture(async f=>{
  await f.post(f.makeInvocation());
  assert.equal((await f.post({thread_id:'thread-1',tool_call_id:'call-1'},'/cancel_tool_call')).status,200);
  assert.equal((await f.post({thread_id:'thread-1',tool_call_id:'call-1'},'/cancel_tool_call')).status,200);
  assert.equal((await f.post({thread_id:'thread-999',tool_call_id:'unknown'},'/cancel_tool_call')).status,200);
  assert.equal(f.store().claimWork(),null);
  assert.equal(f.store().get(jobKey('thread-1','call-1')).status,'cancelled');
}));

test('callback URL SSRF restriction, operation and argument validation',()=>fixture(async f=>{
  const bad=f.makeInvocation();bad.callback_url='http://169.254.169.254/latest/meta-data';
  assert.equal((await f.post(bad)).status,400);
  bad.callback_url='http://127.0.0.1:9999/cb';assert.equal((await f.post(bad)).status,400);
  bad.callback_url=f.makeInvocation().callback_url;bad.operation='delete_repo';
  assert.equal((await f.post(bad)).status,400);
  bad.operation='analyze_ci_failures';bad.arguments={failures:[1]};
  assert.equal((await f.post(bad)).status,400);
  assert.equal(f.store().list().length,0);
}));

test('worker failure terminates with an actionable RAP error after finite retry budget',()=>fixture(async f=>{
  await f.post(f.makeInvocation());
  const pump=f.pump({execute:()=>{throw new Error('deliberate worker failure');}});pump.start();
  const key=jobKey('thread-1','call-1');
  await waitFor(()=>f.store().outbox(key)?.delivered_at!==null && f.store().outbox(key)?.delivered_at!==undefined,3000);
  assert.equal(f.store().get(key).attempts,2);
  assert.equal(f.store().get(key).status,'failed');
  assert.match(f.sinkMessages[0].text,/^Error: deliberate worker failure/);
}));
