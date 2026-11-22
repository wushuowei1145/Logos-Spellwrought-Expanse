package dev.logos.dimension.client;

import dev.logos.dimension.DimensionParticles;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.cloudsea.CloudseaBlocks;
import dev.logos.dimension.cloudsea.CloudseaBiome;
import dev.logos.dimension.wind.WindField;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * 长风草原的草籽光点：风较强时在草丛上方增加少量环境浮光。
 *
 * <p>只在长风草原、且风够大的时候生成；每 2 tick 最多 2 颗，半径 12 格内贴着草面出发，
 * 复用 MOTE 的原有漂浮轨迹与统一数量预算；粒子数量随风力增加，不另做每帧世界扫描。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class WindPollen {
    /** 低于这个风力就不生成，免得无风天也满天飘。 */
    private static final float MIN_WIND = 0.35f;
    private static final int RADIUS = 12;

    private WindPollen() {}

    @SubscribeEvent
    static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null || mc.player == null || mc.isPaused()) return;
        if (!level.dimension().equals(LogosDimension.CLOUDSEA)) return;
        var player = mc.player;
        if (level.getRandom().nextInt(2) != 0) return; // 每 2 tick 一批

        var wind = WindField.of(level);
        if (wind.strength() < MIN_WIND) return;

        RandomSource random = level.getRandom();
        int count = wind.strength() > 0.8f ? 2 : 1;
        for (int i = 0; i < count; i++) {
            double x = player.getX() + (random.nextDouble() - .5) * 2 * RADIUS;
            double z = player.getZ() + (random.nextDouble() - .5) * 2 * RADIUS;
            int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
            if (!level.hasChunk(bx >> 4, bz >> 4)) continue;
            int floor = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, bx, bz);
            var root = new BlockPos(bx, floor, bz);
            // 高度图给的是地面上方的空位；检查这里的草，不能再往下多查一格土。
            if (!level.getBlockState(root.below()).is(CloudseaBlocks.GROUND.get(CloudseaBiome.SILK_FOLDS).get())) continue;
            var grass = level.getBlockState(root);
            if (!grass.is(Blocks.TALL_GRASS) && !grass.is(Blocks.SHORT_GRASS)
                    && !grass.is(CloudseaBlocks.MEADOW_GRASS.get()) && !grass.is(CloudseaBlocks.MEADOW_GRASS_YOUNG.get())) continue;
            double y = floor + .35 + random.nextDouble() * .5;
            double speed = .35 + .55 * wind.strength();
            level.addParticle(DimensionParticles.MOTE.get(),
                x, y, z,
                Math.cos(wind.angle()) * speed,
                .05 + random.nextDouble() * .08,
                Math.sin(wind.angle()) * speed);
        }
    }

}
