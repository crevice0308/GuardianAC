"""Read-only follow-up: distinguish sampled context, saved MOVE frames, and drop timing."""
import json,sys
from pathlib import Path
BLOCKING={'exempt','bypass','vehicle','flight','effects','velocity-grace','teleport-grace','special-terrain','protocol-translated','water','climbable','gliding','sneaking','using-item','container-open','water-below','wall-near','firework-grace'}
def analyze(report_path):
    report_path=Path(report_path);root=report_path.parent;r=json.loads(report_path.read_text())
    rows=[]
    for path in root.glob('soak-*.json'):
        a=json.loads(path.read_text())
        p=next((p for p in a.get('players',[]) if p['name']=='GacModClean'),None)
        if p is None:continue
        rows.append({'label':a['label'],'timeMillis':a['timeMillis'],'ping_ms':p['ping'],'flags':p['flags'],
                     'server_position':[p[k] for k in ('x','y','z')],'dropped':a['dropped'],'tps':a['tps']})
    rows.sort(key=lambda x:x['timeMillis'])
    eligible=[a for a in rows if a['ping_ms']<=200 and a['tps']>=18 and not BLOCKING.intersection(a['flags'])]
    increments=[{'from':a['label'],'to':b['label'],'delta':b['dropped']-a['dropped']} for a,b in zip(rows,rows[1:]) if b['dropped']!=a['dropped']]
    movement={}
    for name,s in r.get('scenarios',{}).items():
        path=root/(name+'-replay.json')
        if not path.exists():continue
        frames=[x for x in json.loads(path.read_text()) if x['kind']=='MOVE' and x['time']>=s.get('scenario_started_millis',0) and x['time']<=s['timeMillis']]
        safe=[x for x in frames if x['measurements'].get('ping',99999)<=200 and x['measurements'].get('tps',0)>=18 and not BLOCKING.intersection(x['flags'])]
        movement[name]={'saved_MOVE_frames':len(frames),'low_ping_modifier_and_grace_free_MOVE_frames':len(safe),'low_ping_modifier_and_grace_free_nonzero_MOVE_frames':sum(abs(x['measurements'].get('horizontal',0))>1e-6 or abs(x['measurements'].get('dy',0))>1e-6 for x in safe)}
    final=r.get('soak',{}).get('all_quit',r.get('final_cleanup',{}))
    return {'artifact_sha256':r.get('jar_sha256'),'soak_snapshot_count':len(rows),
            'persistent_client_sampled_ping_ms':{'min':min(x['ping_ms'] for x in rows),'max':max(x['ping_ms'] for x in rows)} if rows else None,
            'low_ping_modifier_and_grace_free_soak_snapshots':eligible,
            'soak_dropped_samples_first_last':{'first':rows[0]['dropped'],'last':rows[-1]['dropped']} if rows else None,
            'soak_dropped_sample_increments':increments,'final_dropped_samples':final.get('dropped'),
            'saved_pre_soak_MOVE_frame_counts':movement,
            'interpretation':['Soak entries are point-in-time server position/ping/context snapshots. Full soak MOVE replay frames were not retained, so no exact low-ping physics-eligible MOVE-frame count during the soak is claimed.',
                              'If +1 drop increments occur only between a join snapshot and that same account quit snapshot, the timing is consistent with stale owner/session queued-sample rejection. A per-drop cause tag was not recorded, so this remains an inference rather than packet-level attribution.',
                              'The deliberate 1.2-second keepalive latency test is separate from the sampled ping range during the soak.']}
if __name__=='__main__':
    result=analyze(sys.argv[1]);encoded=json.dumps(result,indent=2)
    if len(sys.argv)>2:Path(sys.argv[2]).write_text(encoded+'\n')
    else:print(encoded)
