package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.cloudsea.CloudseaBlocks;
import dev.logos.dimension.companion.Companions;
import dev.logos.dimension.companion.Katashiro;
import dev.logos.dimension.companion.OwnerWatch;
import dev.logos.dimension.companion.ShiraumeCompanion;
import dev.logos.dimension.foxfire.FoxfireFlame;
import dev.logos.dimension.foxfire.FoxfireWraps;
import dev.logos.dimension.shiraume.FoxfireBolt;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 实际客户端检查白梅同伴（docs/SHIRAUME_COMPANION.md 第 7 节）。白瓷台上，镜头就是玩家的视线：
 * <ul>
 * <li>{@code companion_summon}：召唤（弹出来、行礼），走远后她跑过来跟上；
 * <li>{@code companion_fight}：两只尸壳冲着主人来，她放狐火弹；主人打强敌时她给主人的手缠上狐火；
 * <li>{@code companion_sleep}：主人睡下，她走到床边蜷起来睡；主人挂机时她挨着主人打盹；
 * <li>{@code companion_death}：受到致命一击，跪下、侧倒，消散前放下形代，形代被幽蓝狐火烧掉，魂火飞回主人身边。
 * </ul>
 */
public abstract class CompanionViews implements UIScenario {
    static final int FLOOR = 99;
    static final AABB AREA = new AABB(-14, FLOOR, -14, 15, FLOOR + 8, 15);
    /** 她站的地方：台子正中。 */
    static final Vec3 SPOT = new Vec3(.5, FLOOR + 1, .5);

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("shiraume_companion").defaultTimeoutMs(60_000).scenarioTimeoutMs(150_000);
    }

    /** 1600×900、隐藏界面；白瓷台；清掉台子上的东西；主人的契清零。 */
    static ScenarioBuilder stage(ScenarioBuilder s, GameType mode) {
        return stage(s, mode, 55);
    }

    /** {@code fov}：拍她近一点的时候收窄视角。 */
    static ScenarioBuilder stage(ScenarioBuilder s, GameType mode, int fov) {
        return s.setGameMode(mode).runCommand("time set day").runCommand("weather clear").runCommand("gamerule doDaylightCycle false")
                .step("预览镜头", ctx -> {
                    var mc = Minecraft.getInstance();
                    var window = mc.getWindow();
                    window.setWindowed(1600, 900);
                    window.setWidth(1600);
                    window.setHeight(900);
                    mc.getMainRenderTarget().resize(1600, 900);
                    mc.gameRenderer.resize(1600, 900);
                    mc.resizeGui();
                    mc.options.hideGui = true;
                    mc.options.fov().set(fov);
                    mc.options.renderDistance().set(8);
                    mc.options.broadcastOptions();
                })
                .server("白瓷台", ctx -> {
                    ServerLevel level = ctx.server().overworld();
                    for (int x = -14; x <= 15; x++)
                        for (int z = -14; z <= 15; z++) {
                            level.setBlockAndUpdate(new BlockPos(x, FLOOR, z), CloudseaBlocks.PORCELAIN.get().defaultBlockState());
                            for (int y = FLOOR + 1; y <= FLOOR + 8; y++) level.removeBlock(new BlockPos(x, y, z), false);
                        }
                    level.getEntitiesOfClass(Mob.class, AREA).forEach(e -> e.discard());
                    ServerPlayer p = ctx.player();
                    Companions.dismiss(p);
                    level.getEntitiesOfClass(ShiraumeCompanion.class, AREA).forEach(e -> e.discard());
                    p.setData(Companions.BOND, Companions.Bond.NONE);
                    p.getAbilities().flying = false;
                    p.onUpdateAbilities();
                });
    }

    /** 把主人放到 {@code eye}（眼睛的位置），看向 {@code look}；飞着，不会掉下去。 */
    static void aim(ServerPlayer p, Vec3 eye, Vec3 look) {
        if (p.isCreative()) {
            p.getAbilities().flying = true;
            p.onUpdateAbilities();
        }
        Vec3 d = look.subtract(eye);
        DimensionTravel.teleport(p, p.level(), eye.subtract(0, p.getEyeHeight(), 0), (float) Math.toDegrees(Math.atan2(-d.x, d.z)),
                (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z))));
    }

    /** 客户端里也对准一次（服务端的朝向同步过来之前）。 */
    static void aimClient(Vec3 look) {
        var p = Minecraft.getInstance().player;
        var d = look.subtract(p.getEyePosition());
        p.setYRot((float) Math.toDegrees(Math.atan2(-d.x, d.z)));
        p.setXRot((float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z))));
    }

    /** 在 {@code at} 召唤（面朝主人）。 */
    static ShiraumeCompanion summonAt(ServerPlayer p, Vec3 at) {
        Companions.summon(p, true, at);
        ShiraumeCompanion c = Companions.out(p);
        if (c == null) throw new IllegalStateException("没召唤出来");
        return c;
    }

    static List<ShiraumeCompanion> clientCompanions() {
        var level = Minecraft.getInstance().level;
        return level == null ? List.of() : level.getEntitiesOfClass(ShiraumeCompanion.class, AREA);
    }

    static ShiraumeCompanion server(ServerPlayer p) {
        return Companions.out(p);
    }

    // ---------------------------------------------------------------- 召唤与跟随

    @LDLRegisterClient(name = "companion_summon", group = "shiraume_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Summon extends CompanionViews {
        private static final Vec3 EYE = new Vec3(.5, FLOOR + 2.7, -4.6), LOOK = new Vec3(.5, FLOOR + 1.1, .5);
        private long summonedAt;

        @Override
        public void define(ScenarioBuilder s) {
            stage(s, GameType.CREATIVE, 40)
                    .server("站好", ctx -> aim(ctx.player(), EYE, LOOK)).ticks(10)
                    .step("对准", ctx -> aimClient(LOOK))
                    .server("召唤", ctx -> {
                        summonAt(ctx.player(), SPOT);
                        summonedAt = ctx.server().overworld().getGameTime();
                    })
                    .waitUntil("她同步到客户端", ctx -> !clientCompanions().isEmpty())
                    .check("入场时播的是 summon", ctx -> "summon".equals(clientCompanions().get(0).npcMiscAnimation()))
                    .waitUntilServer("弹出来的那一下", ctx -> ctx.server().overworld().getGameTime() - summonedAt >= 5)
                    .screenshot("companion_summon_pop")
                    .waitUntilServer("合手行礼", ctx -> ctx.server().overworld().getGameTime() - summonedAt >= 34)
                    .screenshot("companion_summon_greet")
                    .waitUntilServer("入场播完", ctx -> ctx.server().overworld().getGameTime() - summonedAt >= 64)
                    .check("入场播完后不再是 summon", ctx -> !"summon".equals(clientCompanions().get(0).npcMiscAnimation()))
                    // 主人走到台子对角约 18 格外：她跑过来跟上（9 格外跑，近了改走）
                    .server("主人走远", ctx -> aim(ctx.player(), new Vec3(-12.5, FLOOR + 2.7, 12.5), new Vec3(.5, FLOOR + 1, .5)))
                    .step("对准她", ctx -> aimClient(new Vec3(.5, FLOOR + 1, .5)))
                    .waitUntilServer("她开始跑", ctx -> server(ctx.player()) != null && server(ctx.player()).isSprinting())
                    .waitUntilServer("跑近了一些", ctx -> server(ctx.player()) != null && server(ctx.player()).distanceTo(ctx.player()) < 11.5)
                    .screenshot("companion_run")
                    .waitUntilServer("她跟到身边", ctx -> server(ctx.player()) != null && server(ctx.player()).distanceTo(ctx.player()) < 4.5)
                    .checkServer("跟到身边后不再跑", ctx -> server(ctx.player()) != null && server(ctx.player()).distanceTo(ctx.player()) < 6)
                    .teardown("恢复界面", ctx -> Minecraft.getInstance().options.hideGui = false);
        }
    }

    // ---------------------------------------------------------------- 战斗

    @LDLRegisterClient(name = "companion_fight", group = "shiraume_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Fight extends CompanionViews {
        private static final Vec3 EYE = new Vec3(-3.5, FLOOR + 4.6, -5.5), LOOK = new Vec3(1.5, FLOOR + 1, 2.5);
        private int husks = 2;
        private Mob ravager;

        @Override
        public void define(ScenarioBuilder s) {
            stage(s, GameType.SURVIVAL).runCommand("difficulty normal")
                    .server("主人站在够不着的石台上", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        for (int y = FLOOR + 1; y <= FLOOR + 2; y++) level.setBlockAndUpdate(new BlockPos(-4, y, -6), Blocks.SMOOTH_STONE.defaultBlockState());
                        ServerPlayer p = ctx.player();
                        p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 20 * 120, 4, false, false));
                        aim(p, EYE, LOOK);
                    }).ticks(10)
                    .step("对准", ctx -> aimClient(LOOK))
                    .server("召唤，再放两只尸壳", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        ServerPlayer p = ctx.player();
                        summonAt(p, new Vec3(-1.5, FLOOR + 1, -2.5));
                        for (int i = 0; i < husks; i++) {
                            Mob husk = EntityType.HUSK.create(level, EntitySpawnReason.COMMAND);
                            husk.snapTo(4.5 + i * 2, FLOOR + 1, 6.5, 180, 0);
                            husk.setTarget(p);
                            husk.setPersistenceRequired();
                            level.addFreshEntity(husk);
                        }
                    })
                    .waitUntilServer("她出手放狐火", ctx -> !ctx.server().overworld().getEntitiesOfClass(FoxfireBolt.class, AREA).isEmpty())
                    .ticks(3)
                    .screenshot("companion_cast")
                    .waitUntilServer("尸壳都被打倒", ctx -> ctx.server().overworld().getEntitiesOfClass(Mob.class, AREA,
                            m -> m.getType() == EntityType.HUSK && m.isAlive()).isEmpty())
                    .check("她没伤到主人以外的自己人（主人还活着）", ctx -> Minecraft.getInstance().player.isAlive())
                    // 强敌：主人先动手，她走过来给主人的手缠上狐火
                    .server("一只不动的劫掠兽，主人打它一下", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        ravager = EntityType.RAVAGER.create(level, EntitySpawnReason.COMMAND);
                        ravager.snapTo(2.5, FLOOR + 1, 7.5, 180, 0);
                        ravager.setNoAi(true);
                        ravager.setPersistenceRequired();
                        level.addFreshEntity(ravager);
                        ravager.hurtServer(level, level.damageSources().playerAttack(ctx.player()), 1);
                    })
                    .waitUntilServer("她给主人缠上淡粉狐火", ctx -> FoxfireWraps.flame(ctx.player()) == FoxfireFlame.SHIRAUME)
                    .teardown("收拾", ctx -> Minecraft.getInstance().options.hideGui = false);
        }
    }

    // ---------------------------------------------------------------- 睡觉

    @LDLRegisterClient(name = "companion_sleep", group = "shiraume_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Sleep extends CompanionViews {
        private static final BlockPos BED = new BlockPos(0, FLOOR + 1, 3);

        @Override
        public void define(ScenarioBuilder s) {
            stage(s, GameType.CREATIVE).runCommand("time set midnight").runCommand("difficulty peaceful")
                    .server("一张床，召唤", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        var bed = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH);
                        level.setBlockAndUpdate(BED, bed.setValue(BedBlock.PART, BedPart.FOOT));
                        level.setBlockAndUpdate(BED.south(), bed.setValue(BedBlock.PART, BedPart.HEAD));
                        ServerPlayer p = ctx.player();
                        p.getAbilities().flying = false;
                        p.onUpdateAbilities();
                        DimensionTravel.teleport(p, level, new Vec3(1.5, FLOOR + 1, 4.5), 90, 0);
                        summonAt(p, new Vec3(-1.5, FLOOR + 1, .5));
                    })
                    .waitUntilServer("入场播完", ctx -> server(ctx.player()) != null && !"summon".equals(server(ctx.player()).npcMiscAnimation()))
                    .server("主人睡下", ctx -> ctx.player().startSleepInBed(BED.south()))
                    .waitUntilServer("她在床边蜷起来睡", ctx -> server(ctx.player()) != null && "sleep".equals(server(ctx.player()).npcMiscAnimation()))
                    .check("她蜷着睡（客户端）", ctx -> !clientCompanions().isEmpty() && clientCompanions().get(0).curled())
                    // 截图：主人醒来站在旁边，算作挂机，她挨着主人继续打盹
                    .server("主人醒来、站着不动", ctx -> {
                        ServerPlayer p = ctx.player();
                        p.stopSleepInBed(true, true);
                        ShiraumeCompanion c = server(p);
                        Vec3 at = c.position();
                        aim(p, at.add(1.8, 2.2, -2.0), at.add(0, .25, 0));
                        OwnerWatch.pretendStill(p, OwnerWatch.AFK_TICKS + 100);
                    }).ticks(30)
                    .step("对准她", ctx -> {
                        if (!clientCompanions().isEmpty()) aimClient(clientCompanions().get(0).position().add(0, .25, 0));
                    }).ticks(20)
                    .screenshot("companion_sleep")
                    .teardown("恢复界面", ctx -> Minecraft.getInstance().options.hideGui = false);
        }
    }

    // ---------------------------------------------------------------- 离场

    @LDLRegisterClient(name = "companion_death", group = "shiraume_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Death extends CompanionViews {
        private static final Vec3 EYE = new Vec3(.5, FLOOR + 2.7, -3.4), LOOK = new Vec3(.5, FLOOR + .8, .5);
        private int spiritsBefore;

        @Override
        public void define(ScenarioBuilder s) {
            stage(s, GameType.CREATIVE, 32).runCommand("difficulty peaceful")
                    .server("站好，召唤", ctx -> {
                        aim(ctx.player(), EYE, LOOK);
                        summonAt(ctx.player(), SPOT);
                    })
                    .waitUntilServer("入场播完", ctx -> server(ctx.player()) != null && !"summon".equals(server(ctx.player()).npcMiscAnimation()))
                    .step("对准", ctx -> aimClient(LOOK)).ticks(10)
                    .server("致命一击", ctx -> {
                        spiritsBefore = FoxfireBolt.spirits();
                        ServerLevel level = ctx.server().overworld();
                        ShiraumeCompanion c = server(ctx.player());
                        c.hurtServer(level, level.damageSources().generic(), c.getMaxHealth() * 10);
                    })
                    .waitUntilServer("跪着含泪笑", ctx -> deathTime(ctx) >= 17)
                    .screenshot("companion_death_kneel")
                    .waitUntilServer("开始消散", ctx -> deathTime(ctx) >= 27)
                    .screenshot("companion_death_dissolve")
                    .waitUntilServer("魂火飞出", ctx -> FoxfireBolt.spirits() > spiritsBefore)
                    .waitUntilServer("形代烧起来", ctx -> ctx.server().overworld().getEntitiesOfClass(Katashiro.class, AREA,
                            k -> k.tickCount >= 26).size() > 0)
                    .step("对准形代", ctx -> {
                        var level = Minecraft.getInstance().level;
                        var list = level.getEntitiesOfClass(Katashiro.class, AREA);
                        if (!list.isEmpty()) aimClient(list.get(0).position());
                    })
                    .screenshot("companion_katashiro_burn")
                    .checkServer("她倒下后要休息", ctx -> Companions.restLeft(ctx.player()) > 0)
                    .checkServer("只飞出一缕魂火", ctx -> FoxfireBolt.spirits() == spiritsBefore + 1)
                    .waitUntilServer("她离开了", ctx -> ctx.server().overworld().getEntitiesOfClass(ShiraumeCompanion.class, AREA).isEmpty())
                    .checkServer("休息时叫不出来", ctx -> Companions.summon(ctx.player(), true) != null)
                    .teardown("恢复界面", ctx -> Minecraft.getInstance().options.hideGui = false);
        }

        private static int deathTime(ServerContext ctx) {
            var list = ctx.server().overworld().getEntitiesOfClass(ShiraumeCompanion.class, AREA);
            return list.isEmpty() ? Integer.MAX_VALUE : list.get(0).deathTime;
        }
    }
}
