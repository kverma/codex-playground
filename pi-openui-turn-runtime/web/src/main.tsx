import React,{useState,useEffect} from 'react';
import{createRoot}from'react-dom/client';
import{Renderer}from'@openuidev/react-lang';
import '@openuidev/react-ui/styles/index.css';
import{library}from'./library.tsx';
import'./style.css';
type Message={role:'user'|'assistant'|'plain'|'unavailable'|'error';text:string};
function App(){
 const [messages,setMessages]=useState<Message[]>([]);
 const[draft,setDraft]=useState('Show failed Atlas builds in the last 24 hours');
 const[busy,setBusy]=useState(false);
 const[threadId]=useState(()=>crypto.randomUUID());
 useEffect(()=>{const setFromCard=(event:Event)=>{const text=(event as CustomEvent<string>).detail;if(typeof text==='string')setDraft(text);};window.addEventListener('appstore:chat',setFromCard);return()=>window.removeEventListener('appstore:chat',setFromCard);},[]);
 async function send(text:string){
  if(busy||!text.trim())return;
  setMessages(s=>[...s,{role:'user',text}]);setDraft('');setBusy(true);
  try{
   const response=await fetch('/api/chat',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({threadId,turnId:crypto.randomUUID(),text})});
   const obj=await response.json();
   if(!response.ok)throw Error(obj.error??'Request failed');
   setMessages(s=>[...s,{role:obj.kind==='ui'?'assistant':obj.kind==='text'?'plain':'unavailable',text:obj.kind==='ui'?obj.openui:String(obj.text??'No response')}]);
  }catch(e){setMessages(s=>[...s,{role:'error',text:String(e)}]);}finally{setBusy(false);}
 }
 return <div className="layout"><aside><h2>Pi × OpenUI</h2><p>Typed UI from chat turns</p><h3>Try</h3>{['Show failed Atlas builds in the last 24 hours','Only for the main branch','Find drawing apps','Show details for the first one','Compare the first two apps','Only free ones'].map(x=><button onClick={()=>setDraft(x)} key={x}>{x}</button>)}<p className="muted">Fixture data unless a live adapter is configured. Novel requests need the planner.</p></aside><main><header><strong>Conversation</strong><small>Native OpenUI Renderer</small></header><section className="messages">{messages.length===0&&<div className="welcome">Ask to inspect data, compose cards, or compare systems.</div>}{messages.map((m,i)=><div className={'message '+m.role} key={i}>{m.role==='assistant'?<Renderer library={library} response={m.text} isStreaming={false} onAction={event=>{if(event.type==='continue_conversation')void send(event.humanFriendlyMessage);else if(event.type==='open_url'){const url=String(event.params?.url??'');if(url.startsWith('https://'))window.open(url,'_blank','noopener,noreferrer');}}}/>:m.text}</div>)}{busy&&<p>Executing approved capabilities…</p>}</section><form onSubmit={e=>{e.preventDefault();void send(draft);}}><input aria-label="Chat" value={draft} onChange={e=>setDraft(e.target.value)} placeholder="Ask a question…"/><button disabled={busy||!draft.trim()}>Send</button></form></main></div>;
}
createRoot(document.getElementById('root')!).render(<App/>);
