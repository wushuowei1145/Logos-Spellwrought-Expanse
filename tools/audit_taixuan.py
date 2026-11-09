"""检查导出后的原版模板：完整接线、房间净空、池底、植被接地与禁止实体。"""
from pathlib import Path
from collections import Counter
import json
from blueprint_nbt import read_nbt

ROOT=Path(__file__).resolve().parents[1]
D=ROOT/'src/main/resources/data/logos_dimension'


def audit():
    manifest=json.loads((D/'taixuan_manifest.json').read_text('utf-8'))
    size=manifest['size'][0];tile_size=manifest['tile_size']
    cells={};tags={};materials=Counter();pieces=0
    for path in sorted((D/'structure/taixuan').glob('tile_*.nbt')):
        tile=tuple(map(int,path.stem.split('_')[1:]))
        data=read_nbt(path);pieces+=1
        assert data['size']==[tile_size]*3,path
        assert not data['entities'],('结构不应生成实体',path)
        for block in data['blocks']:
            p=tuple(tile[i]*tile_size+block['pos'][i] for i in range(3))
            assert all(0<=v<size for v in p),p
            state=data['palette'][block['state']]
            if state['Name']=='minecraft:jigsaw':
                tag=block['nbt'];tags[p]=tag
                state={'Name':tag['final_state'].split('[')[0]}
                pool=tag['pool']
                if pool!='minecraft:empty':
                    assert (D/'worldgen/template_pool'/f'{pool.split(":")[1]}.json').is_file(),pool
            cells[p]=state;materials[state['Name']]+=1
    assert pieces==manifest['tiles']==(size//tile_size)**3,pieces
    assert sum(t['pool'].endswith('/footings') for t in tags.values())==len(manifest['footings'])
    inputs=Counter(t['name'] for t in tags.values())
    outgoing=[t for t in tags.values() if t['pool']!='minecraft:empty' and not t['pool'].endswith('/footings')]
    assert len(outgoing)==pieces-1,len(outgoing)
    assert all(inputs[t['target']]==1 for t in outgoing), '部件入口不唯一或缺失'
    air={'minecraft:air','minecraft:cave_air','minecraft:void_air'}
    for x,y0,y1,z in manifest['circulation']['retaining_samples']:
        for y in range(y0,y1+1):
            assert cells[x,y,z]['Name'] not in air,('山体台基未接实',x,y,z)
    for x,z in ((38,63),(63,88),(188,213),(126,220)):
        for y in (manifest['city_floor']+2,manifest['city_floor']+10,manifest['city_floor']+25):
            assert cells.get((x,y,z),{}).get('Name') in air,('坊巷残留树冠或堵塞',x,y,z)
    for landmark in manifest['landmarks']:
        if landmark['name'].startswith('city_watch_'):
            x,y,z=landmark['center']
            assert cells[x+4,manifest['city_floor']+3,z+3]['Name']=='minecraft:stone_bricks',('角楼缺少落地塔身',landmark['name'])
    for room in manifest['rooms']:
        x,y,z=room['pos']
        for dy in (0,1,2):
            assert cells.get((x,y+dy,z),{}).get('Name') in air,('房间净空被占用',room,dy,cells.get((x,y+dy,z)))
        assert cells.get((x,y-1,z),{}).get('Name') not in air|{'minecraft:structure_void'},('楼板缺失',room)
    for x,y,z,rx,rz in manifest['landscape']['ponds']:
        assert cells[x,y-1,z]['Name']=='minecraft:water',('池水丢失',x,y,z)
        assert cells[x,y-3,z]['Name']=='minecraft:clay',('池底悬空',x,y,z)
        for dx in range(-rx,rx+1):
            for dz in range(-rz,rz+1):
                if (dx/rx)**2+(dz/rz)**2<=1:
                    assert cells[x+dx,y-3,z+dz]['Name']=='minecraft:clay',('池底不封闭',dx,dz)
                    for ox,oz in ((1,0),(-1,0),(0,1),(0,-1)):
                        if ((dx+ox)/rx)**2+((dz+oz)/rz)**2>1:
                            assert cells[x+dx+ox,y-1,z+dz+oz]['Name']=='minecraft:mossy_cobblestone',('池岸存在漏水格',dx,dz,ox,oz)
    fall=manifest['landscape']['waterfall_source']
    assert fall, '瀑布源缺失'
    catch=manifest['landscape']['ponds'][-1]
    assert catch[1]==manifest['city_floor']+1 and abs(catch[0]-fall[0])<=1 and catch[2]==fall[2],('瀑布缺少下方接水池',fall,catch)
    for x,y,z in manifest['landscape']['pines']:
        assert cells[x,y,z]['Name'] in {'minecraft:dirt','minecraft:grass_block','minecraft:moss_block'},('崖松土壤缺失',x,y,z)
        assert cells[x,y+1,z]['Name']=='minecraft:spruce_log',('崖松悬空',x,y,z)
    assert not any(n in materials for n in ('minecraft:gravel','minecraft:sand','minecraft:red_sand'))
    assert materials['minecraft:air']>10000
    print(json.dumps({'tiles':pieces,'components':manifest['components'],'rooms':len(manifest['rooms']),
        'native_footings':len(manifest['footings']),'blocks':sum(materials.values()),'entities':0,
        'pines':len(manifest['landscape']['pines']),'ponds':len(manifest['landscape']['ponds'])},ensure_ascii=False))


if __name__=='__main__':audit()
