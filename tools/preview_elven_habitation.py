"""从相同树屋构件和楼梯模板绘制软件预览，区别于实际客户端截图。"""
from pathlib import Path
import math
import numpy as np
from PIL import Image, ImageDraw, ImageFont
from elven_habitation import tree_house, world_floors
from elven_tree_study import Volume, render, COLORS

ROOT=Path(__file__).resolve().parent.parent
OUT=ROOT/'docs/concepts/elven-habitation'
OUT.mkdir(parents=True,exist_ok=True)
COLORS.update({30:(103,116,96),31:(210,195,151),32:(92,68,43),33:(126,103,73),34:(234,234,211),35:(210,187,123)})


def volume(cells):
    v=Volume(24,100)
    for (x,y,z),s in cells.items():
        name=s['Name']
        if name=='minecraft:air':continue
        color=17 if 'lamp' in name or 'bough_light' in name or 'inlay' in name else 35 if 'rope' in name else 30 if 'leaf' in name or 'canopy' in name else 32 if 'dark_oak' in name or 'spruce_stairs' in name else 1 if 'log' in name or 'heartwood' in name else 34 if 'glass' in name else 31
        pos=np.array([x,y,z])-v.origin
        if np.all(pos>=0) and np.all(pos<np.array(v.data.shape)):v.data[tuple(pos)]=color
    return v


def main():
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',31)
    board=Image.new('RGB',(2100,1200),'#e9ece0');d=ImageDraw.Draw(board)
    for i,conifer in enumerate([False,True]):
        cells={};tree_house(cells,conifer)
        for y in range(49 if conifer else 44):
            for x in range(-2,3):
                for z in range(-2,3):cells[x,y,z]={'Name':'logos_dimension:elven_heartwood'}
        cells={(-x,y,-z):s for (x,y,z),s in cells.items()}
        image=render(volume(cells),(950,950),yaw=25,elevation=15)
        board.paste(image,(50+i*1050,130),image)
        d.text((85+i*1050,50),'冷杉木屋 / 深木尖顶' if conifer else '阔叶木屋 / 浅木宽檐',font=font,fill='#354b3e')
    d.text((85,1120),'代码绘制的结构预览 · 绳梯直达根颈 · 实际观感以客户端截图为准',font=font,fill='#57685a')
    board.save(OUT/'tree-houses-code-preview.png')
    cells={};world_floors(cells)
    cells={p:s for p,s in cells.items() if p[2]>=-1 or 'slab' in s['Name']}
    im=render(volume(cells),(1400,1500),yaw=25,elevation=17)
    canvas=Image.new('RGB',(1500,1650),'#e9ece0');canvas.paste(im,(50,100),im)
    ImageDraw.Draw(canvas).text((60,35),'树心剖面 / 五层回廊与连续旋转楼梯',font=font,fill='#354b3e')
    canvas.save(OUT/'spiral-stairs-code-preview.png')
    print(OUT)


if __name__=='__main__':main()
