package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.client.longsword.LongswordClient;
import dev.logos.dimension.item.RyukaItem;
import dev.logos.dimension.ryuka.Ryuka;
import dev.logos.runtime.PlayerMagic;
import java.util.Arrays;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * 流火的实际按键试玩（docs/RYUKA.md）：凝聚、五段连斩（第一、第三人称）、立即格挡、0.5 秒内弹刀与突刺、
 * 魔力护盾（弹开斧头不花魔力、硬挡多付、魔法也挡、魔力不够才破防；精确数值见世界测试 longsword_ryuka_guard）、无形之刃穿盾、
 * 花散、魔力耗尽只剩刀柄再凝聚、切走散去。数值用服务端检查，外观看截图。
 */
@LDLRegisterClient(name="ryuka", group="ryuka", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
public class RyukaView implements UIScenario {
    private boolean previousHideGui;
    private int target, shielded;
    private float health, playerHealth;
    private double mana;
    private boolean blockingAtHit, guardKept;
    private double guardCost;
    private boolean phase(String... moves) {
        var p = LongswordClient.pose(Minecraft.getInstance().player.getUUID());
        return p != null && Arrays.asList(moves).contains(p.move());
    }
    private double regenerationBase = -1;
    /** Logos 的魔力回复属性：设成 {@code value}；传 -1 恢复原值。 */
    private void regeneration(com.lowdragmc.lowdraglib2.uitest.ServerContext ctx, double value) {
        var type = net.minecraft.core.registries.BuiltInRegistries.ATTRIBUTE.get(net.minecraft.resources.Identifier.fromNamespaceAndPath("logos", "mana_regeneration")).orElseThrow();
        var attribute = ctx.player().getAttribute(type);
        if (value >= 0) { regenerationBase = attribute.getBaseValue(); attribute.setBaseValue(value); }
        else if (regenerationBase >= 0) attribute.setBaseValue(regenerationBase);
    }
    private int pins;
    /** 把魔力压在 0，查过 {@code n} 次后放行（每次查询约一刻）。 */
    private boolean pinned(com.lowdragmc.lowdraglib2.uitest.ServerContext ctx, int n) {
        PlayerMagic.of(ctx.player()).mana = 0;
        if (++pins < n) return false;
        pins = 0;
        return true;
    }
    private LivingEntity golem(net.minecraft.server.MinecraftServer server) { return (LivingEntity) server.overworld().getEntity(target); }
    private static void camera(CameraType type) { Minecraft.getInstance().options.setCameraType(type); }
    private static void hold(boolean attack) { Minecraft.getInstance().options.keyAttack.setDown(attack); }
    @Override public void configure(ScenarioOptions o) { o.tags("ryuka").defaultTimeoutMs(60000).scenarioTimeoutMs(240000); }
    @Override public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set day").runCommand("weather clear").runCommand("difficulty easy")
                .runCommand("gamerule spawn_mobs false")
                .step("设置视口", ctx -> {
                    var mc = Minecraft.getInstance(); previousHideGui = mc.options.hideGui;
                    mc.options.hideGui = false; mc.player.getInventory().setSelectedSlot(0);
                    camera(CameraType.THIRD_PERSON_FRONT); mc.options.fov().set(70);
                    var w = mc.getWindow(); w.setWindowed(1280,720); w.setWidth(1280); w.setHeight(720);
                    mc.getMainRenderTarget().resize(1280,720); mc.gameRenderer.resize(1280,720); mc.resizeGui();
                }).server("设置练刀场与木桩", ctx -> {
                    var level = ctx.server().overworld();
                    for (int x=-7;x<=7;x++) for (int z=-7;z<=8;z++) {
                        level.setBlockAndUpdate(new BlockPos(x,99,z), Blocks.STONE_BRICKS.defaultBlockState());
                        for (int y=100;y<108;y++) level.removeBlock(new BlockPos(x,y,z),false);
                    }
                    DimensionTravel.teleport(ctx.player(),level,new Vec3(.5,100,.5),0,0);
                    // 隐身的铁傀儡当木桩：血量给足，整组的伤害都打不死它
                    var golem = EntityType.IRON_GOLEM.create(level, EntitySpawnReason.COMMAND);
                    golem.setNoAi(true); golem.setNoGravity(true);
                    golem.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000); golem.setHealth(1000);
                    golem.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.INVISIBILITY,7200,0,false,false));
                    golem.setPos(.5,100,2.8); level.addFreshEntity(golem); target = golem.getId();
                    var magic = PlayerMagic.of(ctx.player()); magic.baseMaxMana = Math.max(magic.baseMaxMana, 3000); magic.mana = 3000;
                    // 流火放在第 2 格，先空手；切过去那一刻才开始凝聚，截图不会错过
                    ctx.player().getInventory().clearContent();
                    ctx.player().getInventory().setItem(1, new ItemStack(RyukaItem.ITEM.get()));
                }).setGameMode(GameType.SURVIVAL).ticks(10)
                .step("切到流火", ctx -> Minecraft.getInstance().player.getInventory().setSelectedSlot(1))
                .waitUntil("凝聚同步", ctx -> phase("manifest")).ticks(5).screenshot("manifest_third_person")
                .ticks(5).screenshot("manifest_late")
                .waitUntil("待机", ctx -> phase("idle")).ticks(6).screenshot("idle_third_person")
                .check("刀身燃起狐火", ctx -> dev.logos.dimension.client.longsword.RyukaClient.pinkFires() > 0)
                .step("观察侧面", ctx -> {var p=Minecraft.getInstance().player;p.yBodyRot=60;p.yBodyRotO=60;}).ticks(1).screenshot("idle_side")
                .step("恢复身体朝向", ctx -> {var p=Minecraft.getInstance().player;p.yBodyRot=0;p.yBodyRotO=0;})
                .checkServer("拿着就耗魔力", ctx -> PlayerMagic.of(ctx.player()).mana < 3000)
                // ---- 第三人称五段连斩
                .server("记录木桩与魔力", ctx -> { health = golem(ctx.server()).getHealth(); mana = PlayerMagic.of(ctx.player()).mana; })
                .step("按住左键连招", ctx -> hold(true))
                .waitUntil("一", ctx -> phase("slash_1")).ticks(3).screenshot("slash_1")
                .waitUntil("二", ctx -> phase("slash_2")).ticks(3).screenshot("slash_2")
                .waitUntil("三", ctx -> phase("slash_3")).ticks(3).screenshot("slash_3")
                .waitUntil("四", ctx -> phase("slash_4")).ticks(4).screenshot("slash_4")
                .waitUntil("五", ctx -> phase("slash_5")).ticks(5).screenshot("slash_5")
                .ticks(3).screenshot("slash_5_wave")
                .step("松开左键", ctx -> hold(false))
                .waitUntil("回到待机", ctx -> phase("idle"))
                .checkServer("连斩打出伤害", ctx -> golem(ctx.server()).getHealth() < health)
                .checkServer("伤害是魔法而不是近战", ctx -> golem(ctx.server()).getLastDamageSource() != null
                        && !golem(ctx.server()).getLastDamageSource().is(DamageTypes.PLAYER_ATTACK))
                .checkServer("Logos 的奥秘命中特效换成了樱花", ctx -> Ryuka.quietedCount() > 0)
                // 五刀各 10 点、终式刀气 30 点；维持费与 Logos 的自然回复只差几点
                .checkServer("五刀与刀气扣了魔力", ctx -> mana - PlayerMagic.of(ctx.player()).mana >= 70)
                // ---- 第一人称
                .step("第一人称连招", ctx -> { camera(CameraType.FIRST_PERSON); hold(true); })
                .waitUntil("第一人称一", ctx -> phase("slash_1")).ticks(3).screenshot("slash_1_first_person")
                .waitUntil("第一人称二", ctx -> phase("slash_2")).ticks(3).screenshot("slash_2_first_person")
                .waitUntil("第一人称四", ctx -> phase("slash_4")).ticks(4).screenshot("slash_4_first_person")
                .waitUntil("第一人称五", ctx -> phase("slash_5")).ticks(5).screenshot("slash_5_first_person")
                .step("松开左键", ctx -> hold(false))
                .waitUntil("回到待机", ctx -> phase("idle")).ticks(4).screenshot("idle_first_person")
                // ---- 格挡、0.5 秒内弹刀、突刺
                // 弹刀窗口 0.5 秒（打刀 0.3 秒）的逐刻边界由单元测试 RyukaTest 检查。这里在服务端一进入格挡的那一刻就挨打：
                // 场景每一步都要等一会儿，从客户端绕一圈再打会超过 0.5 秒。左键先按住，弹刀后马上接突刺。
                // 格挡中按住左键会转成平一文字の構え（docs/RYUKA.md 3.3 节），所以弹刀之后再按左键接突刺
                .step("第三人称格挡", ctx -> {
                    camera(CameraType.THIRD_PERSON_FRONT);
                    var mc = Minecraft.getInstance(); mc.options.keyUse.setDown(true); mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                }).server("木桩拿起斧头", ctx -> golem(ctx.server()).setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_AXE)))
                .waitUntilServer("一进入格挡就被木桩的斧头砍中", ctx -> {
                    if (!"guard".equals(dev.logos.dimension.longsword.LongswordCombat.move(ctx.player()))) return false;
                    blockingAtHit = ctx.player().isBlocking();
                    health = golem(ctx.server()).getHealth();
                    playerHealth = ctx.player().getHealth();
                    mana = PlayerMagic.of(ctx.player()).mana;
                    ctx.player().hurtServer(ctx.server().overworld(), ctx.server().overworld().damageSources().mobAttack(golem(ctx.server())), 6);
                    guardCost = mana - PlayerMagic.of(ctx.player()).mana;
                    guardKept = ctx.player().isUsingItem();
                    return true;
                })
                .waitUntil("弹刀", ctx -> phase("parry", "riposte")).step("弹刀后按左键", ctx -> hold(true))
                .waitUntil("突刺", ctx -> phase("riposte")).screenshot("parry_riposte")
                .check("按下当刻已在格挡（无举盾延迟）", ctx -> blockingAtHit)
                .check("弹开斧头不破防、不花魔力", ctx -> guardKept && guardCost == 0)
                .step("松开左键", ctx -> hold(false))
                .checkServer("弹刀挡下伤害", ctx -> ctx.player().getHealth() == playerHealth)
                .waitUntil("突刺后回到格挡", ctx -> phase("guard")).ticks(4)
                .checkServer("突刺命中", ctx -> golem(ctx.server()).getHealth() < health)
                .step("第一人称格挡", ctx -> camera(CameraType.FIRST_PERSON)).ticks(3).screenshot("guard_first_person")
                .step("第三人称格挡", ctx -> camera(CameraType.THIRD_PERSON_FRONT)).ticks(14).screenshot("guard_third_person")
                .server("格挡超过 0.5 秒后被斧头砍中", ctx -> {
                    playerHealth = ctx.player().getHealth();
                    mana = PlayerMagic.of(ctx.player()).mana;
                    ctx.player().invulnerableTime = 0;
                    ctx.player().hurtServer(ctx.server().overworld(), ctx.server().overworld().damageSources().mobAttack(golem(ctx.server())), 6);
                    guardCost = mana - PlayerMagic.of(ctx.player()).mana;
                    guardKept = ctx.player().isUsingItem();
                }).ticks(2)
                .check("超过 0.5 秒只格挡、不弹刀", ctx -> phase("guard"))
                .checkServer("普通格挡挡下伤害", ctx -> ctx.player().getHealth() == playerHealth)
                // 简单难度下生物的 6 点伤害打到玩家身上是 4 点，扣 5；斧头破盾 5 秒另付 20
                .check("硬挡斧头多付魔力、不破防", ctx -> guardKept && Math.abs(guardCost - 25) < 1e-6)
                .server("魔法伤害", ctx -> {
                    golem(ctx.server()).setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    playerHealth = ctx.player().getHealth();
                    ctx.player().invulnerableTime = 0;
                    ctx.player().hurtServer(ctx.server().overworld(), ctx.server().overworld().damageSources().indirectMagic(golem(ctx.server()), golem(ctx.server())), 4);
                })
                .checkServer("魔法也挡得住", ctx -> ctx.player().getHealth() == playerHealth)
                .server("魔力不够时被斧头砍中", ctx -> {
                    golem(ctx.server()).setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_AXE));
                    PlayerMagic.of(ctx.player()).mana = 8;
                    ctx.player().invulnerableTime = 0;
                    ctx.player().hurtServer(ctx.server().overworld(), ctx.server().overworld().damageSources().mobAttack(golem(ctx.server())), 6);
                    PlayerMagic.of(ctx.player()).mana = 3000;
                })
                .checkServer("魔力不够才破防", ctx -> !ctx.player().isUsingItem() && ctx.player().getCooldowns().isOnCooldown(ctx.player().getMainHandItem())
                        && ctx.player().getHealth() == playerHealth)
                .ticks(2).screenshot("guard_broken")
                .step("松开右键", ctx -> {
                    var mc = Minecraft.getInstance(); mc.options.keyUse.setDown(false); mc.gameMode.releaseUsingItem(mc.player);
                }).waitUntil("回到待机", ctx -> phase("idle"))
                // ---- 无形之刃：举盾的僵尸挡得住原版攻击，挡不住流火
                .server("举盾的僵尸", ctx -> {
                    var level = ctx.server().overworld();
                    var zombie = EntityType.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
                    zombie.setNoAi(true); zombie.setPos(2.5, 100, 2.5);
                    zombie.setYRot(135); zombie.setYHeadRot(135); zombie.yBodyRot = 135;
                    zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
                    zombie.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
                    level.addFreshEntity(zombie); shielded = zombie.getId();
                    zombie.startUsingItem(InteractionHand.OFF_HAND);
                }).ticks(8)
                .checkServer("僵尸举着盾", ctx -> ((LivingEntity) ctx.server().overworld().getEntity(shielded)).isBlocking())
                .checkServer("流火穿过盾牌", ctx -> {
                    var zombie = (LivingEntity) ctx.server().overworld().getEntity(shielded);
                    float before = zombie.getHealth();
                    zombie.invulnerableTime = 0;
                    Ryuka.strike(ctx.player(), zombie, Ryuka.DAMAGE);
                    return zombie.getHealth() < before;
                })
                // ---- 花散
                .checkServer("斩杀化作花瓣", ctx -> {
                    var zombie = (LivingEntity) ctx.server().overworld().getEntity(shielded);
                    zombie.invulnerableTime = 0;
                    Ryuka.strike(ctx.player(), zombie, 200);
                    return zombie.isDeadOrDying();
                }).ticks(6)
                .check("客户端在放花散", ctx -> dev.logos.dimension.client.dissolve.Dissolves.active() > 0).screenshot("blossom")
                .ticks(30)
                // ---- 魔力耗尽：刀身散去只剩刀柄，出刀无效；回到 20 点重新凝聚
                // Logos 每刻自然回复 0.3 点魔力（每秒 6 点），比维持费快：先关掉回复，模拟一路砍空了魔力
                .server("关掉魔力回复、魔力清零", ctx -> { regeneration(ctx, 0); PlayerMagic.of(ctx.player()).mana = 0; })
                .waitUntilServer("刀身散去", ctx -> Ryuka.faded(ctx.player().getMainHandItem()))
                .waitUntilServer("散去中", ctx -> pinned(ctx, 6)).screenshot("fading")
                .waitUntilServer("散完", ctx -> pinned(ctx, 16)).screenshot("hilt_only")
                // 前面连砍攒满过狐火印、爆开后木桩还在狐火缠身（每秒烧 1 点）：灭掉再记，才看得出出刀有没有伤害
                .server("灭掉缠身、记录木桩", ctx -> {
                    var golem = golem(ctx.server());
                    golem.removeData(dev.logos.dimension.foxfire.FoxfireAttachments.BURN);
                    dev.logos.dimension.ryuka.FoxMarks.clear(golem);
                    health = golem.getHealth();
                })
                .step("只剩刀柄时左键", ctx -> hold(true))
                .waitUntilServer("按住左键一会儿", ctx -> pinned(ctx, 10)).step("松开左键", ctx -> hold(false))
                .check("只剩刀柄不出刀", ctx -> phase("idle"))
                .checkServer("没有伤害", ctx -> golem(ctx.server()).getHealth() == health)
                .server("魔力回到 30", ctx -> PlayerMagic.of(ctx.player()).mana = 30)
                .waitUntilServer("刀身重新凝聚", ctx -> !Ryuka.faded(ctx.player().getMainHandItem())).ticks(6).screenshot("regrowing")
                .ticks(14).screenshot("regrown")
                // ---- 切走：散去成花瓣
                .server("恢复魔力回复、补满魔力", ctx -> { regeneration(ctx, -1); PlayerMagic.of(ctx.player()).mana = 3000; })
                .step("切走物品", ctx -> Minecraft.getInstance().player.getInventory().setSelectedSlot(2))
                .waitUntil("散去同步", ctx -> phase("dissipate")).ticks(4).screenshot("dissipate")
                .ticks(4).screenshot("dissipate_late")
                .waitUntil("散去结束恢复原版", ctx -> LongswordClient.pose(Minecraft.getInstance().player.getUUID()) == null)
                // ---- 第一人称凝聚
                .step("第一人称切回", ctx -> { camera(CameraType.FIRST_PERSON); Minecraft.getInstance().player.getInventory().setSelectedSlot(1); })
                .waitUntil("凝聚同步", ctx -> phase("manifest")).ticks(7).screenshot("manifest_first_person")
                .waitUntil("待机", ctx -> phase("idle"))
                .server("移除木桩", ctx -> { var e=ctx.server().overworld().getEntity(target); if(e!=null)e.discard(); })
                .teardown("恢复输入", ctx -> {
                    var mc=Minecraft.getInstance(); mc.options.keyAttack.setDown(false); mc.options.keyUse.setDown(false);
                    mc.options.hideGui=previousHideGui; camera(CameraType.FIRST_PERSON);
                });
    }
}
