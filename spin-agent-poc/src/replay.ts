import assert from 'node:assert/strict';
export type Action = { op: 'preflight'|'commit'|'reject'|'skip'; revision: number; };
export type Trace = {
  schemaVersion: 1; source: string;
  invariant: 'published-revision-never-decreases';
  actions: Action[];
};
export async function replayAgainstHttp(trace: Trace, url: string) {
  assert.equal(trace.schemaVersion,1);
  assert.equal(trace.invariant,'published-revision-never-decreases');
  assert.ok(trace.actions.length > 0);
  let maxPublished = 0;
  const states: number[] = [];
  for (const [i,action] of trace.actions.entries()) {
    const endpoint = action.op === 'reject' ? 'preflight' : action.op === 'skip' ? 'commit' : action.op;
    const response = await fetch(url+'/'+endpoint, {
      method:'POST', headers:{'content-type':'application/json'},
      body:JSON.stringify({revision:action.revision})
    });
    assert.equal(response.status,200);
    const result = await response.json() as { accepted?:boolean; applied?:boolean };
    if (action.op === 'preflight' || action.op === 'reject')
      assert.equal(result.accepted, action.op === 'preflight', 'model-to-SUT preflight divergence');
    if (action.op === 'skip')
      assert.equal(result.applied,false,'model-to-SUT commit divergence');
    const state = await (await fetch(url+'/state')).json() as {published:number};
    maxPublished = Math.max(maxPublished,state.published);
    states.push(state.published);
    assert.ok(state.published >= maxPublished,
      'monotonicity violation: step='+(i+1)+' '+action.op+'('+action.revision+
      '), published='+state.published+', highest-observed='+maxPublished);
  }
  return states;
}
