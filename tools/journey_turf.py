"""Opaque, tileable pixel-art turf. Three native model variants; six faces per block."""
from pathlib import Path
from PIL import Image, ImageDraw
import json, math, random, re

ROOT=Path(__file__).resolve().parent.parent
A=ROOT/'src/main/resources/assets/logos_dimension'
SIZE=32
FACES=('north','south','east','west','up','down')
GROUPS={
    'woodland':{'JADE_WHIRLS','VERDANT_WHIRLS','LANTERN_SHELVES','ANCIENT_BEECH','COPPER_COPSE'},
    'moss':{'FERNWOOD','MOSS_VALE','GLASS_LAGOONS'},
    'needle':{'LILAC_STEPS','CLOUDROOT_SHELVES','FIR_HEART'},
    'alpine':{'BLUE_BELL_STEPS','HALO_SPIRES','MOON_FOLDS','ECHO_FOLDS'},
    'tundra':{'REVERSE_GARDENS','IVORY_STEPS','AURORA_DUNES','MOON_BIRCH'},
    'coast':{'PEARL_BASINS','OPAL_DUNES','TIDE_RIBBONS'},
    'snow':{'FROST_SPIRES','PALE_SPIRES'},
    'wetland':{'STAR_RIBBONS','DREAM_BASINS'},
}
BASE={'meadow':(101,139,67),'woodland':(88,117,63),'moss':(77,120,78),
      'needle':(87,112,77),'alpine':(106,128,83),'tundra':(118,134,92),
      'coast':(179,174,146),'snow':(197,207,204),'wetland':(89,126,76)}
SOIL={'meadow':(111,86,61),'woodland':(94,73,54),'moss':(88,77,59),
      'needle':(95,79,60),'alpine':(113,103,84),'tundra':(116,105,84),
      'coast':(151,139,112),'snow':(115,121,117),'wetland':(82,73,56)}

def write(p,data):
    p.parent.mkdir(parents=True,exist_ok=True)
    p.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')

def mix(a,b,t):return tuple(round(x*(1-t)+y*t) for x,y in zip(a,b))
def shade(c,v):return tuple(max(0,min(255,round(x+v))) for x in c)
def save(im,name):im.save(A/'textures/block'/f'{name}.png')
def tex(name):return 'logos_dimension:block/'+name

class Field:
    """Periodic low-frequency patches, rather than independent per-pixel speckles."""
    def __init__(self,rng,n):self.n=n;self.values=[[rng.random() for x in range(n)] for y in range(n)]
    def __call__(self,x,y):
        px=x*self.n/SIZE;py=y*self.n/SIZE;ix=math.floor(px);iy=math.floor(py)
        tx=px-ix;ty=py-iy;tx=tx*tx*(3-2*tx);ty=ty*ty*(3-2*ty)
        at=lambda dx,dy:self.values[(iy+dy)%self.n][(ix+dx)%self.n]
        return (at(0,0)*(1-tx)+at(1,0)*tx)*(1-ty)+(at(0,1)*(1-tx)+at(1,1)*tx)*ty

def stroke(im,points,color,width=1):
    q=ImageDraw.Draw(im)
    for dx in [-SIZE,0,SIZE]:
        for dy in [-SIZE,0,SIZE]:q.line([(x+dx,y+dy) for x,y in points],fill=color,width=width)

def leaf(im,x,y,color,angle=0):
    q=ImageDraw.Draw(im)
    points=[(-3,0),(-1,-2),(1,-2),(3,0),(1,2),(-1,1)]
    turn=lambda p:(x+round(p[0]*math.cos(angle)-p[1]*math.sin(angle)),y+round(p[0]*math.sin(angle)+p[1]*math.cos(angle)))
    for dx in [-SIZE,0,SIZE]:
        for dy in [-SIZE,0,SIZE]:q.polygon([(xx+dx,yy+dy) for xx,yy in map(turn,points)],fill=color)
    stroke(im,[turn((-2,0)),turn((2,0))],shade(color,-16))

def soil_texture(group):
    rng=random.Random('soil-'+group);patch=Field(rng,6);fine=Field(rng,13);c=SOIL[group]
    im=Image.new('RGB',(SIZE,SIZE));q=ImageDraw.Draw(im)
    for y in range(0,SIZE,2):
        for x in range(0,SIZE,2):
            tone=round(((patch(x,y)-.5)*28+(fine(x,y)-.5)*9)/4)*4
            q.rectangle((x,y,x+1,y+1),fill=shade(c,tone))
    for n in range(17):
        x=rng.randrange(SIZE);y=rng.randrange(SIZE);col=mix(c,(153,148,128),.30)
        q.line((x,y,x+1,y),fill=shade(col,rng.randrange(-9,9)))
        if n%4==0:q.point((x,y+1),fill=shade(c,-16))
    return im

def ground_texture(key,group,season,variant):
    rng=random.Random(f'{key}-{season}-{variant}');patch=Field(rng,4);tufts=Field(rng,9);wear=Field(rng,3)
    base=BASE[group];shift=sum(map(ord,key))%9-4;base=shade(base,shift)
    if group not in ['coast','snow']:
        base=mix(base,(94,149,70),.18) if season==0 else mix(base,(66,109,55),.16) if season==1 else mix(base,(148,135,69),.36) if season==2 else mix(base,(133,143,119),.50)
    if group=='coast' and season==3:base=mix(base,(175,187,177),.28)
    im=Image.new('RGB',(SIZE,SIZE));q=ImageDraw.Draw(im)
    for y in range(0,SIZE,2):
        for x in range(0,SIZE,2):
            value=(patch(x,y)-.5)*30+(tufts(x,y)-.5)*12
            color=shade(base,round(value/4)*4)
            if group in ['woodland','needle'] and wear(x,y)<.43:color=mix(color,SOIL[group],.38)
            if group=='alpine' and wear(x,y)<.29:color=mix(color,(136,136,119),.65)
            if group=='wetland' and wear(x,y)<.28:color=mix(color,SOIL[group],.46)
            q.rectangle((x,y,x+1,y+1),fill=color)
    # Small grouped blades follow different directions; open ground stays calm at distance.
    for n in range(36 if group not in ['coast','snow'] else 10):
        x=rng.randrange(SIZE);y=rng.randrange(SIZE)
        if group=='alpine' and wear(x,y)<.29:continue
        if group=='coast':
            if wear(x,y)>.33:continue
            color=(119,139,93)
        elif group=='snow':continue
        else:color=shade(im.getpixel((x,y)),rng.choice([-10,-6,10,14]))
        dx,dy=rng.choice([(2,-1),(1,2),(-2,1),(-1,-2)])
        stroke(im,[(x,y),(x+dx,y+dy),(x+dx+1,y+dy)],color)
        if n%3==0:stroke(im,[(x+2,y+2),(x+2+dx,y+2+dy)],shade(color,-5))
    if group in ['woodland','tundra']:
        for n in range(13 if season==2 else 4 if group=='woodland' else 2):
            x=rng.randrange(SIZE);y=rng.randrange(SIZE)
            color=rng.choice([(148,111,58),(164,125,65),(124,97,54)]) if season==2 else rng.choice([(108,99,54),(117,102,61),(101,100,52)])
            leaf(im,x,y,color,rng.random()*math.pi)
    if group=='needle':
        for n in range(25 if season==2 else 17):
            x=rng.randrange(SIZE);y=rng.randrange(SIZE);dx,dy=rng.choice([(3,1),(2,-2),(-1,3)])
            stroke(im,[(x,y),(x+dx,y+dy)],rng.choice([(119,104,68),(105,94,65),(133,116,79)]))
    if group in ['alpine','tundra','coast','snow']:
        for n in range(6):
            x=rng.randrange(SIZE);y=rng.randrange(SIZE);c=(157,157,142) if group!='coast' else (161,155,131)
            stroke(im,[(x,y),(x+2,y),(x+1,y+1)],shade(c,rng.randrange(-7,7)))
    # Climate-dependent winter frost, not the same opaque white carpet in every biome.
    snow=group=='snow' or season==3 and group!='coast'
    if snow:
        frost=Field(rng,5);threshold=.08 if group=='snow' else .30 if group in ['tundra','alpine','needle'] else .51
        for y in range(SIZE):
            for x in range(SIZE):
                value=frost(x,y)
                ice=shade((213,222,216) if group!='snow' else (218,226,225),round((patch(x,y)-.5)*16/3)*3)
                coverage=max(0,min(1,(value-threshold+.22)/.44))
                # Soft hoarfrost avoids hard white/green camouflage when whole fields are tiled.
                strength=.88 if group=='snow' else .52+.41*coverage if group in ['tundra','alpine','needle'] else .22+.48*coverage
                im.putpixel((x,y),mix(im.getpixel((x,y)),ice,strength))
    return im

def side_texture(key,group,season,variant,top,soil):
    rng=random.Random(f'edge-{key}-{season}-{variant}');edge=Field(rng,7);im=soil.copy()
    for x in range(SIZE):
        height=4+round(edge(x,0)*5)+(2 if group in ['moss','woodland'] else 0)
        if group=='snow':height=6+round(edge(x,0)*3)
        for y in range(height):
            color=top.getpixel((x,y%3))
            im.putpixel((x,y),shade(color,-6 if y<height-2 else -17))
        if x%4==rng.randrange(4) and group not in ['coast','snow']:
            root=mix(SOIL[group],(160,136,90),.36)
            stroke(im,[(x,height),(x,height+2),(x-1,height+4)],root)
    return im

biomes=re.findall(r'([A-Z_]+)\("([^\"]+)",0x([A-F0-9]+),0x([A-F0-9]+)',(ROOT/'src/main/java/dev/logos/dimension/cloudsea/CloudseaBiome.java').read_text(encoding='utf-8'))
for group in BASE:save(soil_texture(group),'journey_turf_soil_'+group)
for key,title,ground,leaves in biomes:
    group=next((g for g,keys in GROUPS.items() if key in keys),'meadow');bid='cloudsea_'+key.lower();soil=soil_texture(group)
    states={}
    for season in range(4):
        variants=[]
        for variant in range(3):
            suffix='' if variant==0 else '_v'+str(variant)
            name=f'{bid}_ground_{season}{suffix}';side=f'{bid}_side_{season}{suffix}'
            top=ground_texture(key,group,season,variant)
            save(top,name);save(side_texture(key,group,season,variant,top,soil),side)
            textures={'particle':tex(name),'top':tex(name),'side':tex(side),'soil':tex('journey_turf_soil_'+group)}
            faces={f:{'texture':'#top' if f=='up' else '#soil' if f=='down' else '#side','uv':[0,0,16,16],'cullface':f} for f in FACES}
            write(A/'models/block'/f'{name}.json',{'parent':'minecraft:block/block','ambientocclusion':True,'textures':textures,'elements':[{'from':[0,0,0],'to':[16,16,16],'faces':faces}]})
            variants += [{'model':tex(name),'y':rotation} for rotation in [0,90,180,270]]
        states['season='+str(season)]=variants
    write(A/'blockstates'/f'{bid}_ground.json',{'variants':states})
print('Turf art: 32 families x 4 seasons x 3 texture variants; 9 ecological surfaces, native rotation and 6 culled faces each.')
