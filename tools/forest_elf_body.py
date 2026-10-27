"""把维护者的共用素体局部同步到游戏模型，保留现有零件、表情和动画。

python tools/forest_elf_body.py

源文件在仓库外 forest-elf-model/forest_elf/body/forest_elf_body.bbmodel。
仅替换共用骨骼里的素体方块、贴图中的素体区域与 body_cubes 统计。
不重排总贴图，不从旧的独立动画导出覆盖游戏动画，不改游戏骨骼层级。
"""
import base64
import copy
import io
import json
from pathlib import Path

import numpy as np
from PIL import Image

ROOT=Path(__file__).resolve().parent.parent
SOURCE=ROOT.parent/'forest-elf-model/forest_elf/body/forest_elf_body.bbmodel'
ASSETS=ROOT/'src/main/resources/assets/logos_dimension'
MODEL=ASSETS/'geckolib/models/entity/forest_elf.geo.json'
TEXTURE=ASSETS/'textures/entity/forest_elf.png'
PARTS=ROOT/'src/main/resources/data/logos_dimension/npc_races/forest_elf.json'
BODY_UV_ORIGIN=(96,200)


def export_cube(element,offset):
    """按 Bedrock 坐标导出；保留每面旋转，尤其是骨盆正面的 90° UV。"""
    a,b=element['from'],element['to'];size=[b[i]-a[i] for i in range(3)]
    def rounded(v):return [round(float(n),5)+0.0 for n in v]
    cube={'origin':rounded([-b[0],a[1],a[2]]),'size':rounded(size)}
    if element.get('inflate'):cube['inflate']=element['inflate']
    rotation=element.get('rotation',[0,0,0])
    if any(rotation):
        origin=element['origin'];cube.update(pivot=rounded([-origin[0],origin[1],origin[2]]),rotation=rounded([-rotation[0],-rotation[1],rotation[2]]))
    cube['uv']={}
    for name,face in element['faces'].items():
        if face.get('texture') is None:continue
        u1,v1,u2,v2=face['uv'];u1+=offset[0];u2+=offset[0];v1+=offset[1];v2+=offset[1]
        if name in ['up','down']:uv={'uv':[u2,v2],'uv_size':[u1-u2,v1-v2]}
        else:uv={'uv':[u1,v1],'uv_size':[u2-u1,v2-v1]}
        turn=int(face.get('rotation',0) or 0)%360
        if turn:
            assert turn in [90,180,270]
            uv['uv_rotation']=turn
        cube['uv'][name]=uv
    return cube


def main():
    source=json.loads(SOURCE.read_text(encoding='utf-8'))
    assert source['meta']['model_format']=='bedrock' and len(source['textures'])==1
    group_names={g['uuid']:g['name'] for g in source['groups']}
    elements={e['uuid']:e for e in source['elements']}
    by_bone={name:[] for name in group_names.values()}
    def visit(node,parent=None):
        if isinstance(node,str):
            element=elements[node]
            if element.get('export',True):
                assert parent is not None
                by_bone[parent].append(export_cube(element,BODY_UV_ORIGIN))
        else:
            name=group_names[node['uuid']]
            for c in node.get('children',[]):visit(c,name)
    for node in source['outliner']:visit(node)
    geo=json.loads(MODEL.read_text(encoding='utf-8'));model=geo['minecraft:geometry'][0]
    atlas=np.asarray(Image.open(TEXTURE).convert('RGBA')).copy()
    image=np.asarray(Image.open(io.BytesIO(base64.b64decode(source['textures'][0]['source'].split(',',1)[1]))).convert('RGBA'))
    x,y=BODY_UV_ORIGIN;h,w=image.shape[:2]
    assert y+h<=atlas.shape[0] and x+w<=atlas.shape[1]
    # 已有零件或表情不能占用即将替换的素体贴图区域。
    for bone in model['bones']:
        if bone['name'] in by_bone:continue
        for cube in bone.get('cubes',[]):
            for face in cube['uv'].values():
                u,v=face['uv'];du,dv=face['uv_size'];u0,u1=sorted([u,u+du]);v0,v1=sorted([v,v+dv])
                if du==dv==0:overlap=x<=u<x+w and y<=v<y+h
                else:overlap=u0<x+w and u1>x and v0<y+h and v1>y
                assert not overlap,('素体贴图区被其他零件使用',bone['name'])
    before=copy.deepcopy(model)
    found=set()
    for bone in model['bones']:
        if bone['name'] in by_bone:
            cubes=by_bone[bone['name']];found.add(bone['name'])
            if cubes:bone['cubes']=cubes
            else:bone.pop('cubes',None)
    assert found==set(by_bone),'游戏模型缺少共用骨架'
    for a,b in zip(before['bones'],model['bones']):
        assert {k:v for k,v in a.items() if k!='cubes'}=={k:v for k,v in b.items() if k!='cubes'}
        if a['name'] not in by_bone:assert a==b
    atlas[y:y+h,x:x+w]=image
    parts=json.loads(PARTS.read_text(encoding='utf-8'))
    parts['body_cubes']=sum(len(c) for c in by_bone.values())
    MODEL.write_text(json.dumps(geo,ensure_ascii=False,indent=1)+'\n',encoding='utf-8')
    Image.fromarray(atlas).save(TEXTURE,optimize=True)
    PARTS.write_text(json.dumps(parts,ensure_ascii=False,indent=1)+'\n',encoding='utf-8')
    print(f'共用素体：{parts["body_cubes"]} 块；贴图局部更新 ({x},{y}) {w}×{h}，其他零件、表情、骨架层级与动画保留。')


if __name__=='__main__':main()
