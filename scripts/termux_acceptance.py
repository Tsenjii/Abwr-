#!/usr/bin/env python3
"""Explicitly create up to 15 fixture-only profiles. Does not log into or modify live accounts."""
import argparse
import json
import os
from pathlib import Path
import sys
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from bridge.abwr_shim import AbwrBrowser
from scripts.acceptance_fixture import make_server
import threading

parser=argparse.ArgumentParser()
parser.add_argument('--profiles',type=int,default=15)
parser.add_argument('--create-test-profiles',action='store_true',required=True)
parser.add_argument('--cleanup-created',action='store_true')
args=parser.parse_args()
if not 1<=args.profiles<=15:parser.error('profiles must be 1–15')
server=make_server(port=8877)
threading.Thread(target=server.serve_forever,daemon=True).start()
browser=AbwrBrowser();browser.check_versions()
rows=[]
try:
    for index in range(1,args.profiles+1):
        profile='TEST_AUTO_%02d'%index
        context=browser.context(profile,exit_bucket='fixture_%02d'%index,create=True)
        browser.request('POST','/v1/profiles/switch',{'id':profile})
        page=context.page()
        loaded=page.goto('http://127.0.0.1:8877/?fixture='+profile)
        page.fill('#name','fixture '+profile)
        state=page.status()
        if state.get('extensionPortReady') is not True:raise RuntimeError('Bridge is not ready; batch stopped')
        shot=page.screenshot()
        rows.append({'profile':profile,'documentId':state.get('documentId'),'bridgeReady':True,'residentSessions':state.get('residentSessions'),'screenshot':shot.name})
        record={'profilesRequested':args.profiles,'execution':'sequential','concurrencyProven':1,'results':rows}
        target=browser.root/'ledger'/'acceptance.json'
        with target.open('w') as stream:os.chmod(target,0o600);json.dump(record,stream,indent=2)
        print(profile+' fixture PASS',flush=True)
finally:
    if args.cleanup_created:
        browser.request('POST','/v1/profiles/switch',{'id':'G001'})
        for row in rows:browser.request('POST','/v1/profiles/delete',{'id':row['profile']})
    server.shutdown();server.server_close()
