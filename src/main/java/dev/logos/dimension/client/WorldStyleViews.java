package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.client.screenfx.WorldStyleRenderer;
import dev.logos.dimension.worldstyle.StyleDoor;
import dev.logos.dimension.worldstyle.StyleRealm;
import dev.logos.dimension.worldstyle.StyleRealms;
import dev.logos.dimension.worldstyle.WorldStyle;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * 区域画风的实际客户端检查，分组 {@code world_style}，要在不开光影、Sodium、Iris 加两种光影包下各跑一遍。
 * 走路都是真按前进键：门的判定、进出区域和波都走真实流程。
 */
public abstract class WorldStyleViews implements UIScenario {
    private static final int GROUND = 100;
    private static final Vec3 FEET = new Vec3(.5, GROUND + 1, -1.5);
    private static final BlockPos DOOR_MIN = new BlockPos(-1, GROUND + 1, 10), DOOR_MAX = new BlockPos(1, GROUND + 4, 10);

    @Override public void configure(ScenarioOptions o) {
        o.tags("world_style").defaultTimeoutMs(30000).scenarioTimeoutMs(120000);
    }

    /** 一堵带金框门洞的墙。门前一根柱子和一块玻璃各挡住门的一部分，门里门外都有彩色的东西。 */
    private static void stage(ServerLevel level) {
        for (int x = -24; x <= 24; x++) for (int z = -10; z <= 36; z++) {
            level.setBlockAndUpdate(new BlockPos(x, GROUND, z), Blocks.GRASS_BLOCK.defaultBlockState());
            for (int y = 1; y <= 12; y++) level.setBlockAndUpdate(new BlockPos(x, GROUND + y, z), Blocks.AIR.defaultBlockState());
        }
        for (int x = -14; x <= 14; x++) for (int y = 1; y <= 8; y++) {
            boolean opening = x >= -1 && x <= 1 && y <= 4;
            boolean frame = x >= -2 && x <= 2 && y <= 5;
            if (!opening) level.setBlockAndUpdate(new BlockPos(x, GROUND + y, 10), (frame ? Blocks.GOLD_BLOCK : Blocks.STONE_BRICKS).defaultBlockState());
        }
        Block[] wool = {Blocks.RED_WOOL, Blocks.BLUE_WOOL, Blocks.YELLOW_WOOL, Blocks.MAGENTA_WOOL, Blocks.ORANGE_WOOL, Blocks.CYAN_WOOL};
        for (int i = 0; i < wool.length; i++) for (int y = 1; y <= 4; y++) {
            level.setBlockAndUpdate(new BlockPos(-4 + i, GROUND + y, 16 + i * 2), wool[i].defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(-12 + i * 4 + (i >= 3 ? 4 : 0), GROUND + y, 4), wool[i].defaultBlockState());
        }
        for (int y = 1; y <= 2; y++) level.setBlockAndUpdate(new BlockPos(-1, GROUND + y, 6), Blocks.LIME_WOOL.defaultBlockState());
        for (int y = 1; y <= 4; y++) level.setBlockAndUpdate(new BlockPos(1, GROUND + y, 8), Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState());
        var golem = EntityType.IRON_GOLEM.create(level, EntitySpawnReason.COMMAND);
        golem.setNoAi(true);
        golem.setPos(6.5, GROUND + 1, 16.5);
        level.addFreshEntity(golem);
    }

    private static StyleRealm realm(WorldStyle style) {
        return StyleRealm.of(new BlockPos(-14, GROUND + 1, 10), new BlockPos(14, GROUND + 60, 36), style, StyleDoor.opening(DOOR_MIN, DOOR_MAX, Direction.NORTH));
    }

    protected void arrive(ScenarioBuilder s, WorldStyle style, boolean fabulous) {
        s.setGameMode(GameType.SURVIVAL).runCommand("time set 6000").runCommand("weather clear").runCommand("difficulty peaceful")
                .step("设置视口", ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.options.hideGui = false;
                    mc.player.getInventory().setSelectedSlot(0);
                    mc.options.setCameraType(CameraType.FIRST_PERSON);
                    mc.options.fov().set(70);
                    mc.options.improvedTransparency().set(fabulous);
                    mc.levelRenderer.allChanged();
                    var w = mc.getWindow(); w.setWindowed(1280, 720); w.setWidth(1280); w.setHeight(720);
                    mc.getMainRenderTarget().resize(1280, 720); mc.gameRenderer.resize(1280, 720); mc.resizeGui();
                })
                .server("搭场地", ctx -> {
                    ServerLevel level = ctx.server().overworld();
                    StyleRealms.clear(level);
                    stage(level);
                    DimensionTravel.teleport(ctx.player(), level, FEET, 0, -2);
                    ctx.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_PICKAXE));
                    StyleRealms.add(level, realm(style));
                })
                .waitUntil("到位", ctx -> {
                    var mc = Minecraft.getInstance();
                    return mc.player.position().distanceTo(FEET) < .01 && mc.player.onGround() && WorldStyleRenderer.realmCount() == 1;
                })
                .ticks(20);
    }

    protected static void walk(ScenarioBuilder s, boolean on) {
        s.step(on ? "按住前进" : "松开前进", ctx -> Minecraft.getInstance().options.keyUp.setDown(on));
    }

    protected void cleanup(ScenarioBuilder s) {
        s.teardown("恢复", ctx -> {
            var mc = Minecraft.getInstance();
            mc.options.keyUp.setDown(false);
            mc.options.improvedTransparency().set(false);
            mc.levelRenderer.allChanged();
            mc.options.setCameraType(CameraType.FIRST_PERSON);
        });
    }

    /** 完整走一遍：门外看门里，走近，穿门，看波前铺开，回头看门外，再走出来。 */
    protected void walkThrough(ScenarioBuilder s) {
        s.screenshot("outside");
        s.step("门外", ctx -> {
            ctx.check("门外不算进入", WorldStyleRenderer.current() == null);
            ctx.check("附近有门时通道在跑", WorldStyleRenderer.passes() > 0);
        });
        walk(s, true);
        s.waitUntil("走到门前", ctx -> Minecraft.getInstance().player.getZ() >= 7.6);
        s.screenshot("close");
        // 贴到门前，手盖在门洞上：门外的手不该被染色
        s.waitUntil("贴到门前", ctx -> Minecraft.getInstance().player.getZ() >= 9.2);
        s.screenshot("hand_over_door");
        s.waitUntil("穿过门", ctx -> WorldStyleRenderer.current() != null);
        s.step("确认走门", ctx -> ctx.check("是走门进来的", WorldStyleRenderer.enteredByDoor()));
        s.waitUntil("波到 6 格", ctx -> WorldStyleRenderer.waveRadius() >= 6);
        s.screenshot("wave_6");
        s.waitUntil("波到 24 格", ctx -> WorldStyleRenderer.waveRadius() >= 24);
        s.screenshot("wave_24");
        s.waitUntil("再走几步", ctx -> Minecraft.getInstance().player.getZ() >= 13);
        walk(s, false);
        s.waitUntil("波走完", ctx -> WorldStyleRenderer.waveRadius() < 0).ticks(5);
        s.screenshot("inside");
        s.step("回头", ctx -> {
            var player = Minecraft.getInstance().player;
            player.setYRot(180);
            player.setXRot(-2);
        }).ticks(3).screenshot("look_back");
        s.step("第三人称", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_BACK)).ticks(3).screenshot("third_person");
        s.step("回到第一人称", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON)).ticks(3);
        walk(s, true);
        s.waitUntil("走出门", ctx -> WorldStyleRenderer.current() == null);
        s.step("确认走门出来", ctx -> ctx.check("是走门出来的", WorldStyleRenderer.enteredByDoor()));
        s.waitUntil("再走几步", ctx -> Minecraft.getInstance().player.getZ() <= 5.5);
        walk(s, false);
        s.waitUntil("波走完", ctx -> WorldStyleRenderer.waveRadius() < 0).ticks(5);
        s.screenshot("left");
        s.step("回到门外", ctx -> ctx.check("走出来后不在区域里", WorldStyleRenderer.current() == null));
    }

    @LDLRegisterClient(name = "world_style_fairy", group = "world_style", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Fairy extends WorldStyleViews {
        @Override public void define(ScenarioBuilder s) {
            arrive(s, WorldStyle.FAIRY_TALE, false);
            walkThrough(s);
            cleanup(s);
        }
    }

    @LDLRegisterClient(name = "world_style_monochrome", group = "world_style", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Monochrome extends WorldStyleViews {
        @Override public void define(ScenarioBuilder s) {
            arrive(s, WorldStyle.MONOCHROME, false);
            walkThrough(s);
            cleanup(s);
        }
    }

    /** 原版高画质透明：半透明另走一条路，深度和颜色都不一样，开光影时不用它，要单独看。 */
    @LDLRegisterClient(name = "world_style_fabulous", group = "world_style", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Fabulous extends WorldStyleViews {
        @Override public void define(ScenarioBuilder s) {
            arrive(s, WorldStyle.MONOCHROME, true);
            s.step("确认选项", ctx -> ctx.check("高画质透明开着", Minecraft.getInstance().options.improvedTransparency().get()));
            walkThrough(s);
            cleanup(s);
        }
    }

    /** 不走门：传送进出时波外保持原来的样子，等波来替换。走远之后通道不跑，换维度时区域跟着换。 */
    @LDLRegisterClient(name = "world_style_teleport", group = "world_style", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Teleport extends WorldStyleViews {
        private long passesBefore;

        @Override public void define(ScenarioBuilder s) {
            arrive(s, WorldStyle.FAIRY_TALE, false);
            s.server("传送进去", ctx -> DimensionTravel.teleport(ctx.player(), ctx.server().overworld(), new Vec3(.5, GROUND + 1, 14), 0, 0));
            s.waitUntil("进入", ctx -> WorldStyleRenderer.current() != null);
            s.step("不是走门", ctx -> ctx.check("传送不算走门", !WorldStyleRenderer.enteredByDoor()));
            s.waitUntil("波到 10 格", ctx -> WorldStyleRenderer.waveRadius() >= 10);
            s.screenshot("teleport_in_wave");
            s.waitUntil("波走完", ctx -> WorldStyleRenderer.waveRadius() < 0);
            s.server("传送出来", ctx -> DimensionTravel.teleport(ctx.player(), ctx.server().overworld(), FEET, 0, 0));
            s.waitUntil("离开", ctx -> WorldStyleRenderer.current() == null);
            s.step("不是走门", ctx -> ctx.check("传送不算走门", !WorldStyleRenderer.enteredByDoor()));
            s.waitUntil("波走完", ctx -> WorldStyleRenderer.waveRadius() < 0);

            s.server("走得很远", ctx -> DimensionTravel.teleport(ctx.player(), ctx.server().overworld(), new Vec3(500.5, GROUND + 30, .5), 0, 0));
            s.waitUntil("到了", ctx -> Minecraft.getInstance().player.getX() > 400);
            s.step("记下通道次数", ctx -> passesBefore = WorldStyleRenderer.passes());
            s.ticks(10);
            s.step("通道不跑", ctx -> ctx.check("附近没有门时通道不跑", WorldStyleRenderer.passes() == passesBefore));

            s.server("去下界", ctx -> DimensionTravel.teleport(ctx.player(), ctx.server().getLevel(Level.NETHER), new Vec3(.5, 80, .5), 0, 0));
            s.waitUntil("下界没有区域", ctx -> Minecraft.getInstance().level.dimension() == Level.NETHER && WorldStyleRenderer.realmCount() == 0);
            s.server("回主世界", ctx -> DimensionTravel.teleport(ctx.player(), ctx.server().overworld(), FEET, 0, 0));
            s.waitUntil("主世界的区域回来了", ctx -> Minecraft.getInstance().level.dimension() == Level.OVERWORLD && WorldStyleRenderer.realmCount() == 1);
            s.server("清空", ctx -> StyleRealms.clear(ctx.server().overworld()));
            s.waitUntil("清掉了", ctx -> WorldStyleRenderer.realmCount() == 0);
            cleanup(s);
        }
    }

    /** 试玩命令：在玩家面前搭一座院子并登记成区域，真的走得进去。 */
    @LDLRegisterClient(name = "world_style_demo", group = "world_style", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Demo extends WorldStyleViews {
        @Override public void define(ScenarioBuilder s) {
            s.setGameMode(GameType.SURVIVAL).runCommand("time set 6000").runCommand("weather clear").runCommand("difficulty peaceful");
            s.step("设置视口", ctx -> {
                var mc = Minecraft.getInstance();
                mc.options.setCameraType(CameraType.FIRST_PERSON);
                var w = mc.getWindow(); w.setWindowed(1280, 720); w.setWidth(1280); w.setHeight(720);
                mc.getMainRenderTarget().resize(1280, 720); mc.gameRenderer.resize(1280, 720); mc.resizeGui();
            });
            s.server("站到空地上", ctx -> {
                ServerLevel level = ctx.server().overworld();
                StyleRealms.clear(level);
                for (int x = -20; x <= 20; x++) for (int z = -6; z <= 30; z++) {
                    level.setBlockAndUpdate(new BlockPos(x, GROUND, z), Blocks.GRASS_BLOCK.defaultBlockState());
                    for (int y = 1; y <= 10; y++) level.setBlockAndUpdate(new BlockPos(x, GROUND + y, z), Blocks.AIR.defaultBlockState());
                }
                DimensionTravel.teleport(ctx.player(), level, new Vec3(.5, GROUND + 1, .5), 0, 0);
            });
            s.waitUntil("到位", ctx -> Minecraft.getInstance().player.position().distanceTo(new Vec3(.5, GROUND + 1, .5)) < .01 && Minecraft.getInstance().player.onGround()).ticks(5);
            // 测试框架的 runCommand 是控制台身份，这里走真实的聊天命令
            s.step("输入命令", ctx -> Minecraft.getInstance().player.connection.sendCommand("alchemy realm demo fairy_tale"));
            s.waitUntil("区域登记好了", ctx -> WorldStyleRenderer.realmCount() == 1).ticks(10);
            s.screenshot("demo_outside");
            walk(s, true);
            s.waitUntil("走进去", ctx -> WorldStyleRenderer.current() != null);
            s.step("确认走门", ctx -> ctx.check("从门走进去", WorldStyleRenderer.enteredByDoor()));
            s.waitUntil("走到里面", ctx -> Minecraft.getInstance().player.getZ() >= 6);
            walk(s, false);
            s.waitUntil("波走完", ctx -> WorldStyleRenderer.waveRadius() < 0).ticks(3);
            s.screenshot("demo_inside");
            s.step("清空", ctx -> Minecraft.getInstance().player.connection.sendCommand("alchemy realm clear"));
            s.waitUntil("清掉了", ctx -> WorldStyleRenderer.realmCount() == 0);
            cleanup(s);
        }
    }
}
