"""实际导出模板检查：无作物、无实体、完整拼接与唯一核心。"""
import json
import unittest
from pathlib import Path
from blueprint_nbt import read_nbt

DATA = Path(__file__).resolve().parents[1] / 'src/main/resources/data/logos_dimension'


class BlightedTemplates(unittest.TestCase):
    def test_templates_are_polluted_and_have_no_residents_or_crops(self):
        paths = list((DATA / 'structure/blighted_elven').glob('*.nbt'))
        self.assertEqual(348, len(paths))
        hearts = 0
        for path in paths:
            doc = read_nbt(path)
            self.assertFalse(doc.get('entities'), path.name)
            names = {s['Name'] for s in doc['palette']}
            self.assertFalse(names.intersection({'logos:cultivation_jar', 'logos_dimension:elven_canopy', 'logos_dimension:elven_heartwood', 'logos_dimension:elven_flower_planter'}), path.name)
            self.assertFalse(any('wheat' in n or 'crop' in n for n in names), path.name)
            for b in doc['blocks']:
                name = doc['palette'][b['state']]['Name']
                if name == 'logos_dimension:queen_heart':
                    hearts += 1
                    self.assertEqual('tile_3_0_3', path.stem)
                    self.assertEqual([16, 13, 16], b['pos'])
                    self.assertEqual(7, b['nbt']['GuardianDrop'])
                if name == 'minecraft:jigsaw':
                    pool = b['nbt']['pool']
                    if pool == 'minecraft:empty':
                        continue
                    self.assertTrue(pool.startswith('logos_dimension:blighted_elven/'), pool)
                    self.assertTrue((DATA / ('worldgen/template_pool/' + pool.split(':')[1] + '.json')).exists(), pool)
        self.assertEqual(1, hearts)

    def test_root_pool_is_the_polluted_native_feature(self):
        pool = json.loads((DATA / 'worldgen/template_pool/blighted_elven/roots.json').read_text())
        self.assertEqual('logos_dimension:blighted_elven_roots', pool['elements'][0]['element']['feature'])
        roots = json.loads((DATA / 'worldgen/configured_feature/blighted_elven_roots.json').read_text())
        self.assertEqual('logos_dimension:blight_memory', roots['config']['layers'][0]['provider']['state']['Name'])

    def test_heavy_exterior_corruption_and_safe_hall(self):
        flesh, wood, leaves, clean_hall, veins = 0, 0, 0, 0, 0
        hazards = {'logos_dimension:' + n for n in ('corruption', 'blight_memory', 'corruption_vein', 'corruption_bud', 'corruption_spines', 'corruption_ribs')}
        for path in (DATA / 'structure/blighted_elven').glob('*.nbt'):
            doc = read_nbt(path)
            tile = path.stem.startswith('tile_')
            offset = [int(v) * 32 - (4 if i == 1 else 112) for i, v in enumerate(path.stem.split('_')[1:])] if tile else [0, 0, 0]
            local_flesh, local_tree = 0, 0
            for entry in doc['blocks']:
                state = doc['palette'][entry['state']]
                name = state['Name']
                appearance = int(state.get('Properties', {}).get('appearance', '0'))
                x, y, z = [entry['pos'][i] + offset[i] for i in range(3)]
                if tile and x*x + z*z <= 12*12 and 1 <= y <= 80:
                    self.assertNotIn(name, hazards, (path.name, [x, y, z]))
                    clean_hall += name not in ('minecraft:air', 'minecraft:jigsaw')
                if tile and x*x + z*z <= 16*16 and -4 <= y < 96:
                    continue
                flesh += (name == 'logos_dimension:corruption' or name == 'logos_dimension:blight_memory' and appearance == 0)
                wood += (name == 'logos_dimension:blighted_heartwood' or name == 'logos_dimension:blight_memory' and appearance == 1)
                leaves += (name == 'logos_dimension:blighted_canopy' or name == 'logos_dimension:blight_memory' and appearance == 2)
                veins += name == 'logos_dimension:corruption_vein'
                local_flesh += (name == 'logos_dimension:corruption' or name == 'logos_dimension:blight_memory' and appearance == 0)
                local_tree += name in ('logos_dimension:corruption', 'logos_dimension:blight_memory', 'logos_dimension:blighted_heartwood', 'logos_dimension:blighted_canopy')
            if not tile:
                self.assertGreater(local_flesh / local_tree, .65, path.name)
        self.assertGreater(flesh / (flesh + wood + leaves), .70)
        self.assertGreater(veins, 1000)
        self.assertGreater(clean_hall, 400)
        print('外侧污秽占树材比例 %.1f%%；外露脉络 %d 格；树心安全构件 %d 格' % (100 * flesh / (flesh + wood + leaves), veins, clean_hall))

    def test_pollution_remembers_exact_healthy_structure_material(self):
        palette = json.loads((DATA / 'blight_restoration.json').read_text(encoding='utf-8'))
        self.assertEqual({'Name': 'logos_dimension:cloudsea_moss_vale_ground'}, palette[0])
        self.assertEqual({'Name': 'minecraft:dirt'}, palette[1])
        restored = 0
        for path in (DATA / 'structure/blighted_elven').glob('*.nbt'):
            original = read_nbt(DATA / 'structure/elven' / path.name)
            states = {tuple(b['pos']): original['palette'][b['state']] for b in original['blocks']}
            polluted = read_nbt(path)
            for block in polluted['blocks']:
                state = polluted['palette'][block['state']]
                if state['Name'] != 'logos_dimension:blight_memory':
                    continue
                memory = palette[int(state['Properties']['memory'])]
                self.assertEqual(states[tuple(block['pos'])], memory, (path.name, block['pos']))
                restored += 1
        self.assertGreater(restored, 100000)

    def test_hall_is_stripped_to_black_floor_and_natural_walls(self):
        floors = 0
        for path in (DATA / 'structure/blighted_elven').glob('tile_*.nbt'):
            doc = read_nbt(path)
            offset = [int(v) * 32 - (4 if i == 1 else 112) for i, v in enumerate(path.stem.split('_')[1:])]
            for entry in doc['blocks']:
                x, y, z = [entry['pos'][i] + offset[i] for i in range(3)]
                name = doc['palette'][entry['state']]['Name']
                r = 13 if y < 82 else 13 - (y - 82) * .22
                if y == 1 and x*x + z*z <= 13*13:
                    self.assertEqual('logos_dimension:blighted_lumen', name)
                    floors += 1
                elif 2 <= y < 96 and x*x + z*z < r*r:
                    self.assertIn(name, ('minecraft:air', 'logos_dimension:queen_heart'), (path.name, x, y, z, name))
        self.assertGreater(floors, 500)


if __name__ == '__main__':
    unittest.main()
