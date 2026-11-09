package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.client.longsword.LongswordClient;
import dev.logos.dimension.client.trail.WeaponTrails;
import dev.logos.dimension.item.LingxiaoSwordItem;
import dev.logos.dimension.item.LogosNatureBridge;
import dev.logos.dimension.lingxiao.FlyingSword;
import dev.logos.lang.Element;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * 凌霄的剑气（刀光，docs/WEAPON_TRAILS.md）实际试玩：四式连招在第三人称背后、正面、右侧与第一人称各截一张，
 * 第三式另拍头顶的云剑；检查挥剑时出光、默认是凌霄自己的剑气、缠上冰霜魔力时换成冰霜刀光、回到待机后散尽。
 * 然后御剑：悬停不拖剑气，往前飞、侧飞时脚下的剑拖出一道淡淡的剑气，停下后散尽。最后切槽归鞘，拍挽剑花时的剑气。
 */
@LDLRegisterClient(name="weapon_trails_lingxiao", group="weapon_trails_lingxiao", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
public class LingxiaoTrailView implements UIScenario {
    private boolean previousHideGui;
    private int peakRibbons, peakSparks, camera = -1;

    private static boolean phase(String... moves) {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        var p = LongswordClient.pose(mc.player.getUUID());
        if (p == null || p.variant() != LingxiaoSwordItem.WEAPON.id()) return false;
        for (String m : moves) if (m.equals(p.move())) return true;
        return false;
    }
    private static void camera(CameraType type) { Minecraft.getInstance().options.setCameraType(type); }
    private static void attack(boolean down) { Minecraft.getInstance().options.keyAttack.setDown(down); }
    private void watch() {
        peakRibbons = Math.max(peakRibbons, WeaponTrails.ribbons());
        peakSparks = Math.max(peakSparks, WeaponTrails.sparks());
    }
    private static String move() {
        var p = LongswordClient.pose(Minecraft.getInstance().player.getUUID());
        return p == null ? "" : p.move();
    }
    /** 本机玩家当前招式播到第几秒。 */
    private static double seconds() {
        var mc = Minecraft.getInstance();
        var p = LongswordClient.pose(mc.player.getUUID());
        if (p == null) return -1;
        return (mc.level.getGameTime() + mc.getDeltaTracker().getGameTimeDeltaPartialTick(false) - p.started()) / 20 * p.speed();
    }
    private static FlyingSword sword() { return Minecraft.getInstance().player.getVehicle() instanceof FlyingSword f ? f : null; }
    /** 脚下飞剑那道剑气的样式名；没有时为空。 */
    private static String flightStyle() {
        var f = sword();
        var style = f == null ? null : WeaponTrails.styleOf("lingxiao_flight:" + f.getId());
        return style == null ? "" : style.id().getPath();
    }
    private static boolean frozen() { return !Minecraft.getInstance().level.tickRateManager().runsNormally(); }
    /** 本机玩家这道第三人称刀光现在用的样式名（路径部分）；没有时为空。 */
    private static String style() {
        var s = LongswordClient.trailStyle(Minecraft.getInstance().player.getId(), false);
        return s == null ? "" : s.id().getPath();
    }

    /**
     * 招式播到 {@code at} 秒时定格（/tick freeze）再截图：截图本身要花掉零点几秒，不定格的话拍到的总是剑气散了一半之后。
     * 定格时游戏时间停住，剑气不散、动作不走，截完再恢复。
     */
    private ScenarioBuilder shot(ScenarioBuilder s, String name, String move, double at) {
        return s.waitUntil(name + " 开始", ctx -> move.equals(move()))
                .waitUntil(name + " 到 " + at + " 秒", ctx -> move.equals(move()) && seconds() >= at)
                .runCommand("tick freeze").waitUntil(name + " 定格", ctx -> frozen()).ticks(2)
                .step(name + " 记录", ctx -> watch()).screenshot(name)
                .runCommand("tick unfreeze").waitUntil(name + " 恢复", ctx -> !frozen());
    }

    /**
     * 按住左键打完四式：每式挥完、剑气还新的时候定格拍一张。第三式先拍头顶的云剑，平斩紧跟在云剑后面，
     * 同一遍里来不及再定格一次，等连招转回第三式时再拍。
     */
    private ScenarioBuilder combo(ScenarioBuilder s, String view) {
        s.step(view + " 按住左键", ctx -> attack(true));
        shot(s, view + "_attack_1", "attack_1", .08);
        shot(s, view + "_attack_2", "attack_2", .05);
        shot(s, view + "_attack_3_cloud", "attack_3", .1);
        shot(s, view + "_attack_4", "attack_4", .1);
        shot(s, view + "_attack_3", "attack_3", .3);
        return s.step(view + " 松开左键", ctx -> attack(false))
                .waitUntil(view + " 回到待机", ctx -> phase("idle")).ticks(12);
    }

    @Override public void configure(ScenarioOptions o) { o.tags("weapon_trails").defaultTimeoutMs(60000).scenarioTimeoutMs(300000); }

    @Override public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set day").runCommand("weather clear").runCommand("difficulty easy")
                .runCommand("gamerule spawn_mobs false").runCommand("gamerule spawn_monsters false")
                .step("设置视口", ctx -> {
                    var mc = Minecraft.getInstance(); previousHideGui = mc.options.hideGui;
                    mc.options.hideGui = false; mc.player.getInventory().setSelectedSlot(0);
                    camera(CameraType.THIRD_PERSON_BACK); mc.options.fov().set(70);
                    var w = mc.getWindow(); w.setWindowed(1280, 720); w.setWidth(1280); w.setHeight(720);
                    mc.getMainRenderTarget().resize(1280, 720); mc.gameRenderer.resize(1280, 720); mc.resizeGui();
                }).server("设置练剑场与木桩", ctx -> {
                    var level = ctx.server().overworld();
                    for (int x = -10; x <= 16; x++) for (int z = -7; z <= 30; z++) {
                        level.setBlockAndUpdate(new BlockPos(x, 99, z), Blocks.STONE_BRICKS.defaultBlockState());
                        for (int y = 100; y < 112; y++) level.removeBlock(new BlockPos(x, y, z), false);
                    }
                    DimensionTravel.teleport(ctx.player(), level, new Vec3(.5, 100, .5), 0, 0);
                    // 隐身的铁傀儡当靶子：挨剑但不挡镜头
                    var golem = EntityType.IRON_GOLEM.create(level, EntitySpawnReason.COMMAND);
                    golem.setNoAi(true); golem.setNoGravity(true); golem.setInvulnerable(true);
                    golem.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.INVISIBILITY, 12000, 0, false, false));
                    golem.setPos(.5, 100, 2.8); level.addFreshEntity(golem);
                    ctx.player().getInventory().clearContent(); ctx.player().getInventory().setSelectedSlot(0);
                    ctx.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(LingxiaoSwordItem.ITEM.get()));
                }).setGameMode(GameType.SURVIVAL)
                .waitUntil("持剑待机", ctx -> phase("idle")).ticks(4)
                .check("待机时不出剑气", ctx -> WeaponTrails.ribbons() == 0);
        combo(s, "back")
                .check("挥剑时出剑气", ctx -> peakRibbons > 0)
                .check("剑尖洒出光点", ctx -> peakSparks > 0)
                .check("默认是凌霄的剑气", ctx -> "lingxiao".equals(style()))
                .check("回到待机后剑气散尽", ctx -> WeaponTrails.ribbons() == 0)
                .step("第三人称正面", ctx -> camera(CameraType.THIRD_PERSON_FRONT)).ticks(3);
        combo(s, "front")
                .check("正面回到待机后散尽", ctx -> WeaponTrails.ribbons() == 0);
        // 侧面：右手一侧放一个隐身盔甲架当镜头（玩家面朝南，右手在 -X）；藏起界面，第一人称的手不画
        s.server("右侧机位", ctx -> {
                    var level = ctx.server().overworld();
                    ArmorStand stand = EntityType.ARMOR_STAND.create(level, EntitySpawnReason.COMMAND);
                    stand.setInvisible(true); stand.setNoGravity(true);
                    Vec3 at = new Vec3(-3.4, 101.5, 1.7), look = new Vec3(.5, 101.2, 1.4);
                    double dx = look.x - at.x, dy = look.y - at.y, dz = look.z - at.z;
                    stand.snapTo(at.x, at.y - stand.getEyeHeight(), at.z, (float) -Math.toDegrees(Math.atan2(dx, dz)),
                            (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz))));
                    level.addFreshEntity(stand);
                    camera = stand.getId();
                }).waitUntil("机位同步", ctx -> Minecraft.getInstance().level.getEntity(camera) != null)
                .step("切到侧面机位", ctx -> {
                    var mc = Minecraft.getInstance();
                    Entity stand = mc.level.getEntity(camera);
                    stand.setYHeadRot(stand.getYRot());
                    camera(CameraType.FIRST_PERSON); mc.options.hideGui = true; mc.setCameraEntity(stand);
                }).ticks(3);
        combo(s, "side")
                .step("回到玩家", ctx -> {
                    var mc = Minecraft.getInstance(); mc.setCameraEntity(mc.player); mc.options.hideGui = false;
                    camera(CameraType.THIRD_PERSON_BACK);
                })
                // 缠上魔力：剑气换成那种元素的（凌霄的剑气不是武器自带属性，不固定）
                .server("缠上冰霜", ctx -> dev.logos.runtime.ManaWrap.wrap(ctx.player(), Element.FROST, 4, 600)).ticks(3)
                .step("按住左键", ctx -> attack(true));
        shot(s, "frost_attack_1", "attack_1", .08);
        shot(s, "frost_attack_2", "attack_2", .05)
                .check("缠绕时换成冰霜刀光", ctx -> "frost".equals(style()))
                .step("松开左键", ctx -> attack(false))
                .waitUntil("回到待机", ctx -> phase("idle")).ticks(12)
                .server("解除缠绕", ctx -> dev.logos.runtime.ManaWrap.wrap(ctx.player(), Element.PHYSICAL, 0, 0))
                .step("第一人称", ctx -> camera(CameraType.FIRST_PERSON)).ticks(3);
        combo(s, "fp")
                .check("第一人称回到待机后散尽", ctx -> WeaponTrails.ribbons() == 0)
                // 御剑：悬停时不拖剑气，往前飞、侧飞时剑格处拖出一道淡淡的剑气
                .server("补充魔力", ctx -> LogosNatureBridge.mana(ctx.player(), 200))
                .step("第三人称背后并右键起飞", ctx -> {
                    var mc = Minecraft.getInstance(); camera(CameraType.THIRD_PERSON_BACK);
                    mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                })
                .waitUntil("御剑巡航", ctx -> sword() != null && phase("flight_idle")).ticks(10)
                .check("悬停时不拖剑气", ctx -> WeaponTrails.ribbons() == 0)
                .step("藏起界面，按前进键", ctx -> {
                    var mc = Minecraft.getInstance(); mc.options.hideGui = true; mc.options.keyUp.setDown(true);
                }).ticks(12)
                .check("往前飞时拖出剑气", ctx -> WeaponTrails.ribbons() > 0 && "lingxiao_flight".equals(flightStyle()))
                // 御剑时不定格：/tick freeze 停不住玩家，人会飞离停在原地的剑气
                .screenshot("flight_forward")
                .step("正面看", ctx -> camera(CameraType.THIRD_PERSON_FRONT)).ticks(4).screenshot("flight_front")
                .step("背后视角，松开前进键，按左移键侧飞", ctx -> {
                    var o = Minecraft.getInstance().options; camera(CameraType.THIRD_PERSON_BACK);
                    o.keyUp.setDown(false); o.keyLeft.setDown(true);
                }).ticks(12).screenshot("flight_strafe")
                .step("松开左移键", ctx -> Minecraft.getInstance().options.keyLeft.setDown(false))
                .ticks(30).check("停下后剑气散尽", ctx -> WeaponTrails.ribbons() == 0)
                .step("按潜行下剑", ctx -> Minecraft.getInstance().options.keyShift.setDown(true))
                .waitUntil("已下剑", ctx -> !Minecraft.getInstance().player.isPassenger())
                .step("松开潜行键", ctx -> Minecraft.getInstance().options.keyShift.setDown(false))
                .waitUntil("落地收回剑", ctx -> phase("idle"))
                .step("正面看归鞘", ctx -> camera(CameraType.THIRD_PERSON_FRONT))
                .step("切换空槽收剑", ctx -> {
                    var mc = Minecraft.getInstance(); mc.player.getInventory().setSelectedSlot(1);
                    mc.player.connection.send(new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(1));
                });
        shot(s, "sheathe_flower", "sheathe", .22)
                .check("归鞘挽剑花时出剑气", ctx -> WeaponTrails.ribbons() > 0)
                .teardown("恢复界面", ctx -> {
                    attack(false);
                    // 中途失败时世界可能还定格着
                    var server = Minecraft.getInstance().getSingleplayerServer();
                    if (server != null) server.execute(() -> server.tickRateManager().setFrozen(false));
                    var o = Minecraft.getInstance().options;
                    o.keyUp.setDown(false); o.keyLeft.setDown(false); o.keyShift.setDown(false);
                    var mc = Minecraft.getInstance(); mc.setCameraEntity(mc.player); mc.options.hideGui = previousHideGui;
                    camera(CameraType.FIRST_PERSON);
                });
    }
}
