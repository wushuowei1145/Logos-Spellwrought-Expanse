"""用原版密度函数形成连片平原；只改长风气候带，地底洞穴沿用现有密度。"""
from pathlib import Path
import json

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / 'src/main/resources/data/logos_dimension/worldgen'
NAMESPACE = 'logos_dimension:changfeng/'


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')


def add(a, b):
    return {'type': 'minecraft:add', 'argument1': a, 'argument2': b}


def mul(a, b):
    return {'type': 'minecraft:mul', 'argument1': a, 'argument2': b}


def ramp(coordinate, points):
    return {'type': 'minecraft:spline', 'spline': {'coordinate': coordinate, 'points': [
        {'location': x, 'value': y, 'derivative': 0} for x, y in points]}}


def mix(original, target, mask):
    return add(mul(original, add(1, mul(-1, mask))), mul(target, mask))


def main():
    path = DATA / 'noise_settings/journey.json'
    settings = json.loads(path.read_text(encoding='utf-8'))
    router = settings['noise_router']
    functions = DATA / 'density_function/changfeng'
    # 再运行时不重复套混合层；原始地形与深度只保存一次。
    if router['final_density'] != NAMESPACE + 'terrain':
        write(functions / 'original.json', router['final_density'])
        write(functions / 'original_depth.json', add(router['depth'], 0))
    # 注册表文件根节点不接受引用字符串，引用需要包在一个原版函数里。
    saved_depth = json.loads((functions / 'original_depth.json').read_text(encoding='utf-8'))
    if isinstance(saved_depth, str):
        write(functions / 'original_depth.json', add(saved_depth, 0))
    if router['preliminary_surface_level'] != NAMESPACE + 'surface':
        write(functions / 'original_surface.json', router['preliminary_surface_level'])
    mask = ramp('minecraft:overworld/erosion', [(-.05, 0), (.15, 1), (1.2, 1)])
    for coordinate, points in [
        (router['temperature'], [(-.55, 0), (-.35, 1), (.55, 1), (.75, 0)]),
        (router['vegetation'], [(-1.2, 0), (-.95, 1), (.2, 1), (.4, 0)]),
        ('minecraft:overworld/continents', [(-.1, 0), (0, 1), (1.05, 1), (1.2, 0)]),
    ]:
        mask = mul(mask, ramp(coordinate, points))
    write(functions / 'region.json', {'type': 'minecraft:cache_2d', 'argument': mask})
    # 主体高度约 74，保留幅度约两格的低频起伏，不做完全水平的人工台面。
    target = add({'type': 'minecraft:y_clamped_gradient', 'from_y': 60, 'to_y': 88, 'from_value': .7, 'to_value': -.7},
                 mul(.08, {'type': 'minecraft:noise', 'noise': 'minecraft:surface', 'xz_scale': .02, 'y_scale': 0}))
    above_caves = {'type': 'minecraft:y_clamped_gradient', 'from_y': 48, 'to_y': 64, 'from_value': 0, 'to_value': 1}
    write(functions / 'terrain.json', mix(NAMESPACE + 'original', target, mul(NAMESPACE + 'region', above_caves)))
    # 群系深度同时随新地表移动，避免平原表面被原先山体的地下群系占据。
    depth = {'type': 'minecraft:y_clamped_gradient', 'from_y': -64, 'to_y': 320, 'from_value': 2.15625, 'to_value': -3.84375}
    write(functions / 'depth.json', mix(NAMESPACE + 'original_depth', depth, NAMESPACE + 'region'))
    surface = add(74, mul(1.6, {'type': 'minecraft:noise', 'noise': 'minecraft:surface', 'xz_scale': .02, 'y_scale': 0}))
    write(functions / 'surface.json', {'type': 'minecraft:cache_2d', 'argument': mix(NAMESPACE + 'original_surface', surface, NAMESPACE + 'region')})
    router['final_density'] = NAMESPACE + 'terrain'
    router['depth'] = NAMESPACE + 'depth'
    router['preliminary_surface_level'] = NAMESPACE + 'surface'
    write(path, settings)


if __name__ == '__main__':
    main()
