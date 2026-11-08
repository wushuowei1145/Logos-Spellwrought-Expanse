package dev.logos.dimension;

import com.google.gson.JsonObject;
import dev.logos.dimension.combat.*;
import dev.logos.dimension.lingxiao.FlightRules;
import java.util.*;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 菱形边缘、大体积交集、逐刻消耗、移动上限、剑尖朝向，以及实际资源与招式文件的契约（docs/LINGXIAO.md）。 */
class LingxiaoTest {
    private static Moveset moveset() throws Exception { return Moveset.parse(LongswordMotionTest.read("/data/logos_dimension/combat/movesets/lingxiao.json")); }
    private static JsonObject animations(String view) throws Exception {
        return LongswordMotionTest.read("/assets/logos_dimension/combat/animations/lingxiao_" + view + ".animation.json").getAsJsonObject("animations");
    }
    private static DiamondHitbox shape(){return new DiamondHitbox(Vec3.ZERO,new Vec3(0,0,1),new Vec3(1,0,0),3,1.35,1.5);}
    @Test void diamondWidensAtMiddleAndNarrowsAtBothTips(){
        var s=shape();assertTrue(s.contains(new Vec3(0,0,0)));assertTrue(s.contains(new Vec3(0,0,3)));
        assertTrue(s.contains(new Vec3(1.35,0,1.5)));assertFalse(s.contains(new Vec3(.7,0,.1)));
        assertFalse(s.contains(new Vec3(.7,0,2.9)));assertFalse(s.contains(new Vec3(0,0,-.01)));
    }
    @Test void boundingBoxEdgeCanHitWhenItsCenterIsOutside(){
        var s=shape();var edge=new AABB(1.2,-.2,1.2,1.6,.2,1.8);
        assertFalse(s.contains(edge.getCenter()));assertTrue(s.intersects(edge));
        assertFalse(s.intersects(edge.move(1,0,0)));
    }
    @Test void largeBoxCrossingPrismDoesNotNeedAnInsideCorner(){
        var s=shape();assertTrue(s.intersects(new AABB(-2,-.05,.5,2,.05,2.5)));
        assertFalse(s.intersects(new AABB(-2,2,.5,2,3,2.5)));
    }
    @Test void verticalAimUsesStableFallback(){
        var s=new DiamondHitbox(Vec3.ZERO,new Vec3(0,1,0),new Vec3(1,0,0),3,1.35,1.5);
        assertTrue(s.contains(new Vec3(0,2,0)));assertFalse(s.contains(new Vec3(0,-1,0)));
        assertTrue(s.intersects(new AABB(-.1,2.9,-.1,.1,3.1,.1)));
    }

    /** 扣的是“消耗 + 这一刻的自然回复”：回复照常加回来，二十刻之后面板正好少十点。 */
    @Test void twentyTicksLoseExactlyTenEvenWhileLogosRegenerates(){
        for (double regeneration : new double[]{0, .3, 1.25}) {
            double mana=100;
            for(int i=0;i<20;i++)mana=FlightRules.drain(mana,regeneration)+regeneration;
            assertEquals(90,mana,1e-9,"每刻回复 "+regeneration);
        }
        assertEquals(0,FlightRules.drain(.5,0),1e-9);assertEquals(0,FlightRules.drain(.8,.3),1e-9);
        assertTrue(Double.isNaN(FlightRules.drain(.79,.3)),"不够一刻消耗时不扣、结束飞行");
        assertEquals(.5,FlightRules.drain(1,Double.NaN),1e-9,"回复取不到时按 0 算");
        assertTrue(Double.isNaN(FlightRules.drain(Double.NaN,0)));assertTrue(Double.isNaN(FlightRules.drain(Double.POSITIVE_INFINITY,0)));
    }
    @Test void diagonalAndVerticalInputCannotExceedSpeed(){
        for(int mask=0;mask<32;mask++){
            var input=new Input((mask&1)!=0,(mask&2)!=0,(mask&4)!=0,(mask&8)!=0,(mask&16)!=0,false,false);
            assertTrue(FlightRules.movement(input,new Vec3(0,.6,.8),37).length()<=FlightRules.SPEED+1e-9);
        }
        assertEquals(Vec3.ZERO,FlightRules.movement(Input.EMPTY,new Vec3(0,0,1),0));
        assertTrue(FlightRules.SPEED>=.7-1e-9,"维护者要求加快：至少每秒 14 格");
    }

    /** 剑尖顺着水平飞行方向；往回退时仍朝视线（倒着飞），悬停或只升降时不转。原版偏航：0 朝 +Z，90 朝 -X。 */
    @Test void tipFollowsTheTravelDirection(){
        float speed=(float)FlightRules.SPEED;
        assertEquals(-90,FlightRules.targetYaw(new Vec3(speed,0,0),-90,0),1e-4,"朝东飞，剑尖朝东");
        assertEquals(-90,FlightRules.targetYaw(new Vec3(speed,0,0),0,0),1e-4,"面朝南往左（东）侧飞，剑尖转向东");
        assertEquals(-45,FlightRules.targetYaw(new Vec3(speed,0,speed),0,0),1e-4,"斜飞取合成方向");
        assertEquals(0,FlightRules.targetYaw(new Vec3(0,0,-speed),0,30),1e-4,"往后退时剑尖仍朝视线，不掉头");
        assertEquals(30,FlightRules.targetYaw(new Vec3(0,speed,0),0,30),1e-4,"只升降时保持原朝向");
        assertEquals(30,FlightRules.targetYaw(new Vec3(.01,0,0),0,30),1e-4,"悬停时保持原朝向");
        assertEquals(40,FlightRules.targetYaw(Vec3.ZERO,90,-20),1e-4,"悬停时视线转开太远，剑尖跟到离视线 50° 处");
    }
    @Test void tipPitchesWithClimbAndDiveWithinLimits(){
        float speed=(float)FlightRules.SPEED;
        assertEquals(0,FlightRules.targetPitch(new Vec3(0,0,speed),0),1e-4,"平飞不俯仰");
        float climb=FlightRules.targetPitch(new Vec3(0,.2,speed),0);
        assertTrue(climb<-10&&climb>-FlightRules.MAX_PITCH,"顺着剑尖往上飞，剑尖抬起（原版负值）："+climb);
        assertTrue(FlightRules.targetPitch(new Vec3(0,-.2,speed),0)>10,"往下飞剑尖压低");
        assertEquals(-FlightRules.MAX_PITCH,FlightRules.targetPitch(new Vec3(0,speed,0),0),1e-4,"垂直上升限幅");
        assertTrue(FlightRules.targetPitch(new Vec3(0,.2,-speed),0)>10,"倒着往上飞，剑尾抬起、剑尖压低");
    }

    @Test void clipsMatchTheMovesetInBothViews() throws Exception {
        var set=moveset();
        for(String view:List.of("tp","fp")){
            var warnings=new ArrayList<String>();var clips=CombatClip.parseFile(LongswordMotionTest.read(
                    "/assets/logos_dimension/combat/animations/lingxiao_"+view+".animation.json"),warnings);
            assertTrue(warnings.isEmpty(),warnings.toString());
            var json=animations(view);
            for(var move:set.moves().values()){
                var clip=clips.get(move.clip());
                assertNotNull(clip,view+" 缺少动作 "+move.clip());
                assertEquals(move.length(),clip.length(),1e-6,move.name());
                Map<String,Double> markers=new HashMap<>();
                var timeline=json.getAsJsonObject(move.clip()).getAsJsonObject("timeline");
                if(timeline!=null)for(var e:timeline.entrySet())markers.put(e.getValue().getAsString(),Double.parseDouble(e.getKey()));
                if(move.hasContact())assertEquals(move.contact(),markers.get("contact"),1e-6,move.name()+" 的 contact");
                else assertFalse(markers.containsKey("contact"),move.name()+" 没有伤害却标了 contact");
                for(var t:move.on())if(t.input()==Moveset.Input.ATTACK&&!t.atEnd()){
                    assertEquals(t.at(),markers.get("cancel"),1e-6,move.name()+" 的 cancel");
                    assertTrue(t.at()>=move.lastContact(),move.name()+" 的取消点早于命中，缓冲的下一招会吞掉这一下");
                }
            }
            assertFalse(set.move("flight_idle").hasContact());assertFalse(set.move("flight_idle").accepts(Moveset.Input.ATTACK));
        }
    }

    /** 四式连招按住左键依次接上、回到第一式；每一下的间隔不短于原版铁剑一点五倍攻速的冷却（约 0.42 秒），伤害不被冷却吃掉。 */
    @Test void fourFormsChainWithFullStrengthSpacing() throws Exception {
        var set=moveset();
        var forms=List.of("attack_1","attack_2","attack_3","attack_4");
        double cooldown=1/FlightRules.ATTACK_SPEED;
        for(int i=0;i<forms.size();i++){
            var move=set.move(forms.get(i));
            assertEquals(1,move.damage(),1e-9);assertFalse(move.fullStrength());
            assertEquals(FlightRules.ATTACK_SPEED,move.speedBase(),1e-6,"动作按实际攻速编排，原速播放");
            var next=set.move(forms.get((i+1)%forms.size()));
            assertEquals(next.name(),move.comboNext());
            var chain=move.on().stream().filter(t->t.input()==Moveset.Input.ATTACK).findFirst().orElseThrow();
            assertEquals(next.name(),chain.to());assertTrue(chain.queue());
            double gap=chain.at()-move.contact()+next.contact();
            assertTrue(gap>=cooldown-.03,move.name()+" → "+next.name()+" 两下只隔 "+gap+" 秒");
        }
    }

    /** 起飞动作在 handoff 标记那一刻把剑交给载具：之后骨架里的剑缩成 0（不画第二把），巡航时一直是 0；没有离线舞台的根位移。 */
    @Test void flightHandsTheSwordToTheVehicle() throws Exception {
        for(String view:List.of("tp","fp")){
            var data=animations(view);
            var takeoff=data.getAsJsonObject("flight_takeoff");
            double handoff=-1;
            for(var e:takeoff.getAsJsonObject("timeline").entrySet())if(e.getValue().getAsString().equals("handoff"))handoff=Double.parseDouble(e.getKey());
            assertTrue(handoff>0&&handoff<takeoff.get("animation_length").getAsDouble(),view+" 起飞缺少 handoff 标记");
            for(var e:takeoff.getAsJsonObject("bones").getAsJsonObject("Weapon").getAsJsonObject("scale").entrySet()){
                double t=Double.parseDouble(e.getKey()),s=e.getValue().getAsJsonArray().get(0).getAsDouble();
                if(t>=handoff-1e-6)assertEquals(0,s,1e-9,view+" 交接后 "+t+" 秒骨架里的剑还在");
                else assertTrue(s>=1-1e-9,view+" 交接前剑不应缩小");
            }
            for(var e:data.getAsJsonObject("flight_idle").getAsJsonObject("bones").getAsJsonObject("Weapon").getAsJsonObject("scale").entrySet())
                assertEquals(0,e.getValue().getAsJsonArray().get(0).getAsDouble(),1e-9,view+" 巡航时骨架里不画剑");
            for(String clip:List.of("flight_takeoff","flight_idle","flight_land")){
                var root=data.getAsJsonObject(clip).getAsJsonObject("bones").getAsJsonObject("Root");
                var p=root.has("position")?root.getAsJsonObject("position").entrySet().iterator().next().getValue().getAsJsonArray():null;
                if(view.equals("tp"))assertNull(p,clip+" 第三人称不该有根位移");
                else{assertEquals(-15.13,p.get(0).getAsDouble(),1e-6);assertEquals(-1.82,p.get(1).getAsDouble(),1e-6);}
            }
        }
    }

    @Test void tipIsOneRotatedCubeAndItemCoordinatesStillMatchMesh() throws Exception {
        var mesh=LongswordMotionTest.read("/assets/logos_dimension/combat/weapons/lingxiao_sword.json");
        var model=LongswordMotionTest.read("/assets/logos_dimension/models/item/lingxiao_sword.json");
        assertFalse(mesh.has("first_person_scale"),"第一人称按骨架的 weapon_scale 烘焙和渲染，不另乘");
        var byName=new HashMap<String,com.google.gson.JsonObject>();
        for(var e:model.getAsJsonArray("elements"))byName.put(e.getAsJsonObject().get("name").getAsString(),e.getAsJsonObject());
        int tip=0;
        for(var e:mesh.getAsJsonArray("cubes")){
            var c=e.getAsJsonObject();String name=c.get("name").getAsString();
            if(name.contains("tip")){tip++;assertEquals("slanted_single_tip",name);assertEquals(45,c.getAsJsonArray("rotation").get(2).getAsDouble());}
            if(!c.get("part").getAsString().equals("weapon"))continue;
            var element=byName.get(name);assertNotNull(element);
            double[] offset={8,1,8};
            for(int i=0;i<3;i++)assertEquals(c.getAsJsonArray("start").get(i).getAsDouble()+offset[i],element.getAsJsonArray("from").get(i).getAsDouble(),.0001);
        }
        assertEquals(1,tip);assertEquals(58,mesh.getAsJsonArray("cubes").size());
    }
}
