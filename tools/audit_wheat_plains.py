"""检查怪圈的原版材料、模板边界、逐像素队徽、预设同步和脚本可复现性。"""
import hashlib
import json
from blueprint_nbt import read_nbt
import wheat_plains as wheat

root, data = wheat.ROOT, wheat.DATA
doc = read_nbt(data / 'structure/wheat/team_crop_circle.nbt')
assert doc['size'] == [128, 1, 128] and not doc['entities']
palette = doc['palette']
assert {s['Name'] for s in palette} == {'minecraft:hay_block', 'minecraft:dirt_path', 'minecraft:mud', 'minecraft:jigsaw'}
assert len(doc['blocks']) == len({tuple(b['pos']) for b in doc['blocks']})
mask = wheat.logo_mask()
for block in doc['blocks']:
    x, y, z = block['pos']
    assert 0 <= x < 128 and y == 0 and 0 <= z < 128
    state = palette[block['state']]
    if (x, z) == (64, 64):
        assert state['Name'] == 'minecraft:jigsaw'
        assert block['nbt']['name'] == 'logos_dimension:crop_circle_center'
    else:
        tone = mask.getpixel((x, z))
        assert state['Name'] == {1: 'minecraft:mud', 2: 'minecraft:dirt_path', 3: 'minecraft:hay_block'}[tone]
        if tone == 3:
            assert state['Properties']['axis'] == 'y'
prod = json.loads((data / 'dimension/cloudsea.json').read_text(encoding='utf-8'))
test = json.loads((root / 'gametest-packs/crucible/data/minecraft/worldgen/world_preset/flat.json').read_text(encoding='utf-8'))
assert prod == test['dimensions']['logos_dimension:cloudsea']
files = [data / 'structure/wheat/team_crop_circle.nbt', data / 'advancement/adventure/crop_circle.json',
         wheat.ART / 'crop_circle_plan.png', data / 'dimension/cloudsea.json',
         data / 'worldgen/noise_settings/journey.json']
before = {p: hashlib.sha256(p.read_bytes()).hexdigest() for p in files}
wheat.main()
assert all(hashlib.sha256(p.read_bytes()).hexdigest() == value for p, value in before.items())
print('麦田审计通过：', len(doc['blocks']), '处图案，原版材料，空实体，128 格边界，预设一致，重复生成一致。')
