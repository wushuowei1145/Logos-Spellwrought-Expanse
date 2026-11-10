package dev.logos.dimension.client.music;

import dev.logos.dimension.music.InstrumentBlockEntity;
import dev.logos.dimension.music.organ.RoomAcoustics;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

/**
 * 管风琴所在的房间：以音管中心为起点，向四面八方均匀射出 42 条射线（每条最远 48 格），
 * 统计碰到方块的比例、平均距离和吸音，交给 {@link RoomAcoustics#estimate}。树叶只算半个。
 * 只在客户端算，每 5 秒重算一次。装了 Sound Physics Remastered 时「自动」视为「关」。
 */
final class OrganRoom {
    private static final int RAYS = 42;
    private static final double REACH = 48;
    private static final Vec3[] DIRS = fibonacci(RAYS);
    private static final Map<BlockPos, long[]> TIMES = new HashMap<>();
    private static final Map<BlockPos, RoomAcoustics.Reverb> CACHE = new HashMap<>();
    private static Boolean soundPhysics;

    private OrganRoom() {}

    private static Vec3[] fibonacci(int n) {
        Vec3[] out = new Vec3[n];
        double golden = Math.PI * (3 - Math.sqrt(5));
        for (int i = 0; i < n; i++) {
            double y = 1 - (i + 0.5) * 2.0 / n;
            double r = Math.sqrt(1 - y * y);
            double t = golden * i;
            out[i] = new Vec3(Math.cos(t) * r, y, Math.sin(t) * r);
        }
        return out;
    }

    static boolean soundPhysics() {
        if (soundPhysics == null) soundPhysics = ModList.get().isLoaded("sound_physics_remastered");
        return soundPhysics;
    }

    /** 这台管风琴此刻用的混响（按档位）。 */
    static RoomAcoustics.Reverb reverb(BlockPos pos, InstrumentBlockEntity be) {
        return RoomAcoustics.choose(be.reverbMode(), estimate(pos, be), soundPhysics());
    }

    /** 自动估计的结果（不看档位），界面上显示用。 */
    static RoomAcoustics.Reverb estimate(BlockPos pos, InstrumentBlockEntity be) {
        Level level = Minecraft.getInstance().level;
        if (level == null) return RoomAcoustics.Reverb.NONE;
        long now = level.getGameTime();
        long[] at = TIMES.get(pos);
        RoomAcoustics.Reverb cached = CACHE.get(pos);
        if (cached != null && at != null && now - at[0] < 100 && now >= at[0]) return cached;
        RoomAcoustics.Reverb r = measure(level, be.voiceCenter());
        CACHE.put(pos.immutable(), r);
        TIMES.put(pos.immutable(), new long[]{now});
        return r;
    }

    static RoomAcoustics.Reverb measure(Level level, Vec3 from) {
        double hits = 0, distance = 0, absorption = 0, weight = 0, up = 0, upCount = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (Vec3 d : DIRS) {
            double partial = 0;
            double step = 0.5;
            for (double t = 1.0; t <= REACH; t += step) {
                p.set(Math.floor(from.x + d.x * t), Math.floor(from.y + d.y * t), Math.floor(from.z + d.z * t));
                BlockState s = level.getBlockState(p);
                if (s.isAir() || organPart(s)) continue;
                if (s.is(BlockTags.LEAVES)) {
                    // 树叶：透一半，吸音
                    partial += 0.5;
                    absorption += 0.5 * 0.5;
                    weight += 0.5;
                    distance += 0.5 * t;
                    if (partial >= 1) break;
                    t += 2;
                    continue;
                }
                if (s.getCollisionShape(level, p).isEmpty() && !s.getFluidState().isEmpty()) continue;
                if (s.getCollisionShape(level, p).isEmpty()) continue;
                double rest = 1 - partial;
                partial = 1;
                absorption += rest * absorb(s);
                weight += rest;
                distance += rest * t;
                break;
            }
            hits += Math.min(1, partial);
            if (d.y > 0.3) {
                upCount++;
                up += Math.min(1, partial);
            }
        }
        double enclosure = hits / DIRS.length;
        // 室内要有顶：朝上的射线多半没碰到东西就是露天（地面和管墙本身不能让露天变成室内）
        if (weight <= 0 || upCount > 0 && up / upCount < 0.5) return RoomAcoustics.Reverb.NONE;
        return RoomAcoustics.estimate(enclosure, distance / weight, absorption / weight);
    }

    /** 管风琴自己的方块（演奏台、风箱、音管）不算墙。 */
    private static boolean organPart(BlockState s) {
        return s.getBlock() instanceof dev.logos.dimension.music.InstrumentBlock
            || s.getBlock() instanceof dev.logos.dimension.music.organ.OrganPipeBlock
            || s.getBlock() instanceof dev.logos.dimension.music.organ.OrganTrumpetBlock
            || s.is(dev.logos.dimension.music.Instruments.ORGAN_WIND_CHEST.get());
    }

    /** 吸音系数：硬墙小、软材料大。 */
    private static double absorb(BlockState s) {
        if (s.is(BlockTags.WOOL) || s.is(BlockTags.WOOL_CARPETS)) return 0.6;
        SoundType t = s.getSoundType();
        if (t == SoundType.GLASS || t == SoundType.METAL || t == SoundType.COPPER || t == SoundType.CALCITE
                || t == SoundType.DEEPSLATE || t == SoundType.POLISHED_DEEPSLATE || t == SoundType.DEEPSLATE_BRICKS) return 0.03;
        if (t == SoundType.STONE || t == SoundType.TUFF || t == SoundType.NETHER_BRICKS || t == SoundType.MUD_BRICKS) return 0.04;
        if (t == SoundType.WOOD || t == SoundType.CHERRY_WOOD || t == SoundType.BAMBOO_WOOD || t == SoundType.NETHER_WOOD) return 0.1;
        if (t == SoundType.GRASS || t == SoundType.GRAVEL || t == SoundType.SAND || t == SoundType.ROOTED_DIRT
                || t == SoundType.MOSS || t == SoundType.MOSS_CARPET || t == SoundType.SNOW) return 0.25;
        return 0.08;
    }

    static void clear() {
        TIMES.clear();
        CACHE.clear();
    }
}
