import fs from 'node:fs';
import assert from 'node:assert/strict';
const dir = new URL('./results/', import.meta.url);
const {model,rows} = JSON.parse(fs.readFileSync(new URL('predictions.json',dir)));
const splits = Object.fromEntries(['train','validation','test'].map(s=>[s,rows.filter(r=>r.split===s)]));
for (const [s,items] of Object.entries(splits)) {
  assert.ok(items.length>=8,s+' needs >=8 samples');
  assert.ok(items.some(r=>r.label===0)&&items.some(r=>r.label===1),s+' needs both labels');
}
const score=(items,t)=>{
 let tp=0,tn=0,fp=0,fn=0,brier=0;
 for(const r of items){
   assert.ok(Number.isFinite(r.probability)&&r.probability>=0&&r.probability<=1);
   const p=Number(r.probability>=t), y=r.label;
   if(p&&y)tp++;else if(p&&!y)fp++;else if(!p&&y)fn++;else tn++;
   brier+=(r.probability-y)**2;
 }
 const precision=tp/(tp+fp||1),recall=tp/(tp+fn||1);
 return {threshold:t,n:items.length,tp,tn,fp,fn,precision,recall,
   accuracy:(tp+tn)/items.length,f1:2*precision*recall/(precision+recall||1),
   brier:brier/items.length};
};
const thresholds=Array.from({length:99},(_,i)=>(i+1)/100);
const ranked=thresholds.map(t=>({t,train:score(splits.train,t)}))
  .sort((a,b)=>b.train.f1-a.train.f1||Math.abs(a.t-.5)-Math.abs(b.t-.5));
const candidate=ranked[0].t;
const baselineVal=score(splits.validation,.5), candidateVal=score(splits.validation,candidate);
const accepted=candidateVal.f1>baselineVal.f1;
const deployed=accepted?candidate:.5;
const report={model,method:"Train F1 grid search; validation-only promotion; untouched test",
  samples:Object.fromEntries(Object.entries(splits).map(([k,v])=>[k,v.length])),
  baseline:{train:score(splits.train,.5),validation:baselineVal,test:score(splits.test,.5)},
  candidate:{threshold:candidate,train:score(splits.train,candidate),validation:candidateVal},
  accepted,deployedThreshold:deployed,finalTest:score(splits.test,deployed),
  caution:"Small hand-written benchmark; no evidence of generalization or Jev/Pi integration"};
fs.writeFileSync(new URL('report.json',dir),JSON.stringify(report,null,2));
fs.writeFileSync(new URL('report.md',dir),
 '# OSS classifier optimizer verification\n\nModel: '+model+
 '\n\nBaseline test F1: '+report.baseline.test.f1+
 '\n\nFinal test F1: '+report.finalTest.f1+
 '\n\nCandidate threshold: '+candidate+
 '\n\nPromoted: '+accepted+
 '\n\n**Caution:** This tests a conventional open-source binary classifier, not native Pi or Jev.\n');
console.log(JSON.stringify(report,null,2));
assert.ok(report.finalTest.f1>=0&&report.finalTest.f1<=1);
