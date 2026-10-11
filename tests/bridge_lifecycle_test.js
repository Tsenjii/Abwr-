'use strict';
const fs = require('fs');
const vm = require('vm');
const assert = require('assert');
const source = fs.readFileSync('app/src/main/assets/agentbridge/content.js', 'utf8');

function fixture(failConnections = false) {
  let clock = 0, nextTimer = 0, connects = 0;
  const timers = new Map(), events = {}, ports = [], observers = [];
  class Input {
    constructor() { this.tagName = 'INPUT'; this.type = 'text'; this._value = ''; }
    get value() { return this._value; }
    set value(v) { this._value = v; }
    getAttribute() { return null; }
    getClientRects() { return this.hidden ? [] : [1]; }
    focus() {}
    scrollIntoView() {}
    dispatchEvent() { return true; }
    click() { this.clicked = true; }
  }
  const input = new Input();
  const editor = Object.assign(new Input(), {tagName:'DIV', isContentEditable:true, textContent:''});
  // Ensure the editor takes the actual non-input branch.
  Object.setPrototypeOf(editor, {
    getAttribute() { return null; }, getClientRects() { return [1]; },
    focus() {}, dispatchEvent() { return true; }
  });
  const ctx = {
    browser:{runtime:{connectNative() {
      connects++;
      if (failConnections) throw new Error('not ready');
      const p = {messages:[], onMessage:{addListener(fn) { p.receive = fn; }},
        onDisconnect:{addListener(fn) { p.disconnected = fn; }},
        postMessage(msg) { p.messages.push(msg); },
        disconnect() { if (!p.closed) { p.closed = true; p.disconnected?.(); } }};
      ports.push(p); return p;
    }}},
    location:{href:'https://example.org/'},
    document:{title:'Test', body:{innerText:'hello', querySelectorAll() { return [input]; }}, documentElement:{},
      contains(el) { return el === input; },
      querySelector(sel) { return sel === 'input' ? input : sel === '#editor' ? editor : null; },
      querySelectorAll() { return [input]; }},
    window:{scrollY:12, scrollX:0, addEventListener(name, fn) { events[name] = fn; },
      scrollBy({top}) { this.scrollY += top; }},
    HTMLInputElement:Input, HTMLTextAreaElement:class {}, HTMLSelectElement:class {},
    Event:class { constructor(type) { this.type = type; } }, CSS:{escape:s=>s},
    setTimeout(fn, delay) { const id = ++nextTimer; timers.set(id, {fn, due:clock+delay}); return id; },
    clearTimeout(id) { timers.delete(id); },
    MutationObserver:class {
      constructor(fn) { this.fn = fn; this.closed = false; observers.push(this); }
      observe() {} disconnect() { this.closed = true; }
    }
  };
  vm.runInNewContext(source, ctx);
  return {ctx, input, editor, timers, events, ports, observers,
    get connects() { return connects; },
    succeed() { failConnections = false; },
    next() {
      const entry = [...timers].sort((a,b)=>a[1].due-b[1].due)[0];
      assert(entry, 'expected timer'); timers.delete(entry[0]); clock = entry[1].due; entry[1].fn();
    }};
}

(async () => {
  const failed = fixture(true);
  while (failed.timers.size) failed.next();
  assert.strictEqual(failed.connects, 8, 'retry cycle must be bounded');
  failed.succeed(); failed.events.pageshow();
  assert.strictEqual(failed.ports.length, 1, 'pageshow restores without reloading');

  const f = fixture(), original = f.ports[0];
  await original.receive({bridgeReady:true});
  await original.receive({id:'snapshot', action:'snapshot'});
  assert.strictEqual(original.messages.find(m=>m.id==='snapshot').ok, true);
  const ref = original.messages.find(m=>m.id==='snapshot').result.elements[0].ref;
  f.ctx.location.href += '#other';
  await original.receive({id:'stale', action:'click', target:ref});
  assert.strictEqual(original.messages.find(m=>m.id==='stale').code, 'STALE_REF');
  assert(!f.input.clicked);
  await original.receive({id:'changed', action:'type', selector:'input', text:'wrong page', expectedUrl:'https://example.org/'});
  assert.strictEqual(original.messages.find(m=>m.id==='changed').code, 'PAGE_CHANGED');
  assert.strictEqual(f.input.value, '');
  await original.receive({id:'zero', action:'scroll', y:0});
  assert.strictEqual(f.ctx.window.scrollY, 12, 'zero delta must not scroll');
  await original.receive({id:'editor', action:'type', selector:'#editor', text:'hello editor'});
  assert.strictEqual(f.editor.textContent, 'hello editor');
  f.input.readOnly = true;
  await original.receive({id:'readonly', action:'type', selector:'input', text:'invalid'});
  assert.strictEqual(original.messages.find(m=>m.id==='readonly').code, 'TARGET_NOT_EDITABLE');

  const wait = original.receive({id:'wait', action:'wait', selector:'#later', timeoutMs:500});
  original.disconnect();
  await wait;
  assert(f.observers.every(o=>o.closed), 'disconnection must release DOM observers');
  assert(!original.messages.some(m=>m.id==='wait'), 'disconnected port must not receive a late reply');
  f.next();
  const replacement = f.ports[1];
  assert(replacement);
  assert(!replacement.messages.some(m=>m.id==='wait'), 'old reply must not cross to the new port');
  await original.receive({id:'old-port', action:'click', selector:'input'});
  assert(!f.input.clicked, 'replaced port cannot execute new work');
  const timeout = replacement.receive({id:'timeout', action:'wait', selector:'#missing', timeoutMs:100});
  f.next(); await timeout;
  assert.strictEqual(replacement.messages.find(m=>m.id==='timeout').code, 'WAIT_TIMEOUT');
  f.events.pagehide();
  assert.strictEqual(f.timers.size, 0, 'suspended documents must not reconnect');
  f.events.pageshow();
  assert.strictEqual(f.ports.length, 3, 'BFCache restore needs a fresh native port');
  console.log('Bridge lifecycle, stale target, editor and timeout tests: PASS');
})().catch(error => { console.error(error); process.exitCode = 1; });
