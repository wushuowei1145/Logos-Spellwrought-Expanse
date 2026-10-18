"""Large native-style berry shrubs: woody silhouette, dense foliage and blue berry clusters."""
from pathlib import Path
from PIL import Image,ImageDraw
import json,random,math
ROOT=Path(__file__).resolve().parent.parent;R=ROOT/'src/main/resources';A=R/'assets/logos_dimension';D=R/'data/logos_dimension'
NAME='journey_berry_bramble'
def write(p,v):p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def tex(n):return 'logos_dimension:block/'+n
def face(t):return {'texture':'#'+t,'uv':[0,0,16,16]}
def plane(axis,offset,y,w,h,t):
    a=[8-w/2,y,offset] if axis=='z' else [offset,y,8-w/2];b=[8+w/2,y+h,offset] if axis=='z' else [offset,y+h,8+w/2]
    return {'from':a,'to':b,'faces':{s:face(t) for s in (['north','south'] if axis=='z' else ['east','west'])}}
palettes=[('#3b5935','#668a47','#99b568'),('#2f5035','#537940','#91a765'),('#565a32','#89904b','#b0a16a'),('#495b4b','#738276','#a9b7a6')]
for season,(dark,green,light) in enumerate(palettes):
    for age in range(4):
        name=f'{NAME}_s{season}_a{age}';rng=random.Random(name)
        im=Image.new('RGBA',(64,64));q=ImageDraw.Draw(im)
        branches=[[(32,63),(31,39),(20,24),(15,12)],[(32,60),(38,37),(48,22),(51,13)],[(31,47),(17,41),(8,27)],[(36,42),(49,39),(58,29)],[(32,39),(32,17),(27,5)]]
        for pts in branches:
            q.line(pts,fill='#544630',width=3);q.line([(x+1,y) for x,y in pts],fill='#9a855a',width=1)
        centers=[(16,17,10),(32,12,11),(48,18,11),(10,33,9),(26,29,12),(44,32,12),(56,35,7),(17,46,10),(35,45,12),(49,48,8)]
        if age==0:centers=centers[3:8]
        for cx,cy,r in centers:
            for i in range(14 if season!=3 else 9):
                x=cx+rng.randrange(-r,r+1);y=cy+rng.randrange(-r//2,r//2+1);sz=rng.randrange(3,6)
                q.polygon([(x-sz,y),(x-2,y-sz//2-1),(x+sz,y-1),(x+2,y+sz//2+1)],fill=rng.choice([dark,green,green,light]))
                q.line((x-sz+1,y,x+sz-1,y-1),fill=light,width=1)
        if age>=2:
            for cx,cy in [(16,25),(40,20),(51,39),(26,42),(10,42),(40,49)][:3 if age==2 else 6]:
                q.line((cx,cy-5,cx,cy+2),fill='#857751',width=1)
                for dx,dy in [(-2,0),(3,1),(0,5)]:
                    x,y=cx+dx,cy+dy;q.ellipse((x-3,y-3,x+3,y+3),fill='#213b79',outline='#152a4a')
                    q.line((x-1,y-2,x+1,y-2),fill='#86c7e1',width=1);q.point((x+1,y+1),fill='#4e79b7');q.point((x,y-1),fill='#496d9b')
        im.save(A/'textures/block'/f'{name}.png')
        # Vanilla berry bushes use two diagonal crossed planes. Enlarge their footprint
        # as well as height; never project fruit sideways onto a horizontal shelf.
        w,h=[(15.5,10),(21.5,17),(28,22),(28,22)][age]
        elements=[]
        for angle in [-45,45]:
            e=plane('z',8,0,w,h,'bush');e['rotation']={'axis':'y','angle':angle,'origin':[8,0,8]};elements.append(e)
        write(A/'models/block'/f'{name}.json',{'parent':'minecraft:block/block','ambientocclusion':False,'render_type':'neoforge:cutout','textures':{'particle':tex(name),'bush':tex(name)},'elements':elements})
variants={f'age={age},season={s}':[{'model':tex(f'{NAME}_s{s}_a{age}'),'y':y} for y in [0,90,180,270]] for s in range(4) for age in range(4)}
write(A/'blockstates'/f'{NAME}.json',{'variants':variants})
icon=Image.new('RGBA',(32,32));q=ImageDraw.Draw(icon);q.line([(17,2),(17,11),(9,17)],fill='#8b7d4c',width=2)
q.polygon([(17,5),(27,4),(23,11),(17,10)],fill='#628344');q.line((18,8,25,6),fill='#b0bd76')
for x,y in [(11,14),(20,17),(12,24)]:
    q.ellipse((x-6,y-6,x+6,y+6),fill='#345896',outline='#172e50',width=1);q.arc((x-4,y-4,x+3,y+3),185,290,fill='#94cce3',width=2)
    q.line((x-2,y+1,x+2,y+1),fill='#759abb');q.point((x,y),fill='#203e69')
icon.save(A/'textures/item'/f'{NAME}.png')
write(A/'models/item'/f'{NAME}.json',{'parent':'minecraft:item/generated','textures':{'layer0':'logos_dimension:item/'+NAME}})
write(A/'items'/f'{NAME}.json',{'model':{'type':'minecraft:model','model':'logos_dimension:item/'+NAME}})
# Same item plants, feeds and drops; mature breaking drops 2–3, age 2 drops 1–2.
pools=[]
for age,lo,hi in [(2,1,2),(3,2,3)]:
    pools.append({'rolls':1,'conditions':[{'condition':'minecraft:block_state_property','block':'logos_dimension:'+NAME,'properties':{'age':str(age)}},{'condition':'minecraft:survives_explosion'}],'entries':[{'type':'minecraft:item','name':'logos_dimension:'+NAME,'functions':[{'function':'minecraft:set_count','count':{'type':'minecraft:uniform','min':lo,'max':hi}}]}]})
write(D/'loot_table/blocks'/f'{NAME}.json',{'type':'minecraft:block','pools':pools})
for lang,cn,en in [('zh_cn','林地蓝莓灌木','林地蓝莓'),('en_us','Woodland Blueberry Bush','Woodland Blueberries')]:
    p=A/'lang'/f'{lang}.json';d=json.loads(p.read_text(encoding='utf-8'));d['block.logos_dimension.'+NAME]=cn;d['item.logos_dimension.'+NAME]=en;write(p,d)
# Generate established shrubs rather than grass-like seedlings; use native providers.
p=D/'worldgen/configured_feature/woodland_floor.json';d=json.loads(p.read_text(encoding='utf-8'))
def mature(v):
    if isinstance(v,dict):
        if v.get('Name')=='logos_dimension:'+NAME:v.setdefault('Properties',{})['age']='3'
        for child in v.values():mature(child)
    elif isinstance(v,list):
        for child in v:mature(child)
mature(d);write(p,d)
print('Berry art: 16 large seasonal growth models, blue fruit, edible planting item and native mature placement.')
