package dev.logos.dimension.gate;

import com.mojang.serialization.MapCodec;
import dev.logos.LogosMod;
import dev.logos.lang.Element;
import dev.logos.runtime.Lifeforms;
import dev.logos.runtime.Vfx;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * 启门石：放在门洞正下方，灌满 1000 点魔力开门。
 *
 * <p>手持能补充纯净魔力的物品（Logos 的 {@code mana_fuel} 数据表里不带不稳定标记的都算）右键放入，每次吃掉一个；
 * 术式（{@code infuse-block}）、魔力存储罐和魔力收集器走 {@code ManaVessel} 接口灌入。
 * 灌满就自动开门；门框被破坏后魔力还在，修好门框空手右键重新开。挖走后魔力清零。
 */
public final class GateStoneBlock extends BaseEntityBlock {
    public static final MapCodec<GateStoneBlock> CODEC = simpleCodec(GateStoneBlock::new);
    /** 外观档位：0 没有魔力，4 灌满。 */
    public static final IntegerProperty CHARGE = IntegerProperty.create("charge", 0, 4);

    public GateStoneBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(CHARGE, 0));
    }

    @Override protected MapCodec<GateStoneBlock> codec() { return CODEC; }

    @Override public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new GateStoneBlockEntity(pos, state); }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide() || type != Gate.GATE_STONE_BE.get()) return null;
        return (BlockEntityTicker<T>) (BlockEntityTicker<GateStoneBlockEntity>) GateStoneBlockEntity::serverTick;
    }

    @Override
    protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        Lifeforms.ManaFuel fuel = stack.typeHolder().getData(LogosMod.MANA_FUEL);
        // 不是魔力物品：照常放方块或用物品；空手才算查看和重新开门
        if (fuel == null || fuel.mana() <= 0) return stack.isEmpty() ? InteractionResult.TRY_WITH_EMPTY_HAND : InteractionResult.PASS;
        if (level.isClientSide() || !(level.getBlockEntity(pos) instanceof GateStoneBlockEntity stone)) return InteractionResult.SUCCESS;
        if (fuel.unstable()) {
            player.sendOverlayMessage(Component.translatable("message.logos_dimension.gate.unstable"));
            return InteractionResult.SUCCESS;
        }
        if (stone.mana() >= stone.maxMana()) {
            stone.tryIgnite(player);
            return InteractionResult.SUCCESS;
        }
        stack.consume(1, player);
        stone.receiveMana(fuel.mana());
        level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 1f, .8f + (float) (stone.mana() / stone.maxMana()) * .6f);
        Vfx.burst((ServerLevel) level, pos.getCenter().add(0, .6, 0), Element.ARCANE, 2);
        if (!stone.igniting()) stone.showStatus(player);
        return InteractionResult.SUCCESS;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide() && level.getBlockEntity(pos) instanceof GateStoneBlockEntity stone) {
            if (stone.mana() >= stone.maxMana()) stone.tryIgnite(player);
            else stone.showStatus(player);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        int charge = state.getValue(CHARGE);
        if (charge > 0 && random.nextInt(8 - charge) == 0) level.addParticle(net.minecraft.core.particles.ParticleTypes.GLOW, pos.getX() + random.nextDouble(), pos.getY() + 1.05, pos.getZ() + random.nextDouble(), 0, .03, 0);
    }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(CHARGE); }
}
