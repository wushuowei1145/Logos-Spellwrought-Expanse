package dev.logos.dimension.wheat;

import dev.logos.dimension.LogosDimension;
import java.util.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.*;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;

/** 维护者于 2026-11-26 授权的怪圈选址检查；模板、重力处理与方块放置仍由原版完成。 */
public final class WheatCirclePlacement {
    public static final ResourceKey<Biome> BIOME=ResourceKey.create(Registries.BIOME,LogosDimension.id("wheat_plains"));
    private record Placement(long seed,RandomSpreadStructurePlacement distribution) {}
    // 缓存只存坐标与选择结果，不保留世界或采样器，服务器关闭后可随 RandomState 回收。
    private static final Map<RandomState,Map<Placement,WheatPatchSites>> CACHES=new WeakHashMap<>();

    public static boolean allows(Structure.GenerationContext context){
        var winner=patch(context).winner();
        return winner.isPresent()&&winner.get().blockX()==context.chunkPos().getMinBlockX()&&winner.get().blockZ()==context.chunkPos().getMinBlockZ();
    }
    public static WheatPatchSites.Patch patch(Structure.GenerationContext context){
        if(!(context.biomeSource() instanceof MultiNoiseBiomeSource source))return empty();
        var set=context.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET).getOrThrow(ResourceKey.create(Registries.STRUCTURE_SET,LogosDimension.id("wheat_crop_circles"))).value();
        if(!(set.placement() instanceof RandomSpreadStructurePlacement distribution))return empty();
        WheatPatchSites sites;
        synchronized(CACHES){
            sites=CACHES.computeIfAbsent(context.randomState(),key->new HashMap<>()).computeIfAbsent(new Placement(context.seed(),distribution),key->new WheatPatchSites());
        }
        var sampler=context.randomState().sampler();
        return sites.find(context.chunkPos().getMinBlockX()>>2,context.chunkPos().getMinBlockZ()>>2,(x,z)->{
            var sample=sampler.sample(x,18,z);
            var surface=new Climate.TargetPoint(sample.temperature(),sample.humidity(),sample.continentalness(),sample.erosion(),0,sample.weirdness());
            return source.getNoiseBiome(surface).is(BIOME);
        },(x,z)->{
            int cx=Math.floorDiv(x,4),cz=Math.floorDiv(z,4);
            var candidate=distribution.getPotentialStructureChunk(context.seed(),cx,cz);
            return candidate.x()==cx&&candidate.z()==cz;
        });
    }
    private static WheatPatchSites.Patch empty(){return new WheatPatchSites.Patch(Optional.empty(),0,0,0,0,0,false);}
    private WheatCirclePlacement(){}
}
