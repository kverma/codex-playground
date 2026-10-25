import test from 'node:test';
import assert from 'node:assert/strict';
import {Engine,Store,loadCatalog,type Invoke} from '../src/runtime.ts';
import {connectMockAppStore,callAppStoreTool} from '../src/mock-app-store.ts';
import {connectRichMockServices} from '../src/mock-rich-services.ts';
import {resolveView,validateRegistry} from '../src/registry-resolver.ts';
import {appStoreIntent} from '../src/intent-parsers.ts';

const context=(turnId:string)=>({threadId:'rich-convo',turnId,permissions:['appstore:read']});
test('multi-source MCP endpoints expose independent reviews, privacy, pricing, and compatibility tools',async()=>{
 const m=await connectRichMockServices();
 try{
  const expected={reviews:'get_reviews',privacy:'get_privacy',pricing:'get_pricing',compatibility:'check_devices'};
  for(const [name,tool] of Object.entries(expected)){
   const list=await m.endpoints[name as keyof typeof m.endpoints].client.listTools();
   assert.ok(list.tools.some(t=>t.name===tool&&t.annotations?.readOnlyHint),name);
  }
  const reviews=await m.invoke('reviews.get',{appId:'sketchflow'}) as any;
  const privacy=await m.invoke('privacy.get',{appId:'sketchflow'}) as any;
  assert.equal(reviews.items.length,2);assert.match(privacy.report.tracking,/No tracking/);
 }finally{await m.close();}
});
test('real multi-MCP app rich detail runs independent calls concurrently and device call after app',async()=>{
 const store=new Store(),app=await connectMockAppStore(),other=await connectRichMockServices();
 const catalog=await loadCatalog();
 let active=0,max=0,appFinished=false;const calls:string[]=[];
 const invoke:Invoke=async(cap,args)=>{
  active++;max=Math.max(max,active);calls.push(cap);
  try{
   await new Promise(r=>setTimeout(r,25));
   if(cap==='compatibility.check')assert.equal(appFinished,true,'device node must wait for app dependency');
   let output:unknown;
   if(cap==='appstore.search')output=await callAppStoreTool(app.client,'search_apps',args);
   else if(cap==='appstore.details'){output=await callAppStoreTool(app.client,'get_app',args);appFinished=true;}
   else output=await other.invoke(cap,args);
   return output;
  }finally{active--;}
 };
 try{
  const engine=new Engine(catalog,store,invoke);
  const search=await engine.handle('Find drawing apps',context('t1'));
  assert.equal(search.kind,'ui');
  const rich=await engine.handle('Show full reviews, privacy, pricing and device compatibility for the first one',context('t2'));
  assert.equal(rich.kind,'ui');
  if(rich.kind!=='ui')throw Error('expected UI');
  assert.match(rich.openui,/AppRichDetailView/);
  assert.match(rich.openui,/Great for my workflow/);
  assert.match(rich.openui,/Diagnostics/);
  assert.match(rich.openui,/One-time/);
  assert.match(rich.openui,/iPad/);
  assert.equal(max>=3,true,'independent nodes run in parallel');
  assert.equal(calls.filter(x=>x==='appstore.details').length,1);
  assert.equal(calls.filter(x=>x==='compatibility.check').length,1);
  assert.equal(rich.failures&&Object.keys(rich.failures).length,0);
 }finally{await app.close();await other.close();}
});
test('optional reviews MCP failure renders unavailable state without fabricating review data',async()=>{
 const app=await connectMockAppStore(),services=await connectRichMockServices(),catalog=await loadCatalog();
 try{
  const engine=new Engine(catalog,new Store(),async(cap,args)=>{
   if(cap==='reviews.get')throw Error('Simulated MCP outage');
   if(cap==='appstore.search')return callAppStoreTool(app.client,'search_apps',args);
   if(cap==='appstore.details')return callAppStoreTool(app.client,'get_app',args);
   return services.invoke(cap,args);
  });
  await engine.handle('Find drawing apps',context('s1'));
  const result=await engine.handle('Show full details for the first one',context('s2'));
  assert.equal(result.kind,'ui');
  if(result.kind==='ui'){
   assert.equal(result.failures.reviews,'Simulated MCP outage');
   assert.match(result.openui,/unavailable/);
   assert.doesNotMatch(result.openui,/Great for my workflow/);
   assert.match(result.openui,/AppRichDetailView/);
  }
 }finally{await app.close();await services.close();}
});
test('registry rejects undeclared DAG dependencies and optional source without fallback',async()=>{
 const c=await loadCatalog();const rich=c.views!.find(v=>v.id==='appstore.rich-detail.v1')!;
 const bad=structuredClone(rich);bad.operations[4]!.dependsOn=[];
 assert.throws(()=>validateRegistry({...c,views:[bad]}),/unregistered DAG dependency/);
 const bad2=structuredClone(rich);delete bad2.operations[1]!.fallback;
 assert.throws(()=>validateRegistry({...c,views:[bad2]}),/optional tool requires fallback/);
 const intent=appStoreIntent('Show rich details for the first one',{appResultIds:['sketchflow'],appResultNames:['SketchFlow Studio']})!;
 assert.equal(intent.presentation,'rich');
 const compiled=resolveView(intent,c,{threadId:'t',turnId:'t',permissions:['appstore:read']}).plan;
 assert.deepEqual(compiled.operations[4]!.dependsOn,['app']);
 assert.deepEqual(compiled.operations[4]!.args.appId,{$ref:'results.app.app.id'});
});
test('ordinary conversation uses a plain reply rather than forcing unrelated OpenUI component',async()=>{
 const catalog=await loadCatalog(),store=new Store();let modelCalls=0,toolCalls=0;
 const engine=new Engine(catalog,store,async()=>{toolCalls++;return {};},undefined,undefined,async(text,history)=>{
  modelCalls++;assert.equal(text,'Explain the difference between creativity and invention');
  assert.equal(history.length,0);return 'Creativity generates ideas; invention turns an idea into something new.';
 });
 const answer=await engine.handle('Explain the difference between creativity and invention',{threadId:'chat-text',turnId:'r1',permissions:[]});
 assert.equal(answer.kind,'text');
 if(answer.kind==='text')assert.match(answer.text,/Creativity generates/);
 assert.equal(toolCalls,0);assert.equal(modelCalls,1);
 const repeated=await engine.handle('Explain the difference between creativity and invention',{threadId:'chat-text',turnId:'r1',permissions:[]});
 assert.equal(repeated.kind,'text');assert.equal(modelCalls,1);
});
test('unrecognized conversation without model reports unavailable, not a fake answer',async()=>{
 const c=await loadCatalog();
 const engine=new Engine(c,new Store(),async()=>{throw Error('should not call MCP')});
 const reply=await engine.handle('Explain quantum networking',{threadId:'u',turnId:'a',permissions:[]});
 assert.equal(reply.kind,'unavailable');
 if(reply.kind==='unavailable')assert.match(reply.text,/no conversational model is configured/i);
});
test('planner-produced unknown semantic intent falls back to ordinary chat, but denied registered tool stays denied',async()=>{
 const c=await loadCatalog();
 const engine=new Engine(c,new Store(),async()=>{throw Error('unexpected MCP');},
  async()=>({domain:'education',action:'explain',entity:'concept',cardinality:'one',params:{}}),
  undefined,async()=> 'This is a plain conversational reply.');
 const r=await engine.handle('Explain photosynthesis',{threadId:'p',turnId:'a',permissions:[]});
 assert.equal(r.kind,'text');
 const denied=new Engine(c,new Store(),async()=>{throw Error('should not invoke');},undefined,undefined,async()=> 'not allowed');
 await assert.rejects(()=>denied.handle('Find drawing apps',{threadId:'p2',turnId:'b',permissions:[]}),/No authorized/);
});


test('partial pricing triggers ONLY targeted recovery, keeps privacy partial, and preserves other MCP results',async()=>{
 const catalog=await loadCatalog(),store=new Store(),app=await connectMockAppStore(),services=await connectRichMockServices();
 let priceCalls=0,recoveryCalls=0,reviewCalls=0,privacyCalls=0;
 const engine=new Engine(catalog,store,async(cap,args)=>{
  if(cap==='appstore.search')return callAppStoreTool(app.client,'search_apps',args);
  if(cap==='appstore.details')return callAppStoreTool(app.client,'get_app',args);
  if(cap==='pricing.get'){priceCalls++;return {offer:{currency:'USD',purchaseType:'One-time'},source:'mock-pricing'};}
  if(cap==='pricing.recover'){recoveryCalls++;assert.deepEqual(args.fields,['offer.price']);return services.invoke(cap,args);}
  if(cap==='privacy.get'){privacyCalls++;return {report:{collects:'Diagnostics (optional)',notes:'Sample'},source:'mock-privacy'};}
  if(cap==='reviews.get')reviewCalls++;
  return services.invoke(cap,args);
 });
 try{
  await engine.handle('Find drawing apps',{threadId:'partial',turnId:'a',permissions:['appstore:read']});
  const reply=await engine.handle('Show full details and privacy and reviews for the first one',{threadId:'partial',turnId:'b',permissions:['appstore:read']});
  assert.equal(reply.kind,'ui');if(reply.kind!=='ui')throw Error('expected rich UI');
  assert.match(reply.openui,/AppRichDetailView/);
  assert.match(reply.openui,/\\"status\\": \\"recovered\\"|\\"status\\":\\"recovered\\"|recovered/);
  assert.match(reply.openui,/partial/);
  assert.match(reply.openui,/Diagnostics \(optional\)/);
  assert.match(reply.openui,/Unavailable/);
  assert.equal(priceCalls,1);assert.equal(recoveryCalls,1);assert.equal(reviewCalls,1);assert.equal(privacyCalls,1);
 }finally{await app.close();await services.close();}
});
test('unstructured MCP text is parsed conservatively and does not trigger unnecessary recovery',async()=>{
 const c=await loadCatalog(),a=await connectMockAppStore(),s=await connectRichMockServices();let repairs=0;
 try{
  const engine=new Engine(c,new Store(),async(cap,args)=>{
   if(cap==='appstore.search')return callAppStoreTool(a.client,'search_apps',args);
   if(cap==='appstore.details')return callAppStoreTool(a.client,'get_app',args);
   if(cap==='pricing.get')return 'price: $9.99\ncurrency: USD\npurchaseType: One-time';
   if(cap==='pricing.recover'){repairs++;return s.invoke(cap,args);}
   return s.invoke(cap,args);
  });
  await engine.handle('Find drawing apps',{threadId:'plain-text-pricing',turnId:'1',permissions:['appstore:read']});
  const r=await engine.handle('Show rich details for first one',{threadId:'plain-text-pricing',turnId:'2',permissions:['appstore:read']});
  assert.equal(r.kind,'ui');if(r.kind==='ui'){assert.match(r.openui,/\$9\.99/);assert.match(r.openui,/extracted/);}
  assert.equal(repairs,0);
 }finally{await a.close();await s.close();}
});


test('optional model text extraction is evidence checked and avoids recovering already-supported price',async()=>{
 const c=await loadCatalog(),a=await connectMockAppStore(),s=await connectRichMockServices();let repairs=0,extracts=0;
 try{
  const engine=new Engine(c,new Store(),async(cap,args)=>{
   if(cap==='appstore.search')return callAppStoreTool(a.client,'search_apps',args);
   if(cap==='appstore.details')return callAppStoreTool(a.client,'get_app',args);
   if(cap==='pricing.get')return 'For this fictional app, the listed price is $9.99.';
   if(cap==='pricing.recover'){repairs++;return s.invoke(cap,args);}
   return s.invoke(cap,args);
  },undefined,undefined,undefined,async(raw,fields)=>{
   extracts++;
   assert.match(raw,/\$9\.99/);
   assert.ok(fields['offer.price']);
   return [{path:'offer.price',value:'$9.99',evidence:'price is $9.99'}];
  });
  await engine.handle('Find drawing apps',{threadId:'model-extract',turnId:'x1',permissions:['appstore:read']});
  const out=await engine.handle('Show rich details for the first one',{threadId:'model-extract',turnId:'x2',permissions:['appstore:read']});
  assert.equal(out.kind,'ui');
  if(out.kind==='ui'){assert.match(out.openui,/extracted/);assert.match(out.openui,/\$9\.99/);}
  assert.equal(extracts,1);assert.equal(repairs,0);
 }finally{await a.close();await s.close();}
});
