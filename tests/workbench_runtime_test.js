'use strict';
const assert = require('assert'), fs = require('fs'), vm = require('vm');
const listeners = {}, registrations = [], nativeMessages = [], filters = new Map();
const event = name => ({addListener(fn) { listeners[name] = fn; }});
const tabStores=new Map([[101,'gv-a'],[102,'gv-b'],[103,'gv-a']]);
const context = {URL, Map, Promise, TextDecoder, TextEncoder, setTimeout, clearTimeout,btoa:s=>Buffer.from(s,'binary').toString('base64'),
  browser:{runtime:{id:'builtin',connectNative(name){assert(/^\w+(\.\w+)*$/.test(name),'Native messaging application names cannot contain hyphens');return {postMessage:m=>nativeMessages.push(m),onMessage:event('native'),onDisconnect:event('disconnect')};},onMessage:event('message')},
    userScripts:{async register(options){assert(options.excludeMatches === undefined || options.excludeMatches.length > 0,'Firefox excludes must be omitted when empty');registrations.push(options);return {async unregister(){registrations.push('unregistered');}};}},
    proxy:{onRequest:event('proxyRequest')},tabs:{async get(id){if(!tabStores.has(id))throw Error('Tab not found');return {id,cookieStoreId:tabStores.get(id)};},onCreated:event('created'),onUpdated:event('updated'),onRemoved:event('removed')},webRequest:{...Object.fromEntries(['onBeforeRequest','onBeforeSendHeaders','onHeadersReceived','onCompleted','onBeforeRedirect','onErrorOccurred','onAuthRequired'].map(name=>[name,event(name)])),
      filterResponseData(id){const filter={writes:[],write(data){this.writes.push(Buffer.from(data));},close(){this.closed=true;},disconnect(){this.disconnected=true;}};filters.set(id,filter);return filter;}}}};
vm.runInNewContext(fs.readFileSync('app/src/main/assets/agentbridge/background.js','utf8'),context);
const settle=()=>new Promise(resolve=>setImmediate(resolve));
// The page realm's .apply must not receive a privileged content-script array.
// Forward arguments unchanged without serializing page objects or getters.
const forwarded=[], pageConsole={};
for(const level of ['log','info','warn','error','debug']) {
  const original=function(...args){forwarded.push({level,args,receiver:this});};
  Object.defineProperty(original,'apply',{get(){throw Error('Permission denied to access property length');}});
  pageConsole[level]=original;
}
const inspector={window:{wrappedJSObject:{console:pageConsole},addEventListener(){}},exportFunction:fn=>fn,
  browser:{runtime:{sendMessage:async()=>({recording:true})}}};
vm.runInNewContext(fs.readFileSync('app/src/main/assets/agentbridge/inspect.js','utf8'),inspector);
inspector.__abwrInspector.read({record:true});
const object={get secret(){throw Error('Page getter must not run');}};
pageConsole.log('test-message',object);
assert.strictEqual(forwarded[0].receiver,pageConsole);assert.strictEqual(forwarded[0].args[1],object);
assert.strictEqual(inspector.__abwrInspector.read().events[0].message,'test-message [object]');
(async()=>{
  listeners.native({proxyPolicies:[{cookieStoreId:'gv-a',type:'http',host:'127.0.0.1',port:7914,username:'fixture-a',password:'test-a',revision:'a1'},
    {cookieStoreId:'gv-b',type:'socks',host:'127.0.0.1',port:7915,username:'fixture-b',password:'test-b',revision:'b1'}]});
  const first=await listeners.proxyRequest({tabId:101,cookieStoreId:'firefox-default'});
  assert.strictEqual(first[0].port,7914);assert.strictEqual(first[1],null,'Proxy failure must not fall back to direct');
  const otherRoute=await listeners.proxyRequest({tabId:102,cookieStoreId:'gv-a'});
  assert.strictEqual(otherRoute[0].port,7915,'Request cookieStoreId overrode the actual Gecko tab context');
  const newTab=await listeners.proxyRequest({tabId:103,cookieStoreId:'firefox-default'});
  assert.strictEqual(newTab[0].port,7914,'A new tab required a loaded content bridge to acquire its route');
  listeners.created({id:103,cookieStoreId:'firefox-default'});
  assert.strictEqual((await listeners.proxyRequest({tabId:103}))[0].port,7914,'Stale onCreated metadata poisoned the first route');
  assert.strictEqual((await listeners.onBeforeRequest({tabId:107,cookieStoreId:'firefox-default'})).cancel,true);
  assert.strictEqual((await listeners.onAuthRequired({tabId:101,isProxy:true,requestId:'auth'})).authCredentials.username,'fixture-a');
  assert.strictEqual((await listeners.onAuthRequired({tabId:101,isProxy:true,requestId:'auth'})).cancel,true);
  listeners.native({proxyPolicies:[]});
  assert.strictEqual((await listeners.proxyRequest({tabId:107})).type,'direct');
  listeners.native({scripts:[{id:'s1',enabled:true,matches:['https://example.org/*'],code:'document.title="custom";',grants:[]}]});await settle();
  assert.strictEqual(registrations.length,1);assert.strictEqual(registrations[0].allFrames,false);
  assert.strictEqual(registrations[0].scriptMetadata.id,'s1');assert.strictEqual(registrations[0].js[0].code,'document.title="custom";');
  listeners.native({scripts:[]});await settle();assert.strictEqual(registrations[1],'unregistered');
  assert.strictEqual(listeners.message({abwrInspector:'read',record:true},{id:'outsider',tab:{id:3}}),undefined);
  const sender={id:'builtin',tab:{id:3,cookieStoreId:'firefox-container-1'},frameId:0};
  await listeners.message({abwrInspector:'identity',documentId:'doc-a'},sender);
  await listeners.message({abwrInspector:'observe',record:true},sender);
  listeners.onBeforeRequest({tabId:3,requestId:'observed',url:'https://example.org/test?token=secret',method:'POST',frameId:0,timeStamp:1});
  listeners.onHeadersReceived({tabId:3,requestId:'observed',url:'https://example.org/test',method:'POST',statusCode:403,timeStamp:2,responseHeaders:[{name:'Set-Cookie',value:'session=secret'},{name:'Content-Type',value:'text/html'}]});
  listeners.onCompleted({tabId:3,requestId:'observed',url:'https://example.org/test',method:'POST',statusCode:403,timeStamp:3});
  const observed=await listeners.message({abwrInspector:'observe'},sender);
  assert.strictEqual(observed.pendingRequests,0);assert.strictEqual(observed.events[1].status,403);
  assert.strictEqual(observed.documentId,'doc-a');assert(!JSON.stringify(observed).includes('secret'));
  assert.strictEqual((await listeners.message({abwrInspector:'observe',afterSeq:observed.nextSeq},sender)).events.length,0);
  await listeners.message({abwrInspector:'identity',documentId:'child-a'},{...sender,frameId:7});
  listeners.onBeforeRequest({tabId:3,requestId:'child-request',url:'https://example.org/child-api',method:'GET',frameId:7});
  await listeners.message({abwrInspector:'identity',documentId:'child-b'},{...sender,frameId:7});
  listeners.onHeadersReceived({tabId:3,requestId:'child-request',url:'https://example.org/child-api',method:'GET',frameId:7,statusCode:200});
  const pinned=(await listeners.message({abwrInspector:'observe'},sender)).events.find(e=>e.requestId==='child-request' && e.phase==='response');
  assert.strictEqual(pinned.documentId,'child-a','A later document replaced the request initiator identity');
  await listeners.message({abwrInspector:'observe',record:false},sender);
  assert.strictEqual((await listeners.message({abwrInspector:'observe',record:true,clear:true},sender)).pendingRequests,0,'Restart retained an unobservable pending request');
  for(let i=0;i<260;i++)listeners.onCompleted({tabId:3,requestId:'r'+i,url:'https://example.org/',method:'GET'});
  assert.strictEqual((await listeners.message({abwrInspector:'observe'},sender)).code,'BUSY');
  assert.strictEqual((await listeners.message({abwrInspector:'observe',clear:true,record:false},sender)).recording,false);
  await listeners.message({abwrInspector:'read',record:true},sender);
  listeners.onBeforeRequest({tabId:3,requestId:'r1',url:'https://user:pass@example.org/path?token=secret#private',method:'GET',type:'fetch',timeStamp:1});
  listeners.onBeforeSendHeaders({tabId:3,requestId:'r1',requestHeaders:[{name:'Authorization',value:'Bearer secret'},{name:'Cookie',value:'session=secret'},{name:'Accept',value:'text/html'}]});
  listeners.onCompleted({tabId:3,requestId:'r1',statusCode:200,timeStamp:20});
  const output=await listeners.message({abwrInspector:'read'},sender);
  assert.strictEqual(output.requests[0].url,'https://example.org/path');assert.strictEqual(output.requests[0].durationMs,19);
  assert(!JSON.stringify(output).includes('secret'));assert.strictEqual(output.requests[0].requestHeaders[2].value,'text/html');
  const other=await listeners.message({abwrInspector:'read'},{id:'builtin',tab:{id:4}});assert.strictEqual(other.requests.length,0);
  await listeners.message({abwrInspector:'read',captureBodies:true},sender);
  const body=JSON.stringify({action:'abwr_ci',nonce:'test-nonce',password:'private-password'});
  listeners.onBeforeRequest({tabId:3,requestId:'body',url:'https://example.org/api',method:'POST',type:'xmlhttprequest',timeStamp:30,
    requestBody:{raw:[{bytes:new TextEncoder().encode(body).buffer}]}});
  const stream=filters.get('body'), response=Buffer.from(JSON.stringify({echo:body,label:'中文',token:'private-token'}));
  for(const part of [response.subarray(0,17),response.subarray(17)])stream.ondata({data:Uint8Array.from(part).buffer});
  stream.onstop();
  assert(stream.closed);assert(Buffer.concat(stream.writes).equals(response),'Inspector altered the website response');
  const captured=(await listeners.message({abwrInspector:'read'},sender)).requests.find(row=>row.id==='body');
  assert(captured.requestBody.text.includes('test-nonce'));assert(captured.responseBody.text.includes('中文'));
  assert(!JSON.stringify(captured).includes('private-password'));assert(!JSON.stringify(captured).includes('private-token'));
  listeners.onBeforeRequest({tabId:3,requestId:'large',url:'https://example.org/large',method:'GET',type:'xmlhttprequest',timeStamp:40});
  const large=Buffer.alloc(80000,97), bounded=filters.get('large');bounded.ondata({data:Uint8Array.from(large).buffer});bounded.onstop();
  assert(Buffer.concat(bounded.writes).equals(large),'Preview limit truncated the website response');
  const limited=(await listeners.message({abwrInspector:'read'},sender)).requests.find(row=>row.id==='large');
  assert(limited.responseBody.truncated && limited.responseBody.text.length<=65536);
  listeners.onBeforeRequest({tabId:3,requestId:'stopped',url:'https://example.org/slow',type:'xmlhttprequest'});
  const stopped=filters.get('stopped');
  await listeners.message({abwrInspector:'read',record:false},sender);
  stopped.ondata({data:Uint8Array.from(response).buffer});stopped.onstop();
  assert(stopped.closed && Buffer.concat(stopped.writes).equals(response));
  assert(!(await listeners.message({abwrInspector:'read'},sender)).requests.find(row=>row.id==='stopped').responseBody,'Stopped recording stored a later response');
  listeners.onBeforeRequest({tabId:3,requestId:'off',url:'https://example.org/off',type:'xmlhttprequest'});
  assert(!filters.has('off'),'Disabled recording intercepted a response');
  await listeners.message({abwrInspector:'read',record:true,captureBodies:false},sender);
  for(let i=0;i<230;i++)listeners.onBeforeRequest({tabId:3,requestId:'r'+i,url:'https://example.org/',timeStamp:i});
  assert.strictEqual((await listeners.message({abwrInspector:'read'},sender)).requests.length,200);
  console.log('Sandbox registration, removal, recording scope, redaction and bounds: PASS');
})().catch(error=>{console.error(error);process.exit(1);});
