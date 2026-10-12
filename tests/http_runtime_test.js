'use strict';
const assert=require('assert'),fs=require('fs'),vm=require('vm');
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
let fetched=[];
const page={AbortController,async fetch(url,init) {
  fetched.push({url,init});let index=0;
  return {url,status:200,redirected:false,type:'basic',headers:new Map([['content-type','text/event-stream'],['authorization','secret']]),
    body:{getReader(){return {async read(){const current=index++;if(current===0)return {done:false,value:new Uint8Array([0,1,255])};if(current===1){await delay(60);return {done:false,value:new Uint8Array([65,66])};}return {done:true};},releaseLock(){}};}}};
}};
const context={URL,Uint8Array,btoa,atob,setTimeout,clearTimeout,location:{origin:'https://example.org'},
  window:{wrappedJSObject:page,addEventListener(){}},cloneInto:value=>structuredClone(value)};
vm.runInNewContext(fs.readFileSync('app/src/main/assets/agentbridge/http.js','utf8'),context);
(async()=>{
  const result=await context.__abwrHttp.request({url:'https://example.org/api',method:'POST',body:'{"message":"hi"}',httpStreamId:'plain',maxBytes:100},()=>{});
  assert.strictEqual(fetched[0].init.credentials,'include');assert.strictEqual(fetched[0].init.body,'{"message":"hi"}');
  assert.deepStrictEqual([...Buffer.from(result.body,'base64')],[0,1,255,65,66]);assert.strictEqual(result.headers.authorization,'[redacted]');
  const events=[];const started=Date.now();
  const ack=await context.__abwrHttp.request({url:'https://example.org/sse',stream:true,httpStreamId:'stream',maxBytes:100},event=>events.push({event,time:Date.now()-started}));
  assert.strictEqual(ack.streaming,true);assert(!events.some(x=>x.event.type==='complete'),'returns before upstream finishes');
  await delay(90);assert.strictEqual(events.at(-1).event.type,'complete');
  const chunks=events.filter(x=>x.event.type==='chunk');assert(chunks[1].time-chunks[0].time>=40,'chunks follow real arrival times');
  assert.deepStrictEqual([...Buffer.concat(chunks.map(x=>Buffer.from(x.event.data,'base64')))],[0,1,255,65,66]);
  await assert.rejects(()=>context.__abwrHttp.request({url:'https://other.example/api',httpStreamId:'cross'},()=>{}),/document origin/);
  assert.strictEqual(fetched.length,2,'cross-origin cookie request never dispatched');
  await assert.rejects(()=>context.__abwrHttp.request({url:'https://example.org/large',httpStreamId:'large',maxBytes:1},()=>{}),/maxBytes/);
  console.log('Page HTTP POST, cookie scope, binary bytes, real incremental stream and limits: PASS');
})().catch(error=>{console.error(error);process.exit(1);});
