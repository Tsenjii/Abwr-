// Static syntax check of the bundled web content bridge.
const fs = require('fs');
const assert = require('assert');
const src = fs.readFileSync('app/src/main/assets/agentbridge/content.js','utf8');
assert(src.includes('browser.runtime.connectNative("aibrowser")'));
for (const action of ['dom','click','type','scroll','find','wait']) {
  assert(src.includes('case "'+action+'"'), 'Missing action: '+action);
}
new Function(src);
console.log('WebExtension bridge syntax and action tests: PASS');
