"""复用仓库 combat_author 编排凌霄仙剑；双视角共用刀路与标记。"""
import json
import math
import pickle
import sys
from pathlib import Path

import numpy as np

REPO=Path(__file__).resolve().parents[1]
ROOT=Path(sys.argv[1]).resolve()
sys.path.insert(0,str(REPO/'tools'))
from combat_author import Baker,bedrock,euler_matrix,frame,npc_parried,player_skeleton

IDLE=dict(grip=(.38,-.72,-.40),blade=(.10,.035,-1),edge=(0,-1,0),
    rmode='sword',lmode='free',lhand=(-.40,-.98,.04),up=(0,8,0),hips=(0,0,0),
    rfoot=(1/6,0,0,0),lfoot=(-1/6,0,0,0),
    fp=dict(grip=(.28,-.42,-.63),blade=(-.08,.20,-1),edge=(0,-1,-.1)))
HANG_R=(.40,-1,.04);HANG_L=(-.40,-1,.04)
FLIGHT=dict(grip=(0,-1.807936,.6784),blade=(0,0,-1),edge=(1,0,0),hips=(0,-.055,0),
    rmode='free',rhand=(.27,-.53,-.44),lmode='free',lhand=(-.36,-.85,.06),
    up=(0,0,0),chest=(-3,0,0),rfoot=(.03,0,-.25,0),lfoot=(-.03,0,.14,5),
    fp=dict(grip=(.08,-2.20224,1.08984)))


def k(t,base=None,**kw):return dict(base or {},t=t,**kw)


def clips():
    out=[dict(name='idle',length=2.4,loop='loop',keys=[k(0,IDLE),
        k(1.2,dict(IDLE,grip=(.38,-.69,-.41))),k(2.4,IDLE)])]
    out.append(dict(name='draw',length=1.2,loop='hold',keys=[
        k(0,sheathed=0,rmode='free',rhand=HANG_R,lmode='free',lhand=HANG_L,up=(0,0,0)),
        k(.18,sheathed=0,rmode='sword',lmode='saya',blend=.18,up=(0,12,0)),
        k(.36,sheathed=4,saya_off=(-.10,0,.12),up=(0,18,0)),
        k(.68,sheathed=20.7,saya_off=(-.25,-.02,.35),ease='hold'),
        k(.84,grip=(.25,-.40,-.82),blade=(.12,.42,-.90),edge=(0,-1,-.45),lmode='saya'),
        k(1.02,dict(IDLE,saya_off=(0,0,0)),blend=.18),k(1.2,IDLE)]))
    # 参考史诗战斗单手长剑的斩、挑、转身发力顺序，重新编排本剑握点和脚步。
    low=dict(grip=(-.34,-.55,-.32),blade=(-.88,-.20,.42),edge=(0,-1,0),
        rmode='sword',lmode='free',lhand=(-.59,-.50,.22),up=(6,-36,8),
        hip_rot=(0,-14,0),hips=(-.02,-.08,-.08),
        rfoot=(.24,0,-.28,-12),lfoot=(-.24,0,.14,-12),turn=0,
        fp=dict(grip=(-.20,-.43,-.47)))
    high=dict(grip=(.52,-.06,-.24),blade=(.72,.50,.48),edge=(0,-1,0),
        rmode='sword',lmode='free',lhand=(-.56,-.45,.15),up=(-5,35,-8),
        hip_rot=(0,12,0),hips=(.02,-.06,-.05),
        rfoot=(.24,0,.10,15),lfoot=(-.24,0,-.24,15),turn=0,
        fp=dict(grip=(.39,-.08,-.38)))
    first=dict(name='attack_1',length=.86,loop='once',markers={
        'input_open':.10,'contact':.31,'cancel':.51,'trail_start':.20,'trail_end':.45},keys=[
        k(0,IDLE,hip_rot=(0,0,0),turn=0),
        k(.16,grip=(.46,.06,-.20),blade=(.22,.76,.61),edge=(.8,-.2,-.2),
          up=(-7,32,-6),hip_rot=(0,12,0),hips=(.01,-.05,.025),
          rfoot=(.24,0,.12,12),lfoot=(-.24,0,-.15,-8),lhand=(-.53,-.45,.16),
          ease='hold',fp=dict(grip=(.36,.01,-.36))),
        k(.31,grip=(.14,-.31,-.70),blade=(-.55,-.30,-.78),edge=(-.3,-1,.15),
          up=(8,-22,8),hip_rot=(0,-10,0),hips=(0,-.08,-.10),
          rfoot=(.24,0,-.26,-10),lfoot=(-.24,0,.14,-10),lhand=(-.61,-.43,.20),
          ease='lin',fp=dict(grip=(.14,-.26,-.65))),
        k(.44,low,ease='hold'),k(.54,low,ease='hold'),
        k(.72,dict(IDLE,hips=(0,-.03,-.03)),hip_rot=(0,-4,0),ease='hold'),
        k(.86,IDLE,hip_rot=(0,0,0),ease='hold')])
    second=dict(name='attack_2',length=.90,loop='once',markers={
        'input_open':.06,'contact':.29,'cancel':.49,'trail_start':.16,'trail_end':.40},keys=[
        k(0,low),
        k(.12,grip=(-.34,-.59,-.24),blade=(-.70,-.38,.60),edge=(0,-1,0),
          up=(8,-39,9),hips=(-.025,-.09,-.065),lhand=(-.61,-.48,.21),ease='hold',
          fp=dict(grip=(-.22,-.45,-.42))),
        k(.29,grip=(.26,-.26,-.70),blade=(.68,.48,-.55),edge=(.2,-1,-.05),
          up=(-4,23,-7),hip_rot=(0,9,0),hips=(.02,-.07,-.11),
          rfoot=(.24,0,.10,12),lfoot=(-.24,0,-.26,12),lhand=(-.55,-.47,.16),
          ease='lin',fp=dict(grip=(.24,-.22,-.62))),
        k(.41,high,ease='hold'),k(.52,high,ease='hold'),
        k(.74,dict(IDLE,hips=(0,-.025,-.03)),hip_rot=(0,4,0),ease='hold'),
        k(.90,IDLE,hip_rot=(0,0,0),ease='hold')])
    impact=dict(grip=(.12,-.34,-.75),blade=(-.72,-.10,-.69),edge=(0,-1,0),
        up=(4,-28,6),hip_rot=(0,-14,0),hips=(0,-.08,-.13),
        rfoot=(.25,0,-.28,-12),lfoot=(-.25,0,.10,-12),lhand=(-.59,-.46,.18),turn=360,
        fp=dict(grip=(.12,-.28,-.65)))
    third=dict(name='attack_3',length=1.12,loop='once',markers={
        'input_open':.08,'contact':.47,'cancel':.74,'trail_start':.17,'trail_end':.64},keys=[
        k(0,high),k(.12,high,ease='hold'),
        k(.18,grip=(.54,-.16,-.28),blade=(.30,.55,.78),edge=(0,-1,0),
          up=(-3,29,-6),hip_rot=(0,10,0),hips=(.01,-.07,-.06),turn=60,
          fp=dict(grip=(.40,-.13,-.39))),
        k(.26,grip=(.52,-.28,-.36),blade=(.68,.12,.72),edge=(0,-1,0),
          up=(0,25,-5),hip_rot=(0,8,0),hips=(-.03,-.08,-.10),turn=145,
          lhand=(-.51,-.50,-.05),ease='lin',fp=dict(grip=(.40,-.22,-.45))),
        k(.35,grip=(.54,-.28,-.43),blade=(.94,.04,-.34),edge=(0,-1,0),
          up=(0,10,-3),hip_rot=(0,0,0),hips=(0,-.09,-.10),turn=240,
          fp=dict(grip=(.42,-.22,-.49))),
        k(.43,grip=(.44,-.29,-.60),blade=(.68,.08,-.73),edge=(0,-1,0),
          up=(2,-10,2),hip_rot=(0,-8,0),hips=(0,-.085,-.12),turn=335,
          fp=dict(grip=(.34,-.23,-.58))),
        k(.47,impact,ease='lin'),k(.52,impact,ease='hold'),
        k(.65,dict(low,turn=360),ease='hold'),
        k(.87,IDLE,hip_rot=(0,0,0),turn=360,ease='hold'),
        k(1.12,IDLE,hip_rot=(0,0,0),turn=360,ease='hold')])
    out.extend([first,second,third])
    # 合成片段也实际烘焙交付，预览使用它，连招时不插入各单招末尾的回待机段。
    combo_keys=[];offset=0.0;contact=[];trails_in=[];trails_out=[]
    for clip,cut in [(first,.51),(second,.49),(third,1.12)]:
        for key in clip['keys']:
            if key['t']>cut:continue
            item=dict(key,t=round(offset+key['t'],6))
            if combo_keys and abs(item['t']-combo_keys[-1]['t'])<1e-6:combo_keys[-1]=item
            else:combo_keys.append(item)
        contact.append(round(offset+clip['markers']['contact'],4))
        trails_in.append(round(offset+clip['markers']['trail_start'],4))
        trails_out.append(round(offset+clip['markers']['trail_end'],4))
        offset=round(offset+cut,6)
    combo_keys.append(k(2.40,IDLE,hip_rot=(0,0,0),turn=360,ease='hold'))
    out.append(dict(name='attack_combo',length=2.40,loop='hold',keys=combo_keys,
        markers={'contact':contact,'cancel':1.74,'trail_start':trails_in,'trail_end':trails_out}))
    guard=dict(grip=(.19,-.38,-.58),blade=(-.65,.62,-.46),edge=(.3,-.3,-1),
        rmode='sword',lmode='free',lhand=(-.38,-.78,-.18),up=(0,-4,0))
    out.append(dict(name='guard',length=1.6,loop='loop',keys=[k(0,guard),k(.8,dict(guard,grip=(.19,-.36,-.58))),k(1.6,guard)]))
    parried=dict(name='parried',length=.5,loop='once',keys=[k(0,IDLE),
        k(.07,grip=(.55,-.32,-.20),blade=(.15,.68,-.65),edge=(0,-1,-.4),up=(5,15,0),ease='hold'),
        k(.24,grip=(.49,-.41,-.25),blade=(.1,.5,-.8),edge=(0,-1,-.2),up=(3,10,0)),k(.5,IDLE)])
    out.extend([parried,npc_parried(parried)])
    # 剑花放在右侧纵向平面，抬高握点，让下半圈也不会碰地或穿过身体。
    flourish=[k(0,IDLE),k(.22,grip=(.70,-.27,-.24),blade=(0,.10,-1),edge=(1,0,0),
        up=(0,10,0),lhand=(-.40,-.93,.04),ease='hold',fp=dict(grip=(.58,-.20,-.48)))]
    for i in range(9):
        angle=i*math.pi/4
        flourish.append(k(round(.34+i*.10,4),grip=(.70,-.27,-.24),
            blade=(0,math.sin(angle),-math.cos(angle)),edge=(1,0,0),up=(0,10,0),
            fp=dict(grip=(.58,-.20,-.48))))
    flourish.extend([
        k(1.30,grip=(.54,-.31,-.48),blade=(.10,.32,-.94),edge=(0,-1,-.34),
          lmode='saya',blend=.16,ease='hold',fp=dict(grip=(.42,-.25,-.56))),
        k(1.50,grip=(.25,-.40,-.82),blade=(.12,.42,-.90),edge=(0,-1,-.45)),
        k(1.75,sheathed=20.7,saya_off=(-.25,-.02,.35),up=(0,18,0),ease='hold'),
        k(2.02,sheathed=8,saya_off=(-.12,0,.16)),
        k(2.30,sheathed=0,saya_off=(0,0,0),up=(0,6,0)),
        k(2.40,sheathed=0,rmode='free',rhand=HANG_R,lmode='free',lhand=HANG_L,blend=.25),
        k(2.65,sheathed=0,up=(0,0,0))])
    out.append(dict(name='sheathe',length=2.65,loop='hold',markers={
        'flourish_start':.34,'flourish_end':1.14,'sheath_align':1.75,'sheath_seated':2.30},keys=flourish))
    out.append(dict(name='summon',length=1.5,loop='hold',keys=[
        k(0,grip=(.15,-.20,-1.50),blade=(0,.9,-.2),edge=(1,0,0),rmode='free',rhand=HANG_R,lmode='free',lhand=HANG_L),
        k(.45,grip=(.20,-.15,-1.10),blade=(0,.8,-.4),edge=(1,0,0),rhand=(.35,-.5,-.6)),
        k(.85,grip=(.32,-.40,-.55),blade=(.1,.4,-.9),edge=(0,-1,-.2),rmode='sword',blend=.20),
        k(1.5,IDLE)]))
    out.append(dict(name='flight_takeoff',length=1.6,loop='hold',keys=[k(0,IDLE),
        k(.25,grip=(.10,-.38,-.65),blade=(.1,.6,-.8),edge=(0,-1,-.2),rmode='free',rhand=(.32,-.42,-.6),blend=.18),
        k(.65,FLIGHT,ease='hold'),k(1.6,FLIGHT)]))
    out.append(dict(name='flight_idle',length=2.4,loop='loop',keys=[k(0,FLIGHT),k(1.2,FLIGHT),k(2.4,FLIGHT)]))
    for side in ('left','right'):
        out.append(dict(name='flight_turn_'+side,length=1.4,loop='hold',keys=[k(0,FLIGHT),
            k(.65,dict(FLIGHT,rhand=(.30,-.45,-.4),chest=(-4,0,0))),k(1.4,FLIGHT)]))
    out.append(dict(name='flight_land',length=1.8,loop='hold',keys=[k(0,FLIGHT),k(.90,FLIGHT,ease='hold'),
        k(1.2,grip=(.22,-.48,-.62),blade=(.1,.3,-1),edge=(0,-1,0),rmode='sword',blend=.20),k(1.8,IDLE)]))
    return out


def skeletons():
    data=REPO/'src/main/resources/assets/logos_dimension/combat/skeletons'
    R=frame((-.65,-.20,.73),(0,-1,-.27));mouth=np.array([-4.4,15.0,-2.3])
    result=[]
    for tag,s in [('tp',.8),('fp',.58)]:
        view_mouth=mouth+(np.array([0,4,-3.5]) if tag=='fp' else 0)
        sk=player_skeleton(data/f'player_{tag}.json',s,carry=(view_mouth-R@np.array([0,2.3*s,0]),R))
        sk.tag='player' if tag=='tp' else 'fp'
        sk.body=((-4.4,12,-2.5),(4.4,21,2.5))
        if tag=='fp':
            sk.root=((13.8,-1.8,0),(0,0,10));sk.reach=np.array([-1,1,-1.5])
        weapon=json.loads((ROOT/'lingxiao_sword.mesh.json').read_text(encoding='utf-8'))
        for b in weapon['bones']:
            parent=b['parent'];pivot=sk.weapon_pivot+np.array(b['pivot'])*s
            sk.bones[b['name']]={'name':b['name'],'parent':parent,'pivot':pivot,'rest':np.zeros(3)}
            sk.order.append(b['name'])
        result.append((tag,sk))
    return result


def flight_post(clip,t,eulers,sk):
    name=clip['name'];p=t/clip['length']
    if name=='flight_takeoff':a=math.sin(min(1,max(0,(p-.10)/.9))*math.pi/2)**2
    elif name=='flight_land':a=1-math.sin(min(1,p/.52)*math.pi/2)**2
    else:a=1.0
    if name.startswith('flight'):
        root=eulers['Root'];pos=np.array(root[0],float);r=np.array(root[1],float)
        pos+=np.array([0,22*a,-16*a])
        if name=='flight_idle':pos[1]+=.28*math.sin(2*math.pi*p)
        if name.startswith('flight_turn_'):
            sign=1 if name.endswith('left') else -1;b=math.sin(math.pi*p)
            r+=np.array([0,sign*24*b,-sign*12*b]);pos[0]+=sign*3*b
        eulers['Root']=(pos,r)
        scale=1.0+.8*a
    else:scale=1.0
    eulers['Weapon']=(*eulers['Weapon'][:2],np.array([scale]*3))
    for side,sign in [('Left',-1),('Right',1)]:
        for i in range(3):
            amplitude=2.2 if clip.get('loop')=='loop' else 5*math.sin(math.pi*p)
            eulers[f'Tassel{side}{i}']=(np.zeros(3),np.array([amplitude*math.sin(2*math.pi*p-i*.45),0,sign*amplitude*.6]))
    eulers['ysmGlowJade']=(np.zeros(3),np.zeros(3))
    return eulers


def main():
    all_clips=clips();baked={};skeleton_by={};reports={}
    for tag,sk in skeletons():
        skeleton_by[tag]=sk;baker=Baker(sk)
        for clip in all_clips:
            # 拔剑至完全出鞘的编排由同一份定义分别烘焙，不复制求解器。
            frames=baker.bake(clip,30)
            frames=[(t,flight_post(clip,t,eulers,sk)) for t,eulers in frames]
            if clip.get('loop')=='loop':frames[-1]=(frames[-1][0],frames[0][1])
            baked[tag,clip['name']]=frames
        reports[tag]={'reach_warnings':[list(x) for x in baker.warnings],
            'mean_grip_angle_degrees':float(np.mean([x[3] for x in baker.residuals])),
            'max_grip_angle_degrees':float(max(x[3] for x in baker.residuals))}
        bones=list(baked[tag,all_clips[0]['name']][0][1])
        exported=bedrock(all_clips,{c['name']:baked[tag,c['name']] for c in all_clips},sk,bones)
        (ROOT/f'lingxiao_{tag}.animation.json').write_text(json.dumps(exported,separators=(',',':')),encoding='utf-8',newline='\n')
        print(tag,'已烘焙',len(all_clips),'个动作；够不着警告',len(baker.warnings),flush=True)
    with open(ROOT/'baked_motion.pkl','wb') as f:pickle.dump((all_clips,skeleton_by,baked),f)
    (ROOT/'motion-diagnostics.json').write_text(json.dumps(reports,ensure_ascii=False,indent=2),encoding='utf-8',newline='\n')
    (ROOT/'choreography.json').write_text(json.dumps(all_clips,ensure_ascii=False,indent=2),encoding='utf-8',newline='\n')


if __name__=='__main__':main()
