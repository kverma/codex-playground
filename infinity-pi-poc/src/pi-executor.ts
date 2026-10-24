/** A real Pi Durable custom-task executor, not an emulated worker.
 * No LLM required: deterministic PoC operation exercises persisted tasks/restart and task-ID ledger.
 */
import { BACKGROUND_CONTEXT } from '@earendil-works/chord/context';
import { createModels } from '@earendil-works/pi-ai/models';
import { createRegistry, defineDoc, defineTask, defineExtension, Harness } from '@earendil-works/pi-durable';
import { openNodeSqliteStorage } from '@earendil-works/pi-durable/storage/sqlite/node';
import { analyzeFailures } from './analysis.mjs';

const ctx = BACKGROUND_CONTEXT;
const JobLedger = defineDoc<{ tasks: Record<string,string> }>({
  kind:'rap.job-ledger', version:1, scope:'conversation', history:'latest', fork:'initial',
  initial:()=>({tasks:{}}), checkpointWhen:()=>false,
});
const Work = defineTask<
  {jobId:string; operation:string; args:{failures:string[]} },
  {phase:'compute'}, string
>({
  name:'rap.analyze-ci', version:1, initial:()=>({phase:'compute'}),
  phases: {
    compute: async (task,runtime,context)=>{
      // Safe to replay: pure calculation. Non-idempotent tools need their own durable external keys.
      const answer = analyzeFailures(task.input.args);
      await runtime.commit(()=>({status:'terminal',outcome:{status:'completed',result:answer}}),context);
    },
  },
  abort: (_task,runtime,context)=>runtime.commit(()=>({status:'terminal',outcome:{status:'aborted'}}),context),
});

export async function openPiExecutor(path:string) {
  const registry=createRegistry();
  registry.install(defineExtension({name:'rap-jobs',tasks:[Work]}));
  const harness=await Harness.open(await openNodeSqliteStorage(path),{models:createModels(),registry},ctx);
  const root=await harness.root(ctx);
  harness.resume();
  return {
    async execute(job:{job_id:string;operation:string;args_json:string},signal?:AbortSignal):Promise<string> {
      if(job.operation !== 'analyze_ci_failures') throw new Error('Unsupported operation');
      // The ledger and task creation are ONE Pi Durable atomic commit. A crash after
      // this commit but before the gateway marks completion does NOT make a new Pi task.
      const taskId=await root.commit(async tx=>{
        const doc=await tx.doc(JobLedger,root.id);
        const previous=doc.tasks[job.job_id];
        if(previous)return previous;
        const id=await tx.createTask(Work,{
          jobId:job.job_id,operation:job.operation,args:JSON.parse(job.args_json),
        },{ownership:{kind:'conversation'}});
        doc.tasks[job.job_id]=id;
        return id;
      },ctx);
      const onAbort=()=>{ void harness.abortTask(taskId,ctx).catch(()=>{}); };
      if(signal?.aborted)onAbort();
      else signal?.addEventListener('abort',onAbort,{once:true});
      try {
        const ended=await harness.waitForTask(taskId,ctx);
        if(ended.state.status!=='terminal')throw new Error('Task did not settle');
        const outcome=ended.state.outcome;
        if(outcome.status!=='completed')throw new Error(`Task ${outcome.status}`);
        return outcome.result;
      } finally { signal?.removeEventListener('abort',onAbort); }
    },
    close:()=>harness.close(ctx),
  };
}
