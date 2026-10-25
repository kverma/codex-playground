/**
 * Synthetic Apple App Store-like catalog. Not affiliated with Apple;
 * none of these prices, reviews, apps, or screenshots are live.
 * All tool calls flow over real MCP transport.
 */
import { McpServer } from '@modelcontextprotocol/server';
import { Client, InMemoryTransport } from '@modelcontextprotocol/client';
import { z } from 'zod/v4';

export const appSummarySchema=z.object({
 id:z.string(),name:z.string(),developer:z.string(),subtitle:z.string(),
 category:z.string(),icon:z.string(),color:z.string(),price:z.string(),
 rating:z.number(),ratingCount:z.number(),platforms:z.array(z.string())
});
export const appDetailsSchema=appSummarySchema.extend({
 description:z.string(),features:z.array(z.string()),whatsNew:z.string(),
 ageRating:z.string(),size:z.string(),version:z.string(),
 privacy:z.array(z.string()),screenshots:z.array(z.object({caption:z.string(),color:z.string(),symbol:z.string()}))
});
export type AppSummary=z.infer<typeof appSummarySchema>;
export type AppDetails=z.infer<typeof appDetailsSchema>;

const data:AppDetails[]=[
 {id:'sketchflow',name:'SketchFlow Studio',developer:'Northstar Creative',subtitle:'Drawing, painting and visual ideas',category:'Graphics & Design',icon:'🎨',color:'#694ee9',price:'$9.99',rating:4.8,ratingCount:24180,platforms:['iPad','iPhone'],description:'Create expressive artwork using layers, precision brushes, and a distraction-free canvas.',features:['Layer-based illustration','120 customizable brushes','Apple Pencil gestures','Export PSD and PNG'],whatsNew:'Fresh watercolor brushes and a streamlined layer inspector.',ageRating:'4+',size:'248 MB',version:'3.2.1',privacy:['Identifiers: not collected','Diagnostics: optional'],screenshots:[{caption:'Your creative canvas',color:'#7052e7',symbol:'✦'},{caption:'Brush library',color:'#f07193',symbol:'◉'},{caption:'Layer inspector',color:'#376bc0',symbol:'▤'}]},
 {id:'pixelcraft',name:'PixelCraft Pro',developer:'Mosaic Labs',subtitle:'Photo editing meets illustration',category:'Photo & Video',icon:'📸',color:'#176db4',price:'Free',rating:4.7,ratingCount:11240,platforms:['iPad','iPhone','Mac'],description:'Turn photos into designs with powerful color adjustments, precise masks, and creative overlays.',features:['Non-destructive color editing','AI-free local editing','Layers and clipping masks','RAW photo adjustments'],whatsNew:'Added curated presets for natural color grading.',ageRating:'4+',size:'189 MB',version:'5.4.0',privacy:['Usage data: not linked to you'],screenshots:[{caption:'Edit every detail',color:'#125f9c',symbol:'◌'},{caption:'Before and after',color:'#1896aa',symbol:'◑'},{caption:'Natural color presets',color:'#8757c0',symbol:'◎'}]},
 {id:'focus-harbor',name:'Focus Harbor',developer:'Seaside Software',subtitle:'Plan your day. Protect your focus.',category:'Productivity',icon:'⏱️',color:'#079c91',price:'Free',rating:4.9,ratingCount:18300,platforms:['iPhone','iPad','Mac'],description:'A quiet place to organize projects, plan focused work sessions, and review your progress.',features:['Focus timer','Projects and tags','Calendar synchronization','Weekly insights'],whatsNew:'Improved cross-device focus session sync.',ageRating:'4+',size:'76 MB',version:'2.9.3',privacy:['Usage data: optional analytics'],screenshots:[{caption:'Today at a glance',color:'#0d9e91',symbol:'◴'},{caption:'Focused sessions',color:'#26b99e',symbol:'◉'},{caption:'Projects overview',color:'#196c8a',symbol:'▦'}]},
 {id:'atlas-notes',name:'Atlas Notes',developer:'Monograph Software',subtitle:'Ideas that stay connected',category:'Productivity',icon:'🗂️',color:'#d97636',price:'$4.99',rating:4.6,ratingCount:8900,platforms:['iPhone','iPad','Mac'],description:'Take notes, connect related ideas, and organize your knowledge with backlinks and offline-first storage.',features:['Linked notebooks','Offline full-text search','Markdown and backlinks','Secure export'],whatsNew:'New graph view and faster note indexing.',ageRating:'4+',size:'109 MB',version:'4.0.2',privacy:['Data stays on device by default'],screenshots:[{caption:'Connected ideas',color:'#e0904d',symbol:'✳'},{caption:'Your knowledge graph',color:'#bc5b34',symbol:'⬡'},{caption:'Fast search',color:'#8c56a8',symbol:'⌕'}]},
 {id:'trail-compass',name:'Trail Compass',developer:'Evergreen Maps',subtitle:'Explore trails and weekend escapes',category:'Travel',icon:'🥾',color:'#328458',price:'Free',rating:4.8,ratingCount:14760,platforms:['iPhone','Apple Watch'],description:'Discover scenic hikes, save offline maps, and share trip plans with friends.',features:['Offline trail maps','Elevation profiles','Favorites and collections','Apple Watch navigation'],whatsNew:'New coastal trail collections.',ageRating:'4+',size:'134 MB',version:'6.1.0',privacy:['Location: used for navigation'],screenshots:[{caption:'Discover trails',color:'#398d5c',symbol:'△'},{caption:'Elevation profile',color:'#a0a34a',symbol:'⌁'},{caption:'Offline trail details',color:'#266b75',symbol:'✥'}]},
 {id:'moonlit',name:'Moonlit Rest',developer:'Calm Harbor',subtitle:'Sleep sounds and mindful evenings',category:'Health & Fitness',icon:'🌙',color:'#605baf',price:'Free',rating:4.8,ratingCount:32010,platforms:['iPhone','iPad','Apple Watch'],description:'Wind down with calming soundscapes, guided breathing, and simple bedtime rituals.',features:['Nature soundscapes','Breathing exercises','Sleep timers','Offline listening'],whatsNew:'New soft rainfall and ocean wave mixes.',ageRating:'4+',size:'84 MB',version:'3.8.0',privacy:['No third-party advertising'],screenshots:[{caption:'Evening wind-down',color:'#4d57a9',symbol:'☾'},{caption:'Soundscapes',color:'#3d77a2',symbol:'♬'},{caption:'Breathing guide',color:'#6d60b3',symbol:'◌'}]}
];
export const apps=Object.freeze(data.map(app=>Object.freeze({...app})));
const summary=(app:AppDetails):AppSummary=>{
 const {id,name,developer,subtitle,category,icon,color,price,rating,ratingCount,platforms}=app;
 return {id,name,developer,subtitle,category,icon,color,price,rating,ratingCount,platforms};
};
const queryTerms=(s:string)=>s.toLowerCase().trim().split(/\s+/).filter(Boolean);
export function searchApps(query:string,price:'all'|'free'|'paid'='all',limit=12){
 const terms=queryTerms(query);
 const matches=data.filter(app=>{
  if(price==='free'&&app.price!=='Free'||price==='paid'&&app.price==='Free')return false;
  const body=[app.name,app.subtitle,app.description,app.developer,app.category,...app.features].join(' ').toLowerCase();
  return terms.every(t=>body.includes(t)||t==='app'||t==='apps'||t==='for');
 }).slice(0,limit);
 return {query,price,apps:matches.map(summary),ids:matches.map(a=>a.id),names:matches.map(a=>a.name),total:matches.length,mock:true,source:'mock-app-store'};
}
export function getApp(id:string){
 const app=data.find(a=>a.id===id||a.name.toLowerCase()===id.toLowerCase());
 return {app:app??null,mock:true,source:'mock-app-store'};
}
export function compareApps(ids:string[]){
 const found=ids.slice(0,4).map(id=>data.find(a=>a.id===id)).filter((a):a is AppDetails=>Boolean(a));
 return {apps:found.map(summary),ids:found.map(a=>a.id),mock:true,source:'mock-app-store'};
}
const searchOutput=z.object({query:z.string(),price:z.enum(['all','free','paid']),apps:z.array(appSummarySchema),ids:z.array(z.string()),names:z.array(z.string()),total:z.number(),mock:z.literal(true),source:z.literal('mock-app-store')});
const detailOutput=z.object({app:appDetailsSchema.nullable(),mock:z.literal(true),source:z.literal('mock-app-store')});
const compareOutput=z.object({apps:z.array(appSummarySchema),ids:z.array(z.string()),mock:z.literal(true),source:z.literal('mock-app-store')});
const result=(output:object)=>({content:[{type:'text' as const,text:JSON.stringify(output)}],structuredContent:output});
export function createMockAppStoreServer(){
 const server=new McpServer({name:'mock-apple-app-store',version:'1.0.0'});
 server.registerTool('search_apps',{description:'Search mock App Store applications (synthetic sample data only)',inputSchema:z.object({query:z.string().max(120),price:z.enum(['all','free','paid']).optional(),limit:z.number().int().min(1).max(12).optional()}),outputSchema:searchOutput,annotations:{readOnlyHint:true}},
  async({query,price,limit})=>result(searchApps(query,price??'all',limit??12)));
 server.registerTool('get_app',{description:'Get full details for a mock App Store app by app id',inputSchema:z.object({id:z.string().max(80)}),outputSchema:detailOutput,annotations:{readOnlyHint:true}},
  async({id})=>result(getApp(id)));
 server.registerTool('compare_apps',{description:'Compare two to four mock App Store app summaries',inputSchema:z.object({ids:z.array(z.string()).min(2).max(4)}),outputSchema:compareOutput,annotations:{readOnlyHint:true}},
  async({ids})=>result(compareApps(ids)));
 return server;
}
export async function connectMockAppStore(){
 const [clientTransport,serverTransport]=InMemoryTransport.createLinkedPair();
 const server=createMockAppStoreServer();
 const client=new Client({name:'pi-openui-mock-appstore-client',version:'1.0.0'});
 await server.connect(serverTransport);
 await client.connect(clientTransport);
 return {client,server,close:async()=>{await client.close();await server.close();}};
}
export async function callAppStoreTool(client:Client,name:'search_apps'|'get_app'|'compare_apps',args:Record<string,unknown>){
 const value=await client.callTool({name,arguments:args});
 if(value.isError||!value.structuredContent)throw Error('Mock MCP error: '+JSON.stringify(value.content));
 return value.structuredContent;
}
