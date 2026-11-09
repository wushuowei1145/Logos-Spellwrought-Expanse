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
TILE = B('waxed_cut_copper')
GREY = L('journey_kawara_roof')
GOLD = B('yellow_terracotta')


def stairs(facing, city=False, half='bottom'):
    return (L if city else B)('journey_kawara_stairs' if city else 'waxed_cut_copper_stairs',
             facing=facing, half=half, shape='straight', waterlogged=False)


def slab(city=False, kind='bottom'):
    return (L if city else B)('journey_kawara_slab' if city else 'waxed_cut_copper_slab', type=kind, waterlogged=False)


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
            elif (xx+zz)%3==0:state=slab(city,'double')
            else:state=GREY if city else TILE
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


def bridge(c,a,b,width=3,arch=2):
    dx,dz=b[0]-a[0],b[2]-a[2]
    steps=max(abs(dx),abs(dz),1)
    transverse=(0,1) if abs(dx)>=abs(dz) else (1,0)
    if abs(b[1]-a[1])+math.pi*arch>steps:
        # 高差过大时折桥延长行程，防止相邻楼梯一次升两格而无法步行。
        offset=max(6,math.ceil((abs(b[1]-a[1])/.7-steps)/2)+3)
        sign=-1 if (a[2] if transverse[1] else a[0])<126 else 1
        p=(a[0]+transverse[0]*offset*sign,0,a[2]+transverse[1]*offset*sign)
        q=(b[0]+transverse[0]*offset*sign,0,b[2]+transverse[1]*offset*sign)
        total=steps+2*offset
        p=(p[0],round(a[1]+(b[1]-a[1])*offset/total),p[2])
        q=(q[0],round(a[1]+(b[1]-a[1])*(offset+steps)/total),q[2])
        bridge(c,a,p,width,0);bridge(c,p,q,width,0);bridge(c,q,b,width,0)
        return
    last_y=a[1]
    for i in range(steps+1):
        t=i/steps
        x,z=round(a[0]+dx*t),round(a[2]+dz*t)
        y=round(a[1]+(b[1]-a[1])*t+math.sin(t*math.pi)*arch)
        for k in range(-width//2+1,width//2+1):
            xx,zz=x+transverse[0]*k,z+transverse[1]*k
            c.set(xx,y-1,zz,STONE)
            if y!=last_y:
                facing=('east' if dx>0 else 'west') if abs(dx)>=abs(dz) else ('south' if dz>0 else 'north')
                if y<last_y:facing={'north':'south','south':'north','east':'west','west':'east'}[facing]
                c.set(xx,y,zz,B('smooth_sandstone_stairs',facing=facing,half='bottom',shape='straight',waterlogged=False))
            else:c.set(xx,y,zz,WHITE)
            c.clear(xx,y+1,zz,xx,y+3,zz)
        for side in (-1,1):
            xx,zz=x+transverse[0]*side*(width//2+1),z+transverse[1]*side*(width//2+1)
            c.set(xx,y,zz,STONE)
            c.set(xx,y+1,zz,B('smooth_sandstone_slab',type='top',waterlogged=False))
        last_y=y
