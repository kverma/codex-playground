/** Apples-to-apples graph vs GBrain: both consume the same synthetic cases.
 * GBrain's CLI spawn overhead makes the reported latency non-comparable to
 * in-process Oxigraph WASM; do not promote on speed alone.
 */
import {randomUUID} from 'node:crypto';
import {mkdir,writeFile} from 'node:fs/promises';
import {dirname,resolve} from 'node:path';
import {GBrainCliBackend} from './backends.ts';
import {OxigraphMemoryBackend} from './oxigraph.ts';
import {evaluate} from './benchmark.ts';
import {loadPiNormalizer,runTransferProbe} from './continual.ts';

const args=process.argv.slice(2), live=args.includes('--gbrain');
const pos=args.indexOf('--out'),out=pos>=0?args[pos+1]:undefined;
const id='paired-'+randomUUID();
const norm=await loadPiNormalizer();
async function runOne(name:'oxigraph'|'gbrain') {
  const backend=name==='oxigraph'?new OxigraphMemoryBackend():new GBrainCliBackend();
  const recall=await evaluate(backend,id); // Same exact 72-case corpus.
  const transfer=await runTransferProbe(norm,backend); // Same 18/42 split and evaluator.
  return {name,recall,transfer};
}
const graph=await runOne('oxigraph');
const gb=live?await runOne('gbrain'):undefined;
const equal=(a:unknown,b:unknown)=>JSON.stringify(a)===JSON.stringify(b);
const parity=gb?{
  sameTraining:graph.transfer.split.trainingSha256===gb.transfer.split.trainingSha256,
  sameHeldout:graph.transfer.split.heldoutSha256===gb.transfer.split.heldoutSha256,
  sameRecallCases:equal(graph.recall.cases.map(x=>({id:x.id,expected:x.expected})),gb.recall.cases.map(x=>({id:x.id,expected:x.expected}))),
  sameProbeDecisions:equal(graph.transfer.cases.map(x=>({id:x.id,result:x.withMemory})),gb.transfer.cases.map(x=>({id:x.id,result:x.withMemory})))
}:undefined;
const report={schemaVersion:1,status:'synthetic_only',method:'same-input entity recall and held-out deterministic procedure selection; not semantic RAG or LLM agent task success',
  datasets:{recallCases:72,trainingEpisodes:18,heldoutProbes:42},
  warnings:['GBrain CLI subprocess p95 is not comparable to in-process Oxigraph WASM','Oxigraph JavaScript WASM store is in-memory; ledger replay is PoC only','No official FrontierMemory benchmark, live Pi Durable, production permissions or LLM reasoning tested'],
  graph,gb,parity};
if(out){const file=resolve(out);await mkdir(dirname(file),{recursive:true});await writeFile(file,JSON.stringify(report,null,2)+'\n');}
console.log(JSON.stringify({status:report.status,graph:{recallPassed:graph.recall.metrics.passed,transferPassed:graph.transfer.metrics.memoryPassed},
  gbrain:gb?{recallPassed:gb.recall.metrics.passed,transferPassed:gb.transfer.metrics.memoryPassed}:null,parity},null,2));
if(graph.recall.metrics.passed!==72||graph.transfer.metrics.memoryPassed!==42||
  (gb&&(gb.recall.metrics.passed!==72||gb.transfer.metrics.memoryPassed!==42||Object.values(parity!).some(v=>!v))))process.exitCode=1;
