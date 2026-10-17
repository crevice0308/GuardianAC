from pathlib import Path
import datetime,hashlib,json,sqlite3,os
R=Path(os.environ.get('GUARDIAN_RUNTIME_OUTPUT',str(Path.cwd()/'guardianac-runtime-evidence'))).resolve()
reports=[]
for p in sorted(R.glob('*/**/*-integration-report.json')):
 r=json.loads(p.read_text());r['report']=str(p.relative_to(R));r['assertions']=len(r.get('first_results',[]))+len(r.get('restart_results',[]));r['failed_assertions']=[t['test'] for t in r.get('first_results',[])+r.get('restart_results',[]) if not t['passed']]
 d=p.parent/r['variant'];db=d/'plugins/GuardianAC/bans/bans.db'
 if db.exists():
  c=sqlite3.connect('file:'+str(db)+'?mode=ro',uri=True)
  r['sqlite_integrity']=c.execute('pragma integrity_check').fetchone()[0];r['unban_audit']=c.execute('select case_id,actor,reason,scope from unban_audit').fetchall();r['encrypted_fixture_ips_not_plaintext']=all(ip.encode() not in db.read_bytes() for ip in ['127.0.0.11','127.0.0.12']);c.close()
  players=json.loads((d/'banned-players.json').read_text());ips=json.loads((d/'banned-ips.json').read_text())
  r['owned_uuid_native_removed']=not any(x.get('uuid')=='00000000-0000-4000-8000-000000000001' for x in players)
  r['owned_ip_native_removed']=not any(x.get('ip')=='127.0.0.11' for x in ips)
  r['foreign_native_preserved_after_unban']=any(x.get('source')=='OtherPlugin' for x in players) and any(x.get('source')=='OtherPlugin' for x in ips)
 reports.append(r)
expected=os.environ.get('GUARDIAN_PLUGIN_SHA256')
archived=[{'report':r['report'],'plugin_sha256':r['plugin_sha256'],'assertions':r['assertions'],'failed_assertions':r['failed_assertions']} for r in reports if expected and r['plugin_sha256']!=expected]
if expected:
    reports=[r for r in reports if r['plugin_sha256']==expected]
    latest={}
    for report in reports:
        key=report['variant']
        if key not in latest or report['at']>latest[key]['at']:latest[key]=report
    archived.extend({'report':r['report'],'plugin_sha256':r['plugin_sha256'],'assertions':r['assertions'],'failed_assertions':r['failed_assertions'],'error':r.get('error'),'superseded_attempt':True} for r in reports if latest[r['variant']] is not r)
    reports=sorted(latest.values(),key=lambda r:r['variant'])
result={'artifact_sha256':expected,'historical_reports':archived,'at_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'scope':'Isolated loopback server startup, synthetic Bukkit/PacketEvents fixtures, and one real TCP/Bukkit actor for telemetry lifecycle only; no graphical client or broad joined-gameplay compatibility claim.','reports':reports,'spigot_26_3':{'status':'blocked','reason':'Official Spigot 26.3 build outcome remains unresolved; the exact earlier access failure has not been established. No official server runtime pass exists, and no retry or unofficial replacement is counted.','evidence':'Development environment official BuildTools preparation status; not distributed with this harness'},'not_verified':['Real graphical client GUI UX','Broad legitimate gameplay false-positive rate','High-player-count real network load','Spigot26.3 server runtime','ViaVersion/Geyser/ProtocolLib coexistence','Discord external delivery (disabled by default)','Universal cheat detection or client modification attribution']}
result['boundaries']=[json.loads(b.read_text()) for b in sorted(R.glob('*/report.json')) if 'tests' in json.loads(b.read_text()) and (not expected or json.loads(b.read_text()).get('plugin_sha256')==expected)]
(R/'runtime-matrix-report.json').write_text(json.dumps(result,indent=2,ensure_ascii=False)+'\n')
print([(r['variant'],r['assertions'],r['failed_assertions'],r['plugin_sha256']) for r in reports])
