package dev.logos.dimension.cloudsea;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.TallSeagrassBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/** 远行大陆的高海草。继承原版 TallSeagrassBlock，只把中键选取的 Blocks.SEAGRASS 换成本附属的海草。 */
public class JourneyTallSeagrassBlock extends TallSeagrassBlock {
    public static final MapCodec<TallSeagrassBlock> CODEC = simpleCodec(JourneyTallSeagrassBlock::new);

    public JourneyTallSeagrassBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public MapCodec<TallSeagrassBlock> codec() {
        return CODEC;
    }

    @Override
    protected ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state, boolean includeData) {
        return new ItemStack(AquaticFlora.SEAGRASS.get());
    }
}
