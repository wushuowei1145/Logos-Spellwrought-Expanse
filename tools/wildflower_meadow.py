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
    petal = ('#f2efda' if name.endswith('daisies') else '#a3a9df') if season < 2 else '#ccb994'
    shadow = '#c6c5ac' if name.endswith('daisies') else '#6b7caf'
    if head:
        if season < 3:
            if name.endswith('daisies'):
                for box in [(6,2,9,5),(6,10,9,13),(2,6,5,9),(10,6,13,9),(4,4,6,6),(9,4,11,6),(4,9,6,11),(9,9,11,11)]:
                    q.rectangle(box, fill=petal)
                q.rectangle((6,6,9,9), fill='#d6ae51' if season < 2 else '#a28a51')
                q.point((6,6),fill='#f5d974')
            else:
                q.polygon([(7,2),(11,5),(13,9),(10,12),(5,13),(2,9),(4,4)],fill=shadow)
                q.polygon([(7,3),(10,6),(12,9),(9,10),(5,12),(4,8),(5,5)],fill=petal)
                q.rectangle((6,6,9,9),fill='#e5debe')
        return im
    q.line((8,15,8,3),fill=stem,width=1)
    q.polygon([(8,14),(3,13),(1,9),(5,10),(8,12)],fill=leaf)
    q.polygon([(8,12),(10,8),(14,6),(13,10),(10,13)],fill=leaf)
    q.line((3,11,8,14),fill=vein,width=1)
    q.line((9,12,12,8),fill=vein,width=1)
    if season < 2:
        if name.endswith('daisies'):
            q.rectangle((6,1,9,5),fill=petal);q.rectangle((4,2,11,4),fill=petal)
            q.rectangle((7,2,8,3),fill='#d7ad4a');q.point((7,2),fill='#f4d67d')
        else:
            q.line((8,3,11,2),fill=stem)
            q.rectangle((7,2,11,5),fill=shadow);q.rectangle((8,2,10,4),fill=petal)
            q.point((7,5),fill=petal);q.point((11,5),fill=petal)
            q.line((8,7,4,6),fill=stem)
            q.rectangle((2,6,6,9),fill=shadow);q.rectangle((3,6,5,8),fill=petal)
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
    positions=[(4,5,10,8),(11,11,13,9)] if variant==0 else [(5,11,12,9),(11,4,9,8)]
    for x,z,h,w in positions:
        elements.extend([card(x,z,h,w,'z'),card(x,z,h,w,'x')])
        if season<2:
            elements.append({'from':[x-2,h*.8,z-2],'to':[x+2,h*.8,z+2], 'shade':False,
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
            for variant in range(2):
                model_id=f'block/wildflower/{name}_{season}_{variant}'
                write(A/'models'/f'{model_id}.json',model(name,season,variant))
                variants[f'season={season}'] += [{'model':'logos_dimension:'+model_id,'y':r} for r in [0,90,180,270]]
        write(A/'blockstates'/f'{name}.json',{'variants':variants})
        write(A/'items'/f'{name}.json',{'model':{'type':'minecraft:model','model':f'logos_dimension:block/wildflower/{name}_0_0'}})
        write(D/'loot_table/blocks'/f'{name}.json',{'type':'minecraft:block','pools':[{'rolls':1,'entries':[{'type':'minecraft:item','name':'logos_dimension:'+name}],'conditions':[{'condition':'minecraft:survives_explosion'}]}]})
    ids=['logos_dimension:'+n for n in KINDS]
    for ns,kind,name in [('logos','block','fragile_plants'),('minecraft','block','small_flowers'),('minecraft','item','small_flowers'),('minecraft','block','replaceable_by_trees')]:
        tag(ns,kind,name,ids)
    for language,labels in [('zh_cn',['原野雏菊花簇','原野铃花花簇']),('en_us',['Meadow Daisy Cluster','Meadow Bellflower Cluster'])]:
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
    template=json.loads(placed.read_text(encoding='utf-8'));template['placement'][0]['count']=360;write(placed,template)
    entries=[('minecraft:short_grass',None,58),('logos_dimension:journey_meadow_grass_young',None,24),
             ('logos_dimension:journey_stardew',None,6),('logos_dimension:journey_silver_anemone',0,8),('logos_dimension:journey_fireweed',0,4)]
    provider={'type':'minecraft:weighted_state_provider','entries':[]}
    for name,season,weight in entries:
        value={'Name':name}
        if season is not None:value['Properties']={'season':str(season)}
        provider['entries'].append({'data':value,'weight':weight})
    write(D/'worldgen/configured_feature/wildflower_understory.json',{'type':'minecraft:simple_block','config':{'to_place':provider}})
    under=copy.deepcopy(template);under['feature']='logos_dimension:wildflower_understory';under['placement'][0]['count']=220
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
