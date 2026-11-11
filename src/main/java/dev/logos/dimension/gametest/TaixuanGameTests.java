package dev.logos.dimension.gametest;

import com.google.gson.*;
import dev.logos.dimension.*;
import dev.logos.dimension.taixuan.TaixuanBlocks;
import dev.logos.dimension.taixuan.TaixuanStructure;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.pools.*;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 太玄灵境：原版拼接、模板方块、选址与自然候选点，以及实际摆放后的深渊、路线和宗门检查。 */
public final class TaixuanGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS = DeferredRegister.create(Registries.TEST_FUNCTION, LogosDimension.ID);
    private static final JsonObject DATA = manifest();
    /** 起点锚在画布上的位置：画布坐标减去它、按地标朝向转过去，再加上世界里的锚点。 */
    private static final int AX = DATA.getAsJsonArray("anchor_canvas").get(0).getAsInt(),
        AY = DATA.getAsJsonArray("anchor_canvas").get(1).getAsInt(), AZ = DATA.getAsJsonArray("anchor_canvas").get(2).getAsInt();
    private static ServerLevel cachedLevel;
    private static Site cached;

    public record Site(StructureStart start, BlockPos anchor, Rotation rotation) {
        public BlockPos pos(int x, int y, int z) {
            return anchor.offset(new BlockPos(x - AX, y - AY, z - AZ).rotate(rotation));
        }
        public BlockPos pos(JsonArray coordinates) {
            return pos(coordinates.get(0).getAsInt(), coordinates.get(1).getAsInt(), coordinates.get(2).getAsInt());
        }
        /** 深渊中心那一列（画布 x = z = 126）在世界里的位置。 */
        public BlockPos center(int y) {
            return pos(126, y, 126);
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
            "taixuan_template_blocks", TaixuanGameTests::templateBlocks,
            "taixuan_natural_habitat", TaixuanGameTests::habitat,
            "taixuan_abyss_and_sect", TaixuanGameTests::living);
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

    private static Holder.Reference<Structure> holder(ServerLevel level) {
        return level.registryAccess().lookupOrThrow(Registries.STRUCTURE)
            .getOrThrow(ResourceKey.create(Registries.STRUCTURE, LogosDimension.id("taixuan_sanctuary")));
    }

    public static TaixuanStructure structure(ServerLevel level) {
        return (TaixuanStructure) holder(level).value();
    }

    public static RandomSpreadStructurePlacement placement(ServerLevel level) {
        return (RandomSpreadStructurePlacement) level.registryAccess().lookupOrThrow(Registries.STRUCTURE_SET)
            .getOrThrow(ResourceKey.create(Registries.STRUCTURE_SET, LogosDimension.id("taixuan_sanctuaries"))).value().placement();
    }

    private static Site site(StructureStart start) {
        if (!start.isValid()) return null;
        var root = (PoolElementStructurePiece) start.getPieces().getFirst();
        var local = DATA.getAsJsonArray("anchor");
        var anchor = root.getPosition().offset(new BlockPos(local.get(0).getAsInt(), local.get(1).getAsInt(), local.get(2).getAsInt()).rotate(root.getRotation()));
        return new Site(start, anchor, root.getRotation());
    }

    /** 跳过选址，只看原版拼接能不能把所有部件接上。 */
    public static Site assembleLayout(ServerLevel level, ChunkPos at, long seed) {
        var holder = holder(level);var source = level.getChunkSource();var generator = source.getGenerator();
        return site(structure(level).layout().generate(holder, level.dimension(), level.registryAccess(), generator,
            generator.getBiomeSource(), source.randomState(), level.getStructureManager(), seed, at, 0, level, b -> true));
    }

    /** 和自然生成同一条路：先过选址（群系用结构自己的列表），再原版拼接。 */
    public static Site assemble(ServerLevel level, ChunkPos at, long seed) {
        var holder = holder(level);var source = level.getChunkSource();var generator = source.getGenerator();
        return site(holder.value().generate(holder, level.dimension(), level.registryAccess(), generator,
            generator.getBiomeSource(), source.randomState(), level.getStructureManager(), seed, at, 0, level, holder.value().biomes()::contains));
    }

    /** 只跑选址检查，不拼接。 */
    public static boolean siteFits(ServerLevel level, ChunkPos at) {
        var structure = structure(level);var source = level.getChunkSource();var generator = source.getGenerator();
        var context = new Structure.GenerationContext(level.registryAccess(), generator, generator.getBiomeSource(), source.randomState(),
            level.getStructureManager(), level.getSeed(), at, level, structure.biomes()::contains);
        return structure.siteFits(context);
    }

    /** 自然生成用的完整判断：通过选址，而且 600 格以内没有更好的候选点（不拼接）。 */
    public static boolean wins(ServerLevel level, ChunkPos at) {
        var structure = structure(level);var source = level.getChunkSource();var generator = source.getGenerator();
        var context = new Structure.GenerationContext(level.registryAccess(), generator, generator.getBiomeSource(), source.randomState(),
            level.getStructureManager(), level.getSeed(), at, level, structure.biomes()::contains);
        return structure.wins(context);
    }

    /** 按给定参数采样一处候选点（不拼接）；区块中心不在群系里时返回 null。 */
    public static TaixuanStructure.Measure measure(ServerLevel level, ChunkPos at, int biomeRadius, int rimRadius, int rimMin) {
        var structure = structure(level);var source = level.getChunkSource();var generator = source.getGenerator();
        var context = new Structure.GenerationContext(level.registryAccess(), generator, generator.getBiomeSource(), source.randomState(),
            level.getStructureManager(), level.getSeed(), at, level, structure.biomes()::contains);
        return structure.measure(context, biomeRadius, rimRadius, rimMin);
    }

    /** 云岫峰林附近的原版候选区块，由近到远（按格点一圈一圈往外）。 */
    public static List<ChunkPos> candidates(ServerLevel level, int rings) {
        var at = NativeWorlds.locateBiome(level, "cloudsea_cloud_peaks");
        var placement = placement(level);int spacing = placement.spacing();
        int gx = Math.floorDiv(at.getX() >> 4, spacing), gz = Math.floorDiv(at.getZ() >> 4, spacing);
        var out = new ArrayList<ChunkPos>();
        for (int radius = 0; radius <= rings; radius++) for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
            out.add(placement.getPotentialStructureChunk(level.getSeed(), (gx + dx) * spacing, (gz + dz) * spacing));
        }
        return out;
    }

    /** 前 count 处通过选址的真实候选点（只拼装、不摆放）。 */
    public static List<Site> naturalSites(ServerLevel level, int count) {
        var sites = new ArrayList<Site>();
        for (var chunk : candidates(level, 24)) {
            if (sites.size() >= count) break;
            if (!siteFits(level, chunk)) continue;
            var site = assemble(level, chunk, level.getSeed());
            if (site != null) sites.add(site);
        }
        return sites;
    }

    public static Site natural(ServerLevel level) {
        var sites = naturalSites(level, 1);
        if (sites.isEmpty()) throw new IllegalStateException("云岫峰林附近找不到通过选址的太玄灵境候选点");
        var site = sites.getFirst();
        System.out.println("TAIXUAN_NATURAL anchor=" + site.anchor() + " rotation=" + site.rotation() + " tiles=" + tiles(site));
        return site;
    }

    public static Site placeStudy(ServerLevel level) {
        if (cachedLevel != level || cached == null) {
            cached = natural(level);cachedLevel = level;
            SettlementGameTests.place(level, cached.start());
        }
        return cached;
    }

    public static long tiles(Site site) {
        return site.start().getPieces().stream().filter(p -> p instanceof PoolElementStructurePiece q && q.getElement() instanceof SinglePoolElement).count();
    }

    public static long footings(Site site) {
        return site.start().getPieces().stream().filter(p -> p instanceof PoolElementStructurePiece q && q.getElement() instanceof FeaturePoolElement).count();
    }

    /** 链台中心的太极（白石或磨制黑石），用来判断深渊那几个区块到了客户端。 */
    public static boolean isPlatformCenter(net.minecraft.world.level.block.state.BlockState state) {
        return state.is(Blocks.CALCITE) || state.is(Blocks.POLISHED_BLACKSTONE);
    }

    /**
     * manifest 里每条路线的每一格：脚下有碰撞，上面两格没有碰撞（渊雾、垂藤都算空）。
     * 返回走不通的格子（“路线 画布坐标 脚下/头顶/再上一格”），最多记 limit 条。
     */
    public static List<String> blockedRoutes(Level level, Site site, int limit) {
        var bad = new ArrayList<String>();
        for (var entry : DATA.getAsJsonObject("routes").entrySet()) {
            for (var point : entry.getValue().getAsJsonArray()) {
                var c = point.getAsJsonArray();var floor = site.pos(c);
                boolean ok = !level.getBlockState(floor).getCollisionShape(level, floor).isEmpty();
                for (int dy = 1; dy <= 2 && ok; dy++) ok = level.getBlockState(floor.above(dy)).getCollisionShape(level, floor.above(dy)).isEmpty();
                if (!ok && bad.size() < limit) bad.add(entry.getKey() + " " + c + " " + name(level, floor) + "/" + name(level, floor.above()) + "/" + name(level, floor.above(2)));
            }
        }
        return bad;
    }

    private static String name(Level level, BlockPos pos) {
        return BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).getPath();
    }

    /** 瀑布从泉眼一直到雾层里的接水池都是水；返回断开的格数。自然生成时水不会自己流下来，所以整条都画在模板里。 */
    public static int brokenWaterfalls(Level level, Site site) {
        int broken = 0;var gaps = new StringBuilder();
        for (var fall : DATA.getAsJsonObject("abyss").getAsJsonArray("waterfalls")) {
            var f = fall.getAsJsonArray();
            int fx = f.get(3).getAsInt(), fz = f.get(4).getAsInt(), top = f.get(1).getAsInt(), basin = f.get(5).getAsInt();
            for (int y = basin; y <= top; y++) {
                var p = site.pos(fx, y, fz);
                if (!level.getFluidState(p).is(FluidTags.WATER)) {
                    broken++;
                    if (gaps.length() < 400) gaps.append(fx).append(',').append(y).append(',').append(fz).append('=').append(name(level, p)).append(' ');
                }
            }
        }
        if (broken > 0) System.out.println("TAIXUAN_WATERFALL_GAPS " + site.anchor() + " " + site.rotation() + " " + gaps);
        return broken;
    }

    /**
     * 深渊竖井里（链台以上、深渊口以下，离中心 20～44 格）不该有原地形或别的地物：
     * 这里只能是空气、铁链、吊桥的木头和水；铁链上自然长出来的藤蔓也算。返回不该有的格子。
     */
    public static List<String> shaftLeftovers(Level level, Site site, int limit) {
        var heights = DATA.getAsJsonObject("heights");
        int platform = heights.get("platform").getAsInt(), rim = heights.get("rim").getAsInt();
        var bad = new ArrayList<String>();
        for (int k = 0; k < 16; k++) {
            double a = k * Math.PI / 8;
            for (int r = 20; r <= 44; r += 4) for (int y = platform + 6; y < rim; y += 5) {
                int x = 126 + (int) Math.round(r * Math.cos(a)), z = 126 + (int) Math.round(r * Math.sin(a));
                var state = level.getBlockState(site.pos(x, y, z));
                boolean ok = state.isAir() || state.is(TaixuanBlocks.DARK_IRON.get()) || state.is(Blocks.IRON_CHAIN) || !state.getFluidState().isEmpty()
                    || state.is(BlockTags.PLANKS) || state.is(BlockTags.WOODEN_STAIRS) || state.is(BlockTags.WOODEN_FENCES) || state.is(Blocks.LANTERN)
                    || state.is(Blocks.VINE);
                if (!ok && bad.size() < limit) bad.add(x + "," + y + "," + z + "=" + BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath());
            }
        }
        return bad;
    }

    private static void assembly(GameTestHelper helper) {
        var level = level(helper);
        long expected = DATA.getAsJsonArray("footings").size(), tiles = DATA.get("tiles").getAsLong();
        for (long seed : new long[]{0, 81}) {
            var site = assembleLayout(level, new ChunkPos(-160, -160), seed);
            helper.assertTrue(site != null, "原版拼接没有得到起始部件");
            helper.assertTrue(tiles(site) == tiles, "原版碰撞或深度遗漏部件：" + tiles(site) + " / " + tiles);
            helper.assertTrue(footings(site) == expected, "接地石基子件不齐：" + footings(site) + " / " + expected);
            var box = site.start().getBoundingBox();
            helper.assertTrue(box.maxY() <= level.getMaxY() && box.minY() >= level.getMinY(), "地标超出世界高度：" + box);
            // 锚在世界 y = 画布 y + 偏移（起始高度比它高 1，原版会把起点部件往下挪一格）
            int designed = DATA.getAsJsonArray("anchor_canvas").get(1).getAsInt() + DATA.get("world_y_offset").getAsInt();
            helper.assertTrue(site.anchor().getY() == designed, "起点高度不对：" + site.anchor() + "，应为 y=" + designed);
            System.out.println("TAIXUAN_ASSEMBLY seed=" + seed + " tiles=" + tiles(site) + " footings=" + footings(site) + " bounds=" + box);
        }
        helper.succeed();
    }

    /** 模板里的每一个方块 ID（含拼接块的最终方块）都要在 26.1 的方块注册表里；不认识的 ID 读进来会悄悄变成空气。 */
    private static void templateBlocks(GameTestHelper helper) {
        var resources = helper.getLevel().getServer().getResourceManager();
        var unknown = new TreeSet<String>();int files = 0;
        for (var entry : resources.listResources("structure/taixuan", id -> id.getPath().endsWith(".nbt")).entrySet()) {
            if (!entry.getKey().getNamespace().equals(LogosDimension.ID)) continue;
            try (var input = entry.getValue().open()) {
                var tag = NbtIo.readCompressed(input, NbtAccounter.unlimitedHeap());
                for (var state : tag.getListOrEmpty("palette")) check(((CompoundTag) state).getStringOr("Name", ""), unknown);
                for (var block : tag.getListOrEmpty("blocks")) {
                    var nbt = ((CompoundTag) block).getCompoundOrEmpty("nbt");
                    if (nbt.contains("final_state")) check(nbt.getStringOr("final_state", "").split("\\[")[0], unknown);
                }
            } catch (java.io.IOException error) { throw new IllegalStateException(entry.getKey().toString(), error); }
            files++;
        }
        helper.assertTrue(files >= DATA.get("tiles").getAsInt(), "模板没有全部读到：" + files);
        helper.assertTrue(unknown.isEmpty(), "模板里有 26.1 不认识的方块：" + unknown);
        System.out.println("TAIXUAN_TEMPLATE_BLOCKS files=" + files);
        helper.succeed();
    }

    private static void check(String name, Set<String> unknown) {
        var id = Identifier.tryParse(name);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) unknown.add(name);
    }

    private static void habitat(GameTestHelper helper) {
        var level = level(helper);var site = natural(level);var structure = structure(level);
        helper.assertTrue(tiles(site) == DATA.get("tiles").getAsLong(), "自然候选点拼接被截断");
        helper.assertTrue(NativeWorlds.vanilla(level), "远行大陆不再使用原版噪声地形");
        helper.assertTrue(site.start().getStructure().terrainAdaptation() == TerrainAdjustment.NONE, "结构改变了原有地形密度");
        // 选址只采了 25 个点；这里每 8 格采一次，地标实际占的范围（深渊口外的空地，半径约 112）里云岫峰林至少占八成五
        var source = level.getChunkSource();var biomes = source.getGenerator().getBiomeSource();var sampler = source.randomState().sampler();
        var center = site.center(0);int inside = 0, total = 0;
        for (int dx = -112; dx <= 112; dx += 8) for (int dz = -112; dz <= 112; dz += 8) {
            if (dx * dx + dz * dz > 112 * 112) continue;
            total++;
            var biome = biomes.getNoiseBiome(QuartPos.fromBlock(center.getX() + dx), QuartPos.fromBlock(103), QuartPos.fromBlock(center.getZ() + dz), sampler);
            if (structure.biomes().contains(biome)) inside++;
        }
        // 深渊口一圈的地面高度（相对深渊口），用来看入口和原地形接得怎么样
        int rim = DATA.getAsJsonObject("heights").get("rim").getAsInt() + DATA.get("world_y_offset").getAsInt();
        var rims = new ArrayList<Integer>();
        for (int k = 0; k < 32; k++) {
            double a = k * Math.PI / 16;
            rims.add(source.getGenerator().getBaseHeight(center.getX() + (int) Math.round(Math.cos(a) * structure.rimRadius()),
                center.getZ() + (int) Math.round(Math.sin(a) * structure.rimRadius()), Heightmap.Types.OCEAN_FLOOR_WG, level, source.randomState()) - rim);
        }
        Collections.sort(rims);
        System.out.println("TAIXUAN_HABITAT anchor=" + site.anchor() + " cloud_peaks=" + inside + "/" + total + " rim_ground_minus_rim min=" + rims.getFirst()
            + " p50=" + rims.get(16) + " max=" + rims.getLast());
        helper.assertTrue(inside >= total * 85 / 100, "整座地标里云岫峰林只占 " + inside + "/" + total);
        // 600 格以内不会有第二处
        var placement = placement(level);int spacing = placement.spacing();var chunk = site.start().getChunkPos();
        int gx = Math.floorDiv(chunk.x(), spacing), gz = Math.floorDiv(chunk.z(), spacing);
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            var other = placement.getPotentialStructureChunk(level.getSeed(), (gx + dx) * spacing, (gz + dz) * spacing);
            if (other.equals(chunk) || Math.hypot(other.getMiddleBlockX() - chunk.getMiddleBlockX(), other.getMiddleBlockZ() - chunk.getMiddleBlockZ()) > 600) continue;
            helper.assertTrue(!wins(level, other), "600 格以内还有一处也会生成：" + other);
        }
        // 群系不对的候选点选址必须拒绝
        var away = new ChunkPos((site.anchor().getX() >> 4) + 400, (site.anchor().getZ() >> 4) + 400);
        var awayBiome = biomes.getNoiseBiome(QuartPos.fromBlock(away.getMiddleBlockX()), QuartPos.fromBlock(103), QuartPos.fromBlock(away.getMiddleBlockZ()), sampler);
        if (!structure.biomes().contains(awayBiome)) helper.assertTrue(!siteFits(level, away), "不在云岫峰林的候选点也通过了选址");
        helper.succeed();
    }

    private static void living(GameTestHelper helper) {
        var level = level(helper);var site = placeStudy(level);
        // 整个地标随机旋转时，薄窗必须随建筑转向，含水属性也不能丢失。
        var window = TaixuanBlocks.LATTICE.get();
        var facing = net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING;
        var water = net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED;
        for (var direction : Direction.Plane.HORIZONTAL) for (var rotation : Rotation.values()) {
            var state = window.defaultBlockState().setValue(facing, direction).setValue(water, true).rotate(rotation);
            helper.assertTrue(state.getValue(facing) == rotation.rotate(direction) && state.getValue(water), "花窗旋转或含水属性错误");
        }
        for (var sample : DATA.getAsJsonObject("circulation").getAsJsonArray("retaining_samples")) {
            var column = sample.getAsJsonArray();
            for (int y = column.get(1).getAsInt(); y <= column.get(2).getAsInt(); y++) {
                var p = site.pos(column.get(0).getAsInt(), y, column.get(3).getAsInt());
                helper.assertTrue(level.getBlockState(p).isSolid(), "浮峰殿阁台基没有接实");
            }
        }
        // 室内检查使用最终导出模块记录的位置，真正读取世界中的方块。
        for (var room : DATA.getAsJsonArray("rooms")) {
            var position = site.pos(room.getAsJsonObject().getAsJsonArray("pos"));
            for (int dy = 0; dy < 2; dy++) helper.assertTrue(level.getBlockState(position.above(dy)).getCollisionShape(level, position.above(dy)).isEmpty(),
                "房间通道被地形或其他部件堵住：" + room.getAsJsonObject().get("building"));
            helper.assertTrue(level.getBlockState(position.below()).isSolid(), "室内楼板丢失");
        }
        for (var pond : DATA.getAsJsonObject("landscape").getAsJsonArray("ponds")) {
            var p = site.pos(pond.getAsJsonArray());
            helper.assertTrue(level.getBlockState(p.below()).is(Blocks.WATER), "水潭缺少实际水体");
            helper.assertTrue(level.getBlockState(p.below(3)).is(Blocks.CLAY), "水潭缺少封闭池底");
        }
        for (var pine : DATA.getAsJsonObject("landscape").getAsJsonArray("pines")) {
            var p = site.pos(pine.getAsJsonArray());
            helper.assertTrue(level.getBlockState(p).isSolid(), "崖松没有接地");
            helper.assertTrue(level.getBlockState(p.above()).is(Blocks.SPRUCE_LOG), "崖松树干未接入地面");
        }
        helper.assertTrue(footings(site) == DATA.getAsJsonArray("footings").size(), "缺少承重石基");
        // 深渊：每条路都走得通（深渊口观景台、吊桥、链台、栈道、雾底、镇渊柱楼梯和门、柱顶到山门的桥、宗门各桥）
        var blocked = blockedRoutes(level, site, 12);
        helper.assertTrue(blocked.isEmpty(), "路线走不通：" + blocked);
        helper.assertTrue(brokenWaterfalls(level, site) == 0, "瀑布落水柱断开");
        var leftovers = shaftLeftovers(level, site, 12);
        helper.assertTrue(leftovers.isEmpty(), "深渊里有残留地形：" + leftovers);
        int mistBottom = DATA.getAsJsonObject("heights").getAsJsonArray("mist").get(0).getAsInt();
        var mist = TaixuanBlocks.ABYSS_MIST.get();
        for (int k = 0; k < 8; k++) {
            double a = k * Math.PI / 4;
            int x = 126 + (int) Math.round(30 * Math.cos(a)), z = 126 + (int) Math.round(30 * Math.sin(a));
            for (int y = mistBottom; y < mistBottom + 10; y++)
                helper.assertTrue(level.getBlockState(site.pos(x, y, z)).is(mist), "雾层有缺口：" + x + "," + y + "," + z);
        }
        var details = DATA.getAsJsonObject("abyss").getAsJsonObject("details");
        helper.assertTrue(isPlatformCenter(level.getBlockState(site.pos(details.getAsJsonObject("platform").getAsJsonArray("center")))), "链台中心缺太极");
        helper.assertTrue(level.getBlockState(site.pos(details.getAsJsonObject("arena").getAsJsonArray("dais"))).is(TaixuanBlocks.DARK_IRON.get()), "雾底石台缺玄铁心");
        helper.succeed();
    }

    private TaixuanGameTests() {}
}
