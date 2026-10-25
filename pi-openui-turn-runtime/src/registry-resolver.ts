/**
 * Generic schema-driven UI composition. No domain names, component names or MCP
 * capability identifiers are embedded in the resolver.
 *
 * Candidate discovery is constrained by domain/action/entity/cardinality, followed
 * by ACL, tags, prop compatibility, and deterministic tie rejection.
 * Recipes remain data, never executable JavaScript from a planner.
 */
import type {Catalog,Json,Node,Plan,Context} from './runtime.ts';

export type Cardinality='one'|'many'|'comparison';
export type UIIntent={
 domain:string;action:string;entity:string;cardinality:Cardinality;
 params:Record<string,Json>;statePatch?:Record<string,Json>;
 presentation?:string;
};
export type Recipe={
 id:string;domain:string;action:string;entity:string;cardinality:Cardinality;
 priority:number;description:string;dataContract:string;tags:string[];
 operations:Array<{id:string;capability:string;args:Record<string,unknown>}>;
 view:Node;stateCapture?:Record<string,string>;
};
const own=(o:object,k:string)=>Object.prototype.hasOwnProperty.call(o,k);
const safe=/^[a-zA-Z][a-zA-Z0-9_-]*$/;
const isObj=(v:unknown):v is Record<string,unknown>=>!!v&&typeof v==='object'&&!Array.isArray(v);
const fail=(why:string):never=>{throw Error('Component registry: '+why);};
const components=(value:unknown,catalog:Catalog):void=>{
 if(!isObj(value))return;
 if(typeof value.component==='string'){
  if(!own(catalog.components,value.component))fail('unknown component '+value.component);
  const def=catalog.components[value.component]!;
  if(!isObj(value.props))fail('props object required');
  for(const key of Object.keys(value.props))if(!def.args.includes(key))fail('unknown '+value.component+'.'+key);
  for(const key of def.requiredBindings??[]){
   if(own(value.props,key)&&(!isObj(value.props[key])||typeof (value.props[key] as Record<string,unknown>).$bind!=='string'))fail('factual prop must bind '+value.component+'.'+key);
  }
  for(const x of Object.values(value.props))components(x,catalog);
 }else if(Array.isArray(value)){for(const x of value)components(x,catalog);}
 else for(const x of Object.values(value))components(x,catalog);
};
const visit=(v:unknown,cb:(key:string,ref:string)=>void)=>{
 if(Array.isArray(v)){v.forEach(x=>visit(x,cb));return;}
 if(!isObj(v))return;
 if(own(v,'$param')){
  if(Object.keys(v).length!==1||typeof v.$param!=='string'||!safe.test(v.$param))fail('invalid parameter reference');
  cb('param',v.$param);
  return;
 }
 if(own(v,'$bind')){
  if(Object.keys(v).length!==1||typeof v.$bind!=='string'||!/^results\.[a-zA-Z][\w-]*(?:\.[a-zA-Z][\w-]*)*$/.test(v.$bind))fail('invalid result reference');
  cb('result',v.$bind);
  return;
 }
 if(Object.keys(v).some(k=>['__proto__','constructor','prototype'].includes(k)))fail('unsafe object key');
 for(const x of Object.values(v))visit(x,cb);
};
export function validateRegistry(catalog:Catalog):void{
 if(!Array.isArray(catalog.views))fail('views.json missing');
 const ids=new Set<string>();
 for(const v of catalog.views){
  if(!isObj(v)||typeof v.id!=='string'||ids.has(v.id)||!safe.test(v.id.replace(/\./g,'-')))fail('duplicate/invalid view ID');
  ids.add(v.id);
  if(!['one','many','comparison'].includes(v.cardinality)||!safe.test(v.domain)||!safe.test(v.action)||!safe.test(v.entity))fail('invalid semantic contract');
  if(!Number.isInteger(v.priority)||v.priority<0||v.priority>100||!Array.isArray(v.tags)||!v.tags.every(x=>typeof x==='string'&&safe.test(x)))fail('invalid ranking metadata');
  if(!Array.isArray(v.operations)||!v.operations.length||v.operations.length>12)fail('invalid recipe operations');
  const operations=new Set<string>();
  for(const op of v.operations){
   if(!safe.test(op.id)||operations.has(op.id))fail('duplicate operation ID');
   operations.add(op.id);
   if(!own(catalog.capabilities,op.capability))fail('unknown capability '+op.capability);
   const cap=catalog.capabilities[op.capability]!;
   if(!cap.readOnly)fail('view registry cannot execute mutating capabilities');
   if(!isObj(op.args)||Object.keys(op.args).some(k=>!cap.args.includes(k)))fail('incompatible capability arguments');
   visit(op.args,(kind,ref)=>{if(kind==='result'&&!operations.has(ref.split('.')[1]!))fail('future operation dependency');});
  }
  components(v.view,catalog);
  visit(v.view,(kind,ref)=>{if(kind==='result'&&!operations.has(ref.split('.')[1]!))fail('view depends on unavailable result');});
  for(const [k,path] of Object.entries(v.stateCapture??{})){
   if(!safe.test(k)||typeof path!=='string'||!/^results\.[a-zA-Z][\w-]*(?:\.[a-zA-Z][\w-]*)*$/.test(path)||!operations.has(path.split('.')[1]!))fail('invalid state capture');
  }
 }
}
export function discover(intent:UIIntent,catalog:Catalog,ctx:Context){
 const candidates=(catalog.views??[]).filter(v=>v.domain===intent.domain&&v.action===intent.action&&v.entity===intent.entity&&v.cardinality===intent.cardinality);
 if(!candidates.length)throw Error('No compatible registered UI recipe for '+[intent.domain,intent.action,intent.entity,intent.cardinality].join('/'));
 const authorized=candidates.filter(v=>v.operations.every(op=>(catalog.capabilities[op.capability]?.permissions??[]).every(p=>ctx.permissions.includes(p))));
 if(!authorized.length)throw Error('No authorized UI recipe for requested data capabilities');
 const eligible=authorized.filter(v=>{
  try{
   visit(v.operations,(kind,ref)=>{if(kind==='param'&&!own(intent.params,ref))throw Error('Missing intent parameter '+ref);});
   visit(v.view,(kind,ref)=>{if(kind==='param'&&!own(intent.params,ref))throw Error('Missing view parameter '+ref);});
   return true;
  }catch{return false;}
 });
 if(!eligible.length)throw Error('No compatible UI recipe has the required input slots');
 const ranking=eligible.map(v=>({view:v,score:v.priority+(intent.presentation&&v.tags.includes(intent.presentation)?1000:0)})).sort((a,b)=>b.score-a.score);
 if(ranking.length>1&&ranking[0]!.score===ranking[1]!.score)throw Error('Ambiguous UI selection: '+ranking.filter(x=>x.score===ranking[0]!.score).map(x=>x.view.id).join(', '));
 return ranking[0]!.view;
}
export function resolveView(intent:UIIntent,catalog:Catalog,ctx:Context):{plan:Plan;recipeId:string}{
 const recipe=discover(intent,catalog,ctx);
 const substitute=(v:unknown):unknown=>{
  if(Array.isArray(v))return v.map(substitute);
  if(!isObj(v))return v;
  if(own(v,'$param')){
   if(typeof v.$param!=='string'||!own(intent.params,v.$param))fail('unbound intent parameter');
   return structuredClone(intent.params[v.$param]);
  }
  if(own(v,'$bind'))return {'$bind':v.$bind};
  return Object.fromEntries(Object.entries(v).map(([k,x])=>[k,substitute(x)]));
 };
 const plan:Plan={
  version:1,
  operations:recipe.operations.map(op=>({id:op.id,capability:op.capability,args:substitute(op.args) as Record<string,Json>})),
  view:substitute(recipe.view) as Node,
  ...(intent.statePatch?{statePatch:structuredClone(intent.statePatch)}:{}),
  ...(recipe.stateCapture?{stateCapture:{...recipe.stateCapture}}:{})
 };
 return {plan,recipeId:recipe.id};
}
