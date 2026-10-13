#!/usr/bin/env python3
"""Upgrade the pinned product APK on a fresh CI emulator; never touches a phone."""
import hashlib
import json
import os
from pathlib import Path
import threading
import time
import xml.etree.ElementTree as ET
import android_ci_smoke as ci


def main():
    assert os.environ.get('ABWR_CI_EMULATOR')=='1' and os.environ.get('GITHUB_ACTIONS')=='true'
    assert ci.PACKAGE=='dev.local.aibrowser'
    assert ci.adb('shell','getprop','ro.kernel.qemu').decode().strip()=='1'
    assert ci.adb('shell','id','-u').decode().strip()=='0'
    report={'result':'RUNNING','androidApi':os.environ.get('ABWR_CI_ANDROID_API'),
            'baselineRun':38386576654,'baselineSource':'8b86c094775f7911a4e707cb1a10abf90c358eae',
            'installOperation':'adb install -r','dataCleared':False,'profiles':[]}
    known_tokens=set()
    target=ci.OUT/'signed-upgrade.json'
    server=ci.make_server()
    threading.Thread(target=server.serve_forever,daemon=True).start()
    base='http://127.0.0.1:'+str(server.server_port)
    ci.adb('reverse','tcp:'+str(server.server_port),'tcp:'+str(server.server_port))
    ci.adb('forward','tcp:8765','tcp:8765')
    def read_token():
        try:
            root=ET.fromstring(ci.app_data('cat','shared_prefs/local_api.xml',check=False))
            return next((n.text for n in root if n.get('name')=='token'),None)
        except ET.ParseError:return None
    def start():
        # Match the smoke suite's fresh-emulator OS setup. This grant applies
        # only after the QEMU/Actions/root guards above; never to an owner phone.
        ci.adb('shell','pm','grant',ci.PACKAGE,'android.permission.POST_NOTIFICATIONS')
        ci.adb('forward','tcp:8765','tcp:8765')
        ci.adb('shell','am','start','-W','-n',ci.PACKAGE+'/dev.local.aibrowser.MainActivity')
        ci.token=ci.until(read_token,45)
        known_tokens.add(ci.token)
        ci.until(lambda:ci.api('/v1/status').get('ready'),60)
    def contexts():
        prefs=ET.fromstring(ci.app_data('cat','shared_prefs/browser_profiles.xml'))
        return json.loads(next(n.text for n in prefs if n.get('name')=='profile_contexts_v1'))
    try:
        report['phase']='baseline_start'
        start()
        report['beforeProcessId']=ci.adb('shell','pidof',ci.PACKAGE).decode().strip()
        token_before=ci.token
        old=ci.api('/v1/diagnostics')['installedApplication']
        assert old['versionName']=='0.14.0' and old['versionCode']==23,old
        cert=Path('signing/certificate-sha256.txt').read_text().strip()
        assert cert in old['certificateSha256']
        report['before']=old
        report['phase']='baseline_fixture_seed'
        for label in ('A','B'):
            report['phase']='baseline_fixture_seed_'+label
            profile='CI_UPGRADE_'+label
            ci.api('/v1/profiles/create',{'id':profile})
            state=ci.navigate(base+'/',profile=profile)
            seeded=ci.api('/v1/storage/export',{'profile':profile,'tab':state['activeTab'],
                'documentId':state['documentId'],'consented':True})
            seed_theme=next((x.get('value') for x in seeded['result']['local']['items'] if x['key']=='ABWR_FIXTURE_theme'),None)
            assert seed_theme=='green',('Baseline storage fixture was not seeded',seed_theme)
            tab=ci.api('/v1/tabs/new',{'profile':profile,'url':base+'/?upgrade='+label})['id']
            ci.navigate(base+'/http/set-cookie?value=upgrade-'+label+'&persist=1',profile=profile)
            bookmark=base+'/?upgrade-bookmark='+label
            ci.api('/v1/bookmarks/add',{'profile':profile,'url':bookmark})
            ci.api('/v1/takeover',{'profile':profile,'manual':True})
            report['profiles'].append({'profile':profile,'tab':tab,'bookmark':bookmark,
                'beforeTabs':ci.api('/v1/tabs',profile=profile)})
        # Root access is limited to this fresh Actions emulator. Keep the raw
        # preferences/token out of artifacts; hashes only establish persistence.
        context_before=hashlib.sha256(ci.app_data('cat','shared_prefs/browser_profiles.xml')).hexdigest()
        ids_before=contexts()
        report['phase']='replacement_install'
        ci.adb('shell','am','force-stop',ci.PACKAGE)
        ci.adb('install','-r','emulator-apk/app-release.apk')
        report['phase']='replacement_start'
        start()
        report['afterProcessId']=ci.adb('shell','pidof',ci.PACKAGE).decode().strip()
        new=ci.api('/v1/diagnostics')['installedApplication']
        assert new['versionName']=='0.14.1' and new['versionCode']==24,new
        assert cert in new['certificateSha256']
        assert new['sourceRevision']==os.environ['GITHUB_SHA']
        assert ci.token==token_before,'Upgrade changed control token'
        report['after']=new;report['sameControlToken']=True;report['sameCertificate']=True
        report['profilePreferencesHashUnchanged']=context_before==hashlib.sha256(ci.app_data('cat','shared_prefs/browser_profiles.xml')).hexdigest()
        assert all(contexts().get(row['profile'])==ids_before[row['profile']] for row in report['profiles'])
        report['contextIdsPreserved']=True
        report['phase']='replacement_data_verification'
        for row in report['profiles']:
            profile=row['profile']
            assert ci.api('/v1/status',profile=profile)['manualMode'] is True,'Explicit takeover lost on upgrade'
            row['afterTabs']=ci.api('/v1/tabs',profile=profile)
            before_ids={t['id'] for t in row['beforeTabs']['tabs']}
            after_ids={t['id'] for t in row['afterTabs']['tabs']}
            assert before_ids==after_ids,'Upgrade changed saved tab identities'
            bookmarks=ci.api('/v1/bookmarks',profile=profile)['bookmarks']
            assert isinstance(bookmarks,list) and all(isinstance(b,str) for b in bookmarks),'Bookmark API shape changed'
            assert row['bookmark'] in bookmarks,'Upgrade lost bookmark'
            # Explicitly authorized CI return, followed by same-origin visible
            # cookie proof. No raw cookie export and no user account involved.
            ci.api('/v1/takeover',{'profile':profile,'manual':False})
            ci.navigate(base+'/http/echo',profile=profile)
            echo=ci.command('read',profile=profile)['text']
            label=profile[-1]
            assert 'ABWR_CI_HTTP=upgrade-'+label in echo and 'ABWR_CI_HTTP=upgrade-'+('B' if label=='A' else 'A') not in echo
            ci.navigate(base+'/upgrade',profile=profile)
            state=ci.api('/v1/status',profile=profile)
            stored_text=ci.command('read',profile=profile)['text']
            row['storageProofText']=stored_text[:200]
            exported=ci.api('/v1/storage/export',{'profile':profile,'tab':state['activeTab'],
                'documentId':state['documentId'],'consented':True})
            row['localStoragePreserved']=any(x['key']=='ABWR_FIXTURE_theme' and x['value']=='green' for x in exported['result']['local']['items'])
            assert row['localStoragePreserved'],('Upgrade storage did not preserve theme',stored_text[:200])
            assert 'Stored theme:green' in stored_text,('Upgrade proof page text differs',stored_text[:200])
            row['persistentCookieIsolated']=True;row['bookmarkPreserved']=True
            row['takeoverPreserved']=True;row['tabIdsPreserved']=True;row['localStoragePreserved']=True
        ci.api('/v1/profiles/switch',{'id':'G001'})
        for row in report['profiles']:ci.api('/v1/profiles/delete',{'id':row['profile']})
        report['result']='PASS'
        report['phase']='complete'
        print('PASS fixed-signature in-place upgrade, isolated cookies, bookmarks, tabs, takeover and token',flush=True)
    except Exception as error:
        report['result']='FAIL';report['error']=type(error).__name__
        raise
    finally:
        # Upgrade failure can precede smoke's log collector. Android evidence
        # must survive loss of HTTP and must not replace the original exception.
        if ci.token:known_tokens.add(ci.token)
        for filename,args in (
            ('upgrade-logcat.txt',('logcat','-b','all','-d','-v','threadtime','-t','4000')),
            ('upgrade-native-crash-buffer.txt',('logcat','-b','crash','-d','-v','threadtime')),
            ('upgrade-exit-info.txt',('shell','dumpsys','activity','exit-info',ci.PACKAGE)),
            ('upgrade-process-id.txt',('shell','pidof',ci.PACKAGE)),
            ('upgrade-activity-state.txt',('shell','dumpsys','activity','activities')),
        ):
            try:
                data=ci.adb(*args,check=False)
                for secret in known_tokens:data=data.replace(secret.encode(),b'[REDACTED]')
                (ci.OUT/filename).write_bytes(data)
            except Exception as error:
                report.setdefault('evidenceErrors',{})[filename]=type(error).__name__
        report['finishedAtEpochMs']=round(time.time()*1000)
        target.write_text(json.dumps(report,indent=2))
        server.shutdown();server.server_close()


if __name__=='__main__':main()
