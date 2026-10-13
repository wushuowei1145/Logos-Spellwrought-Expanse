package dev.logos.dimension.world;

/** Seeded, repeatable geography shared by terrain and biome selection, including negative coordinates. */
public final class AlchemicalLayout {
    public static final int DEFAULT_SPACING = 20_000;
    public enum Region { AETHER, SULFUR, MERCURY, SALT }
    public record Center(long gridX, long gridZ, int x, int z, long salt) {}
    public record Sample(Center center, Region region, double distance, double radius) {}

    private final long seed;
    private final int spacing;

    public AlchemicalLayout(long seed, int spacing) {
        if (spacing < 512 || spacing > 200_000) throw new IllegalArgumentException("Invalid pattern spacing");
        this.seed = seed;
        this.spacing = spacing;
    }

    public long seed() { return seed; }
    public int spacing() { return spacing; }
    public double islandRadius() { return spacing * 0.06; }

    public Center center(long gx, long gz) {
        long salt = hash(gx, gz, seed);
        int x = (int) (gx * spacing);
        int z = (int) (gz * spacing);
        // The first island is addressable at the origin; the other centers have stable offsets.
        if (gx != 0 || gz != 0) {
            x += (int) (unit(salt) * spacing * 0.06);
            z += (int) (unit(mix(salt)) * spacing * 0.06);
        }
        return new Center(gx, gz, x, z, salt);
    }

    public Center nearest(int x, int z) {
        long gx = Math.floorDiv(x + spacing / 2, spacing);
        long gz = Math.floorDiv(z + spacing / 2, spacing);
        Center best = null;
        double distance = Double.POSITIVE_INFINITY;
        for (long dz = -1; dz <= 1; dz++) for (long dx = -1; dx <= 1; dx++) {
            Center c = center(gx + dx, gz + dz);
            double d = squared(x - (double) c.x, z - (double) c.z);
            if (d < distance) { distance = d; best = c; }
        }
        return best;
    }

    public Sample sample(int x, int z) {
        Center c = nearest(x, z);
        double dx = x - (double) c.x, dz = z - (double) c.z;
        double distance = Math.hypot(dx, dz);
        double radius = distance / spacing;
        double angle = Math.atan2(dz, dx);
        double warp = noise(x / 1100.0, z / 1100.0, c.salt) * 0.009;
        double r = radius + warp;
        boolean corridor = distance > islandRadius() * 1.05
                && Math.abs(Math.sin(angle * 3 + unit(c.salt) * 3 + noise(x / 1900.0, z / 1900.0, seed) * .65)) < .16;
        Region region = Region.AETHER;
        if (!corridor) {
            if (r < .14) region = Region.SULFUR;
            else if (r > .19 && r < .27) region = Region.MERCURY;
            else if (r > .33 && r < .42) region = Region.SALT;
        }
        return new Sample(c, region, distance, radius);
    }

    public double noise(double x, double z, long salt) {
        long ix = (long) Math.floor(x), iz = (long) Math.floor(z);
        double tx = smooth(x - ix), tz = smooth(z - iz);
        return lerp(lerp(unit(hash(ix, iz, salt)), unit(hash(ix + 1, iz, salt)), tx),
                lerp(unit(hash(ix, iz + 1, salt)), unit(hash(ix + 1, iz + 1, salt)), tx), tz);
    }

    public double fbm(double x, double z, long salt) {
        return noise(x, z, salt) * .65 + noise(x * 2, z * 2, salt + 19) * .25
                + noise(x * 4, z * 4, salt + 43) * .10;
    }

    public static long hash(long x, long z, long seed) { return mix(seed ^ mix(x * 0x9e3779b97f4a7c15L) ^ mix(z * 0xc2b2ae3d27d4eb4fL)); }
    public static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }
    public static double unit(long value) { return ((value >>> 11) * 0x1.0p-53) * 2 - 1; }
    public static double squared(double x, double z) { return x * x + z * z; }
    public static double smooth(double t) { return t * t * (3 - 2 * t); }
    public static double lerp(double a, double b, double t) { return a + (b - a) * t; }
}
