"""Controlled new primitives: no live accounts or identity verification services."""
import base64
from concurrent.futures import ThreadPoolExecutor
import json

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
    command('scrollTo',selector='#automation-prompt')
    target=command('geometry',selector='#automation-prompt')
    evidence=api('/v1/screenshot/evidence')
    meta=evidence['metadata'];assert meta['stale'] is False and meta['documentId']==current['documentId']
    image=base64.b64decode(evidence['imageBase64']);assert image[:3]==b'\xff\xd8\xff'
    center=target['element']['center'];viewport=target['viewport']
    x=center['x']*meta['width']/viewport['width'];y=center['y']*meta['height']/viewport['height']
    path=command('pointer_path',steps=[{'kind':'move','x':x,'y':y,'atMs':0},{'kind':'down','x':x,'y':y,'atMs':100},{'kind':'up','x':x,'y':y,'atMs':220}],imageWidth=meta['width'],imageHeight=meta['height'],**scope)
    assert path['dispatched'] and path['preDownMove']=='position_only'
    prompts=until(lambda:api('/v1/prompts')['prompts'])
    prompt=next(p for p in prompts if p['kind']=='prompt')
    resolved=api('/v1/prompts/resolve',{'id':prompt['id'],'scopeToken':prompt['scopeToken'],'profile':prompt['profile'],'tab':prompt['tab'],'decision':'accept','text':'agent answered'})
    assert resolved['dispatched'] and not resolved['siteCompleted']
    until(lambda:'agent answered' in command('read')['text'])
    assert api('/v1/prompts/resolve',{'id':prompt['id'],'scopeToken':prompt['scopeToken'],'profile':prompt['profile'],'tab':prompt['tab'],'decision':'accept'},expected_status=404)['code']=='PROMPT_NOT_FOUND'
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
    audit=api('/v1/audit',profile='G001')
    assert any(e['event']=='site_prompt' for e in audit['events'])
    assert 'agent answered' not in json.dumps(audit)
    queues=api('/v1/queues');assert queues['pageWorkers']==2 and queues['controlWorkers']==3
    (output/'automation-primitives.json').write_text(json.dumps({'input':sequence,'path':path,'prompt':resolved,'network':event,'networkIdle':idle,'queues':queues,'screenshot':meta,'audit':audit},indent=2))

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
