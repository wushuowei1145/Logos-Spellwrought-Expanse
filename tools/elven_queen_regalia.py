"""按已确认的华服概念改造森林精灵；所有游戏贴图由代码绘制，配件独立分骨。"""
import copy
import json
import math
from pathlib import Path
from PIL import Image, ImageDraw
from elven_blight_art import ART, DATA, load, write, guard_animations


def recolor(source, kind):
    out = source.copy()
    for y in range(out.height):
        for x in range(out.width):
            r, g, b, a = source.getpixel((x, y))
            light = .22 * r + .65 * g + .13 * b
            if kind == 'hair':
                t = max(0, min(1, (light - 25) / 210))
                color = tuple(int(lo + t * (hi - lo)) for lo, hi in zip((15, 23, 24), (116, 132, 99)))
            elif r > 175 and g > 165 and b > 130 and max(r,g,b) - min(r,g,b) < 95:
                color = (min(255, int(light * .96 + 12)), min(255, int(light * .91 + 9)), min(255, int(light * .82 + 10)))
            elif g > r * 1.04:
                color = (int(16 + light * .20), int(11 + light * .13), int(25 + light * .26))
            elif r > g * 1.13 and r > b * 1.1:
                color = (int(80 + light * .56), int(53 + light * .43), int(26 + light * .22))
            else:
                color = (int(17 + light * .22), int(13 + light * .17), int(26 + light * .27))
            out.putpixel((x,y), color + (a,))
    return out


def main():
    target = ART / 'geckolib/models/entity/corrupted_elf_queen.geo.json'
    previous = load(target)['minecraft:geometry'][0]
    previous_head = next(b['pivot'][1] for b in previous['bones'] if b['name'] == 'Head')
    doc = load(ART / 'geckolib/models/entity/forest_elf.geo.json')
    model = doc['minecraft:geometry'][0]
    parts = load(DATA / 'logos_dimension/npc_races/forest_elf.json')
    selected = {'hair_straight_long', 'ear_long', 'eyes_droopy', 'mouth_smile', 'outfit_floral_gown'}
    roots, drop = {}, set()
    for slot, group in parts['slots'].items():
        for part in group['parts']:
            if part['id'] not in selected:
                drop.update(part['groups'])
            else:
                for name in part['groups']:
                    roots[name] = slot
    for part in parts['expressions']:
        drop.update(part['groups'])
    for bone in model['bones']:
        if bone.get('parent') in drop:
            drop.add(bone['name'])
    model['bones'] = [b for b in model['bones'] if b['name'] not in drop]
    by_name = {b['name']: b for b in model['bones']}
    categories = {}
    def category(name):
        if name not in categories:
            b = by_name[name]
            categories[name] = roots.get(name, category(b['parent']) if b.get('parent') in by_name else '')
        return categories[name]
    original = Image.open(ART / 'textures/entity/forest_elf.png').convert('RGBA')
    atlas = Image.new('RGBA', (384,512))
    atlas.paste(original, (0,0))
    for y in range(original.height):
        for x in range(original.width):
            r,g,b,a = original.getpixel((x,y))
            if g > r*1.06 and g > b*1.02:
                light = .22*r + .65*g + .13*b
                atlas.putpixel((x,y),(int(22+light*.16),int(12+light*.09),int(30+light*.19),a))
    atlas.paste(recolor(original, 'hair'), (128,0))
    atlas.paste(recolor(original, 'gown'), (256,0))
    for bone in model['bones']:
        offset = 128 if category(bone['name']) == 'hair' or 'Eyebrow' in bone['name'] else 256 if category(bone['name']) == 'outfit' else 0
        for cube in bone.get('cubes', []):
            for face in cube['uv'].values():
                face['uv'][0] += offset
    colors = [(29,19,42), (49,31,63), (80,54,89), (130,91,53), (192,148,82), (229,199,133), (239,222,191), (118,19,44), (211,59,76), (249,156,148), (24,42,34), (87,105,72)]
    draw = ImageDraw.Draw(atlas)
    for i, color in enumerate(colors):
        draw.rectangle((i*16,368,i*16+15,383), fill=color+(255,))
        for y in range(370,383,5):
            draw.line((i*16+1,y,i*16+14,y),fill=tuple(max(0,c-6) for c in color)+(255,))
    # 眼睛仍使用单独高精度虹膜，衣料与头发保留原版纹素密度。
    draw.rectangle((0,400,23,427),fill=(57,12,32,255))
    for y in range(402,426):
        draw.line((2,y,21,y),fill=(min(235,130+(y-402)*4),25+(y-402),48+(y-402)*2,255))
    draw.rectangle((9,401,13,421),fill=(31,9,23,255))
    draw.rectangle((3,402,7,407),fill=(255,235,219,255))
    draw.rectangle((17,422,20,424),fill=(248,171,155,255))
    for bone in model['bones']:
        if bone['name'].endswith('_pupil'):
            for cube in bone.get('cubes',[]):
                if 'north' in cube['uv']:
                    cube['uv']['north'] = {'uv':[0,400], 'uv_size':[24,28]}
        if bone['name'] == 'mouth_smile':
            for cube in bone.get('cubes',[]):
                for face in cube['uv'].values():
                    face.update(uv=[113,369],uv_size=[1,1])

    def add_group(name, parent, pivot):
        bone = {'name':name,'parent':parent,'pivot':pivot}
        model['bones'].append(bone)
        return bone
    def box(name, parent, pivot, origin, size, color, rotation=None):
        bone = add_group(name,parent,pivot)
        if rotation:
            bone['rotation'] = rotation
        bone['cubes'] = [{'origin':origin,'size':size,'uv':{f:{'uv':[color*16+1,369],'uv_size':[12,12]} for f in ('north','south','east','west','up','down')}}]
        return bone

    # 枝冠、额坠、耳坠均独立；金枝用斜置长方体，不堆砌细小台阶。
    add_group('queen_branch_diadem','Head',[0,48,0])
    for i in range(10):
        a = i * math.tau / 10
        x, z = math.cos(a)*4.7, math.sin(a)*4.5
        box('queen_crown_band_'+str(i),'queen_branch_diadem',[x,49.3,z],[x-1.6,49,z-.23],[3.2,.55,.46],4,[0,-math.degrees(a)-90,0])
    for side in (-1,1):
        for j in range(3):
            x, y, z = side*(1.3+j*1.25), 49.5+j*.35, -4.7+j*.4
            box(f'queen_crown_branch_{side}_{j}','queen_branch_diadem',[x,y,z],[x-.25,y,z-.25],[.5,3.5-j*.5,.5],4,[0,0,side*(22+j*9)])
            box(f'queen_crown_leaf_{side}_{j}','queen_branch_diadem',[x,y+2,z],[x-.65,y+1.7,z-.15],[1.3,1.6,.3],5,[0,0,side*42])
        box('queen_ear_chain_'+str(side),'Head',[side*5.4,41,-.3],[side*5.4-.15,38,-.4],[.3,3,.3],4)
        box('queen_ear_garnet_'+str(side),'Head',[side*5.4,38,-.4],[side*5.4-.45,37.6,-.6],[.9,1.2,.4],7,[0,0,45])
    box('queen_forehead_gold','Head',[0,49,-4.8],[-1.05,48.4,-5],[2.1,2.1,.35],5,[0,0,45])
    box('ysmGlowQueenForehead','Head',[0,49,-5],[-.62,48.8,-5.25],[1.24,1.24,.25],8,[0,0,45])
    # 项链在胸衣上形成植物纹样，不增加肉瘤、骨刺或根须。
    add_group('queen_regal_collar','UpperBody',[0,35,-2.6])
    for side in (-1,1):
        box('queen_collar_chain_'+str(side),'queen_regal_collar',[side*1.5,35.6,-2.6],[side*1.5-.14,33.8,-2.8],[.28,3.3,.35],5,[0,0,side*40])
    box('queen_collar_garnet','queen_regal_collar',[0,33,-2.9],[-.55,32.5,-3.05],[1.1,1.1,.3],8,[0,0,45])

    for side, label in ((1,'Left'),(-1,'Right')):
        arm = by_name[label+'ForeArm']['pivot']
        x, y, z = arm
        name = 'queen_bell_sleeve_'+label.lower()
        add_group(name,label+'ForeArm',arm)
        for j in range(3):
            width = 3.6+j*1.5
            box(name+'_'+str(j),name,arm,[x-width/2,y-2.6-j*2.0,z-1.9],[width,2.9,3.8],0 if j!=2 else 6,[0,0,side*j*4])
        box(name+'_gold_cuff',name,arm,[x-3.1,y-6.9,z-2],[6.2,.4,4],4)

    # 两片独立披裙和短拖尾，边缘用少量折片与金叶区分层次。
    for side in (-1,1):
        name = 'queen_embroidered_skirt_'+str(side)
        add_group(name,'Skirt',[side*3.4,25,0])
        for j in range(3):
            y = 1+j*8
            width = 5.6-j*.8
            z = -4.8+j*.45
            box(name+'_silk_'+str(j),name,[side*3.4,25,0],[side*3.4-width/2,y,z],[width,8.8,.7],0,[0,side*9,side*2])
            box(name+'_gold_'+str(j),name,[side*3.4,25,0],[side*3.4-width/2+.1,y,z-.06],[.25,8.8,.2],4,[0,side*9,side*2])
            for k in range(2):
                origin = [side*3.4-.6+k*.75,y+1+k*3,z-.15]
                box(name+f'_leaf_{j}_{k}',name+'_silk_'+str(j),[origin[0]+.4,origin[1]+.75,z],origin,[.8,1.5,.25],5,[0,0,side*(30+k*10)])
    add_group('queen_court_train','Skirt',[0,23,1])
    for j in range(3):
        box('queen_train_'+str(j),'queen_court_train',[0,23,1],[-5-j*.4,.3+j*.3,3+j*1.8],[10+j*.8,.5,3.2],0)

    from elven_queen_ornaments import enrich
    atlas = enrich(model, atlas, by_name, add_group, box)
    # 冠尖约 128 制作单位，继续沿用 NPC 的 0.5 渲染，实际约四格。
    cubes = [c for b in model['bones'] for c in b.get('cubes',[])]
    # 四格指本体；高举的权杖、以后的替换武器不能反过来压缩身高。
    top = max(c['origin'][1]+c['size'][1] for b in model['bones']
              if not b['name'].startswith(('queen_scepter', 'queen_weapon_', 'ysmGlowQueenScepter'))
              for c in b.get('cubes', []))
    scale = 128/top
    for bone in model['bones']:
        bone['pivot'] = [round(v*scale,5) for v in bone.get('pivot',[0,0,0])]
        for cube in bone.get('cubes',[]):
            for key in ('origin','size','pivot'):
                if key in cube:
                    cube[key] = [round(v*scale,5) for v in cube[key]]
    model['description'].update(identifier='geometry.corrupted_elf_queen',texture_width=768,texture_height=1024,
        visible_bounds_width=6,visible_bounds_height=7,visible_bounds_offset=[0,2.6,0])
    write(ART / 'geckolib/models/entity/corrupted_elf_queen.geo.json',doc)
    atlas.save(ART / 'textures/entity/corrupted_elf_queen.png')
    guard_animations()
    # 用前后骨架的比例校准已有位移，重复导出时比例为一，不会一遍遍放大动作。
    current_head = next(b['pivot'][1] for b in model['bones'] if b['name'] == 'Head')
    motion_scale = current_head / previous_head
    path = ART / 'geckolib/animations/entity/corrupted_elf_queen.animation.json'
    animations = load(path)
    def scale_position(value):
        if isinstance(value, list):
            return [round(v * motion_scale, 5) if isinstance(v, (float, int)) else v for v in value]
        if isinstance(value, dict):
            return {k: scale_position(v) if k not in ('lerp_mode',) else v for k, v in value.items()}
        return value
    for name, clip in animations['animations'].items():
        if name in ('queen_guard', 'queen_rise', 'queen_entrance'):
            continue
        for channels in clip.get('bones', {}).values():
            if 'position' in channels:
                channels['position'] = scale_position(channels['position'])
    from elven_queen_ornaments import support_scepter_poses
    support_scepter_poses(model, animations)
    write(path, animations)
    print('腐化女王：%d 骨骼，%d 方块；冠尖约 128 单位，游戏约 4 格；华服、枝冠、首饰、权杖独立分组。' % (len(model['bones']),len(cubes)))


if __name__ == '__main__':
    main()
