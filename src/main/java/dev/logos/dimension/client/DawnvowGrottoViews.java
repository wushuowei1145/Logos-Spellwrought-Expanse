package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.cloudsea.JourneySeasons;
import dev.logos.dimension.dawnvow.Dawnvow;
import dev.logos.dimension.dawnvow.DawnvowGrottoStructure;
import dev.logos.dimension.dawnvow.GrottoPiece;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.function.Function;

/** 曦誓之洞实机预览（本地开发用）：定位最近的山洞，拍洞口、窄洞尽头、洞内、正午与破晓的斜光，再在破晓拔一次剑。 */
@LDLRegisterClient(name = "dawnvow_grotto_views", group = "dawnvow_dev", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
public class DawnvowGrottoViews implements UIScenario {
    private static GrottoPiece grotto;

    @Override public void configure(ScenarioOptions o) {
        o.tags("dawnvow_dev").defaultTimeoutMs(400000).scenarioTimeoutMs(900000);
    }

    private static void view(ServerLevel level, ServerPlayer player, Vec3 from, Vec3 to) {
        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        float yaw = (float) (-Math.toDegrees(Math.atan2(dx, dz)));
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz))));
        DimensionTravel.teleport(player, level, from, yaw, pitch);
        player.setNoGravity(true);
        player.setDeltaMovement(Vec3.ZERO);
    }

    private static void clock(ServerLevel level, long t) {
        level.clockManager().setTotalTicks(level.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(JourneySeasons.CLOCK), t);
    }

    private ScenarioBuilder shot(ScenarioBuilder s, String name, Function<GrottoPiece, Vec3[]> camera) {
        return s.server("go " + name, ctx -> {
                var level = ctx.server().getLevel(LogosDimension.CLOUDSEA);
                Vec3[] c = camera.apply(grotto);
                view(level, ctx.player(), c[0], c[1]);
            })
            .ticks(80)
            .screenshot("dawnvow_" + name);
    }

    private static Vec3 hilt(GrottoPiece g) {
        return Vec3.atBottomCenterOf(g.stone()).add(0, 1.2, 0);
    }

    @Override public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.SPECTATOR).runCommand("difficulty peaceful").runCommand("weather clear")
            .step("camera", ctx -> {
                var mc = Minecraft.getInstance();
                mc.options.hideGui = true;
                mc.options.renderDistance().set(10);
                mc.options.fov().set(70);
            })
            .server("locate", ctx -> {
                var level = ctx.server().getLevel(LogosDimension.CLOUDSEA);
                level.getGameRules().set(GameRules.RANDOM_TICK_SPEED, 0, ctx.server());
                var holder = level.registryAccess().lookupOrThrow(Registries.STRUCTURE)
                    .getOrThrow(ResourceKey.create(Registries.STRUCTURE, LogosDimension.id("dawnvow_grotto")));
                // 测试世界关闭了结构生成；这里只为预览打开，让山洞像正常存档一样随区块自然生成
                try {
                    var options = ctx.server().getWorldGenSettings().options();
                    var field = net.minecraft.world.level.levelgen.WorldOptions.class.getDeclaredField("generateStructures");
                    field.setAccessible(true);
                    field.setBoolean(options, true);
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
                Structure structure = holder.value();
                net.minecraft.world.level.levelgen.structure.StructureStart start = null;
                int sp = DawnvowGrottoStructure.spacing(), tried = 0;
                found:
                for (int ring = 0; ring <= 40; ring++) {
                    for (int i = -ring; i <= ring; i++) for (int j = -ring; j <= ring; j++) {
                        if (Math.max(Math.abs(i), Math.abs(j)) != ring) continue;
                        var cand = DawnvowGrottoStructure.candidate(level.getSeed(), i * sp, j * sp);
                        tried++;
                        var s0 = level.getChunk(cand.x(), cand.z(), ChunkStatus.STRUCTURE_STARTS).getStartForStructure(structure);
                        if (s0 != null && s0.isValid()) {
                            start = s0;
                            break found;
                        }
                    }
                }
                System.out.println("[dawnvow] candidates tried: " + tried);
                if (start == null) throw new IllegalStateException("没找到曦誓之洞");
                grotto = (GrottoPiece) start.getPieces().getFirst();
                System.out.println("[dawnvow] grotto at " + start.getChunkPos() + " stone " + grotto.stone() + " box " + start.getBoundingBox());
                clock(level, 6000);
                Vec3 c = Vec3.atCenterOf(grotto.stone());
                view(level, ctx.player(), c.add(0, 40, 0), c);
            })
            .ticks(600)
            .server("check stone", ctx -> {
                var level = ctx.server().getLevel(LogosDimension.CLOUDSEA);
                BlockPos at = grotto.stone();
                System.out.println("[dawnvow] stone block " + level.getBlockState(at) + " above " + level.getBlockState(at.above())
                    + " light " + level.getMaxLocalRawBrightness(at.above()));
                if (!level.getBlockState(at).is(Dawnvow.STONE.get())) throw new IllegalStateException("山洞中央没有插着剑的石座：" + level.getBlockState(at));
                // 洞底一圈地面上方的方块分布，确认没有洞外的地表装饰（雪、落叶等）混进来
                var counts = new java.util.TreeMap<String, Integer>();
                for (int r = 2; r <= 10; r += 2) for (int a = 0; a < 24; a++) {
                    Vec3 p = grotto.floorPoint(r, a * Math.PI / 12);
                    for (int dy = 0; dy <= 2; dy++) {
                        var id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(level.getBlockState(BlockPos.containing(p).above(dy)).getBlock()).getPath();
                        counts.merge(dy + ":" + id, 1, Integer::sum);
                    }
                }
                System.out.println("[dawnvow] floor blocks " + counts + " biome " + level.getBiome(at).getRegisteredName());
                var all = new java.util.TreeMap<String, Integer>();
                for (BlockPos q : BlockPos.betweenClosed(at.offset(-14, -6, -14), at.offset(14, 10, 14))) {
                    var st = level.getBlockState(q);
                    if (st.isAir() || st.canOcclude()) continue;
                    var id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
                    all.merge(id, 1, Integer::sum);
                }
                System.out.println("[dawnvow] non-solid in cave " + all);
            });
        shot(s, "mouth", g -> new Vec3[]{g.tunnelPoint(g.exitR() + 7).add(0, 1.2, 0), g.tunnelPoint(g.exitR() - 6).add(0, 1, 0)});
        shot(s, "tunnel_end", g -> new Vec3[]{g.tunnelPoint(15).add(0, 0.6, 0), hilt(g)});
        shot(s, "inside", g -> new Vec3[]{g.floorPoint(7.5, 0.5).add(0, 2.2, 0), hilt(g).add(0, -0.6, 0)});
        shot(s, "inside_back", g -> new Vec3[]{g.floorPoint(7.5, Math.PI + 0.4).add(0, 2.2, 0), hilt(g).add(0, -0.6, 0)});
        shot(s, "close_noon", g -> new Vec3[]{g.floorPoint(3.6, 0.25).add(0, 1.3, 0), hilt(g)});
        s.server("dawn", ctx -> clock(ctx.server().getLevel(LogosDimension.CLOUDSEA), 24000 + 23700));
        shot(s, "close_dawn", g -> new Vec3[]{g.floorPoint(3.6, 0.25).add(0, 1.3, 0), hilt(g)});
        shot(s, "wide_dawn", g -> new Vec3[]{g.tunnelPoint(13).add(0, 1.4, 0), hilt(g)});
        // 破晓时按住拔：每 4 刻点一次，拔到一半拍一张（剑被提起、在颤），拔满后石座变空
        s.server("pull", ctx -> {
            var level = ctx.server().getLevel(LogosDimension.CLOUDSEA);
            Vec3[] c = {grotto.floorPoint(3.6, 0.25).add(0, 1.3, 0), hilt(grotto)};
            view(level, ctx.player(), c[0], c[1]);
        });
        for (int i = 0; i < 9; i++) s.server("tug " + i, ctx -> tug(ctx.server().getLevel(LogosDimension.CLOUDSEA), ctx.player())).ticks(4);
        s.screenshot("dawnvow_pulling");
        for (int i = 0; i < 10; i++) s.server("tug more " + i, ctx -> tug(ctx.server().getLevel(LogosDimension.CLOUDSEA), ctx.player())).ticks(4);
        s.ticks(6).screenshot("dawnvow_drawn").server("drawn", ctx -> {
            var level = ctx.server().getLevel(LogosDimension.CLOUDSEA);
            System.out.println("[dawnvow] after pull: " + level.getBlockState(grotto.stone()));
            if (!level.getBlockState(grotto.stone()).is(Dawnvow.EMPTY_STONE.get())) throw new IllegalStateException("破晓按住拔满后剑应当已拔出");
        });
        s.teardown("restore HUD", ctx -> Minecraft.getInstance().options.hideGui = false);
    }

    private static void tug(ServerLevel level, ServerPlayer player) {
        BlockPos at = grotto.stone();
        level.getBlockState(at).useWithoutItem(level, player, new BlockHitResult(at.getCenter(), Direction.UP, at, false));
    }
}
