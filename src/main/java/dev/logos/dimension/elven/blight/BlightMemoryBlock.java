package dev.logos.dimension.elven.blight;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import dev.logos.dimension.corruption.CorruptionBlock;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/** 生成污染保留健康材质的稳定编号；编号随结构一起旋转，净化不会把树和地面变成空气。 */
public class BlightMemoryBlock extends CorruptionBlock {
    public static final MapCodec<BlightMemoryBlock> CODEC = simpleCodec(BlightMemoryBlock::new);
    public static final IntegerProperty MEMORY = IntegerProperty.create("memory", 0, 255);
    public static final IntegerProperty APPEARANCE = IntegerProperty.create("appearance", 0, 3);
    private static List<BlockState> palette;
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
    public static synchronized List<BlockState> palette() {
        if (palette == null) {
            String path = "/data/logos_dimension/blight_restoration.json";
            try (var in = Objects.requireNonNull(BlightMemoryBlock.class.getResourceAsStream(path), path);
                 var reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                palette = JsonParser.parseReader(reader).getAsJsonArray().asList().stream()
                        .map(j -> BlockState.CODEC.parse(JsonOps.INSTANCE, j).getOrThrow()).toList();
            } catch (Exception e) { throw new IllegalStateException("腐败还原表读取失败", e); }
        }
        return palette;
    }
    public static BlockState original(BlockState state) {
        int id = state.getValue(MEMORY);
        if (id >= palette().size()) throw new IllegalStateException("未知的腐败还原编号：" + id);
        return palette().get(id);
    }
    private BlockState transformed(BlockState state, BlockState original) {
        int id = palette().indexOf(original);
        if (id < 0) throw new IllegalStateException("腐败还原表缺少旋转后的材质：" + original);
        return state.setValue(MEMORY, id);
    }
    @Override protected BlockState rotate(BlockState s, Rotation r) { return transformed(s, original(s).rotate(r)); }
    @Override protected BlockState mirror(BlockState s, Mirror m) { return transformed(s, original(s).mirror(m)); }
}
