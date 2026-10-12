'use strict';
browser.userScripts.onBeforeScript.addListener(script => {
  const grants=script.metadata?.grants || [];
  const api={};
  if(grants.includes('GM_addStyle')) api.GM_addStyle=css=>{
    const style=document.createElement('style');style.textContent=String(css).slice(0,100000);
    (document.head||document.documentElement).appendChild(style);return style;
  };
  if(grants.includes('GM_log')) api.GM_log=(...args)=>console.log('[Abwr userscript]',...args);
  script.defineGlobals(api);
});
