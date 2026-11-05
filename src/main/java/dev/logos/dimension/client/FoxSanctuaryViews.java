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

    private static int su(SanctuaryGroundsPiece g) {
        return g.shrineU();
    }

    /** 神社片区的设计点：有的方案把它以主街为轴南北翻过去。 */
    private static Vec3 sp(SanctuaryGroundsPiece g, double u, double dy, double v) {
        return g.designPoint(u, dy, g.shrineV(v));
    }

    /** 旅馆片区的设计点。 */
    private static Vec3 rp(SanctuaryGroundsPiece g, double u, double dy, double v) {
        return g.designPoint(u, dy, g.ryokanV(v));
    }

    /** 露天风吕岩岸池中间一格：带木桶汤的格局岩岸池在西边，所以两处都试。 */
    private static Vec3 bath(ServerLevel level, SanctuaryGroundsPiece g, double dy) {
        Vec3 east = rp(g, 122.5, dy, 155.5);
        return HotSpringStoneBlock.heatedFrom(level, BlockPos.containing(rp(g, 122.5, -0.5, 155.5))) ? east : rp(g, 116.5, dy, 155.5);
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

    /** 找一处神狐聚落（测试世界关闭了结构生成，这里先用反射打开，让聚落像正常存档一样随区块生成）；找不到返回 null。 */
    static net.minecraft.world.level.levelgen.structure.StructureStart locate(net.minecraft.server.MinecraftServer server) {
        var level = server.getLevel(LogosDimension.CLOUDSEA);
        var holder = level.registryAccess().lookupOrThrow(Registries.STRUCTURE)
            .getOrThrow(ResourceKey.create(Registries.STRUCTURE, LogosDimension.id("fox_sanctuary")));
        // 测试世界关闭了结构生成；这里只为预览把它打开，让聚落像正常存档一样随区块自然生成
        try {
            var options = server.getWorldGenSettings().options();
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
        return start;
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
                var start = locate(ctx.server());
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
        // 手水舍与拝殿之间的抽签处；绘马挂背朝神社、正面朝路
        shot(s, "omikuji", g -> look(sp(g, (su(g) + 4 + 144) / 2 + 0.5, 19.6, 44), sp(g, (su(g) + 4 + 144) / 2 + 0.5, 18, 35)));
        shot(s, "ema_racks", g -> look(sp(g, 143.5, 19, 48), sp(g, 143.5, 18, 43)));
        // 境内的石龛激活、发光；境内外判定
        s.server("hokora", ctx -> {
            var level = ctx.server().getLevel(LogosDimension.CLOUDSEA);
            BlockPos tree = BlockPos.containing(sp(grounds, 159.5, 17, 36.5));
            var state = level.getBlockState(tree);
            boolean inside = dev.logos.dimension.fox.FoxSanctuaryStructure.inShrine(level, BlockPos.containing(sp(grounds, 150.5, 18, 42.5)));
            boolean outside = dev.logos.dimension.fox.FoxSanctuaryStructure.inShrine(level, BlockPos.containing(grounds.designPoint(60.5, 1, 88.5)));
            System.out.println("[fox] hokora " + state + " light=" + state.getLightEmission() + " inShrine precinct=" + inside + " street=" + outside);
            if (!state.is(dev.logos.dimension.cloudsea.FoxWildsBlocks.SHRINE_HOKORA.get()) || !state.getValue(dev.logos.dimension.cloudsea.ShrineHokoraBlock.ACTIVE)) {
                throw new IllegalStateException("神木下的石龛没有激活：" + state);
            }
            if (!inside || outside) throw new IllegalStateException("神社境内判定不对");
        });
        // 温泉旅馆（有的方案在主街北侧）
        shot(s, "ryokan_gate", g -> look(rp(g, 122.5, 2.6, 94), rp(g, 122.5, 4, 112)));
        shot(s, "ryokan_court", g -> look(rp(g, 124, 15, 128), rp(g, 118, 0, 135)));
        shot(s, "onsen", g -> look(rp(g, 140, 6, 157), rp(g, 124, 0, 150)));
        shot(s, "onsen_bath", g -> look(rp(g, 122.5, 2.6, 149.5), rp(g, 122.5, 0, 156)));
        shot(s, "ryokan_west_yard", g -> look(rp(g, 100, 24, 136), rp(g, 100, 0, 152)));
        shot(s, "ryokan_east_yard", g -> look(rp(g, 145, 24, 136), rp(g, 145, 0, 152)));
        // 两翼的房间：一层第一间，从门内往里看
        shot(s, "wing_west", g -> look(rp(g, 102.5, 2.7, 113.5), rp(g, 95.5, 1.5, 114.5)));
        shot(s, "wing_east", g -> look(rp(g, 142.5, 2.7, 113.5), rp(g, 149.5, 1.5, 114.5)));
        shot(s, "main_room", g -> look(rp(g, 113.5, 7.6, 117.5), rp(g, 117.5, 6.5, 123.5)));
        // 露天风吕池底是含水石半砖、下面才是地热石：放一头受伤的牛进池子，三秒后应当在回血
        s.server("hot spring", ctx -> {
            var level = ctx.server().getLevel(LogosDimension.CLOUDSEA);
            Vec3 p = bath(level, grounds, -0.5);
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
            // 冬天：田边灌溉沟（普通的水）会结冰；露天风吕（地热石）与主街水渠（含水石阶）不结冰
            var level = ctx.server().getLevel(LogosDimension.CLOUDSEA);
            BlockPos bath = BlockPos.containing(bath(level, grounds, 0));
            BlockPos ditch = null, channel = null;
            for (int y = -8; y <= 0 && ditch == null; y++) {
                BlockPos p = BlockPos.containing(grounds.designPoint(16.5, y, 130.5));
                if (level.getBlockState(p).is(net.minecraft.world.level.block.Blocks.WATER)) ditch = p;
            }
            for (int v = 70; v <= 100 && channel == null; v++) {
                BlockPos p = BlockPos.containing(grounds.designPoint(45.5, 0, v + 0.5));
                if (level.getBlockState(p).is(net.minecraft.world.level.block.Blocks.STONE_STAIRS)) channel = p;
            }
            if (ditch == null || channel == null) throw new IllegalStateException("没找到灌溉沟或主街水渠：" + ditch + " " + channel);
            JourneySeasons.CONTEXT.set(3);
            try {
                boolean bathFreezes = level.getBiome(bath).value().shouldFreeze(level, bath, false);
                boolean ditchFreezes = level.getBiome(ditch).value().shouldFreeze(level, ditch, false);
                boolean channelFreezes = level.getBiome(channel).value().shouldFreeze(level, channel, false);
                System.out.println("[fox] winter: bath freezes=" + bathFreezes + " channel freezes=" + channelFreezes + " ditch freezes=" + ditchFreezes);
                if (bathFreezes || channelFreezes || !ditchFreezes) throw new IllegalStateException("冬天应当只有灌溉沟结冰，温泉与主街水渠不结冰");
            } finally {
                JourneySeasons.CONTEXT.remove();
            }
        });
        shot(s, "nodate", g -> look(rp(g, 136, 3.5, 99), rp(g, 146, 1, 106)));
        shot(s, "ryokan_stairs", g -> look(rp(g, 111.5, 2.8, 121.5), rp(g, 107.5, 4, 116)));
        // 街尾空地：从地块北边斜着往下看
        shot(s, "end_plot", g -> {
            int[] b = g.endBox();
            double cu = (b[0] + b[2]) / 2.0, cv = (b[1] + b[3]) / 2.0;
            return look(g.designPoint(cu, 18, b[1] - 8), g.designPoint(cu, 3, cv));
        });
        shot(s, "court_bridge", g -> look(rp(g, 121, 7, 140), rp(g, 113.5, 1, 132)));
        // 田地的切面砌成石垣：西缘与东缘
        shot(s, "field_west", g -> look(g.designPoint(28, 0, 135), g.designPoint(15, -2, 135)));
        shot(s, "field_east", g -> look(g.designPoint(78, 0, 135), g.designPoint(91, -2, 135)));
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
