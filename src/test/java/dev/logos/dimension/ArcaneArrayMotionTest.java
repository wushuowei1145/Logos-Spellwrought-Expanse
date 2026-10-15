package dev.logos.dimension;

import dev.logos.dimension.client.ArcaneArrayMotion;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ArcaneArrayMotionTest {
    @Test void transformsWithinAPlayersShortObservation() {
        var start=ArcaneArrayMotion.sample(0);
        var later=ArcaneArrayMotion.sample(120);
        assertTrue(later.fracture()>.5, "The array must visibly unfold within six seconds");
        assertTrue(Math.abs(later.outerAngle()-start.outerAngle())>=12);
        assertTrue(later.orbitAngle()<0 && later.outerAngle()>0);
    }
    @Test void geometryStaysBoundedAndClockMotionIsSmooth() {
        for(int t=-1440;t<2400;t++) {
            var f=ArcaneArrayMotion.sample(t);
            assertTrue(f.unfold()>=.75 && f.unfold()<=1.01);
            assertTrue(f.fracture()>=0 && f.fracture()<=1);
            assertTrue(f.glow()>=.7 && f.glow()<=1.01);
            var n=ArcaneArrayMotion.sample(t+.01);
            assertTrue(Math.abs(f.fracture()-n.fracture())<.002);
            assertTrue(Math.abs(f.unfold()-n.unfold())<.002);
        }
    }
    @Test void starTopologyCrossfadesWithoutAThresholdJump(){
        int intermediate=0;
        for(double t=0;t<720;t+=.05){
            float a=ArcaneArrayMotion.starBlend(ArcaneArrayMotion.sample(t));
            float b=ArcaneArrayMotion.starBlend(ArcaneArrayMotion.sample(t+.05));
            assertTrue(a>=0&&a<=1);
            assertTrue(Math.abs(a-b)<.003,"No single-frame polygon replacement");
            if(a>.05&&a<.95)intermediate++;
        }
        assertTrue(intermediate>1000,"Transition must last long enough to see");
    }
    @Test void glintsAndWavesFadeBeforeTheirPositionsWrap(){
        for(float p:new float[]{0,1}){
            assertEquals(0,ArcaneArrayMotion.travelOpacity(p),.00001);
            assertEquals(0,ArcaneArrayMotion.waveOpacity(p),.00001);
        }
        assertEquals(1,ArcaneArrayMotion.travelOpacity(.5f),.00001);
        assertTrue(ArcaneArrayMotion.travelOpacity(.001f)<.00001);
        assertTrue(ArcaneArrayMotion.waveOpacity(.001f)<.00001);
        for(int t=-1000;t<1000;t++){
            float p=ArcaneArrayMotion.progress(t,85,.375);
            assertTrue(p>=0&&p<1);assertTrue(ArcaneArrayMotion.travelOpacity(p)>=0);
        }
    }
}
