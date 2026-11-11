"""太玄建筑的小构件：局部坐标设计，复用现有画布的旋转和方块朝向规则。"""
import math
from fox_kit import Canvas, B, L, rotate_state

AIR = B('air')
WHITE = B('calcite')
STONE = B('stone_bricks')
TRIM = B('smooth_sandstone')
WOOD = B('stripped_dark_oak_log', axis='y')
BEAM_X = B('stripped_dark_oak_log', axis='x')
BEAM_Z = B('stripped_dark_oak_log', axis='z')
PLANK = B('dark_oak_planks')
PLASTER = L('journey_white_plaster')
# 宗门屋面用黄铜筒瓦（原版铜块太红）；瓦块按轴放，瓦垄顺坡：南北两坡 z，东西两坡 x
TILE = L('taixuan_brass_tiles', axis='z')
GREY = L('journey_kawara_roof')
GOLD = L('taixuan_brass_block')


def tile(axis='z'):
    return L('taixuan_brass_tiles', axis=axis)


def stairs(facing, city=False, half='bottom'):
    if city:return L('journey_kawara_stairs', facing=facing, half=half, shape='straight', waterlogged=False)
    return L('taixuan_brass_tile_stairs', facing=facing, half=half, shape='straight', waterlogged=False)


def slab(city=False, kind='bottom'):
    if city:return L('journey_kawara_slab', type=kind, waterlogged=False)
    return L('taixuan_brass_ridge_slab', type=kind, waterlogged=False)


def pillar(c, x, y, z, height):
    c.set(x, y, z, TRIM)
    c.box(x, y+1, z, x, y+height, z, WOOD)
    c.set(x, y+height+1, z, BEAM_X)


def rail(c, x0, z0, x1, z1, y, openings=()):
    """白石望柱与连续矮栏，入口按坐标留空。"""
    positions=set()
    for x in range(x0, x1+1):positions.update(((x,z0),(x,z1)))
    for z in range(z0, z1+1):positions.update(((x0,z),(x1,z)))
    skipped=set(openings)
    for x,z in sorted(positions-skipped):
        if x%3==0 or z%3==0:
            c.set(x,y,z,B('sandstone_wall',up=True,north='none',south='none',east='none',west='none',waterlogged=False))
            c.set(x,y+1,z,B('smooth_sandstone_slab',type='bottom',waterlogged=False))
        else:c.set(x,y,z,B('smooth_sandstone_slab',type='top',waterlogged=False))


def terrace(c, x, y, z, w, d, railing=True, entry=5):
    """台基只属于实际建筑；不向地面铺整块土层。"""
    c.box(x,y-2,z,x+w-1,y-1,z+d-1,STONE)
    c.box(x-1,y,z-1,x+w,y,z+d,WHITE)
    if railing:
        mid=x+w//2
        rail(c,x-1,z-1,x+w,z+d,y+1,[(xx,z+d) for xx in range(mid-entry//2,mid+entry//2+1)])


def roof(c, x, y, z, w, d, city=False, ring=False, eave=2):
    """四坡屋顶由瓦面、楼梯檐口、翘角和正脊组合；披檐只画墙外环带。"""
    xmin,xmax=x-eave,x+w-1+eave
    zmin,zmax=z-eave,z+d-1+eave
    half=(zmax-zmin)/2
    cx,cz=(xmin+xmax)/2,(zmin+zmax)/2
    ridge=max(0,(w-d)/2)
    max_y=y
    for xx in range(xmin,xmax+1):
        for zz in range(zmin,zmax+1):
            if ring and x+2<=xx<x+w-2 and z+2<=zz<z+d-2:continue
            edge=min(xx-xmin,xmax-xx,zz-zmin,zmax-zz)
            distance=max(abs(zz-cz),max(0,abs(xx-cx)-ridge))
            rise=max(0,half-distance)
            yy=y+int(rise*.62)
            if edge<=1 and min(abs(xx-xmin),abs(xx-xmax))<=1 and min(abs(zz-zmin),abs(zz-zmax))<=1:yy+=1
            if edge==0:
                facing='south' if zz==zmin else 'north' if zz==zmax else 'east' if xx==xmin else 'west'
                state=stairs(facing,city,half='top')
            elif city:state=slab(city,'double') if (xx+zz)%3==0 else GREY
            else:
                # 离屋脊的距离由南北向决定的是南北两坡，瓦垄南北走；否则是东西两坡
                state=tile('z' if abs(zz-cz)>=abs(xx-cx)-ridge else 'x')
            c.set(xx,yy,zz,state);max_y=max(max_y,yy)
            if yy>y:c.set(xx,yy-1,zz,PLANK)
    if not ring:
        lo,hi=math.ceil(cx-ridge),math.floor(cx+ridge)
        for xx in range(lo-1,hi+2):c.set(xx,max_y+1,round(cz),slab(city))
        if not city:
            for xx in (lo-1,hi+1):
                c.set(xx,max_y+1,round(cz),GOLD)
                c.set(xx,max_y+2,round(cz),B('sandstone_wall',up=True,north='none',south='none',east='none',west='none',waterlogged=False))
    return max_y+2


def lattice(c, x, y, z, width, height, facing='south'):
    for dx in range(width):
        for dy in range(height):
            c.set(x+dx,y+dy,z,B('dark_oak_trapdoor',facing=facing,half='bottom',open=True,powered=False,waterlogged=False))


def door(c, x, y, z, facing='south', hinge='left'):
    for half,dy in (('lower',0),('upper',1)):
        c.set(x,y+dy,z,B('dark_oak_door',facing=facing,half=half,hinge=hinge,open=False,powered=False))


def lamp(c,x,y,z):
    c.set(x,y,z,STONE)
    c.box(x,y+1,z,x,y+3,z,B('dark_oak_fence',north=False,south=False,east=False,west=False,waterlogged=False))
    c.set(x,y+4,z,B('lantern',hanging=False,waterlogged=False))
    c.set(x,y+5,z,slab())


def stair_run(c,x,y,z,width,length,rise):
    """两格净空的宽石阶；每次升高一格，台阶之间允许平段。"""
    for step in range(length):
        level=math.floor(step*rise/length)
        for xx in range(x,x+width):
            c.box(xx,y-1,z-step,xx,y+level-1,z-step,WHITE)
            c.set(xx,y+level,z-step,B('smooth_sandstone_stairs',facing='north',half='bottom',shape='straight',waterlogged=False))
            c.clear(xx,y+level+1,z-step,xx,y+level+3,z-step)
        for xx in (x-1,x+width):c.set(xx,y+level+1,z-step,B('smooth_sandstone_slab',type='top',waterlogged=False))


def bridge(c,a,b,width=3,arch=2,trace=None):
    """石桥；trace 是列表时把桥面中线每一格（脚下那格）记进去，给模板审计和世界测试检查能不能走。

    先画桥面和栏板，最后把所有桥面和头顶三格再铺一遍：折桥拐角处一段的栏板会压到另一段的桥面上。"""
    decks=[];corners=[]
    _bridge(c,a,b,width,arch,trace,decks,corners)
    laid={}
    for x,y,z,state in decks:laid[x,z]=y
    h=width//2;w=h+1
    for x,y,z in corners:
        for u in range(-h,h+1):
            for v in range(-h,h+1):
                if (x+u,z+v) not in laid:decks.append((x+u,y,z+v,WHITE));laid[x+u,z+v]=y
    for x,y,z,state in decks:
        c.set(x,y-1,z,STONE);c.set(x,y,z,state);c.clear(x,y+1,z,x,y+3,z)
    # 拐角平台外圈补上栏板（两段桥面进出的格子已经是桥面，不会被挡）
    for x,y,z in corners:
        for u in range(-w,w+1):
            for v in range(-w,w+1):
                if max(abs(u),abs(v))!=w or (x+u,z+v) in laid:continue
                c.set(x+u,y,z+v,STONE);c.set(x+u,y+1,z+v,B('smooth_sandstone_slab',type='top',waterlogged=False))


def _toward(s,e,n,y):
    """从 s 往 e 走 n 格（按主轴步进，和 _bridge 取格方式一样）的位置，高度给定。"""
    dx,dz=e[0]-s[0],e[2]-s[2]
    steps=max(abs(dx),abs(dz),1);t=min(1,n/steps)
    return (round(s[0]+dx*t),y,round(s[2]+dz*t))


def _bridge(c,a,b,width,arch,trace,decks,corners,skip_first=False):
    dx,dz=b[0]-a[0],b[2]-a[2]
    steps=max(abs(dx),abs(dz),1)
    transverse=(0,1) if abs(dx)>=abs(dz) else (1,0)
    if abs(b[1]-a[1])+math.pi*arch>steps:
        # 高差过大时折成“匚”字形延长行程，防止相邻楼梯一次升两格而无法步行。
        # 两个拐角各留一块平台：进出拐角的各 w 格是平的，两段桥面在拐角处同高，不会上下叠在一起。
        w=width//2+1
        run=abs(b[1]-a[1])/.75+4*w
        offset=max(6+w,math.ceil((run-steps)/2))
        sign=-1 if (a[2] if transverse[1] else a[0])<126 else 1
        ox,oz=transverse[0]*sign,transverse[1]*sign
        runs=(offset-w,max(0,steps-2*w),offset-w);total=sum(runs)
        y1=round(a[1]+(b[1]-a[1])*runs[0]/total)
        y2=round(a[1]+(b[1]-a[1])*(runs[0]+runs[1])/total)
        P=(a[0]+ox*offset,y1,a[2]+oz*offset);Q=(b[0]+ox*offset,y2,b[2]+oz*offset)
        a1=_toward(a,P,offset-w,y1)
        b1=_toward(P,Q,w,y1);b2=_toward(P,Q,steps-w,y2)
        c1=_toward(Q,b,w,y2)
        # 后面几段的第一格就是上一段的最后一格（可能是楼梯），不再重画
        first=skip_first
        for s0,e0 in ((a,a1),(a1,P),(P,b1),(b1,b2),(b2,Q),(Q,c1),(c1,b)):
            if (s0[0],s0[2])==(e0[0],e0[2]):continue
            _bridge(c,s0,e0,width,0,trace,decks,corners,first);first=True
        corners.extend([P,Q])
        return
    last_y=a[1]
    for i in range(1 if skip_first else 0,steps+1):
        t=i/steps
        x,z=round(a[0]+dx*t),round(a[2]+dz*t)
        y=round(a[1]+(b[1]-a[1])*t+math.sin(t*math.pi)*arch)
        for k in range(-width//2+1,width//2+1):
            xx,zz=x+transverse[0]*k,z+transverse[1]*k
            if y!=last_y:
                facing=('east' if dx>0 else 'west') if abs(dx)>=abs(dz) else ('south' if dz>0 else 'north')
                if y<last_y:facing={'north':'south','south':'north','east':'west','west':'east'}[facing]
                state=B('smooth_sandstone_stairs',facing=facing,half='bottom',shape='straight',waterlogged=False)
            else:state=WHITE
            c.set(xx,y-1,zz,STONE);c.set(xx,y,zz,state);c.clear(xx,y+1,zz,xx,y+3,zz)
            decks.append((xx,y,zz,state))
        if trace is not None:trace.append([x,y,z])
        for side in (-1,1):
            xx,zz=x+transverse[0]*side*(width//2+1),z+transverse[1]*side*(width//2+1)
            c.set(xx,y,zz,STONE)
            c.set(xx,y+1,zz,B('smooth_sandstone_slab',type='top',waterlogged=False))
        last_y=y
