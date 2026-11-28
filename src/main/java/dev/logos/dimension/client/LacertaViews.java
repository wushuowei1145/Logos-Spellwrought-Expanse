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
 * <li>{@code lacerta_escort}（阶段 B）：你飞起来，她展翅跟到你左后上方；你飞过一堵墙，她从墙上翻过去，一直没卡进方块；你落地她也落地。</li>
 * <li>{@code lacerta_pursuit}：站在高柱上够不着的僵尸，她飞到它头顶接一记炽翼坠星破，砸完落地。</li>
 * <li>{@code lacerta_catch_hand} / {@code lacerta_catch_embrace}：你从二十几格高处掉下去，她飞过来拉住你的手（信赖起抱住你），带你慢慢降落；
 *     你活着落地、几乎没掉血，她数落你一句。</li>
 * <li>{@code lacerta_dragon}（阶段 C）：你让她化身巨龙（碰撞箱变大、血量百分比不变、你的魔力在扣），龙炎喷中远处的、龙爪砍中贴身的，变回人形。</li>
 * <li>{@code lacerta_dragon_auto}：遇上强敌（劫掠兽）她自己化身、打它；它没了，她过一会自己变回来。</li>
 * <li>{@code lacerta_dragon_appear}：上次是巨龙，叫她出来时以巨龙登场，昂首一吼，二十几格外冲你来的僵尸都转头冲她。</li>
 * <li>{@code lacerta_dragon_ride}：空手右键骑上去，空格起飞、按住 W 往前飞、左键挥爪；在半空下来，这一摔只受两成、活着落地。</li>
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
            // 魔力回到新玩家的默认值（200）：巨龙场景补满过，留着的话打高血量的靶子时她会自己化身巨龙（0 印要够维持 5 秒，250）
            var fresh = new dev.logos.runtime.PlayerMagic();
            var magic = dev.logos.runtime.PlayerMagic.of(p);
            magic.baseMaxMana = fresh.baseMaxMana;
            magic.mana = fresh.mana;
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
        return shootHer(s, name, who, side, dist, height, 0);
    }

    /** 同上，机位和看的地方都往下挪 {@code lead} 格（她正往下降，机位同步过来那几刻她已经下去了）。 */
    static ScenarioBuilder shootHer(ScenarioBuilder s, String name, java.util.function.Supplier<LacertaCompanion> who, double side, double dist,
                                    double height, double lead) {
        return shoot(s, name, () -> {
            LacertaCompanion c = who.get();
            double yaw = Math.toRadians(c.yBodyRot + side);
            Vec3 at = c.position().add(-Math.sin(yaw) * dist, height - lead, Math.cos(yaw) * dist);
            return new Vec3[]{at, c.position().add(0, .9 - lead, 0)};
        });
    }

    /** 对着巨龙拍：她身前偏 {@code side} 度、{@code dist} 格远、高 {@code height} 格，看着她前胸（翼展十来格，得站远些）。 */
    static ScenarioBuilder shootDragon(ScenarioBuilder s, String name, java.util.function.Supplier<LacertaCompanion> who, double side, double dist,
                                       double height) {
        return shoot(s, name, () -> {
            LacertaCompanion c = who.get();
            double yaw = Math.toRadians(c.yBodyRot + side), face = Math.toRadians(c.yBodyRot);
            Vec3 chest = c.position().add(-Math.sin(face) * 1.5, 2, Math.cos(face) * 1.5);
            return new Vec3[]{chest.add(-Math.sin(yaw) * dist, height - 2, Math.cos(yaw) * dist), chest};
        });
    }

    /** 从侧面拍她和 {@code other} 之间（喷龙炎、打它）：两者中点往旁边 {@code dist} 格、高 {@code height} 格。 */
    static ScenarioBuilder shootBetween(ScenarioBuilder s, String name, java.util.function.Supplier<LacertaCompanion> who,
                                        java.util.function.Supplier<? extends net.minecraft.world.entity.Entity> other, double dist, double height) {
        return shoot(s, name, () -> {
            LacertaCompanion c = who.get();
            Vec3 a = c.position(), b = other.get().position();
            Vec3 mid = a.add(b).scale(.5).add(0, 1.8, 0);
            Vec3 along = b.subtract(a).multiply(1, 0, 1);
            along = along.lengthSqr() < 1e-4 ? new Vec3(0, 0, 1) : along.normalize();
            Vec3 side = new Vec3(along.z, 0, -along.x);
            return new Vec3[]{mid.add(side.scale(dist)).add(0, height - 1.8, 0), mid};
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
                        // 优先远程：够不着的用掌心赤焰（随机应变时坠星好了会先飞上去砸，见 lacerta_pursuit）
                        ServerPlayer p = ctx.player();
                        p.setData(Companions.LACERTA_BOND, Companions.bond(p, Companions.LACERTA_DEF).withTactic(dev.logos.dimension.companion.CompanionTactic.RANGED));
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
                            "chat.form", "chat.rest", "back")))
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

    // ---------------------------------------------------------------- 阶段 B：伴飞

    @LDLRegisterClient(name = "lacerta_escort", group = "lacerta_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Escort extends LacertaViews {
        /** 一堵墙：x -6..6、z 7、从地面到 FLOOR + 9。 */
        private static final int WALL_Z = 7, WALL_TOP = FLOOR + 9;
        private LacertaCompanion c;
        private ServerPlayer owner;
        private boolean clipped;
        private int watched;

        private static void wall(ServerLevel level, boolean on) {
            for (int x = -6; x <= 6; x++)
                for (int y = FLOOR + 1; y <= WALL_TOP; y++) {
                    BlockPos at = new BlockPos(x, y, WALL_Z);
                    if (on) level.setBlockAndUpdate(at, Blocks.STONE_BRICKS.defaultBlockState());
                    else level.removeBlock(at, false);
                }
        }

        /** 她在你身后（你朝向的反面）、比你的脚高、离你 1.5–5 格。 */
        private boolean behindAbove() {
            Vec3 to = c.position().subtract(owner.position());
            Vec3 forward = Vec3.directionFromRotation(0, owner.getYRot());
            double d = to.length();
            return d > 1.5 && d < 5 && to.y > .5 && to.multiply(1, 0, 1).dot(forward) < 0;
        }

        /** 这一刻她卡没卡进方块（记下来）；看够 {@code polls} 次返回真。 */
        private boolean watch(ServerLevel level, int polls) {
            if (!level.noCollision(c, c.getBoundingBox().deflate(.05))) clipped = true;
            return ++watched >= polls;
        }

        @Override
        public void define(ScenarioBuilder s) {
            lacertaStage(s, GameType.CREATIVE).runCommand("difficulty peaceful")
                    .server("站在地上、召唤", ctx -> {
                        owner = ctx.player();
                        standAt(owner, new Vec3(.5, FLOOR + 1, -2.5));
                        c = summonLacerta(owner, SPOT);
                    })
                    .waitUntilServer("入场播完", ctx -> arrived(c))
                    .checkServer("你在地上时她不飞", ctx -> !c.isFlying())
                    .server("你飞起来，停在半空", ctx -> aim(owner, new Vec3(.5, FLOOR + 7.6, -2.5), new Vec3(.5, FLOOR + 6, 6)))
                    .waitUntilServer("她展翅飞起来跟着", ctx -> c.isFlying() && "escort".equals(c.flightForTest()))
                    .waitUntilServer("到你左后上方", ctx -> behindAbove())
                    .ticks(20)
                    .checkServer("停在那里（还在你身后上方）", ctx -> behindAbove());
            shootHer(s, "lacerta_escort_hover", () -> c, 60, 4, .6);
            back(s).server("前面竖一堵墙，你飞到墙那边", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        wall(level, true);
                        clipped = false;
                        watched = 0;
                        aim(owner, new Vec3(.5, FLOOR + 7.6, 12.5), new Vec3(.5, FLOOR + 6, 20));
                    })
                    .waitUntilServer("她飞过去的这几秒里一直没卡进方块", ctx -> watch(ctx.server().overworld(), 100))
                    .checkServer("没穿墙", ctx -> !clipped)
                    .waitUntilServer("翻过墙，又跟到你身后上方", ctx -> {
                        watch(ctx.server().overworld(), 0);
                        return c.getZ() > WALL_Z + 1 && behindAbove();
                    });
            shootHer(s, "lacerta_escort_over_wall", () -> c, 60, 4.5, 1);
            back(s).server("你落地", ctx -> standAt(owner, new Vec3(.5, FLOOR + 1, 12.5)))
                    .waitUntilServer("她也收翅落地", ctx -> !c.isFlying() && c.onGround())
                    .checkServer("一直没穿墙", ctx -> !clipped)
                    .teardown("拆墙", ctx -> wall(ctx.server().overworld(), false));
        }
    }

    // ---------------------------------------------------------------- 阶段 B：空中追击

    @LDLRegisterClient(name = "lacerta_pursuit", group = "lacerta_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Pursuit extends LacertaViews {
        private static final BlockPos PILLAR = new BlockPos(0, FLOOR + 1, 9);
        private static final Vec3 SIDE_EYE = new Vec3(-8.5, FLOOR + 2.62, 3.5), SIDE_LOOK = new Vec3(.5, FLOOR + 4, 7.5);
        private LacertaCompanion c;
        private Mob z;
        private int skyfalls, landed;

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
                    .server("九格外一根五格高的石柱，柱顶站着一只僵尸", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        for (int y = 0; y < 5; y++) level.setBlockAndUpdate(PILLAR.above(y), Blocks.STONE_BRICKS.defaultBlockState());
                        z = dummy(level, Vec3.atBottomCenterOf(PILLAR.above(5)), 180, 300);
                        skyfalls = LacertaCompanion.started("skyfall_judgement");
                        landed = LacertaCompanion.landed();
                    })
                    .waitUntilServer("她拔剑飞上去", ctx -> c.isFlying() && "pursue".equals(c.flightForTest()))
                    .ticks(12);
            shootHer(s, "lacerta_pursuit_climb", () -> c, 80, 4, .4);
            back(s).waitUntilServer("飞到它头顶，接一记炽翼坠星破", ctx -> LacertaCompanion.started("skyfall_judgement") > skyfalls);
            // 侧面看柱顶：机位同步过来那几刻正好是往下砸
            back(shoot(s, "lacerta_pursuit_dive", new Vec3(8.5, FLOOR + 7, 5.5), Vec3.atBottomCenterOf(PILLAR.above(5)).add(0, 1, 0)))
                    .waitUntilServer("坠星那一剑砍中柱顶的僵尸", ctx -> LacertaCompanion.landed() > landed && z.getHealth() < z.getMaxHealth())
                    .waitUntilServer("砸完不飞了", ctx -> !c.isFlying())
                    .teardown("拆掉石柱", ctx -> {
                        for (int y = 0; y < 7; y++) ctx.server().overworld().removeBlock(PILLAR.above(y), false);
                    });
        }
    }

    // ---------------------------------------------------------------- 阶段 B：护落

    /** 你从高处掉下去，她飞过来接住你（{@code embrace}：信赖起抱住，不然拉住手），带你慢慢降落。 */
    abstract static class Catch extends LacertaViews {
        private static final Vec3 DROP = new Vec3(3.5, FLOOR + 26, .5);
        private LacertaCompanion c;
        private int catches;

        abstract boolean embrace();

        @Override
        public void define(ScenarioBuilder s) {
            String clip = embrace() ? "catch_embrace" : "catch_hand";
            lacertaStage(s, GameType.SURVIVAL).runCommand("difficulty peaceful")
                    .server("召唤；不带抗性（摔伤照算）", ctx -> {
                        ServerPlayer p = ctx.player();
                        standAt(p, new Vec3(.5, FLOOR + 1, -3.5));
                        c = summonLacerta(p, SPOT);
                        if (embrace()) c.affectionForTest(p, 450);
                    })
                    .waitUntilServer("入场播完", ctx -> arrived(c))
                    .server("你出现在二十五格高处，掉下去", ctx -> {
                        ServerPlayer p = ctx.player();
                        p.removeAllEffects();
                        p.setHealth(p.getMaxHealth());
                        catches = LacertaCompanion.catches();
                        dev.logos.dimension.DimensionTravel.teleport(p, p.level(), DROP, 90, 30);
                        p.resetFallDistance();
                    })
                    .waitUntilServer("她飞过来", ctx -> LacertaCompanion.catches() > catches && clip.equals(misc(c)))
                    .waitUntilServer("抓住了", ctx -> "carry".equals(c.flightForTest()));
            shootHer(s, "lacerta_" + clip, () -> c, 70, 4.5, .2, embrace() ? 1.2 : 1.8);
            back(s).ticks(10)
                    .checkServer("带着你慢慢往下降", ctx -> "carry".equals(c.flightForTest()) && !ctx.player().onGround());
            // 稳住以后再拍一张（刚抓住那一刻前面那段下坠刚被减速，画面上她落后得最多）
            back(shootHer(s.ticks(10), "lacerta_" + clip + "_hold", () -> c, 70, 4.5, .2, embrace() ? .8 : 1.4))
                    .waitUntilServer("你落地", ctx -> ctx.player().onGround())
                    .checkServer("活着落地，几乎没掉血", ctx -> ctx.player().isAlive() && ctx.player().getHealth() >= ctx.player().getMaxHealth() - 6)
                    .waitUntilServer("她红着脸数落你一句",
                            ctx -> dev.logos.dimension.companion.CompanionLines.last().endsWith("catch_after_" + (embrace() ? "embrace" : "hand")))
                    .waitUntilServer("她也落地", ctx -> !c.isFlying() && c.onGround());
        }
    }

    @LDLRegisterClient(name = "lacerta_catch_hand", group = "lacerta_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class CatchHand extends Catch {
        @Override boolean embrace() { return false; }
    }

    @LDLRegisterClient(name = "lacerta_catch_embrace", group = "lacerta_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class CatchEmbrace extends Catch {
        @Override boolean embrace() { return true; }
    }

    // ---------------------------------------------------------------- 阶段 C：巨龙

    /** 你的魔力补满（巨龙每秒要你出魔力）。 */
    static void fillMana(ServerPlayer p, double amount) {
        var m = dev.logos.runtime.PlayerMagic.of(p);
        m.baseMaxMana = Math.max(m.baseMaxMana, amount);
        m.mana = amount;
    }

    @LDLRegisterClient(name = "lacerta_dragon", group = "lacerta_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Dragon extends LacertaViews {
        private LacertaCompanion c;
        private Mob far, near;
        private float ratio;
        private double mana;
        private int breathHits, clawHits;

        @Override
        public void define(ScenarioBuilder s) {
            lacertaStage(s, GameType.SURVIVAL).runCommand("difficulty normal")
                    .server("站好、召唤；魔力补满", ctx -> {
                        ServerPlayer p = ctx.player();
                        standAt(p, new Vec3(-8.5, FLOOR + 1, -8.5));
                        c = summonLacerta(p, SPOT);
                        fillMana(p, 5000);
                    })
                    .waitUntilServer("入场播完", ctx -> arrived(c))
                    .server("让她化身巨龙（你在对话里让她变的）", ctx -> {
                        ServerPlayer p = ctx.player();
                        c.setHealth(c.getMaxHealth() * .6f);
                        ratio = c.getHealth() / c.getMaxHealth();
                        mana = dev.logos.runtime.PlayerMagic.of(p).mana;
                        c.transformForTest(p, false);
                    })
                    .checkServer("变成巨龙：碰撞箱 3.2 宽", ctx -> c.isDragonForm() && Math.abs(c.getBbWidth() - 3.2f) < .01)
                    .checkServer("血量百分比不变，生命上限翻倍", ctx -> Math.abs(c.getHealth() / c.getMaxHealth() - ratio) < .02)
                    .checkServer("记在你身上：下次以巨龙登场", ctx -> dev.logos.dimension.companion.LacertaDays.NONE != null
                            && ctx.player().getData(Companions.LACERTA_DAYS).dragonForm())
                    .ticks(40)
                    .checkServer("你的魔力在扣", ctx -> dev.logos.runtime.PlayerMagic.of(ctx.player()).mana < mana - 20);
            shootDragon(s, "lacerta_dragon_front", () -> c, -35, 14, 5.5);
            shootDragon(s, "lacerta_dragon_side", () -> c, -90, 15, 4.5);
            back(s).server("十二格外一只站着不动的僵尸", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        far = dummy(level, new Vec3(.5, FLOOR + 1, 12.5), 180, 300);
                        breathHits = LacertaCompanion.breathHits();
                        c.breathForTest(far.getEyePosition().subtract(c.position().add(0, 1.8, 0)).normalize());
                    })
                    .waitUntilServer("龙炎喷中它", ctx -> LacertaCompanion.breathHits() > breathHits && far.getHealth() < far.getMaxHealth())
                    .waitUntil("客户端沿喷的方向画了一道龙炎", ctx -> dev.logos.dimension.client.companion.LacertaBreathClient.streamed() > 150);
            shootBetween(s, "lacerta_dragon_breath", () -> c, () -> far, -15, 5);
            back(s).server("收住龙炎；贴身再来一只", ctx -> {
                        c.stopBreathForTest();
                        far.discard();
                        near = dummy(ctx.server().overworld(), new Vec3(.5, FLOOR + 1, 4.5), 180, 300);
                    })
                    .ticks(20)
                    .server("挥一爪", ctx -> {
                        clawHits = LacertaCompanion.clawHits();
                        c.setYRot(0);
                        c.yBodyRot = 0;
                        c.clawForTest();
                    })
                    .ticks(8);
            shootBetween(s, "lacerta_dragon_claw", () -> c, () -> near, -12, 5);
            back(s).waitUntilServer("龙爪砍中它", ctx -> LacertaCompanion.clawHits() > clawHits && near.getHealth() < near.getMaxHealth())
                    .server("变回人形", ctx -> {
                        near.discard();
                        c.revertForTest(ctx.player());
                    })
                    .checkServer("变回来：碰撞箱、血量百分比", ctx -> !c.isDragonForm() && Math.abs(c.getBbWidth() - .6f) < .01
                            && Math.abs(c.getHealth() / c.getMaxHealth() - ratio) < .1)
                    .checkServer("不再记着以巨龙登场", ctx -> !ctx.player().getData(Companions.LACERTA_DAYS).dragonForm());
        }
    }

    @LDLRegisterClient(name = "lacerta_dragon_auto", group = "lacerta_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class DragonAuto extends LacertaViews {
        private LacertaCompanion c;
        private Mob ravager;
        private int transforms, hits;

        @Override
        public void define(ScenarioBuilder s) {
            lacertaStage(s, GameType.SURVIVAL).runCommand("difficulty normal")
                    .server("站好、召唤；魔力补满", ctx -> {
                        ServerPlayer p = ctx.player();
                        standAt(p, new Vec3(-8.5, FLOOR + 1, -8.5));
                        c = summonLacerta(p, SPOT);
                        fillMana(p, 5000);
                    })
                    .waitUntilServer("入场播完", ctx -> arrived(c))
                    .server("十格外一只劫掠兽（站着不动，100 生命）", ctx -> {
                        transforms = LacertaCompanion.transforms();
                        hits = LacertaCompanion.clawHits() + LacertaCompanion.breathHits();
                        ravager = CompanionLifeViews.spawn(ctx.server().overworld(), EntityType.RAVAGER, new Vec3(.5, FLOOR + 1, 10.5), 180);
                        ravager.setNoAi(true);
                    })
                    .waitUntilServer("她自己化身巨龙", ctx -> LacertaCompanion.transforms() > transforms && c.isDragonForm())
                    .waitUntilServer("龙爪或龙炎打中它", ctx -> LacertaCompanion.clawHits() + LacertaCompanion.breathHits() > hits);
            shootBetween(s, "lacerta_dragon_auto", () -> c, () -> ravager, -16, 6);
            back(s).server("劫掠兽没了", ctx -> ravager.discard())
                    .waitUntilServer("打完过一会，她自己变回人形", ctx -> !c.isDragonForm())
                    .checkServer("她自己变的不记着（下次照常以人形登场）", ctx -> !ctx.player().getData(Companions.LACERTA_DAYS).dragonForm())
                    .server("她自己再变一次巨龙，这时收回她", ctx -> {
                        c.transformForTest(ctx.player(), true);
                        Companions.dismiss(ctx.player(), Companions.LACERTA_DEF);
                    })
                    .checkServer("巨龙形态下收回的，下次以巨龙登场", ctx -> ctx.player().getData(Companions.LACERTA_DAYS).dragonForm())
                    .teardown("不再记着", ctx -> ctx.player().setData(Companions.LACERTA_DAYS, dev.logos.dimension.companion.LacertaDays.NONE));
        }
    }

    @LDLRegisterClient(name = "lacerta_dragon_appear", group = "lacerta_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class DragonAppear extends LacertaViews {
        private LacertaCompanion c;
        private Mob z1, z2;

        @Override
        public void define(ScenarioBuilder s) {
            lacertaStage(s, GameType.SURVIVAL).runCommand("difficulty normal")
                    .server("上次是巨龙；两只僵尸冲你来（二十格外）", ctx -> {
                        ServerPlayer p = ctx.player();
                        standAt(p, new Vec3(-8.5, FLOOR + 1, -8.5));
                        fillMana(p, 5000);
                        Companions.grantPact(p, Companions.LACERTA_DEF);
                        p.setData(Companions.LACERTA_DAYS, new dev.logos.dimension.companion.LacertaDays(-1, -1, true));
                        ServerLevel level = ctx.server().overworld();
                        z1 = PrincessViews.helmeted(CompanionLifeViews.spawn(level, EntityType.ZOMBIE, new Vec3(10.5, FLOOR + 1, 12.5), 180));
                        z2 = PrincessViews.helmeted(CompanionLifeViews.spawn(level, EntityType.ZOMBIE, new Vec3(12.5, FLOOR + 1, 10.5), 180));
                        z1.setTarget(p);
                        z2.setTarget(p);
                        c = summonLacerta(p, SPOT);
                    })
                    .checkServer("以巨龙登场", ctx -> c.isDragonForm() && "appear".equals(misc(c)))
                    .ticks(12);
            shootDragon(s, "lacerta_dragon_appear_land", () -> c, -40, 15, 5);
            back(s).waitUntilServer("昂首一吼，僵尸都转头冲她", ctx -> z1.getTarget() == c && z2.getTarget() == c);
            shootDragon(s, "lacerta_dragon_appear_roar", () -> c, -40, 15, 5);
            back(s).teardown("不再记着", ctx -> ctx.player().setData(Companions.LACERTA_DAYS, dev.logos.dimension.companion.LacertaDays.NONE));
        }
    }

    @LDLRegisterClient(name = "lacerta_dragon_ride", group = "lacerta_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class DragonRide extends LacertaViews {
        private LacertaCompanion c;
        private double startZ;
        private int claws;
        private float health;
        private double dismountY;

        /** 客户端那一只（骑着时由你的客户端算她怎么走）。 */
        private LacertaCompanion clientDragon() {
            return Minecraft.getInstance().level.getEntity(c.getId()) instanceof LacertaCompanion l ? l : null;
        }

        @Override
        public void define(ScenarioBuilder s) {
            lacertaStage(s, GameType.SURVIVAL).runCommand("difficulty peaceful")
                    .server("站好、召唤、化身巨龙", ctx -> {
                        ServerPlayer p = ctx.player();
                        standAt(p, new Vec3(.5, FLOOR + 1, -4.5));
                        c = summonLacerta(p, new Vec3(.5, FLOOR + 1, -9.5));
                        fillMana(p, 20000);
                    })
                    .waitUntilServer("入场播完", ctx -> arrived(c))
                    .server("变身", ctx -> c.transformForTest(ctx.player(), false))
                    .ticks(20)
                    .server("空手右键她", ctx -> {
                        ServerPlayer p = ctx.player();
                        p.removeAllEffects();
                        p.setHealth(p.getMaxHealth());
                        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                        c.interact(p, InteractionHand.MAIN_HAND, Vec3.ZERO);
                    })
                    .waitUntilServer("骑上去了", ctx -> ctx.player().getVehicle() == c)
                    .waitUntil("你的客户端：骑着时第三人称镜头拉远到 10 格", ctx -> clientDragon() != null
                            && clientDragon().getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.CAMERA_DISTANCE) >= 9.9)
                    .step("第三人称", ctx -> thirdPerson(true))
                    .ticks(10).screenshot("lacerta_dragon_ride_seat")
                    .step("按住空格：起飞", ctx -> dev.logos.dimension.client.companion.LacertaRideClient.forceForTest(
                            new net.minecraft.world.entity.player.Input(false, false, false, false, true, false, false)))
                    .waitUntil("她飞起来了（你的客户端）", ctx -> clientDragon() != null && clientDragon().rideAirborneForTest())
                    .ticks(20)
                    .server("记下位置", ctx -> startZ = c.getZ())
                    .step("按住 W：顺着视线往前飞", ctx -> {
                        var mc = Minecraft.getInstance();
                        mc.player.setYRot(0);
                        mc.player.setXRot(-35);
                        dev.logos.dimension.client.companion.LacertaRideClient.forceForTest(
                                new net.minecraft.world.entity.player.Input(true, false, false, false, false, false, false));
                    })
                    .ticks(10)
                    .step("视线放平一些再拍（仰着拍，镜头在她肚子底下）", ctx -> Minecraft.getInstance().player.setXRot(-8))
                    .ticks(6).screenshot("lacerta_dragon_ride_fly")
                    .step("马上按住 S 刹住、空格往上抬（截图那几秒她一直在往前飞，别飞出台子）", ctx -> dev.logos.dimension.client.companion.LacertaRideClient.forceForTest(
                            new net.minecraft.world.entity.player.Input(false, true, false, false, true, false, false)))
                    .checkServer("往前飞了好几格、离地了", ctx -> c.getZ() > startZ + 4 && !c.onGround())
                    .step("左键：挥爪", ctx -> {
                        claws = LacertaCompanion.claws();
                        net.neoforged.neoforge.client.network.ClientPacketDistributor.sendToServer(
                                dev.logos.dimension.companion.LacertaRideCommands.claw());
                    })
                    .waitUntilServer("挥了一爪", ctx -> LacertaCompanion.claws() > claws)
                    .waitUntilServer("飞到十几格高（还在台子上方）", ctx -> c.getY() > FLOOR + 14 && Math.abs(c.getZ()) < 13)
                    .step("松开", ctx -> dev.logos.dimension.client.companion.LacertaRideClient.forceForTest(null))
                    .server("在半空下来", ctx -> {
                        ServerPlayer p = ctx.player();
                        health = p.getHealth();
                        dismountY = p.getY();
                        p.stopRiding();
                    })
                    .waitUntilServer("你落地", ctx -> ctx.player().onGround())
                    .checkServer("落在台子上、活着，这一摔只受了两成（照下龙的高度算）", ctx -> {
                        ServerPlayer p = ctx.player();
                        double full = dismountY - (FLOOR + 1) - 3;
                        return p.isAlive() && Math.abs(p.getY() - (FLOOR + 1)) < .1 && p.getHealth() >= health - (full * .2 + 1) && p.getHealth() < health;
                    })
                    .step("第一人称", ctx -> thirdPerson(false))
                    .teardown("松开按键", ctx -> dev.logos.dimension.client.companion.LacertaRideClient.forceForTest(null));
        }
    }
}
