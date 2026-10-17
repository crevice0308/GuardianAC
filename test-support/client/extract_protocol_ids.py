"""Extract exact server registration order, never guess cross-version IDs.
Usage: python extract_protocol_ids.py /path/to/paper-26.3.jar /path/to/javap
"""
import json,re,sys,subprocess
from pathlib import Path
root=Path(__file__).resolve().parent/'schema';root.mkdir(exist_ok=True)
mapping={}
for cls in ['configuration.ConfigurationProtocols','login.LoginProtocols','game.GameProtocols']:
 result=subprocess.run([sys.argv[2],'-classpath',sys.argv[1],'-c','-p','net.minecraft.network.protocol.'+cls],check=True,capture_output=True,text=True)
 (root/(cls+'.txt')).write_text(result.stdout);dirs={}
 for direction in ['CLIENTBOUND','SERVERBOUND']:
  names=list(dict.fromkeys(re.findall(r'// Field \S+PacketTypes\.('+direction+r'_\w+):',result.stdout)))
  dirs[direction]={v:i for i,v in enumerate(names)}
 mapping[cls.split('.')[0]]=dirs
(root/'packet-ids.json').write_text(json.dumps(mapping,indent=2))
