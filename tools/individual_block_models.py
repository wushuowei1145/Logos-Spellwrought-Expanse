"""One authored silhouette/surface recipe for each registered decorative block.

Coordinates use Minecraft's 0..16 block space. These are deliberately named recipes,
not randomly perturbed copies of a family mesh. Shared functions only construct cubes.
"""
import json
import re
from polish_models import ASSETS, ROOT, NS, box, model, texture, tint, write


def part(name, bounds, material='body', angle=0, axis='z', glow=0):
    return box(name, bounds[:3], bounds[3:], material,
               (axis,angle) if angle else None, emission=glow)


# Each entry specifies its own structural motif and exact component arrangement.
# Ground profiles stay within 0.14 model pixels of a complete, walkable block surface.
SURFACES = {
 'silk_folds':('平行折绸嵌脉',[(1,2,14,3),(2,7,15,8),(0,12,12,13)]),
 'jade_whirls':('内旋玉瓣',[(2,2,11,4),(10,3,13,11),(4,10,12,13),(3,5,5,10)]),
 'pearl_basins':('珠贝同心浅槽',[(3,3,13,4),(3,12,13,13),(3,4,4,12),(12,4,13,12),(7,7,9,9)]),
 'reverse_gardens':('逆生根槽',[(3,1,4,15),(8,2,9,12),(12,0,13,16),(5,11,9,12)]),
 'star_ribbons':('断续星丝',[(1,3,6,4),(6,5,11,6),(10,7,15,8),(4,11,12,12)]),
 'blue_bell_steps':('错阶铃纹',[(1,2,7,5),(7,5,12,8),(3,8,9,11),(9,11,15,14)]),
 'opal_dunes':('贝光砂脊',[(1,1,14,2),(3,5,16,6),(0,10,13,11),(5,14,15,15)]),
 'halo_spires':('四角光冠槽',[(6,1,10,3),(1,6,3,10),(13,6,15,10),(6,13,10,15),(7,7,9,9)]),
 'moon_folds':('月弧叠片',[(3,2,11,3),(2,4,5,6),(1,7,3,11),(3,12,10,14)]),
 'verdant_whirls':('交织翠羽',[(2,2,5,10),(5,9,13,12),(10,3,13,9),(6,4,9,6)]),
 'glass_lagoons':('折光镜框',[(1,1,15,2),(1,14,15,15),(1,2,2,14),(14,2,15,14),(5,6,11,7)]),
 'lantern_shelves':('垂灯三孔',[(2,2,5,5),(7,6,10,9),(11,10,14,13),(4,11,7,12)]),
 'dawn_ribbons':('曦光射线',[(7,1,9,6),(1,7,6,9),(10,7,15,9),(7,10,9,15)]),
 'lilac_steps':('丁香阶瓣',[(2,2,6,6),(8,3,12,7),(4,9,8,13),(10,10,14,14)]),
 'rose_dunes':('玫辉重瓣',[(4,2,12,4),(2,5,5,11),(11,5,14,11),(5,11,11,14),(7,6,9,9)]),
 'frost_spires':('六枝霜晶',[(7,0,9,16),(1,4,6,5),(10,10,15,11),(3,11,7,12),(9,3,13,4)]),
 'echo_folds':('回声双槽',[(3,2,5,14),(11,2,13,14),(5,11,11,13)]),
 'amber_whirls':('琥珀蜂巢核',[(4,3,12,5),(3,5,5,11),(11,5,13,11),(4,11,12,13),(7,7,9,9)]),
 'dream_basins':('梦泽浮泡',[(2,3,6,5),(9,1,14,3),(8,9,13,12),(2,12,5,14)]),
 'cloudroot_shelves':('编根交点',[(2,1,4,12),(7,4,9,15),(12,0,14,11),(3,6,13,7)]),
 'tide_ribbons':('听潮折波',[(1,6,5,8),(5,3,8,5),(8,6,12,8),(12,10,15,12)]),
 'ivory_steps':('象牙嵌板',[(2,2,14,6),(2,7,10,10),(6,11,14,14)]),
 'aurora_dunes':('极光流束',[(1,1,3,12),(5,4,7,15),(9,1,11,11),(13,5,15,15)]),
 'pale_spires':('白羽脊线',[(7,1,9,15),(2,3,6,5),(10,6,14,8),(2,10,6,12)]),
}


def leaves():
    p=part
    return {
      'silk_folds':('三折悬绸',[
        p('lower_pleat',(1,5,5,15,5.5,11),angle=-22.5,axis='x'),p('middle_pleat',(2,8,4,14,8.5,12),angle=22.5,axis='x'),
        p('upper_fold',(4,11,5,12,11.5,11),angle=-22.5,axis='x'),p('longitudinal_keel',(1,7.6,7.6,15,8.2,8.4),'rib'),
        p('silk_seam',(4,8.3,7.8,12,8.45,8.2),'glow',glow=5)]),
      'jade_whirls':('三瓣旋玉',[
        p('petal_west',(1,7,5,9,7.6,10),angle=22.5),p('petal_east',(8,8,4,15,8.6,11),angle=-22.5),
        p('petal_rear',(5,9,1,11,9.6,9),angle=45,axis='y'),p('jade_socket',(6,7,6,10,10,10),'rib'),
        p('spiral_eye',(7,10,7,9,11.5,9),'glow',angle=45,axis='y',glow=7)]),
      'pearl_basins':('张口珠贝',[
        p('shell_floor',(3,5,3,13,5.7,13)),p('shell_left',(1,6,4,4,6.6,12),angle=-22.5),
        p('shell_right',(12,6,4,15,6.6,12),angle=22.5),p('shell_back',(4,6,12,12,8,13)),
        p('pearl',(6.5,6,6.5,9.5,9,9.5),'glow',angle=45,axis='y',glow=6),p('shell_foot',(6,2,6,10,5,10),'rib')]),
      'reverse_gardens':('倒挂钩叶',[
        p('anchor',(6.5,13,6.5,9.5,16,9.5),'rib'),p('vine',(7.6,1,7.6,8.4,14,8.4),'rib'),
        p('hook_left',(2,8,5,8,8.6,11),angle=-45),p('hook_right',(8,10,5,14,10.6,11),angle=45),
        p('droop_left',(3,2,6,4,9,10)),p('droop_right',(12,4,6,13,11,10)),p('tip_drop',(7,0,7,9,2,9),'glow',glow=6)]),
      'star_ribbons':('穿星缎带',[
        p('ribbon_low',(0,4,6,8,4.5,10),angle=22.5),p('ribbon_high',(7,9,6,16,9.5,10),angle=22.5),
        p('ribbon_bridge',(6,5,7,9,10,9),'rib'),p('star_left',(2,5,7.2,4,7,8.8),'glow',angle=45,glow=6),
        p('star_right',(12,10,7.2,14,12,8.8),'glow',angle=45,glow=6),p('tail',(13,3,7.5,14,9,8.5),'rib')]),
      'blue_bell_steps':('三阶风铃',[
        p('stem',(7.7,0,7.7,8.3,16,8.3),'rib'),p('branch_low',(2,4,7.4,8,5,8.6),'rib'),
        p('branch_mid',(8,8,7.4,14,9,8.6),'rib'),p('branch_top',(4,12,7.4,8,13,8.6),'rib'),
        p('bell_one',(1,2,6,4,4,10)),p('bell_two',(12,6,6,15,8,10)),p('bell_three',(3,10,6,6,12,10)),
        p('chime_one',(2,1,7,3,3,9),'glow',glow=5),p('chime_two',(13,5,7,14,7,9),'glow',glow=5)]),
      'opal_dunes':('五片欧泊扇',[
        p('fan_one',(1,3,7,4,11,8),angle=-45),p('fan_two',(4,2,6.5,6,14,8),angle=-22.5),
        p('fan_three',(7,1,7,9,16,9)),p('fan_four',(10,2,7,12,14,8.5),angle=22.5),
        p('fan_five',(12,3,8,15,11,9),angle=45),p('opal_base',(5,0,5,11,2,11),'rib'),p('opal_seed',(7,2,7,9,6,9),'glow',glow=5)]),
      'halo_spires':('悬环光冠',[
        p('spire',(7,0,7,9,13,9),'rib'),p('halo_top',(3,13,7,13,14,9)),p('halo_base',(3,5,7,13,6,9)),
        p('halo_left',(2,6,7,3,13,9)),p('halo_right',(13,6,7,14,13,9)),
        p('crowned_core',(6.5,8,6.5,9.5,11,9.5),'glow',angle=45,axis='y',glow=8),p('crown_point',(7.5,14,7.5,8.5,16,8.5),'glow',glow=6)]),
      'moon_folds':('弯月薄纱',[
        p('moon_heel',(3,2,7,8,3,9)),p('moon_lower',(1,4,6,4,8,10),angle=-22.5),
        p('moon_upper',(2,8,6,5,13,10),angle=22.5),p('moon_tip',(4,13,7,10,14,9),angle=22.5),
        p('moon_tear',(10,7,7.4,11,11,8.6),'glow',glow=6),p('suspended_vein',(4,5,8,5,11,8.5),'rib')]),
      'verdant_whirls':('交缠翠羽',[
        p('feather_a',(1,7,5,13,7.6,8),angle=22.5,axis='y'),p('feather_b',(3,9,8,15,9.6,11),angle=-22.5,axis='y'),
        p('ascending_frond',(5,5,7,7,14,9),angle=-22.5),p('descending_frond',(10,3,7,12,12,9),angle=-22.5),
        p('twined_knot',(6,7,6,10,10,10),'rib',angle=45,axis='y'),p('green_heart',(7,9.7,7,9,10.5,9),'glow',glow=5)]),
      'glass_lagoons':('浮镜水叶',[
        p('mirror_sheet',(3,5,4,13,5.35,12)),p('mirror_lip_a',(2,5,3,14,6,4),'rib'),
        p('mirror_lip_b',(2,5,12,14,6,13),'rib'),p('mirror_handle',(7.5,1,7.5,8.5,5,8.5),'rib'),
        p('reflection_prism',(5,7,6,11,7.5,10),angle=22.5,axis='x'),p('water_drop',(7,9,7,9,11,9),'glow',angle=45,axis='y',glow=6)]),
      'lantern_shelves':('双层灯帷',[
        p('upper_canopy',(1,12,4,15,12.6,12),angle=-22.5,axis='x'),p('lower_canopy',(3,8,5,13,8.6,11),angle=22.5,axis='x'),
        p('canopy_keel',(7.5,7,7.5,8.5,15,8.5),'rib'),p('tassel_left',(3,3,7.7,3.6,12,8.3),'rib'),
        p('tassel_right',(12,5,7.7,12.6,12,8.3),'rib'),p('lamp_left',(2.4,1,7,4.2,4,9),'glow',glow=8),p('lamp_right',(11.4,3,7,13.2,6,9),'glow',glow=8)]),
      'dawn_ribbons':('旭光展翼',[
        p('sun_wing_left',(0,8,6,8,8.5,10),angle=-22.5),p('sun_wing_right',(8,8,6,16,8.5,10),angle=22.5),
        p('sun_disk',(6,8,7,10,12,9),'glow',angle=45,glow=7),p('sun_ray',(7.7,3,7.7,8.3,16,8.3),'rib'),
        p('silken_tail',(4,2,7.3,7,6,8.7),angle=22.5)]),
      'lilac_steps':('丁香叠蕾',[
        p('flower_branch',(7.6,0,7.6,8.4,15,8.4),'rib'),p('lower_petals',(2,4,5,8,4.6,11),angle=-22.5),
        p('middle_petals',(8,8,5,14,8.6,11),angle=22.5),p('upper_petals',(5,12,6,11,12.6,10),angle=45,axis='y'),
        p('lower_bud',(3,5,7,5,7,9),'glow',glow=5),p('middle_bud',(11,9,7,13,11,9),'glow',glow=5),p('upper_bud',(7,13,7,9,15,9),'glow',glow=5)]),
      'rose_dunes':('折瓣玫辉',[
        p('rose_outer_l',(1,7,4,8,7.6,12),angle=-22.5),p('rose_outer_r',(8,7,4,15,7.6,12),angle=22.5),
        p('rose_cross_a',(4,9,2,12,9.6,9),angle=22.5,axis='x'),p('rose_cross_b',(4,9,8,12,9.6,15),angle=-22.5,axis='x'),
        p('rose_inner',(5.5,10,5.5,10.5,12,10.5),angle=45,axis='y'),p('rose_center',(7,12,7,9,14,9),'glow',glow=6),p('rose_stalk',(7.5,0,7.5,8.5,8,8.5),'rib')]),
      'frost_spires':('霜晶六芒',[
        p('frost_needle',(7,0,7,9,16,9),'rib'),p('frost_west',(2,6,7,7,7,9),angle=45),p('frost_east',(9,6,7,14,7,9),angle=-45),
        p('frost_rear',(7,6,2,9,7,7),angle=-45,axis='x'),p('frost_front',(7,6,9,9,7,14),angle=45,axis='x'),
        p('frost_lens',(6,8,6,10,12,10),angle=45,axis='y'),p('frost_tip',(7.4,12,7.4,8.6,16,8.6),'glow',glow=7)]),
      'echo_folds':('回音双叉',[
        p('fork_foot',(5,0,6,11,3,10),'rib'),p('fork_left',(3,3,6,5,14,10)),p('fork_right',(11,3,6,13,14,10)),
        p('fork_bridge',(4,3,6,12,4,10)),p('left_fin',(1,9,7,4,9.6,9),angle=-22.5),p('right_fin',(12,9,7,15,9.6,9),angle=22.5),
        p('echo_spark',(7,8,7.4,9,10,8.6),'glow',angle=45,glow=6)]),
      'amber_whirls':('蜜光花轮',[
        p('amber_a',(1,8,6,15,8.6,10)),p('amber_b',(6,8.6,1,10,9.2,15)),p('amber_c',(2,9.2,6,14,9.8,10),angle=45,axis='y'),
        p('amber_d',(6,9.8,2,10,10.4,14),angle=45,axis='y'),p('honey_socket',(5,6,5,11,8,11),'rib'),
        p('honey_lens',(6,10.4,6,10,12,10),'glow',angle=45,axis='y',glow=7),p('amber_stem',(7.5,0,7.5,8.5,6,8.5),'rib')]),
      'dream_basins':('梦泽浮盘',[
        p('lower_lens',(2,4,3,14,4.6,13),angle=22.5,axis='x'),p('upper_lens',(4,10,5,12,10.6,11),angle=-22.5,axis='x'),
        p('dream_thread',(7.8,2,7.8,8.2,14,8.2),'rib'),p('dream_orb',(6,6,6,10,9,10),'glow',angle=45,axis='y',glow=6),
        p('dream_drop',(7,13,7,9,15,9),'glow',glow=6)]),
      'cloudroot_shelves':('盘结云根',[
        p('root_crown',(3,12,4,13,13,12),angle=22.5,axis='x'),p('root_left',(4,1,6,5,13,7),'rib',angle=-22.5),
        p('root_right',(11,3,9,12,14,10),'rib',angle=-22.5),p('root_bridge',(4,6,7,12,7,9),angle=22.5),
        p('root_leaf',(5,10,4,11,10.7,12),angle=-22.5,axis='x'),p('root_seed',(6.8,1,6.8,9.2,3.5,9.2),'glow',glow=5)]),
      'tide_ribbons':('潮汐起伏带',[
        p('wave_a',(0,5,6,5,5.6,10),angle=22.5),p('wave_b',(4,8,6,10,8.6,10),angle=-22.5),
        p('wave_c',(9,5,6,16,5.6,10),angle=22.5),p('wave_keel',(2,6,7.7,14,6.6,8.3),'rib'),
        p('tide_spark_a',(3,7,7,5,8,9),'glow',glow=5),p('tide_spark_b',(11,7,7,13,8,9),'glow',glow=5)]),
      'ivory_steps':('象牙层叶',[
        p('terrace_low',(1,3,3,15,4,13)),p('terrace_middle',(3,7,4,13,8,12)),p('terrace_high',(5,11,5,11,12,11)),
        p('tier_stem',(7.2,0,7.2,8.8,12,8.8),'rib'),p('ivory_crown',(6.5,12,6.5,9.5,14,9.5),'glow',angle=45,axis='y',glow=6),
        p('ivory_tassel',(12,1,7.5,13,6,8.5),'rib')]),
      'aurora_dunes':('极光三帷',[
        p('aurora_west',(2,2,7,4,13,7.6),angle=-22.5),p('aurora_center',(7,1,6,9,16,6.6),angle=22.5),
        p('aurora_east',(12,3,8,14,14,8.6),angle=-22.5),p('aurora_base',(3,1,6,13,2,10),'rib'),
        p('aurora_light',(7.8,3,5.85,8.2,14,6),'glow',glow=6),p('aurora_tip',(7,14.5,6,9,16,8),'glow',glow=5)]),
      'pale_spires':('白羽晶脊',[
        p('feather_keel',(7.5,0,7.5,8.5,16,8.5),'rib'),p('barb_l_low',(1,4,7.4,7,5,8.6),angle=22.5),
        p('barb_l_high',(3,10,7.4,7,11,8.6),angle=22.5),p('barb_r_low',(9,6,7.4,15,7,8.6),angle=-22.5),
        p('barb_r_high',(9,12,7.4,13,13,8.6),angle=-22.5),p('feather_nib',(7,13,7,9,16,9),'glow',glow=6)])
    }


def refine_surface(name, motif, rectangles):
    path=ASSETS/'models/block'/f'{name}.json';spec=json.loads(path.read_text(encoding='utf-8'))
    body=spec['elements'][0]; body['name']=motif+'_foundation';body['to'][1]=16
    # Geometric relief is deliberately shallow so repeated terrain stays walkable and quiet.
    parts=[part(f'{motif}_{i}',(x1,16.003,z1,x2,16.09,z2)) for i,(x1,z1,x2,z2) in enumerate(rectangles)]
    # The body stays a complete opaque cube. These tiny inlaid facets only expose their
    # top; subpixel vertical faces would self-shadow into black dashes at terrain scale.
    for element in parts:element['faces']={'up':element['faces']['up']}
    spec['elements']+=parts
    write(path,spec)


def cloudsea_models():
    for key,(motif,rectangles) in SURFACES.items():refine_surface(f'cloudsea_{key}_ground',motif,rectangles)
    for key,(motif,elements) in leaves().items():
        name=f'cloudsea_{key}_leaf';path=ASSETS/'models/block'/f'{name}.json';spec=json.loads(path.read_text(encoding='utf-8'))
        model(name,elements,spec['textures'],True)
    # Common building materials get separate constructions, not a cube_all material swap.
    solids={
      'cloudsea_porcelain':('云瓷斜切面',[(1,1,7,4),(8,8,15,11),(3,12,11,15)]),
      'cloudsea_mantle':('断层封板',[(0,0,16,4),(0,5,11,9),(5,10,16,15)]),
      'cloudsea_demonstone':('魔气裂甲',[(1,1,6,7),(9,2,15,5),(7,10,14,15)]),
      'cloudsea_ivory_bricks':('两列象牙砌面',[(.25,.25,7.75,7.75),(8.25,.25,15.75,7.75),(.25,8.25,15.75,15.75)]),
      'cloudsea_dark_timber':('深木剖纹',[(1,1,4,15),(6,0,9,16),(11,1,15,15)]),
      'cloudsea_blue_slate':('双排叠瓦',[(.2,.2,7.8,7.8),(8.2,.2,15.8,7.8),(2,8.2,14,15.8)]),
      'cloudsea_lit_window':('内嵌暖光窗棂',[(0,0,16,1),(0,15,16,16),(0,1,1,15),(15,1,16,15),(7.5,1,8.5,15)])}
    for name,(motif,rectangles) in solids.items():refine_surface(name,motif,rectangles)
    # Add actual proud mullions on all four sides of the window, matching the inset panes.
    path=ASSETS/'models/block/cloudsea_lit_window.json';spec=json.loads(path.read_text(encoding='utf-8'))
    for x in (0,7.5,15):
        spec['elements'].append(part('front_mullion_'+str(x),(x,0,15.98,min(16,x+1),16,16.07)))
        spec['elements'].append(part('back_mullion_'+str(x),(x,0,-.07,min(16,x+1),16,.02)))
    write(path,spec)


def crucible_models():
    profiles={
      'strata_stone':('层理交叠岩板',[(0,1,12,5),(4,6,16,10),(0,11,13,15)]),
      'aether_soil':('根际土团',[(1,2,6,7),(9,1,15,5),(5,10,11,15)]),
      'aether_turf':('风蚀草皮',[(0,0,16,2),(1,4,5,15),(7,7,15,12)]),
      'island_stone':('空岛古石接缝',[(.25,.25,15.75,7.75),(.25,8.25,7.75,15.75),(8.25,8.25,15.75,15.75)]),
      'charred_stone':('焦壳碎板',[(0,0,5,6),(6,3,12,8),(2,10,8,15),(11,11,16,16)]),
      'mercury_metal':('液金回流脊',[(2,2,14,3),(3,5,13,7),(4,10,12,14)]),
      'fan_trunk':('扇木端纹',[(0,0,3,16),(4,0,12,3),(13,0,16,16),(4,13,12,16)]),
      'vortex':('炼金旋涡刻边',[(0,0,16,1),(0,15,16,16),(0,1,1,15),(15,1,16,15)])}
    for name,(motif,rectangles) in profiles.items():refine_surface(name,motif,rectangles)
    reactive={
      'cinnabar':('朱砂棱鳞',[(1,1,6,6),(8,2,14,5),(4,8,10,14)]),
      'sulfur_deposit':('硫质团壳',[(1,2,7,8),(8,1,15,6),(7,9,14,15),(1,11,5,15)]),
      'mercury_crust':('凝汞褶边',[(1,1,3,14),(4,3,6,16),(8,0,11,13),(13,2,15,15)]),
      'salt_crust':('盐壳错鳞',[(0,0,9,5),(10,1,16,7),(2,7,7,16),(8,9,15,15)]),
      'salt_crystal':('六角盐晶端面',[(4,1,12,3),(1,4,3,12),(13,4,15,12),(4,13,12,15),(5,5,11,11)])}
    for name,(motif,rectangles) in reactive.items():
        for phase in range(4):refine_surface(f'{name}_{phase}',motif,rectangles)
    # Existing natural plants each receive their own distinct blossom/leaf anatomy.
    p=part
    plants={
      'fan_leaves':('扇冠分指叶',[p('fan_root',(6,2,6,10,4,10),'rib'),p('fan_left',(1,6,6,7,6.6,10),angle=-22.5),p('fan_middle',(5,8,5,11,8.6,11)),p('fan_right',(9,6,6,15,6.6,10),angle=22.5),p('fan_spine',(7.6,3,7.6,8.4,12,8.4),'rib')]),
      'glass_leaves':('折光四叶',[p('glass_a',(2,8,6,14,8.4,10),angle=45,axis='y'),p('glass_b',(6,8.5,2,10,8.9,14),angle=45,axis='y'),p('glass_keel',(7.5,2,7.5,8.5,12,8.5),'rib'),p('glass_seed',(6.6,9,6.6,9.4,12,9.4),'glow',angle=45,axis='y',glow=6)]),
      'amber_seeds':('三枝琥珀穗',[p('string_a',(2.5,1,5,3.1,16,5.6),'rib'),p('string_b',(7.7,0,8,8.3,16,8.6),'rib'),p('string_c',(12.5,4,4,13.1,16,4.6),'rib'),p('amber_a',(1.5,2,4,4,5,6.5),'glow',glow=5),p('amber_b',(6.5,5,7,9.5,8,10),'glow',glow=5),p('amber_c',(11.5,7,3,14,10,5.5),'glow',glow=5)]),
      'star_blossom':('六片星瓣',[p('stalk',(7.6,0,7.6,8.4,8,8.4),'rib'),p('star_a',(2,8,7,14,8.6,9),angle=45,axis='y'),p('star_b',(7,8.7,2,9,9.3,14),angle=45,axis='y'),p('star_c',(3,9.4,7,13,10,9)),p('star_eye',(7,10,7,9,11,9),'glow',glow=6)]),
      'aether_fern':('卷梢以太蕨',[p('fern_axis',(7.5,0,7.5,8.5,13,8.5),'rib'),p('fern_left',(1,4,6.5,7,5,9.5),angle=22.5),p('fern_right',(9,7,6.5,15,8,9.5),angle=-22.5),p('fern_upper',(4,10,7,7,11,9),angle=22.5),p('fern_crook',(7,13,7,11,14,9)),p('fern_tip',(10,11,7.5,11,14,8.5),'glow',glow=3)])}
    for name,(motif,elements) in plants.items():
        path=ASSETS/'models/block'/f'{name}.json';spec=json.loads(path.read_text(encoding='utf-8'));model(name,elements,spec['textures'],True)
    # Preserve transparent glass's uninterrupted boundary and the deliberately enlarged vessel.
    path=ASSETS/'models/block/distillation_glass.json';spec=json.loads(path.read_text(encoding='utf-8'))
    spec['elements'] += [p('blown_rim_north',(0,15.8,0,16,16,.16)),p('blown_rim_west',(0,0,0,.16,16,16))]
    write(path,spec)


def catalogue():
    entries={}
    for key,(motif,_) in SURFACES.items():entries[f'cloudsea_{key}_ground']=motif
    for key,(motif,_) in leaves().items():entries[f'cloudsea_{key}_leaf']=motif
    # The common blocks already have individually authored recipes in polish_models.py.
    entries.update(dict(zip(('cloudsea_'+x for x in ('porcelain','mantle','stem','lantern_bud','hanging_root','eye_fern','demon_reed','demonstone','ivory_bricks','dark_timber','blue_slate','lit_window')),
        ('切面云瓷','断层魔岩','节间光茎','开萼灯蕊','三枝丝根','对生瞳蕨','幽焰三苇','魔气裂甲','错砌象牙','剖纹深木','双排蓝瓦','深窗暖格'))))
    original=('strata_stone','aether_soil','aether_turf','island_stone','charred_stone','cinnabar','sulfur_deposit','mercury_metal','mercury_crust','salt_crust','salt_crystal','fan_trunk','fan_leaves','glass_leaves','distillation_glass','amber_seeds','star_blossom','aether_fern','vortex','alchemical_cauldron')
    motifs=('交叠岩板','根际土团','风蚀草皮','古石接缝','焦壳碎板','朱砂棱鳞','硫质团壳','液金回流','凝汞褶边','盐壳错鳞','盐晶端面','木端年纹','分指扇冠','折光四叶','薄边蒸馏玻璃','琥珀三穗','六瓣星花','卷梢以太蕨','液态刻边','放大四足炼金釜')
    entries.update(zip(original,motifs))
    assert len(entries)==80
    rows=['# 逐种方块模型目录','','80 个既有方块各自拥有模型文件与部件设计。可共用底层立方体构造函数；不同悬叶不再共享一套形态模板。地表、建筑材料保持完整可行走的块体，仅在表面增加浅浮雕，避免破坏地形连接。四阶段天然材料在同一方块内部保留四种外观状态。','','| 注册名 | 设计 | 模型路径 |','| --- | --- | --- |']
    for name,motif in entries.items():
        modelname=name+'_0' if name in ('cinnabar','sulfur_deposit','mercury_crust','salt_crust','salt_crystal') else name
        rows.append(f'| `{name}` | {motif} | `models/block/{modelname}.json` |')
    (ROOT/'docs/INDIVIDUAL_BLOCK_MODELS.md').write_text('\n'.join(rows)+'\n',encoding='utf-8')


if __name__=='__main__':
    # Call the base generator so this entry point remains deterministic and idempotent.
    from polish_models import polish_cloudsea, polish_crucible
    polish_cloudsea();polish_crucible();catalogue()
