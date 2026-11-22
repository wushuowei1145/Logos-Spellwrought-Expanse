package dev.logos.dimension.elven.blight;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import dev.logos.dimension.corruption.CorruptionBlock;
import dev.logos.dimension.corruption.RememberedCorruption;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/** 生成污染保留健康材质的稳定编号；编号随结构一起旋转，净化不会把树和地面变成空气。 */
public class BlightMemoryBlock extends CorruptionBlock implements RememberedCorruption {
    public static final MapCodec<BlightMemoryBlock> CODEC = simpleCodec(BlightMemoryBlock::new);
    public static final IntegerProperty MEMORY = IntegerProperty.create("memory", 0, 255);
    public static final IntegerProperty APPEARANCE = IntegerProperty.create("appearance", 0, 3);
    public BlightMemoryBlock(Properties p) {
        this(p, 0, 0);
    }
    public BlightMemoryBlock(Properties p, int appearance, int memory) {
        super(p);
        registerDefaultState(stateDefinition.any().setValue(MEMORY, memory).setValue(APPEARANCE, appearance));
    }
    @Override protected MapCodec<BlightMemoryBlock> codec() { return CODEC; }
    @Override protected void onPlace(BlockState s, net.minecraft.world.level.Level l, net.minecraft.core.BlockPos p, BlockState old, boolean moved) {
        super.onPlace(s, l, p, old, moved);
        if (l instanceof net.minecraft.server.level.ServerLevel level) BlightReclamation.get(level).changed(p);
    }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { b.add(MEMORY, APPEARANCE); }

    /**
     * 还原表与它的旋转、镜像对照。结构放置在多条世界生成线程上逐格旋转，所以全部预先算好：
     * 类初始化时读一次（JVM 保证只初始化一次、对所有线程可见），之后只查数组，不加锁、不线性查找。
     */
    private static final class Palette {
        static final List<BlockState> STATES;
        /** {@code ROTATED[rotation][id]}：转过之后的编号，表里没有时是 -1。 */
        static final int[][] ROTATED;
        /** {@code MIRRORED[mirror][id]}：镜像之后的编号，表里没有时是 -1。 */
        static final int[][] MIRRORED;
        static {
            String path = "/data/logos_dimension/blight_restoration.json";
            try (var in = Objects.requireNonNull(BlightMemoryBlock.class.getResourceAsStream(path), path);
                 var reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                STATES = JsonParser.parseReader(reader).getAsJsonArray().asList().stream()
                        .map(j -> BlockState.CODEC.parse(JsonOps.INSTANCE, j).getOrThrow()).toList();
            } catch (Exception e) { throw new IllegalStateException("腐败还原表读取失败", e); }
            var index = new Object2IntOpenHashMap<BlockState>();
            index.defaultReturnValue(-1);
            for (int i = 0; i < STATES.size(); i++) index.putIfAbsent(STATES.get(i), i);
            Rotation[] rotations = Rotation.values();
            Mirror[] mirrors = Mirror.values();
            ROTATED = new int[rotations.length][STATES.size()];
            MIRRORED = new int[mirrors.length][STATES.size()];
            for (int i = 0; i < STATES.size(); i++) {
                for (Rotation r : rotations) ROTATED[r.ordinal()][i] = index.getInt(STATES.get(i).rotate(r));
                for (Mirror m : mirrors) MIRRORED[m.ordinal()][i] = index.getInt(STATES.get(i).mirror(m));
            }
        }
    }

    public static List<BlockState> palette() { return Palette.STATES; }
    public static BlockState original(BlockState state) {
        int id = state.getValue(MEMORY);
        if (id >= Palette.STATES.size()) throw new IllegalStateException("未知的腐败还原编号：" + id);
        return Palette.STATES.get(id);
    }
    @Override public BlockState remembered(BlockState state) { return original(state); }
    private static BlockState transformed(BlockState state, int[][] table, int which, Object how) {
        int from = state.getValue(MEMORY);
        if (from >= Palette.STATES.size()) throw new IllegalStateException("未知的腐败还原编号：" + from);
        int id = table[which][from];
        if (id < 0) throw new IllegalStateException("腐败还原表缺少" + how + "后的材质：" + Palette.STATES.get(from));
        return state.setValue(MEMORY, id);
    }
    @Override protected BlockState rotate(BlockState s, Rotation r) { return transformed(s, Palette.ROTATED, r.ordinal(), r); }
    @Override protected BlockState mirror(BlockState s, Mirror m) { return transformed(s, Palette.MIRRORED, m.ordinal(), m); }
}
