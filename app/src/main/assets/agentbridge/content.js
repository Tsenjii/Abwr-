/* Privileged built-in GeckoView extension. This does not expose a web-page API. */
(() => {
  "use strict";
  let port;
  try { port = browser.runtime.connectNative("aibrowser"); }
  catch (_) { return; }

  const limited = (value, cap=15000) => String(value ?? "").slice(0, cap);
  const pick = (selector) => {
    if (typeof selector !== "string" || !selector.trim() || selector.length > 1000) {
      throw new Error("Invalid selector");
    }
    const el = document.querySelector(selector);
    if (!el) throw new Error("Element not found: " + limited(selector, 160));
    return el;
  };
  const inspect = (el) => ({
    tag: el.tagName?.toLowerCase() || "",
    text: limited((el.innerText || el.textContent || "").trim(), 320),
    name: el.getAttribute("name"),
    role: el.getAttribute("role"),
    ariaLabel: el.getAttribute("aria-label"),
    selectorHint: el.id ? `#${CSS.escape(el.id)}` : el.tagName.toLowerCase(),
    href: typeof HTMLAnchorElement !== "undefined" && el instanceof HTMLAnchorElement ? limited(el.href, 1000) : null,
    inputType: el instanceof HTMLInputElement ? el.type : null,
    rect: (() => { const r=el.getBoundingClientRect?.() || {x:0,y:0,width:0,height:0}; return {x:Math.round(r.x),y:Math.round(r.y),width:Math.round(r.width),height:Math.round(r.height)}; })(),
    visible: Boolean(el.getClientRects().length)
  });
  async function handle(msg) {
    const action = msg.action;
    switch (action) {
      case "dom": {
        const max = Math.min(100, Math.max(1, Number(msg.limit) || 40));
        const nodes = [...document.querySelectorAll("a,button,input,textarea,select,[role=button]")]
          .slice(0, max).map(inspect);
        return { url: location.href, title: document.title, text: limited(document.body?.innerText, 25000), elements: nodes };
      }
      case "links": {
        return { url:location.href, links:[...document.querySelectorAll("a[href]")].slice(0,Math.min(150,Math.max(1,Number(msg.limit)||60)))
          .map(a=>({text:limited(a.innerText,180),href:limited(a.href,1500)})) };
      }
      case "wait": {
        const selector = limited(msg.selector,1000);
        if (!selector) throw new Error("Missing selector");
        const timeout = Math.max(100, Math.min(15000, Number(msg.timeoutMs)||5000));
        const found = document.querySelector(selector);
        if (found) return {found:true, element:inspect(found)};
        return new Promise(resolve => {
          let completed = false;
          let observer;
          const finish = (result) => {
            if (completed) return; completed = true;
            observer?.disconnect(); clearTimeout(timer); resolve(result);
          };
          const timer = setTimeout(()=>finish({found:false,timeout:true}), timeout);
          observer = new MutationObserver(()=>{
            const item = document.querySelector(selector);
            if(item) finish({found:true,element:inspect(item)});
          });
          observer.observe(document.documentElement,{subtree:true,childList:true,attributes:true});
        });
      }
      case "find": {
        const list = [...document.querySelectorAll(limited(msg.selector,1000))].slice(0,100);
        return { count: list.length, elements: list.map(inspect) };
      }
      case "click": {
        const el = pick(msg.selector); el.scrollIntoView({ block:"center",inline:"nearest" }); el.click();
        return { clicked: true, selector: msg.selector };
      }
      case "focus": {
        const el = pick(msg.selector); el.focus();
        return {focused:true,selector:msg.selector};
      }
      case "select": {
        const el = pick(msg.selector);
        if (!(el instanceof HTMLSelectElement)) throw new Error("Target is not select");
        const v = limited(msg.value,1000);
        if (![...el.options].some(o=>o.value===v)) throw new Error("Option not found");
        el.value = v;
        el.dispatchEvent(new Event("input",{bubbles:true}));
        el.dispatchEvent(new Event("change",{bubbles:true}));
        return {selected:true};
      }
      case "press": {
        const el = msg.selector ? pick(msg.selector) : document.activeElement;
        const key = limited(msg.key,40);
        if (!key) throw new Error("Missing key");
        el.dispatchEvent(new KeyboardEvent("keydown",{key,bubbles:true}));
        el.dispatchEvent(new KeyboardEvent("keyup",{key,bubbles:true}));
        return {key, dispatched:true, note:"DOM synthetic keys may not trigger browser-native shortcuts"};
      }
      case "type": {
        const el = pick(msg.selector);
        if (!(el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement))
          throw new Error("Target is not an input or textarea");
        el.focus();
        const setter = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el), "value")?.set;
        if (setter) setter.call(el, limited(msg.text,20000)); else el.value = limited(msg.text,20000);
        el.dispatchEvent(new Event("input",{bubbles:true}));
        el.dispatchEvent(new Event("change",{bubbles:true}));
        return { typed: true, selector: msg.selector };
      }
      case "scroll": {
        window.scrollBy({top: Math.max(-20000, Math.min(20000, Number(msg.y)||600)), behavior:"instant"});
        return { x:window.scrollX, y:window.scrollY };
      }
      case "status":
        return {url:location.href, title:document.title, readyState:document.readyState};
      case "cookie_metadata":
        // document.cookie cannot see HttpOnly cookies; never return cookie values.
        return { names: document.cookie.split(";").map(x=>x.trim().split("=")[0]).filter(Boolean) };
      default:
        throw new Error("Unsupported action: " + limited(action,100));
    }
  }
  port.onMessage.addListener(async (msg) => {
    if (!msg || typeof msg.id !== "string") return;
    try {
      port.postMessage({ id:msg.id, ok:true, result: await handle(msg) });
    } catch (e) {
      port.postMessage({ id:msg.id, ok:false, error: limited(e?.message || e, 300) });
    }
  });
  port.postMessage({ready:true, url:location.href});
})();