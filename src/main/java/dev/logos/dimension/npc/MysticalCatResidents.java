package dev.logos.dimension.npc;

import java.util.List;

import dev.logos.dimension.LogosDimension;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * 林隙村镇的灵猫居民（docs/MYSTICAL_CAT.md）：村镇的模板里没有实体，像原版村庄的猫一样由这里补上。
 *
 * <p>远行大陆（云海维度）里，每 {@value #INTERVAL} 刻看一次每位玩家所在的村镇：
 * 村镇范围（整座结构的外框再放宽 {@value #MARGIN} 格）里的灵猫少于上限（小村 {@value #HAMLET_CAP}、镇子 {@value #TOWN_CAP}）
 * 就补一只，落在随机一块建筑或道路的地面上。补出来的灵猫以村镇中心为家，不会走远，也不会因为玩家走远而消失。
 * 只在玩家附近、区块已加载时补；打死的过一会儿会有新的搬来。
 */
public final class MysticalCatResidents {
    public static final int INTERVAL = 300, MARGIN = 8, HAMLET_CAP = 3, TOWN_CAP = 6;
    static final ResourceKey<Structure> HAMLET = ResourceKey.create(Registries.STRUCTURE, LogosDimension.id("journey_hamlet"));
    static final ResourceKey<Structure> TOWN = ResourceKey.create(Registries.STRUCTURE, LogosDimension.id("journey_town"));

    private MysticalCatResidents() {}

    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != LogosDimension.CLOUDSEA) return;
        if (level.getGameTime() % INTERVAL != 0) return;
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) continue;
            for (var key : List.of(TOWN, HAMLET)) {
                StructureStart start = town(level, player.blockPosition(), key);
                if (start != null) {
                    refill(level, start, key == TOWN ? TOWN_CAP : HAMLET_CAP);
                    break;
                }
            }
        }
    }

    /** 这个位置所在的村镇（整座结构的外框内），没有为 null。 */
    public static StructureStart town(ServerLevel level, BlockPos pos, ResourceKey<Structure> key) {
        var structure = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getValue(key);
        if (structure == null) return null;
        StructureStart start = level.structureManager().getStructureAt(pos, structure);
        return start.isValid() ? start : null;
    }

    public static AABB area(StructureStart start) {
        BoundingBox b = start.getBoundingBox();
        return new AABB(b.minX(), b.minY(), b.minZ(), b.maxX() + 1, b.maxY() + 1, b.maxZ() + 1).inflate(MARGIN, 16, MARGIN);
    }

    public static int count(ServerLevel level, StructureStart start) {
        return level.getEntitiesOfClass(MysticalCat.class, area(start), MysticalCat::isAlive).size();
    }

    /** 少于上限就补一只；返回补上的灵猫，没补为 null。 */
    public static MysticalCat refill(ServerLevel level, StructureStart start, int cap) {
        if (count(level, start) >= cap) return null;
        BlockPos spot = spot(level, start, level.getRandom());
        if (spot == null) return null;
        MysticalCat cat = NpcEntities.MYSTICAL_CAT.get().create(level, EntitySpawnReason.STRUCTURE);
        if (cat == null) return null;
        cat.snapTo(spot.getX() + .5, spot.getY(), spot.getZ() + .5, level.getRandom().nextFloat() * 360, 0);
        cat.finalizeSpawn(level, level.getCurrentDifficultyAt(spot), EntitySpawnReason.STRUCTURE, null);
        BoundingBox b = start.getBoundingBox();
        cat.setHomeTo(b.getCenter().atY(spot.getY()), Math.max(b.getXSpan(), b.getZSpan()) / 2 + MARGIN);
        cat.setPersistenceRequired();
        level.addFreshEntity(cat);
        return cat;
    }

    /** 随机一块结构部件上方的地面：脚下是实心方块、不是水，站得下一只灵猫。试几次都不行就算了。 */
    static BlockPos spot(ServerLevel level, StructureStart start, RandomSource random) {
        var pieces = start.getPieces();
        if (pieces.isEmpty()) return null;
        for (int attempt = 0; attempt < 12; attempt++) {
            BoundingBox b = pieces.get(random.nextInt(pieces.size())).getBoundingBox();
            int x = b.minX() + random.nextInt(b.getXSpan()), z = b.minZ() + random.nextInt(b.getZSpan());
            if (!level.isLoaded(new BlockPos(x, 0, z)) || !level.hasChunkAt(new BlockPos(x, 0, z))) continue;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos pos = new BlockPos(x, y, z);
            var below = level.getBlockState(pos.below());
            if (!below.isFaceSturdy(level, pos.below(), net.minecraft.core.Direction.UP) || level.getFluidState(pos).is(FluidTags.WATER)) continue;
            var type = NpcEntities.MYSTICAL_CAT.get();
            if (!level.noCollision(type.getSpawnAABB(x + .5, y, z + .5))) continue;
            return pos;
        }
        return null;
    }
}
