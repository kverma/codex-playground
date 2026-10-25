/**
 * Trusted boundary between arbitrary MCP output and typed OpenUI data.
 *
 * A field's fallback is a display-compatible placeholder, NEVER a verified fact.
 * Provenance/status is delivered alongside data so UI cannot confuse missing
 * with real "Free"/"No tracking"/"0 reviews". No dynamic code execution.
 */
export type Expected='string'|'number'|'boolean'|'array'|'object';
export type FieldRule={
 type:Expected; required?:boolean; aliases?:string[];
 missing?:'unavailable'|'fail'; placeholder?:unknown;
 coerce?:'numeric-string'; recoverable?:boolean;
};
export type NormalizationRule={
 source:string;
 fields:Record<string,FieldRule>;
};
export type FieldStatus='present'|'coerced'|'extracted'|'missing'|'invalid'|'recovered';
export type Provenance={status:FieldStatus;source:string;reason?:string;evidence?:string};
export type Quality={status:'ok'|'partial';source:string;fields:Record<string,Provenance>};
export type ExtractionProposal={path:string;value:unknown;evidence:string};
export type TextExtractor=(text:string,fields:Record<string,FieldRule>)=>Promise<ExtractionProposal[]>;
export type Normalized={value:Record<string,unknown>;quality:Quality;missingRecoverable:string[]};
const forbidden=new Set(['__proto__','constructor','prototype']);
const pathRE=/^[a-zA-Z][\w-]*(?:\.[a-zA-Z][\w-]*)*$/;
const plain=(v:unknown):v is Record<string,unknown>=>!!v&&typeof v==='object'&&!Array.isArray(v);
const ok=(v:unknown,t:Expected)=>t==='array'?Array.isArray(v):t==='object'?plain(v):typeof v===t;
const clone=(v:unknown):unknown=>structuredClone(v);
const get=(v:unknown,path:string):unknown=>{
 let cur=v;
 for(const key of path.split('.')){if(!plain(cur)||!Object.prototype.hasOwnProperty.call(cur,key))return undefined;cur=cur[key];}
 return cur;
};
const set=(o:Record<string,unknown>,path:string,value:unknown)=>{
 const keys=path.split('.');let cur=o;
 for(const k of keys.slice(0,-1)){if(!plain(cur[k]))cur[k]=Object.create(null) as Record<string,unknown>;cur=cur[k] as Record<string,unknown>;}
 cur[keys[keys.length-1]!]=value;
};
function guard(rule:NormalizationRule){
 if(!rule||typeof rule.source!=='string'||!rule.source||!plain(rule.fields)||Object.keys(rule.fields).length>60)throw Error('Invalid normalization contract');
 for(const [path,s] of Object.entries(rule.fields)){
  if(!pathRE.test(path)||path.split('.').some(k=>forbidden.has(k))||!['string','number','boolean','array','object'].includes(s.type))throw Error('Invalid normalization path or type: '+path);
  if(s.missing==='unavailable'&&(!Object.prototype.hasOwnProperty.call(s,'placeholder')||!ok(s.placeholder,s.type)))throw Error('Missing typed unavailable placeholder: '+path);
  for(const a of s.aliases??[])if(!pathRE.test(a)||a.split('.').some(k=>forbidden.has(k)))throw Error('Unsafe normalization alias');
 }
}
const jsonText=(text:string):unknown=>{
 const trimmed=text.trim().replace(/^\x60\x60\x60(?:json)?\s*\n/i,'').replace(/\n\x60\x60\x60\s*$/,'');
 try{return JSON.parse(trimmed);}catch{return null;}
};
/** Only exact field-style key/value lines are extracted; natural prose is not guessed. */
export function decodeToolPayload(raw:unknown,rule:NormalizationRule):{data:unknown;text?:string;parsed:boolean}{
 if(plain(raw)&&('structuredContent' in raw||'content' in raw)){
  if(plain(raw.structuredContent))return {data:raw.structuredContent,parsed:true};
  if(Array.isArray(raw.content)){const item=raw.content.find((x:unknown)=>plain(x)&&typeof x.text==='string') as Record<string,unknown>|undefined;if(item)return decodeToolPayload(item.text,rule);}
 }
 if(typeof raw!=='string')return {data:raw,parsed:plain(raw)};
 if(raw.length>50000)throw Error('Unstructured tool response exceeds safe limit');
 const json=jsonText(raw);
 if(plain(json))return {data:json,text:raw,parsed:true};
 const aliases=new Map<string,string>();
 for(const [path,spec] of Object.entries(rule.fields)){
  for(const key of [path,...(spec.aliases??[])])aliases.set(key.toLowerCase(),path);
 }
 const object:Record<string,unknown>=Object.create(null);
 for(const line of raw.split(/\r?\n/).slice(0,150)){
  const m=/^\s*([a-zA-Z][\w.-]{0,70})\s*:\s*(.{1,1500})\s*$/.exec(line);
  if(!m)continue;
  const path=aliases.get(m[1]!.toLowerCase());if(!path)continue;
  set(object,path,m[2]!.trim());
 }
 return {data:object,text:raw,parsed:false};
}
export function normalizeToolResponse(raw:unknown,rule:NormalizationRule,options?:{recovered?:string[];extractions?:ExtractionProposal[]}):Normalized{
 guard(rule);
 const decoded=decodeToolPayload(raw,rule);
 const data=plain(decoded.data)?decoded.data:Object.create(null) as Record<string,unknown>;
 const value:Record<string,unknown>=Object.create(null);
 const fields:Record<string,Provenance>=Object.create(null);
 const recoverable:string[]=[];
 const accepted=new Map<string,ExtractionProposal>();
 if(decoded.text){
  for(const item of options?.extractions??[]){
   if(!pathRE.test(item.path)||!rule.fields[item.path]||typeof item.evidence!=='string'||!item.evidence||!decoded.text.includes(item.evidence))throw Error('Unverifiable extracted evidence: '+item.path);
   // Only accept a source string that literally appears inside cited evidence.
   if(typeof item.value!=='string'||!item.evidence.includes(item.value))throw Error('Extracted value absent from cited evidence: '+item.path);
   accepted.set(item.path,item);
  }
 }
 for(const [path,spec] of Object.entries(rule.fields)){
  let original=get(data,path),aliasUsed=false,proposal=accepted.get(path);
  if(original===undefined||original===null)for(const alias of spec.aliases??[]){
   const candidate=get(data,alias);
   if(candidate!==undefined&&candidate!==null){original=candidate;aliasUsed=true;break;}
  }
  if((original===undefined||original===null)&&proposal)original=proposal.value;
  let status:FieldStatus='present';
  if(original===undefined||original===null)status='missing';
  else if(!ok(original,spec.type)){
   if(spec.type==='number'&&spec.coerce==='numeric-string'&&typeof original==='string'&&/^[+-]?(?:\d+\.?\d*|\.\d+)$/.test(original.trim())&&Number.isFinite(Number(original.trim()))){
    original=Number(original.trim());status='coerced';
   }else status='invalid';
  }
  if(status==='present'&&(aliasUsed||proposal))status=proposal?'extracted':'coerced';
  if(status==='missing'||status==='invalid'){
   fields[path]={status,source:rule.source,reason:status==='missing'?'Field not supplied':'Invalid type'};
   if(spec.required||spec.missing==='fail')throw Error('Essential field '+path+' '+status+' in '+rule.source);
   if(spec.recoverable)recoverable.push(path);
   if(spec.missing==='unavailable')set(value,path,clone(spec.placeholder));
  }else{
   set(value,path,clone(original));
   fields[path]={status:options?.recovered?.includes(path)?'recovered':status,source:rule.source,...(proposal?{evidence:proposal.evidence}:{})};
  }
 }
 // The explicit quality object is metadata, not tool-authored content.
 const quality:Quality={source:rule.source,status:Object.values(fields).some(v=>v.status==='missing'||v.status==='invalid')?'partial':'ok',fields};
 value.quality=quality;
 // A uniform display status is set by the normalizer, never trusted from provider.
 value.status=quality.status==='ok'?'ok':'partial';
 value.source=rule.source;
 return {value,quality,missingRecoverable:recoverable};
}
export async function normalizeWithExtractor(raw:unknown,rule:NormalizationRule,extractor?:TextExtractor):Promise<Normalized>{
 const first=normalizeToolResponse(raw,rule);
 if(!extractor||typeof raw!=='string'||!first.missingRecoverable.length)return first;
 const proposed=await extractor(raw,Object.fromEntries(first.missingRecoverable.map(k=>[k,rule.fields[k]!])));
 return normalizeToolResponse(raw,rule,{extractions:proposed});
}
