package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.companion.CompanionLines;
import dev.logos.dimension.companion.Companions;
import dev.logos.dimension.companion.DragonScaleItem;
import dev.logos.dimension.companion.LacertaCompanion;
import dev.logos.dimension.companion.LacertaMeet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * 拉塞塔的鳞之契和初遇（阶段 E，docs/LACERTA_COMPANION.md 第 21 节），同组 {@code lacerta_companion}：
 * <ul>
 * <li>{@code lacerta_seal_locks}：还没盖鳞之契时挡不了刀、变不了巨龙；盖满九枚以后都会。</li>
 * <li>{@code lacerta_shelter}（第一印）：你在她身边挨一拳，她拢翅替你挡下三成；离远了就全挨。</li>
 * <li>{@code lacerta_gust}（第三印）：三只僵尸围上来，她双翼一扇把它们掀开。</li>
 * <li>{@code lacerta_fire_breath}（第六印）：前面扎着三只，她推掌喷出两秒龙炎烧它们。</li>
 * <li>{@code lacerta_rise}（第七印）：挨了致命的一下，跪下、龙炎绕身站起来、回一半生命；同一天再倒下就照常坠入龙眠。</li>
 * <li>{@code lacerta_wrath}（第八印）：你被僵尸打到三成生命以下，她逆鳞发作、攻击 +30%、先打它。</li>
 * <li>{@code lacerta_meet}：还没立契的你在海拔 120 以上的露天营火上烤着生牛肉，她从天上落下来闻香；给生的、给面包她都不吃，
 *     给熟牛排她吃了、单膝立誓、递给你烙着名字的龙鳞，跟你走。</li>
 * <li>{@code lacerta_meet_leave}：一直不喂她，她鼓着嘴哼一声飞走了，没有立契。</li>
 * </ul>
 */
public abstract class LacertaSealViews extends LacertaViews {
    /** 契印计数（{@link LacertaCompanion#sealCountsForTest}）的下标。 */
    static final int SHELTERS = 0, GUSTS = 1, GUST_HITS = 2, BREATHS = 3, BREATH_HITS = 4, RISES = 5, WRATHS = 6;
    /** 初遇计数（{@link LacertaMeet#counts}）的下标。 */
    static final int ARRIVALS = 0, PACTS = 1, REFUSALS = 2, LEAVES = 3;

    static int count(int i) {
        return LacertaCompanion.sealCountsForTest()[i];
    }

    static int meets(int i) {
        return LacertaMeet.counts()[i];
    }

    static boolean lastLine(String id) {
        return CompanionLines.last().endsWith(id);
    }

    /** 客户端那一只身上 {@code seal} 控制器此刻播到第几秒（没在播为 -1）。 */
    static double sealTime(LacertaCompanion server) {
        var level = net.minecraft.client.Minecraft.getInstance().level;
        if (level == null || !(level.getEntity(server.getId()) instanceof LacertaCompanion l)) return -1;
        var controller = l.getAnimatableInstanceCache().getManagerForId(l.getId()).getAnimationControllers().get("seal");
        if (controller == null || controller.getCurrentAnimationPoint() == null) return -1;
        return controller.getCurrentAnimationTime();
    }

    /** 饱食度压到 17、没有饱和：不自己回血（量你挨了多少时不被回血搅乱）。 */
    static void noRegen(ServerPlayer p) {
        p.getFoodData().setFoodLevel(17);
        p.getFoodData().setSaturation(0);
    }

    /** 一个站着不动的盔甲架（挥拳的那一个：不是敌对生物，她不去打它）。 */
    static ArmorStand stand(ServerLevel level, Vec3 at) {
        ArmorStand a = EntityType.ARMOR_STAND.create(level, EntitySpawnReason.COMMAND);
        a.snapTo(at.x, at.y, at.z, 180, 0);
        a.setInvisible(true);
        level.addFreshEntity(a);
        return a;
    }

    // ---------------------------------------------------------------- 契印锁着

    @LDLRegisterClient(name = "lacerta_seal_locks", group = "lacerta_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Locks extends LacertaSealViews {
        private LacertaCompanion c;
        private ArmorStand by;

        @Override
        public void define(ScenarioBuilder s) {
            lacertaStage(s, GameType.SURVIVAL).runCommand("difficulty peaceful")
                    .server("召唤（还没盖鳞之契）；你的魔力补满", ctx -> {
                        ServerPlayer p = ctx.player();
                        standAt(p, new Vec3(-8.5, FLOOR + 1, -8.5));
                        c = summonLacerta(p, SPOT);
                        fillMana(p, 5000);
                        by = stand(ctx.server().overworld(), new Vec3(.5, FLOOR + 1, 2.5));
                    })
                    .waitUntilServer("入场播完", ctx -> arrived(c))
                    .checkServer("龙骑武艺（第二印）以前：二十下一下都挡不住", ctx -> c.blocksForTest(by, 20) == 0)
                    .checkServer("太古真身（第九印）以前：变不了巨龙", ctx -> !c.transformForTest(ctx.player(), false) && !c.isDragonForm())
                    .server("盖满九枚", ctx -> {
                        for (int i = 0; i < 9; i++) Companions.addSeal(ctx.player(), Companions.LACERTA_DEF);
                    })
                    .checkServer("会挡了", ctx -> c.blocksForTest(by, 20) > 3)
                    .checkServer("能化身巨龙了", ctx -> c.transformForTest(ctx.player(), false) && c.isDragonForm())
                    .server("变回来", ctx -> c.revertForTest(ctx.player()))
                    .teardown("收掉盔甲架", ctx -> {
                        if (by != null) by.discard();
                    });
        }
    }

    // ---------------------------------------------------------------- 一 绯翼庇护

    @LDLRegisterClient(name = "lacerta_shelter", group = "lacerta_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Shelter extends LacertaSealViews {
        private LacertaCompanion c;
        private ArmorStand by;
        private float before, herBefore;
        private int shelters;

        @Override
        public void define(ScenarioBuilder s) {
            lacertaStage(s, GameType.SURVIVAL).runCommand("difficulty normal")
                    .server("召唤（第一印）；你站在她身边", ctx -> {
                        ServerPlayer p = ctx.player();
                        standAt(p, new Vec3(2.1, FLOOR + 1, .5));
                        c = summonLacerta(p, SPOT, 1);
                        c.stayForTest(true);
                        by = stand(ctx.server().overworld(), new Vec3(4.5, FLOOR + 1, .5));
                    })
                    .waitUntilServer("入场播完", ctx -> arrived(c))
                    .server("挨一拳（10 点，不带抗性）", ctx -> {
                        ServerPlayer p = ctx.player();
                        p.removeAllEffects();
                        p.setHealth(p.getMaxHealth());
                        noRegen(p);
                        before = p.getHealth();
                        herBefore = c.getHealth();
                        shelters = count(SHELTERS);
                        p.hurtServer(ctx.server().overworld(), ctx.server().overworld().damageSources().mobAttack(by), 10);
                    })
                    .checkServer("你只挨了七成", ctx -> Math.abs(before - ctx.player().getHealth() - 7) < .6)
                    .checkServer("那三成她替你挨了", ctx -> count(SHELTERS) > shelters && c.getHealth() < herBefore)
                    .checkServer("拢翅（借 act_guard）", ctx -> "act_guard".equals(c.sealClip()))
                    .ticks(6);
            shootHer(s, "lacerta_shelter", () -> c, 35, 3.6, 1.4);
            back(s).waitUntilServer("嘴上数落一句", ctx -> lastLine("shelter"))
                    .ticks(40)
                    .server("离她六格再挨一拳", ctx -> {
                        ServerPlayer p = ctx.player();
                        standAt(p, new Vec3(6.5, FLOOR + 1, .5));
                        p.setHealth(p.getMaxHealth());
                        before = p.getHealth();
                        shelters = count(SHELTERS);
                        p.hurtServer(ctx.server().overworld(), ctx.server().overworld().damageSources().mobAttack(by), 10);
                    })
                    .checkServer("离远了挡不到：全挨", ctx -> Math.abs(before - ctx.player().getHealth() - 10) < .6 && count(SHELTERS) == shelters)
                    .teardown("收掉盔甲架", ctx -> {
                        if (by != null) by.discard();
                    });
        }
    }

    // ---------------------------------------------------------------- 三 烈风振翼

    @LDLRegisterClient(name = "lacerta_gust", group = "lacerta_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Gust extends LacertaSealViews {
        private LacertaCompanion c;
        private final Mob[] z = new Mob[3];
        private int gusts, hits;

        @Override
        public void define(ScenarioBuilder s) {
            lacertaStage(s, GameType.SURVIVAL).runCommand("difficulty normal")
                    .server("召唤（第三印）", ctx -> {
                        ServerPlayer p = ctx.player();
                        standAt(p, new Vec3(-7.5, FLOOR + 1, -7.5));
                        c = summonLacerta(p, SPOT, 3);
                        c.stayForTest(true);
                    })
                    .waitUntilServer("入场播完", ctx -> arrived(c))
                    .server("三只僵尸贴着她围上来", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        gusts = count(GUSTS);
                        hits = count(GUST_HITS);
                        Vec3[] at = {new Vec3(2.5, FLOOR + 1, .5), new Vec3(-1.3, FLOOR + 1, 2.1), new Vec3(-.8, FLOOR + 1, -1.6)};
                        for (int i = 0; i < 3; i++) {
                            z[i] = PrincessViews.helmeted(CompanionLifeViews.spawn(level, EntityType.ZOMBIE, at[i], 0));
                            z[i].getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(200);
                            z[i].setHealth(200);
                            z[i].setTarget(c);
                        }
                    })
                    .waitUntilServer("她双翼一扇", ctx -> count(GUSTS) > gusts && "act_wing_cape".equals(c.sealClip()))
                    .ticks(9);
            shootHer(s, "lacerta_gust", () -> c, 30, 5, 2);
            back(s).waitUntilServer("掀开了围着她的（至少两只）", ctx -> count(GUST_HITS) >= hits + 2)
                    .teardown("收掉僵尸", ctx -> {
                        for (Mob m : z) if (m != null) m.discard();
                    });
        }
    }

    // ---------------------------------------------------------------- 六 赤穹龙息

    @LDLRegisterClient(name = "lacerta_fire_breath", group = "lacerta_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class FireBreath extends LacertaSealViews {
        private LacertaCompanion c;
        private final Mob[] z = new Mob[3];
        private int breaths, hits, streamed;

        @Override
        public void define(ScenarioBuilder s) {
            lacertaStage(s, GameType.SURVIVAL).runCommand("difficulty normal")
                    .server("召唤（第六印）", ctx -> {
                        ServerPlayer p = ctx.player();
                        standAt(p, new Vec3(-7.5, FLOOR + 1, -6.5));
                        c = summonLacerta(p, SPOT, 6);
                        c.stayForTest(true);
                        c.topUp();
                    })
                    .waitUntilServer("入场播完", ctx -> arrived(c))
                    .server("前面七八格三只僵尸扎堆冲她来", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        breaths = count(BREATHS);
                        hits = count(BREATH_HITS);
                        streamed = dev.logos.dimension.client.companion.LacertaBreathClient.streamed();
                        Vec3[] at = {new Vec3(.5, FLOOR + 1, 9.5), new Vec3(-.7, FLOOR + 1, 10.3), new Vec3(1.7, FLOOR + 1, 10.1)};
                        for (int i = 0; i < 3; i++) {
                            z[i] = PrincessViews.helmeted(CompanionLifeViews.spawn(level, EntityType.ZOMBIE, at[i], 180));
                            z[i].getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(200);
                            z[i].setHealth(200);
                            z[i].setTarget(c);
                        }
                    })
                    .waitUntilServer("她推掌喷出龙息", ctx -> count(BREATHS) > breaths && c.breathingFire())
                    .checkServer("站在地上推掌", ctx -> c.onGround() && "flame_throw".equals(c.sealClip()))
                    .ticks(8)
                    .check("推掌定在推到底的那一帧", ctx -> sealTime(c) >= .3 && sealTime(c) <= .45)
                    .check("客户端她的身子也朝着喷的方向", ctx -> net.minecraft.client.Minecraft.getInstance().level.getEntity(c.getId()) instanceof LacertaCompanion l
                            && Math.abs(net.minecraft.util.Mth.wrapDegrees(l.yBodyRot - c.getYRot())) < 10);
            // 从她左后方越肩往前拍（推出去的是左手，火往前喷）
            shoot(s, "lacerta_fire_breath", () -> {
                Vec3 f = Vec3.directionFromRotation(0, c.yBodyRot), left = new Vec3(f.z, 0, -f.x);
                return new Vec3[]{c.position().add(f.scale(-2.4)).add(left.scale(2.2)).add(0, 1.9, 0), c.position().add(f.scale(4)).add(0, .9, 0)};
            });
            back(s).waitUntilServer("烧到它们", ctx -> count(BREATH_HITS) >= hits + 2)
                    .waitUntil("客户端从掌心画了龙炎", ctx -> dev.logos.dimension.client.companion.LacertaBreathClient.streamed() > streamed + 100)
                    .waitUntilServer("两秒后收住", ctx -> !c.breathingFire())
                    .teardown("收掉僵尸", ctx -> {
                        for (Mob m : z) if (m != null) m.discard();
                    });
        }
    }

    // ---------------------------------------------------------------- 七 燃烬不屈

    @LDLRegisterClient(name = "lacerta_rise", group = "lacerta_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Rise extends LacertaSealViews {
        private LacertaCompanion c;
        private int rises, withdrawn;

        @Override
        public void define(ScenarioBuilder s) {
            lacertaStage(s, GameType.SURVIVAL).runCommand("difficulty peaceful")
                    .server("召唤（第七印）", ctx -> {
                        ServerPlayer p = ctx.player();
                        aim(p, new Vec3(-6.5, FLOOR + 2.62, .5), SPOT.add(0, 1, 0));
                        c = summonLacerta(p, SPOT, 7);
                        c.stayForTest(true);
                    })
                    .step("对准", ctx -> aimClient(SPOT.add(0, 1, 0)))
                    .waitUntilServer("入场播完", ctx -> arrived(c))
                    .server("挨了致命的一下", ctx -> {
                        rises = count(RISES);
                        withdrawn = LacertaCompanion.withdrawn();
                        c.withdrawForTest();
                    })
                    .checkServer("不坠入龙眠：跪下", ctx -> count(RISES) > rises && c.risingForTest() && !c.withdrawing() && "death".equals(misc(c)))
                    .ticks(12);
            shoot(s, "lacerta_rise_kneel", SPOT.add(-2.8, 1.1, 2.2), SPOT.add(0, .6, 0));
            back(s).waitUntilServer("龙炎绕身站起来（借 summon 落地以后那一段）", ctx -> "summon".equals(c.sealClip()))
                    .checkServer("回到一半生命", ctx -> c.getHealth() >= c.getMaxHealth() * .45f)
                    .waitUntil("客户端开始播", ctx -> sealTime(c) >= 0)
                    .waitUntil("从落地那一刻（0.9 秒）播起", ctx -> sealTime(c) >= .85);
            shoot(s, "lacerta_rise_land", SPOT.add(-2.8, 1.1, 2.2), SPOT.add(0, .6, 0));
            back(s).waitUntilServer("嘴硬一句", ctx -> lastLine("rise"))
                    .ticks(16);
            shoot(s, "lacerta_rise_stand", SPOT.add(-2.8, 1.3, 2.2), SPOT.add(0, .9, 0));
            back(s).waitUntilServer("站稳了", ctx -> !c.risingForTest())
                    .server("同一天再挨一下致命的", ctx -> c.withdrawForTest())
                    .checkServer("今天用过了：坠入龙眠", ctx -> c.withdrawing())
                    .waitUntilServer("化作火星回到鳞里", ctx -> LacertaCompanion.withdrawn() > withdrawn);
        }
    }

    // ---------------------------------------------------------------- 八 触龙逆鳞

    @LDLRegisterClient(name = "lacerta_wrath", group = "lacerta_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Wrath extends LacertaSealViews {
        private LacertaCompanion c;
        private Mob z;
        private int wraths;
        private float attack;

        @Override
        public void define(ScenarioBuilder s) {
            lacertaStage(s, GameType.SURVIVAL).runCommand("difficulty normal")
                    .server("召唤（第八印）", ctx -> {
                        ServerPlayer p = ctx.player();
                        standAt(p, new Vec3(-4.5, FLOOR + 1, -4.5));
                        c = summonLacerta(p, SPOT, 8);
                    })
                    .waitUntilServer("入场播完", ctx -> arrived(c))
                    .server("一只僵尸把你打到三成生命以下", ctx -> {
                        ServerPlayer p = ctx.player();
                        ServerLevel level = ctx.server().overworld();
                        wraths = count(WRATHS);
                        attack = c.attack();
                        // 在她 12 格外（不招她嘲讽，不然那一声龙吟盖掉怒吼那一句）、你 24 格内
                        z = dummy(level, new Vec3(-14.5, FLOOR + 1, -6.5), 45, 300);
                        // 抗性 IV 下这一拳只掉 0.4；饱和回血一下也回不到三成以上
                        p.setHealth(4);
                        noRegen(p);
                        p.hurtServer(level, level.damageSources().mobAttack(z), 2);
                    })
                    .waitUntilServer("逆鳞发作", ctx -> count(WRATHS) > wraths && c.wrathfulForTest())
                    .checkServer("攻击多三成", ctx -> Math.abs(c.attack() - attack * 1.3f) < .05f)
                    .checkServer("怒吼一句", ctx -> lastLine("wrath"))
                    .waitUntilServer("先收拾伤你的那只", ctx -> mindOf(c).foe() == z)
                    .ticks(16);
            shootHer(s, "lacerta_wrath", () -> c, 35, 3.6, 1.4);
            back(s).teardown("收掉僵尸", ctx -> {
                if (z != null) z.discard();
            });
        }
    }

    // ---------------------------------------------------------------- 初遇

    /** 高山上的一块平台（海拔 121，露天）、你站在南边、营火在中间偏北、上面烤着生牛肉。 */
    abstract static class Meet extends LacertaSealViews {
        static final int HIGH = 121;
        static final BlockPos FIRE = new BlockPos(0, HIGH + 1, 1);
        static final Vec3 YOU = new Vec3(.5, HIGH + 1, -2.5);
        LacertaCompanion c;

        static ScenarioBuilder platform(ScenarioBuilder s) {
            return lacertaStage(s, GameType.SURVIVAL).runCommand("difficulty peaceful")
                    .server("高山上的平台、营火烤着生牛肉；你站在南边看着它", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        for (int x = -5; x <= 5; x++)
                            for (int z = -5; z <= 5; z++) {
                                level.setBlockAndUpdate(new BlockPos(x, HIGH, z), Blocks.STONE.defaultBlockState());
                                for (int y = HIGH + 1; y <= HIGH + 6; y++) level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                            }
                        level.setBlockAndUpdate(FIRE, Blocks.CAMPFIRE.defaultBlockState());
                        if (level.getBlockEntity(FIRE) instanceof CampfireBlockEntity fire) fire.placeFood(level, null, new ItemStack(Items.BEEF));
                        ServerPlayer p = ctx.player();
                        aim(p, YOU.add(0, 1.62, 0), Vec3.atBottomCenterOf(FIRE).add(0, .8, 0));
                    })
                    .step("对准", ctx -> aimClient(Vec3.atBottomCenterOf(FIRE).add(0, .8, 0)));
        }

        static void tearDown(ScenarioBuilder s) {
            s.teardown("拆掉平台；不再强行定", ctx -> {
                ServerLevel level = ctx.server().overworld();
                LacertaMeet.alwaysForTest = false;
                LacertaMeet.waitForTest = 0;
                level.getEntitiesOfClass(LacertaCompanion.class, new net.minecraft.world.phys.AABB(-12, HIGH - 4, -12, 12, HIGH + 40, 12)).forEach(Entity::discard);
                ServerPlayer p = ctx.server().getPlayerList().getPlayer(ctx.player().getUUID());
                if (p != null) Companions.dismiss(p, Companions.LACERTA_DEF);
                for (int x = -5; x <= 5; x++)
                    for (int z = -5; z <= 5; z++)
                        for (int y = HIGH; y <= HIGH + 2; y++) level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
            });
        }

        /** 在等你的那一位。 */
        static LacertaCompanion find(ServerLevel level) {
            List<LacertaCompanion> all = level.getEntitiesOfClass(LacertaCompanion.class, new net.minecraft.world.phys.AABB(-12, HIGH - 4, -12, 12, HIGH + 12, 12),
                    LacertaCompanion::meeting);
            return all.isEmpty() ? null : all.get(0);
        }

        /** 拿着 {@code item} 右键她。 */
        void feed(ServerPlayer p, ItemStack item) {
            p.setItemInHand(InteractionHand.MAIN_HAND, item);
            c.interact(p, InteractionHand.MAIN_HAND, Vec3.ZERO);
        }
    }

    @LDLRegisterClient(name = "lacerta_meet", group = "lacerta_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class MeetPact extends Meet {
        private int arrivals, refusals, pacts;

        @Override
        public void define(ScenarioBuilder s) {
            platform(s).server("今天判定必中", ctx -> {
                        arrivals = meets(ARRIVALS);
                        refusals = meets(REFUSALS);
                        pacts = meets(PACTS);
                        LacertaMeet.alwaysForTest = true;
                    })
                    .waitUntilServer("她循着香味落下来", ctx -> meets(ARRIVALS) > arrivals && (c = find(ctx.server().overworld())) != null)
                    .checkServer("落在营火另一边，面朝你", ctx -> c.getZ() > FIRE.getZ() + .5 && Math.abs(c.getX() - .5) < 1.6)
                    .checkServer("一天只判定一次", ctx -> ctx.player().getData(Companions.LACERTA_DAYS).meetDay() >= 0)
                    .ticks(10).screenshot("lacerta_meet_arrive")
                    .waitUntilServer("落地，鼻尖先抽动起来", ctx -> "wait".equals(c.meetStageForTest()) && lastLine("meet_sniff"))
                    .ticks(20).screenshot("lacerta_meet_sniff")
                    .server("递上生牛肉", ctx -> feed(ctx.player(), new ItemStack(Items.BEEF)))
                    .checkServer("「烤熟了再拿来！」", ctx -> meets(REFUSALS) > refusals && lastLine("meet_raw") && "wait".equals(c.meetStageForTest()))
                    .ticks(40)
                    .server("递上面包", ctx -> feed(ctx.player(), new ItemStack(Items.BREAD)))
                    .checkServer("「这种东西也敢端到真龙面前？」", ctx -> lastLine("meet_refuse") && "wait".equals(c.meetStageForTest()))
                    .ticks(40)
                    .server("递上熟牛排", ctx -> feed(ctx.player(), new ItemStack(Items.COOKED_BEEF)))
                    .checkServer("她吃了", ctx -> "eat".equals(c.meetStageForTest()) && c.eating())
                    .ticks(20).screenshot("lacerta_meet_eat")
                    .waitUntilServer("红着脸硬拗一句", ctx -> "proud".equals(c.meetStageForTest()) && lastLine("meet_fed"))
                    .waitUntilServer("单膝立誓", ctx -> "oath".equals(c.meetStageForTest()))
                    .ticks(30).screenshot("lacerta_meet_oath")
                    .waitUntilServer("烙着你名字的龙鳞递到你手里", ctx -> DragonScaleItem.carries(ctx.player()))
                    .waitUntilServer("立契完成：她成了你的同伴", ctx -> meets(PACTS) > pacts && !c.meeting()
                            && Companions.bond(ctx.player(), Companions.LACERTA_DEF).pact() && Companions.lacerta(ctx.player()) == c)
                    .ticks(30).screenshot("lacerta_meet_after");
            tearDown(s);
        }
    }

    @LDLRegisterClient(name = "lacerta_meet_leave", group = "lacerta_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class MeetLeave extends Meet {
        private int leaves;

        @Override
        public void define(ScenarioBuilder s) {
            platform(s).server("她落下来；这回只等三秒", ctx -> {
                        leaves = meets(LEAVES);
                        LacertaMeet.waitForTest = 60;
                        c = LacertaMeet.spawn(ctx.server().overworld(), ctx.player(), FIRE);
                    })
                    .waitUntilServer("鼻尖抽动", ctx -> "wait".equals(c.meetStageForTest()))
                    .waitUntilServer("等不到，鼓着嘴哼一声", ctx -> "leave".equals(c.meetStageForTest()) && lastLine("meet_leave") && meets(LEAVES) > leaves)
                    .ticks(44).screenshot("lacerta_meet_leave")
                    .waitUntilServer("飞走了", ctx -> c.isRemoved())
                    .checkServer("没有立契", ctx -> !Companions.bond(ctx.player(), Companions.LACERTA_DEF).pact() && !DragonScaleItem.carries(ctx.player()));
            tearDown(s);
        }
    }
}
