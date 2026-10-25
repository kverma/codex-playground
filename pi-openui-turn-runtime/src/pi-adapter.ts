import type{Durable,Invoke,Json,Context}from'./runtime.ts';
/** Call only through the installed Pi Durable harness' journaled tool boundary.
 * Never let a planner supply tool code or permissions. This is the host integration seam;
 * the standalone demo deliberately has no durable storage implementation.
 */
export function fromPiHarness(input:{executeTool:(tool:string,args:Record<string,Json>,context:Context)=>Promise<unknown>; allowed:ReadonlySet<string>}):Invoke{
 return async(cap,args,context)=>{
  if(cap!=='mcp.invoke')throw Error('Not a Pi-MCP capability');
  const tool=args.tool;
  if(typeof tool!=='string'||!input.allowed.has(tool))throw Error('Pi tool not approved');
  const callArgs=args.input;
  if(callArgs===null||typeof callArgs!=='object'||Array.isArray(callArgs))throw Error('Invalid Pi args');
  return input.executeTool(tool,callArgs as Record<string,Json>,context);
 };
}
/** Plug your actual Pi Durable journal here. No fake replay guarantee. */
export function fromPiDurable(run:<T>(key:string,fn:()=>Promise<T>)=>Promise<T>):Durable{return {run};}
