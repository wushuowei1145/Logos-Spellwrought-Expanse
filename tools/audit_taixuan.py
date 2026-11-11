"""检查导出后的原版模板：完整接线、路线走得通、房间净空、水潭与瀑布封住、渊雾只在竖井里、植被接地、依附方块与禁止实体。"""
from pathlib import Path
from collections import Counter
import json
import math
from blueprint_nbt import read_nbt

ROOT=Path(__file__).resolve().parents[1]
D=ROOT/'src/main/resources/data/logos_dimension'
A=ROOT/'src/main/resources/assets/logos_dimension'
AIR={'minecraft:air','minecraft:cave_air','minecraft:void_air'}
MIST='logos_dimension:taixuan_abyss_mist'
# 站上去以后头顶这两格只能是空气、渊雾或垂藤（都没有碰撞）
HEADROOM=AIR|{MIST,'minecraft:vine'}
# 花草只能长在这些上面，否则一更新就掉成物品
PLANTS={'minecraft:'+n for n in ('short_grass','fern','azure_bluet','oxeye_daisy','lily_of_the_valley','cornflower','white_tulip','peony','lilac','rose_bush','azalea','flowering_azalea')}
SOIL={'minecraft:grass_block','minecraft:dirt','minecraft:moss_block','minecraft:podzol','minecraft:coarse_dirt','minecraft:rooted_dirt'}
# 脚下这格不能是这些
NOT_FLOOR=HEADROOM|{'minecraft:water','minecraft:structure_void','minecraft:vine','minecraft:lantern','minecraft:soul_lantern'}


def audit():
    manifest=json.loads((D/'taixuan_manifest.json').read_text('utf-8'))
    sx,sy,sz=manifest['canvas_size'];tile_size=manifest['tile_size']
    cells={};tags={};materials=Counter();pieces=0
    for path in sorted((D/'structure/taixuan').glob('tile_*.nbt')):
        tile=tuple(map(int,path.stem.split('_')[1:]))
        data=read_nbt(path);pieces+=1
        assert data['size']==[tile_size]*3,path
        assert not data['entities'],('结构不应生成实体',path)
        for block in data['blocks']:
            p=tuple(tile[i]*tile_size+block['pos'][i] for i in range(3))
            assert 0<=p[0]<sx and 0<=p[1]<sy and 0<=p[2]<sz,p
            state=data['palette'][block['state']]
            if state['Name']=='minecraft:jigsaw':
                tag=block['nbt'];tags[p]=tag
                state={'Name':tag['final_state'].split('[')[0]}
                pool=tag['pool']
                if pool!='minecraft:empty':
                    assert (D/'worldgen/template_pool'/f'{pool.split(":")[1]}.json').is_file(),pool
            cells[p]=state;materials[state['Name']]+=1
    assert pieces==manifest['tiles']==(sx//tile_size)*(sy//tile_size)*(sz//tile_size),pieces
    assert sum(t['pool'].endswith('/footings') for t in tags.values())==len(manifest['footings'])
    inputs=Counter(t['name'] for t in tags.values())
    outgoing=[t for t in tags.values() if t['pool']!='minecraft:empty' and not t['pool'].endswith('/footings')]
    assert len(outgoing)==pieces-1,len(outgoing)
    assert all(inputs[t['target']]==1 for t in outgoing),'部件入口不唯一或缺失'
    # 本模组方块都要有方块状态文件（原版方块 ID 另由世界测试对照方块注册表）
    for name in materials:
        ns,path=name.split(':')
        assert ns in ('minecraft','logos_dimension'),name
        if ns=='logos_dimension':assert (A/'blockstates'/f'{path}.json').is_file(),('未注册的方块',name)
    assert 'minecraft:chain' not in materials,'26.1 的铁链是 iron_chain'
    # 空地地面的记号方块只在口沿下一格那一层
    assert all(y==manifest['heights']['rim']-1 for (x,y,z),s in cells.items() if s['Name']=='minecraft:dead_horn_coral_block'),'记号方块出现在别的高度'

    def name(p):return cells.get(p,{}).get('Name')
    def solid(p):return name(p) not in (None,'minecraft:air','minecraft:structure_void','minecraft:water',MIST)

    for x,y0,y1,z in manifest['circulation']['retaining_samples']:
        for y in range(y0,y1+1):
            assert name((x,y,z)) not in AIR|{None},('台基未接实',x,y,z)
    for room in manifest['rooms']:
        x,y,z=room['pos']
        for dy in (0,1,2):
            assert name((x,y+dy,z)) in AIR,('房间净空被占用',room,dy,cells.get((x,y+dy,z)))
        assert name((x,y-1,z)) not in AIR|{None,'minecraft:structure_void'},('楼板缺失',room)
    for x,y,z,rx,rz in manifest['landscape']['ponds']:
        assert name((x,y-1,z))=='minecraft:water',('池水丢失',x,y,z)
        for dx in range(-rx,rx+1):
            for dz in range(-rz,rz+1):
                if (dx/rx)**2+(dz/rz)**2<=1:
                    assert name((x+dx,y-3,z+dz))=='minecraft:clay',('池底不封闭',dx,dz)
                    for ox,oz in ((1,0),(-1,0),(0,1),(0,-1)):
                        if ((dx+ox)/rx)**2+((dz+oz)/rz)**2>1:
                            assert name((x+dx+ox,y-1,z+dz+oz))=='minecraft:mossy_cobblestone',('池岸存在漏水格',dx,dz,ox,oz)
    for x,y,z in manifest['landscape']['pines']:
        assert name((x,y,z)) in {'minecraft:dirt','minecraft:grass_block','minecraft:moss_block'},('崖松土壤缺失',x,y,z)
        assert name((x,y+1,z))=='minecraft:spruce_log',('崖松悬空',x,y,z)

    # 路线：脚下有方块，头顶两格是空气或渊雾
    walked={}
    for route,points in manifest['routes'].items():
        assert points,('空路线',route)
        bad=[]
        for x,y,z in points:
            if name((x,y,z)) in NOT_FLOOR|{None} or name((x,y+1,z)) not in HEADROOM or name((x,y+2,z)) not in HEADROOM:
                bad.append((x,y,z,name((x,y,z)),name((x,y+1,z)),name((x,y+2,z))))
        assert not bad,('路线走不通',route,len(bad),bad[:6])
        # 相邻两格高差不超过一格（楼梯、半砖都算在脚下那格里）
        for (x0,y0,z0),(x1,y1,z1) in zip(points,points[1:]):
            if abs(x1-x0)+abs(z1-z0)<=2:assert abs(y1-y0)<=1,('路线上一步跨了两格',route,(x0,y0,z0),(x1,y1,z1))
        walked[route]=len(points)

    # 瀑布：泉眼封在岩壁里，一格横流，整条水柱接到雾层里封住的接水池
    for x,y,z,fx,fz,basin in manifest['abyss']['waterfalls']:
        assert name((x,y,z))=='minecraft:water' and name((fx,y,fz))=='minecraft:water',('泉眼或横流丢失',x,y,z)
        for dx,dz in ((1,0),(-1,0),(0,1),(0,-1)):
            if (x+dx,z+dz)!=(fx,fz):assert solid((x+dx,y,z+dz)),('泉眼侧面漏水',x+dx,y,z+dz)
        assert solid((x,y-1,z)) and solid((x,y+1,z)),('泉眼上下漏水',x,y,z)
        for yy in range(basin,y):
            assert name((fx,yy,fz))=='minecraft:water',('落水柱断开',fx,yy,fz)
        for dx in (-1,0,1):
            for dz in (-1,0,1):
                assert solid((fx+dx,basin-1,fz+dz)),('接水池底漏水',fx+dx,fz+dz)
                if dx or dz:assert solid((fx+dx,basin,fz+dz)),('接水池边漏水',fx+dx,fz+dz)

    # 渊雾只在深渊竖井里、雾层高度上下
    heights=manifest['heights'];lo,hi=heights['mist']
    mist=[p for p,s in cells.items() if s['Name']==MIST]
    assert len(mist)>100000,len(mist)
    assert all(lo<=y<=hi+4 for _,y,_ in mist),'渊雾跑出雾层'
    assert max(math.hypot(x-126,z-126) for x,_,z in mist)<=66,'渊雾跑出深渊'
    # 链台、雾底场地、镇渊柱
    px,py,pz=manifest['abyss']['details']['platform']['center']
    assert solid((px,py,pz)) and solid((px,py-1,pz)),'链台中心缺地板'
    ax,ay,az=manifest['abyss']['details']['arena']['dais']
    assert name((ax,ay,az))=='logos_dimension:taixuan_dark_iron','雾底石台缺玄铁心'
    for tower in manifest['abyss']['towers']:
        # anchor 是链子起点，铁块中心在它往柱子那边退两格
        x,y,z=tower['anchor'];cx,cz=tower['center']
        sx,sz=(1 if 126>cx else -1),(1 if 126>cz else -1)
        assert name((x-2*sx,y,z-2*sz))=='logos_dimension:taixuan_dark_iron',('镇渊柱缺拴链铁块',tower['center'])
    # 一百多个链环，每环二十来格
    assert materials['logos_dimension:taixuan_dark_iron']>2000,materials['logos_dimension:taixuan_dark_iron']
    # 黄铜顶：宗门和镇渊柱亭子都用黄铜筒瓦，城里的灰瓦已经没有了
    assert materials['logos_dimension:taixuan_brass_tiles']+materials['logos_dimension:taixuan_brass_tile_stairs']>2000
    assert not materials['logos_dimension:journey_kawara_roof'],'宗门还有灰瓦'

    # 依附方块：吊灯上方、立灯与门下方、梯子背后都要有方块，否则一更新就掉成物品
    back={'north':(0,0,1),'south':(0,0,-1),'east':(-1,0,0),'west':(1,0,0)}
    loose=[]
    for (x,y,z),s in cells.items():
        n=s['Name'];p=s.get('Properties',{})
        if n in ('minecraft:lantern','minecraft:soul_lantern'):
            below=(x,y+1,z) if p.get('hanging')=='true' else (x,y-1,z)
            if not solid(below):loose.append((n,x,y,z))
        elif n=='minecraft:ladder':
            dx,dy,dz=back[p['facing']]
            if not solid((x+dx,y,z+dz)):loose.append((n,x,y,z))
        elif n=='minecraft:dark_oak_door' and p.get('half')=='lower' and not solid((x,y-1,z)):loose.append((n,x,y,z))
        elif n=='minecraft:campfire' and not solid((x,y-1,z)):loose.append((n,x,y,z))
        elif n=='minecraft:pointed_dripstone' and p.get('vertical_direction')=='down' and name((x,y+1,z)) not in ('minecraft:dripstone_block','minecraft:pointed_dripstone') and not solid((x,y+1,z)):loose.append((n,x,y,z))
        elif n in PLANTS and name((x,y-1,z)) not in SOIL and not (p.get('half')=='upper' and name((x,y-1,z))==n):loose.append((n,x,y,z))
    assert not loose,('悬空的依附方块',len(loose),loose[:8])
    assert not any(n in materials for n in ('minecraft:gravel','minecraft:sand','minecraft:red_sand'))
    assert materials['minecraft:air']>10000
    print(json.dumps({'tiles':pieces,'components':manifest['components'],'rooms':len(manifest['rooms']),
        'native_footings':len(manifest['footings']),'blocks':sum(materials.values()),'entities':0,
        'mist':len(mist),'routes':walked,'waterfalls':len(manifest['abyss']['waterfalls']),
        'pines':len(manifest['landscape']['pines']),'ponds':len(manifest['landscape']['ponds'])},ensure_ascii=False))


if __name__=='__main__':audit()
