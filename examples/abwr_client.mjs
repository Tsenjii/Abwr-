/** Node/Termux example. No desktop browser or Playwright dependency. */
export class AbwrClient {
  constructor(profile, token=process.env.AI_BROWSER_TOKEN) {
    if(!token)throw Error('AI_BROWSER_TOKEN is required');
    this.profile=profile;this.token=token;
  }
  async request(method,path,data) {
    const response=await fetch('http://127.0.0.1:8765'+path,{method,redirect:'error',signal:AbortSignal.timeout(23000),
      headers:{Authorization:'Bearer '+this.token,'X-Browser-Profile':this.profile,'Content-Type':'application/json'},
      ...(data?{body:JSON.stringify(data)}:{})});
    const reply=await response.json();
    if(!response.ok || reply.ok===false)throw Object.assign(Error(reply.message || reply.error || 'ABWR operation failed'),{code:reply.code,retryable:!!reply.retryable});
    return reply;
  }
  async checkVersion() {
    const caps=await this.request('GET','/v1/capabilities');
    if(caps.apiVersion!=='0.14.0' || caps.bridgeSchemaVersion!==4 || caps.mcpToolCount!==82)throw Error('VERSION_MISMATCH');
    return caps;
  }
  async command(action,args={}) {
    const state=await this.request('GET','/v1/status');
    const scope={profile:this.profile,tab:state.activeTab};
    if(state.documentId && !['navigate','wait'].includes(action))Object.assign(scope,{documentId:state.documentId,expectedUrl:state.url});
    // A transport error is not retried: inspect the visible effect before deciding to repeat a write.
    return this.request('POST','/v1/command',{action,...scope,...args});
  }
  async screenshotEvidence() {return this.request('GET','/v1/screenshot/evidence');}
}
