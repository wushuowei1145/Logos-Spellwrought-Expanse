"""太玄灵境：拼装局部 Python 建筑与景观，导出原版 NBT、结构池和群系数据。"""
from pathlib import Path
from collections import deque, Counter
import argparse
import json
import math
import sys

from blueprint_nbt import write_structure
from fox_kit import rotate_pos, rotate_state
from taixuan_kit import *
from taixuan_buildings import hall, home, hui_house, market_stall, gate, pagoda
import taixuan_terrain as terrain
import taixuan_landscape as landscape

ROOT=Path(__file__).resolve().parents[1]
D=ROOT/'src/main/resources/data/logos_dimension'
A=ROOT/'src/main/resources/assets/logos_dimension'
NS='logos_dimension:'
SIZE=terrain.SIZE
CENTER=terrain.CENTER
FLOOR=terrain.FLOOR
TILE=28
GRID=SIZE//TILE
LANDMARKS=[]
ROOMS=[]
COMPONENTS={}


def write(path,value):
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n','utf-8')


def put_module(c,name,module,x,y,z,turns=0):
    """中心定位的局部模块，旋转同时处理门、楼梯、原木与连接朝向。"""
    w=max(p[0] for p in module.cells)+1;d=max(p[2] for p in module.cells)+1
    width,depth=(d,w) if turns%2 else (w,d)
    ox,oz=x-width//2,z-depth//2
    # 建筑地块中的未画位置需要显式空气，清除既有树干和树冠；地块外仍保留原地形。
    # 只清地板以上的空间，不削低楼板下的原土，也不抬高地面。
    top=max(p[1] for p in module.cells)
    if name.startswith('city_'):top=max(top,27)
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


def city(c):
    """扩大的城池采用京城式中轴与坊市，住宅改为徽式天井院落。"""
    lo,hi=8,SIZE-9;mid=CENTER
    # 城内整体清出树冠和树干，不能只挖掉脚边几层而留下悬空树冠。
    # 原地面以下保留，墙外树林不动；城内的新庭园在后面单独种植。
    c.clear(lo+2,FLOOR+1,lo+2,hi-2,FLOOR+44,hi-2)
    for x in range(lo,hi+1):
        for z in (lo,hi):
            if abs(x-mid)>8:
                c.box(x,FLOOR,z,x,FLOOR+7,z+1,STONE)
                c.set(x,FLOOR+8,z,WHITE)
                if x%3==0:c.set(x,FLOOR+9,z,STONE)
    for z in range(lo,hi+1):
        for x in (lo,hi):
            if abs(z-mid)>8:
                c.box(x,FLOOR,z,x+1,FLOOR+7,z,STONE)
                c.set(x,FLOOR+8,z,WHITE)
                if z%3==0:c.set(x,FLOOR+9,z,STONE)
    for i,(x,z,turns) in enumerate(((mid,lo+3,2),(mid,hi-3,0),(lo+3,mid,1),(hi-3,mid,3))):
        put_module(c,'city_gate_'+str(i),gate(True),x,FLOOR,z,turns)
    for i,(x,z) in enumerate(((lo+5,lo+5),(hi-5,lo+5),(lo+5,hi-5),(hi-5,hi-5))):
        put_module(c,'city_watch_'+str(i),hall(11,9,1,5,True),x,FLOOR+7,z)
        # 角楼下是有门与爬梯的石砌塔身，不把阁楼只挂在两条城墙的交点上。
        c.box(x-5,FLOOR,z-4,x+5,FLOOR+6,z+4,STONE)
        c.clear(x-2,FLOOR+1,z-3,x+2,FLOOR+6,z+3)
        c.clear(x-1,FLOOR+1,z-4,x+1,FLOOR+3,z-4)
        c.clear(x+2,FLOOR+1,z,x+2,FLOOR+8,z)
        c.box(x+3,FLOOR+1,z,x+3,FLOOR+8,z,STONE)
        c.box(x+2,FLOOR+1,z,x+2,FLOOR+8,z,B('ladder',facing='west',waterlogged=False))
    for x in range(lo+8,hi-7):
        for dz in range(-4,5):
            c.set(x,FLOOR,mid+dz,WHITE if (x+dz)%11 else STONE)
            c.clear(x,FLOOR+1,mid+dz,x,FLOOR+4,mid+dz)
    for z in range(lo+8,hi-7):
        for dx in range(-4,5):
            c.set(mid+dx,FLOOR,z,WHITE if (z+dx)%11 else STONE)
            c.clear(mid+dx,FLOOR+1,z,mid+dx,FLOOR+4,z)
    houses=0;positions=(26,51,76,101,126,151,176,201,226)
    for iz,z in enumerate(positions):
        for ix,x in enumerate(positions):
            if x==mid or z==mid:continue
            if x in (26,226) and z in (26,226):continue
            if z<=76 and abs(x-mid)<=40:continue
            if z==151 and x in (101,151):continue
            if z==76 and x in (51,76,176,201):continue
            if x==226 and z in (76,101):continue
            kind=(ix+iz*3)%4
            put_module(c,'city_home_'+str(houses),hui_house(kind),x,FLOOR,z,0 if z<mid else 2)
            houses+=1
    # 北侧官署区由前门、仪式庭院、正厅与后阁构成连续纵深。
    for x in range(90,163):
        for z in (17,83):
            if abs(x-mid)>5:
                c.box(x,FLOOR+1,z,x,FLOOR+4,z,PLASTER);c.set(x,FLOOR+5,z,slab(True))
    for z in range(17,84):
        for x in (90,162):c.box(x,FLOOR+1,z,x,FLOOR+4,z,PLASTER);c.set(x,FLOOR+5,z,slab(True))
    c.box(94,FLOOR,60,158,FLOOR,79,WHITE)
    c.clear(94,FLOOR+1,60,158,FLOOR+20,79)
    put_module(c,'city_palace_gate',gate(),mid,FLOOR,83)
    put_module(c,'city_office',hall(33,19,2,6,False),mid,FLOOR,45)
    put_module(c,'city_archive',hall(23,11,1,5,True,True),mid,FLOOR,23)
    put_module(c,'city_academy',hall(27,13,1,6,True,True),64,FLOOR,76)
    put_module(c,'city_ancestral_hall',hall(27,13,1,6,True),190,FLOOR,76)
    # 南市留出整片公共广场，摊棚与货架不依赖商人实体。
    c.box(88,FLOOR,138,164,FLOOR,164,STONE)
    c.clear(88,FLOOR+1,138,164,FLOOR+24,164)
    for i,(x,z) in enumerate(((100,148),(151,148),(100,160),(151,160))):
        put_module(c,'city_market_'+str(i),market_stall('red' if i%2 else 'brown'),x,FLOOR,z)
    # 东侧水院预留给浮峰山泉，落水位置不占用住宅或主街。
    c.box(215,FLOOR,63,240,FLOOR,114,STONE)
    c.clear(215,FLOOR+1,63,240,FLOOR+30,114)
    # 两格宽坊巷沿院落外缘相接，不从房屋中心穿过。
    lanes=[38+25*i for i in range(8)]
    for lane in lanes:
        for a in range(18,235):
            for b in (lane,lane+1):
                for x,z in ((a,b),(b,a)):
                    if free_surface(c,x,FLOOR,z) and c.get(x,FLOOR+1,z) in (None,AIR):c.set(x,FLOOR,z,STONE)
    for x in range(lo+3,hi-1):
        for z in (lo+3,lo+4,hi-3,hi-2):
            if free_surface(c,x,FLOOR,z):c.set(x,FLOOR,z,STONE)
    for z in range(lo+3,hi-1):
        for x in (lo+3,lo+4,hi-3,hi-2):
            if free_surface(c,x,FLOOR,z):c.set(x,FLOOR,z,STONE)
    for i,(x,z) in enumerate(((30,30),(222,30),(30,222),(222,222))):
        c.box(x-4,FLOOR-1,z-4,x+4,FLOOR-1,z+4,B('dirt'))
        c.box(x-4,FLOOR,z-4,x+4,FLOOR,z+4,B('grass_block',snowy=False))
        landscape.pine(c,x,FLOOR,z,seed=191+i,height=9)
    for x in range(26,234,19):
        for z in (mid-6,mid+6):lamp(c,x,FLOOR+1,z)
    for z in range(27,234,19):
        for x in (mid-6,mid+6):lamp(c,x,FLOOR+1,z)
    # 城内未分配给院落和园林的地面补成一层石铺地，避免临水地段在街边留下深沟。
    # 不加整块土台，承重点仍交给原版向下接地石基。
    for x in range(lo,hi+1):
        for z in range(lo,hi+1):
            if free_surface(c,x,FLOOR,z):c.set(x,FLOOR,z,STONE)
    return houses


def free_surface(c,x,y,z):
    return c.get(x,y,z) in (None,AIR)


def sect(c):
    """将概念中的宫阙与侧峰缩放到原版结构的有界范围内。"""
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
    building('sect_mountain_gate',gate(),-21,192,-115)
    building('sect_library',pagoda(15,5),-178,253,88)
    building('sect_east_hall',hall(19,15,3,6,study=True),175,273,125)
    building('sect_training_hall',hall(13,11,2,5),106,205,-79)
    building('sect_rear_library',pagoda(11,4),-83,293,223)
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
    for a,b,width,arch in [((-21,192,-97),(0,221,-57),5,1),
        ((-91,224,65),(-148,252,71),3,2),((92,223,100),(140,272,110),3,2),
        ((72,223,-7),(99,205,-61),3,2),((-65,251,140),(-79,292,199),3,2)]:
        p,q=at(*a),at(*b);bridge(c,p,q,width,arch)
        bridges.append({'from':list(p),'to':list(q),'width':width})
    return {'bridges':bridges,'retaining_columns':len(supports),'retaining_samples':supports[::max(1,len(supports)//24)]}


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


def export(c):
    root=(GRID//2,0,GRID//2);parent={root:None};queue=deque([root]);order=[]
    directions=[((1,0,0),'east'),((-1,0,0),'west'),((0,1,0),'up'),((0,-1,0),'down'),((0,0,1),'south'),((0,0,-1),'north')]
    while queue:
        p=queue.popleft();order.append(p)
        for delta,face in directions:
            q=tuple(p[i]+delta[i] for i in range(3))
            if all(0<=v<GRID for v in q) and q not in parent:parent[q]=(p,face);queue.append(q)
    tiles={p:({}, {}) for p in order}
    for pos,s in c.cells.items():
        assert all(0<=v<SIZE for v in pos),('超出原版结构范围',pos,s)
        key=tuple(v//TILE for v in pos)
        tiles[key][0][tuple(v%TILE for v in pos)]=s
    ports={'east':(TILE-1,1,1),'west':(0,1,1),'up':(1,TILE-1,1),'down':(1,0,1),'south':(1,1,TILE-1),'north':(1,1,0)}
    opposite={'east':'west','west':'east','up':'down','down':'up','north':'south','south':'north'}
    depth={root:0}
    for child,relation in parent.items():
        if relation is None:continue
        p,face=relation;name='tile_'+'_'.join(map(str,child));depth[child]=depth[p]+1
        connector(*tiles[p],ports[face],face,'out_'+name,'in_'+name,name)
        connector(*tiles[child],ports[opposite[face]],opposite[face],'in_'+name,'none',None)
    connector(*tiles[root],(TILE//2,FLOOR,TILE//2),'up','taixuan_anchor','none',None)
    # 石基只延伸建筑底部的承重点和街道网格，不改整片地形。
    low={}
    for (x,y,z),s in c.cells.items():
        if y>FLOOR or s['Name'] in {'minecraft:air','minecraft:structure_void'}:continue
        if (x,z) not in low or y<low[x,z][0]:low[x,z]=(y,s)
    foundations=[]
    for (x,z),(y,s) in low.items():
        edge=any((x+dx,z+dz) not in low for dx,dz in ((1,0),(-1,0),(0,1),(0,-1)))
        if not edge and not (x%4==0 and z%4==0):continue
        p=x//TILE,y//TILE,z//TILE;local=x%TILE,y%TILE,z%TILE
        cells,tags=tiles[p]
        if local in tags:continue
        connector(cells,tags,local,'up','foundation','minecraft:bottom','footings')
        foundations.append([x,y,z])
    for key,(cells,tags) in tiles.items():
        name='tile_'+'_'.join(map(str,key))
        write_structure(D/'structure/taixuan'/f'{name}.nbt',[TILE]*3,cells,tags,data_version=4790,entities=[])
        pool(name)
    for name,module in COMPONENTS.items():
        size,cells=module.normalized()
        write_structure(D/'structure/taixuan/components'/f'{name}.nbt',size,cells,data_version=4790,entities=[])
    return {'tiles':len(tiles),'tile_size':TILE,'canvas_center':CENTER,'depth':max(depth.values()),'root':'tile_4_0_4','anchor':[TILE//2,FLOOR,TILE//2],
            'footings':foundations,'components':len(COMPONENTS)}


def resources():
    base=json.loads((D/'worldgen/biome/cloudsea_sunlit_glade.json').read_text('utf-8'))
    base['temperature']=.68;base['downfall']=.58
    base['effects']={'water_color':'#648e97','grass_color':'#839a70','foliage_color':'#617d59'}
    write(D/'worldgen/biome/taixuan_vale.json',base)
    # 新群系沿用温暖林隙的原版表面规则，避免落入默认裸岩规则。
    path=D/'worldgen/noise_settings/journey.json';geology=json.loads(path.read_text('utf-8'))
    def surface_rule(value):
        if isinstance(value,dict):
            biomes=value.get('biome_is')
            if isinstance(biomes,list) and NS+'cloudsea_sunlit_glade' in biomes and NS+'taixuan_vale' not in biomes:
                biomes.append(NS+'taixuan_vale')
            for child in value.values():surface_rule(child)
        elif isinstance(value,list):
            for child in value:surface_rule(child)
    surface_rule(geology);write(path,geology)
    for name in ('is_overworld','has_structure/mineshaft'):
        path=ROOT/'src/main/resources/data/minecraft/tags/worldgen/biome'/f'{name}.json'
        tag=json.loads(path.read_text('utf-8')) if path.exists() else {'replace':False,'values':[]}
        tag['values']=list(dict.fromkeys(tag['values']+[NS+'taixuan_vale']));write(path,tag)
    path=D/'dimension/cloudsea.json';dimension=json.loads(path.read_text('utf-8'));count=0
    for row in dimension['generator']['biome_source']['biomes']:
        p=row['parameters']
        if row['biome'] in (NS+'cloudsea_sunlit_glade',NS+'taixuan_vale') and p['erosion']>=.3 and p['temperature']>=0:
            row['biome']=NS+'taixuan_vale';count+=1
    assert count>0
    write(path,dimension)
    fixture=ROOT/'gametest-packs/crucible/data/minecraft/worldgen/world_preset/flat.json'
    preset=json.loads(fixture.read_text('utf-8'))
    preset['dimensions'][NS+'cloudsea']=dimension
    write(fixture,preset)
    write(D/'worldgen/processor_list/taixuan_preserve.json',{'processors':[{'processor_type':'minecraft:block_ignore','blocks':[B('structure_void')]}]})
    write(D/'worldgen/configured_feature/taixuan_footings.json',{'type':'minecraft:block_column','config':{
        'direction':'down','prioritize_tip':False,'allowed_placement':{'type':'minecraft:all_of','predicates':[
            {'type':'minecraft:inside_world_bounds','offset':[0,1,0]},
            {'type':'minecraft:any_of','predicates':[{'type':'minecraft:not','predicate':{'type':'minecraft:solid','offset':[0,1,0]}},
                {'type':'minecraft:matching_block_tag','tag':'minecraft:leaves','offset':[0,1,0]}]}]},
        'layers':[{'height':512,'provider':{'type':'minecraft:simple_state_provider','state':STONE}}]}})
    write(D/'worldgen/placed_feature/taixuan_footings.json',{'feature':NS+'taixuan_footings','placement':[{'type':'minecraft:random_offset','xz_spread':0,'y_spread':-2}]})
    write(D/'worldgen/template_pool/taixuan/footings.json',{'name':NS+'taixuan/footings','fallback':'minecraft:empty','elements':[
        {'weight':1,'element':{'element_type':'minecraft:feature_pool_element','feature':NS+'taixuan_footings','projection':'rigid'}}]})
    write(D/'worldgen/structure/taixuan_sanctuary.json',{'type':'minecraft:jigsaw','biomes':[NS+'taixuan_vale'],
        'step':'surface_structures','terrain_adaptation':'none','spawn_overrides':{k:{'bounding_box':'full','spawns':[]} for k in ('monster','creature')},
        'start_pool':NS+'taixuan/tile_4_0_4','start_jigsaw_name':NS+'taixuan_anchor','size':19,
        'start_height':{'absolute':-1},'project_start_to_heightmap':'WORLD_SURFACE_WG',
        'max_distance_from_center':{'horizontal':128,'vertical':256},'use_expansion_hack':False})
    write(D/'worldgen/structure_set/taixuan_sanctuaries.json',{'structures':[{'structure':NS+'taixuan_sanctuary','weight':1}],
        'placement':{'type':'minecraft:random_spread','spacing':32,'separation':16,'salt':7652031}})
    for lang,name in (('zh_cn','太玄灵境'),('en_us','Taixuan Vale')):
        p=A/'lang'/f'{lang}.json';obj=json.loads(p.read_text('utf-8'));obj['biome.logos_dimension.taixuan_vale']=name;write(p,obj)
    return count


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--study',type=Path);args=parser.parse_args()
    from taixuan_art import assets
    assets()
    c=Canvas();houses=city(c);islands=terrain.build(c);paths=sect(c);plants=landscape.build(c,islands)
    summary={'biome':NS+'taixuan_vale','structure':NS+'taixuan_sanctuary','city_name':'太玄城',
             'size':[SIZE]*3,'city_floor':FLOOR,'city_houses':houses,'landmarks':LANDMARKS,'rooms':ROOMS,
             'islands':[{k:v for k,v in s.items() if k!='surface'} for s in islands],
             'landscape':plants,'circulation':paths,'entities':0,'material_cells':len(c.cells),
             'generation':'minecraft:noise / minecraft:multi_noise / minecraft:jigsaw'}
    summary.update(export(c));summary['climate_samples']=resources()
    write(D/'taixuan_manifest.json',summary)
    if args.study:
        import pickle
        args.study.mkdir(parents=True,exist_ok=True)
        with (args.study/'taixuan.pkl').open('wb') as f:pickle.dump((c.cells,summary),f)
    print(json.dumps({k:summary[k] for k in ('material_cells','tiles','depth','components','city_houses','climate_samples')},ensure_ascii=False),flush=True)


if __name__=='__main__':main()
