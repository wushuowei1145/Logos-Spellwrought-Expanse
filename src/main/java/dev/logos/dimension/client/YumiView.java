package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.client.longsword.LongswordClient;
import dev.logos.dimension.item.NatureAttachments;
import dev.logos.dimension.item.YumiItem;
import java.util.Arrays;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 神狐和弓的实际按键试玩：取弓、举弓中松手（不放箭、弓放回）、第三人称完整射法（打起し、引分け、会、離れ与残心）、
 * 满弓命中远处的木桩（伤害明显高于原版弓）、第一人称拉弓、左手惯用时的射法，最后让骷髅拿着它当原版弓射箭。
 * 会时核对画出来的箭在世界里指着视线（含拉满后转动视线、左手惯用），并从正上方和胸前一侧截图。
 */
@LDLRegisterClient(name="yumi", group="yumi", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
public class YumiView implements UIScenario {
    private boolean previousHideGui;
    private int target, skeleton, arrows, overhead, side;
    private final int[] compare = new int[3];
    private float health;
    private boolean phase(String... moves) {
        var p = LongswordClient.pose(Minecraft.getInstance().player.getUUID());
        return p != null && Arrays.asList(moves).contains(p.move());
    }
    private LivingEntity golem(MinecraftServer server) { return (LivingEntity) server.overworld().getEntity(target); }
    private static void camera(CameraType type) { Minecraft.getInstance().options.setCameraType(type); }
    private static void press() {
        var mc = Minecraft.getInstance(); mc.options.keyUse.setDown(true);
        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
    }
    private static void release() {
        var mc = Minecraft.getInstance(); mc.options.keyUse.setDown(false); mc.gameMode.releaseUsingItem(mc.player);
    }
    /** 第三人称画出的箭与视线的夹角余弦（含俯仰）；箭不在弦上时为 -2。 */
    private static double aim() {
        var player = Minecraft.getInstance().player;
        var d = YumiClient.arrowDirection(player.getId());
        if (d == null) return -2;
        var look = Vec3.directionFromRotation(player.getXRot(), player.getYHeadRot());
        return d.x * look.x + d.y * look.y + d.z * look.z;
    }
    private static void pitch(float degrees) {
        var p = Minecraft.getInstance().player; p.setXRot(degrees); p.xRotO = degrees;
    }
    private static void face(float yaw) {
        var p = Minecraft.getInstance().player;
        p.setYRot(yaw); p.yRotO = yaw; p.setYHeadRot(yaw); p.yHeadRotO = yaw;
    }
    /** 换到一个固定的盔甲架镜头截图（玩家一直站在 (0.5, 100, 0.5)，本身照常画出）。 */
    private ScenarioBuilder view(ScenarioBuilder s, java.util.function.IntSupplier stand, String shot) {
        return s.step("换镜头", ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.setCameraEntity(mc.level.getEntity(stand.getAsInt()));
                }).ticks(3).screenshot(shot)
                .step("回到玩家镜头", ctx -> Minecraft.getInstance().setCameraEntity(Minecraft.getInstance().player));
    }
    private static void hand(HumanoidArm arm) {
        var mc = Minecraft.getInstance(); mc.options.mainHand().set(arm); mc.options.broadcastOptions();
    }
    private static int ammo(net.minecraft.server.level.ServerPlayer player) {
        return player.getInventory().countItem(Items.ARROW);
    }
    @Override public void configure(ScenarioOptions o) { o.tags("yumi").defaultTimeoutMs(60000).scenarioTimeoutMs(180000); }
    @Override public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set day").runCommand("weather clear").runCommand("difficulty easy")
                .step("设置视口", ctx -> {
                    var mc = Minecraft.getInstance(); previousHideGui = mc.options.hideGui;
                    mc.options.hideGui = false; mc.player.getInventory().setSelectedSlot(0);
                    camera(CameraType.THIRD_PERSON_FRONT); mc.options.fov().set(70);
                    var w = mc.getWindow(); w.setWindowed(1280,720); w.setWidth(1280); w.setHeight(720);
                    mc.getMainRenderTarget().resize(1280,720); mc.gameRenderer.resize(1280,720); mc.resizeGui();
                }).server("设置射场与木桩", ctx -> {
                    var level = ctx.server().overworld();
                    for (int x=-8;x<=8;x++) for (int z=-8;z<=20;z++) {
                        level.setBlockAndUpdate(new BlockPos(x,99,z), Blocks.STONE_BRICKS.defaultBlockState());
                        for (int y=100;y<110;y++) level.removeBlock(new BlockPos(x,y,z),false);
                    }
                    DimensionTravel.teleport(ctx.player(),level,new Vec3(.5,100,.5),0,0);
                    // 木桩在正前方 14 格（朝向 0 为 +Z），不动、不还手。
                    var golem = EntityType.IRON_GOLEM.create(level, EntitySpawnReason.COMMAND);
                    golem.setNoAi(true); golem.setPos(.5,100,14.5); level.addFreshEntity(golem); target = golem.getId();
                    ctx.player().getInventory().clearContent(); ctx.player().getInventory().setSelectedSlot(0);
                    ctx.player().setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(YumiItem.BAMBOO.get()));
                    ctx.player().getInventory().setItem(8,new ItemStack(Items.ARROW,16));
                    // 截图用的镜头：隐形、不动的盔甲架。一个在正上方朝下看（画面上方是 +Z，即朝向 0 的正前方）；
                    // 一个在 -X 一侧朝 +X 看：朝向 0 拉弓时身体朝 -X，正对胸前，靶在画面右边。
                    var stand = new ArmorStand(EntityType.ARMOR_STAND, level);
                    stand.setInvisible(true); stand.setNoGravity(true); stand.setPos(.5,103,.5); stand.setXRot(90); stand.setYRot(0);
                    level.addFreshEntity(stand); overhead = stand.getId();
                    var chest = new ArmorStand(EntityType.ARMOR_STAND, level);
                    chest.setInvisible(true); chest.setNoGravity(true); chest.setPos(-3.5,100,1.5); chest.setYRot(-90); chest.setYHeadRot(-90);
                    level.addFreshEntity(chest); side = chest.getId();
                }).setGameMode(GameType.SURVIVAL)
                .waitUntil("截图镜头就位", ctx -> Minecraft.getInstance().level.getEntity(overhead) != null
                        && Minecraft.getInstance().level.getEntity(side) != null)
                .waitUntil("取弓", ctx -> phase("ready","idle")).waitUntil("执弓", ctx -> phase("idle")).ticks(4).screenshot("idle_third_person")
                .server("记录箭数", ctx -> arrows = ammo(ctx.player()))
                // 举弓中松手：不放箭，弓倒放回执弓。
                .step("按下右键", ctx -> press()).waitUntil("打起し", ctx -> phase("uchiokoshi")).ticks(3)
                .step("举弓中松手", ctx -> release()).waitUntil("弓放回", ctx -> phase("lower","idle"))
                .waitUntil("回到执弓", ctx -> phase("idle"))
                .checkServer("举弓中松手不放箭", ctx -> ammo(ctx.player()) == arrows
                        && ctx.server().overworld().getEntitiesOfClass(AbstractArrow.class, new AABB(-10,95,-10,10,110,22)).isEmpty())
                // 第三人称完整射法。
                .step("拉弓", ctx -> press()).waitUntil("打起し", ctx -> phase("uchiokoshi")).ticks(7).screenshot("uchiokoshi")
                .waitUntil("引分け", ctx -> phase("hikiwake")).ticks(5).screenshot("daisan")
                .waitUntil("会", ctx -> phase("kai")).ticks(6).screenshot("kai")
                .step("背后看会", ctx -> camera(CameraType.THIRD_PERSON_BACK)).ticks(2).screenshot("kai_back")
                // 原版第三人称镜头总在视线上，侧身后只看得到侧面；低头从斜上方看两脚是否沿靶的方向分开。
                .step("斜上方看足踏み", ctx -> { var p = Minecraft.getInstance().player; p.setXRot(55); p.xRotO = 55; }).ticks(2).screenshot("kai_above")
                .step("抬头回到平视", ctx -> { var p = Minecraft.getInstance().player; p.setXRot(0); p.xRotO = 0; })
                .check("会：箭指着视线", ctx -> aim() > 0.97);
        view(s, () -> overhead, "kai_top");
        view(s, () -> side, "kai_side")
                // 拉满后转动视线：原版身体会落后视线，箭仍要跟着视线。
                .step("会中向左转 70°", ctx -> face(70)).ticks(4)
                .check("转动视线后箭仍指着视线", ctx -> aim() > 0.97)
                .step("转回木桩", ctx -> face(0)).ticks(4)
                .check("转回后箭指着木桩", ctx -> aim() > 0.97)
                // 抬头、低头：双臂和弓跟着俯仰，箭指着视线。
                .step("抬头 40°", ctx -> pitch(-40)).ticks(3)
                .check("抬头时箭指着视线", ctx -> aim() > 0.97);
        view(s, () -> side, "kai_side_up")
                .step("低头 30°", ctx -> pitch(30)).ticks(3)
                .check("低头时箭指着视线", ctx -> aim() > 0.97);
        view(s, () -> side, "kai_side_down")
                .step("回到平视", ctx -> pitch(0)).ticks(2)
                .server("记录木桩生命", ctx -> health = golem(ctx.server()).getHealth())
                .step("放箭", ctx -> release()).waitUntil("離れ", ctx -> phase("hanare")).ticks(3).screenshot("hanare_back")
                .step("正面看残心", ctx -> camera(CameraType.THIRD_PERSON_FRONT)).ticks(6).screenshot("zanshin")
                .waitUntilServer("木桩中箭", ctx -> golem(ctx.server()).getHealth() < health).ticks(2)
                .checkServer("满弓一箭伤害明显高于原版满弓（原版 6）", ctx -> health - golem(ctx.server()).getHealth() >= 9)
                .checkServer("用掉一支箭", ctx -> ammo(ctx.player()) == arrows - 1)
                .checkServer("射出的箭标记为和弓的箭", ctx -> ctx.server().overworld().getEntitiesOfClass(AbstractArrow.class,
                        new AABB(-10,95,-10,10,110,22)).stream().allMatch(a -> a.getData(NatureAttachments.YUMI_ARROW)))
                .waitUntil("残心后回到执弓", ctx -> phase("idle"))
                // 第一人称。
                .step("第一人称", ctx -> camera(CameraType.FIRST_PERSON)).ticks(3).screenshot("idle_first_person")
                .step("第一人称拉弓", ctx -> press()).waitUntil("打起し", ctx -> phase("uchiokoshi")).ticks(6).screenshot("uchiokoshi_first_person")
                .waitUntil("引分け", ctx -> phase("hikiwake")).ticks(5).screenshot("daisan_first_person")
                .waitUntil("会", ctx -> phase("kai")).ticks(6).screenshot("kai_first_person")
                .step("放箭", ctx -> release()).waitUntil("離れ", ctx -> phase("hanare")).ticks(3).screenshot("hanare_first_person")
                .waitUntil("回到执弓", ctx -> phase("idle"))
                // 左手惯用：整副骨架镜像，右手持弓、身体朝另一侧转，箭照样指着视线。
                .step("改成左手惯用", ctx -> hand(HumanoidArm.LEFT))
                .waitUntil("左手惯用生效", ctx -> Minecraft.getInstance().player.getMainArm() == HumanoidArm.LEFT)
                .step("背后看", ctx -> camera(CameraType.THIRD_PERSON_BACK))
                .step("左手惯用拉弓", ctx -> press()).waitUntil("会", ctx -> phase("kai")).ticks(6)
                .check("左手惯用：箭指着视线", ctx -> aim() > 0.97)
                .step("左手惯用抬头 30°", ctx -> pitch(-30)).ticks(3)
                .check("左手惯用抬头时箭指着视线", ctx -> aim() > 0.97)
                .step("左手惯用回到平视", ctx -> pitch(0)).ticks(2);
        view(s, () -> overhead, "kai_top_left_handed")
                .step("放箭", ctx -> release()).waitUntil("回到执弓", ctx -> phase("idle"))
                .step("改回右手惯用", ctx -> hand(HumanoidArm.RIGHT))
                .waitUntil("右手惯用生效", ctx -> Minecraft.getInstance().player.getMainArm() == HumanoidArm.RIGHT)
                .server("移除木桩，放一只拿和弓的骷髅", ctx -> {
                    var level = ctx.server().overworld();
                    var e = level.getEntity(target); if (e != null) e.discard();
                    level.getEntitiesOfClass(AbstractArrow.class, new AABB(-10,95,-10,10,110,22)).forEach(Entity::discard);
                    var mob = EntityType.SKELETON.create(level, EntitySpawnReason.COMMAND);
                    mob.setPos(2.5,100,6.5); mob.setYRot(160); mob.setYHeadRot(160); mob.yBodyRot = 160;
                    mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(YumiItem.VERMILION.get()));
                    mob.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE,7200,0,false,false));
                    level.addFreshEntity(mob); mob.reassessWeaponGoal(); skeleton = mob.getId();
                })
                .runCommand("time set midnight")
                .step("第三人称看骷髅", ctx -> camera(CameraType.THIRD_PERSON_BACK))
                .waitUntil("骷髅拉弓", ctx -> Minecraft.getInstance().level.getEntity(skeleton) instanceof LivingEntity mob && mob.isUsingItem())
                .ticks(8).screenshot("skeleton")
                .waitUntilServer("骷髅用原版弓的 AI 射箭", ctx -> !skeletonArrows(ctx.server()).isEmpty())
                .checkServer("骷髅射出的是原版箭（不带和弓标记）", ctx -> skeletonArrows(ctx.server()).stream()
                        .noneMatch(a -> a.getData(NatureAttachments.YUMI_ARROW)))
                .server("移除骷髅", ctx -> {
                    var e = ctx.server().overworld().getEntity(skeleton); if (e != null) e.discard();
                    ctx.server().overworld().getEntitiesOfClass(AbstractArrow.class, new AABB(-10,95,-10,10,110,22)).forEach(Entity::discard);
                })
                // 对照：原版弓、和弓、左手惯用的和弓，手臂都举着拉弓；大小、朝向、握的位置要和原版弓一样。
                // 还是半夜（白天骷髅会着火），夜视照亮；玩家让开，免得挡在骷髅后面。
                .server("放三只正面的骷髅", ctx -> {
                    ctx.player().addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.NIGHT_VISION, 600, 0, false, false));
                    ctx.player().teleportTo(7, 100, -6);
                    compareSkeletons(ctx.server(), 90);
                }).ticks(5);
        view(s, () -> side, "skeleton_compare_front")
                .server("换成侧面的骷髅", ctx -> compareSkeletons(ctx.server(), 0)).ticks(5);
        view(s, () -> side, "skeleton_compare_side")
                .runCommand("time set day")
                .server("移除对照的骷髅与镜头", ctx -> {
                    for (int id : compare) { var e = ctx.server().overworld().getEntity(id); if (e != null) e.discard(); }
                    for (int id : new int[]{overhead, side}) { var stand = ctx.server().overworld().getEntity(id); if (stand != null) stand.discard(); }
                })
                .teardown("恢复输入", ctx -> {
                    var mc=Minecraft.getInstance(); mc.options.keyUse.setDown(false);
                    mc.setCameraEntity(mc.player); hand(HumanoidArm.RIGHT);
                    mc.options.hideGui=previousHideGui; camera(CameraType.FIRST_PERSON);
                });
    }
    /** 三只不动、举着手臂拉弓的骷髅，朝向 yaw（新生成的才会连身体一起朝过去）。 */
    private void compareSkeletons(MinecraftServer server, float yaw) {
        var level = server.overworld();
        var bows = java.util.List.of(new ItemStack(Items.BOW), new ItemStack(YumiItem.VERMILION.get()), new ItemStack(YumiItem.BAMBOO.get()));
        for (int i = 0; i < 3; i++) {
            var old = level.getEntity(compare[i]);
            if (compare[i] != 0 && old != null) old.discard();
            var mob = EntityType.SKELETON.create(level, EntitySpawnReason.COMMAND);
            mob.snapTo(-1.2, 100, 0.2 + 1.3 * i, yaw, 0);
            mob.setYHeadRot(yaw); mob.setYBodyRot(yaw);
            mob.setNoAi(true); mob.setAggressive(true); mob.setLeftHanded(i == 2);
            mob.setItemSlot(EquipmentSlot.MAINHAND, bows.get(i));
            level.addFreshEntity(mob);
            compare[i] = mob.getId();
        }
    }
    private java.util.List<AbstractArrow> skeletonArrows(MinecraftServer server) {
        return server.overworld().getEntitiesOfClass(AbstractArrow.class, new AABB(-10,95,-10,10,110,22),
                a -> a.getOwner() != null && a.getOwner().getId() == skeleton);
    }
}
