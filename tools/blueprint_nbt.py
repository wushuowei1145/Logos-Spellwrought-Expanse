"""Small big-endian NBT reader and deterministic structure writer; no runtime dependency."""
import struct,gzip,json
from pathlib import Path

class Reader:
    def __init__(self,data):self.data=data;self.offset=0
    def read(self,n):
        value=self.data[self.offset:self.offset+n];self.offset+=n
        if len(value)!=n:raise ValueError('Truncated NBT')
        return value
    def number(self,fmt):return struct.unpack('>'+fmt,self.read(struct.calcsize(fmt)))[0]
    def text(self):return self.read(self.number('H')).decode('utf-8')
    def value(self,kind):
        if 1<=kind<=6:return self.number({1:'b',2:'h',3:'i',4:'q',5:'f',6:'d'}[kind])
        if kind==8:return self.text()
        if kind==9:
            child,n=self.number('B'),self.number('i');return [self.value(child) for _ in range(n)]
        if kind==10:
            value={}
            while (child:=self.number('B')):
                name=self.text();value[name]=self.value(child)
            return value
        if kind in [7,11,12]:return [self.number({7:'b',11:'i',12:'q'}[kind]) for _ in range(self.number('i'))]
        raise ValueError('Unsupported NBT tag '+str(kind))

def read_nbt(path):
    data=Path(path).read_bytes();data=gzip.decompress(data) if data[:2]==b'\x1f\x8b' else data
    reader=Reader(data);kind=reader.number('B');reader.text();return reader.value(kind)

def _text(value):value=value.encode('utf-8');return struct.pack('>H',len(value))+value
def _kind(value):
    if isinstance(value,str):return 8
    if isinstance(value,dict):return 10
    if isinstance(value,list):return 9
    if isinstance(value,float):return 6
    return 3
def _payload(value):
    if isinstance(value,str):return _text(value)
    if isinstance(value,dict):return b''.join(bytes([_kind(v)])+_text(k)+_payload(v) for k,v in value.items())+b'\0'
    if isinstance(value,list):return bytes([_kind(value[0]) if value else 10])+struct.pack('>i',len(value))+b''.join(_payload(v) for v in value)
    if isinstance(value,float):return struct.pack('>d',value)
    return struct.pack('>i',value)

def write_structure(path,size,cells,block_entities=None,data_version=0,entities=None):
    palette=[];lookup={};blocks=[];block_entities=block_entities or {}
    for pos,state in sorted(cells.items(),key=lambda v:(v[0][1],v[0][2],v[0][0])):
        key=json.dumps(state,sort_keys=True);index=lookup.get(key)
        if index is None:index=len(palette);lookup[key]=index;palette.append(state)
        entry={'pos':list(pos),'state':index}
        if pos in block_entities:entry['nbt']=block_entities[pos]
        blocks.append(entry)
    doc={'DataVersion':data_version,'size':list(size),'palette':palette,'blocks':blocks,'entities':entities or []}
    path=Path(path);path.parent.mkdir(parents=True,exist_ok=True)
    raw=gzip.compress(b'\x0a\0\0'+_payload(doc),mtime=0)
    for attempt in range(5):
        try:
            path.write_bytes(raw);break
        except OSError:
            if attempt==4:raise
            import time;time.sleep(0.05)
