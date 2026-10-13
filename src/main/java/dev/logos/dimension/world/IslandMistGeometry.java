package dev.logos.dimension.world;

/** A soft sided volume beneath the island, excluding both the plateau and underground rock. */
public final class IslandMistGeometry {
    public static float density(AlchemicalTerrain terrain, int x, double y, int z) {
        var c = terrain.column(x, z);
        if (!c.hasIsland() || y < c.ground() - 2 || y >= c.islandBottom() - 6) return 0;
        double radial = AlchemicalLayout.smooth(Math.min(1, (terrain.layout().islandRadius() - c.geography().distance()) / 90));
        double roof = AlchemicalLayout.smooth(Math.min(1, (c.islandBottom() - 6 - y) / 14));
        return (float) (radial * roof);
    }
    private IslandMistGeometry() {}
}
