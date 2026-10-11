'use strict';
const assert = require('assert');
const fs = require('fs');
const vm = require('vm');

class Element {
  constructor(tag, name, options = {}) {
    Object.assign(this, {tagName:tag, innerText:name, textContent:name, style:{}, isConnected:true}, options);
  }
  getAttribute(key) { return this[key] ?? null; }
  getClientRects() { return this.hidden ? [] : [1]; }
  getBoundingClientRect() { return {x:10,y:20,top:20,bottom:60,width:80,height:40}; }
  click() { this.clicked = true; }
  scrollIntoView() {}
  focus() { this.focused = true; }
  matches() { return Boolean(this.disabled); }
  dispatchEvent() { return true; }
}
class Input extends Element {}
const links = Array.from({length:180}, (_, i) => new Element('A', 'Navigation '+i));
const button = new Element('BUTTON', 'Continue with test provider');
const cssHidden = new Element('BUTTON', 'Hidden button', {style:{visibility:'hidden'}});
const disabled = new Input('INPUT', '', {type:'checkbox', disabled:true, checked:false});
const field = new Input('INPUT', '', {type:'text', value:'', labels:[{innerText:'Associated field label'}]});
const shadowButton = new Element('BUTTON', 'Shadow action', {id:'shadow-button'});
const shadowRoot = {querySelectorAll() { return [shadowButton]; }};
const shadowHost = new Element('DIV', '', {shadowRoot});
const custom = new Input('INPUT', '', {id:'custom', type:'checkbox', checked:false, style:{opacity:'0'}});
const label = new Element('LABEL', 'Custom checkbox', {id:'custom-label'});
label.click = () => { custom.checked = !custom.checked; };
custom.labels = [label];
const customDisabled = new Input('INPUT', '', {id:'custom-disabled', type:'checkbox', checked:false, disabled:true, style:{opacity:'0'}, labels:[label]});
const hiddenUnlabelled = new Input('INPUT', '', {id:'unlabelled', type:'checkbox', checked:false, style:{opacity:'0'}});
const invisibleLabel = new Element('LABEL', 'Invisible checkbox label', {style:{display:'none'}});
const hiddenLabelled = new Input('INPUT', '', {id:'invisible-label', type:'checkbox', checked:false, style:{opacity:'0'}, labels:[invisibleLabel]});
const elements = [...links, button, cssHidden, disabled, field, shadowHost, custom, customDisabled, hiddenUnlabelled, hiddenLabelled];
const document = {
  title:'Coverage', body:{innerText:'ordinary page', querySelectorAll() { return elements; }},
  documentElement:{}, contains(el) { return elements.includes(el); },
  querySelector(sel) {
    if (sel === '#disabled') return disabled;
    if (sel === '#hidden') return cssHidden;
    if (sel === '#field') return field;
    if (sel === '#custom') return custom;
    if (sel === '#custom-disabled') return customDisabled;
    if (sel === '#unlabelled') return hiddenUnlabelled;
    if (sel === '#invisible-label') return hiddenLabelled;
    if (sel.includes('baxia') && this.challenge) return {};
    return null;
  },
  querySelectorAll(selector) { if (selector === '#shadow-button') return []; return elements; }
};
let receive;
const replies = [];
const port = {onMessage:{addListener(fn) { receive=fn; }},postMessage(msg) { replies.push(msg); }};
const ctx = {document, window:{innerWidth:360,innerHeight:700,getComputedStyle:el=>el.style},
  location:{href:'https://example.org/',origin:'https://example.org',hostname:'example.org',pathname:'/'},
  browser:{runtime:{connectNative:()=>port}}, HTMLInputElement:Input,
  HTMLTextAreaElement:class {}, HTMLSelectElement:class {},
  CSS:{escape:x=>x}, Event:class {}, URL, setTimeout,clearTimeout};
vm.runInNewContext(fs.readFileSync('app/src/main/assets/agentbridge/content.js','utf8'),ctx);
receive({bridgeReady:true});
let nextId=0;
async function call(action, args={}) {
  const id=String(++nextId); await receive({id,action,...args});
  return replies.find(item=>item.id===id);
}
(async()=>{
  const snapshot=await call('snapshot',{limit:80});
  assert(snapshot.ok);
  assert(snapshot.result.elements.some(el=>el.accessibleName==='Continue with test provider'), 'important button must survive 180 preceding navigation links');
  assert(snapshot.result.elements.some(el=>el.accessibleName==='Associated field label'));
  assert(snapshot.result.elements.some(el=>el.accessibleName==='Shadow action'));
  assert(!snapshot.result.elements.some(el=>el.accessibleName==='Hidden button'));
  assert(snapshot.result.truncated && snapshot.result.nextOffset===80);
  const proxy = snapshot.result.elements.find(el=>el.selectorHint==='#custom');
  assert(proxy && proxy.role==='checkbox' && !proxy.visible && proxy.interactable);
  assert.strictEqual(proxy.interactionProxy.reason,'associated_visible_label');
  assert.strictEqual((await call('toggle',{target:proxy.ref,checked:true})).result.checked,true);
  assert.strictEqual((await call('toggle',{target:proxy.ref,checked:true})).result.unchanged,true, 'idempotent set must not click twice');
  assert.strictEqual((await call('click',{target:proxy.ref})).result.checked,false);
  for (const selector of ['#custom-disabled','#unlabelled','#invisible-label'])
    assert.strictEqual((await call('toggle',{selector,checked:true})).code,'TARGET_NOT_INTERACTABLE');
  assert.strictEqual(custom.checked,false,'negative cases must not click an unrelated visible label');
  assert(!snapshot.result.elements.some(el=>el.selectorHint==='#unlabelled' || el.selectorHint==='#invisible-label'));
  const ref=snapshot.result.elements.find(el=>el.accessibleName==='Shadow action').ref;
  assert((await call('click',{target:ref})).ok, 'connected shadow element is a valid snapshot target');
  assert(shadowButton.clicked);
  assert.strictEqual((await call('toggle',{selector:'#disabled'})).code,'TARGET_NOT_INTERACTABLE');
  assert.strictEqual(disabled.checked,false);
  assert.strictEqual((await call('click',{selector:'#hidden'})).code,'TARGET_NOT_INTERACTABLE');
  const more=await call('snapshot',{limit:300,includeHidden:true});
  assert(more.result.elements.some(el=>el.accessibleName==='Hidden button' && el.visible===false));
  assert.strictEqual(more.result.truncated,false);
  assert.strictEqual((await call('click',{target:ref})).code,'STALE_REF');
  assert.strictEqual((await call('dom',{expectedUrl:'https://EXAMPLE.org:443'})).ok,true);
  assert.strictEqual((await call('dom',{expectedDocumentId:'other'})).code,'PAGE_CHANGED');
  document.challenge=true;
  const challenge=await call('compatibility');
  assert.strictEqual(challenge.result.challenge.present,true);
  assert.strictEqual(challenge.result.challenge.absenceProven,false);
  console.log('Snapshot coverage, pagination, labels, shadow roots, hidden/disabled controls and document identity: PASS');
})().catch(error=>{console.error(error);process.exitCode=1;});
