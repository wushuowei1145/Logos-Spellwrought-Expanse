"""Deterministic source assets for Cloudsea; no runtime downloads or third-party textures."""
from pathlib import Path
import json, re, math, random
from PIL import Image, ImageDraw, ImageFilter
project=Path(__file__).resolve().parent.parent
res=project/'src/main/resources'; ns='logos_dimension'; assets=res/'assets'/ns; data=res/'data'/ns
def write(path,value):
    path.parent.mkdir(parents=True,exist_ok=True); path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
text=(project/'src/main/java/dev/logos/dimension/cloudsea/CloudseaBiome.java').read_text(encoding='utf-8')
biomes=re.findall(r'([A-Z_]+)\("([^"]+)",0x([A-F0-9]+),0x([A-F0-9]+),Form\.([A-Z]+)',text)
assert len(biomes)==24
def rgb(hexcolor): return tuple(bytes.fromhex(hexcolor))
def tile(name,color,glow=False):
    frames=4 if glow else 1; sheet=Image.new('RGBA',(16,16*frames)); rng=random.Random(name)
    noise=[rng.randrange(-9,10) for _ in range(256)]
    for f in range(frames):
        im=Image.new('RGBA',(16,16)); p=im.load()
        for y in range(16):
            for x in range(16):
                grain=noise[y*16+x]; vein=abs((x+int(math.sin(y*.45)*2))%13-6)<1
                boost=(18+int(8*math.sin(f*math.pi/2))) if glow and vein else 0
                p[x,y]=tuple(min(255,max(0,c+grain+boost)) for c in color)+(255,)
        sheet.paste(im,(0,f*16))
    path=assets/'textures/block'/f'{name}.png'; path.parent.mkdir(parents=True,exist_ok=True);sheet.save(path)
    if glow: write(path.with_suffix('.png.mcmeta'),{'animation':{'frametime':24,'interpolate':True}})
def cube(start,end,tex='all',rotation=None,glow=0):
    e={'from':start,'to':end,'faces':{f:{'texture':'#'+tex} for f in ['north','south','west','east','up','down']}}
    if rotation: e['rotation']=rotation
    if glow:e.update(shade=False,light_emission=glow)
    return e
special={
 'cloudsea_porcelain':((198,210,206),'云瓷岩','Cloud Porcelain'),
 'cloudsea_mantle':((94,109,126),'魔力地层','Mana Mantle'),
 'cloudsea_stem':((139,187,181),'悬叶茎','Floating Leaf Stalk'),
 'cloudsea_lantern_bud':((231,213,151),'灯蕊果','Lantern Bud'),
 'cloudsea_hanging_root':((139,195,202),'逆生丝根','Inverted Threadroot'),
 'cloudsea_eye_fern':((144,179,202),'瞳叶蕨','Eyeleaf Fern'),
 'cloudsea_demon_reed':((177,105,137),'幽焰苇','Veilflame Reed'),
 'cloudsea_demonstone':((68,55,82),'魔气地岩','Veiled Depthstone'),
 'cloudsea_ivory_bricks':((209,204,185),'云域象牙砖','Cloudsea Ivory Bricks'),
 'cloudsea_dark_timber':((66,60,54),'云域深木','Cloudsea Dark Timber'),
 'cloudsea_blue_slate':((79,96,120),'云域蓝瓦','Cloudsea Blue Slate'),
 'cloudsea_lit_window':((223,204,153),'云域暖光窗','Cloudsea Warm Window')}
zh_path=assets/'lang/zh_cn.json';en_path=assets/'lang/en_us.json';zh=json.loads(zh_path.read_text(encoding='utf-8'));en=json.loads(en_path.read_text(encoding='utf-8'))
zh['itemGroup.logos_dimension.cloudsea']='天门云域';en['itemGroup.logos_dimension.cloudsea']='Cloudsea'
def block(name,color,cn,english,plant=None):
    tile(name,color,plant!='stem')
    model={'parent':'minecraft:block/cube_all','textures':{'all':f'{ns}:block/{name}'}}
    if plant:
        model={'parent':'minecraft:block/block','textures':{'all':f'{ns}:block/{name}','particle':f'{ns}:block/{name}'},'render_type':'neoforge:cutout','elements':[]}
        if plant=='stem': model['elements']=[cube([7,0,7],[9,16,9],glow=2)]
        elif plant.startswith('leaf'):
            form=plant.split('_',1)[1] if '_' in plant else 'fold'
            shapes={
             'fold':[( [0,7,4],[16,8,12],22.5),( [2,10,5],[14,11,11],-22.5)],
             'petal':[( [1,6,3],[15,7,13],45),( [3,10,4],[13,11,12],-45)],
             'basin':[( [1,8,2],[15,9,14],0),( [4,10,5],[12,11,11],0)],
             'hanging':[( [5,0,6],[7,14,10],22.5),( [10,2,6],[12,16,10],-22.5)],
             'ribbon':[( [0,7,6],[16,8,10],22.5),( [0,11,7],[16,12,9],-22.5)],
             'steps':[( [3,2,5],[5,15,11],22.5),( [10,2,5],[12,15,11],-22.5)],
             'dune':[( [4,0,7],[6,15,9],22.5),( [9,0,7],[11,16,9],-22.5)],
             'spire':[( [7,0,4],[9,16,12],0),( [3,5,6],[13,6,10],45)]}
            model['elements']=[cube(a,b,rotation={'origin':[8,8,8],'axis':'z','angle':angle}) for a,b,angle in shapes[form]]
        elif plant=='lantern':model['elements']=[cube([7,8,7],[9,16,9]),cube([5,2,5],[11,8,11],glow=12)]
        elif plant=='root':model['elements']=[cube([3,3,5],[4,16,6],glow=4),cube([8,0,10],[9,16,11],glow=4),cube([12,6,3],[13,16,4],glow=4)]
        else:
            model['elements']=[cube([7,0,7],[9,13,9],glow=4),cube([2,4,6],[14,5,10],rotation={'origin':[8,8,8],'axis':'z','angle':45},glow=4),cube([4,10,6],[12,11,10],rotation={'origin':[8,8,8],'axis':'z','angle':-45},glow=4)]
    write(assets/'models/block'/f'{name}.json',model)
    write(assets/'blockstates'/f'{name}.json',{'variants':{'':{'model':f'{ns}:block/{name}'}}})
    write(assets/'items'/f'{name}.json',{'model':{'type':'minecraft:model','model':f'{ns}:block/{name}'}})
    write(data/'loot_table/blocks'/f'{name}.json',{'type':'minecraft:block','pools':[{'rolls':1,'entries':[{'type':'minecraft:item','name':f'{ns}:{name}'}],'conditions':[{'condition':'minecraft:survives_explosion'}]}]})
    zh[f'block.{ns}.{name}']=cn;en[f'block.{ns}.{name}']=english
for name,(color,cn,eng) in special.items():
    plant='stem' if name.endswith('_stem') else 'lantern' if name.endswith('_bud') else 'root' if name.endswith('_root') else 'fern' if name.endswith(('_fern','_reed')) else None
    block(name,color,cn,eng,plant)
for key,title,ground,leaf,form in biomes:
    bid='cloudsea_'+key.lower();block(bid+'_ground',rgb(ground),title+'魔力地表',key.title().replace('_',' ')+' Ground')
    block(bid+'_leaf',rgb(leaf),title+'悬叶',key.title().replace('_',' ')+' Leaf','leaf_'+form.lower())
    zh[f'biome.{ns}.{bid}']=title;en[f'biome.{ns}.{bid}']=key.title().replace('_',' ')
    write(data/'worldgen/biome'/f'{bid}.json',{'has_precipitation':False,'temperature':.7,'downfall':0,'effects':{'water_color':'#87b6c9','grass_color':'#'+ground.lower(),'foliage_color':'#'+leaf.lower()},'carvers':[],'features':[[] for _ in range(11)],'spawners':{},'spawn_costs':{}})
for bid,title in [('cloudsea_root_caves','逆生根窟'),('cloudsea_demon_depths','魔气地心')]:
    zh[f'biome.{ns}.{bid}']=title;en[f'biome.{ns}.{bid}']=bid.replace('_',' ').title()
    write(data/'worldgen/biome'/f'{bid}.json',{'has_precipitation':False,'temperature':.65,'downfall':0,'effects':{'water_color':'#869aac'},'carvers':[],'features':[[] for _ in range(11)],'spawners':{},'spawn_costs':{}})
write(zh_path,zh);write(en_path,en)
all_biomes=['cloudsea_'+b[0].lower() for b in biomes]+['cloudsea_root_caves','cloudsea_demon_depths']
stem={'type':f'{ns}:cloudsea','generator':{'type':f'{ns}:cloudsea','biome_source':{'type':f'{ns}:cloudsea','biomes':{b:f'{ns}:{b}' for b in all_biomes}}}}
write(data/'dimension/cloudsea.json',stem)
write(data/'world_clock/cloudsea.json',{})
write(data/'dimension_type/cloudsea.json',{'has_skylight':True,'has_ceiling':False,'has_ender_dragon_fight':False,'coordinate_scale':1.0,'ambient_light':.35,'min_y':-128,'height':640,'logical_height':640,'infiniburn':'#minecraft:infiniburn_overworld','monster_spawn_light_level':0,'monster_spawn_block_light_limit':0,'skybox':'none','default_clock':f'{ns}:cloudsea','timelines':[f'{ns}:cloudsea'],'attributes':{'minecraft:visual/cloud_height':480.0,'minecraft:visual/sky_color':'#c4d5de','minecraft:visual/fog_color':'#c5d5db','minecraft:gameplay/bed_rule':{'can_set_spawn':'always','can_sleep':'never','error_message':{'translate':'block.minecraft.bed.no_sleep'}}}})
write(data/'timeline/cloudsea.json',{'clock':f'{ns}:cloudsea','period_ticks':72000,'tracks':{'minecraft:gameplay/sky_light_level':{'keyframes':[{'ticks':0,'value':.9}]},'minecraft:visual/sky_light_factor':{'keyframes':[{'ticks':0,'value':.85}]}}})
dimtype=data/'dimension_type/cloudsea.json';spec=json.loads(dimtype.read_text(encoding='utf-8'));spec['attributes'].update({'minecraft:visual/ambient_light_color':'#52636c','minecraft:visual/cloud_height':2048.0,'minecraft:visual/sky_color':'#7096b9','minecraft:visual/fog_color':'#9cb7ca'});write(dimtype,spec)
test=project/'gametest-packs/crucible/data/minecraft/worldgen/world_preset/flat.json';preset=json.loads(test.read_text(encoding='utf-8'));preset['dimensions'][f'{ns}:cloudsea']=stem;write(test,preset)
tag=res/'data/minecraft/tags/block/mineable/pickaxe.json';value=json.loads(tag.read_text(encoding='utf-8'));value['values']+= [f'{ns}:{n}' for n in special if n not in {'cloudsea_stem','cloudsea_lantern_bud','cloudsea_hanging_root','cloudsea_eye_fern','cloudsea_demon_reed'}]+[f'{ns}:cloudsea_{b[0].lower()}_ground' for b in biomes];value['values']=list(dict.fromkeys(value['values']));write(tag,value)
# A full-coverage cloud-bank texture, deliberately opaque: the cleft is a real gap in sky geometry.
cloud=Image.new('RGB',(512,512),(211,222,232));draw=ImageDraw.Draw(cloud);rng=random.Random(813)
for i in range(900):
    x=rng.randrange(-40,512);y=rng.randrange(-40,512);r=rng.randrange(12,55);c=rng.randrange(190,244)
    draw.ellipse((x-r,y-r*.7,x+r,y+r*.7),fill=(c,min(255,c+8),min(255,c+13)))
cloud=cloud.filter(ImageFilter.GaussianBlur(6));env=assets/'textures/environment';env.mkdir(parents=True,exist_ok=True);cloud.save(env/'cloudsea_clouds.png');Image.new('RGBA',(1,1),(255,255,255,255)).save(env/'cloudsea_white.png')
print('Generated 24 surface + 2 depth biomes, 60 Cloudsea blocks, sky textures and test dimension.')
