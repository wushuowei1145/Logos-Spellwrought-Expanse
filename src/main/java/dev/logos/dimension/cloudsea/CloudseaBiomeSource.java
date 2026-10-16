package dev.logos.dimension.cloudsea;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Map;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.*;

public final class CloudseaBiomeSource extends BiomeSource {
    public static final MapCodec<CloudseaBiomeSource> CODEC=RecordCodecBuilder.mapCodec(i->i.group(
            Codec.unboundedMap(Codec.STRING,Biome.CODEC).fieldOf("biomes").forGetter(s->s.biomes)).apply(i,CloudseaBiomeSource::new));
    private final Map<String,Holder<Biome>> biomes;private volatile CloudseaTerrain terrain=new CloudseaTerrain(0);
    public CloudseaBiomeSource(Map<String,Holder<Biome>> biomes){this.biomes=Map.copyOf(biomes);for(var b:CloudseaBiome.values())require(b.id());require("cloudsea_root_caves");require("cloudsea_demon_depths");}
    private Holder<Biome> require(String id){var value=biomes.get(id);if(value==null)throw new IllegalArgumentException("Missing Cloudsea biome "+id);return value;}
    public void bindSeed(long seed){terrain=new CloudseaTerrain(seed);}public CloudseaTerrain terrain(){return terrain;}
    @Override protected MapCodec<? extends BiomeSource> codec(){return CODEC;}
    @Override protected Stream<Holder<Biome>> collectPossibleBiomes(){return biomes.values().stream();}
    @Override public Holder<Biome> getNoiseBiome(int x,int y,int z,Climate.Sampler sampler){return require(y*4<-65?"cloudsea_demon_depths":y*4<0?"cloudsea_root_caves":terrain.column(x*4,z*4).biome().id());}
}
