package dev.logos.dimension;

import dev.logos.dimension.cloudsea.*;
import org.junit.jupiter.api.Test;
import java.util.EnumSet;
import static org.junit.jupiter.api.Assertions.*;

class CloudseaTerrainTest {
    @Test void all24RegionsAreReachableIncludingNegativeCoordinates(){var t=new CloudseaTerrain(812);var seen=EnumSet.noneOf(CloudseaBiome.class);for(int z=-8;z<=8;z++)for(int x=-8;x<=8;x++){var r=t.region(x,z);assertEquals(r,t.nearest(r.x(),r.z()));seen.add(t.column(r.x(),r.z()).biome());}assertEquals(24,seen.size());}
    @Test void worldSeedIsDeterministicAndChangesTerrain(){var a=new CloudseaTerrain(3);var b=new CloudseaTerrain(4);assertEquals(a.column(-2293,941),new CloudseaTerrain(3).column(-2293,941));assertNotEquals(a.region(-2,3),b.region(-2,3));}
    @Test void groundAndSuspendedLayersFitTheDimension(){var t=new CloudseaTerrain(913);int sheets=0;for(int z=-9000;z<9000;z+=97)for(int x=-9000;x<9000;x+=97){var c=t.column(x,z);assertTrue(c.ground()>=110&&c.highest()<CloudseaTerrain.MIN_Y+CloudseaTerrain.HEIGHT-30);if(c.upperTop()>=c.upperBottom())sheets++;}assertTrue(sheets>100);}
    @Test void cavesKeepBedrockAndSurfaceIntact(){var t=new CloudseaTerrain(51);int caves=0;for(int x=0;x<512;x+=8){var c=t.column(x,79);assertFalse(t.cave(x,-126,79,c));assertFalse(t.cave(x,c.ground()-3,79,c));for(int y=-116;y<100;y+=4)if(t.cave(x,y,79,c))caves++;}assertTrue(caves>100);}
    @Test void shapesUseEightFamiliesAndDistinctSurfacePalettes(){var forms=EnumSet.noneOf(CloudseaBiome.Form.class);var colors=new java.util.HashSet<Integer>();for(var b:CloudseaBiome.values()){forms.add(b.form);colors.add(b.groundColor);}assertEquals(8,forms.size());assertEquals(24,colors.size());}
}
