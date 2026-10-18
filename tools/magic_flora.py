"""Sparse woodland lights: authored botanical textures with tiny emissive dew/tip masks."""
from pathlib import Path
from PIL import Image,ImageDraw
import copy,json,math
ROOT=Path(__file__).resolve().parent.parent;R=ROOT/'src/main/resources';A=R/'assets/logos_dimension';D=R/'data/logos_dimension'
def write(p,v):p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def tex(n):return 'logos_dimension:block/'+n
for name in ['journey_stardew','journey_lumen_fern']:
    fern=name.endswith('fern');body=Image.new('RGBA',(64,64));mask=Image.new('RGBA',(64,64));q=ImageDraw.Draw(body);g=ImageDraw.Draw(mask)
    if fern:
        for tip in [(6,18),(18,7),(33,3),(46,9),(58,22)]:
            origin=(32,62);q.line([origin,tip],fill='#789369',width=2)
            for n in range(2,11):
                t=n/12;x=round(32+(tip[0]-32)*t);y=round(62+(tip[1]-62)*t);span=round(7*math.sin(t*math.pi))
                for sign in [-1,1]:
                    q.polygon([(x,y+2),(x+span*sign,y-5),(x+span*sign,y-8),(x,y-1)],fill='#476b4b');q.line((x,y,x+span*sign,y-6),fill='#8aa678',width=1)
            x,y=tip;g.ellipse((x-1,y-1,x+1,y+2),fill='#a6e2c2');g.point((x,y),fill='#edffe9')
        w,h=15,13
    else:
        for x,y in [(7,23),(15,13),(25,7),(34,4),(44,10),(54,19),(59,29)]:
            q.line([(32,62),(round((32+x)/2),43),(x,y+4)],fill='#5c8055',width=2)
            q.polygon([(31,60),(x-4,y+10),(x,y+4),(x+3,y+15)],fill='#76976a')
            q.line((x,y+5,x-1,y+14),fill='#a6b68a',width=1)
            q.ellipse((x-2,y,x+2,y+5),fill='#91c2b6',outline='#c4e1c7');g.line((x,y+1,x,y+3),fill='#f0f5d4');g.point((x-1,y+1),fill='#cfeddf')
        w,h=14,9
    body.save(A/'textures/block'/f'{name}.png');mask.save(A/'textures/block'/f'{name}_glow.png')
    elements=[]
    for i,angle in enumerate([-45,0,45]):
        for glowing in [False,True]:
            z=8+(i-1)*1.3+(.025 if glowing else 0)
            e={'from':[8-w/2,0,z],'to':[8+w/2,h,z],'rotation':{'axis':'y','angle':angle,'origin':[8,0,8]},'faces':{s:{'texture':'#glow' if glowing else '#plant','uv':[0,0,16,16]} for s in ['north','south']}}
            if glowing:e.update({'light_emission':12,'shade':False})
            elements.append(e)
    write(A/'models/block'/f'{name}.json',{'parent':'minecraft:block/block','render_type':'neoforge:cutout','ambientocclusion':False,'textures':{'particle':tex(name),'plant':tex(name),'glow':tex(name+'_glow')},'elements':elements})
    write(A/'blockstates'/f'{name}.json',{'variants':{'':[{'model':tex(name),'y':a} for a in [0,90,180,270]]}})
    write(A/'items'/f'{name}.json',{'model':{'type':'minecraft:model','model':tex(name)}})
    write(D/'loot_table/blocks'/f'{name}.json',{'type':'minecraft:block','pools':[{'rolls':1,'conditions':[{'condition':'minecraft:survives_explosion'}],'entries':[{'type':'minecraft:item','name':'logos_dimension:'+name}]}]})
for language,names in [('zh_cn',['星露草','萤灯蕨']),('en_us',['Stardew Grass','Glimmer Fern'])]:
    p=A/'lang'/f'{language}.json';d=json.loads(p.read_text(encoding='utf-8'))
    for n,title in zip(['journey_stardew','journey_lumen_fern'],names):d['block.logos_dimension.'+n]=title
    write(p,d)
for feature,entries in [('woodland_floor',[('journey_stardew',1),('journey_lumen_fern',2)]),('meadow_floor',[('journey_stardew',1)])]:
    p=D/'worldgen/configured_feature'/f'{feature}.json';d=json.loads(p.read_text(encoding='utf-8'));values=d['config']['to_place']['entries']
    for name,weight in entries:
        if not any(e['data']['Name']=='logos_dimension:'+name for e in values):values.append({'data':{'Name':'logos_dimension:'+name},'weight':weight})
    write(p,d)
p=R/'data/minecraft/tags/block/replaceable_by_trees.json';d=json.loads(p.read_text(encoding='utf-8'))
for name in ['journey_stardew','journey_lumen_fern']:
    if 'logos_dimension:'+name not in d['values']:d['values'].append('logos_dimension:'+name)
write(p,d)
print('Magic flora: two modeled woodland plants, tiny emissive masks, level 2/3 lighting; native sparse placement.')
