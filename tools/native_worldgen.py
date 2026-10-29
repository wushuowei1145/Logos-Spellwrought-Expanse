"""Author vanilla worldgen JSON and static NBT assets. No Java terrain/feature generators."""
from pathlib import Path
import copy,gzip,json,math,re,runpy,struct,zipfile
from PIL import Image,ImageDraw
ROOT=Path(__file__).resolve().parent.parent
runpy.run_path(str(ROOT/'tools/journey_resources.py'))
R=ROOT/'src/main/resources';D=R/'data/logos_dimension';A=R/'assets/logos_dimension'
def write(path,obj):
    path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(obj,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def state(name,**props):return {'Name':name,**({'Properties':{k:str(v).lower() for k,v in props.items()}} if props else {})}
def provider(name,**props):return {'type':'minecraft:simple_state_provider','state':state(name,**props)}
archive=ROOT.parent/'LogosParent-current/build/moddev/artifacts/minecraft-patched-26.1.2.112-sources.jar'
with zipfile.ZipFile(archive) as z:
    def vanilla(path):return json.loads(z.read('data/minecraft/'+path+'.json'))
    DATA_VERSION=json.loads(z.read('version.json'))['world_version']
    OW=vanilla('worldgen/noise_settings/overworld');LARGE=vanilla('worldgen/noise_settings/large_biomes')
    TREES={n:vanilla('worldgen/configured_feature/'+n) for n in ['oak','birch','spruce','fancy_oak','cherry']}

biomes=re.findall(r'([A-Z_]+)\("([^"]+)",0x([A-F0-9]+),0x([A-F0-9]+)',(ROOT/'src/main/java/dev/logos/dimension/cloudsea/CloudseaBiome.java').read_text(encoding='utf-8'))
ids=['cloudsea_'+b[0].lower() for b in biomes]
conifers={'cloudsea_lilac_steps','cloudsea_cloudroot_shelves','cloudsea_fir_heart'}
birches={'cloudsea_reverse_gardens','cloudsea_ivory_steps','cloudsea_moon_birch'}
cherries={'cloudsea_white_cherry'}
forests=conifers|birches|cherries|{'cloudsea_jade_whirls','cloudsea_verdant_whirls','cloudsea_lantern_shelves','cloudsea_ancient_beech','cloudsea_fernwood','cloudsea_moss_vale','cloudsea_copper_copse'}
barren={ids[i] for i in [2,6,7,15,20,23]}
glades={'cloudsea_sunlit_glade','cloudsea_starflower_glade'}

# Eight different plant silhouettes; flowering, fruiting and dormancy are model states, not new IDs.
plants=[('silver_anemone','银星莲花','Silver Anemone'),('moonbell','月露铃兰','Moonbell'),('glowcap','露灯菌','Dewlight Cap'),('heather','林地石楠','Woodland Heather'),('amber_fern','琥珀鹿角蕨','Amber Staghorn'),('winter_hellebore','冬星嚏根草','Winter Hellebore'),('fireweed','霞穗柳兰','Rosefire Willowherb'),('berry_bramble','林莓灌丛','Woodland Bramble')]
zh=json.loads((A/'lang/zh_cn.json').read_text(encoding='utf-8'));en=json.loads((A/'lang/en_us.json').read_text(encoding='utf-8'))
for i,(name,cn,eng) in enumerate(plants):
    name='journey_'+name
    for season in range(4):
        im=Image.new('RGBA',(32,32));q=ImageDraw.Draw(im)
        green=['#73895a','#526f49','#9e8154','#797e6c'][season];light=['#a2ad7b','#809766','#bf9c64','#aeb6ab'][season]
        flower=['#ece7cd','#a5bec0','#e0c389','#d6e0da'][i%4]
        active=season in ({0},{0,1},{1,2},{1,2},{0,1,2},{0,3},{1},{1,2})[i]
        if i==2:
            for x,y,r in [(9,20,6),(21,13,7),(23,26,4)]:
                q.rectangle((x-1,y+3,x+1,31),fill='#a8ab90');q.pieslice((x-r,y-r//2,x+r,y+r),180,360,fill=flower if active else '#787c6a');q.line((x-r,y+r//4,x+r,y+r//4),fill='#d1d9bd',width=1)
        elif i==4:
            for side in [-1,1]:
                for n in range(3):
                    x=16+side*(4+n*3);y=6+n*4;q.line((16,31,x,y),fill=green,width=2);q.line((x,y+7,x+side*3,y+1),fill=light,width=2)
            q.line((16,31,16,3),fill=light,width=2)
        elif i in [3,7]:
            for n in range(5):
                x=5+n*5;top=7+(n*3%7);q.line((16,31,x,top),fill='#706444',width=1)
                for y in range(top+3,29,5):
                    q.ellipse((x-3,y-2,x+3,y+2),fill=green);q.line((x-2,y-2,x+2,y-2),fill=light)
                    if active:q.ellipse((x-1,y-4,x+2,y-1),fill=('#d1a8b1' if i==3 else '#8e4664'))
        else:
            for n in range(3 if i in [0,5] else 4):
                x=7+n*7;y=8+(n*5%9);q.line((16,31,x,y),fill=green,width=1)
                q.polygon([(15,26),(x-5,19),(x+2,23),(16,29)],fill=green)
                q.polygon([(16,28),(23,18),(27,21),(19,29)],fill=light)
                if active:
                    if i==6:
                        for h in range(4):q.ellipse((x-3,y+h*3,x+3,y+3+h*3),fill='#b68fa3')
                    elif i==1:q.arc((x-3,y-2,x+3,y+6),180,360,fill=flower,width=2);q.line((x-3,y+2,x+3,y+2),fill=flower,width=2)
                    else:
                        for dx,dy in [(-3,0),(3,0),(0,-3),(0,3)]:q.ellipse((x+dx-2,y+dy-2,x+dx+2,y+dy+2),fill=flower)
                        q.point((x,y),fill='#dac37c')
        texture=f'{name}_{season}';im.save(A/'textures/block'/f'{texture}.png')
        planes=[]
        for angle,height,width in [(45,14,14),(-45,14,14)]+([(0,10,13)] if i in [2,4,7] else []):
            planes.append({'from':[8-width/2,0,8],'to':[8+width/2,height,8],'rotation':{'origin':[8,0,8],'axis':'y','angle':angle},'faces':{f:{'texture':'#plant','uv':[0,0,16,16]} for f in ['north','south']}})
        write(A/'models/block'/f'{texture}.json',{'parent':'minecraft:block/block','render_type':'neoforge:cutout','textures':{'plant':'logos_dimension:block/'+texture,'particle':'logos_dimension:block/'+texture},'elements':planes})
    write(A/'blockstates'/f'{name}.json',{'variants':{f'season={s}':{'model':f'logos_dimension:block/{name}_{s}'} for s in range(4)}})
    write(A/'items'/f'{name}.json',{'model':{'type':'minecraft:model','model':f'logos_dimension:block/{name}_0'}})
    write(D/'loot_table/blocks'/f'{name}.json',{'type':'minecraft:block','pools':[{'rolls':1,'entries':[{'type':'minecraft:item','name':'logos_dimension:'+name}]}]})
    zh['block.logos_dimension.'+name]=cn;en['block.logos_dimension.'+name]=eng
for lang,values in [('zh_cn',zh),('en_us',en)]:write(A/'lang'/f'{lang}.json',values)

def surface(bid,top,under):
    return {'type':'minecraft:condition','if_true':{'type':'minecraft:biome','biome_is':['logos_dimension:'+bid]},'then_run':{'type':'minecraft:sequence','sequence':[
        {'type':'minecraft:condition','if_true':{'type':'minecraft:stone_depth','offset':0,'add_surface_depth':False,'secondary_depth_range':0,'surface_type':'floor'},'then_run':{'type':'minecraft:block','result_state':state(top)}},
        {'type':'minecraft:condition','if_true':{'type':'minecraft:stone_depth','offset':4,'add_surface_depth':False,'secondary_depth_range':0,'surface_type':'floor'},'then_run':{'type':'minecraft:block','result_state':state(under)}}]}}
def settings(name,base,materials,default='minecraft:stone'):
    data=copy.deepcopy(base);data['ore_veins_enabled']=False;data['default_block']=state(default)
    bedrock={'type':'minecraft:condition','if_true':{'type':'minecraft:vertical_gradient','random_name':'minecraft:bedrock_floor','true_at_and_below':{'above_bottom':0},'false_at_and_above':{'above_bottom':5}},'then_run':{'type':'minecraft:block','result_state':state('minecraft:bedrock')}}
    data['surface_rule']={'type':'minecraft:sequence','sequence':[bedrock]+[surface(*m) for m in materials]}
    write(D/'worldgen/noise_settings'/f'{name}.json',data)

def point(bid,t,h,c,e,w,depth=0):return {'biome':'logos_dimension:'+bid,'parameters':{'temperature':t,'humidity':h,'continentalness':c,'erosion':e,'weirdness':w,'depth':depth,'offset':0}}
climate=[]
for ti,t in enumerate([-.8,-.4,0,.4,.8]):
 for hi,h in enumerate([-.8,-.4,0,.4,.8]):
  for ci,c in enumerate([-.6,-.08,.3,.75]):
   for ei,e in enumerate([-.65,.15,.65]):
    for wi,w in enumerate([-.8,-.35,0,.35,.8]):
     if ci==0:bid='tide_ribbons' if ti>1 else 'pearl_basins'
     elif ci==1:bid='opal_dunes' if hi<3 else 'glass_lagoons'
     elif ei==0 and ci==3 and abs(w)>.3:bid='frost_spires' if ti<2 else 'pale_spires' if ti==2 else 'halo_spires'
     elif ti==0:bid='fir_heart' if hi>1 else 'lilac_steps' if hi==1 else 'aurora_dunes'
     elif wi==2 and ei>0 and hi>0:bid='starflower_glade' if hi>2 else 'sunlit_glade'
     elif hi>=1 and ei==1 and ti in [2,3]:bid='white_cherry'  # 山脚：中等侵蚀的坡地
     elif hi>=1 and ei==0 and ti==4 and wi==2:bid='white_cherry'  # 温山山顶
     elif hi>=1:
      if ti==1:bid=['ivory_steps','reverse_gardens','moon_birch'][ei]
      elif hi==4:bid='moss_vale' if ei==2 else 'fernwood'
      elif ti==2:bid='cloudroot_shelves' if ei==0 else 'ancient_beech' if wi%2 else 'jade_whirls'
      elif ti==3:bid='lantern_shelves' if hi==3 else 'verdant_whirls' if wi%2 else 'copper_copse'
      else:bid='jade_whirls' if hi>1 else 'copper_copse'
     else:
      # 原版平原之所以看起来平，是因为它们被分给了"侵蚀程度最高（地形最平）"的地方；
      # 这里照做：草原群系只占侵蚀 0.65 的格子，缓坡给牧草坡地，陡坡给旷野丘陵。
      if ei==2:bid=['blue_bell_steps','silk_folds','rose_dunes'][(ti+wi)%3]
      elif ei==1:bid='amber_whirls'
      else:bid='dawn_ribbons'
     if ci==2 and ei==2 and hi==4 and ti>1:bid='dream_basins'
     if ci==2 and ei==0 and wi==2:bid='echo_folds' if ti<2 else 'moon_folds'
     if ci==2 and ei==2 and wi in [0,4] and hi==3:bid='star_ribbons'
     climate.append(point('cloudsea_'+bid,t,h,c,e,w))
climate.append(point('cloudsea_root_caves',[-1.2,1.2],[-1.2,1.2],[-1.2,1.2],[-1.2,1.2],[-1.2,1.2],[.35,1.5]))
cloud={'type':'logos_dimension:cloudsea','generator':{'type':'minecraft:noise','settings':'logos_dimension:journey','biome_source':{'type':'minecraft:multi_noise','biomes':climate}}}
write(D/'dimension/cloudsea.json',cloud)
materials=[]
for bid in ids:
    top='minecraft:sand' if bid in {ids[2],ids[20]} else 'logos_dimension:journey_limestone' if bid==ids[6] else 'minecraft:stone' if bid==ids[7] else 'minecraft:snow_block' if bid in {ids[15],ids[23]} else 'logos_dimension:'+bid+'_ground'
    materials.append((bid,top,'minecraft:dirt'))
settings('journey',OW,materials)

alchemy=[]
for t in [-.8,-.4,0,.4,.8]:
 for h in [-.8,-.4,0,.4,.8]:
  bid='sulfur' if t>=.4 and h<=-.4 else 'mercury' if t<=-.4 and h>=.4 else 'salt' if t==0 and h<=-.4 else 'aether'
  alchemy.append(point(bid,t,h,[-1.2,1.2],[-1.2,1.2],[-1.2,1.2]))
crucible={'type':'logos_dimension:crucible','generator':{'type':'minecraft:noise','settings':'logos_dimension:crucible','biome_source':{'type':'minecraft:multi_noise','biomes':alchemy}}}
write(D/'cauldron/crucible.json',crucible)
(D/'dimension/crucible.json').unlink(missing_ok=True)
settings('crucible',LARGE,[('aether','logos_dimension:aether_turf','logos_dimension:aether_soil'),('sulfur','logos_dimension:sulfur_deposit','logos_dimension:charred_stone'),('mercury','logos_dimension:mercury_crust','logos_dimension:mercury_metal'),('salt','logos_dimension:salt_crust','logos_dimension:strata_stone'),('sanctum','logos_dimension:island_stone','logos_dimension:island_stone')],'logos_dimension:strata_stone')

def configured(name,data):write(D/'worldgen/configured_feature'/f'{name}.json',data)
def outside_island():
    return {'type':'minecraft:block_predicate_filter','predicate':{'type':'minecraft:not','predicate':{'type':'minecraft:matching_blocks','offset':[0,-1,0],'blocks':['logos_dimension:island_stone']}}}

def placed(name,feature,count,height='MOTION_BLOCKING_NO_LEAVES'):
    modifiers=[{'type':'minecraft:count','count':count},{'type':'minecraft:in_square'}]
    if height=='OCEAN_FLOOR_WG':modifiers.append({'type':'minecraft:surface_water_depth_filter','max_water_depth':0})
    modifiers.extend([{'type':'minecraft:heightmap','heightmap':height},outside_island(),{'type':'minecraft:biome'}])
    write(D/'worldgen/placed_feature'/f'{name}.json',{'feature':'logos_dimension:'+feature,'placement':modifiers})
def plant_feature(name,entries,count):
    configured(name,{'type':'minecraft:simple_block','config':{'to_place':{'type':'minecraft:weighted_state_provider','entries':[{'data':state(block,**props),'weight':weight} for block,props,weight in entries]}}})
    placed(name,name,count)
    p=D/'worldgen/placed_feature'/f'{name}.json';spec=json.loads(p.read_text());spec['placement'].insert(-1,{'type':'minecraft:block_predicate_filter','predicate':{'type':'minecraft:matching_blocks','blocks':['minecraft:air']}});write(p,spec)
plant_feature('woodland_floor',[('logos_dimension:journey_woodland_fern',{},20),('logos_dimension:journey_bracken',{},8),('minecraft:moss_carpet',{},20),('minecraft:leaf_litter',{},22),('logos_dimension:journey_berry_bramble',{'season':0},5),('logos_dimension:journey_glowcap',{'season':0},3),('logos_dimension:journey_amber_fern',{'season':0},3)],640)
# Scan through branches to actual soil; a heightmap alone can stop at woody canopy.
floor_path=D/'worldgen/placed_feature/woodland_floor.json'
floor_spec=json.loads(floor_path.read_text());floor_spec['placement'][3:3]=[
    {'type':'minecraft:environment_scan','direction_of_search':'down','max_steps':32,'target_condition':{'type':'minecraft:matching_block_tag','tag':'minecraft:dirt'}},
    {'type':'minecraft:random_offset','xz_spread':0,'y_spread':1}]
write(floor_path,floor_spec)
plant_feature('meadow_floor',[('logos_dimension:journey_meadow_grass',{},65),('logos_dimension:journey_meadow_grass_young',{},12),('logos_dimension:journey_bluebell',{},8),('minecraft:oxeye_daisy',{},4)]+[('logos_dimension:journey_'+p[0],{'season':0},4) for p in plants],360)

# 森林地带各群系的地表植物群落。共享的 woodland_floor / meadow_floor 保留为基准定义
# （其它群系与旧存档仍引用它），森林地带改用各自的 <biome>_floor：同样的生成阶段与
# 尝试次数，只把组合与权重换成本群系自己的，让林地、林隙和精灵森林各有辨识度。
SEASONAL_BLOCKS={'journey_silver_anemone','journey_moonbell','journey_glowcap','journey_heather','journey_amber_fern','journey_winter_hellebore','journey_fireweed','journey_berry_bramble'}
FOREST_FLOORS={
 # 樱花组：白樱林——落瓣与白花铺地，草只作底。
 'cloudsea_white_cherry':(640,[('minecraft:leaf_litter',30),('logos_dimension:journey_meadow_grass',24),('minecraft:oxeye_daisy',16),('logos_dimension:journey_silver_anemone',14),('minecraft:short_grass',12),('logos_dimension:journey_meadow_grass_young',8),('logos_dimension:journey_heather',4)]),
 # 针叶组：紫丁香阶地、云根层台、杉影深林
 'cloudsea_lilac_steps':(640,[('logos_dimension:journey_silver_anemone',20),('logos_dimension:journey_bluebell',16),('logos_dimension:journey_woodland_fern',18),('minecraft:moss_carpet',14),('minecraft:leaf_litter',10),('logos_dimension:journey_glowcap',6),('logos_dimension:journey_stardew',2)]),
 'cloudsea_cloudroot_shelves':(640,[('minecraft:moss_carpet',26),('logos_dimension:journey_woodland_fern',18),('logos_dimension:journey_lumen_fern',8),('logos_dimension:journey_glowcap',8),('logos_dimension:journey_bracken',8),('logos_dimension:journey_berry_bramble',8),('minecraft:leaf_litter',8)]),
 'cloudsea_fir_heart':(640,[('logos_dimension:journey_bracken',26),('minecraft:moss_carpet',22),('logos_dimension:journey_berry_bramble',18),('minecraft:leaf_litter',14),('logos_dimension:journey_woodland_fern',8),('logos_dimension:journey_amber_fern',4),('logos_dimension:journey_glowcap',2),('logos_dimension:journey_lumen_fern',1)]),
 # 白桦组：逆生庭、象牙阶地、月露白桦林
 'cloudsea_reverse_gardens':(640,[('logos_dimension:journey_heather',20),('logos_dimension:journey_woodland_fern',18),('logos_dimension:journey_silver_anemone',16),('minecraft:moss_carpet',16),('minecraft:leaf_litter',14)]),
 'cloudsea_ivory_steps':(640,[('minecraft:leaf_litter',26),('logos_dimension:journey_woodland_fern',20),('minecraft:moss_carpet',16),('logos_dimension:journey_winter_hellebore',12),('logos_dimension:journey_moonbell',8),('logos_dimension:journey_glowcap',2)]),
 'cloudsea_moon_birch':(640,[('logos_dimension:journey_moonbell',22),('logos_dimension:journey_winter_hellebore',16),('logos_dimension:journey_silver_anemone',16),('logos_dimension:journey_woodland_fern',14),('minecraft:moss_carpet',10),('minecraft:leaf_litter',10),('logos_dimension:journey_stardew',3),('logos_dimension:journey_amber_fern',2)]),
 # 阔叶组：翠玉旋涡、青翠旋涡、灯焰层台、古冠山毛榉林、翳蕨密林、雾苔溪林、铜叶林丘
 'cloudsea_jade_whirls':(640,[('logos_dimension:journey_bracken',26),('logos_dimension:journey_woodland_fern',22),('minecraft:moss_carpet',16),('logos_dimension:journey_berry_bramble',12),('logos_dimension:journey_amber_fern',8)]),
 'cloudsea_verdant_whirls':(640,[('logos_dimension:journey_woodland_fern',30),('logos_dimension:journey_meadow_grass',16),('minecraft:moss_carpet',14),('logos_dimension:journey_bluebell',12),('logos_dimension:journey_berry_bramble',12),('minecraft:leaf_litter',10)]),
 'cloudsea_lantern_shelves':(640,[('logos_dimension:journey_glowcap',20),('minecraft:moss_carpet',18),('logos_dimension:journey_woodland_fern',16),('logos_dimension:journey_lumen_fern',14),('minecraft:leaf_litter',10),('logos_dimension:journey_stardew',6),('logos_dimension:journey_amber_fern',4)]),
 'cloudsea_ancient_beech':(640,[('minecraft:leaf_litter',30),('minecraft:moss_carpet',16),('logos_dimension:journey_woodland_fern',12),('logos_dimension:journey_berry_bramble',8),('logos_dimension:journey_bracken',6),('logos_dimension:journey_glowcap',2),('logos_dimension:journey_amber_fern',2),('logos_dimension:journey_stardew',1)]),
 'cloudsea_fernwood':(640,[('logos_dimension:journey_woodland_fern',44),('logos_dimension:journey_bracken',20),('minecraft:leaf_litter',10),('minecraft:moss_carpet',8),('logos_dimension:journey_amber_fern',6),('logos_dimension:journey_berry_bramble',4),('logos_dimension:journey_glowcap',2),('logos_dimension:journey_lumen_fern',1)]),
 'cloudsea_moss_vale':(640,[('minecraft:moss_carpet',38),('logos_dimension:journey_glowcap',12),('logos_dimension:journey_woodland_fern',12),('minecraft:leaf_litter',12),('logos_dimension:journey_lumen_fern',8),('logos_dimension:journey_berry_bramble',4),('logos_dimension:journey_bracken',4),('logos_dimension:journey_stardew',3)]),
 'cloudsea_copper_copse':(640,[('logos_dimension:journey_heather',24),('minecraft:leaf_litter',22),('logos_dimension:journey_berry_bramble',20),('logos_dimension:journey_woodland_fern',12),('logos_dimension:journey_amber_fern',8),('minecraft:moss_carpet',8),('logos_dimension:journey_bracken',4),('logos_dimension:journey_stardew',1)]),
 # 林隙：曦光林隙、星铃花甸（保留草甸的 360 次预算，只换组合）
 'cloudsea_sunlit_glade':(360,[('logos_dimension:journey_meadow_grass',50),('logos_dimension:journey_meadow_grass_young',14),('minecraft:oxeye_daisy',12),('logos_dimension:journey_bluebell',10),('logos_dimension:journey_silver_anemone',8),('logos_dimension:journey_heather',5),('logos_dimension:journey_fireweed',6),('logos_dimension:journey_glowcap',3),('logos_dimension:journey_stardew',2)]),
 'cloudsea_starflower_glade':(360,[('logos_dimension:journey_meadow_grass_young',16),('logos_dimension:journey_silver_anemone',26),('logos_dimension:journey_moonbell',22),('logos_dimension:journey_meadow_grass',22),('logos_dimension:journey_bluebell',16),('logos_dimension:journey_glowcap',6),('logos_dimension:journey_stardew',3)]),
 # 精灵森林：星露草与萤灯蕨为主，配合铃兰、银星莲与苔
 'elven_forest':(640,[('logos_dimension:journey_stardew',20),('logos_dimension:journey_lumen_fern',16),('logos_dimension:journey_silver_anemone',14),('logos_dimension:journey_moonbell',12),('minecraft:moss_carpet',12),('logos_dimension:journey_woodland_fern',10),('logos_dimension:journey_glowcap',10),('minecraft:leaf_litter',6)]),
}
def floor_entries(pairs):
    entries=[]
    for block,weight in pairs:
        short=block.split(':')[-1]
        entries.append((block,{'season':0} if short in SEASONAL_BLOCKS else {},weight))
    return entries
def forest_floor(name,entries,count):
    plant_feature(name,entries,count)
    path=D/'worldgen/placed_feature'/f'{name}.json';spec=json.loads(path.read_text(encoding='utf-8'))
    spec['placement'][3:3]=[
        {'type':'minecraft:environment_scan','direction_of_search':'down','max_steps':32,'target_condition':{'type':'minecraft:matching_block_tag','tag':'minecraft:dirt'}},
        {'type':'minecraft:random_offset','xz_spread':0,'y_spread':1}]
    write(path,spec)
for bid,(count,pairs) in FOREST_FLOORS.items():
    forest_floor(bid+'_floor',floor_entries(pairs),count)

# 平原群系的地表组合：与林地分开维护，一个一个群系做，避免一次改完分不清哪个
# 群系的地表出了问题。长风草原以"风里的长草"为主：长草压过其它，野花只作点缀。
PLAINS_FLOORS={
 'cloudsea_silk_folds':(420,[('minecraft:tall_grass',48),('minecraft:short_grass',12),('minecraft:short_grass',14),('logos_dimension:journey_meadow_grass_young',8),('minecraft:oxeye_daisy',7),('logos_dimension:journey_fireweed',6),('logos_dimension:journey_silver_anemone',5),('logos_dimension:journey_heather',4)]),
 # 野花草甸：野花压过草；原矮草位置改用原版短草，保留原有权重。
 'cloudsea_rose_dunes':(460,[('logos_dimension:journey_bluebell',18),('logos_dimension:journey_silver_anemone',16),('minecraft:oxeye_daisy',14),('minecraft:short_grass',20),('logos_dimension:journey_fireweed',8),('logos_dimension:journey_stardew',6),('logos_dimension:journey_heather',6),('minecraft:short_grass',8)]),
 # 旷野丘陵：起伏草坡，草更短更碎，夹着砾石与卷叶蕨，野花很少。
 'cloudsea_dawn_ribbons':(500,[('minecraft:short_grass',34),('minecraft:short_grass',22),('logos_dimension:journey_bracken',14),('logos_dimension:journey_meadow_grass',10),('minecraft:gravel',5),('logos_dimension:journey_heather',6),('minecraft:oxeye_daisy',4),('logos_dimension:journey_meadow_grass_young',5)]),
}
for bid,(count,pairs) in PLAINS_FLOORS.items():
    forest_floor(bid+'_floor',floor_entries(pairs),count)

# 倒木：用原版 minecraft:fallen_tree，树干取所在群系自己的原木。
# 林地面积大、树多，倒木本来就该常见；沙丘、尖塔等非林地不加。
def fallen_tree(name,log):
    configured(name,{'type':'minecraft:fallen_tree','config':{
        'trunk_provider':provider('logos_dimension:'+log,axis='y'),
        'log_length':{'type':'minecraft:uniform','max_inclusive':7,'min_inclusive':4},
        'stump_decorators':[],'log_decorators':[]}})
    write(D/'worldgen/placed_feature'/f'{name}.json',{'feature':'logos_dimension:'+name,'placement':[
        {'type':'minecraft:rarity_filter','chance':6},
        {'type':'minecraft:in_square'},
        {'type':'minecraft:heightmap','heightmap':'MOTION_BLOCKING_NO_LEAVES'},
        {'type':'minecraft:environment_scan','direction_of_search':'down','max_steps':32,'target_condition':{'type':'minecraft:matching_block_tag','tag':'minecraft:dirt'}},
        {'type':'minecraft:random_offset','xz_spread':0,'y_spread':1},
        outside_island(),
        {'type':'minecraft:block_predicate_filter','predicate':{'type':'minecraft:matching_blocks','blocks':['minecraft:air']}},
        {'type':'minecraft:biome'}]})
for bid in sorted(forests):
    fallen_tree('fallen_tree_'+bid,bid+'_log')

# 林地地下：荧光苔。用原版 multiface_growth，只长在地表以下 13 格以下的洞壁上。
# 一份特征被 13 个林地共用，不按群系拆分，避免再多出十几份几乎相同的文件。
CAVERN_BLOCKS=['minecraft:stone','minecraft:deepslate','logos_dimension:journey_limestone','logos_dimension:island_stone','logos_dimension:strata_stone']
configured('forest_cave_lichen',{'type':'minecraft:multiface_growth','config':{
    'block':'minecraft:glow_lichen','can_be_placed_on':CAVERN_BLOCKS,
    'can_place_on_ceiling':True,'can_place_on_floor':False,'can_place_on_wall':True,
    'chance_of_spreading':.5,'search_range':20}})
write(D/'worldgen/placed_feature/forest_cave_lichen.json',{'feature':'logos_dimension:forest_cave_lichen','placement':[
    {'type':'minecraft:count','count':{'type':'minecraft:uniform','max_inclusive':220,'min_inclusive':160}},
    {'type':'minecraft:height_range','height':{'type':'minecraft:uniform','max_inclusive':{'absolute':96},'min_inclusive':{'above_bottom':0}}},
    {'type':'minecraft:in_square'},
    {'type':'minecraft:surface_relative_threshold_filter','heightmap':'OCEAN_FLOOR_WG','max_inclusive':-13},
    {'type':'minecraft:biome'}]})

# 林地地下的苔藓地面：原版 block_pile + moss_block，只在地下、有支撑才放。
# 和荧光苔一起构成"潮湿洞穴"的观感：脚下是苔堆，墙上是发光苔。
configured('forest_cave_moss',{'type':'minecraft:block_pile','config':{
    'state_provider':{'type':'minecraft:simple_state_provider','state':{'Name':'minecraft:moss_block'}}}})
write(D/'worldgen/placed_feature/forest_cave_moss.json',{'feature':'logos_dimension:forest_cave_moss','placement':[
    {'type':'minecraft:count','count':{'type':'minecraft:uniform','max_inclusive':14,'min_inclusive':6}},
    {'type':'minecraft:height_range','height':{'type':'minecraft:uniform','max_inclusive':{'absolute':96},'min_inclusive':{'above_bottom':0}}},
    {'type':'minecraft:in_square'},
    {'type':'minecraft:surface_relative_threshold_filter','heightmap':'OCEAN_FLOOR_WG','max_inclusive':-13},
    {'type':'minecraft:block_predicate_filter','predicate':{'type':'minecraft:matching_blocks','blocks':['minecraft:air']}},
    {'type':'minecraft:biome'}]})

# 林地蘑菇：每种一份 simple_block 特征，按群系分配到植被阶段。
# 稀有度分开：食用的三种常见，有毒的三种稀有；位置仍走原版的高度图 + 空中 + 可存活判定。
MUSHROOM_BIOMES={
 'journey_field_mushroom':(24,14,['cloudsea_ancient_beech','cloudsea_verdant_whirls','cloudsea_jade_whirls','cloudsea_sunlit_glade','cloudsea_starflower_glade']),
 'journey_chanterelle':(32,10,['cloudsea_moon_birch','cloudsea_ivory_steps','cloudsea_reverse_gardens','cloudsea_ancient_beech']),
 'journey_bolete':(32,10,['cloudsea_fir_heart','cloudsea_cloudroot_shelves','cloudsea_lilac_steps','cloudsea_moss_vale']),
 'journey_fly_agaric':(96,5,['cloudsea_moon_birch','cloudsea_fir_heart','cloudsea_ivory_steps']),
 'journey_death_cap':(128,4,['cloudsea_moss_vale','cloudsea_fernwood']),
 'journey_false_chanterelle':(96,5,['cloudsea_copper_copse','cloudsea_ancient_beech'])}
MUSHROOM_FOR={}
for name,(chance,count,biome_ids) in MUSHROOM_BIOMES.items():
    configured(name,{'type':'minecraft:simple_block','config':{'to_place':{'type':'minecraft:simple_state_provider',
        'state':state('logos_dimension:'+name)}}})
    write(D/'worldgen/placed_feature'/f'{name}.json',{'feature':'logos_dimension:'+name,'placement':[
        {'type':'minecraft:rarity_filter','chance':chance},
        {'type':'minecraft:in_square'},
        {'type':'minecraft:heightmap','heightmap':'MOTION_BLOCKING_NO_LEAVES'},
        {'type':'minecraft:biome'},
        {'type':'minecraft:count','count':count},
        {'type':'minecraft:random_offset','xz_spread':{'type':'minecraft:trapezoid','max':5,'min':-5,'plateau':0},'y_spread':{'type':'minecraft:trapezoid','max':1,'min':-1,'plateau':0}},
        {'type':'minecraft:block_predicate_filter','predicate':{'type':'minecraft:matching_block_tag','tag':'minecraft:air'}},
        {'type':'minecraft:block_predicate_filter','predicate':{'type':'minecraft:would_survive','state':state('logos_dimension:'+name)}}]})
    for bid in biome_ids:MUSHROOM_FOR.setdefault(bid,[]).append('logos_dimension:'+name)

# 林地岩石露头：原版 block_pile，用本附属的石灰岩堆成一小簇。
# 一份特征被 13 个林地共用；非林地不加，避免整片大陆到处冒石头。
configured('forest_rock_outcrop',{'type':'minecraft:block_pile','config':{
    'state_provider':{'type':'minecraft:simple_state_provider','state':{'Name':'logos_dimension:journey_limestone'}}}})
write(D/'worldgen/placed_feature/forest_rock_outcrop.json',{'feature':'logos_dimension:forest_rock_outcrop','placement':[
    {'type':'minecraft:rarity_filter','chance':8},
    {'type':'minecraft:in_square'},
    {'type':'minecraft:heightmap','heightmap':'MOTION_BLOCKING_NO_LEAVES'},
    outside_island(),
    {'type':'minecraft:block_predicate_filter','predicate':{'type':'minecraft:matching_blocks','blocks':['minecraft:air']}},
    {'type':'minecraft:biome'}]})
PLAINS=set(PLAINS_FLOORS)
for bid in ids:
    data=json.loads((D/'worldgen/biome'/f'{bid}.json').read_text());features=[[] for _ in range(11)]
    if bid in PLAINS:
        # 平原第一次试玩的反馈：地面不该长树、也不该被峡谷切出沟壑。
        # 树林不生成，峡谷 carver 去掉（洞穴保留，地下仍然可探索）。
        data['carvers']=['minecraft:cave']
        features[9]=['logos_dimension:'+bid+'_floor']
    elif bid not in barren:
        tree=copy.deepcopy(TREES['spruce' if bid in conifers else 'birch' if bid in birches else 'cherry' if bid in cherries else 'fancy_oak' if bid in forests else 'oak'])
        config=tree['config'];config['foliage_provider']=provider('logos_dimension:'+bid+'_leaf',distance=7,persistent=False,waterlogged=False,season=0)
        if 'base_height' in config['trunk_placer']:
            config['trunk_placer']['base_height']=9 if bid in conifers else 8 if bid in forests else 5
        config['trunk_provider']=provider('logos_dimension:'+bid+'_log',axis='y')
        if config['foliage_placer']['type']=='minecraft:blob_foliage_placer':config['foliage_placer']['radius']=3
        configured('trees_'+bid,tree);placed('trees_'+bid,'trees_'+bid,12 if bid in forests else 0 if bid in glades else 1,'OCEAN_FLOOR_WG')
        features[9]=['logos_dimension:trees_'+bid,'logos_dimension:'+(bid+'_floor' if bid in FOREST_FLOORS or bid in PLAINS_FLOORS else 'meadow_floor')]
        if bid in forests:features[9].append('logos_dimension:fallen_tree_'+bid)
        # 蘑菇：按群系各自分配（食用常见、有毒稀有）。
        # 洞穴荧光苔放在地下装饰阶段；只给有树的林地。
        if bid in forests:features[7].extend(['logos_dimension:forest_cave_lichen','logos_dimension:forest_cave_moss'])
        # 水边：直接引用原版的水边特征（甘蔗与睡莲），它们自带"必须挨着水"的判定，
        # 所以在没有水的地方什么都不会发生，不必为附属另写一套。
        if bid in forests:features[9].extend(['minecraft:patch_sugar_cane','minecraft:patch_waterlily'])
        # 岩石露头：只给有树的林地。
        if bid in forests:features[9].append('logos_dimension:forest_rock_outcrop')
        # 蘑菇放在最后：同一份特征在不同群系里的相对顺序必须一致，否则 FeatureSorter
        # 会报 "Feature order cycle found" 并直接让区块生成失败。精灵森林的 step9 是
        # [树, 地表, 倒木, 光林, 甘蔗, 睡莲, 露头, 白伞菇]，这里必须保持同样的先后。
        features[9].extend(MUSHROOM_FOR.get(bid,[]))
    data['features']=features;write(D/'worldgen/biome'/f'{bid}.json',data)
for bid in ['aether','sulfur','mercury','salt','sanctum']:
    data=json.loads((D/'worldgen/biome'/f'{bid}.json').read_text());data['carvers']=['minecraft:cave','minecraft:canyon'];data['features']=[[] for _ in range(11)]
    if bid=='aether':
        tree=copy.deepcopy(TREES['oak']);tree['config']['trunk_provider']=provider('logos_dimension:fan_trunk',axis='y');tree['config']['foliage_provider']=provider('logos_dimension:fan_leaves');tree['config']['foliage_placer']['radius']=3
        configured('aether_trees',tree);placed('aether_trees','aether_trees',4,'OCEAN_FLOOR_WG')
        plant_feature('aether_flora',[('logos_dimension:aether_fern',{},5),('logos_dimension:star_blossom',{},1)],140);data['features'][9]=['logos_dimension:aether_trees','logos_dimension:aether_flora']
    elif bid in ['mercury','sulfur']:
        liquid='mercury' if bid=='mercury' else 'molten_glass'
        configured(bid+'_lake',{'type':'minecraft:lake','config':{'fluid':provider('logos_dimension:'+liquid),'barrier':provider('logos_dimension:mercury_metal' if bid=='mercury' else 'logos_dimension:charred_stone')}})
        write(D/'worldgen/placed_feature'/f'{bid}_lake.json',{'feature':'logos_dimension:'+bid+'_lake','placement':[{'type':'minecraft:rarity_filter','chance':6},{'type':'minecraft:in_square'},{'type':'minecraft:heightmap','heightmap':'WORLD_SURFACE_WG'},outside_island(),{'type':'minecraft:biome'}]});data['features'][1]=['logos_dimension:'+bid+'_lake']
    elif bid=='salt':plant_feature('salt_crystals',[('logos_dimension:salt_crystal',{'natural':True,'phase':0},1)],7);data['features'][9]=['logos_dimension:salt_crystals']
    write(D/'worldgen/biome'/f'{bid}.json',data)

# Native tags let vanilla saplings, carvers and tree placers understand the custom palette.
def tag(name,values):write(R/'data/minecraft/tags/block'/f'{name}.json',{'replace':False,'values':values})
tag('dirt',['logos_dimension:'+b+'_ground' for b in ids]+['logos_dimension:aether_turf','logos_dimension:aether_soil'])
tag('supports_vegetation',['logos_dimension:'+b+'_ground' for b in ids]+['logos_dimension:aether_turf','logos_dimension:aether_soil'])
tag('leaves',['logos_dimension:'+b+'_leaf' for b in ids]+['logos_dimension:fan_leaves'])
tag('logs',['logos_dimension:cloudsea_stem','logos_dimension:fan_trunk']+['logos_dimension:'+b+'_log' for b in ids])
tag('logs_that_burn',['logos_dimension:cloudsea_stem','logos_dimension:fan_trunk']+['logos_dimension:'+b+'_log' for b in ids])
tag('mineable/axe',['logos_dimension:cloudsea_stem','logos_dimension:fan_trunk']+['logos_dimension:'+b+'_log' for b in ids])
tag('replaceable_by_trees',['logos_dimension:journey_'+p[0] for p in plants]+['logos_dimension:journey_woodland_fern','logos_dimension:journey_bracken','logos_dimension:journey_meadow_grass','logos_dimension:aether_fern','logos_dimension:star_blossom'])
tag('overworld_carver_replaceables',['logos_dimension:strata_stone','logos_dimension:charred_stone','logos_dimension:mercury_metal'])

# Standard structure-template NBT writer: these are authored assets placed by minecraft:jigsaw.
def string(s):b=s.encode();return struct.pack('>H',len(b))+b
def kind(v):return 8 if isinstance(v,str) else 10 if isinstance(v,dict) else 9 if isinstance(v,list) else 3
def payload(v):
    if isinstance(v,str):return string(v)
    if isinstance(v,dict):return b''.join(bytes([kind(x)])+string(k)+payload(x) for k,x in v.items())+b'\0'
    if isinstance(v,list):return bytes([kind(v[0]) if v else 10])+struct.pack('>i',len(v))+b''.join(payload(x) for x in v)
    return struct.pack('>i',v)
def nbt(name,size,cells):
    palette=[];lookup={};blocks=[]
    for pos,block in cells.items():
        key=json.dumps(block,sort_keys=True);index=lookup.get(key)
        if index is None:index=len(palette);lookup[key]=index;palette.append(block)
        blocks.append({'pos':list(pos),'state':index})
    # Anchor aligns the centre of a template to the native jigsaw start point.
    anchor=[size[0]//2,size[1]-1,size[2]//2];jigsaw=state('minecraft:jigsaw',orientation='north_up');palette.append(jigsaw)
    blocks=[b for b in blocks if b['pos']!=anchor];blocks.append({'pos':anchor,'state':len(palette)-1,'nbt':{'id':'minecraft:jigsaw','name':'logos_dimension:anchor','target':'minecraft:empty','pool':'minecraft:empty','joint':'rollable','final_state':'logos_dimension:island_stone' if name=='crucible_island' else 'minecraft:air'}})
    doc={'DataVersion':DATA_VERSION,'size':size,'palette':palette,'blocks':blocks,'entities':[]};path=D/'structure'/f'{name}.nbt';path.parent.mkdir(parents=True,exist_ok=True)
    with path.open('wb') as output:
        with gzip.GzipFile(fileobj=output,mode='wb',mtime=0) as g:g.write(b'\x0a\0\0'+payload(doc))
def structure(name,biomes,height,spacing=None,anchor=False):
    pool={'name':'logos_dimension:'+name,'fallback':'minecraft:empty','elements':[{'weight':1,'element':{'element_type':'minecraft:single_pool_element','location':'logos_dimension:'+name,'processors':'minecraft:empty','projection':'rigid'}}]}
    write(D/'worldgen/template_pool'/f'{name}.json',pool)
    spec={'type':'minecraft:jigsaw','biomes':['logos_dimension:'+b for b in biomes],'step':'surface_structures','spawn_overrides':{},'terrain_adaptation':'none' if anchor else 'beard_thin','start_pool':'logos_dimension:'+name,'size':1,'start_height':{'absolute':height},'max_distance_from_center':128 if anchor else 112,'use_expansion_hack':False}
    if anchor:spec['start_jigsaw_name']='logos_dimension:anchor';spec['spawn_overrides']={m:{'bounding_box':'full','spawns':[]} for m in ['monster','creature','ambient','water_creature','water_ambient']}
    else:spec['project_start_to_heightmap']='WORLD_SURFACE_WG'
    write(D/'worldgen/structure'/f'{name}.json',spec)
    placement={'type':'minecraft:concentric_rings','count':1,'distance':0,'spread':1,'preferred_biomes':[],'salt':7403} if anchor else {'type':'minecraft:random_spread','spacing':spacing,'separation':spacing-24,'salt':7403+spacing}
    write(D/'worldgen/structure_set'/f'{name}.json',{'structures':[{'structure':'logos_dimension:'+name,'weight':1}],'placement':placement})
cells={}
for y in range(65):
    radius=63*pow((y+1)/65,.63)
    for z in range(128):
        for x in range(128):
            if (x-64)**2+(z-64)**2<=radius*radius:cells[x,y,z]=state('logos_dimension:island_stone')
nbt('crucible_island',[128,65,128],cells);structure('crucible_island',['aether','sulfur','mercury','salt','sanctum'],321,anchor=True)
# Journey settlements are authored by settlements.py after the material/plant passes below.
for dim in ['cloudsea','crucible']:
    p=D/'dimension_type'/f'{dim}.json';data=json.loads(p.read_text());data.update(min_y=-64,height=512 if dim=='crucible' else 384,logical_height=512 if dim=='crucible' else 384);write(p,data)
p=ROOT/'gametest-packs/crucible/data/minecraft/worldgen/world_preset/flat.json';data=json.loads(p.read_text());data['dimensions'].pop('logos_dimension:crucible',None);data['dimensions']['logos_dimension:cloudsea']=cloud;write(p,data)
print('Native data: both noise generators, 32 journey biomes, 8 seasonal plants, tree/cover features, finite-instance template and jigsaw structures.')

write(D/'tags/worldgen/structure/journey_settlements.json',{'values':['logos_dimension:journey_hamlet','logos_dimension:journey_town']})
runpy.run_path(str(ROOT/'tools/journey_art.py'))
runpy.run_path(str(ROOT/'tools/grape_resources.py'))
runpy.run_path(str(ROOT/'tools/ambient_particles.py'))
runpy.run_path(str(ROOT/'tools/journey_tree_art.py'))

runpy.run_path(str(ROOT/'tools/berry_resources.py'))

runpy.run_path(str(ROOT/'tools/magic_flora.py'))

runpy.run_path(str(ROOT/'tools/tall_trees.py'))

runpy.run_path(str(ROOT/'tools/settlements.py'))
runpy.run_path(str(ROOT/'tools/elven_forest.py'),run_name='__main__')
