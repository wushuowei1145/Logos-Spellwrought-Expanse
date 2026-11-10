package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.client.longsword.LongswordClient;
import dev.logos.dimension.client.longsword.LongswordRig;
import dev.logos.dimension.client.trail.WeaponTrails;
import dev.logos.dimension.combat.Moveset;
import dev.logos.dimension.item.LongswordItem;
import dev.logos.lang.Element;
import java.util.Arrays;
import java.util.Locale;
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
 * 长剑的刀光（docs/WEAPON_TRAILS.md，设置在 combat/trails/longsword.json）：曦誓是金色的晨光，黑钢长剑是暗钢里透一缕铭文的紫光。
 * 三段连招在第三人称背后、正面、侧面与第一人称截图，每一刀都在扫完、整道刀光还在的那一刻截；
 * 曦誓再从侧面看一次蓄力下劈（竖着劈下的刀光从背后、正面看都是一条线），黑钢长剑竖剑格挡一次（不出光）；
 * 缠上一种元素的魔力砍一刀，检查刀光换成那种元素的。拔剑、待机、蓄势、举剑都不出光。
 */
@LDLRegisterClient(name="weapon_trails_dawnvow", group="weapon_trails_longsword", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
public class LongswordTrailView implements UIScenario {
    /** 连招每一刀截图的时刻（招式里的秒）：出光时段到 0.36–0.37 秒为止，这时整道刀光刚扫完。 */
    private static final double SHOT = .37;
    private boolean previousHideGui;
    private int peakRibbons, peakSparks, quietRibbons, camera = -1;

    protected int variant() { return 0; }
    /** 这把剑自己的刀光样式（路径部分）。 */
    protected String style() { return "dawn"; }
    /** 缠绕检查用的元素。 */
    protected Element wrap() { return Element.LIGHTNING; }

    private boolean phase(String... moves) {
        var p = LongswordClient.pose(Minecraft.getInstance().player.getUUID());
        return p != null && p.variant() == variant() && Arrays.asList(moves).contains(p.move());
    }
    /** 当前招式是 {@code move} 且已经播到第 {@code seconds} 秒。 */
    private boolean at(String move, double seconds) {
        var mc = Minecraft.getInstance();
        var p = LongswordClient.pose(mc.player.getUUID());
        var rig = p == null ? null : LongswordRig.get(p.variant(), false);
        if (rig == null || p.variant() != variant() || !move.equals(p.move())) return false;
        double now = mc.level.getGameTime() + mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        return Moveset.playbackSeconds(p.playback(), rig.length(p.clip()), now - p.started(), p.speed(), p.offset()) >= seconds;
    }
    private static void view(CameraType type, float pitch) {
        var mc = Minecraft.getInstance();
        mc.options.setCameraType(type);
        mc.player.setXRot(pitch); mc.player.xRotO = pitch;
    }
    private static void attack(boolean down) { Minecraft.getInstance().options.keyAttack.setDown(down); }
    private void watch() {
        peakRibbons = Math.max(peakRibbons, WeaponTrails.ribbons());
        peakSparks = Math.max(peakSparks, WeaponTrails.sparks());
    }
    /** 不该出光的时候记下最多有几道刀光。 */
    private boolean quiet(boolean done) {
        quietRibbons = Math.max(quietRibbons, WeaponTrails.ribbons());
        return done;
    }
    /** 本机玩家这道刀光现在用的样式名（路径部分）；没有时为空。 */
    private static String trail(boolean firstPerson) {
        var s = LongswordClient.trailStyle(Minecraft.getInstance().player.getId(), firstPerson);
        return s == null ? "" : s.id().getPath();
    }
    /**
     * 三段连招，一刀一刀地出：按住左键等到这一刀开始就松开，让它播完整段，扫完时截图；回到待机后再按，
     * 连招记忆接着出下一刀。
     */
    private void combo(ScenarioBuilder s, String prefix) {
        for (int n = 1; n <= 3; n++) {
            String move = "attack_" + n;
            s.step("按住左键", ctx -> attack(true))
                    .waitUntil(move, ctx -> phase(move)).step("松开左键", ctx -> attack(false))
                    .waitUntil(move + " 扫完", ctx -> at(move, SHOT)).step("记录", ctx -> watch()).screenshot(prefix + "_" + move)
                    .waitUntil("回到待机", ctx -> phase("idle"));
        }
        s.ticks(12);
    }
    /** 侧面机位：玩家右手边 4 格外、略高处看向胸口。 */
    private void side(ScenarioBuilder s) {
        s.server("侧面机位", ctx -> {
            var level = ctx.server().overworld();
            ArmorStand stand = EntityType.ARMOR_STAND.create(level, EntitySpawnReason.COMMAND);
            stand.setInvisible(true); stand.setNoGravity(true); stand.setInvulnerable(true);
            Vec3 at = new Vec3(-2.7, 101.8, 1.3), look = new Vec3(.5, 101.3, 0.9);
            double dx = look.x - at.x, dy = look.y - at.y, dz = look.z - at.z;
            stand.snapTo(at.x, at.y - stand.getEyeHeight(), at.z, (float) -Math.toDegrees(Math.atan2(dx, dz)),
                    (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz))));
            level.addFreshEntity(stand);
            camera = stand.getId();
        }).waitUntil("机位同步", ctx -> Minecraft.getInstance().level.getEntity(camera) != null)
                .step("切到侧面", ctx -> {
                    var mc = Minecraft.getInstance();
                    Entity stand = mc.level.getEntity(camera);
                    stand.setYHeadRot(stand.getYRot());
                    view(CameraType.FIRST_PERSON, 0);
                    mc.options.hideGui = true;   // 不画第一人称的手
                    mc.setCameraEntity(stand);
                }).ticks(3);
    }
    private void own(ScenarioBuilder s) {
        s.step("回到玩家", ctx -> {
            var mc = Minecraft.getInstance();
            mc.setCameraEntity(mc.player); mc.options.hideGui = false;
        }).server("移走机位", ctx -> {
            var e = ctx.server().overworld().getEntity(camera);
            if (e != null) e.discard();
        });
    }

    @Override public void configure(ScenarioOptions o) { o.tags("weapon_trails").defaultTimeoutMs(60000).scenarioTimeoutMs(600000); }

    @Override public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set day").runCommand("weather clear").runCommand("difficulty peaceful")
                .step("设置视口", ctx -> {
                    var mc = Minecraft.getInstance(); previousHideGui = mc.options.hideGui;
                    mc.options.hideGui = false; mc.player.getInventory().setSelectedSlot(0);
                    mc.options.setCameraType(CameraType.THIRD_PERSON_BACK); mc.options.fov().set(70);
                    var w = mc.getWindow(); w.setWindowed(1280, 720); w.setWidth(1280); w.setHeight(720);
                    mc.getMainRenderTarget().resize(1280, 720); mc.gameRenderer.resize(1280, 720); mc.resizeGui();
                }).server("设置练剑场与木桩", ctx -> {
                    var level = ctx.server().overworld();
                    for (int x = -7; x <= 7; x++) for (int z = -7; z <= 8; z++) {
                        level.setBlockAndUpdate(new BlockPos(x, 99, z), Blocks.STONE_BRICKS.defaultBlockState());
                        for (int y = 100; y < 108; y++) level.removeBlock(new BlockPos(x, y, z), false);
                    }
                    DimensionTravel.teleport(ctx.player(), level, new Vec3(.5, 100, .5), 0, 0);
                    var golem = EntityType.IRON_GOLEM.create(level, EntitySpawnReason.COMMAND);
                    golem.setNoAi(true); golem.setNoGravity(true); golem.setInvulnerable(true);
                    golem.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.INVISIBILITY, 7200, 0, false, false));
                    golem.setPos(.5, 100, 2.8); level.addFreshEntity(golem);
                    ctx.player().getInventory().clearContent(); ctx.player().getInventory().setSelectedSlot(0);
                    dev.logos.runtime.ManaWrap.wrap(ctx.player(), Element.PHYSICAL, 0, 0);
                    ctx.player().setItemInHand(InteractionHand.MAIN_HAND,
                            new ItemStack(variant() == 0 ? LongswordItem.DAWNVOW.get() : LongswordItem.BLACKSTEEL.get()));
                }).setGameMode(GameType.SURVIVAL)
                // 放慢游戏刻：截图要隔一两帧才拍下，原速时刀光已经散掉一截；刀光、动作都按游戏时间走，放慢不改样子
                .runCommand("tick rate 6")
                .step("清零", ctx -> quietRibbons = 0)
                .waitUntil("拔剑到待机", ctx -> quiet(phase("idle"))).ticks(6)
                .check("拔剑与待机不出刀光", ctx -> quietRibbons == 0 && WeaponTrails.ribbons() == 0)
                // 第一刀的蓄势（0.13 秒前）不出光，扫过时才出
                .step("按住左键", ctx -> attack(true))
                .waitUntil("第一刀蓄势", ctx -> quiet(at("attack_1", .1)))
                .check("蓄势不出光", ctx -> quietRibbons == 0)
                .step("松开左键", ctx -> attack(false))
                .waitUntil("回到待机", ctx -> phase("idle")).ticks(12)
                .step("背后、略俯视", ctx -> view(CameraType.THIRD_PERSON_BACK, 15));
        combo(s, "back");
        s.check("挥砍时出刀光", ctx -> peakRibbons > 0)
                .check("刀尖甩出粒子", ctx -> peakSparks > 0)
                .check("刀光是这把剑自己的样式", ctx -> style().equals(trail(false)))
                .check("回到待机后刀光散尽", ctx -> WeaponTrails.ribbons() == 0)
                .step("正面、略仰视", ctx -> view(CameraType.THIRD_PERSON_FRONT, -10));
        combo(s, "front");
        // 缠上魔力：刀光换成那种元素的
        String element = wrap().name().toLowerCase(Locale.ROOT);
        s.server("缠上" + wrap().zh, ctx -> dev.logos.runtime.ManaWrap.wrap(ctx.player(), wrap(), 4, 600))
                .step("背后、略俯视", ctx -> view(CameraType.THIRD_PERSON_BACK, 15)).ticks(3)
                .step("按住左键", ctx -> attack(true))
                .waitUntil("一刀", ctx -> phase("attack_1", "attack_2", "attack_3")).step("松开左键", ctx -> attack(false))
                .waitUntil("扫完", ctx -> at("attack_1", SHOT) || at("attack_2", SHOT) || at("attack_3", SHOT)).screenshot(element + "_attack")
                .check("缠绕时换成" + wrap().zh + "刀光", ctx -> element.equals(trail(false)))
                .waitUntil("回到待机", ctx -> phase("idle")).ticks(12)
                .server("解除缠绕", ctx -> dev.logos.runtime.ManaWrap.wrap(ctx.player(), Element.PHYSICAL, 0, 0)).ticks(3);
        side(s);
        combo(s, "side");
        // 右键：曦誓蓄力下劈，黑钢长剑竖剑格挡；举剑、蓄着、放下剑都不出光
        s.step("按住右键", ctx -> {
                    var mc = Minecraft.getInstance(); quietRibbons = 0; mc.options.keyUse.setDown(true);
                    mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                })
                .waitUntil("举剑", ctx -> quiet(phase("charge_start", "guard_raise")))
                .waitUntil("蓄着", ctx -> quiet(phase("charge_hold", "guard"))).ticks(6)
                .step("记录", ctx -> quiet(true)).screenshot("side_hold")
                .check("举剑与蓄着不出光", ctx -> quietRibbons == 0)
                .step("松开右键", ctx -> {
                    var mc = Minecraft.getInstance(); peakRibbons = 0; mc.options.keyUse.setDown(false); mc.gameMode.releaseUsingItem(mc.player);
                });
        if (variant() == 0) s.waitUntil("下劈", ctx -> at("charge_release", .5)).step("记录", ctx -> watch()).screenshot("side_release")
                .waitUntil("劈到底", ctx -> at("charge_release", .6)).step("记录", ctx -> watch()).screenshot("side_release_late")
                .check("下劈出刀光", ctx -> peakRibbons > 0)
                .waitUntil("回到待机", ctx -> phase("idle")).ticks(12)
                .check("下劈之后刀光散尽", ctx -> WeaponTrails.ribbons() == 0);
        else s.waitUntil("放下剑", ctx -> quiet(phase("guard_lower")))
                .waitUntil("回到待机", ctx -> quiet(phase("idle"))).ticks(4)
                .check("放下剑不出光", ctx -> quietRibbons == 0);
        own(s);
        s.step("第一人称", ctx -> view(CameraType.FIRST_PERSON, 10)).ticks(3);
        combo(s, "first_person");
        s.check("第一人称也是这把剑自己的样式", ctx -> style().equals(trail(true)))
                .check("第一人称回到待机后刀光散尽", ctx -> WeaponTrails.ribbons() == 0)
                .runCommand("tick rate 20")
                .teardown("恢复界面", ctx -> {
                    attack(false);
                    var mc = Minecraft.getInstance(); mc.options.keyUse.setDown(false); mc.setCameraEntity(mc.player);
                    mc.options.hideGui = previousHideGui; mc.options.setCameraType(CameraType.FIRST_PERSON);
                    var server = mc.getSingleplayerServer();
                    if (server != null) server.execute(() -> server.tickRateManager().setTickRate(20));
                });
    }

    @LDLRegisterClient(name="weapon_trails_blacksteel", group="weapon_trails_longsword", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
    public static class Blacksteel extends LongswordTrailView {
        @Override protected int variant() { return 1; }
        @Override protected String style() { return "blacksteel"; }
        @Override protected Element wrap() { return Element.FIRE; }
    }
}
