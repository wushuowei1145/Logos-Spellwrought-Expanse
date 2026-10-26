package dev.logos.dimension.client;

import dev.logos.dimension.DimensionParticles;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.cloudsea.CloudseaBlocks;
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
 * 长风草原的风媒粒子：草籽/花粉被风卷起来，沿风的方向飘一段。
 *
 * <p>只在长风草原、且风够大的时候生成；每 2 tick 最多 2 颗，半径 12 格内贴着草面出发，
 * 所以开销可以忽略。速度直接取 {@link WindField} 的方向与强度，风向变了粒子也跟着变。
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
        if (level == null || mc.player == null) return;
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
            double y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, (int) x, (int) z) + .35;
            // 只在草地上生成：踩点下方必须是长风草原的矮草或普通牧草。
            var below = level.getBlockState(BlockPos.containing(x, y - 1.1, z));
            if (!below.is(Blocks.TALL_GRASS) && !below.is(CloudseaBlocks.MEADOW_GRASS.get())) continue;
            double speed = .35 + .55 * wind.strength();
            level.addParticle(DimensionParticles.MOTE.get(),
                x, y, z,
                Math.cos(wind.angle()) * speed,
                .05 + random.nextDouble() * .08,
                Math.sin(wind.angle()) * speed);
        }
    }

}
