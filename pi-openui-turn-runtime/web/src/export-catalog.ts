import {writeFile,readFile} from 'node:fs/promises';
import {openuiLibrary,library,customComponents} from './library.tsx';
const entries=Object.entries(openuiLibrary.components).map(([name,definition])=>{
 const def=definition as unknown as {description?:string;props?:{shape?:Record<string,unknown>}};
 const args=Object.keys(def.props?.shape??{});
 return [name,{description:def.description??name,args}] as const;
});
const base=Object.fromEntries(entries);
if(!base.Stack||!base.Card||!base.TextContent||Object.keys(base).length<20)throw Error('Incomplete OpenUI base import');
const dir=new URL('../../corpus/',import.meta.url);
await writeFile(new URL('base.json',dir),JSON.stringify({version:1,source:'@openuidev/react-ui/openuiLibrary',components:base},null,2)+'\n');
const custom=JSON.parse(await readFile(new URL('custom.json',dir),'utf8')).components as Record<string,{args:string[]}>;
for(const component of customComponents){
 const def=component as unknown as {name:string;props?:{shape?:Record<string,unknown>}};
 if(!custom[def.name]||!library.components[def.name])throw Error('Missing custom JSON definition: '+def.name);
 if(JSON.stringify(Object.keys(def.props?.shape??{}))!==JSON.stringify(custom[def.name]!.args))throw Error('Custom manifest drift: '+def.name);
}
console.log('Synced '+Object.keys(base).length+' upstream OpenUI components and '+Object.keys(custom).length+' custom components');
