import test from 'node:test';
import assert from 'node:assert/strict';
import {loadCatalog,validate,Engine,Store,type Catalog} from '../src/runtime.ts';
import {parseIntent,appStoreIntent,ciIntent} from '../src/intent-parsers.ts';
import {validateRegistry,discover,resolveView} from '../src/registry-resolver.ts';
import {connectMockAppStore,callAppStoreTool} from '../src/mock-app-store.ts';
const ctx={threadId:'registry',turnId:'turn1',permissions:['appstore:read','ci:read']};
test('every recipe is compatible with exported OpenUI corpus and capabilities',async()=>{
 const catalog=await loadCatalog();assert.equal(catalog.views?.length,4);
 validateRegistry(catalog);
 for(const recipe of catalog.views??[])assert.ok(recipe.view.component in catalog.components);
});
test('semantic intents contain NO component names or tool identifiers',()=>{
 const app=appStoreIntent('Find drawing apps',{});
 assert.deepEqual(app&&{domain:app.domain,action:app.action,entity:app.entity,cardinality:app.cardinality},{domain:'appstore',action:'search',entity:'app',cardinality:'many'});
 assert.equal(JSON.stringify(app).includes('AppSearchGrid'),false);
 assert.equal(JSON.stringify(app).includes('appstore.search'),false);
 const ci=ciIntent('Show failed Atlas builds',{});
 assert.equal(JSON.stringify(ci).includes('MetricCard'),false);
 assert.equal(JSON.stringify(ci).includes('demo.failedBuilds'),false);
});
test('resolver chooses compatible UI and capability across domains, without domain-specific routing',async()=>{
 const catalog=await loadCatalog();
 const intent=appStoreIntent('Find drawing apps',{})!;
 const {plan,recipeId}=resolveView(intent,catalog,ctx);
 assert.equal(recipeId,'appstore.search.grid.v1');assert.equal(plan.operations[0]!.capability,'appstore.search');
 assert.equal((plan.view.props.children as any[])[1].component,'AppSearchGrid');
 validate(plan,catalog,ctx);
 const ci=resolveView(ciIntent('Show failed Atlas builds',{})!,catalog,ctx);
 assert.equal(ci.plan.operations[0]!.capability,'demo.failedBuilds');
 assert.equal((ci.plan.view.props.children as any[])[1].component,'MetricCard');
});
test('changing only JSON registry changes the selected component',async()=>{
 const catalog=await loadCatalog(),intent=appStoreIntent('Find drawing apps',{})!;
 const alternative=structuredClone(catalog.views![0]!);
 alternative.id='appstore.search.comparison-variant.v1';alternative.priority=20;
 (alternative.view.props.children as any[])[1]={component:'AppComparisonView',props:{apps:{$bind:'results.search.apps'}}};
 const modified:Catalog={...catalog,views:[...catalog.views!,alternative]};
 validateRegistry(modified);
 const {plan,recipeId}=resolveView(intent,modified,ctx);
 assert.equal(recipeId,alternative.id);
 assert.match(JSON.stringify(plan.view),/AppComparisonView/);
 assert.doesNotMatch(JSON.stringify(plan.view),/AppSearchGrid/);
 const mcp=await connectMockAppStore();
 try{
  const engine=new Engine(modified,new Store(),async(cap,args)=>callAppStoreTool(mcp.client,'search_apps',args));
  const reply=await engine.handle('Find drawing apps',ctx);
  assert.match(reply.openui,/AppComparisonView/);
  assert.match(reply.openui,/SketchFlow Studio/);
 }finally{await mcp.close();}
});
test('equal-ranked choices are rejected instead of nondeterministic selection',async()=>{
 const catalog=await loadCatalog();
 const duplicate=structuredClone(catalog.views![0]!);
 duplicate.id='appstore.search.other.v1';
 const modified={...catalog,views:[...catalog.views!,duplicate]};
 validateRegistry(modified);
 assert.throws(()=>discover(appStoreIntent('Find drawing apps',{})!,modified,ctx),/Ambiguous UI selection/);
});
test('presentation tags break tie deterministically, not LLM free-text component names',async()=>{
 const catalog=await loadCatalog();
 const other=structuredClone(catalog.views![0]!);other.id='appstore.search.table.v1';other.tags=['table'];
 const modified={...catalog,views:[...catalog.views!,other]};
 const intent={...appStoreIntent('Find drawing apps',{})!,presentation:'table'};
 assert.equal(discover(intent,modified,ctx).id,other.id);
});
test('reject unknown nested components, unsafe slots, and unknown source capabilities',async()=>{
 const catalog=await loadCatalog();
 const nested=structuredClone(catalog.views![0]!);
 (nested.view.props.children as any[])[1]={component:'UninstalledWidget',props:{}};
 assert.throws(()=>validateRegistry({...catalog,views:[nested]}),/unknown component/);
 const invalid=structuredClone(catalog.views![0]!);
 invalid.operations[0]!.args.query={$param:'missing'};
 assert.throws(()=>resolveView(appStoreIntent('Find drawing apps',{})!,{...catalog,views:[invalid]},ctx),/required input slots/);
 const badCapability=structuredClone(catalog.views![0]!);
 badCapability.operations[0]!.capability='mcp.invoked-dangerously';
 assert.throws(()=>validateRegistry({...catalog,views:[badCapability]}),/unknown capability/);
});
test('fail closed when user lacks the capability permissions',async()=>{
 const catalog=await loadCatalog();
 assert.throws(()=>resolveView(appStoreIntent('Find drawing apps',{})!,catalog,{...ctx,permissions:[]}),/No authorized UI recipe/);
});
test('unrecognized semantic intent cannot silently choose an unrelated component',async()=>{
 const catalog=await loadCatalog();
 assert.throws(()=>resolveView({domain:'unknown',action:'predict',entity:'everything',cardinality:'many',params:{}},catalog,ctx),/No compatible registered UI recipe/);
});
