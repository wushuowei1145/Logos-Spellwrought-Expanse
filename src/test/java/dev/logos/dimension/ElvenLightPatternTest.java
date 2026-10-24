package dev.logos.dimension;

import dev.logos.dimension.elven.ElvenLightPattern;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ElvenLightPatternTest {
    @Test void breathingIsPeriodicSmoothAndNeverBlack(){
        assertEquals(.16,ElvenLightPattern.pulse(0),1e-9);assertEquals(1,ElvenLightPattern.pulse(60),1e-9);
        for(int i=-240;i<240;i++){
            assertEquals(ElvenLightPattern.pulse(i+.37),ElvenLightPattern.pulse(i+120.37),1e-9);
            assertTrue(Math.abs(ElvenLightPattern.pulse(i+.1)-ElvenLightPattern.pulse(i))<.01);
        }
    }
    @Test void spatialNoiseIsStableBoundedAndWorksAcrossNegativeCoordinates(){
        var samples=new java.util.HashSet<Long>();
        for(int x=-16;x<16;x++)for(int z=-16;z<16;z++){
            long n=ElvenLightPattern.hash(x,3,z);assertEquals(n,ElvenLightPattern.hash(x,3,z));samples.add(n);
            for(int shift:new int[]{8,24,40})assertTrue(ElvenLightPattern.jitter(n,shift)>=0&&ElvenLightPattern.jitter(n,shift)<6);
        }
        assertEquals(1024,samples.size());assertEquals(0,ElvenLightPattern.capacity(2));assertTrue(ElvenLightPattern.capacity(1)<ElvenLightPattern.capacity(0));
    }
}
