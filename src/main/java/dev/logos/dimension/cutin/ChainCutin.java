package dev.logos.dimension.cutin;

import io.netty.buffer.ByteBuf;
import java.util.List;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * 一次连携切入（docs/CHAIN_CUTIN.md）：发动的玩家站正中间那一格，在场的同伴分在两边。
 * 只是画面：按 {@link ChainTimeline} 播完就结束，不打伤害、不挪人。
 *
 * @param leader  发动的玩家（实体 ID）
 * @param members 参加的同伴（实体 ID），按 {@link ChainLayout} 的规矩从中间往两边排：第一位在左边紧挨着玩家，第二位在右边，依次往外
 * @param seed    光尘、焰柱的随机种子
 */
public record ChainCutin(int leader, List<Integer> members, long seed) {
    /** 同伴最多几位（附属 mod 一共四位同伴，都能一起参加）。 */
    public static final int MAX_MEMBERS = 4;

    public static final StreamCodec<ByteBuf, ChainCutin> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ChainCutin::leader,
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(MAX_MEMBERS)), ChainCutin::members,
            ByteBufCodecs.VAR_LONG, ChainCutin::seed,
            ChainCutin::new);

    public ChainCutin {
        members = List.copyOf(members.subList(0, Math.min(MAX_MEMBERS, members.size())));
    }

    /** 一共几格（玩家加同伴）。 */
    public int size() {
        return members.size() + 1;
    }
}
