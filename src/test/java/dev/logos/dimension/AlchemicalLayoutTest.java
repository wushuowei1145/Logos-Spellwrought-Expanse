package dev.logos.dimension;

import dev.logos.dimension.world.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Quantitative geographic checks catch seam, seed and biome-share errors before world generation. */
class AlchemicalLayoutTest {
    @Test void aetherIsTheMajorityAndAllRegionsExist() {
        var layout = new AlchemicalLayout(7193, 20_000);
        int[] counts = new int[4]; int total = 0;
        for (int z = -10_000; z < 10_000; z += 100) for (int x = -10_000; x < 10_000; x += 100) {
            counts[layout.sample(x, z).region().ordinal()]++; total++;
        }
        assertTrue(counts[0] > total * .55, "Aether must dominate");
        for (int count : counts) assertTrue(count > total * .035, "A region vanished");
    }
    @Test void ringsHaveTheRequiredOrder() {
        var layout = new AlchemicalLayout(49, 20_000);
        int[] count = new int[3];
        for (int angle = 0; angle < 100; angle++) {
            double a = angle * Math.PI / 50;
            int[] radii = {1500, 4600, 7500};
            var expected = new AlchemicalLayout.Region[]{AlchemicalLayout.Region.SULFUR, AlchemicalLayout.Region.MERCURY, AlchemicalLayout.Region.SALT};
            for (int i = 0; i < 3; i++) if (layout.sample((int) (radii[i] * Math.cos(a)), (int) (radii[i] * Math.sin(a))).region() == expected[i]) count[i]++;
        }
        for (int c : count) assertTrue(c > 75, "Ring ordering distorted");
    }
    @Test void oneIslandRemainsAtOriginAcrossAllCoordinates() {
        var layout = new AlchemicalLayout(732, 20_000);
        var origin = layout.center(0, 0);
        var terrain = new AlchemicalTerrain(layout);
        for (int z = -4; z <= 4; z++) for (int x = -4; x <= 4; x++) {
            assertEquals(origin, layout.center(x, z));
            assertEquals(origin, layout.nearest(x * 20_000, z * 20_000));
            assertEquals(x == 0 && z == 0, terrain.column(x * 20_000, z * 20_000).hasIsland());
        }
    }
    @Test void finiteBoundsAreExactlyTwentyThousandBlocks() {
        var layout = new AlchemicalLayout(732, 20_000);
        assertTrue(layout.contains(-10_000, -10_000));
        assertTrue(layout.contains(9_999, 9_999));
        for (int outside : new int[]{-10_001, 10_000, -20_000, 20_000, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            assertFalse(layout.contains(outside, 0));
            assertFalse(layout.contains(0, outside));
        }
    }
    @Test void entryRingIsOuterAetherInsideTheBoundary() {
        for (long seed : new long[]{0, 1, 49, 7193, -34903}) {
            var layout = new AlchemicalLayout(seed, 20_000);
            for (int angle = 0; angle < 32; angle++) {
                double a = angle * Math.PI / 16;
                int x = (int) (layout.spacing() * .465 * Math.cos(a));
                int z = (int) (layout.spacing() * .465 * Math.sin(a));
                assertTrue(layout.contains(x, z));
                assertEquals(AlchemicalLayout.Region.AETHER, layout.sample(x, z).region());
            }
        }
    }
    @Test void seedChangesTerrainWithoutBreakingCenters() {
        var a = new AlchemicalLayout(1, 20_000); var b = new AlchemicalLayout(2, 20_000);
        assertNotEquals(a.center(0, 0).salt(), b.center(0, 0).salt());
        assertEquals(0, a.center(0, 0).x()); assertEquals(0, b.center(0, 0).z());
        assertEquals(new AlchemicalTerrain(a).column(7743, -5432), new AlchemicalTerrain(new AlchemicalLayout(1, 20_000)).column(7743, -5432));
    }
    @Test void centralPlatformIsFlatWithARealAirGap() {
        var terrain = new AlchemicalTerrain(new AlchemicalLayout(93, 20_000));
        var middle = terrain.column(0, 0);
        for (int x = -900; x <= 900; x += 100) {
            var c = terrain.column(x, 0);
            assertEquals(middle.islandTop(), c.islandTop());
            assertTrue(c.islandBottom() - c.ground() > 35, "Island touches ground");
        }
        assertFalse(terrain.column(1500, 0).hasIsland());
    }
    @Test void allTerrainFitsTheDimension() {
        var terrain = new AlchemicalTerrain(new AlchemicalLayout(393, 20_000));
        for (int z = -10_000; z < 10_000; z += 500) for (int x = -10_000; x < 10_000; x += 500) {
            var c = terrain.column(x, z);
            assertTrue(c.ground() > 0 && c.ground() < 240);
            if (c.hasIsland()) assertTrue(c.islandBottom() > c.ground() && c.islandTop() < 400);
        }
    }
    @Test void cycleHandlesWrapAndNegativeTimes() {
        assertEquals(OpusPhase.NIGREDO, OpusPhase.at(0)); assertEquals(OpusPhase.ALBEDO, OpusPhase.at(24_000));
        assertEquals(OpusPhase.CITRINITAS, OpusPhase.at(48_000)); assertEquals(OpusPhase.RUBEDO, OpusPhase.at(72_000));
        assertEquals(OpusPhase.NIGREDO, OpusPhase.at(96_000)); assertEquals(OpusPhase.RUBEDO, OpusPhase.at(-1));
    }
    @Test void hauntedVolumeExcludesThePlateauAndUnderground() {
        var terrain = new AlchemicalTerrain(new AlchemicalLayout(93,20_000));
        var c = terrain.column(0,0);
        assertTrue(dev.logos.dimension.world.IslandMistGeometry.density(terrain,0,c.ground()+2,0) > .9);
        assertEquals(0,dev.logos.dimension.world.IslandMistGeometry.density(terrain,0,c.islandTop()+1,0));
        assertEquals(0,dev.logos.dimension.world.IslandMistGeometry.density(terrain,0,c.ground()-10,0));
        assertEquals(0,dev.logos.dimension.world.IslandMistGeometry.density(terrain,1500,200,0));
    }
}
