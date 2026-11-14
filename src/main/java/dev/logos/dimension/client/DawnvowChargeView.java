package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.client.longsword.LongswordClient;
import dev.logos.dimension.client.vfx.DawnvowClient;
import dev.logos.dimension.client.vfx.DawnvowScreen;
import dev.logos.dimension.client.vfx.DawnvowShake;
import dev.logos.dimension.dawnvow.DawnvowBurst;
import dev.logos.dimension.dawnvow.DawnvowCharge;
import dev.logos.dimension.dawnvow.DawnvowPayload;
import dev.logos.dimension.dawnvow.SwordWave;
import dev.logos.dimension.item.LongswordItem;
import dev.logos.runtime.PlayerMagic;
import java.util.Arrays;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * 曦誓蓄力彩蛋的实际客户端：黄昏的石台上按住右键 15 秒，依次拍普通蓄力、7 秒起的大型蓄力（四周升起的光点、身上的光，
 * 后期把周围调暗、身边照亮）、12 秒的剑身金光、蓄满的金色流火（侧面、正面与第一人称），松手后拍巨型剑气、扎进地面和光柱；
 * 服务端检查剑气对三种血量的生物；再蓄一下普通蓄力斩，拍贴地剑气。最后只在本机重放一道巨型剑气，
 * 停住曦誓特效的时钟，按 {@link DawnvowBurst} 的时间轴一刻一刻拍光柱冲起之前的那串画面。
 */
@LDLRegisterClient(name = "dawnvow_charge", group = "dawnvow_charge", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
public class DawnvowChargeView implements UIScenario {
    private int camera = -1;
    private final int[] targets = new int[3];
    private long started;
    private boolean previousHideGui;
    private double arrive;
    private long passes;

    /** 重放时停在时间轴的哪一刻（从剑气到头算起）拍哪一张。 */
    private record Phase(String name, float age) {}

    private static float beat(int index) {
        return DawnvowBurst.CONTRAST + (index + .5f) * DawnvowBurst.BEAT;
    }

    private static final Phase[] PHASES = {
            new Phase("burst_lens", 2), new Phase("burst_swell", 8), new Phase("burst_dome", 13),
            new Phase("burst_black_out", 14.6f), new Phase("burst_black", 16), new Phase("burst_contract", 18.6f),
            new Phase("burst_contract_late", 19.6f),
            new Phase("burst_invert", beat(0)), new Phase("burst_stars_white", beat(2)), new Phase("burst_stars_white_2", beat(3)),
            new Phase("burst_stars_black", beat(4)), new Phase("burst_expand", 27.5f), new Phase("burst_white", 31.5f)};

    @Override public void configure(ScenarioOptions o) {
        o.tags("dawnvow_charge").defaultTimeoutMs(120000).scenarioTimeoutMs(600000);
    }

    private static boolean phase(String... moves) {
        var mc = Minecraft.getInstance();
        var p = LongswordClient.pose(mc.player.getUUID());
        return p != null && Arrays.asList(moves).contains(p.move());
    }

    private static double held() {
        return Minecraft.getInstance().level.getGameTime();
    }

    private ScenarioBuilder at(ScenarioBuilder s, String name, int tick) {
        return s.waitUntil("蓄力 " + tick + " 刻", ctx -> held() - started >= tick);
    }

    private static <T extends Mob> T target(ServerLevel level, EntityType<T> type, double x) {
        T e = type.create(level, EntitySpawnReason.COMMAND);
        e.setNoAi(true);
        e.setPos(x, 100, .5);
        e.setYRot(90);
        level.addFreshEntity(e);
        return e;
    }

    private void side(ScenarioBuilder s, String name, Vec3 at, Vec3 look) {
        s.server("机位 " + name, ctx -> {
            var level = ctx.server().overworld();
            if (camera >= 0 && level.getEntity(camera) != null) level.getEntity(camera).discard();
            ArmorStand stand = EntityType.ARMOR_STAND.create(level, EntitySpawnReason.COMMAND);
            stand.setInvisible(true);
            stand.setNoGravity(true);
            double dx = look.x - at.x, dy = look.y - at.y, dz = look.z - at.z;
            stand.snapTo(at.x, at.y - stand.getEyeHeight(), at.z, (float) -Math.toDegrees(Math.atan2(dx, dz)),
                    (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz))));
            level.addFreshEntity(stand);
            camera = stand.getId();
        }).waitUntil("机位同步 " + name, ctx -> Minecraft.getInstance().level.getEntity(camera) != null)
                .step("切到机位 " + name, ctx -> {
                    Entity stand = Minecraft.getInstance().level.getEntity(camera);
                    stand.setYHeadRot(stand.getYRot());
                    Minecraft.getInstance().setCameraEntity(stand);
                });
    }

    private void own(ScenarioBuilder s, String name, CameraType type) {
        s.step("回到玩家 " + name, ctx -> {
            var mc = Minecraft.getInstance();
            mc.setCameraEntity(mc.player);
            mc.options.setCameraType(type);
        });
    }

    @Override public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set 13000").runCommand("weather clear").runCommand("difficulty normal")
                .step("设置视口", ctx -> {
                    var mc = Minecraft.getInstance();
                    previousHideGui = mc.options.hideGui;
                    mc.options.hideGui = true;
                    mc.options.renderDistance().set(10);
                    mc.options.fov().set(70);
                    mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                    mc.player.getInventory().setSelectedSlot(0);
                })
                .server("搭石台与靶子", ctx -> {
                    var level = ctx.server().overworld();
                    level.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.SPAWN_MONSTERS, false, ctx.server());
                    level.getGameRules().set(net.minecraft.world.level.gamerules.GameRules.SPAWN_MOBS, false, ctx.server());
                    for (var e : level.getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class, new net.minecraft.world.phys.AABB(-40, 80, -40, 120, 140, 40))) e.discard();
                    for (int x = -12; x <= 72; x++) for (int z = -16; z <= 16; z++) {
                        level.setBlockAndUpdate(new BlockPos(x, 99, z), Blocks.SMOOTH_STONE.defaultBlockState());
                        for (int y = 100; y < 112; y++) level.removeBlock(new BlockPos(x, y, z), false);
                    }
                    // 八格处一格高的台阶，十二到十四格处四格高的土石墙：巨型剑气迈上台阶、削开墙
                    for (int z = -8; z <= 8; z++) {
                        level.setBlockAndUpdate(new BlockPos(8, 100, z), Blocks.SMOOTH_STONE.defaultBlockState());
                        for (int x = 12; x <= 14; x++) for (int y = 100; y < 104; y++)
                            level.setBlockAndUpdate(new BlockPos(x, y, z), (y < 102 ? Blocks.DIRT : Blocks.COBBLESTONE).defaultBlockState());
                    }
                    var player = ctx.player();
                    DimensionTravel.teleport(player, level, new Vec3(.5, 100, .5), -90, 0);
                    player.getInventory().clearContent();
                    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(LongswordItem.DAWNVOW.get()));
                    var magic = PlayerMagic.of(player);
                    magic.baseMaxMana = Math.max(magic.baseMaxMana, 3000);
                    magic.mana = 3000;
                    targets[0] = target(level, EntityType.HUSK, 18).getId();
                    targets[1] = target(level, EntityType.IRON_GOLEM, 28).getId();
                    targets[2] = target(level, EntityType.WARDEN, 50).getId();
                })
                .setGameMode(GameType.SURVIVAL)
                .waitUntil("拔剑", ctx -> phase("idle")).ticks(4)
                .step("按住右键（中途取消）", ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.options.keyUse.setDown(true);
                    mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                    started = mc.level.getGameTime();
                })
                .waitUntil("蓄力同步（中途取消）", ctx -> phase("charge_start", "charge_hold"));
        at(s, "进入大型蓄力（中途取消）", 165).check("中途取消前已进入大型蓄力", ctx -> DawnvowClient.auras() > 0)
                .step("中途松开", ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.options.keyUse.setDown(false);
                    mc.gameMode.releaseUsingItem(mc.player);
                })
                .waitUntil("中途取消后收势", ctx -> phase("idle")).ticks(30)
                .check("中途取消后身上的光与剑身金光都收掉", ctx -> DawnvowClient.auras() == 0
                        && DawnvowClient.blade(Minecraft.getInstance().player.getId(), 0) == 0)
                .screenshot("cancel_cleared")
                .server("补满魔力", ctx -> PlayerMagic.of(ctx.player()).mana = 3000)
                .ticks(10)
                .step("按住右键", ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.options.keyUse.setDown(true);
                    mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                    started = mc.level.getGameTime();
                })
                .waitUntil("蓄力同步", ctx -> phase("charge_start", "charge_hold"));
        side(s, "侧面", new Vec3(4.5, 101.8, 8), new Vec3(.5, 101, .5));
        at(s, "普通蓄力", 60).screenshot("charge_3s");
        at(s, "大型蓄力", 160).check("进入大型蓄力", ctx -> DawnvowClient.auras() > 0).screenshot("grand_8s");
        side(s, "远处", new Vec3(-14, 108, 22), new Vec3(4, 100, 0));
        at(s, "光点", 220).screenshot("grand_11s_wide");
        side(s, "侧面近", new Vec3(4.5, 101.8, 8), new Vec3(.5, 101.2, .5));
        at(s, "剑身金光", 270).check("剑身发光", ctx -> DawnvowClient.blade(Minecraft.getInstance().player.getId(), 0) != 0).screenshot("blade_13s");
        own(s, "正面", CameraType.THIRD_PERSON_FRONT);
        at(s, "蓄满", 302).screenshot("full_burst");
        at(s, "金色流火", 318).check("蓄满", ctx -> DawnvowClient.blade(Minecraft.getInstance().player.getId(), 0) < 0).screenshot("full_front");
        own(s, "第一人称", CameraType.FIRST_PERSON);
        // 隐藏界面时原版不画第一人称的手
        s.step("显示手", ctx -> Minecraft.getInstance().options.hideGui = false).ticks(4).screenshot("full_first_person")
                .check("调暗时分层留到了画面（剑上的火焰不跟着暗）", ctx -> DawnvowScreen.lastLayers() > 0)
                // 抬头看天：火焰背后是远处的天空，这时火焰最容易跟着暗下去
                .step("抬头", ctx -> Minecraft.getInstance().player.setXRot(-35)).ticks(3).screenshot("full_first_person_sky")
                .step("低头回来", ctx -> Minecraft.getInstance().player.setXRot(0))
                .step("隐藏界面", ctx -> Minecraft.getInstance().options.hideGui = true);
        own(s, "背后", CameraType.THIRD_PERSON_BACK);
        s.ticks(4).screenshot("full_back");
        side(s, "剑气侧面", new Vec3(24, 103, 22), new Vec3(26, 101, 0));
        s
                .step("松开右键", ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.options.keyUse.setDown(false);
                    mc.gameMode.releaseUsingItem(mc.player);
                })
                .waitUntil("巨型剑气", ctx -> DawnvowClient.waves() > 0).ticks(1).screenshot("release")
                .ticks(3).screenshot("wave")
                .ticks(3).screenshot("wave_late")
                .waitUntil("剑气扎进地面时镜头抖动", ctx -> DawnvowShake.strength() > .05)
                .step("记下剑气到头的时刻", ctx -> {
                    var mc = Minecraft.getInstance();
                    arrive = DawnvowClient.arrival(mc.player.getId());
                    passes = DawnvowScreen.passes();
                })
                .ticks(2).screenshot("landing")
                .check("后期在画", ctx -> DawnvowScreen.passes() > passes);
        side(s, "光柱远景", new Vec3(-10, 112, 30), new Vec3(30, 106, 0));
        // 远景机位离落点约 67 格，震动按距离减到三成多
        s.waitUntil("光柱冲起", ctx -> Minecraft.getInstance().level.getGameTime() >= arrive + SwordWave.PILLAR_DELAY + 1)
                .check("光柱冲起时镜头一震", ctx -> DawnvowShake.strength() > .2)
                .check("几何黑白闪六拍都放到了", ctx -> DawnvowScreen.beatsShown() == DawnvowBurst.BEATS.length)
                .ticks(2).screenshot("pillar_wide")
                .server("记下生命", ctx -> {
                    for (int id : targets) {
                        var e = ctx.server().overworld().getEntity(id);
                        System.out.println("[dawnvow] target " + id + " " + (e instanceof LivingEntity l ? l.getType() + " " + l.getHealth() + " alive=" + l.isAlive() : "gone"));
                    }
                })
                .checkServer("尸壳被直接击杀", ctx -> !(ctx.server().overworld().getEntity(targets[0]) instanceof LivingEntity l && l.isAlive()))
                .checkServer("铁傀儡受 50 点伤害", ctx -> ((LivingEntity) ctx.server().overworld().getEntity(targets[1])).getHealth() == 50)
                .checkServer("监守者吃到光柱 200 点", ctx -> ((LivingEntity) ctx.server().overworld().getEntity(targets[2])).getHealth() == 300)
                .checkServer("土石墙被削开、台阶与地面还在", ctx -> {
                    var level = ctx.server().overworld();
                    return level.getBlockState(new BlockPos(13, 100, 0)).isAir() && level.getBlockState(new BlockPos(13, 103, 0)).isAir()
                            && level.getBlockState(new BlockPos(8, 100, 0)).is(Blocks.SMOOTH_STONE) && level.getBlockState(new BlockPos(13, 99, 0)).is(Blocks.SMOOTH_STONE);
                });
        side(s, "削开的墙", new Vec3(4, 109, 13), new Vec3(14, 100, 0));
        s.ticks(2).screenshot("trench");
        own(s, "正面 剑身金光", CameraType.THIRD_PERSON_FRONT);
        s.ticks(40).check("放出后剑身金光保留", ctx -> DawnvowClient.blade(Minecraft.getInstance().player.getId(), 0) != 0)
                .checkServer("攻击距离变长", ctx -> ctx.player().getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ENTITY_INTERACTION_RANGE) > 5.9)
                .screenshot("blessed_front");
        own(s, "背后 普通蓄力斩", CameraType.THIRD_PERSON_BACK);
        s.waitUntil("收势", ctx -> phase("idle")).ticks(10)
                .step("再蓄一下", ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.options.keyUse.setDown(true);
                    mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                })
                .waitUntil("蓄力同步 2", ctx -> phase("charge_start", "charge_hold")).ticks(20)
                .step("松开", ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.options.keyUse.setDown(false);
                    mc.gameMode.releaseUsingItem(mc.player);
                })
                .waitUntil("下劈", ctx -> phase("charge_release")).ticks(15).screenshot("small_wave")
                .ticks(6).screenshot("small_wave_later");
        side(s, "剑气侧面", new Vec3(8, 102.5, 7), new Vec3(10, 100, .5));
        s.ticks(1).screenshot("small_wave_side");
        // 只在本机重放一道巨型剑气（服务端不知道，没有伤害和光柱），停住时钟按时间轴拍光柱冲起之前的那串画面
        own(s, "背后 重放", CameraType.THIRD_PERSON_BACK);
        s.waitUntil("上一道剑气画完", ctx -> DawnvowClient.waves() == 0)
                .step("重放巨型剑气", ctx -> {
                    var mc = Minecraft.getInstance();
                    long t = mc.level.getGameTime();
                    float[] path = new float[49];
                    Arrays.fill(path, 100f);
                    DawnvowClient.receive(new DawnvowPayload(DawnvowPayload.RELEASE, mc.player.getId(), t, new Vec3(2.5, 100, .5), new Vec3(1, 0, 0), path));
                    arrive = t + 24 / DawnvowCharge.GRAND_SPEED;
                    passes = DawnvowScreen.passes();
                });
        for (Phase phase : PHASES) {
            s.step("停在第 " + phase.age() + " 刻", ctx -> DawnvowClient.pin(arrive + phase.age())).ticks(3).screenshot(phase.name());
        }
        s.check("重放时后期在画", ctx -> DawnvowScreen.passes() > passes)
                .step("放开时钟", ctx -> DawnvowClient.pin(-1));
        s.teardown("恢复", ctx -> {
            var mc = Minecraft.getInstance();
            DawnvowClient.pin(-1);
            mc.options.keyUse.setDown(false);
            mc.setCameraEntity(mc.player);
            mc.options.hideGui = previousHideGui;
            mc.options.setCameraType(CameraType.FIRST_PERSON);
        });
    }
}
