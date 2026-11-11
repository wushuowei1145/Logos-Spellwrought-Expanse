package dev.logos.dimension.music;

import net.minecraft.world.level.block.Block;

/** 灵猫竖琴：两格高，琴弦所在的平面正对放置的玩家。多格逻辑见 {@link TallInstrumentBlock}。 */
public class ConcertHarpBlock extends TallInstrumentBlock {
    public ConcertHarpBlock(Properties properties) {
        super(properties, InstrumentKind.CONCERT_HARP, Block.box(1, 0, 4, 15, 16, 12));
    }
}
