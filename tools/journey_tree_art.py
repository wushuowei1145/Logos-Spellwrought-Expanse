"""Individual wood families and dense inner leaf clusters. Closed backing; culled static details."""
from pathlib import Path
from PIL import Image,ImageDraw
import json,math,random,re

ROOT=Path(__file__).resolve().parent.parent
A=ROOT/'src/main/resources/assets/logos_dimension';D=ROOT/'src/main/resources/data/logos_dimension'
SIDES=('north','south','east','west','up','down')
GROUPS={
 'birch':{'REVERSE_GARDENS','IVORY_STEPS','MOON_BIRCH'},
 'conifer':{'LILAC_STEPS','CLOUDROOT_SHELVES','FIR_HEART'},
 'beech':{'JADE_WHIRLS','ANCIENT_BEECH'},
 'oak':{'VERDANT_WHIRLS','LANTERN_SHELVES','FERNWOOD'},
 'willow':{'STAR_RIBBONS','GLASS_LAGOONS','DREAM_BASINS','MOSS_VALE'},
 'alpine':{'BLUE_BELL_STEPS','HALO_SPIRES','MOON_FOLDS','ECHO_FOLDS','FROST_SPIRES','AURORA_DUNES','PALE_SPIRES'},
 'coast':{'PEARL_BASINS','OPAL_DUNES','TIDE_RIBBONS'},'copper':{'COPPER_COPSE'}}
PALETTE={'birch':(196,193,169),'conifer':(96,76,61),'beech':(132,136,121),'oak':(105,93,67),
         'willow':(115,126,105),'alpine':(123,117,103),'coast':(151,145,122),'copper':(132,106,73),'glade':(141,127,94)}
WOOD={'birch':(189,170,124),'conifer':(163,130,88),'beech':(173,153,115),'oak':(165,140,93),
      'willow':(167,156,116),'alpine':(170,153,119),'coast':(182,172,136),'copper':(184,144,93),'glade':(187,162,111)}

def write(p,data):p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def shade(c,v):return tuple(max(0,min(255,round(x+v))) for x in c)
def tex(name):return 'logos_dimension:block/'+name
def save(im,name):im.save(A/'textures/block'/f'{name}.png')

def bark(name,group,variant):
    rng=random.Random(name+str(variant));base=shade(PALETTE[group],sum(map(ord,name))%13-6)
    im=Image.new('RGB',(64,64));q=ImageDraw.Draw(im)
    for x in range(64):
        grain=math.sin(x*.65+variant)*5+math.sin(x*.21)*4
        for y in range(64):
            noise=rng.choice([-2,-1,0,0,1,2]);tone=grain+math.sin(y*.12+x*.3)*2+noise
            im.putpixel((x,y),shade(base,tone))
    if group=='birch':
        for y in [4,17,31,46,59]:
            x=rng.randrange(-3,38);w=rng.randrange(10,25)
            q.line([(x,y),(x+w//2,y+1),(x+w,y)],fill=shade(base,-67),width=2)
            q.line((x+2,y+3,x+w-2,y+2),fill=shade(base,18),width=1)
            for n in range(3):q.point((rng.randrange(64),y+rng.randrange(5)),fill=shade(base,-35))
        q.arc((15+variant*6,23,29+variant*6,37),0,330,fill=shade(base,-51),width=2)
    elif group=='beech':
        for x in [9,29,48]:
            q.line([(x+round(2*math.sin(y*.10+x)),y) for y in range(64)],fill=shade(base,-12),width=1)
        for x,y in [(19+variant*4,20),(46-variant*3,48)]:
            q.arc((x-8,y-4,x+8,y+7),5,170,fill=shade(base,-36),width=2)
            q.arc((x-7,y-7,x+7,y+4),185,350,fill=shade(base,16),width=1)
            q.line((x-3,y+2,x+3,y+1),fill=shade(base,-42),width=2)
    else:
        spacing=9 if group in ['oak','conifer'] else 13 if group in ['coast','glade'] else 7
        for x in range(-3,65,spacing):
            phase=rng.random()*6;points=[(x+round(2.5*math.sin(y*.1+phase)+math.sin(y*.24)),y) for y in range(-2,66,2)]
            q.line(points,fill=shade(base,-35 if group=='oak' else -25),width=2)
            q.line([(xx+2,yy) for xx,yy in points],fill=shade(base,16),width=1)
        if group in ['oak','conifer','alpine']:
            for y in range(5,64,12):
                for x in range((y//12%2)*7,64,14):
                    q.line((x,y,x+4,y+3,x+8,y+2),fill=shade(base,-19),width=1)
        for x,y in [(20+variant*3,17),(48-variant*4,47)]:
            for r in [3,6,9]:q.ellipse((x-r*.65,y-r,x+r*.65,y+r),outline=shade(base,-25 if r==3 else -14),width=1)
            q.line((x,y-2,x,y+2),fill=shade(base,-42),width=2)
        if group in ['willow','coast','copper']:
            color=(113,139,102) if group!='copper' else (173,142,86)
            for n in range(8):
                x=rng.randrange(64);y=rng.randrange(64);q.line((x,y,x+2,y-1,x+3,y),fill=color,width=1)
        if group=='conifer':
            for x,y in [(8+variant*2,30),(44,56)]:q.line((x,y,x,y+4),fill=(161,128,68),width=2);q.point((x,y+4),fill=(189,156,87))
    return im

def end_grain(name,group):
    rng=random.Random(name+'-end');c=WOOD[group];im=Image.new('RGB',(64,64),PALETTE[group]);q=ImageDraw.Draw(im)
    q.rectangle((3,3,60,60),fill=c);cx=30+rng.randrange(6);cy=29+rng.randrange(6)
    for radius in range(4,45,4):
        points=[]
        for step in range(129):
            a=step*math.pi*2/128;r=radius+math.sin(a*3+radius)*.7
            points.append((cx+math.cos(a)*r,cy+math.sin(a)*r*.92))
        q.line(points,fill=shade(c,-17),width=1);q.line([(x+1,y+1) for x,y in points],fill=shade(c,9),width=1)
    for angle,length in [(rng.random()*6,23),(rng.random()*6,18)]:
        q.line([(cx+math.cos(angle)*r,cy+math.sin(angle)*r*.92) for r in range(9,length)],fill=shade(c,-32),width=1)
    q.rectangle((0,0,63,63),outline=shade(PALETTE[group],-18),width=3)
    q.point((cx,cy),fill=shade(c,-22));return im

def box(lo,hi,texture='bark',sides=SIDES,cull=None,uv=(0,0,16,16)):
    return {'from':lo,'to':hi,'faces':{side:{'texture':'#'+texture,'uv':list(uv),'cullface':cull or side} for side in sides}}

def leaf_inner(name,season,evergreen):
    original=Image.open(A/'textures/block'/f'{name}.png').convert('RGBA')
    colors=[color[:3] for color in original.getdata() if color[3]>0]
    rng=random.Random(name+'-inner');im=Image.new('RGBA',(32,32));q=ImageDraw.Draw(im)
    count=105 if season!=3 or evergreen else 68
    for n in range(count):
        x=rng.randrange(-3,35);y=rng.randrange(-3,35);c=rng.choice(colors);w=rng.randrange(3,6)
        q.polygon([(x-w,y),(x-1,y-3),(x+2,y-2),(x+w,y),(x+1,y+3),(x-2,y+2)],fill=c+(255,))
        q.line((x-w+1,y,x+w-1,y),fill=shade(c,12)+(255,),width=1)
    return im

biomes=re.findall(r'([A-Z_]+)\("([^\"]+)",0x([A-F0-9]+),0x([A-F0-9]+)',(ROOT/'src/main/java/dev/logos/dimension/cloudsea/CloudseaBiome.java').read_text(encoding='utf-8'))
zh=json.loads((A/'lang/zh_cn.json').read_text(encoding='utf-8'));en=json.loads((A/'lang/en_us.json').read_text(encoding='utf-8'))
for key,title,g,l in biomes:
    bid='cloudsea_'+key.lower();name=bid+'_log';group=next((group for group,keys in GROUPS.items() if key in keys),'glade')
    save(end_grain(name,group),name+'_end');variants={'axis=y':[],'axis=x':[],'axis=z':[]}
    for variant in range(3):
        suffix='' if variant==0 else '_v'+str(variant);model_name=name+suffix;save(bark(name,group,variant),model_name)
        elements=[box([0,0,0],[16,16,16])]
        elements[0]['faces']['up']['texture']='#end';elements[0]['faces']['down']['texture']='#end'
        seed=sum(map(ord,key))+variant*11
        # Relief sits outside a closed backing: no cutouts or gaps between stacked logs.
        for side in ['north','south']:
            x=2+seed%9;width=2.0 if group not in ['beech','birch'] else 5.0
            y=0 if group not in ['beech','birch'] else 3+seed%6;height=16-y if group not in ['beech','birch'] else 4
            depth=.11 if group in ['birch','beech'] else .20
            lo=[x,y,-depth if side=='north' else 16];hi=[x+width,y+height,0 if side=='north' else 16+depth]
            visible=[side,'east','west','up','down']
            elements.append(box(lo,hi,'bark',visible,side,(x,16-y-height,x+width,16-y)))
        write(A/'models/block'/f'{model_name}.json',{'parent':'minecraft:block/block','ambientocclusion':True,'textures':{'particle':tex(model_name),'bark':tex(model_name),'end':tex(name+'_end')},'elements':elements})
        variants['axis=y'] += [{'model':tex(model_name),'y':a} for a in [0,90,180,270]]
        variants['axis=x'].append({'model':tex(model_name),'x':90,'y':90})
        variants['axis=z'].append({'model':tex(model_name),'x':90})
    write(A/'blockstates'/f'{name}.json',{'variants':variants});write(A/'items'/f'{name}.json',{'model':{'type':'minecraft:model','model':tex(name)}})
    write(D/'loot_table/blocks'/f'{name}.json',{'type':'minecraft:block','pools':[{'rolls':1,'entries':[{'type':'minecraft:item','name':'logos_dimension:'+name}],'conditions':[{'condition':'minecraft:survives_explosion'}]}]})
    if key=='WHITE_CHERRY':zh['block.logos_dimension.'+name]='白樱花原木';en['block.logos_dimension.'+name]='White Cherry Log'
    if key=='WHITE_CHERRY':
        zh['block.logos_dimension.'+name]='白樱花原木';en['block.logos_dimension.'+name]='White Cherry Log'
        zh['item.logos_dimension.'+name]='白樱花原木';en['item.logos_dimension.'+name]='White Cherry Log'
    else:
        zh['block.logos_dimension.'+name]=title+'原木';en['block.logos_dimension.'+name]=en['block.logos_dimension.'+bid+'_leaf'].removesuffix(' Leaves')+' Log'

    for season in range(4):
        leaf=bid+'_leaf_'+str(season);save(leaf_inner(leaf,season,group=='conifer'),leaf+'_inner')
        p=A/'models/block'/f'{leaf}.json';m=json.loads(p.read_text());m['textures']['inner']=tex(leaf+'_inner')
        def plane(axis):
            lo=[1,1,8] if axis=='z' else [8,1,1] if axis=='x' else [1,8,1]
            hi=[15,15,8] if axis=='z' else [8,15,15] if axis=='x' else [15,8,15]
            sides=['north','south'] if axis=='z' else ['east','west'] if axis=='x' else ['up','down']
            return box(lo,hi,'inner',sides)
        # Keep the outer silhouette and diagonal fringe; replace the top-only fringe with central volume.
        m['elements']=m['elements'][:2]+[plane('z'),plane('x'),plane('y')]
        write(p,m)
write(A/'lang/zh_cn.json',zh);write(A/'lang/en_us.json',en)
write(A/'models/block/cloudsea_stem.json',{'parent':tex('cloudsea_ancient_beech_log')})
print('Tree art: 32 named log families, 96 bark models, 128 dense inner leaf textures and 14-face culled leaf volumes.')

# 白樱原木：树皮压暗，做出"深色樱花木"；只动白樱群系，别影响其它树的树种。
def _darken(name, factor):
    path = A / 'textures/block' / f'{name}.png'
    if not path.exists():
        return
    im = Image.open(path).convert('RGBA')
    im.putdata([(int(r * factor), int(g * factor), int(b * factor), a) for r, g, b, a in im.getdata()])
    im.save(path)

for suffix in ('', '_v1', '_v2', '_end'):
    _darken('cloudsea_white_cherry_log' + suffix, 0.52)
