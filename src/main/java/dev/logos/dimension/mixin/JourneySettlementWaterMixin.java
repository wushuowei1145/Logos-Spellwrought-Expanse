package dev.logos.dimension.mixin;

import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 林隙村镇：起始点落在水面时跳过生成。
 *
 * <p>原版 {@code minecraft:jigsaw} 的 {@code project_start_to_heightmap: WORLD_SURFACE_WG} 把水面也算作地表，
 * 导致广场和房子会泡进湖里。这里在选址时比较 WORLD_SURFACE_WG 与 OCEAN_FLOOR_WG：
 * 前者高于后者说明表面是水，直接放弃这个候选点。只作用于本附属的 settlement/* 起始池，
 * 不改动其他原生 jigsaw 结构。
 */
@Mixin(JigsawStructure.class)
public abstract class JourneySettlementWaterMixin {
    @Inject(method = "findGenerationPoint", at = @At("HEAD"), cancellable = true)
    private void logosdimension$skipWaterStart(Structure.GenerationContext context, CallbackInfoReturnable<Optional<Structure.GenerationStub>> cir) {
        if (!this.isJourneySettlement()) {
            return;
        }
        ChunkPos chunk = context.chunkPos();
        int x = chunk.getMinBlockX();
        int z = chunk.getMinBlockZ();
        int surface = context.chunkGenerator().getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, context.heightAccessor(), context.randomState());
        int floor = context.chunkGenerator().getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, context.heightAccessor(), context.randomState());
        if (surface > floor) {
            cir.setReturnValue(Optional.empty());
        }
    }

    private boolean isJourneySettlement() {
        Holder<StructureTemplatePool> pool = ((JigsawStructure) (Object) this).getStartPool();
        return pool.unwrapKey().map(key -> {
            var id = key.identifier();
            return id.getNamespace().equals("logos_dimension") && id.getPath().startsWith("settlement/");
        }).orElse(false);
    }
}
