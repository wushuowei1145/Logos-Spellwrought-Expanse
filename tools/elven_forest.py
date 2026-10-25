"""将已确认的精灵树形制作成原版群系、结构模板、静态模型与贴图。"""
from pathlib import Path
from collections import deque, Counter
import copy, json, math, sys
import numpy as np
from PIL import Image, ImageDraw

ROOT=Path(__file__).resolve().parent.parent
sys.path.insert(0,str(ROOT/'tools'))
from blueprint_nbt import write_structure
from elven_tree_study import world_tree, broadleaf, fir
from elven_habitation import tree_house, tree_lights, world_floors, assets as habitation_assets
R=ROOT/'src/main/resources';D=R/'data/logos_dimension';A=R/'assets/logos_dimension'
NS='logos_dimension:'


def write(p,value):
    p.parent.mkdir(parents=True,exist_ok=True)
    p.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')


def state(name,**props):
    return {'Name':name if ':' in name else NS+name,**({'Properties':{k:str(v).lower() for k,v in props.items()}} if props else {})}


def tex(name):return NS+'block/'+name


def box(lo,hi,texture='wood',lit=False):
    result={'from':list(lo),'to':list(hi),'faces':{side:{'texture':'#'+texture,'uv':[0,0,16,16]} for side in ['north','south','east','west','up','down']}}
    if lit:result.update(shade=False,light_emission=15)
    return result


def texture(name,color,grain=False):
    rng=np.random.default_rng(sum(ord(c)*(i+1) for i,c in enumerate(name)))
    yy,xx=np.mgrid[:64,:64]
    noise=rng.normal(0,2.8,(64,64))
    if grain:noise+=np.sin(xx*.75+np.sin(yy*.12)*.8)*5+np.sin(xx*2.1)*2
    rgb=np.clip(np.array(color)[None,None,:]+noise[:,:,None],0,255).astype(np.uint8)
    im=Image.fromarray(rgb).convert('RGBA');im.save(A/'textures/block'/f'{name}.png')


def models():
    (A/'textures/block').mkdir(parents=True,exist_ok=True)
    palette={'wood':(208,194,151),'dark':(139,110,75),'green':(111,143,113),'gold':(177,150,83),'pearl':(255,240,190),'book_blue':(93,128,140),'book_red':(135,89,84),'book_green':(85,113,78),'paper':(224,215,180),'earth':(86,77,56)}
    for name,color in palette.items():texture('elven_'+name,color,name in ['wood','dark'])
    im=Image.open(A/'textures/block/elven_wood.png');q=ImageDraw.Draw(im)
    for angle in [0,math.pi/2,math.pi,math.pi*1.5]:
        points=[]
        for a,b in [(0,0),(6,9),(0,24),(-6,9)]:
            points.append((32+a*math.cos(angle)-b*math.sin(angle),32+a*math.sin(angle)+b*math.cos(angle)))
        q.polygon(points,fill=(255,239,183,255))
    q.ellipse((29,29,35,35),fill=(255,248,207,255));im.save(A/'textures/block/elven_inlay.png')
    texture('elven_heartwood',(113,101,80),True)
    im=Image.open(A/'textures/block/elven_heartwood.png');q=ImageDraw.Draw(im)
    for x in range(2,64,9):q.line([(x,0),(x+2,18),(x,37),(x-1,63)],fill=(78,76,62,255),width=1)
    im.save(A/'textures/block/elven_heartwood.png')
    im=Image.new('RGBA',(64,64),(147,129,93,255));q=ImageDraw.Draw(im)
    for r in range(4,48,5):q.ellipse((32-r,31-r,32+r,31+r),outline=(115+r//5,100+r//5,76,255),width=1)
    im.save(A/'textures/block/elven_heartwood_end.png')
    write(A/'models/block/elven_heartwood.json',{'parent':'minecraft:block/cube_column','textures':{'side':tex('elven_heartwood'),'end':tex('elven_heartwood_end')}})
    write(A/'blockstates/elven_heartwood.json',{'variants':{'axis=y':{'model':tex('elven_heartwood')},'axis=x':{'model':tex('elven_heartwood'),'x':90,'y':90},'axis=z':{'model':tex('elven_heartwood'),'x':90}}})
    for season,color in enumerate([(108,143,109),(87,124,93),(152,141,83),(145,161,146)]):
        rng=np.random.default_rng(143+season);im=Image.new('RGBA',(64,64));q=ImageDraw.Draw(im)
        for _ in range(370):
            x,y=rng.integers(-4,64,2);r=int(rng.integers(2,5));n=int(rng.integers(-17,18))
            c=tuple(int(np.clip(v+n,0,255)) for v in color)+(255,)
            q.ellipse((int(x-r),int(y-r//2),int(x+r),int(y+r//2+1)),fill=c)
            q.line((int(x-r+1),int(y),int(x+r-1),int(y)),fill=tuple(min(255,v+9) for v in c[:3])+(255,))
        name='elven_canopy_'+str(season);im.save(A/'textures/block'/f'{name}.png')
        m=json.loads((A/'models/block'/f'cloudsea_ancient_beech_leaf_{season}.json').read_text(encoding='utf-8'))
        m['textures']={k:tex(name) for k in m['textures']}
        # 巨树中的叶心与边缘面也随相邻叶块剔除，避免重复绘制树冠内部。
        for element in m['elements']:
            for side,face in element['faces'].items():face['cullface']=side
        write(A/'models/block'/f'{name}.json',m)
    write(A/'blockstates/elven_canopy.json',{'variants':{'season='+str(i):{'model':tex('elven_canopy_'+str(i))} for i in range(4)}})
    textures={k:tex('elven_'+k) for k in palette};textures['particle']=textures['wood']
    def model(name,elements):write(A/'models/block'/f'{name}.json',{'parent':'minecraft:block/block','ambientocclusion':True,'render_type':'neoforge:cutout','textures':textures,'elements':elements})
    def directional(name):write(A/'blockstates'/f'{name}.json',{'variants':{'facing='+f:{'model':tex(name),'y':a} for f,a in [('north',0),('east',90),('south',180),('west',270)]}})
    def leaf(x,y,z,width,height,texture='wood'):
        return [box((x-width*math.sin(t*math.pi)/2,y+t*height,z),(x+width*math.sin(t*math.pi)/2,y+(t+.10)*height,z+.8),texture) for t in np.linspace(.02,.88,7)]
    bench=[box((x,0,z),(x+1.7,10,z+1.7),'dark') for x in [1,13.3] for z in [2,12.3]]
    bench += [box((.4,10,1),(15.6,12,15),'wood'),box((1.2,12,2),(14.8,13.8,12),'green'),box((.4,12,13.5),(15.6,14,15.4),'gold')]
    for x in [3,8,13]:bench+=leaf(x,14,14,4.6,11)
    model('elven_leaf_seat',bench);directional('elven_leaf_seat')
    desk=[box((x,0,z),(x+1.5,13.4,z+1.5),'dark') for x in [1,13.5] for z in [1,13.5]]
    desk +=[box((0,13.4,0),(16,15.4,16)),box((4,15.4,4),(12,16.8,10),'book_green'),box((4.5,16.8,4.5),(11.5,17.1,9.5),'paper')]
    model('elven_reading_table',desk);directional('elven_reading_table')
    planter=[box((2,0,2),(14,6,14)),box((3,6,3),(13,6.8,13),'earth')]
    for x,z in [(5,5),(10,8),(6,11)]:
        planter +=[box((x,6,z),(x+.8,17,z+.8),'green')]+leaf(x+1,8,z,5,9,'green')
        planter +=[box((x-1.5,16,z-1),(x+2.1,17.7,z+1.6),'paper'),box((x-.3,17.2,z-.3),(x+.9,18.3,z+.9),'gold')]
    model('elven_flower_planter',planter);directional('elven_flower_planter')
    lamp=[]
    for t in np.linspace(.05,.90,8):
        w=5.8*math.sin(t*math.pi)
        lamp +=[box((8-w,t*14,7),(8+w,(t+.09)*14,8),'wood'),box((7,t*14,8-w),(8,(t+.09)*14,8+w),'wood')]
    lamp +=[box((6.7,4,6.7),(9.3,11.7,9.3),'pearl',True),box((7.5,0,7.5),(8.5,1.4,8.5),'gold')]
    model('elven_leaf_lamp',lamp);directional('elven_leaf_lamp')
    model('elven_lamp_vine',[box((7.3,0,7.3),(8.7,16,8.7),'dark')]+leaf(9,4,8,3,7,'green'));directional('elven_lamp_vine')
    for half in ['lower','upper']:
        shelves=[box((0,0,10),(1.2,16,16),'dark'),box((14.8,0,10),(16,16,16),'dark'),box((0,0,15),(16,16,16),'dark')]
        for y in [0,7.2,14.4]:shelves.append(box((0,y,5),(16,y+1.2,16)))
        for row in range(2):
            for i in range(7):
                x=1.4+i*1.85;y=1.2+row*7.2;h=4.3+(i*3%3)*.3
                shelves +=[box((x,y,6),(x+1.4,y+h,13.8),['book_blue','book_red','book_green','paper'][i%4]),box((x+.15,y+.6,5.94),(x+1.2,y+.8,6.02),'gold')]
        model('elven_bookshelf_'+half,shelves)
    write(A/'blockstates/elven_bookshelf.json',{'variants':{f'facing={f},half={h}':{'model':tex('elven_bookshelf_'+h),'y':a} for h in ['lower','upper'] for f,a in [('north',0),('east',90),('south',180),('west',270)]}})
    for name,texture_name,light in [('elven_pale_planks','elven_wood',False),('elven_luminous_inlay','elven_inlay',True)]:
        e=box((0,0,0),(16,16,16),'wood',light)
        for face in e['faces'].values():face['cullface']=next(k for k,v in e['faces'].items() if v is face)
        write(A/'models/block'/f'{name}.json',{'textures':{'wood':tex(texture_name),'particle':tex(texture_name)},'elements':[e]})
        write(A/'blockstates'/f'{name}.json',{'variants':{'':{'model':tex(name)}}})
    names={'elven_heartwood':('精灵古心木','Elven Heartwood'),'elven_canopy':('精灵林冠叶','Elven Canopy'),'elven_pale_planks':('浅色精灵木板','Pale Elven Planks'),'elven_luminous_inlay':('叶辉嵌片','Leaflight Inlay'),'elven_leaf_seat':('精灵叶背椅','Elven Leaf Seat'),'elven_reading_table':('精灵阅览桌','Elven Reading Table'),'elven_flower_planter':('精灵白花花架','Elven Flower Planter'),'elven_bookshelf':('精灵藏书架','Elven Bookshelf'),'elven_leaf_lamp':('暖白叶灯','Warm Leaflight'),'elven_lamp_vine':('吊灯细藤','Lantern Vine')}
    for n in names:
        target='elven_canopy_0' if n=='elven_canopy' else 'elven_bookshelf_lower' if n=='elven_bookshelf' else n
        write(A/'items'/f'{n}.json',{'model':{'type':'minecraft:model','model':tex(target)}})
        conditions=[{'condition':'minecraft:survives_explosion'}]
        if n=='elven_bookshelf':conditions.append({'condition':'minecraft:block_state_property','block':NS+n,'properties':{'half':'lower'}})
        write(D/'loot_table/blocks'/f'{n}.json',{'type':'minecraft:block','pools':[{'rolls':1,'conditions':conditions,'entries':[{'type':'minecraft:item','name':NS+n}]}]})
    for lang,index in [('zh_cn',0),('en_us',1)]:
        p=A/'lang'/f'{lang}.json';data=json.loads(p.read_text(encoding='utf-8'))
        for n,title in names.items():data['block.logos_dimension.'+n]=title[index]
        data['biome.logos_dimension.elven_forest']=['精灵森林','Elven Forest'][index]
        write(p,data)


def trimmed(v):
    data=v.data.copy();ys=np.arange(data.shape[1])+v.origin[1]
    data[(data==5)|(data==6)]=0
    data[:,ys<=1,:]=np.where(data[:,ys<=1,:]==3,0,data[:,ys<=1,:])
    # 树冠保留两格厚外壳，枝干不变，避免把巨大叶团内部全部填实。
    leaf=(data==2)|(data==4)
    core=leaf.copy()
    for _ in range(2):
        shrunk=core.copy()
        for axis in range(3):shrunk &= np.roll(core,1,axis)&np.roll(core,-1,axis)
        core=shrunk
    data[core]=0
    return data


def base_state(material,y):
    if material in (1,25):return state('elven_heartwood' if material==1 else 'cloudsea_fir_heart_log',axis='y')
    if material==3 and y<35:return state('minecraft:moss_block')
    if material in (2,3,4):return state('cloudsea_fir_heart_leaf' if material==4 else 'elven_canopy',distance=1,persistent=True,waterlogged=False,season=0)
    return state('elven_pale_planks')


def connector(cells,tags,pos,front,name,target,pool):
    old=cells.get(pos);final='minecraft:structure_void' if not old else old['Name']+('['+','.join(k+'='+v for k,v in old['Properties'].items())+']' if old.get('Properties') else '')
    cells[pos]=state('minecraft:jigsaw',orientation=front+('_north' if front in ['up','down'] else '_up'))
    tags[pos]={'id':'minecraft:jigsaw','name':NS+name,'target':NS+target if ':' not in target else target,'pool':NS+'elven/'+pool if pool!='empty' else 'minecraft:empty','joint':'rollable' if pool=='roots' else 'aligned','final_state':final}


def pool(name,templates):
    write(D/'worldgen/template_pool/elven'/f'{name}.json',{'name':NS+'elven/'+name,'fallback':'minecraft:empty','elements':[{'weight':1,'element':{'element_type':'minecraft:single_pool_element','location':NS+'elven/'+n,'processors':NS+'elven_preserve','override_liquid_settings':'ignore_waterlogging','projection':'rigid'}} for n in templates]})


def footing(cells,tags):
    low={}
    for (x,y,z),s in cells.items():
        if s['Name'] not in [NS+'elven_heartwood',NS+'cloudsea_fir_heart_log']:continue
        if (x,z) not in low or y<low[x,z]:low[x,z]=y
    count=0
    for (x,z),y in low.items():
        if y>7 or not ((x%3==0 and z%3==0) or any((x+dx,z+dz) not in low for dx,dz in [(1,0),(-1,0),(0,1),(0,-1)])):continue
        if (x,y,z) in tags:continue
        connector(cells,tags,(x,y,z),'up','root','minecraft:bottom','roots');count+=1
    return count


def setup_footings():
    soft=lambda:{'type':'minecraft:any_of','predicates':[{'type':'minecraft:not','predicate':{'type':'minecraft:solid','offset':[0,1,0]}},{'type':'minecraft:matching_block_tag','tag':'minecraft:leaves','offset':[0,1,0]}]}
    write(D/'worldgen/configured_feature/elven_roots.json',{'type':'minecraft:block_column','config':{'direction':'down','prioritize_tip':False,'allowed_placement':{'type':'minecraft:all_of','predicates':[{'type':'minecraft:inside_world_bounds','offset':[0,1,0]},soft()]},'layers':[{'height':512,'provider':{'type':'minecraft:simple_state_provider','state':state('elven_heartwood',axis='y')}}]}})
    write(D/'worldgen/placed_feature/elven_roots.json',{'feature':NS+'elven_roots','placement':[{'type':'minecraft:random_offset','xz_spread':0,'y_spread':-2}]})
    write(D/'worldgen/template_pool/elven/roots.json',{'name':NS+'elven/roots','fallback':'minecraft:empty','elements':[{'weight':1,'element':{'element_type':'minecraft:feature_pool_element','feature':NS+'elven_roots','projection':'rigid'}}]})
    write(D/'worldgen/processor_list/elven_preserve.json',{'processors':[{'processor_type':'minecraft:block_ignore','blocks':[{'Name':'minecraft:structure_void'}]}]})


def structure(name,start,spacing,depth,biomes,large=False):
    spec={'type':'minecraft:jigsaw','biomes':[NS+b for b in biomes],'step':'surface_structures','terrain_adaptation':'none','spawn_overrides':{kind:{'bounding_box':'full','spawns':[]} for kind in ['monster','creature']},'start_pool':NS+'elven/'+start,'size':depth,'start_height':{'absolute':-3},'project_start_to_heightmap':'WORLD_SURFACE_WG','max_distance_from_center':{'horizontal':128,'vertical':256},'use_expansion_hack':False}
    if large:spec['start_jigsaw_name']=NS+'tree_anchor'
    write(D/'worldgen/structure'/f'{name}.json',spec)
    placement={'type':'minecraft:random_spread','spacing':spacing,'separation':spacing-1 if not large else spacing-8,'salt':976125 if large else 976126}
    if not large:placement['exclusion_zone']={'other_set':NS+'elven_world_trees','chunk_count':8}
    write(D/'worldgen/structure_set'/('elven_world_trees.json' if large else 'elven_giant_trees.json'),{'structures':[{'structure':NS+name,'weight':1}],'placement':placement})


def furniture(cells):
    def put(x,y,z,name,**p):cells[x,y,z]=state(name,**p)
    for x in range(-12,13):
        for z in range(-12,13):
            if x*x+z*z<169:put(x,1,z,'elven_pale_planks')
    for x,z in [(0,0),(4,0),(-4,0),(0,4),(0,-4)]:put(x,1,z,'elven_luminous_inlay')
    for x,facing in [(-9,'east'),(9,'west')]:
        for z in [2,3,4]:put(x,2,z,'elven_leaf_seat',facing=facing)
    for x in [-7,-6,-5]:
        put(x,2,9,'elven_bookshelf',facing='north',half='lower');put(x,3,9,'elven_bookshelf',facing='north',half='upper')
    for x in [5,6]:put(x,2,8,'elven_reading_table',facing='north')
    put(5,2,6,'elven_leaf_seat',facing='south')
    for x in [-10,10]:put(x,2,0,'elven_flower_planter',facing='north')
    for a in np.linspace(0,math.tau,8,endpoint=False)+.16:
        x,z=round(math.cos(a)*11),round(math.sin(a)*11)
        put(x,5,z,'elven_leaf_lamp',facing='north')
        for y in range(6,9):put(x,y,z,'elven_lamp_vine',facing='north')
        # 木枝向外接入树壳，叶灯的吊点不能凭空悬浮。
        for radius in range(11,17):
            bx,bz=round(math.cos(a)*radius),round(math.sin(a)*radius)
            put(bx,9,bz,'elven_heartwood',axis='x' if abs(math.cos(a))>abs(math.sin(a)) else 'z')
    for a in [.18*math.pi,.82*math.pi,1.5*math.pi]:
        x,z=round(math.cos(a)*9.5),round(math.sin(a)*9.5)
        for y in range(15,97):put(x,y,z,'elven_lamp_vine',facing='north')
        for y in [22,39,56,73,88]:put(x,y,z,'elven_leaf_lamp',facing='north')
    for y in range(12,23):put(0,y,2,'elven_lamp_vine',facing='north')
    for z in range(2,14):put(0,23,z,'elven_heartwood',axis='z')
    for x,z in [(0,2),(-2,2),(2,2),(0,0),(0,4)]:put(x,11,z,'elven_leaf_lamp',facing='north')
    for x in range(-2,3):put(x,12,2,'elven_heartwood',axis='x')
    for z in range(0,5):put(0,12,z,'elven_heartwood',axis='z')


def world_assets():
    v=world_tree();data=trimmed(v)
    homes=[]
    # 周边八棵巨木属于同一地标结构，世界树因此处在这片林冠的中央。
    for i,a in enumerate(np.linspace(0,math.tau,8,endpoint=False)):
        t=fir(80+i%2*6) if i%3==0 else broadleaf(66+i%3*4,4.5,11+i)
        td=trimmed(t);idx=np.argwhere(td);xyz=idx+t.origin+np.array([round(math.cos(a)*74),0,round(math.sin(a)*74)])
        values=td[tuple(idx.T)].copy()
        if i%3==0:values[values==1]=25
        dst=xyz-v.origin;inside=np.all((dst>=0)&(dst<np.array(data.shape)),axis=1)
        dst=dst[inside];values=values[inside];empty=data[tuple(dst.T)]==0;data[tuple(dst[empty].T)]=values[empty]
        if i in [1,6]:homes.append((i,round(math.cos(a)*74),round(math.sin(a)*74)))
    cells={}
    for p in np.argwhere(data):
        x,y,z=(p+v.origin).tolist();cells[x,y,z]=base_state(int(data[tuple(p)]),y)
    for y in range(2,96):
        radius=13 if y<82 else 13-(y-82)*.22
        for x in range(-13,14):
            for z in range(-27,14):
                if x*x+z*z<radius*radius or (y<35 and z<0 and (x/9)**2+(max(0,y-19)/16)**2<1):cells[x,y,z]=state('minecraft:air')
    furniture(cells)
    levels=world_floors(cells)
    house_specs=[tree_house(cells,i%3==0,i,(x,0,z)) for i,x,z in homes]
    lights=tree_lights(cells,seed=37)
    # 只在根拱入口设置短木阶，不生成矩形土台。
    for z,y in [(-29,0),(-28,1)]:
        for x in range(-4,5):cells[x,y,z]=state('minecraft:oak_stairs',facing='south',half='bottom',shape='straight',waterlogged=False)
    root=(3,0,3);parent={root:None};queue=deque([root]);order=[]
    directions=[((1,0,0),'east'),((-1,0,0),'west'),((0,1,0),'up'),((0,-1,0),'down'),((0,0,1),'south'),((0,0,-1),'north')]
    while queue:
        p=queue.popleft();order.append(p)
        for delta,face in directions:
            n=tuple(p[i]+delta[i] for i in range(3))
            if all(0<=c<7 for c in n) and n not in parent:parent[n]=(p,face);queue.append(n)
    tiles={p:({}, {}) for p in order}
    for (x,y,z),s in cells.items():
        p=((x+112)//32,(y+4)//32,(z+112)//32)
        assert p in tiles,(x,y,z)
        tiles[p][0][((x+112)%32,(y+4)%32,(z+112)%32)]=s
    opposite={'east':'west','west':'east','up':'down','down':'up','north':'south','south':'north'}
    ports={'east':(31,1,1),'west':(0,1,1),'up':(1,31,1),'down':(1,0,1),'south':(1,1,31),'north':(1,1,0)}
    for child,relation in parent.items():
        if relation is None:continue
        p,face=relation;name='tile_'+'_'.join(map(str,child));pc,pe=tiles[p];cc,ce=tiles[child]
        connector(pc,pe,ports[face],face,'out_'+name,'in_'+name,name)
        connector(cc,ce,ports[opposite[face]],opposite[face],'in_'+name,'none','empty')
    c,e=tiles[root];connector(c,e,(16,4,16),'up','tree_anchor','none','empty')
    footings=0
    for p,(c,e) in tiles.items():
        if p[1]==0:footings+=footing(c,e)
        name='tile_'+'_'.join(map(str,p));write_structure(D/'structure/elven'/f'{name}.nbt',[32,32,32],c,e,data_version=4790,entities=[]);pool(name,[name])
    pool('world_tree',['tile_3_0_3']);structure('elven_world_tree','world_tree',32,14,['elven_forest'],True)
    return {'tiles':len(tiles),'root_footings':footings,'material_cells':len(cells),'root_tile':'tile_3_0_3','anchor':[16,4,16],'height_above_ground':216,'surrounding_giants':8,'tree_houses':house_specs,'interior':levels,'bough_lights':lights}


def ordinary_assets():
    names=[];counts=[]
    for i in range(5):
        t=broadleaf(68+i*4,4.7+i*.25,51+i) if i<3 else fir(82+(i-3)*6)
        data=trimmed(t);coords=np.argwhere(data);lo=coords.min(axis=0);hi=coords.max(axis=0);cells={}
        # 模板底部固定为原预览地表以下四格，原版高度投影保持根颈与地表一致。
        lo[1]=0
        for p in coords:
            g=p+t.origin;material=int(data[tuple(p)])
            if i>=3 and material==1:material=25
            cells[tuple((p-lo).tolist())]=base_state(material,int(g[1]))
        offset=tuple((-t.origin-lo).tolist());lights=tree_lights(cells,seed=i)
        for furnished in [False,True]:
            variant=copy.deepcopy(cells)
            home=tree_house(variant,i>=3,i,offset) if furnished else None
            tags={};count=footing(variant,tags);name='giant_'+str(i)+('_home' if furnished else '')
            size=[max(p[axis] for p in variant)+1 for axis in range(3)]
            assert all(min(p[axis] for p in variant)>=0 for axis in range(3))
            write_structure(D/'structure/elven'/f'{name}.nbt',size,variant,tags,data_version=4790,entities=[])
            names.append(name);counts.append({'template':name,'blocks':len(variant),'footings':count,'height':int(hi[1]+t.origin[1]),'house':home,'bough_lights':lights})
    pool('giants',names)
    p=D/'worldgen/template_pool/elven/giants.json';obj=json.loads(p.read_text())
    # 2/(5+2)=28.6%，平均每三至四棵巨木一间屋，不把每棵树变成聚落。
    for row in obj['elements']:row['weight']=2 if row['element']['location'].endswith('_home') else 5
    write(p,obj);structure('elven_giant_tree','giants',3,1,['elven_forest'])
    return counts


def biome():
    b=json.loads((D/'worldgen/biome/cloudsea_ancient_beech.json').read_text(encoding='utf-8'))
    b['spawners']={};b['features'][9]=[NS+'trees_cloudsea_ancient_beech',NS+'elven_forest_floor',NS+'fallen_tree_cloudsea_ancient_beech',NS+'elven_glow_grove','minecraft:patch_sugar_cane','minecraft:patch_waterlily',NS+'forest_rock_outcrop',NS+'journey_field_mushroom'];b['temperature']=.6;b['downfall']=.8
    # 精灵森林只改雾色，不动天空；其余群系不跟随，避免整个大陆到处变色。
    b['effects'].update({'fog_color':'#a9cdb4','water_fog_color':'#a2c4bb'})
    write(D/'worldgen/biome/elven_forest.json',b)
    write(D/'worldgen/configured_feature/elven_glow_grove.json',{'type':'minecraft:simple_block','config':{'to_place':{'type':'minecraft:simple_state_provider','state':state('elven_bough_light')}}})
    # 原版高度投影与土壤过滤，只在精灵森林地面空位添少量固定光源。
    write(D/'worldgen/placed_feature/elven_glow_grove.json',{'feature':NS+'elven_glow_grove','placement':[{'type':'minecraft:count','count':8},{'type':'minecraft:in_square'},{'type':'minecraft:heightmap','heightmap':'MOTION_BLOCKING_NO_LEAVES'},{'type':'minecraft:environment_scan','direction_of_search':'down','max_steps':32,'target_condition':{'type':'minecraft:matching_block_tag','tag':'minecraft:dirt'}},{'type':'minecraft:random_offset','xz_spread':0,'y_spread':1},{'type':'minecraft:block_predicate_filter','predicate':{'type':'minecraft:all_of','predicates':[{'type':'minecraft:replaceable'},{'type':'minecraft:would_survive','state':state('elven_bough_light')}]}},{'type':'minecraft:biome'}]})
    for path in [D/'dimension/cloudsea.json',ROOT/'gametest-packs/crucible/data/minecraft/worldgen/world_preset/flat.json']:
        obj=json.loads(path.read_text(encoding='utf-8'))
        generator=obj['generator'] if 'generator' in obj else obj['dimensions'][NS+'cloudsea']['generator']
        entries=generator['biome_source']['biomes'];selected=0
        for entry in entries:
            p=entry['parameters']
            if all(isinstance(p[k],(int,float)) for k in ['temperature','humidity','continentalness','erosion','depth']) and p['depth']==0 and p['temperature'] in [0,.4] and p['humidity']>=.4 and p['continentalness']>=.3 and p['erosion']>=.15:
                entry['biome']=NS+'elven_forest';selected+=1
        write(path,obj)
    p=D/'worldgen/noise_settings/journey.json';obj=json.loads(p.read_text());rules=obj['surface_rule']['sequence']
    if not any(r.get('if_true',{}).get('biome_is')==[NS+'elven_forest'] for r in rules):
        rule=copy.deepcopy(next(r for r in rules if r.get('if_true',{}).get('biome_is')==[NS+'cloudsea_moss_vale']))
        rule['if_true']['biome_is']=[NS+'elven_forest'];rules.insert(1,rule)
    write(p,obj)
    # 原地形噪声保持 384 格，仅扩展顶部空白空间以容纳世界树树冠。
    p=D/'dimension_type/cloudsea.json';obj=json.loads(p.read_text());obj.update(height=512,logical_height=512);write(p,obj)
    for tag in ['leaves','logs','logs_that_burn','mineable/axe']:
        p=R/'data/minecraft/tags/block'/f'{tag}.json';obj=json.loads(p.read_text())
        add=[NS+'elven_canopy'] if tag=='leaves' else [NS+'elven_heartwood'] if tag in ['logs','logs_that_burn'] else [NS+n for n in ['elven_heartwood','elven_pale_planks','elven_leaf_seat','elven_reading_table','elven_bookshelf','elven_flower_planter','elven_leaf_lamp','elven_lamp_vine']]
        obj['values']=list(dict.fromkeys(obj['values']+add));write(p,obj)
    for tag in ['has_structure/mineshaft','is_overworld']:
        p=R/'data/minecraft/tags/worldgen/biome'/f'{tag}.json'
        if p.exists():
            obj=json.loads(p.read_text());obj['values']=list(dict.fromkeys(obj['values']+[NS+'elven_forest']));write(p,obj)
    return selected


def main():
    models();habitation_assets(R);setup_footings();summary={'biome':NS+'elven_forest','climate_entries':biome(),'world_tree':world_assets(),'giants':ordinary_assets(),'generation':'minecraft:jigsaw / minecraft:noise / minecraft:multi_noise','entities':False,'central_clear_diameter':10,'block_light_sources':True,'tree_house_probability':2/7,'breath_cycle_ticks':120}
    write(D/'elven_forest_manifest.json',summary);print(json.dumps(summary,ensure_ascii=False),flush=True)


if __name__=='__main__':main()
