import test from 'node:test';
import assert from 'node:assert/strict';
import {connectMockAppStore,callAppStoreTool,searchApps,getApp} from '../src/mock-app-store.ts';
import {Engine,Store,loadCatalog,type Invoke} from '../src/runtime.ts';

test('mock App Store MCP advertises three discoverable read-only tools and validates schemas',async()=>{
 const mcp=await connectMockAppStore();
 try{
  const list=await mcp.client.listTools();
  assert.deepEqual(list.tools.map(x=>x.name).sort(),['compare_apps','get_app','search_apps']);
  assert.ok(list.tools.every(x=>x.inputSchema&&x.annotations?.readOnlyHint));
  const result=await mcp.client.callTool({name:'search_apps',arguments:{query:'drawing',limit:12}});
  assert.equal(result.isError,undefined);
  assert.deepEqual((result.structuredContent as any).ids,['sketchflow','pixelcraft']);
  const invalid=await mcp.client.callTool({name:'search_apps',arguments:{query:'drawing',limit:999}});
  assert.equal(invalid.isError,true);
  const details=await callAppStoreTool(mcp.client,'get_app',{id:'sketchflow'}) as any;
  assert.equal(details.app.name,'SketchFlow Studio');
  assert.equal(details.app.screenshots.length,3);
  assert.equal(details.mock,true);
  const comp=await callAppStoreTool(mcp.client,'compare_apps',{ids:['sketchflow','pixelcraft']}) as any;
  assert.equal(comp.apps[0].price,'$9.99');
  assert.equal(comp.apps[1].price,'Free');
 }finally{await mcp.close();}
});

test('MCP stdio host is interoperable with an actual spawned MCP client',async()=>{
 const {Client}=await import('@modelcontextprotocol/client');
 const {StdioClientTransport}=await import('@modelcontextprotocol/client/stdio');
 const client=new Client({name:'test-stdio-appstore',version:'1.0.0'});
 try{
  await client.connect(new StdioClientTransport({command:process.execPath,args:['--import','tsx','src/app-store-stdio.ts']}));
  const list=await client.listTools();
  assert.ok(list.tools.some(t=>t.name==='get_app'));
  const result=await client.callTool({name:'get_app',arguments:{id:'focus-harbor'}});
  assert.equal((result.structuredContent as any).app.name,'Focus Harbor');
 }finally{await client.close();}
});

test('chat state: search → first detail → second detail → compare → free filter → free detail',async()=>{
 const mcp=await connectMockAppStore();
 try{
  const catalog=await loadCatalog();
  const calls:string[]=[];
  const tools:{[key:string]: 'search_apps'|'get_app'|'compare_apps'}={
   'appstore.search':'search_apps','appstore.details':'get_app','appstore.compare':'compare_apps'
  };
  const invoke:Invoke=async(cap,args)=>{calls.push(cap);if(!tools[cap])throw Error('unexpected capability');return callAppStoreTool(mcp.client,tools[cap],args);};
  const store=new Store(),engine=new Engine(catalog,store,invoke);
  const go=async(turnId:string,text:string)=>engine.handle(text,{threadId:'appstore-test',turnId,permissions:['appstore:read']});
  const search=await go('t1','Find drawing apps');
  assert.match(search.openui,/AppSearchGrid/);
  assert.match(search.openui,/SketchFlow Studio/);
  assert.deepEqual(store.get('appstore-test').slots.appResultIds,['sketchflow','pixelcraft']);
  const first=await go('t2','Show details for the first one');
  assert.match(first.openui,/AppDetailView/);assert.match(first.openui,/SketchFlow Studio/);
  assert.equal(store.get('appstore-test').slots.selectedAppId,'sketchflow');
  const second=await go('t3','Tell me more about the second app');
  assert.match(second.openui,/PixelCraft Pro/);assert.equal(store.get('appstore-test').slots.selectedAppId,'pixelcraft');
  const compare=await go('t4','Compare the first two apps');
  assert.match(compare.openui,/AppComparisonView/);
  assert.match(compare.openui,/SketchFlow Studio/);
  const free=await go('t5','Only free ones');
  assert.match(free.openui,/AppSearchGrid/);
  assert.deepEqual(store.get('appstore-test').slots.appResultIds,['pixelcraft']);
  const detail=await go('t6','Open the first one');
  assert.match(detail.openui,/PixelCraft Pro/);
  assert.deepEqual(calls,['appstore.search','appstore.details','appstore.details','appstore.compare','appstore.search','appstore.details']);
  const retried=await go('t6','Open the first one');
  assert.equal(retried.openui,detail.openui);
  assert.equal(calls.length,6,'Idempotent turn does not refetch');
 }finally{await mcp.close();}
});

test('unknown app, price filters and unauthorized capabilities fail closed',async()=>{
 assert.equal(getApp('nonexistent').app,null);
 assert.deepEqual(searchApps('drawing','free').ids,['pixelcraft']);
 assert.deepEqual(searchApps('drawing','paid').ids,['sketchflow']);
 const catalog=await loadCatalog(),e=new Engine(catalog,new Store(),async()=>({}));
 await assert.rejects(()=>e.handle('Find drawing apps',{threadId:'unauth',turnId:'t1',permissions:[]}),/No authorized UI recipe/);
});
