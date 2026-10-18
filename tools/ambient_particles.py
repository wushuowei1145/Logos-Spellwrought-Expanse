"""Small authored particle sprites: warm winged fireflies, soft motes and four-point glints."""
from pathlib import Path
from PIL import Image,ImageDraw
import math,json
ROOT=Path(__file__).resolve().parent.parent/'src/main/resources/assets/logos_dimension'
textures=ROOT/'textures/particle';textures.mkdir(parents=True,exist_ok=True)
definitions=ROOT/'particles';definitions.mkdir(parents=True,exist_ok=True)

def halo(color,radius=6,opacity=75):
    im=Image.new('RGBA',(16,16))
    for y in range(16):
        for x in range(16):
            r=math.hypot(x-7.5,y-7.5)
            if r<radius:im.putpixel((x,y),color+(round(opacity*(1-r/radius)**2),))
    return im

for frame in range(2):
    im=halo((255,216,100),7,125);q=ImageDraw.Draw(im)
    if frame==0:
        q.line((4,6,6,7),fill=(183,198,155,155),width=1);q.line((9,7,11,6),fill=(183,198,155,155),width=1)
    else:
        q.line((6,5,5,8),fill=(183,198,155,155),width=1);q.line((10,5,9,8),fill=(183,198,155,155),width=1)
    q.rectangle((7,5,8,6),fill=(119,111,69,230));q.rectangle((7,7,8,10),fill=(255,222,113,255))
    q.rectangle((7,8,8,9),fill=(255,255,214,255));im.save(textures/f'journey_firefly_{frame}.png')
im=halo((255,255,255),6,95);q=ImageDraw.Draw(im);q.rectangle((7,7,8,8),fill=(255,255,255,235));q.point((7,6),fill=(255,255,255,130));im.save(textures/'journey_mana_mote.png')
im=halo((226,255,255),7,65);q=ImageDraw.Draw(im)
for length,alpha in [(4,65),(3,115),(2,180),(1,240)]:
    q.line((7,7-length,7,7+length),fill=(238,255,255,alpha));q.line((7-length,7,7+length,7),fill=(238,255,255,alpha))
q.point((7,7),fill=(255,255,255,255));im.save(textures/'journey_plant_glimmer.png')
for name,sprites in [('journey_firefly',['journey_firefly_0','journey_firefly_1']),('journey_mana_mote',['journey_mana_mote']),('journey_plant_glimmer',['journey_plant_glimmer'])]:
    (definitions/f'{name}.json').write_text(json.dumps({'textures':['logos_dimension:'+s for s in sprites]},indent=2)+'\n',encoding='utf-8')
# Quiet encounters: organic spores, a small woodland mark, silver water rings and a shy wisp.
im=halo((215,255,226),7,95);q=ImageDraw.Draw(im)
q.ellipse((6,6,9,10),fill=(231,255,238,225));q.line((7,4,8,7),fill=(200,240,218,170));q.point((10,5),fill=(213,250,229,100));im.save(textures/'journey_luminous_spore.png')
im=Image.new('RGBA',(32,32));q=ImageDraw.Draw(im)
for a,b in [(8,59),(90,141),(172,223),(254,305)]:q.arc((5,5,26,26),a,b,fill=(255,255,255,140),width=1)
q.polygon([(15,7),(19,15),(15,23),(11,15)],outline=(255,255,255,195))
q.line((15,11,15,19),fill=(255,255,255,170));q.line((12,15,18,15),fill=(255,255,255,170))
for x,y in [(5,15),(26,15),(15,4),(15,27)]:q.rectangle((x,y,x+1,y+1),fill=(255,255,255,185))
im.save(textures/'journey_woodland_sigil.png')
im=Image.new('RGBA',(32,32));q=ImageDraw.Draw(im)
q.ellipse((4,4,27,27),outline=(225,248,255,140),width=1);q.arc((7,7,24,24),20,170,fill=(225,248,255,100),width=1);q.arc((7,7,24,24),205,340,fill=(225,248,255,100),width=1)
for x,y in [(8,8),(24,22),(20,5)]:q.point((x,y),fill=(250,255,255,210))
im.save(textures/'journey_silver_ripple.png')
im=Image.new('RGBA',(32,32))
for y in range(32):
    for x in range(32):
        r=math.hypot(x-16,y-13)
        if r<11:im.putpixel((x,y),(224,250,255,round(100*(1-r/11)**2)))
q=ImageDraw.Draw(im);q.arc((6,10,24,29),50,175,fill=(205,243,255,95),width=1)
q.polygon([(16,6),(20,13),(18,17),(14,17),(12,13)],fill=(232,255,255,210));q.ellipse((15,11,17,14),fill=(255,255,255,250));q.point((10,22),fill=(213,249,255,125))
im.save(textures/'journey_water_wisp.png')
for name in ['journey_luminous_spore','journey_woodland_sigil','journey_silver_ripple','journey_water_wisp']:
    (definitions/f'{name}.json').write_text(json.dumps({'textures':['logos_dimension:'+name]},indent=2)+'\n',encoding='utf-8')
print('Ambient particle resources: seven native definitions and eight translucent sprites.')
