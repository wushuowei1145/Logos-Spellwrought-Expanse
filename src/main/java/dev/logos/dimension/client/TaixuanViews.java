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
import net.minecraft.world.phys.Vec3;

/** 开发客户端在真实的云岫峰林候选点放置原版结构，记录浮空宗门、深渊、链台、雾层、镇渊柱、雾底场地和栈道的画面。 */
public abstract class TaixuanViews implements UIScenario {
    /** 机位（画布坐标，脚下那格）和看向的点：0 宗门全景 1 深渊口 2 链台 3 雾层 4 镇渊柱与铁链 5 雾底场地 7 浮岛底下；6 栈道按路线取。 */
    private static final int[][] EYES = {{300, 236, 320}, {126, 121, 196}, {150, 100, 160}, {126, 91, 110}, {126, 150, 140}, {150, 10, 150}, {0, 0, 0}, {126, 140, 98}};
    private static final int[][] TARGETS = {{126, 190, 126}, {126, 72, 112}, {126, 87, 126}, {126, 42, 72}, {185, 165, 185}, {126, 11, 126}, {0, 0, 0}, {126, 196, 126}};
    private static final String[] NAMES = {"taixuan_floating_sect", "taixuan_abyss_rim", "taixuan_chain_platform", "taixuan_mist_layer",
        "taixuan_tower_chain", "taixuan_fog_arena", "taixuan_plank_walk", "taixuan_island_underside"};
    private TaixuanGameTests.Site site;
    private Vec3 look;
    protected int view() { return 0; }

    @Override public void configure(ScenarioOptions options) {
        options.tags("taixuan").defaultTimeoutMs(480000).scenarioTimeoutMs(600000);
    }

    /** 栈道机位：走过三分之一处站在走道上，看前面二十格。 */
    private static int[][] walk() {
        var points = TaixuanGameTests.manifest().getAsJsonObject("routes").getAsJsonArray("plank_walk");
        var eye = points.get(points.size() / 3).getAsJsonArray();var target = points.get(points.size() / 3 + 20).getAsJsonArray();
        return new int[][]{{eye.get(0).getAsInt(), eye.get(1).getAsInt() + 1, eye.get(2).getAsInt()},
            {target.get(0).getAsInt(), target.get(1).getAsInt() + 1, target.get(2).getAsInt()}};
    }

    @Override public void define(ScenarioBuilder scenario) {
        scenario.setGameMode(GameType.CREATIVE).runCommand("difficulty peaceful")
            .step("设置太玄灵境机位", ctx -> {
                var mc = Minecraft.getInstance();
                mc.options.hideGui = true;
                mc.options.fov().set(view() == 0 ? 55 : 70);
                mc.options.renderDistance().set(24);
                mc.options.broadcastOptions();
            })
            .server("在云岫峰林候选点拼装地标", ctx -> {
                var level = ctx.server().getLevel(LogosDimension.CLOUDSEA);
                site = TaixuanGameTests.placeStudy(level);
                int[] e = view() == 6 ? walk()[0] : EYES[view()], t = view() == 6 ? walk()[1] : TARGETS[view()];
                var eye = site.pos(e[0], e[1], e[2]).getBottomCenter();
                look = site.pos(t[0], t[1], t[2]).getCenter();
                var direction = look.subtract(eye.add(0, ctx.player().getEyeHeight(), 0));
                float yaw = (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
                float pitch = (float) -Math.toDegrees(Math.atan2(direction.y, Math.hypot(direction.x, direction.z)));
                DimensionTravel.teleport(ctx.player(), level, eye, yaw, pitch);
                ctx.player().getAbilities().flying = true;ctx.player().onUpdateAbilities();
                level.clockManager().setTotalTicks(level.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK)
                    .getOrThrow(JourneySeasons.CLOCK), JourneySeasons.SEASON_TICKS + 6000);
                level.getWeatherData().setRaining(false);level.getWeatherData().setThundering(false);
                System.out.println("TAIXUAN_CLIENT view=" + NAMES[view()] + " anchor=" + site.anchor() + " rotation=" + site.rotation() + " tiles=" + TaixuanGameTests.tiles(site));
            })
            .waitUntil("深渊与浮岛区块抵达客户端", ctx -> {
                var mc = Minecraft.getInstance();
                return site != null && mc.level != null && mc.level.dimension().equals(LogosDimension.CLOUDSEA)
                    && TaixuanGameTests.isPlatformCenter(mc.level.getBlockState(site.center(86)))
                    && mc.level.hasChunkAt(BlockPos.containing(look));
            }).ticks(280)
            .step("对准实际生成的地标", ctx -> {
                var player = Minecraft.getInstance().player;var direction = look.subtract(player.getEyePosition());
                player.setYRot((float) Math.toDegrees(Math.atan2(-direction.x, direction.z)));
                player.setXRot((float) -Math.toDegrees(Math.atan2(direction.y, Math.hypot(direction.x, direction.z))));
            }).ticks(60)
            .checkServer("使用原版地形", ctx -> NativeWorlds.vanilla(ctx.player().level()))
            .checkServer("结构部件接齐", ctx -> TaixuanGameTests.tiles(site) == TaixuanGameTests.manifest().get("tiles").getAsLong())
            .check("机位不在实体方块里", ctx -> {
                var mc = Minecraft.getInstance();var at = BlockPos.containing(mc.player.getEyePosition());
                return mc.level.getBlockState(at).getCollisionShape(mc.level, at).isEmpty();
            })
            .check("镜头对准目标", ctx -> Minecraft.getInstance().player.getLookAngle().dot(look.subtract(Minecraft.getInstance().player.getEyePosition()).normalize()) > .98)
            .screenshot(NAMES[view()])
            .teardown("恢复界面", ctx -> Minecraft.getInstance().options.hideGui = false);
    }

    @LDLRegisterClient(name="taixuan_floating_sect", group="taixuan", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
    public static class Sect extends TaixuanViews {}
    @LDLRegisterClient(name="taixuan_abyss_rim", group="taixuan", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
    public static class AbyssRim extends TaixuanViews { @Override protected int view() { return 1; } }
    @LDLRegisterClient(name="taixuan_chain_platform", group="taixuan", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
    public static class ChainPlatform extends TaixuanViews { @Override protected int view() { return 2; } }
    @LDLRegisterClient(name="taixuan_mist_layer", group="taixuan", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
    public static class MistLayer extends TaixuanViews { @Override protected int view() { return 3; } }
    @LDLRegisterClient(name="taixuan_tower_chain", group="taixuan", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
    public static class TowerChain extends TaixuanViews { @Override protected int view() { return 4; } }
    @LDLRegisterClient(name="taixuan_fog_arena", group="taixuan", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
    public static class FogArena extends TaixuanViews { @Override protected int view() { return 5; } }
    @LDLRegisterClient(name="taixuan_plank_walk", group="taixuan", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
    public static class PlankWalk extends TaixuanViews { @Override protected int view() { return 6; } }
    @LDLRegisterClient(name="taixuan_island_underside", group="taixuan", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
    public static class IslandUnderside extends TaixuanViews { @Override protected int view() { return 7; } }
}
