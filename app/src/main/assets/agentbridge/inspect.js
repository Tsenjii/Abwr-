/* Opt-in console capture. No evaluation endpoint or page-to-native command API. */
(() => {
  'use strict';
  let recording=false;
  const events=[];
  const clean=value=>String(value).replace(/\b(?:Bearer\s+)[A-Za-z0-9._~+/=-]+/gi,'Bearer [redacted]')
    .replace(/((?:token|password|secret|authorization|cookie|session|code)\s*[=:]\s*)[^\s,;]+/gi,'$1[redacted]').slice(0,800);
  const add=(level,args)=>{
    if(!recording)return;
    const message=args.slice(0,8).map(v=>{
      if(v===null)return 'null';
      if(['string','number','boolean','undefined'].includes(typeof v))return clean(v);
      return '[object]'; // Do not invoke page getters or serialize credentials from objects.
    }).join(' ');
    events.push({level,message,timeMs:Date.now()});if(events.length>120)events.shift();
  };
  globalThis.__abwrInspector={read(options={}) {
    if(typeof options.record==='boolean')recording=options.record;
    if(options.clear===true)events.length=0;
    return {recording,events:events.slice(),source:'page_console',objectsSerialized:false};
  }};
  window.addEventListener('error',e=>add('error',[e.message||'Script error']));
  window.addEventListener('unhandledrejection',()=>add('error',['Unhandled promise rejection']));
  try {
    const pageConsole=window.wrappedJSObject.console;
    for(const level of ['log','info','warn','error','debug']) {
      const original=pageConsole[level];
      // Use the content realm's apply. The page's Function.prototype.apply
      // cannot read our privileged rest-argument array across the Xray boundary.
      pageConsole[level]=exportFunction(function(...args){add(level,args);return Reflect.apply(original,pageConsole,args);},window);
    }
  } catch (_) { /* Error events remain available when a page locks down console. */ }
  browser.runtime.sendMessage({abwrInspector:'state'}).then(state=>{recording=!!state?.recording;}).catch(()=>{});
})();
