#!/usr/bin/env python3
"""Sequential fixture-only profiles; preserve existing accounts and the selected container."""
import argparse
import json
import os
from pathlib import Path
import sys
import threading
import uuid

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from bridge.abwr_shim import AbwrBrowser, AbwrError
from scripts.acceptance_fixture import make_server
from scripts.concurrency_measure import measure_sdk

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--profiles',type=int,default=15)
    parser.add_argument('--create-test-profiles',action='store_true',required=True)
    parser.add_argument('--cleanup-created',action='store_true')
    parser.add_argument('--concurrency-rounds',type=int,default=10)
    args=parser.parse_args()
    if not 1<=args.profiles<=15:parser.error('profiles must be 1–15')
    if not 1<=args.concurrency_rounds<=100:parser.error('concurrency rounds must be 1–100')
    browser=AbwrBrowser();browser.check_versions()
    original=browser.request('GET','/v1/status')['profile']
    server=make_server(port=8877)
    threading.Thread(target=server.serve_forever,daemon=True).start()
    run_id=uuid.uuid4().hex[:8]
    record={'runId':run_id,'profilesRequested':args.profiles,'execution':'sequential',
            'concurrencyProven':1,'createdProfiles':[],'results':[],'completed':False,'cleanupErrors':[]}
    target=browser.root/'ledger'/('acceptance_'+run_id+'.json')
    def save():
        temporary=target.with_suffix('.part')
        with temporary.open('w') as stream:
            os.chmod(temporary,0o600);json.dump(record,stream,indent=2)
        temporary.replace(target)
    try:
        for index in range(1,args.profiles+1):
            profile='TEST_AUTO_%s_%02d'%(run_id,index)
            context=browser.context(profile,exit_bucket='fixture',create=True)
            record['createdProfiles'].append(profile);save()
            browser.request('POST','/v1/profiles/switch',{'id':profile})
            page=context.page()
            page.goto('http://127.0.0.1:8877/?fixture='+profile)
            page.fill('#name','fixture '+profile)
            state=page.status()
            if state.get('extensionPortReady') is not True:
                raise AbwrError('BRIDGE_NOT_READY','Bridge is not ready; batch stopped')
            shot=page.screenshot()
            record['results'].append({'profile':profile,'documentId':state.get('documentId'),
                'bridgeReady':True,'residentSessions':state.get('residentSessions'),'screenshot':shot.name})
            save();print(profile+' fixture PASS',flush=True)
        measured=measure_sdk(browser,record['createdProfiles'][-2:],args.concurrency_rounds)
        record['concurrency']=measured;save()
        if measured['maximumReadConcurrencyProven']<1:
            raise AbwrError('BRIDGE_NOT_READY','Even sequential read measurement failed')
        record['completed']=True
    except AbwrError as error:
        record['failureCode']=error.code
        raise
    finally:
        try:
            browser.request('POST','/v1/profiles/switch',{'id':original})
        except AbwrError as error:record['cleanupErrors'].append({'operation':'restore_selection','code':error.code})
        if args.cleanup_created:
            for profile in record['createdProfiles']:
                try:browser.request('POST','/v1/profiles/delete',{'id':profile})
                except AbwrError as error:record['cleanupErrors'].append({'profile':profile,'code':error.code})
        save();server.shutdown();server.server_close()

if __name__=='__main__':main()
