import {connectMockAppStore,callAppStoreTool} from './mock-app-store.ts';
import{createServer}from'node:http';import{Engine,Store,loadCatalog,type Invoke,type Planner,type Json}from'./runtime.ts';
const catalog=await loadCatalog(),store=new Store();
const mockAppStore=await connectMockAppStore();
const fixture=[{name:'Unit tests #421',repository:'atlas',service:'atlas',branch:'main',conclusion:'failure',url:'https://github.com/example/atlas/actions/runs/421'},{name:'Integration #419',repository:'atlas',service:'atlas',branch:'main',conclusion:'failure',url:'https://github.com/example/atlas/actions/runs/419'},{name:'Release #416',repository:'atlas',service:'atlas',branch:'dev',conclusion:'failure',url:'https://github.com/example/atlas/actions/runs/416'}];
const invoke:Invoke=async(cap,args)=>{
 const tools={'appstore.search':'search_apps','appstore.details':'get_app','appstore.compare':'compare_apps'} as const;
 if(cap in tools)return callAppStoreTool(mockAppStore.client,tools[cap as keyof typeof tools],args);
 if(cap==='demo.failedBuilds'){const repo=String(args.repository??'atlas').toLowerCase(),branch=String(args.branch??'all').toLowerCase();const runs=fixture.filter(x=>x.repository===repo&&(branch==='all'||x.branch===branch));return {count:String(runs.length),window:'Last '+String(args.hours??24)+' hours · DEMO DATA',prettyRuns:JSON.stringify(runs,null,2),runs};}
 if(cap==='api.get'){const endpoint=new URL(String(args.endpoint));const allowed=(process.env.API_ALLOWED_ORIGINS??'').split(',').filter(Boolean);if(endpoint.protocol!=='https:'||!allowed.includes(endpoint.origin))throw Error('Denied API origin');const params=args.params;if(params&&typeof params==='object'&&!Array.isArray(params))for(const [k,v]of Object.entries(params))if(['string','number','boolean'].includes(typeof v))endpoint.searchParams.set(k,String(v));const r=await fetch(endpoint,{signal:AbortSignal.timeout(15000),headers:{accept:'application/json'}});if(!r.ok)throw Error('API '+r.status);const data=await r.text();if(data.length>200000)throw Error('API response too big');return JSON.parse(data);}
 if(cap==='mcp.invoke'){const tool=String(args.tool);if(!(process.env.MCP_ALLOWED_TOOLS??'').split(',').includes(tool))throw Error('MCP tool denied');const url=process.env.MCP_GATEWAY_URL;if(!url||new URL(url).protocol!=='https:')throw Error('HTTPS gateway required');const r=await fetch(url,{method:'POST',headers:{'content-type':'application/json',...(process.env.MCP_GATEWAY_TOKEN?{authorization:'Bearer '+process.env.MCP_GATEWAY_TOKEN}:{})},body:JSON.stringify({tool,input:args.input??{}}),signal:AbortSignal.timeout(20000)});if(!r.ok)throw Error('MCP '+r.status);const data=await r.text();if(data.length>200000)throw Error('MCP response too big');return JSON.parse(data);}
 throw Error('Unknown capability');
};
const planner:Planner|undefined=process.env.PLANNER_URL&&process.env.PLANNER_TOKEN?async(text,context,registered)=>{
 const endpoint=new URL(process.env.PLANNER_URL!);if(endpoint.protocol!=='https:')throw Error('Planner requires HTTPS');
 const response=await fetch(endpoint,{method:'POST',headers:{'content-type':'application/json',authorization:'Bearer '+process.env.PLANNER_TOKEN},body:JSON.stringify({model:process.env.PLANNER_MODEL??'approved-model',messages:[{role:'system',content:'Return JSON semantic intent only: {domain,action,entity,cardinality,params,presentation?}, selecting only compatible entries from the authorized view registry: '+JSON.stringify(registered.views?.map(v=>({domain:v.domain,action:v.action,entity:v.entity,cardinality:v.cardinality,tags:v.tags,description:v.description})))+'. Never output UI component names, tool identifiers, executable code, or claimed data values. Never add permissions. The server decides tools and rendering.'},{role:'user',content:JSON.stringify({text,context})}],response_format:{type:'json_object'}}),signal:AbortSignal.timeout(20000)});
 if(!response.ok)throw Error('Planner HTTP '+response.status);const value=await response.json();return JSON.parse(value.choices?.[0]?.message?.content??'{}');
}:undefined;
const engine=new Engine(catalog,store,invoke,planner);
const server=createServer(async(req,res)=>{
 const reply=(status:number,obj:unknown)=>{res.writeHead(status,{'content-type':'application/json','cache-control':'no-store'});res.end(JSON.stringify(obj));};
 try{
 if(req.method==='GET'&&req.url==='/api/catalog')return reply(200,{components:Object.keys(catalog.components),capabilities:Object.keys(catalog.capabilities),viewCount:catalog.views?.length??0});
 if(req.method==='GET'&&req.url?.startsWith('/api/views')){
  const u=new URL(req.url,'http://127.0.0.1');
  return reply(200,{views:(catalog.views??[]).filter(v=>(!u.searchParams.has('domain')||u.searchParams.get('domain')===v.domain)&&(!u.searchParams.has('action')||u.searchParams.get('action')===v.action)).map(v=>({id:v.id,domain:v.domain,action:v.action,entity:v.entity,cardinality:v.cardinality,description:v.description,tags:v.tags,priority:v.priority,dataContract:v.dataContract,components:[...new Set([...JSON.stringify(v.view).matchAll(/\"component\":\"([^\"]+)\"/g)].map(match=>match[1]))]}))});
 }
 if(req.method==='POST'&&req.url==='/api/chat'){
  let raw='';for await(const piece of req){raw+=String(piece);if(raw.length>20000)throw Error('Request too long');}
  const {threadId,turnId,text}=JSON.parse(raw);if(typeof threadId!=='string'||!/^[\w-]{1,100}$/.test(threadId)||typeof turnId!=='string'||!/^[\w-]{1,100}$/.test(turnId)||typeof text!=='string'||text.length>5000)throw Error('Invalid request');
  // DEMO permissions only. Production MUST authenticate and derive ACLs server-side.
  return reply(200,await engine.handle(text,{threadId,turnId,permissions:['ci:read','appstore:read']}));
 }
 reply(404,{error:'Not found'});
 }catch(e){reply(400,{error:String(e)});}
});
server.listen(Number(process.env.PORT??8787),'127.0.0.1',()=>console.log('OpenUI API ready (demo mode)'));
