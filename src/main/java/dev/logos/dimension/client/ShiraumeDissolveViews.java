package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.shiraume.ShiraumeBeast;
import dev.logos.dimension.shiraume.ShiraumeEntities;
import dev.logos.dimension.shiraume.ShiraumeFight;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Util;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.GameType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

/**
 * 狐形被击倒后的消散卡不卡（本地开发用）：白瓷台上召出狐形、击倒，量消散那几秒里最长的一帧。
 * v3 狐形有 6976 块、贴图 2048，消散按贴图像素切小格时一帧要切上亿格。
 */
@LDLRegisterClient(name = "shiraume_dissolve", group = "shiraume", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public class ShiraumeDissolveViews implements UIScenario {
    static final int FLOOR = ShiraumeViews.FLOOR;
    private static boolean measuring;
    private static long last, worst, total, killedAt;
    private static int frames, slow;
    private static final java.util.List<String> SLOW = new java.util.ArrayList<>();

    @SubscribeEvent
    static void frame(RenderFrameEvent.Post event) {
        if (!measuring) return;
        long now = Util.getNanos();
        if (last != 0) {
            long d = now - last;
            worst = Math.max(worst, d);
            total += d;
            if (d > 50_000_000L) {
                slow++;
                SLOW.add(String.format(java.util.Locale.ROOT, "%.0f ms at +%.2f s", d / 1e6, (now - killedAt) / 1e9));
            }
        }
        last = now;
        frames++;
    }

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("shiraume").defaultTimeoutMs(120_000).scenarioTimeoutMs(600_000);
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set 6000").runCommand("weather clear")
                .step("camera", ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.options.setCameraType(CameraType.FIRST_PERSON);
                    mc.options.renderDistance().set(8);
                    mc.options.fov().set(70);
                })
                .server("狐形", ctx -> {
                    ServerLevel level = ctx.server().overworld();
                    ShiraumeViews.arena(level);
                    ShiraumeFight.reset(level);
                    ctx.player().teleportTo(level, .5, FLOOR + 1, -9.5, java.util.Set.of(), 0, 10, false);
                    ShiraumeBeast b = ShiraumeEntities.BEAST.get().create(level, EntitySpawnReason.COMMAND);
                    b.snapTo(.5, FLOOR + 1, 3.5, 180, 0);
                    b.setYHeadRot(180);
                    b.setYBodyRot(180);
                    level.addFreshEntity(b);
                    ShiraumeFight.adopt(level, b);
                    b.setScripted(true);
                })
                .ticks(60)
                .step("开始量", ctx -> {
                    last = 0;
                    worst = 0;
                    frames = 0;
                    measuring = true;
                })
                .ticks(20)
                .step("平时最长一帧", ctx -> System.out.println("[shiraume] before defeat: worst frame " + worst / 1e6 + " ms over " + frames + " frames"))
                .step("重新量", ctx -> {
                    last = 0;
                    worst = 0;
                    total = 0;
                    frames = 0;
                    slow = 0;
                    SLOW.clear();
                    killedAt = Util.getNanos();
                })
                .server("击倒", ctx -> {
                    ServerLevel level = ctx.server().overworld();
                    ShiraumeBeast b = ShiraumeViews.beast(level);
                    b.setScripted(false);
                    b.hurtServer(level, level.damageSources().genericKill(), 1e6f);
                })
                // 量的这段不截图：截一张整屏的图本身就要卡两百毫秒
                .ticks(140)
                .step("停", ctx -> {
                    measuring = false;
                    System.out.println("[shiraume] dissolve: worst frame " + worst / 1e6 + " ms, average " + (frames > 1 ? total / 1e6 / (frames - 1) : 0)
                            + " ms, frames over 50 ms " + slow + " " + SLOW + ", frames " + frames);
                })
                .check("消散时最长的一帧不到 250 毫秒", ctx -> worst < 250_000_000L)
                // 再来一只，这次只截图看样子
                .server("又一只", ctx -> {
                    ServerLevel level = ctx.server().overworld();
                    ShiraumeFight.reset(level);
                    ShiraumeBeast b = ShiraumeEntities.BEAST.get().create(level, EntitySpawnReason.COMMAND);
                    b.snapTo(.5, FLOOR + 1, 3.5, 180, 0);
                    b.setYHeadRot(180);
                    b.setYBodyRot(180);
                    level.addFreshEntity(b);
                    ShiraumeFight.adopt(level, b);
                })
                .ticks(40)
                .step("量第二只", ctx -> {
                    last = 0;
                    worst = 0;
                    total = 0;
                    frames = 0;
                    slow = 0;
                    SLOW.clear();
                    killedAt = Util.getNanos();
                    measuring = true;
                })
                .server("击倒第二只", ctx -> {
                    ServerLevel level = ctx.server().overworld();
                    ShiraumeViews.beast(level).hurtServer(level, level.damageSources().genericKill(), 1e6f);
                })
                .ticks(78)
                .step("停第二只", ctx -> {
                    measuring = false;
                    System.out.println("[shiraume] second dissolve: worst frame " + worst / 1e6 + " ms, average " + (frames > 1 ? total / 1e6 / (frames - 1) : 0)
                            + " ms, frames over 50 ms " + slow + " " + SLOW + ", frames " + frames);
                })
                .screenshot("dissolve_mid");
    }
}
