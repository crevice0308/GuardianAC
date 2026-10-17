"""Produce a compact, deliberately bounded summary from one preserved fixture report."""
import json,sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parent.parent/'runtime'))
from terminal_gates import report_passed

def summarize(r):
    assertions=r.get('assertions',[])
    soak=r.get('soak',{})
    samples=soak.get('samples',[])
    def bounds(key):
        values=[x[key] for x in samples if key in x]
        return {'min':min(values),'max':max(values)} if values else None
    cleanups=soak.get('quit_cleanups',[])
    final=soak.get('all_quit',r.get('final_cleanup',{}))
    cache_keys=['enginePlayers','telemetryPlayers','packetPlayers','packetQueue','replayPlayers','replayBytes','hiddenPlayers','hiddenPositions','xrayViewers']
    full_pass=bool(soak.get('elapsed_seconds',0)>=1800 and report_passed(r)
                   and all(final.get(k)==0 for k in cache_keys)
                   and r.get('sqlite',{}).get('integrity')==[['ok']])
    scenarios={name:{'requested_endpoint':data.get('requested_endpoint'),
                     'observed_y_range':data.get('server_move_y_range'),
                     'corrective_teleports':len(data.get('trajectory_corrections',[])),
                     'findings':sum(len(p['findings']) for p in data.get('players',[])),
                     'flags':data.get('players',[{}])[0].get('flags',[])}
               for name,data in r.get('scenarios',{}).items()}
    failures=[x for x in assertions if not x['passed']]+r.get('post_run_findings',[])
    return {'artifact_sha256':r.get('jar_sha256'),'server_sha256':r.get('server_sha256'),
            'full_30_minute_soak_passed':full_pass,'error':r.get('error'),'termination':r.get('termination'),
            'assertions_passed':sum(x['passed'] for x in assertions),'assertions_total':len(assertions),
            'failed_assertions_or_post_run_findings':failures,'scenario_results':scenarios,
            'actual_soak_seconds':soak.get('elapsed_seconds'),'started_utc':soak.get('started_utc'),
            'ended_utc':soak.get('ended_utc'),'churn_joins':soak.get('joins'),'quit_cleanup_observations':len(cleanups),
            'sample_count':len(samples),'sampled_tps':bounds('tps'),'sampled_heap_used_bytes':bounds('heapUsed'),
            'configured_jvm_heap_cap_bytes':samples[0].get('heapMax') if samples else None,
            'sampled_gc_count':bounds('gcCount'),'sampled_replay_bytes':bounds('replayBytes'),
            'sampled_packet_queue':bounds('packetQueue'),
            'final_player_state_counts':{k:final.get(k) for k in cache_keys},
            'final_findings':final.get('findingsCount'),'final_bans':final.get('banCount'),
            'final_context_capture_failures':final.get('contextCaptureFailures'),'final_malformed_packets':final.get('malformed'),'final_cancelled_packets':final.get('cancelled'),
            'sqlite':r.get('sqlite'),'server_exit':r.get('server_exit'),
            'limitations':r.get('limitations',[])+['Heap values are periodic samples, not a retained-object analysis or lifetime maximum; this run is not a production-scale leak-proof certification.']}

if __name__=='__main__':
    source=Path(sys.argv[1]);result=summarize(json.loads(source.read_text()))
    encoded=json.dumps(result,indent=2)
    if len(sys.argv)>2:Path(sys.argv[2]).write_text(encoded+'\n')
    else:print(encoded)
