package dev.logos.dimension;

import dev.logos.dimension.cloudsea.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CloudseaTerrainTest {
    @Test void climateProducesVariedTemperateEcosystems(){
        var t=new CloudseaTerrain(812);var seen=EnumSet.noneOf(CloudseaBiome.class);
        for(int z=-14500;z<=10000;z+=113)for(int x=-18000;x<18000;x+=127)seen.add(t.column(x,z).biome());
        assertTrue(seen.size()>=20,seen.toString());
    }
    @Test void worldSeedIsDeterministicAndChangesTerrain(){
        var a=new CloudseaTerrain(3);var b=new CloudseaTerrain(4);
        assertEquals(a.column(-2293,941),new CloudseaTerrain(3).column(-2293,941));assertNotEquals(a.column(-2293,941),b.column(-2293,941));
    }
    @Test void northernRangeAndSouthernSeaHaveGeographicOrder(){
        var t=new CloudseaTerrain(913);int high=0,sea=0,plains=0;
        for(int x=-10000;x<10000;x+=211){
            if(t.column(x,-6500).ground()>200)high++;
            if(t.column(x,10000).ocean())sea++;
            if(t.column(x,1800).ground()<150)plains++;
        }
        assertTrue(high>25);assertTrue(sea>80);assertTrue(plains>80);
        for(int z=-12000;z<12000;z+=97)for(int x=-12000;x<12000;x+=149){var c=t.column(x,z);assertFalse(c.sheet(c.ground()+100));assertTrue(c.ground()>=12&&c.highest()<400);}
    }
    @Test void watershedIsConnectedAndGroundHasNoBiomeSeams(){
        var t=new CloudseaTerrain(31);
        for(int z=-3000;z<5000;z+=37){
            int x=(int)t.riverX(0,z);var c=t.column(x,z);assertTrue(c.water()>c.ground());
            assertTrue(Math.abs(t.riverX(0,z+1)-t.riverX(0,z))<1.5);
        }
        int jumps=0;for(int x=-3000;x<3000;x++){if(Math.abs(t.column(x,1000).ground()-t.column(x+1,1000).ground())>2)jumps++;}assertEquals(0,jumps);
    }
    @Test void cavesKeepBedrockAndSurfaceIntact(){
        var t=new CloudseaTerrain(51);int caves=0;
        for(int x=0;x<512;x+=8){var c=t.column(x,79);assertFalse(t.cave(x,-126,79,c));assertFalse(t.cave(x,c.ground()-3,79,c));for(int y=-116;y<70;y+=4)if(t.cave(x,y,79,c))caves++;}assertTrue(caves>100);
    }
    @Test void seasonClockHandlesNegativeAndYearWrap(){
        assertEquals(0,JourneySeasons.at(0));assertEquals(1,JourneySeasons.at(192000));assertEquals(3,JourneySeasons.at(-1));assertEquals(0,JourneySeasons.at(768000));
    }
}
