package dev.logos.dimension.client.jiansui;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.client.camera.CameraDirector;
import dev.logos.dimension.client.execution.ExecutionClient;
import dev.logos.dimension.execution.ExecutionTimeline.Phase;
import dev.logos.dimension.gametest.TaixuanGameTests;
import dev.logos.dimension.item.LongswordItem;
import dev.logos.dimension.item.RyukaItem;
import dev.logos.dimension.item.UchigatanaItem;
import dev.logos.dimension.jiansui.JianSui;
import dev.logos.dimension.jiansui.JianSuiFight;
import dev.logos.dimension.jiansui.JianSuiFight.State;
import dev.logos.dimension.longsword.LongswordCombat;
import dev.logos.dimension.taixuan.TaixuanSite;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/**
 * 剑祟终结（docs/JIAN_SUI_BOSS.md 第 11 节）拿不同的东西各走一遍：铁剑、打刀、流火、黑钢长剑、空手，再加一次左手拿铁剑。
 * 真的打一次：跳到第三阶段，人站到顶块上剑祟跟前、面朝它，近战打中。黑白「飞身刺」停在飞身、刺中、定格三拍截图；
 * 回到彩色后停在撬剑的姿势截一张，按一下攻击键（撬一下）停在顶到最大的那一刻再截一张；再撬两下，截崩出、倒下和镜头回来以后。
 * <p>
 * 截图时三样一起停：{@code /tick freeze} 停住服务端终结的时间表（按游戏刻走），黑白特写停在 {@link ExecutionClient#hold}、
 * 镜头停在 {@link CameraDirector#hold}（这两样按客户端的刻走，冻结管不到）。放开前先停回原来那一刻，两边才对得上。
 */
public abstract class JianSuiFinisherViews implements UIScenario {
    private static final String[] NAMES = {"jian_sui_finisher_sword", "jian_sui_finisher_uchigatana", "jian_sui_finisher_ryuka",
            "jian_sui_finisher_blacksteel", "jian_sui_finisher_hand", "jian_sui_finisher_left"};
    private TaixuanSite site;
    private ServerLevel level;
    /** 停住的那一刻：黑白特写 / 镜头各自走到第几刻。 */
    private int inkAt, cameraAt;

    protected int view() { return 0; }

    @Override public void configure(ScenarioOptions options) {
        options.tags("jian_sui").defaultTimeoutMs(480000).scenarioTimeoutMs(600000);
    }

    private ItemStack weapon() {
        return switch (view()) {
            case 1 -> new ItemStack(UchigatanaItem.INK.get());
            case 2 -> new ItemStack(RyukaItem.ITEM.get());
            case 3 -> new ItemStack(LongswordItem.BLACKSTEEL.get());
            case 4 -> ItemStack.EMPTY;
            default -> new ItemStack(Items.IRON_SWORD);
        };
    }

    private String shot(String beat) {
        return NAMES[view()] + "_" + beat;
    }

    private static boolean frozen() {
        var level = Minecraft.getInstance().level;
        return level != null && level.tickRateManager().isFrozen();
    }

    private static void command(String command) {
        Minecraft.getInstance().player.connection.sendCommand(command);
    }

    private static void hand(HumanoidArm arm) {
        var mc = Minecraft.getInstance();
        mc.options.mainHand().set(arm);
        mc.options.broadcastOptions();
    }

    /** 黑白里停在第 {@code age} 刻截一张（服务端已经停住）。 */
    private static void still(ScenarioBuilder s, String name, float age) {
        s.step("停在第 " + age + " 刻", ctx -> ExecutionClient.hold(age)).ticks(3).screenshot(name);
    }

    /** 彩色里停住镜头和服务端（撬剑的姿势按游戏刻走，跟着服务端一起停）。 */
    private void stopColour(ScenarioBuilder s, String why) {
        s.step(why, ctx -> {
            cameraAt = CameraDirector.age();
            CameraDirector.hold((float) cameraAt);
            command("tick freeze");
        }).waitUntil("服务端停了", ctx -> frozen()).ticks(3);
    }

    private void goColour(ScenarioBuilder s) {
        s.step("接着放", ctx -> {
            JianSuiFinisherClient.hold(null);
            CameraDirector.hold((float) cameraAt);
            CameraDirector.hold(null);
            command("tick unfreeze");
        }).waitUntil("服务端走了", ctx -> !frozen());
    }

    @Override public void define(ScenarioBuilder scenario) {
        scenario.setGameMode(GameType.CREATIVE).runCommand("difficulty peaceful").runCommand("time set noon").runCommand("weather clear")
            .step("设置机位", ctx -> {
                var mc = Minecraft.getInstance();
                // 上一个场景留下的裂缝、节拍不带过来（同一维度里换场景，客户端世界不换）
                JianSuiCueClient.reset();
                JianSuiFinisherClient.reset();
                mc.options.hideGui = true;
                mc.options.setCameraType(CameraType.FIRST_PERSON);
                mc.options.hideLightningFlash().set(false);
                mc.options.fov().set(70);
                mc.options.renderDistance().set(12);
                mc.options.broadcastOptions();
                hand(view() == 5 ? HumanoidArm.LEFT : HumanoidArm.RIGHT);
            })
            .waitUntil("主手对了", ctx -> Minecraft.getInstance().player.getMainArm() == (view() == 5 ? HumanoidArm.LEFT : HumanoidArm.RIGHT))
            .server("拼装地标、重置剑祟、站到石台边", ctx -> {
                level = ctx.server().getLevel(LogosDimension.CLOUDSEA);
                site = TaixuanSite.of(TaixuanGameTests.placeStudy(level).start());
                JianSuiFight.reset(level, site);
                var player = ctx.player();
                Vec3 plunge = JianSuiFight.plunge(site);
                Vec3 out = plunge.subtract(site.axis(plunge.y)).multiply(1, 0, 1).normalize();
                DimensionTravel.teleport(player, level, plunge.add(out.scale(5)).add(0, .3, 0), 0, 0);
                player.getAbilities().flying = false;
                player.onUpdateAbilities();
                player.getInventory().setSelectedSlot(0);
                player.setItemInHand(InteractionHand.MAIN_HAND, weapon());
            })
            .ticks(40)
            .server("跳到第三阶段，站到剑祟跟前", ctx -> {
                JianSuiFight.skip(level, site, State.FALL);
                JianSui boss = JianSuiFight.boss(level, site);
                ctx.check("第三阶段有剑祟", boss != null);
                // 人站在剑祟和玄铁心之间（顶块上），两个面对面
                Vec3 in = site.axis(boss.getY()).subtract(boss.position()).multiply(1, 0, 1).normalize();
                float bossYaw = (float) Math.toDegrees(Math.atan2(-in.x, in.z));
                boss.setYRot(bossYaw);
                boss.setYBodyRot(bossYaw);
                boss.setYHeadRot(bossYaw);
                Vec3 feet = boss.position().add(in.scale(1.5));
                ctx.player().teleportTo(level, feet.x, feet.y, feet.z, java.util.Set.of(), bossYaw + 180, 8, true);
            })
            .waitUntil("剑祟到了客户端", ctx -> {
                var mc = Minecraft.getInstance();
                var at = mc.player.blockPosition();
                return mc.level.getChunkSource().hasChunk(SectionPos.blockToSectionCoord(at.getX()), SectionPos.blockToSectionCoord(at.getZ()))
                        && !mc.level.getEntitiesOfClass(JianSui.class, mc.player.getBoundingBox().inflate(6)).isEmpty();
            })
            .ticks(30)
            .screenshot(shot("before"))
            .server("近战打中剑祟", ctx -> {
                JianSui boss = JianSuiFight.boss(level, site);
                boss.hurtServer(level, level.damageSources().playerAttack(ctx.player()), 1);
                ctx.check("进了终结", JianSuiFight.state(level, site) == State.FINISHER);
            })
            // ---- 黑白「飞身刺」
            .waitUntil("黑白开始", ctx -> ExecutionClient.active() && ExecutionClient.phase() == Phase.INK)
            .step("一起停住", ctx -> {
                inkAt = ExecutionClient.age();
                ExecutionClient.hold((float) inkAt);
                command("tick freeze");
            })
            .waitUntil("服务端停了", ctx -> frozen())
            .server("战斗骨架停了", ctx -> ctx.check("打中的人的战斗骨架暂停", LongswordCombat.paused(ctx.player())))
            .step("两个人按特写摆", ctx -> {
                var mc = Minecraft.getInstance();
                ctx.check("自己按特写摆", ExecutionClient.posing(mc.player));
                ctx.check("剑祟按特写摆", mc.level.getEntitiesOfClass(JianSui.class, mc.player.getBoundingBox().inflate(8)).stream().anyMatch(ExecutionClient::posing));
                ctx.check("黑白不对调", !ExecutionClient.swapped());
            });
        still(scenario, shot("leap"), 8);
        still(scenario, shot("contact"), 11);
        still(scenario, shot("freeze"), 16);
        scenario
            .step("停回原来那一刻再放开", ctx -> {
                ExecutionClient.hold((float) inkAt);
                ExecutionClient.hold(null);
                command("tick unfreeze");
            })
            .waitUntil("服务端走了", ctx -> !frozen())
            // ---- 彩色：撬剑
            .waitUntil("退回彩色", ctx -> !ExecutionClient.active() && CameraDirector.active())
            .waitUntil("撬剑的窗口开了", ctx -> CameraDirector.age() >= JianSuiFight.PRY_OPEN - JianSuiFight.FIN_COLOUR + 2);
        stopColour(scenario, "停在撬剑的姿势");
        scenario
            .step("还没撬", ctx -> ctx.check("还没有一下", JianSuiFinisherClient.beatCount() == 0))
            .screenshot(shot("pry"))
            .step("按一下攻击键（撬）", ctx -> ctx.check("这一下告诉了服务端", JianSuiFinisherClient.press()));
        goColour(scenario);
        scenario.waitUntil("撬了一下", ctx -> JianSuiFinisherClient.lastBeat() >= 0);
        stopColour(scenario, "停在顶到最大的那一下");
        scenario
            .step("姿势停在这一下顶到最大", ctx -> JianSuiFinisherClient.hold(JianSuiFinisherClient.lastBeat() + JianSuiFinisherClient.HIT_STOP + .5))
            .ticks(2)
            .screenshot(shot("pry_beat"));
        goColour(scenario);
        scenario
            .waitUntil("第二下", ctx -> JianSuiFinisherClient.press())
            .waitUntil("第三下", ctx -> JianSuiFinisherClient.press())
            .waitUntil("撬满三下", ctx -> JianSuiFinisherClient.beatCount() >= 3)
            .ticks(JianSuiFight.BURST_AFTER + 2)
            .screenshot(shot("burst"))
            .ticks(JianSuiFight.FIN_WIDE_AFTER + 20)
            .screenshot(shot("collapse"))
            .waitUntil("过场结束", ctx -> !CameraDirector.active())
            .ticks(10)
            .screenshot(shot("after"))
            .step("镜头和身体都还回来了", ctx -> {
                var mc = Minecraft.getInstance();
                ctx.check("自己的身体不再按特写摆", !ExecutionClient.posing(mc.player));
                ctx.check("遮幅收了", CameraDirector.bars() == 0);
                ctx.check("镜头回到玩家", !mc.gameRenderer.getMainCamera().isDetached());
            })
            .server("收尾", ctx -> {
                ctx.check("记成已打败", JianSuiFight.state(level, site) == State.DEFEATED);
                ctx.check("战斗骨架恢复", !LongswordCombat.paused(ctx.player()));
                JianSuiFight.reset(level, site);
                ctx.player().setGameMode(GameType.CREATIVE);
            })
            .teardown("恢复", ctx -> {
                var mc = Minecraft.getInstance();
                ExecutionClient.hold(null);
                CameraDirector.hold(null);
                JianSuiFinisherClient.hold(null);
                mc.options.hideGui = false;
                hand(HumanoidArm.RIGHT);
                if (mc.player != null) command("tick unfreeze");
            });
    }

    @LDLRegisterClient(name="jian_sui_finisher_sword", group="jian_sui_finisher", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
    public static class Sword extends JianSuiFinisherViews {}
    @LDLRegisterClient(name="jian_sui_finisher_uchigatana", group="jian_sui_finisher", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
    public static class Uchigatana extends JianSuiFinisherViews { @Override protected int view() { return 1; } }
    @LDLRegisterClient(name="jian_sui_finisher_ryuka", group="jian_sui_finisher", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
    public static class Ryuka extends JianSuiFinisherViews { @Override protected int view() { return 2; } }
    @LDLRegisterClient(name="jian_sui_finisher_blacksteel", group="jian_sui_finisher", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
    public static class Blacksteel extends JianSuiFinisherViews { @Override protected int view() { return 3; } }
    @LDLRegisterClient(name="jian_sui_finisher_hand", group="jian_sui_finisher", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
    public static class Hand extends JianSuiFinisherViews { @Override protected int view() { return 4; } }
    @LDLRegisterClient(name="jian_sui_finisher_left", group="jian_sui_finisher", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
    public static class Left extends JianSuiFinisherViews { @Override protected int view() { return 5; } }
}
