package dev.logos.dimension.world;

import com.google.gson.JsonParser;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.*;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.logos.dimension.LogosDimension;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.levelgen.*;

/** Decode old save IDs straight into vanilla generators. No legacy terrain or placement code runs. */
public final class LegacyWorldgenCodecs {
    public static MapCodec<NoiseBasedChunkGenerator> generator(String settings){
        return RecordCodecBuilder.mapCodec(i->i.group(
                BiomeSource.CODEC.fieldOf("biome_source").forGetter(NoiseBasedChunkGenerator::getBiomeSource),
                RegistryOps.<NoiseGeneratorSettings,NoiseBasedChunkGenerator>retrieveElement(ResourceKey.create(Registries.NOISE_SETTINGS,LogosDimension.id(settings))))
                .apply(i,NoiseBasedChunkGenerator::new));
    }
    public static final MapCodec<BiomeSource> CLOUDSEA=biomes("dimension/cloudsea.json");
    public static final MapCodec<BiomeSource> ALCHEMICAL=biomes("cauldron/crucible.json");
    private static MapCodec<BiomeSource> biomes(String resource){return RecordCodecBuilder.mapCodec(i->i.group(
            RegistryOps.<Biome,BiomeSource>retrieveGetter(Registries.BIOME)).apply(i,lookup->fromData(lookup,resource)));}
    private static BiomeSource fromData(net.minecraft.core.HolderGetter<Biome> lookup,String resource){
        try(var input=LegacyWorldgenCodecs.class.getResourceAsStream("/data/logos_dimension/"+resource);
            var reader=new InputStreamReader(Objects.requireNonNull(input),StandardCharsets.UTF_8)){
            var entries=JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("generator").getAsJsonObject("biome_source").getAsJsonArray("biomes");
            var points=new ArrayList<Pair<Climate.ParameterPoint,Holder<Biome>>>();
            for(var entry:entries){var obj=entry.getAsJsonObject();String name=Identifier.parse(obj.get("biome").getAsString()).getPath();
                var biome=lookup.getOrThrow(ResourceKey.create(Registries.BIOME,LogosDimension.id(name)));
                points.add(Pair.of(Climate.ParameterPoint.CODEC.parse(JsonOps.INSTANCE,obj.get("parameters")).getOrThrow(),biome));
            }
            return MultiNoiseBiomeSource.createFromList(new Climate.ParameterList<>(points));
        }catch(Exception error){throw new IllegalStateException("Cannot upgrade legacy biome settings",error);}
    }
    private LegacyWorldgenCodecs(){}
}
