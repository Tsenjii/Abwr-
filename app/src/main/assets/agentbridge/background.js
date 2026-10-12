/* Built-in broker. User code only enters Firefox's userScripts sandbox. */
(() => {
  'use strict';
  const registered = new Map(), recordings = new Map();
  let nativePort = null, reconnect = null, syncQueue = Promise.resolve();
  const safeUrl = value => { try { const u = new URL(value); return u.origin + u.pathname; } catch (_) { return '[unavailable]'; } };
  const headers = list => (list || []).slice(0, 40).map(h => ({name:String(h.name).slice(0,80),
    value:/^(?:content-type|content-length|cache-control|accept|accept-encoding|server|vary|date|etag|last-modified)$/i.test(h.name)
      ? String(h.value || '').slice(0,300) : '[redacted]'}));
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
        const handle=await browser.userScripts.register({matches:script.matches, excludeMatches:script.excludes || [],
          js:[{code:script.code}], runAt:script.runAt || 'document_idle', allFrames:false,
          scriptMetadata:{id, name:script.name, grants:script.grants || []}});
        registered.set(id,{handle,signature:JSON.stringify(script)});
      } catch(error) { errors.push({id,message:String(error.message || error).slice(0,250)}); }
    }
    publish({scriptsStatus:true,active:registered.size,errors});
  }
  function connect() {
    if (nativePort) return;
    try {
      const p=browser.runtime.connectNative('abwr-tools'); nativePort=p;
      p.onMessage.addListener(msg=> {
        if (Array.isArray(msg.scripts)) syncQueue=syncQueue.then(()=>applyScripts(msg.scripts)).catch(error=>publish({scriptsStatus:true,active:registered.size,errors:[{message:String(error).slice(0,250)}]}));
      });
      p.onDisconnect.addListener(()=>{if(nativePort===p){nativePort=null;schedule();}});
      p.postMessage({toolsReady:true});
    } catch (_) {nativePort=null;schedule();}
  }
  function schedule(){if(reconnect!==null)return;reconnect=setTimeout(()=>{reconnect=null;connect();},5000);}
  browser.runtime.onMessage.addListener((msg,sender)=>{
    if(sender.id!==browser.runtime.id || !sender.tab || !Number.isInteger(sender.tab.id)) return undefined;
    const id=sender.tab.id;
    if(msg?.abwrInspector==='state') return Promise.resolve({recording:recordings.has(id)});
    if(msg?.abwrInspector!=='read') return undefined;
    if(msg.record===true && !recordings.has(id)) {
      if(recordings.size>=8)recordings.delete(recordings.keys().next().value);
      recordings.set(id,new Map());
    }
    if(msg.record===false) recordings.delete(id);
    const items=recordings.get(id); if(msg.clear===true)items?.clear();
    return Promise.resolve({recording:!!items,requests:items?[...items.values()]:[],scope:'current_tab',
      headersRedacted:true,bodiesCaptured:false});
  });
  const update=(d,phase)=>{
    const map=recordings.get(d.tabId);if(!map)return;
    let entry=map.get(d.requestId);
    if(!entry){entry={id:d.requestId,url:safeUrl(d.url),method:d.method,type:d.type,startedAtMs:d.timeStamp,phase};map.set(d.requestId,entry);}
    Object.assign(entry,{phase});
    if(d.statusCode!==undefined)entry.status=d.statusCode;
    if(d.requestHeaders)entry.requestHeaders=headers(d.requestHeaders);
    if(d.responseHeaders)entry.responseHeaders=headers(d.responseHeaders);
    if(d.error)entry.error=String(d.error).slice(0,150);
    if(phase==='complete'||phase==='error'||phase==='redirect')entry.durationMs=Math.max(0,Math.round(d.timeStamp-entry.startedAtMs));
    while(map.size>200)map.delete(map.keys().next().value);
  };
  const filter={urls:['<all_urls>']};
  browser.webRequest.onBeforeRequest.addListener(d=>update(d,'request'),filter);
  browser.webRequest.onBeforeSendHeaders.addListener(d=>update(d,'sending'),filter,['requestHeaders']);
  browser.webRequest.onHeadersReceived.addListener(d=>update(d,'response'),filter,['responseHeaders']);
  browser.webRequest.onCompleted.addListener(d=>update(d,'complete'),filter);
  browser.webRequest.onBeforeRedirect.addListener(d=>update(d,'redirect'),filter);
  browser.webRequest.onErrorOccurred.addListener(d=>update(d,'error'),filter);
  browser.tabs.onRemoved.addListener(id=>recordings.delete(id));
  connect();
})();
