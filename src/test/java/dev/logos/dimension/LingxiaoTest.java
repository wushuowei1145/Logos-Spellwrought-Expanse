package dev.logos.dimension;

import dev.logos.dimension.combat.*;
import dev.logos.dimension.lingxiao.FlightRules;
import java.util.*;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 菱形边缘、大体积交集、逐刻消耗、移动上限，以及实际导入资源和招式的契约。 */
class LingxiaoTest {
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
    @Test void twentyTicksCostExactlyTenWithoutFreeRestartWindow(){
        double mana=100;
        for(int i=0;i<20;i++)mana=FlightRules.drain(mana);
        assertEquals(90,mana,1e-9);
        assertEquals(0,FlightRules.drain(.5),1e-9);assertTrue(Double.isNaN(FlightRules.drain(.49)));
        assertTrue(Double.isNaN(FlightRules.drain(Double.NaN)));assertTrue(Double.isNaN(FlightRules.drain(Double.POSITIVE_INFINITY)));
    }
    @Test void diagonalAndVerticalInputCannotExceedSpeed(){
        for(int mask=0;mask<32;mask++){
            var input=new Input((mask&1)!=0,(mask&2)!=0,(mask&4)!=0,(mask&8)!=0,(mask&16)!=0,false,false);
            assertTrue(FlightRules.movement(input,new Vec3(0,.6,.8),37).length()<=FlightRules.SPEED+1e-9);
        }
        assertEquals(Vec3.ZERO,FlightRules.movement(Input.EMPTY,new Vec3(0,0,1),0));
    }
    @Test void movesAndContactsMatchBothViewsAndUseNormalDamage() throws Exception {
        var set=Moveset.parse(LongswordMotionTest.read("/data/logos_dimension/combat/movesets/lingxiao.json"));
        for(String view:List.of("tp","fp")){
            var warnings=new ArrayList<String>();var clips=CombatClip.parseFile(LongswordMotionTest.read(
                    "/assets/logos_dimension/combat/animations/lingxiao_"+view+".animation.json"),warnings);
            assertTrue(warnings.isEmpty(),warnings.toString());
            for(var move:set.moves().values()){
                assertEquals(move.length(),clips.get(move.clip()).length(),1e-6,move.name());
                if(move.name().startsWith("attack_")){
                    assertEquals(1,move.damage(),1e-9);assertFalse(move.fullStrength());assertEquals(1.6,move.speedBase(),1e-9);
                }
            }
            assertFalse(set.move("flight_idle").hasContact());assertFalse(set.move("flight_idle").accepts(Moveset.Input.ATTACK));
        }
    }
    @Test void tipIsOneRotatedCubeAndItemCoordinatesStillMatchMesh() throws Exception {
        var mesh=LongswordMotionTest.read("/assets/logos_dimension/combat/weapons/lingxiao_sword.json");
        var model=LongswordMotionTest.read("/assets/logos_dimension/models/item/lingxiao_sword.json");
        assertEquals(.58/.8,mesh.get("first_person_scale").getAsDouble(),1e-9);
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
    @Test void runtimeFlightDoesNotApplyOfflineStageTranslation() throws Exception {
        for(String view:List.of("tp","fp")){
            var data=LongswordMotionTest.read("/assets/logos_dimension/combat/animations/lingxiao_"+view+".animation.json").getAsJsonObject("animations");
            for(String clip:List.of("flight_takeoff","flight_idle","flight_land")){
                var root=data.getAsJsonObject(clip).getAsJsonObject("bones").getAsJsonObject("Root");
                var p=root.getAsJsonArray("position");assertEquals(view.equals("tp")?0:-1.8,p.get(1).getAsDouble(),1e-9);
                assertEquals(0,p.get(2).getAsDouble(),1e-9);
            }
        }
    }
}
