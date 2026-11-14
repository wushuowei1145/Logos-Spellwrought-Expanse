package dev.logos.dimension.music;

import net.minecraft.world.level.block.Block;

/**
 * 灵猫竖琴：两格高，琴弦所在的平面正对放置的玩家。琴身靠在格子演奏者那一侧（朝北放置时 z 9–16，琴弦在 z = 13，
 * 同 tools/instrument_models/strings.py 的 HARP_SHIFT），后面琴凳上的人手才够得着弦。多格逻辑见 {@link TallInstrumentBlock}。
 */
public class ConcertHarpBlock extends TallInstrumentBlock {
    public ConcertHarpBlock(Properties properties) {
        super(properties, InstrumentKind.CONCERT_HARP, Block.box(1, 0, 9, 15, 16, 16));
    }
}
