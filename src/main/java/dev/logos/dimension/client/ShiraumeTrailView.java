package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import dev.logos.dimension.camera.CameraPoint;
import dev.logos.dimension.camera.CameraShot;
import dev.logos.dimension.camera.CameraShots;
import dev.logos.dimension.client.camera.CameraDirector;
import dev.logos.dimension.client.shiraume.ShiraumeTrails;
import dev.logos.dimension.client.trail.WeaponTrails;
import dev.logos.dimension.shiraume.Shiraume;
import dev.logos.dimension.shiraume.ShiraumeBeast;
import dev.logos.dimension.shiraume.ShiraumeBoss;
import dev.logos.dimension.shiraume.ShiraumeEntities;
import dev.logos.dimension.shiraume.ShiraumeFight;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 白梅手里流火的刀光（docs/WEAPON_TRAILS.md）实际试玩：在白瓷平台上召出人形，停掉她的 AI，逐一放各招，
 * 每招挥到底那一刻截图并检查留下了刀光、样式是流火的；待机、起手蓄势时不出刀光，挥完散尽。最后换成狐形，叼着刀甩头横扫。
 * 截图的时刻按模型上动画自己播到的秒数挑（{@link ShiraumeTrails#clipTime}），和刀光出光用的是同一个时刻。
 */
@LDLRegisterClient(name = "shiraume_trails", group = "weapon_trails_ryuka", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
public class ShiraumeTrailView implements UIScenario {
    /** 人形放的招（招式名）；动画名是 {@code ryuka_} 加招式名。 */
    private static final String[] HUMAN = {"kesa", "gyaku", "yoko", "tsuki", "ichimonji_cut", "tsubame_1", "tsubame_2",
            "sanren_1", "sanren_2", "sanren_3", "kiriage", "recover_slash", "deflect"};
    /** 她站的地方与朝向（面朝北，-Z）；每招之前放回这里，突き往前冲的四格不累积。 */
    private static final Vec3 STAND = new Vec3(.5, ShiraumeViews.FLOOR + 1, 3.5);

    private boolean previousHideGui;
    private int peakRibbons, peakSparks;
    /** 起手、蓄势（出光时段之前）看到过刀光。 */
    private boolean early;

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("weapon_trails_ryuka").defaultTimeoutMs(60_000).scenarioTimeoutMs(400_000);
    }

    private static @Nullable Mob boss(Class<? extends Mob> type) {
        var level = Minecraft.getInstance().level;
        return level == null ? null : level.getEntitiesOfClass(type, ShiraumeViews.AREA).stream().findFirst().orElse(null);
    }

    private static String style(@Nullable Mob boss) {
        var s = boss == null ? null : WeaponTrails.styleOf("shiraume_ryuka:" + boss.getId());
        return s == null ? "" : s.id().getPath();
    }

    /** 镜头跟着她：{@code from} 是相对她脚底的机位，看向她 {@code lookHeight} 格高处。 */
    private static void shoot(ServerPlayer player, Mob boss, Vec3 from, double lookHeight) {
        CameraShots.play(player, CameraShot.of(CameraPoint.on(boss, from), CameraPoint.on(boss, new Vec3(0, lookHeight, 0)))
                .timing(0, 20 * 300, 0).keepControl());
    }

    private static void stand(Mob boss) {
        boss.snapTo(STAND.x, STAND.y, STAND.z, 180, 0);
        boss.setYHeadRot(180);
        boss.setYBodyRot(180);
        boss.setDeltaMovement(Vec3.ZERO);
    }

    /** 放一招：挥到底（出光时段将尽）那一刻截图，检查留下刀光、起手时没有、是流火的样式。 */
    private void play(ScenarioBuilder s, Class<? extends Mob> type, String move, String animation, String shot) {
        float[] w = ShiraumeTrails.window(animation);
        if (w == null) throw new IllegalArgumentException(animation + " 不出刀光");
        s.step("清零 " + shot, ctx -> { peakRibbons = 0; early = false; })
                .server("放 " + shot, ctx -> {
                    var level = ctx.server().overworld();
                    Mob b = type == Shiraume.class ? ShiraumeViews.human(level) : ShiraumeViews.beast(level);
                    stand(b);
                    ((ShiraumeBoss) b).play(move);
                })
                .waitUntil(shot + " 挥到底", ctx -> {
                    Mob b = boss(type);
                    double t = b == null ? -1 : ShiraumeTrails.clipTime(b, animation);
                    int ribbons = WeaponTrails.ribbons();
                    peakRibbons = Math.max(peakRibbons, ribbons);
                    peakSparks = Math.max(peakSparks, WeaponTrails.sparks());
                    if (t > .05 && t < w[0] - .08 && ribbons > 0) early = true;
                    // 截图要过几帧才真正拍下（约 0.08 秒），提前一点，拍到的正是刚挥到底
                    return t >= w[1] - .06;
                })
                .screenshot(shot)
                .check(shot + " 留下刀光", ctx -> peakRibbons > 0)
                .check(shot + " 起手时不出刀光", ctx -> !early)
                .check(shot + " 是流火的刀光", ctx -> "ryuka".equals(style(boss(type))));
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("difficulty normal").runCommand("weather clear").runCommand("time set 6000")
                .runCommand("gamerule spawn_mobs false")
                .step("视口", ctx -> {
                    var mc = Minecraft.getInstance(); previousHideGui = mc.options.hideGui;
                    mc.options.hideGui = true;
                    mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                    mc.options.renderDistance().set(8);
                    mc.options.fov().set(70);
                    var w = mc.getWindow(); w.setWindowed(1280, 720); w.setWidth(1280); w.setHeight(720);
                    mc.getMainRenderTarget().resize(1280, 720); mc.gameRenderer.resize(1280, 720); mc.resizeGui();
                })
                .server("白瓷平台与白梅", ctx -> {
                    ServerLevel level = ctx.server().overworld();
                    ShiraumeViews.arena(level);
                    ShiraumeFight.reset(level);
                    // 玩家空手站在远处，不进画面
                    ctx.player().getInventory().clearContent();
                    ctx.player().teleportTo(level, .5, ShiraumeViews.FLOOR + 1, -22.5, java.util.Set.of(), 0, 8, false);
                    Shiraume boss = ShiraumeEntities.SHIRAUME.get().create(level, EntitySpawnReason.COMMAND);
                    stand(boss);
                    level.addFreshEntity(boss);
                    boss.setScripted(true);
                    // 镜头在她左前方偏高
                    shoot(ctx.player(), boss, new Vec3(-3.4, 2.2, -3.8), 1.5);
                })
                .ticks(40)
                .check("待机时不出刀光", ctx -> WeaponTrails.ribbons() == 0);
        for (String move : HUMAN) {
            play(s, Shiraume.class, move, "ryuka_" + move, "human_" + move);
            s.server("回到待机 " + move, ctx -> ShiraumeViews.human(ctx.server().overworld()).play("idle")).ticks(14)
                    .check(move + " 之后刀光散尽", ctx -> WeaponTrails.ribbons() == 0);
        }
        // 另一个角度：她的右前方、低一点（看袈裟、横扫与燕返的弧面）
        s.server("右前方机位", ctx -> shoot(ctx.player(), ShiraumeViews.human(ctx.server().overworld()), new Vec3(3.8, 1.3, -3.6), 1.6))
                .ticks(4);
        for (String move : new String[]{"kesa", "yoko", "tsubame_1", "sanren_2"}) {
            play(s, Shiraume.class, move, "ryuka_" + move, "human_right_" + move);
            s.server("回到待机 右前方 " + move, ctx -> ShiraumeViews.human(ctx.server().overworld()).play("idle")).ticks(14);
        }
        s.check("刀尖甩出花瓣", ctx -> peakSparks > 0)
                // ---- 狐形：叼着刀甩头横扫
                .server("换成狐形", ctx -> {
                    ServerLevel level = ctx.server().overworld();
                    var human = ShiraumeViews.human(level);
                    if (human != null) human.discard();
                    ShiraumeBeast beast = ShiraumeEntities.BEAST.get().create(level, EntitySpawnReason.COMMAND);
                    stand(beast);
                    level.addFreshEntity(beast);
                    beast.setScripted(true);
                    shoot(ctx.player(), beast, new Vec3(-4.2, 5.2, -5.2), 1.6);
                })
                .ticks(40)
                .check("狐形待机时不出刀光", ctx -> WeaponTrails.ribbons() == 0);
        play(s, ShiraumeBeast.class, "bite_slash", "fox_bite_slash", "beast_bite_slash");
        s.server("狐形回到待机", ctx -> ShiraumeViews.beast(ctx.server().overworld()).play("idle_combat")).ticks(14)
                .check("狐形挥完刀光散尽", ctx -> WeaponTrails.ribbons() == 0)
                .server("右前方机位 狐形", ctx -> shoot(ctx.player(), ShiraumeViews.beast(ctx.server().overworld()), new Vec3(4.8, 4.4, -4.2), 1.6))
                .ticks(4);
        play(s, ShiraumeBeast.class, "bite_slash", "fox_bite_slash", "beast_right_bite_slash");
        s.server("收场", ctx -> {
                    ServerLevel level = ctx.server().overworld();
                    var beast = ShiraumeViews.beast(level);
                    if (beast != null) beast.discard();
                    ShiraumeFight.reset(level);
                })
                .teardown("恢复", ctx -> {
                    var mc = Minecraft.getInstance();
                    CameraDirector.returnNow();
                    mc.options.hideGui = previousHideGui;
                    mc.options.setCameraType(CameraType.FIRST_PERSON);
                });
    }
}
