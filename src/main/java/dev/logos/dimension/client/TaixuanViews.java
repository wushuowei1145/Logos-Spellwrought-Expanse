package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.*;
import dev.logos.dimension.cloudsea.JourneySeasons;
import dev.logos.dimension.gametest.TaixuanGameTests;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** 开发客户端在真实地形候选点放置原版结构，记录宗门、城池与园林画面。 */
public abstract class TaixuanViews implements UIScenario {
    private TaixuanGameTests.Site site;
    private Vec3 look;
    protected int view() { return 0; }

    @Override public void configure(ScenarioOptions options) {
        options.tags("taixuan").defaultTimeoutMs(480000).scenarioTimeoutMs(600000);
    }

    @Override public void define(ScenarioBuilder scenario) {
        scenario.setGameMode(GameType.CREATIVE).runCommand("difficulty peaceful")
            .step("设置太玄灵境机位", ctx -> {
                var mc = Minecraft.getInstance();
                mc.options.hideGui = true;
                mc.options.fov().set(view() == 2 ? 66 : view() == 1 ? 65 : view() == 3 ? 65 : 55);
                mc.options.renderDistance().set(24);
                mc.options.broadcastOptions();
            })
            .server("在自然群系候选点拼装地标", ctx -> {
                var level = ctx.server().getLevel(LogosDimension.CLOUDSEA);
                site = TaixuanGameTests.placeStudy(level);
                var eye = view() == 3 ? site.pos(151, 10, 214).getBottomCenter() : view() == 2 ? site.pos(208, 150, 165).getBottomCenter()
                    : view() == 1 ? site.pos(292, 140, 326).getBottomCenter() : site.pos(244, 178, 284).getBottomCenter();
                look = (view() == 3 ? site.pos(151, 8, 201) : view() == 2 ? site.pos(173, 135, 112) : view() == 1 ? site.pos(126, 9, 126) : site.pos(126, 146, 121)).getCenter();
                var direction = look.subtract(eye.add(0, ctx.player().getEyeHeight(), 0));
                float yaw = (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
                float pitch = (float) -Math.toDegrees(Math.atan2(direction.y, Math.hypot(direction.x, direction.z)));
                DimensionTravel.teleport(ctx.player(), level, eye, yaw, pitch);
                ctx.player().getAbilities().flying = true;ctx.player().onUpdateAbilities();
                level.clockManager().setTotalTicks(level.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK)
                    .getOrThrow(JourneySeasons.CLOCK), JourneySeasons.SEASON_TICKS + 6000);
                level.getWeatherData().setRaining(false);level.getWeatherData().setThundering(false);
                System.out.println("TAIXUAN_CLIENT view=" + view() + " anchor=" + site.anchor() + " tiles=" + TaixuanGameTests.tiles(site));
            })
            .waitUntil("城市与浮峰区块抵达客户端", ctx -> {
                var mc = Minecraft.getInstance();
                return site != null && mc.level != null && mc.level.dimension().equals(LogosDimension.CLOUDSEA)
                    && mc.level.getBlockState(site.pos(126, 4, 126)).is(Blocks.CALCITE);
            }).ticks(280)
            .step("对准实际生成的地标", ctx -> {
                var player = Minecraft.getInstance().player;var direction = look.subtract(player.getEyePosition());
                player.setYRot((float) Math.toDegrees(Math.atan2(-direction.x, direction.z)));
                player.setXRot((float) -Math.toDegrees(Math.atan2(direction.y, Math.hypot(direction.x, direction.z))));
            }).ticks(40)
            .checkServer("使用原版地形", ctx -> NativeWorlds.vanilla(ctx.player().level()))
            .checkServer("729 个结构部件接齐", ctx -> TaixuanGameTests.tiles(site) == 729)
            .check("机位不在实体方块里", ctx -> {
                var mc = Minecraft.getInstance();var at = BlockPos.containing(mc.player.getEyePosition());
                return mc.level.getBlockState(at).getCollisionShape(mc.level, at).isEmpty();
            })
            .check("镜头对准目标", ctx -> Minecraft.getInstance().player.getLookAngle().dot(look.subtract(Minecraft.getInstance().player.getEyePosition()).normalize()) > .98)
            .screenshot(view() == 3 ? "taixuan_street" : view() == 2 ? "taixuan_garden" : view() == 1 ? "taixuan_city" : "taixuan_floating_sect")
            .teardown("恢复界面", ctx -> Minecraft.getInstance().options.hideGui = false);
    }

    @LDLRegisterClient(name="taixuan_floating_sect", group="taixuan", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
    public static class Sect extends TaixuanViews {}
    @LDLRegisterClient(name="taixuan_city", group="taixuan", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
    public static class City extends TaixuanViews { @Override protected int view() { return 1; } }
    @LDLRegisterClient(name="taixuan_garden", group="taixuan", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
    public static class Garden extends TaixuanViews { @Override protected int view() { return 2; } }
    @LDLRegisterClient(name="taixuan_street", group="taixuan", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
    public static class Street extends TaixuanViews { @Override protected int view() { return 3; } }
}
