package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.client.companion.CompanionPanelScreen;
import dev.logos.dimension.companion.CompanionEntity;
import dev.logos.dimension.companion.CompanionMind;
import dev.logos.dimension.companion.CompanionPouch;
import dev.logos.dimension.companion.Companions;
import dev.logos.dimension.companion.DragonScaleItem;
import dev.logos.dimension.companion.LacertaCompanion;
import dev.logos.dimension.companion.LacertaDays;
import dev.logos.dimension.item.CrimsonFlameLongswordItem;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * 实际客户端检查拉塞塔（docs/LACERTA_COMPANION.md，接入方案阶段 A），一组 {@code lacerta_companion}：
 * <ul>
 * <li>{@code lacerta_summon}：还没立契时右键一片空白的誓约逆鳞——鳞上烙下名字、立契，她从天上落下来、展开翅膀；平时不拔剑；再用一次收回。</li>
 * <li>{@code lacerta_fight}：十几格外两只僵尸冲主人来：她拔剑、一声龙吟把它们引到自己身上、换着招砍倒；主人一直没挨她的打；打完收剑。</li>
 * <li>{@code lacerta_skills}：站着不动的靶子，逐一让她出破晓炎枪、炽焰旋风斩、红莲劫焰斩、炽翼坠星破、逆鳞十文字，拍每一招的特效。</li>
 * <li>{@code lacerta_flame}：站在高台上够不着的僵尸：她从左掌心抛出龙炎弹打中它（看弹体的颜色）。</li>
 * <li>{@code lacerta_withdraw}：挨了致命的一下不死：受击、「最后的骑士」、化作火星回到鳞里；鳞上转起冷却，叫不出来。</li>
 * <li>{@code lacerta_wings}：头顶压着顶棚时收起翅膀，拆掉顶棚又展开。</li>
 * <li>{@code lacerta_sleep_watch} / {@code _doze} / {@code _rest} / {@code _together}：你睡下，她按这一档在床边摆好（位置、朝向、动画）；你醒来她起身。</li>
 * <li>{@code lacerta_talk}：潜行右键说话（中文拍）；她自己的话题、右肩甲、看四样东西的点评；详细数据三页。</li>
 * </ul>
 */
public abstract class LacertaViews extends KohakuSleepViews {
    /** 主人站在台子南边看她：她在台子正中。 */
    static final Vec3 EYE = new Vec3(.5, FLOOR + 2.62, -4.5), LOOK = new Vec3(.5, FLOOR + 1.3, .5);
    /** 招式（测试里数每一招出过几次）。 */
    static final List<String> MOVES = List.of("cleave_1", "rend_2", "ascend_3", "cataclysm_4", "crimson_thrust", "vortex_rush", "flame_sweep",
            "skyfall_judgement", "cross_rend", "dragon_fang");

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("lacerta_companion").defaultTimeoutMs(60_000).scenarioTimeoutMs(180_000);
    }

    /** 台子；清掉拉塞塔、她的契、龙鳞的冷却和背包；别的怪也清掉。 */
    static ScenarioBuilder lacertaStage(ScenarioBuilder s, GameType mode) {
        return stage(s, mode).server("清掉拉塞塔", ctx -> {
            ServerPlayer p = ctx.player();
            ServerLevel level = ctx.server().overworld();
            Companions.dismiss(p, Companions.LACERTA_DEF);
            level.getEntitiesOfClass(LacertaCompanion.class, AREA.inflate(8)).forEach(Entity::discard);
            level.getEntitiesOfClass(Mob.class, AREA.inflate(24), m -> !(m instanceof CompanionEntity)).forEach(Entity::discard);
            p.setData(Companions.LACERTA_BOND, Companions.Bond.NONE);
            p.setData(Companions.LACERTA_POUCH, new CompanionPouch());
            p.setData(Companions.LACERTA_DAYS, LacertaDays.NONE);
            LacertaCompanion.sleepForTest(null);
            LacertaCompanion.forceMoveForTest(null);
            p.getCooldowns().removeCooldown(p.getCooldowns().getCooldownGroup(DragonScaleItem.blank()));
            p.getInventory().clearContent();
            p.removeAllEffects();
            p.setHealth(p.getMaxHealth());
            p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 20 * 150, 4, false, false));
        });
    }

    /** 立契并在 {@code at} 叫她出来。 */
    static LacertaCompanion summonLacerta(ServerPlayer p, Vec3 at) {
        Companions.grantPact(p, Companions.LACERTA_DEF);
        Companions.summon(p, Companions.LACERTA_DEF, at);
        LacertaCompanion c = Companions.lacerta(p);
        if (c == null) throw new IllegalStateException("没召唤出来");
        return c;
    }

    /** 入场动画播完。 */
    static boolean arrived(LacertaCompanion c) {
        return c.tickCount > 80 && !"summon".equals(misc(c));
    }

    static String misc(LacertaCompanion c) {
        String m = c.npcMiscAnimation();
        return m == null ? "" : m;
    }

    static CompanionMind mindOf(LacertaCompanion c) {
        return ((CompanionEntity) c).mind();
    }

    /** 一只站着不动、血很厚的僵尸（招式、龙炎弹的靶子）。 */
    static Mob dummy(ServerLevel level, Vec3 at, float yaw, double health) {
        Mob m = PrincessViews.helmeted(CompanionLifeViews.spawn(level, EntityType.ZOMBIE, at, yaw));
        m.setNoAi(true);
        m.getAttribute(Attributes.MAX_HEALTH).setBaseValue(health);
        m.setHealth((float) health);
        return m;
    }

    // ---------------------------------------------------------------- 机位

    private static int camera = -1;

    /** 从 {@code at} 看向 {@code look} 的一个隐形机位，切过去截一张（{@link #back} 切回主人）。 */
    static ScenarioBuilder shoot(ScenarioBuilder s, String name, Vec3 at, Vec3 look) {
        return shoot(s, name, () -> new Vec3[]{at, look});
    }

    /** 对着她拍：她身前偏 {@code side} 度、{@code dist} 格远、高 {@code height} 格，看着她胸口。 */
    static ScenarioBuilder shootHer(ScenarioBuilder s, String name, java.util.function.Supplier<LacertaCompanion> who, double side, double dist,
                                    double height) {
        return shoot(s, name, () -> {
            LacertaCompanion c = who.get();
            double yaw = Math.toRadians(c.yBodyRot + side);
            Vec3 at = c.position().add(-Math.sin(yaw) * dist, height, Math.cos(yaw) * dist);
            return new Vec3[]{at, c.position().add(0, .9, 0)};
        });
    }

    /** 机位和看的地方在服务端那一刻才算（{@code where} 给 [机位, 看哪]）。 */
    static ScenarioBuilder shoot(ScenarioBuilder s, String name, java.util.function.Supplier<Vec3[]> where) {
        return s.server("机位 " + name, ctx -> {
            Vec3[] w = where.get();
            Vec3 at = w[0], look = w[1];
            ServerLevel level = ctx.server().overworld();
            var old = camera < 0 ? null : level.getEntity(camera);
            if (old != null) old.discard();
            ArmorStand stand = EntityType.ARMOR_STAND.create(level, EntitySpawnReason.COMMAND);
            stand.setInvisible(true);
            stand.setNoGravity(true);
            double dx = look.x - at.x, dy = look.y - at.y, dz = look.z - at.z;
            stand.snapTo(at.x, at.y - stand.getEyeHeight(), at.z, (float) -Math.toDegrees(Math.atan2(dx, dz)),
                    (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz))));
            level.addFreshEntity(stand);
            camera = stand.getId();
        }).waitUntil("机位同步 " + name, ctx -> Minecraft.getInstance().level.getEntity(camera) != null)
                .step("切到机位 " + name, ctx -> {
                    var mc = Minecraft.getInstance();
                    Entity stand = mc.level.getEntity(camera);
                    stand.setYHeadRot(stand.getYRot());
                    stand.yRotO = stand.getYRot();
                    stand.xRotO = stand.getXRot();
                    mc.setCameraEntity(stand);
                    mc.options.hideGui = true;
                }).ticks(3)
                // 床上的聊天框关不得（一关主人就起床了），只清掉聊天记录
                .step("清掉聊天记录", ctx -> Minecraft.getInstance().gui.getChat().clearMessages(false)).screenshot(name);
    }

    static ScenarioBuilder back(ScenarioBuilder s) {
        return s.step("回到主人", ctx -> {
            var mc = Minecraft.getInstance();
            mc.setCameraEntity(mc.player);
            mc.options.hideGui = false;
        }).server("移除机位", ctx -> {
            var e = camera < 0 ? null : ctx.server().overworld().getEntity(camera);
            if (e != null) e.discard();
            camera = -1;
        });
    }

    // ---------------------------------------------------------------- 召唤

    @LDLRegisterClient(name = "lacerta_summon", group = "lacerta_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Summon extends LacertaViews {
        private LacertaCompanion c;

        @Override
        public void define(ScenarioBuilder s) {
            lacertaStage(s, GameType.SURVIVAL).runCommand("difficulty peaceful")
                    .server("站好，手里一片空白的誓约逆鳞", ctx -> {
                        ServerPlayer p = ctx.player();
                        aim(p, EYE, LOOK);
                        p.setItemInHand(InteractionHand.MAIN_HAND, DragonScaleItem.blank());
                    })
                    .step("对准", ctx -> aimClient(LOOK))
                    .ticks(10)
                    .server("右键用它", ctx -> {
                        ServerPlayer p = ctx.player();
                        DragonScaleItem.ITEM.get().use(p.level(), p, InteractionHand.MAIN_HAND);
                        c = Companions.lacerta(p);
                        if (c != null) c.teleportTo(SPOT.x, SPOT.y, SPOT.z);
                    })
                    .checkServer("立了契，这一片烙上了主人的名字", ctx -> {
                        ServerPlayer p = ctx.player();
                        return Companions.bond(p, Companions.LACERTA_DEF).pact() && DragonScaleItem.boundTo(p.getMainHandItem(), p) && c != null;
                    })
                    .waitUntilServer("她在入场", ctx -> "summon".equals(misc(c)))
                    .ticks(5).screenshot("lacerta_summon_dive")
                    .ticks(14).screenshot("lacerta_summon_land")
                    .ticks(14).screenshot("lacerta_summon_unfurl")
                    .waitUntilServer("入场播完", ctx -> arrived(c))
                    .checkServer("太平的时候不拔剑，翅膀展开着", ctx -> !c.swordDrawnForTest() && !c.wingsFolded())
                    .ticks(20).screenshot("lacerta_summon_idle")
                    .server("再用一次龙鳞：收回", ctx -> {
                        ServerPlayer p = ctx.player();
                        p.getCooldowns().removeCooldown(p.getCooldowns().getCooldownGroup(p.getMainHandItem()));
                        DragonScaleItem.ITEM.get().use(p.level(), p, InteractionHand.MAIN_HAND);
                    })
                    .waitUntilServer("她回到鳞里", ctx -> c.isRemoved() && Companions.lacerta(ctx.player()) == null)
                    .checkServer("收回不算倒下：不用歇", ctx -> Companions.restLeft(ctx.player(), Companions.LACERTA_DEF) == 0);
        }
    }

    // ---------------------------------------------------------------- 打

    @LDLRegisterClient(name = "lacerta_fight", group = "lacerta_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Fight extends LacertaViews {
        private static final Vec3 SIDE_EYE = new Vec3(-7.5, FLOOR + 3.2, 2.5), SIDE_LOOK = new Vec3(.5, FLOOR + 1.3, 6.5);
        private final Map<String, Integer> before = new HashMap<>();
        private LacertaCompanion c;
        private Mob z1, z2;
        private int draws, taunts, landed;
        private float health;

        @Override
        public void define(ScenarioBuilder s) {
            lacertaStage(s, GameType.SURVIVAL).runCommand("difficulty normal")
                    .server("站好、召唤", ctx -> {
                        ServerPlayer p = ctx.player();
                        aim(p, SIDE_EYE, SIDE_LOOK);
                        c = summonLacerta(p, SPOT);
                    })
                    .step("对准", ctx -> aimClient(SIDE_LOOK))
                    .waitUntilServer("入场播完", ctx -> arrived(c))
                    .ticks(20)
                    .server("十几格外两只僵尸冲着主人来", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        ServerPlayer p = ctx.player();
                        health = p.getHealth();
                        draws = LacertaCompanion.draws();
                        taunts = LacertaCompanion.taunts();
                        landed = LacertaCompanion.landed();
                        for (String m : MOVES) before.put(m, LacertaCompanion.started(m));
                        z1 = PrincessViews.helmeted(CompanionLifeViews.spawn(level, EntityType.ZOMBIE, new Vec3(-1.5, FLOOR + 1, 13.5), 180));
                        z2 = PrincessViews.helmeted(CompanionLifeViews.spawn(level, EntityType.ZOMBIE, new Vec3(3.5, FLOOR + 1, 14.5), 180));
                        z1.setTarget(p);
                        z2.setTarget(p);
                    })
                    .waitUntilServer("她拔剑", ctx -> LacertaCompanion.draws() > draws)
                    .ticks(6);
            shootHer(s, "lacerta_fight_draw", () -> c, 25, 3.2, 1.2)
                    .waitUntilServer("一声龙吟，僵尸转头冲她", ctx -> LacertaCompanion.taunts() > taunts
                            && (z1.getTarget() == c || z2.getTarget() == c || !z1.isAlive() || !z2.isAlive()));
            back(shootHer(s, "lacerta_fight_taunt", () -> c, -30, 3.6, 1.3))
                    .waitUntilServer("剑砍中了", ctx -> LacertaCompanion.landed() > landed)
                    .ticks(2).screenshot("lacerta_fight_hit")
                    .waitUntilServer("两只僵尸都倒下", ctx -> !z1.isAlive() && !z2.isAlive())
                    .checkServer("主人一直没挨她的打", ctx -> ctx.player().getHealth() >= health)
                    .checkServer("至少换了两种招", ctx -> MOVES.stream().filter(m -> LacertaCompanion.started(m) > before.get(m)).count() >= 2)
                    .waitUntilServer("打完收剑", ctx -> !c.swordDrawnForTest())
                    .ticks(30).screenshot("lacerta_fight_after");
        }
    }

    // ---------------------------------------------------------------- 每一招的特效

    @LDLRegisterClient(name = "lacerta_skills", group = "lacerta_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Skills extends LacertaViews {
        private static final Vec3 SIDE_EYE = new Vec3(-8.5, FLOOR + 3.4, 3.5), SIDE_LOOK = new Vec3(.5, FLOOR + 1.2, 5.5);
        private static final String[] SHOW = {"crimson_thrust", "vortex_rush", "flame_sweep", "skyfall_judgement", "cross_rend"};
        private LacertaCompanion c;
        private Mob a, b, d;
        private int count;

        @Override
        public void define(ScenarioBuilder s) {
            lacertaStage(s, GameType.SURVIVAL).runCommand("difficulty normal")
                    .server("站好、召唤；前面一排站着不动的僵尸", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        ServerPlayer p = ctx.player();
                        aim(p, SIDE_EYE, SIDE_LOOK);
                        c = summonLacerta(p, SPOT);
                        a = dummy(level, new Vec3(.5, FLOOR + 1, 7.5), 180, 400);
                        b = dummy(level, new Vec3(-1.5, FLOOR + 1, 8.5), 180, 400);
                        d = dummy(level, new Vec3(2.5, FLOOR + 1, 8.5), 180, 400);
                    })
                    .step("对准", ctx -> aimClient(SIDE_LOOK))
                    .waitUntilServer("入场播完、拔剑", ctx -> arrived(c) && c.swordDrawnForTest());
            for (String move : SHOW) {
                s.server("下一招：" + move, ctx -> {
                            count = LacertaCompanion.started(move);
                            a.setHealth(a.getMaxHealth());
                            b.setHealth(b.getMaxHealth());
                            d.setHealth(d.getMaxHealth());
                            LacertaCompanion.forceMoveForTest(move);
                        })
                        .waitUntilServer("出了 " + move, ctx -> LacertaCompanion.started(move) > count)
                        .ticks(6).screenshot("lacerta_skill_" + move + "_a")
                        .ticks(6).screenshot("lacerta_skill_" + move + "_b")
                        .waitUntilServer(move + " 收势", ctx -> !move.equals(c.moveForTest()));
            }
            s.teardown("不再强行定", ctx -> LacertaCompanion.forceMoveForTest(null));
        }
    }

    // ---------------------------------------------------------------- 掌心赤焰

    @LDLRegisterClient(name = "lacerta_flame", group = "lacerta_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Flame extends LacertaViews {
        private static final BlockPos PILLAR = new BlockPos(0, FLOOR + 1, 9);
        private static final Vec3 SIDE_EYE = new Vec3(-7.5, FLOOR + 3.6, 3.5), SIDE_LOOK = new Vec3(.5, FLOOR + 3, 5.5);
        private LacertaCompanion c;
        private Mob z;
        private int flames;

        @Override
        public void define(ScenarioBuilder s) {
            lacertaStage(s, GameType.SURVIVAL).runCommand("difficulty normal")
                    .server("站好、召唤", ctx -> {
                        ServerPlayer p = ctx.player();
                        aim(p, SIDE_EYE, SIDE_LOOK);
                        c = summonLacerta(p, SPOT);
                    })
                    .step("对准", ctx -> aimClient(SIDE_LOOK))
                    .waitUntilServer("入场播完", ctx -> arrived(c))
                    .server("九格外一根四格高的石柱，柱顶站着一只僵尸", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        for (int y = 0; y < 4; y++) level.setBlockAndUpdate(PILLAR.above(y), Blocks.STONE_BRICKS.defaultBlockState());
                        z = dummy(level, Vec3.atBottomCenterOf(PILLAR.above(4)), 180, 200);
                        flames = LacertaCompanion.flames();
                    })
                    .waitUntilServer("她抬起左手抛出龙炎弹", ctx -> "flame_throw".equals(misc(c)))
                    .ticks(7).screenshot("lacerta_flame_throw")
                    .ticks(3).screenshot("lacerta_flame_bolt")
                    .waitUntilServer("放出去了", ctx -> LacertaCompanion.flames() > flames)
                    .waitUntilServer("打中柱顶的僵尸", ctx -> z.getHealth() < z.getMaxHealth())
                    .checkServer("龙炎弹不点方块：柱顶周围没有火", ctx -> BlockPos.betweenClosedStream(PILLAR.offset(-2, 2, -2), PILLAR.offset(2, 6, 2))
                            .noneMatch(at -> ctx.server().overworld().getBlockState(at).is(Blocks.FIRE)))
                    .teardown("拆掉石柱", ctx -> {
                        for (int y = 0; y < 5; y++) ctx.server().overworld().removeBlock(PILLAR.above(y), false);
                    });
        }
    }

    // ---------------------------------------------------------------- 龙眠

    @LDLRegisterClient(name = "lacerta_withdraw", group = "lacerta_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Withdraw extends LacertaViews {
        private LacertaCompanion c;
        private int withdrawn;

        @Override
        public void define(ScenarioBuilder s) {
            lacertaStage(s, GameType.SURVIVAL).runCommand("difficulty peaceful")
                    .server("站好、召唤", ctx -> {
                        ServerPlayer p = ctx.player();
                        aim(p, EYE, LOOK);
                        c = summonLacerta(p, SPOT);
                        p.setItemInHand(InteractionHand.MAIN_HAND, DragonScaleItem.bound(p));
                    })
                    .step("对准", ctx -> aimClient(LOOK))
                    .waitUntilServer("入场播完", ctx -> arrived(c))
                    .server("她挨了致命的一下", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        withdrawn = LacertaCompanion.withdrawn();
                        c.invulnerableTime = 0;
                        c.hurtServer(level, level.damageSources().generic(), 1000);
                    })
                    .checkServer("没有死：她在撤退", ctx -> c.isAlive() && !c.isDeadOrDying() && c.withdrawing())
                    .checkServer("撤退时打不到", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        c.invulnerableTime = 0;
                        return !c.hurtServer(level, level.damageSources().generic(), 5);
                    })
                    .waitUntilServer("受击以后单膝跪下（最后的骑士）", ctx -> "death".equals(misc(c)))
                    .ticks(24).screenshot("lacerta_withdraw_kneel")
                    .ticks(50).screenshot("lacerta_withdraw_ember")
                    .ticks(34).screenshot("lacerta_withdraw_salute")
                    .ticks(12).screenshot("lacerta_withdraw_sparks")
                    .waitUntilServer("化作火星回到鳞里", ctx -> c.isRemoved() && LacertaCompanion.withdrawn() > withdrawn)
                    .checkServer("要歇五分钟，不会自动叫回", ctx -> {
                        ServerPlayer p = ctx.player();
                        var b = Companions.bond(p, Companions.LACERTA_DEF);
                        return Companions.restLeft(p, Companions.LACERTA_DEF) > 5800 && !b.comeBack() && b.out().isEmpty();
                    })
                    .checkServer("龙鳞上转起冷却", ctx -> ctx.player().getCooldowns().isOnCooldown(DragonScaleItem.blank()))
                    .checkServer("还在歇着，叫不出来", ctx -> Companions.summon(ctx.player(), Companions.LACERTA_DEF, null) != null);
        }
    }

    // ---------------------------------------------------------------- 收翅

    @LDLRegisterClient(name = "lacerta_wings", group = "lacerta_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Wings extends LacertaViews {
        /** 从她背后斜着看翅膀（她面朝北边的主人）。 */
        private static final Vec3 BACK_EYE = new Vec3(2.5, FLOOR + 2.2, 4.5);
        private LacertaCompanion c;
        /** 顶棚盖在她站的那一格上方。 */
        private final BlockPos under = BlockPos.containing(SPOT);

        private void ceiling(ServerLevel level, boolean on) {
            for (int x = -1; x <= 1; x++)
                for (int z = -1; z <= 1; z++) {
                    BlockPos at = new BlockPos(under.getX() + x, FLOOR + 3, under.getZ() + z);
                    if (on) level.setBlockAndUpdate(at, Blocks.SMOOTH_STONE.defaultBlockState());
                    else level.removeBlock(at, false);
                }
        }

        @Override
        public void define(ScenarioBuilder s) {
            lacertaStage(s, GameType.CREATIVE).runCommand("difficulty peaceful")
                    .server("站好、召唤，让她留在原地", ctx -> {
                        ServerPlayer p = ctx.player();
                        aim(p, EYE, LOOK);
                        c = summonLacerta(p, SPOT);
                    })
                    .step("对准", ctx -> aimClient(LOOK))
                    .waitUntilServer("入场播完", ctx -> arrived(c))
                    .server("留在原地", ctx -> c.stayForTest(true))
                    .waitUntilServer("心智换成留在原地", ctx -> mindOf(c).current() == CompanionMind.Kind.STAY)
                    .server("站回台子正中（她会先朝主人走两步）", ctx -> {
                        c.getNavigation().stop();
                        c.teleportTo(SPOT.x, SPOT.y, SPOT.z);
                    })
                    .ticks(20)
                    .checkServer("开阔的地方翅膀展开", ctx -> !c.wingsFolded())
                    .server("头顶两格压一块顶棚", ctx -> ceiling(ctx.server().overworld(), true))
                    .waitUntilServer("收起翅膀", ctx -> c.wingsFolded());
            shoot(s.ticks(30), "lacerta_wings_folded", BACK_EYE, LOOK)
                    .server("拆掉顶棚", ctx -> ceiling(ctx.server().overworld(), false))
                    .waitUntilServer("又展开", ctx -> !c.wingsFolded());
            back(shoot(s.ticks(30), "lacerta_wings_open", BACK_EYE, LOOK))
                    .checkServer("还站在顶棚底下那一片（展开是因为拆了顶棚，不是走出去了）",
                            ctx -> Math.abs(c.getX() - (under.getX() + .5)) < 1.4 && Math.abs(c.getZ() - (under.getZ() + .5)) < 1.4)
                    .teardown("拆掉顶棚", ctx -> ceiling(ctx.server().overworld(), false));
        }
    }

    // ---------------------------------------------------------------- 枕边守夜

    /** 你睡下（夜不会过去），她按 {@code tier} 在床边摆好；拍一张；你醒来，她起身。 */
    abstract static class Vigil extends LacertaViews {
        private LacertaCompanion c;

        abstract String tier();

        /** 她摆好以后 misc 播的（共枕先躺下）。 */
        String clip() {
            return switch (tier()) {
                case "watch" -> "sleep_watch";
                case "doze" -> "sleep";
                case "rest" -> "sleep_rest";
                default -> "sleep_together_down";
            };
        }

        /** 床头那一格西边（床朝南，顺时针那一侧）。 */
        static final Vec3 SIDE = Vec3.atBottomCenterOf(HEAD.west());

        @Override
        public void define(ScenarioBuilder s) {
            lacertaStage(s, GameType.SURVIVAL).runCommand("difficulty peaceful").runCommand("time set midnight").runCommand("gamerule advance_time false")
                    .runCommand("gamerule players_sleeping_percentage 101")
                    .server("一张床，站在床边；她在旁边", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        ServerPlayer p = ctx.player();
                        bed(level, FOOT);
                        standAt(p, BEDSIDE);
                        c = summonLacerta(p, new Vec3(-2.5, FLOOR + 1, 4.5));
                        LacertaCompanion.sleepForTest(tier());
                        p.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 20 * 120, 0, false, false));
                    })
                    .waitUntilServer("入场播完", ctx -> arrived(c))
                    .server("看着床", ctx -> aim(ctx.player(), BEDSIDE.add(0, 1.62, 0), Vec3.atCenterOf(HEAD)))
                    .checkServer("主人躺下", ctx -> sleep(ctx.player()))
                    .waitUntilServer("她在床边摆好", ctx -> tier().equals(c.poseForTest()))
                    .checkServer("在床头那一格的西边", ctx -> Math.abs(c.getX() - SIDE.x) < .3 && Math.abs(c.getZ() - SIDE.z) < .6)
                    .checkServer("朝向对", ctx -> {
                        Vec3 toBed = new Vec3(1, 0, 0);
                        double yaw = Math.toRadians(c.yBodyRot);
                        Vec3 front = new Vec3(-Math.sin(yaw), 0, Math.cos(yaw)), left = new Vec3(Math.cos(yaw), 0, Math.sin(yaw));
                        return switch (tier()) {
                            case "watch" -> front.dot(toBed) < -.7;
                            case "rest" -> left.dot(toBed) > .7;
                            default -> front.dot(toBed) > .7;
                        };
                    })
                    .waitUntilServer("播这一档的动画", ctx -> clip().equals(misc(c)));
            shoot(s.ticks(20), "lacerta_sleep_" + tier(), SIDE.add(-2.2, 1.7, -2.4), SIDE.add(.3, .5, .4));
            if (tier().equals("together")) {
                s.waitUntilServer("躺下以后睡着", ctx -> "sleep_together".equals(misc(c)));
                shoot(s.ticks(30), "lacerta_sleep_together_loop", SIDE.add(-1.6, 2.2, -1.2), SIDE.add(.6, .3, .3));
            }
            back(s).server("主人醒来", ctx -> ctx.player().stopSleepInBed(true, true))
                    .waitUntilServer("她起身（不再是这一档的睡姿；之后可能去你身边坐下）", ctx -> !c.poseForTest().equals(tier()))
                    .waitUntilServer("起身的样子", ctx -> switch (tier()) {
                        case "watch" -> !"sleep_watch".equals(misc(c));
                        case "together" -> "sleep_together_up".equals(misc(c));
                        default -> "act_stretch".equals(misc(c));
                    })
                    .teardown("不再强行定；夜晚照常过去", ctx -> {
                        LacertaCompanion.sleepForTest(null);
                        var server = ctx.server();
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "gamerule players_sleeping_percentage 100");
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "gamerule advance_time true");
                    });
        }
    }

    @LDLRegisterClient(name = "lacerta_sleep_watch", group = "lacerta_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class SleepWatch extends Vigil {
        @Override String tier() { return "watch"; }
    }

    @LDLRegisterClient(name = "lacerta_sleep_doze", group = "lacerta_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class SleepDoze extends Vigil {
        @Override String tier() { return "doze"; }
    }

    @LDLRegisterClient(name = "lacerta_sleep_rest", group = "lacerta_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class SleepRest extends Vigil {
        @Override String tier() { return "rest"; }
    }

    @LDLRegisterClient(name = "lacerta_sleep_together", group = "lacerta_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class SleepTogether extends Vigil {
        @Override String tier() { return "together"; }
    }

    // ---------------------------------------------------------------- 说话、面板

    @LDLRegisterClient(name = "lacerta_talk", group = "lacerta_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Talk extends LacertaViews {
        private static final Vec3 TALK_EYE = new Vec3(.5, FLOOR + 2.62, -1.9), AT = new Vec3(.5, FLOOR + 1, 1.2);
        private LacertaCompanion c;
        private java.util.concurrent.CompletableFuture<Void> reload = java.util.concurrent.CompletableFuture.completedFuture(null);

        private void language(String code) {
            var mc = Minecraft.getInstance();
            mc.getLanguageManager().setSelected(code);
            mc.options.languageCode = code;
            reload = mc.reloadResourcePacks();
        }

        /** 拿着 {@code held} 让她看，她的点评是 {@code verdict}。 */
        private static ScenarioBuilder critique(ScenarioBuilder s, ItemStack held, String verdict) {
            return s.server("拿出来：" + verdict, ctx -> ctx.player().setItemInHand(InteractionHand.MAIN_HAND, held.copy()))
                    .step("帮我看看这把武器", ctx -> CompanionTalkViews.talk().choose("chat.weapon"))
                    .waitUntil("她点评：" + verdict, ctx -> CompanionTalkViews.said("weapon_" + verdict))
                    .step("跳过", ctx -> {
                        CompanionTalkViews.talk().skip();
                        CompanionTalkViews.talk().skip();
                    })
                    .waitUntil("回到话题", ctx -> CompanionTalkViews.talk().options().contains("chat.weapon"));
        }

        @Override
        public void define(ScenarioBuilder s) {
            s.step("切成中文", ctx -> language("zh_cn")).waitUntil("资源重载完", ctx -> reload.isDone());
            lacertaStage(s, GameType.SURVIVAL).runCommand("difficulty peaceful")
                    .server("站好、召唤", ctx -> {
                        ServerPlayer p = ctx.player();
                        p.removeAllEffects();
                        aim(p, TALK_EYE, AT.add(0, .9, 0));
                        c = summonLacerta(p, AT);
                    })
                    .step("对准", ctx -> aimClient(AT.add(0, .9, 0)))
                    .waitUntilServer("入场播完", ctx -> arrived(c))
                    .server("潜行右键她", ctx -> {
                        ServerPlayer p = ctx.player();
                        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                        p.setShiftKeyDown(true);
                        c.interact(p, InteractionHand.MAIN_HAND, Vec3.ZERO);
                        p.setShiftKeyDown(false);
                    })
                    .waitUntil("对话打开", ctx -> CompanionTalkViews.talk() != null)
                    .check("开口第一句是招呼", ctx -> CompanionTalkViews.talk().lineKey() != null && CompanionTalkViews.talk().lineKey().contains(".talk.greet_"))
                    .ticks(20)
                    .checkServer("她站住、看着主人", ctx -> c.talking() && mindOf(c).current() == CompanionMind.Kind.TALK)
                    .step("字出完", ctx -> CompanionTalkViews.talk().skip()).ticks(6)
                    .screenshot("lacerta_talk_open")
                    .step("聊天", ctx -> CompanionTalkViews.talk().choose("chat")).ticks(6)
                    .check("她自己的话题", ctx -> CompanionTalkViews.talk().options().equals(List.of("chat.scale", "chat.food", "chat.slept",
                            "chat.pauldron", "chat.knight", "chat.cold", "chat.airships", "chat.praise", "chat.weapon", "chat.past", "chat.seals", "chat.us",
                            "chat.rest", "back")))
                    .step("右肩甲的来历", ctx -> CompanionTalkViews.talk().choose("chat.pauldron"))
                    .waitUntil("她回话", ctx -> CompanionTalkViews.said("pauldron_1"))
                    .step("跳到最后", ctx -> CompanionTalkViews.talk().skip()).ticks(6)
                    .screenshot("lacerta_talk_pauldron")
                    .step("跳过", ctx -> {
                        CompanionTalkViews.talk().skip();
                        CompanionTalkViews.talk().skip();
                        CompanionTalkViews.talk().skip();
                    })
                    .waitUntil("回到话题", ctx -> CompanionTalkViews.talk().options().contains("chat.weapon"));
            critique(s, new ItemStack(Items.DIAMOND_SWORD), "melee");
            critique(s, new ItemStack(CrimsonFlameLongswordItem.ITEM.get()), "crimson");
            critique(s, new ItemStack(Items.BOW), "ranged");
            critique(s, new ItemStack(Items.BREAD), "joke");
            ItemStack worn = new ItemStack(Items.IRON_SWORD);
            worn.setDamageValue(worn.getMaxDamage() - 5);
            critique(s, worn, "broken")
                    .server("空手", ctx -> ctx.player().setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY))
                    .step("返回 → 详细数据", ctx -> {
                        CompanionTalkViews.talk().choose("back");
                        CompanionTalkViews.talk().choose("stats");
                    })
                    .waitUntil("角色面板打开", ctx -> CompanionTalkViews.panel() != null)
                    .ticks(20)
                    .check("面板上的数：生命 90（自己 80，继承主人 20 的一半）、护甲 14、韧性 6", ctx -> {
                        var sheet = CompanionTalkViews.panel().parent().sheet();
                        return Math.abs(sheet.stat("health").value() - 90) < .01 && Math.abs(sheet.stat("armor").value() - 14) < .01
                                && Math.abs(sheet.stat("toughness").value() - 6) < .01 && sheet.stat("attack").value() > 18;
                    })
                    .screenshot("lacerta_panel_stats")
                    .step("契印 · 能力", ctx -> CompanionTalkViews.panel().tab(CompanionPanelScreen.Tab.SKILLS)).ticks(4)
                    .screenshot("lacerta_panel_skills")
                    .step("往事", ctx -> CompanionTalkViews.panel().tab(CompanionPanelScreen.Tab.MEMORIES)).ticks(4)
                    .screenshot("lacerta_panel_memories")
                    .step("关掉", ctx -> Minecraft.getInstance().setScreen(null)).ticks(4)
                    .step("切回英文", ctx -> language("en_us")).waitUntil("资源重载完", ctx -> reload.isDone());
        }
    }
}
