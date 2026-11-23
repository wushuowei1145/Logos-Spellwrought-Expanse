"""将实际客户端截图排成九宫格；只裁切和标注，不绘制或替换游戏场景。"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
from wayside_ruins import CATALOG, ROOT

SCENES=['bloom','fern','root','frost','wind','tide','reed','ravine','rain']

def preview():
    target=ROOT/'build/wayside-preview';target.mkdir(parents=True,exist_ok=True)
    font_path=Path('C:/Windows/Fonts/msyh.ttc')
    if not font_path.exists():
        font_path=Path('/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc')
    title=ImageFont.truetype(str(font_path),30);label=ImageFont.truetype(str(font_path),22)
    small=ImageFont.truetype(str(font_path),17)
    board=Image.new('RGB',(1620,1230),'#142524');draw=ImageDraw.Draw(board)
    draw.text((24,18),'旅途小遗迹 · 九类模板实机预览',font=title,fill='#e4eadb')
    draw.text((26,62),'独立测试台展示轮廓；真实自然生成效果见单独的海岸勘察图。',font=small,fill='#b5c2b4')
    source=ROOT/'build/ldlib2-uitest/screenshots'
    for i,((kind,name,_,_),scene) in enumerate(zip(CATALOG,SCENES)):
        shot=Image.open(source/f'wayside_{scene}/09_{kind}.png').convert('RGB')
        # 保留主体与脚下接点，去掉不参与对照的大片天空和测试台边缘。
        shot=shot.crop((160,100,1120,690)).resize((516,317),Image.Resampling.LANCZOS)
        x=20+(i%3)*536;y=104+(i//3)*355
        board.paste(shot,(x,y));draw.text((x+2,y+321),f'{i+1:02d}  {name}',font=label,fill='#dce7cf')
    draw.text((24,1210),'代码绘制 16×16 材质 · Iris + Sodium + Photon v1.3b · 每类另有一套残损变体',font=small,fill='#b5c2b4',anchor='ls')
    board.save(target/'overview.jpg',quality=94,subsampling=0)
    print(target/'overview.jpg')

if __name__=='__main__':preview()
