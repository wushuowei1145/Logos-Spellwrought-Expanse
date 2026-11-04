package dev.logos.dimension.cloudsea;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.KelpBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;

/** 远行大陆的海带头。继承原版 KelpBlock，只把向下接的 Blocks.KELP_PLANT 换成本附属的海带茎。 */
public class JourneyKelpBlock extends KelpBlock {
    public static final MapCodec<KelpBlock> CODEC = simpleCodec(JourneyKelpBlock::new);

    public JourneyKelpBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public MapCodec<KelpBlock> codec() {
        return CODEC;
    }

    @Override
    protected Block getBodyBlock() {
        return AquaticFlora.KELP_PLANT.get();
    }
}
