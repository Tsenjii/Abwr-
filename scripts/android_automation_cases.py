"""Controlled new primitives: no live accounts or identity verification services."""
import base64
from concurrent.futures import ThreadPoolExecutor
import json
import threading
from urllib.parse import urlsplit

def run_case(api,command,navigate,until,fixture,output):
    api('/v1/profiles/switch',{'id':'G001'})
    api('/v1/takeover',{'profile':'G001','manual':False})
    current=navigate(fixture)
    tab=api('/v1/status')['activeTab']
    scope={'profile':'G001','tab':tab,'documentId':current['documentId']}
    command('focus',selector='#name',**scope)
    sequence=command('key_sequence',keys=['a','b','c'],intervalMs=50,**scope)
    assert sequence['dispatched'] and sequence['siteCompleted'] is False
    assert command('find',selector='#name')['elements'][0]['value']=='abc'
    command('focus',selector='#automation-prompt')
    command('scrollTo',selector='#automation-prompt')
    evidence=api('/v1/screenshot/evidence')
    target=command('geometry',selector='#automation-prompt')
    meta=evidence['metadata'];assert meta['stale'] is False and meta['documentId']==current['documentId']
    image=base64.b64decode(evidence['imageBase64']);assert image[:3]==b'\xff\xd8\xff'
    center=target['element']['center'];viewport=target['viewport']
    scale=meta['width']/viewport['width']
    x=center['x']*scale;y=center['y']*scale
    (output/'automation-pointer-before.jpg').write_bytes(image)
    (output/'automation-pointer-before.json').write_text(json.dumps({'geometry':target,'screenshot':meta,'x':x,'y':y,'status':api('/v1/status')},indent=2))
    path=command('pointer_path',steps=[{'kind':'move','x':x,'y':y,'atMs':0},{'kind':'down','x':x,'y':y,'atMs':100},{'kind':'up','x':x,'y':y,'atMs':220}],imageWidth=meta['width'],imageHeight=meta['height'],**scope)
    assert path['dispatched'] and path['preDownMove']=='position_only'
    (output/'automation-pointer-result.json').write_text(json.dumps(path,indent=2))
    prompt=until(lambda:next((p for p in api('/v1/prompts')['prompts'] if p['kind']=='prompt'),None))
    resolved=api('/v1/prompts/resolve',{'id':prompt['id'],'scopeToken':prompt['scopeToken'],'profile':prompt['profile'],'tab':prompt['tab'],'decision':'accept','text':'agent answered'})
    assert resolved['dispatched'] and not resolved['siteCompleted']
    until(lambda:'agent answered' in command('read')['text'])
    assert api('/v1/prompts/resolve',{'id':prompt['id'],'scopeToken':prompt['scopeToken'],'profile':prompt['profile'],'tab':prompt['tab'],'decision':'accept'},expected_status=404)['code']=='PROMPT_NOT_FOUND'
    imported=api('/v1/files/import',{'name':'agent-fixture.txt','dataBase64':base64.b64encode(b'controlled file bytes').decode()})
    command('scrollTo',selector='#automation-file')
    file_target=command('geometry',selector='#automation-file')
    file_shot=api('/v1/screenshot/evidence')['metadata']
    file_center=file_target['element']['center'];file_viewport=file_target['viewport']
    scale=file_shot['width']/file_viewport['width']
    command('click_point',x=file_center['x']*scale,y=file_center['y']*scale,imageWidth=file_shot['width'],imageHeight=file_shot['height'],**scope)
    file_prompt=until(lambda:next((p for p in api('/v1/prompts')['prompts'] if p['kind']=='file'),None))
    file_result=api('/v1/prompts/resolve',{'id':file_prompt['id'],'scopeToken':file_prompt['scopeToken'],'profile':file_prompt['profile'],'tab':file_prompt['tab'],'decision':'accept','uris':[imported['uri']]})
    assert file_result['dispatched']
    try:until(lambda:'agent-fixture.txt:controlled file bytes' in command('read')['text'])
    finally:(output/'automation-file-result.json').write_text(json.dumps(command('find',selector='#automation-file-answer'),indent=2))
    command('scrollTo',selector='#automation-permission')
    permission_target=command('geometry',selector='#automation-permission')
    permission_shot=api('/v1/screenshot/evidence')['metadata']
    point=permission_target['element']['center'];size=permission_target['viewport']
    scale=permission_shot['width']/size['width']
    command('click_point',x=point['x']*scale,y=point['y']*scale,imageWidth=permission_shot['width'],imageHeight=permission_shot['height'],**scope)
    permission_prompt=until(lambda:next((p for p in api('/v1/prompts')['prompts'] if p['kind']=='site_permission'),None))
    permission_result=api('/v1/prompts/resolve',{'id':permission_prompt['id'],'scopeToken':permission_prompt['scopeToken'],'profile':permission_prompt['profile'],'tab':permission_prompt['tab'],'decision':'accept'})
    until(lambda:command('find',selector='#automation-permission-answer')['elements'][0]['text']=='granted')
    api('/v1/network/observe',{'profile':'G001','tab':tab,'record':True,'clear':True})
    command('click',selector='#http-check')
    def responses():
        events=api('/v1/network/observe',{'profile':'G001','tab':tab,'limit':200})['events']
        return next((e for e in events if e.get('phase')=='response' and e['url'].endswith('/http/echo') and e.get('status')==200),None)
    event=until(responses)
    assert event['documentId']==current['documentId'] and isinstance(event.get('frameId'),int)
    assert 'responseHeaders' in event
    idle=command('wait',condition='network_idle',idleMs=300,timeoutMs=5000,profile='G001',tab=tab)
    assert idle['matched']
    assert api('/v1/command',{'action':'wait','selector':'#absent-fixture','timeoutMs':100},expected_status=504)['code']=='WAIT_TIMEOUT'
    frames=command('frames')['frames']
    frame=next(f for f in frames if '/child' in f['url'])
    assert command('wait',condition='frame_ready',frame=frame['frame'],expectedFrameUrl=frame['url'],timeoutMs=1000)['matched']
    with ThreadPoolExecutor(1) as executor:
        waiting=executor.submit(command,'wait',condition='document_changed',previousDocumentId=current['documentId'],timeoutMs=10000)
        until(lambda:api('/v1/diagnostics')['waitingForBridge']>0)
        newer=navigate(fixture+'?automation-new=1')
        assert waiting.result(timeout=12)['documentId']==newer['documentId']
    with ThreadPoolExecutor(1) as executor:
        authenticating=executor.submit(navigate,fixture.rstrip('/')+'/auth')
        auth=until(lambda:next((p for p in api('/v1/prompts')['prompts'] if p['kind']=='http_auth'),None))
        api('/v1/prompts/resolve',{'id':auth['id'],'scopeToken':auth['scopeToken'],'profile':auth['profile'],'tab':auth['tab'],'decision':'accept','username':'fixture-owner','password':'controlled-auth'})
        auth_page=authenticating.result(timeout=15)
        assert 'Controlled HTTP authentication complete' in command('read')['text']
    audit=api('/v1/audit',profile='G001')
    assert any(e['event']=='site_prompt' for e in audit['events'])
    assert 'agent answered' not in json.dumps(audit)
    queues=api('/v1/queues');assert queues['pageWorkers']==2 and queues['controlWorkers']==3
    (output/'automation-primitives.json').write_text(json.dumps({'input':sequence,'path':path,'prompt':resolved,'filePrompt':file_result,'permission':permission_result,'authDocumentId':auth_page['documentId'],'network':event,'networkIdle':idle,'queues':queues,'screenshot':meta,'audit':audit},indent=2))

def profile_scale_case(api,navigate,output,fixture):
    results=[]
    for number in range(1,16):
        profile='CI_AUTO_%02d'%number
        api('/v1/profiles/create',{'id':profile})
        state=navigate(fixture+'?profile='+profile,profile=profile)
        assert state['extensionPortReady'] is True
        results.append({'profile':profile,'tab':state['activeTab'],'documentId':state['documentId'],'bridgeReady':state['extensionPortReady'],'residentSessions':state['residentSessions']})
    api('/v1/profiles/switch',{'id':'G001'})
    # These are freshly created fixture-only contexts, never existing user accounts.
    for row in results:api('/v1/profiles/delete',{'id':row['profile']})
    (output/'automation-15-profiles.json').write_text(json.dumps({'scale':15,'execution':'sequential','concurrentProfilesProven':1,'results':results},indent=2))

def proxy_case(api,navigate,command,until,adb,output,fixture,top,child,token):
    from proxy_fixture import make_proxy,make_socks
    import sys,hashlib
    from pathlib import Path
    sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
    from bridge.abwr_shim import AbwrBrowser
    servers=[];created=[];evidence=[]
    # Gecko 137+ deliberately bypasses proxy filters for loopback targets.
    # 10.0.2.2 is the emulator's host alias; the controlled proxy maps it to
    # the same fixture server without changing Gecko's security preferences.
    allowed={('10.0.2.2',top.server_port),('127.0.0.1',child.server_port),('example.org',443)}
    target_map={'10.0.2.2':'127.0.0.1'}
    base='http://10.0.2.2:'+str(top.server_port)
    try:
        for number,label in enumerate(('A','B')):
            proxy=make_proxy(label,allowed,username='fixture-'+label,password='controlled-'+label,target_map=target_map)
            servers.append(proxy);threading.Thread(target=proxy.serve_forever,daemon=True).start()
            slot=7914+number;adb('reverse','tcp:'+str(slot),'tcp:'+str(proxy.server_port))
            profile='CI_PROXY_'+label;api('/v1/profiles/create',{'id':profile});created.append(profile)
            assert api('/v1/status',profile=profile)['url']=='about:blank'
            result=api('/v1/proxy',{'profile':profile,'enabled':True,'server':'http://127.0.0.1:'+str(slot),'username':'fixture-'+label,'password':'controlled-'+label})
            assert result['mode']=='per_profile' and result['dispatched'] and not result['verifiedConnectivity']
            state=navigate(base+'/',profile=profile)
            until(lambda:api('/v1/proxy',profile=profile)['identityVerified'])
            with proxy.lock:assert any(r['path']=='/' for r in proxy.records),'First navigation missed its configured route'
            api('/v1/network/observe',{'profile':profile,'tab':state['activeTab'],'record':True,'clear':True})
        for label in ('A','B'):
            profile='CI_PROXY_'+label;path='/proxy-proof/'+label.lower()
            state=navigate(base+path,profile=profile)
            events=api('/v1/network/observe',{'profile':profile,'tab':state['activeTab'],'limit':200})['events']
            response=next(e for e in events if e.get('phase')=='response' and e['url'].endswith(path))
            assert response['status']==200
            assert any(h['name'].lower()=='x-abwr-fixture-exit' and h['value']==label for h in response['responseHeaders'])
            evidence.append({'profile':profile,'response':response,'documentId':state['documentId']})
        sdk=AbwrBrowser(token=token,root=output/'sdk-state')
        state=api('/v1/status',profile='CI_PROXY_A')
        guarded={'profile':'CI_PROXY_A','tab':state['activeTab'],'documentId':state['documentId'],
                 'expectedUrl':state['url'],'url':base+'/http/binary','transport':'page'}
        for field,value in [('tab','stale-tab'),('documentId','stale-document'),('expectedUrl',base+'/stale-url')]:
            refused=api('/v1/http/request',{**guarded,field:value},expected_status=409)
            assert refused['code']=='PAGE_CHANGED'
        probe=sdk.context('CI_PROXY_A').page('main').probe_proxy(base+'/http/binary',hashlib.sha256(bytes(range(256))).hexdigest())
        assert probe['healthy'] and probe['networkObserved']
        (output/'automation-sdk-proxy-probe.json').write_text(json.dumps(probe,indent=2))
        tab=api('/v1/tabs/new',{'profile':'CI_PROXY_A','url':base+'/proxy-proof/new-tab'})['id']
        navigate(base+'/proxy-proof/new-tab',profile='CI_PROXY_A')
        with servers[0].lock:assert any(r['path']=='/proxy-proof/new-tab' for r in servers[0].records)
        with servers[1].lock:assert not any(r['path']=='/proxy-proof/new-tab' for r in servers[1].records)
        api('/v1/tabs/close',{'profile':'CI_PROXY_A','id':tab})
        api('/v1/profiles/create',{'id':'CI_PROXY_DIRECT'});created.append('CI_PROXY_DIRECT')
        navigate(base+'/proxy-proof/direct-new',profile='CI_PROXY_DIRECT')
        for server in servers:
            with server.lock:assert not any(r['path']=='/proxy-proof/direct-new' for r in server.records)
        # Certificate validation stays enabled. The proxy tunnels bytes; the
        # Gecko response observer, not CONNECT's 200, proves HTTPS completed.
        secure=navigate('https://example.org/?abwr-fixture=proxy-a',profile='CI_PROXY_A')
        events=api('/v1/network/observe',{'profile':'CI_PROXY_A','tab':secure['activeTab'],'limit':200})['events']
        assert any(e.get('phase')=='response' and e['url']=='https://example.org/' and e.get('status')==200 for e in events)
        with servers[0].lock:assert any(r['method']=='CONNECT' and r['host']=='example.org' for r in servers[0].records)
        navigate(base+'/proxy-proof/a-live',profile='CI_PROXY_A')
        socks=make_socks('S',allowed,'fixture-socks','controlled-socks',target_map=target_map);servers.append(socks)
        threading.Thread(target=socks.serve_forever,daemon=True).start()
        adb('reverse','tcp:7913','tcp:'+str(socks.server_address[1]))
        api('/v1/proxy',{'profile':'CI_PROXY_A','server':'socks5://127.0.0.1:7913','username':'fixture-socks','password':'controlled-socks'})
        navigate(base+'/proxy-proof/socks',profile='CI_PROXY_A')
        with socks.lock:assert any(r['authenticated'] for r in socks.records)
        api('/v1/proxy',{'profile':'CI_PROXY_A','server':'http://127.0.0.1:7914','username':'fixture-A','password':'controlled-A'})
        servers[0].shutdown();servers[0].server_close()
        failure=api('/v1/http/request',{'profile':'CI_PROXY_A','transport':'page','url':base+'/proxy-proof/must-not-direct'},expected_status=(502,504))
        with top.count_lock:assert top.request_counts['/proxy-proof/must-not-direct']==0,'Failed proxy fell back to direct'
        healthy=api('/v1/http/request',{'profile':'CI_PROXY_B','transport':'page','url':base+'/proxy-proof/b-still-live'})
        assert healthy['result']['status']==200
        (output/'automation-proxy-routing.json').write_text(json.dumps({'profiles':evidence,'httpsDocumentId':secure['documentId'],'failureCode':failure['code'],'directFallbackObserved':False,'requests':[[dict(r) for r in server.records] for server in servers]},indent=2))
    except Exception:
        (output/'automation-proxy-failure.json').write_text(json.dumps({'profiles':evidence,'states':[api('/v1/status',profile=p) for p in created],'diagnostics':api('/v1/diagnostics'),'requests':[[dict(r) for r in server.records] for server in servers]},indent=2))
        raise
    finally:
        for profile in created:
            api('/v1/proxy',{'profile':profile,'enabled':False})
        api('/v1/profiles/switch',{'id':'G001'})
        for profile in created:api('/v1/profiles/delete',{'id':profile})
        for server in servers:server.shutdown();server.server_close()
