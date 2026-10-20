"""改造用户蓝图，并在现有土壤上种植原版小麦。

正式世界生成完全使用原版结构、模板池及 NBT，没有运行时建筑填块器。
"""
from pathlib import Path
from collections import Counter
import json, math, random, sys
from zipfile import ZipFile
from PIL import Image, ImageDraw
sys.path.insert(0,str(Path(__file__).resolve().parent))
from blueprint_nbt import write_structure
from settlement_geometry import VOID, without_terrain, room_air
ROOT=Path(__file__).resolve().parent.parent;R=ROOT/'src/main/resources';D=R/'data/logos_dimension';A=R/'assets/logos_dimension'
def write(p,v):
    p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def st(n,**p):return {'Name':n if ':' in n else 'minecraft:'+n,**({'Properties':{k:str(v).lower() for k,v in p.items()}} if p else {})}
AIR=st('air');STONE=st('logos_dimension:journey_limestone');WOOD=st('spruce_planks');GRASS=st('grass_block',snowy=False)
OFF={'north':(0,-1),'south':(0,1),'west':(-1,0),'east':(1,0)};OPP={'north':'south','south':'north','west':'east','east':'west'}
stats=Counter();templates={}
def save(name,size,cells,entities=None):
    write_structure(D/'structure/settlement'/f'{name}.nbt',size,cells,entities or {},data_version=4790,entities=[])
    templates[name]={'size':size,'blocks':len(cells),'air':sum(s==AIR for s in cells.values()),'preserved_void':sum(s==VOID for s in cells.values()),'footings':sum(t['pool'].endswith('/footings') and not t['name'].endswith('footing_backup') for t in (entities or {}).values())}
def joint(c,e,pos,face,name,target,pool,final_state='minecraft:air'):
    c[pos]=st('jigsaw',orientation='up_north' if face=='up' else face+'_up');e[pos]={'id':'minecraft:jigsaw','name':'logos_dimension:'+name,'target':target if ':' in target else 'logos_dimension:'+target,'pool':('minecraft:empty' if pool=='empty' else 'logos_dimension:settlement/'+pool),'joint':'rollable' if face=='up' else 'aligned','final_state':final_state}
def cuboid(c,a,b,s):
    for x in range(a[0],b[0]+1):
        for y in range(a[1],b[1]+1):
            for z in range(a[2],b[2]+1):c[x,y,z]=s
def lamp(c,x,y,z):
    if any(c.get((x,y+dy,z),AIR)['Name']=='minecraft:jigsaw' for dy in range(4)):return
    for dy in range(3):c[x,y+dy,z]=st('spruce_fence')
    c[x,y+3,z]=st('lantern',hanging=False,waterlogged=False)
def pool(name,choices,projection='rigid'):
    write(D/'worldgen/template_pool/settlement'/f'{name}.json',{'name':'logos_dimension:settlement/'+name,'fallback':'minecraft:empty','elements':[{'weight':weight,'element':{'element_type':'minecraft:single_pool_element','location':'logos_dimension:settlement/'+n,'processors':'logos_dimension:settlement_preserve_void','override_liquid_settings':'ignore_waterlogging','projection':projection}} for n,weight in choices]})

# 结构空位保留原地形；空气必须参与放置，才能清空房间。
write(D/'worldgen/processor_list/settlement_preserve_void.json',{'processors':[{'processor_type':'minecraft:block_ignore','blocks':[st('structure_void')]}]})
def paving(x,z):
    n=(x*13+z*7)%13
    return st('mossy_stone_bricks' if n==0 else 'cracked_stone_bricks' if n<4 else 'stone_bricks')

def state_string(s):
    return s['Name']+('['+','.join(k+'='+v for k,v in sorted(s['Properties'].items()))+']' if s.get('Properties') else '')

def footings(c,e,max_y,style='house'):
    # 仅支撑建筑实际底部的方块，不给蓝图矩形地块整体垫底。
    columns={}
    for (x,y,z),s in c.items():
        if s['Name'] in {'minecraft:air','minecraft:structure_void','minecraft:jigsaw'}:continue
        if (x,z) not in columns or y<columns[x,z][0]:columns[x,z]=(y,s)
    bases={}
    for (x,z),(y,s) in columns.items():
        n=s['Name']
        if y<=max_y and (n.endswith(('_planks','_log','_wood','_bricks','_slab','_stairs','_wall','_fence')) or n in {'logos_dimension:journey_limestone','logos_dimension:journey_lime_plaster','minecraft:cobblestone','minecraft:mossy_cobblestone','minecraft:deepslate','minecraft:polished_deepslate','minecraft:smooth_basalt','minecraft:stone','minecraft:smooth_stone'}):
            bases[x,z]=(y,s)
    # 外墙采用连续石基，内部每四格设置支柱。
    # 只从外侧搜索边界，避免地板内部的家具空洞产生多余支柱。
    from collections import deque
    xmin=min(x for x,z in bases)-1;xmax=max(x for x,z in bases)+1
    zmin=min(z for x,z in bases)-1;zmax=max(z for x,z in bases)+1
    exterior={(xmin,zmin)};pending=deque(exterior)
    while pending:
        x,z=pending.popleft()
        for dx,dz in OFF.values():
            p=x+dx,z+dz
            if xmin<=p[0]<=xmax and zmin<=p[1]<=zmax and p not in bases and p not in exterior:
                exterior.add(p);pending.append(p)
    for (x,z),(y,s) in sorted(bases.items()):
        edge=any((x+dx,z+dz) in exterior for dx,dz in OFF.values())
        if style=='house' and not (edge or (x%4==0 and z%4==0)):continue
        if style=='road' and not (x in (0,4,8) and (z%4==0 or z==max(p[1] for p in bases))):continue
        if style=='plaza':
            if not (edge or (x%4==0 and z%4==0) or y<max_y-1 or s['Name'].endswith(('_planks','_fence'))):continue
        joint(c,e,(x,y,z),'up','footing','minecraft:bottom','footings',state_string(s))
        if style=='road':
            # 随地形投影的道路会把子件移到当地地表高度，子件可能超出道路的名义包围盒。
            # 超出包围盒时，父件内部自由空间会拒绝该子件，因此增加朝向盒外的备用端口。
            # 两个端口投影到同一世界位置，只有一个能接入，确保支墩生成且不重复放置。
            joint(c,e,(x,6,z),'up','footing_backup','minecraft:bottom','footings')

# 原版向下柱形特征遇到第一个实心方块即停止，适用于坡地和河床。
# BlockColumnFeature 放置当前格前先检查下一格；偏移 +1 将检查对齐当前格，
# 避免柱脚与地面之间剩下一格空隙。
soft=lambda offset:{'type':'minecraft:any_of','predicates':[{'type':'minecraft:not','predicate':{'type':'minecraft:solid','offset':offset}},{'type':'minecraft:matching_block_tag','tag':'minecraft:leaves','offset':offset}]}
write(D/'worldgen/configured_feature/settlement_footing.json',{'type':'minecraft:block_column','config':{'direction':'down','prioritize_tip':False,'allowed_placement':{'type':'minecraft:all_of','predicates':[{'type':'minecraft:inside_world_bounds','offset':[0,1,0]},soft([0,1,0])]},'layers':[{'height':384,'provider':{'type':'minecraft:simple_state_provider','state':st('stone_bricks')}}]}})
write(D/'worldgen/placed_feature/settlement_footing.json',{'feature':'logos_dimension:settlement_footing','placement':[{'type':'minecraft:random_offset','xz_spread':0,'y_spread':-2},{'type':'minecraft:block_predicate_filter','predicate':soft([0,0,0])}]})
write(D/'worldgen/template_pool/settlement/footings.json',{'name':'logos_dimension:settlement/footings','fallback':'minecraft:empty','elements':[{'weight':1,'element':{'element_type':'minecraft:feature_pool_element','feature':'logos_dimension:settlement_footing','projection':'rigid'}}]})

# 仅替换完整方块的材质；屋顶、门和原木等方向性方块保留原属性。
FORBIDDEN={'structure_block','structure_void','barrier','light','command_block','repeating_command_block','chain_command_block','jigsaw','tnt','spawner','trial_spawner','vault','bedrock','end_portal','nether_portal','reinforced_deepslate'}
ALIASES={'grass':'short_grass','chain':'iron_chain','frame':'spruce_fence','glow_frame':'spruce_fence','spruce_shelf':'spruce_planks','cave_vines_body_with_berries':'cave_vines_plant'}
REMAP={**{n:'logos_dimension:journey_limestone' for n in ['stone','cobblestone','mossy_cobblestone','andesite','polished_andesite','tuff','stone_bricks','cracked_stone_bricks','mossy_stone_bricks']},**{n:'logos_dimension:journey_lime_plaster' for n in ['calcite','diorite','polished_diorite','white_wool','white_concrete','white_terracotta']}}
def adapt(s):
    n=s['Name'].split(':')[-1];p=dict(s.get('Properties',{}));n=ALIASES.get(n,n)
    p={k:v for k,v in p.items() if not k.endswith('_bit') and k not in {'dirt_type','extinguished'} and v!='null'}
    if n.endswith('_sign'):p.pop('hanging',None)
    if n.endswith('_slab') and 'half' in p:p['type']=p.pop('half')
    if n.endswith('_hanging_sign') and not n.endswith('_wall_hanging_sign') and 'facing' in p:
        p['rotation']={'south':'0','west':'4','north':'8','east':'12'}[p.pop('facing')]
    if n=='cave_vines_plant':p={'berries':'true'}
    if n in {'spruce_planks','spruce_fence'}:p={}
    if n in FORBIDDEN:stats['removed_'+n]+=1;return AIR
    if n in {'sand','red_sand','gravel'} or n.endswith('_concrete_powder'):return STONE
    if n.endswith('_ore') or n in {'gold_block','emerald_block','diamond_block','copper_block'}:stats['replaced_resource_blocks']+=1;return STONE
    if n in REMAP:return st(REMAP[n])
    if n.endswith('_door'):p['open']='false';p['powered']='false'
    if n.endswith('_leaves'):p['persistent']='true';p['distance']='1'
    if 'waterlogged' in p:p['waterlogged']='false'
    return st('minecraft:'+n,**p)

def choose_door(c,size):
    choices=[]
    for (x,y,z),s in c.items():
        if not s['Name'].endswith('_door') or s.get('Properties',{}).get('half')!='lower':continue
        f=OPP[s['Properties']['facing']];dx,dz=OFF[f];px,pz=x+dx,z+dz;cost=0;steps=0
        while 0<=px<size[0] and 0<=pz<size[2]:
            for yy in [y,y+1]:
                b=c.get((px,yy,pz),AIR)['Name']
                if b!='minecraft:air' and not any(k in b for k in ['grass','flower','fern','carpet','torch','vine']):cost+=1
            px+=dx;pz+=dz;steps+=1
        choices.append(((y*3+cost*8+steps*.2,cost,steps),(x,y,z),f))
    if choices:return min(choices)[1:]
    # 长屋参考蓝图是开放工坊，给端墙补上一扇真正的两格高木门。
    x=size[0]//2;z=0;y=2
    for dy,half in [(0,'lower'),(1,'upper')]:c[x,y+dy,z]=st('spruce_door',facing='south',half=half,hinge='left',open=False,powered=False)
    return (x,y,z),'north'

manifest=json.loads((ROOT/'references/user_blueprints/manifest.json').read_text(encoding='utf-8'))
house_names=[];farm_names=[]
for entry in manifest:
    ref=entry['geometry'].removesuffix('.json');spec=json.loads((ROOT/'references/user_blueprints'/entry['geometry']).read_text(encoding='utf-8'))
    src={tuple(row[:3]):adapt(spec['palette'][row[3]]) for row in spec['blocks']}
    for (x,y,z),s in list(src.items()):
        if s['Name'].endswith('_door') and s.get('Properties',{}).get('half')=='lower':
            upper=src.get((x,y+1,z))
            if upper and upper['Name']==s['Name']:src[x,y+1,z]=st(s['Name'],**{**s['Properties'],'half':'upper'})
    door,face=choose_door(src,spec['size']);dx,dz=OFF[face];height=spec['size'][1]
    # 移除源图土层，避免改造后的下落材料形成石台。
    raw={tuple(row[:3]):spec['palette'][row[3]] for row in spec['blocks']}
    _,removed=without_terrain(raw,max(0,door[1]-1))
    for pos in removed:src.pop(pos,None)
    clear=room_air(src,spec['size'])
    stats['removed_source_terrain']+=len(removed)
    for farm in [False,True]:
        margin=1 if ref=='stone_cottage' else 3 if ref=='long_house' else 2
        w=spec['size'][0]+margin*2;d=spec['size'][2]+margin*2;ox=oz=margin
        door_y=door[1];floor=door_y-1;c={};e={}
        # 不复制土台、不加垫土或整块草坪，也不大范围挖空地形。
        cuboid(c,(0,0,0),(w-1,height-1,d-1),VOID)
        for x,y,z in clear:c[x+ox,y,z+oz]=AIR
        for (x,y,z),s in src.items():c[x+ox,y,z+oz]=s
        x,y,z=door;x+=ox;z+=oz
        # 无论源图门口偏向哪边，折线路径都接到地块边缘的中央。
        px,pz=x+dx,z+dz
        route=[]
        while 0<=px<w and 0<=pz<d:
            route.append((px,pz));px+=dx;pz+=dz
        end=(w//2 if dz else (0 if dx<0 else w-1),d//2 if dx else (0 if dz<0 else d-1))
        if route:
            ax,az=route[-1]
            while (ax,az)!=end:
                ax+=0 if ax==end[0] else (1 if ax<end[0] else -1);az+=0 if az==end[1] else (1 if az<end[1] else -1);route.append((ax,az))
        for px,pz in route:
            if y>0:c[px,y-1,pz]=paving(px,pz)
            c[px,y,pz]=AIR;c[px,y+1,pz]=AIR
        joint(c,e,(end[0],y,end[1]),face,'house_entry','empty','empty')
        # 保留源图家具，不添加悬空庭院柱、村民或动物。
        if farm:
            # 单点种植子件位于现有宅地内，不附带田块模板、地基、道路或大型地形适配盒。
            for xx,zz in [(0,0),(w-1,0),(0,d-1),(w-1,d-1)]:
                joint(c,e,(xx,y,zz),'up','wheat_seed','minecraft:bottom','wheat','minecraft:structure_void')
        name=('farm_' if farm else 'house_')+ref
        footings(c,e,max(door_y,1))
        save(name,[w,height,d],c,e)
        (farm_names if farm else house_names).append(name)
pool('compact', [('house_stone_cottage',1)])
pool('houses',[(n,1) for n in house_names if 'stone_cottage' not in n])
pool('farms',[(n,1) for n in farm_names if 'stone_cottage' not in n])
pool('narrow_farms',[('farm_long_house',1)])

# 原版两格种植：替换一格现有土壤，再在其上方原空位种植。
# 每次尝试独立读取真实地表，不铺水、不清空空气，也不添加填充层。
soil=['minecraft:grass_block','minecraft:dirt','minecraft:coarse_dirt']
# 明确允许对应的自定义草皮；石头、道路、木材、泥巴、水和作物均不允许。
soil += ['logos_dimension:'+p.stem.removesuffix('_ground')+'_ground' for p in (A/'blockstates').glob('cloudsea_*_ground.json')]
write(D/'tags/block/journey_wheat_soil.json',{'values':sorted(set(soil))})
column={'type':'minecraft:block_column','config':{'direction':'up','prioritize_tip':False,'allowed_placement':{'type':'minecraft:true'},'layers':[
    {'height':1,'provider':{'type':'minecraft:simple_state_provider','state':st('farmland',moisture=7)}},
    {'height':1,'provider':{'type':'minecraft:weighted_state_provider','entries':[{'data':st('wheat',age=age),'weight':weight} for age,weight in [(4,1),(5,2),(6,2),(7,5)]]}}
]}}
write(D/'worldgen/configured_feature/settlement_wheat_column.json',column)
write(D/'worldgen/placed_feature/settlement_wheat.json',{'feature':'logos_dimension:settlement_wheat_column','placement':[
    {'type':'minecraft:count','count':192},
    {'type':'minecraft:random_offset','xz_spread':{'type':'minecraft:uniform','min_inclusive':-7,'max_inclusive':7},'y_spread':0},
    {'type':'minecraft:heightmap','heightmap':'MOTION_BLOCKING_NO_LEAVES'},
    {'type':'minecraft:random_offset','xz_spread':0,'y_spread':-1},
    {'type':'minecraft:block_predicate_filter','predicate':{'type':'minecraft:all_of','predicates':[
        {'type':'minecraft:matching_block_tag','tag':'logos_dimension:journey_wheat_soil'},
        {'type':'minecraft:matching_blocks','offset':[0,1,0],'blocks':['minecraft:air']}
    ]}}
]})
write(D/'worldgen/template_pool/settlement/wheat.json',{'name':'logos_dimension:settlement/wheat','fallback':'minecraft:empty','elements':[{'weight':1,'element':{'element_type':'minecraft:feature_pool_element','feature':'logos_dimension:settlement_wheat','projection':'rigid'}}]})
# 仅删除明确列出的旧路径，避免重新生成时恢复会改造地形的旧田块。
obsolete=[D/'structure/settlement'/('rice_paddy_'+str(i)+'.nbt') for i in range(2)]
obsolete += [D/'structure/settlement'/(n+'_yard.nbt') for n in farm_names]
obsolete += [D/'worldgen/template_pool/settlement'/(n+'.json') for n in ['paddies','narrow_yards','yards']]
obsolete += [D/'worldgen/configured_feature/settlement_wheat_patch.json']
for p in obsolete:
    if p.exists():p.unlink()

def covered_well(c,ground):
    # 密封井筒、下沉水面、厚石井沿、四根木柱和人字屋顶。
    for x in range(10,14):
        for z in range(10,14):
            for y in range(ground-3,ground+2):
                c[x,y,z]=STONE if x in (10,13) or z in (10,13) or y==ground-3 else (st('water',level=0) if y==ground-2 else AIR)
    for x,z in [(10,10),(10,13),(13,10),(13,13)]:c[x,ground+1,z]=st('chiseled_stone_bricks')
    for x,z in [(9,10),(14,10),(9,13),(14,13)]:
        c[x,ground+1,z]=st('stone_brick_wall',up=True,north='none',south='none',east='none',west='none',waterlogged=False)
        cuboid(c,(x,ground+2,z),(x,ground+5,z),st('stripped_spruce_log',axis='y'))
    for z in (10,13):cuboid(c,(9,ground+5,z),(14,ground+5,z),st('spruce_log',axis='x'))
    # 三层递升屋面、宽屋檐与叠合屋脊，全部采用不会下落的方块。
    for x in range(8,16):
        rise=min(x-8,15-x);y=ground+5+min(rise,3)
        for z in range(9,15):
            c[x,y,z]=st('spruce_slab',type='bottom',waterlogged=False) if x in (11,12) else st('spruce_stairs',facing='east' if x<12 else 'west',half='bottom',shape='straight',waterlogged=False)
    cuboid(c,(10,ground+4,11),(13,ground+4,11),st('dark_oak_log',axis='x'))
    c[9,ground+4,11]=st('spruce_trapdoor',facing='west',half='top',open=True,powered=False,waterlogged=False)
    for y in range(ground+2,ground+4):c[11,y,11]=st('iron_chain',axis='y',waterlogged=False)
    for x,z in [(10,12),(13,12)]:
        for y in (ground+5,ground+6):c[x,y,z]=st('iron_chain',axis='y',waterlogged=False)
        c[x,ground+4,z]=st('lantern',hanging=True,waterlogged=False)

# 中央空地、井和市场；四条原版道路保持在原版 128 格范围限制内。
for town in [False,True]:
    label='town' if town else 'hamlet';c={};e={};w=24;g=3
    cuboid(c,(0,0,0),(23,13,23),VOID)
    for x in range(w):
        for z in range(w):
            # 小型圆角广场和连接道路，不铺实心矩形土台。
            if (x-11.5)**2+(z-11.5)**2<=100 or 10<=x<=13 or 10<=z<=13:
                c[x,g,z]=paving(x,z)
                for y in range(g+1,g+5):c[x,y,z]=AIR
    covered_well(c,g)
    for x,z in [(4,4),(19,4),(4,19),(19,19)]:
        cuboid(c,(x,g+1,z),(x+1,g+1,z+1),WOOD)
        if town:
            for xx in [x,x+2]:cuboid(c,(xx,g+1,z),(xx,g+3,z),st('spruce_fence'))
            cuboid(c,(x-1,g+4,z-1),(x+3,g+4,z+2),st('spruce_slab',type='bottom',waterlogged=False))
            c[x,g+2,z]=st('barrel',facing='up',open=False)
        else:c[x,g+2,z]=st('flower_pot')
    for x,z in [(7,7),(16,7),(7,16),(16,16)]:lamp(c,x,g+1,z)
    for x,z,f in [(12,0,'north'),(12,23,'south'),(0,12,'west'),(23,12,'east')]:joint(c,e,(x,g+1,z),f,'plaza_road','road_entry',label+'_roads')
    footings(c,e,g+1,'plaza')
    save(label+'_plaza',[24,14,24],c,e)
    pool(label+'_start',[(label+'_plaza',1)])
    L=97 if town else 83;c={};e={}
    for x in range(9):
        for z in range(L):
            c[x,0,z]=paving(x,z) if 1<=x<=7 else st('mossy_cobblestone')
            for y in range(1,7):c[x,y,z]=AIR
    joint(c,e,(4,1,L-1),'south','road_entry','empty','empty')
    distances=[27,50,82] if town else [27,65]
    for i,dist in enumerate(distances):
        for x,f in [(0,'west'),(8,'east')]:
            choices='compact' if i==0 else ('narrow_farms' if town and i==1 else 'farms' if i==1 else 'houses')
            joint(c,e,(x,1,L-1-dist),f,'street_lot','house_entry',choices)
    for z in range(9,L-4,19):lamp(c,0,1,z);lamp(c,8,1,z+3)
    # 门柱与长椅标记街道通向荒野的过渡位置。
    for x in [0,8]:cuboid(c,(x,1,1),(x,4,1),STONE)
    footings(c,e,0,'road')
    save(label+'_road',[9,7,L],c,e);pool(label+'_roads',[(label+'_road',1)],'terrain_matching')
    name='journey_'+label
    write(D/'worldgen/structure'/f'{name}.json',{'type':'minecraft:jigsaw','biomes':['logos_dimension:cloudsea_'+b for b in ['sunlit_glade','starflower_glade','silk_folds','rose_dunes']],'step':'surface_structures','spawn_overrides':{'creature':{'bounding_box':'full','spawns':[]}},'terrain_adaptation':'none','start_pool':'logos_dimension:settlement/'+label+'_start','size':4,'start_height':{'absolute':-g},'project_start_to_heightmap':'WORLD_SURFACE_WG','max_distance_from_center':112,'use_expansion_hack':False})
    spacing=192 if town else 128
    write(D/'worldgen/structure_set'/f'{name}.json',{'structures':[{'structure':'logos_dimension:'+name,'weight':1}],'placement':{'type':'minecraft:random_spread','spacing':spacing,'separation':spacing-32,'salt':84307+spacing}})
    # 移除过时的整块模板与模板池；上方定位命令使用的标识保持不变。
    for old in [D/'structure'/f'{name}.nbt',D/'worldgen/template_pool'/f'{name}.json']:
        if old.exists():old.unlink()

# 旧版 0.9.0 水稻资源仅为已有存档保留，不再生成或显示在创造物品栏。
# 水稻有八个独立生长阶段，基部叶片疏落，成熟时稻穗弯垂。
for age in range(8):
    im=Image.new('RGBA',(64,64));q=ImageDraw.Draw(im);rng=random.Random(950+age)
    for stem in range(6):
        x=7+stem*10+rng.randrange(-2,3);top=52-age*5-rng.randrange(0,4)
        bend=-1 if stem%2 else 1
        q.line([(x,63),(x-2*bend,35),(x+bend*4,top)],fill='#6b833e',width=2)
        for yy,sign in [(48,-1),(40,1),(31,-1)]:
            if yy<top+4:continue
            q.polygon([(x,yy+5),(x+sign*8,yy-7),(x+sign*11,yy-9),(x+sign*5,yy+2)],fill='#849850')
            q.line((x,yy+4,x+sign*10,yy-8),fill='#b0b76d')
        if age>=5:
            end=(x+bend*11,top+7);q.line([(x+bend*4,top+6),(x+bend*6,top),end],fill='#bcaa60',width=1)
            for n in range(6):
                px=x+bend*(5+n);py=top+max(0,n-1)
                q.ellipse((px-1,py,px+1,py+3),fill=['#bec278','#d2c281','#decf9c'][age-5])
    name='journey_rice_'+str(age);p=A/'textures/block'/f'{name}.png';p.parent.mkdir(parents=True,exist_ok=True);im.save(p)
    h=min(32,12+age*3);elements=[]
    for z in [4,12]:
        elements.append({'from':[0,0,z],'to':[16,h,z],'faces':{f:{'texture':'#crop','uv':[0,0,16,16]} for f in ['north','south']}})
    for x in [4,12]:
        elements.append({'from':[x,0,0],'to':[x,h,16],'faces':{f:{'texture':'#crop','uv':[0,0,16,16]} for f in ['east','west']}})
    write(A/'models/block'/f'{name}.json',{'parent':'minecraft:block/block','render_type':'neoforge:cutout','ambientocclusion':False,'textures':{'particle':'logos_dimension:block/'+name,'crop':'logos_dimension:block/'+name},'elements':elements})
write(A/'blockstates/journey_rice.json',{'variants':{'age='+str(i):{'model':'logos_dimension:block/journey_rice_'+str(i)} for i in range(8)}})
im=Image.new('RGBA',(32,32));q=ImageDraw.Draw(im)
for x,y,a in [(8,11,0),(17,8,1),(21,19,0),(10,22,1)]:q.ellipse((x-2,y-4,x+2,y+4),fill='#e4d5a5',outline='#a69969');q.line((x,y-2,x,y+2),fill='#fff0c3')
(A/'textures/item').mkdir(parents=True,exist_ok=True);im.save(A/'textures/item/journey_rice.png')
write(A/'models/item/journey_rice.json',{'parent':'minecraft:item/generated','textures':{'layer0':'logos_dimension:item/journey_rice'}})
write(A/'items/journey_rice.json',{'model':{'type':'minecraft:model','model':'logos_dimension:item/journey_rice'}})
write(D/'loot_table/blocks/journey_rice.json',{'type':'minecraft:block','pools':[{'rolls':1,'entries':[{'type':'minecraft:item','name':'logos_dimension:journey_rice'}]},{'rolls':1,'conditions':[{'condition':'minecraft:block_state_property','block':'logos_dimension:journey_rice','properties':{'age':'7'}}],'entries':[{'type':'minecraft:item','name':'logos_dimension:journey_rice','functions':[{'function':'minecraft:set_count','count':{'type':'minecraft:uniform','min':1,'max':3}}]}]}]})
for lang,title in [('zh_cn','水稻'),('en_us','Rice')]:
    p=A/'lang'/f'{lang}.json';v=json.loads(p.read_text(encoding='utf-8'));v['block.logos_dimension.journey_rice']=title;write(p,v)
write(D/'settlement_manifest.json',{'sources':len(manifest),'native_templates':templates,'adaptation_counts':dict(stats),'planned_houses':{'hamlet':16,'town':24},'crop':'minecraft:wheat','planting':'native feature on existing soil and empty air only','terrain_adaptation':'none','entities':False,'foundation':'native downward stone footings to the first solid block; no earth slab','interior':'explicit air; exterior structure void ignored by native processor','paving':'non-falling stone bricks with bridge piers','note':'No standalone fields, earth fill layers, terrain flattening, villagers or animals. Footings extend only below actual building bases, plaza edges/grid and road bridge bents. Raised architectural planters stay. Legacy rice registration is retained only for existing saves.'})
print('Native blueprint settlements:',len(templates),'templates; 16/24 lots, terrain-following vanilla wheat. Sanitization:',dict(stats))
