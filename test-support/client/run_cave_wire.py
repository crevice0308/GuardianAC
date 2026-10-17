"""Opt-in, capacity-bounded cave wire path; no graphical-rendering claim."""
import os,sys,json,re,shutil,time
from pathlib import Path
ROOT=Path(__file__).resolve().parent;sys.path.insert(0,os.environ.get('GUARDIAN_RUNTIME_SUPPORT',str(ROOT.parent/'runtime')))
from server_harness import prepare,Server,sha
from fixture_safety import fixture_port
from terminal_gates import close_clients,stop_server,report_passed,write_report
from protocol_client import Client
from chunk_decode import decode_chunk,at,block_update
EXPECTED_ASSERTIONS=frozenset(['bounded_cavity_concealment_capacity', 'cave_bound_native_context_without_capture_failures', 'cave_client_connected_without_findings', 'cave_client_real_join', 'cave_fixture_chunk_received', 'cavity_and_wall_world_unchanged_after_reveal', 'cavity_world_air_unchanged', 'far_cavity_air_masked_on_wire', 'near_cavity_authoritative_air_reveal', 'overcapacity_column_stays_fail_open'])
variant='paper-26.3';run=sys.argv[1] if len(sys.argv)>1 else 'cave'
d=prepare(variant,run);out=Path(os.environ['GUARDIAN_CLIENT_OUTPUT'])/run;out.mkdir(parents=True,exist_ok=True)
shutil.copy2(Path(os.environ['GUARDIAN_CLIENT_FIXTURE_JAR']),d/'plugins/RealClientFixture.jar')
p=d/'server.properties';p.write_text(re.sub(r'(?m)^white-list=.*$','white-list=false',p.read_text()))
s=None;c=None;r={'server':variant,'jar_sha256':sha(d/'plugins/GuardianAC.jar'),'server_sha256':sha(d/'server.jar'),'cavity_air_blocks':27,'per_chunk_capacity':4096,'assertions':[]}
def check(name,ok,detail=None):
 x={'test':name,'passed':bool(ok)}
 if detail is not None:x['detail']=detail
 r['assertions'].append(x);print('GACREALTEST '+json.dumps(x),flush=True)
def cmd(command):
 n=s.cmd('gacreal '+command);s.expect('GACREAL COMMAND '+command.split()[0]+' DONE',timeout=20,start=n)
def report(label):
 cmd('report '+label);return json.loads((d/'plugins/RealClientFixture'/(label+'.json')).read_text())
try:
 s=Server(d,'cave-wire')
 s.expect('Done \\(')
 if not any('internal development build enabled:' in line for line in s.lines):raise RuntimeError('GuardianAC telemetry inactive')
 s.cmd('difficulty peaceful');cmd('setup');cmd('cavesetup')
 cfg=d/'plugins/GuardianAC/config.yml';before=cfg.read_text();after=re.sub(r'(?m)^(\s*hide-caves:)\s*false\s*$',r'\1 true',before)
 if after==before:raise RuntimeError('Expected default disabled cave setting was not found')
 cfg.write_text(after);n=s.cmd('gac reload');s.expect(r'GuardianAC の設定を読み込みました。|GuardianAC configuration loaded\.',timeout=20,start=n)
 r['settings']='xray.hide-caves=true through config.yml and normal gac reload'
 c=Client(fixture_port(variant),'GacRealCave');check('cave_client_real_join',c.wait_join());c.pump(.5)
 for _ in range(100):c.tick()
 far=report('cave-far');r['far']=far
 raw=c.chunks.get((2,0));check('cave_fixture_chunk_received',raw is not None)
 if raw is None:raise RuntimeError('Missing cave chunk')
 (out/'paper-26.3-cave-far-chunk.bin').write_bytes(raw);decoded=decode_chunk(raw);state=at(decoded,40,-24,8);r['far_cavity_state']=state
 check('far_cavity_air_masked_on_wire',state==far['deepslateId'],state)
 check('cavity_world_air_unchanged',far['worldCave']=='AIR' and far['worldCaveWall']=='STONE')
 check('bounded_cavity_concealment_capacity',0<far['concealedBlocks']<=4096,far['concealedBlocks'])
 # Untouched neighboring flat-world column has >4096 eligible air blocks.
 # Decode and report the documented atomic fail-open behavior explicitly.
 large=c.chunks.get((3,0));r['oversized_column_received']=large is not None
 if large:
  (out/'paper-26.3-cave-overcapacity-chunk.bin').write_bytes(large);ld=decode_chunk(large)
  eligible=sum(at(ld,x,y,z)==0 for y in range(-63,0) for z in range(1,15) for x in range(49,63))
  r['oversized_air_candidates']=eligible;r['oversized_wire_state']=at(ld,56,-24,8)
  check('overcapacity_column_stays_fail_open',eligible>4096 and r['oversized_wire_state']==0 and far['skippedChunks']>0,{'eligible_air':eligible,'wire_state':r['oversized_wire_state'],'skipped':far['skippedChunks']})
 cmd('cavenear GacRealCave')
 for _ in range(40):c.tick()
 updates=[p for p in map(block_update,c.block_updates) if (p['x'],p['y'],p['z'])==(40,-24,8)];r['cavity_updates']=updates
 check('near_cavity_authoritative_air_reveal',any(p['state']==0 for p in updates),updates)
 near=report('cave-near');r['near']=near
 check('cavity_and_wall_world_unchanged_after_reveal',near['worldCave']=='AIR' and near['worldCaveWall']=='STONE')
 check('cave_bound_native_context_without_capture_failures',near['contextCaptureFailures']==0 and len(near['players'])==1 and near['players'][0]['exactProtocol'] and near['players'][0]['packetStateToken']>0)
 check('cave_client_connected_without_findings',not c.closed and c.disconnect is None and all(not p['findings'] for p in near['players']))
 r['run_completed']=True
except (Exception,KeyboardInterrupt) as e:r['error']=repr(e)
finally:
 close_clients(r,[c],reports_key='clients')
 if r['clients']:r['client']=r['clients'][0]
 stop_server(r,s,'exit');r['passed']=report_passed(r,('exit',),EXPECTED_ASSERTIONS)
 write_report(out/'paper-26.3-cave.json',r);print(json.dumps(r,indent=2))
raise SystemExit(0 if r['passed'] else 1)
