import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { assertMemorySafe } from './safety.ts';
import type { MemoryBackend, MemoryFact, MemoryReceipt, RecallResult } from './contracts.ts';
const execFileAsync = promisify(execFile);

export class FixtureBackend implements MemoryBackend {
  private records = new Map<string, MemoryFact>();
  private ids = new Map<string, string>();
  private next = 1;
  async remember(f: MemoryFact): Promise<MemoryReceipt> {
    assertMemorySafe(f);
    const previous = this.ids.get(f.requestId);
    if (previous) return { id: previous, status: 'duplicate' };
    const id = String(this.next++);
    this.records.set(id, f); this.ids.set(f.requestId, id);
    return { id, status: 'inserted' };
  }
  async recall(entity: string): Promise<RecallResult> {
    return { facts: [...this.records].filter(([, v]) => v.entity === entity)
      .map(([id,v]) => ({ id, text: v.text, provenance: v.provenance })).reverse() };
  }
  async forget(id: string, _reason: string): Promise<boolean> { return this.records.delete(id); }
}

/** Uses only the public documented CLI, never mutates the harness or shared brain config. */
export class GBrainCliBackend implements MemoryBackend {
  private binary: string;
  private timeoutMs: number;
  constructor(binary = process.env.GBRAIN_BIN || 'gbrain', timeoutMs = 30_000) {
    this.binary = binary; this.timeoutMs = timeoutMs;
  }
  private async call(args: string[]): Promise<unknown> {
    // No implicit keys / provider access: keep this PoC keyless. Only isolated test data.
    const allowed = ['PATH','HOME','TMPDIR','XDG_CONFIG_HOME','XDG_DATA_HOME','GBRAIN_HOME'];
    const env = Object.fromEntries(allowed.flatMap(k => process.env[k] === undefined ? [] : [[k, process.env[k]!]]));
    let stdout: string;
    try { ({ stdout } = await execFileAsync(this.binary, [...args, '--json'], { env, timeout: this.timeoutMs, maxBuffer: 8*1024*1024 })); }
    catch (e) { throw new Error(`GBRAIN_COMMAND_FAILED: ${(e as Error).message.slice(0, 700)}`); }
    try { return JSON.parse(stdout); }
    catch { throw new Error(`GBRAIN_NON_JSON: ${stdout.slice(0,300)}`); }
  }
  async remember(f: MemoryFact): Promise<MemoryReceipt> {
    assertMemorySafe(f);
    const data = await this.call(['remember', f.text, '--provenance', f.provenance,
      '--entity', f.entity, '--visibility', f.visibility]);
    const obj = unwrap(data);
    if (!hasString(obj, 'id') && typeof obj.id !== 'number') throw new Error('GBRAIN_REMEMBER_SCHEMA_UNEXPECTED');
    if (!['inserted','duplicate','superseded'].includes(String(obj.status))) throw new Error('GBRAIN_REMEMBER_STATUS_UNEXPECTED');
    return { id: String(obj.id), status: obj.status as MemoryReceipt['status'] };
  }
  async recall(entity: string): Promise<RecallResult> {
    const data = unwrap(await this.call(['recall', entity]));
    const facts = Array.isArray(data.facts) ? data.facts : (Array.isArray(data) ? data : undefined);
    if (!facts) throw new Error('GBRAIN_RECALL_SCHEMA_UNEXPECTED');
    return { facts: facts.map((item: unknown) => {
      if (!item || typeof item !== 'object') throw new Error('GBRAIN_FACT_SCHEMA_UNEXPECTED');
      const obj = item as Record<string, unknown>;
      const field = ['fact','text','content','fact_text'].find(k => typeof obj[k] === 'string');
      if (!field) throw new Error('GBRAIN_FACT_TEXT_MISSING');
      return { id: String(obj.fact_id ?? obj.id ?? ''), text: String(obj[field]), provenance: String(obj.provenance ?? '') };
    }), degraded: typeof data.search_degraded === 'string' ? data.search_degraded : undefined };
  }
  async forget(id: string, reason: string): Promise<boolean> {
    const data = unwrap(await this.call(['forget', id, '--reason', reason]));
    if (typeof data.expired !== 'boolean') throw new Error('GBRAIN_FORGET_SCHEMA_UNEXPECTED');
    return data.expired;
  }
}
function unwrap(input: unknown): Record<string, any> {
  if (!input || typeof input !== 'object') throw new Error('GBRAIN_ENVELOPE_UNEXPECTED');
  const obj = input as Record<string, unknown>;
  if (obj.error) throw new Error(`GBRAIN_ERROR: ${String(obj.error)}`);
  // Named CLI produces the verb envelope directly. Retain room for CLI wrappers.
  return ((obj.result && typeof obj.result === 'object') ? obj.result : obj) as Record<string, any>;
}
function hasString(o: Record<string, any>, k: string): boolean { return typeof o[k] === 'string' && !!o[k]; }
