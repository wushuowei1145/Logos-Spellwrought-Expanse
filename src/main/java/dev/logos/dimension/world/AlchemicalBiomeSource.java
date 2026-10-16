package dev.logos.dimension.world;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.mojang.serialization.Codec;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

/** The same seeded geography selects real biomes and material bands without biome/terrain disagreement. */
public final class AlchemicalBiomeSource extends BiomeSource {
    public static final MapCodec<AlchemicalBiomeSource> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Biome.CODEC.fieldOf("aether").forGetter(s -> s.aether),
            Biome.CODEC.fieldOf("sulfur").forGetter(s -> s.sulfur),
            Biome.CODEC.fieldOf("mercury").forGetter(s -> s.mercury),
            Biome.CODEC.fieldOf("salt").forGetter(s -> s.salt),
            Biome.CODEC.fieldOf("sanctum").forGetter(s -> s.sanctum),
            Codec.intRange(512, 200_000).optionalFieldOf("pattern_spacing", AlchemicalLayout.DEFAULT_SPACING).forGetter(s -> s.spacing)
    ).apply(i, AlchemicalBiomeSource::new));
    private final Holder<Biome> aether, sulfur, mercury, salt, sanctum;
    private final int spacing;
    private volatile AlchemicalLayout layout;

    public AlchemicalBiomeSource(Holder<Biome> aether, Holder<Biome> sulfur, Holder<Biome> mercury, Holder<Biome> salt, Holder<Biome> sanctum, int spacing) {
        this.aether = aether; this.sulfur = sulfur; this.mercury = mercury; this.salt = salt;
        this.sanctum = sanctum; this.spacing = spacing; this.layout = new AlchemicalLayout(0, spacing);
    }
    public void bindSeed(long seed) { layout = new AlchemicalLayout(seed, spacing); }
    public AlchemicalLayout layout() { return layout; }
    /** Instance generators must not share mutable seed bindings with the template or other cauldrons. */
    public AlchemicalBiomeSource copyForInstance() {
        var copy = new AlchemicalBiomeSource(aether, sulfur, mercury, salt, sanctum, AlchemicalLayout.DEFAULT_SPACING);
        copy.bindSeed(layout.seed());
        return copy;
    }
    @Override protected MapCodec<? extends BiomeSource> codec() { return CODEC; }
    @Override protected Stream<Holder<Biome>> collectPossibleBiomes() { return Stream.of(aether, sulfur, mercury, salt, sanctum); }
    @Override public Holder<Biome> getNoiseBiome(int quartX, int quartY, int quartZ, Climate.Sampler sampler) {
        int x = quartX * 4, z = quartZ * 4;
        var sample = layout.sample(x, z);
        if (quartY * 4 >= 250 && sample.distance() < layout.islandRadius()) return sanctum;
        return switch (sample.region()) {
            case SULFUR -> sulfur; case MERCURY -> mercury; case SALT -> salt; default -> aether;
        };
    }
}
