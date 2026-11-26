# -*- coding: utf-8 -*-
"""铸弹台的方块模型、方块状态、掉落、合成配方，七条压制配方（docs/FIREARMS.md 第 6 节），
以及术式弹的七档蚀刻配方、三种弹药图纸的配方和两个弹种标签（第 9 节）。

用法：uv run --no-project python tools/ammo_bench.py

模型只用原版贴图（深色橡木、去皮深色橡木、平滑玄武岩当铸铁、铁块、金块当黄铜（只用在小零件上）、铜块、木桶、紫水晶），
站在正面看：前沿是抽屉，左前一盘立着的子弹，右后一台螺旋压床，左后一座嵌紫水晶的铸铁底座（以后刻术式用），
台下搁板上是火药桶和几块锭。
"""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'src/main/resources/assets/logos_dimension'
DATA = ROOT / 'src/main/resources/data/logos_dimension'
NAME = 'ammo_bench'

TEX = {
    'planks': 'minecraft:block/dark_oak_planks',
    'log': 'minecraft:block/stripped_dark_oak_log',
    'cast': 'minecraft:block/smooth_basalt',
    'steel': 'minecraft:block/iron_block',
    'brass': 'minecraft:block/gold_block',
    'copper': 'minecraft:block/copper_block',
    'keg': 'minecraft:block/barrel_side',
    'keg_top': 'minecraft:block/barrel_top',
    'crystal': 'minecraft:block/amethyst_block',
    'particle': 'minecraft:block/dark_oak_planks',
}


def cube(frm, to, tex, *, top=None, cull_down=False, rotation=None, light=0):
    faces = {}
    for f in ('north', 'south', 'east', 'west', 'up', 'down'):
        t = top if top and f in ('up', 'down') else tex
        face = {'texture': f'#{t}'}
        if f == 'down' and cull_down:
            face['cullface'] = 'down'
        faces[f] = face
    e = {'from': list(frm), 'to': list(to), 'faces': faces}
    if rotation:
        e['rotation'] = rotation
    if light:
        e['light_emission'] = light
    return e


def elements():
    out = []
    # 台腿、搁板、背板、台面、抽屉
    for x in (0, 14):
        for z in (0, 14):
            out.append(cube((x, 0, z), (x + 2, 9, z + 2), 'log', cull_down=True))
    out.append(cube((1, 2, 1), (15, 3, 15), 'planks'))
    out.append(cube((2, 3, 14.5), (14, 9, 15.5), 'planks'))
    out.append(cube((2, 6.5, .5), (14, 9, 1.5), 'planks'))
    out.append(cube((7.25, 7.25, 0), (8.75, 8.25, .5), 'brass'))
    out.append(cube((0, 9, 0), (16, 11, 16), 'planks'))
    # 搁板上：火药桶、一摞铜锭和铁锭
    out.append(cube((9, 3, 4), (14, 8, 9), 'keg', top='keg_top'))
    out.append(cube((2.5, 3, 5), (7, 4, 8.5), 'copper'))
    out.append(cube((3, 4, 5.5), (6.5, 5, 8), 'steel'))
    # 左后：螺旋压床（铸铁底座、两根立柱、横梁，中间一根丝杆，顶上一条带黄铜球的扳杆，下面黄铜压头）
    out.append(cube((1.5, 11, 8.5), (9.5, 12, 14.5), 'cast'))
    out.append(cube((2, 12, 10.5), (3.5, 14, 12), 'cast'))
    out.append(cube((7.5, 12, 10.5), (9, 14, 12), 'cast'))
    out.append(cube((1.5, 14, 10), (9.5, 15.25, 12.5), 'cast'))
    out.append(cube((5, 12.75, 10.75), (6, 15.75, 11.75), 'steel'))
    out.append(cube((1, 15.25, 11), (10, 15.75, 11.5), 'steel'))
    out.append(cube((.25, 15, 10.75), (1.25, 16, 11.75), 'brass'))
    out.append(cube((9.75, 15, 10.75), (10.75, 16, 11.75), 'brass'))
    out.append(cube((4.25, 12, 10), (6.75, 12.75, 12.5), 'brass'))
    # 前右：铸铁托盘里立着六发子弹（铜壳、钢弹头）
    out.append(cube((9, 11, 1.5), (14.5, 11.5, 6), 'cast'))
    for x in (9.75, 11.5, 13.25):
        for z in (2.25, 4):
            out.append(cube((x, 11.5, z), (x + 1, 13, z + 1), 'copper'))
            out.append(cube((x + .25, 13, z + .25), (x + .75, 13.5, z + .75), 'steel'))
    # 右后：铸铁底座嵌一块紫水晶（以后在这里刻术式），一圈黄铜箍，微微发光
    out.append(cube((11, 11, 11), (15, 11.75, 15), 'cast'))
    out.append(cube((11.75, 11.75, 11.75), (14.25, 12.1, 14.25), 'brass'))
    out.append(cube((12.25, 11.75, 12.25), (13.75, 15, 13.75), 'crystal',
                    rotation={'angle': 45, 'axis': 'y', 'origin': [13, 13, 13]}, light=6))
    out.append(cube((11.5, 11.75, 13.75), (12.25, 13.25, 14.5), 'crystal',
                    rotation={'angle': -22.5, 'axis': 'z', 'origin': [11.9, 11.75, 14.1]}, light=4))
    return out


RECIPES = {
    # 弹种看弹壳：纸壳霰弹、铜壳步枪弹；金属看弹头。一批八发。
    'copper_buckshot_shell': ('minecraft:paper', 'minecraft:copper_ingot'),
    'buckshot_shell': ('minecraft:paper', 'minecraft:iron_ingot'),
    'netherite_buckshot_shell': ('minecraft:paper', 'minecraft:netherite_scrap'),
    'copper_rifle_round': ('minecraft:copper_ingot', 'minecraft:copper_ingot'),
    'iron_rifle_round': ('minecraft:copper_ingot', 'minecraft:iron_ingot'),
    'netherite_rifle_round': ('minecraft:copper_ingot', 'minecraft:netherite_scrap'),
    'blank_round': ('minecraft:copper_ingot', 'minecraft:amethyst_shard'),
}


# 术式弹的蚀刻（docs/FIREARMS.md 第 9 节）：照 Logos 载页的七档，催化是那一档载页的标志材料，导体是金锭（第三档起换活金锭）。
# capacity 是每发普通弹能注入的魔力（约为同档载页的五分之一），空弹头加倍。
ETCHING = [
    (1, 10, None, 'minecraft:gold_ingot'),
    (2, 30, 'minecraft:amethyst_shard', 'minecraft:gold_ingot'),
    (3, 80, 'logos:echo_tincture', 'logos:living_gold_ingot'),
    (4, 160, 'logos:raven_head', 'logos:living_gold_ingot'),
    (5, 320, 'logos:chalk', 'logos:living_gold_ingot'),
    (6, 640, 'logos:firmament_stone', 'logos:living_gold_ingot'),
    (7, 1280, 'logos:philosophers_stone', 'logos:living_gold_ingot'),
]
TIER_NAMES = ['basic', 'crystal', 'echo', 'transmuted', 'chalk', 'firmament', 'sage']

# 弹药图纸：纸、金粒加一发那种弹（弹种看这发弹），做出来就写好了「启式 → 这种弹」。
BLUEPRINTS = {
    'buckshot': ('#logos_dimension:buckshot_shells', 'buckshot-round', '霰弹术式'),
    'rifle': ('#logos_dimension:rifle_rounds', 'rifle-round', '步枪弹术式'),
    'blank': ('logos_dimension:blank_round', 'blank-round', '空弹头术式'),
}


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')


def main():
    write_json(ASSETS / f'models/block/{NAME}.json', {
        'parent': 'minecraft:block/block', 'textures': TEX, 'elements': elements()})
    write_json(ASSETS / f'blockstates/{NAME}.json', {'variants': {
        f'facing={d}': {'model': f'logos_dimension:block/{NAME}', **({'y': y} if y else {})}
        for d, y in (('north', 0), ('east', 90), ('south', 180), ('west', 270))}})
    write_json(ASSETS / f'items/{NAME}.json', {'model': {'type': 'minecraft:model', 'model': f'logos_dimension:block/{NAME}'}})
    write_json(DATA / f'loot_table/blocks/{NAME}.json', {
        'type': 'minecraft:block',
        'pools': [{'rolls': 1, 'entries': [{'type': 'minecraft:item', 'name': f'logos_dimension:{NAME}'}],
                   'conditions': [{'condition': 'minecraft:survives_explosion'}]}]})
    # 铸弹台本身：锻造台加一台活塞（压床）和两块铜锭。
    write_json(DATA / f'recipe/{NAME}.json', {
        'type': 'minecraft:crafting_shaped', 'category': 'misc',
        'pattern': [' P ', 'CSC'],
        'key': {'P': 'minecraft:piston', 'C': 'minecraft:copper_ingot', 'S': 'minecraft:smithing_table'},
        'result': {'id': f'logos_dimension:{NAME}', 'count': 1}})
    for result, (casing, head) in RECIPES.items():
        write_json(DATA / f'recipe/ammo_pressing/{result}.json', {
            'type': 'logos_dimension:ammo_pressing', 'casing': casing, 'powder': 'minecraft:gunpowder', 'head': head,
            'result': {'id': f'logos_dimension:{result}', 'count': 8}, 'time': 40})
    for tier, capacity, catalyst, conductor in ETCHING:
        recipe = {'type': 'logos_dimension:round_etching', 'tier': tier, 'capacity': capacity}
        if catalyst:
            recipe['catalyst'] = catalyst
        recipe['conductor'] = conductor
        write_json(DATA / f'recipe/round_etching/{TIER_NAMES[tier - 1]}.json', recipe)
    for shape, (round_, word, name) in BLUEPRINTS.items():
        write_json(DATA / f'recipe/ammo_blueprint_{shape}.json', {
            'type': 'minecraft:crafting_shapeless', 'category': 'misc',
            'ingredients': ['minecraft:paper', 'minecraft:gold_nugget', round_],
            'result': {'id': 'logos_dimension:ammo_blueprint', 'count': 1,
                       'components': {'logos:spell': f'(spell :name "{name}" :mana 10 ({word}))'}}})
    write_json(DATA / 'tags/item/buckshot_shells.json', {'values': [
        'logos_dimension:copper_buckshot_shell', 'logos_dimension:buckshot_shell', 'logos_dimension:netherite_buckshot_shell']})
    write_json(DATA / 'tags/item/rifle_rounds.json', {'values': [
        'logos_dimension:copper_rifle_round', 'logos_dimension:iron_rifle_round', 'logos_dimension:netherite_rifle_round']})
    print('ok', len(elements()), 'elements')


if __name__ == '__main__':
    main()
