"""Audit shipped NBT, native links and crop support without needing a Minecraft client."""
from pathlib import Path
import json
from blueprint_nbt import read_nbt
ROOT=Path(__file__).resolve().parent.parent;D=ROOT/'src/main/resources/data/logos_dimension'
templates={p.stem:read_nbt(p) for p in (D/'structure/settlement').glob('*.nbt')}
pools={p.stem:json.loads(p.read_text()) for p in (D/'worldgen/template_pool/settlement').glob('*.json')}
ports=0;wheat_seeds=0
for name,doc in templates.items():
    cells={tuple(b['pos']):doc['palette'][b['state']] for b in doc['blocks']}
    assert len(cells)==len(doc['blocks']),('duplicate position',name)
    for block in doc['blocks']:
        pos=tuple(block['pos']);state=cells[pos];n=state['Name']
        assert all(0<=x<limit for x,limit in zip(pos,doc['size'])),(name,pos)
        assert not n.endswith('_ore') and n not in {'minecraft:gold_block','minecraft:emerald_block','minecraft:structure_block','minecraft:command_block','minecraft:barrier'},(name,n)
        if 'nbt' in block:
            assert n=='minecraft:jigsaw',(name,'decoration overwrote a connector',pos,n)
            tag=block['nbt'];assert tag['id']=='minecraft:jigsaw' and tag['final_state'] in {'minecraft:air','minecraft:structure_void'}
            if tag['pool']!='minecraft:empty':assert tag['pool'].removeprefix('logos_dimension:settlement/') in pools
            if tag['pool']=='logos_dimension:settlement/wheat':
                assert tag['target']=='minecraft:bottom' and tag['final_state']=='minecraft:structure_void'
                wheat_seeds+=1
            ports+=1
        assert n!='logos_dimension:journey_rice',(name,'legacy rice still generated')
        assert n not in {'minecraft:sand','minecraft:red_sand','minecraft:gravel'} and not n.endswith('_concrete_powder'),(name,'falling paving',pos,n)
    assert not doc['entities'],(name,'villagers/animals/entities must not be generated')
    if name.startswith(('house_','farm_')):
        for y in range(min(3,doc['size'][1])):
            assert not any(s['Name'] in {'minecraft:dirt','minecraft:grass_block','minecraft:farmland','minecraft:coarse_dirt','minecraft:podzol'} for p,s in cells.items() if p[1]==y),(name,'copied soil at base',y)
        assert any(s['Name']=='minecraft:air' for s in cells.values()),(name,'missing room air')
        corner=next(b for b in doc['blocks'] if b['pos']==[0,0,0])
        assert cells[(0,0,0)]['Name']=='minecraft:structure_void' or corner.get('nbt',{}).get('final_state')=='minecraft:structure_void',(name,'plot corner must preserve terrain')
for name,pool in pools.items():
    for e in pool['elements']:
        element=e['element']
        if element['element_type']=='minecraft:feature_pool_element':assert element['feature']=='logos_dimension:settlement_wheat'
        else:
            assert element['element_type']=='minecraft:single_pool_element'
            assert element['location'].removeprefix('logos_dimension:settlement/') in templates,(name,e)
            assert element['processors']=='logos_dimension:settlement_preserve_void'
            assert element['override_liquid_settings']=='ignore_waterlogging'
for name in ['hamlet','town']:
    assert not (D/'structure'/('journey_'+name+'.nbt')).exists()
    expected=7 if name=='town' else 5
    road=templates[name+'_road'];actual=sum('nbt' in b for b in road['blocks'])
    assert actual==expected,(name,'missing street connector',actual)
    assert json.loads((D/'worldgen/structure'/('journey_'+name+'.json')).read_text())['terrain_adaptation']=='none'
    structure=json.loads((D/'worldgen/structure'/('journey_'+name+'.json')).read_text())
    assert structure['start_height']=={'absolute':-3},'Buried well shaft must not lift the square'
    assert structure['spawn_overrides']['creature']['spawns']==[]
    plaza=templates[name+'_plaza'];well={tuple(b['pos']):plaza['palette'][b['state']]['Name'] for b in plaza['blocks']}
    for x in (11,12):
        for z in (11,12):
            assert well[x,0,z]=='logos_dimension:journey_limestone'
            assert well[x,1,z]=='minecraft:water'
    assert sum(n=='minecraft:stripped_spruce_log' for n in well.values())==12
processor=json.loads((D/'worldgen/processor_list/settlement_preserve_void.json').read_text())
assert processor['processors']==[{'processor_type':'minecraft:block_ignore','blocks':[{'Name':'minecraft:structure_void'}]}], 'AIR must not be ignored'
assert wheat_seeds==28
assert not any('paddy' in n for n in templates)
crop=json.loads((D/'worldgen/configured_feature/settlement_wheat_column.json').read_text())
assert crop['type']=='minecraft:block_column' and len(crop['config']['layers'])==2
assert all(layer['height']==1 for layer in crop['config']['layers'])
print(f'SETTLEMENT_AUDIT {len(templates)} templates, {len(pools)} pools, {ports} intact connectors, {wheat_seeds} wheat anchors; no earth plinth, falling roads or entities; dry room AIR and preserved exterior VOID; covered wells.')
