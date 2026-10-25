/**
 * Domain-specific language understanding only. Crucially, no OpenUI component
 * name or MCP capability name occurs in this module. Renderers are selected
 * exclusively by corpus/view-registrations.json.
 */
import type {Json} from './runtime.ts';
import type {UIIntent} from './registry-resolver.ts';
type Slots=Record<string,Json>;
const ordinal=(s:string):number|undefined=>{
 const match=/\b(first|1st|second|2nd|third|3rd|fourth|4th)\b/i.exec(s);
 return match?({'first':0,'1st':0,'second':1,'2nd':1,'third':2,'3rd':2,'fourth':3,'4th':3} as Record<string,number>)[match[1]!.toLowerCase()]:undefined;
};
function choose(text:string,slots:Slots):string|undefined{
 const ids=Array.isArray(slots.appResultIds)?slots.appResultIds.filter((x):x is string=>typeof x==='string'):[];
 const names=Array.isArray(slots.appResultNames)?slots.appResultNames:[];
 const index=ordinal(text);
 if(index!==undefined)return ids[index];
 const found=names.findIndex(x=>typeof x==='string'&&text.toLowerCase().includes(x.toLowerCase()));
 if(found>=0)return ids[found];
 if(/\b(other|another)\b/i.test(text)&&ids.length>1)return ids.find(x=>x!==slots.selectedAppId);
 if(/\b(it|this|that|selected)\b/i.test(text)&&typeof slots.selectedAppId==='string')return slots.selectedAppId;
 if(ids.length===1)return ids[0];
 return undefined;
}
function queryFromText(text:string):string{
 const s=text.trim().replace(/[?!.,]+$/,'');
 const suffix=/\bapps?\s+(?:for|about|related to)\s+(.+)$/i.exec(s);
 if(suffix)return suffix[1]!.replace(/\b(please|on app store|in app store)\b/gi,'').trim();
 const prefix=/\b(?:find|show|search(?: for)?|browse|list)\s+(?:me\s+)?(?:some\s+)?(.+?)\s+apps?\b/i.exec(s);
 if(prefix)return prefix[1]!.replace(/^the\s+/i,'').trim();
 return s.replace(/^(?:find|show|search(?: for)?|browse|list)\s+/i,'').replace(/\s+apps?$/i,'').trim();
}
export function appStoreIntent(text:string,slots:Slots):UIIntent|null{
 const s=text.toLowerCase(),known=Array.isArray(slots.appResultIds)&&slots.appResultIds.length>0;
 const compare=/\b(compare|comparison|versus|vs\.?|side by side)\b/.test(s);
 const details=/\b(detail|details|open|about|more|tell me|drill down|look at)\b/.test(s);
 const priceOnly=/\b(only free|free ones|just free|paid ones|only paid)\b/.test(s);
 const explicitSearch=/\b(find|search|browse|list|show|discover)\b.*\bapps?\b/.test(s)||/\bapps?\s+(?:for|about)\b/.test(s);
 const domainMention=/\b(app store|appstore)\b/.test(s);
 if(compare&&known){
  const ids=(slots.appResultIds as string[]).slice(0,4);
  const chosen=choose(text,slots);
  const second=/(?:second|2nd)/i.test(text)?ids[1]:undefined;
  const targets=chosen&&second&&chosen!==second?[chosen,second]:ids.slice(0,3);
  if(targets.length<2)return null;
  return {domain:'appstore',action:'compare',entity:'app',cardinality:'comparison',params:{ids:targets},statePatch:{appIntent:'compare'}};
 }
 if(details&&(known||domainMention)){
  const id=choose(text,slots);if(!id)return null;
  return {domain:'appstore',action:'inspect',entity:'app',cardinality:'one',params:{id},statePatch:{appIntent:'detail',selectedAppId:id}};
 }
 if(explicitSearch||(priceOnly&&typeof slots.appQuery==='string')||(domainMention&&/\b(find|search)\b/.test(s))){
  const query=priceOnly?String(slots.appQuery):queryFromText(text);
  const price:'all'|'free'|'paid'=priceOnly?(/\bpaid\b/.test(s)?'paid':'free'):/\b(?:only free|free apps|free ones)\b/.test(s)?'free':'all';
  return {domain:'appstore',action:'search',entity:'app',cardinality:'many',params:{query,price,limit:12},statePatch:{appIntent:'search',appQuery:query,appPrice:price,selectedAppId:''}};
 }
 return null;
}
export function ciIntent(text:string,slots:Slots):UIIntent|null{
 const s=text.toLowerCase();
 if(!/\b(fail(?:ed|ing|ures?)?|ci|builds?|actions)\b/.test(s)&&!(/\b(only|last|branch)\b/.test(s)&&slots.intent==='builds'))return null;
 const requestedRepo=/\b(?:for|of)\s+([a-z0-9_-]+)/i.exec(text)?.[1];
 const repo=requestedRepo&&!['main','master','dev','release','the','branch'].includes(requestedRepo.toLowerCase())?requestedRepo:(typeof slots.repository==='string'?slots.repository:'atlas');
 const time=/\b(?:last|past)\s+(\d+)\s+(hours?|days?)/i.exec(text);
 const hours=time?Number(time[1])*(time[2]!.startsWith('day')?24:1):(typeof slots.hours==='number'?slots.hours:24);
 const branch=/\b(?:on\s+|for\s+|only\s+(?:on\s+)?(?:the\s+)?)?(main|dev|release|master)\s+branch\b/i.exec(text)?.[1]??(typeof slots.branch==='string'?slots.branch:'all');
 return {domain:'ci',action:'inspect',entity:'build',cardinality:'many',
  params:{repository:repo,branch,hours,heading:'Failed CI builds · '+repo},
  statePatch:{intent:'builds',repository:repo,branch,hours}};
}
export function parseIntent(text:string,slots:Slots):UIIntent|null{
 return appStoreIntent(text,slots)??ciIntent(text,slots);
}
