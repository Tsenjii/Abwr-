#!/usr/bin/env python3
"""Validate owner-configured gateway exits and actual Gecko responses, sequentially."""
import argparse
import ipaddress
import json
import os
from pathlib import Path
import sys
import urllib.error
import urllib.request
from urllib.parse import urlsplit
import uuid

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from bridge.abwr_shim import AbwrBrowser, AbwrError, _NoRedirect
from scripts.termux_lifecycle import write_record


def control(port,token,path,data=None):
    request=urllib.request.Request('http://127.0.0.1:'+str(port)+path,
        headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'},
        data=json.dumps(data).encode() if data is not None else None)
    opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),_NoRedirect())
    try:response=opener.open(request,timeout=25)
    except urllib.error.HTTPError as error:response=error
    with response:
        raw=response.read(65537)
        if len(raw)>65536:raise AbwrError('RESPONSE_TOO_LARGE','Gateway reply exceeds 64 KiB')
        body=json.loads(raw)
        if response.status!=200:raise AbwrError(body.get('code','GATEWAY_ERROR'),'Gateway refused operation',body.get('retryable',False))
        return body


def manifest_rows(path):
    rows=json.loads(path.read_text()).get('exits')
    if not isinstance(rows,list) or not 1<=len(rows)<=15:raise ValueError('Configure 1–15 exit probes')
    used=set()
    for row in rows:
        if not isinstance(row,dict):raise ValueError('Every exit must be an object')
        target=row.get('target','')
        if not isinstance(target,str) or not 1<=len(target)<=2048:raise ValueError('A bounded target URL is required')
        port=row.get('port');url=urlsplit(target);digest=row.get('expectedSha256','')
        if isinstance(port,bool) or not isinstance(port,int) or not 7901<=port<=7915 or port in used:raise ValueError('Unique ports 7901–7915 required')
        if url.scheme!='https' or not url.hostname or url.username or url.password or url.fragment or url.hostname in {'localhost','127.0.0.1','::1'}:raise ValueError('Use a real HTTPS target without embedded credentials')
        if url.port is not None and not 1<=url.port<=65535:raise ValueError('Invalid target port')
        if not isinstance(digest,str):raise ValueError('Expected SHA-256 must be text')
        if len(digest)!=64 or any(c not in '0123456789abcdef' for c in digest):raise ValueError('Known expected target SHA-256 is required')
        if row.get('expectedExitIp') is not None:ipaddress.ip_address(row['expectedExitIp'])
        used.add(port)
    return rows


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest',type=Path,required=True)
    parser.add_argument('--control-port',type=int,default=9091)
    parser.add_argument('--create-test-profiles',action='store_true',required=True)
    parser.add_argument('--cleanup-created',action='store_true')
    args=parser.parse_args()
    if not 1<=args.control_port<=65535 or args.control_port in {8765,8877} or 7901<=args.control_port<=7915:parser.error('Invalid control port')
    try:rows=manifest_rows(args.manifest)
    except (ValueError,TypeError,KeyError):parser.error('Invalid probe manifest; use unique slot ports, HTTPS targets and expected content hashes')
    token=os.environ.get('ABWR_GATEWAY_TOKEN','')
    if not token:parser.error('ABWR_GATEWAY_TOKEN is required')
    browser=AbwrBrowser();browser.check_versions();original=browser.request('GET','/v1/status')['profile']
    run_id=uuid.uuid4().hex[:8];target=browser.root/'ledger'/('network_'+run_id+'.json')
    report={'runId':run_id,'requestedSlots':len(rows),'completed':False,'results':[],
        'createdProfiles':[],'cleanupErrors':[],'automaticFailover':False}
    try:
        pool=control(args.control_port,token,'/slots?waitMs=10000')
        if not {x['port'] for x in rows}.issubset({x['port'] for x in pool['slots']}):raise AbwrError('SLOT_NOT_CONFIGURED','Probe references an unconfigured slot')
        for row in rows:
            port=row['port'];profile='TEST_NET_'+run_id+'_'+str(port)
            context=browser.context(profile,exit_bucket='slot_'+str(port),create=True)
            report['createdProfiles'].append(profile);write_record(target,report)
            context.set_proxy('http://127.0.0.1:'+str(port))
            gateway=control(args.control_port,token,'/slots/probe',{'port':port,
                'target':row['target'],'expectedSha256':row['expectedSha256']})
            if not gateway.get('healthy') or not gateway.get('tlsVerified'):raise AbwrError('PROBE_FAILED','Gateway TLS/content probe failed; no retry or alternate slot')
            page=context.page();page.on('response',lambda event:None)
            page.goto(row['target'])
            probe=page.probe_proxy(row['target'],row['expectedSha256'])
            if not probe.get('healthy') or not probe.get('networkObserved') or not probe.get('tlsVerified'):
                raise AbwrError('PROBE_FAILED','Gecko TLS/content/response proof failed; no retry or alternate slot')
            # /slots retains historical mappings. Only this pinned response's
            # IP can prove a current independent exit, never a stale mapping.
            observed_ip=gateway.get('exitIp')
            if row.get('expectedExitIp') is not None and observed_ip!=str(ipaddress.ip_address(row['expectedExitIp'])):
                raise AbwrError('EXIT_IP_MISMATCH','Observed exit mapping differs from the owner manifest')
            report['results'].append({'port':port,'profile':profile,'healthy':probe.get('healthy'),
                'networkObserved':probe.get('networkObserved'),'tlsVerified':gateway['tlsVerified'],
                'bodySha256':gateway['bodySha256'],'exitIp':observed_ip,
                'exitIpVerified':row.get('expectedExitIp') is not None,
                'documentId':page.status().get('documentId'),'bridgeReady':page.status().get('extensionPortReady')})
            write_record(target,report);print(str(port)+' Gecko/gateway HTTPS content PASS',flush=True)
        verified=[r['exitIp'] for r in report['results'] if r['exitIpVerified']]
        report['independentExitsProven']=len(set(verified));report['completed']=True
    except AbwrError as error:
        report['failureCode']=error.code;raise
    finally:
        try:browser.request('POST','/v1/profiles/switch',{'id':original})
        except AbwrError as error:report['cleanupErrors'].append({'operation':'restore_selection','code':error.code})
        if args.cleanup_created:
            for profile in report['createdProfiles']:
                try:browser.request('POST','/v1/profiles/delete',{'id':profile})
                except AbwrError as error:report['cleanupErrors'].append({'profile':profile,'code':error.code})
        write_record(target,report)
    print(str(target),flush=True)


if __name__=='__main__':main()
