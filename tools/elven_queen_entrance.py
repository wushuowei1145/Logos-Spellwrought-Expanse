"""保留既有动作，新增五秒入场；镜头前景全部由代码绘制。"""
from pathlib import Path
import json,copy,math,random
from PIL import Image,ImageDraw
ROOT=Path(__file__).resolve().parents[1]
ASSET=ROOT/'src/main/resources/assets/logos_dimension'
p=ASSET/'geckolib/animations/entity/corrupted_elf_queen.animation.json'
doc=json.loads(p.read_text(encoding='utf-8'))
guard=doc['animations']['queen_guard']['bones']
rise=doc['animations']['queen_rise']['bones']
bones={}
for name,channels in rise.items():
    result={}
    for channel,keys in channels.items():
        if isinstance(keys,dict):
            frames={str(float(t)+1):copy.deepcopy(v) for t,v in keys.items()}
            first=copy.deepcopy(next(iter(keys.values())))
            last=copy.deepcopy(list(keys.values())[-1])
            frames['0.0']=first
            frames['5.0']=last
            result[channel]=dict(sorted(frames.items(),key=lambda kv:float(kv[0])))
        else:
            result[channel]=copy.deepcopy(keys)
    bones[name]=result
# 冠与长发稍有延迟，保留原有起身动作的关节和服装展开。
clip={'loop':False,'animation_length':5,'bones':bones}
doc['animations']['queen_entrance']=clip
p.write_text(json.dumps(doc,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
tex=ASSET/'textures/gui'
tex.mkdir(parents=True,exist_ok=True)
for layer in ('near','far'):
    rng=random.Random(397 if layer=='near' else 719)
    image=Image.new('RGBA',(192,256))
    d=ImageDraw.Draw(image)
    alpha=230 if layer=='near' else 105
    # 有厚度的血管沿近裁剪面左右边缘攀爬，中央保持可见。
    for branch in range(8):
        y0=rng.randrange(-50,256)
        path=[]
        x=12+rng.randrange(25)
        for j in range(15):
            y=y0+j*12
            xx=x+math.sin(j*.55+branch)*10+j*2.5
            path.append((xx,y))
        d.line(path,fill=(22,5,12,alpha),width=13 if layer=='near' else 7)
        d.line([(x+3,y) for x,y in path],fill=(90,17,30,int(alpha*.78)),width=3)
        for j in range(2,13,3):
            x,y=path[j]
            side=1 if j%2 else -1
            tip=(x+side*rng.randrange(23,58),y-rng.randrange(9,28))
            d.polygon([(x-2,y+8),(tip[0]-5,tip[1]+10),tip,(x+4,y-7)],fill=(40,7,18,int(alpha*.9)))
            d.line([(x,y),tip],fill=(133,35,43,int(alpha*.66)),width=1)
            if layer=='near':
                d.ellipse((x-3,y-4,x+3,y+4),fill=(127,21,35,150))
    if layer=='far':
        for i in range(6):
            y=rng.randrange(256)
            d.polygon([(0,y-25),(90,y-7),(110,y+8),(15,y+44)],fill=(134,20,35,15))
    image.save(tex/f'queen_entry_{layer}.png')
print('新增入场动画与两张透明前景，不覆盖原有起身和攻击。')
