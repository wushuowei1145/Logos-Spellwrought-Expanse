package dev.logos.dimension.cloudsea;

import com.mojang.serialization.MapCodec;
import dev.logos.dimension.DimensionParticles;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * 温泉地热石：
 * 放置于水底时，加热上方水体成为温泉，在水面升腾起白色蒸汽粒子，并为浸泡其中的生物提供生命恢复效果。
 */
public class HotSpringStoneBlock extends Block {
    public static final MapCodec<HotSpringStoneBlock> CODEC = simpleCodec(HotSpringStoneBlock::new);

    @Override
    public MapCodec<HotSpringStoneBlock> codec() {
        return CODEC;
    }

    public HotSpringStoneBlock(Properties properties) {
        super(properties);
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        BlockPos.MutableBlockPos cursor = pos.mutable().move(0, 1, 0);
        int maxDepth = 6;
        while (maxDepth-- > 0 && level.getFluidState(cursor).is(FluidTags.WATER)) {
            cursor.move(0, 1, 0);
        }
        // cursor 当前位于水体上方第一格
        BlockPos surfaceAirPos = cursor.immutable();
        BlockPos waterTopPos = surfaceAirPos.below();

        if (level.getFluidState(waterTopPos).is(FluidTags.WATER)) {
            // 在水表面生成温泉水汽
            if (random.nextFloat() < 0.65f) {
                double px = waterTopPos.getX() + 0.15 + random.nextDouble() * 0.7;
                double py = waterTopPos.getY() + 0.95;
                double pz = waterTopPos.getZ() + 0.15 + random.nextDouble() * 0.7;
                level.addParticle(DimensionParticles.STEAM.get(), px, py, pz, 0.0, 0.02, 0.0);
            }
            // 偶尔轻微咕嘟冒泡声
            if (random.nextInt(60) == 0) {
                level.playLocalSound(
                        waterTopPos.getX() + 0.5,
                        waterTopPos.getY() + 0.9,
                        waterTopPos.getZ() + 0.5,
                        SoundEvents.BUBBLE_COLUMN_UPWARDS_AMBIENT,
                        SoundSource.BLOCKS,
                        0.25f,
                        1.0f + (random.nextFloat() - random.nextFloat()) * 0.2f,
                        false
                );
            }
        }
    }

    @Override
    public void stepOn(Level level, BlockPos pos, BlockState state, Entity entity) {
        super.stepOn(level, pos, state, entity);
        healSoakingEntity(entity);
    }

    private static void healSoakingEntity(Entity entity) {
        if (entity instanceof LivingEntity living && entity.isInWater()) {
            if (!living.hasEffect(MobEffects.REGENERATION)) {
                living.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 50, 0, false, false, true));
            }
            if (living.isOnFire()) {
                living.clearFire();
            }
        }
    }
}
