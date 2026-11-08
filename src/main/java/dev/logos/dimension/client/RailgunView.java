package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.client.vfx.RailgunClient;
import dev.logos.dimension.economy.Currency;
import dev.logos.dimension.economy.Economy;
import dev.logos.lang.Element;
import dev.logos.runtime.ManaWrap;
import dev.logos.runtime.PlayerMagic;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * 超电磁炮彩蛋的实际客户端检查：真按住右键蓄力到发射，分别从第一人称、侧面（仿动画里人物在右、射线朝左的构图）
 * 和第三人称截蓄力与发射各阶段的图。每次发射前重搭靶场：三十格外一面带窗的楼，后面一堵黑曜石挡住射线。
 */
@LDLRegisterClient(name = "railgun", group = "railgun", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
public class RailgunView implements UIScenario {
    private boolean previousHideGui;
    private int camera = -1;
    /** 开始蓄力、发射时客户端世界的刻数：截图按特效自己的时钟等，不按场景步数。 */
    private long started, fired;

    @Override public void configure(ScenarioOptions o) { o.tags("railgun").defaultTimeoutMs(60000).scenarioTimeoutMs(240000); }

    private static void stage(ServerLevel level) {
        for (int x = -8; x <= 40; x++) for (int z = -12; z <= 12; z++) {
            level.setBlockAndUpdate(new BlockPos(x, 99, z), Blocks.GRASS_BLOCK.defaultBlockState());
            for (int y = 100; y < 120; y++) level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
        }
        // 一面楼：石砖墙面，玻璃窗格
        for (int x = 30; x <= 33; x++) for (int y = 100; y < 118; y++) for (int z = -10; z <= 10; z++) {
            boolean window = x == 30 && (y - 100) % 4 != 0 && Math.floorMod(z, 4) != 0;
            level.setBlockAndUpdate(new BlockPos(x, y, z), window ? Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState()
                    : x == 30 ? Blocks.WHITE_CONCRETE.defaultBlockState() : Blocks.STONE_BRICKS.defaultBlockState());
        }
        for (int y = 100; y < 118; y++) for (int z = -10; z <= 10; z++) level.setBlockAndUpdate(new BlockPos(36, y, z), Blocks.OBSIDIAN.defaultBlockState());
    }

    private ScenarioBuilder shot(ScenarioBuilder s, String view, int[] charge, int[] after) {
        s.server("搭靶场 " + view, ctx -> {
            stage(ctx.server().overworld());
            var golem = EntityType.IRON_GOLEM.create(ctx.server().overworld(), EntitySpawnReason.COMMAND);
            golem.setNoAi(true);
            golem.setPos(14.5, 100, .5);
            ctx.server().overworld().addFreshEntity(golem);
            ctx.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Economy.coin(Currency.IMPERIAL, 1), 16));
            ManaWrap.wrap(ctx.player(), Element.LIGHTNING, 1, 600);
            var magic = PlayerMagic.of(ctx.player());
            magic.maxMana = magic.mana = 10000;
            DimensionTravel.teleport(ctx.player(), ctx.server().overworld(), new Vec3(.5, 100, .5), -90, 0);
        }).waitUntil("缠绕同步 " + view, ctx -> ManaWrap.of(Minecraft.getInstance().player) != null).ticks(10);
        s.step("按住右键 " + view, ctx -> {
            var mc = Minecraft.getInstance();
            mc.options.keyUse.setDown(true);
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        }).waitUntil("开始蓄力 " + view, ctx -> RailgunClient.charging(Minecraft.getInstance().player.getId()))
                .step("记下蓄力时刻 " + view, ctx -> started = Minecraft.getInstance().level.getGameTime());
        for (int c : charge) s.waitUntil(view + " 蓄力第 " + c + " 刻", ctx -> Minecraft.getInstance().level.getGameTime() - started >= c)
                .screenshot(view + "_charge_" + c);
        s.waitUntil("发射 " + view, ctx -> RailgunClient.shots() > 0)
                .step("记下发射时刻 " + view, ctx -> fired = Minecraft.getInstance().level.getGameTime())
                .step("松开右键 " + view, ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.options.keyUse.setDown(false);
                    mc.gameMode.releaseUsingItem(mc.player);
                });
        for (int a : after) s.waitUntil(view + " 发射后第 " + a + " 刻", ctx -> Minecraft.getInstance().level.getGameTime() - fired >= a)
                .screenshot(view + "_fire_" + a);
        // 第一人称看自己开炮只有泛光；别的机位离得近，还会压暗一点
        boolean own = view.equals("first");
        return s.check("发射时跑过泛光、" + (own ? "没有" : "有") + "全屏调色 " + view, ctx -> dev.logos.dimension.client.screenfx.GlowBloom.passes() > 0
                        && (dev.logos.dimension.client.vfx.RailgunScreen.passes() > 0) != own)
                .waitUntil("特效结束 " + view, ctx -> RailgunClient.shots() == 0);
    }

    @Override public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.SURVIVAL).runCommand("time set 13600").runCommand("weather clear").runCommand("difficulty peaceful")
                .step("设置视口", ctx -> {
                    var mc = Minecraft.getInstance();
                    previousHideGui = mc.options.hideGui;
                    mc.options.hideGui = true;
                    mc.player.getInventory().setSelectedSlot(0);
                    mc.options.setCameraType(CameraType.FIRST_PERSON);
                    mc.options.fov().set(70);
                    var w = mc.getWindow(); w.setWindowed(1280, 720); w.setWidth(1280); w.setHeight(720);
                    mc.getMainRenderTarget().resize(1280, 720); mc.gameRenderer.resize(1280, 720); mc.resizeGui();
                });
        shot(s, "first", new int[] {8, 20}, new int[] {1, 4, 12, 30});
        s.server("侧面机位", ctx -> {
            var stand = EntityType.ARMOR_STAND.create(ctx.server().overworld(), EntitySpawnReason.COMMAND);
            stand.setInvisible(true);
            stand.setNoGravity(true);
            stand.snapTo(2, 99.7, -9.5, 0, 0);
            ctx.server().overworld().addFreshEntity(stand);
            camera = stand.getId();
        }).waitUntil("机位同步", ctx -> Minecraft.getInstance().level.getEntity(camera) != null)
                .step("切到侧面", ctx -> Minecraft.getInstance().setCameraEntity(Minecraft.getInstance().level.getEntity(camera)));
        shot(s, "side", new int[] {4, 12, 20, 26}, new int[] {1, 3, 6, 9, 14, 30});
        s.step("回到玩家", ctx -> {
            var mc = Minecraft.getInstance();
            mc.setCameraEntity(mc.player);
            mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        }).runCommand("time set 6000");
        shot(s, "back_day", new int[] {22}, new int[] {2, 8});
        s.step("第三人称正面", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_FRONT));
        shot(s, "front", new int[] {24}, new int[] {3});
        s.teardown("恢复", ctx -> {
            var mc = Minecraft.getInstance();
            mc.options.keyUse.setDown(false);
            mc.setCameraEntity(mc.player);
            mc.options.hideGui = previousHideGui;
            mc.options.setCameraType(CameraType.FIRST_PERSON);
        });
    }
}
