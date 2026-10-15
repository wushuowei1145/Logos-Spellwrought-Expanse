from pathlib import Path
import json, math, random
from PIL import Image, ImageDraw, ImageFont

base = Path(__file__).parent
project = base.parent
resources = project / 'src/main/resources'
ns = 'logos_dimension'
assets = resources / 'assets' / ns
data = resources / 'data' / ns

def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

palette = {
 'strata_stone':((116,124,122),'层理石','Stratified Stone'),
 'aether_soil':((83,88,77),'以太土','Aether Soil'),
 'aether_turf':((117,153,138),'以太草皮','Aether Turf'),
 'island_stone':((188,193,184),'空岛基石','Sky Island Foundation'),
 'charred_stone':((48,46,44),'焦化岩','Charred Stone'),
 'cinnabar':((157,71,62),'朱砂沉积','Cinnabar Deposit'),
 'sulfur_deposit':((178,155,76),'炼金硫沉积','Alchemical Sulfur Deposit'),
 'mercury_metal':((159,175,180),'凝汞金属','Solidified Mercury'),
 'mercury_crust':((136,155,161),'凝汞壳','Mercury Crust'),
 'salt_crust':((213,209,193),'盐壳','Salt Crust'),
 'salt_crystal':((218,226,219),'盐晶','Salt Crystal'),
 'fan_trunk':((64,62,54),'扇冠树干','Fan Tree Trunk'),
 'fan_leaves':((135,180,168),'扇冠叶','Fan Leaves'),
 'glass_leaves':((157,204,211),'玻璃叶','Glass Leaves'),
 'distillation_glass':((180,206,204),'蒸馏玻璃','Distillation Glass'),
 'amber_seeds':((199,158,83),'琥珀种穗','Amber Seed Tassels'),
 'star_blossom':((211,227,221),'星形花','Star Blossom'),
 'aether_fern':((113,165,149),'以太蕨','Aether Fern'),
 'vortex':((174,159,112),'炼金漩涡','Alchemical Vortex'),
 'alchemical_cauldron':((49,52,59),'炼金釜','Alchemical Cauldron')}
reactive = {'cinnabar','sulfur_deposit','mercury_crust','salt_crust','salt_crystal'}
plants = {'star_blossom','aether_fern','amber_seeds'}

def texture(name, color, phase=None):
    rng = random.Random(name)
    im = Image.new('RGBA', (16,16), (0,0,0,0))
    p = im.load()
    for y in range(16):
        for x in range(16):
            v = rng.choice([-12,-8,-4,0,0,4,8,12])
            if name in {'strata_stone','island_stone','charred_stone'}: v += -13 if y in {4,10,15} else 0
            if 'mercury' in name: v += int(18*math.sin(x*.6+y*.3))
            if name == 'fan_trunk': v += -17 if x in {2,3,9,12} else 0
            c = color
            if phase is not None:
                f = [.92,1.025,1.0,.98][phase]
                c = tuple(int(k*f) for k in color)
                if phase == 2: c = (c[0]+3,c[1]+2,c[2]-3)
                if phase == 3: c = (c[0]+4,c[1]-2,c[2]-3)
            alpha = 255
            if name in {'fan_leaves','glass_leaves'} and ((x*3+y*5)%11<2): continue
            if name == 'glass_leaves': alpha = 165
            if name == 'salt_crystal': alpha = 190
            if name == 'distillation_glass':
                alpha = 32 if x not in {0,15} and y not in {0,15} else 140
                if x-y in {1,2}: v=30; alpha=60
            p[x,y] = tuple(max(0,min(255,k+v)) for k in c)+(alpha,)
    d = ImageDraw.Draw(im)
    if name == 'salt_crust':
        for line in [[(1,2),(6,3),(8,8),(14,10)],[(8,8),(4,11),(3,15)]]: d.line(line, fill=(141,142,132,255), width=1)
    if name in {'fan_leaves','glass_leaves'}:
        d.line([(0,15),(8,3),(15,15)],fill=tuple(max(0,k-32) for k in color)+(220,),width=1)
        d.line([(8,15),(8,3)],fill=tuple(min(255,k+22) for k in color)+(220,),width=1)
    if name == 'vortex':
        for k in range(64):
            a=k*.32; r=7*(1-k/64)
            x=int(8+math.cos(a)*r); y=int(8+math.sin(a)*r)
            d.point((x,y), fill=(237,223,163,255))
    if name in plants:
        im = Image.new('RGBA',(16,16),(0,0,0,0)); d=ImageDraw.Draw(im)
        if name=='star_blossom':
            d.line([(8,14),(8,7)],fill=(80,121,97,255),width=1)
            for a in range(0,360,60):
                t=math.radians(a); px=8+int(math.cos(t)*4); py=6+int(math.sin(t)*4)
                d.line([(8,6),(px,py)],fill=(223,230,214,255),width=2)
            d.point((8,6),fill=(139,197,211,255))
        elif name=='aether_fern':
            d.line([(8,14),(7,2)],fill=(68,113,98,255),width=1)
            for y in range(4,13,3):
                d.line([(8,y+1),(3,y-2)],fill=tuple(color)+(255,),width=2)
                d.line([(8,y+1),(13,y-2)],fill=tuple(color)+(255,),width=2)
        else:
            for x in [3,7,12]:
                d.line([(x,0),(x,12)],fill=(128,109,71,255),width=1)
                for y in [3,6,9]: d.rectangle((x-1,y,x+1,y+1),fill=tuple(color)+(255,))
    return im

def save_texture(name, im):
    path=assets/'textures/block'/f'{name}.png'; path.parent.mkdir(parents=True,exist_ok=True); im.save(path)

zh_path=assets/'lang/zh_cn.json'; en_path=assets/'lang/en_us.json'
zh=json.loads(zh_path.read_text(encoding='utf-8')) if zh_path.exists() else {}
en=json.loads(en_path.read_text(encoding='utf-8')) if en_path.exists() else {}
zh.update({'itemGroup.logos_dimension':'炼金釜 · Logos 附属维度','message.logos_dimension.missing_dimension':'炼金维度未加载，请检查安装与世界数据。'})
en.update({'itemGroup.logos_dimension':'The Crucible · Logos Addon','message.logos_dimension.missing_dimension':'The Crucible dimension is unavailable. Check the installation and world data.'})
for name,(color,cn,english) in palette.items():
    zh[f'block.{ns}.{name}']=cn; en[f'block.{ns}.{name}']=english
    variants={}
    versions=range(4) if name in reactive else [None]
    for phase in versions:
        suffix='' if phase is None else f'_{phase}'
        tex=name+suffix; save_texture(tex,texture(name,color,phase))
        model={'parent':'minecraft:block/cube_all','textures':{'all':f'{ns}:block/{tex}'}}
        if name in plants: model={'parent':'minecraft:block/cross','textures':{'cross':f'{ns}:block/{tex}'},'render_type':'neoforge:cutout'}
        if name=='fan_leaves': model['render_type']='neoforge:cutout'
        if name in {'salt_crystal','glass_leaves','distillation_glass'}: model['render_type']='neoforge:translucent'
        write(assets/'models/block'/f'{tex}.json',model)
        variants['' if phase is None else f'phase={phase}']={'model':f'{ns}:block/{tex}'}
    write(assets/'blockstates'/f'{name}.json',{'variants':variants})
    item_model=name+'_0' if name in reactive else name
    write(assets/'items'/f'{name}.json',{'model':{'type':'minecraft:model','model':f'{ns}:block/{item_model}'}})
    # Concentrated natural minerals feed the existing Logos material economy.
    if name in {'cinnabar','salt_crystal'}:
        entry={'type':'minecraft:item','name':'logos:tainted_mana_shard'}
    else: entry={'type':'minecraft:item','name':f'{ns}:{name}'}
    write(data/'loot_table/blocks'/f'{name}.json',{'type':'minecraft:block','pools':[{'rolls':1,'entries':[entry],'conditions':[{'condition':'minecraft:survives_explosion'}]}]})

# A hollow, low-poly crucible, importable as a Java block model in Blockbench.
save_texture('cauldron_iron', texture('cauldron_iron', (47,51,58)))
rim=texture('cauldron_brass', (142,122,77)); rd=ImageDraw.Draw(rim)
rd.line([(0,2),(15,2)], fill=(192,168,103,255)); rd.line([(0,13),(15,13)], fill=(83,76,60,255))
save_texture('cauldron_brass',rim)
sheet=Image.new('RGBA',(64,64*24)); gold=(231,195,111); silver=(178,202,210)
for frame in range(24):
    im=Image.new('RGBA',(64,64)); pix=im.load()
    for y in range(64):
        for x in range(64):
            dx=x-31.5; dy=y-31.5; r=math.hypot(dx,dy); a=math.atan2(dy,dx)
            wave=(a-frame*math.tau/24+r*.15)*3
            crest=max(0,math.cos(wave))**10
            alt=math.floor((a-frame*math.tau/24+r*.15)*3/math.tau)%2
            tint=gold if alt==0 else silver
            depth=.2+.8*crest
            core=max(0,1-r/5)**2
            pix[x,y]=tuple(min(255,int((18,26,35)[i]*(1-depth)+tint[i]*depth+core*85)) for i in range(3))+(255,)
    sheet.paste(im,(0,64*frame))
save_texture('cauldron_vortex',sheet)
write(assets/'textures/block/cauldron_vortex.png.mcmeta',{'animation':{'frametime':3,'interpolate':True}})
elements=[]
def cauldron_cube(name,start,end,tex):
    elements.append({'name':name,'from':start,'to':end,'faces':{face:{'texture':'#'+tex} for face in ['up','down','north','south','east','west']}})
cauldron_cube('basin_floor',[1,3,1],[15,4,15],'iron')
for name,start,end in [
 ('wall_west',[1,4,1],[2,11,15]),('wall_east',[14,4,1],[15,11,15]),
 ('wall_north',[2,4,1],[14,11,2]),('wall_south',[2,4,14],[14,11,15])]: cauldron_cube(name,start,end,'iron')
for name,start,end in [
 ('rim_north',[0,11,0],[16,13,2]),('rim_south',[0,11,14],[16,13,16]),
 ('rim_west',[0,11,2],[2,13,14]),('rim_east',[14,11,2],[16,13,14])]: cauldron_cube(name,start,end,'brass')
for x in [2,12]:
    for z in [2,12]: cauldron_cube(f'foot_{x}_{z}',[x,0,z],[x+2,3,z+2],'iron')
for x in [-1,16]:
    cauldron_cube(f'handle_{x}_front',[x,7,5],[x+1,10,6],'brass')
    cauldron_cube(f'handle_{x}_back',[x,7,10],[x+1,10,11],'brass')
    cauldron_cube(f'handle_{x}_bar',[x,6,5],[x+1,7,11],'brass')
elements.append({'name':'liquid_vortex','from':[2,8,2],'to':[14,8,14],
                 'faces':{'up':{'texture':'#vortex','uv':[0,0,16,16]}},'shade':False,'light_emission':15})
write(assets/'models/block/alchemical_cauldron.json',{'parent':'minecraft:block/block',
 'textures':{'particle':f'{ns}:block/cauldron_iron','iron':f'{ns}:block/cauldron_iron','brass':f'{ns}:block/cauldron_brass','vortex':f'{ns}:block/cauldron_vortex'},
 'elements':elements,'display':{'gui':{'rotation':[30,225,0],'translation':[0,1,0],'scale':[.8,.8,.8]},
 'ground':{'translation':[0,3,0],'scale':[.35,.35,.35]},'fixed':{'scale':[.75,.75,.75]}}})
write(data/'recipe/alchemical_cauldron.json',{'type':'minecraft:crafting_shapeless','category':'misc',
 'ingredients':['minecraft:cauldron',f'{ns}:vortex'],'result':{'id':f'{ns}:alchemical_cauldron','count':1}})

for name,color in [('mercury',(175,190,201)),('molten_glass',(223,124,55))]:
    for mode in ['still','flow']:
        sheet=Image.new('RGBA',(16,64))
        for frame in range(4):
            im=Image.new('RGBA',(16,16)); p=im.load()
            for y in range(16):
                for x in range(16):
                    v=int(25*math.sin((x+y*.7+frame*2)*.45)+9*math.cos((y-frame*2)*.6))
                    p[x,y]=tuple(max(0,min(255,k+v)) for k in color)+(240 if name=='mercury' else 255,)
            sheet.paste(im,(0,frame*16))
        tex=f'{name}_{mode}'; save_texture(tex,sheet)
        write(assets/'textures/block'/f'{tex}.png.mcmeta',{'animation':{'frametime':12 if name=='mercury' else 20,'interpolate':True}})
    write(assets/'blockstates'/f'{name}.json',{'variants':{'':{'model':'minecraft:block/water'}}})
    write(assets/'items'/f'{name}_bucket.json',{'model':{'type':'minecraft:model','model':f'{ns}:item/{name}_bucket'}})
    bucket=Image.new('RGBA',(16,16),(0,0,0,0)); bd=ImageDraw.Draw(bucket)
    bd.polygon([(2,5),(13,5),(11,13),(4,13)],fill=(66,75,79,255))
    bd.polygon([(3,6),(12,6),(10,12),(5,12)],fill=(160,171,172,255))
    bd.ellipse((2,3,13,7),fill=(188,200,199,255))
    bd.ellipse((3,4,12,6),fill=tuple(color)+(255,))
    bd.line([(4,7),(5,11)],fill=(223,231,223,255),width=1)
    save_texture(name+'_bucket',bucket)
    write(assets/'models/item'/f'{name}_bucket.json',{'parent':'minecraft:item/generated','textures':{'layer0':f'{ns}:block/{name}_bucket'}})
    zh[f'item.{ns}.{name}_bucket']='汞桶' if name=='mercury' else '熔融玻璃桶'
    en[f'item.{ns}.{name}_bucket']='Mercury Bucket' if name=='mercury' else 'Molten Glass Bucket'
    zh[f'fluid_type.{ns}.{name}']='液态汞' if name=='mercury' else '熔融玻璃'
    en[f'fluid_type.{ns}.{name}']='Liquid Mercury' if name=='mercury' else 'Molten Glass'

for name,cn,en_name in [('aether','以太荒野','Aether Wilderness'),('sulfur','硫之环','Sulfur Belt'),('mercury','汞之环','Mercury Belt'),('salt','盐之环','Salt Belt'),('sanctum','中心空岛禁域','Central Island Sanctum')]:
    zh[f'biome.{ns}.{name}']=cn; en[f'biome.{ns}.{name}']=en_name
    spawners={} if name=='sanctum' else {'monster':[{'type':'logos:mana_leech','weight':12 if name=='aether' else 30,'minCount':1,'maxCount':2}]}
    write(data/'worldgen/biome'/f'{name}.json',{'has_precipitation':name=='aether','temperature':.75,'downfall':.25 if name=='aether' else 0,
        'effects':{'water_color':'#779f9b','grass_color':'#8aab95','foliage_color':'#99bdb3'},'carvers':[],
        'features':[[] for _ in range(11)],'spawners':spawners,'spawn_costs':{}})
for phase,cn in [('nigredo','黑化'),('albedo','白化'),('citrinitas','黄化'),('rubedo','红化')]:
    zh[f'phase.{ns}.{phase}']=cn; en[f'phase.{ns}.{phase}']=phase.capitalize()
write(assets/'lang/zh_cn.json',zh); write(assets/'lang/en_us.json',en)
write(data/'dimension/crucible.json',{'type':f'{ns}:crucible','generator':{'type':f'{ns}:crucible','biome_source':{'type':f'{ns}:alchemical','pattern_spacing':20000,**{b:f'{ns}:{b}' for b in ['aether','sulfur','mercury','salt','sanctum']}}}})
write(data/'world_clock/opus.json',{})
write(data/'dimension_type/crucible.json',{'has_skylight':True,'has_ceiling':False,'has_ender_dragon_fight':False,
    'coordinate_scale':1.0,'ambient_light':.10,'min_y':-64,'height':512,'logical_height':512,
    'infiniburn':'#minecraft:infiniburn_overworld','monster_spawn_light_level':7,'monster_spawn_block_light_limit':0,
    'skybox':'none','default_clock':f'{ns}:opus','timelines':[f'{ns}:opus'],
    'attributes':{'minecraft:visual/cloud_height':410.0,'minecraft:visual/sky_color':'#a8b5b4','minecraft:visual/fog_color':'#aab7b3',
        'minecraft:gameplay/bed_rule':{'can_set_spawn':'always','can_sleep':'never','error_message':{'translate':'block.minecraft.bed.no_sleep'}}}})
colors={'minecraft:visual/sky_color':['#222730','#c8d5d4','#c9b78a','#ad867d'],
        'minecraft:visual/fog_color':['#333742','#c5d2cc','#c4b58c','#9a7770'],
        'minecraft:visual/cloud_color':['#884f535d','#bfe1e6de','#bfd7c8a3','#bfbb9790']}
tracks={name:{'keyframes':[{'ticks':i*24000,'value':color} for i,color in enumerate(values)]} for name,values in colors.items()}
tracks['minecraft:gameplay/sky_light_level']={'keyframes':[{'ticks':i*24000,'value':v} for i,v in enumerate([.45,1.0,.9,.75])]}
tracks['minecraft:visual/sky_light_factor']={'keyframes':[{'ticks':i*24000,'value':v} for i,v in enumerate([.4,1.0,.9,.75])]}
write(data/'timeline/opus.json',{'clock':f'{ns}:opus','period_ticks':96000,'time_markers':{f'{ns}:{p}':{'ticks':i*24000,'show_in_commands':True} for i,p in enumerate(['nigredo','albedo','citrinitas','rubedo'])},'tracks':tracks})
write(data/'recipe/vortex.json',{'type':'minecraft:crafting_shaped','category':'misc','pattern':['GGG','GSG','GGG'],'key':{'G':'minecraft:glass','S':'logos:tainted_mana_shard'},'result':{'id':f'{ns}:vortex','count':1}})
for tag,names in [('mineable/pickaxe',['strata_stone','island_stone','charred_stone','cinnabar','sulfur_deposit','mercury_metal','mercury_crust','salt_crust','salt_crystal','alchemical_cauldron']),('logs',['fan_trunk']),('leaves',['fan_leaves','glass_leaves']),('supports_vegetation',['aether_turf','aether_soil']),('animals_spawnable_on',['aether_turf'])]:
    write(resources/'data/minecraft/tags/block'/f'{tag}.json',{'replace':False,'values':[f'{ns}:{n}' for n in names]})

# The sparse astrolabe is a real renderer texture, not a concept illustration.
im=Image.new('RGBA',(1024,1024),(0,0,0,0)); d=ImageDraw.Draw(im); c=(223,213,176,150)
for r in [230,340,440]: d.ellipse((512-r,512-r,512+r,512+r),outline=c,width=4)
for i in range(24):
    a=i*math.pi/12; r=440; length=17 if i%3==0 else 7
    d.line([(512+math.cos(a)*r,512+math.sin(a)*r),(512+math.cos(a)*(r-length),512+math.sin(a)*(r-length))],fill=c,width=2)
for i in range(8):
    a=i*math.pi/4; x=512+math.cos(a)*340; y=512+math.sin(a)*340
    d.ellipse((x-13,y-13,x+13,y+13),outline=c,width=2)
    d.line([(x,y+13),(x,y+32)],fill=c,width=2); d.line([(x-8,y+24),(x+8,y+24)],fill=c,width=2)
    if i%2==0: d.polygon([(x,y-25),(x-14,y-1),(x+14,y-1)],outline=c,width=2)
for a,b in [(0,3),(1,4),(2,6),(5,7)]:
    p=lambda i:(512+math.cos(i*math.pi/4)*230,512+math.sin(i*math.pi/4)*230)
    d.line([p(a),p(b)],fill=(223,213,176,90),width=2)
path=assets/'textures/environment/astrolabe.png'; path.parent.mkdir(parents=True,exist_ok=True); im.save(path)

# Soft, localized fog shelves and a ragged silhouette; visual apparitions are never actual mobs.
mist=Image.new('RGBA',(256,256)); mp=mist.load()
for y in range(256):
    for x in range(256):
        radius=math.hypot((x-127.5)/127.5,(y-127.5)/127.5)
        edge=max(0,1-radius)**.5
        swirls=(math.sin(x*.058+math.sin(y*.045)*2)+math.sin(y*.07-x*.022)+math.sin((x+y)*.11)) / 3
        mp[x,y]=(156,162,170,int(edge*(16+15*(swirls+1))))
mist.save(assets/'textures/environment/island_mist.png')
shade=Image.new('RGBA',(32,64),(0,0,0,0)); sd=ImageDraw.Draw(shade)
sd.polygon([(12,3),(19,4),(21,10),(18,16),(22,19),(27,32),(29,45),(26,43),(23,35),(22,47),(24,59),(21,61),(17,46),(15,61),(12,60),(13,45),(10,33),(7,45),(4,49),(5,39),(8,23),(12,17),(10,10)],fill=(11,15,20,225))
sd.line([(11,20),(9,33),(7,38)],fill=(28,32,36,135),width=2)
sd.polygon([(16,16),(19,22),(17,35),(15,30)],fill=(27,31,36,90))
sd.point((13,10),fill=(155,168,173,155)); sd.point((17,10),fill=(138,155,165,140))
for y in range(18,60,4): sd.point((12+y%5,y),fill=(0,0,0,0))
shade.save(assets/'textures/environment/mist_shadow.png')
write(data/'tags/entity_type/island_bosses.json',{'replace':False,'values':[]})

preview=Image.new('RGB',(640,math.ceil(len(palette)/4)*130),(35,40,41)); d=ImageDraw.Draw(preview)
try: font=ImageFont.truetype('DejaVuSansMono.ttf',12)
except OSError: font=ImageFont.load_default()
for i,name in enumerate(palette):
    x=(i%4)*160; y=(i//4)*130
    sprite=Image.open(assets/'textures/block'/f'{name+"_1" if name in reactive else name}.png')
    preview.paste(sprite.resize((96,96),Image.Resampling.NEAREST),(x+32,y+4),sprite.resize((96,96),Image.Resampling.NEAREST))
    d.text((x+6,y+104),name,font=font,fill=(220,224,219))
out=project/'build/asset-preview'; out.mkdir(parents=True,exist_ok=True); preview.save(out/'texture_palette.png')
print(f'Generated {len(palette)} natural/block materials, 2 fluids, 5 biomes and the alchemical timeline.')

from polish_models import polish_crucible
polish_crucible()
