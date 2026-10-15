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
}
