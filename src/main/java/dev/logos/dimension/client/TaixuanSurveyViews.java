package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.*;
import dev.logos.dimension.cloudsea.JourneySeasons;
import dev.logos.dimension.gametest.TaixuanGameTests;
import dev.logos.dimension.taixuan.TaixuanStructure;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;

/**
 * 自然生成勘察：在全新世界里找出几处真实候选点，不调用一次性摆放，只把玩家送过去，让区块按正常探索流程生成。
 * 记录地标在不同地形上的样子（全景、深渊口、镇渊柱外侧、栈道、瀑布），自然生成后路线、瀑布、深渊净空和结构上长树的检查，
 * 以及同样大小一片区块有无地标时的服务端生成耗时。
 * 世界里已经生成过的区块不会重新生成，所以要在删掉 runs/uiTest/saves 之后单独运行这一组。
 */
public abstract class TaixuanSurveyViews implements UIScenario {
    private static List<TaixuanGameTests.Site> sites;
    private TaixuanGameTests.Site site;
    private Vec3 look;
    private int broken, rooted;
    private List<String> blocked = List.of(), leftovers = List.of();
    protected abstract int index();

    @Override public void configure(ScenarioOptions options) {
        options.tags("taixuan_survey").defaultTimeoutMs(600000).scenarioTimeoutMs(1200000);
    }

    private static List<TaixuanGameTests.Site> sites(ServerLevel level) {
        if (sites == null) {
            long t = System.nanoTime();
            sites = TaixuanGameTests.naturalSites(level, 4);
            System.out.printf("TAIXUAN_SURVEY sites=%d search_ms=%d%n", sites.size(), (System.nanoTime() - t) / 1000000);
        }
        return sites;
    }

    /**
     * uitest 新建的世界关掉了结构生成（world_gen_settings 里 generate_structures = 0），
     * 不打开的话所有结构都不会自然生成。只在这个开发场景里用反射换掉世界选项。
     */
    private static void enableStructures(net.minecraft.server.MinecraftServer server) {
        // 区块的结构起点步骤看 WorldGenSettings，摆放结构看各维度 StructureManager 里的那一份，两处都要换
        withStructures(server.getWorldGenSettings());
        for (var level : server.getAllLevels()) withStructures(level.structureManager());
        if (!server.getWorldGenSettings().options().generateStructures()) throw new IllegalStateException("没能打开结构生成");
    }

    private static void withStructures(Object holder) {
        try {
            for (Class<?> type = holder.getClass(); type != null; type = type.getSuperclass())
                for (var field : type.getDeclaredFields())
                    if (field.getType() == net.minecraft.world.level.levelgen.WorldOptions.class) {
                        field.setAccessible(true);
                        field.set(holder, ((net.minecraft.world.level.levelgen.WorldOptions) field.get(holder)).withStructures(true));
                        return;
                    }
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        throw new IllegalStateException("找不到世界选项字段：" + holder.getClass());
    }

    /**
     * 长在建筑上的外来树：空地里（离中心 100 格以内，原地形在深渊口以上都清掉了）浮岛高度上的原木，
     * 往下顺着原木找到树根，根下面是建筑材料（built_surfaces）或原版草方块（只出现在结构里）的就算。
     * 树枝下面是自己的树叶，不算；崖壁松长在浮岛、浮石的岩壁上是想要的样子，也不算；宗门自己的崖松按 manifest 的位置排除。
     */
    private static int rootedTrees(ServerLevel level, TaixuanGameTests.Site site) {
        var data = TaixuanGameTests.manifest();
        var pines = new java.util.HashSet<Long>();
        for (var pine : data.getAsJsonObject("landscape").getAsJsonArray("pines")) {
            var p = site.pos(pine.getAsJsonArray());pines.add(((long) p.getX() << 32) ^ (p.getZ() & 0xffffffffL));
        }
        var tag = net.minecraft.tags.TagKey.create(Registries.BLOCK, LogosDimension.id("built_surfaces"));
        int rim = data.getAsJsonObject("heights").get("rim").getAsInt();
        var seen = new java.util.HashSet<BlockPos>();var roots = new java.util.TreeMap<String, Integer>();int count = 0;
        for (int x = 0; x < 252; x += 2) for (int z = 0; z < 252; z += 2) for (int y = rim + 12; y < 308; y++) {
            if ((x - 126) * (x - 126) + (z - 126) * (z - 126) > 100 * 100) continue;
            var p = site.pos(x, y, z);var state = level.getBlockState(p);
            if (!state.is(net.minecraft.tags.BlockTags.LOGS) || state.is(net.minecraft.world.level.block.Blocks.STRIPPED_DARK_OAK_LOG)) continue;
            if (pines.contains(((long) p.getX() << 32) ^ (p.getZ() & 0xffffffffL))) continue;
            var lowest = p;
            while (level.getBlockState(lowest.below()).is(net.minecraft.tags.BlockTags.LOGS)) lowest = lowest.below();
            if (!seen.add(lowest)) continue;
            var under = level.getBlockState(lowest.below());
            if (under.is(net.minecraft.tags.BlockTags.LEAVES)) continue;
            boolean structure = under.is(tag) || under.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK)
                || (under.is(net.minecraft.world.level.block.Blocks.DIRT) && level.getBlockState(lowest.below(2)).is(tag));
            if (!structure) continue;
            count++;
            if (roots.size() < 12) roots.merge(x + "," + (y) + "," + z + " on " + net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(under.getBlock()).getPath(), 1, Integer::sum);
        }
        if (count > 0) System.out.println("TAIXUAN_ROOTED_TREES " + site.anchor() + " " + roots.keySet());
        return count;
    }

    /** 同步生成一片区块到 FULL，返回毫秒；只用于粗略对比，不是稳定的性能测试。 */
    private static long generate(ServerLevel level, int minX, int minZ, int maxX, int maxZ) {
        long t = System.nanoTime();
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) level.getChunk(x, z);
        return (System.nanoTime() - t) / 1000000;
    }

    private ScenarioBuilder shot(ScenarioBuilder s, String name, int[] eye, int[] target) {
        return s.server("机位 " + name, ctx -> {
                var level = ctx.server().getLevel(LogosDimension.CLOUDSEA);
                var e = site.pos(eye[0], eye[1], eye[2]).getBottomCenter();
                look = site.pos(target[0], target[1], target[2]).getCenter();
                var d = look.subtract(e.add(0, ctx.player().getEyeHeight(), 0));
                DimensionTravel.teleport(ctx.player(), level, e, (float) Math.toDegrees(Math.atan2(-d.x, d.z)),
                    (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z))));
                ctx.player().getAbilities().flying = true;ctx.player().onUpdateAbilities();
            })
            .waitUntil("区块到达 " + name, ctx -> Minecraft.getInstance().level != null
                && Minecraft.getInstance().level.hasChunkAt(BlockPos.containing(look)))
            .ticks(200)
            .step("对准 " + name, ctx -> {
                var player = Minecraft.getInstance().player;var d = look.subtract(player.getEyePosition());
                player.setYRot((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
                player.setXRot((float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z))));
            }).ticks(40)
            .screenshot("survey_" + index() + "_" + name);
    }

    @Override public void define(ScenarioBuilder scenario) {
        scenario.setGameMode(GameType.CREATIVE).runCommand("difficulty peaceful")
            .step("设置画面", ctx -> {
                var mc = Minecraft.getInstance();
                mc.options.hideGui = true;mc.options.fov().set(70);mc.options.renderDistance().set(24);
                mc.options.broadcastOptions();
            })
            .server("找候选点并按自然流程生成", ctx -> {
                var level = ctx.server().getLevel(LogosDimension.CLOUDSEA);
                enableStructures(ctx.server());
                var all = sites(level);
                if (index() >= all.size()) throw new IllegalStateException("候选点只有 " + all.size() + " 处");
                site = all.get(index());
                level.clockManager().setTotalTicks(level.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK)
                    .getOrThrow(JourneySeasons.CLOCK), JourneySeasons.SEASON_TICKS + 6000);
                level.getWeatherData().setRaining(false);level.getWeatherData().setThundering(false);
                BoundingBox b = site.start().getBoundingBox();
                int x0 = b.minX() >> 4, z0 = b.minZ() >> 4, x1 = b.maxX() >> 4, z1 = b.maxZ() >> 4;
                // 对照区：同样大小、往北偏 1600 格，生成后看里面有没有太玄的引用
                int off = 100;
                long control = generate(level, x0, z0 - off, x1, z1 - off);
                boolean clean = true;
                var structure = TaixuanGameTests.structure(level);
                for (int x = x0; x <= x1; x++) for (int z = z0 - off; z <= z1 - off; z++)
                    if (level.getChunk(x, z).getReferencesForStructure(structure).size() > 0) clean = false;
                long landmark = generate(level, x0, z0, x1, z1);
                var real = level.structureManager().getStructureAt(site.anchor(), structure);
                broken = TaixuanGameTests.brokenWaterfalls(level, site);
                blocked = TaixuanGameTests.blockedRoutes(level, site, 12);
                leftovers = TaixuanGameTests.shaftLeftovers(level, site, 12);
                rooted = rootedTrees(level, site);
                System.out.printf("TAIXUAN_SURVEY site=%d anchor=%s rotation=%s chunks=%d control_ms=%d control_clean=%s landmark_ms=%d natural=%s pieces=%d waterfall_gaps=%d blocked=%s leftovers=%s rooted_trees=%d%n",
                    index(), site.anchor(), site.rotation(), (x1 - x0 + 1) * (z1 - z0 + 1), control, clean, landmark, real.isValid(),
                    real.isValid() ? real.getPieces().size() : 0, broken, blocked, leftovers, rooted);
                if (!real.isValid()) throw new IllegalStateException("候选点没有自然生成地标");
            });
        shot(scenario, "overview", new int[]{300, 236, 320}, new int[]{126, 190, 126});
        shot(scenario, "rim", new int[]{126, 121, 196}, new int[]{126, 72, 112});
        // 镇渊柱的门朝深渊中心：机位在空地里、深渊口上空，看东南那根的门和门外的下行石阶
        shot(scenario, "tower_door", new int[]{160, 124, 152}, new int[]{185, 117, 176});
        shot(scenario, "below_island", new int[]{126, 140, 98}, new int[]{126, 196, 126});
        var falls = TaixuanGameTests.manifest().getAsJsonObject("abyss").getAsJsonArray("waterfalls");
        var fall = falls.get(0).getAsJsonArray();
        int fx = fall.get(3).getAsInt(), fy = fall.get(1).getAsInt(), fz = fall.get(4).getAsInt();
        shot(scenario, "waterfall", new int[]{126 + (126 - fx) / 3, fy - 40, 126 + (126 - fz) / 3}, new int[]{fx, fy - 60, fz});
        scenario.checkServer("自然生成后瀑布落水柱完整", ctx -> broken == 0)
            .checkServer("自然生成后路线都走得通", ctx -> blocked.isEmpty())
            .checkServer("深渊里没有残留地形或地物", ctx -> leftovers.isEmpty())
            .checkServer("结构上没有长出外来的树", ctx -> rooted == 0)
            .teardown("恢复界面", ctx -> Minecraft.getInstance().options.hideGui = false);
    }

    /**
     * 只算选址：一大片格点上有多少候选点落在云岫峰林、多少通过完整选址，通过的点彼此隔多远，
     * 深渊口一圈和镇渊柱门口那一圈的地面比深渊口高多少。不生成区块，几十秒。
     */
    @LDLRegisterClient(name="taixuan_site_stats", group="taixuan_stats", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
    public static class SiteStats implements UIScenario {
        @Override public void configure(ScenarioOptions options) { options.tags("taixuan_stats").defaultTimeoutMs(600000).scenarioTimeoutMs(900000); }
        @Override public void define(ScenarioBuilder scenario) {
            scenario.server("统计候选点", ctx -> {
                var level = ctx.server().getLevel(LogosDimension.CLOUDSEA);
                var gen = level.getChunkSource().getGenerator();var random = level.getChunkSource().randomState();
                var structure = TaixuanGameTests.structure(level);var placement = TaixuanGameTests.placement(level);
                var data = TaixuanGameTests.manifest();
                int rim = data.getAsJsonObject("heights").get("rim").getAsInt() + data.get("world_y_offset").getAsInt();
                int spacing = placement.spacing(), grid = 40, candidates = 0, inBiome = 0;
                var passed = new ArrayList<ChunkPos>();
                var rims = new ArrayList<Integer>();var doors = new ArrayList<Integer>();
                for (int gx = -grid; gx <= grid; gx++) for (int gz = -grid; gz <= grid; gz++) {
                    var chunk = placement.getPotentialStructureChunk(level.getSeed(), gx * spacing, gz * spacing);candidates++;
                    var center = gen.getBiomeSource().getNoiseBiome(net.minecraft.core.QuartPos.fromBlock(chunk.getMiddleBlockX()), net.minecraft.core.QuartPos.fromBlock(103),
                        net.minecraft.core.QuartPos.fromBlock(chunk.getMiddleBlockZ()), random.sampler());
                    if (!structure.biomes().contains(center)) continue;
                    inBiome++;
                    if (!TaixuanGameTests.siteFits(level, chunk)) continue;
                    passed.add(chunk);
                    int cx = chunk.getMiddleBlockX(), cz = chunk.getMiddleBlockZ();
                    for (int k = 0; k < 16; k++) {
                        double a = k * Math.PI / 8;
                        rims.add(gen.getBaseHeight(cx + (int) Math.round(64 * Math.cos(a)), cz + (int) Math.round(64 * Math.sin(a)), Heightmap.Types.OCEAN_FLOOR_WG, level, random) - rim);
                    }
                    // 镇渊柱门口：斜角方向离中心约 93 格
                    for (int k = 0; k < 4; k++) {
                        double a = Math.PI / 4 + k * Math.PI / 2;
                        doors.add(gen.getBaseHeight(cx + (int) Math.round(93 * Math.cos(a)), cz + (int) Math.round(93 * Math.sin(a)), Heightmap.Types.OCEAN_FLOOR_WG, level, random) - rim);
                    }
                }
                var gaps = new ArrayList<Integer>();
                for (var a : passed) {
                    int best = Integer.MAX_VALUE;
                    for (var b : passed) if (a != b) best = Math.min(best, (int) Math.hypot(a.getMiddleBlockX() - b.getMiddleBlockX(), a.getMiddleBlockZ() - b.getMiddleBlockZ()));
                    if (best < Integer.MAX_VALUE) gaps.add(best);
                }
                java.util.Collections.sort(rims);java.util.Collections.sort(doors);java.util.Collections.sort(gaps);
                java.util.function.BiFunction<List<Integer>, Double, Integer> q = (l, p) -> l.isEmpty() ? 0 : l.get(Math.min(l.size() - 1, (int) (p * l.size())));
                int side = (2 * grid + 1) * spacing * 16;
                System.out.printf("TAIXUAN_SITES area=%dx%d candidates=%d in_biome=%d passed=%d nearest p10=%d p50=%d | rim_minus_rim p10=%d p50=%d p90=%d | door_minus_rim p10=%d p50=%d p90=%d%n",
                    side, side, candidates, inBiome, passed.size(), q.apply(gaps, .1), q.apply(gaps, .5),
                    q.apply(rims, .1), q.apply(rims, .5), q.apply(rims, .9), q.apply(doors, .1), q.apply(doors, .5), q.apply(doors, .9));
                // 放宽前后对比：群系半径、外圈可缺几个点、深渊口一圈可低几个点；同一片里两处挨着（600 格内）的也数一下
                int[][] variants = {{128, 0, 0}, {128, 2, 0}, {128, 0, 3}, {112, 2, 3}, {112, 3, 4}, {104, 3, 4}};
                var pools = new ArrayList<List<ChunkPos>>();
                for (int v = 0; v < variants.length; v++) pools.add(new ArrayList<>());
                for (int gx = -grid; gx <= grid; gx++) for (int gz = -grid; gz <= grid; gz++) {
                    var chunk = placement.getPotentialStructureChunk(level.getSeed(), gx * spacing, gz * spacing);
                    java.util.Map<Integer, TaixuanStructure.Measure> byRadius = new java.util.HashMap<>();
                    for (int v = 0; v < variants.length; v++) {
                        int[] p = variants[v];
                        var m = byRadius.computeIfAbsent(p[0], r -> TaixuanGameTests.measure(level, chunk, r, structure.rimRadius(), structure.rimMinHeight()));
                        if (m != null && m.inner() == 0 && m.outer() <= p[1] && m.rimLow() <= p[2]) pools.get(v).add(chunk);
                    }
                }
                // 按现在的参数真正会生成的（含 600 格内只留一处）
                var winners = new ArrayList<ChunkPos>();
                for (var chunk : passed) if (TaixuanGameTests.wins(level, chunk)) winners.add(chunk);
                pools.add(winners);
                for (int v = 0; v < pools.size(); v++) {
                    var list = pools.get(v);var near = new ArrayList<Integer>();int crowded = 0;
                    for (var a : list) {
                        int best = Integer.MAX_VALUE;
                        for (var b : list) if (a != b) best = Math.min(best, (int) Math.hypot(a.getMiddleBlockX() - b.getMiddleBlockX(), a.getMiddleBlockZ() - b.getMiddleBlockZ()));
                        if (best < Integer.MAX_VALUE) near.add(best);
                        if (best < 600) crowded++;
                    }
                    java.util.Collections.sort(near);
                    String name = v < variants.length ? String.format("biome_r=%d outer_misses<=%d rim_low<=%d", variants[v][0], variants[v][1], variants[v][2]) : "current_with_dedupe";
                    System.out.printf("TAIXUAN_SITES_VARIANT %s passed=%d nearest p10=%d p50=%d within600=%d%n",
                        name, list.size(), q.apply(near, .1), q.apply(near, .5), crowded);
                }
            });
        }
    }

    @LDLRegisterClient(name="taixuan_survey_0", group="taixuan_survey", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
    public static class Site0 extends TaixuanSurveyViews { @Override protected int index() { return 0; } }
    @LDLRegisterClient(name="taixuan_survey_1", group="taixuan_survey", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
    public static class Site1 extends TaixuanSurveyViews { @Override protected int index() { return 1; } }
    @LDLRegisterClient(name="taixuan_survey_2", group="taixuan_survey", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
    public static class Site2 extends TaixuanSurveyViews { @Override protected int index() { return 2; } }
    @LDLRegisterClient(name="taixuan_survey_3", group="taixuan_survey", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
    public static class Site3 extends TaixuanSurveyViews { @Override protected int index() { return 3; } }
}
