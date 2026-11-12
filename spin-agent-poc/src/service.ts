import http from 'node:http';
import { once } from 'node:events';
import type { AddressInfo } from 'node:net';

export type Strategy = 'buggy' | 'fixed';
export class Publisher {
  published = 0;
  approved = new Map<number, boolean>();
  strategy: Strategy;
  constructor(strategy: Strategy) { this.strategy = strategy; }
  preflight(revision: number) {
    const accepted = revision > this.published;
    this.approved.set(revision, accepted);
    return {accepted, published: this.published};
  }
  commit(revision: number) {
    if (!this.approved.get(revision))
      return {applied:false, published:this.published, reason:'not-approved'};
    if (this.strategy === 'fixed' && revision <= this.published)
      return {applied:false, published:this.published, reason:'stale-commit'};
    // Intentionally flawed branch is unconditional; fixed branch guards against regression.
    this.published = revision;
    return {applied:true, published:this.published};
  }
}
export async function startService(strategy: Strategy) {
  const svc = new Publisher(strategy);
  const server = http.createServer(async (req, res) => {
    res.setHeader('content-type','application/json');
    try {
      if (req.method === 'GET' && req.url === '/state') {
        res.end(JSON.stringify({published:svc.published})); return;
      }
      if (req.method === 'POST' && (req.url === '/preflight' || req.url === '/commit')) {
        let body='';
        for await (const part of req) body += part.toString();
        const {revision} = JSON.parse(body);
        if (!Number.isSafeInteger(revision) || revision < 1) {
          res.writeHead(400).end(JSON.stringify({error:'invalid revision'})); return;
        }
        const response = req.url === '/preflight' ? svc.preflight(revision) : svc.commit(revision);
        res.end(JSON.stringify(response)); return;
      }
      res.writeHead(404).end(JSON.stringify({error:'not found'}));
    } catch (e) { res.writeHead(500).end(JSON.stringify({error:String(e)})); }
  });
  server.listen(0,'127.0.0.1');
  await once(server,'listening');
  const address = server.address() as AddressInfo;
  return {
    url: 'http://127.0.0.1:' + address.port,
    close: async () => { server.close(); await once(server,'close'); }
  };
}
