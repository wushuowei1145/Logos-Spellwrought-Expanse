package dev.logos.dimension.paint;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DirectionalBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * 画刷：抄原版考古刷的模型和刷的动作（BRUSH 动画 + 按住刷），
 * 刷完在目标方块那一面生成对应颜色的漆面。右键调色盘方块 = 蘸色。
 */
public class PaintBrushItem extends Item {
    public PaintBrushItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        Player player = context.getPlayer();

        // 蘸色：右键放置的调色盘，把调色盘颜色写进画刷
        if (level.getBlockState(pos).is(PaintSystem.PALETTE.get())) {
            if (player != null && !level.isClientSide()
                    && level.getBlockEntity(pos) instanceof PaintSurfaceBlockEntity palette) {
                player.getItemInHand(context.getHand())
                        .set(PaintSystem.PAINT_COLOR.get(), palette.paint());
            }
            return InteractionResult.SUCCESS;
        }

        if (player != null) {
            player.startUsingItem(context.getHand());
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public ItemUseAnimation getUseAnimation(ItemStack stack) {
        return ItemUseAnimation.BRUSH;
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return 20; // 约 1 秒刷一下
    }

    @Override
    public void onUseTick(Level level, LivingEntity entity, ItemStack stack, int ticksRemaining) {
        if (!(entity instanceof Player player)) {
            return;
        }
        if (ticksRemaining % 10 == 5) {
            HitResult hit = calculateHitResult(player);
            if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
                level.playSound(player, blockHit.getBlockPos(), SoundEvents.BRUSH_GENERIC, SoundSource.BLOCKS);
            }
        }
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        if (entity instanceof Player player && !level.isClientSide()) {
            HitResult hit = calculateHitResult(player);
            if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
                Direction face = blockHit.getDirection();
                BlockPos targetPos = blockHit.getBlockPos();
                BlockState target = level.getBlockState(targetPos);
                // 只刷木制方块（按原版音效材质判定，覆盖原木、木板、楼梯、半砖、栅栏等）
                if (target.getSoundType(level, targetPos, player) != SoundType.WOOD) {
                    return stack;
                }
                BlockPos surfacePos = targetPos.relative(face);
                if (level.getBlockState(surfacePos).isAir()) {
                    int color = stack.getOrDefault(PaintSystem.PAINT_COLOR.get(), PaintSurfaceBlockEntity.DEFAULT_COLOR);
                    // 漆层贴在漆面格子里靠目标方块的一侧，所以朝向取被刷面的反方向
                    level.setBlock(surfacePos, PaintSystem.SURFACE.get().defaultBlockState()
                            .setValue(DirectionalBlock.FACING, face.getOpposite())
                            .setValue(PaintSurfaceBlock.COVER, PaintSurfaceBlock.coverFor(target, face)), 3);
                    if (level.getBlockEntity(surfacePos) instanceof PaintSurfaceBlockEntity surface) {
                        surface.setPaint(color);
                    }
                }
            }
        }
        return stack;
    }

    private static HitResult calculateHitResult(Player player) {
        return ProjectileUtil.getHitResultOnViewVector(player, net.minecraft.world.entity.EntitySelector.CAN_BE_PICKED, player.blockInteractionRange());
    }
}
