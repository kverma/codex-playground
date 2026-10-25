import {parseIntent} from './intent-parsers.ts';
import {resolveView,validateRegistry,type Recipe,type UIIntent} from './registry-resolver.ts';
import {readFile} from 'node:fs/promises';
export type Json=null|boolean|number|string|Json[]|{[key:string]:Json};
export type Props=Record<string,unknown>;
export type Node={component:string;props:Props};
export type Op={id:string;capability:string;args:Record<string,Json>};
export type Plan={version:1;operations:Op[];view:Node;statePatch?:Record<string,Json>;stateCapture?:Record<string,string>};
export type Context={threadId:string;turnId:string;permissions:string[]};
export type Component={description:string;args:string[];requiredBindings?:string[];propTypes?:Record<string,OutputType>};
export type OutputType='string'|'number'|'boolean'|'array'|'object'|'nullable-object';
export type Capability={permissions:string[];readOnly:boolean;args:string[];outputs?:Record<string,OutputType>};
export type Catalog={components:Record<string,Component>;capabilities:Record<string,Capability>;views?:Recipe[]};
export type Invoke=(name:string,args:Record<string,Json>,ctx:Context)=>Promise<unknown>;
export type Planner=(text:string,context:Record<string,Json>,catalog:Catalog)=>Promise<unknown>;
export type Durable={run<T>(id:string,fn:()=>Promise<T>):Promise<T>};
const forbidden=new Set(['__proto__','prototype','constructor']);
const own=(obj:object,key:string)=>Object.prototype.hasOwnProperty.call(obj,key);
const isObj=(v:unknown):v is Record<string,unknown>=>v!==null&&typeof v==='object'&&!Array.isArray(v);
const pathPattern=/^results\.[A-Za-z][A-Za-z0-9_-]*(?:\.[A-Za-z][A-Za-z0-9_-]*)*$/;
function checkJson(value:unknown,depth=0):void{
 if(depth>14)throw Error('JSON depth exceeded');
 if(value===null||typeof value==='boolean')return;
 if(typeof value==='string'){if(value.length>10000)throw Error('String length exceeded');return;}
 if(typeof value==='number'&&Number.isFinite(value))return;
 if(Array.isArray(value)){if(value.length>300)throw Error('Array budget exceeded');value.forEach(v=>checkJson(v,depth+1));return;}
 if(isObj(value)){if(Object.keys(value).length>70)throw Error('Object budget exceeded');for(const [k,v] of Object.entries(value)){if(forbidden.has(k))throw Error('Unsafe key');checkJson(v,depth+1);}return;}
 throw Error('Non-JSON data');
}
export function validate(raw:unknown,catalog:Catalog,ctx:Context):Plan{
 if(!isObj(raw)||raw.version!==1||!Array.isArray(raw.operations)||raw.operations.length>12)throw Error('Invalid plan');
 const ids=new Set<string>();
 for(const op of raw.operations){
  if(!isObj(op)||typeof op.id!=='string'||!/^[a-zA-Z][a-zA-Z0-9_-]*$/.test(op.id)||ids.has(op.id))throw Error('Invalid operation');
  ids.add(op.id);
  if(typeof op.capability!=='string'||!own(catalog.capabilities,op.capability))throw Error('Unknown capability');
  const cap=catalog.capabilities[op.capability]!;
  if(!cap.readOnly||!cap.permissions.every(p=>ctx.permissions.includes(p)))throw Error('Tool not authorized');
  if(!isObj(op.args)||Object.keys(op.args).some(k=>!cap.args.includes(k)))throw Error('Unrecognized tool arguments');
  checkJson(op.args);
 }
 let count=0;
 const visit=(v:unknown,depth:number):void=>{
  if(depth>16||++count>150)throw Error('View budget exceeded');
  if(isObj(v)&&own(v,'component')){
   const name=v.component;
   if(typeof name!=='string'||!own(catalog.components,name))throw Error('Unregistered component');
   const def=catalog.components[name]!;
   if(!isObj(v.props)||Object.keys(v.props).some(k=>!def.args.includes(k)))throw Error('Unregistered component props');
   for(const [key,x] of Object.entries(v.props)){
    if(def.requiredBindings?.includes(key)&&(!isObj(x)||!own(x,'$bind')))throw Error('Factual property requires binding');
    visit(x,depth+1);
   }
   return;
  }
  if(isObj(v)&&own(v,'$bind')){
   if(Object.keys(v).length!==1||typeof v.$bind!=='string'||!pathPattern.test(v.$bind)||v.$bind.split('.').some(k=>forbidden.has(k))||!ids.has(v.$bind.split('.')[1]!))throw Error('Invalid data reference');
   return;
  }
  if(Array.isArray(v)){if(v.length>120)throw Error('Too many children');v.forEach(x=>visit(x,depth+1));return;}
  if(isObj(v)){for(const [k,x] of Object.entries(v)){if(forbidden.has(k))throw Error('Unsafe property');visit(x,depth+1);}return;}
  checkJson(v);
 };
 visit(raw.view,0);
 if(raw.statePatch!==undefined){if(!isObj(raw.statePatch))throw Error('Invalid state patch');checkJson(raw.statePatch);}
 if(raw.stateCapture!==undefined){
  if(!isObj(raw.stateCapture)||Object.keys(raw.stateCapture).length>12)throw Error('Invalid state capture');
  for(const [key,path] of Object.entries(raw.stateCapture)){
   if(!/^[a-zA-Z][a-zA-Z0-9]{0,63}$/.test(key)||typeof path!=='string'||!pathPattern.test(path)||!ids.has(path.split('.')[1]!))throw Error('Unsafe state capture');
  }
 }
 return raw as unknown as Plan;
}
function resolve(path:string,results:Record<string,unknown>):unknown{
 if(!pathPattern.test(path))throw Error('Unsafe binding');
 let cur:unknown={results};
 for(const k of path.split('.')){if(!isObj(cur)||!own(cur,k))throw Error('Missing result: '+path);cur=cur[k];}
 return cur;
}
/** Reject malformed typed tool responses before they reach component props. */
export function validateResult(name:string,value:unknown,catalog:Catalog):void{
 const fields=catalog.capabilities[name]?.outputs;
 if(!fields)return;
 if(!isObj(value))throw Error('Invalid capability result: expected object from '+name);
 for(const [field,type] of Object.entries(fields)){
  if(!own(value,field))throw Error('Missing capability result field: '+name+'.'+field);
  const got=value[field];
  const valid=type==='array'?Array.isArray(got):type==='nullable-object'?got===null||isObj(got):type==='object'?isObj(got):typeof got===type;
  if(!valid)throw Error('Capability result type mismatch: '+name+'.'+field+' expected '+type);
 }
}

export function compile(plan:Plan,catalog:Catalog,results:Record<string,unknown>):string{
 const lines:string[]=[];let next=0;
 const val=(v:unknown):string=>{
  if(isObj(v)&&own(v,'$bind'))return JSON.stringify(resolve(String(v.$bind),results));
  if(isObj(v)&&own(v,'component'))return component(v as unknown as Node);
  if(Array.isArray(v))return '['+v.map(val).join(', ')+']';
  if(isObj(v))return '{'+Object.entries(v).map(([k,x])=>JSON.stringify(k)+': '+val(x)).join(', ')+'}';
  const s=JSON.stringify(v);if(s===undefined||s.length>300000)throw Error('Invalid output data');return s;
 };
 const component=(node:Node,id='n_'+(++next)):string=>{
  const def=catalog.components[node.component]!;
  const last=def.args.reduce((i,k,j)=>own(node.props,k)?j:i,-1);
  const args=def.args.slice(0,last+1).map(k=>own(node.props,k)?val(node.props[k]):'null');
  lines.push(id+' = '+node.component+'('+args.join(', ')+')');
  return id;
 };
 component(plan.view,'root');
 return lines.join('\n');
}
export type History={threadId:string;revision:number;slots:Record<string,Json>;turns:Array<{turnId:string;openui:string;plan:Plan}>};
export class Store{
 private readonly histories=new Map<string,History>();
 get(id:string):History{return this.histories.get(id)??{threadId:id,revision:0,slots:{},turns:[]};}
 save(next:History,revision:number){if(this.get(next.threadId).revision!==revision)throw Error('Revision conflict');this.histories.set(next.threadId,next);}
}
export async function loadCatalog():Promise<Catalog>{
 const root=new URL('../corpus/',import.meta.url);
 const [base,custom,caps,views]=await Promise.all(['base.json','custom.json','capabilities.json','view-registrations.json'].map(f=>readFile(new URL(f,root),'utf8').then(JSON.parse)));
 const catalog:Catalog={components:{...base.components,...custom.components},capabilities:caps.capabilities,views:views.views};
 validateRegistry(catalog);
 return catalog;
}
export class Engine{
 constructor(public catalog:Catalog,public store:Store,public invoke:Invoke,public planner?:Planner,public durable?:Durable){}
 async handle(text:string,ctx:Context){
  const old=this.store.get(ctx.threadId),retry=old.turns.find(x=>x.turnId===ctx.turnId);
  if(retry)return {...retry,revision:old.revision};
  // Domain parsers only produce semantic intents. The chosen view and MCP
  // capability come entirely from the JSON registry, not from routing code.
  const intent=parseIntent(text,old.slots);
  const fallback=intent?null:(this.planner?await this.planner(text,old.slots,this.catalog):null);
  const isIntent=(v:unknown):v is UIIntent=>isObj(v)&&typeof v.domain==='string'&&typeof v.action==='string'&&typeof v.entity==='string'&&isObj(v.params);
  const raw=intent?resolveView(intent,this.catalog,ctx).plan:isIntent(fallback)?resolveView(fallback,this.catalog,ctx).plan:fallback;
  if(!raw)throw Error('Arbitrary request requires configured planner');
  const plan=validate(raw,this.catalog,ctx);
  const results:Record<string,unknown>={};
  for(const op of plan.operations){
   const exec=()=>this.invoke(op.capability,op.args,ctx);
   const output=this.durable?await this.durable.run(ctx.threadId+'/'+ctx.turnId+'/'+op.id,exec):await exec();
   validateResult(op.capability,output,this.catalog);
   results[op.id]=output;
  }
  const openui=compile(plan,this.catalog,results);
  const captured:Record<string,Json>={};
  for(const [key,path] of Object.entries(plan.stateCapture??{})){
   const data=resolve(path,results);checkJson(data);captured[key]=data as Json;
  }
  this.store.save({threadId:ctx.threadId,revision:old.revision+1,slots:{...old.slots,...(plan.statePatch??{}),...captured},turns:[...old.turns,{turnId:ctx.turnId,plan,openui}].slice(-30)},old.revision);
  return {turnId:ctx.turnId,revision:old.revision+1,plan,openui};
 }
}
