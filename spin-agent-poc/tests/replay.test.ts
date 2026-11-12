import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { startService } from '../src/service.ts';
import { replayAgainstHttp, type Trace } from '../src/replay.ts';
const trace = JSON.parse(await readFile(new URL('../fixtures/manual-seed.json',import.meta.url),'utf8')) as Trace;
test('seed detects stale commit in buggy HTTP implementation',async()=>{
  const app = await startService('buggy');
  try {await assert.rejects(replayAgainstHttp(trace,app.url),/monotonicity violation/);}
  finally {await app.close();}
});
test('seed succeeds against fixed HTTP implementation',async()=>{
  const app=await startService('fixed');
  try {assert.deepEqual(await replayAgainstHttp(trace,app.url),[0,0,2,2]);}
  finally {await app.close();}
});
