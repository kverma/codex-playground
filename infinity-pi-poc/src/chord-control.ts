import { Type } from 'typebox';
import { createHost, hostNamed, method, remote } from '@earendil-works/chord';
import { BACKGROUND_CONTEXT } from '@earendil-works/chord/context';
import type { JobStore } from './store.mjs';

const JobView = Type.Object({ jobId:Type.String(), groupId:Type.String(), operation:Type.String(),
  status:Type.String(), attempts:Type.Integer(), updatedAt:Type.Number() });
const Views = Type.Record(Type.String(),JobView);
export const JobsControl=remote('poc.infinity-jobs',{
  version:1, input:Type.Object({scope:Type.String()}), host:hostNamed('control'),
  variants:{default:{ state:{jobs:Views}, methods:{
    getJob:method(Type.Object({jobId:Type.String()}),Type.Union([JobView,Type.Null()])),
    cancelJob:method(Type.Object({groupId:Type.String(), toolId:Type.String()}),Type.Boolean()),
  } }},
});
const view=(j:any)=>({jobId:j.job_id,groupId:j.group_id,operation:j.operation,
  status:j.status,attempts:j.attempts,updatedAt:j.updated_at});
/** Chord is ONLY a live projection of the SQLite journal. Never persist business events in Chord state. */
export async function openChordControl(store:JobStore) {
  const host=createHost({name:'control',self:{kind:'control'}});
  host.serve(JobsControl,(_input,_principal,self)=>{
    const jobs=self.state('jobs',Views,Object.fromEntries(store.list().map(j=>[j.job_id,view(j)])));
    const onChanged=(j:any)=>jobs.change(BACKGROUND_CONTEXT,draft=>{draft[j.job_id]=view(j);});
    store.on('changed',onChanged);
    return {variant:'default' as const,jobs,
      getJob:({jobId}:{jobId:string})=>{const j=store.get(jobId);return j?view(j):null;},
      cancelJob:({groupId,toolId}:{groupId:string;toolId:string})=>store.cancel(groupId,toolId),
    };
  });
  const reference=await host.get(JobsControl,{scope:'local'});
  return {host,reference,close:async()=>{reference.release();await host.close();}};
}
