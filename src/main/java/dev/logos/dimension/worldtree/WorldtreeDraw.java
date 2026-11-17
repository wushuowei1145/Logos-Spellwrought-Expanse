package dev.logos.dimension.worldtree;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * 正在拉的世界树之弓：开始拉弓的刻、按先后排的锁定（目标实体编号与锁定的刻）、中间那根实箭出现的刻（还没出现为 -1）、
 * 是不是左键横着拉（横着拉时不锁定，“锁定”只是攒下的虚影箭，目标编号为 -1）。
 * 作为物品组件挂在弓上，跟着装备同步给所有看得到的人：弓上的虚影箭、锁定标记、凝聚动画都从这里算，不另发网络包。
 * 只同步、不存档（读档时不会带着上次没放的锁定）。
 */
public record WorldtreeDraw(long start, List<Lock> locks, long full, boolean sideways) {
    public record Lock(int entity, long time) {
        static final StreamCodec<RegistryFriendlyByteBuf, Lock> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Lock::entity, ByteBufCodecs.VAR_LONG, Lock::time, Lock::new);
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, WorldtreeDraw> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, WorldtreeDraw::start,
            Lock.STREAM_CODEC.apply(ByteBufCodecs.list(WorldtreeAim.MAX_LOCKS)), WorldtreeDraw::locks,
            ByteBufCodecs.VAR_LONG, WorldtreeDraw::full, ByteBufCodecs.BOOL, WorldtreeDraw::sideways, WorldtreeDraw::new);

    public WorldtreeDraw {
        locks = List.copyOf(locks);
    }

    public static WorldtreeDraw begin(long now, boolean sideways) {
        return new WorldtreeDraw(now, List.of(), -1, sideways);
    }

    public WorldtreeDraw lock(int entity, long now) {
        var next = new ArrayList<>(locks);
        next.add(new Lock(entity, now));
        return new WorldtreeDraw(start, next, full, sideways);
    }

    public WorldtreeDraw withFull(long now) {
        return new WorldtreeDraw(start, locks, now, sideways);
    }

    public boolean hasFull() {
        return full >= 0;
    }

    /** 这个目标已经被锁了几次。 */
    public int count(int entity) {
        int n = 0;
        for (Lock lock : locks) if (lock.entity == entity) n++;
        return n;
    }
}
