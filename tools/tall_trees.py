"""Sparse tall canopy/giant conifer variants, entirely vanilla configured/placed features."""
from pathlib import Path
import copy,json,zipfile
ROOT=Path(__file__).resolve().parent.parent;D=ROOT/'src/main/resources/data/logos_dimension'
FAMILIES={'ancient_beech':'fancy_oak','jade_whirls':'fancy_oak','fernwood':'fancy_oak','moss_vale':'fancy_oak','fir_heart':'mega_spruce','lilac_steps':'mega_spruce','cloudroot_shelves':'mega_spruce'}
def write(p,v):p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def provider(name,**props):return {'type':'minecraft:simple_state_provider','state':{'Name':name,'Properties':{k:str(v).lower() for k,v in props.items()}}}
with zipfile.ZipFile(ROOT.parent/'LogosParent-current/build/moddev/artifacts/minecraft-patched-26.1.2.112-sources.jar') as z:
    for family,base in FAMILIES.items():
        bid='cloudsea_'+family;name='tall_trees_'+bid
        tree=json.loads(z.read('data/minecraft/worldgen/configured_feature/'+base+'.json'));c=tree['config']
        c['trunk_provider']=provider('logos_dimension:'+bid+'_log',axis='y');c['foliage_provider']=provider('logos_dimension:'+bid+'_leaf',distance=7,persistent=False,waterlogged=False,season=0)
        c['decorators']=[]  # Keep the established forest floor instead of generating podzol discs.
        if base=='mega_spruce':
            c['trunk_placer'].update({'base_height':22,'height_rand_a':6,'height_rand_b':7})
            c['foliage_placer']['crown_height']={'type':'minecraft:uniform','min_inclusive':14,'max_inclusive':19}
        else:c['trunk_placer'].update({'base_height':16,'height_rand_a':5,'height_rand_b':4})
        write(D/'worldgen/configured_feature'/f'{name}.json',tree)
        write(D/'worldgen/placed_feature'/f'{name}.json',{'feature':'logos_dimension:'+name,'placement':[
            {'type':'minecraft:rarity_filter','chance':3},{'type':'minecraft:in_square'},
            {'type':'minecraft:surface_water_depth_filter','max_water_depth':0},
            {'type':'minecraft:heightmap','heightmap':'OCEAN_FLOOR_WG'},
            {'type':'minecraft:block_predicate_filter','predicate':{'type':'minecraft:matching_block_tag','offset':[0,-1,0],'tag':'minecraft:dirt'}},
            {'type':'minecraft:biome'}]})
        p=D/'worldgen/biome'/f'{bid}.json';b=json.loads(p.read_text(encoding='utf-8'));features=b['features'][9]
        if 'logos_dimension:'+name not in features:features.insert(0,'logos_dimension:'+name)
        write(p,b)
print('Tall trees: seven native tree variants; broadleaf 16–25, conifers 22–35 trunk height, one attempt per three chunks.')
