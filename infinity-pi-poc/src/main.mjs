import { mkdir } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { JobStore } from './store.mjs';
import { makeGateway, Pump } from './gateway.mjs';
import { analyzeFailures } from './analysis.mjs';

const db=resolve(process.env.POC_GATEWAY_DB ?? './.local/rap.sqlite');
const piDb=resolve(process.env.POC_PI_DB ?? './.local/pi.sqlite');
await mkdir(dirname(db),{recursive:true});
await mkdir(dirname(piDb),{recursive:true});
const store=new JobStore(db);
const usePi=process.env.POC_EXECUTOR!=='fixture';
const executor=usePi
  ? await (await import('./pi-executor.ts')).openPiExecutor(piDb)
  : { execute:job=>analyzeFailures(JSON.parse(job.args_json)),close:async()=>{} };
const control=usePi ? await (await import('./chord-control.ts')).openChordControl(store) : null;
const port=Number(process.env.POC_PORT ?? '3001');
const listenHost=process.env.POC_HOST ?? '127.0.0.1';
// Default callbacks: loopback only. Explicit origins must be supplied for external trusted Infinity.
const origins=new Set((process.env.RAP_CALLBACK_ORIGINS ?? 'http://127.0.0.1:4002').split(',').map(x=>x.trim()));
const endpoint=process.env.RAP_PUBLIC_ENDPOINT ?? `http://127.0.0.1:${port}`;
if(listenHost!=='127.0.0.1' && !process.env.RAP_INVOKE_TOKEN)throw new Error('Non-loopback requires RAP_INVOKE_TOKEN');
const server=makeGateway({store,callbackOrigins:origins,token:process.env.RAP_INVOKE_TOKEN,endpoint});
const pump=new Pump({store,executor,callbackOrigins:origins});
await new Promise(resolve=>server.listen(port,listenHost,resolve));
pump.start();
console.log(`RAP server at ${endpoint}, executor=${usePi?'pi-durable':'fixture'}`);
let closing=false;
async function close(){if(closing)return;closing=true;await pump.stop();await new Promise(r=>server.close(r));await control?.close();await executor.close();store.close();}
for(const sig of ['SIGINT','SIGTERM'])process.on(sig,()=>{close().then(()=>process.exit(0)).catch(()=>process.exit(1));});
