package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.client.longsword.LongswordClient;
import dev.logos.dimension.client.vfx.SpearThrustGhosts;
import dev.logos.dimension.combat.Airborne;
import dev.logos.dimension.item.OathSpearItem;
import java.util.Arrays;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * 群誓·狼牙长枪的实际按键试玩：从背后转两圈拔枪、四段连击（第四击把木桩挑上半空）、按住右键连刺（枪尖残影、
 * 刺中浮空目标维持浮空）、三秒后转枪下砸（砸落、原版摔落伤害）、收枪转一圈背回身后，第一与第三人称截图。
 * 木桩是关了 AI 的尸壳（会受摔落伤害，白天不着火；铁傀儡免疫摔落），生命加到 1000，整套打下来不会死。
 * 拔枪与待机先从正面拍（木桩还没放），打木桩时从背后拍，免得木桩挡在镜头和人之间。
 */
@LDLRegisterClient(name="oath_spear", group="oath_spear", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
public class OathSpearView implements UIScenario {
    private boolean previousHideGui;
    private int target;
    private float health;
    private double baseY;
    /** 木桩受到的摔落伤害（魔力缠绕的附加魔法伤害会在摔落之后结算，盖掉“最后一次伤害”，所以单独记）。 */
    private static volatile float fallDamage;
    private static volatile int watched = -1;
    private static boolean listening;
    private boolean phase(String... moves) {
        var p = LongswordClient.pose(Minecraft.getInstance().player.getUUID());
        return p != null && Arrays.asList(moves).contains(p.move());
    }
    private LivingEntity dummy(net.minecraft.server.MinecraftServer server) { return (LivingEntity) server.overworld().getEntity(target); }
    private static void camera(CameraType type) { Minecraft.getInstance().options.setCameraType(type); }
    private static void hold(boolean use) {
        var mc = Minecraft.getInstance(); mc.options.keyUse.setDown(use);
        if (use) mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND); else mc.gameMode.releaseUsingItem(mc.player);
    }
    @Override public void configure(ScenarioOptions o) { o.tags("oath_spear").defaultTimeoutMs(60000).scenarioTimeoutMs(240000); }
    @Override public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set day").runCommand("weather clear").runCommand("difficulty easy")
                .step("设置视口", ctx -> {
                    var mc = Minecraft.getInstance(); previousHideGui = mc.options.hideGui;
                    mc.options.hideGui = false; mc.player.getInventory().setSelectedSlot(0);
                    camera(CameraType.THIRD_PERSON_FRONT); mc.options.fov().set(70);
                    var w = mc.getWindow(); w.setWindowed(1280,720); w.setWidth(1280); w.setHeight(720);
                    mc.getMainRenderTarget().resize(1280,720); mc.gameRenderer.resize(1280,720); mc.resizeGui();
                }).server("设置练枪场与木桩", ctx -> {
                    var level = ctx.server().overworld();
                    for (int x=-8;x<=8;x++) for (int z=-8;z<=10;z++) {
                        level.setBlockAndUpdate(new BlockPos(x,99,z), Blocks.STONE_BRICKS.defaultBlockState());
                        for (int y=100;y<110;y++) level.removeBlock(new BlockPos(x,y,z),false);
                    }
                    DimensionTravel.teleport(ctx.player(),level,new Vec3(.5,100,.5),0,0);
                    ctx.player().getInventory().clearContent(); ctx.player().getInventory().setSelectedSlot(0);
                    ctx.player().setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(OathSpearItem.PACK_OATH.get()));
                    // 魔力缠绕按物品的立体模型表面定位：长枪的立体模型与枪网格一致。
                    dev.logos.runtime.ManaWrap.wrap(ctx.player(),dev.logos.lang.Element.FROST,4,1200);
                }).setGameMode(GameType.SURVIVAL)
                .waitUntil("拔枪同步", ctx -> phase("draw")).ticks(3).screenshot("draw_grab")
                .ticks(5).screenshot("draw_spin_1").ticks(6).screenshot("draw_spin_2").ticks(6).screenshot("draw_settle")
                .waitUntil("待机", ctx -> phase("idle")).ticks(4).screenshot("idle_third_person")
                .check("魔力缠绕产生表面", ctx -> dev.logos.client.SurfaceAuras.emitters() > 0)
                .step("观察侧面", ctx -> {var p=Minecraft.getInstance().player;p.yBodyRot=70;p.yBodyRotO=70;}).ticks(1).screenshot("idle_side")
                .step("恢复身体朝向", ctx -> {var p=Minecraft.getInstance().player;p.yBodyRot=0;p.yBodyRotO=0;})
                .server("放木桩", ctx -> {
                    var level = ctx.server().overworld();
                    var dummy = EntityType.HUSK.create(level, EntitySpawnReason.COMMAND);
                    dummy.setNoAi(true); dummy.setPersistenceRequired();
                    dummy.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000); dummy.setHealth(1000);
                    dummy.setPos(.5,100,3.3); dummy.setYRot(180); dummy.yBodyRot = 180; dummy.yHeadRot = 180;
                    level.addFreshEntity(dummy); target = dummy.getId();
                }).ticks(2)
                .step("第一人称", ctx -> camera(CameraType.FIRST_PERSON)).ticks(3).screenshot("idle_first_person")
                .step("第一人称连击", ctx -> Minecraft.getInstance().options.keyAttack.setDown(true))
                .waitUntil("第一人称直刺", ctx -> phase("thrust_1")).ticks(3).screenshot("thrust_1_first_person")
                .waitUntil("第一人称横扫", ctx -> phase("sweep_2")).ticks(4).screenshot("sweep_2_first_person")
                .waitUntil("第一人称过肩刺", ctx -> phase("overhand_3")).ticks(5).screenshot("overhand_3_first_person")
                .waitUntil("第一人称上挑", ctx -> phase("launch_4")).ticks(8).screenshot("launch_4_first_person")
                .step("松开左键", ctx -> Minecraft.getInstance().options.keyAttack.setDown(false))
                .waitUntil("回到待机", ctx -> phase("idle"))
                .waitUntil("木桩落回原处", ctx -> true).ticks(40)
                .checkServer("挑飞的木桩已落回（原本关着 AI 也会放回地面）", ctx -> !Airborne.is(dummy(ctx.server()))
                        && Math.abs(dummy(ctx.server()).getY() - 100) < 0.05)
                .step("第三人称连击（背后）", ctx -> camera(CameraType.THIRD_PERSON_BACK))
                .server("记录木桩", ctx -> { health = dummy(ctx.server()).getHealth(); baseY = dummy(ctx.server()).getY(); })
                .step("按住左键", ctx -> Minecraft.getInstance().options.keyAttack.setDown(true))
                .waitUntil("直刺", ctx -> phase("thrust_1")).ticks(3).screenshot("thrust_1")
                .waitUntil("横扫", ctx -> phase("sweep_2")).ticks(4).screenshot("sweep_2")
                .waitUntil("过肩刺", ctx -> phase("overhand_3")).ticks(5).screenshot("overhand_3")
                // 第四击一开始就松开左键，不再接回第一击；挑中后马上按住右键接连刺。
                .waitUntil("上挑", ctx -> phase("launch_4"))
                .step("松开左键", ctx -> Minecraft.getInstance().options.keyAttack.setDown(false))
                .ticks(9).screenshot("launch_4").ticks(3)
                .checkServer("连击造成伤害", ctx -> dummy(ctx.server()).getHealth() < health)
                .checkServer("上挑把木桩挑上半空", ctx -> Airborne.is(dummy(ctx.server())) && dummy(ctx.server()).getY() > baseY + 0.8)
                .screenshot("launched")
                .step("按住右键", ctx -> hold(true))
                .waitUntil("连刺", ctx -> phase("flurry_a")).ticks(4).screenshot("flurry_a")
                .check("刺到头时枪尖冲出残影", ctx -> !SpearThrustGhosts.ghosts().isEmpty())
                // 从背后看枪被身子挡住；转身一帧从侧面看连刺与枪尖残影（残影按身体朝向算出的枪尖放出）。
                .step("侧身", ctx -> {var p=Minecraft.getInstance().player;p.yBodyRot=-75;p.yBodyRotO=-75;}).ticks(1).screenshot("flurry_side")
                .step("侧身", ctx -> {var p=Minecraft.getInstance().player;p.yBodyRot=-75;p.yBodyRotO=-75;}).ticks(1).screenshot("flurry_side_2")
                .step("侧身", ctx -> {var p=Minecraft.getInstance().player;p.yBodyRot=-75;p.yBodyRotO=-75;}).ticks(1).screenshot("flurry_side_3")
                .step("恢复身体朝向", ctx -> {var p=Minecraft.getInstance().player;p.yBodyRot=0;p.yBodyRotO=0;})
                .waitUntil("第二段连刺", ctx -> phase("flurry_b"))
                // 挑飞本身只有 1.6 秒；这时已经过了，是连刺一直在延长。
                .checkServer("连刺维持着浮空", ctx -> Airborne.is(dummy(ctx.server())))
                .waitUntil("第三段连刺", ctx -> phase("flurry_c")).ticks(6).screenshot("flurry_c")
                .server("记录木桩", ctx -> {
                    health = dummy(ctx.server()).getHealth(); watched = target; fallDamage = 0;
                    if (!listening) { listening = true; net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(
                            (net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Post e) -> {
                                if (e.getEntity().getId() == watched && e.getSource().is(DamageTypes.FALL)) fallDamage += e.getInflictedDamage();
                            }); }
                })
                .waitUntil("转枪下砸", ctx -> phase("slam")).ticks(5).screenshot("slam_spin").ticks(4).screenshot("slam_raise")
                .ticks(6).screenshot("slam_impact")
                .checkServer("下砸结束浮空、砸回地面", ctx -> !Airborne.is(dummy(ctx.server())) && Math.abs(dummy(ctx.server()).getY() - baseY) < 0.05)
                .server("记录下砸结果", ctx -> org.slf4j.LoggerFactory.getLogger("OathSpearView")
                        .info("下砸后：生命 {} → {}，其中摔落 {}", health, dummy(ctx.server()).getHealth(), fallDamage))
                // 玩家体型：摔落距离 12（下砸额外）+ 约 1.9（浮空高度）− 3（安全距离）≈ 11。
                .checkServer("受到原版摔落伤害", ctx -> fallDamage >= 8)
                .step("松开右键", ctx -> hold(false)).waitUntil("回到待机", ctx -> phase("idle"))
                .step("第一人称连刺", ctx -> { camera(CameraType.FIRST_PERSON); hold(true); })
                .waitUntil("第一人称连刺开始", ctx -> phase("flurry_a")).ticks(5).screenshot("flurry_first_person")
                .ticks(3).screenshot("flurry_first_person_2")
                .step("松开右键", ctx -> hold(false)).waitUntil("回到待机", ctx -> phase("idle"))
                .server("移除木桩", ctx -> { var e=ctx.server().overworld().getEntity(target); if(e!=null)e.discard(); })
                .step("第三人称看收枪", ctx -> { camera(CameraType.THIRD_PERSON_FRONT); Minecraft.getInstance().player.getInventory().setSelectedSlot(2); })
                .waitUntil("收枪同步", ctx -> phase("sheathe")).ticks(8).screenshot("sheathe_spin")
                .ticks(12).screenshot("sheathe_back").ticks(6).screenshot("sheathe_release")
                .waitUntil("收枪结束恢复原版", ctx -> LongswordClient.pose(Minecraft.getInstance().player.getUUID()) == null)
                .ticks(4).screenshot("sheathed")
                .teardown("恢复输入", ctx -> {
                    var mc=Minecraft.getInstance(); mc.options.keyAttack.setDown(false); mc.options.keyUse.setDown(false);
                    mc.options.hideGui=previousHideGui; camera(CameraType.FIRST_PERSON);
                });
    }
}
