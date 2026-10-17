from server_harness import *
from terminal_gates import stop_server,no_errors,normal_exit,matrix_passed,boundaries_passed,write_report,stops_passed
import sqlite3
import sys

EXPECTED_TESTS = [
 'native_enabled_detected_in_all_worlds',
 'corrupt_database_disables_guardian',
 'corrupt_original_preserved',
 'backup_integrity_ok',
 'offline_restore_succeeded',
 'recovered_database_integrity',
 'recovered_database_boots',
]
if len(sys.argv)!=3:raise SystemExit('Usage: test_boundaries.py NEW_BOUNDARY_RUN COMPLETED_SEED_RUN')
run,seed_run=sys.argv[1:]
if any(not re.fullmatch(r'[A-Za-z0-9_-]+',name) for name in [run,seed_run]):raise ValueError('Invalid boundary or seed run name')
if run==seed_run:raise ValueError('Boundary run must be a new disposable copy')
authorization()
seed_report_path=ROOT/seed_run/'paper-26.3-integration-report.json'
seed_report=json.loads(seed_report_path.read_text())
seed_results=seed_report.get('first_results',[])+seed_report.get('restart_results',[])
if seed_report.get('plugin_sha256')!=os.environ['GUARDIAN_PLUGIN_SHA256'] or not matrix_passed(seed_report):
 raise RuntimeError('A successfully completed, exact-artifact Paper seed is required')
seed_store=ROOT/seed_run/'paper-26.3/plugins/GuardianAC/bans'
seed_hash=sha(seed_store/'bans.db')
root=ROOT/run;root.mkdir(exist_ok=False)
result={'at':stamp(),'seed_run':seed_run,'seed_plugin_sha256':seed_report['plugin_sha256'],'seed_db_sha256_before':seed_hash,'tests':[],'stops':[]}
s=None

def record(name,value):result['tests'].append({'test':name,'passed':bool(value)})
def stop(phase):
 global s
 entry={'phase':phase};result['stops'].append(entry)
 stop_server(entry,s,'exit_code');s=None
 if not no_errors(entry):result['shutdown_error']=entry.get('shutdown_error','Unproven server stop')
 if phase!='exception-cleanup' and not no_errors(entry):raise RuntimeError('Boundary phase did not stop normally: '+phase)
def integrity(path):
 connection=sqlite3.connect('file:'+str(path)+'?mode=ro',uri=True)
 try:return connection.execute('pragma integrity_check').fetchone()[0]
 finally:connection.close()

try:
 d=prepare('paper-26.3',run);shutil.copy2(FIXTURE_JAR,d/'plugins/GuardianFixture.jar')
 p=d/'config/paper-world-defaults.yml';config=p.read_text();needle='  anti-xray:\n    enabled: false'
 if config.count(needle)!=1:raise RuntimeError('Expected one disabled native Anti-Xray setting in the fresh fixture')
 p.write_text(config.replace(needle,'  anti-xray:\n    enabled: true',1))
 result['plugin_sha256']=sha(d/'plugins/GuardianAC.jar');result['fixture_sha256']=sha(d/'plugins/GuardianFixture.jar');result['server_sha256']=sha(d/'server.jar')
 s=Server(d,'native-enabled');s.expect('Done \\(');s.expect('internal development build enabled')
 n=s.cmd('gactest core');s.expect('GACTEST COMMAND core DONE',start=n,timeout=20);line=s.expect('GACTEST native_modes',start=n,timeout=5)
 record('native_enabled_detected_in_all_worlds','=ENABLED' in line and '=DISABLED' not in line and '=UNKNOWN' not in line);stop('native-enabled')
 # Corrupt only this new disposable copy. The successful seed and official baseline stay read-only.
 store=d/'plugins/GuardianAC/bans'
 if not store.resolve().is_relative_to(root.resolve()) or store.resolve()==seed_store.resolve():raise RuntimeError('Unsafe boundary database destination')
 shutil.rmtree(store);shutil.copytree(seed_store,store)
 db=store/'bans.db';data=bytearray(db.read_bytes());data[:16]=b'CORRUPT FIXTURE!!';db.write_bytes(data);bad=sha(db)
 s=Server(d,'corrupt-database');s.expect('Done \\(');s.expect('GuardianAC failed initialization safely',timeout=20)
 record('corrupt_database_disables_guardian',not any('internal development build enabled' in l for l in s.lines));stop('corrupt-database')
 record('corrupt_original_preserved',sha(db)==bad)
 backup=sorted((store/'backups').glob('*.db'))[-1];result['selected_backup_sha256']=sha(backup);record('backup_integrity_ok',integrity(backup)=='ok')
 cp=os.pathsep.join([str(FIXTURE_JAR),str(d/'plugins/GuardianAC.jar')]+[str(p) for p in (d/'libraries').rglob('slf4j-api-*.jar')])
 proc=subprocess.run([str(JAVA),'-cp',cp,'test.guardian.OfflineRecovery',str(d/'plugins/GuardianAC'),backup.name],text=True,capture_output=True,timeout=30)
 result['restore_output']=proc.stdout+proc.stderr;result['restore_exit']=proc.returncode
 record('offline_restore_succeeded',normal_exit(proc.returncode) and 'GACTEST RECOVERED' in proc.stdout)
 if normal_exit(proc.returncode):
  record('recovered_database_integrity',integrity(db)=='ok')
  s=Server(d,'recovered-database');s.expect('Done \\(');s.expect('internal development build enabled');record('recovered_database_boots',True);stop('recovered-database')
 result['run_completed']=True
except (Exception,KeyboardInterrupt) as e:result['error']=repr(e)
finally:
 if s is not None:stop('exception-cleanup')
 try:
  if not result['stops'] or not all(stops_passed(row,('exit_code',)) for row in result['stops']):raise RuntimeError('Final seed database verification unavailable: not all owned fixture stops were proven normal; database was not read')
  result['seed_db_sha256_after']=sha(seed_store/'bans.db');result['seed_database_unchanged']=result['seed_db_sha256_after']==seed_hash
 except (Exception,KeyboardInterrupt) as e:result['seed_verification_error']=repr(e)
 result['assertions']=len(result['tests']);result['passed_assertions']=sum(row['passed'] for row in result['tests'])
 result['passed']=boundaries_passed(result,EXPECTED_TESTS)
 write_report(root/'report.json',result,ensure_ascii=False);print(json.dumps(result,ensure_ascii=False))

raise SystemExit(0 if result['passed'] else 1)
