"""Real protocol fixture extension; preserves FIFO when latency is enabled/disabled."""
import struct,time
from protocol_client import Client,IDS,vi
class ModifierClient(Client):
 def __init__(self,*args):
  self.keepalive_delay=0;self.delayed=[];self.delayed_count=0;self.entity_id=None;self.last_requested=None;self.trajectory_corrections=[];self.scenario_started_millis=int(time.time()*1000)
  super().__init__(*args)
 def receive(self,pid,payload):
  if self.state=='game' and pid==IDS['game']['CLIENTBOUND']['CLIENTBOUND_LOGIN']:
   self.entity_id=struct.unpack_from('>i',payload)[0]
  if self.state=='game' and pid==IDS['game']['CLIENTBOUND']['CLIENTBOUND_KEEP_ALIVE'] and (self.keepalive_delay or self.delayed):
   self.packets['game:CLIENTBOUND_KEEP_ALIVE']+=1;self.delayed_count+=1
   due=max(time.monotonic()+self.keepalive_delay,self.delayed[-1][0]+.000001 if self.delayed else 0)
   self.delayed.append((due,payload));return
  super().receive(pid,payload)
 def pump(self,duration=.05):
  end=time.monotonic()+duration
  while not self.closed:
   due=[x for x in self.delayed if x[0]<=time.monotonic()]
   for x in due:self.send('KEEP_ALIVE',x[1]);self.delayed.remove(x)
   left=end-time.monotonic()
   if left<=0:return
   super().pump(min(.05,left))
 def tick(self,position=None,on_ground=True):
  if hasattr(self,'stop_check'):self.stop_check()
  if position is not None:self.last_requested=tuple(position)
  before=len(self.events)
  super().tick(position,on_ground)
  if position is not None:
   for e in self.events[before:]:
    if 'teleport' in e and any(abs(e['position'][i]-position[i])>.035 for i in range(3)):
     self.trajectory_corrections.append({'requested':position[:3],'accepted':e['position'][:3],'teleport':e['teleport']})
 def input(self,bits):self.send('PLAYER_INPUT',bytes([bits]))
 def sprint(self,enabled):self.send('PLAYER_COMMAND',vi(self.entity_id)+vi(1 if enabled else 2)+vi(0))
