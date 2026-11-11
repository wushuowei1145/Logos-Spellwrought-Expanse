"""太玄灵境：浮空宗门、下面的深渊与雾层、四根镇渊柱、铁链和链台。拼装局部 Python 建筑与景观，
导出原版 NBT 和结构池；选址由 Java 的 TaixuanStructure 负责（只看群系和噪声地形），建筑仍是原版拼接。"""
from pathlib import Path
from collections import deque
import argparse
import json
import math

import numpy as np

from blueprint_nbt import write_structure
from fox_kit import rotate_pos, rotate_state, mirror_state
from taixuan_kit import *
from taixuan_buildings import hall, gate, pagoda
import taixuan_terrain as terrain
import taixuan_landscape as landscape
import taixuan_abyss as abyss
import taixuan_details as details

ROOT=Path(__file__).resolve().parents[1]
D=ROOT/'src/main/resources/data/logos_dimension'
A=ROOT/'src/main/resources/assets/logos_dimension'
NS='logos_dimension:'
SIZE=terrain.SIZE
SIZE_Y=terrain.SIZE_Y
CENTER=terrain.CENTER
TILE=28
GRID_XZ=SIZE//TILE
GRID_Y=SIZE_Y//TILE
# 起点模板在正中那一列、竖直方向中间那一块，上下都不超过原版拼接 256 格的竖直范围
ROOT_TILE=(GRID_XZ//2,GRID_Y//2,GRID_XZ//2)
ANCHOR_LOCAL=(TILE//2,4,TILE//2)
ANCHOR_CANVAS=tuple(ROOT_TILE[i]*TILE+ANCHOR_LOCAL[i] for i in range(3))
# 起点锚要落在世界 y = 画布 y + abyss.Y0。原版拼接按起点部件的地面差（单一模板是 1）把起点部件往下挪一格，
# 所以起始高度要多给 1，锚才正好在设计高度上
START_HEIGHT=ANCHOR_CANVAS[1]+abyss.Y0+1
LANDMARKS=[]
ROOMS=[]
COMPONENTS={}


def write(path,value):
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n','utf-8',newline='\n')


def put_module(c,name,module,x,y,z,turns=0):
    """中心定位的局部模块，旋转同时处理门、楼梯、原木与连接朝向。"""
    w=max(p[0] for p in module.cells)+1;d=max(p[2] for p in module.cells)+1
    width,depth=(d,w) if turns%2 else (w,d)
    ox,oz=x-width//2,z-depth//2
    # 建筑地块中的未画位置需要显式空气，清除既有树干和树冠；只清地板以上的空间
    top=max(p[1] for p in module.cells)
    for px in range(w):
        for pz in range(d):
            for py in range(1,top+2):
                if module.get(px,py,pz) is None:module.set(px,py,pz,AIR)
    c.paste(module,ox,y,oz,turns)
    corners=[rotate_pos(px,pz,turns) for px,pz in ((0,0),(w-1,0),(0,d-1),(w-1,d-1))]
    mx,mz=min(p[0] for p in corners),min(p[1] for p in corners)
    def world(p):
        rx,rz=rotate_pos(p[0],p[2],turns)
        return [ox+rx-mx,y+p[1],oz+rz-mz]
    candidates=[]
    for (px,py,pz),s in module.cells.items():
        if s!=PLANK:continue
        if all(module.get(px,py+dy,pz)==AIR for dy in (1,2,3)):
            candidates.append((abs(px-w/2)+abs(pz-d/2)+abs(py)*.1,(px,py+1,pz)))
    if candidates:
        room=world(min(candidates)[1]);ROOMS.append({'building':name,'pos':room})
    spec={'name':name,'center':[x,y,z],'origin':[ox,y,oz],'rotation':turns,
          'size':[width,max(p[1] for p in module.cells)+3,depth]}
    LANDMARKS.append(spec)
    if name not in COMPONENTS:COMPONENTS[name]=module
    return spec


def sect(c):
    """宫阙与侧峰：主岛在深渊正上方，四座侧峰各一座楼阁，桥从主岛连出去。"""
    at=terrain.at
    supports=[]
    def support(x0,x1,z0,z1,y):
        # 台基向下接到本岛实土，显式室内空气不填，桥梁也不套用这种支撑。
        for xx in range(x0,x1+1):
            for zz in range(z0,z1+1):
                gy=terrain.ground(c,xx,zz)
                if gy is None or gy>=y-1 or y-gy>38:continue
                filled=[]
                for yy in range(gy+1,y):
                    if c.get(xx,yy,zz) is None:
                        c.set(xx,yy,zz,STONE);filled.append(yy)
                if filled:supports.append([xx,min(filled),max(filled),zz])
    def building(name,module,x,altitude,north):
        p=at(x,altitude,north);spec=put_module(c,name,module,*p)
        ox,_,oz=spec['origin'];w,_,d=spec['size']
        support(ox+2,ox+w-3,oz+2,oz+d-3,p[1])
        return spec
    building('sect_main_hall',hall(31,19,3,7,study=True),0,252,84)
    building('sect_front_hall',hall(19,13,2,6),0,235,20)
    for side in (-1,1):
        building('sect_wing_'+str(side),hall(15,23,2,5),side*70,224,52)
        building('sect_court_'+str(side),hall(13,11,1,6),side*55,223,-4)
        building('sect_rear_tower_'+str(side),pagoda(11,3),side*72,225,102)
    building('sect_mountain_gate',gate(),0,192,-134)
    building('sect_library',pagoda(15,5),-187,253,55)
    building('sect_east_hall',hall(19,15,3,6,study=True),172,273,55)
    building('sect_training_hall',hall(13,11,2,5),96,221,83)
    building('sect_rear_library',pagoda(11,4),0,293,235)
    # 中央庭院与三重台阶连接殿前地坪。
    for x,north,alt,w,d in ((0,28,221,150,138),(0,57,233,113,99),(0,79,250,82,62)):
        cx,y,cz=at(x,alt,north)
        ww,dd=round(w*terrain.HORIZONTAL),round(d*terrain.HORIZONTAL)
        # 地坪只补未被殿阁占用的底层，避免后画台地封死房间。
        for xx in range(cx-ww//2,cx+ww//2+1):
            for zz in range(cz-dd//2,cz+dd//2+1):
                if c.get(xx,y,zz) is None:c.set(xx,y,zz,WHITE)
                for yy in (y-2,y-1):
                    if c.get(xx,yy,zz) is None:c.set(xx,yy,zz,STONE)
        support(cx-ww//2,cx+ww//2,cz-dd//2,cz+dd//2,y-2)
    x,y,z=at(0,221,-39);stair_run(c,x-5,y+1,z,11,16,8)
    x,y,z=at(0,234,37);stair_run(c,x-4,y+1,z,9,9,9)
    bridges=[]
    for n,(a,b,width,arch) in enumerate([((0,192,-105),(0,221,-57),5,1),
        ((-95,224,52),(-150,252,52),3,2),((95,223,48),(130,272,48),3,2),
        ((0,251,158),(0,292,201),3,2)]):
        p,q=at(*a),at(*b);bridge(c,p,q,width,arch,abyss.ROUTES.setdefault(f'sect_bridge_{n}',[]))
        bridges.append({'from':list(p),'to':list(q),'width':width})
    return {'bridges':bridges,'retaining_columns':len(supports),'retaining_samples':supports[::max(1,len(supports)//24)]}


def lowest(c,x,z,top):
    """这一列链台以上第一格实心（浮岛的底）：从链台上方往上找。"""
    for y in range(abyss.PLATFORM+5,top):
        s=c.get(x,y,z)
        if s is not None and s['Name'] not in ('minecraft:air','minecraft:structure_void'):return y
    return None


ROCK=('stone','andesite','calcite','diorite','tuff','dripstone_block','moss_block','mossy_cobblestone')


def waterfall_spots(c,islands):
    """主岛侧壁上找两处泉眼：岛顶比深渊宽，从岛边落下会砸在深渊口外的地上，所以泉眼开在往下收窄的岛腰上，
    离中心 28～46 格，整条水柱一直到雾层都在深渊竖井里、不碰别的方块，也不贴着链子和吊桥；
    先取最高的一处，第二处要和它隔开至少 100°，两道瀑布分在深渊两边。"""
    spots=[]
    for (x,y,z),s in c.cells.items():
        if not 165<=y<=215 or s['Name'].split(':')[1] not in ROCK:continue
        r=math.hypot(x-CENTER,z-CENTER)
        if not 28<=r<=46:continue
        for dx,dz in ((1,0),(-1,0),(0,1),(0,-1)):
            fx,fz=x+dx,z+dz
            if (fx-CENTER)*dx+(fz-CENTER)*dz<=0 or c.get(fx,y,fz) is not None:continue
            rf=math.hypot(fx-CENTER,fz-CENTER)
            # 东西两条吊链，和南边吊桥（x 中心 ±2，往南 16～66 格）两侧各留几格
            if abs(fz-CENTER)<6 and abs(abs(fx-CENTER)-13)<6:continue
            if abs(fx-CENTER)<=8 and fz-CENTER>=10:continue
            ok=True
            for yy in range(abyss.MIST_TOP-6,y):
                if c.get(fx,yy,fz) not in (None,AIR,abyss.MIST) or rf>float(abyss.shaft_radius(yy,math.atan2(fz-CENTER,fx-CENTER)))-2:
                    ok=False;break
            if ok:spots.append((y,math.atan2(fz-CENTER,fx-CENTER),(x,y,z,fx,fz)))
    spots.sort(key=lambda s:-s[0])
    if not spots:return []
    first=spots[0]
    def apart(a,b):return abs((a-b+math.pi)%(2*math.pi)-math.pi)
    second=next((s for s in spots if apart(s[1],first[1])>=math.radians(100)),None)
    return [s[2] for s in (first,second) if s]


def build_abyss(c,islands):
    """深渊、雾底、链台、镇渊柱、铁链、吊桥、栈道、瀑布和镇渊柱到山门峰的桥。"""
    abyss.shaft(c)
    abyss.arena(c)
    posts=abyss.platform(c)
    towers=[abyss.tower(c,x,z,i) for i,(x,z) in enumerate(abyss.TOWERS)]
    links=0
    for t,name in zip(towers,('se','sw','nw','ne')):
        links+=abyss.chain(c,t['anchor'],posts[name],sag=7.0)
    # 主岛底下垂下两条链子到链台东西两角
    hanging=[]
    for name in ('e','w'):
        x,_,z=posts[name]
        bottom=lowest(c,x,z,abyss.SIZE_Y-2)
        if bottom is None:continue
        links+=abyss.chain(c,(x,bottom-1,z),posts[name],sag=0.0)
        hanging.append([x,bottom,z])
    # 链台底尖到雾底石台：一条穿过雾层的链子
    links+=abyss.chain(c,(CENTER,abyss.REPORT['platform_bottom']-1,CENTER),(CENTER,abyss.ARENA+4,CENTER),sag=0.0)
    abyss.stair_bridge(c)
    abyss.plank_walk(c)
    # 栈道到底以后，雾底地面上一直走到中间石台跟前；栈道末段底下那几格不算（头顶是栈道）
    ex,_,ez=abyss.ROUTES['plank_walk'][-1]
    n=int(math.hypot(ex-CENTER,ez-CENTER))
    clear=lambda x,z:all(c.get(x,abyss.ARENA+dy,z) in (None,AIR,abyss.MIST) for dy in (1,2,3))
    started=False
    for k in range(n-9):
        x,z=round(ex+(CENTER-ex)*k/n),round(ez+(CENTER-ez)*k/n)
        started=started or clear(x,z)
        if started:abyss.route('arena',x,abyss.ARENA,z)
    falls=[abyss.waterfall(c,*spot) for spot in waterfall_spots(c,islands)]
    # 西南镇渊柱顶到山门峰：一道石拱桥，从地面走上宗门；桥面起点和柱顶齐平
    t=towers[abyss.GATE_TOWER];tx,tz=t['center']
    gate_island=next(s for s in islands if s['seed']==21)
    gx,gy,gz=gate_island['center']
    p=(tx+abyss.TOWER_HALF+1,abyss.TOWER_TOP,tz)
    q=(gx-12,gy,gz)
    bridge(c,p,q,3,2,abyss.ROUTES.setdefault('tower_bridge',[]))
    abyss.basin_floor(c)
    return {'towers':towers,'chain_links':links,'island_chains':hanging,'waterfalls':falls,
            'tower_bridge':{'from':list(p),'to':list(q)},'details':abyss.REPORT}


def clearance(c):
    """宗门（深渊口以上的所有方块）每一列从底下 4 格往上都清成空气，削掉穿过浮岛、挡在桥上的天然石柱。"""
    for (x,y,z),s in c.cells.items():
        if y>=abyss.RIM+12 and s['Name'] not in ('minecraft:air','minecraft:structure_void'):
            abyss.clear_column(x,z,y-4)


def string_state(s):
    return s['Name']+('['+','.join(k+'='+v for k,v in sorted(s['Properties'].items()))+']' if s.get('Properties') else '')


def connector(cells,tags,pos,front,name,target,pool):
    old=cells.get(pos)
    cells[pos]=B('jigsaw',orientation=front+('_north' if front in ('up','down') else '_up'))
    tags[pos]={'id':'minecraft:jigsaw','name':NS+name,'target':target if ':' in target else NS+target,
               'pool':'minecraft:empty' if pool is None else NS+'taixuan/'+pool,
               'joint':'rollable' if pool=='footings' else 'aligned',
               'final_state':string_state(old) if old else 'minecraft:structure_void'}


def pool(name):
    write(D/'worldgen/template_pool/taixuan'/f'{name}.json',{
        'name':NS+'taixuan/'+name,'fallback':'minecraft:empty','elements':[
            {'weight':1,'element':{'element_type':'minecraft:single_pool_element','location':NS+'taixuan/'+name,
             'processors':NS+'taixuan_preserve','override_liquid_settings':'ignore_waterlogging','projection':'rigid'}}]})


def tile_air(key):
    """这一块模板里要显式写空气的格子：深渊竖井、浮岛上方的列、铁链周围。"""
    tx,ty,tz=key
    xs=np.arange(tx*TILE,(tx+1)*TILE);ys=np.arange(ty*TILE,(ty+1)*TILE);zs=np.arange(tz*TILE,(tz+1)*TILE)
    X,Y,Z=np.meshgrid(xs,ys,zs,indexing='ij')
    mask=abyss.in_shaft(X,Y,Z)|abyss.in_basin(X,Y,Z)
    floor=np.full((TILE,TILE),SIZE_Y,dtype=np.int64)
    for i,x in enumerate(xs):
        for k,z in enumerate(zs):
            v=abyss.CLEAR_COLUMNS.get((int(x),int(z)))
            if v is not None:floor[i,k]=v
    mask|=Y>=floor[:,None,:]
    lo=np.array([xs[0],ys[0],zs[0]],float);hi=np.array([xs[-1],ys[-1],zs[-1]],float)
    for center,r in abyss.CLEAR_SPHERES:
        p=np.array(center)
        if np.any(p+r<lo) or np.any(p-r>hi):continue
        mask|=(X-p[0])**2+(Y-p[1])**2+(Z-p[2])**2<=r*r
    return X[mask],Y[mask],Z[mask]


def export(c):
    root=ROOT_TILE;parent={root:None};queue=deque([root]);order=[]
    directions=[((1,0,0),'east'),((-1,0,0),'west'),((0,1,0),'up'),((0,-1,0),'down'),((0,0,1),'south'),((0,0,-1),'north')]
    limits=(GRID_XZ,GRID_Y,GRID_XZ)
    while queue:
        p=queue.popleft();order.append(p)
        for delta,face in directions:
            q=tuple(p[i]+delta[i] for i in range(3))
            if all(0<=q[i]<limits[i] for i in range(3)) and q not in parent:parent[q]=(p,face);queue.append(q)
    tiles={p:({}, {}) for p in order}
    for pos,s in c.cells.items():
        assert 0<=pos[0]<SIZE and 0<=pos[1]<SIZE_Y and 0<=pos[2]<SIZE,('超出原版结构范围',pos,s)
        key=(pos[0]//TILE,pos[1]//TILE,pos[2]//TILE)
        tiles[key][0][(pos[0]%TILE,pos[1]%TILE,pos[2]%TILE)]=s
    air=0
    for key,(cells,_) in tiles.items():
        for x,y,z in zip(*tile_air(key)):
            local=(int(x)%TILE,int(y)%TILE,int(z)%TILE)
            if local not in cells:cells[local]=AIR;air+=1
    ports={'east':(TILE-1,1,1),'west':(0,1,1),'up':(1,TILE-1,1),'down':(1,0,1),'south':(1,1,TILE-1),'north':(1,1,0)}
    opposite={'east':'west','west':'east','up':'down','down':'up','north':'south','south':'north'}
    depth={root:0}
    for child,relation in parent.items():
        if relation is None:continue
        p,face=relation;name='tile_'+'_'.join(map(str,child));depth[child]=depth[p]+1
        connector(*tiles[p],ports[face],face,'out_'+name,'in_'+name,name)
        connector(*tiles[child],ports[opposite[face]],opposite[face],'in_'+name,'none',None)
    connector(*tiles[root],ANCHOR_LOCAL,'up','taixuan_anchor','none',None)
    # 镇渊柱底座外缘、深渊口平台的边：往下接地到实地
    foundations=[]
    for x,y,z in sorted(set(abyss.FOOTINGS)):
        if y%TILE==TILE-1:
            # 接地子件放在拼接块上面一格；拼接块在模板顶层的话，那一格落进上面那块模板，原版拼接会因为碰撞扔掉它。
            # 往下挪一格，原来那格补成石头，石柱照样从支撑下面接下去
            local=x%TILE,y%TILE,z%TILE
            tiles[x//TILE,y//TILE,z//TILE][0].setdefault(local,STONE)
            y-=1
        p=x//TILE,y//TILE,z//TILE;local=x%TILE,y%TILE,z%TILE
        cells,tags=tiles[p]
        if local not in cells or cells[local] in (AIR,abyss.MIST):cells[local]=STONE
        if local in tags:continue
        connector(cells,tags,local,'up','foundation','minecraft:bottom','footings')
        foundations.append([x,y,z])
    for path in (D/'structure/taixuan').glob('tile_*.nbt'):path.unlink()
    for path in (D/'worldgen/template_pool/taixuan').glob('tile_*.json'):path.unlink()
    for key,(cells,tags) in tiles.items():
        name='tile_'+'_'.join(map(str,key))
        write_structure(D/'structure/taixuan'/f'{name}.nbt',[TILE]*3,cells,tags,data_version=4790,entities=[])
        pool(name)
    for path in (D/'structure/taixuan/components').glob('*.nbt'):path.unlink()
    for name,module in COMPONENTS.items():
        size,cells=module.normalized()
        write_structure(D/'structure/taixuan/components'/f'{name}.nbt',size,cells,data_version=4790,entities=[])
    return {'tiles':len(tiles),'tile_size':TILE,'canvas_center':CENTER,'canvas_size':[SIZE,SIZE_Y,SIZE],
            'depth':max(depth.values()),'root':'tile_'+'_'.join(map(str,root)),'anchor':list(ANCHOR_LOCAL),
            'anchor_canvas':list(ANCHOR_CANVAS),'start_height':START_HEIGHT,'world_y_offset':abyss.Y0,
            'footings':foundations,'components':len(COMPONENTS),'explicit_air':air}


# 远行大陆的天然地表都是各群系自己的地皮方块，原版草方块只出现在结构里；砌筑材料、树叶、原木也不会是天然地面。
NO_TREE_ROOTS=['minecraft:grass_block','minecraft:moss_block','minecraft:calcite','minecraft:stone_bricks','minecraft:polished_andesite',
    'minecraft:smooth_sandstone','minecraft:mossy_cobblestone','minecraft:andesite','minecraft:diorite','minecraft:tuff',
    'minecraft:dripstone_block','minecraft:clay','minecraft:lantern','minecraft:bell','minecraft:bamboo','minecraft:melon',
    'minecraft:pumpkin','minecraft:barrel','minecraft:chest','minecraft:bookshelf','minecraft:decorated_pot','minecraft:composter',
    'minecraft:crafting_table','minecraft:smoker','minecraft:cauldron','minecraft:water_cauldron','minecraft:ladder',
    'minecraft:polished_blackstone','minecraft:polished_deepslate','minecraft:deepslate_tiles','minecraft:cracked_deepslate_tiles',
    'minecraft:cobbled_deepslate','minecraft:polished_diorite','minecraft:smooth_stone','minecraft:sea_lantern',
    'minecraft:cracked_stone_bricks','minecraft:shroomlight','minecraft:campfire','minecraft:iron_chain','minecraft:soul_lantern','minecraft:chiseled_stone_bricks',
    NS+'journey_white_plaster',NS+'journey_kawara_roof',NS+'taixuan_carved_brick',NS+'taixuan_lattice_window',
    NS+'taixuan_brass_tiles',NS+'taixuan_brass_block',NS+'taixuan_dark_iron',NS+'taixuan_abyss_mist',
    '#minecraft:slabs','#minecraft:stairs','#minecraft:wool','#minecraft:walls','#minecraft:fences',
    '#minecraft:planks','#minecraft:leaves','#minecraft:logs','#minecraft:doors','#minecraft:trapdoors','#minecraft:flower_pots',
    '#minecraft:wool_carpets']
# 只出现在建筑里、天然地表不会有的材料。云岫峰林崖壁上的松和灌丛（悬在半空、贴着岩壁长）不能挨着这些：
# 它们的落点只看旁边有没有实心方块，会长进镇渊柱的楼梯井、贴着桥长出来挡路。天然崖壁的砂岩、安山岩、凝灰岩、方解石条纹不在里面
BUILT_SURFACES=['minecraft:stone_bricks','minecraft:cracked_stone_bricks','minecraft:chiseled_stone_bricks','minecraft:mossy_stone_bricks',
    'minecraft:polished_andesite','minecraft:polished_diorite','minecraft:polished_deepslate','minecraft:polished_blackstone',
    'minecraft:deepslate_tiles','minecraft:cracked_deepslate_tiles','minecraft:smooth_stone','minecraft:sea_lantern','minecraft:shroomlight',
    'minecraft:lantern','minecraft:soul_lantern','minecraft:campfire','minecraft:iron_chain','minecraft:stripped_dark_oak_log',
    'minecraft:bookshelf','minecraft:barrel','minecraft:chest',
    NS+'journey_white_plaster',NS+'journey_kawara_roof',NS+'taixuan_carved_brick',NS+'taixuan_lattice_window',NS+'taixuan_brass_tiles',
    NS+'taixuan_brass_block',NS+'taixuan_dark_iron',NS+'taixuan_abyss_mist',
    '#minecraft:stairs','#minecraft:slabs','#minecraft:walls','#minecraft:fences','#minecraft:planks','#minecraft:doors',
    '#minecraft:trapdoors','#minecraft:wool','#minecraft:wool_carpets']
# 贴壁长的：崖壁松看周围四格、头顶六格（树干会分叉，树冠离根四五格；正上方有桥的话会长进桥面），
# 崖壁灌丛（一格树叶）和崖壁藤蔓只看紧挨着的（藤蔓爬满镇渊柱外墙和铁链，在白雾前面是一片黑斑）
WALL_FEATURES={'cloud_peaks_wall_pines':[(d*sx,0,d*sz) for d in (1,2,3,4) for sx,sz in ((1,0),(-1,0),(0,1),(0,-1))]
                   +[(d*sx,0,d*sz) for d in (2,3) for sx in (1,-1) for sz in (1,-1)]+[(0,-1,0)]+[(0,dy,0) for dy in range(1,7)]
                   +[(2*sx,4,2*sz) for sx,sz in ((1,0),(-1,0),(0,1),(0,-1))],
               'cloud_peaks_cliff_shrubs':[(1,0,0),(-1,0,0),(0,0,1),(0,0,-1),(0,-1,0),(0,1,0)],
               'cloud_peaks_cliff_vines':[(1,0,0),(-1,0,0),(0,0,1),(0,0,-1),(0,1,0)]}
TREE_FEATURES=('trees_cloudsea_','tall_trees_cloudsea_','fallen_tree_cloudsea_','forest_rock_outcrop',
    'cloud_peaks_summit_pines','cloud_peaks_edge_pines','cloud_peaks_wall_pines','cloud_peaks_valley_trees')


def guard_tree_roots():
    """远行大陆的树、高树、倒木、林地石堆和云岫峰林的黄山松不在建筑、草方块、树冠上扎根。

    这些放置规则只排除了浮空岛石，按高度图落点就种。自然生成时结构先放、树后长：
    宗门浮岛的草地、链台、镇渊柱顶都会长出整棵树（树干下那格还被换成泥土）；开发测试是事后整体摆放，所以一直看不出来。
    新增方块标签 no_tree_roots，在这几类放置规则的群系过滤之前加一道“落点下方不是这些方块”。"""
    write(D/'tags/block/no_tree_roots.json',{'replace':False,'values':NO_TREE_ROOTS})
    guard={'type':'minecraft:block_predicate_filter','predicate':{'type':'minecraft:not','predicate':{
        'type':'minecraft:matching_block_tag','offset':[0,-1,0],'tag':NS+'no_tree_roots'}}}
    changed=0
    for path in sorted((D/'worldgen/placed_feature').glob('*.json')):
        if not path.stem.startswith(TREE_FEATURES):continue
        spec=json.loads(path.read_text('utf-8'));mods=spec['placement']
        # 区块装饰时原版只更新最终高度图，*_WG 停在原始地形：按 OCEAN_FLOOR_WG 种的树看不见结构，
        # 会照原地形的高度长在结构里、甚至带着一格泥土悬在半空。原版树用的就是 OCEAN_FLOOR。
        for m in mods:
            if m.get('type')=='minecraft:heightmap' and m.get('heightmap')=='OCEAN_FLOOR_WG':m['heightmap']='OCEAN_FLOOR'
        mods[:]=[m for m in mods if m!=guard]
        at=next((i for i,m in enumerate(mods) if m.get('type')=='minecraft:biome'),len(mods))
        mods.insert(at,guard);write(path,spec);changed+=1
    assert changed>=44,changed
    # 贴壁长的松和灌丛：周围几格有建筑材料就不长（放在最后一道，灌丛的落点在随机偏移之后）
    write(D/'tags/block/built_surfaces.json',{'replace':False,'values':BUILT_SURFACES})
    walled=0
    for name,offsets in WALL_FEATURES.items():
        path=D/'worldgen/placed_feature'/f'{name}.json'
        spec=json.loads(path.read_text('utf-8'));mods=spec['placement']
        wall_guard={'type':'minecraft:block_predicate_filter','predicate':{'type':'minecraft:not','predicate':{'type':'minecraft:any_of','predicates':[
            {'type':'minecraft:matching_block_tag','offset':list(o),'tag':NS+'built_surfaces'} for o in offsets]}}}
        mods[:]=[m for m in mods if not (m.get('type')=='minecraft:block_predicate_filter' and NS+'built_surfaces' in json.dumps(m))]
        mods.append(wall_guard);write(path,spec);walled+=1
    assert walled==len(WALL_FEATURES)
    return changed


def resources():
    guard_tree_roots()
    # 空地地面的记号方块按放下时那一格原来是什么换掉：空的不放，水放水，土石换成草（见 abyss.basin_floor）
    mark={'predicate_type':'minecraft:block_match','block':abyss.FLOOR_MARK['Name']}
    def where(block):return {'predicate_type':'minecraft:block_match','block':block}
    write(D/'worldgen/processor_list/taixuan_preserve.json',{'processors':[
        {'processor_type':'minecraft:block_ignore','blocks':[B('structure_void')]},
        {'processor_type':'minecraft:rule','rules':[
            {'input_predicate':mark,'location_predicate':where('minecraft:air'),'output_state':B('air')},
            {'input_predicate':mark,'location_predicate':where('minecraft:cave_air'),'output_state':B('air')},
            {'input_predicate':mark,'location_predicate':where('minecraft:water'),'output_state':B('water',level=0)},
            {'input_predicate':mark,'location_predicate':{'predicate_type':'minecraft:always_true'},'output_state':B('grass_block',snowy=False)}]}]})
    write(D/'worldgen/configured_feature/taixuan_footings.json',{'type':'minecraft:block_column','config':{
        'direction':'down','prioritize_tip':False,'allowed_placement':{'type':'minecraft:all_of','predicates':[
            {'type':'minecraft:inside_world_bounds','offset':[0,1,0]},
            {'type':'minecraft:any_of','predicates':[{'type':'minecraft:not','predicate':{'type':'minecraft:solid','offset':[0,1,0]}},
                {'type':'minecraft:matching_block_tag','tag':'minecraft:leaves','offset':[0,1,0]}]}]},
        'layers':[{'height':512,'provider':{'type':'minecraft:simple_state_provider','state':STONE}}]}})
    write(D/'worldgen/placed_feature/taixuan_footings.json',{'feature':NS+'taixuan_footings','placement':[{'type':'minecraft:random_offset','xz_spread':0,'y_spread':-2}]})
    write(D/'worldgen/template_pool/taixuan/footings.json',{'name':NS+'taixuan/footings','fallback':'minecraft:empty','elements':[
        {'weight':1,'element':{'element_type':'minecraft:feature_pool_element','feature':NS+'taixuan_footings','projection':'rigid'}}]})
    biomes='#'+NS+'has_structure/taixuan_sanctuary'
    write(D/'tags/worldgen/biome/has_structure/taixuan_sanctuary.json',{'replace':False,'values':[NS+'cloudsea_cloud_peaks']})
    settings={'biomes':biomes,'step':'surface_structures','terrain_adaptation':'none',
              'spawn_overrides':{k:{'bounding_box':'full','spawns':[]} for k in ('monster','creature','ambient','underground_water_creature','water_creature','water_ambient','axolotls')}}
    layout={**settings,'start_pool':NS+'taixuan/'+'tile_'+'_'.join(map(str,ROOT_TILE)),'start_jigsaw_name':NS+'taixuan_anchor',
            'size':20,'start_height':{'absolute':START_HEIGHT},
            'max_distance_from_center':{'horizontal':128,'vertical':256},'use_expansion_hack':False}
    write(D/'worldgen/structure/taixuan_sanctuary.json',{'type':NS+'taixuan_sanctuary',**settings,'layout':layout,
        # 群系：整座地标实际只到空地边上（约 112 格），外圈 16 个点许缺 2 个；
        # 深渊口一圈：镇渊柱门外和观景台的下行石阶落在离中心约 72 格处，最多下到口沿以下 12 格（世界 y=80），
        # 16 个点许有 3 个低于它（峰林里的溪流常常横穿一两处）。放宽前 31104 格见方只有 4 处，见 docs/TAIXUAN.md
        'biome_radius':112,'rim_radius':72,'rim_min_height':80,'outer_misses':2,'rim_misses':3})
    write(D/'worldgen/structure_set/taixuan_sanctuaries.json',{'structures':[{'structure':NS+'taixuan_sanctuary','weight':1}],
        'placement':{'type':'minecraft:random_spread','spacing':24,'separation':17,'salt':7652031}})


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--study',type=Path);args=parser.parse_args()
    from taixuan_art import assets,brass_assets
    assets();brass_assets()
    c=Canvas();islands=terrain.build(c);paths=sect(c);plants=landscape.build(c,islands,LANDMARKS)
    deep=build_abyss(c,islands)
    extra=details.build(c,islands,LANDMARKS,deep['waterfalls'],deep['island_chains'])
    # 崖边松和原来的崖松一样记进 landscape.pines，审计和世界测试一起查接地
    plants['pines']+=extra.pop('pines')
    clearance(c)
    summary={'structure':NS+'taixuan_sanctuary','biomes':[NS+'cloudsea_cloud_peaks'],
             'landmarks':LANDMARKS,'rooms':ROOMS,
             'islands':[{k:v for k,v in s.items() if k!='surface'} for s in islands],
             'landscape':plants,'details':extra,'circulation':paths,'abyss':deep,'entities':0,'material_cells':len(c.cells),
             'heights':{'world_y_offset':abyss.Y0,'arena':abyss.ARENA,'mist':[abyss.MIST_BOTTOM,abyss.MIST_TOP],
                        'platform':abyss.PLATFORM,'lining_top':abyss.LINING_TOP,'rim':abyss.RIM,
                        'towers':[abyss.TOWER_BASE,abyss.TOWER_TOP],'anchor':abyss.ANCHOR},
             'routes':abyss.ROUTES,
             'generation':'minecraft:noise / minecraft:multi_noise / minecraft:jigsaw (site check: TaixuanStructure)'}
    summary.update(export(c));resources()
    write(D/'taixuan_manifest.json',summary)
    if args.study:
        import pickle
        args.study.mkdir(parents=True,exist_ok=True)
        with (args.study/'taixuan.pkl').open('wb') as f:pickle.dump((c.cells,summary),f)
    print(json.dumps({k:summary[k] for k in ('material_cells','tiles','depth','components','explicit_air','start_height')},ensure_ascii=False),flush=True)


if __name__=='__main__':main()
