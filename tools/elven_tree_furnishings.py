"""树心少量精灵家具、叶形灯具与预览照明的参数化几何。"""
import math
import numpy as np

COLORS = {
    10: (159, 124, 82), 11: (218, 206, 170), 12: (186, 155, 86),
    13: (119, 148, 119), 14: (141, 92, 87), 15: (95, 131, 142),
    16: (92, 119, 83), 17: (255, 237, 180), 18: (100, 138, 89),
    19: (128, 105, 72), 20: (93, 83, 60), 21: (202, 193, 154),
    22: (145, 113, 77), 23: (236, 229, 202), 24: (243, 225, 168),
}
SIGNS = np.array([[0,0,0],[1,0,0],[1,1,0],[0,1,0],[0,0,1],[1,0,1],[1,1,1],[0,1,1]],float)
QUADS = [(1,2,6,5),(0,4,7,3),(3,7,6,2),(0,1,5,4),(0,3,2,1),(4,5,6,7)]
NORMALS = np.array([[1,0,0],[-1,0,0],[0,1,0],[0,-1,0],[0,0,-1],[0,0,1]],float)


class Furnishings:
    """几何保留物件类别，方便校验中央通道与地面支撑。"""
    def __init__(self):
        self.parts = []
        self.lights = []
        self.objects = []

    def box(self, center, size, material, kind, yaw=0):
        a=math.radians(yaw)
        rotation=np.array([[math.cos(a),0,math.sin(a)],[0,1,0],[-math.sin(a),0,math.cos(a)]])
        points=((SIGNS-.5)*size)@rotation.T+center
        self.parts.append({'points':points,'normals':NORMALS@rotation.T,'material':material,'kind':kind})

    def stem(self, points, width, material, kind):
        points=np.array(points,float)
        for start,end in zip(points[:-1],points[1:]):
            steps=max(2,int(np.linalg.norm(end-start)/max(.12,width*.7)))
            for t in np.linspace(0,1,steps):
                self.box(start+(end-start)*t,(width,width,width),material,kind)

    def leaf(self, center, height, width, material, kind, yaw=0):
        # 分层收尖的叶形轮廓，避免做成普通方框或金属灯笼。
        for t in np.linspace(.08,.92,9):
            self.box(np.array(center)+(0,(t-.5)*height,0),(width*math.sin(t*math.pi),height/8,.14),material,kind,yaw)

    def lantern(self, pos, scale=1, power=.48):
        x,y,z=pos
        self.leaf((x,y,z),1.9*scale,1.1*scale,11,'灯罩')
        self.leaf((x,y,z),1.9*scale,1.1*scale,11,'灯罩',90)
        self.box((x,y,z),(.38*scale,.86*scale,.38*scale),17,'灯芯')
        self.box((x,y-1.02*scale,z),(.15*scale,.20*scale,.15*scale),12,'灯梢')
        self.lights.append((np.array(pos,float),power,8.5*scale))

    def bench(self, cx,cz,yaw):
        a=math.radians(yaw)
        right=np.array([math.cos(a),0,-math.sin(a)])
        back=np.array([math.sin(a),0,math.cos(a)])
        origin=np.array([cx,2,cz],float)
        def piece(x,y,z,size,material):
            self.box(origin+right*x+(0,y,0)+back*z,size,material,'座椅',yaw)
        for x in (-1.45,1.45):
            for z in (-.36,.36):piece(x,.39,z,(.16,.78,.16),10)
        piece(0,.84,0,(3.45,.19,1.02),11)
        piece(0,.99,-.06,(3.03,.16,.73),13)
        for x in (-1.58,1.58):
            piece(x,1.20,.04,(.14,.8,.14),10)
            piece(x,1.58,-.06,(.20,.11,1.02),11)
        for x in np.linspace(-1.4,1.4,7):
            p=origin+right*x+back*.43+(0,1.52,0)
            self.leaf(p,1.15,.43,11,'座椅',yaw)
        piece(0,1.09,.45,(3.18,.13,.13),12)
        self.objects.append({'name':'曲木叶背长椅','center':[cx,2,cz]})

    def bookcase(self):
        cx,cz=-5.6,9.2
        for y in (2.08,3.00,3.92,4.8):
            self.box((cx,y,cz),(3.5,.16,.8),11,'书架')
        for x in (cx-1.66,cx+1.66):self.box((x,3.4,cz),(.17,2.8,.8),10,'书架')
        rng=np.random.default_rng(401)
        for row,y in enumerate((2.56,3.48,4.36)):
            x=cx-1.43
            while x<cx+1.35:
                w=float(rng.uniform(.12,.23));h=float(rng.uniform(.42,.69))
                self.box((x+w/2,y,cz-.10),(w,h,.46),int(rng.choice([13,14,15,16,23])),'书籍')
                self.box((x+w/2,y-h*.27,cz-.34),(w*.8,.025,.022),12,'书籍')
                x+=w+.035
        for dx in (-1.6,1.6):self.leaf((cx+dx,5.0,cz),.8,.4,18,'书架绿饰')
        self.objects.append({'name':'低矮藏书架','center':[cx,2,cz]})

    def reading(self):
        cx,cz=5.6,8.4
        for x in (-1.02,1.02):
            for z in (-.48,.48):self.box((cx+x,2.53,cz+z),(.14,1.06,.14),10,'阅览桌')
        self.box((cx,3.13,cz),(2.6,.20,1.45),11,'阅览桌')
        self.box((cx-.15,3.28,cz-.12),(.8,.09,.56),16,'桌上书籍',-12)
        self.box((cx-.15,3.34,cz-.13),(.73,.025,.50),23,'桌上书籍',-12)
        self.lantern((cx+.79,3.65,cz+.10),.34,.16)
        self.box((cx,2.65,cz-1.5),(.82,.15,.78),13,'阅览椅')
        for dx in (-.30,.30):
            for dz in (-.28,.28):self.box((cx+dx,2.28,cz-1.5+dz),(.10,.56,.10),10,'阅览椅')
        self.leaf((cx,3.3,cz-1.81),1.45,.84,11,'阅览椅')
        self.objects.append({'name':'小阅览桌椅','center':[cx,2,cz]})

    def planter(self,cx,cz):
        self.box((cx,2.28,cz),(.95,.56,.95),11,'花架')
        self.box((cx,2.58,cz),(.78,.06,.78),20,'花架')
        for i,a in enumerate(np.linspace(0,math.tau,5,endpoint=False)):
            x,z=cx+math.cos(a)*.40,cz+math.sin(a)*.40
            self.stem([(cx,2.60,cz),(x,3.1+i*.07,z),(x,3.6+i*.07,z)],.09,18,'花架')
            self.leaf((x,3.15,z),.72,.39,18,'花架',a*180/math.pi)
            self.box((x,3.68+i*.07,z),(.17,.20,.17),23,'花朵')
        self.objects.append({'name':'白花绿叶花架','center':[cx,2,cz]})

    def arrange(self,volume):
        self.bench(-9.1,3.7,90)
        self.bench(9.1,3.7,-90)
        self.bookcase()
        self.reading()
        self.planter(-10.2,.1)
        self.planter(10.2,.1)
        # 灯具沿内壁布置，中央主灯位于通行高度以上。
        for a in np.linspace(0,math.tau,8,endpoint=False)+.16:
            x,z=math.cos(a)*11.1,math.sin(a)*11.1
            self.stem([(x*1.14,6,z*1.14),(x,6.4,z),(x,5.7,z)],.12,19,'壁灯支架')
            self.lantern((x,5.1,z),.63,.54)
        self.stem([(0,22,13.2),(0,19,8),(0,17,2),(0,13,2)],.18,19,'主灯枝架')
        for a in np.linspace(0,math.tau,5,endpoint=False):
            x,z=math.cos(a)*1.65,2+math.sin(a)*1.65
            self.stem([(0,13,2),(x*.6,12.6,2+(z-2)*.6),(x,12,z)],.10,12,'主灯花枝')
            self.lantern((x,11.55,z),.52,.46)
        self.lantern((0,11.8,2),.88,.85)
        for a in (math.pi*.18,math.pi*.82,math.pi*1.5):
            x,z=math.cos(a)*9.5,math.sin(a)*9.5
            self.stem([(x,97,z),(x+.3,60,z),(x-.2,32,z),(x,15,z)],.13,19,'高处吊藤')
            for y in (22,39,56,73,88):self.lantern((x,y,z),.58,.5)
        # 低对比木地板与四瓣浅色叶纹嵌片，中央不加祭坛或家具。
        for x in range(-12,13):
            for z in range(-12,13):
                if x*x+z*z>=13**2:continue
                material=10 if (x+z)%5 else 22
                leaf=((x/4.8)**2+(z/1.55)**2<1 or (x/1.55)**2+(z/4.8)**2<1)
                if leaf:material=21
                if abs(x)+abs(z)<=1:material=24
                volume.data[x-volume.origin[0],1-volume.origin[1],z-volume.origin[2]]=material
        self.lights.append((np.array([0,1.8,0]),.48,7))
        return self

    def validate(self):
        for part in self.parts:
            p=part['points'];low=p.min(axis=0);high=p.max(axis=0)
            nearest=np.maximum(np.maximum(low[[0,2]],0),-high[[0,2]])
            if low[1]<10 and high[1]>2:assert nearest@nearest>=5**2,part['kind']
            if part['kind'] in {'座椅','书架','书籍','阅览桌','阅览椅','花架'}:
                assert low[1]>=2-1e-6,part['kind']
        return {'furniture_objects':self.objects,'light_sources':len(self.lights),'center_clear_diameter':10,'center_walk_height':8}


def lighting(points,lights):
    """仅供设计图的柔和点光照明，不等同于 Minecraft 实际方块光照。"""
    result=np.zeros(len(points),float)
    for position,power,radius in lights:
        distance=((points-position)**2).sum(axis=1)
        result+=power/(1+distance/(radius*radius))
    return result
