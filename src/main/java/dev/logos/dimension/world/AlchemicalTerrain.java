package dev.logos.dimension.world;

import dev.logos.dimension.world.AlchemicalLayout.Region;

/** Column geometry keeps the island's reserved top separate from the living ground beneath it. */
public final class AlchemicalTerrain {
    public static final int MIN_Y = -64, HEIGHT = 512, SEA_LEVEL = 86;
    public enum Liquid { NONE, WATER, MERCURY, MOLTEN_GLASS }
    public record Column(AlchemicalLayout.Sample geography, int ground, int liquidTop, Liquid liquid,
                         int islandBottom, int islandTop, int metalBottom, int metalTop) {
        public boolean hasIsland() { return islandTop >= islandBottom; }
        public boolean hasMetalIsland() { return metalTop >= metalBottom; }
    }
    private final AlchemicalLayout layout;

    public AlchemicalTerrain(AlchemicalLayout layout) { this.layout = layout; }
    public AlchemicalLayout layout() { return layout; }

    public Column column(int x, int z) {
        var s = layout.sample(x, z);
        long seed = layout.seed();
        double continent = layout.fbm(x / 980.0, z / 980.0, seed + 31);
        double detail = layout.fbm(x / 140.0, z / 140.0, seed + 79);
        double ridge = 1 - Math.abs(layout.noise(x / 450.0, z / 450.0, seed + 91));
        double river = Math.abs(layout.noise(x / 620.0, z / 620.0, seed + 109));
        int ground, liquidTop = Integer.MIN_VALUE;
        Liquid liquid = Liquid.NONE;
        switch (s.region()) {
            case SULFUR -> {
                double fissure = Math.abs(layout.noise(x / 230.0, z / 230.0, seed + 141));
                ground = terrace(116 + continent * 32 + ridge * 52 + detail * 9, 4);
                if (fissure < .095) {
                    ground = (int) (58 + fissure * 380);
                    if (ground < 74) { liquidTop = 74; liquid = Liquid.MOLTEN_GLASS; }
                }
            }
            case MERCURY -> {
                ground = terrace(103 + continent * 28 + detail * 6, 3);
                if (ground < 105) { liquidTop = 105; liquid = Liquid.MERCURY; }
            }
            case SALT -> ground = terrace(111 + continent * 22 + ridge * 23 + detail * 5, 4);
            default -> {
                ground = terrace(121 + continent * 48 + ridge * 52 + detail * 8, 4);
                if (river < .07) {
                    ground = (int) AlchemicalLayout.lerp(79, ground, AlchemicalLayout.smooth(Math.min(1, river / .07)));
                    if (ground < SEA_LEVEL) { liquidTop = SEA_LEVEL; liquid = Liquid.WATER; }
                }
            }
        }
        int bottom = 1, top = 0;
        double islandR = layout.islandRadius();
        if (s.distance() < islandR) {
            double edge = Math.min(1, (islandR - s.distance()) / (islandR * .16));
            int plateau = 337 + (int) (AlchemicalLayout.unit(s.center().salt()) * 8);
            top = plateau - (int) ((1 - edge) * 10);
            bottom = top - (int) (20 + Math.sqrt(edge) * 66 + layout.noise(x / 90.0, z / 90.0, seed + 167) * 8);
        }
        int metalBottom = 1, metalTop = 0;
        if (s.region() == Region.MERCURY) {
            int gridX = Math.floorDiv(x, 240), gridZ = Math.floorDiv(z, 240);
            long salt = AlchemicalLayout.hash(gridX, gridZ, seed + 203);
            double d = Math.hypot(Math.floorMod(x, 240) - 120, Math.floorMod(z, 240) - 120);
            double r = 20 + (AlchemicalLayout.unit(salt) + 1) * 15;
            if (AlchemicalLayout.unit(salt) > .45 && d < r) {
                metalTop = 156 + (int) (AlchemicalLayout.unit(salt) * 12);
                metalBottom = metalTop - 4 - (int) (Math.sqrt(1 - d / r) * 13);
            }
        }
        return new Column(s, ground, liquidTop, liquid, bottom, top, metalBottom, metalTop);
    }

    private static int terrace(double value, int step) {
        double q = value / step;
        return (int) (Math.floor(q) * step + AlchemicalLayout.smooth(Math.min(1, (q - Math.floor(q)) * 2)) * step);
    }
}
