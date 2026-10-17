"""16-client bounded loopback smoke, not a 100–1000-player capacity benchmark."""
import sys,json,time,shutil,re,os,struct
from pathlib import Path
ROOT=Path(__file__).resolve().parent;sys.path.insert(0,os.environ.get('GUARDIAN_RUNTIME_SUPPORT',str(ROOT.parent/'runtime')))
from server_harness import prepare,Server,sha
from fixture_safety import fixture_port
from terminal_gates import close_clients,stop_server,report_passed,write_report
from protocol_client import Client
from chunk_decode import decode_chunk,at,block_update
EXPECTED_ASSERTIONS=frozenset(['16_all_far_frames_retained', '16_all_initial_far_ore_frames_masked', '16_bound_native_contexts_without_capture_failures', '16_client_zero_findings', '16_client_zero_malformed', '16_client_zero_packet_drops', '16_clients_joined_remain_connected', '16_near_ore_revealed_on_actual_wire', '16_no_eligible_candidate_budget_failures', '16_no_target_counter_available', '16_received_actual_far_ore_chunk', '16_world_ore_unchanged_after_reveal', '16_world_ore_unchanged_before_reveal'])
variant=sys.argv[1] if len(sys.argv)>1 else 'paper-26.3';run=sys.argv[2] if len(sys.argv)>2 else 'client-load-v1'
d=prepare(variant,run);out=Path(os.environ.get('GUARDIAN_CLIENT_OUTPUT',str(Path.cwd()/'guardianac-client-evidence')))/run;out.mkdir(parents=True,exist_ok=True)
shutil.copy2(Path(os.environ['GUARDIAN_CLIENT_FIXTURE_JAR']),d/'plugins/RealClientFixture.jar')
p=d/'server.properties';p.write_text(re.sub(r'(?m)^max-players=.*$', 'max-players=16',re.sub(r'(?m)^white-list=.*$', 'white-list=false', p.read_text())))
s=None;clients=[];r={'server':variant,'jar_sha256':sha(d/'plugins/GuardianAC.jar'),'server_sha256':sha(d/'server.jar'),'limit':16,'assertions':[]};port=fixture_port(variant)
try:
 s=Server(d,'16-client-smoke')
 s.expect('Done \\(')
 if not any('internal development build enabled:' in line for line in s.lines):raise RuntimeError('GuardianAC did not activate native telemetry; refusing vacuous client validation')
 s.cmd('difficulty peaceful');n=s.cmd('gacreal setup');s.expect('GACREAL COMMAND setup DONE',start=n)
 for i in range(16):
  c=Client(port,'GacRealL'+str(i).zfill(2));clients.append(c);joined=c.wait_join()
  print('JOIN',i,joined,flush=True)
  for b in clients:b.pump(.001)
  if not joined:raise RuntimeError('client '+str(i)+' did not join')
 started=time.monotonic();until=started+20;next_tick=started;iterations=0;max_rss=0
 while time.monotonic()<until:
  for c in clients:
   if c.closed:raise RuntimeError(c.name+' disconnected')
   c.send('MOVE_PLAYER_STATUS_ONLY',b'\1');c.send('CLIENT_TICK_END')
  iterations+=1;next_tick+=.05
  while time.monotonic()<next_tick:
   for c in clients:c.pump(.0001)
   time.sleep(.001)
  status=Path('/proc/'+str(s.p.pid)+'/status').read_text();m=re.search(r'VmRSS:\s*(\d+)',status)
  if m:max_rss=max(max_rss,int(m[1]))
 r['wire_ore_states']={c.name:(at(decode_chunk(c.chunks[(2,0)]),40,20,8) if (2,0) in c.chunks else None) for c in clients}
 r['all_initial_ore_states']={c.name:[at(decode_chunk(raw),40,20,8) for raw in c.fixture_chunk_history] for c in clients}
 for c in clients:
  for i,raw in enumerate(c.fixture_chunk_history):(out/(variant+'-'+c.name+'-far-'+str(i)+'.bin')).write_bytes(raw)
 r['seconds']=time.monotonic()-started;r['ticks_sent_per_client']=iterations;r['max_rss_kib']=max_rss
 n=s.cmd('gacreal report load');s.expect('GACREAL COMMAND report DONE',start=n);obs=json.loads((d/'plugins/RealClientFixture/load.json').read_text());r['observation']=obs
 n=s.cmd('gac status');s.expect('announced bans',start=n);r['status']=''.join(s.lines[n:])
 r['assertions']=[{'test':'16_clients_joined_remain_connected','passed':all(c.joined and not c.closed for c in clients) and len(obs['players'])==16},{'test':'16_client_zero_findings','passed':all(not p['findings'] for p in obs['players'])},{'test':'16_client_zero_packet_drops','passed':obs['dropped']==0},{'test':'16_client_zero_malformed','passed':obs['malformed']==0}]
 r['assertions'].extend([
  {'test':'16_all_far_frames_retained','passed':all(c.fixture_chunk_packets==len(c.fixture_chunk_history) for c in clients)},
  {'test':'16_received_actual_far_ore_chunk','passed':len(r['wire_ore_states'])==16 and all(v is not None for v in r['wire_ore_states'].values())},
  {'test':'16_all_initial_far_ore_frames_masked','passed':len(r['all_initial_ore_states'])==16 and all(v and all(state==obs['stoneId'] for state in v) for v in r['all_initial_ore_states'].values())},
  {'test':'16_no_target_counter_available','passed':obs['noTargetChunks']>=0},
  {'test':'16_no_eligible_candidate_budget_failures','passed':obs['budgetLimitedChunks']==0},
  {'test':'16_world_ore_unchanged_before_reveal','passed':obs['worldOre']=='DIAMOND_ORE'}])
 n=s.cmd('gacreal nearall');s.expect('GACREAL COMMAND nearall DONE',timeout=20,start=n)
 until=time.monotonic()+2
 while time.monotonic()<until:
  for c in clients:c.send('MOVE_PLAYER_STATUS_ONLY',b'\1');c.send('CLIENT_TICK_END')
  next_frame=time.monotonic()+.05
  while time.monotonic()<next_frame:
   for c in clients:c.pump(.0001)
   time.sleep(.001)
 r['wire_ore_reveals']={c.name:[p for p in map(block_update,c.block_updates) if (p['x'],p['y'],p['z'])==(40,20,8)] for c in clients}
 r['assertions'].append({'test':'16_near_ore_revealed_on_actual_wire','passed':len(r['wire_ore_reveals'])==16 and all(any(p['state']==obs['oreId'] for p in updates) for updates in r['wire_ore_reveals'].values())})
 n=s.cmd('gacreal report load-near');s.expect('GACREAL COMMAND report DONE',timeout=20,start=n);r['near_observation']=json.loads((d/'plugins/RealClientFixture/load-near.json').read_text())
 r['assertions'].append({'test':'16_bound_native_contexts_without_capture_failures','passed':r['near_observation']['contextCaptureFailures']==0 and len(obs['players'])==16 and all(p['exactProtocol'] and p['packetStateToken']>0 for p in obs['players'])})
 r['assertions'].append({'test':'16_world_ore_unchanged_after_reveal','passed':r['near_observation']['worldOre']=='DIAMOND_ORE'})
 r['run_completed']=True
except (Exception,KeyboardInterrupt) as e:r['error']=repr(e)
finally:
 close_clients(r,clients,reports_key='clients')
 stop_server(r,s,'exit');r['passed']=report_passed(r,('exit',),EXPECTED_ASSERTIONS)
 write_report(out/(variant+'-load.json'),r);print(json.dumps({k:v for k,v in r.items() if k!='clients'},indent=2))
raise SystemExit(0 if r['passed'] else 1)
