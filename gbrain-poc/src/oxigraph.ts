/** An actual Oxigraph RDF/WASM adapter. In-memory engine: not production persistent.
 * All knowledge assertions are immutable RDF resources, withdrawal appends tombstones.
 * Graph relations are explicitly evidenced and never automatically inferred as true.
 */
import { createRequire } from 'node:module';
import { createHash } from 'node:crypto';
import { assertMemorySafe } from './safety.ts';
import type { MemoryBackend, MemoryFact, MemoryReceipt, RecallResult } from './contracts.ts';

const require = createRequire(import.meta.url);
// oxigraph@0.5.11 publishes its Node-compatible CommonJS/WASM entrypoint.
const rdf = require('oxigraph') as {
  Store: new () => RdfStore;
  namedNode: (uri:string) => Term;
  literal: (s:string) => Term;
  quad: (s:Term,p:Term,o:Term) => Quad;
};
type Term = { value:string; termType:string };
type Quad = { subject:Term;predicate:Term;object:Term };
type RdfStore = { add(q:Quad):void; match(s?:Term|null,p?:Term|null,o?:Term|null):Iterable<Quad>;query(query:string):Iterable<Map<string,Term>> };
const NS='urn:pi-memory:2026:';
const term=(name:string)=>rdf.namedNode(NS+name);
const sub=(id:string)=>term('assertion/'+id);
const entityNode=(entity:string)=>term('entity/'+encodeURIComponent(entity));
const textHash=(v:string)=>createHash('sha256').update(v).digest('hex');
const properties=['id','entity','text','provenance','visibility','origin','requestId','knownAt','validFrom','validUntil','withdrawnAt','withdrawReason'] as const;
type Field = typeof properties[number];
export type AsOf = { knowledgeAt:string; validAt:string };
export type Validity = {validFrom?:string;validUntil?:string};
export type LinkType = 'depends-on'|'verified-by'|'supersedes'|'caused-by'|'mitigated-by';
export type GraphLink = {from:string;type:LinkType;to:string;provenance:string};
export type LedgerEvent =
  | {kind:'assert';id:string;fact:MemoryFact;knownAt:string;validFrom:string;validUntil?:string}
  | {kind:'withdraw';id:string;reason:string;knownAt:string}
  | {kind:'link';id:string;knownAt:string;link:GraphLink};
const linkTypes=new Set<LinkType>(['depends-on','verified-by','supersedes','caused-by','mitigated-by']);

function stamp(raw:string):string {
  const ms=Date.parse(raw);
  if (!Number.isFinite(ms)) throw new Error('INVALID_TEMPORAL_TIMESTAMP');
  return new Date(ms).toISOString();
}
/** Times are caller-specified in tests, and monotonic on a single instance in normal use. */
export class OxigraphMemoryBackend implements MemoryBackend {
  private readonly store:RdfStore = new rdf.Store();
  private readonly events:LedgerEvent[]=[];
  private lastEventMs=Number.NEGATIVE_INFINITY;
  constructor(private readonly clock:()=>string=()=>new Date().toISOString()) {}

  private nextTime():string {
    const observed=Date.parse(stamp(this.clock()));
    const next=Math.max(observed,this.lastEventMs+1);
    this.lastEventMs=next;
    return new Date(next).toISOString();
  }
  private set(s:Term,field:string,value:string) { this.store.add(rdf.quad(s,term(field),rdf.literal(value))); }
  private get(s:Term,field:Field):string|undefined {
    return [...this.store.match(s,term(field),null)][0]?.object.value;
  }
  private existing(id:string):boolean {return this.get(sub(id),'id')===id;}
  private assert(id:string,fact:MemoryFact,knownAt:string,validFrom:string,validUntil?:string) {
    if(this.existing(id)) throw Error('ASSERTION_ID_COLLISION');
    const s=sub(id);
    for(const [k,v] of Object.entries({...fact,id,knownAt,validFrom,validUntil})) {
      if(v !== undefined) this.set(s,k,String(v));
    }
  }
  async remember(fact:MemoryFact):Promise<MemoryReceipt> {return this.rememberTemporal(fact,{});}
  async rememberTemporal(fact:MemoryFact,validity:Validity):Promise<MemoryReceipt> {
    assertMemorySafe(fact);
    const id=textHash(`${fact.entity}\u0000${fact.requestId}`);
    if(this.existing(id)) {
      const s=sub(id);
      // Duplicated request with a different payload is a conflict, never silently accepted.
      if(this.get(s,'text')!==fact.text || this.get(s,'provenance')!==fact.provenance) throw Error('REQUEST_ID_PAYLOAD_CONFLICT');
      return {id,status:'duplicate'};
    }
    const knownAt=this.nextTime(),validFrom=stamp(validity.validFrom??knownAt);
    const validUntil=validity.validUntil===undefined?undefined:stamp(validity.validUntil);
    if(validUntil && validUntil<=validFrom)throw Error('INVALID_VALID_INTERVAL');
    this.assert(id,fact,knownAt,validFrom,validUntil);
    this.events.push({kind:'assert',id,fact:{...fact},knownAt,validFrom,validUntil});
    return {id,status:'inserted'};
  }
  async forget(id:string,reason:string):Promise<boolean> {
    if(!reason.trim()||reason.length>500)throw Error('WITHDRAW_REASON_REQUIRED');
    if(!this.existing(id)||this.get(sub(id),'withdrawnAt'))return false;
    const knownAt=this.nextTime();
    this.set(sub(id),'withdrawnAt',knownAt);this.set(sub(id),'withdrawReason',reason);
    this.events.push({kind:'withdraw',id,reason,knownAt});
    return true;
  }
  async recall(entity:string):Promise<RecallResult> {
    const now=new Date(Math.max(Date.now(),this.lastEventMs)).toISOString();
    return this.recallAsOf(entity,{knowledgeAt:now,validAt:now});
  }
  async recallAsOf(entity:string,asOf:AsOf):Promise<RecallResult> {
    // Exact RDF term matching, not interpolated SPARQL, protects against query injection.
    const when=stamp(asOf.knowledgeAt),valid=stamp(asOf.validAt);
    const facts:RecallResult['facts']=[];
    for(const match of this.store.match(null,term('entity'),rdf.literal(entity))) {
      const s=match.subject;
      const knownAt=this.get(s,'knownAt'),from=this.get(s,'validFrom');
      const until=this.get(s,'validUntil'),withdrawn=this.get(s,'withdrawnAt');
      if(!knownAt||!from||knownAt>when||from>valid||(until!==undefined&&valid>=until)||(withdrawn!==undefined&&withdrawn<=when))continue;
      const id=this.get(s,'id'),text=this.get(s,'text'),provenance=this.get(s,'provenance');
      if(id&&text&&provenance)facts.push({id,text,provenance});
    }
    // Stable order and deterministic evidence rather than unspecified graph iteration order.
    facts.sort((a,b)=>a.id.localeCompare(b.id));
    return {facts};
  }
  async addLink(link:GraphLink):Promise<void> {
    if(!linkTypes.has(link.type)) throw Error('RELATIONSHIP_TYPE_INVALID');
    for(const entity of [link.from,link.to])assertMemorySafe({entity,text:'typed relationship',provenance:link.provenance,
      visibility:'world',origin:'inferred',requestId:entity});
    const id=textHash([link.from,link.type,link.to,link.provenance].join('\u0000'));
    const knownAt=this.nextTime();
    // A named RDF predicate represents the actual directed graph relationship.
    this.store.add(rdf.quad(entityNode(link.from),term('relation/'+link.type),entityNode(link.to)));
    this.set(term('link/'+id),'provenance',link.provenance);
    this.set(term('link/'+id),'knownAt',knownAt);
    this.events.push({kind:'link',id,knownAt,link:{...link}});
  }
  /** Graph traversal is intentionally separate from fact recall. */
  linksFrom(from:string):GraphLink[] {
    const results:GraphLink[]=[];
    for(const q of this.store.match(entityNode(from),null,null)) {
      if(!q.predicate.value.startsWith(NS+'relation/'))continue;
      const type=q.predicate.value.slice((NS+'relation/').length) as LinkType;
      const to=decodeURIComponent(q.object.value.slice((NS+'entity/').length));
      const matching=this.events.filter((e):e is Extract<LedgerEvent,{kind:'link'}> =>e.kind==='link'&&e.link.from===from&&e.link.type===type&&e.link.to===to);
      for(const e of matching)results.push({...e.link});
    }
    return results.sort((a,b)=>(a.type+a.to).localeCompare(b.type+b.to));
  }
  /** Explicit bounded multi-hop traversal, not semantic inference. */
  walk(from:string,maxDepth=2):Array<{depth:number;link:GraphLink}> {
    if(!Number.isInteger(maxDepth)||maxDepth<0||maxDepth>5)throw Error('GRAPH_DEPTH_INVALID');
    const seen=new Set([from]),frontier=[from],results:Array<{depth:number;link:GraphLink}>=[];
    for(let depth=1;depth<=maxDepth;depth++) {
      const next:string[]=[];
      for(const node of frontier.splice(0)) for(const link of this.linksFrom(node)) {
        if(seen.has(link.to))continue;
        seen.add(link.to);results.push({depth,link});next.push(link.to);
      }
      frontier.push(...next);
      if(frontier.length===0)break;
    }
    return results;
  }
  /** A real SPARQL query executed by the WASM engine; callers must use trusted, fixed queries. */
  queryTrusted(query:string):Array<Record<string,string>> {
    const result:Array<Record<string,string>>=[];
    for(const row of this.store.query(query)) {
      const record:Record<string,string>={};
      for(const [k,v] of row)record[k]=v.value;
      result.push(record);
    }
    return result;
  }
  /** Portable ledger events; store in append-only durable storage in a production version. */
  exportLedger():LedgerEvent[] {return structuredClone(this.events);}
  static fromLedger(events:LedgerEvent[],clock?:()=>string):OxigraphMemoryBackend {
    const backend=new OxigraphMemoryBackend(clock);
    let last=Number.NEGATIVE_INFINITY;
    for(const e of events) {
      const t=Date.parse(stamp(e.knownAt));
      if(t<last)throw Error('LEDGER_EVENT_ORDER_INVALID');
      last=t;
      if(e.kind==='assert') {
        assertMemorySafe(e.fact);
        if(textHash(`${e.fact.entity}\u0000${e.fact.requestId}`)!==e.id)throw Error('LEDGER_ID_MISMATCH');
        backend.assert(e.id,e.fact,stamp(e.knownAt),stamp(e.validFrom),e.validUntil===undefined?undefined:stamp(e.validUntil));
      } else if(e.kind==='withdraw') {
        if(!backend.existing(e.id)||backend.get(sub(e.id),'withdrawnAt'))throw Error('LEDGER_INVALID_WITHDRAWAL');
        backend.set(sub(e.id),'withdrawnAt',stamp(e.knownAt));
        backend.set(sub(e.id),'withdrawReason',e.reason);
      } else if(e.kind==='link') {
        if(!linkTypes.has(e.link.type))throw Error('LEDGER_INVALID_RELATIONSHIP');
        backend.store.add(rdf.quad(entityNode(e.link.from),term('relation/'+e.link.type),entityNode(e.link.to)));
        backend.set(term('link/'+e.id),'provenance',e.link.provenance);
        backend.set(term('link/'+e.id),'knownAt',stamp(e.knownAt));
      } else throw Error('LEDGER_UNKNOWN_EVENT');
      backend.events.push(structuredClone(e));
    }
    backend.lastEventMs=last;
    return backend;
  }
}
