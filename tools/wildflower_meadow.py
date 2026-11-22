"""生成野花草甸的十六像素花簇与原版噪声花带；不重写其他群系。"""
from pathlib import Path
import copy
import json
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / 'src/main/resources'
A = RES / 'assets/logos_dimension'
D = RES / 'data/logos_dimension'
KINDS = ['journey_meadow_daisies', 'journey_meadow_bells']


def write(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')


def state(name, season=None):
    value = {'Name': 'logos_dimension:' + name}
    if season is not None:
        value['Properties'] = {'season': str(season)}
    return value


def paint(name, season, head=False):
    im = Image.new('RGBA', (16, 16))
    q = ImageDraw.Draw(im)
    leaf = ['#628844', '#527c3c', '#8a8951', '#899280'][season]
    vein = ['#94ae63', '#8fa65a', '#bbaf72', '#c2cabb'][season]
    stem = ['#506e36', '#49672f', '#806943', '#858875'][season]
    white = name.endswith('daisies')
    petal = ('#f4ebd8' if white else '#b2b3e1') if season < 2 else '#ccb994'
    shadow = '#d7c8ad' if white else '#7a86b8'
    if head:
        if season < 3:
            if white:
                # 宽瓣波斯菊：八片花瓣重叠成饱满花冠，花心只留小块暖金。
                for box in [(5,0,10,7),(8,2,15,8),(9,6,15,12),(5,8,11,15),
                            (1,8,7,15),(0,5,7,10),(0,1,7,7),(2,0,8,6)]:
                    q.ellipse(box,fill=shadow)
                    q.ellipse((box[0]+1,box[1],box[2],box[3]-1),fill=petal)
                q.rectangle((6,6,9,9),fill='#d7b356' if season<2 else '#a28a51')
                q.point((6,6),fill='#f5d974')
            else:
                # 绣球由数朵小花形成整团花冠，避免一个大色块糊在茎上。
                q.ellipse((1,1,14,14),fill=shadow)
                for x,y in [(5,4),(10,4),(3,8),(8,8),(12,9),(6,12),(10,12)]:
                    q.polygon([(x,y-2),(x+2,y-1),(x+2,y+1),(x,y+2),(x-2,y+1),(x-2,y-1)],fill=petal)
                    q.point((x,y),fill='#e8e3ee')
        return im
    q.line((8,15,8,5),fill=stem,width=2)
    for points in [[(8,15),(1,14),(0,9),(5,10),(8,13)],[(8,15),(10,10),(15,9),(14,14)],
                   [(8,12),(2,10),(1,6),(6,8)],[(8,12),(10,7),(15,6),(13,11)]]:
        q.polygon(points,fill=leaf)
    q.line((2,11,8,15),fill=vein,width=1)
    q.line((9,14,13,10),fill=vein,width=1)
    if season < 2:
        if white:
            q.ellipse((1,1,14,8),fill=shadow)
            for box in [(2,0,7,5),(6,0,11,5),(9,1,14,6),(8,4,13,8),(3,4,8,8),(1,2,6,6)]:
                q.ellipse(box,fill=petal)
            q.rectangle((6,3,9,5),fill='#d7ad4a');q.point((6,3),fill='#f4d67d')
        else:
            q.ellipse((1,0,14,9),fill=shadow)
            for x,y in [(5,2),(10,2),(3,5),(8,5),(12,6),(6,8)]:
                q.polygon([(x,y-1),(x+2,y),(x+1,y+2),(x-1,y+2),(x-2,y)],fill=petal)
                q.point((x,y),fill='#e8e3ee')
    else:
        q.rectangle((7,2,9,4),fill='#9a8256' if season==2 else '#bec7b9')
        if season==3:q.line((2,9,5,10),fill='#e1e5d8')
    return im


def card(x,z,h,w,axis,texture='body'):
    low, high = ([x-w/2,0,z],[x+w/2,h,z]) if axis=='z' else ([x,0,z-w/2],[x,h,z+w/2])
    faces = ['north','south'] if axis=='z' else ['east','west']
    return {'from':low,'to':high,'shade':False,'faces':{f:{'texture':'#'+texture,'uv':[0,0,16,16]} for f in faces}}


def model(name,season,variant):
    elements=[]
    # 错开花冠位置和高度，避免高密度时所有花头都落在整齐的半格网点上。
    positions=[[(4.5,4.5,8.5,11),(11.5,11.5,11,11)],
               [(5.8,12,10,11),(10.3,3.5,8,10.5)],
               [(3.3,7.7,9,10.5),(11,11.7,11.2,11)],
               [(6.2,3.4,10.8,10.5),(12,12,7.8,11)]][variant]
    for x,z,h,w in positions:
        elements.extend([card(x,z,h,w,'z'),card(x,z,h,w,'x')])
        if season<2:
            elements.append({'from':[x-3.7,h*.77,z-3.7],'to':[x+3.7,h*.77,z+3.7], 'shade':False,
                             'faces':{f:{'texture':'#head','uv':[0,0,16,16]} for f in ['up','down']}})
    return {'parent':'minecraft:block/block','ambientocclusion':False,'render_type':'minecraft:cutout',
            'textures':{'body':f'logos_dimension:block/wildflower/{name}_{season}',
                        'head':f'logos_dimension:block/wildflower/{name}_{season}_head',
                        'particle':f'logos_dimension:block/wildflower/{name}_{season}'},'elements':elements}


def tag(namespace, kind, name, values):
    path=RES/f'data/{namespace}/tags/{kind}/{name}.json'
    obj=json.loads(path.read_text(encoding='utf-8')) if path.exists() else {'replace':False,'values':[]}
    obj['values']=list(dict.fromkeys(obj['values']+values))
    write(path,obj)


def main():
    textures=A/'textures/block/wildflower';textures.mkdir(parents=True,exist_ok=True)
    for name in KINDS:
        variants={}
        for season in range(4):
            paint(name,season).save(textures/f'{name}_{season}.png')
            paint(name,season,True).save(textures/f'{name}_{season}_head.png')
            variants[f'season={season}']=[]
            for variant in range(4):
                model_id=f'block/wildflower/{name}_{season}_{variant}'
                write(A/'models'/f'{model_id}.json',model(name,season,variant))
                variants[f'season={season}'] += [{'model':'logos_dimension:'+model_id,'y':r} for r in [0,90,180,270]]
        write(A/'blockstates'/f'{name}.json',{'variants':variants})
        write(A/'items'/f'{name}.json',{'model':{'type':'minecraft:model','model':f'logos_dimension:block/wildflower/{name}_0_0'}})
        write(D/'loot_table/blocks'/f'{name}.json',{'type':'minecraft:block','pools':[{'rolls':1,'entries':[{'type':'minecraft:item','name':'logos_dimension:'+name}],'conditions':[{'condition':'minecraft:survives_explosion'}]}]})
    ids=['logos_dimension:'+n for n in KINDS]
    for ns,kind,name in [('logos','block','fragile_plants'),('minecraft','block','small_flowers'),('minecraft','item','small_flowers'),('minecraft','block','replaceable_by_trees')]:
        tag(ns,kind,name,ids)
    for language,labels in [('zh_cn',['奶油波斯菊花簇','蓝紫绣球花簇']),('en_us',['Cream Cosmos Cluster','Blue Hydrangea Cluster'])]:
        path=A/f'lang/{language}.json';obj=json.loads(path.read_text(encoding='utf-8'))
        for name,label in zip(KINDS,labels):
            for prefix in ['block','item']:obj[f'{prefix}.logos_dimension.{name}']=label
        write(path,obj)
    # 同一噪声位置选择同类花，形成大块白花、蓝花交错的花带。
    floor=D/'worldgen/configured_feature/cloudsea_rose_dunes_floor.json'
    write(floor,{'type':'minecraft:simple_block','config':{'to_place':{'type':'minecraft:noise_provider',
        'seed':602211,'noise':{'firstOctave':-2,'amplitudes':[1.0,.35]},'scale':.25,
        'states':[state(KINDS[0],0)]*3+[state(KINDS[1],0)]*2}}})
    placed=D/'worldgen/placed_feature/cloudsea_rose_dunes_floor.json'
    template=json.loads(placed.read_text(encoding='utf-8'));template['placement'][0]['count']=600;write(placed,template)
    entries=[('minecraft:short_grass',None,58),('logos_dimension:journey_meadow_grass_young',None,24),
             ('logos_dimension:journey_stardew',None,6),('logos_dimension:journey_silver_anemone',0,8),('logos_dimension:journey_fireweed',0,4)]
    provider={'type':'minecraft:weighted_state_provider','entries':[]}
    for name,season,weight in entries:
        value={'Name':name}
        if season is not None:value['Properties']={'season':str(season)}
        provider['entries'].append({'data':value,'weight':weight})
    write(D/'worldgen/configured_feature/wildflower_understory.json',{'type':'minecraft:simple_block','config':{'to_place':provider}})
    under=copy.deepcopy(template);under['feature']='logos_dimension:wildflower_understory';under['placement'][0]['count']=180
    write(D/'worldgen/placed_feature/wildflower_understory.json',under)
    path=D/'worldgen/biome/cloudsea_rose_dunes.json';biome=json.loads(path.read_text(encoding='utf-8'))
    features=biome['features'][9]
    if 'logos_dimension:wildflower_understory' not in features:features.append('logos_dimension:wildflower_understory')
    write(path,biome)
    # 湿润的平地形成连续花原；与长风草原的较干气候主体错开。
    climate={'biome':'logos_dimension:cloudsea_rose_dunes','parameters':{
        'temperature':[-.1,.55],'humidity':[.28,.8],'continentalness':[0,1.0],
        'erosion':[.45,1.2],'weirdness':[-1.2,1.2],'depth':0,'offset':0}}
    for path in [D/'dimension/cloudsea.json',ROOT/'gametest-packs/crucible/data/minecraft/worldgen/world_preset/flat.json']:
        obj=json.loads(path.read_text(encoding='utf-8'))
        generator=obj['generator'] if 'generator' in obj else obj['dimensions']['logos_dimension:cloudsea']['generator']
        points=generator['biome_source']['biomes']
        if climate not in points:points.append(climate)
        write(path,obj)


if __name__=='__main__':
    main()
