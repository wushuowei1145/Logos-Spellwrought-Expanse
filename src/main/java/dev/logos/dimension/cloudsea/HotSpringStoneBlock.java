package dev.logos.dimension.cloudsea;

import com.mojang.serialization.MapCodec;
import dev.logos.dimension.DimensionParticles;
import dev.logos.dimension.LogosDimension;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import org.jspecify.annotations.Nullable;

/**
 * 温泉地热石：像灵魂沙之于气泡柱，加热它正上方的一整段水柱。
 *
 * <p>水柱从石头上方一格起，连续的水或含水方块（含水的台阶、半砖等）都算，最多 {@link #MAX_DEPTH} 格，
 * 所以池底可以在地热石上再铺一层含水的半砖或台阶。水面升起白色蒸汽；泡在这段水柱里的生物（站着、踩在台阶上或浮在水面）
 * 获得生命恢复并熄灭身上的火。
 */
@EventBusSubscriber(modid = LogosDimension.ID)
public class HotSpringStoneBlock extends Block {
    public static final MapCodec<HotSpringStoneBlock> CODEC = simpleCodec(HotSpringStoneBlock::new);
    /** 水柱最多算这么深；更深的水面不冒蒸汽，水里的生物也找不到底下的地热石。 */
    public static final int MAX_DEPTH = 8;

    @Override
    public MapCodec<HotSpringStoneBlock> codec() {
        return CODEC;
    }

    public HotSpringStoneBlock(Properties properties) {
        super(properties);
    }

    /** 地热石上方水柱的最上一格；上方不是水、或水深超过 {@link #MAX_DEPTH} 时为 null。 */
    public static @Nullable BlockPos waterTop(BlockGetter level, BlockPos stone) {
        BlockPos.MutableBlockPos cursor = stone.mutable().move(Direction.UP);
        for (int depth = 0; depth < MAX_DEPTH; depth++) {
            if (!level.getFluidState(cursor).is(FluidTags.WATER)) {
                return depth == 0 ? null : cursor.below();
            }
            cursor.move(Direction.UP);
        }
        return level.getFluidState(cursor).is(FluidTags.WATER) ? null : cursor.below();
    }

    /** 从这一格沿水柱往下找，水柱底下是不是地热石。 */
    public static boolean heatedFrom(BlockGetter level, BlockPos pos) {
        BlockPos.MutableBlockPos cursor = pos.mutable();
        for (int depth = 0; depth <= MAX_DEPTH && level.getFluidState(cursor).is(FluidTags.WATER); depth++) {
            cursor.move(Direction.DOWN);
        }
        return level.getBlockState(cursor).getBlock() instanceof HotSpringStoneBlock;
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        BlockPos waterTopPos = waterTop(level, pos);
        if (waterTopPos == null) return;
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

    /** 每秒看一次：在水里、脚下水柱通到地热石的生物得到生命恢复。 */
    @SubscribeEvent
    static void soak(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof LivingEntity living) || living.level().isClientSide()
                || living.tickCount % 20 != 0 || !living.isInWater()) return;
        if (!heatedFrom(living.level(), living.blockPosition())) return;
        if (!living.hasEffect(MobEffects.REGENERATION)) {
            living.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 60, 0, false, false, true));
        }
        if (living.isOnFire()) {
            living.clearFire();
        }
    }
}
