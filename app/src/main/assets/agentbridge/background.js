/* Built-in broker. User code only enters Firefox's userScripts sandbox. */
(() => {
  'use strict';
  const registered = new Map(), recordings = new Map(), startupErrors = [];
  let nativePort = null, reconnect = null, acknowledgement = null, syncQueue = Promise.resolve();
  const safeUrl = value => { try { const u = new URL(value); return u.origin + u.pathname; } catch (_) { return '[unavailable]'; } };
  const sensitive=name=>/password|secret|api[-_]?key|(?:access|refresh|id)[-_]?token|authorization|cookie|session|credential|cf_clearance|turnstile|captcha|^gid$|^token$/i.test(name);
  const headers = list => (list || []).slice(0, 40).map(h => ({name:String(h.name).slice(0,80),
    value:sensitive(h.name) ? '[redacted]' : /^(?:location|referer)$/i.test(h.name) ? safeUrl(h.value) : String(h.value || '').slice(0,500)}));
  const redact = text => {
    try {
      const visit=(value,depth=0)=> {
        if(depth>12)return '[nested]';
        if(Array.isArray(value))return value.slice(0,1000).map(v=>visit(v,depth+1));
        if(value && typeof value==='object')return Object.fromEntries(Object.entries(value).slice(0,1000).map(([k,v])=>[k,sensitive(k)?'[redacted]':visit(v,depth+1)]));
        if(typeof value==='string' && (value.trim().startsWith('{') || value.trim().startsWith('['))) {try{return JSON.stringify(visit(JSON.parse(value),depth+1));}catch(_){}}
        return typeof value==='string'?value.replace(/Bearer\s+[A-Za-z0-9._~+/=-]+/gi,'Bearer [redacted]'):value;
      };
      return JSON.stringify(visit(JSON.parse(text)));
    } catch (_) {
      return String(text).replace(/Bearer\s+[A-Za-z0-9._~+/=-]+/gi,'Bearer [redacted]')
        .replace(/((?:password|secret|access_token|refresh_token|authorization|cookie|session|cf_clearance|turnstile|captcha|gid|token)["']?\s*[=:]\s*["']?)[^&\s,;"'}]+/gi,'$1[redacted]');
    }
  };
  const publish = message => { try { nativePort?.postMessage(message); } catch (_) {} };
  async function applyScripts(scripts) {
    const desired = new Map((Array.isArray(scripts) ? scripts : []).filter(s=>s.enabled).map(s=>[s.id,s]));
    for (const [id, saved] of registered) {
      if (!desired.has(id) || JSON.stringify(desired.get(id)) !== saved.signature) {
        await saved.handle.unregister(); registered.delete(id);
      }
    }
    const errors=[];
    for (const [id, script] of desired) {
      if (registered.has(id)) continue;
      try {
        if (!browser.userScripts?.register) throw Error('This Gecko runtime does not support sandboxed userScripts');
        const options={matches:script.matches,
          js:[{code:script.code}], runAt:script.runAt || 'document_idle', allFrames:false,
          scriptMetadata:{id, name:script.name, grants:script.grants || []}};
        if(script.excludes?.length)options.excludeMatches=script.excludes;
        const handle=await browser.userScripts.register(options);
        registered.set(id,{handle,signature:JSON.stringify(script)});
      } catch(error) { errors.push({id,message:String(error.message || error).slice(0,250)}); }
    }
    publish({scriptsStatus:true,active:registered.size,errors,inspectorErrors:startupErrors.slice()});
  }
  function connect() {
    if (nativePort) return;
    try {
      const p=browser.runtime.connectNative('abwr_tools'); nativePort=p;
      p.onMessage.addListener(msg=> {
        if (Array.isArray(msg.scripts)) { if(acknowledgement!==null)clearTimeout(acknowledgement);acknowledgement=null; }
        if (Array.isArray(msg.scripts)) syncQueue=syncQueue.then(()=>applyScripts(msg.scripts)).catch(error=>publish({scriptsStatus:true,active:registered.size,errors:[{message:String(error).slice(0,250)}]}));
      });
      p.onDisconnect.addListener(()=>{if(nativePort===p){if(acknowledgement!==null)clearTimeout(acknowledgement);acknowledgement=null;nativePort=null;schedule();}});
      acknowledgement=setTimeout(()=>{if(nativePort===p){nativePort=null;try{p.disconnect();}catch(_){}schedule();}},2500);
      p.postMessage({toolsReady:true});
    } catch (_) {nativePort=null;schedule();}
  }
  function schedule(){if(reconnect!==null)return;reconnect=setTimeout(()=>{reconnect=null;connect();},5000);}
  browser.runtime.onMessage.addListener((msg,sender)=>{
    if(sender.id!==browser.runtime.id || !sender.tab || !Number.isInteger(sender.tab.id) || sender.tab.id<0) return undefined;
    const id=sender.tab.id;
    if(msg?.abwrInspector==='state') return Promise.resolve({recording:!!recordings.get(id)?.enabled});
    if(msg?.abwrInspector!=='read') return undefined;
    if(msg.record===true && !recordings.has(id)) {
      if(recordings.size>=8)recordings.delete(recordings.keys().next().value);
      recordings.set(id,{enabled:true,captureBodies:false,bytes:0,items:new Map()});
    }
    const state=recordings.get(id);
    if(state && typeof msg.record==='boolean')state.enabled=msg.record;
    if(state && typeof msg.captureBodies==='boolean')state.captureBodies=msg.captureBodies;
    if(state && msg.clear===true){state.items.clear();state.bytes=0;}
    return Promise.resolve({recording:!!state?.enabled,captureBodies:!!state?.captureBodies,
      requests:state?[...state.items.values()]:[],scope:'current_tab',headersRedacted:true,
      bodyPreviewLimit:65536,bodiesCaptured:!!state?.captureBodies});
  });
  const preview=(state,entry,key,text,truncated=false)=> {
    if(!state.enabled || !state.captureBodies || state.bytes>=1000000)return;
    const value=redact(text).slice(0,65536);state.bytes+=value.length;
    entry[key]={text:value,truncated,redacted:true,encoding:'text'};
  };
  const update=(d,phase)=>{
    const state=recordings.get(d.tabId);if(!state?.enabled)return;const map=state.items;
    let entry=map.get(d.requestId);
    if(!entry){entry={id:d.requestId,url:safeUrl(d.url),method:d.method,type:d.type,startedAtMs:d.timeStamp,phase};map.set(d.requestId,entry);}
    Object.assign(entry,{phase});
    if(d.statusCode!==undefined)entry.status=d.statusCode;
    if(d.requestHeaders)entry.requestHeaders=headers(d.requestHeaders);
    if(d.responseHeaders)entry.responseHeaders=headers(d.responseHeaders);
    if(d.error)entry.error=String(d.error).slice(0,150);
    if(phase==='complete'||phase==='error'||phase==='redirect')entry.durationMs=Math.max(0,Math.round(d.timeStamp-entry.startedAtMs));
    if(phase==='request' && state.captureBodies) {
      if(d.requestBody?.formData){
        const values=Object.fromEntries(Object.entries(d.requestBody.formData).map(([k,v])=>[k,sensitive(k)?'[redacted]':v]));
        preview(state,entry,'requestBody',JSON.stringify(values));
      } else if(d.requestBody?.raw){
        const chunks=d.requestBody.raw.map(p=>p.bytes?new Uint8Array(p.bytes):new Uint8Array());
        const total=chunks.reduce((n,b)=>n+b.length,0);const bytes=new Uint8Array(Math.min(total,65536));let offset=0;
        for(const chunk of chunks){const part=chunk.subarray(0,bytes.length-offset);bytes.set(part,offset);offset+=part.length;if(offset===bytes.length)break;}
        preview(state,entry,'requestBody',new TextDecoder().decode(bytes),total>bytes.length);
      }
      if(['xmlhttprequest','fetch'].includes(d.type) && browser.webRequest.filterResponseData) {
        try {
          const filter=browser.webRequest.filterResponseData(d.requestId);let size=0,truncated=false;const parts=[];
          filter.ondata=e=>{filter.write(e.data);if(!state.enabled || !state.captureBodies)return;const raw=new Uint8Array(e.data);const take=Math.min(65536-size,raw.length);if(take>0){parts.push(raw.slice(0,take));size+=take;}if(take<raw.length)truncated=true;};
          filter.onstop=()=>{const bytes=new Uint8Array(size);let offset=0;for(const part of parts){bytes.set(part,offset);offset+=part.length;}
            preview(state,entry,'responseBody',new TextDecoder().decode(bytes),truncated);filter.close();};
          filter.onerror=()=>{entry.responseBodyError='Response preview unavailable';try{filter.disconnect();}catch(_){}};
        } catch(_){entry.responseBodyError='Response preview unsupported by this runtime';}
      }
    }
    while(map.size>200)map.delete(map.keys().next().value);
  };
  // Optional inspector APIs must not prevent the native broker and sandbox
  // service from starting on a Gecko runtime with a narrower API surface.
  connect();
  const filter={urls:['<all_urls>']};
  const hook=(name,run)=>{try{run();}catch(error){startupErrors.push({api:name,message:String(error.message||error).slice(0,250)});}};
  // Pause before the network starts so a fast/cached response cannot finish
  // before its opt-in StreamFilter is attached. The request is not modified.
  hook('webRequest.onBeforeRequest',()=>browser.webRequest.onBeforeRequest.addListener(d=>{update(d,'request');return {};},filter,['requestBody','blocking']));
  hook('webRequest.onBeforeSendHeaders',()=>browser.webRequest.onBeforeSendHeaders.addListener(d=>update(d,'sending'),filter,['requestHeaders']));
  hook('webRequest.onHeadersReceived',()=>browser.webRequest.onHeadersReceived.addListener(d=>update(d,'response'),filter,['responseHeaders']));
  hook('webRequest.onCompleted',()=>browser.webRequest.onCompleted.addListener(d=>update(d,'complete'),filter));
  hook('webRequest.onBeforeRedirect',()=>browser.webRequest.onBeforeRedirect.addListener(d=>update(d,'redirect'),filter));
  hook('webRequest.onErrorOccurred',()=>browser.webRequest.onErrorOccurred.addListener(d=>update(d,'error'),filter));
  hook('tabs.onRemoved',()=>browser.tabs.onRemoved.addListener(id=>recordings.delete(id)));
})();
