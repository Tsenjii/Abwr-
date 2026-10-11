/* Privileged built-in GeckoView extension. This does not expose a web-page API. */
(() => {
  "use strict";
  let port;
  try { port = browser.runtime.connectNative("aibrowser"); }
  catch (_) { return; }

  const limited = (value, cap=15000) => String(value ?? "").slice(0, cap);
  // Snapshot refs deliberately live only until the next snapshot. No secrets cross
  // the bridge: inputs of type=password are always redacted.
  // Unique across document reloads, unlike a plain zero-based counter.
  // Up to 100 snapshots within the same millisecond retain unique ref numbers.
  let snapshotVersion = Date.now() * 100;
  const snapshotRefs = new Map();
  const consoleEvents = [];
  const logError = (kind, message) => {
    consoleEvents.push({kind, message:limited(message,400), timeMs:Date.now()});
    if (consoleEvents.length > 40) consoleEvents.shift();
  };
  window.addEventListener?.("error", event => logError("error", event.message || "Script error"));
  window.addEventListener?.("unhandledrejection", event => {
    const reason = event.reason;
    logError("unhandledrejection", reason?.message || typeof reason || "Rejected promise");
  });
  const getRef = (target) => {
    if (typeof target !== "string") return null;
    if (!/^e[0-9]+_[0-9]+$/.test(target)) return null;
    const el = snapshotRefs.get(target);
    if (!el || !document.contains(el))
      throw new Error("Stale or unknown snapshot ref: " + target + ". Request a new snapshot.");
    return el;
  };
  const pick = (selector) => {
    const referenced = getRef(selector);
    if (referenced) return referenced;
    if (typeof selector !== "string" || !selector.trim() || selector.length > 1000) {
      throw new Error("Invalid selector");
    }
    const el = document.querySelector(selector);
    if (!el) throw new Error("Element not found: " + limited(selector, 160));
    return el;
  };
  const semanticRole = (el) => el.getAttribute("role") || ({
    A:"link", BUTTON:"button", INPUT:el.type==="checkbox"?"checkbox":el.type==="radio"?"radio":"textbox",
    TEXTAREA:"textbox", SELECT:"combobox", H1:"heading", H2:"heading", H3:"heading", H4:"heading"
  })[el.tagName] || "generic";
  const accessibleLabel = el => limited(el.getAttribute("aria-label") ||
    el.getAttribute("placeholder") || el.getAttribute("title") ||
    el.innerText || el.textContent || "", 160).replace(/\s+/g, " ").trim();
  const inspect = (el) => ({
    tag: el.tagName?.toLowerCase() || "",
    text: limited((el.innerText || el.textContent || "").trim(), 320),
    name: el.getAttribute("name"),
    role: semanticRole(el),
    ariaLabel: el.getAttribute("aria-label"),
    selectorHint: el.id ? `#${CSS.escape(el.id)}` : el.tagName.toLowerCase(),
    href: typeof HTMLAnchorElement !== "undefined" && el instanceof HTMLAnchorElement ? limited(el.href, 1000) : null,
    inputType: el instanceof HTMLInputElement ? el.type : null,
    value: el instanceof HTMLInputElement
      ? (el.type === "password" ? null : limited(el.value, 300))
      : (el instanceof HTMLTextAreaElement || el instanceof HTMLSelectElement
         ? limited(el.value, 300) : null),
    checked: el instanceof HTMLInputElement && ["checkbox","radio"].includes(el.type) ? el.checked : null,
    rect: (() => { const r=el.getBoundingClientRect?.() || {x:0,y:0,width:0,height:0}; return {x:Math.round(r.x),y:Math.round(r.y),width:Math.round(r.width),height:Math.round(r.height)}; })(),
    visible: Boolean(el.getClientRects().length)
  });
  async function handle(msg) {
    const action = msg.action;
    switch (action) {
      case "snapshot": {
        const limit = Math.min(150, Math.max(1, Number(msg.limit) || 80));
        const scope = msg.selector ? pick(limited(msg.selector, 1000)) : document.body;
        const selector = "a,button,input,textarea,select,[role],h1,h2,h3,h4";
        const nodes = Array.from(scope?.querySelectorAll(selector) || []).slice(0, limit);
        snapshotVersion += 1;
        snapshotRefs.clear();
        const lines = [
          "Page: " + limited(document.title, 250) + " | " + limited(location.href, 1000),
          "Agent-readable snapshot (refs valid until next snapshot or navigation):"
        ];
        const elements = [];
        nodes.forEach((el, index) => {
          const ref = "e" + snapshotVersion + "_" + (index+1);
          snapshotRefs.set(ref, el);
          const role = semanticRole(el);
          const name = accessibleLabel(el);
          const value = el instanceof HTMLInputElement && el.type === "password"
            ? "[redacted]"
            : (el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement ||
               el instanceof HTMLSelectElement ? limited(el.value, 100) : "");
          const suffix = el instanceof HTMLInputElement && ["checkbox", "radio"].includes(el.type)
            ? (el.checked ? " [checked]" : " [unchecked]") : "";
          const desc = role + " \"" + name.replace(/"/g, "'") + "\"" + suffix +
            (value ? " value=\"" + value.replace(/"/g, "'") + "\"" : "");
          lines.push("  [" + ref + "] " + limited(desc, 320));
          elements.push({ref, accessibleName:name, ...inspect(el)});
        });
        return {url:location.href, title:document.title, snapshot:lines.join("\n"),
          elements, refsVersion:snapshotVersion, truncated:nodes.length>=limit};
      }
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
        const selector = limited(msg.selector || "", 1000);
        const wantedText = limited(msg.text || "", 250);
        if (!selector && !wantedText) throw new Error("Missing selector or text");
        const timeout = Math.max(100, Math.min(10000, Number(msg.timeoutMs)||5000));
        const locate = () => selector ? document.querySelector(selector)
          : (limited(document.body?.innerText, 25000).includes(wantedText) ? document.body : null);
        const found = locate();
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
            const item = locate();
            if(item) finish({found:true,element:inspect(item)});
          });
          observer.observe(document.documentElement,{subtree:true,childList:true,characterData:true,attributes:true});
        });
      }
      case "find": {
        // Optional CSS / visible text / role / accessible label filters.
        const sel = limited(msg.target || msg.selector || "*", 1000);
        const query = limited(msg.text, 300).toLowerCase();
        const role = limited(msg.role, 80);
        const label = limited(msg.ariaLabel, 200).toLowerCase();
        const list = [...document.querySelectorAll(sel)].filter(el =>
          (!query || limited(el.innerText || el.textContent, 3000).toLowerCase().includes(query)) &&
          (!role || el.getAttribute("role") === role) &&
          (!label || limited(el.getAttribute("aria-label"), 400).toLowerCase().includes(label))
        ).slice(0,100);
        return {count:list.length, elements:list.map(inspect)};
      }
      case "click": {
        const el = pick(msg.target || msg.selector); el.scrollIntoView({ block:"center",inline:"nearest" }); el.click();
        return { clicked: true, target: msg.target || msg.selector };
      }
      case "clear": {
        const el = pick(msg.target || msg.selector);
        if (!(el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement))
          throw new Error("Target is not a text input");
        if (el.type === "file") throw new Error("Cannot clear file input via automation");
        el.focus();
        const setter = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el), "value")?.set;
        if (setter) setter.call(el, ""); else el.value = "";
        el.dispatchEvent(new Event("input",{bubbles:true}));
        el.dispatchEvent(new Event("change",{bubbles:true}));
        return {cleared:true, selector:msg.selector};
      }
      case "toggle": {
        const el = pick(msg.target || msg.selector);
        if (!(el instanceof HTMLInputElement) || !["checkbox","radio"].includes(el.type))
          throw new Error("Target is not a checkbox or radio");
        if (typeof msg.checked === "boolean" && el.checked === msg.checked)
          return {checked:el.checked, unchanged:true};
        el.click();
        return {checked:el.checked};
      }
      case "focus": {
        const el = pick(msg.target || msg.selector); el.focus();
        return {focused:true,selector:msg.selector};
      }
      case "select": {
        const el = pick(msg.target || msg.selector);
        if (!(el instanceof HTMLSelectElement)) throw new Error("Target is not select");
        const v = limited(msg.value,1000);
        if (![...el.options].some(o=>o.value===v)) throw new Error("Option not found");
        el.value = v;
        el.dispatchEvent(new Event("input",{bubbles:true}));
        el.dispatchEvent(new Event("change",{bubbles:true}));
        return {selected:true};
      }
      case "keyboard": {
        const el = msg.target || msg.selector ? pick(msg.target || msg.selector) : document.activeElement;
        const key = limited(msg.key,40);
        if (!["Enter","Tab","Escape","ArrowUp","ArrowDown","ArrowLeft","ArrowRight"].includes(key))
          throw new Error("Unsupported keyboard key");
        el.dispatchEvent(new KeyboardEvent("keydown",{key,bubbles:true}));
        el.dispatchEvent(new KeyboardEvent("keyup",{key,bubbles:true}));
        return {key,dispatched:true, trusted:false,
          note:"Synthetic keyboard events cannot trigger browser-native shortcuts or every website handler"};
      }
      case "scrollTo": {
        if (msg.target || msg.selector) pick(msg.target || msg.selector).scrollIntoView({block:"center",behavior:"instant"});
        else window.scrollTo({top:msg.position==="bottom" ? document.documentElement.scrollHeight : 0,behavior:"instant"});
        return {x:window.scrollX,y:window.scrollY};
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
        const el = pick(msg.target || msg.selector);
        if (!(el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement))
          throw new Error("Target is not an input or textarea");
        el.focus();
        const setter = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el), "value")?.set;
        if (setter) setter.call(el, limited(msg.text,20000)); else el.value = limited(msg.text,20000);
        el.dispatchEvent(new Event("input",{bubbles:true}));
        el.dispatchEvent(new Event("change",{bubbles:true}));
        if (msg.submit === true) {
          const form = el.form || el.closest("form");
          if (!form || typeof form.requestSubmit !== "function")
            throw new Error("No submitting form is associated with the target input");
          form.requestSubmit();
          return {typed:true, submitted:true};
        }
        return { typed: true, target: msg.target || msg.selector };
      }
      case "scroll": {
        window.scrollBy({top: Math.max(-20000, Math.min(20000, Number(msg.y)||600)), behavior:"instant"});
        return { x:window.scrollX, y:window.scrollY };
      }
      case "status":
        return {url:location.href, title:document.title, readyState:document.readyState};
      case "cookie_metadata":
        // document.cookie cannot see HttpOnly cookies; never return cookie values.
        return { origin: location.origin, httpOnlyIncluded:false,
          names: document.cookie.split(";").map(x=>x.trim().split("=")[0]).filter(Boolean) };
      case "storage_inspect": {
        // Same-origin per container only. Limit output and redact likely credentials;
        // never return raw auth cookie values, passwords, tokens or entire browser DB.
        const inspectStorage = (store) => {
          const items = [];
          for (let i = 0; i < Math.min(store.length, 80); i++) {
            const key = store.key(i);
            if (typeof key !== "string") continue;
            const val = store.getItem(key) || "";
            const secret = /token|auth|session|secret|password|credential|bearer|csrf|jwt|cookie|oauth|key/i.test(key) ||
              /^(eyJ[A-Za-z0-9_-]{15,}\.|Bearer\s+)/.test(val);
            items.push({key:limited(key,100), length:val.length,
              value:secret ? "[redacted]" : limited(val, 350)});
          }
          return {count:store.length, truncated:store.length>80, items};
        };
        return {origin:location.origin, local:inspectStorage(localStorage),
          session:inspectStorage(sessionStorage),
          note:"Values resembling credentials are always redacted; these APIs cannot access other origins."};
      }
      case "devtools": {
        const timing = typeof performance !== "undefined" && performance.getEntriesByType ?
          performance.getEntriesByType("resource").slice(-40).map(item=>({
            name:limited((() => {
              try { const u=new URL(item.name); return u.origin+u.pathname; }
              catch (_) { return "[unknown URL]"; }
            })(),250),
            type:limited(item.initiatorType,30),
            durationMs:Math.round(item.duration || 0),
            transferSize:item.transferSize || 0
          })) : [];
        return {
          url:location.href, title:document.title, readyState:document.readyState,
          viewport:{width:window.innerWidth||0,height:window.innerHeight||0},
          domNodes:document.querySelectorAll("*").length,
          scripts:document.scripts?.length || 0,
          errors:consoleEvents.slice(-30),
          resourceTimings:timing,
          cookieCount:document.cookie ? document.cookie.split(";").filter(Boolean).length : 0,
          note:"Page-level diagnostics only; not Chrome DevTools Protocol, full HTTP traffic, or HttpOnly cookies."
        };
      }
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