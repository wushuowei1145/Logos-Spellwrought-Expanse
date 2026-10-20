"""One-time import of user-supplied geometry. Never imports entities, commands or block NBT."""
from pathlib import Path
from collections import Counter
import json,math,hashlib,sys
from blueprint_nbt import read_nbt

ROOT=Path(__file__).resolve().parent.parent
SOURCES=[('5a8070d81a864ece9ddb490b9b311aa8.litematic','reference_01'),('c82ba2a7c02148ba812fc5380b4b773a.litematic','reference_02'),('casa-medieval (1).nbt','stone_cottage'),('casa-medieval (2).nbt','long_house'),('casa-medieval.nbt','gabled_house'),('medieval-house.nbt','timber_house'),('simple-medieval-house.nbt','garden_house')]
def write(p,v):p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(v,ensure_ascii=False,separators=(',',':'))+'\n',encoding='utf-8')
def import_all(folder):
    manifest=[]
    for filename,name in SOURCES:
        path=Path(folder)/filename;doc=read_nbt(path);cells={}
        if 'Regions' in doc:
            for region in doc['Regions'].values():
                sizes=[int(region['Size'][axis]) for axis in 'xyz'];position=[int(region['Position'][axis])+min(0,sizes[i]+1) for i,axis in enumerate('xyz')]
                sx,sy,sz=map(abs,sizes);palette=region['BlockStatePalette'];bits=max(2,(len(palette)-1).bit_length());mask=(1<<bits)-1;words=[n&((1<<64)-1) for n in region['BlockStates']]
                for index in range(sx*sy*sz):
                    bit=index*bits;word,shift=divmod(bit,64);value=words[word]>>shift
                    if shift+bits>64:value|=words[word+1]<<(64-shift)
                    block=palette[value&mask]
                    if block['Name'] in ['minecraft:air','minecraft:cave_air','minecraft:void_air']:continue
                    x=index%sx;y=index//(sx*sz);z=index//sx%sz;cells[x+position[0],y+position[1],z+position[2]]=block
        else:
            for b in doc['blocks']:
                state=doc['palette'][b['state']]
                if state['Name'] not in ['minecraft:air','minecraft:cave_air','minecraft:void_air']:cells[tuple(b['pos'])]=state
        lower=[min(p[a] for p in cells) for a in range(3)];upper=[max(p[a] for p in cells) for a in range(3)];size=[upper[a]-lower[a]+1 for a in range(3)]
        palette=[];lookup={};blocks=[]
        for p,state in sorted(cells.items(),key=lambda v:(v[0][1],v[0][2],v[0][0])):
            key=json.dumps(state,sort_keys=True)
            if key not in lookup:lookup[key]=len(palette);palette.append(state)
            blocks.append([p[a]-lower[a] for a in range(3)]+[lookup[key]])
        write(ROOT/'references/user_blueprints'/f'{name}.json',{'size':size,'palette':palette,'blocks':blocks})
        meta=doc.get('Metadata',{});manifest.append({'source_file':filename,'geometry':name+'.json','sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'author_metadata':meta.get('Author','Not present'),'size':size,'non_air_blocks':len(blocks)})
        print(name,size,'blocks',len(blocks),'declared',meta.get('TotalBlocks','n/a'))
        if 'TotalBlocks' in meta:assert len(blocks)==meta['TotalBlocks'],'Packed palette count mismatch'
    write(ROOT/'references/user_blueprints/manifest.json',manifest)
if __name__=='__main__':import_all(sys.argv[1])
