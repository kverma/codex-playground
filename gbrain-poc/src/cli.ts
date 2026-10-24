import { randomUUID } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { GBrainCliBackend, FixtureBackend } from './backends.ts';
import { evaluate, makeCases } from './benchmark.ts';

const command=process.argv[2] || 'benchmark';
const backend = process.argv.includes('--gbrain') ? new GBrainCliBackend() : new FixtureBackend();
const outIndex=process.argv.indexOf('--out');
const output=outIndex >=0 ? process.argv[outIndex+1] : undefined;
async function save(content: unknown) {
  const text=JSON.stringify(content,null,2)+'\n';
  if (output) { const p=resolve(output); await mkdir(dirname(p),{recursive:true}); await writeFile(p,text); }
  process.stdout.write(text);
}
if (command === 'benchmark') {
  const id = backend instanceof GBrainCliBackend ? `gbrain-${randomUUID()}` : 'fixture';
  const result = await evaluate(backend,id);
  await save(result);
  if (result.metrics.failed) process.exitCode=1;
} else if(command === 'smoke-gbrain') {
  const g=new GBrainCliBackend(); const suffix=randomUUID(); const entity=`projects/poc-smoke-${suffix}`;
  const phrase=`Synthetic isolated smoke marker ${suffix}`;
  const entry=await g.remember({entity,text:phrase,provenance:'isolated-gh-actions-poc-smoke',visibility:'world',origin:'inferred',requestId:suffix});
  const before=await g.recall(entity);
  if (!before.facts.some(f=>f.text===phrase)) throw Error('SMOKE_RECALL_FAILED');
  await g.forget(entry.id,'cleanup synthetic smoke data');
  const after=await g.recall(entity);
  if (after.facts.some(f=>f.text===phrase)) throw Error('SMOKE_FORGET_FAILED');
  await save({schemaVersion:1,backend:'gbrain-cli',status:'passed',operations:['remember','recall','forget','recall'],uniqueMarkerValidated:true,
    // Marker deliberately not emitted in CI artifacts.
    note:'Native Pi prompt injection / automatic recall not covered'});
} else if(command==='cases') await save(makeCases());
else throw Error(`Unknown command: ${command}`);
