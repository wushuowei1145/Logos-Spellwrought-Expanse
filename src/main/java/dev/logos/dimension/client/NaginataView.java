package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.client.longsword.LongswordClient;
import dev.logos.dimension.item.NaginataItem;
import java.util.Arrays;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * 神狐薙刀的实际按键试玩：拔刀（退刃套）、单手立持、四刀连招（正面、背后、第一人称各一遍）、格挡与受击反应、收刀。
 * 木桩是关了 AI 的尸壳，生命加到 1000；正面那一遍在放木桩之前拍，免得木桩挡镜头。
 */
@LDLRegisterClient(name="naginata", group="naginata", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
public class NaginataView implements UIScenario {
    private boolean previousHideGui;
    private int target;
    private float health;
    private int damage;
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
    private static void attack(boolean down) { Minecraft.getInstance().options.keyAttack.setDown(down); }
    @Override public void configure(ScenarioOptions o) { o.tags("naginata").defaultTimeoutMs(60000).scenarioTimeoutMs(240000); }
    @Override public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set day").runCommand("weather clear").runCommand("difficulty easy")
                .runCommand("gamerule spawn_mobs false").runCommand("gamerule spawn_monsters false")
                .step("设置视口", ctx -> {
                    var mc = Minecraft.getInstance(); previousHideGui = mc.options.hideGui;
                    mc.options.hideGui = false; mc.player.getInventory().setSelectedSlot(0);
                    camera(CameraType.THIRD_PERSON_FRONT); mc.options.fov().set(70);
                    var w = mc.getWindow(); w.setWindowed(1280,720); w.setWidth(1280); w.setHeight(720);
                    mc.getMainRenderTarget().resize(1280,720); mc.gameRenderer.resize(1280,720); mc.resizeGui();
                }).server("设置练武场", ctx -> {
                    var level = ctx.server().overworld();
                    for (int x=-8;x<=8;x++) for (int z=-8;z<=10;z++) {
                        level.setBlockAndUpdate(new BlockPos(x,99,z), Blocks.STONE_BRICKS.defaultBlockState());
                        for (int y=100;y<110;y++) level.removeBlock(new BlockPos(x,y,z),false);
                    }
                    DimensionTravel.teleport(ctx.player(),level,new Vec3(.5,100,.5),0,0);
                    ctx.player().getInventory().clearContent(); ctx.player().getInventory().setSelectedSlot(0);
                    ctx.player().setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(NaginataItem.ALL.getFirst().get()));
                }).setGameMode(GameType.SURVIVAL)
                .waitUntil("拔刀同步", ctx -> phase("draw")).ticks(5).screenshot("draw_across")
                .ticks(5).screenshot("draw_pull").ticks(5).screenshot("draw_hip")
                .waitUntil("待机", ctx -> phase("idle")).ticks(4).screenshot("idle_third_person")
                .step("观察侧面", ctx -> {var p=Minecraft.getInstance().player;p.yBodyRot=70;p.yBodyRotO=70;}).ticks(1).screenshot("idle_side")
                .step("恢复身体朝向", ctx -> {var p=Minecraft.getInstance().player;p.yBodyRot=0;p.yBodyRotO=0;})
                // 正面看一整套（木桩还没放，不挡镜头）：举刀、砍下去、收招回单手立持。
                .step("正面连招", ctx -> attack(true))
                .waitUntil("袈裟", ctx -> phase("kesa_1")).ticks(3).screenshot("kesa_1_front_raise").ticks(4).screenshot("kesa_1_front")
                .waitUntil("逆袈裟", ctx -> phase("kaeshi_2")).ticks(3).screenshot("kaeshi_2_front").ticks(4).screenshot("kaeshi_2_front_end")
                .waitUntil("脛払い", ctx -> phase("sune_3")).ticks(4).screenshot("sune_3_front_over").ticks(4).screenshot("sune_3_front")
                .waitUntil("面", ctx -> phase("men_4"))
                .step("松开左键", ctx -> attack(false))
                .ticks(5).screenshot("men_4_front_raise").ticks(5).screenshot("men_4_front").ticks(5).screenshot("men_4_front_zanshin")
                .waitUntil("收回单手立持", ctx -> phase("idle")).ticks(3).screenshot("idle_after_combo")
                .step("背后看待机", ctx -> camera(CameraType.THIRD_PERSON_BACK)).ticks(2).screenshot("idle_back")
                .server("放木桩", ctx -> {
                    var level = ctx.server().overworld();
                    var dummy = EntityType.HUSK.create(level, EntitySpawnReason.COMMAND);
                    dummy.setNoAi(true); dummy.setPersistenceRequired();
                    dummy.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000); dummy.setHealth(1000);
                    dummy.setPos(.5,100,3.3); dummy.setYRot(180); dummy.yBodyRot = 180; dummy.yHeadRot = 180;
                    level.addFreshEntity(dummy); target = dummy.getId();
                }).ticks(2)
                .step("第一人称", ctx -> camera(CameraType.FIRST_PERSON)).ticks(3).screenshot("idle_first_person")
                .step("换开再换回看第一人称拔刀、收刀", ctx -> Minecraft.getInstance().player.getInventory().setSelectedSlot(2))
                .waitUntil("第一人称收刀", ctx -> phase("sheathe")).ticks(10).screenshot("sheathe_first_person_cover")
                .waitUntil("收刀结束", ctx -> LongswordClient.pose(Minecraft.getInstance().player.getUUID()) == null)
                .step("换回薙刀", ctx -> Minecraft.getInstance().player.getInventory().setSelectedSlot(0))
                .waitUntil("第一人称拔刀", ctx -> phase("draw")).ticks(5).screenshot("draw_first_person_across").ticks(5).screenshot("draw_first_person_pull")
                .waitUntil("第一人称待机", ctx -> phase("idle"))
                .step("第一人称连招", ctx -> attack(true))
                .waitUntil("第一人称袈裟", ctx -> phase("kesa_1")).ticks(3).screenshot("kesa_1_first_person_raise").ticks(4).screenshot("kesa_1_first_person")
                .waitUntil("第一人称逆袈裟", ctx -> phase("kaeshi_2")).ticks(4).screenshot("kaeshi_2_first_person")
                .waitUntil("第一人称脛払い", ctx -> phase("sune_3")).ticks(8).screenshot("sune_3_first_person")
                .waitUntil("第一人称面", ctx -> phase("men_4")).ticks(6).screenshot("men_4_first_person_raise").ticks(5).screenshot("men_4_first_person")
                .step("松开左键", ctx -> attack(false))
                .waitUntil("回到待机", ctx -> phase("idle"))
                .step("第一人称格挡", ctx -> hold(true)).waitUntil("第一人称架起", ctx -> phase("guard")).ticks(3).screenshot("guard_first_person")
                .step("松开右键", ctx -> hold(false)).waitUntil("回到待机", ctx -> phase("idle"))
                .step("第三人称连招（背后）", ctx -> camera(CameraType.THIRD_PERSON_BACK))
                .server("记录木桩", ctx -> health = dummy(ctx.server()).getHealth())
                .step("按住左键", ctx -> attack(true))
                .waitUntil("袈裟", ctx -> phase("kesa_1")).ticks(6).screenshot("kesa_1")
                .waitUntil("逆袈裟", ctx -> phase("kaeshi_2")).ticks(4).screenshot("kaeshi_2")
                .waitUntil("脛払い", ctx -> phase("sune_3")).ticks(8).screenshot("sune_3")
                .waitUntil("面", ctx -> phase("men_4"))
                .step("松开左键", ctx -> attack(false))
                .ticks(10).screenshot("men_4")
                .checkServer("四刀连招打中木桩", ctx -> dummy(ctx.server()).getHealth() < health)
                .waitUntil("回到待机", ctx -> phase("idle"))
                .step("回到正面镜头", ctx -> camera(CameraType.THIRD_PERSON_FRONT))
                .server("移除木桩", ctx -> { var e = ctx.server().overworld().getEntity(target); if (e != null) e.discard(); })
                // 格挡：按下右键立刻架起，正在进行的连招被打断；被击中有受击反应，并且照常消耗耐久。
                .step("出第一刀", ctx -> attack(true)).waitUntil("袈裟出手", ctx -> phase("kesa_1")).step("松开左键", ctx -> attack(false)).ticks(2)
                .step("按住右键", ctx -> hold(true))
                .waitUntil("立即架起", ctx -> phase("guard")).ticks(3).screenshot("guard")
                .check("架起后在格挡", ctx -> Minecraft.getInstance().player.isBlocking())
                .server("被打一下", ctx -> {
                    health = ctx.player().getHealth(); damage = ctx.player().getMainHandItem().getDamageValue();
                    var level = ctx.server().overworld();
                    var attacker = EntityType.HUSK.create(level, EntitySpawnReason.COMMAND);
                    attacker.setNoAi(true); attacker.setPos(.5, 100, 2.5); level.addFreshEntity(attacker);
                    ctx.player().hurtServer(level, level.damageSources().mobAttack(attacker), 6);
                    attacker.discard();
                })
                .waitUntil("受击反应", ctx -> phase("guard_hit", "guard")).ticks(1).screenshot("guard_hit")
                .checkServer("挡下伤害、照常消耗耐久", ctx -> ctx.player().getHealth() == health && ctx.player().getMainHandItem().getDamageValue() > damage)
                .waitUntil("回到架势", ctx -> phase("guard"))
                .step("侧面看格挡", ctx -> {var p=Minecraft.getInstance().player;p.yBodyRot=70;p.yBodyRotO=70;}).ticks(1).screenshot("guard_side")
                .step("松开右键", ctx -> hold(false)).waitUntil("回到待机", ctx -> phase("idle"))
                .step("恢复身体朝向", ctx -> {var p=Minecraft.getInstance().player;p.yBodyRot=0;p.yBodyRotO=0;})
                .server("魔力缠绕", ctx -> dev.logos.runtime.ManaWrap.wrap(ctx.player(),dev.logos.lang.Element.FIRE,4,600))
                .ticks(10).screenshot("idle_wrap")
                .check("魔力缠绕产生表面", ctx -> dev.logos.client.SurfaceAuras.emitters() > 0)
                .step("换到别的物品栏看收刀", ctx -> Minecraft.getInstance().player.getInventory().setSelectedSlot(2))
                .waitUntil("收刀同步", ctx -> phase("sheathe")).ticks(8).screenshot("sheathe_wheel")
                .ticks(10).screenshot("sheathe_cover").ticks(5).screenshot("sheathe_seat").ticks(4).screenshot("sheathe_stand")
                .waitUntil("收刀结束恢复原版", ctx -> LongswordClient.pose(Minecraft.getInstance().player.getUUID()) == null)
                .ticks(4).screenshot("sheathed")
                .teardown("恢复输入", ctx -> {
                    var mc=Minecraft.getInstance(); mc.options.keyAttack.setDown(false); mc.options.keyUse.setDown(false);
                    mc.options.hideGui=previousHideGui; camera(CameraType.FIRST_PERSON);
                });
    }
}
