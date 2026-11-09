"""代码绘制徽式花格窗与云纹砖雕；不读取外部图片，不使用图像生成服务。"""
from pathlib import Path
import json
import math
import random
from PIL import Image, ImageDraw

ROOT=Path(__file__).resolve().parents[1]
A=ROOT/'src/main/resources/assets/logos_dimension'
D=ROOT/'src/main/resources/data/logos_dimension'
NS='logos_dimension:'


def write(path,value):
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n','utf-8')


def box(a,b):
    x0,y0,z0=a;x1,y1,z1=b
    faces={
        'north':[x0,16-y1,x1,16-y0],'south':[16-x1,16-y1,16-x0,16-y0],
        'west':[z0,16-y1,z1,16-y0],'east':[16-z1,16-y1,16-z0,16-y0],
        'up':[x0,z0,x1,z1],'down':[x0,16-z1,x1,16-z0]}
    return {'from':list(a),'to':list(b),'faces':{k:{'texture':'#wood','uv':v} for k,v in faces.items()}}


def assets():
    tex=A/'textures/block';tex.mkdir(parents=True,exist_ok=True)
    rng=random.Random(20261109)
    image=Image.new('RGBA',(32,32));pixels=image.load()
    for y in range(32):
        for x in range(32):
            n=rng.randrange(-4,5)+int(math.sin(x*.7+math.sin(y*.18))*5)
            pixels[x,y]=(max(0,56+n),max(0,46+n),max(0,36+n),255)
    draw=ImageDraw.Draw(image)
    for x in (2,9,18,27):draw.line([(x,0),(x+1,12),(x,23),(x+1,31)],fill=(84,70,48,255))
    image.save(tex/'taixuan_woodwork.png')
    image=Image.new('RGBA',(32,32));pixels=image.load()
    for y in range(32):
        for x in range(32):
            n=rng.randrange(-5,6);pixels[x,y]=(154+n,153+n,143+n,255)
    draw=ImageDraw.Draw(image)
    draw.rectangle((0,0,31,31),outline=(103,107,105,255))
    draw.rectangle((2,2,29,29),outline=(197,194,178,255))
    draw.rectangle((4,4,27,27),outline=(105,109,106,255))
    # 双回纹与卷云使用明暗双线形成浅浮雕。
    paths=[[(7,10),(7,7),(14,7),(14,13),(10,13),(10,10)],
           [(24,21),(24,24),(17,24),(17,18),(21,18),(21,21)],
           [(7,23),(7,17),(12,17),(12,22),(15,22)],
           [(24,8),(24,14),(19,14),(19,9),(16,9)]]
    for path in paths:
        draw.line([(x+1,y+1) for x,y in path],fill=(94,101,98,255),width=2)
        draw.line(path,fill=(207,201,181,255),width=1)
    draw.rectangle((14,14,17,17),outline=(206,200,180,255))
    image.save(tex/'taixuan_carved_brick.png')
    elements=[box((0,0,6.5),(1.3,16,9.5)),box((14.7,0,6.5),(16,16,9.5)),
              box((1.3,0,6.5),(14.7,1.3,9.5)),box((1.3,14.7,6.5),(14.7,16,9.5))]
    for x in (4,8,12):elements.append(box((x-.35,1.3,7.2),(x+.35,14.7,8.8)))
    for y in (4,8,12):elements.append(box((1.3,y-.35,7.2),(14.7,y+.35,8.8)))
    for x,y in ((4,4),(12,4),(4,12),(12,12)):
        elements.extend([box((x-1.4,y-1.4,7),(x+1.4,y-.85,9)),box((x-1.4,y+.85,7),(x+1.4,y+1.4,9)),
                         box((x-1.4,y-.85,7),(x-.85,y+.85,9)),box((x+.85,y-.85,7),(x+1.4,y+.85,9))])
    write(A/'models/block/taixuan_lattice_window.json',{'parent':'minecraft:block/block','ambientocclusion':True,'render_type':'neoforge:cutout',
        'textures':{'wood':NS+'block/taixuan_woodwork','particle':NS+'block/taixuan_woodwork'},'elements':elements})
    write(A/'blockstates/taixuan_lattice_window.json',{'variants':{'facing='+f:{'model':NS+'block/taixuan_lattice_window','y':angle} for f,angle in (('north',0),('east',90),('south',180),('west',270))}})
    write(A/'models/block/taixuan_carved_brick.json',{'parent':'minecraft:block/cube_all','textures':{'all':NS+'block/taixuan_carved_brick'}})
    write(A/'blockstates/taixuan_carved_brick.json',{'variants':{'':{'model':NS+'block/taixuan_carved_brick'}}})
    for name in ('taixuan_lattice_window','taixuan_carved_brick'):
        write(A/'items'/f'{name}.json',{'model':{'type':'minecraft:model','model':NS+'block/'+name}})
        write(D/'loot_table/blocks'/f'{name}.json',{'type':'minecraft:block','pools':[{'rolls':1,'entries':[{'type':'minecraft:item','name':NS+name}],
            'conditions':[{'condition':'minecraft:survives_explosion'}]}]})
    write(D/'recipe/taixuan_lattice_window.json',{'type':'minecraft:crafting_shaped','category':'building',
        'pattern':['SSS','PPP','SSS'],'key':{'S':'minecraft:stick','P':'minecraft:dark_oak_planks'},
        'result':{'id':NS+'taixuan_lattice_window','count':4}})
    write(D/'recipe/taixuan_carved_brick.json',{'type':'minecraft:stonecutting','ingredient':'minecraft:stone_bricks',
        'result':{'id':NS+'taixuan_carved_brick','count':1}})
    for lang,names in (('zh_cn',('徽式花格窗','云纹砖雕')),('en_us',('Hui Lattice Window','Cloud-Carved Brick'))):
        p=A/'lang'/f'{lang}.json';data=json.loads(p.read_text('utf-8'))
        for name,title in zip(('taixuan_lattice_window','taixuan_carved_brick'),names):
            for prefix in ('block.','item.'):data[prefix+'logos_dimension.'+name]=title
        write(p,data)
    for tag,name in (('axe','taixuan_lattice_window'),('pickaxe','taixuan_carved_brick')):
        p=ROOT/'src/main/resources/data/minecraft/tags/block/mineable'/f'{tag}.json'
        value=json.loads(p.read_text('utf-8')) if p.exists() else {'replace':False,'values':[]}
        value['values']=list(dict.fromkeys(value['values']+[NS+name]));write(p,value)


if __name__=='__main__':assets()
