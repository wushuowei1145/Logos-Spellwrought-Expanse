"""导入古木之拥的完整几何与原图，生成五片叶的常亮纹理及当前版本模型适配。"""
import argparse
import json
from io import BytesIO
from pathlib import Path
from zipfile import ZipFile
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
ASSETS = ROOT / 'src/main/resources/assets/logos_dimension'
LEAVES = ['leaf_upper_out', 'leaf_top', 'leaf_upper_in', 'leaf_lower_out', 'leaf_lower_in']

def write(path, value):
    """统一写入可审查的资源文件。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--pack', type=Path, required=True)
    args = parser.parse_args()
    with ZipFile(args.pack) as z:
        texture = z.read('ancient_embrace_v1/ancient_embrace.png')
        geometry = json.loads(z.read('ancient_embrace_v1/geometry.json'))
        source = z.read('ancient_embrace_v1/AncientEmbraceModel.java').decode('utf-8-sig')
    target = ASSETS / 'textures/entity/shield/ancient_embrace.png'
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(texture)
    image = Image.open(BytesIO(texture)).convert('RGBA')
    glow = Image.new('RGBA', image.size)
    # 叶片共用三块 box UV，只在这些纹素绘制更亮的生命绿，盾面与藤蔓不发光。
    for box in geometry['boxes']:
        if box['group'] != 'leaves':
            continue
        u, v = box['uv']; w, h, d = box['size']
        for y in range(v, int(v + d + h)):
            for x in range(u, int(u + 2 * (w + d))):
                r, g, b, a = image.getpixel((x, y))
                if a:
                    glow.putpixel((x, y), (min(255, r + 95), min(255, g + 105), min(255, b + 70), a))
    glow.save(target.with_name('ancient_embrace_glow.png'))
    # 保留导出类的全部 29 个方块，只适配 Model API 与按叶片选择可见部件。
    source = source.replace('package example.client.model;', 'package dev.logos.dimension.client;')
    source = source.replace('import com.mojang.blaze3d.vertex.PoseStack;\n', '').replace('import com.mojang.blaze3d.vertex.VertexConsumer;\n', '')
    source = source.replace('import net.minecraft.client.renderer.RenderType;', 'import net.minecraft.client.renderer.rendertype.RenderTypes;')
    source = source.replace('/** Mojang mappings / NeoForge Minecraft 1.21.1. Register and render via your item renderer. */',
                            '/** 导入素材包的完整盾牌几何；普通与常亮两遍互斥绘制，保留原图、UV 和实际实体光照。 */')
    source = source.replace('extends Model {', 'extends Model<Integer> {')
    source = source.replace('    private final ModelPart root;', '    private final boolean emissive;')
    source = source.replace('AncientEmbraceModel(ModelPart root)', 'AncientEmbraceModel(ModelPart root, boolean emissive)')
    source = source.replace('super(RenderType::entityCutoutNoCull);\n        this.root = root;', 'super(root, RenderTypes::entityCutout);\n        this.emissive = emissive;')
    start = source.index('    @Override\n    public void renderToBuffer')
    lines = ['    @Override public void setupAnim(Integer leaves) {', '        resetPose();']
    for box in geometry['boxes']:
        name = box['name']
        if box['group'] == 'leaves':
            index = next(i for i, prefix in enumerate(LEAVES) if name.startswith(prefix + '_'))
            visible = f'(leaves > {index}) == emissive'
        else:
            visible = '!emissive'
        lines.append(f'        root.getChild("{name}").visible = {visible};')
    source = source[:start] + '\n'.join(lines) + '\n    }\n}\n'
    (ROOT / 'src/main/java/dev/logos/dimension/client/AncientEmbraceModel.java').write_text(source, encoding='utf-8')
    special = {'type': 'logos_dimension:ancient_embrace'}
    transform = {'translation': [0, 0, 0], 'left_rotation': [0, 0, 0, 1],
                 'scale': [1, -1, -1], 'right_rotation': [0, 0, 0, 1]}
    write(ASSETS / 'items/ancient_embrace.json', {'model': {
        'type': 'minecraft:condition', 'property': 'minecraft:using_item',
        'on_false': {'type': 'minecraft:special', 'base': 'minecraft:item/shield', 'model': special},
        'on_true': {'type': 'minecraft:special', 'base': 'minecraft:item/shield_blocking', 'model': special},
        'transformation': transform}})
    for lang, title, tooltip in [('zh_cn', '古木之拥', '格挡点亮叶片，五片全亮后收盾消耗5魔力恢复4点生命；举盾瞬间格挡点亮两片'),
                                  ('en_us', 'Ancient Embrace', 'Block to light leaves. Lower at five leaves: 5 mana restores 4 health. Well-timed blocks light two leaves.')]:
        path = ASSETS / f'lang/{lang}.json'
        data = json.loads(path.read_text(encoding='utf-8'))
        data['item.logos_dimension.ancient_embrace'] = title
        data['item.logos_dimension.ancient_embrace.desc'] = tooltip
        write(path, data)
    tag = ROOT / 'src/main/resources/data/minecraft/tags/item/enchantable/durability.json'
    data = json.loads(tag.read_text(encoding='utf-8'))
    if 'logos_dimension:ancient_embrace' not in data['values']:
        data['values'].append('logos_dimension:ancient_embrace')
    write(tag, data)

if __name__ == '__main__':
    main()
