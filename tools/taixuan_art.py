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
    path.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n','utf-8',newline='\n')


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


# ---------------------------------------------------------------- 黄铜筒瓦、玄铁、渊雾
# 黄铜取自维护者给的截图里那种偏黄的铜色（中间调约 200,165,84），原版铜块偏红（约 193,109,63）。
BRASS={'deep':(108,72,28),'shadow':(154,110,46),'mid':(198,158,80),'light':(222,188,110),'hi':(242,216,150)}
# 一根筒瓦 8 像素宽：左边一格是两垄之间的板瓦沟，往右由暗到亮再到暗，亮线落在瓦背上
TUBE=('deep','shadow','mid','light','hi','light','mid','shadow')


def _shade(color,k,n=0):
    return tuple(max(0,min(255,int(c*k)+n)) for c in color)+(255,)


def brass_tiles():
    """筒瓦瓦面（瓦垄竖着走）：四垄筒瓦，每 8 像素一节，节缝相邻两垄错开半节。"""
    rng=random.Random(20261111);image=Image.new('RGBA',(32,32));pixels=image.load()
    jitter=[[rng.randrange(-6,7) for _ in range(5)] for _ in range(4)]
    for x in range(32):
        column,k=divmod(x,8)
        for y in range(32):
            seg=((y+(4 if column%2 else 0))//8)%4
            local=(y+(4 if column%2 else 0))%8
            factor=.70 if local==0 else 1.08 if local==1 else 1.0
            pixels[x,y]=_shade(BRASS[TUBE[k]],factor,jitter[column][seg]+rng.randrange(-3,4))
    return image


def tile_ends():
    """瓦面朝外的那一面：上半是一垄垄筒瓦的圆头，下沿一排瓦当，瓦当之间是三角的滴水。"""
    image=Image.new('RGBA',(32,32),BRASS['shadow']+(255,));draw=ImageDraw.Draw(image)
    for k in range(4):
        cx=k*8+4
        for y in range(0,18):
            for x in range(k*8,k*8+8):
                d=abs(x+.5-cx)
                if d<3.6-(y-14)*.25 if y>14 else d<3.6:
                    tone='hi' if abs(x+.5-cx-.5)<1 else 'light' if d<2.2 else 'mid'
                    image.putpixel((x,y),_shade(BRASS[tone],1.0 if y%8 else .78))
        # 瓦当：一圈暗边、亮面、中心乳钉
        draw.ellipse((cx-3.5,20,cx+3.5,27),fill=BRASS['mid']+(255,),outline=BRASS['deep']+(255,))
        draw.ellipse((cx-1.5,22,cx+1.5,25),fill=BRASS['light']+(255,),outline=BRASS['shadow']+(255,))
        image.putpixel((cx,23),BRASS['hi']+(255,))
        # 两个瓦当之间的滴水
        draw.polygon([(k*8,27),(k*8+1,31),(k*8+2,27)],fill=BRASS['deep']+(255,))
    draw.line([(0,19),(31,19)],fill=BRASS['deep']+(255,))
    return image


def brass_ridge():
    """正脊与檐口压条：横向的一道道脊瓦，每 16 像素一个圆钉饰。"""
    image=Image.new('RGBA',(32,32));pixels=image.load()
    band=('hi','light','mid','mid','shadow','deep','light','mid')
    for y in range(32):
        for x in range(32):pixels[x,y]=_shade(BRASS[band[y%8]],1.0,(x*7+y*3)%5-2)
    draw=ImageDraw.Draw(image)
    for x in (8,24):
        for y in (3,19):
            draw.ellipse((x-2,y-2,x+2,y+2),fill=BRASS['light']+(255,),outline=BRASS['deep']+(255,))
    return image


def brass_block():
    """素面黄铜：拉丝底，斜边框，中间浅刻卷云。"""
    rng=random.Random(42);image=Image.new('RGBA',(32,32));pixels=image.load()
    for y in range(32):
        for x in range(32):pixels[x,y]=_shade(BRASS['mid'],1.0+.05*math.sin(y*1.7),rng.randrange(-4,5))
    draw=ImageDraw.Draw(image)
    draw.line([(0,0),(31,0)],fill=BRASS['hi']+(255,));draw.line([(0,0),(0,31)],fill=BRASS['hi']+(255,))
    draw.line([(0,31),(31,31)],fill=BRASS['deep']+(255,));draw.line([(31,0),(31,31)],fill=BRASS['deep']+(255,))
    draw.rectangle((3,3,28,28),outline=BRASS['shadow']+(255,))
    paths=[[(8,12),(8,8),(15,8),(15,14),(11,14),(11,11)],[(23,20),(23,23),(16,23),(16,17),(20,17),(20,20)]]
    for path in paths:
        draw.line([(x+1,y+1) for x,y in path],fill=BRASS['hi']+(255,))
        draw.line(path,fill=BRASS['deep']+(255,))
    return image


def dark_iron():
    """玄铁：发蓝的暗铁，锤打的凹痕，四角铆钉，铆钉下有一点锈迹。
    底色是偏蓝的铁灰（不是近黑）：深渊里光线暗，太黑的话铁链只剩一团黑影，看不出一环一环。"""
    rng=random.Random(7);image=Image.new('RGBA',(32,32));pixels=image.load()
    for y in range(32):
        for x in range(32):
            dent=10*math.sin(x*.9+math.sin(y*.5)*2)*math.sin(y*.7)
            n=rng.randrange(-6,7)+int(dent)
            # 上缘一道亮边、下缘一道暗边，块与块之间分得开
            edge=14 if y<2 else -16 if y>29 else 0
            pixels[x,y]=(max(0,82+n+edge),max(0,88+n+edge),max(0,100+n+edge),255)
    draw=ImageDraw.Draw(image)
    draw.rectangle((0,0,31,31),outline=(44,47,56,255))
    for x,y in ((5,5),(26,5),(5,26),(26,26)):
        draw.ellipse((x-2,y-2,x+2,y+2),fill=(92,98,110,255),outline=(24,26,31,255))
        image.putpixel((x-1,y-1),(150,156,166,255))
        for dy in range(3,3+rng.randrange(2,6)):
            if y+dy<31:image.putpixel((x,y+dy),(104,64,36,255))
    return image


def abyss_mist():
    """渊雾：半透明的白，透明度随平滑噪声在 150 到 200 间起伏，像云面；隔着雾面隐约看得见下面更暗。四边能接上。"""
    image=Image.new('RGBA',(32,32));pixels=image.load()
    for y in range(32):
        for x in range(32):
            u,v=x/32*2*math.pi,y/32*2*math.pi
            n=(math.sin(u+1.3)*math.cos(v*2+.4)+.6*math.sin(u*2+v+2.1)+.4*math.cos(u*3-v*2))/2
            a=int(175+25*max(-1,min(1,n)))
            g=int(242+6*n)
            pixels[x,y]=(g-4,g-1,min(255,g+3),a)
    return image


def stairs_blockstate(model):
    """原版楼梯的四十种状态；uvlock 关着，瓦垄跟着楼梯朝向一起转，始终顺着坡往下走。"""
    base={'east':0,'south':90,'west':180,'north':270};out={}
    for facing,b in base.items():
        for half in ('bottom','top'):
            for shape in ('straight','inner_left','inner_right','outer_left','outer_right'):
                name=model+('' if shape=='straight' else '_inner' if 'inner' in shape else '_outer')
                if half=='bottom':y=b+(270 if shape in ('inner_left','outer_left') else 0)
                else:y=b+(90 if shape in ('inner_right','outer_right') else 0)
                v={'model':name}
                if half=='top':v['x']=180
                if y%360:v['y']=y%360
                out[f'facing={facing},half={half},shape={shape}']=v
    return {'variants':out}


BRASS_BLOCKS={'taixuan_brass_tiles':('黄铜筒瓦','Brass Roof Tiles'),
    'taixuan_brass_tile_stairs':('黄铜筒瓦楼梯','Brass Roof Tile Stairs'),
    'taixuan_brass_ridge_slab':('黄铜脊瓦台阶','Brass Ridge Slab'),
    'taixuan_brass_block':('黄铜块','Block of Brass'),
    'taixuan_dark_iron':('玄铁块','Block of Dark Iron'),
    'taixuan_abyss_mist':('渊雾','Abyss Mist')}


def brass_assets():
    tex=A/'textures/block'
    brass_tiles().save(tex/'taixuan_brass_tiles_ns.png')
    brass_tiles().rotate(90).save(tex/'taixuan_brass_tiles_ew.png')
    tile_ends().save(tex/'taixuan_brass_tile_ends.png')
    brass_ridge().save(tex/'taixuan_brass_ridge.png')
    brass_block().save(tex/'taixuan_brass_block.png')
    dark_iron().save(tex/'taixuan_dark_iron.png')
    abyss_mist().save(tex/'taixuan_abyss_mist.png')
    t=lambda name:NS+'block/'+name
    # 筒瓦块按轴放：axis=z 瓦垄南北走（南北两坡），axis=x 东西走（东西两坡）；朝瓦垄的两面是瓦头和瓦当
    for axis,top,ends,sides in (('z','taixuan_brass_tiles_ns',('north','south'),('east','west')),
                                ('x','taixuan_brass_tiles_ew',('east','west'),('north','south'))):
        faces={'up':top,'down':'taixuan_brass_block'}
        for f in ends:faces[f]='taixuan_brass_tile_ends'
        for f in sides:faces[f]='taixuan_brass_ridge'
        write(A/f'models/block/taixuan_brass_tiles_{axis}.json',{'parent':'minecraft:block/cube',
            'textures':{**{k:t(v) for k,v in faces.items()},'particle':t(top)}})
    write(A/'blockstates/taixuan_brass_tiles.json',{'variants':{'axis=x':{'model':t('taixuan_brass_tiles_x')},
        'axis=y':{'model':t('taixuan_brass_tiles_z')},'axis=z':{'model':t('taixuan_brass_tiles_z')}}})
    stair_tex={'bottom':t('taixuan_brass_block'),'top':t('taixuan_brass_tiles_ew'),'side':t('taixuan_brass_tile_ends')}
    for suffix,parent in (('','stairs'),('_inner','inner_stairs'),('_outer','outer_stairs')):
        write(A/f'models/block/taixuan_brass_tile_stairs{suffix}.json',{'parent':'minecraft:block/'+parent,'textures':stair_tex})
    write(A/'blockstates/taixuan_brass_tile_stairs.json',stairs_blockstate(NS+'block/taixuan_brass_tile_stairs'))
    ridge={'bottom':t('taixuan_brass_block'),'top':t('taixuan_brass_ridge'),'side':t('taixuan_brass_ridge')}
    write(A/'models/block/taixuan_brass_ridge_slab.json',{'parent':'minecraft:block/slab','textures':ridge})
    write(A/'models/block/taixuan_brass_ridge_slab_top.json',{'parent':'minecraft:block/slab_top','textures':ridge})
    write(A/'models/block/taixuan_brass_ridge_double.json',{'parent':'minecraft:block/cube_bottom_top','textures':ridge})
    write(A/'blockstates/taixuan_brass_ridge_slab.json',{'variants':{'type=bottom':{'model':t('taixuan_brass_ridge_slab')},
        'type=top':{'model':t('taixuan_brass_ridge_slab_top')},'type=double':{'model':t('taixuan_brass_ridge_double')}}})
    for name in ('taixuan_brass_block','taixuan_dark_iron'):
        write(A/f'models/block/{name}.json',{'parent':'minecraft:block/cube_all','textures':{'all':t(name)}})
        write(A/f'blockstates/{name}.json',{'variants':{'':{'model':t(name)}}})
    write(A/'models/block/taixuan_abyss_mist.json',{'parent':'minecraft:block/cube_all','render_type':'neoforge:translucent',
        'ambientocclusion':False,'textures':{'all':t('taixuan_abyss_mist')}})
    write(A/'blockstates/taixuan_abyss_mist.json',{'variants':{'':{'model':t('taixuan_abyss_mist')}}})
    items={'taixuan_brass_tiles':'taixuan_brass_tiles_z','taixuan_brass_tile_stairs':'taixuan_brass_tile_stairs',
           'taixuan_brass_ridge_slab':'taixuan_brass_ridge_slab','taixuan_brass_block':'taixuan_brass_block',
           'taixuan_dark_iron':'taixuan_dark_iron','taixuan_abyss_mist':'taixuan_abyss_mist'}
    for name,model in items.items():
        write(A/'items'/f'{name}.json',{'model':{'type':'minecraft:model','model':t(model)}})
    for name in BRASS_BLOCKS:
        if name=='taixuan_abyss_mist':continue
        entry={'type':'minecraft:item','name':NS+name}
        if name.endswith('_slab'):
            entry['functions']=[{'function':'minecraft:set_count','count':2,'add':False,'conditions':[{'condition':'minecraft:block_state_property',
                'block':NS+name,'properties':{'type':'double'}}]},{'function':'minecraft:explosion_decay'}]
        write(D/'loot_table/blocks'/f'{name}.json',{'type':'minecraft:block','pools':[{'rolls':1,'entries':[entry],
            'conditions':[{'condition':'minecraft:survives_explosion'}]}]})
    # 配方：黄铜 = 铜锭配金粒；瓦、楼梯、脊瓦从黄铜块切石机切；玄铁 = 铁锭配煤
    write(D/'recipe/taixuan_brass_block.json',{'type':'minecraft:crafting_shaped','category':'building',
        'pattern':['CGC','GCG','CGC'],'key':{'C':'minecraft:copper_ingot','G':'minecraft:gold_nugget'},'result':{'id':NS+'taixuan_brass_block','count':4}})
    for name,count in (('taixuan_brass_tiles',2),('taixuan_brass_tile_stairs',2),('taixuan_brass_ridge_slab',4)):
        write(D/f'recipe/{name}_from_brass.json',{'type':'minecraft:stonecutting','ingredient':NS+'taixuan_brass_block',
            'result':{'id':NS+name,'count':count}})
    write(D/'recipe/taixuan_dark_iron.json',{'type':'minecraft:crafting_shaped','category':'building',
        'pattern':['ICI','CIC','ICI'],'key':{'I':'minecraft:iron_ingot','C':'minecraft:coal'},'result':{'id':NS+'taixuan_dark_iron','count':4}})
    for lang,col in (('zh_cn',0),('en_us',1)):
        p=A/'lang'/f'{lang}.json';data=json.loads(p.read_text('utf-8'))
        for name,titles in BRASS_BLOCKS.items():
            for prefix in ('block.','item.'):data[prefix+'logos_dimension.'+name]=titles[col]
        write(p,data)
    for tag,names in (('mineable/pickaxe',[n for n in BRASS_BLOCKS if n!='taixuan_abyss_mist']),
                      ('stairs',['taixuan_brass_tile_stairs']),('slabs',['taixuan_brass_ridge_slab'])):
        p=ROOT/'src/main/resources/data/minecraft/tags/block'/f'{tag}.json'
        value=json.loads(p.read_text('utf-8')) if p.exists() else {'replace':False,'values':[]}
        value['values']=list(dict.fromkeys(value['values']+[NS+n for n in names]));write(p,value)
    for tag,names in (('stairs',['taixuan_brass_tile_stairs']),('slabs',['taixuan_brass_ridge_slab'])):
        p=ROOT/'src/main/resources/data/minecraft/tags/item'/f'{tag}.json'
        value=json.loads(p.read_text('utf-8')) if p.exists() else {'replace':False,'values':[]}
        value['values']=list(dict.fromkeys(value['values']+[NS+n for n in names]));write(p,value)


if __name__=='__main__':
    assets()
    brass_assets()
