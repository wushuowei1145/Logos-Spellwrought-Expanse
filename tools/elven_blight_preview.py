"""导出 Blockbench 源文件和贴图模型预览；预览是离线渲染，不是游戏截图。"""
import base64
import json
import uuid
from pathlib import Path
from combat_render import Geo, Camera, render, sheet

ROOT = Path(__file__).resolve().parents[1]
ART = ROOT / 'src/main/resources/assets/logos_dimension'
OUT = ROOT.parents[1] / 'outputs/elven-boss/regalia-v3'
OUT.mkdir(parents=True, exist_ok=True)
MODEL = ART / 'geckolib/models/entity/corrupted_elf_queen.geo.json'
TEXTURE = ART / 'textures/entity/corrupted_elf_queen.png'


def export_source(model_path=MODEL, texture_path=TEXTURE, output_path=None, with_animations=True, project_name='腐化女王'):
    geo = json.loads(model_path.read_text(encoding='utf-8'))['minecraft:geometry'][0]
    elements, groups, outliner = [], {}, []
    def uid(name):
        return str(uuid.uuid5(uuid.NAMESPACE_URL, 'logos:queen/' + name))
    for b in geo['bones']:
        p = b['pivot']
        r = b.get('rotation', [0, 0, 0])
        group = {'name': b['name'], 'uuid': uid(b['name']), 'origin': [-p[0], p[1], p[2]], 'rotation': [-r[0], -r[1], r[2]], 'children': []}
        groups[b['name']] = group
        for i, c in enumerate(b.get('cubes', [])):
            o, s = c['origin'], c['size']
            faces = {}
            for face, uv in c['uv'].items():
                u, v = uv['uv']
                w, h = uv['uv_size']
                bounds = [u, v, u + w, v + h]
                if face in ('up', 'down'):
                    bounds = [u + w, v + h, u, v]
                faces[face] = {'uv': bounds, 'texture': 0, 'rotation': uv.get('uv_rotation', 0)}
            cp = c.get('pivot', p)
            cr = c.get('rotation', [0, 0, 0])
            element = {'name': b['name'] + '_' + str(i), 'type': 'cube', 'uuid': uid(b['name'] + '/' + str(i)), 'from': [-o[0] - s[0], o[1], o[2]], 'to': [-o[0], o[1] + s[1], o[2] + s[2]], 'origin': [-cp[0], cp[1], cp[2]], 'rotation': [-cr[0], -cr[1], cr[2]], 'faces': faces, 'box_uv': False, 'rescale': False, 'autouv': 0, 'color': 0}
            elements.append(element)
            group['children'].append(element['uuid'])
    for b in geo['bones']:
        if b.get('parent') in groups:
            groups[b['parent']]['children'].append(groups[b['name']])
        else:
            outliner.append(groups[b['name']])
    project = {'meta': {'format_version': '4.10', 'model_format': 'bedrock', 'box_uv': False}, 'name': '腐化女王', 'model_identifier': 'corrupted_elf_queen', 'visible_box': [4, 5, 2.2], 'resolution': {'width': 128, 'height': 416}, 'elements': elements, 'outliner': outliner, 'textures': [{'uuid': uid('texture'), 'name': texture_path.name, 'id': '0', 'width': 128, 'height': 416, 'uv_width': 128, 'uv_height': 416, 'source': 'data:image/png;base64,' + base64.b64encode(texture_path.read_bytes()).decode()}]}
    width,height = geo['description']['texture_width'],geo['description']['texture_height']
    project['name'] = project_name
    description = geo['description']
    project['model_identifier'] = description['identifier'].removeprefix('geometry.')
    project['visible_box'] = [description['visible_bounds_width'], description['visible_bounds_height'], description['visible_bounds_offset'][1]]
    project['resolution'] = {'width':width,'height':height}
    for texture in project['textures']:
        texture.update(width=width,height=height,uv_width=width,uv_height=height)
    clips = json.loads((ART / 'geckolib/animations/entity/corrupted_elf_queen.animation.json').read_text(encoding='utf-8'))['animations'] if with_animations else {}
    project['animations'] = []
    for name, clip in clips.items():
        animators = {}
        for bone, channels in clip.get('bones', {}).items():
            if bone not in groups:
                continue
            keys = []
            for channel, values in channels.items():
                if channel not in ('position','rotation','scale'):
                    continue
                frames = values if isinstance(values,dict) else {'0':values if isinstance(values,list) else [values,values,values]}
                for time, data in frames.items():
                    vector = data.get('post',data.get('pre')) if isinstance(data,dict) else data
                    if not isinstance(vector,list):
                        vector = [vector,vector,vector]
                    converted = []
                    for axis,value in enumerate(vector):
                        flip = channel == 'rotation' and axis < 2 or channel == 'position' and axis == 0
                        converted.append(str(-value if isinstance(value,(int,float)) and flip else '-('+str(value)+')' if flip else value))
                    keys.append({'uuid':uid(name+'/'+bone+'/'+channel+'/'+time),'channel':channel,'time':float(time),'interpolation':data.get('lerp_mode','linear') if isinstance(data,dict) else 'linear','data_points':[dict(zip(('x','y','z'),converted))]})
            animators[groups[bone]['uuid']] = {'name':bone,'type':'bone','keyframes':keys}
        project['animations'].append({'uuid':uid('animation/'+name),'name':name,'loop':'loop' if clip.get('loop') is True else 'once','length':clip.get('animation_length',1),'snapping':20,'animators':animators})
    (output_path or OUT / 'corrupted_elf_queen.bbmodel').write_text(json.dumps(project, ensure_ascii=False), encoding='utf-8')


def export_scepter():
    """另导出以握点为原点的权杖，方便以后接到道具或换武器动作里。"""
    import copy
    doc = json.loads(MODEL.read_text(encoding='utf-8'))
    geo = doc['minecraft:geometry'][0]
    selected = {'queen_scepter_socket'}
    for b in geo['bones']:
        if b.get('parent') in selected:
            selected.add(b['name'])
    origin = next(b['pivot'] for b in geo['bones'] if b['name'] == 'queen_scepter_socket')[:]
    geo['bones'] = [copy.deepcopy(b) for b in geo['bones'] if b['name'] in selected]
    for b in geo['bones']:
        b['pivot'] = [round(v - origin[i], 5) for i, v in enumerate(b['pivot'])]
        if b['name'] == 'queen_scepter_socket':
            b.pop('parent', None)
            b.pop('rotation', None)
        for c in b.get('cubes', []):
            for field in ('origin', 'pivot'):
                if field in c:
                    c[field] = [round(v - origin[i], 5) for i, v in enumerate(c[field])]
    geo['description'].update(identifier='geometry.queen_branch_scepter', visible_bounds_width=3,
                              visible_bounds_height=6, visible_bounds_offset=[0, 1, 0])
    path = OUT / 'queen_branch_scepter.geo.json'
    path.write_text(json.dumps(doc, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    export_source(path, TEXTURE, OUT / 'queen_branch_scepter.bbmodel', False, '墨绿树枝权杖')


if __name__ == '__main__':
    export_source()
    export_scepter()
    model = Geo(MODEL, TEXTURE)
    quads = model.quads({})
    panels = [(label, render(quads, Camera(yaw, 4, 4.0, (0, 76, 0), (620, 640)))) for label, yaw in [('正面', 0), ('侧前', 30), ('背面', 180)]]
    sheet([('模型', panels)], '腐化女王 · 四格华服与树枝权杖 · 离线模型预览', OUT / 'queen-model-preview.png')
    print(OUT / 'queen-model-preview.png')
