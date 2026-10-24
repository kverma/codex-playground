/** Chord-facing, read-only knowledge service. An authoritative ingestion pipeline
 * owns writes; do not pretend an RPC selector is a transactional DB cutover.
 * Uses real Chord typed contracts, principal variants and retained state cells.
 */
import { Type } from 'typebox';
import { createHost, forbidden, hostNamed, invalid, local, method, remote } from '@earendil-works/chord';
import type { MemoryBackend } from './contracts.ts';

export type Engine = 'gbrain' | 'oxigraph';
export type Principal = 'owner' | 'viewer';
export type Engines = Record<Engine, MemoryBackend>;

const EngineSchema = Type.Union([Type.Literal('gbrain'), Type.Literal('oxigraph')]);
const Routing = Type.Object({ readBackend: EngineSchema, generation: Type.Integer({ minimum: 0 }), proof: Type.Union([Type.Literal('initial'), Type.Literal('provenance'), Type.Literal('content-only')]) });
const Facts = Type.Array(Type.Object({ id: Type.String(), text: Type.String(), provenance: Type.String() }));
const RecallArgs = Type.Object({ entity: Type.String({ minLength: 1, maxLength: 130 }) });
const RecallResult = Type.Object({ facts: Facts, backend: EngineSchema, generation: Type.Integer({ minimum: 0 }) });
const SwitchArgs = Type.Object({
  to: EngineSchema,
  expectedGeneration: Type.Integer({ minimum: 0 }),
  probeEntities: Type.Array(Type.String({ minLength: 1, maxLength: 130 }), { minItems: 1, maxItems: 64 }),
  parity: Type.Optional(Type.Union([Type.Literal('provenance'), Type.Literal('content-only')])),
});

/** An internal common object: shared by all principals for a given space. */
const Router = local('poc.knowledge-router', {
  version: 1,
  input: Type.Object({ space: Type.String() }),
  members: {
    routing: Routing,
    recall: method(RecallArgs, RecallResult),
    switchReadBackend: method(SwitchArgs, Routing),
    implementation: method(undefined, Type.String()),
  },
});

/** A remote contract with a read-only variant. Do not give untrusted callers
 * an operator principal; the host's authentication boundary must enforce it.
 */
export const KnowledgeService = remote('poc.knowledge-service', {
  version: 1,
  input: Type.Object({ space: Type.String() }),
  host: hostNamed('knowledge-server'),
  variants: {
    reader: { state: { routing: Routing }, methods: { recall: method(RecallArgs, RecallResult) } },
    operator: { state: { routing: Routing }, methods: {
      recall: method(RecallArgs, RecallResult),
      switchReadBackend: method(SwitchArgs, Routing),
      implementation: method(undefined, Type.String()),
    } },
  },
});

const ENTITY = /^(?:projects|services|incidents|runbooks)\/[a-z0-9][a-z0-9/_-]{0,120}$/;
function checkEntity(entity: string) {
  if (!ENTITY.test(entity)) throw invalid('args', 'ENTITY_SCOPE_INVALID');
}
function factFingerprint(facts: Array<{ text: string; provenance: string }>, mode: 'provenance'|'content-only'): string {
  return JSON.stringify(facts.map(f => mode === 'provenance' ? [f.text, f.provenance] : [f.text])
    .sort((a, b) => JSON.stringify(a).localeCompare(JSON.stringify(b))));
}

/** Create a named, reloadable Chord facet. `engines` live OUTSIDE the Chord
 * state cell: Chord is not their persistence or transactional coordinator.
 */
export function knowledgeFacet(engines: Engines, initially: Engine = 'oxigraph', versionTag = 'v1',
  options: { allowContentOnlyParityForSyntheticTests?: boolean } = {}) {
  return { name: 'knowledge-service', setup: (api: any) => {
    api.serve(Router, ({ space }: { space: string }, _principal: unknown, self: any) => {
      if (!/^[a-z][a-z0-9-]{0,40}$/.test(space)) throw invalid('args', 'SPACE_INVALID');
      const routing = self.state('routing', Routing, { readBackend: initially, generation: 0, proof: 'initial' });
      const recall = async ({ entity }: {entity: string}) => {
        checkEntity(entity);
        // Pin selection BEFORE I/O; a concurrent switch doesn't retarget this call.
        const { readBackend, generation } = routing.value;
        const response = await engines[readBackend].recall(entity);
        return { facts: response.facts.map(f => ({ id: f.id, text: f.text, provenance: f.provenance })), backend: readBackend, generation };
      };
      const switchReadBackend = async ({ to, expectedGeneration, probeEntities, parity = 'provenance' }: {
        to: Engine; expectedGeneration: number; probeEntities: string[]; parity?: 'provenance'|'content-only';
      }, ctx: any) => {
        const previous = routing.value;
        if (expectedGeneration !== previous.generation) throw invalid('args', 'STALE_ROUTING_GENERATION');
        if (to === previous.readBackend) return previous;
        if (parity === 'content-only' && !options.allowContentOnlyParityForSyntheticTests)
          throw invalid('args', 'CONTENT_ONLY_PARITY_DISABLED');
        for (const entity of probeEntities) {
          checkEntity(entity);
          const [oldData, candidate] = await Promise.all([
            engines[previous.readBackend].recall(entity), engines[to].recall(entity),
          ]);
          if (oldData.degraded || candidate.degraded) throw invalid('args', 'DEGRADED_RETRIEVAL');
          if (factFingerprint(oldData.facts, parity) !== factFingerprint(candidate.facts, parity))
            throw invalid('args', `PARITY_MISMATCH_FOR_${entity}`);
        }
        if (routing.value.generation !== expectedGeneration) throw invalid('args', 'STALE_ROUTING_GENERATION');
        const next = { readBackend: to, generation: expectedGeneration + 1, proof: parity };
        routing.replace(ctx, next);
        return next;
      };
      return { routing, recall, switchReadBackend, implementation: () => versionTag };
    });
    api.serve(KnowledgeService, ({ space }: { space: string }, principal: Principal, self: any) => {
      const router = self.get(Router, { space });
      if (principal === 'viewer') {
        return { variant: 'reader', routing: router.routing,
          recall: (args: { entity: string }, ctx: unknown) => router.recall(args, ctx) };
      }
      if (principal !== 'owner') throw forbidden('Unrecognized principal');
      return { variant: 'operator', routing: router.routing,
        recall: (args: { entity: string }, ctx: unknown) => router.recall(args, ctx),
        switchReadBackend: (args: any, ctx: any) => router.switchReadBackend(args, ctx),
        implementation: (ctx: unknown) => router.implementation(ctx),
      };
    });
  } };
}

/** The connection is trusted local-only in this PoC: a real deployment must
 * issue principal assertions only AFTER enterprise SSO/mTLS verification.
 */
export function createKnowledgeHost() {
  return createHost<Principal>({ name: 'knowledge-server', self: 'owner', principalKey: p => p });
}
