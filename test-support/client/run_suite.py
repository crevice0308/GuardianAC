import os
import sys,json,time,shutil,re,struct,sqlite3,traceback,hashlib
from pathlib import Path
ROOT=Path(__file__).resolve().parent
sys.path.insert(0,os.environ.get('GUARDIAN_RUNTIME_SUPPORT',str(ROOT.parent/'runtime')))
from server_harness import prepare,Server,sha
from fixture_safety import fixture_port
from terminal_gates import close_clients,stop_server,no_errors,stops_passed,write_report
from protocol_client import Client
from chunk_decode import decode_chunk,at,block_update
from suite_gates import first_phase_allows_restart,complete_suite_passed
variant=sys.argv[1] if len(sys.argv)>1 else 'paper-26.3';run=sys.argv[2] if len(sys.argv)>2 else 'client-v1'
d=prepare(variant,run);out=Path(os.environ.get('GUARDIAN_CLIENT_OUTPUT',str(Path.cwd()/'guardianac-client-evidence')))/run;out.mkdir(parents=True,exist_ok=True)
shutil.copy2(Path(os.environ['GUARDIAN_CLIENT_FIXTURE_JAR']),d/'plugins/RealClientFixture.jar')
p=d/'server.properties';p.write_text(re.sub(r'(?m)^white-list=.*$', 'white-list=false', p.read_text()))
s=None;clients=[];port=fixture_port(variant)
r={'server':variant,'jar_sha256':sha(d/'plugins/GuardianAC.jar'),'server_sha256':sha(d/'server.jar'),'assertions':[],'client_implementation':'real TCP 26.3 minimal independent codec verified from canonical server classes; not Mojang rendered client'}
def check(name,ok,detail=None):
 entry={'test':name,'passed':bool(ok)}
 if detail is not None:entry['detail']=detail
 r['assertions'].append(entry);print('GACREALTEST '+json.dumps(entry),flush=True)
def command(cmd):
 n=s.cmd('gacreal '+cmd);s.expect('GACREAL COMMAND '+cmd.split()[0]+' DONE',timeout=20,start=n)
def report(label):
 command('report '+label);return json.loads((d/'plugins/RealClientFixture'/(label+'.json')).read_text())
def ticks(c,n):
 for _ in range(n):
  if c.closed:break
  c.tick()
def make(name):
 c=Client(port,name);clients.append(c);check(name+'_join',c.wait_join());c.pump(.5);return c
try:
 s=Server(d,'real-clients')
 s.expect('Done \\(')
 if not any('internal development build enabled:' in line for line in s.lines):raise RuntimeError('GuardianAC did not activate native telemetry; refusing vacuous client validation')
 s.cmd('difficulty peaceful');command('setup')
 c=make('GacRealClean');ticks(c,100)
 # Grounded walking over a purpose-built stone platform, at a conservative 2.4 m/s.
 start=c.position
 for i in range(120):c.tick((start[0]+(i+1)*.12,65,start[2],-90,0))
 # Normal 0.42 initial vertical velocity, 0.08 gravity, 0.98 air drag; 4 jumps.
 for jump in range(4):
  y=65;vy=.42
  while True:
   y+=vy;vy=(vy-.08)*.98
   if y<=65:c.tick((c.position[0],65,c.position[2],0,0),True);break
   c.tick((c.position[0],y,c.position[2],0,0),False)
  ticks(c,10)
 # Normal valid hotbar changes and unarmed swings over 5 seconds.
 for i in range(100):
  if i%10==0:c.send('SET_CARRIED_ITEM',struct.pack('>h',(i//10)%9))
  if i%5==0:c.send('PUNCH')
  c.tick()
 clean=report('clean');r['clean']=clean
 check('clean_trace_connected',not c.closed and c.disconnect is None)
 check('clean_trace_zero_findings',all(not p['findings'] for p in clean['players']))
 check('clean_trace_zero_malformed_or_cancelled',clean['malformed']==0 and clean['cancelled']==0)
 # Decode actual socket-delivered outgoing chunk, preserving raw bytes for reproduction.
 raw=c.chunks.get((2,0));check('ore_fixture_chunk_received',raw is not None)
 if raw:
  (out/(variant+'-ore-far-chunk.bin')).write_bytes(raw)
  decoded=decode_chunk(raw);r['far_ore_state']=at(decoded,40,20,8)
  check('far_outgoing_ore_masked',r['far_ore_state']==clean['stoneId'],r['far_ore_state'])
  check('world_ore_not_mutated',clean['worldOre']=='DIAMOND_ORE')
  r['decoded_chunk']={'sections':len(decoded['sections']),'heightmaps':len(decoded['heightmaps']),'encoded_section_bytes':decoded['length']}
  command('near GacRealClean');ticks(c,40)
  updates=[block_update(p) for p in c.block_updates];r['ore_updates']=[p for p in updates if (p['x'],p['y'],p['z'])==(40,20,8)]
  check('near_ore_authoritative_reveal',any(p['state']==clean['oreId'] for p in r['ore_updates']),r['ore_updates'])
  near=report('near');r['near']=near;check('reveal_does_not_mutate_world',near['worldOre']=='DIAMOND_ORE')
 r['clean_client']=c.report();c.close();time.sleep(.25)
 b=make('GacRealBad');ticks(b,100);before=report('before-malformed')
 b.send('SET_CARRIED_ITEM',struct.pack('>h',3));b.pump(.2)
 b.send('SET_CARRIED_ITEM',struct.pack('>h',15));b.pump(.2)
 b.send('MOVE_PLAYER_ROT',struct.pack('>ffB',0,100,1));b.pump(.2)
 after=report('after-malformed');r['malformed_before']=before;r['malformed_after']=after
 check('invalid_slot_and_pitch_cancelled',after['cancelled']-before['cancelled']==2 and after['malformed']-before['malformed']==2)
 check('isolated_invalid_packets_not_standalone_kick',not b.closed and b.disconnect is None)
 check('invalid_packets_do_not_change_player_state',len(after['players'])==1 and after['players'][0]['slot']==3 and abs(after['players'][0]['pitch'])<=90)
 # Finite invalid packets at bounded 3.33 packets/sec. Two independently parsed
 # protocol families; no injection into DetectionEngine and no fabricated samples.
 attempts=0
 for i in range(50):
  if b.closed or b.disconnect:break
  b.send('SET_CARRIED_ITEM',struct.pack('>h',15));b.pump(.15)
  if b.closed or b.disconnect:break
  b.send('MOVE_PLAYER_ROT',struct.pack('>ffB',0,100,1));b.pump(.45);attempts+=1
 r['multi_family_cycles']=attempts;r['bad_client']=b.report()
 check('repeated_independent_hard_evidence_enforced',b.closed or b.disconnect is not None)
 r['after_evidence']=report('after-evidence');check('native_packet_context_bound_without_capture_failures',r['after_evidence']['contextCaptureFailures']==0 and all(len(o['players'])==1 and o['players'][0]['exactProtocol'] and o['players'][0]['packetStateToken']>0 for o in [clean,before]));b.close()
except (Exception,KeyboardInterrupt) as e:r['error']=repr(e);r['traceback']=traceback.format_exc();print(r['traceback'],flush=True)
finally:
 close_clients(r,clients)
 stop_server(r,s)
 if stops_passed(r,('server_exit',)):
  try:
   dbs=list((d/'plugins/GuardianAC').rglob('*.db'))
   if not dbs:raise RuntimeError('Missing persisted GuardianAC SQLite database')
   db=sqlite3.connect('file:'+str(dbs[0])+'?mode=ro',uri=True);db.row_factory=sqlite3.Row
   try:
    r['ban_records']=[dict(row) for row in db.execute('SELECT player_uuid,player_name,type,vl,case_id,evidence_id,expires_at,revoked_at FROM bans')]
    r['evidence_summary']=[dict(row) for row in db.execute('SELECT player_uuid,case_id,original_bytes,payload_bytes,sha256 FROM evidence')]
   finally:db.close()
   check('ban_persisted_with_evidence',any(row['player_name']=='GacRealBad' and row['evidence_id'] for row in r['ban_records']))
   check('clean_not_banned',not any(row['player_name']=='GacRealClean' for row in r['ban_records']))
  except (Exception,KeyboardInterrupt) as e:r['error']=r.get('error') or repr(e);r['sqlite_error']=repr(e)
 else:r['sqlite_error']='Persisted SQLite inspection unavailable: normal-stop/result gate did not pass; database was not read'
 if not no_errors(r):r.setdefault('error','Fixture cleanup or shutdown failed')
 # Verify persisted real-player sanction after a proven normal first stop.
 if first_phase_allows_restart(r) and stops_passed(r,('server_exit',)):
  s=None;restart_clients=[]
  try:
   s=Server(d,'real-client-restart')
   s.expect(r'Done \(');bad=Client(port,'GacRealBad');restart_clients.append(bad);bad.wait_join();r['restart_bad']=bad.report();check('persisted_ban_denies_rejoin',not bad.joined and bad.disconnect is not None);bad.close()
   clean=Client(port,'GacRealClean');restart_clients.append(clean);check('clean_rejoin_after_restart',clean.wait_join());r['restart_clean']=clean.report();clean.close()
  except (Exception,KeyboardInterrupt) as e:r['error']=repr(e);r['traceback']=traceback.format_exc()
  finally:
   close_clients(r,restart_clients);stop_server(r,s,'restart_exit')
   if not no_errors(r):r.setdefault('error','Restart cleanup or shutdown failed')
 else:r['restart_skipped']='First phase failed, incomplete, or did not stop normally; no second server was launched.'
 r['passed']=complete_suite_passed(r) and stops_passed(r,('server_exit','restart_exit'))
 write_report(out/(variant+'-suite.json'),r);print(json.dumps(r,indent=2))
sys.exit(0 if complete_suite_passed(r) and stops_passed(r,('server_exit','restart_exit')) else 1)
