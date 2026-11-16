package dev.logos.dimension.gametest;

import dev.logos.dimension.NativeWorlds;
import dev.logos.dimension.corruption.CorruptionNetworks;
import dev.logos.dimension.elven.blight.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;

/** 净化必须还原树木、保留相邻污染与玩家改建，并在早拆核心和重载后保持一致。 */
final class BlightReclamationTests {
    private record Area(ServerLevel world, BlockPos core, BlockPos other) {
        void close() {
            for (BlockPos p : List.of(core, other)) {
                if (world.getBlockState(p).is(ElvenBlight.NODE.get())) world.removeBlock(p, false);
                world.setChunkForced(p.getX() >> 4, p.getZ() >> 4, false);
            }
        }
    }
    private static Area area(GameTestHelper h) {
        var origin = h.absolutePos(BlockPos.ZERO);
        int x = (200000 + Math.floorMod(origin.getX(), 4096) * 16) & ~15, z = (200000 + Math.floorMod(origin.getZ(), 4096) * 16) & ~15;
        var area = new Area(h.getLevel(), new BlockPos(x + 8, 240, z + 8), new BlockPos(x + 24, 240, z + 8));
        for (BlockPos p : List.of(area.core, area.other)) {
            area.world.setChunkForced(p.getX() >> 4, p.getZ() >> 4, true);
            area.world.getChunk(p);
        }
        return area;
    }
    private static BlockState wood() {
        int id = -1;
        var palette = BlightMemoryBlock.palette();
        // 按实际注册 ID 查找，不依赖编号排列。
        for (int i = 0; i < palette.size(); i++)
            if (net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(palette.get(i).getBlock()).toString().equals("logos_dimension:elven_heartwood")) { id = i; break; }
        if (id < 0) throw new IllegalStateException("还原表缺少精灵古木");
        return ElvenBlight.MEMORY.get().defaultBlockState().setValue(BlightMemoryBlock.MEMORY, id);
    }
    static void cleanup(GameTestHelper h) {
        var f = area(h);
        var memory = wood();
        var original = BlightMemoryBlock.original(memory);
        BlockPos first = f.core.below(), second = f.other.below(), built = first.east();
        f.world.setBlockAndUpdate(first, memory);
        f.world.setBlockAndUpdate(second, memory);
        f.world.setBlockAndUpdate(built, memory);
        f.world.setBlockAndUpdate(f.core, ElvenBlight.NODE.get().defaultBlockState());
        f.world.setBlockAndUpdate(f.other, ElvenBlight.NODE.get().defaultBlockState());
        final boolean[] broken = {false};
        h.succeedWhen(RealTimeWait.paced(() -> {
            var data = CorruptionNetworks.get(f.world);
            if (!broken[0]) {
                h.assertTrue(data.ofCore(f.core) != null && data.at(first) == data.ofCore(f.core) && original == data.originalAt(f.world, first), "等待生成污染登记与健康材质记录");
                f.world.setBlockAndUpdate(built, Blocks.DIAMOND_BLOCK.defaultBlockState());
                f.world.removeBlock(f.core, false);
                broken[0] = true;
            }
            h.assertTrue(f.world.getBlockState(first) == original, "清理后没有恢复健康古木");
            try {
                h.assertTrue(f.world.getBlockState(second) == memory && data.ofCore(f.other) != null, "清理一个核心误清了邻区");
                h.assertTrue(f.world.getBlockState(built).is(Blocks.DIAMOND_BLOCK), "净化覆盖了玩家改建");
                h.assertTrue(!BlightReclamation.get(f.world).cell(ChunkPos.containing(first)).active(), "未记录已收复区域");
                // 同一地区还没登记完便拆掉第二个核心，剩余污染也必须能恢复。
                f.world.removeBlock(f.other, false);
                h.succeed();
            } finally { f.close(); }
        }));
    }
    static void saved(GameTestHelper h) {
        var f = area(h);
        var memory = wood();
        var original = BlightMemoryBlock.original(memory);
        for (int id = 0; id < BlightMemoryBlock.palette().size(); id++) {
            var state = memory.setValue(BlightMemoryBlock.MEMORY, id);
            var healthy = BlightMemoryBlock.original(state);
            for (Rotation rotation : Rotation.values()) for (Mirror mirror : Mirror.values())
                h.assertTrue(BlightMemoryBlock.original(state.rotate(rotation).mirror(mirror)) == healthy.rotate(rotation).mirror(mirror), "旋转或镜像后还原材质错误：" + healthy);
        }
        f.world.setBlockAndUpdate(f.core.below(), memory);
        f.world.setBlockAndUpdate(f.core, ElvenBlight.NODE.get().defaultBlockState());
        var data = BlightReclamation.get(f.world);
        data.source(f.world, f.core.offset(32, 0, 0), true);
        h.assertTrue(!CorruptionNetworks.get(f.world).ofCore(f.core).passive(), "源头死后区域仍被动生长");
        var savedNetworks = CorruptionNetworks.CODEC.encodeStart(NbtOps.INSTANCE, CorruptionNetworks.get(f.world)).getOrThrow();
        h.assertTrue(!CorruptionNetworks.CODEC.parse(NbtOps.INSTANCE, savedNetworks).getOrThrow().ofCore(f.core).passive(), "网络读档丢失停止供养状态");
        // 在区段扫描前就拆掉核心；不能依赖已经建立好的网络来还原。
        f.world.removeBlock(f.core, false);
        var encoded = BlightReclamation.CODEC.encodeStart(NbtOps.INSTANCE, data).getOrThrow();
        var reloaded = BlightReclamation.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();
        h.assertTrue(!reloaded.cell(ChunkPos.containing(f.core)).active() && !reloaded.supplied(f.core), "读档丢失净化进度或源头击败状态");
        h.succeedWhen(RealTimeWait.paced(() -> {
            h.assertTrue(f.world.getBlockState(f.core.below()) == original, "提前拆掉核心导致污染无法还原");
            f.close();
            h.succeed();
        }));
    }
    static void natural(GameTestHelper h) {
        var world = ElvenForestGameTests.level(h);
        var at = NativeWorlds.locateBiome(world, "blighted_elven_forest");
        List<BlockPos> nodes = new ArrayList<>();
        List<ChunkPos> forced = new ArrayList<>();
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
            var chunk = new ChunkPos((at.getX() >> 4) + x, (at.getZ() >> 4) + z);
            forced.add(chunk);
            world.setChunkForced(chunk.x(), chunk.z(), true);
            var loaded = world.getChunk(chunk.x(), chunk.z());
            for (BlockPos p : loaded.getBlockEntitiesPos()) if (world.getBlockEntity(p) instanceof BlightNode) nodes.add(p);

        }
        h.assertTrue(!nodes.isEmpty(), "实际原版群系没有生成区域核心");
        h.succeedWhen(RealTimeWait.paced(() -> {
            h.assertTrue(nodes.stream().anyMatch(p -> {
                var data = CorruptionNetworks.get(world);
                var network = data.ofCore(p);
                return network != null && network.size() >= 30 && java.util.stream.IntStream.rangeClosed(1, 8)
                        .anyMatch(d -> data.at(p.below(d)) == network && data.originalAt(world, p.below(d)) != null);
            }), "自然区域核心未接上污染和原方块记录");
            for (ChunkPos chunk : forced) world.setChunkForced(chunk.x(), chunk.z(), false);
            h.succeed();
        }));
    }
}
