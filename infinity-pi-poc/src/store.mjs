import { DatabaseSync } from 'node:sqlite';
import { createHash, randomUUID } from 'node:crypto';
import { EventEmitter } from 'node:events';

export const jobKey = (groupId, id) => createHash('sha256').update(`${groupId}\0${id}`).digest('hex');

/** The SQLite file has exactly one owning gateway process. Synchronous transactions fence state transitions. */
export class JobStore extends EventEmitter {
  constructor(file) {
    super();
    this.db = new DatabaseSync(file);
    this.db.exec(`PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL; PRAGMA busy_timeout=5000;
      CREATE TABLE IF NOT EXISTS jobs (
        job_id TEXT PRIMARY KEY, group_id TEXT NOT NULL, call_id TEXT,
        tool_id TEXT NOT NULL, operation TEXT NOT NULL, args_json TEXT NOT NULL,
        callback_url TEXT NOT NULL, payload_hash TEXT NOT NULL,
        status TEXT NOT NULL DEFAULT 'accepted', attempts INTEGER NOT NULL DEFAULT 0,
        available_at INTEGER NOT NULL DEFAULT 0, lease_owner TEXT, lease_until INTEGER,
        result_text TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
      );
      CREATE TABLE IF NOT EXISTS outbox (
        job_id TEXT PRIMARY KEY REFERENCES jobs(job_id), payload_json TEXT NOT NULL,
        delivered_at INTEGER, attempts INTEGER NOT NULL DEFAULT 0,
        next_at INTEGER NOT NULL DEFAULT 0, lease_owner TEXT, lease_until INTEGER,
        last_error TEXT
      );
      CREATE INDEX IF NOT EXISTS jobs_ready ON jobs(status,available_at,lease_until);
      CREATE INDEX IF NOT EXISTS outbox_ready ON outbox(delivered_at,next_at,lease_until);`);
  }
  transact(fn) {
    this.db.exec('BEGIN IMMEDIATE');
    try { const result = fn(); this.db.exec('COMMIT'); return result; }
    catch(e) { this.db.exec('ROLLBACK'); throw e; }
  }
  get(key) { return this.db.prepare('SELECT * FROM jobs WHERE job_id=?').get(key); }
  list() { return this.db.prepare('SELECT * FROM jobs ORDER BY created_at DESC LIMIT 500').all(); }
  accept(invocation) {
    const key = jobKey(invocation.group_id, invocation.id);
    const hash = createHash('sha256').update(JSON.stringify({
      operation: invocation.operation, arguments: invocation.arguments,
      callback_url: invocation.callback_url, call_id: invocation.call_id ?? null,
    })).digest('hex');
    const time = Date.now();
    const result = this.transact(() => {
      const prior = this.get(key);
      if (prior) {
        if (prior.payload_hash !== hash) return { conflict: true, job: prior };
        return { created: false, job: prior };
      }
      this.db.prepare(`INSERT INTO jobs(job_id,group_id,tool_id,call_id,operation,args_json,callback_url,
        payload_hash,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?)`).run(
        key, invocation.group_id, invocation.id, invocation.call_id ?? null,
        invocation.operation, JSON.stringify(invocation.arguments), invocation.callback_url,
        hash, time, time);
      return { created: true, job: this.get(key) };
    });
    if (result.created) this.emit('changed', result.job);
    return result;
  }
  claimWork({ now = Date.now(), leaseMs = 30_000, maxAttempts = 3 } = {}) {
    const result = this.transact(() => {
      // Exhausted abandoned leases become failures, not permanently stuck jobs.
      const exhausted = this.db.prepare(`SELECT * FROM jobs WHERE attempts >= ?
        AND (status='accepted' OR (status='running' AND lease_until <= ?)) LIMIT 1`).get(maxAttempts, now);
      if (exhausted) {
        this.#finish(exhausted, 'Error: Pi worker retry limit reached', now, false);
        return { exhausted: this.get(exhausted.job_id) };
      }
      const job = this.db.prepare(`SELECT * FROM jobs WHERE attempts < ? AND
        ((status='accepted' AND available_at<=?) OR (status='running' AND lease_until<=?))
        ORDER BY created_at LIMIT 1`).get(maxAttempts, now, now);
      if (!job) return null;
      const owner = randomUUID();
      this.db.prepare(`UPDATE jobs SET status='running', attempts=attempts+1,
        lease_owner=?,lease_until=?,updated_at=? WHERE job_id=?`).run(owner, now + leaseMs, now, job.job_id);
      return { job: this.get(job.job_id), owner };
    });
    if (result?.exhausted) this.emit('changed', result.exhausted);
    if (result?.job) this.emit('changed', result.job);
    return result?.exhausted ? null : result;
  }
  renew(key, owner, leaseMs, now = Date.now()) {
    return this.db.prepare(`UPDATE jobs SET lease_until=? WHERE job_id=? AND status='running'
      AND lease_owner=?`).run(now + leaseMs, key, owner).changes === 1;
  }
  #finish(job, text, now, success = true) {
    const payload = JSON.stringify({ type: 'tool_result', group_id: job.group_id, id: job.tool_id,
      call_id: job.call_id, text });
    this.db.prepare(`UPDATE jobs SET status=?,result_text=?,lease_owner=NULL,
      lease_until=NULL,updated_at=? WHERE job_id=?`).run(success ? 'completed' : 'failed', text, now, job.job_id);
    this.db.prepare(`INSERT OR IGNORE INTO outbox(job_id,payload_json) VALUES(?,?)`).run(job.job_id, payload);
  }
  finish(key, owner, text, now = Date.now()) {
    const result = this.transact(() => {
      const job = this.get(key);
      if (!job || job.status !== 'running' || job.lease_owner !== owner) return false;
      this.#finish(job, text, now);
      return true;
    });
    if (result) this.emit('changed', this.get(key));
    return result;
  }
  retry(key, owner, reason, maxAttempts = 3, now = Date.now()) {
    const changed = this.transact(() => {
      const job = this.get(key);
      if (!job || job.status !== 'running' || job.lease_owner !== owner) return false;
      if (job.attempts >= maxAttempts) this.#finish(job, `Error: ${String(reason).slice(0,200)}`, now, false);
      else this.db.prepare(`UPDATE jobs SET status='accepted',lease_owner=NULL,lease_until=NULL,
        available_at=?,updated_at=? WHERE job_id=?`).run(now + 50 * 2 ** job.attempts, now, key);
      return true;
    });
    if (changed) this.emit('changed', this.get(key));
    return changed;
  }
  cancel(group, id, now = Date.now()) {
    const key = jobKey(group, id);
    const changed = this.transact(() => {
      const job = this.get(key);
      if (!job || job.status === 'completed' || job.status === 'cancelled') return false;
      this.db.prepare(`UPDATE jobs SET status='cancelled',lease_owner=NULL,lease_until=NULL,
        updated_at=? WHERE job_id=?`).run(now, key);
      // RAP cancellation is advisory. No callback required; the runtime already knows the cancellation.
      return true;
    });
    if (changed) this.emit('changed', this.get(key));
    return changed;
  }
  claimCallback({ now = Date.now(), leaseMs = 30_000 } = {}) {
    return this.transact(() => {
      const row = this.db.prepare(`SELECT o.*,j.callback_url FROM outbox o JOIN jobs j USING(job_id)
        WHERE o.delivered_at IS NULL AND o.next_at<=? AND (o.lease_until IS NULL OR o.lease_until<=?)
        ORDER BY o.next_at LIMIT 1`).get(now, now);
      if (!row) return null;
      const owner = randomUUID();
      this.db.prepare(`UPDATE outbox SET lease_owner=?,lease_until=?,attempts=attempts+1
        WHERE job_id=?`).run(owner, now + leaseMs, row.job_id);
      return { ...row, owner };
    });
  }
  callbackOutcome(key, owner, ok, reason = '', now = Date.now()) {
    return this.db.prepare(`UPDATE outbox SET delivered_at=?,lease_owner=NULL,lease_until=NULL,
      last_error=?,next_at=? WHERE job_id=? AND lease_owner=?`).run(
      ok ? now : null, ok ? null : String(reason).slice(0,200), ok ? now : now + Math.min(60_000, 200 * 2 ** Math.min(8, this.callbackAttempts(key))), key, owner).changes === 1;
  }
  callbackAttempts(key) { return this.db.prepare('SELECT attempts FROM outbox WHERE job_id=?').get(key)?.attempts ?? 0; }
  outbox(key) { return this.db.prepare('SELECT * FROM outbox WHERE job_id=?').get(key); }
  close() { this.db.close(); }
}
