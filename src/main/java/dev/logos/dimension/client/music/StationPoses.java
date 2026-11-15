package dev.logos.dimension.client.music;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.music.InstrumentBlock;
import dev.logos.dimension.music.InstrumentKind;
import dev.logos.dimension.music.InstrumentPosts;
import dev.logos.dimension.music.InstrumentSeat;
import dev.logos.dimension.music.Instruments;
import dev.logos.dimension.music.StarbloomBenchBlock;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 工位上的演奏者（docs/ORCHESTRA.md 第 15 节）：大提琴、低音提琴琴后（拉弓），钟琴、管钟前面和定音鼓后的琴凳上（敲）。
 * 算出 {@link MusicianPoses.Musician}：坐标都在「演奏者面朝北」的方块像素里（x 向东、y 向上、z 向南，同琴凳坐标），
 * 原版人形模型、附属 NPC 的骨架各自换算（同琴凳上的手位）。
 * <p>
 * 琴槌不是物品：在工位上演奏时画在手上（钟琴两支小琴槌、管钟右手一把皮锤、定音鼓两支毡头槌，模型在 items/held/）。
 * 一个音落下时那只手的槌头落到音条 / 管顶 / 鼓皮上，其余时间抬起 3 像素。
 */
public final class StationPoses {
    /** 槌头抬起的高度（像素）。 */
    private static final float LIFT = 3;
    /** 一个音落下后槌头贴着的时间（毫秒）。 */
    private static final float STRIKE_MS = 140;
    private static final Map<InstrumentKind, ItemStack> TOOLS = new EnumMap<>(InstrumentKind.class);

    private StationPoses() {}

    /** 琴槌模型：握点（模型像素 y）和槌头中心（y），同 tools/instrument_models/percussion.py 的琴槌。 */
    static float toolGrip(InstrumentKind kind) {
        return 1.5f;
    }

    static float toolHead(InstrumentKind kind) {
        return switch (kind) {
            case TUBULAR_BELLS -> 9.8f;
            case TIMPANI -> 10.8f;
            // 大鼓的羊毛槌、小军鼓的鼓棒尖、吊镲的毛线槌、三角铁的钢棒尖（tools/instrument_models/drums.py）
            case BASS_DRUM -> 11.0f;
            case SNARE_DRUM -> 11.2f;
            case SUSPENDED_CYMBAL -> 10.8f;
            case TRIANGLE -> 9.3f;
            default -> 10.7f;
        };
    }

    /** 画琴槌用的物品状态。 */
    static ItemStackRenderState tool(InstrumentKind kind, LivingEntity entity) {
        ItemStack stack = TOOLS.computeIfAbsent(kind, k -> {
            ItemStack s = new ItemStack(Instruments.VIOLIN.get());
            s.set(DataComponents.ITEM_MODEL, LogosDimension.id(switch (k) {
                case TUBULAR_BELLS -> "held/chime_hammer";
                case TIMPANI -> "held/timpani_mallet";
                case BASS_DRUM -> "held/bass_drum_beater";
                case SNARE_DRUM -> "held/snare_stick";
                case SUSPENDED_CYMBAL -> "held/cymbal_mallet";
                case TRIANGLE -> "held/triangle_beater";
                default -> "held/glockenspiel_mallet";
            }));
            return s;
        });
        ItemStackRenderState state = new ItemStackRenderState();
        Minecraft.getInstance().getItemModelResolver().updateForLiving(state, stack, ItemDisplayContext.NONE, entity);
        return state;
    }

    // —— 工位（大提琴、低音提琴、钟琴、管钟）——

    static MusicianPoses.@Nullable Musician post(LivingEntity entity, InstrumentSeat seat) {
        InstrumentKind kind = seat.kind();
        if (kind == null) return null;
        BlockPos main = seat.instrument();
        var f = InstrumentPosts.feet(kind);
        boolean standing = InstrumentPosts.standing(kind);
        if (kind.seated()) {
            // 方块模型里琴正面朝北、演奏者在南面朝北：就是演奏者面朝北的坐标
            Vector3f feet = new Vector3f((float) f.x, (float) f.y, (float) f.z);
            float move = VoiceBank.recentlyPlayed(main) ? 1 : 0;
            // 大提琴的凳面高 7.1 像素（tools/instrument_models/strings.py），前沿在双脚前 2.3 像素
            return new MusicianPoses.Musician(MusicianPoses.Mode.BOWED, kind, move, null, null, feet, standing,
                standing ? 0 : 7.1f - feet.y, 2.3f);
        }
        // 钟琴、管钟、无音高的打击乐：方块模型里演奏者在北面朝南，转 180° 成演奏者面朝北的坐标（侧放的大鼓人在东面朝西，转 90°）
        Vector3f feet = frame(kind, new Vector3f((float) f.x, (float) f.y, (float) f.z));
        List<int[]> notes = VoiceBank.notesAt(main);
        if (kind.unpitched()) return drums(entity, kind, notes, feet);
        MusicianPoses.Hands h = MusicianPoses.hands(entity.getId(), kind, notes, kind == InstrumentKind.TUBULAR_BELLS ? kind.low() : (kind.low() + kind.high()) / 2);
        Vector3f left, right;
        if (kind == InstrumentKind.GLOCKENSPIEL) {
            // 音条左长右短（模型 x 2–13，从演奏者看低音在右手边）：右手敲低音、左手敲高音，敲在前排音条中间
            right = turn(new Vector3f(2.5f + h.low * 10.5f, 12.3f + LIFT * (1 - h.lowPress), 6));
            left = turn(new Vector3f(2.5f + h.high * 10.5f, 12.3f + LIFT * (1 - h.highPress), 6));
        } else {
            // 管钟：右手一把皮锤敲管顶靠演奏者的一侧，往回收着；左手垂着
            int tube = Math.round(h.high * 7);
            right = turn(new Vector3f(3.5f + tube * 1.37f, 26.9f, 7.3f - LIFT * (1 - h.highPress)));
            left = null;
        }
        return new MusicianPoses.Musician(MusicianPoses.Mode.MALLETS, kind, 0, left, right, feet, true, 0, 0);
    }

    // —— 大鼓、小军鼓、吊镲、三角铁（站着敲）——

    /**
     * 无音高的打击乐：落点在方块模型的像素里（演奏者在北面朝南，右手在西边、x 小的一侧），和 tools/instrument_models/drums.py 的约定一致。
     * 大鼓侧放（方块状态多转 90°）：人在东面朝西，右手的羊毛槌打东面那面鼓皮偏右上（北、上）的地方，抬起时往回收（往东）；小军鼓：两根鼓棒在鼓皮靠演奏者的一侧轮流打；
     * 吊镲：两支毛线槌在镲靠演奏者的边上轮流打；三角铁：右手的钢棒从三角里面往下敲底边。
     */
    private static MusicianPoses.Musician drums(LivingEntity entity, InstrumentKind kind, List<int[]> notes, Vector3f feet) {
        Vector3f left = null, right;
        switch (kind) {
            case BASS_DRUM -> right = frame(kind, new Vector3f(11.6f + LIFT * (1 - press(notes, -1)), 10.0f, 6.5f));
            case TRIANGLE -> right = turn(new Vector3f(8.0f, 13.9f + 0.8f * LIFT * (1 - press(notes, -1)), 6.0f));
            default -> {
                float[] p = alternate(entity.getId(), notes);
                float y = kind == InstrumentKind.SNARE_DRUM ? 13.6f : 15.0f;
                float z = kind == InstrumentKind.SNARE_DRUM ? 5.2f : 4.2f;
                float spread = kind == InstrumentKind.SNARE_DRUM ? 1.6f : 3.0f;
                right = turn(new Vector3f(8 - spread, y + LIFT * (1 - p[1]), z));
                left = turn(new Vector3f(8 + spread, y + LIFT * (1 - p[0]), z));
            }
        }
        return new MusicianPoses.Musician(MusicianPoses.Mode.MALLETS, kind, 0, left, right, feet, true, 0, 0);
    }

    /** 一位鼓手两只手轮流敲：每个新落下的音交给上一个音以外的那只手（军鼓滚奏、镲连击，同一个键也左右交替）。 */
    private static final class Strokes {
        final java.util.ArrayDeque<long[]> recent = new java.util.ArrayDeque<>(); // {开始的时刻 ms, 0 左 1 右}
        int next = 1;
    }

    private static final java.util.Map<Integer, Strokes> STROKES = new java.util.HashMap<>();

    /** 两只手此刻「贴着」的程度 {左, 右}（1 刚落下、0 抬起）。 */
    private static float[] alternate(int id, List<int[]> notes) {
        if (STROKES.size() > 256) STROKES.clear();
        Strokes st = STROKES.computeIfAbsent(id, k -> new Strokes());
        long now = System.currentTimeMillis();
        List<Long> starts = new java.util.ArrayList<>();
        for (int[] n : notes) starts.add(now - n[1]);
        starts.sort(Long::compare);
        for (long start : starts) {
            boolean known = false;
            for (long[] r : st.recent) if (Math.abs(r[0] - start) < 15) known = true;
            if (known) continue;
            st.recent.addLast(new long[]{start, st.next});
            st.next = 1 - st.next;
        }
        float[] p = new float[2];
        while (!st.recent.isEmpty() && now - st.recent.peekFirst()[0] > 1000) st.recent.pollFirst();
        for (long[] r : st.recent) p[(int) r[1]] = Math.max(p[(int) r[1]], 1 - (now - r[0]) / STRIKE_MS);
        p[0] = Math.max(0, p[0]);
        p[1] = Math.max(0, p[1]);
        return p;
    }

    /** 方块模型像素绕方块中心转 180°。 */
    private static Vector3f turn(Vector3f p) {
        return new Vector3f(16 - p.x, p.y, 16 - p.z);
    }

    /** 工位乐器的方块像素 → 演奏者面朝北的坐标：人在北面朝南的转 180°；侧放的大鼓人在东面朝西，转 90°（西 → 北）。 */
    private static Vector3f frame(InstrumentKind kind, Vector3f p) {
        return kind == InstrumentKind.BASS_DRUM ? new Vector3f(16 - p.z, p.y, p.x) : turn(p);
    }

    // —— 定音鼓（琴凳）——

    /**
     * 坐在定音鼓后关联的琴凳上：双人琴凳每一格前面一面鼓，左手打坐的人左边那面、右手打右边那面；单人琴凳一面鼓，两手一左一右轮流打。
     * 鼓的模型按朝北放置画（踏板在南面朝着鼓手），和琴凳朝同一个方向，所以鼓的像素直接平移到琴凳坐标。
     */
    static MusicianPoses.Musician timpani(Level level, BlockPos bench, Vector3f feet) {
        List<@Nullable BlockPos> drums = StarbloomBenchBlock.linkedAll(level, bench);
        Vector3f left = null, right = null;
        if (drums.size() >= 2 && drums.get(0) != null && drums.get(1) != null) {
            left = drumHead(0, press(VoiceBank.notesAt(drums.get(0)), -1));
            right = drumHead(1, press(VoiceBank.notesAt(drums.get(1)), -1));
        } else {
            int cell = drums.size() >= 2 && drums.get(0) == null ? 1 : 0;
            BlockPos drum = drums.get(cell);
            List<int[]> notes = drum == null ? List.of() : VoiceBank.notesAt(drum);
            left = drumHead(cell, press(notes, 0)).add(-2.2f, 0, 0);
            right = drumHead(cell, press(notes, 1)).add(2.2f, 0, 0);
        }
        return new MusicianPoses.Musician(MusicianPoses.Mode.MALLETS, InstrumentKind.TIMPANI, 0, left, right, feet, false,
            (float) (StarbloomBenchBlock.TOP - StarbloomBenchBlock.FEET.y), (float) (feet.z - StarbloomBenchBlock.FRONT_EDGE));
    }

    /** 鼓皮上打的点（琴凳像素）：鼓皮中心往鼓手这边 2.5 像素，抬起时高 LIFT。 */
    private static Vector3f drumHead(int cell, float press) {
        return new Vector3f(8 + 16 * cell, 13.3f + LIFT * (1 - press), 10.5f - 16);
    }

    /** 这面鼓上刚落下的音有多「贴着」（1 刚落下、0 抬起）。parity ≥ 0 时只算音高奇偶为 parity 的音（单面鼓两手轮流）。 */
    private static float press(List<int[]> notes, int parity) {
        float p = 0;
        for (int[] n : notes) {
            if (parity >= 0 && Math.floorMod(n[0], 2) != parity) continue;
            p = Math.max(p, 1 - n[1] / STRIKE_MS);
        }
        return Math.max(0, p);
    }

    /** 是否坐在定音鼓后（关联的乐器是定音鼓）。 */
    static boolean isTimpani(BlockState mainState) {
        return mainState.getBlock() instanceof InstrumentBlock block && block.kind(mainState) == InstrumentKind.TIMPANI;
    }
}
