/* Privileged built-in GeckoView extension. This does not expose a web-page API. */
(() => {
  "use strict";
  let port = null;
  let reconnectTimer = null;
  let acknowledgementTimer = null;
  let reconnectAttempts = 0;
  let suspended = false;
  const activeWaits = new Set();
  const failure = (code, message) => Object.assign(new Error(message), {code});
  const documentId = "d" + (typeof crypto !== "undefined" && crypto.randomUUID
    ? crypto.randomUUID() : Date.now().toString(36) + Math.random().toString(36).slice(2));
  const sameUrl = (a, b) => {
    try { return new URL(a).href === new URL(b).href; } catch (_) { return a === b; }
  };
  const viewport = () => ({width:window.innerWidth || 0, height:window.innerHeight || 0,
    devicePixelRatio:window.devicePixelRatio || 1,
    visual:window.visualViewport ? {width:window.visualViewport.width, height:window.visualViewport.height,
      offsetLeft:window.visualViewport.offsetLeft, offsetTop:window.visualViewport.offsetTop,
      scale:window.visualViewport.scale} : null});
  const cancelWaits = () => {
    for (const cancel of [...activeWaits]) cancel();
  };

  const limited = (value, cap=15000) => String(value ?? "").slice(0, cap);
  // Snapshot refs deliberately live only until the next snapshot. No secrets cross
  // the bridge: inputs of type=password are always redacted.
  // Unique across document reloads, unlike a plain zero-based counter.
  // Up to 100 snapshots within the same millisecond retain unique ref numbers.
  let snapshotVersion = Date.now() * 100 + Math.floor(Math.random() * 100);
  const snapshotRefs = new Map();
  let snapshotUrl = location.href;
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
    if (!/^e[a-z0-9]+(?:_[0-9]+){1,2}$/i.test(target)) return null;
    const el = snapshotRefs.get(target);
    if (!el || !(el.isConnected === true || document.contains(el)) || snapshotUrl !== location.href)
      throw failure("STALE_REF", "Stale or unknown snapshot ref: " + target + ". Request a new snapshot.");
    return el;
  };
  const pick = (selector) => {
    const referenced = getRef(selector);
    if (referenced) return referenced;
    if (typeof selector !== "string" || !selector.trim() || selector.length > 1000) {
      throw failure("BAD_ARGUMENTS", "A non-empty target ref or CSS selector is required");
    }
    let el;
    try { el = document.querySelector(selector) || candidates(document, selector)[0]; }
    catch (_) { throw failure("INVALID_SELECTOR", "Invalid CSS selector"); }
    if (!el) throw failure("ELEMENT_NOT_FOUND", "Element not found: " + limited(selector, 160));
    return el;
  };
  const semanticRole = (el) => el.getAttribute("role") || (el.isContentEditable ? "textbox" : null) || ({
    A:"link", BUTTON:"button", INPUT:el.type==="checkbox"?"checkbox":el.type==="radio"?"radio":
      ["button","submit","reset","image"].includes(el.type)?"button":el.type==="file"?"file":"textbox",
    TEXTAREA:"textbox", SELECT:"combobox", SUMMARY:"button", IFRAME:"frame",
    H1:"heading", H2:"heading", H3:"heading", H4:"heading"
  })[el.tagName] || "generic";
  const accessibleLabel = el => limited(el.getAttribute("aria-label") ||
    (el.getAttribute("aria-labelledby") || "").split(/\s+/).map(id => document.getElementById?.(id)?.textContent || "").join(" ").trim() ||
    Array.from(el.labels || []).map(label => label.innerText || label.textContent || "").join(" ").trim() ||
    el.getAttribute("placeholder") || el.getAttribute("title") || el.getAttribute("alt") ||
    (["button","submit","reset"].includes(el.type) ? el.value : "") ||
    el.innerText || el.textContent || el.getAttribute("name") || "", 160).replace(/\s+/g, " ").trim();
  const interactiveSelector = "a,button,input,textarea,select,summary,iframe,[role],[contenteditable]:not([contenteditable=false]),[onclick],h1,h2,h3,h4";
  // Open shadow roots are part of the inspection scope. Closed roots remain opaque.
  const candidates = (scope, selector) => {
    const result = [], roots = [scope], seen = new Set();
    let scanned = 0;
    while (roots.length && scanned < 12000 && result.length < 6000) {
      const root = roots.shift(); if (!root || seen.has(root)) continue;
      seen.add(root);
      let matched;
      try { matched = root.querySelectorAll?.(selector) || []; }
      catch (_) { throw failure("INVALID_SELECTOR", "Invalid CSS selector"); }
      for (const el of Array.from(matched).slice(0, 6000 - result.length)) result.push(el);
      for (const host of Array.from(root.querySelectorAll?.("*") || []).slice(0, 12000 - scanned)) {
        scanned++;
        if (host.shadowRoot) roots.push(host.shadowRoot);
      }
    }
    return [...new Set(result)];
  };
  const isVisible = el => {
    if (!el.getClientRects?.().length || el.hidden || el.type === "hidden") return false;
    if (typeof window.getComputedStyle === "function") {
      let ancestor = el;
      for (let depth = 0; ancestor && depth < 64; depth++) {
        const style = window.getComputedStyle(ancestor);
        if (ancestor.hidden || style.display === "none" || ["hidden","collapse"].includes(style.visibility) || style.opacity === "0") return false;
        ancestor = ancestor.parentElement || ancestor.getRootNode?.().host;
      }
    }
    return true;
  };
  const isSensitiveInput = (el) =>
    el instanceof HTMLInputElement && (
      el.type === "password" ||
      /(?:password|passwd|passcode|secret|token|session|credential|otp|2fa|one.time.code|verification)/i
        .test([el.name, el.id, el.autocomplete, el.getAttribute("aria-label")].filter(Boolean).join(" ")));
  const isDisabled = el => Boolean(el.disabled || el.matches?.(":disabled") || el.getAttribute("aria-disabled") === "true");
  // Custom checkboxes often hide the native input. Only its actual associated
  // visible label may act as a proxy; unrelated wrappers and hidden inputs stay blocked.
  const toggleProxy = el => el instanceof HTMLInputElement && ["checkbox","radio"].includes(el.type) && !isVisible(el)
    ? Array.from(el.labels || []).find(label => isVisible(label) && !isDisabled(label)) || null : null;
  const clickTarget = el => {
    const target = isVisible(el) ? el : toggleProxy(el);
    if (!target || isDisabled(el) || isDisabled(target))
      throw failure("TARGET_NOT_INTERACTABLE", "Target is hidden or disabled; inspect the page before retrying");
    return target;
  };
  const assertInteractable = el => {
    if (!isVisible(el) || isDisabled(el))
      throw failure("TARGET_NOT_INTERACTABLE", "Target is hidden or disabled; inspect the page before retrying");
  };
  const fill = (el, text) => {
    assertInteractable(el);
    if (el.readOnly) throw failure("TARGET_NOT_EDITABLE", "Target is read-only");
    if (text.length > 20000) throw failure("TEXT_TOO_LARGE", "Text exceeds the 20000-character input limit");
    el.focus();
    if (el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement) {
      if (el.type === "file") throw failure("TARGET_NOT_EDITABLE", "File input requires the owner's document picker");
      const prototype = el instanceof HTMLInputElement ? HTMLInputElement.prototype : HTMLTextAreaElement.prototype;
      const setter = Object.getOwnPropertyDescriptor(prototype, "value")?.set;
      if (setter) setter.call(el, text); else el.value = text;
    } else if (el.isContentEditable) {
      el.textContent = text;
    } else throw failure("TARGET_NOT_EDITABLE", "Target is not an input, textarea or contenteditable editor");
    el.dispatchEvent(typeof InputEvent === "function"
      ? new InputEvent("input", {bubbles:true, inputType:"insertText", data:text})
      : new Event("input", {bubbles:true}));
    el.dispatchEvent(new Event("change", {bubbles:true}));
  };
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
      ? (isSensitiveInput(el) ? "[redacted]" : limited(el.value, 300))
      : (el instanceof HTMLTextAreaElement || el instanceof HTMLSelectElement
         ? limited(el.value, 300) : null),
    checked: el instanceof HTMLInputElement && ["checkbox","radio"].includes(el.type) ? el.checked : null,
    disabled: isDisabled(el),
    readOnly: Boolean(el.readOnly),
    src: el.tagName === "IFRAME" ? limited(el.src || el.getAttribute("src"), 1200) : null,
    options: el instanceof HTMLSelectElement ? Array.from(el.options).slice(0, 60)
      .map(o => ({value:limited(o.value,300),text:limited(o.text || o.textContent,160),disabled:Boolean(o.disabled)})) : undefined,
    rect: (() => { const r=(toggleProxy(el) || el).getBoundingClientRect?.() || {x:0,y:0,width:0,height:0}; return {x:Math.round(r.x),y:Math.round(r.y),width:Math.round(r.width),height:Math.round(r.height)}; })(),
    visible: isVisible(el),
    interactable: !isDisabled(el) && Boolean(isVisible(el) || toggleProxy(el)),
    interactionProxy: (() => { const label=toggleProxy(el); return label ? {
      tag:"label", selectorHint:label.id ? "#"+CSS.escape(label.id) : null,
      reason:"associated_visible_label", actions:["click","toggle"]} : null; })()
  });
  async function handle(msg) {
    if (typeof msg.expectedDocumentId === "string" && msg.expectedDocumentId !== documentId)
      throw failure("PAGE_CHANGED", "Document changed; request a fresh snapshot");
    if (typeof msg.expectedUrl === "string" && !sameUrl(msg.expectedUrl, location.href))
      throw failure("PAGE_CHANGED", "Page URL changed; inspect status and refresh the snapshot");
    const action = msg.action;
    switch (action) {
      case "snapshot": {
        const limit = Math.min(300, Math.max(1, Number(msg.limit) || 100));
        const offset = Math.min(6000, Math.max(0, Number(msg.offset) || 0));
        const scope = msg.selector ? pick(limited(msg.selector, 1000)) : document.body;
        const query = limited(msg.text || "", 300).toLowerCase();
        const all = candidates(scope, interactiveSelector).filter(el =>
          (msg.includeHidden === true || isVisible(el) || toggleProxy(el)) && (!msg.role || semanticRole(el) === msg.role) &&
          (!query || accessibleLabel(el).toLowerCase().includes(query)));
        const score = el => {
          const role = semanticRole(el), r = (toggleProxy(el) || el).getBoundingClientRect?.();
          return (["textbox","checkbox","radio","combobox","file","button"].includes(role) ? 300 : role === "link" ? 100 : 50) +
            (r && r.width > 0 && r.height > 0 && r.bottom > 0 && r.top < (window.innerHeight || 0) ? 80 : 0) +
            (isVisible(el) || toggleProxy(el) ? 30 : 0);
        };
        const ordered = msg.order === "document" ? all : all.map((el, index) => ({el,index,score:score(el)}))
          .sort((a,b) => b.score-a.score || a.index-b.index).map(item => item.el);
        const nodes = ordered.slice(offset, offset + limit);
        snapshotVersion += 1;
        snapshotRefs.clear();
        snapshotUrl = location.href;
        const lines = [
          "Page: " + limited(document.title, 250) + " | " + limited(location.href, 1000),
          "Agent-readable snapshot (refs valid until next snapshot or navigation):"
        ];
        const elements = [];
        nodes.forEach((el, index) => {
          const ref = "e" + documentId.slice(1).replace(/[^a-z0-9]/gi, "") + "_" + snapshotVersion + "_" + (index+1);
          snapshotRefs.set(ref, el);
          const role = semanticRole(el);
          const name = accessibleLabel(el);
          const value = isSensitiveInput(el)
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
          elements, refsVersion:snapshotVersion, total:all.length, offset,
          truncated:all.length > offset + nodes.length, nextOffset:all.length > offset + nodes.length ? offset + nodes.length : null,
          coverage:{scope:"document_and_open_shadow_roots", limit:6000, iframes:"separate_frame_results", closedShadowRoots:"opaque"}};
      }
      case "read": {
        // Reader-style extraction for AI: no screenshots, no costly full HTML serialization.
        // The output is bounded by maxChars to reduce inference tokens and memory pressure.
        const maxChars = Math.min(18000, Math.max(1000, Number(msg.maxChars) || 9000));
        const root = document.querySelector("main,article,[role=main]") || document.body;
        const text = limited((root?.innerText || "").trim(), maxChars);
        const headings = [...document.querySelectorAll("h1,h2,h3")].slice(0, 30)
          .map(el=>({level:el.tagName.toLowerCase(),text:limited(el.innerText || el.textContent, 160)}));
        const description = document.querySelector('meta[name="description"]')?.content || "";
        const canonical = document.querySelector('link[rel="canonical"]')?.href || "";
        return {url:location.href, title:limited(document.title,300),
          language:document.documentElement?.lang || "", description:limited(description,450),
          canonical:limited(canonical,1100), headings,
          text, truncated:(root?.innerText || "").length > maxChars};
      }
      case "dom": {
        const max = Math.min(100, Math.max(1, Number(msg.limit) || 40));
        const nodes = candidates(document, interactiveSelector)
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
        const locate = () => {
          if (typeof msg.expectedUrl === "string" && !sameUrl(msg.expectedUrl, location.href))
            throw failure("PAGE_CHANGED", "Page URL changed while waiting");
          return selector ? document.querySelector(selector)
            : (limited(document.body?.innerText, 25000).includes(wantedText) ? document.body : null);
        };
        const found = locate();
        if (found) return {found:true, element:inspect(found)};
        return new Promise((resolve, reject) => {
          let completed = false;
          let observer;
          const finish = (result, error) => {
            if (completed) return; completed = true;
            observer?.disconnect(); clearTimeout(timer); activeWaits.delete(cancel);
            if (error) reject(error); else resolve(result);
          };
          const cancel = () => finish(null, failure("CANCELLED", "Page bridge disconnected or document suspended"));
          const timer = setTimeout(()=>finish(null, failure("WAIT_TIMEOUT", "Requested element or text was not found within timeoutMs")), timeout);
          activeWaits.add(cancel);
          observer = new MutationObserver(()=>{
            try {
              const item = locate();
              if(item) finish({found:true,element:inspect(item)});
            } catch (error) { finish(null, error); }
          });
          try { observer.observe(document.documentElement,{subtree:true,childList:true,characterData:true,attributes:true}); }
          catch (error) { finish(null, error); }
        });
      }
      case "find": {
        // Optional CSS / visible text / role / accessible label filters.
        const sel = limited(msg.target || msg.selector ||
          "a,button,input,textarea,select,[role],label,p,span,h1,h2,h3", 1000);
        const query = limited(msg.text, 300).toLowerCase();
        const role = limited(msg.role, 80);
        const label = limited(msg.ariaLabel, 200).toLowerCase();
        const list = candidates(document, sel).filter(el =>
          (!query || limited(el.innerText || el.textContent, 3000).toLowerCase().includes(query)) &&
          (!role || semanticRole(el) === role) &&
          (!label || accessibleLabel(el).toLowerCase().includes(label))
        ).slice(0,100);
        return {count:list.length, elements:list.map(inspect)};
      }
      case "click": {
        const el = pick(msg.target || msg.selector), target = clickTarget(el);
        target.scrollIntoView({ block:"center",inline:"nearest" }); target.click();
        return { clicked: true, target: msg.target || msg.selector, viaLabel:target !== el,
          checked:el instanceof HTMLInputElement && ["checkbox","radio"].includes(el.type) ? el.checked : null };
      }
      case "clear": {
        const el = pick(msg.target || msg.selector);
        fill(el, "");
        return {cleared:true, selector:msg.selector};
      }
      case "toggle": {
        const el = pick(msg.target || msg.selector);
        const target = clickTarget(el);
        if (!(el instanceof HTMLInputElement) || !["checkbox","radio"].includes(el.type))
          throw new Error("Target is not a checkbox or radio");
        if (typeof msg.checked === "boolean" && el.checked === msg.checked)
          return {checked:el.checked, unchanged:true};
        target.scrollIntoView({block:"center",inline:"nearest"}); target.click();
        if (typeof msg.checked === "boolean" && el.checked !== msg.checked)
          throw failure("CONTROL_STATE_UNCHANGED", "The visible control did not reach the requested checked state; inspect before retrying");
        return {checked:el.checked, viaLabel:target !== el};
      }
      case "focus": {
        const el = pick(msg.target || msg.selector); assertInteractable(el); el.focus();
        return {focused:true,selector:msg.selector};
      }
      case "select": {
        const el = pick(msg.target || msg.selector);
        assertInteractable(el);
        if (!(el instanceof HTMLSelectElement)) throw new Error("Target is not select");
        const v = limited(msg.value,1000);
        const option = [...el.options].find(o=>o.value===v);
        if (!option) throw failure("OPTION_NOT_FOUND", "Option not found");
        if (option.disabled || option.parentElement?.disabled) throw failure("TARGET_NOT_INTERACTABLE", "Option is disabled");
        el.value = v;
        el.dispatchEvent(new Event("input",{bubbles:true}));
        el.dispatchEvent(new Event("change",{bubbles:true}));
        return {selected:true};
      }
      case "keyboard": {
        const el = msg.target || msg.selector ? pick(msg.target || msg.selector) : document.activeElement;
        const key = limited(msg.key,40);
        if (!["Enter","Tab","Escape","ArrowUp","ArrowDown","ArrowLeft","ArrowRight","Backspace","Delete","Home","End","PageUp","PageDown"].includes(key))
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
        fill(el, String(msg.text ?? ""));
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
        const delta = msg.y === undefined ? 600 : Number(msg.y);
        window.scrollBy({top: Math.max(-20000, Math.min(20000, Number.isFinite(delta) ? delta : 600)), behavior:"instant"});
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
      case "compatibility": {
        // Informational checks only. Never simulate CAPTCHAs or manufacture success.
        const host = location.hostname || "";
        const current = location.href || "";
        // Authorization redirects may contain one-time codes or tokens.
        // Do not return search/query or fragment components in diagnostics.
        const safeLocation = (location.origin || "") + (location.pathname || "/");
        const visible = limited(document.body?.innerText, 3800).toLowerCase();
        const cfFrame = !!document.querySelector(
          'iframe[src*="challenges.cloudflare.com"], .cf-turnstile, [data-cf-turnstile-response]');
        const cfText = /checking your browser|verify you are human|verifying you are human|just a moment|attention required/i.test(
          limited(document.title, 250) + " " + visible);
        const googleRejection = /disallowed_useragent|this browser or app may not be secure|unsupported browser/i
          .test(current + " " + visible);
        const otherWidget = !!document.querySelector('iframe[src*="recaptcha"],iframe[src*="hcaptcha"],iframe[src*="baxia"],.g-recaptcha,.h-captcha,.nc-container,[id*="baxia"],[class*="baxia"],[id*="captcha"]');
        const verificationText = /captcha|security verification|slide to verify|安全验证|安全驗證|滑动.*验证|滑動.*驗證|拖动.*验证|拖動.*驗證|请完成验证|請完成驗證|人机验证|人機驗證/.test(visible);
        const challenge = cfFrame || cfText || otherWidget || verificationText;
        const oauth = /(?:^|\.)(?:accounts\.google\.com|github\.com|githubusercontent\.com)$/.test(host) ||
          /\/(?:oauth|authorize|login)(?:\/|\?|$)/i.test(current);
        return {
          url:limited(safeLocation, 1200), title:limited(document.title, 250),
          host:limited(host, 180), readyState:document.readyState,
          challenge:{present:challenge ? true : null, state:challenge?"possible_challenge":"unknown",
            type:cfFrame || cfText ? "possible_cloudflare_or_turnstile" : otherWidget || verificationText ? "possible_verification" : null,
            confidence:cfFrame || otherWidget?"iframe_or_widget":cfText || verificationText?"page_text":"no_known_signal",
            absenceProven:false},
          oauth:{possible:oauth, embeddedBrowserRejection:googleRejection},
          javascriptActive:true, documentCookieApiAvailable:typeof document.cookie === "string",
          note:"Heuristic page signals, not proof of a passed challenge or successful login. Follow the site's normal verification flow."
        };
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
  function scheduleReconnect() {
    if (suspended || port || reconnectTimer !== null || reconnectAttempts >= 8) return;
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null;
      connect();
    }, Math.min(8000, 250 * 2 ** Math.max(0, reconnectAttempts - 1)));
  }
  function clearAcknowledgement() {
    if (acknowledgementTimer !== null) clearTimeout(acknowledgementTimer);
    acknowledgementTimer = null;
  }
  function dropConnection(connected) {
    if (port !== connected) return;
    clearAcknowledgement();
    port = null; snapshotRefs.clear(); cancelWaits();
    try { connected.disconnect?.(); } catch (_) {}
    scheduleReconnect();
  }
  function connect() {
    if (suspended || port) return;
    reconnectAttempts += 1;
    let connected;
    try {
      connected = browser.runtime.connectNative("aibrowser");
      port = connected;
      connected.onDisconnect?.addListener(() => {
        if (port !== connected) return;
        clearAcknowledgement();
        port = null;
        snapshotRefs.clear();
        cancelWaits();
        scheduleReconnect();
      });
      connected.onMessage.addListener(async (msg) => {
        if (port !== connected || suspended || !msg) return;
        if (msg.bridgeReady === true) { clearAcknowledgement(); reconnectAttempts = 0; return; }
        if (typeof msg.id !== "string") return;
        let reply;
        try {
          const result = await handle(msg);
          reply = {id:msg.id, ok:true, result:{...result, documentId, viewport:viewport()}};
        }
        catch (error) { reply = {id:msg.id, ok:false, code:error?.code || "PAGE_ACTION_FAILED",
          error:limited(error?.message || error, 300), retryable:false}; }
        // A delayed observer reply belongs only to the port that received it.
        if (port !== connected || suspended) return;
        try { connected.postMessage(reply); }
        catch (_) {
          dropConnection(connected);
        }
      });
      acknowledgementTimer = setTimeout(() => dropConnection(connected), 2500);
      connected.postMessage({ready:true, documentId, url:location.href, readyState:document.readyState, bridgeVersion:"0.11.0", schemaVersion:3});
    } catch (_) {
      clearAcknowledgement();
      if (port === connected) port = null;
      try { connected?.disconnect?.(); } catch (_) {}
      scheduleReconnect();
    }
  }
  window.addEventListener?.("pagehide", () => {
    suspended = true;
    clearAcknowledgement();
    if (reconnectTimer !== null) clearTimeout(reconnectTimer);
    reconnectTimer = null;
    const old = port; port = null;
    snapshotRefs.clear(); cancelWaits();
    try { old?.disconnect?.(); } catch (_) {}
  });
  const resume = () => {
    suspended = false;
    if (!port) { reconnectAttempts = 0; connect(); }
  };
  window.addEventListener?.("pageshow", resume);
  window.addEventListener?.("focus", () => { if (!suspended) resume(); });
  document.addEventListener?.("visibilitychange", () => { if (!suspended && document.visibilityState === "visible") resume(); });
  const notifyState = () => { try { port?.postMessage({pageState:true, documentId, url:location.href, readyState:document.readyState}); } catch (_) {} };
  window.addEventListener?.("load", notifyState);
  window.addEventListener?.("hashchange", notifyState);
  window.addEventListener?.("popstate", notifyState);
  window.addEventListener?.("online", () => { if (!suspended) resume(); });
  connect();
})();
