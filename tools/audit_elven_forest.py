"""审计精灵树模板的拼接、净空、资源引用和静态模型边界。"""
from pathlib import Path
import json
from collections import Counter, deque
from blueprint_nbt import read_nbt

ROOT=Path(__file__).resolve().parent.parent
R=ROOT/'src/main/resources';D=R/'data/logos_dimension';A=R/'assets/logos_dimension'
templates={p.stem:read_nbt(p) for p in (D/'structure/elven').glob('*.nbt')}
pools={p.stem:json.loads(p.read_text(encoding='utf-8')) for p in (D/'worldgen/template_pool/elven').glob('*.json')}
links={};counts=Counter();ports=0;jars=0;elf_entities=0
for name,doc in templates.items():
    assert doc['DataVersion']==4790,name
    for ent in doc.get('entities',[]):
        assert ent.get('nbt',{}).get('id')=='logos_dimension:forest_elf',(name,ent)
        elf_entities+=1
    cells={tuple(b['pos']):b for b in doc['blocks']}
    assert len(cells)==len(doc['blocks']),name
    outgoing=[]
    for pos,b in cells.items():
        assert all(0<=x<limit for x,limit in zip(pos,doc['size'])),(name,pos)
        state=doc['palette'][b['state']];n=state['Name'];counts[n]+=1
        assert n not in {'minecraft:dirt','minecraft:grass_block','minecraft:gravel','minecraft:sand','minecraft:command_block'},(name,n)
        if 'nbt' not in b:continue
        if n=='logos:cultivation_jar':
            tag=b['nbt'];jars+=1
            assert tag.get('id')=='logos:cultivation_jar' and 'seed' in tag and 'plant' in tag,(name,tag)
            seed_id=tag['seed']['stacks'][0]['id']
            assert not any(m in seed_id or m in tag['plant'] for m in ['copper_pod','iron_pod','gold_pod','netherite_pod','copper_seed','iron_seed','gold_seed','netherite_seed']),(name,tag)
            continue
        assert n=='minecraft:jigsaw',(name,n)
        tag=b['nbt'];ports+=1
        if tag['pool']=='logos_dimension:elven/roots':
            assert tag['joint']=='rollable' and tag['target']=='minecraft:bottom'
            assert tag['final_state'].startswith(('logos_dimension:elven_heartwood[','logos_dimension:cloudsea_fir_heart_log['))
        elif tag['pool']!='minecraft:empty':
            target=tag['pool'].split('/')[-1];assert target in pools
            outgoing.append(target)
            child=templates[target]
            assert any(c.get('nbt',{}).get('name')==tag['target'] for c in child['blocks']),(name,target)
        assert tag['final_state']!='minecraft:jigsaw'
    links[name]=outgoing
for name,pool in pools.items():
    for entry in pool['elements']:
        e=entry['element']
        if e['element_type']=='minecraft:feature_pool_element':assert e['feature']=='logos_dimension:elven_roots'
        else:
            assert e['location'].split('/')[-1] in templates
            assert e['processors']=='logos_dimension:elven_preserve' and e['projection']=='rigid'
visited=set();q=deque([('tile_3_0_3',0)]);depth=0
while q:
    name,d=q.popleft();assert name not in visited,('cycle',name)
    visited.add(name);depth=max(depth,d);q.extend((c,d+1) for c in links[name])
assert len(visited)==343 and depth<=14,(len(visited),depth)
# 将树心所在的底部模板还原到全局坐标，校验十格宽主通道和家具位置。
world={};world_states={}
for name in visited:
    if not name.startswith('tile_'):continue
    tile=list(map(int,name.split('_')[1:]));doc=templates[name]
    for b in doc['blocks']:
        p=tuple(b['pos'][i]+tile[i]*32-[112,4,112][i] for i in range(3))
        world[p]=b.get('nbt',{}).get('final_state',doc['palette'][b['state']]['Name']).split('[')[0]
        world_states[p]=doc['palette'][b['state']]
for x in range(-5,6):
    for z in range(-5,6):
        if x*x+z*z>=25:continue
        for y in range(2,10):assert world.get((x,y,z))=='minecraft:air',(x,y,z)
for x in [-7,-6,-5]:
    for y in [2,3]:assert world[x,y,9]=='logos_dimension:elven_bookshelf'
assert world[0,1,0]=='logos_dimension:elven_luminous_inlay'
for p in (A/'models/block').glob('elven_*.json'):
    m=json.loads(p.read_text(encoding='utf-8'))
    for texture in m.get('textures',{}).values():
        if texture.startswith('logos_dimension:'):assert (A/'textures'/(texture.split(':')[1]+'.png')).exists(),(p,texture)
    for element in m.get('elements',[]):
        assert all(-16<=v<=32 for k in ['from','to'] for v in element[k]),p
        spans=[b-a for a,b in zip(element['from'],element['to'])]
        assert all(v>=0 for v in spans) and sum(v>0 for v in spans)>=2,p
        for f in element['faces'].values():assert 'uv' in f and len(f['uv'])==4,(p,'missing UV')
for name in ['elven_world_tree','elven_giant_tree']:
    s=json.loads((D/'worldgen/structure'/f'{name}.json').read_text())
    assert s['type']=='minecraft:jigsaw' and s['terrain_adaptation']=='none'
    assert all(not row['spawns'] for row in s['spawn_overrides'].values())
print(f'ELVEN_AUDIT templates={len(templates)} connected_tiles={len(visited)} depth={depth} ports={ports} models=valid central_clear_diameter=10 entities=0 soil_plinth=0')
manifest=json.loads((D/'elven_forest_manifest.json').read_text(encoding='utf-8'))
interior=manifest['world_tree']['interior']
assert len(interior['floors'])==5
for floor in interior['floors']:
    assert world[7,floor,0] in ['logos_dimension:elven_pale_planks','logos_dimension:elven_luminous_inlay']
    assert world[0,floor,0]=='minecraft:air',('lightwell',floor)
for i,(x,y,z,half) in enumerate(interior['stairs']):
    assert world[x,y,z]=='minecraft:birch_slab',('missing stair',i,x,y,z)
    assert world_states[x,y,z]['Properties']['type']==half,('incorrect slab half',i)
    top=y+(1 if half=='top' else .5)
    assert top==2+i*.5,('stair rise is not half a block',i,top)
    for head in [y+1,y+2]:assert world.get((x,head,z)) in ['minecraft:air','logos_dimension:elven_lamp_vine','logos_dimension:elven_leaf_lamp','logos_dimension:elven_bough_light'],('blocked stair',i,x,head,z,world.get((x,head,z)))
for home in manifest['world_tree']['tree_houses']:
    x,y,z=home['rope']
    for height in range(y,home['rope_top']+1):assert world[x,height,z]=='logos_dimension:elven_climbing_rope',('broken rope',x,height,z)
for sh in manifest['world_tree'].get('suspended_houses',[]):
    for rx0,rz0,r_bot,r_top in sh['ropes']:
        for y_r in range(r_bot,r_top+1):assert world[rx0,y_r,rz0]=='logos_dimension:elven_climbing_rope',('broken suspended rope',rx0,y_r,rz0)
assert jars>=16 and len(manifest['world_tree'].get('cultivation_jars',[]))==jars,('cultivation_jars',jars)
from elven_habitation import _can_connect_horizontal
for (x,y,z),st in world_states.items():
    n=st['Name']
    if '_fence' in n or 'glass_pane' in n:
        is_pane='glass_pane' in n
        props=st.get('Properties',{})
        for side,dx,dz in [('north',0,-1),('south',0,1),('east',1,0),('west',-1,0)]:
            nb=world.get((x+dx,y,z+dz),'minecraft:air')
            expected='true' if _can_connect_horizontal(nb,is_pane=is_pane) else 'false'
            assert props.get(side)==expected,('misconnected fence/pane',(x,y,z),n,side,props.get(side),expected,nb)
for giant in manifest['giants']:
    if not giant['house']:continue
    doc=templates[giant['template']];cells={tuple(b['pos']):doc['palette'][b['state']]['Name'] for b in doc['blocks']}
    cell_states={tuple(b['pos']):doc['palette'][b['state']] for b in doc['blocks']}
    x,y,z=giant['house']['rope']
    for height in range(y,giant['house']['rope_top']+1):assert cells[x,height,z]=='logos_dimension:elven_climbing_rope'
    for (gx,gy,gz),st in cell_states.items():
        gn=st['Name']
        if '_fence' in gn or 'glass_pane' in gn:
            is_pane='glass_pane' in gn
            props=st.get('Properties',{})
            for side,dx,dz in [('north',0,-1),('south',0,1),('east',1,0),('west',-1,0)]:
                nb=cells.get((gx+dx,gy,gz+dz),'minecraft:air')
                expected='true' if _can_connect_horizontal(nb,is_pane=is_pane) else 'false'
                assert props.get(side)==expected,('misconnected giant fence/pane',giant['template'],(gx,gy,gz),gn,side)
for th in manifest['world_tree']['trunk_houses']:
    cx,cy,cz=th['center'];fy=th['floor']
    for dx in range(-2,3):
        for dz in range(-2,3):
            assert world.get((cx+dx,fy,cz+dz)) in ['logos_dimension:elven_pale_planks','minecraft:spruce_planks'],('hole in trunk house floor',th,cx+dx,fy,cz+dz,world.get((cx+dx,fy,cz+dz)))
            assert world.get((cx+dx,fy+1,cz+dz))!='minecraft:birch_slab',('walkway slab inside trunk house',th,cx+dx,fy+1,cz+dz)
# 校验所有高层房间的椅子朝向均正对阅览桌（而非背朝桌子）
face_vec={'north':(0,-1),'south':(0,1),'east':(1,0),'west':(-1,0)}
for (x,y,z),st in world_states.items():
    if st['Name']=='logos_dimension:elven_leaf_seat' and y>5:
        f=st['Properties']['facing'];vx,vz=face_vec[f]
        front1=world.get((x+vx,y,z+vz));front2=world.get((x+vx*2,y,z+vz*2))
        back1=world.get((x-vx,y,z-vz))
        assert 'logos_dimension:elven_reading_table' in (front1,front2) and back1!='logos_dimension:elven_reading_table',('chair facing away from table',(x,y,z),f,front1,front2,back1)
# 校验世界树五层内廊 (Y=17,33,49,65,81) 均能同层无障碍步行直通外圈螺旋栈道 (r>=24)
for fy in interior['floors']:
    q_p=deque([(0,8)]);seen_p={(0,8)};reached_outer=False
    while q_p:
        px,pz=q_p.popleft()
        if (px*px+pz*pz)>=24*24:reached_outer=True;break
        for dx,dz in [(1,0),(-1,0),(0,1),(0,-1)]:
            nx,nz=px+dx,pz+dz
            if (nx,nz) in seen_p or abs(nx)>45 or abs(nz)>45:continue
            fl=world.get((nx,fy,nz),'')
            h1=world.get((nx,fy+1,nz),'minecraft:air');h2=world.get((nx,fy+2,nz),'minecraft:air')
            if fl in ('logos_dimension:elven_pale_planks','logos_dimension:elven_luminous_inlay','minecraft:spruce_planks','minecraft:birch_slab') and h1=='minecraft:air' and h2=='minecraft:air':
                seen_p.add((nx,nz));q_p.append((nx,nz))
    assert reached_outer,('interior floor portal blocked or not connected to outer spiral walkway',fy,len(seen_p))
def _top_y(wx,wy,wz):
    n=world.get((wx,wy,wz),'minecraft:air')
    if n in ('logos_dimension:elven_pale_planks','minecraft:spruce_planks','logos_dimension:elven_luminous_inlay'):return wy+1.0
    if n=='minecraft:birch_slab':
        t=world_states.get((wx,wy,wz),{}).get('Properties',{}).get('type','bottom')
        return wy+(1.0 if t=='top' else 0.5)
    return None
# 校验近卫巨木桥接入口无栅栏阻挡且与平台同高，且能沿跨树长桥无障碍步行超过 22 格
for gh in manifest['world_tree']['tree_houses']:
    bx,by,bz=gh['bridge_port']
    assert by==gh['floor'],('giant bridge height mismatch',gh)
    assert world.get((bx,by+1,bz))=='minecraft:air' and world.get((bx,by+2,bz))=='minecraft:air',('giant bridge port blocked',gh,world.get((bx,by+1,bz)))
    q_g=deque([(bx,by,bz)]);seen_g={(bx,by,bz)};reached_bridge=False
    while q_g:
        px,py,pz=q_g.popleft()
        cur_top=_top_y(px,py,pz)
        if cur_top is None:continue
        if (px-gh['center'][0])**2+(pz-gh['center'][2])**2>=22*22:reached_bridge=True;break
        for dx,dz in [(1,0),(-1,0),(0,1),(0,-1)]:
            nx,nz=px+dx,pz+dz
            for ny in (py-1,py,py+1):
                if (nx,ny,nz) in seen_g:continue
                nt=_top_y(nx,ny,nz)
                if nt is None or abs(nt-cur_top)>0.55:continue
                head_base=int(nt) if nt==int(nt) else int(nt)+1
                if world.get((nx,head_base,nz),'minecraft:air')=='minecraft:air' and world.get((nx,head_base+1,nz),'minecraft:air')=='minecraft:air':
                    seen_g.add((nx,ny,nz));q_g.append((nx,ny,nz))
    assert reached_bridge,('giant tree terrace cannot walk smoothly onto radial/ring bridges',gh,len(seen_g))
# 校验全部 18 座依附木屋与 12 座悬吊木屋从室内可无障碍步入外部栈道网
for group_name,group in [('trunk',manifest['world_tree']['trunk_houses']),('suspended',manifest['world_tree']['suspended_houses'])]:
    for h_idx,h_spec in enumerate(group):
        cx,_,cz=h_spec['center'];fy=h_spec['floor']
        start=(cx,fy,cz);q_h=deque([start]);seen_h={start};escaped=False
        while q_h:
            px,py,pz=q_h.popleft()
            cur_top=_top_y(px,py,pz)
            if cur_top is None:continue
            if (px-cx)**2+(pz-cz)**2>=12*12:escaped=True;break
            for dx,dz in [(1,0),(-1,0),(0,1),(0,-1)]:
                nx,nz=px+dx,pz+dz
                for ny in (py-1,py,py+1):
                    if (nx,ny,nz) in seen_h:continue
                    nt=_top_y(nx,ny,nz)
                    if nt is None or abs(nt-cur_top)>0.55:continue
                    head_base=int(nt) if nt==int(nt) else int(nt)+1
                    if world.get((nx,head_base,nz),'minecraft:air')=='minecraft:air' and world.get((nx,head_base+1,nz),'minecraft:air')=='minecraft:air':
                        seen_h.add((nx,ny,nz));q_h.append((nx,ny,nz))
        assert escaped,(f'{group_name} house #{h_idx} doorway or walkway has >1.0 block height step or obstruction',h_spec,len(seen_h))
weights=pools['giants']['elements'];home_weight=sum(row['weight'] for row in weights if row['element']['location'].endswith('_home'))
assert abs(home_weight/sum(row['weight'] for row in weights)-2/7)<1e-9
print(f'ELVEN_HABITATION floors=5 stairs={len(interior["stairs"])} giant_houses={len(manifest["world_tree"]["tree_houses"])} trunk_houses={len(manifest["world_tree"].get("trunk_houses",[]))} suspended_houses={len(manifest["world_tree"].get("suspended_houses",[]))} cultivation_jars={jars} elf_entities={elf_entities} probability=2/7')
