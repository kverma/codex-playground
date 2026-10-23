/** FrontierMemory-inspired *offline synthetic* transfer probe, NOT FrontierMemory leaderboard scoring. */
import { createHash } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import type { MemoryBackend, MemoryFact } from './contracts.ts';
import { FixtureBackend } from './backends.ts';
import { reviewCandidate } from './safety.ts';

export type Procedure = { signal: string; action: string; evidenceIds: string[] };
export type Episode = { id: string; session: string; resource: string; signal: string;
  action: string; verifier: 'PASS' | 'FAIL'; trustedVerifier: boolean; transcript: string };
export type Probe = { id: string; resource: string; signals: string[]; narrative: string; expected: string | null;
  category: 'transfer' | 'unknown' | 'ambiguous' };
export type ProbeResult = { id: string; category: Probe['category']; expected: string | null;
  baseline: string | null; withMemory: string | null; oracle: string | null;
  baselinePassed: boolean; memoryPassed: boolean; oraclePassed: boolean };
export const RULES = [
  { signal: 'replica-quorum', action: 'compare-coordinator-and-replica-consistency' },
  { signal: 'partition-replay', action: 'inspect-consumer-offset-and-idempotency-key' },
  { signal: 'ownership-epoch', action: 'verify-lease-fence-before-retry' },
  { signal: 'cohort-price', action: 'compare-original-enrollment-price-snapshot' },
  { signal: 'cache-publish', action: 'trace-current-payload-to-cache-ingestion' },
  { signal: 'credential-expiry', action: 'verify-token-issuer-audience-and-expiry' },
] as const;
const HEADER = { type: 'session', version: 3, id: 'generated', timestamp: '2026-01-01T00:00:00.000Z', cwd: '/synthetic' };
const j = (x: unknown) => JSON.stringify(x);
const hash = (s: string) => createHash('sha256').update(s).digest('hex');

/** Native Pi SessionManager *shape*: training has different resource names than evaluation. */
export function makeTrainingEpisodes(): Episode[] {
  const result: Episode[] = [];
  for (const [i, rule] of RULES.entries()) {
    for (let n = 0; n < 3; n++) {
      const id = `train-${i}-${n}`, session = `pi-synthetic-${id}`;
      const resource = `train-svc-${i}-${n}`;
      const pass = n !== 2;
      const action = pass ? rule.action : `incorrect-action-${i}`;
      const ts = `2026-01-${String(1+i).padStart(2,'0')}T0${n}:00:00.000Z`;
      const callId = `tool-${id}`;
      const rows = [
        { ...HEADER, id: session, timestamp: ts },
        { type: 'message', id: `${id}-user`, timestamp: ts, message: { role:'user', content:[{type:'text',text:`Investigate ${resource}; observed signal ${rule.signal}.`}] } },
        { type: 'message', id: `${id}-call`, timestamp: ts, message: { role:'assistant', model:'fixture-model', content:[{type:'toolCall',id:callId,name:'verify_remediation', arguments:{signal:rule.signal, action, resource}}] } },
        { type: 'message', id: `${id}-out`, timestamp: ts, message: { role:'toolResult', toolCallId:callId, content:[{type:'text',text:`independent fixture verifier: ${pass?'PASS':'FAIL'}`}], isError:!pass } },
      ];
      result.push({ id, session, resource, signal:rule.signal, action,
        verifier:pass?'PASS':'FAIL', trustedVerifier:true, transcript:rows.map(j).join('\n')+'\n' });
    }
  }
  return result;
}

/** Explicitly disjoint, held-out services and differently worded incident descriptions. */
export function makeHeldoutProbes(): Probe[] {
  const cases: Probe[] = [];
  const phrases = ['after a regional failover','during a staggered rollout','on a weekend deployment','after controller restart','when the service scaled out'];
  for (const [i, rule] of RULES.entries()) for (let v = 0; v < 5; v++) {
    cases.push({id:`holdout-${i}-${v}`,resource:`eval-svc-${i}-${v}`,signals:[rule.signal],
      narrative:`An unrelated service ${phrases[v]} reports ${rule.signal} symptoms. Choose the diagnostic procedure, not a fix.`,
      expected:rule.action,category:'transfer'});
  }
  for (let k = 0; k < 6; k++) cases.push({id:`holdout-unknown-${k}`,resource:`eval-novel-${k}`,
    signals:[`novel-signal-${k}`],narrative:'Insufficient history for this incident; do not guess.',expected:null,category:'unknown'});
  for (let k = 0; k < 6; k++) cases.push({id:`holdout-ambiguous-${k}`,resource:`eval-conflict-${k}`,
    signals:[RULES[k].signal,RULES[(k+1)%RULES.length].signal],
    narrative:'Two unrelated root-cause indicators; avoid choosing a single remediation.',expected:null,category:'ambiguous'});
  return cases;
}

type Normalized = { records: Array<Record<string, unknown>>; diagnostics: unknown[] };
type Normalizer = (input:{source:'pi';transcript:string})=>Normalized;
/** Real @letta-ai/trajectory is loaded on demand: fixture-only tests remain dependency free. */
export async function loadPiNormalizer(): Promise<Normalizer> {
  const {normalizeTranscript} = await import('@letta-ai/trajectory');
  return normalizeTranscript as unknown as Normalizer;
}

export function extractVerifiedProcedures(episodes: Episode[], normalize: Normalizer): Procedure[] {
  const examples = new Map<string,{actions:Map<string,Set<string>>;failed:Set<string>}>();
  for (const episode of episodes) {
    if (!episode.trustedVerifier || !/^train-\d+-\d+$/.test(episode.id)) continue;
    const {records,diagnostics} = normalize({source:'pi',transcript:episode.transcript});
    // Do not learn from malformed/incomplete traces or prose about success.
    if (diagnostics.length || !Array.isArray(records) || !records.length) continue;
    const calls = new Map<string,Record<string,string>>();
    for (const rec of records) {
      if (rec.role !== 'assistant' || !Array.isArray(rec.tool_calls)) continue;
      for (const tool of rec.tool_calls as Array<Record<string,unknown>>) {
        if (tool.name !== 'verify_remediation' || typeof tool.args !== 'string' || typeof tool.id !== 'string') continue;
        try { const parsed = JSON.parse(tool.args);
          if (typeof parsed.signal === 'string' && typeof parsed.action === 'string' && typeof parsed.resource === 'string') calls.set(tool.id,{signal:parsed.signal,action:parsed.action,resource:parsed.resource});
        } catch { /* malformed claims cannot become evidence */ }
      }
    }
    for (const rec of records) {
      if (rec.role !== 'tool' || typeof rec.tool_call_id !== 'string') continue;
      const call = calls.get(rec.tool_call_id); if (!call || call.resource !== episode.resource || call.signal !== episode.signal || call.action !== episode.action) continue;
      const authoritativePass = episode.verifier === 'PASS';
      const receiptPass = rec.ok === true && /\bPASS\b/.test(String(rec.content)) && !/\bFAIL\b/.test(String(rec.content));
      const receiptFail = rec.ok === false || /\bFAIL\b/.test(String(rec.content));
      const state = examples.get(episode.signal) ?? {actions:new Map(),failed:new Set<string>()};
      examples.set(episode.signal,state);
      if (authoritativePass && receiptPass) {
        const ids = state.actions.get(call.action) ?? new Set<string>();
        ids.add(episode.id);state.actions.set(call.action,ids);
      } else if (!authoritativePass && receiptFail) state.failed.add(call.action);
    }
  }
  const accepted: Procedure[] = [];
  for (const [signal, state] of examples) {
    const choices = [...state.actions].filter(([action,ids])=>ids.size>=2 && !state.failed.has(action));
    // Fail closed on contradictory successful actions, not just last-write-wins.
    if (choices.length !== 1) continue;
    const [action,ids] = choices[0];
    accepted.push({signal,action,evidenceIds:[...ids].sort()});
  }
  return accepted.sort((a,b)=>a.signal.localeCompare(b.signal));
}

/** The independent reviewer label is a deterministic *simulator*, not a real reviewer. */
export async function loadApprovedProcedures(backend:MemoryBackend, procedures:Procedure[], training:Episode[]) {
  let accepted=0,rejected=0;
  for (const procedure of procedures) {
    const supports=procedure.evidenceIds.map(id=>training.find(e=>e.id===id)).filter((e):e is Episode=>!!e);
    const proposal={id:`proposal-${procedure.signal}`,proposer:'fixture-learner',entity:`runbooks/${procedure.signal}`,
      lesson:`When signal ${procedure.signal} is verified, use diagnostic action ${procedure.action}.`,
      evidence:supports.map(e=>({uri:`synthetic-trace://${e.id}`,excerpt:`verified ${e.action} ${e.verifier}`,observedAt:'2026-01-10T00:00:00.000Z',trusted:e.trustedVerifier})),
      regression:{caseId:`preflight-${procedure.signal}`,passed:supports.length>=2 && supports.every(e=>e.verifier==='PASS'),checkedAt:'2026-01-11T00:00:00.000Z'}};
    const gate=reviewCandidate(proposal,'fixture-independent-gate');
    if (!gate.accepted){rejected++;continue;}
    const fact:MemoryFact={entity:proposal.entity,text:procedure.action,provenance:procedure.evidenceIds.map(x=>`synthetic:${x}`).join(' '),
      visibility:'world',origin:'inferred',requestId:proposal.id};
    await backend.remember(fact);accepted++;
  }
  return {accepted,rejected};
}

const BASELINE = new Map<string,string>([
  ['replica-quorum','compare-coordinator-and-replica-consistency'],
  ['credential-expiry','verify-token-issuer-audience-and-expiry'],
]);
/** Does not receive expected answers. Only published signs and verified memory. */
export async function decide(probe:Omit<Probe,'expected'>, backend:MemoryBackend|null):Promise<string|null> {
  if (probe.signals.length !== 1) return null;
  const signal=probe.signals[0];
  if (!/^[a-z][a-z0-9-]{1,60}$/.test(signal)) return null;
  if (!backend) return BASELINE.get(signal)??null;
  const fact=(await backend.recall(`runbooks/${signal}`)).facts;
  if (fact.length===0) return BASELINE.get(signal)??null;
  // Divergent stored procedures are not trustworthy.
  const actions=new Set(fact.map(x=>x.text));
  return actions.size===1 ? [...actions][0] : null;
}

export type TransferReport={schemaVersion:1;method:string;trainEpisodes:number;heldoutProbes:number;
  split:{disjointResources:boolean;trainingSha256:string;heldoutSha256:string};
  promotion:{accepted:number;rejected:number};metrics:{baselinePassRate:number;memoryPassRate:number;oraclePassRate:number;
  improvementPercentagePoints:number;baselinePassed:number;memoryPassed:number;oraclePassed:number; transferPassed:number;unsafeUnknownOrAmbiguous:number};cases:ProbeResult[]};
export async function runTransferProbe(normalize:Normalizer, backend:MemoryBackend=new FixtureBackend()):Promise<TransferReport> {
  const training=makeTrainingEpisodes(),heldout=makeHeldoutProbes();
  const trained=new Set(training.map(x=>x.resource));
  const disjointResources=heldout.every(x=>!trained.has(x.resource)) && new Set(heldout.map(x=>x.id)).size===heldout.length;
  if (!disjointResources) throw Error('DATASET_LEAKAGE_RESOURCE_OVERLAP');
  const procedures=extractVerifiedProcedures(training,normalize);
  const promotion=await loadApprovedProcedures(backend,procedures,training);
  const results:ProbeResult[]=[];
  for (const task of heldout) {
    const input={id:task.id,resource:task.resource,signals:task.signals,narrative:task.narrative,category:task.category};
    const baseline=await decide(input,null),withMemory=await decide(input,backend);
    const oracle=task.signals.length===1 ? (RULES.find(x=>x.signal===task.signals[0])?.action??null) : null;
    results.push({id:task.id,category:task.category,expected:task.expected,baseline,withMemory,oracle,
      baselinePassed:baseline===task.expected,memoryPassed:withMemory===task.expected,oraclePassed:oracle===task.expected});
  }
  const total=results.length, n=(k:'baselinePassed'|'memoryPassed'|'oraclePassed')=>results.filter(x=>x[k]).length;
  const baselinePassed=n('baselinePassed'),memoryPassed=n('memoryPassed'),oraclePassed=n('oraclePassed');
  return {schemaVersion:1,method:'deterministic offline held-out policy-transfer control (NOT LLM task success / official FrontierMemory)',
    trainEpisodes:training.length,heldoutProbes:total,
    split:{disjointResources,trainingSha256:hash(training.map(x=>x.transcript).join('\n')),heldoutSha256:hash(j(heldout))},
    promotion,metrics:{baselinePassRate:baselinePassed/total,memoryPassRate:memoryPassed/total,oraclePassRate:oraclePassed/total,
      improvementPercentagePoints:+((memoryPassed-baselinePassed)*100/total).toFixed(2),baselinePassed,memoryPassed,oraclePassed,
      transferPassed:results.filter(x=>x.category==='transfer'&&x.memoryPassed).length,
      unsafeUnknownOrAmbiguous:results.filter(x=>x.category!=='transfer'&&x.withMemory!==null).length},cases:results};
}

/** Portable FrontierMemory-format *example only*. Does not access official benchmark data. */
export async function exportMemoryFolder(procedures:Procedure[], folder:string) {
  const lines=['# MEMORY.md','', 'Synthetic validated diagnostic procedures; no raw tool transcripts.',
    'Do not execute these actions automatically; verify incident preconditions and obtain approval.', ''];
  for (const p of procedures) lines.push(`- **${p.signal}**: ${p.action}. Provenance: ${p.evidenceIds.join(', ')}.`);
  const contents=lines.join('\n')+'\n';
  if (Buffer.byteLength(contents,'utf8')>8*1024*1024) throw Error('MEMORY_BUDGET_EXCEEDED');
  await mkdir(folder,{recursive:true});await writeFile(join(folder,'MEMORY.md'),contents,{flag:'w'});
  return {bytes:Buffer.byteLength(contents,'utf8'),sha256:hash(contents)};
}
