"""崖松、竹庭、山花、水潭和垂藤，按实际土壤和岩台高度拼装。"""
import math
import random
from taixuan_kit import B, Canvas
from taixuan_terrain import at, ground, FLOOR

LEAF=B('spruce_leaves',distance=1,persistent=True,waterlogged=False)
LOG=B('spruce_log',axis='y')


def free(c,x,y,z):
    s=c.get(x,y,z)
    return not s or s['Name'] in {'minecraft:air','minecraft:short_grass','minecraft:fern'}


def segment(c,a,b,state,replace=False):
    length=max(abs(b[i]-a[i]) for i in range(3))
    for k in range(length+1):
        p=tuple(round(a[i]+(b[i]-a[i])*k/max(1,length)) for i in range(3))
        if replace or free(c,*p):c.set(*p,state)


def pine(c,x,y,z,seed=0,height=12):
    rng=random.Random(seed);lean=rng.choice((-1,1))
    if not free(c,x,y+1,z):return None
    for k in range(height):
        xx=x+lean*(k//5)
        if free(c,xx,y+k+1,z):c.set(xx,y+k+1,z,LOG)
    for level,radius in ((height//2,4),(height-3,4),(height,3)):
        angle=rng.uniform(0,math.tau)
        tip=(x+lean*(level//5)+round(math.cos(angle)*3),y+level,z+round(math.sin(angle)*2))
        segment(c,(x+lean*(level//5),y+level-1,z),tip,B('spruce_log',axis='x' if abs(tip[0]-x)>abs(tip[2]-z) else 'z'))
        for dx in range(-radius,radius+1):
            for dz in range(-radius,radius+1):
                if (dx/(radius+.1))**2+(dz/(radius*.75+.1))**2>1:continue
                for dy in range(2 if abs(dx)+abs(dz)<radius else 1):
                    p=(tip[0]+dx,tip[1]+dy,tip[2]+dz)
                    if free(c,*p):c.set(*p,LEAF)
    return [x,y,z]


def bamboo(c,x,y,z,seed=0,radius=3,count=11):
    rng=random.Random(seed);planted=0
    for _ in range(count):
        xx=x+rng.randint(-radius,radius);zz=z+rng.randint(-radius,radius)
        gy=ground(c,xx,zz)
        if gy is None or abs(gy-y)>3 or not free(c,xx,gy+1,zz):continue
        height=rng.randint(5,9)
        for dy in range(1,height+1):
            if not free(c,xx,gy+dy,zz):break
            c.set(xx,gy+dy,zz,B('bamboo',age=1,leaves='large' if dy>=height-1 else 'small' if dy==height-2 else 'none',stage=1 if dy==height else 0))
        planted+=1
    return planted


def shrub(c,x,y,z,seed=0):
    rng=random.Random(seed)
    for dx,dz in ((0,0),(1,0),(0,1),(-1,0),(0,-1)):
        yy=ground(c,x+dx,z+dz)
        if yy is None or abs(yy-y)>2 or not free(c,x+dx,yy+1,z+dz):continue
        c.set(x+dx,yy+1,z+dz,B(rng.choice(('azalea','flowering_azalea','fern','azure_bluet'))))


def pond(c,x,y,z,rx=4,rz=6):
    """石底和岸边先封闭，再填两层水；不在空气中放水面薄片。"""
    water={(dx,dz) for dx in range(-rx,rx+1) for dz in range(-rz,rz+1) if (dx/rx)**2+(dz/rz)**2<=1}
    shore={(dx+ox,dz+oz) for dx,dz in water for ox,oz in ((1,0),(-1,0),(0,1),(0,-1))}-water
    # 离散椭圆的四邻域全部封边，不能用连续半径阈值留下漏水格。
    for dx,dz in sorted(shore):
        c.box(x+dx,y-3,z+dz,x+dx,y-1,z+dz,B('mossy_cobblestone'))
        if (dx+dz)%2==0:c.set(x+dx,y,z+dz,B('mossy_cobblestone_slab',type='bottom',waterlogged=False))
    for dx,dz in sorted(water):
        c.set(x+dx,y-3,z+dz,B('clay'))
        c.box(x+dx,y-2,z+dz,x+dx,y-1,z+dz,B('water',level=0))
        c.clear(x+dx,y,z+dz,x+dx,y+2,z+dz)
        if (dx*7+dz*13)%11==0:c.set(x+dx,y,z+dz,B('lily_pad'))
    return [x,y,z,rx,rz]


def vines(c,islands):
    count=0
    for spec in islands:
        rng=random.Random(spec['seed']+971)
        surface=spec['surface']
        for x,z in sorted(surface):
            if rng.random()>.055:continue
            y=surface[x,z]
            for dx,dz,face in ((1,0,'west'),(-1,0,'east'),(0,1,'north'),(0,-1,'south')):
                if (x+dx,z+dz) in surface:continue
                length=rng.randint(4,13)
                for dy in range(length):
                    p=x+dx,y-dy,z+dz
                    if not free(c,*p):break
                    state=B('vine',up=False,**{k:k==face for k in ('north','south','east','west')})
                    c.set(*p,state);count+=1
                break
    return count


def build(c,islands):
    pines=[];bamboos=0;gardens=[]
    for i,(xx,north,altitude) in enumerate([(-98,25,218),(108,85,217),(-192,44,249),(-49,-121,188),(217,127,269),(125,-77,201),(-72,132,218),(49,138,218)]):
        x,_,z=at(xx,altitude,north);y=ground(c,x,z)
        if y is not None:
            p=pine(c,x,y,z,51+i,12 if i<6 else 9)
            if p:pines.append(p)
    for i,(xx,north,altitude) in enumerate([(-83,-2,218),(82,10,218),(-198,58,249),(204,144,269),(100,82,217)]):
        x,_,z=at(xx,altitude,north);y=ground(c,x,z)
        if y is not None:bamboos+=bamboo(c,x,y,z,170+i)
    for spec in islands:
        rng=random.Random(spec['seed'])
        for x,z in sorted(spec['surface']):
            if rng.random()>.018:continue
            y=ground(c,x,z)
            if y is not None:shrub(c,x,y,z,seed=x*19+z)
    x,_,z=at(108,217,68);y=ground(c,x,z)
    if y is not None:
        gardens.append(pond(c,x,y+1,z))
        for step in range(1,9):
            xx,zz=x-step,z+step//2
            gy=ground(c,xx,zz)
            if gy is not None and free(c,xx,gy+1,zz):c.set(xx,gy+1,zz,B('smooth_stone_slab',type='bottom',waterlogged=False))
    # 瀑布单独落向东侧预留水院，避免园林水潭漏水淹到下方屋顶。
    east=next(s for s in islands if s['seed']==32)
    x,z=max(east['surface'],key=lambda p:p[0]);y=ground(c,x,z)
    waterfall=None
    if y is not None:
        for dx,dz in ((-1,0),(0,-1),(0,1)):c.set(x+dx,y,z+dz,B('mossy_cobblestone'))
        c.set(x,y-1,z,B('stone'));c.set(x,y,z,B('water',level=0));waterfall=[x,y,z]
        gardens.append(pond(c,x+1,FLOOR+1,z,5,7))
    return {'pines':pines,'bamboo_stalks':bamboos,'ponds':gardens,'vine_blocks':vines(c,islands),'waterfall_source':waterfall}
