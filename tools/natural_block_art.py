"""Pixel-authored, block-scale materials. Runtime geometry stays bounded and neighbour-cullable."""
import json
import math
import random
import re
from PIL import Image, ImageDraw
from polish_models import ASSETS, ROOT, NS, box, model, write


def shade(c, factor=1, mix=None, amount=0):
    rgb=tuple(max(0,min(255,round(v*factor))) for v in c[:3])
    return tuple(round(a*(1-amount)+b*amount) for a,b in zip(rgb,mix))+(255,) if mix else rgb+(255,)


def save(name, image):
    folder=ASSETS/'textures/block'
    image.save(folder/f'{name}.png')
    (folder/f'{name}.png.mcmeta').unlink(missing_ok=True)


def pixel_material(name,color,kind):
    rng=random.Random(name); im=Image.new('RGBA',(16,16),shade(color));d=ImageDraw.Draw(im)
    # Larger colour clusters read as material at a distance, unlike independent pixel noise.
    for _ in range(37):
        x,y=rng.randrange(16),rng.randrange(16);w,h=rng.randrange(2,6),rng.randrange(1,4)
        d.rectangle((x,y,x+w,y+h),fill=shade(color,rng.choice((.91,.96,1.02,1.06))))
    if kind=='bark':
        for x in (1,5,9,14):
            pts=[(x,0),(x-1,4),(x,8),(x+1,12),(x,15)]
            d.line(pts,fill=shade(color,.68));d.line([(a+1,b) for a,b in pts],fill=shade(color,1.18))
        d.line([(7,6),(6,8),(7,11),(8,9),(7,6)],fill=shade(color,.75))
    elif kind=='end':
        for r in (2,5,7):d.rectangle((8-r,8-r,8+r,8+r),outline=shade(color,.79 if r!=5 else 1.12))
        d.line((8,8,14,14),fill=shade(color,.67))
    elif kind=='bricks':
        for row in (0,8):
            d.line((0,row,15,row),fill=shade(color,.65))
            d.line((0,row+1,15,row+1),fill=shade(color,1.12))
            for x in ((0,8) if row==0 else (4,12)):
                d.line((x,row+1,x,row+7),fill=shade(color,.72))
        d.line((2,5,5,5),fill=shade(color,.92))
    elif kind=='slate':
        for row in (0,5,10,15):
            d.line((0,row,15,row),fill=shade(color,.62))
            if row<15:d.line((0,row+1,15,row+1),fill=shade(color,1.17))
            for x in (3+(row//5%2)*5,11+(row//5%2)*5):d.line((x,row,x,row+4),fill=shade(color,.72))
    elif kind=='marble':
        d.line([(0,11),(3,10),(4,8),(7,7),(9,4),(12,4),(15,2)],fill=shade(color,.86))
        d.line([(4,8),(4,6),(2,5)],fill=shade(color,.92))
    elif kind=='strata':
        for row in (4,11):d.line([(0,row),(5,row+1),(9,row),(15,row)],fill=shade(color,.82))
    elif kind=='char':
        d.line([(0,5),(5,4),(8,7),(7,11),(10,15)],fill=shade(color,.58))
        d.line([(8,7),(14,6),(15,3)],fill=shade(color,.65))
    elif kind=='metal':
        for x in range(16):
            v=.83+.24*math.sin((x+3)*math.pi/19)
            for y in range(16):im.putpixel((x,y),shade(color,v+rng.choice((-.02,0,.02))))
        d.line((1,2,14,2),fill=shade(color,1.16));d.line((2,13,14,13),fill=shade(color,.82))
    elif kind=='crystal':
        d.polygon([(0,0),(10,0),(7,7),(0,12)],fill=shade(color,1.10))
        d.polygon([(7,7),(15,3),(15,15),(5,15)],fill=shade(color,.91))
        d.line([(0,12),(7,7),(10,0)],fill=shade(color,1.18))
    elif kind=='soil':
        for _ in range(13):
            x,y=rng.randrange(15),rng.randrange(15);d.rectangle((x,y,x+1,y+1),fill=shade(color,.72))
    elif kind=='turf':
        for _ in range(19):
            x,y=rng.randrange(16),rng.randrange(16);d.line((x,y,x+1,y-1),fill=shade(color,rng.choice((.76,1.17))))
    save(name,im)


def full_cube(name,color,kind='strata',ground=False,glow=False):
    pixel_material(name,color,kind)
    textures={'body':f'{NS}:block/{name}'}
    body=box('continuous_'+kind,[0,0,0],[16,16,16],cull=True)
    if ground:
        side=tuple(int(v*.77) for v in color)
        pixel_material(name+'_side',side,'soil' if kind=='turf' else 'strata')
        image=Image.open(ASSETS/'textures/block'/f'{name}_side.png');top=Image.open(ASSETS/'textures/block'/f'{name}.png')
        # Irregular two-pixel cap, rather than disconnected raised plates on every block.
        for x in range(16):
            depth=2+(x*7+color[0])%3
            for y in range(depth):image.putpixel((x,y),top.getpixel((x,y)))
        save(name+'_side',image);textures['side']=f'{NS}:block/{name}_side'
        for face in ('north','south','east','west'):body['faces'][face]['texture']='#side'
    model(name,[body],textures)


def foliage(name,color,family,seed):
    rng=random.Random(seed);base=shade(color,.66);im=Image.new('RGBA',(16,16),base);d=ImageDraw.Draw(im)
    # Connected leaf clusters, with an opaque interior and a few purposeful air gaps.
    for _ in range(24):
        x,y=rng.randrange(-2,16),rng.randrange(-2,16)
        w,h=(rng.randrange(3,7),rng.randrange(2,5)) if family not in ('SPIRE','STEPS') else (rng.randrange(2,4),rng.randrange(4,7))
        d.ellipse((x,y,x+w,y+h),fill=shade(color,rng.choice((.79,.9,1.0))))
        d.line((x+1,y+1,x+w-1,y+1),fill=shade(color,1.10,mix=(224,234,201),amount=.10))
    for _ in range(10):
        x,y=rng.randrange(16),rng.randrange(16)
        d.rectangle((x,y,x+rng.randrange(1,3),y+1),fill=(0,0,0,0))
    if family in ('PETAL','HANGING'):
        for _ in range(3):
            x,y=rng.randrange(14),rng.randrange(14)
            d.point((x,y),fill=(230,217,165,255));d.point((x+1,y),fill=(182,160,105,255))
    save(name,im)
    model(name,[box('full_leaf_cluster',[0,0,0],[16,16,16],cull=True)],{'body':f'{NS}:block/{name}'},True)
    path=ASSETS/'models/block'/f'{name}.json';spec=json.loads(path.read_text());spec['ambientocclusion']=False;write(path,spec)
    write(ASSETS/'blockstates'/f'{name}.json',{'variants':{'':[{'model':f'{NS}:block/{name}','y':a} for a in (0,90,180,270)]}})


def log(name,color):
    pixel_material(name,color,'bark');pixel_material(name+'_end',shade(color,1.32)[:3],'end')
    e=box('full_structural_log',[0,0,0],[16,16,16],cull=True)
    for face in ('up','down'):e['faces'][face]['texture']='#end'
    model(name,[e],{'body':f'{NS}:block/{name}','end':f'{NS}:block/{name}_end'})
    variants={'axis=y':{'model':f'{NS}:block/{name}'},'axis=x':{'model':f'{NS}:block/{name}','x':90,'y':90},'axis=z':{'model':f'{NS}:block/{name}','x':90}}
    write(ASSETS/'blockstates'/f'{name}.json',{'variants':variants})


def cloudsea_models():
    source=(ROOT/'src/main/java/dev/logos/dimension/cloudsea/CloudseaBiome.java').read_text(encoding='utf-8')
    specs=re.findall(r'([A-Z_]+)\("[^"]+",0x([A-F0-9]+),0x([A-F0-9]+),Form\.([A-Z]+)',source)
    for i,(key,ground,leaf,form) in enumerate(specs):
        # A darker foliage layer separates living canopies from pale mineral terrain.
        full_cube('cloudsea_'+key.lower()+'_ground',tuple(bytes.fromhex(ground)),'turf' if form in ('PETAL','HANGING') else 'crystal' if form=='SPIRE' else 'strata',True)
        foliage('cloudsea_'+key.lower()+'_leaf',tuple(int(v*.87) for v in bytes.fromhex(leaf)),form,i+71)
    for name,color,kind in (
        ('porcelain',(190,202,198),'marble'),('mantle',(89,106,120),'strata'),('demonstone',(63,48,71),'char'),
        ('ivory_bricks',(202,196,172),'bricks'),('dark_timber',(68,59,48),'bark'),('blue_slate',(65,85,106),'slate')):
        full_cube('cloudsea_'+name,color,kind)
    log('cloudsea_stem',(91,111,105))
    # Warm recessed panes read as a window at distance, without thirty extra unculled bars.
    im=Image.new('RGBA',(16,16),(64,61,51,255));d=ImageDraw.Draw(im)
    for x,y in ((2,2),(9,2),(2,9),(9,9)):
        d.rectangle((x,y,x+4,y+4),fill=(211,166,83,255));d.line((x,y,x+4,y),fill=(249,220,151,255));d.line((x,y,x,y+4),fill=(231,191,109,255))
    save('cloudsea_lit_window',im)
    model('cloudsea_lit_window',[box('recessed_window',[0,0,0],[16,16,16],cull=True,emission=4)],{'body':f'{NS}:block/cloudsea_lit_window'})
    tags=ROOT/'src/main/resources/data/minecraft/tags/block'
    for tag,values in [('leaves',[f'{NS}:cloudsea_{s[0].lower()}_leaf' for s in specs]),('logs',[f'{NS}:cloudsea_stem']),('mineable/axe',[f'{NS}:cloudsea_stem',f'{NS}:cloudsea_dark_timber'])]:
        path=tags/f'{tag}.json';previous=json.loads(path.read_text()) if path.exists() else {'replace':False,'values':[]}
        previous['values']=list(dict.fromkeys(previous['values']+values));write(path,previous)


def crucible_models():
    for name,color,kind,ground in (
        ('strata_stone',(114,122,118),'strata',False),('aether_soil',(80,76,60),'soil',False),('aether_turf',(101,143,120),'turf',True),
        ('island_stone',(189,192,176),'marble',False),('charred_stone',(47,43,43),'char',False),('mercury_metal',(155,171,179),'metal',False)):
        full_cube(name,color,kind,ground)
    for name,color,kind in (('cinnabar',(147,67,57),'char'),('sulfur_deposit',(173,148,67),'crystal'),('mercury_crust',(134,155,162),'metal'),('salt_crust',(206,203,186),'strata'),('salt_crystal',(208,225,219),'crystal')):
        for phase,factor in enumerate((.94,1.03,1.0,.98)):full_cube(f'{name}_{phase}',shade(color,factor)[:3],kind)
    log('fan_trunk',(76,68,54))
    foliage('fan_leaves',(101,150,128),'PETAL',1201)
    foliage('glass_leaves',(124,173,178),'SPIRE',1202)
    # The enlarged cauldron, individual flowers, lantern, roots and ferns keep their own anatomy.


def catalogue():
    paths=sorted((ASSETS/'models/block').glob('*.json'))
    rows=['# 方块模型与材质目录','','树叶使用完整块体与镂空叶簇贴图，树干使用可定向原木。地表与砌块用可剔除内部面的完整模型，通过专用像素材质区分；花、蕨、垂根、灯蕊和炼金釜保留专用部件。去除贴在每块地面上的重复微型浮雕。','','| 模型 | 部件数 | 外观结构 |','| --- | --- | --- |']
    for path in paths:
        spec=json.loads(path.read_text(encoding='utf-8'));elements=spec.get('elements',[])
        rows.append(f'| `{path.stem}` | {len(elements)} | '+', '.join(e.get('name','cube') for e in elements)+' |')
    (ROOT/'docs/INDIVIDUAL_BLOCK_MODELS.md').write_text('\n'.join(rows)+'\n',encoding='utf-8')
