import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {decodeToolPayload,normalizeToolResponse,normalizeWithExtractor,mergeRecoveredFields} from '../src/tool-normalizer.ts';
const rules=JSON.parse(await readFile(new URL('../corpus/normalization.json',import.meta.url),'utf8')).capabilities;
const privacy=rules['privacy.get'],pricing=rules['pricing.get'],reviews=rules['reviews.get'];
test('partial privacy data preserves known collection info and marks unknown tracking, never no tracking',()=>{
 const res=normalizeToolResponse({report:{collects:'Optional diagnostics',notes:'Test'},status:'ok',source:'provider'},privacy);
 assert.equal((res.value.report as any).collects,'Optional diagnostics');
 assert.equal((res.value.report as any).tracking,'Unavailable');
 assert.equal(res.quality.fields['report.collects'].status,'present');
 assert.equal(res.quality.fields['report.tracking'].status,'missing');
 assert.equal(res.quality.status,'partial');assert.equal(res.value.status,'partial');
});
test('missing price never becomes Free; zero reviews differs from missing reviews',()=>{
 const price=normalizeToolResponse({offer:{currency:'USD',purchaseType:'One-time'}},pricing);
 assert.equal((price.value.offer as any).price,'Unavailable');
 assert.deepEqual(price.missingRecoverable,['offer.price']);
 assert.equal(price.quality.fields['offer.price'].status,'missing');
 const none=normalizeToolResponse({items:[]},reviews);
 assert.equal(none.quality.status,'ok');
 assert.equal(none.quality.fields.items.status,'present');
 const missing=normalizeToolResponse({},reviews);
 assert.equal(missing.quality.status,'partial');
 assert.equal(missing.quality.fields.items.status,'missing');
});
test('deterministic extraction parses fenced JSON or allowlisted key/value pairs, not arbitrary invented prose',()=>{
 const a=normalizeToolResponse('```json\n{"offer":{"price":"$9.99","currency":"USD","purchaseType":"One-time"}}\n```',pricing);
 assert.equal((a.value.offer as any).price,'$9.99');
 const b=normalizeToolResponse('price: $9.99\ncurrency: USD\npurchaseType: One-time',pricing);
 assert.equal((b.value.offer as any).price,'$9.99');
 assert.equal(b.quality.fields['offer.price'].status,'extracted');
 const c=normalizeToolResponse('The product is probably free but no official price was supplied.',pricing);
 assert.equal((c.value.offer as any).price,'Unavailable');
 assert.equal(c.quality.fields['offer.price'].status,'missing');
});
test('only declared unambiguous numeric coercions, never free-form guesses',()=>{
 const numeric={source:'mock-ratings',fields:{rating:{type:'number' as const,coerce:'numeric-string' as const,missing:'unavailable' as const,placeholder:-1}}};
 const one=normalizeToolResponse({rating:'4.8'},numeric);assert.equal(one.value.rating,4.8);assert.equal(one.quality.fields.rating.status,'coerced');
 const two=normalizeToolResponse({rating:'4.8 stars'},numeric);assert.equal(two.value.rating,-1);assert.equal(two.quality.fields.rating.status,'invalid');
});
test('evidence-constrained extractor can fill a field but rejects unsupported answers',async()=>{
 const source='SketchFlow costs $9.99 in this fictional catalog';
 const good=await normalizeWithExtractor(source,pricing,async()=>[{path:'offer.price',value:'$9.99',evidence:'costs $9.99'}]);
 assert.equal((good.value.offer as any).price,'$9.99');
 assert.equal(good.quality.fields['offer.price'].status,'extracted');
 await assert.rejects(()=>normalizeWithExtractor(source,pricing,async()=>[{path:'offer.price',value:'Free',evidence:'costs $9.99'}]),/Extracted value absent/);
 await assert.rejects(()=>normalizeWithExtractor(source,pricing,async()=>[{path:'offer.price',value:'$9.99',evidence:'not in source'}]),/Unverifiable/);
});
test('field recovery can merge ONLY allowlisted missing fields and updates provenance',()=>{
 const partial={offer:{currency:'USD',purchaseType:'One-time'}};
 const patch={offer:{price:'$9.99',purchaseType:'Injected other change'}};
 const merged=mergeRecoveredFields(partial,patch,['offer.price'],pricing);
 const normalized=normalizeToolResponse(merged.data,pricing,{recovered:merged.recovered});
 assert.equal((normalized.value.offer as any).price,'$9.99');
 assert.equal((normalized.value.offer as any).purchaseType,'One-time');
 assert.equal(normalized.quality.fields['offer.price'].status,'recovered');
 assert.throws(()=>mergeRecoveredFields(partial,patch,['offer.purchaseType'],pricing),/Unapproved/);
});
test('failed optional MCP data is unavailable rather than falsely present',()=>{
 const fallback={report:{collects:'Unavailable',tracking:'Unavailable',notes:'No source'}};
 const normalized=normalizeToolResponse(fallback,privacy,{unavailable:true});
 assert.equal(normalized.value.status,'unavailable');
 assert.equal(normalized.quality.fields['report.tracking'].status,'unavailable');
});
test('required fields cannot silently fill with placeholders',()=>{
 const rule={source:'mock-app',fields:{'app.id':{type:'string' as const,required:true}}};
 assert.throws(()=>normalizeToolResponse({app:{name:'Unverified'}},rule),/Essential field app.id missing/);
});
test('ignore prototype pollution and reject unsafe aliases and oversized text payloads',()=>{
 assert.throws(()=>normalizeToolResponse({}, {source:'x',fields:{'__proto__.polluted':{type:'string',missing:'unavailable',placeholder:'x'}}}),/Invalid normalization/);
 assert.throws(()=>decodeToolPayload('x'.repeat(50001),pricing),/safe limit/);
});
