/** Isolated, keyless two-engine Chord RPC switch smoke. Real Oxigraph always;
 * GBrain CLI only when --gbrain is passed (CI sets isolated GBRAIN_HOME).
 */
import { randomUUID } from 'node:crypto';
import { mkdir, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { FixtureBackend, GBrainCliBackend } from './backends.ts';
import { OxigraphMemoryBackend } from './oxigraph.ts';
import { createKnowledgeHost, KnowledgeService, knowledgeFacet } from './chord-knowledge.ts';
import type { MemoryFact } from './contracts.ts';

const args=process.argv.slice(2), real=args.includes('--gbrain');
const index=args.indexOf('--out'), output=index>=0?args[index+1]:undefined;
const graph=new OxigraphMemoryBackend();
const gb=real?new GBrainCliBackend():new FixtureBackend();
const marker=randomUUID(),entity=`incidents/chord-${marker}`;
const fact:MemoryFact={entity,text:'Synthetic verified diagnostic action: inspect refresh pointer',
  provenance:`synthetic-chord-smoke-${marker}`,requestId:marker,origin:'inferred',visibility:'world'};
await Promise.all([graph.remember(fact),gb.remember(fact)]);
const host=createKnowledgeHost();
let result;
try {
  await host.load([knowledgeFacet({oxigraph:graph,gbrain:gb},'oxigraph','v1',
    {allowContentOnlyParityForSyntheticTests:real})]);
  const ref=await host.get(KnowledgeService,{space:'poc'},{principal:'owner'});
  try {
    const before=await ref.recall({entity});
    let strictProvenanceParityRejected = false;
    if (real) {
      try {await ref.switchReadBackend({to:'gbrain',expectedGeneration:0,probeEntities:[entity]});}
      catch(e) {if (/PARITY_MISMATCH/.test(String(e))) strictProvenanceParityRejected=true;else throw e;}
      if (!strictProvenanceParityRejected) throw Error('EXPECTED_GBRAIN_CLI_PROVENANCE_GAP_NOT_OBSERVED');
    }
    const changed=await ref.switchReadBackend({to:'gbrain',expectedGeneration:0,probeEntities:[entity],
      ...(real?{parity:'content-only' as const}:{})});
    const after=await ref.recall({entity});
    if (before.facts.length!==1||after.facts.length!==1||before.facts[0]?.text!==after.facts[0]?.text ||
      before.backend!=='oxigraph'||after.backend!=='gbrain'||changed.generation!==1) throw Error('CHORD_REAL_BACKEND_PARITY_FAILED');
    result={schemaVersion:1,status:'passed',engines:real?'real-oxigraph-wasm-and-real-gbrain-cli':'real-oxigraph-wasm-and-fixture',
      assertions: ['read via first backend','parity gate','switch to second backend','read via second backend'],
      before:{backend:before.backend,generation:before.generation},after:{backend:after.backend,generation:after.generation},
      // Omit unique source marker and exact entity from published reports.
      factParity:true,provenanceParity:!real,strictProvenanceParityRejected,
      proof:changed.proof,contentOnlyModeExplicitlyEnabled:real,
      writeCutoverTested:false,checkpointIntegrationTested:false};
  } finally {ref.release();}
} finally {await host.close();}
const body=JSON.stringify(result,null,2)+'\n';
if(output){const dest=resolve(output);await mkdir(dirname(dest),{recursive:true});await writeFile(dest,body);}
console.log(body);
