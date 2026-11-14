package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.ServerContext;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import dev.logos.dimension.client.shiraume.ShiraumeClient;
import dev.logos.dimension.client.shiraume.ShiraumeScreen;
import dev.logos.dimension.item.LongswordItem;
import dev.logos.dimension.longsword.LongswordCombat;
import dev.logos.dimension.shiraume.Shiraume;
import dev.logos.dimension.shiraume.ShiraumeBeast;
import dev.logos.dimension.shiraume.ShiraumeCue;
import dev.logos.dimension.shiraume.ShiraumeEntities;
import dev.logos.dimension.shiraume.ShiraumeFight;
import dev.logos.runtime.Casting;
import dev.logos.runtime.PlayerMagic;
import java.util.List;
import java.util.Set;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * 白梅战这一轮的实机场景（本地开发用，docs/SHIRAUME_BOSS.md）：在 {@link ShiraumeViews} 那块白瓷平台上，
 * 血条走远再回来只有一条；缩地的速度线；一字斩破盾；突き清掉两个增益；背后砍她的转身横扫；吃东西时的读指令；
 * 术式打够了被盯上（头顶淡粉的狐火）；变身后 v3 狐形叼刀、大跳落在圈里、跳闪；狐形的斩法扑击、死亡消息、复活后血条还是一条。
 * 每段截图，并检查状态与客户端收到的提示。
 */
@LDLRegisterClient(name = "shiraume_tuning", group = "shiraume", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
public class ShiraumeTuningViews implements UIScenario {
    static final int FLOOR = ShiraumeViews.FLOOR;
    private static long passesBefore;
    private static int applesBefore;

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("shiraume").defaultTimeoutMs(120_000).scenarioTimeoutMs(1_200_000);
    }

    static ServerLevel level(ServerContext ctx) {
        return ctx.server().overworld();
    }

    static Shiraume human(ServerLevel level) {
        return ShiraumeViews.human(level);
    }

    static ShiraumeBeast beast(ServerLevel level) {
        return ShiraumeViews.beast(level);
    }

    /** 客户端屏幕上方的 Boss 血条有几条。 */
    static int bars() {
        try {
            var field = BossHealthOverlay.class.getDeclaredField("events");
            field.setAccessible(true);
            return ((java.util.Map<?, ?>) field.get(Minecraft.getInstance().gui.getBossOverlay())).size();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 把玩家放到 (x, z)、面朝 (lookX, lookZ)。 */
    static void place(ServerContext ctx, double x, double z, double lookX, double lookZ) {
        ServerPlayer p = ctx.player();
        float yaw = (float) Math.toDegrees(Math.atan2(-(lookX - x), lookZ - z));
        p.teleportTo(level(ctx), x, FLOOR + 1, z, Set.of(), yaw, 0, false);
        p.setYHeadRot(yaw);
    }

    /** 她站到 (x, z)、面朝 (lookX, lookZ)。 */
    static void stand(net.minecraft.world.entity.Mob boss, double x, double z, double lookX, double lookZ) {
        float yaw = (float) Math.toDegrees(Math.atan2(-(lookX - x), lookZ - z));
        boss.getNavigation().stop();
        boss.snapTo(x, FLOOR + 1, z, yaw, 0);
        boss.setYHeadRot(yaw);
        boss.setYBodyRot(yaw);
        boss.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
    }

    static long beneficial(ServerPlayer p) {
        return p.getActiveEffects().stream().filter(e -> e.getEffect().value().isBeneficial()).count();
    }

    static void use(boolean down) {
        var mc = Minecraft.getInstance();
        mc.options.keyUse.setDown(down);
        if (down) mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        else mc.gameMode.releaseUsingItem(mc.player);
    }

    static ItemStack castingSword() {
        var sword = new ItemStack(LongswordItem.BLACKSTEEL.get());
        sword.set(net.minecraft.core.component.DataComponents.CONTAINER, net.minecraft.world.item.component.ItemContainerContents.fromItems(List.of(
                dev.logos.item.SpellPages.inscribed(dev.logos.item.PageTier.CRYSTAL, "(spell :name \"蓄\" :mana 40 (mark caster #charged))", null))));
        return sword;
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.SURVIVAL).runCommand("difficulty normal").runCommand("weather clear").runCommand("time set 6000")
                .runCommand("gamerule keep_inventory true")
                .step("camera", ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.options.hideGui = false;
                    mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                    mc.options.renderDistance().set(8);
                    mc.options.fov().set(80);
                    ShiraumeClient.clear();
                })
                .server("arena", ctx -> {
                    ServerLevel level = level(ctx);
                    ShiraumeViews.arena(level);
                    ShiraumeFight.reset(level);
                    ServerPlayer player = ctx.player();
                    place(ctx, .5, -9.5, .5, 6.5);
                    ShiraumeViews.sturdy(player, 400);
                    Shiraume boss = ShiraumeEntities.SHIRAUME.get().create(level, EntitySpawnReason.COMMAND);
                    boss.snapTo(.5, FLOOR + 1, 6.5, 180, 0);
                    boss.setYHeadRot(180);
                    boss.setYBodyRot(180);
                    level.addFreshEntity(boss);
                    ShiraumeFight.adopt(level, boss);
                    boss.setScripted(true);
                    boss.showBar(true, 1);
                })
                .ticks(40)
                // ---- 血条：走远再回来，屏幕上只有一条
                .check("开打时一条血条", ctx -> bars() == 1)
                .server("走远（区块卸载）", ctx -> {
                    level(ctx).setBlockAndUpdate(new BlockPos(4000, FLOOR, 0), Blocks.STONE.defaultBlockState());
                    ctx.player().teleportTo(level(ctx), 4000.5, FLOOR + 1, .5, Set.of(), 0, 0, false);
                })
                .ticks(120)
                .check("走远之后血条收起来了", ctx -> bars() == 0)
                .server("回来", ctx -> place(ctx, .5, -9.5, .5, 6.5))
                .waitUntilServer("她重新载入", ctx -> human(level(ctx)) != null)
                .ticks(60)
                .check("回来以后还是一条血条（不多出一条）", ctx -> bars() == 1)
                .screenshot("tuning_bar_back")
                // ---- 缩地：第一人称看她冲过来，速度线朝她汇聚
                .step("first person", ctx -> {
                    Minecraft.getInstance().options.setCameraType(CameraType.FIRST_PERSON);
                    passesBefore = ShiraumeScreen.passes();
                })
                .server("拉开距离、放开她", ctx -> {
                    Shiraume b = human(level(ctx));
                    place(ctx, .5, -12.5, .5, 6.5);
                    stand(b, .5, 6.5, .5, -12.5);
                    b.setScripted(false);
                })
                .waitUntilServer("缩地", ctx -> human(level(ctx)).move().equals("dash"))
                .ticks(3)
                .screenshot("tuning_dash_speed_lines")
                .check("收到速度感提示、全屏通道跑了", ctx -> ShiraumeClient.received(ShiraumeCue.SPEED) > 0 && ShiraumeScreen.passes() > passesBefore)
                .ticks(30)
                // ---- 一字斩破盾
                .step("third person", ctx -> Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_BACK))
                .server("举盾", ctx -> {
                    Shiraume b = human(level(ctx));
                    b.setScripted(true);
                    b.play("idle");
                    place(ctx, .5, -2.5, .5, 1);
                    stand(b, .5, .5, .5, -2.5);
                    ctx.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SHIELD));
                    ctx.player().setHealth(ctx.player().getMaxHealth());
                    // 侧面的机位跟着她（截图用，不锁操作）
                    ShiraumeViews.follow(ctx.player(), b);
                })
                .waitUntil("盾到了客户端", ctx -> Minecraft.getInstance().player.getMainHandItem().is(Items.SHIELD))
                .step("hold shield", ctx -> use(true))
                .ticks(25)
                .server("一字斩", ctx -> human(level(ctx)).play("ichimonji_cut"))
                .ticks(8)
                .screenshot("tuning_guard_break")
                .checkServer("盾被破了", ctx -> ctx.player().getCooldowns().isOnCooldown(new ItemStack(Items.SHIELD)))
                .check("收到破盾提示", ctx -> ShiraumeClient.received(ShiraumeCue.GUARD_BREAK) > 0)
                .step("release shield", ctx -> use(false))
                .ticks(20)
                // ---- 突き清两个增益
                .server("上增益", ctx -> {
                    ServerPlayer p = ctx.player();
                    p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    for (var e : List.of(MobEffects.SPEED, MobEffects.STRENGTH, MobEffects.REGENERATION, MobEffects.JUMP_BOOST))
                        p.addEffect(new MobEffectInstance(e, 2400, 0));
                    Shiraume b = human(level(ctx));
                    b.play("idle");
                    place(ctx, .5, -3.5, .5, 1);
                    stand(b, .5, .5, .5, -3.5);
                })
                .ticks(5)
                .server("突き", ctx -> human(level(ctx)).play("tsuki"))
                .ticks(14)
                .screenshot("tuning_purge")
                .checkServer("清掉了两个增益", ctx -> beneficial(ctx.player()) == 2)
                .check("收到清除增益提示", ctx -> ShiraumeClient.received(ShiraumeCue.PURGE) > 0)
                .ticks(20)
                // ---- 背后砍她：转身横扫
                .server("站到她背后砍一刀", ctx -> {
                    Shiraume.alwaysTurn = true;
                    ServerPlayer p = ctx.player();
                    p.removeAllEffects();
                    p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.IRON_SWORD));
                    Shiraume b = human(level(ctx));
                    b.play("idle");
                    stand(b, .5, .5, .5, 6);
                    place(ctx, .5, -2, .5, .5);
                    b.setScripted(false);
                    b.invulnerableTime = 0;
                    p.resetAttackStrengthTicker();
                    p.attack(b);
                })
                .waitUntilServer("转身横扫", ctx -> human(level(ctx)).move().equals("turn_sweep"))
                .ticks(6)
                .screenshot("tuning_turn_sweep")
                .server("横扫完", ctx -> Shiraume.alwaysTurn = false)
                .ticks(30)
                // ---- 吃东西时读指令：吃得完，吃完那一刻挨刀
                .server("拿金苹果、站远一点", ctx -> {
                    Shiraume.alwaysRead = true;
                    ServerPlayer p = ctx.player();
                    p.setHealth(p.getMaxHealth() * .6f);
                    p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.GOLDEN_APPLE, 8));
                    applesBefore = 8;
                    Shiraume b = human(level(ctx));
                    b.setScripted(true);
                    b.play("idle");
                    place(ctx, .5, -8.5, .5, 1);
                    stand(b, .5, 1, .5, -8.5);
                    b.setScripted(false);
                })
                .waitUntil("金苹果到了客户端", ctx -> Minecraft.getInstance().player.getMainHandItem().is(Items.GOLDEN_APPLE))
                .step("eat", ctx -> use(true))
                .waitUntilServer("读指令的第一刀", ctx -> human(level(ctx)).move().equals("yomi_1"))
                .ticks(9)
                .screenshot("tuning_read_cut")
                .server("log read", ctx -> System.out.println("[shiraume] read cut: using=" + ctx.player().isUsingItem() + " left="
                        + ctx.player().getUseItemRemainingTicks() + " apples=" + ctx.player().getMainHandItem().getCount()))
                .waitUntilServer("那个金苹果吃完了", ctx -> ctx.player().getMainHandItem().getCount() <= applesBefore - 1)
                .step("stop eating", ctx -> use(false))
                .server("读完", ctx -> Shiraume.alwaysRead = false)
                .ticks(40)
                // ---- 术式打够五下：被盯上
                .server("用术式打她五下", ctx -> {
                    Shiraume b = human(level(ctx));
                    b.setScripted(true);
                    b.play("idle");
                    place(ctx, .5, -7.5, .5, 1);
                    stand(b, .5, 1, .5, -7.5);
                    b.setScripted(false);
                    b.setNoAi(true);
                })
                .repeat(5, r -> r.server("术式", ctx -> {
                    Shiraume b = human(level(ctx));
                    b.invulnerableTime = 0;
                    Casting.castAs(ctx.player(), Casting.compile("(spell :name \"刺\" :mana 5 (ray :range 16 (on-hit (damage target 1 arcane))))"), 5, 0, b, null);
                }).ticks(10))
                .checkServer("盯上了玩家", ctx -> ctx.player().getUUID().equals(human(level(ctx)).counter().locked()))
                .ticks(10)
                .screenshot("tuning_locked")
                .check("收到锁定提示", ctx -> ShiraumeClient.received(ShiraumeCue.LOCK) > 0)
                // ---- 变身：v3 狐形叼着流火
                .server("清空第一条血", ctx -> {
                    human(level(ctx)).setNoAi(false);
                    ctx.player().setHealth(ctx.player().getMaxHealth());
                    ctx.player().setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    ShiraumeFight.skipToPhaseTwo(level(ctx));
                })
                .waitUntilServer("狐形出现", ctx -> beast(level(ctx)) != null)
                .waitUntilServer("第二阶段开始", ctx -> !beast(level(ctx)).scripted())
                .checkServer("变身后还盯着", ctx -> ctx.player().getUUID().equals(beast(level(ctx)).counter().locked()))
                .server("看她", ctx -> {
                    ShiraumeBeast b = beast(level(ctx));
                    b.setScripted(true);
                    b.play("idle_combat");
                    stand(b, .5, 2, .5, -6);
                    place(ctx, .5, -6, .5, 2);
                    ShiraumeViews.follow(ctx.player(), b);
                })
                .ticks(20)
                .screenshot("tuning_fox_v3")
                // ---- 大跳：落在圈里
                .server("大跳", ctx -> {
                    ShiraumeBeast b = beast(level(ctx));
                    place(ctx, .5, -9, .5, 4);
                    stand(b, .5, 4, .5, -9);
                    b.setTarget(ctx.player());
                    b.play("pounce_crouch");
                })
                .waitUntilServer("腾空", ctx -> beast(level(ctx)).move().equals("pounce_air"))
                .ticks(8)
                .screenshot("tuning_fox_leap")
                .waitUntilServer("落地", ctx -> beast(level(ctx)).move().equals("pounce_land"))
                .ticks(2)
                .screenshot("tuning_fox_land")
                .checkServer("落在标记的圈里（玩家脚下）", ctx -> {
                    var b = beast(level(ctx)).position();
                    var p = ctx.player().position();
                    return Math.hypot(b.x - p.x, b.z - p.z) < 1.5;
                })
                .ticks(30)
                .server("跳闪", ctx -> beast(level(ctx)).play("hop_left"))
                .ticks(4)
                .screenshot("tuning_fox_hop")
                .ticks(20)
                // ---- 狐形的斩法：蓄力时扑过来，满是狐火的一口；血量很低时就是这一下的死亡消息
                .server("拿起长剑", ctx -> {
                    ServerPlayer p = ctx.player();
                    p.setItemInHand(InteractionHand.MAIN_HAND, castingSword());
                    var m = PlayerMagic.of(p);
                    m.baseMaxMana = m.mana = 1000;
                    ShiraumeBeast b = beast(level(ctx));
                    b.setScripted(true);
                    b.play("idle_combat");
                    stand(b, .5, 6, .5, -10);
                    place(ctx, .5, -10, .5, 6);
                })
                .ticks(45)
                .waitUntil("长剑到了客户端", ctx -> Minecraft.getInstance().player.getMainHandItem().is(LongswordItem.BLACKSTEEL.get()))
                .step("举剑（按住右键）", ctx -> use(true))
                .ticks(3)
                .server("左键蓄力", ctx -> LongswordCombat.attack(ctx.player()))
                .waitUntilServer("蓄上力了", ctx -> {
                    var c = dev.logos.runtime.Charging.of(ctx.player());
                    return c != null && c.injected > 0;
                })
                .server("放开她", ctx -> beast(level(ctx)).setScripted(false))
                .waitUntilServer("扑过来", ctx -> beast(level(ctx)) != null && beast(level(ctx)).move().equals("kitsunebi_rush"))
                .server("只剩一点血", ctx -> ctx.player().setHealth(6))
                .ticks(3)
                .screenshot("tuning_fox_counter_rush")
                .waitUntil("被斩倒", ctx -> Minecraft.getInstance().screen instanceof DeathScreen)
                .ticks(20)
                .screenshot("tuning_counter_death_message")
                .check("收到斩法提示", ctx -> ShiraumeClient.received(ShiraumeCue.COUNTER) > 0)
                .step("松开右键、复活", ctx -> {
                    use(false);
                    Minecraft.getInstance().player.respawn();
                })
                .waitUntil("回到世界", ctx -> Minecraft.getInstance().player != null && Minecraft.getInstance().player.isAlive()
                        && !(Minecraft.getInstance().screen instanceof DeathScreen))
                .server("回到场地", ctx -> {
                    place(ctx, .5, -12.5, .5, 6);
                    ShiraumeViews.sturdy(ctx.player(), 400);
                })
                .ticks(60)
                .check("复活回来以后还是最多一条血条", ctx -> bars() <= 1)
                .screenshot("tuning_after_respawn")
                .teardown("restore", ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.options.keyUse.setDown(false);
                    mc.options.setCameraType(CameraType.FIRST_PERSON);
                    ShiraumeClient.clear();
                    Shiraume.alwaysRead = false;
                    Shiraume.alwaysTurn = false;
                });
    }
}
