package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.companion.Companions;
import dev.logos.dimension.companion.KohakuCompany;
import dev.logos.dimension.companion.KohakuCompanion;
import dev.logos.dimension.companion.OwnerWatch;
import dev.logos.dimension.companion.ShiraumeCompanion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;

/**
 * 实际客户端检查琥珀睡觉（docs/KOHAKU_COMPANION.md 第 9 节，第五步），也在 {@code kohaku_companion} 一组。夜里你躺下，一晚过去
 * （{@code advance_time} 开着，一个人睡够 5 秒就跳到早上），醒来以后拍她赖床：
 * <ul>
 * <li>{@code kohaku_sleep}：贴着你的床边侧躺、脸朝着你；一整夜以后你有琥珀相伴，她赖一会床、翻个身起来、伸懒腰、困着走过来递礼物。</li>
 * <li>{@code kohaku_sleep_spare}：旁边有一张空床，她占那张趴成猫饼；你半夜自己起来（没睡满一晚）：她照样赖床起来，没有琥珀相伴。</li>
 * <li>{@code kohaku_sleep_yours}：挤上你的床缩在床边；你醒来时她已经横着摊成「大」字。</li>
 * <li>{@code kohaku_sleep_belly}：露肚皮睡在你床边。</li>
 * <li>{@code kohaku_sleep_shiraume}：白梅蜷在床边，她贴着白梅的背蜷着。</li>
 * <li>{@code kohaku_bed_nap}：白天你挂机，她去旁边的空床上打盹；你一动她就起来。</li>
 * </ul>
 */
public abstract class KohakuSleepViews extends KohakuLifeViews {
    /** 你的床：床脚、床头（朝南）；旁边那张空床。 */
    static final BlockPos FOOT = new BlockPos(0, FLOOR + 1, 2), HEAD = FOOT.south(), SPARE_FOOT = FOOT.east(2);
    /** 站在床边。 */
    static final Vec3 BEDSIDE = new Vec3(-1.5, FLOOR + 1, 2.5);

    static void bed(ServerLevel level, BlockPos foot) {
        var bed = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH);
        level.setBlockAndUpdate(foot, bed.setValue(BedBlock.PART, BedPart.FOOT));
        level.setBlockAndUpdate(foot.south(), bed.setValue(BedBlock.PART, BedPart.HEAD));
    }

    /** 站到她斜前方高一点的地方看她（站在地上）。 */
    static void lookAt(ServerPlayer p, KohakuCompanion c, Vec3 offset) {
        Vec3 at = c.position();
        aim(p, at.add(offset), at.add(0, .3, 0));
    }

    /** 主人躺下（返回躺下了没有）。 */
    static boolean sleep(ServerPlayer p) {
        return p.startSleepInBed(HEAD).right().isPresent() || p.isSleeping();
    }

    static ScenarioBuilder night(ScenarioBuilder s) {
        return kohakuStage(s, GameType.SURVIVAL).runCommand("difficulty peaceful").runCommand("time set midnight").runCommand("gamerule advance_time true")
                .runCommand("gamerule players_sleeping_percentage 100");
    }

    static void reset() {
        KohakuCompanion.luckyForTest(false);
        KohakuCompanion.sleepForTest(null, null);
    }

    // ---------------------------------------------------------------- 贴着床边、一整夜、醒来的礼物

    @LDLRegisterClient(name = "kohaku_sleep", group = "kohaku_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Sleep extends KohakuSleepViews {
        private KohakuCompanion c;
        private int nights, gifts;

        @Override
        public void define(ScenarioBuilder s) {
            night(s).server("一张床，站在床边，召唤；这一晚侧卧、醒来一定给礼物", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        ServerPlayer p = ctx.player();
                        bed(level, FOOT);
                        standAt(p, BEDSIDE);
                        c = summonKohaku(p, BEDSIDE.add(-1, 0, -1));
                        KohakuCompanion.sleepForTest("curl", "floor");
                        KohakuCompanion.luckyForTest(true);
                        nights = KohakuCompanion.fullNights();
                        gifts = KohakuCompanion.morningGifts();
                    })
                    .waitUntilServer("入场播完", ctx -> arrived(c))
                    .checkServer("主人躺下", ctx -> sleep(ctx.player()))
                    .waitUntilServer("她贴着床边侧躺下来", ctx -> "curl".equals(c.poseForTest()) && "floor".equals(c.sleepWhereForTest()) && c.curled())
                    .checkServer("脸朝着床（你）", ctx -> {
                        Vec3 toBed = Vec3.atCenterOf(HEAD).subtract(c.position()).multiply(1, 0, 1).normalize();
                        return Vec3.directionFromRotation(0, c.yBodyRot).dot(toBed) > .5;
                    })
                    .checkServer("长杖收起来了", ctx -> c.getMainHandItem().isEmpty())
                    .waitUntilServer("一夜过去，主人醒了", ctx -> !ctx.player().isSleeping())
                    .checkServer("天亮了", ctx -> !ctx.server().overworld().isDarkOutside())
                    .server("站到能看见她的地方", ctx -> lookAt(ctx.player(), c, new Vec3(2.2, 1.9, -2.2)))
                    .checkServer("她还在赖床", ctx -> c.curled())
                    .ticks(8).screenshot("kohaku_sleep_linger")
                    .waitUntilServer("动一动、翻个身起来", ctx -> "wake_linger".equals(KohakuViews.move(c)))
                    .ticks(28).screenshot("kohaku_wake_linger")
                    .waitUntilServer("起来伸个懒腰", ctx -> "act_cat_stretch".equals(misc(c)))
                    .checkServer("一起睡过一整夜", ctx -> KohakuCompanion.fullNights() > nights)
                    .checkServer("你有琥珀相伴", ctx -> ctx.player().hasEffect(KohakuCompany.EFFECT))
                    .waitUntilServer("伸完懒腰，走过来递礼物", ctx -> "give".equals(misc(c)))
                    .ticks(10).screenshot("kohaku_morning_gift")
                    .waitUntilServer("给了", ctx -> KohakuCompanion.morningGifts() > gifts && !ctx.player().getInventory().isEmpty())
                    .waitUntilServer("递完，还困着", ctx -> "face_sleepy".equals(misc(c)))
                    .teardown("不再强行定", ctx -> reset());
        }
    }

    // ---------------------------------------------------------------- 旁边的空床、没睡满一晚

    @LDLRegisterClient(name = "kohaku_sleep_spare", group = "kohaku_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Spare extends KohakuSleepViews {
        private KohakuCompanion c;
        private int nights;

        @Override
        public void define(ScenarioBuilder s) {
            night(s).runCommand("gamerule players_sleeping_percentage 101")
                    .server("两张并排的床，站在床边，召唤；这一晚猫饼", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        ServerPlayer p = ctx.player();
                        bed(level, FOOT);
                        bed(level, SPARE_FOOT);
                        standAt(p, BEDSIDE);
                        c = summonKohaku(p, BEDSIDE.add(-1, 0, -1));
                        KohakuCompanion.sleepForTest("loaf", null);
                        nights = KohakuCompanion.fullNights();
                    })
                    .waitUntilServer("入场播完", ctx -> arrived(c))
                    .checkServer("主人躺下", ctx -> sleep(ctx.player()))
                    .waitUntilServer("她占了旁边那张床，趴成猫饼", ctx -> "spare".equals(c.sleepWhereForTest()) && "sleep_loaf".equals(KohakuViews.move(c)) && c.curled())
                    .checkServer("躺在床上", ctx -> c.getY() > FLOOR + 1.4 && Math.abs(c.getX() - (SPARE_FOOT.getX() + .5)) < .5)
                    .server("主人半夜自己起来，站到能看见她的地方", ctx -> {
                        ctx.player().stopSleepInBed(true, true);
                        lookAt(ctx.player(), c, new Vec3(-2.6, 1.8, -2.2));
                    })
                    .ticks(10).screenshot("kohaku_sleep_spare")
                    .waitUntilServer("她起来", ctx -> "loaf_up".equals(KohakuViews.move(c)))
                    .waitUntilServer("伸个懒腰", ctx -> "act_cat_stretch".equals(misc(c)))
                    .checkServer("没睡满一晚：没有琥珀相伴", ctx -> KohakuCompanion.fullNights() == nights && !ctx.player().hasEffect(KohakuCompany.EFFECT))
                    .teardown("不再强行定", ctx -> {
                        reset();
                        ctx.server().getCommands().performPrefixedCommand(ctx.server().createCommandSourceStack(), "gamerule players_sleeping_percentage 100");
                    });
        }
    }

    // ---------------------------------------------------------------- 挤上你的床：「大」字

    @LDLRegisterClient(name = "kohaku_sleep_yours", group = "kohaku_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Yours extends KohakuSleepViews {
        private KohakuCompanion c;

        @Override
        public void define(ScenarioBuilder s) {
            night(s).server("一张床，站在床边，召唤；这一晚挤上你的床", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        ServerPlayer p = ctx.player();
                        bed(level, FOOT);
                        standAt(p, BEDSIDE);
                        c = summonKohaku(p, BEDSIDE.add(-1, 0, -1));
                        KohakuCompanion.sleepForTest("curl", "yours");
                    })
                    .waitUntilServer("入场播完", ctx -> arrived(c))
                    .checkServer("主人躺下", ctx -> sleep(ctx.player()))
                    .waitUntilServer("她缩在你的床边上", ctx -> "yours".equals(c.sleepWhereForTest()) && c.curled() && c.getY() > FLOOR + 1.4)
                    .waitUntilServer("一夜过去，主人醒了", ctx -> !ctx.player().isSleeping())
                    .waitUntilServer("她横着摊成「大」字", ctx -> "sprawl".equals(c.poseForTest()) && "sleep_sprawl".equals(KohakuViews.move(c)))
                    .server("站到能看见她的地方", ctx -> lookAt(ctx.player(), c, new Vec3(-2.4, 2.0, -1.6)))
                    .ticks(8).screenshot("kohaku_sleep_sprawl")
                    .waitUntilServer("赖够了起来", ctx -> "sprawl_up".equals(KohakuViews.move(c)))
                    .waitUntilServer("伸个懒腰", ctx -> "act_cat_stretch".equals(misc(c)))
                    .teardown("不再强行定", ctx -> reset());
        }
    }

    // ---------------------------------------------------------------- 露肚皮

    @LDLRegisterClient(name = "kohaku_sleep_belly", group = "kohaku_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Belly extends KohakuSleepViews {
        private KohakuCompanion c;

        @Override
        public void define(ScenarioBuilder s) {
            night(s).server("一张床，站在床边，召唤；这一晚露肚皮", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        ServerPlayer p = ctx.player();
                        bed(level, FOOT);
                        standAt(p, BEDSIDE);
                        c = summonKohaku(p, BEDSIDE.add(-1, 0, -1));
                        KohakuCompanion.sleepForTest("belly", "floor");
                    })
                    .waitUntilServer("入场播完", ctx -> arrived(c))
                    .checkServer("主人躺下", ctx -> sleep(ctx.player()))
                    .waitUntilServer("她露着肚皮睡在床边", ctx -> "belly".equals(c.poseForTest()) && "sleep_belly".equals(KohakuViews.move(c)) && c.curled())
                    .waitUntilServer("一夜过去，主人醒了", ctx -> !ctx.player().isSleeping())
                    .server("站到能看见她的地方", ctx -> lookAt(ctx.player(), c, new Vec3(2.0, 1.8, -2.0)))
                    .ticks(8).screenshot("kohaku_sleep_belly")
                    .waitUntilServer("赖够了起来", ctx -> "belly_up".equals(KohakuViews.move(c)))
                    .waitUntilServer("伸个懒腰", ctx -> "act_cat_stretch".equals(misc(c)))
                    .teardown("不再强行定", ctx -> reset());
        }
    }

    // ---------------------------------------------------------------- 贴着白梅的背

    @LDLRegisterClient(name = "kohaku_sleep_shiraume", group = "kohaku_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class WithShiraume extends KohakuSleepViews {
        private KohakuCompanion c;
        private ShiraumeCompanion shiraume;

        @Override
        public void define(ScenarioBuilder s) {
            night(s).runCommand("gamerule players_sleeping_percentage 101")
                    .server("一张床，站在床边，叫出白梅；主人躺下", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        ServerPlayer p = ctx.player();
                        bed(level, FOOT);
                        standAt(p, BEDSIDE);
                        shiraume = summonAt(p, BEDSIDE.add(-1, 0, 1));
                    })
                    .waitUntilServer("白梅入场播完", ctx -> !"summon".equals(shiraume.npcMiscAnimation()))
                    .checkServer("主人躺下", ctx -> sleep(ctx.player()))
                    .waitUntilServer("白梅在床边蜷起来睡", ctx -> shiraume.curled())
                    .server("再叫出琥珀（这一晚侧卧）", ctx -> {
                        KohakuCompanion.sleepForTest("curl", "shiraume");
                        c = summonKohaku(ctx.player(), BEDSIDE.add(-2, 0, -1));
                    })
                    .waitUntilServer("她贴着白梅的背蜷着", ctx -> "shiraume".equals(c.sleepWhereForTest()) && c.curled() && c.distanceTo(shiraume) < 1.4)
                    .server("主人起来，站到能看见她们的地方", ctx -> {
                        ctx.player().stopSleepInBed(true, true);
                        lookAt(ctx.player(), c, new Vec3(-2.4, 2.0, -1.8));
                    })
                    .ticks(8).screenshot("kohaku_sleep_shiraume")
                    .teardown("收拾", ctx -> {
                        reset();
                        // 白梅由下一个场景开头收回（stage 会 dismiss）
                        ctx.server().getCommands().performPrefixedCommand(ctx.server().createCommandSourceStack(), "gamerule players_sleeping_percentage 100");
                    });
        }
    }

    // ---------------------------------------------------------------- 你挂机时去床上打盹

    @LDLRegisterClient(name = "kohaku_bed_nap", group = "kohaku_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class BedNap extends KohakuSleepViews {
        private KohakuCompanion c;
        private int naps;

        @Override
        public void define(ScenarioBuilder s) {
            kohakuStage(s, GameType.SURVIVAL).runCommand("difficulty peaceful")
                    .server("白天，旁边一张空床；站好、召唤（这一回一定去，趴成猫饼）", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        ServerPlayer p = ctx.player();
                        bed(level, FOOT.east(3));
                        standAt(p, STAND);
                        c = summonKohaku(p, STAND.add(1.5, 0, -1));
                        KohakuCompanion.luckyForTest(true);
                        KohakuCompanion.sleepForTest("loaf", null);
                        naps = KohakuCompanion.bedNaps();
                    })
                    .waitUntilServer("入场播完", ctx -> arrived(c))
                    .server("主人挂机", ctx -> OwnerWatch.pretendStill(ctx.player(), OwnerWatch.AFK_TICKS + 100))
                    .waitUntilServer("她去床上趴着打盹", ctx -> "bed".equals(c.spotForTest()) && "sleep_loaf".equals(KohakuViews.move(c)) && c.curled())
                    .checkServer("躺在床上", ctx -> c.getY() > FLOOR + 1.4 && KohakuCompanion.bedNaps() > naps)
                    .server("看她", ctx -> lookAt(ctx.player(), c, new Vec3(-2.4, 1.8, -2.4)))
                    .ticks(8).screenshot("kohaku_bed_nap")
                    .server("主人走开", ctx -> standAt(ctx.player(), STAND.add(-3, 0, -2)))
                    .waitUntilServer("她起来", ctx -> "loaf_up".equals(KohakuViews.move(c)))
                    .waitUntilServer("伸个懒腰", ctx -> "act_cat_stretch".equals(misc(c)))
                    .teardown("不再强行定", ctx -> reset());
        }
    }
}
