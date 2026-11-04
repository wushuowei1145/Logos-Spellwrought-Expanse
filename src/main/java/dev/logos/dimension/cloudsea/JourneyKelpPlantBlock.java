package dev.logos.dimension.cloudsea;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.GrowingPlantHeadBlock;
import net.minecraft.world.level.block.KelpPlantBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;

/** 远行大陆的海带茎。继承原版 KelpPlantBlock，只把顶端的 Blocks.KELP 换成本附属的海带头。 */
public class JourneyKelpPlantBlock extends KelpPlantBlock {
    public static final MapCodec<KelpPlantBlock> CODEC = simpleCodec(JourneyKelpPlantBlock::new);

    public JourneyKelpPlantBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public MapCodec<KelpPlantBlock> codec() {
        return CODEC;
    }

    @Override
    protected GrowingPlantHeadBlock getHeadBlock() {
        return (GrowingPlantHeadBlock) AquaticFlora.KELP.get();
    }
}
