"""导入审定仙剑美术包；演示根位移不作为游戏内移动，实际御剑由服务端载具负责。"""
import json
import shutil
import sys
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
ASSETS=ROOT/'src/main/resources/assets/logos_dimension'


def write(path,data):
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')


def main():
    source=Path(sys.argv[1]);resources=source/'resources/assets/logos_dimension'
    for folder in ('combat/weapons','textures/item','models/item','items'):
        for file in (resources/folder).glob('lingxiao*'):
            target=ASSETS/folder/file.name;target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(file,target)
    mesh=json.loads((ASSETS/'combat/weapons/lingxiao_sword.json').read_text(encoding='utf-8'))
    # 审定美术的第一人称按 0.58 倍烘焙，第三人称按 0.8 倍；不改共用参考骨架。
    mesh['first_person_scale']=.58/.8
    write(ASSETS/'combat/weapons/lingxiao_sword.json',mesh)
    for view in ('tp','fp'):
        data=json.loads((source/f'lingxiao_{view}.animation.json').read_text(encoding='utf-8'))
        for name,clip in data['animations'].items():
            if name.startswith('flight_'):
                # 仅移除离线舞台里的升空、前进、悬浮和侧倾，保留第一人称取景基准。
                clip['bones']['Root']={'position':[-13.8,-1.8,0] if view=='fp' else [0,0,0],
                    'rotation':[0,0,10] if view=='fp' else [0,0,0]}
        write(ASSETS/f'combat/animations/lingxiao_{view}.animation.json',data)
    moves={
        'draw':{'length':1.2,'next':'idle'},
        'idle':{'length':2.4,'playback':'loop','blend_in':.12,'on':[
            {'input':'attack','to':'attack_1','combo':True},{'input':'use_press','to':'flight_takeoff'}]},
        'flight_takeoff':{'length':1.6,'reset_combo':True,'blend_in':.10,'next':'flight_idle'},
        'flight_idle':{'length':2.4,'playback':'loop','blend_in':.10},
        'flight_land':{'length':1.8,'reset_combo':True,'blend_in':.10,'next':'idle'},
        'parried':{'length':.5,'reset_combo':True,'blend_in':.03,'next':'idle'},
        'parried_npc':{'length':.8,'reset_combo':True,'blend_in':.03,'next':'idle'},
        'sheathe':{'length':2.65,'blend_in':.10,'next':'$clear'}
    }
    for i,length,contact,cancel in [(1,.86,.31,.51),(2,.90,.29,.49),(3,1.12,.47,.74)]:
        next_name='attack_'+str(i%3+1)
        moves['attack_'+str(i)]={'length':length,'attack_speed_base':1.6,'contact':contact,'damage':1,
            'blend_in':.07,'combo_next':next_name,'next':'idle','on':[
                {'input':'attack','to':next_name,'window':[.1],'queue':True,'at':cancel}]}
    write(ROOT/'src/main/resources/data/logos_dimension/combat/movesets/lingxiao.json',
        {'start':'draw','sheathe':'sheathe','combo_memory':1.5,'moves':moves})
    print('已导入仙剑网格、双视角动作与招式。')


if __name__=='__main__':main()
