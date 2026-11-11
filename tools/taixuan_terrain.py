"""离线生成浮峰的体素；游戏内只读取原版 NBT，不运行地形填块器。"""
import math
import random
import numpy as np
from taixuan_kit import B

SIZE=252
# 竖直方向 11 块模板高：下面是深渊和雾底，上面是宗门
SIZE_Y=308
CENTER=126
# 主岛在概念图里的 north=48 处，落在画布正中
SECT_Z=148
FLOOR=4
# 宗门的高度基准：主岛顶面（概念高度 218）落在画布 229，即世界 y≈205
Y_SECT=100
HORIZONTAL=.46
VERTICAL=.59


def at(x,altitude,north):
    return CENTER+round(x*HORIZONTAL),Y_SECT+round(altitude*VERTICAL),SECT_Z-round(north*HORIZONTAL)


def noise(x,z):
    """整数散列插值得到连续噪声；标量与 NumPy 数组共用。"""
    a=np.floor(x).astype(np.int64);b=np.floor(z).astype(np.int64)
    u=x-a;v=z-b;u=u*u*(3-2*u);v=v*v*(3-2*v)
    def h(i,j):
        # 整数散列，乘法溢出就是想要的回绕
        with np.errstate(over='ignore'):
            n=i*374761393+j*668265263
            return np.mod((n^(n>>13))*1274126177,65536)/65535
    return (h(a,b)*(1-u)+h(a+1,b)*u)*(1-v)+(h(a,b+1)*(1-u)+h(a+1,b+1)*u)*v


def island(c,x,north,altitude,rx,rz,drop,seed):
    rng=random.Random(seed);phase=rng.uniform(0,6)
    lean=(rng.uniform(-.19,.19),rng.uniform(-.16,.16))
    roots=[(rng.uniform(-.46,.46),rng.uniform(-.44,.44),rng.uniform(.36,.60),rng.uniform(.44,.72),rng.uniform(.26,.55)) for _ in range(3)]
    center=at(x,altitude,north)
    xmin=max(1,center[0]-math.ceil(rx*HORIZONTAL*1.6));xmax=min(SIZE-2,center[0]+math.ceil(rx*HORIZONTAL*1.6))
    zmin=max(1,center[2]-math.ceil(rz*HORIZONTAL*1.6));zmax=min(SIZE-2,center[2]+math.ceil(rz*HORIZONTAL*1.6))
    ymin=max(1,center[1]-math.ceil(drop*VERTICAL*1.1));ymax=min(SIZE_Y-2,center[1]+2)
    xx=np.arange(xmin,xmax+1)[:,None,None]
    yy=np.arange(ymin,ymax+1)[None,:,None]
    zz=np.arange(zmin,zmax+1)[None,None,:]
    sx=(xx-center[0])/(rx*HORIZONTAL);sz=-(zz-center[2])/(rz*HORIZONTAL)
    u,v=np.broadcast_arrays(sx+yy*0,sz+yy*0)
    depth=(center[1]+1-yy)/(drop*VERTICAL)
    # 倾斜、下部收分与断层同时反解，不再把所有岛收成同一个尖锥。
    for _ in range(3):
        root=np.maximum.reduce([a*np.exp(-(((u-cx)/wx)**2+((v-cz)/wz)**2)**2*.85) for cx,cz,wx,wz,a in roots])
        t=np.clip(depth/(.40+root),0,1)
        notch=.10*np.exp(-((t-.56)/.17)**2)*noise(u*4+phase,v*4)
        taper=1-.18*t-notch
        rough=np.sin(np.pi*t)
        dx=(noise(u*7+seed+t*2,v*7-t*.8)-.5)*9*rough/rx
        dz=(noise(u*8+t,v*8+seed)-.5)*8*rough/rz
        u=(sx-lean[0]*t-dx)/taper
        v=(sz-lean[1]*t-dz)/taper
    a=u+.12*np.sin(v*3+phase)+v*.07;b=v+.10*np.sin(u*4+phase*.6)
    angle=np.arctan2(v,u)
    detail=.10*(noise(u*4+seed,v*4)-.5)+.11*np.sin(angle*2+phase)+.07*np.sin(angle*3+phase*.7)
    mask=1-(np.abs(a)**2.8+np.abs(b)**2.8)**(1/2.8)+detail>=0
    root=np.maximum.reduce([am*np.exp(-(((u-cx)/wx)**2+((v-cz)/wz)**2)**2*.85) for cx,cz,wx,wz,am in roots])
    wear=(noise(u*22+seed,v*22)-.5)*2.4/(drop*VERTICAL)
    mask &= (depth>=0)&(depth<=.40+root+wear)
    upper=mask & ~np.roll(mask,-1,axis=1)
    upper[:,-1,:]=mask[:,-1,:]
    soil=B('dirt');grass=B('grass_block',snowy=False)
    idx=np.argwhere(mask)
    states=rock(mask,idx,xmin,ymin,zmin,seed,center[1]-drop*VERTICAL*.45)
    count=0;tops={}
    for k,(ix,iy,iz) in enumerate(idx):
        px,py,pz=int(ix+xmin),int(iy+ymin),int(iz+zmin)
        if upper[ix,iy,iz]:state=grass;tops[px,pz]=max(py,tops.get((px,pz),-1))
        elif iy+2<mask.shape[1] and not mask[ix,iy+2,iz]:state=soil
        else:state=states[k]
        c.set(px,py,pz,state);count+=1
    return {'seed':seed,'center':list(center),'blocks':count,'surface':tops,
            'shape':'偏斜断层、侧向岩脊与多个错位岩根'}


# 岩层：起伏的水平层理为主，夹薄层白色方解石与暗色凝灰岩；少量竖向节理；崖顶外露面有苔藓，底面挂滴水石。
STRATA=(('stone',30),('andesite',20),('calcite',11),('stone',16),('diorite',10),('tuff',6),('andesite',7))
_STRATA_TOTAL=sum(w for _,w in STRATA)


def _stratum(band,seed):
    h=(band*2654435761+seed*40503)%_STRATA_TOTAL
    for name,w in STRATA:
        if h<w:return name
        h-=w
    return 'stone'


def rock(mask,idx,xmin,ymin,zmin,seed,belly):
    """按格给出岩体材质；只看得见外露面，但层理对整块岩体一致，挖开后也连贯。"""
    px=idx[:,0]+xmin;py=idx[:,1]+ymin;pz=idx[:,2]+zmin
    # 层面随位置缓慢起伏 ±4 格，层厚 3～4 格
    warp=(noise(px/23.0+seed*.31,pz/23.0)-.5)*8+(noise(px/7.0,pz/7.0+seed)-.5)*1.5
    band=np.floor((py+warp)/3.5).astype(np.int64)
    joint=noise(px/2.3+seed,pz/2.3)>.8
    # 外露方向：下方是空气为底面（只算岛腹 belly 以下），向上 3～5 格内有地表为崖顶
    def at(dx,dy,dz):
        x=idx[:,0]+dx;y=idx[:,1]+dy;z=idx[:,2]+dz
        ok=(x>=0)&(x<mask.shape[0])&(y>=0)&(y<mask.shape[1])&(z>=0)&(z<mask.shape[2])
        out=np.zeros(len(idx),bool)
        out[ok]=mask[x[ok],y[ok],z[ok]]
        return out
    below_air=~at(0,-1,0)
    side_air=~(at(1,0,0)&at(-1,0,0)&at(0,0,1)&at(0,0,-1))
    near_top=~at(0,3,0)|~at(0,5,0)
    speck=noise(px/1.7+seed*.7,py/1.7+pz*.31)
    cache={};out=[]
    for k in range(len(idx)):
        if below_air[k] and py[k]<belly and not side_air[k]:
            # 只有岛腹真正朝下的面挂滴水石，侧壁逐层内收的台阶不算
            name='dripstone_block' if speck[k]>.62 else 'tuff' if speck[k]>.38 else _stratum(int(band[k]),seed)
        elif side_air[k] and near_top[k] and speck[k]>.62:
            name='moss_block' if speck[k]>.8 else 'mossy_cobblestone'
        elif joint[k] and side_air[k]:
            name='tuff' if speck[k]>.5 else 'andesite'
        else:
            # 层内混入相邻层的碎块，避免一整条同色的平带
            b=int(band[k])+(1 if speck[k]>.74 else -1 if speck[k]<.2 else 0)
            name=_stratum(b,seed)
        if name not in cache:cache[name]=B(name)
        out.append(cache[name])
    return out


def ground(c,x,z,minimum=140):
    for y in range(SIZE_Y-1,minimum-1,-1):
        s=c.get(x,y,z)
        if s and s['Name'] in {'minecraft:grass_block','minecraft:moss_block','minecraft:dirt'}:return y
    return None


def build(c):
    # 主岛压在深渊正上方；四座侧峰落在东南西北，避开四个斜角上的镇渊柱；东侧一座卫峰贴着主岛
    specs=[(0,48,218,107,113,117,17),(96,83,217,29,45,63,68),
           (0,-135,188,44,30,58,21),(-187,48,249,34,51,139,31),
           (172,48,269,45,45,94,32),(0,235,291,23,35,119,42)]
    result=[]
    for spec in specs:result.append(island(c,*spec))
    return result
