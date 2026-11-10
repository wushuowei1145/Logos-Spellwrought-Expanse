package dev.logos.dimension.companion;

import com.mojang.serialization.Codec;
import dev.logos.dimension.LogosDimension;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 狐火灯（docs/SHIRAUME_COMPANION.md 第 8.6 节）：主人在暗处挖矿、赶路时，她掌心托一团狐火照亮周围。
 * 光是一格看不见的光源方块，跟着她走：每次续 {@link #LIFE} 刻，没续上就拆掉。
 * 点过的位置记在维度存档里，服务器中途停掉也不会在世界里留下光源。
 */
public final class CompanionLight {
    public static final int LEVEL = 13, LIFE = 30;
    private static final BlockState LIGHT = Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, LEVEL);

    static final class Lit extends SavedData {
        static final Codec<Lit> CODEC = Codec.unboundedMap(Codec.STRING, Codec.LONG).xmap(Lit::new, Lit::save);
        static final SavedDataType<Lit> TYPE = new SavedDataType<>(LogosDimension.id("shiraume_lights"), Lit::new, CODEC);
        final Map<Long, Long> until = new HashMap<>();

        Lit() {}

        private Lit(Map<String, Long> saved) {
            saved.forEach((k, v) -> until.put(Long.parseLong(k), v));
        }

        private Map<String, Long> save() {
            Map<String, Long> out = new HashMap<>();
            until.forEach((k, v) -> out.put(Long.toString(k), v));
            return out;
        }
    }

    private CompanionLight() {}

    private static Lit lit(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(Lit.TYPE);
    }

    /** 在 {@code at} 点一盏（那一格是空气才点；已经点着的续上）。返回这一格现在亮没亮。 */
    static boolean hold(ServerLevel level, BlockPos at) {
        Lit data = lit(level);
        long now = level.getGameTime();
        Long old = data.until.get(at.asLong());
        if (old == null) {
            if (!level.getBlockState(at).isAir()) return false;
            level.setBlock(at, LIGHT, 3);
        }
        data.until.put(at.asLong(), now + LIFE);
        data.setDirty();
        return true;
    }

    /** 每 5 刻拆掉到点的灯（灯所在的区块没加载时等下次）。 */
    static void tick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 5 != 0) return;
        for (ServerLevel level : event.getServer().getAllLevels()) {
            Lit data = level.getDataStorage().get(Lit.TYPE);
            if (data == null || data.until.isEmpty()) continue;
            long now = level.getGameTime();
            for (Iterator<Map.Entry<Long, Long>> it = data.until.entrySet().iterator(); it.hasNext(); ) {
                var e = it.next();
                if (e.getValue() > now) continue;
                BlockPos pos = BlockPos.of(e.getKey());
                if (!level.isLoaded(pos)) continue;
                if (level.getBlockState(pos).is(Blocks.LIGHT)) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                it.remove();
                data.setDirty();
            }
        }
    }

    /** 测试用：这个维度里现在点着几盏。 */
    public static int count(ServerLevel level) {
        Lit data = level.getDataStorage().get(Lit.TYPE);
        return data == null ? 0 : data.until.size();
    }
}
