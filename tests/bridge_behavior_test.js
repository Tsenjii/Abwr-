'use strict';
const fs = require('fs');
const vm = require('vm');
const assert = require('assert');

class Element {}
class Input extends Element {
  constructor(type = 'text') { super(); this.tagName = 'INPUT'; this.type = type; this._value = ''; this.checked = false; }
  get value() { return this._value; }
  set value(v) { this._value = String(v); }
  getAttribute(name) { return name === 'name' ? 'search' : null; }
  getClientRects() { return [1]; }
  getBoundingClientRect() { return {x:1,y:2,width:90,height:20}; }
  focus() { this.focused = true; }
  scrollIntoView() { }
  dispatchEvent(e) { this.lastEvent = e.type; return true; }
  click() { this.clicked = true; if (this.type === 'checkbox') this.checked = !this.checked; }
}
class Anchor extends Element {
  constructor() { super(); this.tagName = 'A'; this.href = 'https://iana.org/'; this.innerText = 'Learn more'; }
  getAttribute(n) { return n === 'href' ? this.href : null; }
  getClientRects() { return [1]; }
  getBoundingClientRect() { return {x:1,y:2,width:80,height:20}; }
  scrollIntoView() { }
  click() { this.clicked = true; }
}
class MockEvent { constructor(type, options) { this.type = type; this.key = options?.key; } }
const input = new Input();
const checkbox = new Input('checkbox');
const anchor = new Anchor();
const messages = [];
let listener;
const port = {
  onMessage: { addListener(fn) { listener = fn; } },
  postMessage(msg) { messages.push(msg); }
};
const all = [input, checkbox, anchor];
const document = {
  body: {innerText:'Example page test text', querySelectorAll(sel) { return document.querySelectorAll(sel); }},
  documentElement: {},
  title: 'Example',
  activeElement: input,
  contains(el) { return all.includes(el); },
  querySelector(sel) { return sel === 'input' ? input : sel === 'input[type=checkbox]' ? checkbox : sel === 'a' ? anchor : null; },
  querySelectorAll(sel) {
    if (sel === '*') return all;
    if (sel === 'input') return [input, checkbox];
    if (sel === 'input[type=checkbox]') return [checkbox];
    if (sel === 'a') return [anchor];
    if (sel.includes('a,button')) return all;
    return [];
  }
};
const window = {scrollX:0,scrollY:0,
  scrollBy({top}) { this.scrollY += top; },
  scrollTo({top}) { this.scrollY = top; }
};
const ctx = {
  browser: {runtime:{connectNative() { return port; }}},
  document, window, location: {href:'https://example.org/'},
  HTMLInputElement: Input, HTMLTextAreaElement: class {}, HTMLSelectElement: class {},
  HTMLAnchorElement: Anchor, Event: MockEvent, KeyboardEvent: MockEvent,
  CSS: {escape: s=>s}, setTimeout, clearTimeout,
  MutationObserver: class {observe(){} disconnect(){}}
};
vm.runInNewContext(fs.readFileSync('app/src/main/assets/agentbridge/content.js','utf8'),ctx);
assert.strictEqual(typeof listener, 'function', 'Native port listener must be registered');
let n = 0;
async function call(action, data = {}) {
  const id = String(++n);
  await listener({id, action, ...data});
  const result = messages.find(m=>m.id===id);
  assert(result, 'No reply for '+action);
  assert.strictEqual(result.ok, true, result.error || action);
  return result.result;
}
(async()=>{
  assert.strictEqual((await call('dom')).title, 'Example');
  const reading = await call('read',{maxChars:2000});
  assert.strictEqual(reading.title, 'Example');
  assert(reading.text.includes('Example page test text'));
  assert(reading.text.length <= 2000);
  const snap = await call('snapshot');
  assert(snap.snapshot.includes('Agent-readable snapshot'));
  assert(snap.snapshot.includes('Learn more'));
  assert(snap.elements.length >= 3);
  const inputRef = snap.elements.find(e=>e.tag==='input' && e.inputType==='text').ref;
  const linkRef = snap.elements.find(e=>e.tag==='a').ref;
  await call('type',{target:inputRef,text:'snapshot-ref-test'});
  assert.strictEqual(input.value,'snapshot-ref-test');
  await call('click',{target:linkRef});
  assert.strictEqual(anchor.clicked,true);
  const second = await call('snapshot');
  assert.notStrictEqual(second.refsVersion,snap.refsVersion);
  await listener({id:'stale',action:'click',target:linkRef});
  const stale = messages.find(m=>m.id==='stale');
  assert(stale && stale.ok===false && /Stale/.test(stale.error));

  assert.strictEqual((await call('find',{selector:'a',text:'Learn'})).count,1);
  assert.strictEqual((await call('find',{role:'link',text:'Learn'})).count,1);
  assert.strictEqual((await call('find',{selector:'',text:'Learn'})).count,1);
  await call('type',{selector:'input',text:'hello'});
  assert.strictEqual(input.value,'hello');
  assert.strictEqual((await call('find',{selector:'input'})).elements[0].value,'hello');
  await call('clear',{selector:'input'});
  assert.strictEqual(input.value,'');
  await call('toggle',{selector:'input[type=checkbox]',checked:true});
  assert.strictEqual(checkbox.checked,true);
  await call('toggle',{selector:'input[type=checkbox]',checked:true});
  assert.strictEqual(checkbox.checked,true);
  await call('click',{selector:'a'});
  assert.strictEqual(anchor.clicked,true);
  await call('scroll',{y:600});
  assert.strictEqual(window.scrollY,600);
  await call('keyboard',{selector:'input',key:'Enter'});
  assert.strictEqual(input.lastEvent,'keyup');
  console.log('Bridge native-message behavior tests: PASS');
})().catch(error=>{console.error(error);process.exitCode=1;});
