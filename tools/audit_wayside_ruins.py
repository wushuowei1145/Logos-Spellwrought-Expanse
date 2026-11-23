"""检查小遗迹模板的界限、战利品、实体、连续支撑和花藤附着；不启动游戏。"""
import json
from pathlib import Path
from PIL import Image
from blueprint_nbt import read_nbt

ROOT=Path(__file__).resolve().parent.parent
D=ROOT/'src/main/resources/data/logos_dimension'
manifest=json.loads((ROOT/'art/wayside/manifest.json').read_text(encoding='utf-8'))
assert manifest['kinds']==9 and len(manifest['templates'])==18
for texture in (ROOT/'src/main/resources/assets/logos_dimension/textures/block/wayside').glob('*.png'):
    with Image.open(texture) as image:assert image.size==(16,16),(texture,image.size)
for entry in manifest['templates']:
    obj=read_nbt(D/f"structure/wayside/{entry['id']}.nbt")
    assert not obj['entities'],entry['id']
    assert 1<=entry['loot']<=2 and entry['supports']>0,entry
    assert max(obj['size'])<=20,entry
    cells={tuple(b['pos']):obj['palette'][b['state']] for b in obj['blocks']}
    # 将铺石和接地端口作为根，所有装饰都必须通过面邻接连回实体支撑，避免悬空碎件。
    solid={p for p,s in cells.items() if s['Name'] not in ['minecraft:air','minecraft:water','minecraft:structure_void']}
    reached={p for p in solid if p[1]==4};pending=list(reached)
    while pending:
        x,y,z=pending.pop()
        for dx,dy,dz in [(1,0,0),(-1,0,0),(0,1,0),(0,-1,0),(0,0,1),(0,0,-1)]:
            p=(x+dx,y+dy,z+dz)
            if p in solid and p not in reached:reached.add(p);pending.append(p)
    assert reached==solid,(entry['id'],'悬空部件',sorted(solid-reached))
    for b in obj['blocks']:
        p=b['pos'];s=obj['palette'][b['state']]
        assert all(0<=p[i]<obj['size'][i] for i in range(3)),(entry,p)
        assert s['Name'] not in ['minecraft:dirt','minecraft:grass_block','minecraft:spawner','minecraft:barrier','minecraft:command_block'],(entry,s)
        if 'LootTable' in b.get('nbt',{}):
            assert s['Name']=='minecraft:chest',(entry,p)
            assert (D/('loot_table/'+b['nbt']['LootTable'].split(':')[1]+'.json')).exists()
            assert cells.get((p[0],p[1]+1,p[2]),{'Name':'minecraft:air'})['Name'] in ['minecraft:air','minecraft:water'],(entry,'箱盖净空',p)
            assert s['Properties']['waterlogged']==str(entry['id'].startswith('rain_memory')).lower(),(entry,'湖底箱含水状态',p)
        if s['Name']=='logos_dimension:wayside_bloom_vine':
            for face,(dx,dz) in {'north':(0,-1),'south':(0,1),'east':(1,0),'west':(-1,0)}.items():
                if s['Properties'][face]=='true':assert (p[0]+dx,p[1],p[2]+dz) in cells,(entry,p,face)
print('18 templates: bounds, cache, empty entities, no terrain slabs, connected parts and flower support passed.')
