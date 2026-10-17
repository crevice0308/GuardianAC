"""Independent wire decoder for 26.3 heightmap map and paletted chunk sections.
The fixed-size packed-long storage (no array length), fluid count and enum
heightmaps were verified from exact Paper server codecs saved under schema/.
"""
import struct
from protocol_client import rvi

def palette(data,o,count,indirect_max):
    bits=data[o];o+=1
    if bits==0:
        state,o=rvi(data,o);return [state]*count,o
    pal=None
    if bits<=indirect_max:
        n,o=rvi(data,o);pal=[]
        for _ in range(n):
            value,o=rvi(data,o);pal.append(value)
    per=64//bits;n=(count+per-1)//per
    words=struct.unpack_from('>'+str(n)+'Q',data,o);o+=8*n
    vals=[(words[i//per]>>(i%per*bits))&((1<<bits)-1) for i in range(count)]
    return ([pal[i] for i in vals] if pal is not None else vals),o

def decode_chunk(payload,min_y=-64,height=384):
    x,z=struct.unpack_from('>ii',payload);n,o=rvi(payload,8);heightmaps={}
    for _ in range(n):
        typ,o=rvi(payload,o);ln,o=rvi(payload,o);heightmaps[typ]=payload[o:o+ln*8].hex();o+=ln*8
    ln,o=rvi(payload,o);raw=payload[o:o+ln];q=0;sections=[]
    for i in range(height//16):
        non_air,fluid=struct.unpack_from('>hh',raw,q);q+=4
        blocks,q=palette(raw,q,4096,8);biomes,q=palette(raw,q,64,3)
        sections.append({'non_air':non_air,'fluid':fluid,'blocks':blocks,'biomes':biomes})
    if q!=len(raw):raise ValueError(f'Chunk section trailing bytes {q}/{len(raw)}')
    return {'x':x,'z':z,'heightmaps':heightmaps,'sections':sections,'length':ln,'min_y':min_y}
def at(chunk,x,y,z):
    yy=y-chunk['min_y'];return chunk['sections'][yy//16]['blocks'][(yy%16)*256+(z%16)*16+x%16]
def block_update(payload):
    v=struct.unpack_from('>Q',payload)[0];x=v>>38;z=(v>>12)&0x3ffffff;y=v&0xfff
    if x>=1<<25:x-=1<<26
    if z>=1<<25:z-=1<<26
    if y>=1<<11:y-=1<<12
    state,_=rvi(payload,8);return {'x':x,'y':y,'z':z,'state':state}
