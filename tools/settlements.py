"""User blueprint adaptations and native wheat planted onto existing soil.

Production generation is entirely vanilla NBT/pools/structures. No runtime building filler.
"""
from pathlib import Path
from collections import Counter
import json, math, random, sys
from zipfile import ZipFile
from PIL import Image, ImageDraw
sys.path.insert(0,str(Path(__file__).resolve().parent))
from blueprint_nbt import write_structure
ROOT=Path(__file__).resolve().parent.parent;R=ROOT/'src/main/resources';D=R/'data/logos_dimension';A=R/'assets/logos_dimension'
def write(p,v):
    p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def st(n,**p):return {'Name':n if ':' in n else 'minecraft:'+n,**({'Properties':{k:str(v).lower() for k,v in p.items()}} if p else {})}
AIR=st('air');STONE=st('logos_dimension:journey_limestone');WOOD=st('spruce_planks');GRASS=st('grass_block',snowy=False)
OFF={'north':(0,-1),'south':(0,1),'west':(-1,0),'east':(1,0)};OPP={'north':'south','south':'north','west':'east','east':'west'}
stats=Counter();templates={}
def save(name,size,cells,entities=None,residents=None):
    write_structure(D/'structure/settlement'/f'{name}.nbt',size,cells,entities or {},data_version=4790,entities=residents)
    templates[name]={'size':size,'blocks':len(cells)}
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
def villager(x,y,z,profession='none'):
    return {'pos':[x+.5,float(y),z+.5],'blockPos':[x,y,z],'nbt':{'id':'minecraft:villager','PersistenceRequired':1,'Age':0,'VillagerData':{'type':'minecraft:plains','profession':'minecraft:'+profession,'level':1}}}
def pool(name,choices,projection='rigid'):
    write(D/'worldgen/template_pool/settlement'/f'{name}.json',{'name':'logos_dimension:settlement/'+name,'fallback':'minecraft:empty','elements':[{'weight':weight,'element':{'element_type':'minecraft:single_pool_element','location':'logos_dimension:settlement/'+n,'processors':'minecraft:empty','projection':projection}} for n,weight in choices]})

# Remap full blocks only; directional roof/door/log states retain their original properties.
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
    # The long reference is an open workshop. Give its end wall a real two-block door.
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
    door,face=choose_door(src,spec['size']);dx,dz=OFF[face];height=spec['size'][1]+3
    for farm in [False,True]:
        margin=1 if ref=='stone_cottage' else 3 if ref=='long_house' else 2
        w=spec['size'][0]+margin*2;d=spec['size'][2]+margin*2;ox=oz=margin
        door_y=door[1]+2;floor=door_y-1;c={};e={}
        # Compact native foundations and explicit interior air keep rooms usable on modest slopes.
        cuboid(c,(0,max(0,floor-2),0),(w-1,floor,d-1),st('dirt'))
        cuboid(c,(0,floor,0),(w-1,floor,d-1),GRASS)
        cuboid(c,(0,floor+1,0),(w-1,height-1,d-1),AIR)
        for (x,y,z),s in src.items():c[x+ox,y+2,z+oz]=s
        x,y,z=door;x+=ox;y+=2;z+=oz
        # Dogleg access ends at the centre of the plot face, regardless of source doorway offset.
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
            c[px,y-1,pz]=st('gravel');c[px,y,pz]=AIR;c[px,y+1,pz]=AIR
        joint(c,e,(end[0],y,end[1]),face,'house_entry','empty','empty')
        # Yard lighting and a work station; source inventories/entities are deliberately not copied.
        lamp(c,1,floor+1,1);c[w-2,floor+1,d-2]=st('composter',level=0)
        if farm:
            # Point-sized feature children stay inside the existing house lot, with no
            # field template, foundations, paths or large terrain-adaptation box.
            for xx,zz in [(0,0),(w-1,0),(0,d-1),(w-1,d-1)]:
                joint(c,e,(xx,y,zz),'up','wheat_seed','minecraft:bottom','wheat','minecraft:structure_void')
        name=('farm_' if farm else 'house_')+ref
        residents=[villager(end[0],y,end[1],'farmer')] if farm and ref=='long_house' else []
        save(name,[w,height,d],c,e,residents)
        (farm_names if farm else house_names).append(name)
pool('compact', [('house_stone_cottage',1)])
pool('houses',[(n,1) for n in house_names if 'stone_cottage' not in n])
pool('farms',[(n,1) for n in farm_names if 'stone_cottage' not in n])
pool('narrow_farms',[('farm_long_house',1)])

# Vanilla atomic two-block planting: replace one existing soil block, then its empty top.
# Every attempt independently reads the real surface. Never place water, air, or fill layers.
soil=['minecraft:grass_block','minecraft:dirt','minecraft:coarse_dirt']
# Named custom turf is explicitly allowed; stone, paths, wood, mud, water and crops are not.
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
# Exact obsolete paths only, so regeneration cannot bring the terraforming fields back.
obsolete=[D/'structure/settlement'/('rice_paddy_'+str(i)+'.nbt') for i in range(2)]
obsolete += [D/'structure/settlement'/(n+'_yard.nbt') for n in farm_names]
obsolete += [D/'worldgen/template_pool/settlement'/(n+'.json') for n in ['paddies','narrow_yards','yards']]
obsolete += [D/'worldgen/configured_feature/settlement_wheat_patch.json']
for p in obsolete:
    if p.exists():p.unlink()

# Central clearing, well and market. Four native roads fit inside the vanilla 128-block limit.
for town in [False,True]:
    label='town' if town else 'hamlet';c={};e={};w=24
    for x in range(w):
        for z in range(w):
            c[x,0,z]=st('gravel' if (x*13+z*7)%9 else 'andesite')
            for y in range(1,9):c[x,y,z]=AIR
    for x in range(9,15):
        for z in range(9,15):c[x,0,z]=STONE
    for x in range(10,14):
        for z in range(10,14):c[x,1,z]=STONE if x in [10,13] or z in [10,13] else st('water',level=0)
    for x,z in [(10,10),(13,13)]:cuboid(c,(x,2,z),(x,4,z),st('spruce_fence'))
    cuboid(c,(9,5,9),(14,5,14),st('spruce_slab',type='bottom',waterlogged=False))
    for x,z in [(4,4),(19,4),(4,19),(19,19)]:
        cuboid(c,(x,1,z),(x+1,1,z+1),WOOD)
        if town:
            for xx in [x,x+2]:cuboid(c,(xx,1,z),(xx,3,z),st('spruce_fence'))
            cuboid(c,(x-1,4,z-1),(x+3,4,z+2),st('white_carpet' if x==4 else 'green_carpet'))
            c[x,2,z]=st('barrel',facing='up',open=False)
        else:c[x,2,z]=st('flower_pot')
    for x,z in [(7,7),(16,7),(7,16),(16,16)]:lamp(c,x,1,z)
    for x,z,f in [(12,0,'north'),(12,23,'south'),(0,12,'west'),(23,12,'east')]:joint(c,e,(x,1,z),f,'plaza_road','road_entry',label+'_roads')
    save(label+'_plaza',[24,9,24],c,e,[villager(x,1,z,job) for x,z,job in [(7,5,'farmer'),(17,5,'fletcher'),(5,17,'none'),(17,17,'cartographer')]])
    pool(label+'_start',[(label+'_plaza',1)])
    L=97 if town else 83;c={};e={}
    for x in range(9):
        for z in range(L):
            c[x,0,z]=st('gravel' if 1<=x<=7 else 'cobblestone')
            for y in range(1,7):c[x,y,z]=AIR
    joint(c,e,(4,1,L-1),'south','road_entry','empty','empty')
    distances=[27,50,82] if town else [27,65]
    for i,dist in enumerate(distances):
        for x,f in [(0,'west'),(8,'east')]:
            choices='compact' if i==0 else ('narrow_farms' if town and i==1 else 'farms' if i==1 else 'houses')
            joint(c,e,(x,1,L-1-dist),f,'street_lot','house_entry',choices)
    for z in range(9,L-4,19):lamp(c,0,1,z);lamp(c,8,1,z+3)
    # Gate posts and a bench mark the transition from street to open wilderness.
    for x in [0,8]:cuboid(c,(x,1,1),(x,4,1),STONE)
    save(label+'_road',[9,7,L],c,e);pool(label+'_roads',[(label+'_road',1)],'terrain_matching')
    name='journey_'+label
    write(D/'worldgen/structure'/f'{name}.json',{'type':'minecraft:jigsaw','biomes':['logos_dimension:cloudsea_'+b for b in ['sunlit_glade','starflower_glade','silk_folds','rose_dunes']],'step':'surface_structures','spawn_overrides':{},'terrain_adaptation':'none','start_pool':'logos_dimension:settlement/'+label+'_start','size':4,'start_height':{'absolute':0},'project_start_to_heightmap':'WORLD_SURFACE_WG','max_distance_from_center':112,'use_expansion_hack':False})
    spacing=192 if town else 128
    write(D/'worldgen/structure_set'/f'{name}.json',{'structures':[{'structure':'logos_dimension:'+name,'weight':1}],'placement':{'type':'minecraft:random_spread','spacing':spacing,'separation':spacing-32,'salt':84307+spacing}})
    # Remove obsolete monolithic templates/pools: the locate IDs above deliberately stay stable.
    for old in [D/'structure'/f'{name}.nbt',D/'worldgen/template_pool'/f'{name}.json']:
        if old.exists():old.unlink()

# Legacy 0.9.0 rice assets remain for existing saves only; never generated or offered in the creative tab.
# Eight distinct rice growth stages. Sparse leaves at the base, arching panicles at maturity.
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
write(D/'settlement_manifest.json',{'sources':len(manifest),'native_templates':templates,'adaptation_counts':dict(stats),'planned_houses':{'hamlet':16,'town':24},'crop':'minecraft:wheat','planting':'native feature on existing soil and empty air only','terrain_adaptation':'none','note':'No standalone fields, water, fill layers or terrain flattening. Legacy rice registration is retained only for existing saves.'})
print('Native blueprint settlements:',len(templates),'templates; 16/24 lots, terrain-following vanilla wheat. Sanitization:',dict(stats))
