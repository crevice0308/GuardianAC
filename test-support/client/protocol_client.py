"""Bounded loopback-only Minecraft Java 26.3 protocol fixture.
Packet IDs/codecs verified from exact Paper 26.3 server bytecode in schema/.
This is a real TCP login/configuration/play client, not a Player mock.
"""
import collections, hashlib, ipaddress, json, math, select, socket, struct, time, uuid, zlib
from pathlib import Path
from fixture_safety import FIXTURE_HOST,validate_client_port
IDS=json.loads((Path(__file__).parent/'schema/packet-ids.json').read_text())
def vi(n):
    n&=0xffffffff; b=bytearray()
    while True:
        c=n&127;n>>=7;b.append(c|(128 if n else 0))
        if not n:return bytes(b)
def rvi(b,o=0):
    n=0
    for i in range(5):
        if o+i>=len(b):raise EOFError('incomplete varint')
        c=b[o+i];n|=(c&127)<<(7*i)
        if c<128:return n,o+i+1
    raise ValueError('oversized varint')
def text(s):
    b=s.encode();return vi(len(b))+b
def offline_uuid(name):
    return uuid.UUID(bytes=hashlib.md5(('OfflinePlayer:'+name).encode()).digest(),version=3)
class Client:
    def __init__(self,port,name,source_host=None,hold_configuration=False):
        port=validate_client_port(port)
        self.hold_configuration=bool(hold_configuration);self.configuration_ready=False;self.name=name;self.port=port;self.uuid=str(offline_uuid(name));self.state='login';self.compression=None
        if source_host is not None:
            source_host=str(ipaddress.IPv4Address(source_host))
            if not ipaddress.IPv4Address(source_host).is_loopback:raise ValueError('Client source bind must remain IPv4 loopback')
        self.s=socket.create_connection((FIXTURE_HOST,port),timeout=10,source_address=(source_host,0) if source_host is not None else None);self.s.setblocking(False)
        self.buf=bytearray();self.packets=collections.Counter();self.sent=collections.Counter();self.events=[];self.closed=False;self.disconnect=None
        self.position=None;self.chunks={};self.fixture_chunk_history=[];self.fixture_chunk_packets=0;self.block_updates=[];self.joined=False;self.loaded=False
        self.send_id(0,vi(777)+text(FIXTURE_HOST)+struct.pack('>H',port)+vi(2))
        self.send_id(0,text(name)+offline_uuid(name).bytes)
    def send_id(self,pid,payload=b''):
        p=vi(pid)+payload
        if self.compression is not None:
            p=(vi(len(p))+zlib.compress(p)) if len(p)>=self.compression else b'\0'+p
        self.s.sendall(vi(len(p))+p)
    def send(self,name,payload=b''):
        self.sent[self.state+':'+name]+=1
        self.send_id(IDS[self.state]['SERVERBOUND']['SERVERBOUND_'+name],payload)
    def close(self):
        self.closed=True;self.s.close()
    def info(self):
        self.send('CLIENT_INFORMATION',text('en_us')+bytes([3,0,1,127,1,0,1,0]))
    def receive(self,pid,payload):
        names={v:k for k,v in IDS[self.state]['CLIENTBOUND'].items()};name=names.get(pid,'UNKNOWN_'+str(pid))
        self.packets[self.state+':'+name]+=1
        if 'DISCONNECT' in name:
            self.disconnect=payload.hex();self.events.append({'disconnect':payload.decode('utf8',errors='replace')});return
        if name.endswith('COOKIE_REQUEST'):
            self.send('COOKIE_RESPONSE',payload+b'\0');return
        if name.endswith('KEEP_ALIVE'):
            self.send('KEEP_ALIVE',payload);return
        if name.endswith('_PING'):
            self.send('PONG',payload);return
        if self.state=='login':
            if name=='CLIENTBOUND_LOGIN_COMPRESSION':self.compression=rvi(payload)[0]
            elif name=='CLIENTBOUND_LOGIN_FINISHED':
                self.events.append({'login':self.uuid});self.send('LOGIN_ACKNOWLEDGED');self.state='configuration';self.info()
            elif name=='CLIENTBOUND_CUSTOM_QUERY':
                query,_=rvi(payload);self.send('CUSTOM_QUERY_ANSWER',vi(query)+b'\0')
        elif self.state=='configuration':
            if name=='CLIENTBOUND_SELECT_KNOWN_PACKS':self.send('SELECT_KNOWN_PACKS',vi(0))
            elif name=='CLIENTBOUND_FINISH_CONFIGURATION':
                self.configuration_ready=True
                if not self.hold_configuration:
                    self.send('FINISH_CONFIGURATION');self.state='game';self.events.append({'play':True})
        elif self.state=='game':
            if name=='CLIENTBOUND_LOGIN':self.joined=True
            elif name=='CLIENTBOUND_PLAYER_POSITION':
                tid,o=rvi(payload); values=struct.unpack_from('>ddddddffi',payload,o)
                x,y,z,dx,dy,dz,yaw,pitch,flags=values
                old=self.position or (0,0,0,0,0)
                vals=[x,y,z,yaw,pitch]
                for i in range(5):
                    if flags&(1<<i):vals[i]+=old[i]
                self.position=tuple(vals)
                self.send('ACCEPT_TELEPORTATION',vi(tid)+struct.pack('>dddff',*self.position))
                if not self.loaded:self.send('PLAYER_LOADED');self.loaded=True
                self.events.append({'position':self.position,'teleport':tid,'flags':flags})
            elif name=='CLIENTBOUND_CHUNK_BATCH_FINISHED':self.send('CHUNK_BATCH_RECEIVED',struct.pack('>f',20.0))
            elif name=='CLIENTBOUND_LEVEL_CHUNK_WITH_LIGHT':
                x,z=struct.unpack_from('>ii',payload);self.chunks[x,z]=payload
                if (x,z)==(2,0):
                    self.fixture_chunk_packets+=1
                    if len(self.fixture_chunk_history)<16:self.fixture_chunk_history.append(payload)
            elif name=='CLIENTBOUND_BLOCK_UPDATE':self.block_updates.append(payload)
    def pump(self,duration=.05):
        until=time.monotonic()+duration
        while not self.closed:
            try:
                ln,o=rvi(self.buf)
                if len(self.buf)>=o+ln:
                    p=bytes(self.buf[o:o+ln]);del self.buf[:o+ln]
                    if self.compression is not None:
                        n,j=rvi(p);p=zlib.decompress(p[j:]) if n else p[j:]
                    pid,j=rvi(p);self.receive(pid,p[j:]);continue
            except EOFError:pass
            wait=until-time.monotonic()
            if wait<=0:return
            rr,_,_=select.select([self.s],[],[],wait)
            if not rr:return
            b=self.s.recv(1048576)
            if not b:self.closed=True;return
            self.buf.extend(b)
    def wait_configuration(self,timeout=20):
        end=time.monotonic()+timeout
        while time.monotonic()<end and not self.closed:
            self.pump(.1)
            if self.state=='configuration' and self.configuration_ready:return True
        return False
    def wait_join(self,timeout=20):
        end=time.monotonic()+timeout
        while time.monotonic()<end and not self.closed:
            self.pump(.1)
            if self.joined and self.position:return True
        return False
    def tick(self,position=None,on_ground=True):
        if position is None:self.send('MOVE_PLAYER_STATUS_ONLY',bytes([1 if on_ground else 0]))
        else:
            self.position=tuple(position);self.send('MOVE_PLAYER_POS_ROT',struct.pack('>dddffB',*position,1 if on_ground else 0))
        self.send('CLIENT_TICK_END');self.pump(.05)
    def report(self):
        return {'name':self.name,'uuid':self.uuid,'state':self.state,'configuration_ready':self.configuration_ready,'hold_configuration':self.hold_configuration,'joined':self.joined,'closed':self.closed,'disconnect':self.disconnect,'position':self.position,'chunks':len(self.chunks),'fixture_chunk_packets':self.fixture_chunk_packets,'packets':dict(self.packets),'sent':dict(self.sent),'events':self.events}
