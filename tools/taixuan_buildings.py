"""殿阁、民居、商铺与城门的局部模块；由总布局旋转、平移后组合。"""
from taixuan_kit import *


def table(c,x,y,z,length=3):
    c.set(x,y,z,B('dark_oak_fence',north=False,south=False,east=False,west=False,waterlogged=False))
    c.set(x+length-1,y,z,B('dark_oak_fence',north=False,south=False,east=False,west=False,waterlogged=False))
    for xx in range(x,x+length):c.set(xx,y+1,z,B('dark_oak_slab',type='top',waterlogged=False))


def inside(c,x,y,z,w,d,study=False):
    if study:
        c.box(x+1,y+1,z+1,x+w-2,y+3,z+1,B('bookshelf'))
        for xx in (x+3,x+w-6):
            table(c,xx,y+1,z+d//2)
            c.set(xx+1,y+1,z+d//2+2,B('dark_oak_stairs',facing='north',half='bottom',shape='straight',waterlogged=False))
    else:
        c.set(x+2,y+1,z+2,B('barrel',facing='up',open=False))
        c.set(x+w-3,y+1,z+2,B('chest',facing='south',type='single',waterlogged=False))
        if w>=13:table(c,x+w//2-1,y+1,z+d//2)
    for xx in (x+2,x+w-3):
        c.set(xx,y+5,z+2,PLANK)
        c.set(xx,y+4,z+2,B('lantern',hanging=True,waterlogged=False))


def hall(w=23,d=15,stories=1,h=6,city=False,study=False):
    """前廊与内厅分开，层间披檐不穿入楼层，右后方留实际楼梯。"""
    c=Canvas();margin=3
    x,z=margin,margin
    terrace(c,x-1,0,z-1,w+2,d+4,not city,5)
    for f in range(stories):
        inset=2*f
        xx,zz=x+inset,z+inset
        ww,dd=w-2*inset,d-2*inset
        if ww<9 or dd<7:break
        y=f*h
        c.box(xx,y,zz,xx+ww-1,y,zz+dd-1,PLANK)
        c.box(xx,y+1,zz,xx+ww-1,y+h-1,zz+dd-1,PLASTER)
        c.clear(xx+1,y+1,zz+1,xx+ww-2,y+h-1,zz+dd-2)
        for px in range(xx,xx+ww,4):
            for pz in (zz,zz+dd-1):pillar(c,px,y,pz,h-1)
        for pz in range(zz,zz+dd,4):
            for px in (xx,xx+ww-1):pillar(c,px,y,pz,h-1)
        c.box(xx,y+h,zz,xx+ww-1,y+h,zz,BEAM_X)
        c.box(xx,y+h,zz+dd-1,xx+ww-1,y+h,zz+dd-1,BEAM_X)
        for px in range(xx+2,xx+ww-2,4):
            lattice(c,px,y+2,zz,2,2,'north')
            lattice(c,px,y+2,zz+dd-1,2,2,'south')
        mid=xx+ww//2
        c.clear(mid-1,y+1,zz+dd-1,mid+1,y+3,zz+dd+1)
        door(c,mid-1,y+1,zz+dd-1,'south','left')
        door(c,mid,y+1,zz+dd-1,'south','right')
        # 柱廊的檐顶与梁相接，暖灯挂在梁下。
        for px in range(xx,xx+ww,4):pillar(c,px,y,zz+dd+1,h-1)
        c.box(xx,y+h,zz+dd+1,xx+ww-1,y+h,zz+dd+1,BEAM_X)
        # 灯挂在墙梁与廊梁之间的一根短枋下，以前正上方是空的，一有方块更新就掉成物品
        for px in (mid-3,mid+3):
            c.set(px,y+h,zz+dd,BEAM_Z)
            c.set(px,y+h-1,zz+dd,B('lantern',hanging=True,waterlogged=False))
        inside(c,xx,y,zz,ww,dd,study)
        top=f==stories-1
        roof(c,xx,y+h+1,zz,ww,dd+2,city,ring=not top)
    # 先完成各层，再开楼梯口，避免后续楼板把已画的楼梯封住。
    for f in range(stories-1):
        y=f*h;xx=x+2*f;zz=z+2*f;ww=w-4*f;dd=d-4*f
        sx=xx+ww-5
        if dd<h+7:
            # 小楼层没有足够的直梯进深，改为有实心背板的爬梯井。
            pz=zz+dd//2
            c.clear(sx,y+1,pz,sx,y+h+2,pz)
            c.box(sx,y+1,pz+1,sx,y+h+2,pz+1,PLANK)
            c.box(sx,y+1,pz,sx,y+h+1,pz,B('ladder',facing='north',waterlogged=False))
            continue
        for k in range(h+1):
            pz=zz+dd-3-k
            c.clear(sx,y+1+k,pz,sx,y+3+k,pz)
            c.set(sx,y+k,pz,B('dark_oak_stairs',facing='north',half='bottom',shape='straight',waterlogged=False))
    # 所有模块保持非负局部坐标，方便复用 Canvas.paste 的旋转规则。
    size,_=c.normalized()
    return c


def pagoda(width=15,stories=5):
    c=Canvas()
    base=hall(width,width,1,5,study=True)
    c.paste(base,0,0,0)
    for f in range(1,stories):
        shrink=min(f//2,3)
        w=width-2*shrink
        part=hall(w,w,1,5,study=True)
        c.paste(part,shrink,f*6,shrink)
    # 各层真正连通，柱心一侧的爬梯穿过楼板，顶层仍保留屋顶。
    px,pz=width//2+3,width//2+3
    c.clear(px,1,pz,px,stories*6-1,pz)
    c.box(px,1,pz+1,px,stories*6-1,pz+1,PLANK)
    c.box(px,1,pz,px,stories*6-1,pz,B('ladder',facing='north',waterlogged=False))
    return c


def home(kind=0):
    """四种可进入的民居院落：茶铺、旅舍、书铺与普通住家。"""
    c=Canvas();w,d=15,13
    part=hall(w,d,2 if kind in (1,3) else 1,5,True,study=kind==2)
    c.paste(part,0,0,0)
    # 街边雨棚与院门、盆栽和实际室内家具各有区别。
    for xx in (1,21):
        c.set(xx,1,19,B('potted_bamboo' if kind%2 else 'potted_azalea_bush'))
    if kind==0:
        table(c,6,1,8)
        c.set(5,1,5,B('smoker',facing='south',lit=True))
        c.set(7,1,5,B('cauldron'))
    elif kind==1:
        for xx in (5,10):
            c.set(xx,1,6,B('white_bed',facing='north',part='foot',occupied=False))
            c.set(xx,1,5,B('white_bed',facing='north',part='head',occupied=False))
    elif kind==2:c.box(5,1,5,13,3,5,B('bookshelf'))
    else:c.set(5,1,5,B('crafting_table'))
    c.box(1,0,18,21,0,20,STONE)
    return c


def gate(city=False):
    c=Canvas();w,d=23,13
    if city:
        c.box(2,0,2,28,8,16,STONE)
        c.clear(13,1,2,17,6,16)
        for xx in (12,18):c.box(xx,0,2,xx,6,16,WHITE)
        c.box(13,7,2,17,7,16,TRIM)
        for xx in (5,23):c.clear(xx,1,2,xx+2,4,16)
        part=hall(25,13,2,5,True)
        c.paste(part,0,9,0)
    else:
        for xx in (3,8,14,19):pillar(c,xx,0,5,9 if xx in (8,14) else 6)
        c.box(3,6,5,19,6,5,BEAM_X)
        c.box(8,9,5,14,9,5,BEAM_X)
        roof(c,7,10,3,9,5,False,eave=1)
        for xx in (2,15):roof(c,xx,7,3,6,5,False,eave=1)
        for xx in (5,17):c.set(xx,5,5,B('lantern',hanging=True,waterlogged=False))
    return c


def hui_house(kind=0,style=0):
    """白墙黛瓦的徽式天井院落：前门、厢房、后厅与封火墙。

    style 第 0 位换门罩（雕砖额枋或带瓦檐的门楼），第 1 位换马头墙（三叠或五叠），
    同一排相邻的院落不再一模一样。"""
    c=Canvas();floors=2 if kind in (1,2,3) else 1
    carved=L('taixuan_carved_brick')
    def window(x,y,z,facing):
        for dy in range(2):c.set(x,y+dy,z,L('taixuan_lattice_window',facing=facing,waterlogged=False))
    c.box(1,0,1,21,0,24,STONE)
    c.box(1,1,1,21,4,24,PLASTER)
    c.clear(2,1,2,20,16,23)
    # 沿街门罩与开敞天井，白墙连续，不给每块灰泥加框。
    c.clear(10,1,1,11,3,1)
    door(c,10,1,1,'north','left');door(c,11,1,1,'north','right')
    c.box(8,0,1,14,0,1,TRIM)
    c.box(8,4,1,14,4,1,carved)
    if style&1:
        # 门楼：两侧雕砖门柱，额枋上挑出一道瓦檐
        for xx in (9,12):c.box(xx,1,0,xx,3,0,carved)
        c.box(9,4,0,12,4,0,carved)
        for xx in range(7,16):
            c.set(xx,5,0,stairs('south',True))
            c.set(xx,5,1,GREY)
        for xx in range(8,15):c.set(xx,6,1,slab(True))
    else:
        for xx in range(7,16):c.set(xx,5,1,slab(True))
    # 门两侧的灯挂在贴墙的木挑头下，不悬空
    for xx in (3,19):
        c.set(xx,5,0,B('dark_oak_fence',north=False,south=True,east=False,west=False,waterlogged=False))
        c.set(xx,4,0,B('lantern',hanging=True,waterlogged=False))
    # 两侧低矮厢房，房门朝向天井。
    for left,right,facing in ((2,5,'east'),(17,20,'west')):
        c.box(left,1,4,right,4,12,PLASTER)
        c.clear(left+1,1,5,right-1,3,11)
        door(c,right if facing=='east' else left,1,8,facing)
        window(left+1,2,4,'north')
        for xx in range(left-1,right+2):
            yy=5+min(xx-(left-1),(right+1)-xx)
            for zz in range(3,14):
                c.set(xx,yy,zz,GREY)
                if yy>5:c.set(xx,yy-1,zz,PLANK)
    for xx in range(6,17):
        for zz in range(4,13):c.set(xx,0,zz,WHITE if (xx+zz)%4 else B('polished_andesite'))
    for xx,zz in ((7,5),(16,5)):
        c.set(xx,1,zz,B('potted_bamboo' if kind%2 else 'potted_flowering_azalea_bush'))
    if kind==1:c.set(11,1,8,B('water_cauldron',level=3))
    else:c.set(11,1,8,B('decorated_pot',facing='north',cracked=False,waterlogged=False))
    # 后厅不逐层收成塔楼，保持徽宅上下通直的白墙体量。
    c.box(4,1,14,18,floors*5,22,PLASTER)
    for f in range(floors):
        y=f*5
        c.box(5,y,15,17,y,21,PLANK)
        c.clear(5,y+1,15,17,y+4,21)
        for xx in (7,15):
            window(xx,y+2,14,'north');window(xx,y+2,22,'south')
        for xx in (4,18):c.box(xx,y+1,14,xx,y+4,14,WOOD)
        c.box(4,y+5,14,18,y+5,14,BEAM_X)
        door(c,10,y+1,14,'north')
        c.set(16,y+1,20,B('chest',facing='north',type='single',waterlogged=False))
        c.set(6,y+4,16,B('lantern',hanging=True,waterlogged=False));c.set(6,y+5,16,PLANK)
    roof(c,4,floors*5+1,14,15,9,True,eave=2)
    # 三叠马头墙高过瓦面，灰瓦压顶，端部与坡面相接。
    steps=((1,4),(3,3),(6,1)) if not style&2 else ((0,5),(2,4),(4,3),(5,2),(6,1))
    for xx in (2,20):
        for zz in range(12,25):
            distance=abs(zz-18)
            rise=next(r for d,r in steps if distance<=d)
            height=floors*5+1+rise
            c.box(xx,1,zz,xx,height,zz,PLASTER)
            c.set(xx,height+1,zz,slab(True))
            # 每一叠的端头压一块整瓦，再盖半砖，形成翘起的墙头
            if distance in tuple(d for d,_ in steps[1:]):
                c.set(xx,height+1,zz,GREY)
                c.set(xx,height+2,zz,slab(True))
    if floors==2:
        # 二层正门外有实心回廊和护栏，不能把门直接开在半空。
        c.box(5,5,12,17,5,13,PLANK)
        for xx in range(5,18):c.set(xx,6,12,B('dark_oak_fence',north=False,south=False,east=xx<17,west=xx>5,waterlogged=False))
        for xx in (5,17):c.set(xx,6,13,B('dark_oak_fence',north=True,south=False,east=False,west=False,waterlogged=False))
        # 横向直梯有足够进深，上端穿过楼板并留下实际净空。
        for k in range(6):
            xx=6+k
            c.clear(xx,k+1,20,xx,k+3,20)
            c.set(xx,k,20,B('dark_oak_stairs',facing='east',half='bottom',shape='straight',waterlogged=False))
        c.set(7,6,16,B('white_bed',facing='north',part='foot',occupied=False))
        c.set(7,6,15,B('white_bed',facing='north',part='head',occupied=False))
    if kind==2:
        c.box(13,1,17,16,3,17,B('bookshelf'))
        table(c,7,1,17)
    elif kind==3:
        c.set(6,1,16,B('smoker',facing='north',lit=True))
        c.set(7,1,16,B('crafting_table'))
    else:table(c,8,1,17)
    return c


def market_stall(color='red',goods=0):
    """南市摊棚：条纹布篷、柜台与四种货色（杂货、瓜果、布匹、茶与花）。"""
    c=Canvas()
    c.box(0,0,0,5,0,5,STONE)
    for x in (0,5):
        for z in (0,5):c.box(x,1,z,x,4,z,WOOD)
    for x in range(6):
        for z in range(6):c.set(x,5,z,B(color+'_wool' if x%2 else 'white_wool'))
    c.box(1,1,4,4,1,4,B('barrel',facing='north',open=False))
    c.box(1,2,4,4,2,4,B('dark_oak_slab',type='bottom',waterlogged=False))
    if goods==1:
        c.set(1,1,1,B('melon'));c.set(2,1,1,B('pumpkin'));c.set(4,1,1,B('melon'))
        c.set(2,3,4,B('potted_red_tulip'))
    elif goods==2:
        for x,col in ((1,'cyan'),(2,'white'),(3,'blue'),(4,'light_gray')):
            c.set(x,1,1,B(col+'_carpet'))
        c.set(1,3,4,B('white_wool'));c.set(4,3,4,B('cyan_wool'))
    elif goods==3:
        for x in (1,3):c.set(x,3,4,B('potted_azalea_bush'))
        c.set(2,3,4,B('decorated_pot',facing='north',cracked=False,waterlogged=False))
        c.set(4,1,1,B('potted_flowering_azalea_bush'))
        c.set(1,1,1,B('composter',level=0))
    else:
        c.set(1,1,1,B('composter',level=0));c.set(4,1,1,B('decorated_pot',facing='north',cracked=False,waterlogged=False))
    return c


def well():
    """南市水井：井栏、四柱与小瓦顶，井里两格深的水，底和四周封住。"""
    c=Canvas()
    c.box(0,-3,0,4,-1,4,STONE)
    c.box(2,-2,2,2,0,2,B('water',level=0))
    for x in range(1,4):
        for z in range(1,4):
            if (x,z)!=(2,2):c.set(x,1,z,B('stone_brick_wall',up=True,north='none',south='none',east='none',west='none',waterlogged=False))
    for x,z in ((0,0),(4,0),(0,4),(4,4)):
        c.box(x,1,z,x,3,z,B('dark_oak_fence',north=False,south=False,east=False,west=False,waterlogged=False))
    for x in range(-1,6):
        for z in range(-1,6):
            edge=x in (-1,5) or z in (-1,5)
            if edge:
                facing='south' if z==-1 else 'north' if z==5 else 'east' if x==-1 else 'west'
                c.set(x,4,z,stairs(facing,True))
            else:c.set(x,4,z,GREY)
    c.set(2,5,2,slab(True))
    c.set(2,3,2,B('iron_chain',axis='y',waterlogged=False))
    return c


def paifang():
    """三间四柱的石牌坊，跨在九格宽的主街上：中间开间五格宽、五格高，两侧小间各两格。
    柱脚夹抱鼓石，额枋嵌云纹砖雕，三处各压一道瓦顶。"""
    c=Canvas();carved=L('taixuan_carved_brick')
    for x,top in ((1,6),(4,8),(10,8),(13,6)):
        c.box(x,0,1,x,top,1,STONE)
        for z,facing in ((0,'south'),(2,'north')):
            c.set(x,0,z,B('polished_andesite'))
            c.set(x,1,z,B('stone_brick_stairs',facing=facing,half='bottom',shape='straight',waterlogged=False))
    # 中间额枋与匾
    c.box(5,6,1,9,6,1,STONE)
    c.box(5,7,1,9,7,1,carved)
    c.box(4,8,1,10,8,1,STONE)
    for x in range(3,12):
        c.set(x,9,0,stairs('south',True));c.set(x,9,2,stairs('north',True));c.set(x,9,1,GREY)
    for x in range(3,12):c.set(x,10,1,slab(True))
    for x in (3,11):c.set(x,10,1,GREY);c.set(x,11,1,slab(True))
    # 两侧小间
    for x0,x1 in ((1,4),(10,13)):
        c.box(x0+1,4,1,x1-1,4,1,STONE)
        c.box(x0+1,5,1,x1-1,5,1,carved)
        c.box(x0,6,1,x1,6,1,STONE)
        lo,hi=(0,3) if x0==1 else (11,14)
        for x in range(lo,hi+1):
            c.set(x,7,0,stairs('south',True));c.set(x,7,2,stairs('north',True))
            if c.get(x,7,1) is None:c.set(x,7,1,GREY)
            if c.get(x,8,1) is None:c.set(x,8,1,slab(True))
    return c


def drum_tower():
    """城心鼓楼：十七格见方的石砌墩台，十字券洞让两条主街从下面穿过；
    墩台上是两层楼阁，上层挂钟；东北墩里有门和爬梯通到台顶。"""
    c=Canvas();n=17;h=8
    c.box(0,0,0,n-1,h-1,n-1,STONE)
    c.box(0,0,0,n-1,0,n-1,WHITE)
    # 七格宽、五格高的十字券洞，洞顶两侧用倒置台阶收成圆角
    c.clear(5,1,0,11,5,n-1);c.clear(0,1,5,n-1,5,11)
    for z in range(n):
        if not 5<=z<=11:
            c.set(5,5,z,B('stone_brick_stairs',facing='west',half='top',shape='straight',waterlogged=False))
            c.set(11,5,z,B('stone_brick_stairs',facing='east',half='top',shape='straight',waterlogged=False))
    for x in range(n):
        if not 5<=x<=11:
            c.set(x,5,5,B('stone_brick_stairs',facing='north',half='top',shape='straight',waterlogged=False))
            c.set(x,5,11,B('stone_brick_stairs',facing='south',half='top',shape='straight',waterlogged=False))
    for x in range(5,12):
        for z in range(5,12):c.set(x,0,z,WHITE if (x+z)%2 else B('polished_andesite'))
    # 券洞里挂灯
    for x,z in ((8,2),(8,14),(2,8),(14,8)):
        c.set(x,5,z,B('lantern',hanging=True,waterlogged=False))
    # 东北墩：西侧开门，进去两步是爬梯，上到台顶
    c.clear(12,1,2,13,2,2)
    door(c,12,1,2,'west','left')
    c.clear(14,1,2,14,h,2)
    c.box(14,1,2,14,h+1,2,B('ladder',facing='south',waterlogged=False))
    c.box(14,h,1,14,h+1,1,PLASTER)
    # 台顶：白石地面与灰泥女墙
    c.box(0,h,0,n-1,h,n-1,WHITE)
    c.box(14,h,2,14,h,2,B('ladder',facing='south',waterlogged=False))
    for i in range(n):
        for x,z in ((i,0),(i,n-1),(0,i),(n-1,i)):
            c.set(x,h+1,z,PLASTER);c.set(x,h+2,z,slab(True))
    part=hall(11,9,2,5,True)
    pw=max(p[0] for p in part.cells)+1;pd=max(p[2] for p in part.cells)+1
    c.paste(part,(n-pw)//2,h,(n-pd)//2,overwrite=True)
    # 上层正中挂钟（上方补一块木梁承托）
    cx=n//2;cz=n//2-1;y=h+5+4
    c.set(cx,y+1,cz,BEAM_X)
    c.set(cx,y,cz,B('bell',attachment='ceiling',facing='north',powered=False))
    return c
