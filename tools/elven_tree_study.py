"""用确定性的方块体积和软件渲染器绘制精灵森林树形研究图。

仅输出离线设计图和尺寸记录，不注册群系，也不参与游戏世界生成。
"""
from pathlib import Path
import json
import math
import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'docs/concepts/elven-forest'
FONT = Path('C:/Windows/Fonts/msyh.ttc')
BOLD = Path('C:/Windows/Fonts/msyhbd.ttc')
INK = '#35463e'
MUTED = '#78857a'
PAPER = '#f1f2e9'


class Volume:
    """一格对应一个方块；材质编号只用于离线配色。"""
    def __init__(self, radius, height):
        self.origin = np.array([-radius, -4, -radius], dtype=np.int32)
        self.data = np.zeros((radius * 2 + 1, height + 8, radius * 2 + 1), dtype=np.uint8)

    def ellipsoid(self, center, radii, material=1, leafy=False, empty=False):
        center = np.asarray(center, dtype=float)
        radii = np.maximum(radii, .65)
        low = np.maximum(np.floor(center - radii * 1.15).astype(int) - self.origin, 0)
        high = np.minimum(np.ceil(center + radii * 1.15).astype(int) - self.origin + 1, self.data.shape)
        if np.any(high <= low):
            return
        sl = tuple(slice(int(a), int(b)) for a, b in zip(low, high))
        x, y, z = np.ogrid[low[0]:high[0], low[1]:high[1], low[2]:high[2]]
        x, y, z = x + self.origin[0], y + self.origin[1], z + self.origin[2]
        d = ((x-center[0])/radii[0])**2 + ((y-center[1])/radii[1])**2 + ((z-center[2])/radii[2])**2
        # 连续低频起伏打散叶团边界，保留有厚度的实心叶冠。
        edge = 1 + (.13*np.sin(x*.41+z*.16)*np.cos(y*.33)+.08*np.sin(z*.53-y*.24) if leafy else 0)
        mask = d <= edge
        view = self.data[sl]
        if empty:
            mask &= view == 0
        view[mask] = material

    def tube(self, points, r0, r1, material=1):
        points = np.asarray(points, float)
        for t in np.linspace(0, 1, max(8, int(np.linalg.norm(np.diff(points, axis=0), axis=1).sum()*1.1))):
            p = (1-t)**3*points[0]+3*(1-t)**2*t*points[1]+3*(1-t)*t*t*points[2]+t**3*points[3]
            r = r0*(1-t)+r1*t
            self.ellipsoid(p, (r, r*.95, r), material)

    def ground(self, radius):
        # 仅为研究图提供自然形展示底面，不代表生成时抬地或铺土台。
        for y in range(-3, 1):
            self.ellipsoid((0, y, 0), (radius, .7, radius*.80), 6 if y == 0 else 5)
        rng = np.random.default_rng(20261021)
        for _ in range(65):
            x, z = rng.uniform(-radius, radius, 2)
            if x*x+(z/.8)**2 < radius*radius:
                self.ellipsoid((x, 1, z), (rng.uniform(1, 3), rng.uniform(.6, 1.4), rng.uniform(1, 3)), 3)

    def hollow(self):
        # 最后清空树心，避免根和枝条侵入未来居住空间。
        x, z = np.ogrid[:self.data.shape[0], :self.data.shape[2]]
        x, z = x+self.origin[0], z+self.origin[2]
        for y in range(2, 96):
            radius = 13 if y < 82 else 13-(y-82)*.22
            view = self.data[:, y-self.origin[1], :]
            view[x*x+z*z < radius*radius] = 0
            if y < 35:
                arch = (x/9)**2+(max(0, y-19)/16)**2 < 1
                view[arch & (z < 0)] = 0

    def counts(self):
        occupied = np.argwhere(self.data)
        lo, hi = occupied.min(axis=0)+self.origin, occupied.max(axis=0)+self.origin
        return {'size': (hi-lo+1).tolist(), 'occupied_blocks': int(len(occupied)), 'bounds': [lo.tolist(), hi.tolist()]}


def broadleaf(height=72, radius=4.5, seed=3):
    v = Volume(int(height*.56), height+8)
    rng = np.random.default_rng(seed)
    v.ground(height*.22)
    for y in range(int(height*.82)):
        r = radius*(1-y/height*.75)+radius*.75*math.exp(-y/6)
        v.ellipsoid((math.sin(y/20)*1.2, y, math.sin(y/27)), (r, 1.1, r))
    for a in np.linspace(0, math.tau, 7, endpoint=False):
        end = np.array([math.cos(a)*radius*3.3, .5, math.sin(a)*radius*3.3])
        v.tube([(0, 12, 0), (end[0]*.4, 7, end[2]*.4), (end[0]*.8, 2, end[2]*.8), end], radius*.6, .7)
    for i, a in enumerate(np.linspace(0, math.tau, 7, endpoint=False)):
        h = height*(.66+rng.uniform(-.07, .07))
        end = np.array([math.cos(a)*height*.24, h, math.sin(a)*height*.24])
        v.tube([(0, height*.36, 0), (end[0]*.45, h*.75, end[2]*.45), (end[0]*.8, h*.94, end[2]*.8), end], radius*.67, 1.0)
        v.ellipsoid(end+(0, height*.055, 0), (height*.18, height*.135, height*.165), 2, True, True)
    v.ellipsoid((1, height*.86, 0), (height*.22, height*.145, height*.20), 2, True, True)
    return v


def fir(height=84):
    v = Volume(30, height+8)
    v.ground(18)
    for y in range(height-3):
        r = max(.8, 4.7*(1-y/height)) + 2*math.exp(-y/6)
        v.ellipsoid((math.sin(y/35), y, 0), (r, 1, r))
    for i, y in enumerate(range(25, height, 7)):
        radius = 19*(1-y/height)+2.8
        for a in np.linspace(0, math.tau, 5, endpoint=False)+i*.51:
            x, z = math.cos(a)*radius*.55, math.sin(a)*radius*.55
            v.tube([(0,y-3,0),(x*.5,y-3,z*.5),(x,y-2,z),(x,y,z)], 1.8, .6)
            v.ellipsoid((x,y,z), (radius*.70, 4.5, radius*.70), 4, True, True)
    v.ellipsoid((0,height-3,0), (3,6,3), 4, True, True)
    return v


def world_tree():
    v = Volume(112, 226)
    v.ground(54)
    # 树干以缓慢扭转的棱脊塑形，根颈自然展宽，避免圆筒或整齐塔柱。
    x, z = np.ogrid[-112:113, -112:113]
    for y in range(153):
        cx, cz = math.sin(y/48)*2.5, math.sin(y/59)*2
        theta = np.arctan2(z-cz, x-cx)
        r = (23.5-y*.018 if y<=96 else 21.77-(y-96)*.22) + 5*math.exp(-y/14)
        ridge = 1+.07*np.cos(theta*9-y*.018)+.035*np.sin(theta*5+y*.04)
        v.data[:, y+4, :][(x-cx)**2+(z-cz)**2 <= (r*ridge)**2] = 1
    for i, a in enumerate(np.linspace(0, math.tau, 10, endpoint=False)):
        end = np.array([math.cos(a)*43, .5, math.sin(a)*43])
        v.tube([(math.cos(a)*12,34,math.sin(a)*12),(end[0]*.65,22,end[2]*.65),(end[0]*.88,4,end[2]*.88),end], 6.3, 1.7)
        v.tube([(math.cos(a)*14,17,math.sin(a)*14),(end[0]*.7,10,end[2]*.7),(end[0]*.9,4,end[2]*.9),end], 2.0, .7, 3)
    rng = np.random.default_rng(31)
    ends = []
    for i, a in enumerate(np.linspace(0, math.tau, 8, endpoint=False)):
        reach = 55+rng.uniform(-5, 7)
        end = np.array([math.cos(a)*reach, 148+rng.uniform(-5, 18), math.sin(a)*reach])
        start = (math.cos(a)*8, 83+i%3*10, math.sin(a)*8)
        v.tube([start,(end[0]*.42,113,end[2]*.42),(end[0]*.90,end[1]-6,end[2]*.90),end], 8.0, 2.3)
        ends.append(end)
        for side in (-1, 1):
            fork = end+np.array([math.cos(a+side*.7)*15, 14+side*3, math.sin(a+side*.7)*15])
            v.tube([end- (0,5,0),end+(0,7,0),fork-(0,5,0),fork], 3.3, 1)
            ends.append(fork)
    # 树冠由不等高的厚叶团组成；分枝之间留下透光间隙。
    for i, end in enumerate(ends):
        v.ellipsoid(end+(0,8,0), (16+i%3, 10+i%2, 16+i%4), 2, True, True)
        for j in range(3):
            a = i*.83+j*math.tau/3
            tip = end+np.array([math.cos(a)*13, 10+j*2, math.sin(a)*13])
            v.ellipsoid(tip, (12+j, 9+j, 12+j), 2, True, True)
    for center, size in [((1,194,1),(35,21,31)),((-24,183,9),(27,19,28)),((21,181,-17),(29,18,27)),((8,176,28),(24,17,27))]:
        v.ellipsoid(center, size, 2, True, True)
    # 只在少数枝梢挂垂叶，不用大面积荧光覆盖自然配色。
    for end in ends[::2]:
        for j in range(3):
            xx, zz = end[0]+j*4-4, end[2]+2
            v.tube([(xx,end[1],zz),(xx+1,end[1]-6,zz),(xx+1,end[1]-14,zz),(xx,end[1]-20-j*2,zz)], 1.7, .6, 3)
    v.hollow()
    return v


def render(v, size, yaw=25, elevation=17, crop=None):
    """按可见方块面排序绘制正交投影，不借助图片生成服务。"""
    data = v.data if crop is None else crop
    angle, pitch = math.radians(yaw), math.radians(elevation)
    right = np.array([math.cos(angle), 0, math.sin(angle)])
    up = np.array([-math.sin(angle)*math.sin(pitch), math.cos(pitch), math.cos(angle)*math.sin(pitch)])
    eye = np.cross(up, right)
    axes = [(0,1), (1,1), (2,-1)]
    offsets = [np.array([[1,0,0],[1,1,0],[1,1,1],[1,0,1]]),np.array([[0,1,0],[0,1,1],[1,1,1],[1,1,0]]),np.array([[0,0,0],[0,1,0],[1,1,0],[1,0,0]])]
    faces, mids, mats, sides = [], [], [], []
    occupied = data != 0
    for side, ((axis, sign), corners) in enumerate(zip(axes, offsets)):
        neighbour = np.roll(occupied, -sign, axis=axis)
        border = [slice(None)]*3
        border[axis] = -1 if sign > 0 else 0
        neighbour[tuple(border)] = False
        indices = np.argwhere(occupied & ~neighbour)
        coords = indices + v.origin
        faces.append(coords[:,None,:]+corners[None,:,:])
        mids.append(coords)
        mats.append(data[tuple(indices.T)])
        sides.append(np.full(len(coords), side, dtype=np.uint8))
    # 玩家按 1.8 格身高绘制，作为世界树和树心的实际尺度参照。
    if v.data.shape[0] > 150:
        px, pz, py = (5., -36., 1.) if crop is None else (0., 2., 2.)
        parts = [((px-.24,py,pz-.13),(px+.24,py+.72,pz+.13),9),((px-.30,py+.72,pz-.17),(px+.30,py+1.34,pz+.17),8),((px-.23,py+1.34,pz-.23),(px+.23,py+1.8,pz+.23),7)]
        for low, high, material in parts:
            lo, hi = np.array(low), np.array(high)
            for side, corners in enumerate(offsets):
                vertices = lo+(hi-lo)*corners
                faces.append(vertices[None,:,:])
                mids.append(((lo+hi)*.5)[None,:])
                mats.append(np.array([material], dtype=np.uint8))
                sides.append(np.array([side], dtype=np.uint8))
    xyz = np.concatenate(faces)
    mids, mats, sides = np.concatenate(mids), np.concatenate(mats), np.concatenate(sides)
    screen = np.stack([xyz@right, -(xyz@up)], axis=-1)
    low, high = screen.min(axis=(0,1)), screen.max(axis=(0,1))
    aa = 1.4
    target = np.array(size)*aa
    scale = min((target-30)/(high-low))
    screen = (screen-(low+high)*.5)*scale+target*.5
    order = np.argsort(mids@eye, kind='stable')
    # 同一材质使用连续色带和小幅方块噪声，顶面暖亮，侧面冷绿。
    integers = mids.astype(np.int64)
    noise = ((integers[:,0]*73856093 ^ integers[:,1]*19349663 ^ integers[:,2]*83492791) % 101)/100-.5
    woodgrain = np.sin(mids[:,0]*1.8+mids[:,2]*.7)*.08 + np.sin(mids[:,1]*.12+mids[:,0]*.5)*.025
    base = np.zeros((len(mats),3), dtype=float)
    for material, color in {1:(116,100,77),2:(94,130,92),3:(82,116,77),4:(69,104,87),5:(125,116,91),6:(128,147,99),7:(198,165,127),8:(55,111,121),9:(60,66,77)}.items():
        base[mats==material] = color
    brightness = np.choose(sides, [.78, 1.10, .94])*(1+noise*.15)
    brightness += np.where(mats==1, woodgrain, .035*np.sin(mids[:,0]*.17+mids[:,2]*.11))
    if v.data.shape[0] > 150:
        # 洞内背光表面使用衰减环境光，洞口与剖面才有清楚的空间深度。
        adjacent = mids+np.stack([sides==0,sides==1,-(sides==2).astype(int)],axis=1)
        inner_radius = np.where(adjacent[:,1]<82,13,13-(adjacent[:,1]-82)*.22)
        inner = (mats==1)&(adjacent[:,0]**2+adjacent[:,2]**2<inner_radius**2)&(adjacent[:,1]>=2)&(adjacent[:,1]<96)
        brightness[inner] *= .38+.18*mids[inner,1]/96
    colors = np.clip(base*brightness[:,None],0,255).astype(np.uint8)
    im = Image.new('RGBA', tuple(target.astype(int)))
    draw = ImageDraw.Draw(im)
    for i in order:
        draw.polygon([tuple(p) for p in screen[i]], fill=tuple(colors[i])+(255,))
    result = im.resize(size, Image.Resampling.LANCZOS)
    if v.data.shape[0]>150:
        point=np.array([px,py,pz]);projected=np.array([point@right,-point@up])
        result.info['player_position']=((projected-(low+high)*.5)*scale+target*.5)/aa
    return result


def text(draw, pos, value, size=25, color=INK, bold=False):
    draw.text(pos, value, fill=color, font=ImageFont.truetype(str(BOLD if bold else FONT),size))


def panel(draw, box):
    draw.rounded_rectangle(box, 20, fill='#e6ebe0', outline='#d7ded2', width=2)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    world = world_tree()
    oak, pine = broadleaf(), fir()
    metrics = {'stage':'代码绘制的树形研究，尚未接入游戏', 'world_tree':world.counts(), 'canopy_tree':oak.counts(), 'spire_fir':pine.counts(), 'hollow_clear_diameter':26, 'hollow_clear_height':80, 'seed':20261021}
    # 用实际空心体积校验净空，避免效果图看似中空但几何仍是实心。
    xx,zz=np.ogrid[-112:113,-112:113]
    chamber=xx*xx+zz*zz<13**2
    shell=(xx*xx+zz*zz>=13**2)&(xx*xx+zz*zz<=16**2)
    assert not world.data[:,6:86,:][np.broadcast_to(chamber[:,None,:],(225,80,225))].any()
    assert world.data[:,40:86,:][np.broadcast_to(shell[:,None,:],(225,46,225))].all()
    print(json.dumps(metrics, ensure_ascii=False), flush=True)
    board = Image.new('RGB',(2400,1600),PAPER)
    d = ImageDraw.Draw(board)
    text(d,(70,40),'精灵森林 / 树形研究',54,bold=True)
    text(d,(73,111),'高大而自然的林冠 · 中空世界树 · 分层叶冠与自然根拱',26,MUTED)
    text(d,(1920,65),'01   代码绘制预览',24,MUTED)
    panel(d,(45,175,1555,1500));panel(d,(1580,175,2355,1035));panel(d,(1580,1060,2355,1500))
    hero = render(world,(1450,1205))
    board.paste(hero,(72,225),hero)
    hx,hy=hero.info['player_position']+np.array([72,225])
    d.line((hx-6,hy-2,hx-110,hy+18),fill=MUTED,width=2)
    d.rounded_rectangle((hx-257,hy-3,hx-114,hy+38),8,fill=PAPER)
    text(d,(hx-247,hy+2),'玩家 1.8 格',19)
    text(d,(84,198),'世界树 / 中央地标',30,bold=True)
    text(d,(88,1437),'约 216 格高   ·   宽冠约 200 格   ·   树干约 44 格粗',26)
    # 两种林冠树共用同一块数比例，便于比较轮廓和粗细。
    a=render(oak,(378,650));b=render(pine,(354,714))
    board.paste(a,(1587,287),a);board.paste(b,(1990,223),b)
    text(d,(1618,207),'林地巨木 / 约 60–90 格高',28,bold=True)
    text(d,(1625,937),'宽冠古木',28,bold=True);text(d,(2048,937),'高塔杉木',28,bold=True)
    text(d,(1620,988),'分叉粗干、层叠叶冠，保留林下通行空间',22,MUTED)
    text(d,(1618,1090),'树心留白',30,bold=True)
    text(d,(1620,1152),'净空直径约 26 格',27)
    text(d,(1620,1202),'下部内厅净高至少 80 格',27)
    text(d,(1620,1252),'根拱自然敞开，内部暂不放建筑',25)
    colors=['#74644d','#52744d','#5e825c','#93a875','#bac9b1']
    for i,c in enumerate(colors):d.rounded_rectangle((1624+i*131,1335,1732+i*131,1380),8,fill=c)
    text(d,(1620,1410),'灰棕树皮 / 苔绿 / 鼠尾草绿 / 柔金叶缘',22,MUTED)
    text(d,(70,1532),'树形与尺度预览，非游戏实机；全部由方块体积计算与软件绘制。',23,MUTED)
    board.save(OUT/'tree-study.png')
    # 剖切只移除前半侧树壳，保留后壁，展示实际留空的树心。
    cut = world.data.copy()
    cut[:,114:,:] = 0
    cut[:,:,:112] = 0
    cut[:,98:,:] = 0
    room = Image.new('RGB',(2100,1450),PAPER)
    dr=ImageDraw.Draw(room)
    text(dr,(65,42),'世界树 / 树心剖面',50,bold=True)
    text(dr,(68,114),'切去半侧树壳，保留自然后壁与根拱；内部不放房屋、阶梯或装饰。',25,MUTED)
    img=render(world,(1420,1180),yaw=12,elevation=16,crop=cut)
    room.paste(img,(10,195),img)
    hx,hy=img.info['player_position']+np.array([10,195])
    dr.line((hx+8,hy-2,hx+100,hy+26),fill=MUTED,width=2)
    dr.rounded_rectangle((hx+102,hy+7,hx+270,hy+54),8,fill=PAPER)
    text(dr,(hx+111,hy+12),'玩家 1.8 格',22)
    text(dr,(1470,280),'预留给精灵族的家园',32,bold=True)
    text(dr,(1470,370),'内厅约 26 格宽',29)
    text(dr,(1470,425),'可用净高至少 80 格',29)
    text(dr,(1470,510),'厚树壳与外部棱脊',26)
    text(dr,(1470,560),'低处根拱作为自然入口',26)
    text(dr,(1470,610),'分枝从外壁向上生长',26)
    text(dr,(1470,660),'内部空心已做几何校验',26)
    dr.line((1500,800,1900,800),fill='#b8c3b3',width=2)
    text(dr,(1470,850),'先确认树形和尺度',30,bold=True)
    text(dr,(1470,915),'通过后再制作原版数据包',25,MUTED)
    text(dr,(1470,958),'群系、树木结构和定位入口',25,MUTED)
    text(dr,(65,1385),'离线体积剖面，非实机截图；尚未向维度添加任何生成规则。',23,MUTED)
    room.save(OUT/'world-tree-cutaway.png')
    (OUT/'dimensions.json').write_text(json.dumps(metrics,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('TREE_STUDY_READY',OUT,flush=True)


if __name__ == '__main__':
    main()
