"""Reproducible temperate art, climate data, and adapted MIT Millenaire layer blueprints."""
from pathlib import Path
import json, math, random, re, zipfile
from PIL import Image, ImageDraw

ROOT=Path(__file__).resolve().parent.parent
RES=ROOT/'src/main/resources'; A=RES/'assets/logos_dimension'; D=RES/'data/logos_dimension'
def write(p,v):
    p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def save(im,name):
    p=A/'textures/block'/f'{name}.png';p.parent.mkdir(parents=True,exist_ok=True);im.save(p)
    p.with_suffix('.png.mcmeta').unlink(missing_ok=True)
def color(hex):return tuple(bytes.fromhex(hex))
def tint(c,n):return tuple(max(0,min(255,int(x+n))) for x in c)
def cube(texture):return {'parent':'minecraft:block/cube_all','textures':{'all':texture}}
def model(name,spec):
    write(A/'models/block'/f'{name}.json',spec)
    write(A/'blockstates'/f'{name}.json',{'variants':{'':{'model':f'logos_dimension:block/{name}'}}})
    write(A/'items'/f'{name}.json',{'model':{'type':'minecraft:model','model':f'logos_dimension:block/{name}'}})
def tile(name,c,kind='stone'):
    r=random.Random(name);im=Image.new('RGBA',(32,32),c+(255,));p=im.load()
    for y in range(32):
        for x in range(32):
            grain=r.randrange(-5,6);grain+=int(math.sin(x*.24+y*.13)*3)
            if kind=='wood':grain+=int(math.sin(x*.75+math.sin(y*.23))*8)
            if kind=='tile':grain+=-16 if y%7==0 or (x+(y//7%2)*8)%16==0 else 0
            p[x,y]=tint(c,grain)+(255,)
    if kind=='frame':
        q=ImageDraw.Draw(im);q.rectangle((0,0,3,31),fill='#514336');q.rectangle((28,0,31,31),fill='#514336')
        q.rectangle((0,0,31,3),fill='#514336');q.rectangle((0,28,31,31),fill='#514336');q.line((3,4,28,27),fill='#65503a',width=3)
    save(im,name);return im

text=(ROOT/'src/main/java/dev/logos/dimension/cloudsea/CloudseaBiome.java').read_text(encoding='utf-8')
biomes=re.findall(r'([A-Z_]+)\("([^"]+)",0x([A-F0-9]+),0x([A-F0-9]+)',text)
assert len(biomes)==32
zh=json.loads((A/'lang/zh_cn.json').read_text(encoding='utf-8'));en=json.loads((A/'lang/en_us.json').read_text(encoding='utf-8'))
zh['itemGroup.logos_dimension.cloudsea']='远行大陆';en['itemGroup.logos_dimension.cloudsea']='The Wayfarer Lands'
english=['Windbound Plains','Beech Woods','Shallow Sandbars','Northern Birchwood','River Valley','Highland Meadow','Chalk Coast','Bare Ridges','Old River Gorge','Oak Woodland','Stillwater Lake','Deep Broadleaf Woods','Open Downs','Northern Firwood','Wildflower Meadow','Glacier Ridge','Alpine River','Pasture Slopes','Reed Wetland','Mountain Pinewood','Southern Bay','Silver Birch Heath','Cold Heath','Snowbound Summit','Ancient Beechwood','Fernwood','Moonlit Birch Grove','Fir Heartwood','Mossy Vale','Copper Copse','Sunlit Glade','Starbell Glade']
# 只给三个最有辨识度的林地改雾色，天空与其余群系都不动。
# 精灵森林的群系文件由 tools/elven_forest.py 覆盖生成，雾色写在那里。
ATMOSPHERE={
 'cloudsea_fernwood':{'fog_color':'#9fb3aa'},
 'cloudsea_moss_vale':{'fog_color':'#c6d0c8','water_fog_color':'#b8c6c2'},
 'cloudsea_fir_heart':{'fog_color':'#aab9c4'},
}
all_ids=[]
for i,(key,title,g,l) in enumerate(biomes):
    bid='cloudsea_'+key.lower();all_ids.append(bid);base=color(g);leaf=color(l)
    for season in range(4):
        ground=tint(base,5 if season==0 else -2 if season==1 else -7)
        if season==2:ground=(min(180,ground[0]+12),ground[1],max(50,ground[2]-5))
        if season==3:ground=(208,214,204)
        top=tile(f'{bid}_ground_{season}',ground)
        side=tile(f'{bid}_side_{season}',(116,94,69));sp=side.load();tp=top.load()
        for x in range(32):
            for y in range(4+(x*7%4)):sp[x,y]=tp[x,y]
        save(side,f'{bid}_side_{season}')
        write(A/'models/block'/f'{bid}_ground_{season}.json',{'parent':'minecraft:block/cube_bottom_top','textures':{'top':f'logos_dimension:block/{bid}_ground_{season}','side':f'logos_dimension:block/{bid}_side_{season}','bottom':'minecraft:block/dirt'}})
        lc=leaf
        if season==0:lc=tint(leaf,9)
        if season==2:lc=[(166,126,57),(163,102,51),(176,143,65)][i%3]
        if season==3:lc=(144,151,136)
        if i in [13,19,22]:lc=leaf
        r=random.Random(bid);im=Image.new('RGBA',(32,32));q=ImageDraw.Draw(im)
        for yy in range(-2,36,4):
            for xx in range(-2,36,4):
                if r.random()<(.22 if season==3 and i not in [13,19,22] else .015):continue
                x=xx+r.randrange(-1,2);y=yy+r.randrange(-1,2);c=tint(lc,r.randrange(-13,14))
                q.rectangle((x,y,x+4,y+3),fill=c+(255,));q.point((x,y),fill=tint(c,13)+(255,))
                q.point((x+4,y+2),fill=tint(c,-8)+(255,))
        save(im,f'{bid}_leaf_{season}')
        write(A/'models/block'/f'{bid}_leaf_{season}.json',{'parent':'minecraft:block/leaves','render_type':'neoforge:cutout','textures':{'all':f'logos_dimension:block/{bid}_leaf_{season}'}})
    for kind in ['ground','leaf']:
        write(A/'blockstates'/f'{bid}_{kind}.json',{'variants':{f'season={s}':{'model':f'logos_dimension:block/{bid}_{kind}_{s}'} for s in range(4)}})
        write(A/'items'/f'{bid}_{kind}.json',{'model':{'type':'minecraft:model','model':f'logos_dimension:block/{bid}_{kind}_0'}})
        zh[f'block.logos_dimension.{bid}_{kind}']=title+('草皮' if kind=='ground' else '树叶')
        en[f'block.logos_dimension.{bid}_{kind}']=english[i]+(' Turf' if kind=='ground' else ' Leaves')
    zh[f'biome.logos_dimension.{bid}']=title;en[f'biome.logos_dimension.{bid}']=english[i]
    cold=i in [3,13,19,21,22];alpine=i in [7,15,23];ocean=i in [2,20]
    mobs={} # No generated animals, either with the terrain or through ambient spawning.
    # 只有下面这几个群系做大气区分，其余保持默认，避免整个维度到处变色。
    effects={'water_color':'#567d91','grass_color':'#'+g.lower(),'foliage_color':'#'+l.lower()}
    effects.update(ATMOSPHERE.get(bid,{}))
    write(D/'worldgen/biome'/f'{bid}.json',{'has_precipitation':True,'temperature':-.15 if alpine else .15 if cold else .65,'downfall':.75 if i in [1,9,11,18] else .45,'effects':effects,'carvers':['minecraft:cave','minecraft:cave_extra_underground','minecraft:canyon'],'features':[[] for _ in range(11)],'spawners':mobs,'spawn_costs':{}})

materials={
 'journey_limestone':((191,189,169),'stone','石灰岩','Limestone'),
 'journey_lime_plaster':((210,202,178),'stone','石灰泥墙','Lime Plaster'),
 'journey_timber_frame':((210,202,178),'frame','木骨灰泥墙','Timber Frame'),
 'journey_roof_tile':((139,74,58),'tile','红褐陶瓦','Russet Roof Tile'),
 'cloudsea_stem':((108,101,79),'wood','山毛榉原木','Beech Log')}
for name,(c,kind,cn,eng) in materials.items():
    tile(name,c,kind)
    if name!='cloudsea_stem':model(name,cube(f'logos_dimension:block/{name}'))
    else:
        model(name,{'parent':'minecraft:block/cube_column','textures':{'side':f'logos_dimension:block/{name}','end':'minecraft:block/oak_log_top'}})
        write(A/'blockstates'/f'{name}.json',{'variants':{'axis=y':{'model':f'logos_dimension:block/{name}'},'axis=x':{'model':f'logos_dimension:block/{name}','x':90,'y':90},'axis=z':{'model':f'logos_dimension:block/{name}','x':90}}})
    zh[f'block.logos_dimension.{name}']=cn;en[f'block.logos_dimension.{name}']=eng

plants=[('woodland_fern','林下羽叶蕨','Woodland Fern'),('bracken','卷叶蕨丛','Bracken'),('bluebell','垂铃花','Bluebell'),('foxglove','毛地黄','Foxglove'),('lupine','羽扇豆','Lupine'),('meadow_grass','原野牧草','Meadow Grass'),('river_reed','河岸芦苇','River Reed'),('grapevine','葡萄藤架','Grapevine')]
for index,(short,cn,eng) in enumerate(plants):
    name='journey_'+short;im=Image.new('RGBA',(32,32));q=ImageDraw.Draw(im)
    if index in [0,1]:
        # Hand-shaped pinnate fronds, a visible rachis and alternating narrow leaflets.
        q.line((15,30,16,4 if index==1 else 7),fill='#597345',width=1)
        for n in range(2,11):
            y=30-n*2;span=int((1-abs(n-6)/7)*(11 if index==1 else 9))
            for side in [-1,1]:
                q.line((16,y,16+side*span,y-5),fill='#486740',width=2)
                q.line((16,y,16+side*span,y-6),fill='#78915d',width=1)
    elif index==5:
        for n in range(9):
            x=3+n*3;tip=7+(n*7%12);q.line((16,31,x,tip),fill=['#7e905f','#647d4d','#98a16d'][n%3],width=1)
            q.line((16,31,x+2,tip+4),fill='#566e43',width=1)
    elif index==6:
        for x,y in [(8,5),(17,2),(25,9)]:
            q.line((x,31,x,y+6),fill='#809062',width=1);q.rectangle((x-1,y,x+1,y+6),fill='#8c7a52');q.line((x,24,x-5,15),fill='#6d8250',width=1)
    elif index==7:
        q.line((5,31,5,3),fill='#6c5940',width=2);q.line((26,31,26,3),fill='#6c5940',width=2);q.line((4,8,27,8),fill='#6c5940',width=2)
        q.line((9,30,22,6),fill='#597344',width=2)
        for x,y in [(9,23),(17,15),(23,10)]:q.polygon([(x-5,y),(x,y-3),(x+4,y),(x,y+3)],fill='#6e8854');q.ellipse((x,y+1,x+3,y+5),fill='#646078')
    else:
        q.line((16,31,16,3),fill='#607f49',width=1)
        for x,y in [(11,25),(19,20),(12,15)]:q.polygon([(16,y+3),(x-4,y),(x,y-3),(16,y)],fill='#748b57')
        blooms=['#7385b5','#b17d9f','#8a82ac'][index-2]
        for j in range(5 if index==2 else 7):
            y=4+j*3;x=16+(-3 if j%2 else 3)
            q.line((16,y+1,x,y+2),fill='#607f49',width=1);q.ellipse((x-2,y,x+2,y+3),fill=blooms);q.point((x-1,y),fill='#c9bdd2')
    save(im,name)
    def plane(angle=0,tilt=0,x=8,z=8,height=16,width=16):
        e={'from':[x-width/2,0,z],'to':[x+width/2,height,z],'faces':{f:{'texture':'#plant','uv':[0,0,16,16]} for f in ['north','south']}}
        if angle:e['rotation']={'origin':[8,0,8],'axis':'y','angle':angle}
        if tilt:e['rotation']={'origin':[8,0,8],'axis':'x','angle':tilt}
        return e
    elements=[plane(45),plane(-45)]
    if index==0:elements=[plane(0,-22.5,height=12),plane(0,22.5,height=12),plane(45,0,height=13),plane(-45,0,height=11)]
    if index==1:elements=[plane(a,0,height=h) for a,h in [(0,16),(45,15),(-45,14),(22.5,13),(-22.5,12)]]
    if index==5:elements=[plane(a,0,height=h) for a,h in [(0,13),(45,14),(-45,12)]]
    if index==7:elements=[plane(0)]
    model(name,{'parent':'minecraft:block/block','textures':{'plant':f'logos_dimension:block/{name}','particle':f'logos_dimension:block/{name}'},'render_type':'neoforge:cutout','elements':elements})
    zh[f'block.logos_dimension.{name}']=cn;en[f'block.logos_dimension.{name}']=eng
    write(D/'loot_table/blocks'/f'{name}.json',{'type':'minecraft:block','pools':[{'rolls':1,'entries':[{'type':'minecraft:item','name':'logos_dimension:'+name}],'conditions':[{'condition':'minecraft:survives_explosion'}]}]})

# No magic ecology below ground; retain registry IDs as ordinary depth biomes for old saves.
for bid,title in [('cloudsea_root_caves','大陆溶洞'),('cloudsea_demon_depths','深层洞窟')]:
    write(D/'worldgen/biome'/f'{bid}.json',{'has_precipitation':False,'temperature':.5,'downfall':.4,'effects':{'water_color':'#567d91'},'carvers':['minecraft:cave','minecraft:cave_extra_underground','minecraft:canyon'],'features':[[] for _ in range(11)],'spawners':{},'spawn_costs':{}})
    zh[f'biome.logos_dimension.{bid}']=title
write(A/'lang/zh_cn.json',zh);write(A/'lang/en_us.json',en)
dim=D/'dimension/cloudsea.json';spec=json.loads(dim.read_text());spec['generator']['settings']='minecraft:overworld';write(dim,spec)
archive=ROOT.parent/'LogosParent-current/build/moddev/artifacts/minecraft-patched-26.1.2.112-sources.jar'
with zipfile.ZipFile(archive) as z:
    dimension=json.loads(z.read('data/minecraft/dimension_type/overworld.json'))
    day=json.loads(z.read('data/minecraft/timeline/day.json'))
dimension.update(min_y=-128,height=640,logical_height=640,default_clock='logos_dimension:cloudsea',timelines=['logos_dimension:cloudsea'])
dimension['attributes'].update({'minecraft:visual/cloud_height':480.0,'minecraft:visual/sky_color':'#88acd2','minecraft:visual/fog_color':'#bacbd5'})
day['clock']='logos_dimension:cloudsea';write(D/'dimension_type/cloudsea.json',dimension);write(D/'timeline/cloudsea.json',day)
write(RES/'data/minecraft/tags/worldgen/biome/has_structure/mineshaft.json',{'replace':False,'values':['logos_dimension:'+b for b in all_ids]+['logos_dimension:cloudsea_root_caves','logos_dimension:cloudsea_demon_depths']})
test=ROOT/'gametest-packs/crucible/data/minecraft/worldgen/world_preset/flat.json';preset=json.loads(test.read_text());preset['dimensions']['logos_dimension:cloudsea']=spec;write(test,preset)

# Original Internet PNGs encode horizontal layers separated by a cyan one-pixel gutter.
source=ROOT/'third_party/millenaire';palette={}
for line in (source/'blocklist.txt').read_text().splitlines():
    fields=line.split(';')
    if len(fields)>=5:
        try:palette[tuple(map(int,fields[4].split('/')))]=fields[0]
        except ValueError:pass
def state(name,**properties):return {'Name':name,**({'Properties':{k:str(v) for k,v in properties.items()}} if properties else {})}
def block_for(tag):
    low=tag.lower()
    if tag in ['preserveground','allbuttrees','grass']:return None
    if low.startswith('woodstairs') or 'stairs' in low:
        facing='north' if 'top' in low else 'south' if 'bottom' in low else 'west' if 'left' in low else 'east'
        return state('minecraft:brick_stairs' if 'wood' in low else 'minecraft:stone_brick_stairs',facing=facing,half='top' if 'inv' in low else 'bottom',shape='straight',waterlogged='false')
    if low.startswith('log'):return state('minecraft:dark_oak_log',axis='x' if 'hor' in low else 'y')
    if 'colombages cross' in low:return state('logos_dimension:journey_timber_frame')
    if 'colombages' in low or 'wool' in low or 'dirt wall' in low:return state('logos_dimension:journey_lime_plaster')
    if 'glass' in low:return state('minecraft:glass_pane')
    if low.startswith('door'):return state('minecraft:air')
    if 'fencegate' in low:return state('minecraft:oak_fence_gate',facing='east' if 'horizontal' in low else 'north')
    if 'fence' in low:return state('minecraft:oak_fence')
    if 'soil' in low:return state('minecraft:farmland',moisture=7)
    if 'water' in low:return state('minecraft:water',level=0)
    if 'torch' in low:return state('minecraft:torch')
    if 'plank' in low:return state('minecraft:dark_oak_planks')
    if 'slab' in low:return state('minecraft:stone_brick_slab',type='double' if 'double' in low else 'bottom')
    if 'cobble' in low or 'stone' in low:return state('logos_dimension:journey_limestone')
    if 'furnace' in low:return state('minecraft:furnace')
    if 'workbench' in low:return state('minecraft:crafting_table')
    if 'path' in low:return state('minecraft:dirt_path')
    return state('minecraft:air')
def convert(name,out):
    header=(source/(name+'_A.txt')).read_text().splitlines()[0]
    w=int(re.search(r'width:(\d+)',header)[1]);d=int(re.search(r'length:(\d+)',header)[1])
    im=Image.open(source/(name+'_A0.png')).convert('RGB');h=(im.width+1)//(w+1)
    cells=[];doors=[]
    for y in range(h):
        for z in range(d):
            for x in range(w):
                tag=palette.get(im.getpixel((y*(w+1)+x,z)),'empty');value=block_for(tag)
                if tag.lower().startswith('door'):
                    doors.append((x,y,z,'west' if 'left' in tag.lower() else 'east' if 'right' in tag.lower() else 'north'))
                if tag=='planks' and 1<y<5 and (x in [0,w-1] or z in [0,d-1]):
                    value=state('logos_dimension:journey_timber_frame' if x%3==0 or z%4==0 else 'logos_dimension:journey_lime_plaster')
                cells.append(value)
    for x,y,z,facing in doors:
        for dy,half in [(0,'lower'),(1,'upper')]:
            if y+dy<h:cells[((y+dy)*d+z)*w+x]=state('minecraft:dark_oak_door',facing=facing,half=half,hinge='left',open='false',powered='false')
    # An additional timber-framed story makes the dense town visibly different from rural cottages.
    if out.startswith('townhouse'):
        layer=w*d;middle=cells[2*layer:5*layer];stories=3 if out.endswith('four') else 2 if out.endswith('three') else 1;cells=cells[:5*layer]+middle*stories+cells[5*layer:];h+=3*stories
    pal=[];encoded=[]
    for v in cells:
        if v is None:encoded.append(-1);continue
        if v not in pal:pal.append(v)
        encoded.append(pal.index(v))
    write(D/'journey_templates'/f'{out}.json',{'width':w,'height':h,'depth':d,'palette':pal,'blocks':encoded})
for name,out in [('playersmallhouse','cottage'),('bakery','bakery'),('guardhouse','guardhouse'),('inn','inn'),('farm','farm'),('playersmallhouse','townhouse'),('playersmallhouse','townhouse_three'),('playersmallhouse','townhouse_four')]:convert(name,out)
print('Generated 32 temperate biomes, seasonal turf/leaves, eight modeled plants, six adapted blueprints and native sky data.')
