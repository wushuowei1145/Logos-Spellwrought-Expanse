package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.cloudsea.FoxWildsBlocks;
import dev.logos.dimension.cloudsea.JourneySeasons;
import dev.logos.dimension.fox.FoxSanctuaryStructure;
import dev.logos.dimension.fox.SanctuaryVaultPiece;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;

import java.util.function.Function;

/**
 * 神狐聚落地下封印场地的实机预览（本地开发用）：让每处聚落都带地下场地，定位一处后从封じの堂一路拍下去——
 * 回旋石阶、旧地下通道、大鸟居之间、楼门、封印之间；最后真的把九根封印杭一根根拆掉，看剩余提示与解封。
 */
@LDLRegisterClient(name = "fox_vault_views", group = "fox_dev", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
public class FoxVaultViews implements UIScenario {
    private static SanctuaryVaultPiece vault;

    @Override public void configure(ScenarioOptions o) {
        o.tags("fox_dev").defaultTimeoutMs(400000).scenarioTimeoutMs(900000);
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

    private ScenarioBuilder shot(ScenarioBuilder s, String name, Function<SanctuaryVaultPiece, Vec3[]> camera) {
        return s.server("go " + name, ctx -> {
                var level = ctx.server().getLevel(LogosDimension.CLOUDSEA);
                Vec3[] c = camera.apply(vault);
                view(level, ctx.player(), c[0], c[1]);
            })
            .ticks(140)
            .screenshot("vault_" + name);
    }

    private static Vec3 d(SanctuaryVaultPiece v, double u, double y, double w) {
        return v.designPoint(u, y, w);
    }

    /** 屏幕中下方那行提示（动作栏）现在显示的是不是 expected。 */
    private static void expectOverlay(net.minecraft.network.chat.Component expected) {
        String shown = null;
        try {
            for (var f : net.minecraft.client.gui.Gui.class.getDeclaredFields()) {
                if (f.getType() == net.minecraft.network.chat.Component.class && f.getName().toLowerCase().contains("overlay")) {
                    f.setAccessible(true);
                    var c = (net.minecraft.network.chat.Component) f.get(Minecraft.getInstance().gui);
                    shown = c == null ? null : c.getString();
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        System.out.println("[fox] overlay: " + shown);
        if (!expected.getString().equals(shown)) throw new IllegalStateException("提示不对：" + shown + "，应为 " + expected.getString());
    }

    /** 通道中线第 k 个折点。 */
    private static double[] pt(SanctuaryVaultPiece v, int k) {
        int[] p = v.path();
        k = Math.floorMod(k, p.length / 2);
        return new double[]{p[k * 2] + 0.5, p[k * 2 + 1] + 0.5};
    }

    @Override public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.SPECTATOR).runCommand("difficulty peaceful").runCommand("weather clear")
            .step("camera", ctx -> {
                var mc = Minecraft.getInstance();
                mc.options.hideGui = true;
                mc.options.renderDistance().set(12);
                mc.options.fov().set(75);
            })
            .server("locate", ctx -> {
                var level = ctx.server().getLevel(LogosDimension.CLOUDSEA);
                level.getGameRules().set(GameRules.RANDOM_TICK_SPEED, 0, ctx.server());
                String forced = System.getProperty("logos.foxPlan", System.getenv("LOGOS_FOX_PLAN"));
                FoxSanctuaryStructure.devPlan = forced == null ? -1 : Integer.parseInt(forced.trim());
                FoxSanctuaryStructure.devVault = 1;
                var start = FoxSanctuaryViews.locate(ctx.server());
                if (start == null) throw new IllegalStateException("没找到神狐聚落");
                for (var piece : start.getPieces()) if (piece instanceof SanctuaryVaultPiece v) vault = v;
                if (vault == null) throw new IllegalStateException("聚落没有地下封印场地");
                int[] sh = vault.shaft();
                System.out.println("[fox] vault at " + start.getChunkPos() + " street y=" + vault.streetLevel() + " shaft=" + sh[0] + "," + sh[1]
                    + " ground=" + sh[2] + " path=" + java.util.Arrays.toString(vault.path()));
                level.clockManager().setTotalTicks(level.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(JourneySeasons.CLOCK), 6000);
                Vec3 c = vault.designPoint(88, 0, 100);
                view(level, ctx.player(), c.add(0, 40, 0), c);
            })
            .ticks(900);
        // 地面上的封じの堂与堂里的井口
        shot(s, "gate_house", v -> {
            int[] sh = v.shaft();
            return look(d(v, sh[0] + 0.5, sh[2] + 6, sh[1] + 15.5), d(v, sh[0] + 0.5, sh[2] + 3, sh[1] + 0.5));
        });
        shot(s, "gate_house_side", v -> {
            int[] sh = v.shaft();
            return look(d(v, sh[0] + 13.5, sh[2] + 5, sh[1] + 9.5), d(v, sh[0] + 0.5, sh[2] + 3, sh[1] + 0.5));
        });
        // 堂里一角往下看：回旋石阶绕着心柱下去
        shot(s, "well_top", v -> {
            int[] sh = v.shaft();
            return look(d(v, sh[0] + 3.3, sh[2] + 3.6, sh[1] + 3.3), d(v, sh[0] + 3.0, sh[2] - 8, sh[1] - 2.5));
        });
        shot(s, "well_mid", v -> {
            int[] sh = v.shaft();
            return look(d(v, sh[0] + 3.0, sh[2] - 12, sh[1] - 2.5), d(v, sh[0] + 3.0, sh[2] - 18, sh[1] + 3.5));
        });
        // 井底：站在心柱侧面，看出口与外面的通道
        shot(s, "well_bottom", v -> {
            int[] sh = v.shaft();
            double[] a = pt(v, 0), b = pt(v, 1);
            double ex = Math.signum(b[0] - a[0]), ez = Math.signum(b[1] - a[1]);
            double cx = sh[0] + 0.5 + ez * 2.5, cz = sh[1] + 0.5 - ex * 2.5;
            return look(d(v, cx, -29, cz), d(v, a[0] + ex * 4, -27.4, a[1] + ez * 4));
        });
        // 旧地下通道：出井那一段与快到大厅的那一段
        shot(s, "corridor_start", v -> {
            double[] a = pt(v, 1), b = pt(v, 2);
            return look(d(v, a[0], -28.3, a[1]), d(v, b[0], -28.3, b[1]));
        });
        shot(s, "corridor_deep", v -> {
            double[] a = pt(v, -3), b = pt(v, -2);
            double t = 0.35;
            return look(d(v, a[0] + (b[0] - a[0]) * t, -28.3, a[1] + (b[1] - a[1]) * t), d(v, b[0], -28.3, b[1]));
        });
        // 大鸟居之间
        shot(s, "hall_balcony", v -> {
            double[] e = pt(v, -1);
            double u = e[0] < 88 ? 54.5 : 122.5;
            return look(d(v, u, -28.3, 72.5), d(v, 88.5, -44, 88.5));
        });
        shot(s, "hall_overview", v -> look(d(v, 88.5, -27, 71.5), d(v, 88.5, -50, 95.5)));
        shot(s, "hall_stairs", v -> look(d(v, 70.5, -36, 84.5), d(v, 60.5, -38, 72.5)));
        shot(s, "hall_torii", v -> look(d(v, 88.5, -56.3, 77.5), d(v, 88.5, -42, 92.5)));
        shot(s, "gate_front", v -> look(d(v, 88.5, -55.5, 87.5), d(v, 88.5, -49, 97.5)));
        shot(s, "gate_passage", v -> look(d(v, 88.5, -56, 94.5), d(v, 88.5, -54, 106.5)));
        // 封印之间
        shot(s, "arena_entry", v -> look(d(v, 88.5, -55.5, 102.5), d(v, 88.5, -50, 131.5)));
        shot(s, "arena_high", v -> look(d(v, 70.5, -40, 103.5), d(v, 88.5, -55, 131.5)));
        shot(s, "arena_ceiling", v -> look(d(v, 80.5, -52, 125.5), d(v, 56.5, -32, 128.5)));
        shot(s, "arena_south", v -> look(d(v, 88.5, -50, 157.5), d(v, 88.5, -52, 110.5)));
        shot(s, "seal_close", v -> look(d(v, 91.5, -54.5, 114.5), d(v, 88.5, -55, 118.5)));
        shot(s, "stone_close", v -> look(d(v, 88.5, -53.5, 121.5), d(v, 88.5, -50, 131.5)));
        shot(s, "pillars", v -> look(d(v, 62.5, -50, 112.5), d(v, 47.5, -45, 140.5)));
        // 岩顶倒过来的那条街：参道、通往倒挂町屋的小路、倒立的街灯，一直通到南头倒挂的鸟居
        shot(s, "ceiling_road", v -> look(d(v, 88.5, -50, 122.5), d(v, 88.5, -26, 152.5)));
        shot(s, "ceiling_road_side", v -> look(d(v, 100.5, -48, 112.5), d(v, 76.5, -26, 128.5)));
        // 亮一点再看一遍全貌（不少玩家把亮度调到最高）
        s.step("bright", ctx -> Minecraft.getInstance().options.gamma().set(1.0));
        shot(s, "bright_hall", v -> look(d(v, 88.5, -27, 71.5), d(v, 88.5, -50, 95.5)));
        shot(s, "bright_arena", v -> look(d(v, 70.5, -40, 103.5), d(v, 88.5, -55, 131.5)));
        s.step("dim", ctx -> Minecraft.getInstance().options.gamma().set(0.5));
        // 先挖掉第一根封印杭脚下的底座：杭跟着塌掉，也要算断了一道（以前不算）
        s.server("break pedestal", ctx -> {
            var level = ctx.server().getLevel(LogosDimension.CLOUDSEA);
            var player = ctx.player();
            player.setGameMode(GameType.SURVIVAL);
            var seals = vault.seals();
            int stakes = 0;
            for (BlockPos p : seals) if (level.getBlockState(p).is(FoxWildsBlocks.SEAL_STAKE.get())) stakes++;
            System.out.println("[fox] seal stakes standing: " + stakes + " / " + seals.size() + " in arena=" + vault.inArena(seals.getFirst().above()));
            if (stakes != 9) throw new IllegalStateException("封印杭不是九根：" + stakes);
            BlockPos first = seals.getFirst();
            view(level, player, Vec3.atCenterOf(first).add(1.5, 0.5, 1.5), Vec3.atCenterOf(first));
            player.gameMode.destroyBlock(first.below());
            boolean gone = level.getBlockState(first).isAir() && level.getBlockState(first.above()).isAir();
            System.out.println("[fox] pedestal under seal 0 broken: stake gone=" + gone + " below=" + level.getBlockState(first.below()).getBlock());
            if (!gone) throw new IllegalStateException("挖掉底座后封印杭没有塌掉");
        }).ticks(5);
        s.step("seal message after pedestal", ctx -> expectOverlay(net.minecraft.network.chat.Component.translatable("block.logos_dimension.journey_seal_stake.left", 8)));
        // 再把剩下八根直接拆掉：每拆一根提示剩几道，拆完杀生石那边有动静
        s.server("break seals", ctx -> {
            var level = ctx.server().getLevel(LogosDimension.CLOUDSEA);
            var player = ctx.player();
            var seals = vault.seals();
            for (BlockPos p : seals) {
                if (level.getBlockState(p).isAir()) continue;
                view(level, player, Vec3.atCenterOf(p).add(1.5, 0.5, 1.5), Vec3.atCenterOf(p));
                player.gameMode.destroyBlock(p.above());
            }
            int left = 0;
            for (BlockPos p : seals) if (!level.getBlockState(p).isAir() || !level.getBlockState(p.above()).isAir()) left++;
            System.out.println("[fox] seal stakes left after breaking: " + left + " stone at " + vault.sealStone());
            if (left != 0) throw new IllegalStateException("还有封印杭没拆掉：" + left);
            player.setGameMode(GameType.SPECTATOR);
        }).ticks(5);
        s.step("seal message after all", ctx -> expectOverlay(net.minecraft.network.chat.Component.translatable("block.logos_dimension.journey_seal_stake.released")));
        shot(s, "after_release", v -> look(d(v, 88.5, -53.5, 121.5), d(v, 88.5, -50, 131.5)));
        // 首领战开始时拆掉杀生石、石台与九个底座：三组都拆空，地上不留掉落物，露出圆台原本的地面
        s.server("clear center", ctx -> {
            var level = ctx.server().getLevel(LogosDimension.CLOUDSEA);
            int stone = vault.clear(level, SanctuaryVaultPiece.Removable.STONE);
            int altar = vault.clear(level, SanctuaryVaultPiece.Removable.ALTAR);
            int pedestals = vault.clear(level, SanctuaryVaultPiece.Removable.PEDESTALS);
            int left = 0;
            for (var group : SanctuaryVaultPiece.Removable.values()) {
                for (BlockPos p : vault.blocks(group)) if (!level.getBlockState(p).isAir()) left++;
            }
            var box = new net.minecraft.world.phys.AABB(vault.sealStone()).inflate(24, 8, 24);
            int drops = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, box).size();
            BlockPos floor = vault.sealStone().below(3);
            System.out.println("[fox] cleared stone=" + stone + " altar=" + altar + " pedestals=" + pedestals + " left=" + left
                + " drops=" + drops + " floor under stone=" + level.getBlockState(floor).getBlock());
            if (stone < 100 || altar < 50 || pedestals < 9 * 9 || left != 0 || drops != 0 || level.getBlockState(floor).isAir()) {
                throw new IllegalStateException("中间没拆干净或掉了东西");
            }
        });
        shot(s, "after_clear", v -> look(d(v, 88.5, -50, 112.5), d(v, 88.5, -57, 131.5)));
        s.teardown("restore HUD", ctx -> {
            Minecraft.getInstance().options.hideGui = false;
            Minecraft.getInstance().options.gamma().set(0.5);
            FoxSanctuaryStructure.devVault = -1;
        });
    }
}
