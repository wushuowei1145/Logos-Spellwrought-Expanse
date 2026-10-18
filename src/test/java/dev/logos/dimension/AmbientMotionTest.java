package dev.logos.dimension;

import dev.logos.dimension.client.AmbientMotion;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AmbientMotionTest {
    @Test void firefliesBelongToWarmDryNights(){
        assertTrue(AmbientMotion.fireflies(1,18000,true,false));
        assertFalse(AmbientMotion.fireflies(3,18000,true,false));
        assertFalse(AmbientMotion.fireflies(1,6000,true,false));
        assertFalse(AmbientMotion.fireflies(1,18000,false,false));
        assertFalse(AmbientMotion.fireflies(1,18000,true,true));
    }
    @Test void duskDawnAndMidnightAreContinuous(){
        for(long t=-24000;t<48000;t++){
            assertTrue(AmbientMotion.night(t)>=0&&AmbientMotion.night(t)<=1);
            assertTrue(Math.abs(AmbientMotion.night(t+1)-AmbientMotion.night(t))<.002);
        }
        assertEquals(0,AmbientMotion.night(6000));assertEquals(1,AmbientMotion.night(18000));
    }
    @Test void flightStaysLocalAndFadesWithoutPopping(){
        for(var kind:AmbientMotion.Kind.values())for(int lifetime:new int[]{36,140,240})for(double phase:new double[]{0,1,4,6}){
            assertEquals(0,AmbientMotion.sample(kind,0,lifetime,phase).alpha());
            assertEquals(0,AmbientMotion.sample(kind,lifetime,lifetime,phase).alpha());
            for(double t=0;t<lifetime;t+=.25){
                var a=AmbientMotion.sample(kind,t,lifetime,phase);var b=AmbientMotion.sample(kind,t+.01,lifetime,phase);
                assertTrue(Math.abs(a.x())<1&&Math.abs(a.z())<1&&Math.abs(a.y())<2.2);
                assertTrue(a.alpha()>=0&&a.alpha()<=1);
                assertTrue(Math.abs(a.x()-b.x())<.002&&Math.abs(a.y()-b.y())<.002&&Math.abs(a.z()-b.z())<.002);
                assertTrue(Math.abs(a.alpha()-b.alpha())<.01);
            }
        }
    }
}
