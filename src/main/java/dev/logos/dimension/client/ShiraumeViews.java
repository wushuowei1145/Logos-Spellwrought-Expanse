package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.client.shiraume.ShiraumeClient;
import dev.logos.dimension.client.shiraume.ShiraumeScreen;
import dev.logos.dimension.item.RyukaItem;
import dev.logos.dimension.shiraume.FoxfireBolt;
import dev.logos.dimension.shiraume.Shiraume;
import dev.logos.dimension.shiraume.ShiraumeBeast;
import dev.logos.dimension.shiraume.ShiraumeCue;
import dev.logos.dimension.shiraume.ShiraumeEntities;
import dev.logos.dimension.shiraume.ShiraumeFight;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 白梅战的实机场景（本地开发用，docs/SHIRAUME_BOSS.md）：在一块白瓷平台上召出人形（不在封印之间里，没有开场过场），
 * 玩家站着不动挨打：看她出招、双眼红光、砍中；逼出一次投技看处决和「只剩一点血」；直接变身看光柱与狐形登场；
 * 狐形打一阵，狂暴后放狐火；最后击倒，看化作花瓣、红光退去与流火的奖励。每段截图，并检查状态与客户端收到的提示。
 * 开场的封印与过场在 {@code fox_vault_views} 那样的真实场地里另测。
 */
@LDLRegisterClient(name = "shiraume_duel", group = "shiraume", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
public class ShiraumeViews implements UIScenario {
    static final int FLOOR = 99, HALF = 28;
    static final AABB AREA = new AABB(-HALF, FLOOR - 2, -HALF, HALF + 1, FLOOR + 30, HALF + 1);
    /** 服务端记下的招式（看她出过哪些招）。 */
    static final List<String> MOVES = new ArrayList<>();

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("shiraume").defaultTimeoutMs(120_000).scenarioTimeoutMs(900_000);
    }

    static ServerLevel level(com.lowdragmc.lowdraglib2.uitest.ServerContext ctx) {
        return ctx.server().overworld();
    }

    static Shiraume human(ServerLevel level) {
        return level.getEntitiesOfClass(Shiraume.class, AREA).stream().findFirst().orElse(null);
    }

    static ShiraumeBeast beast(ServerLevel level) {
        return level.getEntitiesOfClass(ShiraumeBeast.class, AREA).stream().findFirst().orElse(null);
    }

    /** 一块白瓷平台，四周一圈屏障（被推下去就结束了）。 */
    static void arena(ServerLevel level) {
        level.getGameRules().set(GameRules.MOB_GRIEFING, false, level.getServer());
        level.getGameRules().set(GameRules.RANDOM_TICK_SPEED, 0, level.getServer());
        for (int x = -HALF; x <= HALF; x++)
            for (int z = -HALF; z <= HALF; z++) {
                boolean edge = Math.abs(x) == HALF || Math.abs(z) == HALF;
                level.setBlockAndUpdate(new BlockPos(x, FLOOR, z), dev.logos.dimension.cloudsea.CloudseaBlocks.PORCELAIN.get().defaultBlockState());
                for (int y = FLOOR + 1; y <= FLOOR + 10; y++)
                    level.setBlockAndUpdate(new BlockPos(x, y, z), edge ? Blocks.BARRIER.defaultBlockState() : Blocks.AIR.defaultBlockState());
            }
        level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class, AREA, e -> !(e instanceof net.minecraft.world.entity.player.Player)).forEach(e -> e.discard());
    }

    /** 玩家血量加到很高（挨几下不至于死），满血；生存模式，站在台子南边，面朝北边的白梅。 */
    static void sturdy(ServerPlayer player, double health) {
        var attribute = player.getAttribute(Attributes.MAX_HEALTH);
        attribute.addOrReplacePermanentModifier(new AttributeModifier(LogosDimension.id("shiraume_test"), health - 20, AttributeModifier.Operation.ADD_VALUE));
        player.setHealth(player.getMaxHealth());
    }

    @Override
    public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.SURVIVAL).runCommand("difficulty normal").runCommand("weather clear").runCommand("time set 6000")
                .step("camera", ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.options.hideGui = false;
                    mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                    mc.options.renderDistance().set(8);
                    mc.options.fov().set(80);
                })
                .server("arena", ctx -> {
                    ServerLevel level = level(ctx);
                    arena(level);
                    ShiraumeFight.reset(level);
                    MOVES.clear();
                    ServerPlayer player = ctx.player();
                    player.teleportTo(level, .5, FLOOR + 1, -9.5, java.util.Set.of(), 0, 8, false);
                    sturdy(player, 400);
                    Shiraume boss = ShiraumeEntities.SHIRAUME.get().create(level, EntitySpawnReason.COMMAND);
                    boss.snapTo(.5, FLOOR + 1, 6.5, 180, 0);
                    boss.setYHeadRot(180);
                    boss.setYBodyRot(180);
                    level.addFreshEntity(boss);
                    ShiraumeFight.adopt(level, boss);
                    boss.showBar(true, 1);
                    follow(player, boss);
                })
                .ticks(60)
                .screenshot("shiraume_stance")
                // 第一阶段：她冲过来出招，红光，砍中（血量变少）
                .waitUntilServer("她出了一招", ctx -> {
                    Shiraume b = human(level(ctx));
                    if (b != null && !b.move().equals("idle") && !MOVES.contains(b.move())) MOVES.add(b.move());
                    return MOVES.stream().anyMatch(m -> !m.equals("dash") && !m.equals("deflect"));
                })
                .ticks(14)
                .screenshot("shiraume_swing")
                .waitUntilServer("玩家挨了一刀", ctx -> ctx.player().getHealth() < ctx.player().getMaxHealth() - 1)
                .check("出招前双眼红光（客户端收到过提示）", ctx -> ShiraumeClient.received(ShiraumeCue.EYES) > 0)
                .server("log moves", ctx -> System.out.println("[shiraume] moves so far " + MOVES + " player hp " + ctx.player().getHealth()))
                // 投技：血量跨过六分之五 → 瞄准、冲刺、斩り上げ，玩家被挑飞进处决特写，重击后只剩 1 点血
                .server("逼出投技", ctx -> {
                    ServerPlayer player = ctx.player();
                    player.setHealth(player.getMaxHealth());
                    Shiraume b = human(level(ctx));
                    b.setHealth(b.getMaxHealth() * .8f);
                })
                .waitUntilServer("开始瞄准", ctx -> human(level(ctx)).move().equals("throw_aim"))
                .ticks(10)
                .screenshot("shiraume_throw_aim")
                .waitUntilServer("挑飞，进处决", ctx -> human(level(ctx)).move().equals("throw_hold"))
                .ticks(20)
                .screenshot("shiraume_execution")
                .waitUntilServer("重击之后只剩 1 点血", ctx -> ctx.player().getHealth() <= 1.01f)
                // 马上回满：只剩 1 点血时她接着出招，这一刀砍中就死了，后面的截图全是死亡画面
                .server("回满血", ctx -> ctx.player().setHealth(ctx.player().getMaxHealth()))
                .check("光环烧掉了一枚", ctx -> {
                    var mc = Minecraft.getInstance();
                    return mc.level.getEntitiesOfClass(Shiraume.class, AREA).stream().anyMatch(b -> b.haloBurned() == 1);
                })
                // 变身：第一条血清空 → 单膝跪地、拍地、光柱、狐形
                .server("清空第一条血", ctx -> {
                    ServerPlayer player = ctx.player();
                    player.setHealth(player.getMaxHealth());
                    ShiraumeFight.skipToPhaseTwo(level(ctx));
                })
                .waitUntilServer("开始变身", ctx -> human(level(ctx)) != null && human(level(ctx)).move().startsWith("kneel"))
                .ticks(46)
                .screenshot("shiraume_pillar")
                .check("收到光柱与色散", ctx -> ShiraumeClient.received(ShiraumeCue.PILLAR) > 0 && ShiraumeClient.received(ShiraumeCue.ABERRATION) > 0)
                .waitUntilServer("狐形出现", ctx -> beast(level(ctx)) != null)
                .waitUntilServer("狐形登场", ctx -> beast(level(ctx)).move().equals("reveal"))
                .ticks(24)
                .screenshot("shiraume_reveal")
                .waitUntilServer("第二阶段开始", ctx -> !beast(level(ctx)).scripted())
                .server("player full", ctx -> {
                    ctx.player().setHealth(ctx.player().getMaxHealth());
                    follow(ctx.player(), beast(level(ctx)));
                })
                .waitUntilServer("狐形出招", ctx -> {
                    String m = beast(level(ctx)).move();
                    if (!m.equals("idle_combat") && !MOVES.contains("fox:" + m)) MOVES.add("fox:" + m);
                    return MOVES.stream().filter(x -> x.startsWith("fox:")).count() >= 2;
                })
                .ticks(10)
                .screenshot("shiraume_beast")
                // 狂暴：八分之一血以下，每招之后放狐火
                .server("逼到狂暴", ctx -> {
                    ShiraumeBeast b = beast(level(ctx));
                    b.hurtServer(level(ctx), level(ctx).damageSources().generic(), b.getHealth() - b.getMaxHealth() * .1f);
                    ctx.player().setHealth(ctx.player().getMaxHealth());
                })
                .waitUntilServer("狐火飞出来", ctx -> !level(ctx).getEntitiesOfClass(FoxfireBolt.class, AREA).isEmpty())
                .ticks(10)
                .screenshot("shiraume_foxfire")
                .server("log moves 2", ctx -> System.out.println("[shiraume] all moves " + MOVES))
                // 击倒：趴下、化作花瓣、红光退去、流火落在场地中央
                .server("击倒", ctx -> {
                    ShiraumeBeast b = beast(level(ctx));
                    b.hurtServer(level(ctx), level(ctx).damageSources().genericKill(), 1e6f);
                })
                .waitUntilServer("场地记为击败", ctx -> ShiraumeFight.state(level(ctx), arenaKey(level(ctx))) == ShiraumeFight.State.DEFEATED)
                .ticks(70)
                .screenshot("shiraume_petals")
                .waitUntilServer("流火落下", ctx -> level(ctx).getEntitiesOfClass(ItemEntity.class, AREA, i -> i.getItem().is(RyukaItem.ITEM.get())).size() >= 1)
                .ticks(20)
                .screenshot("shiraume_reward")
                .check("全屏通道跑过", ctx -> ShiraumeScreen.passes() > 0)
                .teardown("restore", ctx -> {
                    var mc = Minecraft.getInstance();
                    mc.options.setCameraType(CameraType.FIRST_PERSON);
                    ShiraumeClient.clear();
                });
    }

    /** 截图用的镜头：跟在她右前方，一直看着她（不锁操作、不藏界面，玩家照样挨打）。 */
    static void follow(ServerPlayer player, net.minecraft.world.entity.Mob boss) {
        dev.logos.dimension.camera.CameraShots.play(player, dev.logos.dimension.camera.CameraShot.of(
                dev.logos.dimension.camera.CameraPoint.on(boss, new Vec3(6.5, 3.2, -5.5)),
                dev.logos.dimension.camera.CameraPoint.on(boss, new Vec3(0, 2.2, 0))).timing(0, 20 * 60, 0).keepControl().keepHud());
    }

    static long arenaKey(ServerLevel level) {
        return ShiraumeFight.keys(level).stream().findFirst().orElse(0L);
    }
}
