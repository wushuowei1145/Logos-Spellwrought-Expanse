"""崖松、竹庭、山花、水潭和垂藤，按实际土壤和岩台高度拼装。"""
import math
import random
from taixuan_kit import B, Canvas, AIR
from taixuan_terrain import at, ground

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


def camphor(c,x,y,z,seed=0):
    """南市的老樟树：石栏树池里的粗干，三四根斜枝托起一团宽圆的树冠（持久树叶，不会衰败）。"""
    rng=random.Random(seed)
    leaf=B('oak_leaves',distance=1,persistent=True,waterlogged=False)
    bloom=B('flowering_azalea_leaves',distance=1,persistent=True,waterlogged=False)
    # 树池：一圈石栏，里面是草和土
    for dx in range(-2,3):
        for dz in range(-2,3):
            if max(abs(dx),abs(dz))==2:
                c.set(x+dx,y,z+dz,B('polished_andesite'))
                c.set(x+dx,y+1,z+dz,B('stone_brick_slab',type='bottom',waterlogged=False))
            else:
                c.set(x+dx,y-1,z+dz,B('dirt'));c.set(x+dx,y,z+dz,B('grass_block',snowy=False))
    trunk=B('oak_log',axis='y')
    for k in range(1,7):c.set(x,y+k,z,trunk)
    tips=[]
    for i in range(4):
        angle=i*math.tau/4+rng.uniform(-.4,.4)
        tip=(x+round(math.cos(angle)*3),y+6+rng.randint(1,2),z+round(math.sin(angle)*3))
        segment(c,(x,y+5,z),tip,B('oak_log',axis='x' if abs(tip[0]-x)>=abs(tip[2]-z) else 'z'),replace=True)
        tips.append(tip)
    tips.append((x,y+8,z))
    for tx,ty,tz in tips:
        for dx in range(-3,4):
            for dy in range(-1,3):
                for dz in range(-3,4):
                    if dx*dx+dz*dz+(dy*1.6)**2>11:continue
                    p=(tx+dx,ty+dy,tz+dz)
                    if free(c,*p):c.set(*p,bloom if (p[0]*7+p[1]*3+p[2]*5)%9==0 else leaf)
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


def pond_spot(c,spec,near,landmarks,rx=4,rz=6):
    """在这座浮峰上找一块放得下水潭的草地：椭圆、四邻岸边和外面再一圈都是草方块，高度和中心差不超过一格
    （池岸从池底封到水面，高一格低一格都漏不了水），上面空着，离殿阁包围盒至少 2 格；从 near 由近到远找。找不到返回 None。"""
    surface=spec['surface']
    def free_of_buildings(x,z):
        return all(not (l['origin'][0]-2<=x<=l['origin'][0]+l['size'][0]+2 and l['origin'][2]-2<=z<=l['origin'][2]+l['size'][2]+2) for l in landmarks)
    for (x,z) in sorted(surface,key=lambda p:(p[0]-near[0])**2+(p[1]-near[1])**2):
        y=surface[x,z];ok=True
        for dx in range(-rx-2,rx+3):
            for dz in range(-rz-2,rz+3):
                if (dx/(rx+2))**2+(dz/(rz+2))**2>1.05:continue
                h=surface.get((x+dx,z+dz))
                if h is None or abs(h-y)>1:ok=False;break
                s=c.get(x+dx,h,z+dz)
                if not s or s['Name']!='minecraft:grass_block' or not free(c,x+dx,h+1,z+dz) or not free_of_buildings(x+dx,z+dz):
                    ok=False;break
            if not ok:break
        if ok:return x,y,z
    return None


def build(c,islands,landmarks=()):
    """各浮峰上的崖松、竹丛、灌木、垂藤和卫峰上的水潭。落进深渊的瀑布在 taixuan_abyss 里画。"""
    pines=[];bamboos=0;gardens=[]
    # 水潭先放（崖松、竹丛、灌木都会占掉草地上面那格）：原来卫峰上的位置现在被演武殿占了。先在卫峰上就近找，横竖两个方向、大小两档都试；
    # 卫峰放不下就依次试别的侧峰
    x,_,z=at(108,217,68)
    order=[s for s in islands if s['seed']==68]+[s for s in islands if s['seed'] not in (68,17)]
    spot=None
    for spec in order:
        for size in ((4,6),(6,4),(3,5),(5,3)):
            spot=pond_spot(c,spec,(x,z) if spec['seed']==68 else (spec['center'][0],spec['center'][2]),landmarks,*size)
            if spot:break
        if spot:break
    if spot is not None:
        px,py,pz=spot
        gardens.append(pond(c,px,py+1,pz,*size))
        x,y,z=px,py,pz
        for step in range(1,9):
            xx,zz=x-step,z+step//2
            gy=ground(c,xx,zz)
            if gy is not None and free(c,xx,gy+1,zz):c.set(xx,gy+1,zz,B('smooth_stone_slab',type='bottom',waterlogged=False))
    # 主岛四棵、卫峰一棵、藏经峰、山门峰、东殿峰各一棵
    for i,(xx,north,altitude) in enumerate([(-98,25,218),(104,95,217),(-200,20,249),(-30,-140,188),(190,70,269),(60,-10,218),(-72,132,218),(49,138,218)]):
        x,_,z=at(xx,altitude,north);y=ground(c,x,z)
        if y is not None:
            p=pine(c,x,y,z,51+i,12 if i<6 else 9)
            if p:pines.append(p)
    for i,(xx,north,altitude) in enumerate([(-83,-2,218),(82,10,218),(-195,60,249),(185,30,269),(100,82,217)]):
        x,_,z=at(xx,altitude,north);y=ground(c,x,z)
        if y is not None:bamboos+=bamboo(c,x,y,z,170+i)
    for spec in islands:
        rng=random.Random(spec['seed'])
        for x,z in sorted(spec['surface']):
            if rng.random()>.018:continue
            y=ground(c,x,z)
            if y is not None:shrub(c,x,y,z,seed=x*19+z)
    return {'pines':pines,'bamboo_stalks':bamboos,'ponds':gardens,'vine_blocks':vines(c,islands)}
