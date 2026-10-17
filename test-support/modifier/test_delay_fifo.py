"""Unit check for the fixture's latency queue, independent of a server process."""
import collections,sys,unittest,os
from pathlib import Path
sys.path.insert(0,os.environ.get('GUARDIAN_CLIENT_SUPPORT',str(Path(__file__).resolve().parent.parent/'client')))
sys.path.insert(0,os.environ.get('GUARDIAN_RUNTIME_SUPPORT',str(Path(__file__).resolve().parent.parent/'runtime')))
from modifier_client import ModifierClient,IDS
class DelayQueueTest(unittest.TestCase):
 def test_disabling_delay_does_not_overtake_previous_keepalive(self):
  c=ModifierClient.__new__(ModifierClient);c.state='game';c.keepalive_delay=1.2;c.delayed=[];c.delayed_count=0;c.packets=collections.Counter()
  pid=IDS['game']['CLIENTBOUND']['CLIENTBOUND_KEEP_ALIVE']
  c.receive(pid,b'first');c.keepalive_delay=0;c.receive(pid,b'second')
  self.assertEqual([x[1] for x in c.delayed],[b'first',b'second'])
  self.assertLess(c.delayed[0][0],c.delayed[1][0])
 def test_stop_callback_prevents_next_packet(self):
  c=ModifierClient.__new__(ModifierClient)
  def stop():raise RuntimeError('explicit fixture stop')
  c.stop_check=stop
  with self.assertRaisesRegex(RuntimeError,'explicit fixture stop'):c.tick()
if __name__=='__main__':unittest.main()
