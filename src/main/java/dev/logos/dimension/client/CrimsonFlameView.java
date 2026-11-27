package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.client.foxfire.FoxfireClient;
import dev.logos.dimension.client.longsword.LongswordClient;
import dev.logos.dimension.dragonfire.Dragonfire;
import dev.logos.dimension.foxfire.FoxfireAttachments;
import dev.logos.dimension.foxfire.FoxfireCue;
import dev.logos.dimension.item.CrimsonFlameLongswordItem;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * 炽焰长剑实际试玩（docs/CRIMSON_FLAME_LONGSWORD.md）：真实按键打一式、格挡、格挡中左键的红莲劫焰斩、潜行突刺、疾跑旋风斩、
 * 空中坠星，第三人称和第一人称各截图。检查打出的都是龙炎（靶子最后挨的伤害类型）、横扫的游脉画成龙炎、
 * 冲刺时身上冒龙炎火星、坠星不摔伤自己。
 */
@LDLRegisterClient(name="crimson_flame_longsword", group="crimson_flame_longsword", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
public class CrimsonFlameView implements UIScenario {
    private static final Vec3 ORIGIN = new Vec3(.5, 100, .5);
    private boolean previousHideGui;
    /** 近处（一剑够得着）和远处（只有游脉够得着）的靶子。 */
    private int near = -1, far = -1;
    private int veins, embers;
    private float health;

    private static boolean phase(String... moves) {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        var p = LongswordClient.pose(mc.player.getUUID());
        if (p == null || p.variant() != CrimsonFlameLongswordItem.ITEM.get().combatWeapon().id()) return false;
        for (String m : moves) if (m.equals(p.move())) return true;
        return false;
    }
    private static double seconds() {
        var mc = Minecraft.getInstance();
        var p = LongswordClient.pose(mc.player.getUUID());
        if (p == null) return -1;
        return (mc.level.getGameTime() + mc.getDeltaTracker().getGameTimeDeltaPartialTick(false) - p.started()) / 20 * p.speed();
    }
    private static void camera(CameraType type) { Minecraft.getInstance().options.setCameraType(type); }
    private static void attack(boolean down) { Minecraft.getInstance().options.keyAttack.setDown(down); }
    private static void use(boolean down) {
        var mc = Minecraft.getInstance();
        mc.options.keyUse.setDown(down);
        if (down) mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        else mc.gameMode.releaseUsingItem(mc.player);
    }
    private static LivingEntity target(MinecraftServer server, int id) {
        return server.overworld().getEntity(id) instanceof LivingEntity e ? e : null;
    }
    /** 回到起点之后挨过打（掉了血），最后一下是龙炎。 */
    private static boolean dragonfire(LivingEntity e) {
        return e != null && e.getHealth() < e.getMaxHealth() && e.getLastDamageSource() != null && e.getLastDamageSource().is(Dragonfire.DAMAGE);
    }
    /** 回到起点、面朝南，补满靶子的血、熄掉上一招点的龙炎缠身（最多烧 15 秒，会拖到下一招的检查里）。 */
    private void reset(ServerPlayer p) {
        DimensionTravel.teleport(p, p.level(), ORIGIN, 0, 0);
        for (int id : new int[]{near, far}) {
            var e = target(p.level().getServer(), id);
            if (e != null) { e.removeData(FoxfireAttachments.BURN); e.setHealth(e.getMaxHealth()); }
        }
    }

    /** 左键点一下：按下两刻再松开。 */
    private static ScenarioBuilder click(ScenarioBuilder s, String name) {
        return s.step(name + " 按下左键", ctx -> attack(true)).ticks(2).step(name + " 松开左键", ctx -> attack(false));
    }

    /** 等到 {@code move} 播到 {@code at} 秒拍一张。 */
    private static ScenarioBuilder shot(ScenarioBuilder s, String name, String move, double at) {
        return s.waitUntil(name + " 开始", ctx -> phase(move))
                .waitUntil(name + " 到 " + at + " 秒", ctx -> phase(move) && seconds() >= at).screenshot(name);
    }

    @Override public void configure(ScenarioOptions o) { o.defaultTimeoutMs(60000).scenarioTimeoutMs(300000); }

    @Override public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set day").runCommand("weather clear").runCommand("difficulty easy")
                .runCommand("gamerule spawn_mobs false").runCommand("gamerule spawn_monsters false")
                .step("设置视口", ctx -> {
                    var mc = Minecraft.getInstance(); previousHideGui = mc.options.hideGui;
                    mc.options.hideGui = false; mc.player.getInventory().setSelectedSlot(0);
                    camera(CameraType.THIRD_PERSON_BACK); mc.options.fov().set(70);
                    var w = mc.getWindow(); w.setWindowed(1280, 720); w.setWidth(1280); w.setHeight(720);
                    mc.getMainRenderTarget().resize(1280, 720); mc.gameRenderer.resize(1280, 720); mc.resizeGui();
                }).server("设置练剑场与靶子", ctx -> {
                    var level = ctx.server().overworld();
                    for (int x = -10; x <= 10; x++) for (int z = -6; z <= 24; z++) {
                        level.setBlockAndUpdate(new BlockPos(x, 99, z), Blocks.STONE_BRICKS.defaultBlockState());
                        for (int y = 100; y < 110; y++) level.removeBlock(new BlockPos(x, y, z), false);
                    }
                    // 铁傀儡血多，挨得住整套；不动、不挡镜头太多
                    for (int i = 0; i < 2; i++) {
                        var g = EntityType.IRON_GOLEM.create(level, EntitySpawnReason.COMMAND);
                        g.setNoAi(true);
                        g.setPos(i == 0 ? new Vec3(.5, 100, 3.2) : new Vec3(-2.5, 100, 9));
                        level.addFreshEntity(g);
                        if (i == 0) near = g.getId(); else far = g.getId();
                    }
                    ctx.player().getInventory().clearContent(); ctx.player().getInventory().setSelectedSlot(0);
                    ctx.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(CrimsonFlameLongswordItem.ITEM.get()));
                    reset(ctx.player());
                }).setGameMode(GameType.SURVIVAL)
                .waitUntil("拔剑", ctx -> phase("draw")).ticks(8).screenshot("draw_third_person")
                .waitUntil("待机", ctx -> phase("idle")).ticks(6).screenshot("idle_third_person");

        // 一式：一剑是龙炎，不缠身
        click(s, "一式");
        shot(s, "cleave_1_third_person", "cleave_1", .38)
                .waitUntil("回到待机", ctx -> phase("idle")).ticks(4)
                .checkServer("一式打出的是龙炎", ctx -> dragonfire(target(ctx.server(), near)))
                .checkServer("一式不点缠身", ctx -> !Dragonfire.burning(target(ctx.server(), near)))
                .checkServer("龙炎不点原版的火", ctx -> !target(ctx.server(), near).isOnFire());

        // 格挡，格挡中左键打出红莲劫焰斩：五道游脉画成龙炎，远处的靶子被游脉打中、缠身
        s.server("回到起点", ctx -> reset(ctx.player())).ticks(4)
                .step("按住右键", ctx -> use(true))
                .waitUntil("格挡同步", ctx -> phase("guard")).ticks(6).screenshot("guard_third_person")
                .step("记下游脉提示", ctx -> veins = FoxfireClient.received(FoxfireCue.VEIN));
        click(s, "格挡中左键");
        shot(s, "flame_sweep_third_person", "flame_sweep", .45)
                .step("松开右键", ctx -> use(false))
                .ticks(10).screenshot("flame_sweep_veins")
                .check("游脉画成龙炎", ctx -> FoxfireClient.received(FoxfireCue.VEIN) > veins)
                .waitUntil("横扫后回到待机", ctx -> phase("idle", "guard")).ticks(4)
                .checkServer("远处的靶子挨了游脉的龙炎", ctx -> dragonfire(target(ctx.server(), far)))
                .checkServer("游脉点了龙炎缠身", ctx -> Dragonfire.burning(target(ctx.server(), far)))
                .waitUntil("待机", ctx -> phase("idle"));

        // 潜行 + 左键：突刺，身后冒龙炎火星
        s.server("回到起点", ctx -> reset(ctx.player())).ticks(4)
                .step("按住潜行", ctx -> { Minecraft.getInstance().options.keyShift.setDown(true); embers = CrimsonFlameClient.embers(); }).ticks(3);
        click(s, "潜行左键");
        shot(s, "crimson_thrust_third_person", "crimson_thrust", .28)
                .step("松开潜行", ctx -> Minecraft.getInstance().options.keyShift.setDown(false))
                .check("突刺时冒龙炎火星", ctx -> CrimsonFlameClient.embers() > embers)
                .waitUntil("突刺后回到待机", ctx -> phase("idle")).ticks(4)
                .checkServer("突刺打出的是龙炎", ctx -> dragonfire(target(ctx.server(), near)))
                .checkServer("突刺点了龙炎缠身", ctx -> Dragonfire.burning(target(ctx.server(), near)));

        // 疾跑 + 左键：旋风斩，绕身一圈龙炎。往西跑，不撞靶子
        s.server("回到起点（面朝西）", ctx -> { reset(ctx.player()); DimensionTravel.teleport(ctx.player(), ctx.player().level(), new Vec3(6.5, 100, 14.5), 90, 0); })
                .ticks(4)
                .step("疾跑", ctx -> {
                    var o = Minecraft.getInstance().options; o.keyUp.setDown(true); o.keySprint.setDown(true);
                    embers = CrimsonFlameClient.embers();
                })
                .waitUntil("跑起来", ctx -> Minecraft.getInstance().player.isSprinting()).ticks(3);
        click(s, "疾跑左键");
        shot(s, "vortex_rush_third_person", "vortex_rush", .3)
                .step("停下", ctx -> { var o = Minecraft.getInstance().options; o.keyUp.setDown(false); o.keySprint.setDown(false); })
                .check("旋风斩绕身冒龙炎", ctx -> CrimsonFlameClient.embers() > embers)
                .waitUntil("旋风后回到待机", ctx -> phase("idle")).ticks(4);

        // 跳起 + 左键：坠星，落点炸开龙炎，自己不摔伤。坠星往前冲约 3 格，从起点后面 3 格起跳，免得落进靶子里、镜头穿模
        s.server("退到起点后面", ctx -> { reset(ctx.player()); DimensionTravel.teleport(ctx.player(), ctx.player().level(), ORIGIN.add(0, 0, -3), 0, 0); }).ticks(4)
                .step("记下血量，起跳", ctx -> {
                    var mc = Minecraft.getInstance(); health = mc.player.getHealth(); mc.options.keyJump.setDown(true);
                    embers = CrimsonFlameClient.embers();
                })
                .waitUntil("离地", ctx -> !Minecraft.getInstance().player.onGround())
                .step("松开跳跃", ctx -> Minecraft.getInstance().options.keyJump.setDown(false));
        click(s, "空中左键");
        shot(s, "skyfall_rise_third_person", "skyfall_judgement", .2);
        shot(s, "skyfall_impact_third_person", "skyfall_judgement", .56)
                .check("坠星时冒龙炎火星", ctx -> CrimsonFlameClient.embers() > embers)
                .waitUntil("坠星后回到待机", ctx -> phase("idle")).ticks(6)
                .check("坠星不摔伤自己", ctx -> Minecraft.getInstance().player.getHealth() >= health)
                .checkServer("坠星落点打出的是龙炎", ctx -> dragonfire(target(ctx.server(), near)));

        // 第一人称：一式、格挡、横扫
        s.server("回到起点", ctx -> reset(ctx.player())).step("第一人称", ctx -> camera(CameraType.FIRST_PERSON)).ticks(6)
                .screenshot("idle_first_person");
        click(s, "第一人称一式");
        shot(s, "cleave_1_first_person", "cleave_1", .3)
                .waitUntil("第一人称回到待机", ctx -> phase("idle")).ticks(4)
                .step("第一人称按住右键", ctx -> use(true))
                .waitUntil("第一人称格挡", ctx -> phase("guard")).ticks(6).screenshot("guard_first_person");
        click(s, "第一人称格挡中左键");
        shot(s, "flame_sweep_first_person", "flame_sweep", .4)
                .step("第一人称松开右键", ctx -> use(false))
                .waitUntil("第一人称回到待机", ctx -> phase("idle"));

        s.server("带走靶子", ctx -> {
                    for (int id : new int[]{near, far}) { var e = ctx.server().overworld().getEntity(id); if (e != null) e.discard(); }
                })
                .teardown("恢复按键和界面", ctx -> {
                    var mc = Minecraft.getInstance(); var o = mc.options;
                    o.keyAttack.setDown(false); o.keyUse.setDown(false); o.keyShift.setDown(false);
                    o.keyUp.setDown(false); o.keySprint.setDown(false); o.keyJump.setDown(false);
                    mc.options.hideGui = previousHideGui; camera(CameraType.FIRST_PERSON);
                });
    }
}
