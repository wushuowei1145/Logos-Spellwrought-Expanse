"""Authored block geometry and material patterns, shared by both asset generators.

No registrations or terrain changes: existing IDs remain valid in saved worlds.
"""
from pathlib import Path
import json
import math
import random
import re
from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'src/main/resources/assets/logos_dimension'
NS = 'logos_dimension'
FACES = ('up', 'down', 'north', 'south', 'east', 'west')


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def tint(color, delta, alpha=255):
    return tuple(max(0, min(255, int(c + delta))) for c in color) + (alpha,)


def texture(name, color, kind='stone', glow=False):
    """32px material with a separate, sparse emissive mask; never a uniformly luminous cube."""
    rng = random.Random(name)
    image = Image.new('RGBA', (32, 32))
    for y in range(32):
        for x in range(32):
            value = rng.choice((-3, -2, -1, 0, 0, 1, 2, 3))
            if kind in ('stone', 'side', 'porcelain'):
                value += int(3 * math.sin(y * .65 + math.sin(x / 11)))
            image.putpixel((x, y), tint(color, value))
    d = ImageDraw.Draw(image)
    dark, light = tint(color, -26), tint(color, 18)
    mask = Image.new('RGBA', (32, 32))
    m = ImageDraw.Draw(mask)
    gleam = tint(tuple(int(c * .45 + v * .55) for c, v in zip(color, (205, 239, 232))), 6)
    if kind in ('stone', 'side', 'porcelain'):
        for row in (7, 20, 30):
            line = [(x, row + int(1.8 * math.sin(x / 9 + row))) for x in range(32)]
            d.line(line, fill=tint(color, -12), width=1)
        if kind != 'side':
            d.line([(2, 3), (11, 6), (15, 13), (23, 16), (31, 14)], fill=light)
        if kind == 'side':
            d.rectangle((0, 0, 31, 3), fill=tint(color, 12))
        if glow:
            m.line([(5, 8), (10, 11), (10, 15), (19, 19), (25, 18)], fill=gleam)
    elif kind == 'brick':
        for row in range(0, 32, 8):
            d.line((0, row, 31, row), fill=dark)
            d.line((0, row+1, 31, row+1), fill=light)
            for col in range(-16, 40, 16):
                x = col + (8 if (row//8)%2 else 0)
                d.line((x, row+1, x, row+7), fill=dark)
                d.line((x+1, row+2, x+1, row+6), fill=tint(color, -8))
    elif kind == 'wood':
        for x in (2, 7, 13, 19, 27):
            d.line([(x + int(math.sin(y/7+x)*1.2), y) for y in range(32)], fill=tint(color, -15))
        d.ellipse((10, 11, 17, 22), outline=tint(color, 13))
        d.ellipse((12, 14, 15, 19), outline=tint(color, -12))
    elif kind == 'endgrain':
        for r in (3, 7, 11, 15):
            d.ellipse((16-r, 16-r, 16+r, 16+r), outline=tint(color, -12 if r%2 else 9))
        d.line((17, 17, 29, 27), fill=tint(color, -23))
    elif kind == 'slate':
        for row in range(0, 32, 8):
            d.line((0, row, 31, row), fill=dark, width=2)
            d.line((0, row+2, 31, row+2), fill=light)
            for x in range(0, 32, 8):
                shift = 4 if (row//8)%2 else 0
                d.line((x+shift, row+3, x+shift, row+7), fill=tint(color, -17))
    elif kind == 'window':
        image = Image.new('RGBA', (32, 32), (47, 57, 66, 255)); d = ImageDraw.Draw(image)
        for box in ((3,3,14,14), (17,3,28,14), (3,17,14,28), (17,17,28,28)):
            d.rectangle(box, fill=(199, 164, 91, 255), outline=(110, 94, 62, 255), width=1)
            d.line((box[0]+2,box[1]+1,box[0]+2,box[3]-2),fill=(243,218,154,255))
            m.rectangle((box[0]+2,box[1]+2,box[2]-2,box[3]-2),fill=(238,208,137,255))
    elif kind.startswith('ground_'):
        form = kind[7:]
        for y in range(32):
            for x in range(32):
                if form == 'fold': v = math.sin(y*.48+math.sin(x*.15)*1.4)
                elif form == 'petal': v = math.sin(math.hypot(x-16,y-16)*.65+math.atan2(y-16,x-16)*3)
                elif form == 'basin': v = math.sin(math.hypot(x-12,y-20)*.72)
                elif form == 'hanging': v = math.sin(x*.53+math.sin(y*.22))
                elif form == 'ribbon': v = math.sin((x-y*.65)*.43)
                elif form == 'steps': v = -1 if y%8 == 0 or (x+(y//8%2)*8)%16==0 else .2
                elif form == 'dune': v = math.sin(y*.4+math.sin(x*.21)*2)
                else: v = math.cos(math.atan2(y-16,x-16)*6)*.7
                d.point((x,y),fill=tint(color, int(v*6)+rng.randrange(-2,3)))
        # Distinct, thin magic seams rather than a bright, repeating checkerboard.
        path = [(1,24),(8,23),(13,19),(17,19),(20,13),(27,11),(30,5)]
        d.line(path,fill=tint(color,-15)); m.line(path,fill=gleam)
        m.rectangle((18,12,20,14),outline=gleam)
    else:  # Living tissue: central vein and thin branching capillaries.
        d.line((15, 0, 16, 31), fill=light, width=2)
        for row in (6, 14, 23):
            d.line((16,row,2,row-5),fill=tint(color,-13))
            d.line((16,row,30,row-5),fill=tint(color,-13))
        if glow: m.line((15,2,15,29),fill=gleam)
    folder = ASSETS/'textures/block'; folder.mkdir(parents=True,exist_ok=True)
    image.save(folder/f'{name}.png')
    # Former prototype textures animated the entire material. Only the inset now pulses.
    (folder/f'{name}.png.mcmeta').unlink(missing_ok=True)
    if glow:
        sheet=Image.new('RGBA',(32,128))
        for f, strength in enumerate((.52,.72,1,.72)):
            frame=mask.copy(); frame.putalpha(mask.getchannel('A').point(lambda a: int(a*strength)))
            # Keep alpha above the cutout threshold so a pulse never flashes on/off.
            sheet.paste(frame,(0,32*f))
        sheet.save(folder/f'{name}_glow.png')
        write(folder/f'{name}_glow.png.mcmeta',{'animation':{'frametime':30,'interpolate':True}})


def box(name, start, end, tex='body', rotate=None, emission=0, cull=False, faces=FACES):
    result={'name':name,'from':start,'to':end,'faces':{f:{'texture':'#'+tex} for f in faces}}
    # 26.1 computes alpha coverage from face UVs while baking. Expanded glow shells
    # must sample within the sprite, even when their geometric bounds exceed 0..16.
    x1,y1,z1=start; x2,y2,z2=end
    uv={'up':[x1,z1,x2,z2],'down':[x1,16-z2,x2,16-z1],
        'north':[16-x2,16-y2,16-x1,16-y1],'south':[x1,16-y2,x2,16-y1],
        'west':[z1,16-y2,z2,16-y1],'east':[16-z2,16-y2,16-z1,16-y1]}
    for face in faces:result['faces'][face]['uv']=[max(0,min(16,v)) for v in uv[face]]
    if cull:
        for f in faces: result['faces'][f]['cullface']=f
    if rotate: result['rotation']={'origin':[8,8,8],'axis':rotate[0],'angle':rotate[1]}
    if emission: result.update(shade=False,light_emission=emission)
    return result


def model(name, elements, textures, plant=False):
    result={'parent':'minecraft:block/block','textures':{'particle':textures.get('body',next(iter(textures.values()))),**textures},'elements':elements}
    if plant: result['render_type']='neoforge:cutout'
    write(ASSETS/'models/block'/f'{name}.json',result)


def material_block(name, color, kind='stone', glow=False, side=True):
    texture(name,color,kind,glow)
    if side: texture(name+'_side',tint(color,-9)[:3],'side',glow)
    textures={'body':f'{NS}:block/{name}','side':f'{NS}:block/{name}_side' if side else f'{NS}:block/{name}'}
    body=box('material',[0,0,0],[16,16,16],cull=True)
    for face in ('north','south','east','west'): body['faces'][face]['texture']='#side'
    elements=[body]
    if glow:
        textures.update(glow=f'{NS}:block/{name}_glow',side_glow=f'{NS}:block/{name}_side_glow' if side else f'{NS}:block/{name}_glow')
        overlay=box('inlaid_mana',[ -.004,-.004,-.004],[16.004,16.004,16.004],'glow',emission=5,cull=True)
        for face in ('north','south','east','west'): overlay['faces'][face]['texture']='#side_glow'
        elements.append(overlay)
    model(name,elements,textures,glow)


def leaf_elements(form):
    # Thin faceted leaf shells, ribs and small luminous insets. Maximum 12 elements.
    e=[]
    if form in ('fold','ribbon','dune'):
        width = 3 if form=='ribbon' else 5
        for side in (-1,1):
            for i in range(2):
                y=6+i*2
                e.append(box(f'fold_{side}_{i}',[1+i*2,y,8+(0 if side==1 else -width)],[15-i*2,y+.55,8+(width if side==1 else 0)],rotate=('x',side*22.5)))
        e.append(box('midrib',[1,6.6,7.7],[15,7.2,8.3],'rib'))
        e.append(box('vein_light',[4,7.25,7.85],[12,7.35,8.15],'glow',emission=5))
    elif form in ('petal','basin'):
        for angle in (-45,0,45):
            e.append(box(f'petal_{angle}',[4,6,2],[12,6.6,14],rotate=('y',angle)))
        e.append(box('cup_lip_left',[2,7,5],[4,7.6,11],rotate=('z',-22.5)))
        e.append(box('cup_lip_right',[12,7,5],[14,7.6,11],rotate=('z',22.5)))
        e.append(box('luminous_seed',[7,6.7,7],[9,8,9],'glow',emission=7))
    elif form=='hanging':
        e.append(box('hanging_stalk',[7.65,2,7.65],[8.35,16,8.35],'rib'))
        for i in range(3):
            for side in (-1,1):
                e.append(box(f'drooping_leaf_{i}_{side}',[8+(0 if side>0 else -5),3+i*4,6],[8+(5 if side>0 else 0),3.55+i*4,10],rotate=('z',side*-22.5)))
        e.append(box('hanging_light',[7,0,7],[9,2,9],'glow',emission=7))
    elif form=='steps':
        e.append(box('stem',[7.7,0,7.7],[8.3,15,8.3],'rib'))
        for i in range(4):
            side=1 if i%2 else -1
            e.append(box(f'bell_leaf_{i}',[8+(0 if side>0 else -6),3+i*3,6],[8+(6 if side>0 else 0),3.6+i*3,10],rotate=('z',side*22.5)))
            e.append(box(f'bell_drop_{i}',[8+side*4-.6,1+i*3,7.4],[8+side*4+.6,3+i*3,8.6],'glow',emission=5))
    else:
        e.extend([box('spine',[7.4,0,7.4],[8.6,16,8.6],'rib'),box('crystal_mid',[5.7,4,5.7],[10.3,12,10.3],rotate=('y',45)),box('crystal_crown',[6.5,11,6.5],[9.5,15,9.5],rotate=('y',45)),box('core',[7.3,5,7.3],[8.7,12,8.7],'glow',emission=7)])
    return e


def flora(name, color, form):
    texture(name,color,'leaf')
    texture(name+'_rib',tint(color,-31)[:3],'wood')
    texture(name+'_core',tuple(int(c*.25+v*.75) for c,v in zip(color,(223,234,189))),'core')
    tex={'body':f'{NS}:block/{name}','rib':f'{NS}:block/{name}_rib','glow':f'{NS}:block/{name}_core'}
    if form=='stem':
        e=[box('stalk',[7,0,7],[9,16,9]),box('node_collar',[6.5,6,6.5],[9.5,7,9.5],'rib'),box('light_channel',[7.7,0,6.95],[8.3,16,7],'glow',emission=4)]
    elif form=='lantern':
        e=[box('pendant_stem',[7.5,11,7.5],[8.5,16,8.5],'rib'),box('bud_body',[5,4,5],[11,11,11]),box('bud_tip',[6.5,1.5,6.5],[9.5,4,9.5]),box('warm_heart',[6,4.5,6],[10,10.5,10],'glow',emission=12)]
        for side in (-1,1):
            e.append(box('open_sepal_'+str(side),[8+(0 if side>0 else -5),10,6],[8+(5 if side>0 else 0),10.7,10],rotate=('z',side*22.5)))
        # Expose the heart through four vertical slits rather than enclosing its emissive faces.
        e[1]=box('upper_shell',[5,9,5],[11,11,11])
        e.extend([box('shell_left',[4.5,4,5],[5.5,9,11]),box('shell_right',[10.5,4,5],[11.5,9,11])])
    elif form=='root':
        e=[]
        for i,(x,z,length) in enumerate(((3,5,12),(8,10,15),(12,3,9))):
            e.append(box(f'root_{i}',[x,16-length,z],[x+.65,16,z+.65],'rib'))
            e.append(box(f'fork_{i}',[x-.4,16-length,z],[x+.3,21-length,z+.7],rotate=('z',22.5 if i%2 else -22.5)))
            e.append(box(f'root_pearl_{i}',[x-.35,15.4-length,z-.35],[x+1,16.75-length,z+1],'glow',emission=6))
    elif form=='eye':
        e=[box('fern_spine',[7.7,0,7.7],[8.3,14,8.3],'rib')]
        for i in range(3):
            for side in (-1,1):
                e.append(box(f'eye_leaf_{i}_{side}',[8+(0 if side>0 else -5),3+i*3,6],[8+(5 if side>0 else 0),3.6+i*3,10],rotate=('z',side*22.5)))
        e.extend([box('eye_frame',[5,11,7.5],[11,14,8.5],rotate=('z',45)),box('pupil',[7.5,11.4,7.3],[8.5,13.6,8.7],'glow',emission=7)])
    elif form=='reed':
        e=[]
        for i,(x,z,h) in enumerate(((4,5,12),(8,9,16),(12,5,10))):
            e.append(box(f'reed_stem_{i}',[x-.4,0,z-.4],[x+.4,h-3,z+.4],'rib'))
            e.append(box(f'flame_calyx_{i}',[x-1.2,h-5,z-1.2],[x+1.2,h-1,z+1.2],rotate=('y',45)))
            e.append(box(f'flame_{i}',[x-.55,h-4,z-.55],[x+.55,h,z+.55],'glow',emission=9))
    else: e=leaf_elements(form)
    model(name,e,tex,True)
    write(ASSETS/'blockstates'/f'{name}.json',{'variants':{'':[{'model':f'{NS}:block/{name}','y':angle} for angle in (0,90,180,270)]}})


def polish_cloudsea():
    source=(ROOT/'src/main/java/dev/logos/dimension/cloudsea/CloudseaBiome.java').read_text(encoding='utf-8')
    for key,ground,leaf,form in re.findall(r'([A-Z_]+)\("[^"]+",0x([A-F0-9]+),0x([A-F0-9]+),Form\.([A-Z]+)',source):
        name='cloudsea_'+key.lower()
        material_block(name+'_ground',tuple(bytes.fromhex(ground)),'ground_'+form.lower(),True)
        flora(name+'_leaf',tuple(bytes.fromhex(leaf)),form.lower())
    for name,color,kind in (
        ('cloudsea_porcelain',(198,210,206),'porcelain'),('cloudsea_mantle',(94,109,126),'stone'),
        ('cloudsea_demonstone',(68,55,82),'stone'),('cloudsea_ivory_bricks',(209,204,185),'brick'),
        ('cloudsea_dark_timber',(66,60,54),'wood'),('cloudsea_blue_slate',(79,96,120),'slate')):
        material_block(name,color,kind,False,kind in ('stone','porcelain'))
    texture('cloudsea_dark_timber_end',(81,73,59),'endgrain')
    path=ASSETS/'models/block/cloudsea_dark_timber.json'; spec=json.loads(path.read_text());spec['textures']['end']=f'{NS}:block/cloudsea_dark_timber_end'
    for f in ('up','down'):spec['elements'][0]['faces'][f]['texture']='#end'
    write(path,spec)
    material_block('cloudsea_lit_window',(223,204,153),'window',True,False)
    for name,color,kind in (('stem',(139,187,181),'stem'),('lantern_bud',(231,213,151),'lantern'),('hanging_root',(139,195,202),'root'),('eye_fern',(144,179,202),'eye'),('demon_reed',(177,105,137),'reed')):
        flora('cloudsea_'+name,color,kind)


def astrolabe():
    """Three independent transparent engravings: calligraphy is geometry, not unreadable text."""
    size=2048; center=1024; gold=(235,208,139,225); ivory=(231,241,224,240); soft=(175,209,216,165)
    def point(r,a):return (center+math.cos(a)*r,center+math.sin(a)*r)
    def ring(d,r,col=gold,width=3):d.ellipse((center-r,center-r,center+r,center+r),outline=col,width=width)
    def polygon(d,r,n,offset=0,col=gold,width=3,step=1):
        pts=[point(r,offset+i*math.tau/n*step) for i in range(n)];d.line(pts+[pts[0]],fill=col,width=width)
    def glyph(d,x,y,i,scale=1):
        r=9*scale; d.ellipse((x-r,y-r,x+r,y+r),outline=ivory,width=2)
        a=(i%4)*math.pi/2
        ux,uy=math.cos(a),math.sin(a)
        d.line((x+ux*r,y+uy*r,x+ux*r*2.7,y+uy*r*2.7),fill=gold,width=3)
        d.line((x+ux*r*2-uy*r*.65,y+uy*r*2+ux*r*.65,x+ux*r*2+uy*r*.65,y+uy*r*2-ux*r*.65),fill=gold,width=2)
        if i%3==0:d.polygon([(x,y-r*2.4),(x-r*.8,y-r),(x+r*.8,y-r)],outline=gold,width=2)
        elif i%3==1:d.arc((x-r*1.7,y-r*1.7,x+r*1.7,y+r*1.7),195,345,fill=gold,width=2)
        else:d.line((x-r*.4,y-r*2.1,x+r*.7,y-r*.6),fill=gold,width=2)
    images=[]
    for layer in range(3):
        im=Image.new('RGBA',(size,size));d=ImageDraw.Draw(im)
        if layer==0:
            for r,width in ((942,3),(930,2),(906,3),(846,2),(830,4),(810,1)):ring(d,r,gold,width)
            for i in range(180):
                a=i*math.tau/180; length=24 if i%5==0 else 10
                d.line((point(909,a),point(909+length,a)),fill=ivory if i%5==0 else soft,width=3 if i%5==0 else 2)
            for i in range(36):
                a=i*math.tau/36;x,y=point(872,a);glyph(d,x,y,i,.85)
            for i in range(12):
                a=i*math.tau/12
                x,y=point(944,a);d.line((point(932,a),point(986,a)),fill=gold,width=3)
                d.polygon([(x+math.cos(a)*25,y+math.sin(a)*25),(x-math.sin(a)*9,y+math.cos(a)*9),(x-math.cos(a)*17,y-math.sin(a)*17),(x+math.sin(a)*9,y-math.cos(a)*9)],outline=ivory,width=3)
        elif layer==1:
            for r in (758,740,560,544):ring(d,r,soft if r%4 else gold,2)
            polygon(d,730,8,math.pi/8,soft,2,3);polygon(d,690,6,0,gold,3,2);polygon(d,690,6,math.pi/3,gold,3,2)
            for i in range(8):
                a=i*math.pi/4;x,y=point(650,a)
                d.ellipse((x-64,y-64,x+64,y+64),fill=(30,43,49,85),outline=gold,width=3)
                d.ellipse((x-56,y-56,x+56,y+56),outline=soft,width=2);glyph(d,x,y,i,1.7)
                d.line((point(568,a),point(590,a)),fill=ivory,width=3)
            for i in range(24):
                a=i*math.tau/24;x,y=point(748,a)
                d.ellipse((x-4,y-4,x+4,y+4),fill=ivory)
        else:
            ring(d,483,gold,3);ring(d,470,soft,2);ring(d,333,gold,2)
            for i in range(12):
                a=i*math.tau/12
                d.arc((center-452,center-452,center+452,center+452),i*30+3,i*30+24,fill=ivory,width=3)
                x,y=point(398,a);glyph(d,x,y,i,1.15)
            polygon(d,318,4,math.pi/4,ivory,4)
            polygon(d,282,3,-math.pi/2,gold,4)
            ring(d,142,ivory,3);ring(d,126,soft,2)
            for i in range(8):
                a=i*math.pi/4;d.line((point(150,a),point(236,a)),fill=soft,width=2)
            glyph(d,center,center,0,3.2)
            # Six thin petals give the central seal a recognizable flower-like signature.
            for i in range(6):
                a=i*math.tau/6;x,y=point(72,a);d.ellipse((x-14,y-14,x+14,y+14),outline=gold,width=2)
        # A restrained halo is baked into alpha, leaving crisp line art in front.
        halo=im.filter(ImageFilter.GaussianBlur(6));halo.putalpha(halo.getchannel('A').point(lambda a:int(a*.35)))
        halo.alpha_composite(im)
        images.append(halo)
        path=ASSETS/'textures/environment'/f'astrolabe_{("outer","orbits","seal")[layer]}.png';path.parent.mkdir(parents=True,exist_ok=True);halo.save(path)
    preview=Image.new('RGBA',(size,size),(24,36,46,255))
    for im in images:preview.alpha_composite(im)
    out=ROOT/'build/asset-preview';out.mkdir(parents=True,exist_ok=True);preview.resize((1024,1024),Image.Resampling.LANCZOS).save(out/'astrolabe-polished.png')


def polish_crucible():
    for name,color,kind in (
        ('strata_stone',(116,124,122),'stone'),('aether_soil',(83,88,77),'stone'),('aether_turf',(117,153,138),'ground_fold'),
        ('island_stone',(188,193,184),'porcelain'),('charred_stone',(48,46,44),'stone'),('mercury_metal',(159,175,180),'ground_basin')):
        material_block(name,color,kind,False,True)
    for name,color in (('cinnabar',(157,71,62)),('sulfur_deposit',(178,155,76)),('mercury_crust',(136,155,161)),('salt_crust',(213,209,193)),('salt_crystal',(218,226,219))):
        for phase in range(4):
            adjusted=tuple(int(c*(.90,1.02,.98,.96)[phase]) for c in color)
            material_block(f'{name}_{phase}',adjusted,'ground_basin' if 'mercury' in name else 'porcelain',phase in (1,3),True)
    material_block('fan_trunk',(64,62,54),'wood',False,False)
    texture('fan_trunk_end',(81,76,59),'endgrain')
    path=ASSETS/'models/block/fan_trunk.json';spec=json.loads(path.read_text());spec['textures']['end']=f'{NS}:block/fan_trunk_end'
    for face in ('up','down'):spec['elements'][0]['faces'][face]['texture']='#end'
    write(path,spec)
    # Transparent, edge-cut laboratory glass; neighboring panes still cull through TransparentBlock.
    glass=Image.new('RGBA',(32,32),(179,211,218,19));gd=ImageDraw.Draw(glass)
    gd.rectangle((0,0,31,31),outline=(194,224,225,104),width=1)
    gd.line((4,9,12,2),fill=(234,246,237,95),width=1)
    gd.line((6,12,16,2),fill=(234,246,237,56),width=1)
    glass.save(ASSETS/'textures/block/distillation_glass.png')
    model('distillation_glass',[box('blown_glass',[0,0,0],[16,16,16],cull=True)],{'body':f'{NS}:block/distillation_glass'})
    path=ASSETS/'models/block/distillation_glass.json';spec=json.loads(path.read_text());spec['render_type']='neoforge:translucent';write(path,spec)
    # Give the original portal stone a raised liquid lens, retaining a full collision pedestal.
    material_block('vortex',(93,106,107),'porcelain',False,True)
    path=ASSETS/'models/block/vortex.json';spec=json.loads(path.read_text());spec['textures']['liquid']=f'{NS}:block/cauldron_vortex'
    for face in ('up',):spec['elements'][0]['faces'][face]['texture']='#liquid'
    spec['elements'].append(box('raised_liquid_lens',[2,16.01,2],[14,16.02,14],'liquid',emission=12,faces=('up',)))
    write(path,spec)
    flora('fan_leaves',(135,180,168),'fold');flora('glass_leaves',(157,204,211),'spire')
    flora('amber_seeds',(199,158,83),'root');flora('aether_fern',(113,165,149),'steps');flora('star_blossom',(211,227,221),'petal')
    # Preserve the vessel design; scale from the base so the feet stay on the floor.
    path=ASSETS/'models/block/alchemical_cauldron.json';spec=json.loads(path.read_text())
    if not spec.get('logos_polished_scale'):
        for element in spec['elements']:
            for corner in ('from','to'):
                x,y,z=element[corner];element[corner]=[8+(x-8)*1.5,y*1.5,8+(z-8)*1.5]
        spec['logos_polished_scale']=1.5
        spec['display']['gui']['scale']=[.65,.65,.65]
        write(path,spec)
    astrolabe()


if __name__=='__main__':
    polish_cloudsea()
    polish_crucible()
    print('Polished existing blocks and generated three independent astrolabe layers.')
