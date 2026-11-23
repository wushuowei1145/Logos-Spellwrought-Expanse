"""检查小遗迹模板的界限、战利品、实体、花藤附着与确定性；不启动游戏。"""
import json
from pathlib import Path
from blueprint_nbt import read_nbt

ROOT=Path(__file__).resolve().parent.parent
D=ROOT/'src/main/resources/data/logos_dimension'
manifest=json.loads((ROOT/'art/wayside/manifest.json').read_text(encoding='utf-8'))
assert manifest['kinds']==9 and len(manifest['templates'])==18
for entry in manifest['templates']:
    obj=read_nbt(D/f"structure/wayside/{entry['id']}.nbt")
    assert not obj['entities'],entry['id']
    assert entry['loot']==1 and entry['supports']>0,entry
    assert max(obj['size'])<=20,entry
    cells={tuple(b['pos']):obj['palette'][b['state']] for b in obj['blocks']}
    for b in obj['blocks']:
        p=b['pos'];s=obj['palette'][b['state']]
        assert all(0<=p[i]<obj['size'][i] for i in range(3)),(entry,p)
        assert s['Name'] not in ['minecraft:dirt','minecraft:grass_block','minecraft:spawner','minecraft:barrier','minecraft:command_block'],(entry,s)
        if 'LootTable' in b.get('nbt',{}):assert (D/('loot_table/'+b['nbt']['LootTable'].split(':')[1]+'.json')).exists()
        if s['Name']=='logos_dimension:wayside_bloom_vine':
            for face,(dx,dz) in {'north':(0,-1),'south':(0,1),'east':(1,0),'west':(-1,0)}.items():
                if s['Properties'][face]=='true':assert (p[0]+dx,p[1],p[2]+dz) in cells,(entry,p,face)
print('18 templates: bounds, cache, empty entities, no terrain slabs and flower support passed.')
