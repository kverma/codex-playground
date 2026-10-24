import { createServer } from 'node:http';
import { timingSafeEqual } from 'node:crypto';
import { jobKey } from './store.mjs';

export const manifest = (endpoint) => ({ name:'pi-durable-worker',
  description:'Delegates deterministic CI failure analysis to the Pi worker', endpoint,
  tools:[{ name:'analyze_ci_failures', description:'Cluster CI failure messages into timeout/network/other.',
    inputSchema:{type:'object',properties:{failures:{type:'array',items:{type:'string'}}},required:['failures']},
    annotations:{longRunning:true} }] });

const equalToken = (provided, expected) => {
  if (!expected) return true;
  if (typeof provided !== 'string') return false;
  const a=Buffer.from(provided), b=Buffer.from(`Bearer ${expected}`);
  return a.length===b.length && timingSafeEqual(a,b);
};
function parseInvocation(value, allowedOrigins) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Invalid request');
  for (const name of ['id','group_id','callback_url','operation']) {
    if (typeof value[name] !== 'string' || value[name].length < 1 || value[name].length > 2048) throw new Error(`Invalid ${name}`);
  }
  if (value.call_id !== undefined && value.call_id !== null && (typeof value.call_id !== 'string' || value.call_id.length>2048)) throw new Error('Invalid call_id');
  if (value.operation !== 'analyze_ci_failures') throw new Error('Unknown operation');
  const url = new URL(value.callback_url);
  if (!['http:','https:'].includes(url.protocol) || url.username || url.password ||
      !allowedOrigins.has(url.origin)) throw new Error('Callback origin not permitted');
  if (!value.arguments || !Array.isArray(value.arguments.failures) || value.arguments.failures.length>1000 ||
      !value.arguments.failures.every(x=>typeof x==='string' && x.length<=1000)) throw new Error('Invalid arguments');
  return value;
}
async function body(req) {
  if (!req.headers['content-type']?.startsWith('application/json')) throw new Error('JSON required');
  let s=''; for await(const b of req) { s+=b; if(s.length>128*1024) throw new Error('Request too large'); }
  return JSON.parse(s);
}
function reply(res,status,data) { res.writeHead(status,{'content-type':'application/json','cache-control':'no-store'}); res.end(JSON.stringify(data)); }
export function makeGateway({store, callbackOrigins = new Set(['http://127.0.0.1:4002']), token=undefined, endpoint}) {
  const server=createServer(async(req,res)=>{
    try {
      const url = new URL(req.url,'http://localhost');
      if(req.method==='GET' && url.pathname==='/.well-known/rap-toolset') return reply(res,200,manifest(endpoint));
      if(!equalToken(req.headers.authorization,token)) return reply(res,401,{error:'Unauthorized'});
      if(req.method==='POST' && url.pathname==='/cancel_tool_call') {
        const value = await body(req);
        if(typeof value.thread_id==='string' && typeof value.tool_call_id==='string') store.cancel(value.thread_id,value.tool_call_id);
        // RAP requires HTTP 200 even for unknown calls.
        return reply(res,200,{ok:true});
      }
      if(req.method==='POST' && url.pathname==='/') {
        const invocation=parseInvocation(await body(req),callbackOrigins);
        const accepted=store.accept(invocation); // SYNCHRONOUS DURABLE COMMIT BEFORE ACK
        if(accepted.conflict) return reply(res,409,{error:'Conflicting duplicate tool call'});
        return reply(res,200,{ok:true, id:invocation.id, duplicate:!accepted.created});
      }
      return reply(res,404,{error:'Not found'});
    } catch(e) { return reply(res,400,{error:e instanceof SyntaxError?'Invalid JSON':String(e.message)}); }
  });
  return server;
}
export class Pump {
  constructor({store, executor, fetchImpl=fetch, callbackOrigins, intervalMs=100, leaseMs=30000, maxAttempts=3}) {
    this.store=store; this.executor=executor; this.fetchImpl=fetchImpl; this.callbackOrigins=callbackOrigins;
    this.intervalMs=intervalMs; this.leaseMs=leaseMs; this.maxAttempts=maxAttempts;
    this.running=new Map(); this.callbackInFlight=false; this.timer=null;
  }
  start() { if(this.timer)return; this.timer=setInterval(()=>this.tick().catch(console.error),this.intervalMs); this.tick().catch(console.error); }
  async tick() {
    const claim=this.store.claimWork({leaseMs:this.leaseMs,maxAttempts:this.maxAttempts});
    if(claim) {
      const {job,owner}=claim;
      const controller=new AbortController(); this.running.set(job.job_id,controller);
      const renew=setInterval(()=>{ if(!this.store.renew(job.job_id,owner,this.leaseMs))controller.abort(); }, Math.max(10,Math.floor(this.leaseMs/3)));
      void Promise.resolve().then(()=>this.executor.execute(job,controller.signal)).then(
        text=>this.store.finish(job.job_id,owner,typeof text==='string'?text:JSON.stringify(text)),
        err=>this.store.retry(job.job_id,owner,err?.message || 'Worker failure',this.maxAttempts)
      ).finally(()=>{clearInterval(renew);if(this.running.get(job.job_id)===controller)this.running.delete(job.job_id);});
    }
    if(this.callbackInFlight)return;
    const cb=this.store.claimCallback({leaseMs:this.leaseMs});
    if(!cb)return;
    this.callbackInFlight=true;
    try {
      const u=new URL(cb.callback_url);
      if(!this.callbackOrigins.has(u.origin))throw new Error('Callback origin no longer allowed');
      const response=await this.fetchImpl(cb.callback_url,{method:'POST',headers:{'content-type':'application/json'},
        body:cb.payload_json,redirect:'manual',signal:AbortSignal.timeout(Math.min(10000,this.leaseMs))});
      if(!response.ok) throw new Error(`Callback HTTP ${response.status}`);
      this.store.callbackOutcome(cb.job_id,cb.owner,true);
    } catch(e) {this.store.callbackOutcome(cb.job_id,cb.owner,false,e.message);}
    finally {this.callbackInFlight=false;}
  }
  async stop() {clearInterval(this.timer);this.timer=null; for(const c of this.running.values())c.abort();}
}
