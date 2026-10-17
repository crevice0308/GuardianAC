"""Independent loopback modifier/latency/TPS and 30-minute resource trace.
Import only the separate, bytecode-verified real TCP client/server lifecycle helpers.
No sample injection, Player proxy, clock modification or production engine mutation.
"""
import sys,os,time,json,shutil,re,struct,math,sqlite3,traceback,subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parent
PROJECT=Path(os.environ['GUARDIAN_PROJECT_ROOT']).resolve()
CLIENT_SUPPORT=Path(os.environ['GUARDIAN_CLIENT_SUPPORT']).resolve()
sys.path[:0]=[str(CLIENT_SUPPORT),str(ROOT.parent/'runtime')]
from protocol_client import Client,IDS,vi
from server_harness import prepare,Server,sha,JAVA
from fixture_safety import fixture_port
from terminal_gates import close_clients,stop_server,report_passed,write_report,stops_passed
from modifier_client import ModifierClient
variant=sys.argv[1] if len(sys.argv)>1 else 'paper-26.3'
run=sys.argv[2] if len(sys.argv)>2 else 'modifier-v1'
soak_seconds=int(sys.argv[3]) if len(sys.argv)>3 else 1800
assert soak_seconds in (0,1800),'only explicit diagnostic/no-soak or full 30-minute window'
out=Path(os.environ.get('GUARDIAN_MODIFIER_OUTPUT',str(Path.cwd()/'guardianac-modifier-evidence')))/run;out.mkdir(parents=True,exist_ok=False)
d=prepare(variant,run);jar=PROJECT/'build/libs/GuardianAC-Ultimate-1.0.0-dev.1.jar'
expected_sha=os.environ['GUARDIAN_PLUGIN_SHA256']
assert re.fullmatch('[0-9a-f]{64}',expected_sha) and sha(jar)==expected_sha,'verify the exact authorized runtime artifact' 
fixture_jar=Path(os.environ['GUARDIAN_MODIFIER_FIXTURE_JAR']).resolve()
shutil.copy2(jar,d/'plugins/GuardianAC.jar');shutil.copy2(fixture_jar,d/'plugins/ModifierFixture.jar')
p=d/'server.properties';p.write_text(re.sub(r'(?m)^white-list=.*$','white-list=false',p.read_text()))
s=None;port=fixture_port(variant);clients=[]
r={'variant':variant,'network_only':os.environ.get('GUARDIAN_MODIFIER_NETWORK_ONLY')=='true','cleanup_only':os.environ.get('GUARDIAN_MODIFIER_CLEANUP_ONLY')=='true','jar_sha256':sha(jar),'server_sha256':sha(d/'server.jar'),'fixture_sha256':sha(fixture_jar),'runner_sha256':sha(Path(__file__)),'client_extension_sha256':sha(ROOT/'modifier_client.py'),'assertions':[],'scenarios':{},'limitations':['Minimal independent real TCP client, not rendered Mojang client or complete vanilla prediction.','Grounded conservative trajectories show accepted clean/modifier context, not maximal vanilla movement reconstruction.','Elytra and riptide dynamics not tested: authoritative item-use/equipment and exact client prediction not implemented.','Vehicle fixture verifies real stationary passenger exemption only; vehicle steering and movement not tested.','Knockback applies server-authoritative velocity and source-derived airborne recurrence; collision variants not tested.','Soak is two simultaneous loopback clients, repeated joins/quits, 30 minutes; not production-scale endurance.']}
class FixtureStop(RuntimeError):pass
def check_stop():
 marker=out/'STOP'
 if marker.exists():raise FixtureStop(marker.read_text()[:2000] or 'Explicit fixture stop requested')
def counter_snapshot():
 # Read existing protocol-client counters only; never alter packet generation or delivery.
 return [{'name':c.name,'joined':c.joined,'closed':c.closed,'disconnect':c.disconnect,
          'sent':dict(c.sent),'received':dict(c.packets),
          'pending_delayed_keepalives':len(c.delayed),'delayed_keepalives_total':c.delayed_count}
         for c in clients]
def save():
 r['client_counters']=counter_snapshot()
 r['client_counter_scope']='Named send() call counters and fully decoded received frames. Initial handshake/login-start send_id() calls are excluded. These are application counters, not a wire capture or proof of server-side evaluation.'
 write_report(out/'report.json',r)
def check(name,ok,detail=None):
 e={'test':name,'passed':bool(ok)}
 if detail is not None:e['detail']=detail
 r['assertions'].append(e);print('GACMODTEST '+json.dumps(e),flush=True);save()
def command(cmd):
 n=s.cmd('gacmod '+cmd);s.expect('GACMOD COMMAND '+cmd.split()[0]+' DONE',timeout=40,start=n)
def report(label):
 command('report '+label);a=json.loads((d/'plugins/ModifierFixture'/(label+'.json')).read_text())
 a['client_counters']=counter_snapshot()
 dbs=sorted((d/'plugins/GuardianAC').rglob('*.db'),key=lambda p:p.name!='bans.db')
 if dbs:
  db=sqlite3.connect('file:'+str(dbs[0])+'?mode=ro',uri=True)
  try:a['sqlite_live']={'bans':db.execute('SELECT count(*) FROM bans').fetchone()[0],'evidence':db.execute('SELECT count(*) FROM evidence').fetchone()[0],'database_bytes':dbs[0].stat().st_size,'wal_bytes':Path(str(dbs[0])+'-wal').stat().st_size if Path(str(dbs[0])+'-wal').exists() else 0}
  finally:db.close()
 write_report(out/(label+'.json'),a);return a
def ticks(c,n):
 for _ in range(n):
  if c.closed or c.disconnect:raise RuntimeError('client disconnected: '+c.name+' '+str(c.disconnect))
  c.tick()
def make(name):
 c=ModifierClient(port,name);c.stop_check=check_stop;clients.append(c);check(name+'_join',c.wait_join());c.pump(.5);ticks(c,10);return c
def condition(c,kind,settle=55):
 c.scenario_started_millis=int(time.time()*1000);c.input(0);c.sprint(False);command('condition '+c.name+' '+kind);c.pump(.25)
 if settle:ticks(c,settle)
 c.last_requested=None;c.trajectory_corrections=[]
def point(c):return list(c.position)
def move(c,n,step=.08):
 start=point(c)
 for i in range(n):c.tick((start[0]+step*(i+1),start[1],start[2],-90,0),True)
def observe(name,c,flag=None,property=None,expected=None):
 a=report(name);p=next(x for x in a['players'] if x['name']==c.name)
 replay=json.loads((d/'plugins/ModifierFixture'/(name+'-'+c.name+'-replay.json')).read_text());(out/(name+'-replay.json')).write_text(json.dumps(replay,indent=2))
 recent=[x for x in replay if x['time']>=c.scenario_started_millis][-40:];a['recent_flags']=sorted({v for x in recent for v in x['flags']});a['recent_tps_range']=[min(x['measurements'].get('tps',20) for x in recent),max(x['measurements'].get('tps',20) for x in recent)] if recent else []
 a['scenario_started_millis']=c.scenario_started_millis;a['requested_endpoint']=c.last_requested;a['trajectory_corrections']=c.trajectory_corrections
 moves=[x['measurements'] for x in replay if x['time']>=c.scenario_started_millis and x['kind']=='MOVE']
 a['server_move_y_range']=[min(x['y'] for x in moves),max(x['y'] for x in moves)] if moves else None
 r['scenarios'][name]=a
 check(name+'_connected',not c.closed and not c.disconnect)
 check(name+'_zero_findings',not p['findings'],p['findings'])
 check(name+'_zero_context_capture_failures',a['contextCaptureFailures']==0,a['contextCaptureFailures'])
 check(name+'_production_movement_observed',bool(moves),len(moves))
 if name=='sprint':check('clean_not_blanket_exempt',not set(p['flags'])&{'exempt','bypass','protocol-translated','teleport-grace','velocity-grace','effects','vehicle','flight'},p['flags'])
 expected_position=c.last_requested or c.position
 check(name+'_accepted_requested_location',all(abs(p[k]-expected_position[i])<.035 for i,k in enumerate(('x','y','z'))),{'server':[p[k] for k in ('x','y','z')],'requested':expected_position[:3]})
 check(name+'_no_trajectory_corrections',not c.trajectory_corrections,c.trajectory_corrections)
 if flag:check(name+'_expected_context',flag in p['flags'] or flag in a['recent_flags'],{'expected':flag,'flags':p['flags'],'recent_flags':a['recent_flags']})
 if property:check(name+'_server_state',p[property]==expected,{property:p[property]})
 return a
try:
 s=Server(d,'modifiers')
 s.expect('Done \\(');s.expect('GuardianAC internal development build enabled',timeout=5);s.cmd('difficulty peaceful');command('setup');c=make('GacModClean');ticks(c,65)
 if os.environ.get('GUARDIAN_MODIFIER_NETWORK_ONLY')!='true' and os.environ.get('GUARDIAN_MODIFIER_CLEANUP_ONLY')!='true':
  # Conservative grounded packet movement below normal legal speeds, actual sprint/sneak input.
  c.input(65);c.sprint(True);move(c,80,.25);observe('sprint',c,property='sprinting',expected=True)
  condition(c,'stone');c.input(33);move(c,65,.045);observe('sneak',c,'sneaking','sneaking',True)
  for mode,flag,step in [('water','water',.02),('slab','special-terrain',.08),('ice','special-terrain',.08),('slime','special-terrain',.04),('honey','special-terrain',.025),('speed','effects',.30)]:
   condition(c,mode);c.input(1);move(c,65,step);observe(mode,c,flag)
  condition(c,'ladder');start=point(c)
  for i in range(24):c.tick((start[0],start[1]+.1176*(i+1),start[2],0,0),False)
  c.input(32);observe('ladder',c,'climbable','climbing',True)
  for mode,velocity in [('stone',.42),('jump',.52)]:
   condition(c,mode);start=point(c)
   for rep in range(4):
    y=start[1];vy=velocity
    while True:
     y+=vy;vy=(vy-.08)*.98
     if y<=start[1]:c.tick((start[0],start[1],start[2],0,0),True);break
     c.tick((start[0],y,start[2],0,0),False)
    ticks(c,8)
   observe('jump-'+mode,c,'effects' if mode=='jump' else None)
  # Source-derived .01 descending gravity / .98 air drag with SLOW_FALLING.
  condition(c,'slowfall',0);start=point(c);y=start[1];vy=0
  while y>65:
   y=max(65,y+vy);vy=(vy-.01)*.98;c.tick((start[0],y,start[2],0,0),y==65)
  observe('slowfall',c,'effects')
  # Source-derived LEVITATION recurrence from exact server LivingEntity.travelInAir.
  condition(c,'levitation',0);start=point(c);y=start[1];vy=0
  for i in range(80):
   y+=vy;vy=(vy+(.05-vy)*.2)*.98;c.tick((start[0],y,start[2],0,0),False)
  observe('levitation',c,'effects')
  condition(c,'stone',0);ticks(c,3);observe('teleport',c,'teleport-grace')
  condition(c,'world',0);ticks(c,5);observe('worldchange',c,'teleport-grace')
  condition(c,'stone');start=point(c);command('velocity '+c.name);c.pump(.02);x,y,z=start[:3];vx=.3;vy=.42
  for i in range(25):
   x+=vx;y+=vy
   if y<=65:y=65;vy=0;vx*=.546
   else:vy=(vy-.08)*.98;vx*=.91
   c.tick((x,y,z,0,0),y==65)
  observe('knockback',c,'velocity-grace')
  condition(c,'vehicle',0);ticks(c,25);observe('vehicle',c,'vehicle','vehicle',True)
 if os.environ.get('GUARDIAN_MODIFIER_CLEANUP_ONLY')!='true':
  # Delay only actual server keepalive acknowledgements, 1.2 sec each, no spoofed ping setter.
  condition(c,'stone');c.keepalive_delay=1.2;end=time.monotonic()+46
  while time.monotonic()<end:c.tick()
  a=observe('latency',c);p0=a['players'][0];check('latency_observed_by_server',p0['ping']>=250,{'ping_ms':p0['ping'],'delayed_keepalives':c.delayed_count,'delay_ms':1200});c.keepalive_delay=0;c.pump(1.3)
  # Ten ordinary movement ticks are held for 500 ms then delivered as a bounded TCP burst.
  condition(c,'stone');start=point(c);packets=[]
  for i in range(10):packets.append(struct.pack('>dddffB',start[0]+.05*(i+1),65,start[2],-90,0,1));c.pump(.05)
  for payload in packets:c.send('MOVE_PLAYER_POS_ROT',payload);c.send('CLIENT_TICK_END')
  c.position=(start[0]+.5,65,start[2],-90,0);c.last_requested=c.position;ticks(c,70);observe('bounded-queue',c)
  command('lag 60 80');end=time.monotonic()+6
  while time.monotonic()<end:c.tick()
  a=observe('low-tps',c);check('low_tps_actual_measurement',a['minLagTps']<16,{'minimum_tps':a['minLagTps'],'scheduled_sleeps':60,'sleep_ms':80});check('lag_ended',not a['lagActive']);ticks(c,90)
  check('modifier_trace_zero_malformed',a['malformed']==0,{'malformed':a['malformed'],'cancelled':a['cancelled']})
 # Short real quit/late-inventory-event regression before committing the 30-minute window.
 r['cleanup_regression']=[]
 for index in range(5):
  q=make('GacModPre'+str(index));ticks(q,8);q.close();c.pump(.6)
  snap=report('preflight-quit-'+str(index));r['cleanup_regression'].append(snap)
  keys=['enginePlayers','telemetryPlayers','packetPlayers','replayPlayers','xrayViewers']
  check('preflight_quit_'+str(index)+'_all_state_matches_online',all(snap[k]<=len(snap['players']) for k in keys),{k:snap[k] for k in keys}|{'online':len(snap['players'])})
 lifecycle_path=d/'plugins/ModifierFixture/lifecycle.jsonl'
 if lifecycle_path.exists():
  r['lifecycle_trace']=[json.loads(x) for x in lifecycle_path.read_text().splitlines()];(out/'lifecycle.jsonl').write_text(lifecycle_path.read_text())
 if any(not x['passed'] for x in r['assertions']):raise FixtureStop('A scenario or short quit lifecycle regression failed; no full-soak success is claimed.')
 r['scenario_finished_utc']=time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime());save()
 if soak_seconds:
  condition(c,'stone');r['soak']={'requested_seconds':soak_seconds,'started_utc':time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime()),'samples':[],'joins':0,'quit_cleanups':[]};save()
  start_time=time.monotonic();deadline=start_time+soak_seconds;next_sample=start_time;next_churn=start_time;churn=None;churn_until=0;cycle=0;next_action=start_time;mode='stone';move_counter=0
  print('GACMOD SOAK START '+r['soak']['started_utc'],flush=True)
  while time.monotonic()<deadline:
   now=time.monotonic()
   if now>=next_action:
    mode=['stone','ice','slime','honey','slab','speed'][cycle%6];cycle+=1;condition(c,mode,0);next_action=now+45;move_counter=0
   if now>=next_churn:
    if churn:churn.close();churn=None
    churn=make('GacMod'+str(r['soak']['joins']+1).zfill(3));r['soak']['joins']+=1;churn_until=time.monotonic()+12;next_churn=now+60
   if churn and now>=churn_until:
    name=churn.name;churn.close();churn=None;c.pump(.3);snap=report('soak-cleanup-'+name);r['soak']['quit_cleanups'].append({'name':name,'players':len(snap['players']),'enginePlayers':snap['enginePlayers'],'packetPlayers':snap['packetPlayers'],'replayPlayers':snap['replayPlayers'],'hiddenPlayers':snap['hiddenPlayers'],'telemetryPlayers':snap['telemetryPlayers'],'xrayViewers':snap['xrayViewers']});save()
   # Grounded slow movement alternates direction inside its finite platform, with valid hotbar and punch.
   pos=point(c);delta=.02 if mode=='honey' else .035;direction=1 if (move_counter//200)%2==0 else -1;c.tick((pos[0]+delta*direction,pos[1],pos[2],-90 if direction==1 else 90,0),True);move_counter+=1
   if move_counter%100==0:c.send('SET_CARRIED_ITEM',struct.pack('>h',(move_counter//100)%9));c.send('PUNCH')
   if churn:churn.send('MOVE_PLAYER_STATUS_ONLY',b'\1');churn.send('CLIENT_TICK_END');churn.pump(.001)
   if now>=next_sample:
    a=report('soak-'+str(len(r['soak']['samples'])).zfill(3));a['elapsed_seconds']=time.monotonic()-start_time;r['soak']['samples'].append(a);next_sample=now+60;save();print('GACMOD SOAK METRIC '+json.dumps({k:a[k] for k in ['elapsed_seconds','tps','heapUsed','replayBytes','enginePlayers','packetPlayers','findingsCount','banCount']}),flush=True)
  if churn:churn.close();churn=None
  c.close();time.sleep(1);r['soak']['elapsed_seconds']=time.monotonic()-start_time;r['soak']['ended_utc']=time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime());final=report('all-quit');r['soak']['all_quit']=final
  check('soak_full_30_minutes',r['soak']['elapsed_seconds']>=1800,r['soak']['elapsed_seconds'])
  check('soak_all_player_state_cleaned',all(final[k]==0 for k in ['enginePlayers','telemetryPlayers','packetPlayers','packetQueue','replayPlayers','replayBytes','hiddenPlayers','hiddenPositions','xrayViewers']),{k:final[k] for k in ['enginePlayers','telemetryPlayers','packetPlayers','packetQueue','replayPlayers','replayBytes','hiddenPlayers','hiddenPositions','xrayViewers']})
  check('soak_each_quit_cleaned',all(all(v<=x['players'] for k,v in x.items() if k not in ['name','players']) for x in r['soak']['quit_cleanups']),r['soak']['quit_cleanups'])
  check('soak_zero_findings_bans_or_malformed',final['findingsCount']==0 and final['banCount']==0 and final['malformed']==0,{'findings':final['findingsCount'],'bans':final['banCount'],'malformed':final['malformed']})
  check('soak_replay_bound',max(x['replayBytes'] for x in r['soak']['samples'])<=2*65536,max(x['replayBytes'] for x in r['soak']['samples']))
  check('soak_heap_under_configured_cap',all(x['heapUsed']<=x['heapMax'] for x in r['soak']['samples']))
 r['run_completed']=True
except FixtureStop as e:
 r['error']='FixtureStop: '+str(e);r['termination']={'status':'explicit_or_failed_gate_stop','reason':str(e),'full_soak_completed':False};print(r['error'],flush=True)
except (Exception,KeyboardInterrupt) as e:
 r['error']=repr(e);r['traceback']=traceback.format_exc();print(r['traceback'],flush=True)
finally:
 close_clients(r,clients)
 try:
  time.sleep(.4)
  if s is not None and s.p.poll() is None:
   r['final_cleanup']=report('finally-cleanup')
   keys=['enginePlayers','telemetryPlayers','packetPlayers','packetQueue','replayPlayers','replayBytes','hiddenPlayers','hiddenPositions','xrayViewers']
   check('final_quit_state_zero',all(r['final_cleanup'][k]==0 for k in keys),{k:r['final_cleanup'][k] for k in keys})
   check('final_context_capture_failures_zero',r['final_cleanup']['contextCaptureFailures']==0,r['final_cleanup']['contextCaptureFailures'])
  else:r['final_cleanup_error']='No live server remained to verify final quit cleanup'
 except (Exception,KeyboardInterrupt) as e:r['final_cleanup_error']=repr(e)
 stop_server(r,s)
 if stops_passed(r,('server_exit',)):
  try:
   dbs=sorted((d/'plugins/GuardianAC').rglob('*.db'),key=lambda p:p.name!='bans.db')
   if not dbs:raise RuntimeError('Missing final GuardianAC SQLite database')
   db=sqlite3.connect('file:'+str(dbs[0])+'?mode=ro',uri=True)
   try:r['sqlite']={'size_bytes':dbs[0].stat().st_size,'integrity':db.execute('PRAGMA integrity_check').fetchall(),'bans':db.execute('SELECT count(*) FROM bans').fetchone()[0],'evidence':db.execute('SELECT count(*) FROM evidence').fetchone()[0],'page_count':db.execute('PRAGMA page_count').fetchone()[0]}
   finally:db.close()
   check('sqlite_integrity',r['sqlite']['integrity']==[('ok',)])
  except (Exception,KeyboardInterrupt) as e:r['sqlite_error']=repr(e)
 else:r['sqlite_error']='Final SQLite inspection unavailable: normal-stop/result gate did not pass; database was not read'
 r['passed']=report_passed(r)
 try:save()
 except (Exception,KeyboardInterrupt) as e:
  r['report_snapshot_error']=repr(e);r['passed']=False
  write_report(out/'report.json',r)
 print('GACMOD FINISHED '+json.dumps({'error':r.get('error'),'passed':r['passed'],'assertions_passed':sum(x['passed'] for x in r['assertions']),'total':len(r['assertions']),'exit':r['server_exit']}),flush=True)

raise SystemExit(0 if r['passed'] else 1)
