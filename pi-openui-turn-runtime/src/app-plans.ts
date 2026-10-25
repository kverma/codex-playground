import type {Json,Plan,Node} from './runtime.ts';
type Slots=Record<string,Json>;
const bind=(path:string)=>({'$bind':path});
const view=(title:string,component:string,props:Record<string,unknown>):Node=>({component:'Stack',props:{children:[
 {component:'TextContent',props:{text:title}},
 {component,props}
]}});
const ordinal=(s:string):number|undefined=>{
 const match=/\b(first|1st|second|2nd|third|3rd|fourth|4th)\b/i.exec(s);
 return match?({'first':0,'1st':0,'second':1,'2nd':1,'third':2,'3rd':2,'fourth':3,'4th':3} as Record<string,number>)[match[1]!.toLowerCase()]:undefined;
};
function choose(text:string,slots:Slots):string|undefined{
 const ids=Array.isArray(slots.appResultIds)?slots.appResultIds.filter((x):x is string=>typeof x==='string'):[];
 const names=Array.isArray(slots.appResultNames)?slots.appResultNames:[];
 const at=ordinal(text);
 if(at!==undefined)return ids[at];
 const found=names.findIndex(x=>typeof x==='string'&&text.toLowerCase().includes(x.toLowerCase()));
 if(found>=0)return ids[found];
 if(/\b(other|another)\b/i.test(text)&&ids.length>1){
  return ids.find(x=>x!==slots.selectedAppId);
 }
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
export function appStorePlan(text:string,slots:Slots):Plan|null{
 const s=text.toLowerCase();
 const known=Array.isArray(slots.appResultIds)&&slots.appResultIds.length>0;
 const compare=/\b(compare|comparison|versus|vs\.?|side by side)\b/.test(s);
 const details=/\b(detail|details|open|about|more|tell me|drill down|look at)\b/.test(s);
 const priceOnly=/\b(only free|free ones|just free|paid ones|only paid)\b/.test(s);
 const explicitSearch=/\b(find|search|browse|list|show|discover)\b.*\bapps?\b/.test(s)||/\bapps?\s+(?:for|about)\b/.test(s);
 const domainMention=/\b(app store|appstore)\b/.test(s);
 if(compare&&known){
  const ids=slots.appResultIds as string[];
  const chosen=choose(text,slots);
  const second=/(?:second|2nd)/i.test(text)?ids[1]:undefined;
  const targets=(chosen&&second&&chosen!==second?[chosen,second]:ids.slice(0,Math.min(ids.length,3))).slice(0,4);
  if(targets.length<2)return null;
  return {version:1,operations:[{id:'comparison',capability:'appstore.compare',args:{ids:targets}}],
   statePatch:{appIntent:'compare'},
   view:view('App Store · side-by-side comparison (MOCK)', 'AppComparisonView',{apps:bind('results.comparison.apps')})};
 }
 if(details&&(known||domainMention)){
  const id=choose(text,slots);
  if(!id)return null;
  return {version:1,operations:[{id:'detail',capability:'appstore.details',args:{id}}],
   statePatch:{appIntent:'detail',selectedAppId:id},
   view:view('App Store · app details (MOCK)','AppDetailView',{app:bind('results.detail.app')})};
 }
 if(explicitSearch||priceOnly&&typeof slots.appQuery==='string'||domainMention&&/\b(find|search)\b/.test(s)){
  const query=priceOnly?String(slots.appQuery):queryFromText(text);
  const price: 'all'|'free'|'paid'=priceOnly?(/\bpaid\b/.test(s)?'paid':'free'):/\b(?:only free|free apps|free ones)\b/.test(s)?'free':'all';
  return {version:1,operations:[{id:'search',capability:'appstore.search',args:{query,price,limit:12}}],
   statePatch:{appIntent:'search',appQuery:query,appPrice:price,selectedAppId:''},
   stateCapture:{appResultIds:'results.search.ids',appResultNames:'results.search.names'},
   view:view('App Store · search results (MOCK)','AppSearchGrid',{query:bind('results.search.query'),apps:bind('results.search.apps')})};
 }
 return null;
}
