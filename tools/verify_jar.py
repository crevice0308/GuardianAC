#!/usr/bin/env python3
"""Audit the assembled single JAR without running a server."""
import collections, hashlib, json, pathlib, sys, zipfile
from verify_embedded_telemetry import inspect as inspect_embedded_telemetry
p=pathlib.Path(sys.argv[1]);issues=[]
with zipfile.ZipFile(p) as z:
    names=z.namelist();members=set(names)
    for required in ['plugin.yml','config.yml','dev/guardianac/bukkit/GuardianPlugin.class',
        'dev/guardianac/bukkit/RuntimeMessages.class','locales/runtime_ja.properties','locales/runtime_en.properties',
        'dev/guardianac/libs/gson/Gson.class','dev/guardianac/libs/pe/api/packetevents/PacketEvents.class',
        'dev/guardianac/libs/pe/impl/packetevents/factory/spigot/SpigotPacketEventsBuilder.class','org/sqlite/JDBC.class',
        'META-INF/services/java.sql.Driver','META-INF/guardianac/LICENSE.txt',
        'META-INF/guardianac/NOTICE.txt','META-INF/guardianac/THIRD-PARTY-NOTICES.md']:
        if required not in members:issues.append('missing '+required)
    for prefix in ['org/bukkit/','io/netty/','com/github/retrooper/','io/github/retrooper/','com/google/gson/']:
        if any(n.startswith(prefix) for n in names):issues.append('unexpected unrelocated/server-provided '+prefix)
    plugin=z.read('plugin.yml').decode()
    if 'main: dev.guardianac.bukkit.GuardianPlugin' not in plugin:issues.append('wrong plugin entrypoint')
    if names.count('plugin.yml')!=1:issues.append('duplicate plugin metadata')
    duplicates=[name for name,count in collections.Counter(names).items() if count>1 and not name.endswith('/')]
    if duplicates:issues.append('duplicate ZIP members: '+', '.join(duplicates[:10]))
    rank=lambda name:(0 if name=='META-INF/' else 1 if name=='META-INF/MANIFEST.MF' else 2,name)
    if names!=sorted(names,key=rank):issues.append('non-canonical ZIP member order')
    if any(i.date_time!=(1980,1,1,0,0,0) for i in z.infolist()):issues.append('non-canonical archive timestamps')
    if 'org.sqlite.JDBC' not in z.read('META-INF/services/java.sql.Driver').decode():issues.append('missing SQLite JDBC service registration')
    if not any(n.startswith('org/sqlite/native/Linux/') for n in names):issues.append('missing bundled Linux SQLite native')
    if not any(n.startswith('org/sqlite/native/Windows/') for n in names):issues.append('missing bundled Windows SQLite native')
telemetry=inspect_embedded_telemetry(p)
issues.extend(telemetry['issues'])
result={'embedded_metrics_audit':telemetry,'file':p.name,'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'bytes':p.stat().st_size,'entries':len(names),'issues':issues}
print(json.dumps(result,indent=2));sys.exit(bool(issues))
