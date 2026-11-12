import { spawnSync } from 'node:child_process';
import { readFileSync, writeFileSync, mkdirSync, mkdtempSync, cpSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { createHash } from 'node:crypto';

const root = resolve(import.meta.dirname, '..');
const out = join(root, 'reports'), generated = join(root, 'generated');
mkdirSync(out, {recursive:true});
mkdirSync(generated, {recursive:true});

function exec(program, args, cwd) {
  const r = spawnSync(program, args, {cwd, encoding:'utf8', maxBuffer:4_000_000});
  if (r.error) throw Error(program+' unavailable: '+r.error.message);
  const log=(r.stdout||'')+(r.stderr||'');
  if (r.status !== 0) throw Error(program+' '+args.join(' ')+' failed ('+r.status+'):\n'+log);
  return log;
}
function verify(file, label) {
  const dir = mkdtempSync(join(tmpdir(),'spin-'+label+'-'));
  const source=join(root,'models',file);
  cpSync(source,join(dir,'model.pml'));
  exec('spin',['-a','model.pml'],dir);
  exec('cc',['-O2','-o','pan','pan.c'],dir);
  const check=exec('./pan',['-m100000'],dir);
  writeFileSync(join(out,label+'-verification.txt'),check);
  const m=check.match(/errors:\s*(\d+)/);
  if (!m) throw Error('Missing SPIN verification stats for '+label);
  const errors=Number(m[1]);
  const trail=errors ? exec('spin',['-t','-p','-T','model.pml'],dir) : '';
  if (trail) {
    writeFileSync(join(out,label+'-guided-trail.txt'),trail);
    cpSync(join(dir,'model.pml.trail'),join(out,label+'.trail'));
  }
  return {errors,check,trail,source};
}
const version=exec('spin',['-V'],root).trim();
const buggy=verify('buggy.pml','buggy');
const fixed=verify('fixed.pml','fixed');
if (buggy.errors !== 1 || fixed.errors !== 0) {
  throw Error('Unexpected verification result: buggy='+buggy.errors+', fixed='+fixed.errors);
}
if (!/assertion violated/.test(buggy.check)) throw Error('Buggy model has no safety violation');
if (!/assertion violated/.test(buggy.trail)) throw Error('Guided replay has no assertion violation');
const actions=[...buggy.trail.matchAll(/TRACE\|(preflight|commit|reject|skip)\|(\d+)/g)]
  .map(m=>({op:m[1],revision:Number(m[2])}));
const commits=actions.filter(a=>a.op==='commit').map(a=>a.revision);
if (!commits.some((v,i)=>commits.slice(i+1).some(later=>later<v))) {
  throw Error('No stale commit sequence in actual SPIN trail: '+JSON.stringify(actions));
}
const trace={
  schemaVersion:1,
  source:'spin-guided-error-trail',
  invariant:'published-revision-never-decreases',
  modelSha256:createHash('sha256').update(readFileSync(buggy.source)).digest('hex'),
  spinVersion:version,
  actions,
};
writeFileSync(join(generated,'spin-counterexample.json'),JSON.stringify(trace,null,2)+'\n');
const lines=[
  "// GENERATED from an actual SPIN error trail; do not hand edit.",
  "import test from 'node:test';",
  "import assert from 'node:assert/strict';",
  "import { startService } from '../src/service.ts';",
  "import { replayAgainstHttp, type Trace } from '../src/replay.ts';",
  "const trace = "+JSON.stringify(trace)+" as Trace;",
  "test('SPIN-derived counterexample detects buggy HTTP implementation', async () => {",
  "  const app = await startService('buggy');",
  "  try { await assert.rejects(replayAgainstHttp(trace, app.url), /monotonicity violation/); }",
  "  finally { await app.close(); }",
  "});",
  "test('SPIN-derived counterexample passes corrected HTTP implementation', async () => {",
  "  const app = await startService('fixed');",
  "  try { await replayAgainstHttp(trace, app.url); }",
  "  finally { await app.close(); }",
  "});",
];
writeFileSync(join(generated,'spin-counterexample.test.ts'),lines.join('\n')+'\n');
console.log('SPIN: '+version);
console.log('Results: buggy='+buggy.errors+', fixed='+fixed.errors);
console.log('Trail: '+actions.map(a=>a.op+'('+a.revision+')').join(' -> '));
