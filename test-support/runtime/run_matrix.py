from server_harness import *
from terminal_gates import close_clients,stop_server,normal_exit,normal_lifecycle,no_errors,assertions_passed,matrix_passed,MATRIX_FIRST_ASSERTIONS,write_report
import sys
sys.path.insert(0,str(SCRIPT_DIR.parent/'client'))
from protocol_client import Client
from fixture_safety import properties,fixture_port
variant=sys.argv[1];run=sys.argv[2] if len(sys.argv)>2 else 'gson-fixed';d=prepare(variant,run)
shutil.copy2(FIXTURE_JAR,d/'plugins/GuardianFixture.jar')
# Nondefault capacity makes the shared validated engine/telemetry startup binding observable.
config=d/'plugins/GuardianAC/config.yml'
if not config.exists():
 with zipfile.ZipFile(d/'plugins/GuardianAC.jar') as jar: config_text=jar.read('config.yml').decode('utf-8')
 config_text,n=re.subn(r'(?m)^(\s*)max-players:\s*\d+\s*$',r'\g<1>max-players: 37',config_text)
 if n!=1:raise RuntimeError('Pinned artifact config must contain one max-players setting')
 config.parent.mkdir(parents=True,exist_ok=True);config.write_text(config_text)
p=d/'server.properties';fixture_properties=properties(p);fixture_properties['white-list']='false';p.write_text('\n'.join(k+'='+v for k,v in sorted(fixture_properties.items()))+'\n')
r={'variant':variant,'at':stamp(),'plugin_sha256':sha(d/'plugins/GuardianAC.jar'),'fixture_sha256':sha(d/'plugins/GuardianFixture.jar'),'server_sha256':sha(d/'server.jar'),'stages':[]}
s=None
client=None
try:
 s=Server(d,'integration');s.expect('Done \\(');s.expect('internal development build enabled')
 for command,end in [('gac status','rules'),('gactest core','GACTEST COMMAND core DONE'),('gactest seed','GACTEST ASYNC DONE'),('gactest gui','GACTEST GUI ASYNC DONE'),('gactest guiipunban','GACTEST GUI IP ASYNC DONE'),('gactest confirmclose','GACTEST COMMAND confirmclose DONE'),('gactest packet','GACTEST COMMAND packet DONE')]:
  n=s.cmd(command);s.expect(end,start=n,timeout=25);r['stages'].append(command)
 n=s.cmd('gactest settingsruntime');s.expect('GACTEST SETTINGS RUNTIME DONE',start=n,timeout=60);r['stages'].append('gactest settingsruntime')
 client=Client(fixture_port(variant),'GacLifeFixture')
 joined=client.wait_join();r['lifecycle_client']=client.report()
 if not joined:raise RuntimeError('Telemetry lifecycle real actor failed to join')
 client.pump(.25);n=s.cmd('gactest permanentipoffline');s.expect('GACTEST COMMAND permanentipoffline DONE',start=n,timeout=20);r['stages'].append('gactest permanentipoffline')
 n=s.cmd('gactest telemetry');s.expect('GACTEST COMMAND telemetry DONE',start=n,timeout=20);r['stages'].append('gactest telemetry');client.close();client=None
 n=s.cmd('gactest permanentip');s.expect('GACTEST PERMANENT IP ASYNC DONE',start=n,timeout=150);r['stages'].append('gactest permanentip')
 n=s.cmd('gactest nativelifecycle');s.expect('GACTEST NATIVE LIFECYCLE ASYNC DONE',start=n,timeout=30);r['stages'].append('gactest nativelifecycle')
 s.cmd('gac replay GAC-11111111');s.expect('証拠を書き出しました:|Evidence exported:',timeout=10);r['evidence_export']=json.loads((d/'plugins/GuardianAC/exports/GAC-11111111.json').read_text())
 n=s.cmd('gactest settingsseed');s.expect('GACTEST SETTINGS RESTART SEED DONE',start=n,timeout=20);r['stages'].append('gactest settingsseed')
 n=s.cmd('gactest nativeseed');s.expect('GACTEST NATIVE LIFECYCLE SEED DONE',start=n,timeout=20);r['stages'].append('gactest nativeseed')
 r['runtime_api']=s.expect('GACTEST bukkit_api=',timeout=5).strip();r['first_results']=json.loads((d/'plugins/GuardianFixture/results.json').read_text());r['permanent_ip']={
  'positive_trust_source':'explicit_synthetic_DI_and_SQLite_observations_not_Microsoft_authentication',
  'synthetic_real_inventory_positive_reachable':any(x['test']=='permanent_ip_synthetic_gui_positive_reachable' and x['passed'] for x in r['first_results']),
  'default_real_offline_client_refused':all(any(x['test']==name and x['passed'] for x in r['first_results']) for name in ['permanent_ip_default_offline_real_actor_present','permanent_ip_default_offline_environment_refused','permanent_ip_default_offline_tcp_observation_untrusted','permanent_ip_default_offline_production_controller_denied'])};stop_server(r,s,'first_stop');s=None
 if not no_errors(r) or not normal_exit(r.get('first_stop')) or not normal_lifecycle(r.get('first_stop_lifecycle')) or not assertions_passed(r.get('first_results'),MATRIX_FIRST_ASSERTIONS) or any(value is not True for key,value in r['permanent_ip'].items() if key!='positive_trust_source'):raise RuntimeError('First matrix phase failed or did not stop normally; restart skipped')
 r['permanent_ip']['protected_address_not_plaintext_in_sqlite']=b'127.0.0.71' not in (d/'plugins/GuardianAC/bans/bans.db').read_bytes()
 s=Server(d,'restart');s.expect('Done \\(');s.expect('internal development build enabled')
 n=s.cmd('gactest restart');s.expect('GACTEST COMMAND restart DONE',start=n,timeout=20)
 n=s.cmd('gactest settingsrestart');s.expect('GACTEST SETTINGS RESTART VERIFIED DONE',start=n,timeout=20);r['stages'].append('gactest settingsrestart')
 n=s.cmd('gactest nativerestart');s.expect('GACTEST NATIVE LIFECYCLE RESTART DONE',start=n,timeout=45);r['stages'].append('gactest nativerestart')
 n=s.cmd('gactest permanentiprestart');s.expect('GACTEST PERMANENT IP RESTART DONE',start=n,timeout=20);r['stages'].append('gactest permanentiprestart')
 n=s.cmd('gac unban GAC-11111111');line=s.expect('30秒以内に確認してください:|Confirm within 30 seconds:',start=n,timeout=15);token=re.search('/gac confirm (\\S+)',line).group(1)
 n=s.cmd('gac confirm '+token+' isolated runtime fixture');s.expect('対象ケース数: 1|Cases: 1',start=n,timeout=20)
 n=s.cmd('gac confirm '+token+' repeated');s.expect('無効|invalid',start=n,timeout=10);r['confirmation_replay_denied']=True
 n=s.cmd('gactest disable');s.expect('GACTEST COMMAND disable DONE',start=n,timeout=10);r['restart_results']=json.loads((d/'plugins/GuardianFixture/results.json').read_text())
 r['run_completed']=True
except (Exception,KeyboardInterrupt) as e:r['error']=repr(e)
finally:
 close_clients(r,[client])
 if s is not None:stop_server(r,s,'stop')
 r['passed']=matrix_passed(r)
 write_report(d.parent/(variant+'-integration-report.json'),r,ensure_ascii=False);print(json.dumps(r,ensure_ascii=False))

raise SystemExit(0 if r['passed'] else 1)
