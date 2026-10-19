"""Audit shipped NBT, native links and crop support without needing a Minecraft client."""
from pathlib import Path
import json
from blueprint_nbt import read_nbt
ROOT=Path(__file__).resolve().parent.parent;D=ROOT/'src/main/resources/data/logos_dimension'
templates={p.stem:read_nbt(p) for p in (D/'structure/settlement').glob('*.nbt')}
pools={p.stem:json.loads(p.read_text()) for p in (D/'worldgen/template_pool/settlement').glob('*.json')}
ports=0;rice=0
for name,doc in templates.items():
    cells={tuple(b['pos']):doc['palette'][b['state']] for b in doc['blocks']}
    assert len(cells)==len(doc['blocks']),('duplicate position',name)
    for block in doc['blocks']:
        pos=tuple(block['pos']);state=cells[pos];n=state['Name']
        assert all(0<=x<limit for x,limit in zip(pos,doc['size'])),(name,pos)
        assert not n.endswith('_ore') and n not in {'minecraft:gold_block','minecraft:emerald_block','minecraft:structure_block','minecraft:command_block','minecraft:barrier'},(name,n)
        if 'nbt' in block:
            assert n=='minecraft:jigsaw',(name,'decoration overwrote a connector',pos,n)
            tag=block['nbt'];assert tag['id']=='minecraft:jigsaw' and tag['final_state']=='minecraft:air'
            if tag['pool']!='minecraft:empty':assert tag['pool'].removeprefix('logos_dimension:settlement/') in pools
            ports+=1
        if n=='logos_dimension:journey_rice':
            assert cells[pos[0],pos[1]-1,pos[2]]['Name']=='minecraft:mud',(name,'unsupported rice',pos)
            rice+=1
    for resident in doc['entities']:
        assert resident['nbt']['id']=='minecraft:villager'
        assert set(resident['nbt'])=={'id','PersistenceRequired','Age','VillagerData'},'Untrusted entity data imported'
for name,pool in pools.items():
    for e in pool['elements']:
        assert e['element']['element_type']=='minecraft:single_pool_element'
        assert e['element']['location'].removeprefix('logos_dimension:settlement/') in templates,(name,e)
for name in ['hamlet','town']:
    assert not (D/'structure'/('journey_'+name+'.nbt')).exists()
    expected=7 if name=='town' else 5
    road=templates[name+'_road'];actual=sum('nbt' in b for b in road['blocks'])
    assert actual==expected,(name,'missing street connector',actual)
assert rice>3000
print(f'SETTLEMENT_AUDIT {len(templates)} templates, {len(pools)} pools, {ports} intact connectors, {rice} supported rice plants; no imported inventories/commands/entities.')
