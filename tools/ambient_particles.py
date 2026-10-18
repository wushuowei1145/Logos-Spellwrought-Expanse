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
print('Ambient particle resources: three native definitions and four 16x16 RGBA sprites.')
