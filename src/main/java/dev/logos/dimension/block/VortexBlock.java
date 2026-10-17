package dev.logos.dimension.block;

import com.mojang.serialization.MapCodec;
import dev.logos.dimension.DimensionTravel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** A compact liquid-metal vortex serves as a reversible entrance without generating a building. */
public final class VortexBlock extends Block {
    public static final MapCodec<VortexBlock> CODEC = simpleCodec(VortexBlock::new);
    public VortexBlock(BlockBehaviour.Properties properties) { super(properties); }
    @Override public MapCodec<VortexBlock> codec() { return CODEC; }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer){
            if(dev.logos.dimension.LogosDimension.isCrucible(level.dimension()))DimensionTravel.leave(serverPlayer);
            else serverPlayer.sendSystemMessage(net.minecraft.network.chat.Component.translatable("message.logos_dimension.use_cauldron"));
        }
        return InteractionResult.SUCCESS;
    }
    @Override public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (random.nextInt(3) == 0) level.addParticle(ParticleTypes.END_ROD, pos.getX() + .5, pos.getY() + 1.1, pos.getZ() + .5,
                (random.nextDouble() - .5) * .03, .03, (random.nextDouble() - .5) * .03);
    }
}
