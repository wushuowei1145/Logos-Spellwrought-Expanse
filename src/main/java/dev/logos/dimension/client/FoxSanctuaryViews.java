package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.cloudsea.HotSpringStoneBlock;
import dev.logos.dimension.cloudsea.JourneySeasons;
import dev.logos.dimension.fox.SanctuaryGroundsPiece;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.phys.Vec3;

import java.util.function.Function;

/** 神狐聚落实机预览（本地开发用）：定位最近的聚落，依次拍俯瞰、山脚、山腰、鸟居段、入口石阶与登顶。 */
@LDLRegisterClient(name = "fox_sanctuary_views", group = "fox_dev", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
public class FoxSanctuaryViews implements UIScenario {
    private static SanctuaryGroundsPiece grounds;
    private static LivingEntity soaker;

    @Override public void configure(ScenarioOptions o) {
        o.tags("fox_dev").defaultTimeoutMs(400000).scenarioTimeoutMs(900000);
    }

    /** 各方案的神社参道 u（主街东端 + 6），与 tools/fox_sanctuary.py 的 PLANS 对应。 */
    private static final int[] SHRINE_U = {132, 126, 128};

    private static int su(SanctuaryGroundsPiece g) {
        return SHRINE_U[g.plan()];
    }

    /** 神社与旅馆片区的设计点：方案 b 把两者以主街为轴南北翻过去（翻转轴两倍为 176，连续坐标 v → 177 - v）。 */
    private static Vec3 sp(SanctuaryGroundsPiece g, double u, double dy, double v) {
        return g.designPoint(u, dy, g.plan() == 1 ? 177 - v : v);
    }

    /** 露天风吕岩岸池中间一格（terrace 格局的岩岸池在西边）。 */
    private static Vec3 bath(SanctuaryGroundsPiece g, double dy) {
        return sp(g, g.plan() == 2 ? 116.5 : 122.5, dy, 155.5);
    }

    private static Vec3[] look(Vec3 from, Vec3 to) {
        return new Vec3[]{from, to};
    }

    private static void view(ServerLevel level, net.minecraft.server.level.ServerPlayer player, Vec3 from, Vec3 to) {
        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        float yaw = (float) (-Math.toDegrees(Math.atan2(dx, dz)));
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz))));
        DimensionTravel.teleport(player, level, from, yaw, pitch);
        player.setNoGravity(true);
        player.setDeltaMovement(Vec3.ZERO);
    }

    private ScenarioBuilder shot(ScenarioBuilder s, String name, Function<SanctuaryGroundsPiece, Vec3[]> camera) {
        return s.server("go " + name, ctx -> {
                var level = ctx.server().getLevel(LogosDimension.CLOUDSEA);
                Vec3[] c = camera.apply(grounds);
                view(level, ctx.player(), c[0], c[1]);
            })
            .ticks(160)
            .screenshot("fox_" + name);
    }

    @Override public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.SPECTATOR).runCommand("difficulty peaceful").runCommand("weather clear")
            .step("camera", ctx -> {
                var mc = Minecraft.getInstance();
                mc.options.hideGui = true;
                mc.options.renderDistance().set(14);
                mc.options.fov().set(70);
            })
            .server("locate", ctx -> {
                var level = ctx.server().getLevel(LogosDimension.CLOUDSEA);
                level.getGameRules().set(GameRules.RANDOM_TICK_SPEED, 0, ctx.server());
                // 指定布局方案：-Dlogos.foxPlan=N 或环境变量 LOGOS_FOX_PLAN=N；不指定时随机
                String forced = System.getProperty("logos.foxPlan", System.getenv("LOGOS_FOX_PLAN"));
                dev.logos.dimension.fox.FoxSanctuaryStructure.devPlan = forced == null ? -1 : Integer.parseInt(forced.trim());
                String style = System.getProperty("logos.foxStyle", System.getenv("LOGOS_FOX_STYLE"));
                dev.logos.dimension.fox.FoxSanctuaryStructure.devStyle = style == null ? -1 : Integer.parseInt(style.trim());
                var holder = level.registryAccess().lookupOrThrow(Registries.STRUCTURE)
                    .getOrThrow(ResourceKey.create(Registries.STRUCTURE, LogosDimension.id("fox_sanctuary")));
                // 测试世界关闭了结构生成；这里只为预览把它打开，让聚落像正常存档一样随区块自然生成
                try {
                    var options = ctx.server().getWorldGenSettings().options();
                    var field = net.minecraft.world.level.levelgen.WorldOptions.class.getDeclaredField("generateStructures");
                    field.setAccessible(true);
                    field.setBoolean(options, true);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
                var gen = level.getChunkSource().getGenerator();
                var cherry = ResourceKey.create(Registries.BIOME, LogosDimension.id("cloudsea_white_cherry"));
                Structure structure = holder.value();
                net.minecraft.world.level.levelgen.structure.StructureStart start = null;
                java.util.Set<Long> tried = new java.util.HashSet<>();
                int sp = dev.logos.dimension.fox.FoxSanctuaryStructure.spacing();
                found:
                for (int r = 2000; r <= 40000; r += 160) {
                    for (int a = 0; a < Math.max(1, r / 40); a++) {
                        double ang = a * Math.PI * 2 / Math.max(1, r / 40);
                        int x = (int) (Math.cos(ang) * r), z = (int) (Math.sin(ang) * r);
                        if (!gen.getBiomeSource().getNoiseBiome(x >> 2, 24, z >> 2, level.getChunkSource().randomState().sampler()).is(cherry)) continue;
                        for (int i = -2; i <= 2; i++) for (int j = -2; j <= 2; j++) {
                            var cand = dev.logos.dimension.fox.FoxSanctuaryStructure.candidate(level.getSeed(), (x >> 4) + i * sp, (z >> 4) + j * sp);
                            if (!tried.add(cand.pack())) continue;
                            var s0 = level.getChunk(cand.x(), cand.z(), ChunkStatus.STRUCTURE_STARTS).getStartForStructure(structure);
                            if (s0 != null && s0.isValid()) {
                                start = s0;
                                break found;
                            }
                        }
                    }
                }
                System.out.println("[fox] candidates tried: " + tried.size());
                if (start == null) throw new IllegalStateException("没找到神狐聚落");
                grounds = (SanctuaryGroundsPiece) start.getPieces().getFirst();
                System.out.println("[fox] sanctuary at " + start.getChunkPos() + " plan=" + grounds.plan() + " street y=" + grounds.streetLevel() + " trail=" + grounds.trailSize());
                level.clockManager().setTotalTicks(level.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(JourneySeasons.CLOCK), 6000);
                Vec3 c = grounds.designPoint(88, 0, 88);
                view(level, ctx.player(), c.add(0, 60, 0), c);
            })
            .ticks(900);
        shot(s, "aerial", g -> look(g.designPoint(60, 85, 215), g.designPoint(90, 0, 95)));
        shot(s, "aerial_trail", g -> look(g.designPoint(-60, 35, 150), g.designPoint(10, -20, 40)));
        shot(s, "aerial_town", g -> look(g.designPoint(60, 45, 130), g.designPoint(90, 0, 70)));
        shot(s, "foot", g -> look(g.trailPoint(g.trailSize() - 1).add(0, 0.6, 0), g.trailPoint(g.trailSize() - 14).add(0, 1, 0)));
        shot(s, "lower", g -> look(g.trailPoint((int) (g.trailSize() * 0.75)).add(0, 0.6, 0), g.trailPoint((int) (g.trailSize() * 0.75) - 12).add(0, 1, 0)));
        shot(s, "bridge", g -> look(g.trailPoint((int) (g.trailSize() * 0.46)).add(0, 0.6, 0), g.trailPoint((int) (g.trailSize() * 0.40) - 2).add(0, 0.6, 0)));
        shot(s, "torii", g -> look(g.trailPoint((int) (g.trailSize() * 0.30)).add(0, 0.6, 0), g.trailPoint((int) (g.trailSize() * 0.30) - 14).add(0, 2, 0)));
        shot(s, "stair_foot", g -> look(g.trailPoint(3).add(0, 0.6, 0), g.designPoint(20, 1, 88)));
        shot(s, "reveal", g -> look(g.designPoint(21, 1.6, 88.5), g.designPoint(88, 2, 86)));
        shot(s, "food_plaza", g -> look(g.designPoint(73, 2.6, 93), g.designPoint(73, 2, 76)));
        shot(s, "street_end", g -> look(g.designPoint(108, 2.6, 92), sp(g, su(g), 6, 74)));
        // 神社参道（千本鸟居 / 九十九折 / 三段石阶）与境内
        shot(s, "approach", g -> look(sp(g, su(g) + 0.5, 2.6, 84), sp(g, su(g) + 0.5, 14, 50)));
        shot(s, "shrine", g -> look(sp(g, su(g) + 1, 18.6, 47), sp(g, 147, 21, 31)));
        shot(s, "shrine_front", g -> look(sp(g, 151.5, 19.6, 50), sp(g, 151.5, 18, 38)));
        shot(s, "shrine_back", g -> look(sp(g, 150, 24, -6), sp(g, 148, 12, 18)));
        shot(s, "chozuya", g -> look(sp(g, su(g) - 1, 21, 43), sp(g, su(g) - 1, 17, 38)));
        // 温泉旅馆（方案 b 在主街北侧）
        shot(s, "ryokan_gate", g -> look(sp(g, 122.5, 2.6, 94), sp(g, 122.5, 4, 112)));
        shot(s, "ryokan_court", g -> look(sp(g, 124, 15, 128), sp(g, 118, 0, 135)));
        shot(s, "onsen", g -> look(sp(g, 140, 6, 157), sp(g, 124, 0, 150)));
        shot(s, "onsen_bath", g -> look(sp(g, 122.5, 2.6, 149.5), sp(g, 122.5, 0, 156)));
        shot(s, "ryokan_west_yard", g -> look(sp(g, 100, 24, 136), sp(g, 100, 0, 152)));
        shot(s, "ryokan_east_yard", g -> look(sp(g, 145, 24, 136), sp(g, 145, 0, 152)));
        // 露天风吕池底是含水石半砖、下面才是地热石：放一头受伤的牛进池子，三秒后应当在回血
        s.server("hot spring", ctx -> {
            var level = ctx.server().getLevel(LogosDimension.CLOUDSEA);
            Vec3 p = bath(grounds, -0.5);
            BlockPos slab = BlockPos.containing(p);
            if (!HotSpringStoneBlock.heatedFrom(level, slab)) {
                throw new IllegalStateException("露天风吕池底找不到地热石：" + level.getBlockState(slab) + " / " + level.getBlockState(slab.below()));
            }
            var cow = EntityType.COW.create(level, EntitySpawnReason.COMMAND);
            cow.snapTo(p.x, p.y, p.z, 0, 0);
            cow.setHealth(4);
            level.addFreshEntity(cow);
            soaker = cow;
        }).ticks(60).server("hot spring regen", ctx -> {
            boolean regen = soaker.hasEffect(MobEffects.REGENERATION);
            System.out.println("[fox] hot spring: regeneration=" + regen + " health=" + soaker.getHealth() + " at " + soaker.blockPosition());
            soaker.discard();
            if (!regen) throw new IllegalStateException("泡在露天风吕里的牛没有得到生命恢复");
        }).server("hot spring winter", ctx -> {
            // 冬天：主街水渠会结冰，露天风吕（地热石）不结冰
            var level = ctx.server().getLevel(LogosDimension.CLOUDSEA);
            BlockPos bath = BlockPos.containing(bath(grounds, 0));
            BlockPos ditch = null;
            for (int u = 40; u <= 60 && ditch == null; u++) for (int v = 70; v <= 100 && ditch == null; v++) {
                BlockPos p = BlockPos.containing(grounds.designPoint(u + 0.5, 0, v + 0.5));
                if (level.getBlockState(p).is(net.minecraft.world.level.block.Blocks.WATER) && !HotSpringStoneBlock.heatedFrom(level, p)) ditch = p;
            }
            if (ditch == null) throw new IllegalStateException("没找到主街水渠");
            JourneySeasons.CONTEXT.set(3);
            try {
                boolean bathFreezes = level.getBiome(bath).value().shouldFreeze(level, bath, false);
                boolean ditchFreezes = level.getBiome(ditch).value().shouldFreeze(level, ditch, false);
                System.out.println("[fox] winter: bath freezes=" + bathFreezes + " ditch freezes=" + ditchFreezes);
                if (bathFreezes || !ditchFreezes) throw new IllegalStateException("冬天应当只有水渠结冰、温泉不结冰");
            } finally {
                JourneySeasons.CONTEXT.remove();
            }
        });
        shot(s, "nodate", g -> look(sp(g, 136, 3.5, 99), sp(g, 146, 1, 106)));
        shot(s, "ryokan_stairs", g -> look(sp(g, 111.5, 2.8, 121.5), sp(g, 107.5, 4, 116)));
        // 街尾空地：茶庭 / 五重塔 / 竹林小径
        shot(s, "end_plot", g -> switch (g.plan()) {
            case 1 -> look(g.designPoint(105.5, 16, 103), g.designPoint(105.5, 10, 127));
            case 2 -> look(g.designPoint(147, 7, 63), g.designPoint(147, 1, 84));
            default -> look(g.designPoint(136, 6, 92), g.designPoint(148, 0, 82));
        });
        shot(s, "court_bridge", g -> look(sp(g, 121, 7, 140), sp(g, 113.5, 1, 132)));
        shot(s, "lantern_posts", g -> look(g.designPoint(47, 3, 92), g.designPoint(42, 5, 87)));
        shot(s, "fields", g -> look(g.designPoint(40, 6, 106), g.designPoint(55, -4, 138)));
        shot(s, "houses", g -> look(g.designPoint(60, 6, 72), g.designPoint(62, 2, 40)));
        // 主街中央的屋台：高处顺街看一排；站在北侧通道（脚踩街面）看第一组与最后一组的长凳和暖帘
        shot(s, "yatai_row", g -> look(g.designPoint(25, 4, 85.5), g.designPoint(60, 0.5, 90)));
        shot(s, "yatai_close", g -> look(g.designPoint(45.5, 1, 85.5), g.designPoint(33, 1.8, 88)));
        shot(s, "yatai_east", g -> look(g.designPoint(124.5, 1.5, 89.5), g.designPoint(104, 1.8, 93)));
        s.server("night", ctx -> {
            var level = ctx.server().getLevel(LogosDimension.CLOUDSEA);
            level.clockManager().setTotalTicks(level.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(JourneySeasons.CLOCK), 18000);
        });
        shot(s, "yatai_night", g -> look(g.designPoint(45.5, 1, 85.5), g.designPoint(33, 1.8, 88)));
        shot(s, "yatai_night_row", g -> look(g.designPoint(25, 4, 85.5), g.designPoint(60, 0.5, 90)));
        s.teardown("restore HUD", ctx -> Minecraft.getInstance().options.hideGui = false);
    }
}
