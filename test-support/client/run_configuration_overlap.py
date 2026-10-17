"""Two real sockets, one offline UUID: stale CONFIGURATION disconnect must preserve PLAY owner."""
import json,os,re,shutil,sys,time
from pathlib import Path
ROOT=Path(__file__).resolve().parent
sys.path.insert(0,os.environ.get('GUARDIAN_RUNTIME_SUPPORT',str(ROOT.parent/'runtime')))
from server_harness import prepare,Server,sha
from fixture_safety import fixture_port
from terminal_gates import close_clients,stop_server,report_passed,record_error,write_report
from protocol_client import Client
from chunk_decode import decode_chunk,at,block_update
EXPECTED_ASSERTIONS=frozenset(['A_login_finished_configuration_deliberately_held', 'A_real_PacketEvents_disconnect_observed', 'A_still_in_configuration_while_B_in_PLAY', 'B_authoritative_near_ore_reveal', 'B_exact_protocol_and_state_established', 'B_exact_protocol_preserved', 'B_far_ore_masked_on_actual_received_bytes', 'B_packet_state_identity_preserved', 'B_remains_connected_clean_exact_protocol', 'B_reveal_ledger_present_before_old_close', 'B_reveal_ledger_preserved', 'B_same_offline_uuid_joins_real_PLAY', 'no_chunk_resend_can_repopulate_the_ledger', 'only_B_is_online_PLAY_player', 'world_ore_unchanged_through_overlap_and_reveal', 'zero_context_capture_failures_through_overlap'])
variant=sys.argv[1] if len(sys.argv)>1 else 'paper-26.3'
run=sys.argv[2] if len(sys.argv)>2 else 'configuration-overlap'
d=prepare(variant,run)
out=Path(os.environ['GUARDIAN_CLIENT_OUTPUT'])/run;out.mkdir(parents=True,exist_ok=True)
shutil.copy2(Path(os.environ['GUARDIAN_CLIENT_FIXTURE_JAR']),d/'plugins/RealClientFixture.jar')
p=d/'server.properties';p.write_text(re.sub(r'(?m)^white-list=.*$','white-list=false',p.read_text()))
s=None;a=b=None
r={'server':variant,'jar_sha256':sha(d/'plugins/GuardianAC.jar'),'server_sha256':sha(d/'server.jar'),'client_implementation':'actual two-socket protocol-777 login/configuration/play','assertions':[]}
port=fixture_port(variant)
name='GacRealOverlap'
def check(test,passed,detail=None):
 row={'test':test,'passed':bool(passed)}
 if detail is not None:row['detail']=detail
 r['assertions'].append(row);print('GACREALTEST '+json.dumps(row),flush=True)
 if not passed:raise AssertionError(test)
def cmd(command):
 n=s.cmd('gacreal '+command);s.expect('GACREAL COMMAND '+command.split()[0]+' DONE',timeout=20,start=n)
def report(label):
 cmd('report '+label);return json.loads((d/'plugins/RealClientFixture'/(label+'.json')).read_text())
def actor(observation):
 players=[p for p in observation['players'] if p['name']==name]
 if len(players)!=1:raise RuntimeError('Expected exactly one online PLAY actor')
 return players[0]
try:
 s=Server(d,'configuration-overlap')
 s.expect('Done \\(')
 if not any('internal development build enabled:' in line for line in s.lines):raise RuntimeError('GuardianAC native telemetry inactive')
 s.cmd('difficulty peaceful');cmd('setup')
 a=Client(port,name,hold_configuration=True)
 check('A_login_finished_configuration_deliberately_held',a.wait_configuration() and not a.joined and a.sent['configuration:FINISH_CONFIGURATION']==0)
 r['A_before_B']=a.report()
 b=Client(port,name)
 check('B_same_offline_uuid_joins_real_PLAY',b.wait_join() and b.uuid==a.uuid)
 for _ in range(100):
  b.tick();a.pump(.001)
 r['A_before_close']=a.report()
 check('A_still_in_configuration_while_B_in_PLAY',not a.closed and a.disconnect is None and a.state=='configuration' and not a.joined and a.sent['configuration:FINISH_CONFIGURATION']==0)
 before=report('overlap-before');r['before']=before;bp=actor(before)
 check('only_B_is_online_PLAY_player',len(before['players'])==1)
 check('B_exact_protocol_and_state_established',bp['exactProtocol'] and bp['packetStateToken']>0)
 raw=b.chunks.get((2,0))
 check('B_far_ore_masked_on_actual_received_bytes',raw is not None and at(decode_chunk(raw),40,20,8)==before['stoneId'])
 (out/(variant+'-overlap-far-chunk.bin')).write_bytes(raw)
 check('B_reveal_ledger_present_before_old_close',before['hiddenLedgerPlayers']==1 and before['hiddenLedgerSize']>0)
 frames=b.fixture_chunk_packets
 a.close();r['A_explicit_close']=True
 deadline=time.monotonic()+5;after=None
 while time.monotonic()<deadline:
  b.pump(.2);after=report('overlap-after');ap=actor(after)
  if ap['disconnectEventsForUuid']>bp['disconnectEventsForUuid']:break
 r['after']=after;ap=actor(after)
 check('A_real_PacketEvents_disconnect_observed',ap['disconnectEventsForUuid']==bp['disconnectEventsForUuid']+1 and not b.closed)
 check('B_packet_state_identity_preserved',ap['packetStateToken']==bp['packetStateToken'],{'before':bp['packetStateToken'],'after':ap['packetStateToken']})
 check('B_exact_protocol_preserved',ap['exactProtocol'])
 check('B_reveal_ledger_preserved',after['hiddenLedgerPlayers']==1 and after['hiddenLedgerSize']==before['hiddenLedgerSize'],{'before':before['hiddenLedgerSize'],'after':after['hiddenLedgerSize']})
 check('no_chunk_resend_can_repopulate_the_ledger',b.fixture_chunk_packets==frames,{'before':frames,'after':b.fixture_chunk_packets})
 cmd('near '+name)
 for _ in range(40):b.tick()
 updates=[p for p in map(block_update,b.block_updates) if (p['x'],p['y'],p['z'])==(40,20,8)];r['ore_updates']=updates
 check('B_authoritative_near_ore_reveal',any(p['state']==before['oreId'] for p in updates),updates)
 near=report('overlap-near');r['near']=near;np=actor(near)
 check('world_ore_unchanged_through_overlap_and_reveal',all(o['worldOre']=='DIAMOND_ORE' for o in [before,after,near]))
 check('zero_context_capture_failures_through_overlap',all(o['contextCaptureFailures']==0 for o in [before,after,near]))
 check('B_remains_connected_clean_exact_protocol',not b.closed and b.disconnect is None and np['exactProtocol'] and not np['findings'] and near['cancelled']==0 and near['malformed']==0)
 r['run_completed']=True
except (Exception,KeyboardInterrupt) as error:r['error']=repr(error)
finally:
 for key,client in (('A_final',a),('B_final',b)):
  if client is not None:
   try:r[key]=client.report()
   except (Exception,KeyboardInterrupt) as e:record_error(r,'client_report_error',e)
 close_clients(r,[a,b])
 stop_server(r,s,'exit');r['passed']=report_passed(r,('exit',),EXPECTED_ASSERTIONS)
 write_report(out/(variant+'-configuration-overlap.json'),r);print(json.dumps(r,indent=2),flush=True)
raise SystemExit(0 if r['passed'] else 1)
