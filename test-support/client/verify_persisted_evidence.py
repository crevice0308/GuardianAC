"""Read-only verification of the real-client ban's persisted supporting evidence."""
import hashlib,json,sqlite3,sys,os
from pathlib import Path
root=Path(__file__).resolve().parent
sys.path.insert(0,os.environ.get('GUARDIAN_RUNTIME_SUPPORT',str(root.parent/'runtime')))
from terminal_gates import write_report
run=sys.argv[1];variant=sys.argv[2]
p=Path(os.environ['GUARDIAN_RUNTIME_OUTPUT'])/run/variant/'plugins/GuardianAC/bans/bans.db'
c=sqlite3.connect('file:'+str(p)+'?mode=ro',uri=True);c.row_factory=sqlite3.Row
rows=c.execute('select b.player_name,b.player_uuid,b.case_id,b.type,b.vl,b.created_at,b.expires_at,e.payload,e.sha256 from bans b join evidence e on b.evidence_id=e.id').fetchall();c.close();out=[]
for row in rows:
 record=dict(row);payload=record.pop('payload');ev=json.loads(payload);record['hash_matches']=hashlib.sha256(payload.encode()).hexdigest()==record['sha256'];record['independent_families']=ev['families'];record['findings']=ev['findings'];record['replay_kinds']=sorted(set(f['kind'] for f in ev['replay']))
 record['raw_slot15_present']=any(f['observation']['measurements'].get('held-slot')==15 for f in ev['findings'])
 record['raw_pitch100_present']=any(f['observation']['measurements'].get('pitch')==100 for f in ev['findings'])
 out.append(record)
passed=len(out)==1 and all(r['player_name']=='GacRealBad' and r['type']=='UUID' and r['hash_matches'] and r['independent_families']>=2 and r['raw_slot15_present'] and r['raw_pitch100_present'] for r in out)
result={'test':'persisted_evidence_integrity_and_raw_independent_observations','passed':passed,'records':out}
write_report(Path(os.environ['GUARDIAN_CLIENT_OUTPUT'])/run/(variant+'-persisted-evidence.json'),result);print(json.dumps(result,indent=2))
raise SystemExit(0 if passed is True else 1)
