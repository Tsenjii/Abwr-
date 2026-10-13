#!/usr/bin/env python3
"""Phone-only lifecycle evidence, fixture profile only, no adb or desktop."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import sys
import subprocess
import threading
import time
import uuid

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from bridge.abwr_shim import AbwrBrowser, AbwrError
from scripts.acceptance_fixture import make_server
from scripts.idle_checkpoint import idle_for


def write_record(target,record):
    part=target.with_suffix('.part')
    with part.open('w') as stream:
        os.chmod(part,0o600);json.dump(record,stream,indent=2)
    part.replace(target)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mode',choices=['foreground','background','locked'],required=True)
    parser.add_argument('--create-test-profile',action='store_true',required=True)
    parser.add_argument('--cleanup-created',action='store_true')
    parser.add_argument('--checkpoints',default='60,600,1800',help='Independent idle intervals in seconds, maximum 1800')
    args=parser.parse_args()
    try:checkpoints=[int(x) for x in args.checkpoints.split(',')]
    except ValueError:parser.error('Checkpoints must be integer seconds')
    if not checkpoints or checkpoints!=sorted(set(checkpoints)) or not 1<=checkpoints[0]<=checkpoints[-1]<=1800:
        parser.error('Use unique ascending checkpoints from 1 to 1800 seconds')
    browser=AbwrBrowser();browser.check_versions()
    original=browser.request('GET','/v1/status')['profile']
    server=make_server(port=8877);threading.Thread(target=server.serve_forever,daemon=True).start()
    run_id=uuid.uuid4().hex[:8];profile='TEST_LIFE_'+run_id
    target=browser.root/'ledger'/('lifecycle_'+run_id+'.json')
    record={'runId':run_id,'mode':args.mode,'checkpointsRequested':checkpoints,'completed':False,
        'fixtureOnly':True,'pixelColorVerified':False,'checks':[],'cleanupErrors':[]}
    record['device']={}
    for key,prop in [('model','ro.product.model'),('androidVersion','ro.build.version.release'),('androidApi','ro.build.version.sdk')]:
        try:record['device'][key]=subprocess.run(['/system/bin/getprop',prop],capture_output=True,text=True,timeout=3,check=True).stdout.strip()
        except (OSError,subprocess.SubprocessError):record['device'][key]=None
    created=False
    def diagnostics():
        source=browser.request('GET','/v1/diagnostics')
        return {key:source.get(key) for key in ['installedApplication','version','sourceRevision',
            'processId','processInstanceId','uptimeMs','screenInteractive','apiWakeLeases',
            'batteryOptimizationExempt','backgroundRendererActive','backgroundRendererPhase',
            'lastScreenshotError','pendingBridgeCommands','waitingForBridge']}
    def capture(page,label):
        state=page.status();shot=page.screenshot()
        meta=json.loads(shot.with_suffix('.json').read_text())
        return {'label':label,'documentId':state['documentId'],'viewAttached':state.get('viewAttached'),
            'bridgeReady':state.get('extensionPortReady'),'metadata':meta,'screenshot':shot.name,
            'jpegSha256':hashlib.sha256(shot.read_bytes()).hexdigest(),'diagnostics':diagnostics()}
    def expected_surface(state,diag):
        if args.mode=='foreground':return state.get('viewAttached') is True and diag.get('screenInteractive') is True
        return state.get('viewAttached') is False and diag.get('screenInteractive') is (args.mode!='locked')
    try:
        context=browser.context(profile,exit_bucket='fixture',create=True);created=True
        browser.request('POST','/v1/profiles/switch',{'id':profile});page=context.page()
        page.goto('http://127.0.0.1:8877/color?c=green&checkpoint=0')
        first=capture(page,'initial');record['initial']=first;write_record(target,record)
        if args.mode!='foreground':
            print('Waiting up to 120s for '+args.mode+' state. Run this process in Termux while ABWR is backgrounded.',flush=True)
        deadline=time.monotonic()+120
        while not expected_surface(page.status(),diagnostics()):
            if time.monotonic()>deadline:raise AbwrError('STATE_TIMEOUT','Requested foreground/background/screen state was not observed')
            page.wait_for_timeout(1000)
        marker=diagnostics()
        if not isinstance(marker.get('processId'),int) or not marker.get('processInstanceId'):
            raise AbwrError('VERSION_MISMATCH','Lifecycle proof requires process identity diagnostics')
        start=time.monotonic();previous=first
        for seconds in checkpoints:
            # Each checkpoint starts a new full interval after the previous
            # API/capture work. Cumulative targets would only idle 1/9/20 min.
            idle_seconds=idle_for(seconds)
            before=diagnostics()
            if before['processId']!=marker['processId'] or before['processInstanceId']!=marker['processInstanceId']:
                raise AbwrError('PROCESS_RESTARTED','Android process changed during idle measurement')
            if not expected_surface(page.status(),before):raise AbwrError('SURFACE_STATE_CHANGED','Requested test state changed during idle measurement')
            color='green' if previous['label'].endswith('blue') else 'blue'
            page.goto('http://127.0.0.1:8877/color?c='+color+'&checkpoint='+str(seconds))
            current=capture(page,str(seconds)+'s_'+color)
            if current['diagnostics']['processId']!=marker['processId'] or current['diagnostics']['processInstanceId']!=marker['processInstanceId']:
                raise AbwrError('PROCESS_RESTARTED','Android process changed during checkpoint capture')
            if current['documentId']==previous['documentId'] or current['jpegSha256']==previous['jpegSha256']:
                raise AbwrError('STALE_EVIDENCE','Expected a fresh document and changed JPEG')
            if current['metadata']['capturedAtMs']<=previous['metadata']['capturedAtMs']:
                raise AbwrError('STALE_EVIDENCE','Capture timestamp did not advance')
            if color+' lower viewport' not in page.command('read')['text']:
                raise AbwrError('FIXTURE_MISMATCH','Visible document differs from requested fixture')
            if not expected_surface(page.status(),current['diagnostics']):raise AbwrError('SURFACE_STATE_CHANGED','Capture changed the requested state')
            current['requestedIdleSeconds']=seconds
            current['idleSeconds']=round(idle_seconds,3)
            current['elapsedSeconds']=round(time.monotonic()-start,2)
            record['checks'].append(current);write_record(target,record);previous=current
            print(str(seconds)+'s '+args.mode+' fresh-document/JPEG PASS',flush=True)
        record['completed']=True
    except AbwrError as error:
        record['failureCode']=error.code;raise
    finally:
        try:browser.request('POST','/v1/profiles/switch',{'id':original})
        except AbwrError as error:record['cleanupErrors'].append({'operation':'restore_selection','code':error.code})
        if created and args.cleanup_created:
            try:browser.request('POST','/v1/profiles/delete',{'id':profile})
            except AbwrError as error:record['cleanupErrors'].append({'operation':'delete_fixture','code':error.code})
        write_record(target,record);server.shutdown();server.server_close()
    print(str(target),flush=True)


if __name__=='__main__':main()
