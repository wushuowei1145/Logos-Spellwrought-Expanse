package dev.logos.dimension.world;

/** Legacy Boss-state key and UUID seed mixer; no terrain or biome generation. */
public final class AlchemicalLayout {
    public enum Region { AETHER, SULFUR, MERCURY, SALT }
    public record Center(long gridX, long gridZ, int x, int z, long salt) {}
    public static long mix(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }
    private AlchemicalLayout() {}
}
