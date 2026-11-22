package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.companion.KohakuCompanion;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/**
 * 实际客户端检查琥珀的近身解法（docs/KOHAKU_COMPANION.md 第 6、7.4 节，第三步），也在 {@code kohaku_companion} 一组：
 * <ul>
 * <li>{@code kohaku_conjure}：一只僵尸贴到她跟前：右手凝出凝辉（长杖收起来）、横扫一圈打中它、推开，剑碎成光，她往后一跳。</li>
 * <li>{@code kohaku_blink}：三只僵尸把她围住：她蹲身一跃，闪到 8 格以外开阔的地方，原地留下猫爪印。</li>
 * <li>{@code kohaku_two_step}：九枚爪之契，主人只剩两成血、身边围着两只僵尸：她拉着主人一起闪走。</li>
 * </ul>
 */
public abstract class KohakuMoveViews extends KohakuSpellViews {
    /** 从斜上方、隔开几格看她（主人飞在半空，她留在原地）：剑和横扫都在画面里。 */
    static final Vec3 NEAR_EYE = new Vec3(-4.2, FLOOR + 3.6, -3), NEAR_LOOK = new Vec3(.5, FLOOR + 1, 1.2);

    static Mob zombie(ServerLevel level, Vec3 at, net.minecraft.world.entity.LivingEntity target) {
        Mob z = PrincessViews.helmeted(CompanionLifeViews.spawn(level, EntityType.ZOMBIE, at, 180));
        z.setTarget(target);
        return z;
    }

    // ---------------------------------------------------------------- 凝辉

    @LDLRegisterClient(name = "kohaku_conjure", group = "kohaku_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Conjure extends KohakuMoveViews {
        private KohakuCompanion c;
        private Mob zombie;
        private int sweeps, hits;
        private float health;
        private Vec3 from;

        @Override
        public void define(ScenarioBuilder s) {
            kohakuStage(s, GameType.CREATIVE).runCommand("difficulty normal")
                    .server("飞在半空看、召唤，让她留在原地", ctx -> {
                        ServerPlayer p = ctx.player();
                        p.getAbilities().flying = true;
                        p.onUpdateAbilities();
                        aim(p, NEAR_EYE, NEAR_LOOK);
                        c = summonKohaku(p, SPOT);
                        c.stayForTest(true);
                    })
                    .step("对准", ctx -> aimClient(NEAR_LOOK))
                    .waitUntilServer("入场播完", ctx -> arrived(c))
                    .server("一只僵尸贴到她跟前", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        c.topUp();
                        sweeps = KohakuCompanion.sweeps();
                        hits = KohakuCompanion.sweepHits();
                        zombie = zombie(level, c.position().add(0, 0, 2), c);
                        health = zombie.getHealth();
                        from = c.position();
                    })
                    .waitUntilServer("凝出凝辉", ctx -> "conjure_sweep".equals(KohakuViews.move(c)))
                    .checkServer("长杖收起来了，右手空着凝剑", ctx -> c.getMainHandItem().isEmpty())
                    .ticks(5).screenshot("kohaku_conjure_form")
                    .ticks(3).screenshot("kohaku_conjure_windup")
                    .check("手上画了凝辉", ctx -> dev.logos.dimension.client.companion.VesperglassOnBone.draws() > 0)
                    .ticks(2).screenshot("kohaku_conjure_swing")
                    .ticks(2).screenshot("kohaku_conjure_sweep")
                    .waitUntilServer("横扫打中僵尸", ctx -> KohakuCompanion.sweeps() > sweeps && KohakuCompanion.sweepHits() > hits)
                    .checkServer("僵尸受了奥术伤害", ctx -> zombie.getHealth() < health)
                    .ticks(4).screenshot("kohaku_conjure_shatter")
                    .waitUntilServer("她往后跳开、动作播完", ctx -> !"conjure_sweep".equals(KohakuViews.move(c)))
                    .checkServer("离僵尸远了", ctx -> !zombie.isAlive() || c.distanceTo(zombie) > 2.5)
                    .waitUntilServer("又拄着长杖", ctx -> !c.getMainHandItem().isEmpty())
                    .server("收掉僵尸、落地", ctx -> {
                        zombie.discard();
                        c.stayForTest(false);
                        ctx.player().getAbilities().flying = false;
                        ctx.player().onUpdateAbilities();
                    });
        }
    }

    // ---------------------------------------------------------------- 猫步

    @LDLRegisterClient(name = "kohaku_blink", group = "kohaku_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Blink extends KohakuMoveViews {
        private KohakuCompanion c;
        private Mob[] zombies;
        private int blinks;
        private Vec3 from;

        @Override
        public void define(ScenarioBuilder s) {
            kohakuStage(s, GameType.SURVIVAL).runCommand("difficulty normal")
                    .server("站好、召唤", ctx -> {
                        ServerPlayer p = ctx.player();
                        aim(p, SIDE_EYE, SIDE_LOOK);
                        c = summonKohaku(p, SPOT.add(0, 0, 4));
                    })
                    .step("对准", ctx -> aimClient(SIDE_LOOK))
                    .waitUntilServer("入场播完", ctx -> arrived(c))
                    .server("三只僵尸把她围住", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        c.topUp();
                        blinks = KohakuCompanion.blinks();
                        from = c.position();
                        zombies = new Mob[]{zombie(level, from.add(2, 0, 1), c), zombie(level, from.add(-2, 0, 1), c), zombie(level, from.add(0, 0, 2.5), c)};
                    })
                    .waitUntilServer("她蹲身一跃（或已经闪走了）", ctx -> "blink_out".equals(KohakuViews.move(c)) || KohakuCompanion.blinks() > blinks)
                    .ticks(4).screenshot("kohaku_blink_out")
                    .waitUntilServer("闪走了", ctx -> KohakuCompanion.blinks() > blinks)
                    .checkServer("落在 7 格以外", ctx -> c.position().distanceTo(from) > 7)
                    .ticks(2).screenshot("kohaku_blink_paws")
                    .ticks(8).screenshot("kohaku_blink_land")
                    .checkServer("落点附近敌人不超过一个", ctx -> {
                        int near = 0;
                        for (Mob z : zombies) if (z.distanceTo(c) < 5) near++;
                        return near <= 1;
                    })
                    .server("收掉僵尸", ctx -> {
                        for (Mob z : zombies) z.discard();
                    });
        }
    }

    // ---------------------------------------------------------------- 两个人的猫步

    @LDLRegisterClient(name = "kohaku_two_step", group = "kohaku_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class TwoStep extends KohakuMoveViews {
        private KohakuCompanion c;
        private Mob[] zombies;
        private int carries;
        private Vec3 from;

        @Override
        public void define(ScenarioBuilder s) {
            kohakuStage(s, GameType.SURVIVAL).runCommand("difficulty normal")
                    .server("站好、召唤、九枚爪之契", ctx -> {
                        ServerPlayer p = ctx.player();
                        aim(p, EYE, LOOK);
                        c = sealed(p, 9);
                    })
                    .step("对准", ctx -> aimClient(LOOK))
                    .waitUntilServer("入场播完", ctx -> arrived(c))
                    .server("主人只剩两成血，身边围着两只僵尸", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        ServerPlayer p = ctx.player();
                        c.topUp();
                        carries = KohakuCompanion.carries();
                        from = p.position();
                        p.setHealth(p.getMaxHealth() * .2f);
                        zombies = new Mob[]{zombie(level, from.add(1.8, 0, .5), p), zombie(level, from.add(-1.8, 0, .5), p)};
                    })
                    .waitUntilServer("她拉着主人一起闪走", ctx -> KohakuCompanion.carries() > carries)
                    .checkServer("主人也被带走了（离开原地 6 格以上）", ctx -> ctx.player().position().distanceTo(from) > 6)
                    .checkServer("她就在主人身边", ctx -> c.distanceTo(ctx.player()) < 4)
                    .ticks(6).screenshot("kohaku_two_step")
                    .server("收掉僵尸、回满血", ctx -> {
                        for (Mob z : zombies) z.discard();
                        ctx.player().setHealth(ctx.player().getMaxHealth());
                    });
        }
    }
}
