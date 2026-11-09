"""离线生成浮峰的体素；游戏内只读取原版 NBT，不运行地形填块器。"""
import math
import random
import numpy as np
from taixuan_kit import B

SIZE=252
CENTER=126
SECT_Z=142
FLOOR=4
HORIZONTAL=.46
VERTICAL=.59


def at(x,altitude,north):
    return CENTER+round(x*HORIZONTAL),FLOOR+round(altitude*VERTICAL),SECT_Z-round(north*HORIZONTAL)


def noise(x,z):
    """整数散列插值得到连续噪声；标量与 NumPy 数组共用。"""
    a=np.floor(x).astype(np.int64);b=np.floor(z).astype(np.int64)
    u=x-a;v=z-b;u=u*u*(3-2*u);v=v*v*(3-2*v)
    def h(i,j):
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
    ymin=max(FLOOR+10,center[1]-math.ceil(drop*VERTICAL*1.1));ymax=min(SIZE-2,center[1]+2)
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
    materials=[B('stone'),B('andesite'),B('calcite'),B('diorite')]
    soil=B('dirt');grass=B('grass_block',snowy=False)
    count=0;tops={}
    for ix,iy,iz in np.argwhere(mask):
        px,py,pz=int(ix+xmin),int(iy+ymin),int(iz+zmin)
        if upper[ix,iy,iz]:state=grass;tops[px,pz]=max(py,tops.get((px,pz),-1))
        elif iy+2<mask.shape[1] and not mask[ix,iy+2,iz]:state=soil
        else:
            n=(px*13+pz*7+py//5+seed)%19
            state=materials[2 if n<5 else 3 if n==5 else 1 if n<9 else 0]
        c.set(px,py,pz,state);count+=1
    return {'seed':seed,'center':list(center),'blocks':count,'surface':tops,
            'shape':'偏斜断层、侧向岩脊与多个错位岩根'}


def ground(c,x,z,minimum=40):
    for y in range(SIZE-1,minimum-1,-1):
        s=c.get(x,y,z)
        if s and s['Name'] in {'minecraft:grass_block','minecraft:moss_block','minecraft:dirt'}:return y
    return None


def build(c):
    specs=[(0,48,218,107,113,117,17),(91,73,217,29,45,63,68),
           (-21,-116,188,44,30,58,21),(-178,81,249,34,51,139,31),
           (175,118,269,57,45,94,32),(106,-82,201,34,25,66,41),
           (-83,223,291,23,35,119,42)]
    result=[]
    for spec in specs:result.append(island(c,*spec))
    return result
