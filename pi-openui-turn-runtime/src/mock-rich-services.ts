/**
 * Independent MOCK MCP services. Each is a real McpServer connected over its own
 * client/transport. Data is synthetic; never presented as Apple's live catalog.
 */
import {McpServer} from '@modelcontextprotocol/server';
import {Client,InMemoryTransport} from '@modelcontextprotocol/client';
import {z} from 'zod/v4';

const seed={
 sketchflow:{id:'sketchflow',rating:4.8,platforms:['iPad','iPhone'],price:'$9.99'},
 pixelcraft:{id:'pixelcraft',rating:4.7,platforms:['iPad','iPhone','Mac'],price:'Free'},
 'focus-harbor':{id:'focus-harbor',rating:4.9,platforms:['iPhone','iPad','Mac'],price:'Free'}
} as const;
const get=(id:string)=>seed[id as keyof typeof seed];
const result=(payload:object)=>({content:[{type:'text' as const,text:JSON.stringify(payload)}],structuredContent:payload});
export const reviewsFallback={items:[],status:'unavailable',source:'mock-reviews'};
export const privacyFallback={report:{collects:'Unavailable',tracking:'Unavailable',notes:'Review privacy information in the actual store'},status:'unavailable',source:'mock-privacy'};
export const pricingFallback={offer:{price:'Unavailable',currency:'USD',purchaseType:'Unknown'},status:'unavailable',source:'mock-pricing'};
export const compatibilityFallback={devices:[],status:'unavailable',source:'mock-compatibility'};
function reviewsServer(){
 const server=new McpServer({name:'mock-review-service',version:'1.0.0'});
 const item=z.object({author:z.string(),rating:z.number(),title:z.string(),body:z.string()});
 server.registerTool('get_reviews',{description:'Synthetic user reviews by app ID',annotations:{readOnlyHint:true},inputSchema:z.object({appId:z.string()}),outputSchema:z.object({items:z.array(item),status:z.string(),source:z.string()})},async({appId})=>{
  const app=get(appId);
  return result({items:app?[{author:'Mock Reviewer',rating:5,title:'Great for my workflow',body:'Fictional review used to test an OpenUI rich detail panel.'},{author:'Sample User',rating:4,title:'Good tools',body:'Another synthetic review, not from the App Store.'}]:[],status:app?'ok':'not_found',source:'mock-reviews'});
 });
 return server;
}
function privacyServer(){
 const server=new McpServer({name:'mock-privacy-service',version:'1.0.0'});
 server.registerTool('get_privacy',{description:'Synthetic app privacy summary',annotations:{readOnlyHint:true},inputSchema:z.object({appId:z.string()}),outputSchema:z.object({report:z.object({collects:z.string(),tracking:z.string(),notes:z.string()}),status:z.string(),source:z.string()})},async({appId})=>result({report:{collects:'Diagnostics (optional)',tracking:'No tracking in the synthetic listing',notes:'Sample privacy information — not a verified privacy label'},status:get(appId)?'ok':'not_found',source:'mock-privacy'}));
 return server;
}
function pricingServer(){
 const server=new McpServer({name:'mock-pricing-service',version:'1.0.0'});
 server.registerTool('recover_pricing_fields',{description:'Retrieve only requested missing pricing fields (synthetic)',annotations:{readOnlyHint:true},inputSchema:z.object({appId:z.string(),storefront:z.string(),fields:z.array(z.enum(['offer.price','offer.currency','offer.purchaseType'])).min(1).max(3)}),outputSchema:z.object({offer:z.object({price:z.string().optional(),currency:z.string().optional(),purchaseType:z.string().optional()})})},async({appId,storefront,fields})=>{
  const app=get(appId);
  const all:{[key:string]:string}={'offer.price':app?.price??'Not found','offer.currency':storefront==='US'?'USD':'Unknown','offer.purchaseType':app?.price==='Free'?'Free':'One-time'};
  const offer:Record<string,string>={};
  for(const field of fields)offer[field.split('.')[1]!]=all[field]!;
  return result({offer});
 });
 server.registerTool('get_pricing',{description:'Synthetic app price by storefront',annotations:{readOnlyHint:true},inputSchema:z.object({appId:z.string(),storefront:z.string().default('US')}),outputSchema:z.object({offer:z.object({price:z.string(),currency:z.string(),purchaseType:z.string()}),status:z.string(),source:z.string()})},async({appId,storefront})=>{
  const app=get(appId);
  return result({offer:{price:app?.price??'Not found',currency:storefront==='US'?'USD':'Unknown',purchaseType:app?.price==='Free'?'Free':'One-time'},status:app?'ok':'not_found',source:'mock-pricing'});
 });
 return server;
}
function compatibilityServer(){
 const server=new McpServer({name:'mock-device-service',version:'1.0.0'});
 server.registerTool('check_devices',{description:'Synthetic device compatibility, dependent on app lookup',annotations:{readOnlyHint:true},inputSchema:z.object({appId:z.string(),devices:z.array(z.string())}),outputSchema:z.object({devices:z.array(z.object({name:z.string(),supported:z.boolean()})),status:z.string(),source:z.string()})},async({appId,devices})=>{
  const app=get(appId);return result({devices:devices.map(name=>({name,supported:!!app?.platforms.includes(name as never)})),status:app?'ok':'not_found',source:'mock-compatibility'});
 });
 return server;
}
export type ServiceName='reviews'|'privacy'|'pricing'|'compatibility';
export async function connectRichMockServices(){
 const factories={reviews:reviewsServer,privacy:privacyServer,pricing:pricingServer,compatibility:compatibilityServer};
 const endpoints={} as Record<ServiceName,{client:Client;server:McpServer}>;
 for(const name of Object.keys(factories) as ServiceName[]){
  const [clientTransport,serverTransport]=InMemoryTransport.createLinkedPair();
  const server=factories[name](),client=new Client({name:'openui-'+name+'-client',version:'1.0.0'});
  await server.connect(serverTransport);await client.connect(clientTransport);
  endpoints[name]={client,server};
 }
 const map={
  'reviews.get':'reviews:get_reviews',
  'privacy.get':'privacy:get_privacy',
  'pricing.get':'pricing:get_pricing',
  'pricing.recover':'pricing:recover_pricing_fields',
  'compatibility.check':'compatibility:check_devices'
 } as const;
 async function invoke(capability:string,args:Record<string,unknown>){
  const mapping=map[capability as keyof typeof map];
  if(!mapping)throw Error('No mock tool registered: '+capability);
  const [service,tool]=mapping.split(':') as [ServiceName,string];
  const output=await endpoints[service].client.callTool({name:tool,arguments:args});
  if(output.isError||!output.structuredContent)throw Error('Mock MCP '+tool+' failed');
  return output.structuredContent;
 }
 return {endpoints,invoke,close:async()=>{for(const endpoint of Object.values(endpoints)){await endpoint.client.close();await endpoint.server.close();}}};
}
