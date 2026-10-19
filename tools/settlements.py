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
    templates[name]={'size':size,'blocks':len(cells),'air':sum(s==AIR for s in cells.values()),'preserved_void':sum(s==VOID for s in cells.values())}
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

# Structure void preserves existing terrain; AIR must remain active to empty rooms.
write(D/'worldgen/processor_list/settlement_preserve_void.json',{'processors':[{'processor_type':'minecraft:block_ignore','blocks':[st('structure_void')]}]})
def paving(x,z):
    n=(x*13+z*7)%13
    return st('mossy_stone_bricks' if n==0 else 'cracked_stone_bricks' if n<4 else 'stone_bricks')

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
    door,face=choose_door(src,spec['size']);dx,dz=OFF[face];height=spec['size'][1]
    # Strip source soil before adapting falling materials so it cannot become a stone plinth.
    raw={tuple(row[:3]):spec['palette'][row[3]] for row in spec['blocks']}
    _,removed=without_terrain(raw,max(0,door[1]-1))
    for pos in removed:src.pop(pos,None)
    clear=room_air(src,spec['size'])
    stats['removed_source_terrain']+=len(removed)
    for farm in [False,True]:
        margin=1 if ref=='stone_cottage' else 3 if ref=='long_house' else 2
        w=spec['size'][0]+margin*2;d=spec['size'][2]+margin*2;ox=oz=margin
        door_y=door[1];floor=door_y-1;c={};e={}
        # No copied earth slab, added soil, whole-plot lawn or broad air excavation.
        cuboid(c,(0,0,0),(w-1,height-1,d-1),VOID)
        for x,y,z in clear:c[x+ox,y,z+oz]=AIR
        for (x,y,z),s in src.items():c[x+ox,y,z+oz]=s
        x,y,z=door;x+=ox;z+=oz
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
            if y>0:c[px,y-1,pz]=paving(px,pz)
            c[px,y,pz]=AIR;c[px,y+1,pz]=AIR
        joint(c,e,(end[0],y,end[1]),face,'house_entry','empty','empty')
        # Source furniture stays; no free-floating yard posts, villagers or animals are added.
        if farm:
            # Point-sized feature children stay inside the existing house lot, with no
            # field template, foundations, paths or large terrain-adaptation box.
            for xx,zz in [(0,0),(w-1,0),(0,d-1),(w-1,d-1)]:
                joint(c,e,(xx,y,zz),'up','wheat_seed','minecraft:bottom','wheat','minecraft:structure_void')
        name=('farm_' if farm else 'house_')+ref
        save(name,[w,height,d],c,e)
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

def covered_well(c,ground):
    # Sealed shaft, recessed water, thick masonry curb, four timber posts and a gabled roof.
    for x in range(10,14):
        for z in range(10,14):
            for y in range(ground-3,ground+2):
                c[x,y,z]=STONE if x in (10,13) or z in (10,13) or y==ground-3 else (st('water',level=0) if y==ground-2 else AIR)
    for x,z in [(10,10),(10,13),(13,10),(13,13)]:c[x,ground+1,z]=st('chiseled_stone_bricks')
    for x,z in [(9,10),(14,10),(9,13),(14,13)]:
        c[x,ground+1,z]=st('stone_brick_wall',up=True,north='none',south='none',east='none',west='none',waterlogged=False)
        cuboid(c,(x,ground+2,z),(x,ground+5,z),st('stripped_spruce_log',axis='y'))
    for z in (10,13):cuboid(c,(9,ground+5,z),(14,ground+5,z),st('spruce_log',axis='x'))
    # Three rising courses, wide eaves and an overlapping ridge. All blocks are non-falling.
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

# Central clearing, well and market. Four native roads fit inside the vanilla 128-block limit.
for town in [False,True]:
    label='town' if town else 'hamlet';c={};e={};w=24;g=3
    cuboid(c,(0,0,0),(23,13,23),VOID)
    for x in range(w):
        for z in range(w):
            # Small rounded square and connecting paths, not a solid rectangular earth tile.
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
    # Gate posts and a bench mark the transition from street to open wilderness.
    for x in [0,8]:cuboid(c,(x,1,1),(x,4,1),STONE)
    save(label+'_road',[9,7,L],c,e);pool(label+'_roads',[(label+'_road',1)],'terrain_matching')
    name='journey_'+label
    write(D/'worldgen/structure'/f'{name}.json',{'type':'minecraft:jigsaw','biomes':['logos_dimension:cloudsea_'+b for b in ['sunlit_glade','starflower_glade','silk_folds','rose_dunes']],'step':'surface_structures','spawn_overrides':{'creature':{'bounding_box':'full','spawns':[]}},'terrain_adaptation':'none','start_pool':'logos_dimension:settlement/'+label+'_start','size':4,'start_height':{'absolute':-g},'project_start_to_heightmap':'WORLD_SURFACE_WG','max_distance_from_center':112,'use_expansion_hack':False})
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
write(D/'settlement_manifest.json',{'sources':len(manifest),'native_templates':templates,'adaptation_counts':dict(stats),'planned_houses':{'hamlet':16,'town':24},'crop':'minecraft:wheat','planting':'native feature on existing soil and empty air only','terrain_adaptation':'none','entities':False,'foundation':'no copied terrain or extra earth slab','interior':'explicit air; exterior structure void ignored by native processor','paving':'non-falling stone bricks','note':'No standalone fields, fill layers, terrain flattening, villagers or animals. Raised architectural planters stay. Legacy rice registration is retained only for existing saves.'})
print('Native blueprint settlements:',len(templates),'templates; 16/24 lots, terrain-following vanilla wheat. Sanitization:',dict(stats))
