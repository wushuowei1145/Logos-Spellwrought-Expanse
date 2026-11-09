package dev.logos.dimension.gametest;

import com.google.gson.*;
import dev.logos.dimension.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.resources.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.pools.*;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 太玄灵境的原版拼接、自然候选选址、室内净空及景观测试。 */
public final class TaixuanGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS = DeferredRegister.create(Registries.TEST_FUNCTION, LogosDimension.ID);
    private static ServerLevel cachedLevel;
    private static Site cached;

    public record Site(StructureStart start, BlockPos anchor, Rotation rotation) {
        public BlockPos pos(int x, int y, int z) {
            return anchor.offset(new BlockPos(x - 126, y - 4, z - 126).rotate(rotation));
        }
        public BlockPos pos(JsonArray coordinates) {
            return pos(coordinates.get(0).getAsInt(), coordinates.get(1).getAsInt(), coordinates.get(2).getAsInt());
        }
    }

    public static JsonObject manifest() {
        var input = Objects.requireNonNull(TaixuanGameTests.class.getResourceAsStream("/data/logos_dimension/taixuan_manifest.json"));
        try (var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (java.io.IOException error) { throw new IllegalStateException(error); }
    }

    public static void register(IEventBus bus) {
        var tests = Map.<String, Consumer<GameTestHelper>>of(
            "taixuan_native_assembly", TaixuanGameTests::assembly,
            "taixuan_natural_habitat", TaixuanGameTests::habitat,
            "taixuan_rooms_and_gardens", TaixuanGameTests::living);
        tests.forEach((name, test) -> FUNCTIONS.register(name, () -> test));
        FUNCTIONS.register(bus);
        bus.addListener((RegisterGameTestsEvent event) -> {
            var environment = event.registerEnvironment(LogosDimension.id("taixuan"), new TestEnvironmentDefinition.AllOf());
            for (String name : tests.keySet()) event.registerTest(LogosDimension.id(name), new FunctionGameTestInstance(
                ResourceKey.create(Registries.TEST_FUNCTION, LogosDimension.id(name)),
                new TestData<>(environment, Identifier.withDefaultNamespace("empty"), 12000, 0, true, Rotation.NONE, false, 1, 1, false, 16)));
        });
    }

    public static ServerLevel level(GameTestHelper helper) {
        return Objects.requireNonNull(helper.getLevel().getServer().getLevel(LogosDimension.CLOUDSEA));
    }

    public static Site assemble(ServerLevel level, ChunkPos at, long seed, boolean biomeFilter) {
        var holder = level.registryAccess().lookupOrThrow(Registries.STRUCTURE)
            .getOrThrow(ResourceKey.create(Registries.STRUCTURE, LogosDimension.id("taixuan_sanctuary")));
        var source = level.getChunkSource();
        var generator = source.getGenerator();
        var start = holder.value().generate(holder, level.dimension(), level.registryAccess(), generator,
            generator.getBiomeSource(), source.randomState(), level.getStructureManager(), seed, at, 0, level,
            biomeFilter ? holder.value().biomes()::contains : b -> true);
        if (!start.isValid()) return null;
        var root = (PoolElementStructurePiece) start.getPieces().getFirst();
        var anchor = root.getPosition().offset(new BlockPos(14, 4, 14).rotate(root.getRotation()));
        return new Site(start, anchor, root.getRotation());
    }

    public static long tiles(Site site) {
        return site.start().getPieces().stream().filter(p -> p instanceof PoolElementStructurePiece q && q.getElement() instanceof SinglePoolElement).count();
    }

    public static long footings(Site site) {
        return site.start().getPieces().stream().filter(p -> p instanceof PoolElementStructurePiece q && q.getElement() instanceof FeaturePoolElement).count();
    }

    public static Site natural(ServerLevel level) {
        var at = NativeWorlds.locateBiome(level, "taixuan_vale");
        var placement = (RandomSpreadStructurePlacement) level.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET)
            .getOrThrow(ResourceKey.create(Registries.STRUCTURE_SET, LogosDimension.id("taixuan_sanctuaries")) ).value().placement();
        int spacing = placement.spacing();
        int gx = Math.floorDiv(at.getX() >> 4, spacing), gz = Math.floorDiv(at.getZ() >> 4, spacing);
        var source = level.getChunkSource();
        var biome = ResourceKey.create(Registries.BIOME, LogosDimension.id("taixuan_vale"));
        for (int radius = 0; radius <= 16; radius++) for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
            var chunk = placement.getPotentialStructureChunk(level.getSeed(), (gx + dx) * spacing, (gz + dz) * spacing);
            if (!source.getGenerator().getBiomeSource().getNoiseBiome(chunk.x() * 4 + 2, 24, chunk.z() * 4 + 2, source.randomState().sampler()).is(biome)) continue;
            var site = assemble(level, chunk, level.getSeed(), true);
            if (site != null) {
                System.out.println("TAIXUAN_NATURAL biome=" + at + " candidate=" + chunk + " anchor=" + site.anchor() + " tiles=" + tiles(site));
                return site;
            }
        }
        throw new IllegalStateException("太玄灵境附近未找到通过真实群系过滤的原版结构候选点");
    }

    public static Site placeStudy(ServerLevel level) {
        if (cachedLevel != level || cached == null) {
            cached = natural(level);cachedLevel = level;
            SettlementGameTests.place(level, cached.start());
        }
        return cached;
    }

    private static void assembly(GameTestHelper helper) {
        var level = level(helper);
        var expected = manifest().getAsJsonArray("footings").size();
        for (long seed : new long[]{0, 81}) {
            var site = assemble(level, new ChunkPos(-160, -160), seed, false);
            helper.assertTrue(site != null, "原版拼接没有得到起始部件");
            helper.assertTrue(tiles(site) == 729, "原版碰撞或深度遗漏部件：" + tiles(site));
            helper.assertTrue(footings(site) == expected, "接地石基子件不齐：" + footings(site) + " / " + expected);
            helper.assertTrue(site.start().getBoundingBox().maxY() <= level.getMaxY(), "浮峰或高塔超过世界高度");
            System.out.println("TAIXUAN_ASSEMBLY seed=" + seed + " tiles=" + tiles(site) + " footings=" + footings(site) + " bounds=" + site.start().getBoundingBox());
        }
        helper.succeed();
    }

    private static void habitat(GameTestHelper helper) {
        var site = natural(level(helper));
        helper.assertTrue(tiles(site) == 729, "自然候选点拼接被截断");
        helper.assertTrue(NativeWorlds.vanilla(level(helper)), "远行大陆不再使用原版噪声地形");
        helper.assertTrue(site.start().getStructure().terrainAdaptation() == TerrainAdjustment.NONE, "结构改变了原有地形密度");
        helper.succeed();
    }

    private static void living(GameTestHelper helper) {
        var level = level(helper);var site = placeStudy(level);var data = manifest();
        // 整个地标随机旋转时，薄窗必须随建筑转向，含水属性也不能丢失。
        var window = dev.logos.dimension.taixuan.TaixuanBlocks.LATTICE.get();
        var facing = net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING;
        var water = net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED;
        for (var direction : Direction.Plane.HORIZONTAL) for (var rotation : Rotation.values()) {
            var state = window.defaultBlockState().setValue(facing, direction).setValue(water, true).rotate(rotation);
            helper.assertTrue(state.getValue(facing) == rotation.rotate(direction) && state.getValue(water), "花窗旋转或含水属性错误");
        }
        // 同时读脚边和高处，防止只清树干底部却留下悬空树冠。
        for (int[] lane : new int[][]{{38,63},{63,88},{188,213},{126,220}}) {
            for (int y : new int[]{6,14,29}) {
                var position = site.pos(lane[0], y, lane[1]);
                helper.assertTrue(level.getBlockState(position).isAir(), "坊巷有残留树木或地形遮挡");
            }
        }
        for (var sample : data.getAsJsonObject("circulation").getAsJsonArray("retaining_samples")) {
            var column = sample.getAsJsonArray();
            for (int y = column.get(1).getAsInt(); y <= column.get(2).getAsInt(); y++) {
                var p = site.pos(column.get(0).getAsInt(), y, column.get(3).getAsInt());
                helper.assertTrue(level.getBlockState(p).isSolid(), "浮峰殿阁台基没有接实");
            }
        }
        for (var entry : data.getAsJsonArray("landmarks")) {
            var landmark = entry.getAsJsonObject();
            if (!landmark.get("name").getAsString().startsWith("city_watch_")) continue;
            var center = landmark.getAsJsonArray("center");
            var p = site.pos(center.get(0).getAsInt() + 4, 7, center.get(2).getAsInt() + 3);
            helper.assertTrue(level.getBlockState(p).is(Blocks.STONE_BRICKS), "角楼缺少落地塔身");
        }
        // 室内检查使用最终导出模块记录的位置，真正读取世界中的方块。
        for (var room : data.getAsJsonArray("rooms")) {
            var position = site.pos(room.getAsJsonObject().getAsJsonArray("pos"));
            for (int dy = 0; dy < 2; dy++) helper.assertTrue(level.getBlockState(position.above(dy)).getCollisionShape(level, position.above(dy)).isEmpty(),
                "房间通道被地形或其他部件堵住：" + room.getAsJsonObject().get("building"));
            helper.assertTrue(level.getBlockState(position.below()).isSolid(), "室内楼板丢失");
        }
        for (var pond : data.getAsJsonObject("landscape").getAsJsonArray("ponds")) {
            var coordinates = pond.getAsJsonArray();var p = site.pos(coordinates);
            helper.assertTrue(level.getBlockState(p.below()).is(Blocks.WATER), "水潭缺少实际水体");
            helper.assertTrue(level.getBlockState(p.below(3)).is(Blocks.CLAY), "水潭缺少封闭池底");
            int rx = coordinates.get(3).getAsInt(), rz = coordinates.get(4).getAsInt();
            for (int dx = -rx; dx <= rx; dx++) for (int dz = -rz; dz <= rz; dz++) {
                if ((double) dx * dx / (rx * rx) + (double) dz * dz / (rz * rz) > 1) continue;
                for (int[] direction : new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
                    int nx = dx + direction[0], nz = dz + direction[1];
                    if ((double) nx * nx / (rx * rx) + (double) nz * nz / (rz * rz) <= 1) continue;
                    var wall = site.pos(coordinates.get(0).getAsInt() + nx, coordinates.get(1).getAsInt() - 1, coordinates.get(2).getAsInt() + nz);
                    helper.assertTrue(level.getBlockState(wall).isSolid(), "池岸存在漏水格");
                }
            }
        }
        for (var pine : data.getAsJsonObject("landscape").getAsJsonArray("pines")) {
            var p = site.pos(pine.getAsJsonArray());
            helper.assertTrue(level.getBlockState(p).isSolid(), "崖松没有接地");
            helper.assertTrue(level.getBlockState(p.above()).is(Blocks.SPRUCE_LOG), "崖松树干未接入地面");
        }
        helper.assertTrue(footings(site) == data.getAsJsonArray("footings").size(), "城市缺少承重石基");
        helper.succeed();
    }

    private TaixuanGameTests() {}
}
