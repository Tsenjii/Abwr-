'use strict';
const assert = require('assert'), fs = require('fs'), vm = require('vm');
const listeners = {}, registrations = [], nativeMessages = [];
const event = name => ({addListener(fn) { listeners[name] = fn; }});
const context = {URL, Map, Promise, setTimeout, clearTimeout,
  browser:{runtime:{id:'builtin',connectNative(){return {postMessage:m=>nativeMessages.push(m),onMessage:event('native'),onDisconnect:event('disconnect')};},onMessage:event('message')},
    userScripts:{async register(options){registrations.push(options);return {async unregister(){registrations.push('unregistered');}};}},
    tabs:{onRemoved:event('removed')},webRequest:Object.fromEntries(['onBeforeRequest','onBeforeSendHeaders','onHeadersReceived','onCompleted','onBeforeRedirect','onErrorOccurred'].map(name=>[name,event(name)]))}};
vm.runInNewContext(fs.readFileSync('app/src/main/assets/agentbridge/background.js','utf8'),context);
const settle=()=>new Promise(resolve=>setImmediate(resolve));
(async()=>{
  listeners.native({scripts:[{id:'s1',enabled:true,matches:['https://example.org/*'],code:'document.title="custom";',grants:[]}]});await settle();
  assert.strictEqual(registrations.length,1);assert.strictEqual(registrations[0].allFrames,false);
  assert.strictEqual(registrations[0].scriptMetadata.id,'s1');assert.strictEqual(registrations[0].js[0].code,'document.title="custom";');
  listeners.native({scripts:[]});await settle();assert.strictEqual(registrations[1],'unregistered');
  assert.strictEqual(listeners.message({abwrInspector:'read',record:true},{id:'outsider',tab:{id:3}}),undefined);
  const sender={id:'builtin',tab:{id:3}};
  await listeners.message({abwrInspector:'read',record:true},sender);
  listeners.onBeforeRequest({tabId:3,requestId:'r1',url:'https://user:pass@example.org/path?token=secret#private',method:'GET',type:'fetch',timeStamp:1});
  listeners.onBeforeSendHeaders({tabId:3,requestId:'r1',requestHeaders:[{name:'Authorization',value:'Bearer secret'},{name:'Cookie',value:'session=secret'},{name:'Accept',value:'text/html'}]});
  listeners.onCompleted({tabId:3,requestId:'r1',statusCode:200,timeStamp:20});
  const output=await listeners.message({abwrInspector:'read'},sender);
  assert.strictEqual(output.requests[0].url,'https://example.org/path');assert.strictEqual(output.requests[0].durationMs,19);
  assert(!JSON.stringify(output).includes('secret'));assert.strictEqual(output.requests[0].requestHeaders[2].value,'text/html');
  const other=await listeners.message({abwrInspector:'read'},{id:'builtin',tab:{id:4}});assert.strictEqual(other.requests.length,0);
  for(let i=0;i<230;i++)listeners.onBeforeRequest({tabId:3,requestId:'r'+i,url:'https://example.org/',timeStamp:i});
  assert.strictEqual((await listeners.message({abwrInspector:'read'},sender)).requests.length,200);
  console.log('Sandbox registration, removal, recording scope, redaction and bounds: PASS');
})().catch(error=>{console.error(error);process.exit(1);});
