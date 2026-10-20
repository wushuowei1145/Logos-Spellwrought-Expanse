"""Attached vine multipart art, food icons, native placement and renewable crop data."""
from pathlib import Path
from PIL import Image,ImageDraw
import json
ROOT=Path(__file__).resolve().parent.parent;R=ROOT/'src/main/resources';A=R/'assets/logos_dimension';D=R/'data/logos_dimension'
def write(p,v):p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def tex(name):return 'logos_dimension:block/'+name
def plane(z,texture):return {'from':[0,0,z],'to':[16,16,z],'faces':{side:{'texture':'#'+texture,'uv':[0,0,16,16]} for side in ['north','south']}}
def model(name,elements,textures):write(A/'models/block'/f'{name}.json',{'parent':'minecraft:block/block','ambientocclusion':False,'render_type':'neoforge:cutout','textures':{'particle':tex('journey_grape_leaves'),**textures},'elements':elements})
vine=Image.new('RGBA',(32,32));q=ImageDraw.Draw(vine)
for points in [[(7,32),(10,22),(13,13),(12,0)],[(10,24),(24,17),(31,9)],[(13,13),(3,6),(0,0)]]:
    q.line(points,fill='#706044',width=2);q.line([(x+1,y) for x,y in points],fill='#a08b5f',width=1)
q.arc((22,6,29,15),70,320,fill='#8e9d63',width=1);q.arc((1,17,8,23),120,370,fill='#819653',width=1)
vine.save(A/'textures/block/journey_grape_stems.png')
leaves=Image.new('RGBA',(32,32));q=ImageDraw.Draw(leaves)
for x,y,size in [(7,7,6),(23,12,7),(6,24,6),(21,26,5)]:
    points=[(x,y-size),(x+2,y-3),(x+size,y-3),(x+size-2,y),(x+size,y+2),(x+3,y+3),(x,y+size),(x-2,y+3),(x-size,y+2),(x-size+2,y-1),(x-size,y-3),(x-2,y-3)]
    q.polygon(points,fill='#567940');q.line((x,y-size+1,x,y+size-1),fill='#a0b66e',width=1)
    q.line((x-size+2,y-2,x,y+2,x+size-2,y-2),fill='#819b55',width=1);q.point((x+2,y-3),fill='#adc481')
leaves.save(A/'textures/block/journey_grape_leaves.png')
model('journey_grape_stems',[plane(.08,'vine')],{'vine':tex('journey_grape_stems')})
model('journey_grape_leaves',[plane(.2,'leaf')],{'leaf':tex('journey_grape_leaves')})
for ripe in [False,True]:
    name='journey_grape_ripe' if ripe else 'journey_grape_green'
    im=Image.new('RGBA',(16,16),'#72527e' if ripe else '#8a9a56');q=ImageDraw.Draw(im)
    q.rectangle((1,1,7,6),fill='#a189ad' if ripe else '#c0c782');q.rectangle((10,10,15,15),fill='#4c3a5c' if ripe else '#637441');q.line((3,2,6,2),fill='#c4b5d1' if ripe else '#dce3a8');im.save(A/'textures/block'/f'{name}.png')
    elements=[]
    for x,y,z in [(10,10,1),(12,9,1),(9,8,1),(11,7,1.4),(13,7,1),(10,5,1.4),(12,5,1),(11,3,1)]:
        elements.append({'from':[x-.9,y-.9,z],'to':[x+.9,y+.9,z+1.8],'faces':{s:{'texture':'#fruit','uv':[0,0,16,16]} for s in ['south','east','west','up','down']}})
    model(name,elements,{'fruit':tex(name)})
parts=[]
for direction,rotation in [('north',{}),('east',{'y':90}),('south',{'y':180}),('west',{'y':270}),('up',{'x':270})]:
    def part(model,conditions):parts.append({'when':{direction:'true',**conditions},'apply':{'model':tex(model),**rotation}})
    part('journey_grape_stems',{})
    part('journey_grape_leaves',{'foliage':'true'})
    part('journey_grape_green',{'foliage':'true','age':'2'})
    part('journey_grape_ripe',{'foliage':'true','age':'3'})
write(A/'blockstates/journey_grapevine.json',{'multipart':parts})
write(A/'models/block/journey_grapevine.json',{'parent':tex('journey_grape_leaves')})
# A readable leaf/fruit silhouette in inventory, not a wall viewed edge-on.
for name in ['journey_grapes','journey_grape_leaf','journey_grapevine']:
    im=Image.new('RGBA',(32,32));q=ImageDraw.Draw(im)
    if name!='journey_grapes':im.alpha_composite(leaves);im.alpha_composite(vine)
    if name!='journey_grape_leaf':
        q.line((16,2,15,8),fill='#7d704d',width=2)
        for x,y in [(11,10),(18,10),(8,16),(15,16),(22,16),(12,22),(19,22),(16,27)]:
            q.ellipse((x-3,y-3,x+3,y+3),fill='#745585',outline='#45354f');q.line((x-1,y-2,x+1,y-2),fill='#b69cc4',width=1)
    path=A/'textures/item'/f'{name}.png';path.parent.mkdir(parents=True,exist_ok=True);im.save(path)
    write(A/'models/item'/f'{name}.json',{'parent':'minecraft:item/generated','textures':{'layer0':'logos_dimension:item/'+name}})
    write(A/'items'/f'{name}.json',{'model':{'type':'minecraft:model','model':'logos_dimension:item/'+name}})
for lang,values in [('zh_cn',{'item.logos_dimension.journey_grapes':'葡萄','item.logos_dimension.journey_grape_leaf':'葡萄叶','block.logos_dimension.journey_grapevine':'葡萄藤'}),('en_us',{'item.logos_dimension.journey_grapes':'Grapes','item.logos_dimension.journey_grape_leaf':'Grape Leaf','block.logos_dimension.journey_grapevine':'Grapevine'})]:
    p=A/'lang'/f'{lang}.json';d=json.loads(p.read_text(encoding='utf-8'));d.update(values);write(p,d)
write(D/'loot_table/blocks/journey_grapevine.json',{'type':'minecraft:block','pools':[{'rolls':1,'entries':[{'type':'minecraft:item','name':'logos_dimension:journey_grapevine'}],'conditions':[{'condition':'minecraft:survives_explosion'}]}]})
for tag in ['climbable','replaceable_by_trees']:
    p=R/'data/minecraft/tags/block'/f'{tag}.json';d=json.loads(p.read_text()) if p.exists() else {'replace':False,'values':[]}
    if 'logos_dimension:journey_grapevine' not in d['values']:d['values'].append('logos_dimension:journey_grapevine')
    write(p,d)
# Use only native simple_block + support predicates, so wild vines attach to real faces.
forests={'jade_whirls','verdant_whirls','lantern_shelves','ancient_beech','fernwood','moss_vale','copper_copse'}
for direction,offset,face in [('north',[0,0,-1],'south'),('south',[0,0,1],'north'),('east',[1,0,0],'west'),('west',[-1,0,0],'east')]:
    name='journey_grapes_'+direction
    write(D/'worldgen/configured_feature'/f'{name}.json',{'type':'minecraft:simple_block','config':{'to_place':{'type':'minecraft:simple_state_provider','state':{'Name':'logos_dimension:journey_grapevine','Properties':{direction:'true','age':'1','foliage':'true'}}}}})
    write(D/'worldgen/placed_feature'/f'{name}.json',{'feature':'logos_dimension:'+name,'placement':[
        {'type':'minecraft:count','count':6},{'type':'minecraft:in_square'},{'type':'minecraft:heightmap','heightmap':'MOTION_BLOCKING_NO_LEAVES'},
        {'type':'minecraft:environment_scan','direction_of_search':'down','max_steps':32,'target_condition':{'type':'minecraft:matching_block_tag','tag':'minecraft:dirt'}},
        {'type':'minecraft:random_offset','xz_spread':0,'y_spread':1},
        {'type':'minecraft:block_predicate_filter','predicate':{'type':'minecraft:all_of','predicates':[
            {'type':'minecraft:any_of','predicates':[{'type':'minecraft:matching_blocks','blocks':['minecraft:air']},{'type':'minecraft:matching_block_tag','tag':'minecraft:replaceable_by_trees'}]},
            {'type':'minecraft:has_sturdy_face','offset':offset,'direction':face}]}},{'type':'minecraft:biome'}]})
for bid in forests:
    p=D/'worldgen/biome'/f'cloudsea_{bid}.json';d=json.loads(p.read_text())
    for facing in ['north','south','east','west']:
        feature='logos_dimension:journey_grapes_'+facing
        if feature not in d['features'][9]:d['features'][9].append(feature)
    write(p,d)
print('Grape resources: five native attachment faces, leaf/green/ripe layers, food icons and supported native forest placement.')
