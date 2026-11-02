package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.client.longsword.LongswordClient;
import dev.logos.dimension.item.UchigatanaItem;
import java.util.Arrays;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** 神狐打刀的实际按键试玩：拔刀、四段连招、立即格挡、刚架刀被击中弹刀、突刺、收刀转刀，第一与第三人称截图。 */
@LDLRegisterClient(name="uchigatana", group="uchigatana", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
public class UchigatanaView implements UIScenario {
    private boolean previousHideGui;
    private int target, durability;
    private float health, playerHealth;
    private boolean phase(String... moves) {
        var p = LongswordClient.pose(Minecraft.getInstance().player.getUUID());
        return p != null && Arrays.asList(moves).contains(p.move());
    }
    private LivingEntity golem(net.minecraft.server.MinecraftServer server) { return (LivingEntity) server.overworld().getEntity(target); }
    private static void camera(CameraType type) { Minecraft.getInstance().options.setCameraType(type); }
    @Override public void configure(ScenarioOptions o) { o.tags("uchigatana").defaultTimeoutMs(60000).scenarioTimeoutMs(180000); }
    @Override public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set day").runCommand("weather clear").runCommand("difficulty easy")
                .step("设置视口", ctx -> {
                    var mc = Minecraft.getInstance(); previousHideGui = mc.options.hideGui;
                    // 隐藏界面（F1）时原版不画第一人称的手，所以保留界面。
                    mc.options.hideGui = false; mc.player.getInventory().setSelectedSlot(0);
                    camera(CameraType.FIRST_PERSON); mc.options.fov().set(70);
                    var w = mc.getWindow(); w.setWindowed(1280,720); w.setWidth(1280); w.setHeight(720);
                    mc.getMainRenderTarget().resize(1280,720); mc.gameRenderer.resize(1280,720); mc.resizeGui();
                }).server("设置练刀场与木桩", ctx -> {
                    var level = ctx.server().overworld();
                    for (int x=-7;x<=7;x++) for (int z=-7;z<=8;z++) {
                        level.setBlockAndUpdate(new BlockPos(x,99,z), Blocks.STONE_BRICKS.defaultBlockState());
                        for (int y=100;y<108;y++) level.removeBlock(new BlockPos(x,y,z),false);
                    }
                    DimensionTravel.teleport(ctx.player(),level,new Vec3(.5,100,.5),0,0);
                    var golem = EntityType.IRON_GOLEM.create(level, EntitySpawnReason.COMMAND);
                    golem.setNoAi(true); golem.setNoGravity(true);
                    golem.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.INVISIBILITY,7200,0,false,false));
                    golem.setPos(.5,100,2.8); level.addFreshEntity(golem); target = golem.getId();
                    ctx.player().getInventory().clearContent(); ctx.player().getInventory().setSelectedSlot(0);
                    ctx.player().setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(UchigatanaItem.INK.get()));
                    // 魔力缠绕按物品的立体模型表面定位，打刀的立体模型与刀网格一致。
                    dev.logos.runtime.ManaWrap.wrap(ctx.player(),dev.logos.lang.Element.FIRE,4,600);
                }).setGameMode(GameType.SURVIVAL)
                .waitUntil("拔刀同步", ctx -> phase("draw")).ticks(6).screenshot("draw_first_person")
                .waitUntil("待机", ctx -> phase("idle")).ticks(4).screenshot("idle_first_person")
                .check("第一人称魔力缠绕产生表面", ctx -> dev.logos.client.SurfaceAuras.emitters() > 0)
                .step("第三人称正面", ctx -> camera(CameraType.THIRD_PERSON_FRONT)).ticks(3).screenshot("idle_third_person")
                .check("第三人称魔力缠绕仍在刀上", ctx -> dev.logos.client.SurfaceAuras.emitters() > 0)
                .step("观察侧面", ctx -> {var p=Minecraft.getInstance().player;p.yBodyRot=60;p.yBodyRotO=60;}).ticks(1).screenshot("idle_side")
                .step("恢复身体朝向", ctx -> {var p=Minecraft.getInstance().player;p.yBodyRot=0;p.yBodyRotO=0;})
                .step("第一人称连招", ctx -> { camera(CameraType.FIRST_PERSON); Minecraft.getInstance().options.keyAttack.setDown(true); })
                .waitUntil("第一人称第一刀", ctx -> phase("slash_1")).ticks(3).screenshot("slash_1_first_person")
                .waitUntil("第一人称第二刀", ctx -> phase("slash_2")).ticks(3).screenshot("slash_2_first_person")
                .waitUntil("第一人称第三刀", ctx -> phase("slash_3")).ticks(4).screenshot("slash_3_first_person")
                .waitUntil("第一人称第四刀", ctx -> phase("slash_4")).ticks(7).screenshot("slash_4_first_person")
                .step("松开左键回待机", ctx -> Minecraft.getInstance().options.keyAttack.setDown(false))
                .waitUntil("回到待机", ctx -> phase("idle")).step("第三人称连招", ctx -> camera(CameraType.THIRD_PERSON_FRONT))
                .server("记录木桩生命", ctx -> health = golem(ctx.server()).getHealth())
                .step("按住左键连招", ctx -> Minecraft.getInstance().options.keyAttack.setDown(true))
                .waitUntil("第一刀", ctx -> phase("slash_1")).ticks(3).screenshot("slash_1")
                .waitUntil("第二刀", ctx -> phase("slash_2")).ticks(3).screenshot("slash_2")
                .waitUntil("第三刀", ctx -> phase("slash_3")).ticks(4).screenshot("slash_3")
                .waitUntil("第四刀", ctx -> phase("slash_4")).ticks(7).screenshot("slash_4")
                .step("松开左键", ctx -> Minecraft.getInstance().options.keyAttack.setDown(false))
                .checkServer("连招造成伤害", ctx -> golem(ctx.server()).getHealth() < health)
                .step("连招中按右键", ctx -> {
                    var mc = Minecraft.getInstance(); mc.options.keyAttack.setDown(true);
                }).waitUntil("再次出刀", ctx -> phase("slash_1","slash_2","slash_3","slash_4"))
                .server("记录木桩生命", ctx -> health = golem(ctx.server()).getHealth())
                // 左键一直按着：格挡中不出刀；弹刀一同步到客户端就接突刺（弹刀只有 0.42 秒）。
                .step("出刀中立即格挡", ctx -> {
                    var mc = Minecraft.getInstance(); mc.options.keyUse.setDown(true);
                    mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                }).waitUntil("格挡同步", ctx -> phase("guard"))
                .checkServer("按下当刻已在格挡（无举盾延迟）", ctx -> ctx.player().isBlocking())
                .server("刚架刀时被木桩击中", ctx -> {
                    var golem = golem(ctx.server()); playerHealth = ctx.player().getHealth();
                    durability = ctx.player().getMainHandItem().getDamageValue();
                    ctx.player().hurtServer(ctx.server().overworld(), ctx.server().overworld().damageSources().mobAttack(golem), 6);
                }).waitUntil("弹刀", ctx -> phase("parry")).ticks(1).screenshot("parry")
                .waitUntil("突刺", ctx -> phase("riposte")).ticks(2).screenshot("riposte")
                .step("松开左键", ctx -> Minecraft.getInstance().options.keyAttack.setDown(false))
                // 格挡照常耗耐久（这一下约 5 点），弹刀不耗；突刺命中只按原版扣 1 点。
                .checkServer("弹刀挡下伤害且不耗耐久", ctx -> ctx.player().getHealth() == playerHealth
                        && ctx.player().getMainHandItem().getDamageValue() - durability <= 1)
                .waitUntil("突刺后回到格挡", ctx -> phase("guard")).ticks(8)
                .checkServer("突刺命中", ctx -> golem(ctx.server()).getHealth() < health)
                .step("第一人称格挡", ctx -> camera(CameraType.FIRST_PERSON)).ticks(3).screenshot("guard_first_person")
                .step("第三人称格挡", ctx -> camera(CameraType.THIRD_PERSON_FRONT)).ticks(3).screenshot("guard_third_person")
                .server("格挡后段被击中", ctx -> {
                    playerHealth = ctx.player().getHealth(); durability = ctx.player().getMainHandItem().getDamageValue();
                    ctx.player().hurtServer(ctx.server().overworld(), ctx.server().overworld().damageSources().mobAttack(golem(ctx.server())), 6);
                }).ticks(2)
                .check("超过 0.3 秒只格挡、不弹刀", ctx -> phase("guard"))
                .checkServer("普通格挡挡下伤害、照常消耗耐久", ctx -> ctx.player().getHealth() == playerHealth
                        && ctx.player().getMainHandItem().getDamageValue() > durability)
                .step("松开右键", ctx -> {
                    var mc = Minecraft.getInstance(); mc.options.keyUse.setDown(false); mc.gameMode.releaseUsingItem(mc.player);
                }).waitUntil("回到待机", ctx -> phase("idle"))
                .step("第三人称看收刀", ctx -> { camera(CameraType.THIRD_PERSON_FRONT); Minecraft.getInstance().player.getInventory().setSelectedSlot(2); })
                .waitUntil("收刀同步", ctx -> phase("sheathe")).ticks(9).screenshot("sheathe_spin")
                .ticks(14).screenshot("sheathe_across").ticks(10).screenshot("sheathe_insert")
                .waitUntil("收刀结束恢复原版", ctx -> LongswordClient.pose(Minecraft.getInstance().player.getUUID()) == null)
                .ticks(4).screenshot("sheathed")
                .server("移除木桩", ctx -> { var e=ctx.server().overworld().getEntity(target); if(e!=null)e.discard(); })
                .teardown("恢复输入", ctx -> {
                    var mc=Minecraft.getInstance(); mc.options.keyAttack.setDown(false); mc.options.keyUse.setDown(false);
                    mc.options.hideGui=previousHideGui; camera(CameraType.FIRST_PERSON);
                });
    }
}
