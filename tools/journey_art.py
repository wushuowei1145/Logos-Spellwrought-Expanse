"""Final, deterministic block art pass. Static baked geometry; no per-plant renderers."""
from pathlib import Path
from PIL import Image, ImageDraw
import json, math, random, re, runpy

ROOT=Path(__file__).resolve().parent.parent
R=ROOT/'src/main/resources';A=R/'assets/logos_dimension';D=R/'data/logos_dimension'
SIDES=('north','south','east','west','up','down')
def write(p,v):p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def rgb(c):return tuple(bytes.fromhex(c.lstrip('#'))) if isinstance(c,str) else tuple(c)
def tint(c,n):return tuple(max(0,min(255,int(v+n))) for v in rgb(c))
def save(im,name):im.save(A/'textures/block'/f'{name}.png')
def face(tex,uv=(0,0,16,16),cull=None):return {'texture':'#'+tex,'uv':list(uv),**({'cullface':cull} if cull else {})}
def cube(lo,hi,tex='body',cull=False,sides=SIDES):
    return {'from':list(lo),'to':list(hi),'faces':{s:face(tex,cull=s if cull else None) for s in sides}}
def blade(x=8,z=8,y=0,w=14,h=14,axis='z',angle=0,tex='plant',cull=False):
    lo=[x-w/2,y,z] if axis=='z' else [x,y,z-w/2];hi=[x+w/2,y+h,z] if axis=='z' else [x,y+h,z+w/2]
    sides=('north','south') if axis=='z' else ('east','west')
    e={'from':lo,'to':hi,'faces':{s:face(tex,cull=s if cull else None) for s in sides}}
    if angle:e['rotation']={'axis':'y','angle':angle,'origin':[x,y,z]}
    return e
def horizontal(y=1,tex='plant',lo=0,hi=16,cull=False):
    return {'from':[lo,y,lo],'to':[hi,y,hi],'faces':{s:face(tex,cull=s if cull else None) for s in ['up','down']}}
def model(name,elements,textures,cutout=False):
    write(A/'models/block'/f'{name}.json',{'parent':'minecraft:block/block','ambientocclusion':not cutout,'textures':{'particle':next(iter(textures.values())),**textures},'elements':elements,**({'render_type':'neoforge:cutout'} if cutout else {})})
def local(name):return 'logos_dimension:block/'+name
def stone_texture(name,color,kind='rock'):
    rng=random.Random(name);c=rgb(color);im=Image.new('RGBA',(32,32),c+(255,));q=ImageDraw.Draw(im)
    for n in range(55):
        x=rng.randrange(32);y=rng.randrange(32);w=rng.randrange(2,9);h=rng.randrange(1,5)
        q.polygon([(x,y),(x+w,y-1),(x+w-1,y+h),(x+1,y+h+1)],fill=tint(c,rng.randrange(-13,14))+(255,))
    if kind in ['rock','slate','limestone']:
        for y in [6,18,28]:
            points=[(x,y+int(1.5*math.sin(x*.4+y))) for x in range(-2,35,3)]
            q.line(points,fill=tint(c,-30)+(255,),width=1);q.line([(x,yy+1) for x,yy in points],fill=tint(c,13)+(255,),width=1)
        for x,y in [(4,8),(23,19),(13,29)]:q.line([(x,y),(x-2,y+4),(x+1,y+7)],fill=tint(c,-23)+(255,),width=1)
    if kind=='bricks':
        for y in [0,8,16,24]:
            q.line((0,y,31,y),fill=tint(c,-40)+(255,));q.line((0,y+1,31,y+1),fill=tint(c,16)+(255,))
            for x in range(-16+(y//8%2)*8,33,16):q.line((x,y,x,y+8),fill=tint(c,-36)+(255,))
    if kind in ['wood','bark']:
        q.rectangle((0,0,31,31),fill=c+(255,))
        for x in range(0,32,3):
            points=[(x+round(math.sin(y*.23+x)*1.1),y) for y in range(32)]
            q.line(points,fill=tint(c,-24 if x%2 else 15)+(255,),width=1)
        for x,y in [(8,12),(23,27)]:q.ellipse((x-2,y-4,x+2,y+4),outline=tint(c,-28)+(255,));q.line((x,y-2,x,y+2),fill=tint(c,16)+(255,))
    if kind=='plaster':
        for n in range(40):
            x=rng.randrange(32);y=rng.randrange(32);q.point((x,y),fill=tint(c,rng.choice([-17,13]))+(255,))
        q.line([(2,1),(3,7),(1,10)],fill=tint(c,-24)+(255,))
    if kind=='tiles':
        for y in range(0,32,8):
            for x in range(-8+(y//8%2)*4,32,8):
                q.rectangle((x,y,x+7,y+7),fill=tint(c,-7)+(255,));q.line((x+3,y+1,x+3,y+6),fill=tint(c,20)+(255,),width=2)
                q.line((x,y+7,x+7,y+7),fill=tint(c,-32)+(255,),width=1);q.line((x+7,y,x+7,y+7),fill=tint(c,-26)+(255,))
    if name=='journey_timber_frame':
        for x in [0,27]:q.rectangle((x,0,x+4,31),fill='#514336')
        for y in [0,27]:q.rectangle((0,y,31,y+4),fill='#514336')
        q.line((4,4,27,27),fill='#66513d',width=3);q.line((5,4,28,27),fill='#8b7250',width=1)
    save(im,name)

solids={
 'journey_limestone':('#a9adb0','limestone'), 'journey_lime_plaster':('#d6d0bf','plaster'),
 'journey_timber_frame':('#d5cbb8','plaster'), 'journey_roof_tile':('#8f5447','tiles'),
 'cloudsea_porcelain':('#c3cdcd','plaster'), 'cloudsea_mantle':('#68777b','rock'),
 'cloudsea_demonstone':('#514c5e','rock'), 'cloudsea_ivory_bricks':('#c1c0b6','bricks'),
 'cloudsea_dark_timber':('#51443b','wood'), 'cloudsea_blue_slate':('#586c77','slate'),
 'cloudsea_lit_window':('#b6a477','plaster'), 'cloudsea_stem':('#716b59','bark')}
for name,(color,kind) in solids.items():
    stone_texture(name,color,kind);elements=[cube([0,0,0],[16,16,16],cull=True)];textures={'body':local(name)}
    if name=='journey_limestone':
        stone_texture(name+'_top','#b7bab9','limestone')
        # Recessed bedding joint, solid outline and culled internal faces.
        elements=[cube([0,0,0],[16,16,16],cull=True,sides=('east','west','up','down'))]
        for side in ['north','south']:
            for y,h,inset in [(0,6,0),(6,4,.22),(10,6,0)]:
                z=inset if side=='north' else 16-inset
                elements.append({'from':[0,y,z],'to':[16,y+h,z],'faces':{side:face('body',[0,16-y-h,16,16-y],side)}})
            for y,normal in [(6,'up'),(10,'down')]:
                elements.append({'from':[0,y,0 if side=='north' else 15.78],'to':[16,y,.22 if side=='north' else 16],
                                 'faces':{normal:face('body',[0,0,16,.22],side)}})
        elements[0]['faces']['up']['texture']='#top';textures['top']=local(name+'_top')
    elif name=='journey_timber_frame':
        textures['beam']=local('cloudsea_dark_timber')
        del elements[0]['faces']['north']
        elements.append({'from':[0,0,.7],'to':[16,16,.7],'faces':{'north':face('body',cull='north')}})
        for x in [0,13]:elements.append(cube([x,0,0],[x+3,16,.5],'beam',sides=('north','west','east')))
        for y in [0,13]:elements.append(cube([3,y,0],[13,y+3,.5],'beam',sides=('north','up','down')))
        e=cube([7,1,.02],[9,15,.65],'beam',sides=('north','west','east'));e['rotation']={'axis':'z','angle':45,'origin':[8,8,8]};elements.append(e)
    elif name in ['journey_roof_tile','cloudsea_blue_slate']:
        elements=[]
        for z in range(0,16,4):elements.append(cube([0,0,z],[16,16-(z%8)*.08,z+4],cull=True))
    elif name=='cloudsea_lit_window':
        stone_texture('journey_window_glass','#d2b578','plaster');textures.update(beam=local('cloudsea_dark_timber'),glass=local('journey_window_glass'))
        elements=[cube([1,1,1],[15,15,15],'glass',True)]
        for x in [0,7,14]:elements.append(cube([x,0,0],[x+2,16,16],'beam',True,sides=('north','south','east','west')))
        for y in [0,7,14]:elements.append(cube([0,y,0],[16,y+2,16],'beam',True,sides=('north','south','up','down')))
    elif name=='cloudsea_stem':
        im=Image.new('RGBA',(32,32),'#9e9477');q=ImageDraw.Draw(im)
        for radius in [3,6,9,12,15]:q.ellipse((16-radius,16-radius,16+radius,16+radius),outline='#766f59',width=1)
        q.line((16,16,28,26),fill='#665e4f',width=1);save(im,'journey_beech_end');textures['end']=local('journey_beech_end')
        for side in ['up','down']:elements[0]['faces'][side]['texture']='#end'
    model(name,elements,textures)

biomes=re.findall(r'([A-Z_]+)\("([^\"]+)",0x([A-F0-9]+),0x([A-F0-9]+)',(ROOT/'src/main/java/dev/logos/dimension/cloudsea/CloudseaBiome.java').read_text(encoding='utf-8'))
for key,title,ground,leaf in biomes:
    bid='cloudsea_'+key.lower();rng=random.Random(bid);evergreen=key in ['LILAC_STEPS','CLOUDROOT_SHELVES','FIR_HEART']
    for season in range(4):
        c=rgb(leaf);c=tint(c,9 if season==0 else 0)
        if season==2 and not evergreen:c=[(161,115,48),(164,94,46),(178,137,58)][sum(map(ord,key))%3]
        if season==3 and not evergreen:c=(130,144,131)
        im=Image.new('RGBA',(32,32));q=ImageDraw.Draw(im);fringe=Image.new('RGBA',(32,32));f=ImageDraw.Draw(fringe)
        # Overlapping serrated leaf clusters, avoiding the old regular tiled rectangles.
        for n in range(42 if season!=3 or evergreen else 27):
            x=rng.randrange(-3,33);y=rng.randrange(-3,33);w=rng.randrange(4,9);h=rng.randrange(3,6);shade=tint(c,rng.randrange(-22,20))+(255,)
            points=[(x-w//2,y),(x-2,y-h//2),(x,y-h),(x+2,y-h//2),(x+w//2,y),(x+2,y+1),(x,y+h//2),(x-2,y+1)]
            q.polygon(points,fill=shade);q.line((x-w//2+1,y,x+w//2-1,y),fill=tint(c,22)+(255,),width=1)
            if n%3==0:f.polygon(points,fill=shade);f.line((x,y-h+1,x,y+h//2),fill=tint(c,25)+(255,),width=1)
        name=f'{bid}_leaf_{season}';save(im,name);save(fringe,name+'_fringe')
        elements=[cube([0,0,0],[16,16,16],cull=True)]
        elements += [blade(8,8,1,16,14,angle=45,tex='fringe',cull=True),horizontal(14.9,'fringe',cull=True)]
        model(name,elements,{'body':local(name),'fringe':local(name+'_fringe')},True)
        # Preserve the historical leaf RNG stream after moving turf art to its own generator.
        for _ in range(170):rng.randrange(32);rng.randrange(32);rng.randrange(-15,16)
        for _ in range(100):rng.randrange(32);rng.randrange(32);rng.randrange(3)

stone_texture('journey_botanical_stem','#62764a','wood');stone_texture('journey_botanical_bark','#786144','bark')
stone_texture('journey_botanical_leaf','#647d48','plaster')
base_plants=['woodland_fern','bracken','bluebell','foxglove','lupine','meadow_grass','river_reed']
seasonal=['silver_anemone','moonbell','glowcap','heather','amber_fern','winter_hellebore','fireweed','berry_bramble']
legacy={'cloudsea_lantern_bud':'moonbell','cloudsea_hanging_root':'bracken','cloudsea_eye_fern':'amber_fern','cloudsea_demon_reed':'river_reed'}

def draw_plant(kind,season):
    rng=random.Random(kind+str(season));im=Image.new('RGBA',(32,32));q=ImageDraw.Draw(im)
    green=['#6e8b4e','#52773f','#9b844a','#74816a'][season];bright=['#a8be79','#8fa963','#c3a96b','#bbc9b6'][season]
    flowers={'silver_anemone':'#f1ebd7','moonbell':'#d5e5eb','bluebell':'#8a9fcb','foxglove':'#b782ad','lupine':'#9385bd','winter_hellebore':'#e2e7d1','fireweed':'#c789ad','heather':'#baa0b2'}
    if kind=='meadow_grass':
        for x in range(32):
            for y in range(27+rng.randrange(4),32):q.point((x,y),fill=green)
        for x in range(-1,34,2):
            y=rng.randrange(3,20);bend=rng.randrange(-5,6);q.line([(x,31),(x-1,20),(x+bend,y)],fill=green,width=2);q.line((x,29,x+bend,y+1),fill=bright,width=1)
    elif kind in ['woodland_fern','bracken','amber_fern']:
        for origin,tip in [((16,31),(3,7)),((16,31),(15,1)),((16,31),(29,6))]:
            q.line([origin,tip],fill=bright,width=1)
            for k in range(2,10):
                t=k/11;x=round(origin[0]+(tip[0]-origin[0])*t);y=round(origin[1]+(tip[1]-origin[1])*t);span=round(5*math.sin(t*math.pi))
                for sign in [-1,1]:q.line((x,y,x+span*sign,y-4),fill=green,width=2);q.line((x,y,x+span*sign,y-5),fill=bright,width=1)
    elif kind in ['heather','berry_bramble']:
        for x in [3,9,16,23,29]:
            top=rng.randrange(3,12);q.line((16,31,x,top),fill='#7c6246',width=1)
            for y in range(top+2,30,4):
                q.polygon([(x-4,y),(x,y-3),(x+4,y),(x,y+3)],fill=green);q.line((x-3,y,x+3,y),fill=bright)
                if season in [1,2]:q.ellipse((x-1,y-3,x+1,y-1),fill='#61476e' if kind=='berry_bramble' else '#c09cae')
    elif kind=='river_reed':
        for x,h in [(3,9),(11,2),(20,5),(28,12)]:
            q.line((x,31,x,h+5),fill=bright,width=1);q.rectangle((x-1,h,x+1,h+7),fill='#8c7952')
            q.polygon([(x,30),(x-7,11),(x-4,24),(x,31)],fill=green);q.polygon([(x,31),(x+6,17),(x+3,27)],fill=green)
    elif kind=='glowcap':
        for x,y,r in [(7,20,6),(21,12,8),(25,26,4)]:
            q.rectangle((x-1,y+2,x+1,31),fill='#c7cbb1');q.pieslice((x-r,y-r//2,x+r,y+r),180,360,fill='#a2b8ae' if season in [1,2] else '#78867c');q.line((x-r,y+2,x+r,y+2),fill='#d2e3c4',width=1)
    else:
        tall=kind in ['foxglove','lupine','fireweed'];color=flowers.get(kind,'#d6dabb')
        active=season in ([0,3] if kind=='winter_hellebore' else [1,2] if tall or kind=='heather' else [0,1])
        for x,top in ([(8,9),(21,4)] if not tall else [(16,1)]):
            q.line((x,31,x,top),fill=green,width=1)
            for y in [20,26]:q.polygon([(x,y+3),(x-8,y-3),(x-4,y-5),(x,y)],fill=green);q.polygon([(x,y),(x+7,y-6),(x+9,y-3),(x,y+3)],fill=bright)
            if active:
                if tall:
                    for y in range(top,21,3):
                        xx=x+(-3 if y%2 else 3);q.ellipse((xx-3,y,xx+2,y+4),fill=color);q.point((xx-1,y),fill='#e3d5dc')
                elif kind=='moonbell':
                    q.arc((x-4,top,x+4,top+9),180,360,fill=color,width=3);q.line((x-4,top+5,x+4,top+5),fill=color,width=2)
                else:
                    for dx,dy in [(-3,0),(3,0),(0,-3),(0,3)]:q.ellipse((x+dx-2,top+dy-2,x+dx+2,top+dy+2),fill=color)
                    q.rectangle((x-1,top-1,x+1,top+1),fill='#c7ad56')
    return im

def plant_model(name,kind,season=0):
    texture=name;save(draw_plant(kind,season),texture)
    elements=[blade(angle=45),blade(angle=-45)]
    if kind=='meadow_grass':
        elements=[blade(8,z,0,16,h) for z,h in [(2,9),(6,13),(10,11),(14,10)]]+[blade(x,8,0,16,12,'x') for x in [4,12]]
        # The mat fills the block footprint instead of converging to a central dot.
        mat=Image.new('RGBA',(32,32));q=ImageDraw.Draw(mat);rng=random.Random('meadow_mat')
        for n in range(370):
            x=rng.randrange(32);y=rng.randrange(32);q.line((x,y,x+1,y-2),fill=rng.choice(['#68834b','#809557','#9eac70']),width=2)
        save(mat,'journey_meadow_mat');elements.append(horizontal(.15,'mat'))
    elif kind in ['woodland_fern','bracken','amber_fern']:
        elements=[blade(8,8,.5,15,h,angle=a) for a,h in [(0,12),(45,14),(-45,13)]]+[blade(8,8,.5,15,11,'x')]
        elements.append(horizontal(3,'plant',1,15))
    elif kind in ['heather','berry_bramble']:
        elements=[blade(8,z,0,14,h,angle=a) for z,h,a in [(4,11,0),(10,13,45),(13,10,-45)]]+[blade(4,8,0,12,12,'x')]
        elements.append(cube([7.6,0,7.6],[8.4,10,8.4],'bark',sides=('north','south','east','west')))
    elif kind=='glowcap':
        elements=[]
        for x,z,h,w in [(4,5,6,5),(10,11,10,7),(13,4,4,4)]:
            elements.append(cube([x-.5,0,z-.5],[x+.5,h,z+.5],'stem'))
            elements.append(cube([x-w/2,h-1,z-w/2],[x+w/2,h+1,z+w/2],'cap'))
            elements.append(cube([x-w/2+1,h+1,z-w/2+1],[x+w/2-1,h+2,z+w/2-1],'cap',sides=('north','south','east','west','up')))
        stone_texture(texture+'_cap','#a1b9ae' if season in [1,2] else '#7c897d','plaster')
    elif kind=='river_reed':elements=[blade(8,z,0,16,h) for z,h in [(3,14),(9,16),(14,13)]]+[blade(8,8,0,16,15,'x')]
    elif kind in ['silver_anemone','winter_hellebore']:
        elements=[blade(8,5,0,14,11,angle=45),blade(8,11,0,15,12,angle=-45),horizontal(2,'plant',0,16),blade(8,8,0,14,12,'x')]
    else:
        elements=[blade(8,z,0,13,h,angle=a) for z,h,a in [(5,14,45),(11,16,-45)]]+[blade(8,8,0,14,13,'x')]
        elements.append(cube([7.7,0,7.7],[8.3,12,8.3],'stem',sides=('north','south','east','west')))
    textures={'plant':local(texture),'stem':local('journey_botanical_stem'),'bark':local('journey_botanical_bark')}
    if kind=='meadow_grass':textures['mat']=local('journey_meadow_mat')
    if kind=='glowcap':textures['cap']=local(texture+'_cap')
    model(name,elements,textures,True)

for kind in base_plants:plant_model('journey_'+kind,kind,1 if kind in ['foxglove','lupine'] else 0)
for kind in seasonal:
    for season in range(4):plant_model('journey_'+kind+'_'+str(season),kind,season)
for name,kind in legacy.items():plant_model(name,kind)
# Rotate dense groundcover so adjacent tiles do not show regular parallel rows.
for kind in base_plants:
    name='journey_'+kind
    write(A/'blockstates'/f'{name}.json',{'variants':{'':[{'model':local(name),'y':y} for y in [0,90,180,270]]}})

print('Journey art: all 32 turf/leaf families, 12 solids, 43 plant models; culled leaf fringes and full-footprint grass.')
runpy.run_path(str(ROOT/'tools/journey_turf.py'))
