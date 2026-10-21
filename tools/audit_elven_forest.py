"""审计精灵树模板的拼接、净空、资源引用和静态模型边界。"""
from pathlib import Path
import json
from collections import Counter, deque
from blueprint_nbt import read_nbt

ROOT=Path(__file__).resolve().parent.parent
R=ROOT/'src/main/resources';D=R/'data/logos_dimension';A=R/'assets/logos_dimension'
templates={p.stem:read_nbt(p) for p in (D/'structure/elven').glob('*.nbt')}
pools={p.stem:json.loads(p.read_text(encoding='utf-8')) for p in (D/'worldgen/template_pool/elven').glob('*.json')}
links={};counts=Counter();ports=0
for name,doc in templates.items():
    assert doc['DataVersion']==4790 and not doc['entities'],name
    cells={tuple(b['pos']):b for b in doc['blocks']}
    assert len(cells)==len(doc['blocks']),name
    outgoing=[]
    for pos,b in cells.items():
        assert all(0<=x<limit for x,limit in zip(pos,doc['size'])),(name,pos)
        state=doc['palette'][b['state']];n=state['Name'];counts[n]+=1
        assert n not in {'minecraft:dirt','minecraft:grass_block','minecraft:gravel','minecraft:sand','minecraft:command_block'},(name,n)
        if 'nbt' not in b:continue
        assert n=='minecraft:jigsaw'
        tag=b['nbt'];ports+=1
        if tag['pool']=='logos_dimension:elven/roots':
            assert tag['joint']=='rollable' and tag['target']=='minecraft:bottom'
            assert tag['final_state'].startswith(('logos_dimension:elven_heartwood[','logos_dimension:cloudsea_fir_heart_log['))
        elif tag['pool']!='minecraft:empty':
            target=tag['pool'].split('/')[-1];assert target in pools
            outgoing.append(target)
            child=templates[target]
            assert any(c.get('nbt',{}).get('name')==tag['target'] for c in child['blocks']),(name,target)
        assert tag['final_state']!='minecraft:jigsaw'
    links[name]=outgoing
for name,pool in pools.items():
    for entry in pool['elements']:
        e=entry['element']
        if e['element_type']=='minecraft:feature_pool_element':assert e['feature']=='logos_dimension:elven_roots'
        else:
            assert e['location'].split('/')[-1] in templates
            assert e['processors']=='logos_dimension:elven_preserve' and e['projection']=='rigid'
visited=set();q=deque([('tile_3_0_3',0)]);depth=0
while q:
    name,d=q.popleft();assert name not in visited,('cycle',name)
    visited.add(name);depth=max(depth,d);q.extend((c,d+1) for c in links[name])
assert len(visited)==343 and depth<=14,(len(visited),depth)
# 将树心所在的底部模板还原到全局坐标，校验十格宽主通道和家具位置。
world={}
for name in visited:
    if not name.startswith('tile_'):continue
    tile=list(map(int,name.split('_')[1:]));doc=templates[name]
    for b in doc['blocks']:
        p=tuple(b['pos'][i]+tile[i]*32-[112,4,112][i] for i in range(3))
        world[p]=b.get('nbt',{}).get('final_state',doc['palette'][b['state']]['Name']).split('[')[0]
for x in range(-5,6):
    for z in range(-5,6):
        if x*x+z*z>=25:continue
        for y in range(2,10):assert world.get((x,y,z))=='minecraft:air',(x,y,z)
for x in [-7,-6,-5]:
    for y in [2,3]:assert world[x,y,9]=='logos_dimension:elven_bookshelf'
assert world[0,1,0]=='logos_dimension:elven_luminous_inlay'
for p in (A/'models/block').glob('elven_*.json'):
    m=json.loads(p.read_text(encoding='utf-8'))
    for texture in m.get('textures',{}).values():
        if texture.startswith('logos_dimension:'):assert (A/'textures'/(texture.split(':')[1]+'.png')).exists(),(p,texture)
    for element in m.get('elements',[]):
        assert all(-16<=v<=32 for k in ['from','to'] for v in element[k]),p
        spans=[b-a for a,b in zip(element['from'],element['to'])]
        assert all(v>=0 for v in spans) and sum(v>0 for v in spans)>=2,p
        for f in element['faces'].values():assert 'uv' in f and len(f['uv'])==4,(p,'missing UV')
for name in ['elven_world_tree','elven_giant_tree']:
    s=json.loads((D/'worldgen/structure'/f'{name}.json').read_text())
    assert s['type']=='minecraft:jigsaw' and s['terrain_adaptation']=='none'
    assert all(not row['spawns'] for row in s['spawn_overrides'].values())
print(f'ELVEN_AUDIT templates={len(templates)} connected_tiles={len(visited)} depth={depth} ports={ports} models=valid central_clear_diameter=10 entities=0 soil_plinth=0')
