"""代码构筑九类小遗迹、两套残损布局；正式生成只使用原版 NBT、拼接和方块柱特征。"""
from pathlib import Path
from collections import Counter
import json
import random
import math
from PIL import Image, ImageDraw
from blueprint_nbt import write_structure

ROOT=Path(__file__).resolve().parent.parent
R=ROOT/'src/main/resources';D=R/'data/logos_dimension';A=R/'assets/logos_dimension'
PREFIX='logos_dimension:'
CATALOG=[
 ('bloom_waystone','花缠路碑',['silk_folds','rose_dunes','sunlit_glade','starflower_glade'],'minecraft:book'),
 ('fern_scriptorium','蕨影书龛',['fernwood','moss_vale','lantern_shelves'],'minecraft:paper'),
 ('root_cradle','古根摇篮',['ancient_beech','jade_whirls','verdant_whirls','moon_birch','copper_copse'],'minecraft:glow_berries'),
 ('frost_resonator','霜脊谐铃台',['frost_spires','pale_spires','aurora_dunes','fir_heart','lilac_steps'],'minecraft:amethyst_shard'),
 ('wind_compass','长风残盘',['silk_folds','dawn_ribbons','amber_whirls','blue_bell_steps'],'minecraft:compass'),
 ('tide_lens','望潮透镜座',['opal_dunes','tide_ribbons','pearl_basins'],'minecraft:prismarine_shard'),
 ('reed_siphon','芦汀汲月台',['dream_basins','star_ribbons'],'minecraft:glass_bottle'),
 ('ravine_thread','断崖星纱门',['halo_spires','echo_folds','moon_folds','cloudroot_shelves'],'minecraft:feather'),
 ('rain_memory','沉月镜庭',['glass_lagoons'],'minecraft:nautilus_shell'),
]
# 较大的遗迹放第二只独立箱子，彼此不相邻，避免意外合并成大箱。
SECOND_CACHES={'fern_scriptorium':(9,5,6),'root_cradle':(4,5,7),'wind_compass':(10,5,7),
               'ravine_thread':(7,5,6),'rain_memory':(4,5,6)}

def write(path,obj):
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps(obj,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')

def st(name,**props):
    return {'Name':name if ':' in name else 'minecraft:'+name,**({'Properties':{k:str(v).lower() for k,v in props.items()}} if props else {})}

STONE=st(PREFIX+'wayside_carved_stone');MOSS=st(PREFIX+'wayside_moss_stone');RUNE=st(PREFIX+'wayside_rune_tile')
AIR=st('air');VOID=st('structure_void')

def tagged(namespace,kind,name,values):
    path=R/f'data/{namespace}/tags/{kind}/{name}.json'
    obj=json.loads(path.read_text(encoding='utf-8')) if path.exists() else {'replace':False,'values':[]}
    obj['values']=list(dict.fromkeys(obj['values']+values));write(path,obj)

def art():
    textures=A/'textures/block/wayside';textures.mkdir(parents=True,exist_ok=True)
    for name in ['carved_stone','moss_stone','rune_tile']:
        im=Image.new('RGBA',(16,16));q=ImageDraw.Draw(im);rng=random.Random(name)
        for y in range(16):
            for x in range(16):
                n=rng.randrange(-7,8);base=(91,101,110)
                q.point((x,y),fill=tuple(max(0,min(255,c+n)) for c in base)+(255,))
        for y in [0,7,15]:q.line((0,y,15,y),fill='#3d4750');q.line((0,min(15,y+1),15,min(15,y+1)),fill='#87939a')
        q.line((7,1,7,6),fill='#465059');q.line((2,9,2,14),fill='#465059');q.line((12,9,12,14),fill='#465059')
        if name=='moss_stone':
            for _ in range(25):
                x=rng.randrange(16);y=rng.randrange(16);q.rectangle((x,y,min(15,x+rng.randrange(1,4)),min(15,y+1)),fill=rng.choice(['#54664d','#687756','#82916b']))
        elif name=='rune_tile':
            q.polygon([(8,2),(13,8),(8,13),(2,8)],outline='#a2b5ac');q.line((8,3,8,12),fill='#ccd7ba');q.line((4,8,11,8),fill='#ccd7ba');q.rectangle((7,6,9,9),fill='#d4ddb9')
        else:
            q.line((3,3,4,3,4,5),fill='#bec1ac');q.line((11,11,11,13,13,13),fill='#bec1ac')
        im.save(textures/f'{name}.png')
        model={'parent':'minecraft:block/cube_all','textures':{'all':f'logos_dimension:block/wayside/{name}'}}
        write(A/f'models/block/wayside/{name}.json',model)
    im=Image.new('RGBA',(16,16));q=ImageDraw.Draw(im)
    q.line([(5,15),(6,11),(9,8),(8,3),(11,0)],fill='#456647',width=2)
    for x,y in [(4,13),(10,10),(5,6),(11,3)]:
        q.polygon([(x-3,y),(x,y-2),(x+2,y),(x,y+2)],fill='#7d9a69');q.point((x,y),fill='#b2c49a')
    for x,y in [(5,9),(11,4)]:
        for dx,dy in [(-2,0),(2,0),(0,-2),(0,2)]:q.rectangle((x+dx-1,y+dy-1,x+dx+1,y+dy+1),fill='#e5eadc')
        q.point((x,y),fill='#c9ad69')
    im.save(textures/'bloom_vine.png')
    flower=Image.new('RGBA',(16,16));q=ImageDraw.Draw(flower)
    for box in [(5,0,10,7),(8,4,15,10),(5,8,10,15),(0,5,7,10)]:q.ellipse(box,fill='#e5eadb')
    q.rectangle((6,6,9,9),fill='#bdbb80');q.point((7,6),fill='#ede1a8');flower.save(textures/'wall_flower.png')
    faces=lambda tex:{f:{'texture':'#'+tex,'uv':[0,0,16,16]} for f in ['north','south']}
    vine={'parent':'minecraft:block/block','ambientocclusion':False,'render_type':'minecraft:cutout',
          'textures':{'vine':'logos_dimension:block/wayside/bloom_vine','flower':'logos_dimension:block/wayside/wall_flower','particle':'logos_dimension:block/wayside/bloom_vine'},
          'elements':[{'from':[0,0,.12],'to':[16,16,.12],'shade':False,'faces':faces('vine')}]+
          [{'from':[x-4,y-4,1.1],'to':[x+4,y+4,1.1],'shade':False,'faces':faces('flower')} for x,y in [(4,6),(12,12)]]}
    write(A/'models/block/wayside/bloom_vine_side.json',vine)
    # 上附着沿用侧面模型旋转，四面仍由原版藤蔓属性决定，失去墙体会脱落。
    parts=[{'when':{face:'true'},'apply':{'model':'logos_dimension:block/wayside/bloom_vine_side',**({'x':270} if face=='up' else {'y':angle})}} for face,angle in [('north',0),('east',90),('south',180),('west',270),('up',0)]]
    write(A/'blockstates/wayside_bloom_vine.json',{'multipart':parts})
    lamp=Image.new('RGBA',(16,16),'#465653');q=ImageDraw.Draw(lamp)
    q.rectangle((3,2,12,13),fill='#b4d7c4');q.rectangle((5,4,10,11),fill='#d3e5c9');q.line((2,0,2,15),fill='#788e80');q.line((13,0,13,15),fill='#788e80')
    lamp.save(textures/'jade_lamp.png')
    elements=[]
    def box(a,b,tex):elements.append({'from':a,'to':b,'faces':{f:{'texture':tex,'uv':[0,0,16,16]} for f in ['up','down','north','south','east','west']}})
    box([4,3,4],[12,12,12],'#glass');box([3,1,3],[13,3,13],'#stone');box([3,12,3],[13,14,13],'#stone');box([7,14,7],[9,16,9],'#stone')
    for x,z in [(4,4),(11,4),(4,11),(11,11)]:box([x,3,z],[x+1,12,z+1],'#stone')
    write(A/'models/block/wayside/jade_lamp.json',{'parent':'minecraft:block/block','textures':{'glass':'logos_dimension:block/wayside/jade_lamp','stone':'logos_dimension:block/wayside/carved_stone','particle':'logos_dimension:block/wayside/jade_lamp'},'elements':elements})
    for name,model in [('carved_stone','carved_stone'),('moss_stone','moss_stone'),('rune_tile','rune_tile'),('jade_lamp','jade_lamp')]:
        write(A/f'blockstates/wayside_{name}.json',{'variants':{'':{'model':f'logos_dimension:block/wayside/{model}'}}})
    for name in ['carved_stone','moss_stone','rune_tile','jade_lamp','bloom_vine']:
        block='wayside_'+name;model='bloom_vine_side' if name=='bloom_vine' else name
        write(A/f'items/{block}.json',{'model':{'type':'minecraft:model','model':f'logos_dimension:block/wayside/{model}'}})
        write(D/f'loot_table/blocks/{block}.json',{'type':'minecraft:block','pools':[{'rolls':1,'entries':[{'type':'minecraft:item','name':PREFIX+block}],'conditions':[{'condition':'minecraft:survives_explosion'}]}]})
    tagged('minecraft','block','mineable/pickaxe',[PREFIX+'wayside_'+n for n in ['carved_stone','moss_stone','rune_tile','jade_lamp']])
    tagged('logos','block','fragile_plants',[PREFIX+'wayside_bloom_vine']);tagged('minecraft','block','climbable',[PREFIX+'wayside_bloom_vine'])
    zh=['风蚀刻纹石','苔痕旧石','月纹嵌石','青玉遗灯','缠石星花藤'];en=['Weathered Carved Stone','Mossworn Stone','Moon Inlay Stone','Jade Relic Lamp','Stoneclinging Starbloom']
    for language,labels in [('zh_cn',zh),('en_us',en)]:
        path=A/f'lang/{language}.json';obj=json.loads(path.read_text(encoding='utf-8'))
        before=dict(obj)
        for name,label in zip(['carved_stone','moss_stone','rune_tile','jade_lamp','bloom_vine'],labels):
            for prefix in ['block','item']:obj[f'{prefix}.logos_dimension.wayside_{name}']=label
        for name,label,_,_ in CATALOG:obj[f'structure.logos_dimension.wayside_{name}']=label if language=='zh_cn' else name.replace('_',' ').title()
        # 本功能的译名没有变化时保留原文件，避免重排其他功能追加的语言文本。
        if obj!=before:write(path,obj)

class Build:
    def __init__(self,kind,variant):
        self.kind=kind;self.variant=variant;self.cells={};self.entities={};self.feet=set();self.rng=random.Random(kind+str(variant))
    def put(self,x,y,z,state=STONE):self.cells[x,y,z]=state
    def box(self,a,b,state=STONE):
        for x in range(a[0],b[0]+1):
            for y in range(a[1],b[1]+1):
                for z in range(a[2],b[2]+1):self.put(x,y,z,state)
    def floor(self,x,z,state=None):
        self.put(x,4,z,state or (MOSS if self.rng.random()<.28 else STONE));self.feet.add((x,4,z))
    def pillar(self,x,z,h,width=1):
        for xx in range(x,x+width):
            for zz in range(z,z+width):
                self.floor(xx,zz)
                for y in range(5,5+h):self.put(xx,y,zz,MOSS if self.rng.random()<.25 else STONE)
    def vine(self,x,y,z,face='north'):
        if (x,y,z) in self.cells:return
        dx,dz={'north':(0,-1),'south':(0,1),'west':(-1,0),'east':(1,0)}[face]
        support=self.cells.get((x+dx,y,z+dz))
        if support is None or support['Name'] in ['minecraft:air','minecraft:structure_void','minecraft:iron_chain']:return
        self.put(x,y,z,st(PREFIX+'wayside_bloom_vine',**{d:d==face for d in ['up','north','south','east','west']}))
    def lamp(self,x,y,z,length=2):
        for dy in range(length):self.put(x,y-dy,z,st('iron_chain',axis='y',waterlogged=False))
        self.put(x,y-length,z,st(PREFIX+'wayside_jade_lamp'))
    def cache(self,x,y,z):
        if y==5 and (x,4,z) not in self.cells:self.floor(x,z)
        self.put(x,y,z,st('chest',facing='south',type='single',waterlogged=self.kind=='rain_memory'))
        self.entities[x,y,z]={'id':'minecraft:chest','LootTable':PREFIX+'chests/wayside/'+self.kind}
    def ring(self,cx,cz,r,y=4,breaks=()):
        for x in range(cx-r-1,cx+r+2):
            for z in range(cz-r-1,cz+r+2):
                d=math.hypot(x-cx,z-cz)
                if r-.75<=d<=r+.3 and not any(abs(x-bx)+abs(z-bz)<2 for bx,bz in breaks):
                    if y==4:self.floor(x,z)
                    else:self.put(x,y,z,STONE)
    def finish(self):
        if self.kind in SECOND_CACHES:self.cache(*SECOND_CACHES[self.kind])
        if self.variant:
            for _ in range(3):
                x,z=self.rng.randrange(1,11),self.rng.randrange(1,11)
                if (x,4,z) not in self.cells:
                    self.floor(x,z);self.put(x,5,z,MOSS)
        # 仅清出已有铺石上方的两格通行空间；湖底遗迹保留水，不生成空气泡。
        for x,y,z in list(self.feet):
            for dy in [1,2]:
                if (x,y+dy,z) not in self.cells:self.put(x,y+dy,z,st('water',level=0) if self.kind=='rain_memory' else AIR)
        # 只在实体石脚下接原版柱形特征，不复制土台；外部空位不写入，保留原地形。
        for x,y,z in sorted(self.feet):
            original=self.cells[x,y,z]
            self.put(x,y,z,st('jigsaw',orientation='up_north'))
            self.entities[x,y,z]={'id':'minecraft:jigsaw','name':PREFIX+'wayside_footing','target':'minecraft:bottom','pool':PREFIX+'wayside/supports','joint':'rollable','final_state':original['Name']}
        maxx=max(p[0] for p in self.cells)+1;maxy=max(p[1] for p in self.cells)+1;maxz=max(p[2] for p in self.cells)+1
        name=f'{self.kind}_{self.variant}'
        write_structure(D/f'structure/wayside/{name}.nbt',(maxx,maxy,maxz),self.cells,self.entities,data_version=4790,entities=[])
        return {'id':name,'size':[maxx,maxy,maxz],'blocks':len(self.cells),'supports':len(self.feet),'loot':sum('LootTable' in e for e in self.entities.values())}

def build(kind,variant):
    b=Build(kind,variant);v=variant
    if kind=='bloom_waystone':
        for x in range(3,7):
            for z in range(3,7):
                if (x,z) not in [(3,3),(6,6)]:b.floor(x,z)
        for x,z,h in [(4,4,6),(5,4,6),(4,5,8-v),(5,5,7+v)]:b.pillar(x,z,h)
        for y in range(6,12):
            b.vine(4,y,3,'south');b.vine(6,y,5,'west')
        b.box((4,12-v,4),(7,12-v,5),STONE);b.lamp(7,11-v,5,3)
        b.put(4,9,4,RUNE);b.cache(3,5,5)
        for x,z in [(1,4),(2,5),(7,7)]:b.floor(x,z,st('stone_brick_slab',type='bottom',waterlogged=False))
    elif kind=='fern_scriptorium':
        for x in range(2,11):
            for z in range(3,8):
                if (x+z)%5!=0:b.floor(x,z)
        for x in [2,3,8,9,10]:b.pillar(x,3,3+(x%3))
        for z in range(4,8):b.pillar(2,z,5 if z<6 else 2)
        for x in range(4,8):b.put(x,5,3,st('chiseled_stone_bricks'));b.put(x,6,3,RUNE)
        b.box((3,9,3),(8-v,9,3),STONE);b.lamp(7-v,8,3,2)
        b.cache(3,5,5);b.put(6,5,5,st('lectern',facing='south',has_book=False,powered=False))
        for x,y,z in [(2,7,4),(9,6,4),(3,6,2)]:b.vine(x,y,z,'north' if z==4 else 'south')
        for x in [5,6,7]:b.put(x,5,7,st('moss_carpet'))
    elif kind=='root_cradle':
        b.ring(6,6,4,breaks=[(6,10)])
        for x,z in [(3,4),(8,3),(9,8)]:
            b.floor(x,z);b.pillar(x,z,2)
        log=st(PREFIX+'cloudsea_ancient_beech_log',axis='y')
        for x,z,side in [(3,5,1),(9,7,-1)]:
            b.floor(x,z)
            for y in range(5,12):
                xx=x+(y-5)//3*side;b.put(xx,y,z,log)
                if y>5:b.put(x+(y-6)//3*side,y,z,log)
            for dx in range(-1,2):b.put(6+dx,11,z,log)
        b.put(6,11,6,log);b.lamp(6,10,6,2);b.floor(6,6);b.put(6,5,6,RUNE);b.cache(7,5,6)
        for x,z in [(3,4),(8,3),(9,8)]:b.vine(x,6,z+1,'north')
        for x,z in [(4,4),(8,8),(5,9)]:b.floor(x,z);b.put(x,5,z,st('moss_carpet'))
    elif kind=='frost_resonator':
        b.ring(5,5,3,breaks=[(5,8)])
        for x,z,h in [(3,4,7),(7,4,9-v),(5,7,5+v)]:
            b.pillar(x,z,h);b.put(x,5+h,z,st('polished_deepslate'));b.put(x,6+h,z,st('lightning_rod',facing='up',powered=False,waterlogged=False))
        b.box((3,11,4),(7,11,4),STONE);b.lamp(5,10,4,2)
        b.floor(5,5);b.put(5,5,5,st('amethyst_cluster',facing='up',waterlogged=False));b.cache(6,5,6)
        for x,z in [(2,5),(4,2),(8,6)]:b.floor(x,z,st('snow_block'));b.put(x,5,z,st('snow',layers=2))
    elif kind=='wind_compass':
        b.ring(7,7,5,breaks=[(11,10),(3,4)])
        for d,(dx,dz) in enumerate([(1,0),(-1,0),(0,1),(0,-1)]):
            for k in range(1,5):b.floor(7+dx*k,7+dz*k,RUNE if k==4 else STONE)
        b.pillar(7,7,2);b.put(7,7,7,st('weathered_copper'))
        for x,z,h in [(7,2,4),(12,7,2+v),(2,7,3),(7,12,1)]:b.pillar(x,z,h)
        b.put(7,5,3,MOSS);b.put(7,6,3,st(PREFIX+'wayside_jade_lamp'));b.cache(6,5,7)
        b.vine(8,6,2,'west');b.vine(2,6,8,'north')
    elif kind=='tide_lens':
        b.ring(6,6,4,breaks=[(6,10),(9,8)])
        for x,z in [(3,5),(9,5)]:b.pillar(x,z,5)
        for y,xs in [(9,[3,4,8,9]),(10,[4,5,7,8]),(11,[5,6,7])]:
            for x in xs:b.put(x,y,5,st('oxidized_copper') if y==11 else STONE)
        b.lamp(6,10,5,2);b.put(6,7,5,st('tinted_glass'));b.cache(9,5,6)
        for x in [4,5,6,7,8]:b.floor(x,7,st('prismarine_bricks'))
        b.vine(2,7,5,'east');b.vine(10,8,5,'west')
    elif kind=='reed_siphon':
        for x in range(3,9):
            for z in range(3,7):
                if not(x==8 and z==3):b.floor(x,z)
        for x,z,h in [(3,3,5),(8,3,6),(3,6,2),(8,6,2)]:b.pillar(x,z,h)
        for x,y in [(3,10),(4,10),(4,11),(5,11),(6,11),(6,10),(7,10),(7,9),(8,9),(8,10)]:b.put(x,y,3,st('oxidized_cut_copper'))
        b.lamp(5,10,3,2);b.cache(7,5,5)
        b.box((4,5,4),(6,5,5),st('prismarine_bricks'));b.put(5,6,4,st('water_cauldron',level=2))
        b.vine(3,7,4,'north');b.vine(8,8,4,'north')
    elif kind=='ravine_thread':
        for x,z,h in [(3,4,9),(8,4,7),(6,7,3)]:b.pillar(x,z,h)
        b.box((3,13,4),(7,13,4),STONE);b.put(8,12,4,STONE)
        for x,length in [(4,4),(5,6),(6,3),(7,5)]:
            for dy in range(length):b.put(x,12-dy,4,st('iron_chain',axis='y',waterlogged=False))
            b.put(x,12-length,4,RUNE if x%2 else st(PREFIX+'wayside_jade_lamp'))
        for x in range(3,9):b.floor(x,5)
        b.cache(5,5,6);b.vine(2,10,4,'east');b.vine(9,8,4,'west')
    elif kind=='rain_memory':
        b.ring(6,6,4,breaks=[(6,10)])
        for x in range(4,9):
            for z in range(4,9):b.floor(x,z,RUNE if (x,z)==(6,6) else MOSS)
        for x,z,h in [(3,4,3),(9,4,4),(4,9,2),(8,9,3)]:b.pillar(x,z,h)
        b.put(6,5,6,st('tinted_glass'));b.cache(7,5,7)
        b.put(3,8,4,st(PREFIX+'wayside_jade_lamp'));b.put(9,9,4,st(PREFIX+'wayside_jade_lamp'))
        for x,z in [(4,3),(8,3),(3,8)]:b.put(x,5,z,st('sea_pickle',pickles=2,waterlogged=True))
    return b.finish()

def loot(kind,theme):
    def item(name,weight,lo=1,hi=1):
        return {'type':'minecraft:item','name':name,'weight':weight,'functions':[{'function':'minecraft:set_count','count':{'type':'minecraft:uniform','min':lo,'max':hi}}]}
    write(D/f'loot_table/chests/wayside/{kind}.json',{'type':'minecraft:chest','pools':[
        # 补给保底，地形物品另外抽取，避免只开出纸张。
        {'rolls':{'type':'minecraft:uniform','min':2,'max':4},'entries':[
            item('minecraft:bread',5,2,5),item('minecraft:baked_potato',5,3,7),item('minecraft:torch',6,4,10),
            item('minecraft:arrow',3,4,10),item('minecraft:paper',3,2,6),item('minecraft:book',1,1,2)]},
        {'rolls':{'type':'minecraft:uniform','min':1,'max':2},'entries':[item(theme,3,1,2),{'type':'minecraft:empty','weight':6}]},
        {'rolls':1,'entries':[item('minecraft:golden_apple',1),{'type':'minecraft:empty','weight':19}]},
        # 钱币和 Logos 物品统一由手写的分级表给，见 docs/LOOT_TIERS.md。
        {'rolls':1,'entries':[{'type':'minecraft:loot_table','value':PREFIX+'chests/tiers/common'}]}]})

def generate():
    art();summary=[]
    write(D/'worldgen/processor_list/wayside_preserve.json',{'processors':[{'processor_type':'minecraft:block_ignore','blocks':[VOID]}]})
    soft=lambda off:{'type':'minecraft:any_of','predicates':[{'type':'minecraft:not','predicate':{'type':'minecraft:solid','offset':off}},{'type':'minecraft:matching_block_tag','tag':'minecraft:leaves','offset':off}]}
    write(D/'worldgen/configured_feature/wayside_footing.json',{'type':'minecraft:block_column','config':{'direction':'down','prioritize_tip':False,'allowed_placement':{'type':'minecraft:all_of','predicates':[{'type':'minecraft:inside_world_bounds','offset':[0,1,0]},soft([0,1,0])]},'layers':[{'height':384,'provider':{'type':'minecraft:simple_state_provider','state':STONE}}]}})
    write(D/'worldgen/placed_feature/wayside_footing.json',{'feature':PREFIX+'wayside_footing','placement':[{'type':'minecraft:random_offset','xz_spread':0,'y_spread':-2},{'type':'minecraft:block_predicate_filter','predicate':soft([0,0,0])}]})
    write(D/'worldgen/template_pool/wayside/supports.json',{'name':PREFIX+'wayside/supports','fallback':'minecraft:empty','elements':[{'weight':1,'element':{'element_type':'minecraft:feature_pool_element','feature':PREFIX+'wayside_footing','projection':'rigid'}}]})
    entries=[]
    for kind,title,biomes,theme in CATALOG:
        summary.extend(build(kind,v) for v in [0,1]);loot(kind,theme)
        pool=PREFIX+'wayside/'+kind
        # 让原版模板按现场水体决定含水状态，避免湖底灯挤掉水，也不在浅水上方凭空造水源。
        write(D/f'worldgen/template_pool/wayside/{kind}.json',{'name':pool,'fallback':'minecraft:empty','elements':[{'weight':1,'element':{'element_type':'minecraft:single_pool_element','location':PREFIX+f'wayside/{kind}_{v}','processors':PREFIX+'wayside_preserve','override_liquid_settings':'apply_waterlogging','projection':'rigid'}} for v in [0,1]]})
        tagged('logos_dimension','worldgen/biome','has_structure/wayside_'+kind,[PREFIX+'cloudsea_'+b for b in biomes])
        write(D/f'worldgen/structure/wayside_{kind}.json',{'type':'minecraft:jigsaw','biomes':'#logos_dimension:has_structure/wayside_'+kind,
            'step':'surface_structures','spawn_overrides':{},'terrain_adaptation':'none','start_pool':pool,'size':1,
            'start_height':{'absolute':-3},'project_start_to_heightmap':'OCEAN_FLOOR_WG','max_distance_from_center':24,'use_expansion_hack':False})
        entries.append({'structure':PREFIX+'wayside_'+kind,'weight':1})
    write(D/'worldgen/structure_set/wayside_ruins.json',{'structures':entries,'placement':{'type':'minecraft:random_spread','spacing':26,'separation':14,'salt':79101324}})
    tagged('logos_dimension','worldgen/structure','wayside_ruins',[e['structure'] for e in entries])
    write(ROOT/'art/wayside/manifest.json',{'templates':summary,'kinds':len(CATALOG),'variants':2,'note':'不含实体、复制土台或运行时建筑生成器。'})

if __name__=='__main__':generate()
