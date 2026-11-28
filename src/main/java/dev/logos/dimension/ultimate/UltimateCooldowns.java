package dev.logos.dimension.ultimate;

import com.mojang.serialization.Codec;
import java.util.HashMap;
import java.util.Map;

/**
 * 主人身上每位同伴的奥义什么时候好（游戏刻）。存档、死亡不清：召唤收回不会把冷却刷掉（规范 3.2）。
 *
 * @param readyAt 同伴 ID → 那一刻以后能再放
 */
public record UltimateCooldowns(Map<String, Long> readyAt) {
    public static final UltimateCooldowns NONE = new UltimateCooldowns(Map.of());
    static final Codec<UltimateCooldowns> CODEC = Codec.unboundedMap(Codec.STRING, Codec.LONG).xmap(UltimateCooldowns::new, UltimateCooldowns::readyAt);

    public UltimateCooldowns {
        readyAt = Map.copyOf(readyAt);
    }

    /** 还要等多少刻（0 是好了）。 */
    public long left(String id, long now) {
        return Math.max(0, readyAt.getOrDefault(id, 0L) - now);
    }

    public UltimateCooldowns with(String id, long ready) {
        Map<String, Long> next = new HashMap<>(readyAt);
        next.put(id, ready);
        return new UltimateCooldowns(next);
    }
}
