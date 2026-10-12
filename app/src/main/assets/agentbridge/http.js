/* Browser Fetch in this document's origin and Gecko container. No arbitrary eval or token injection. */
(() => {
  'use strict';
  const active = new Map();
  globalThis.__abwrHttp = {
    cancel(id) { active.get(id)?.(); },
    cancelAll() { for (const cancel of active.values()) cancel(); },
    async request(msg, emit) {
      if (new URL(msg.url).origin !== location.origin) throw Object.assign(Error('Page HTTP requires the document origin'),{code:'BAD_ARGUMENTS'});
      const page = window.wrappedJSObject;
      const abort = new page.AbortController();
      const cancel = () => abort.abort(); active.set(msg.httpStreamId,cancel);
      const timeout = setTimeout(cancel,Math.max(1000,Math.min(msg.stream ? 60000:20000,msg.timeoutMs || (msg.stream ? 60000:15000))));
      const encode = bytes => { let s=''; for(let i=0;i<bytes.length;i+=8192)s+=String.fromCharCode(...bytes.subarray(i,i+8192));return btoa(s); };
      const decode = value => Uint8Array.from(atob(value),c=>c.charCodeAt(0));
      let reader;
      try {
        const headers = msg.headers || {};
        const init = cloneInto({method:msg.method || 'GET',headers,credentials:msg.credentials || 'include',
          redirect:msg.followRedirects === true ? 'follow':'manual'},page);
        init.signal = abort.signal;
        if(msg.body !== undefined)init.body=String(msg.body);
        if(msg.bodyBase64 !== undefined)init.body=cloneInto(decode(msg.bodyBase64),page);
        const response = await page.fetch(msg.url,init);
        if(response.type==='opaqueredirect')throw Object.assign(Error('Redirect is opaque to browser Fetch; explicitly allow redirects or use native transport'),{code:'HTTP_OPAQUE_REDIRECT'});
        const safeHeaders={};response.headers.forEach((value,key)=>{safeHeaders[key]=/cookie|authorization/i.test(key)?'[redacted]':value;});
        const metadata={url:response.url,status:response.status,headers:safeHeaders,redirected:response.redirected,
          transport:'page',cookieScope:msg.credentials==='omit'?'none':'selected_profile_document'};
        let count=0;
        const chunks=[];
        async function consume() {
          if(!response.body)return;
          reader=response.body.getReader();
          while(true){const {done,value}=await reader.read();if(done)break;
            const bytes=new Uint8Array(value);count+=bytes.length;
            if(count>(msg.maxBytes || 1000000))throw Object.assign(Error('HTTP response exceeds maxBytes'),{code:'RESPONSE_TOO_LARGE'});
            if(msg.stream){for(let start=0;start<bytes.length;start+=32768)emit({type:'chunk',data:encode(bytes.subarray(start,start+32768))});}
            else chunks.push(bytes);
          }
        }
        if(msg.stream){
          emit({type:'headers',response:metadata});
          consume().then(()=>emit({type:'complete'})).catch(error=>emit({type:'error',code:error.code || (error.name==='AbortError'?'HTTP_TIMEOUT':'HTTP_NETWORK_ERROR'),
            message:error.code?error.message:'Browser HTTP stream stopped; inspect connection and timeout'})).finally(()=>{clearTimeout(timeout);active.delete(msg.httpStreamId);reader?.releaseLock();});
          return {streamId:msg.httpStreamId,streaming:true};
        }
        await consume();
        const bytes=new Uint8Array(count);let offset=0;for(const chunk of chunks){bytes.set(chunk,offset);offset+=chunk.length;}
        return {...metadata,body:encode(bytes),bodyEncoding:'base64',bytes:count,truncated:false};
      } catch(error) {
        throw Object.assign(Error(error.code ? error.message : 'Browser Fetch failed; inspect CORS, network and redirect settings'),{code:error.code || (error.name==='AbortError'?'HTTP_TIMEOUT':'HTTP_NETWORK_ERROR')});
      } finally {
        if(!msg.stream || !reader){clearTimeout(timeout);active.delete(msg.httpStreamId);reader?.releaseLock();}
      }
    }
  };
  window.addEventListener('pagehide',()=>globalThis.__abwrHttp.cancelAll());
})();
