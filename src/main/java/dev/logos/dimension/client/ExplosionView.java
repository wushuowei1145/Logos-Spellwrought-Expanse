package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.client.explosion.ExplosionClient;
import dev.logos.dimension.client.explosion.ExplosionScreen;
import dev.logos.dimension.client.screenfx.PostVfx;
import dev.logos.dimension.client.screenfx.ScreenEffectRenderer;
import dev.logos.dimension.explosion.ExplosionExhaustion;
import dev.logos.dimension.explosion.ExplosionMagic;
import dev.logos.dimension.explosion.ExplosionStaffItem;
import dev.logos.dimension.screenfx.ScreenFlash;
import dev.logos.runtime.PlayerMagic;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.mojang.logging.LogUtils;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 爆裂魔法的实际客户端检查：在超平坦上垫一块十几格厚的石台，台上种几棵树、搭一间小屋、放几头牛和铁傀儡；
 * 施法者站在九十多格外的另一块石垫上，真按住右键吟唱 20 秒到蓄满，再松手。
 * 吟唱从侧面机位按进度截图，蓄满后再从第一人称和远处各截一张；爆炸从远处机位按 {@link dev.logos.dimension.explosion.ExplosionTimeline}
 * 的各个时刻停住特效的时钟截图（服务端照常走）。最后检查石台上的东西都死了、坑挖了、后期确实画了。
 */
@LDLRegisterClient(name = "explosion_staff", group = "explosion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
public class ExplosionView implements UIScenario {
    /** 超平坦的草在 -61；石台从 -60 垫到 -50，草面 -49，人站在 -48。 */
    static final int TOP = -49, PLATEAU = 74;
    static final Vec3 TARGET = new Vec3(.5, TOP + 1, 96.5), CASTER = new Vec3(.5, TOP + 1, .5);
    private final List<UUID> inside = new ArrayList<>();
    private int side = -1, far = -1, front = -1, top = -1, prone = -1;
    private Vec3 lying = Vec3.ZERO;
    private boolean previousHideGui;
    private int previousDistance;

    @Override public void configure(ScenarioOptions o) { o.tags("explosion").defaultTimeoutMs(90000).scenarioTimeoutMs(420000); }

    /** 目的地周围垫一块石台，台上种几棵树、搭一间小屋；施法者脚下另垫一块石垫。 */
    static void stage(ServerLevel level) {
        for (int x = -PLATEAU; x <= PLATEAU; x++) for (int z = -PLATEAU; z <= PLATEAU; z++)
            if (x * x + z * z <= PLATEAU * PLATEAU) column(level, (int) TARGET.x + x, (int) TARGET.z + z);
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) column(level, x, z);
        int[][] trees = {{-20, 70}, {14, 80}, {-35, 100}, {30, 110}, {-8, 125}, {40, 90}, {-45, 80}, {20, 140}};
        for (int[] t : trees) tree(level, t[0], t[1]);
        hut(level, 18, 82);
    }

    private static void column(ServerLevel level, int x, int z) {
        for (int y = -60; y < TOP; y++) level.setBlock(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        level.setBlock(new BlockPos(x, TOP, z), Blocks.GRASS_BLOCK.defaultBlockState(), Block.UPDATE_CLIENTS);
    }

    private static void tree(ServerLevel level, int x, int z) {
        BlockState log = Blocks.OAK_LOG.defaultBlockState(), leaves = Blocks.OAK_LEAVES.defaultBlockState();
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = 3; dy <= 6; dy++)
            if (dx * dx + dz * dz + (dy - 4) * (dy - 4) <= 7) level.setBlock(new BlockPos(x + dx, TOP + dy, z + dz), leaves, Block.UPDATE_CLIENTS);
        for (int dy = 1; dy <= 5; dy++) level.setBlock(new BlockPos(x, TOP + dy, z), log, Block.UPDATE_CLIENTS);
    }

    private static void hut(ServerLevel level, int x0, int z0) {
        for (int x = 0; x < 7; x++) for (int z = 0; z < 7; z++) for (int y = 1; y <= 5; y++) {
            boolean wall = x == 0 || x == 6 || z == 0 || z == 6 || y == 5;
            boolean door = z == 0 && x == 3 && y <= 2;
            level.setBlock(new BlockPos(x0 + x, TOP + y, z0 + z), wall && !door ? Blocks.OAK_PLANKS.defaultBlockState() : Blocks.AIR.defaultBlockState(),
                    Block.UPDATE_CLIENTS);
        }
    }

    private Entity spawn(ServerLevel level, EntityType<? extends Entity> type, double x, double z, boolean track) {
        Entity e = type.create(level, EntitySpawnReason.COMMAND);
        if (e instanceof Mob mob) {
            mob.setNoAi(true);
            mob.setPersistenceRequired();
        }
        e.snapTo(x, TOP + 1, z, 0, 0);
        level.addFreshEntity(e);
        if (track) inside.add(e.getUUID());
        return e;
    }

    /** 一个看着 {@code at} 的隐形机位。 */
    static int camera(ServerLevel level, Vec3 from, Vec3 at) {
        var stand = EntityType.ARMOR_STAND.create(level, EntitySpawnReason.COMMAND);
        stand.setInvisible(true);
        stand.setNoGravity(true);
        Vec3 d = at.subtract(from);
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        // 盔甲架的眼睛比脚高 1.7 格左右
        stand.snapTo(from.x, from.y - 1.7, from.z, yaw, pitch);
        stand.setYHeadRot(yaw);
        level.addFreshEntity(stand);
        return stand.getId();
    }

    /** 时钟停着：一刻一刻推到爆炸第 {@code age} 刻再截图，每张都准确停在时间轴上。 */
    private ScenarioBuilder at(ScenarioBuilder s, float age, String name) {
        return s.step("推到第 " + age + " 刻", ctx -> ExplosionClient.advanceTo(age))
                .frames(3)
                .screenshot(name);
    }

    @Override public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.SURVIVAL).runCommand("time set 6000").runCommand("weather clear").runCommand("difficulty easy")
                .runCommand("gamerule spawn_mobs false")
                .step("设置视口", ctx -> {
                    var mc = Minecraft.getInstance();
                    previousHideGui = mc.options.hideGui;
                    previousDistance = mc.options.renderDistance().get();
                    mc.options.hideGui = true;
                    mc.options.renderDistance().set(16);
                    // 服务端按客户端报上去的视距决定发多远的区块和实体：改了要报上去
                    mc.options.broadcastOptions();
                    mc.player.getInventory().setSelectedSlot(0);
                    mc.options.setCameraType(CameraType.FIRST_PERSON);
                    mc.options.fov().set(70);
                    ExplosionClient.clear();
                    var w = mc.getWindow(); w.setWindowed(1280, 720); w.setWidth(1280); w.setHeight(720);
                    mc.getMainRenderTarget().resize(1280, 720); mc.gameRenderer.resize(1280, 720); mc.resizeGui();
                })
                .server("视距", ctx -> ctx.server().getPlayerList().setViewDistance(16))
                .server("搭石台", ctx -> stage(ctx.server().overworld()))
                .serverTicks(80)
                .waitUntil("石垫到了客户端", ctx -> Minecraft.getInstance().level.getBlockState(new BlockPos(0, TOP, 0)).is(Blocks.GRASS_BLOCK))
                .server("站到石垫上", ctx -> DimensionTravel.teleport(ctx.player(), ctx.server().overworld(), CASTER, 0, .97f))
                .waitUntil("站稳", ctx -> Minecraft.getInstance().player.getY() > TOP && Minecraft.getInstance().player.onGround())
                .step("记下位置", ctx -> LogUtils.getLogger().info("[explosion] player at {}", Minecraft.getInstance().player.position()))
                .server("放生物和机位", ctx -> {
                    ServerLevel level = ctx.server().overworld();
                    inside.clear();
                    double[][] cows = {{4, 100}, {-12, 88}, {22, 108}, {-30, 112}, {10, 140}, {-50, 96}};
                    for (double[] c : cows) spawn(level, EntityType.COW, c[0], c[1], true);
                    spawn(level, EntityType.IRON_GOLEM, -6, 96, true);
                    spawn(level, EntityType.IRON_GOLEM, 30, 80, true);
                    spawn(level, EntityType.ARMOR_STAND, 0, 110, true);
                    // 爆炸半径外：被气浪掀开，活下来
                    spawn(level, EntityType.COW, 0, 168, false);
                    side = camera(level, new Vec3(-22, TOP + 7, -26), new Vec3(2, TOP + 19, 40));
                    far = camera(level, new Vec3(-110, TOP + 30, 20), new Vec3(0, TOP + 26, 96));
                    // 在施法者前方、偏向拿杖的右手一侧（面朝南时右手在西边）：看手里的魔杖
                    front = camera(level, new Vec3(-1.4, TOP + 2.6, 3.6), new Vec3(.2, TOP + 2.1, .5));
                    ctx.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ExplosionStaffItem.ITEM.get()));
                    var magic = PlayerMagic.of(ctx.player());
                    magic.maxMana = magic.mana = 10000;
                })
                .waitUntil("魔杖同步", ctx -> Minecraft.getInstance().player.getMainHandItem().is(ExplosionStaffItem.ITEM.get()))
                .waitUntil("机位同步", ctx -> Minecraft.getInstance().level.getEntity(side) != null && Minecraft.getInstance().level.getEntity(far) != null
                        && Minecraft.getInstance().level.getEntity(front) != null)
                // 隐藏界面会连第一人称的手一起藏起来，杖头的位置也就只能估：第一人称的几张开着界面拍
                .step("开界面", ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.options.hideGui = false;
                    // 新世界的操作提示会挡在右上角
                    mc.getTutorial().setStep(TutorialSteps.NONE);
                    mc.getToastManager().clear();
                })
                .ticks(10)
                .screenshot("staff_first_person")
                .step("关界面", ctx -> Minecraft.getInstance().options.hideGui = true)
                .step("看魔杖", ctx -> Minecraft.getInstance().setCameraEntity(Minecraft.getInstance().level.getEntity(front)))
                .ticks(6)
                .screenshot("staff_third_person")
                .step("记下机位", ctx -> LogUtils.getLogger().info("[explosion] side {} far {}", Minecraft.getInstance().level.getEntity(side), Minecraft.getInstance().level.getEntity(far)))
                .step("切到侧面", ctx -> Minecraft.getInstance().setCameraEntity(Minecraft.getInstance().level.getEntity(side)))
                .ticks(10)
                .screenshot("before_side")
                .step("按住右键", ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.options.keyUse.setDown(true);
                    mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                })
                .waitUntil("开始吟唱", ctx -> ExplosionClient.charges() > 0);
        for (float p : new float[] {.12f, .35f, .6f, .85f}) {
            s.waitUntil("吟唱进度 " + p, ctx -> ExplosionClient.progress(Minecraft.getInstance().player.getId()) >= p)
                    .screenshot("charge_side_" + (int) (p * 100));
            if (p == .6f) s.step("第一人称看吟唱", ctx -> {
                        var mc = Minecraft.getInstance();
                        mc.setCameraEntity(mc.player);
                        mc.options.setCameraType(CameraType.FIRST_PERSON);
                        mc.options.hideGui = false;
                    })
                    .ticks(4)
                    .screenshot("charge_first_65")
                    .step("回到侧面", ctx -> {
                        Minecraft.getInstance().options.hideGui = true;
                        Minecraft.getInstance().setCameraEntity(Minecraft.getInstance().level.getEntity(side));
                    });
        }
        s.waitUntil("蓄满", ctx -> ExplosionClient.progress(Minecraft.getInstance().player.getId()) >= 1)
                // 蓄满那一下：杖头一闪，球形的冲击和地面的风墙从身上推出去
                .ticks(5)
                .screenshot("full_burst")
                .ticks(10)
                .screenshot("held_side")
                .step("第一人称", ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.setCameraEntity(mc.player);
                    mc.options.setCameraType(CameraType.FIRST_PERSON);
                    mc.options.hideGui = false;
                })
                .ticks(6)
                .screenshot("held_first")
                .step("远处机位", ctx -> {
                    Minecraft.getInstance().options.hideGui = true;
                    Minecraft.getInstance().setCameraEntity(Minecraft.getInstance().level.getEntity(far));
                })
                .ticks(6)
                .screenshot("held_far")
                .checkServer("吟唱注满了 6400 魔力", ctx -> ExplosionMagic.full(ctx.player()))
                .step("松开右键", ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.options.keyUse.setDown(false);
                    mc.gameMode.releaseUsingItem(mc.player);
                })
                .waitUntil("爆炸开始", ctx -> ExplosionClient.blasts() > 0)
                .step("停住特效的时钟", ctx -> ExplosionClient.freeze(true));
        for (float age : new float[] {4, 11, 14, 22, 28, 31, 35, 37}) at(s, age, "blast_" + (int) age);
        at(s, 44, "blast_44");
        s.step("停住黑白闪", ctx -> ScreenEffectRenderer.hold(ScreenFlash.Frame.SWAP, TARGET))
                .frames(3)
                .screenshot("blast_flash_swap")
                .step("黑白闪：吞没", ctx -> ScreenEffectRenderer.hold(ScreenFlash.Frame.ENGULF, TARGET))
                .frames(3)
                .screenshot("blast_flash_engulf")
                .step("放开黑白闪", ctx -> ScreenEffectRenderer.hold(null, TARGET));
        for (float age : new float[] {48, 62, 85, 110, 135, 170, 240, 360}) at(s, age, "blast_" + (int) age);
        s.step("放开特效的时钟", ctx -> ExplosionClient.freeze(false))
                .step("侧面看坑", ctx -> Minecraft.getInstance().setCameraEntity(Minecraft.getInstance().level.getEntity(side)))
                .ticks(40)
                .screenshot("crater_side")
                // 气浪会把半径一到 2.2 倍内的机位推走：俯视的机位等爆炸过去再放
                .server("俯视机位", ctx -> top = camera(ctx.server().overworld(), new Vec3(-34, TOP + 64, 40), new Vec3(TARGET.x, TOP - 10, TARGET.z)))
                .waitUntil("俯视机位同步", ctx -> Minecraft.getInstance().level.getEntity(top) != null)
                .step("收掉烟，俯视坑", ctx -> {
                    ExplosionClient.clear();
                    Minecraft.getInstance().setCameraEntity(Minecraft.getInstance().level.getEntity(top));
                })
                .ticks(10)
                .screenshot("crater_top")
                // 放完之后魔力耗尽：球体散尽时施法者趴下（原版的趴下姿势）30 秒。气浪把人往远离爆点的方向（北边）推，机位放在那一侧
                .waitUntil("施法者趴下", ctx -> Minecraft.getInstance().player.getPose() == Pose.SWIMMING)
                .step("记下施法者在哪", ctx -> {
                    lying = Minecraft.getInstance().player.position();
                    LogUtils.getLogger().info("[explosion] caster lying at {} (client), pose {}", lying, Minecraft.getInstance().player.getPose());
                })
                .server("看施法者的机位", ctx -> {
                    LogUtils.getLogger().info("[explosion] caster at {} (server), forced pose {}", ctx.player().position(), ctx.player().getForcedPose());
                    prone = camera(ctx.server().overworld(), lying.add(2.6, 2.2, -2.6), lying.add(0, .2, 0));
                })
                .waitUntil("看施法者的机位同步", ctx -> Minecraft.getInstance().level.getEntity(prone) != null)
                .step("看施法者", ctx -> Minecraft.getInstance().setCameraEntity(Minecraft.getInstance().level.getEntity(prone)))
                .ticks(6)
                .screenshot("caster_prone")
                .checkServer("施法者在服务端也趴着", ctx -> ExplosionExhaustion.exhausted(ctx.player()))
                .checkServer("石台上的东西都没了", ctx -> {
                    ServerLevel level = ctx.server().overworld();
                    for (UUID id : inside) {
                        Entity e = level.getEntity(id);
                        if (e != null && e.isAlive()) return false;
                    }
                    return true;
                })
                .checkServer("挖出了坑", ctx -> ExplosionMagic.carved() > 50000)
                .checkServer("坑底到了基岩", ctx -> ctx.server().overworld().getBlockState(BlockPos.containing(TARGET.x, -63, TARGET.z)).isAir())
                .check("全屏后期画过", ctx -> ExplosionScreen.passes() > 0 && PostVfx.passes() > 0)
                .teardown("恢复", ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.options.keyUse.setDown(false);
                    ExplosionClient.freeze(false);
                    ScreenEffectRenderer.hold(null, Vec3.ZERO);
                    mc.setCameraEntity(mc.player);
                    mc.options.hideGui = previousHideGui;
                    mc.options.renderDistance().set(previousDistance);
                    mc.options.setCameraType(CameraType.FIRST_PERSON);
                });
    }
}
