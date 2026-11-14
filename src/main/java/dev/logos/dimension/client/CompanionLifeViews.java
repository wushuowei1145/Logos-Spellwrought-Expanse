package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.cloudsea.FoxWildsBlocks;
import dev.logos.dimension.companion.CompanionBlade;
import dev.logos.dimension.companion.CompanionLight;
import dev.logos.dimension.companion.CompanionLines;
import dev.logos.dimension.companion.CompanionMind;
import dev.logos.dimension.companion.Companions;
import dev.logos.dimension.companion.KatashiroItem;
import dev.logos.dimension.companion.ShiraumeCompanion;
import dev.logos.dimension.companion.ShiraumePact;
import dev.logos.dimension.item.RyukaItem;
import dev.logos.dimension.ryuka.FoxMarks;
import dev.logos.dimension.shiraume.FoxfireBolt;
import dev.logos.runtime.Familiars;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.phys.Vec3;

/**
 * 实际客户端检查白梅同伴第二版（docs/SHIRAUME_COMPANION.md 第 8 节），同一组 {@code shiraume_companion}：
 * <ul>
 * <li>{@code companion_pact}：封印之间里跪坐等你的分霊，说话后献刀、折形代、起身，成为你的同伴（Logos 认她是长期同伴）；
 * <li>{@code companion_boss_reward}：打倒狐形白梅后，花瓣聚回少女跪坐着等你，场地中央不再掉流火；
 * <li>{@code companion_pact_again}：已经立过契的人再遇到一位：得到空白形代，那位化作花瓣回去；拿空白形代右键她，盖一枚契印；
 * <li>{@code companion_melee}：耐打的尸壳冲她来，她凝出流火，突刺只拉近、接着连砍；没敌人了收刀；
 * <li>{@code companion_vs_foxes}：两只寻常神狐（打刀、铁甲）一起冲她来，她打得过；
 * <li>{@code companion_guard}：骷髅朝主人放箭，她闪过去一刀弹开，再去收拾射手；
 * <li>{@code companion_execute}：主人的流火印在劫掠兽身上爆开，她冲上去上挑接处决；
 * <li>{@code companion_feed}：喂她甜浆果（喜欢）、腐肉（不吃）；她饿了自己去摘浆果，浆果丛扎不到她；
 * <li>{@code companion_soak}：主人泡在一格半深的露天风吕格局里，她浮在旁边泡着不起身；换到浅池，她跟过去坐在池底泡；
 * <li>{@code companion_lantern}：主人在没有天光的地方，她身边飘起一团淡粉狐火；潜行右键打开她的包袱；
 * <li>{@code companion_outfit}：泡温泉换上浴衣「梅灯夜渡」，出浴后还穿着，一打起来换回巫女服；命令也能换。
 * </ul>
 */
public abstract class CompanionLifeViews extends CompanionViews {
    // ---------------------------------------------------------------- 立契

    /** 主人站在两格外和她说话（平常右键的距离；旧的机位二在这里会被主人自己挡住）。 */
    @LDLRegisterClient(name = "companion_pact", group = "shiraume_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Pact extends CompanionLifeViews {
        private static final Vec3 EYE = new Vec3(.5, FLOOR + 2.62, -.8), AT = new Vec3(.5, FLOOR + 1, 1.2);
        private ShiraumeCompanion waiting;
        private long began;

        @Override
        public void define(ScenarioBuilder s) {
            stage(s, GameType.SURVIVAL, 50)
                    .server("站好", ctx -> {
                        aim(ctx.player(), EYE, AT.add(0, .5, 0));
                        ctx.player().getInventory().clearContent();
                    }).ticks(10)
                    .step("对准", ctx -> aimClient(AT.add(0, .5, 0)))
                    .server("放一位等你的白梅", ctx -> waiting = ShiraumePact.spawn(ctx.server().overworld(), ctx.player().getUUID(), AT, 180))
                    .waitUntil("同步到客户端", ctx -> !clientCompanions().isEmpty())
                    .check("她在跪坐等你（只同步给你）", ctx -> clientCompanions().get(0).waiting() && "pact_wait".equals(clientCompanions().get(0).npcMiscAnimation()))
                    .check("刚被打败，跪坐着在发抖", ctx -> clientCompanions().get(0).tremble(0) == 1)
                    .ticks(30)
                    .screenshot("companion_pact_wait")
                    // 过场会把界面藏起来，台词字幕照样画：打开界面拍下来看字幕
                    .step("显示界面", ctx -> Minecraft.getInstance().options.hideGui = false)
                    .server("和她说话", ctx -> {
                        waiting.interact(ctx.player(), InteractionHand.MAIN_HAND, Vec3.ZERO);
                        began = ctx.server().overworld().getGameTime();
                    })
                    .checkServer("过场开始", ctx -> waiting.mode() == ShiraumeCompanion.Mode.PACT)
                    .checkServer("站在两格外的主人不用挪", ctx -> ctx.player().position().subtract(waiting.position()).horizontalDistance() > 1.95
                            && ctx.player().position().subtract(waiting.position()).horizontalDistance() < 2.05)
                    .waitUntilServer("俯身献刀", ctx -> ctx.server().overworld().getGameTime() - began >= 64)
                    .screenshot("companion_pact_sword")
                    .waitUntilServer("流火到手", ctx -> ctx.player().getInventory().contains(new ItemStack(RyukaItem.ITEM.get())))
                    .waitUntilServer("开始折形代", ctx -> ctx.server().overworld().getGameTime() - began >= 100)
                    .screenshot("companion_pact_fold")
                    .waitUntilServer("折好形代递过来", ctx -> ctx.server().overworld().getGameTime() - began >= 140)
                    .screenshot("companion_pact_katashiro")
                    .check("说完、递形代时不抖了", ctx -> clientCompanions().get(0).tremble(0) < .2f)
                    .waitUntilServer("写着名字的形代到手", ctx -> KatashiroItem.carries(ctx.player()))
                    .waitUntilServer("立契完成", ctx -> waiting.isCompanion())
                    .checkServer("契上记着她", ctx -> ctx.player().getData(Companions.BOND).pact() && Companions.out(ctx.player()) == waiting)
                    .checkServer("Logos 认她是没有时限的长期同伴", ctx -> Familiars.companionOf(ctx.player()) == waiting
                            && Familiars.bondOf(waiting).expiresAt() == Long.MAX_VALUE)
                    .checkServer("最后一句台词发出去了", ctx -> CompanionLines.last().endsWith("pact_5"))
                    .ticks(20)
                    .screenshot("companion_pact_done")
                    .teardown("恢复界面", ctx -> Minecraft.getInstance().options.hideGui = false);
        }
    }

    /** 主人贴着她说话（不到一格）：过场开始前先退开到两格，机位才不会被自己挡住。 */
    @LDLRegisterClient(name = "companion_pact_again", group = "shiraume_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class PactAgain extends CompanionLifeViews {
        private static final Vec3 EYE = new Vec3(.5, FLOOR + 2.62, .3), AT = new Vec3(.5, FLOOR + 1, 1.2);
        private ShiraumeCompanion waiting;
        private int spirits;

        @Override
        public void define(ScenarioBuilder s) {
            stage(s, GameType.SURVIVAL, 50)
                    .server("已经立过契", ctx -> {
                        Companions.grantPact(ctx.player());
                        ctx.player().getInventory().clearContent();
                        aim(ctx.player(), EYE, AT.add(0, .5, 0));
                    }).ticks(10)
                    .server("另一块杀生石里的她", ctx -> {
                        waiting = ShiraumePact.spawn(ctx.server().overworld(), ctx.player().getUUID(), AT, 180);
                        spirits = FoxfireBolt.spirits();
                    })
                    .waitUntil("同步到客户端", ctx -> !clientCompanions().isEmpty())
                    .server("和她说话", ctx -> waiting.interact(ctx.player(), InteractionHand.MAIN_HAND, Vec3.ZERO))
                    .checkServer("贴得太近：先退开到两格", ctx -> {
                        System.out.println("[companion] stand off: player " + ctx.player().position() + " her " + waiting.position() + " mode " + waiting.mode());
                        return Math.abs(ctx.player().position().subtract(waiting.position()).horizontalDistance() - ShiraumePact.STAND_OFF) < .05;
                    })
                    .waitUntilServer("她化作花瓣回去了", ctx -> waiting.isRemoved())
                    .checkServer("一缕魂火飞回主人身边", ctx -> FoxfireBolt.spirits() > spirits)
                    .checkServer("得到的是空白形代", ctx -> ctx.player().getInventory().contains(KatashiroItem.blank()) && !KatashiroItem.carries(ctx.player()))
                    .checkServer("还没有契印", ctx -> ctx.player().getData(Companions.BOND).seals() == 0)
                    .server("叫出自己的白梅，拿空白形代右键她", ctx -> {
                        ServerPlayer p = ctx.player();
                        ShiraumeCompanion c = summonAt(p, new Vec3(.5, FLOOR + 1, 1.5));
                        p.setItemInHand(InteractionHand.MAIN_HAND, KatashiroItem.blank());
                        c.interact(p, InteractionHand.MAIN_HAND, Vec3.ZERO);
                    })
                    .checkServer("盖了一枚契印，形代用掉了", ctx -> ctx.player().getData(Companions.BOND).seals() == 1
                            && ctx.player().getMainHandItem().isEmpty())
                    .ticks(30)
                    .screenshot("companion_seal")
                    .teardown("恢复界面", ctx -> Minecraft.getInstance().options.hideGui = false);
        }
    }

    /** 打倒狐形白梅（命令召出，场地以她为心）：花瓣聚回少女，跪坐着等你；场地中央不再掉流火。 */
    @LDLRegisterClient(name = "companion_boss_reward", group = "shiraume_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class BossReward extends CompanionLifeViews {
        private static final Vec3 EYE = new Vec3(.5, FLOOR + 3.2, -7.5), LOOK = new Vec3(.5, FLOOR + 1, 1.5);
        private Mob beast;

        @Override
        public void define(ScenarioBuilder s) {
            stage(s, GameType.SURVIVAL).runCommand("difficulty normal")
                    .server("狐形白梅", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        dev.logos.dimension.shiraume.ShiraumeFight.reset(level);
                        ServerPlayer p = ctx.player();
                        p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 20 * 120, 4, false, false));
                        aim(p, EYE, LOOK);
                        beast = dev.logos.dimension.shiraume.ShiraumeEntities.BEAST.get().create(level, EntitySpawnReason.COMMAND);
                        beast.snapTo(.5, FLOOR + 1, 1.5, 180, 0);
                        level.addFreshEntity(beast);
                        dev.logos.dimension.shiraume.ShiraumeFight.adopt(level, beast);
                    }).ticks(10)
                    .server("打倒", ctx -> beast.hurtServer(ctx.server().overworld(), ctx.server().overworld().damageSources().playerAttack(ctx.player()), 1e6f))
                    .waitUntilServer("花瓣聚回少女，跪坐着等你", ctx -> ctx.server().overworld().getEntitiesOfClass(ShiraumeCompanion.class, AREA,
                            c -> c.waiting() && ctx.player().getUUID().equals(c.ownerId())).size() == 1)
                    .checkServer("场地中央不再掉流火", ctx -> ctx.server().overworld().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, AREA,
                            i -> i.getItem().is(RyukaItem.ITEM.get())).isEmpty())
                    .step("对准她", ctx -> aimClient(clientCompanions().isEmpty() ? LOOK : clientCompanions().get(0).position().add(0, .5, 0)))
                    .ticks(30)
                    .screenshot("companion_boss_reward")
                    .teardown("恢复界面", ctx -> Minecraft.getInstance().options.hideGui = false);
        }
    }

    // ---------------------------------------------------------------- 流火

    /** 主人站在够不着的石台上（不挨打），看着她。 */
    static ServerPlayer onPillar(ServerLevel level, ServerPlayer p, Vec3 eye, Vec3 look) {
        for (int y = FLOOR + 1; y <= FLOOR + 2; y++) level.setBlockAndUpdate(BlockPos.containing(eye.x, y, eye.z), Blocks.SMOOTH_STONE.defaultBlockState());
        p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 20 * 120, 4, false, false));
        aim(p, eye, look);
        return p;
    }

    static Mob spawn(ServerLevel level, EntityType<? extends Mob> type, Vec3 at, float yaw) {
        Mob m = type.create(level, EntitySpawnReason.COMMAND);
        m.snapTo(at.x, at.y, at.z, yaw, 0);
        m.setPersistenceRequired();
        level.addFreshEntity(m);
        return m;
    }

    /**
     * 一只耐打的尸壳（60 血）在三格多外冲她来：她凝刀，突刺只用来拉近（一场下来最多一两记），冲到跟前接着连砍别的招式
     * （用户：照搬 Boss 时突刺冲不远，一记接一记地刺）。
     */
    @LDLRegisterClient(name = "companion_melee", group = "shiraume_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Melee extends CompanionLifeViews {
        private static final Vec3 EYE = new Vec3(-3.5, FLOOR + 4.6, -4.5), LOOK = new Vec3(.5, FLOOR + 1, 1.5);
        private ShiraumeCompanion c;
        private Mob husk;
        private int thrusts, cuts;
        private long began;

        @Override
        public void define(ScenarioBuilder s) {
            stage(s, GameType.SURVIVAL).runCommand("difficulty normal")
                    .server("主人站上石台", ctx -> onPillar(ctx.server().overworld(), ctx.player(), EYE, LOOK)).ticks(10)
                    .step("对准", ctx -> aimClient(LOOK))
                    .server("召唤；一只 60 血的尸壳在三格多外冲她来", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        c = summonAt(ctx.player(), new Vec3(.5, FLOOR + 1, .5));
                        husk = spawn(level, EntityType.HUSK, new Vec3(.5, FLOOR + 1, 3.9), 180);
                        husk.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(60);
                        husk.setHealth(60);
                        husk.setTarget(c);
                        thrusts = CompanionBlade.started("tsuki");
                        cuts = CompanionBlade.startedTotal() - thrusts;
                        began = level.getGameTime();
                    })
                    .waitUntilServer("凝出流火", ctx -> c.blade().drawn())
                    .check("客户端看到刀", ctx -> clientCompanions().get(0).bladeShown())
                    .waitUntilServer("挥刀", ctx -> c.blade().attacking())
                    .ticks(6)
                    .screenshot("companion_melee_cut")
                    .waitUntilServer("尸壳倒下", ctx -> !husk.isAlive())
                    .checkServer("突刺只拉近用，冲到跟前接着砍别的", ctx -> {
                        int t = CompanionBlade.started("tsuki") - thrusts, o = CompanionBlade.startedTotal() - CompanionBlade.started("tsuki") - cuts;
                        long ticks = ctx.server().overworld().getGameTime() - began;
                        System.out.println("[companion] melee: " + ticks + " ticks, thrusts=" + t + " other cuts=" + o);
                        // 每记突刺后面至少接 COMBO_AFTER_THRUST 刀（刀砍得进去以后尸壳倒得快，一记突刺加两刀就够了）
                        return t <= 1 + ticks / CompanionMind.THRUST_GAP && o >= CompanionMind.COMBO_AFTER_THRUST * Math.max(1, t);
                    })
                    .waitUntilServer("没敌人了，收刀", ctx -> !c.blade().drawn())
                    .ticks(5)
                    .check("客户端里刀散了", ctx -> Minecraft.getInstance().level.getEntity(c.getId()) instanceof ShiraumeCompanion cc && !cc.bladeShown())
                    .teardown("恢复界面", ctx -> Minecraft.getInstance().options.hideGui = false);
        }
    }

    /**
     * 主人打了一头不还手、也不跑的牛（不是敌对生物，也没冲着谁来；30 血，不到“强敌”，她不先缠狐火；三格多外）：她过去拿刀砍，刀得真的砍进去
     * （用户：对着目标攻击不造成伤害。刀的敌我过滤只认敌对生物和冲着她、主人来的，她自己挑的目标反倒砍不进去）。
     */
    @LDLRegisterClient(name = "companion_owner_target", group = "shiraume_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class OwnerTarget extends CompanionLifeViews {
        // 主人站在旁边矮石台上（六格内，不然她先跟着主人走）
        private static final Vec3 EYE = new Vec3(-2, FLOOR + 4.6, -1.5), LOOK = new Vec3(.5, FLOOR + 1, 2.5);
        private ShiraumeCompanion c;
        private Mob cow;
        private int landed;

        @Override
        public void define(ScenarioBuilder s) {
            stage(s, GameType.SURVIVAL).runCommand("difficulty normal")
                    .server("主人站上石台", ctx -> onPillar(ctx.server().overworld(), ctx.player(), EYE, LOOK)).ticks(10)
                    .step("对准", ctx -> aimClient(LOOK))
                    .server("召唤", ctx -> c = summonAt(ctx.player(), new Vec3(.5, FLOOR + 1, .5)))
                    .waitUntilServer("入场播完", ctx -> !"summon".equals(c.npcMiscAnimation()))
                    .server("她身边三格多一头不动的牛（30 血；再远她就放狐火了）", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        c.getNavigation().stop();
                        cow = spawn(level, EntityType.COW, new Vec3(c.getX(), FLOOR + 1, c.getZ() + 3.2), 180);
                        cow.setNoAi(true);
                        cow.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(30);
                        cow.setHealth(30);
                        landed = CompanionBlade.landed();
                    })
                    .server("主人打它一下", ctx -> cow.hurtServer(ctx.server().overworld(), ctx.player().damageSources().playerAttack(ctx.player()), 1))
                    .waitUntilServer("凝出流火", ctx -> trace() && c.blade().drawn())
                    .waitUntilServer("砍进去两刀", ctx -> trace() && CompanionBlade.landed() - landed >= 2)
                    .screenshot("companion_owner_target")
                    .checkServer("牛掉了刀伤的血", ctx -> {
                        System.out.println("[companion] owner target: cow " + cow.getHealth() + "/30, " + CompanionBlade.tally());
                        return cow.getHealth() <= 30 - 1 - 2 * c.foxfireDamage() * CompanionBlade.BLADE * .8;
                    })
                    .teardown("恢复界面", ctx -> Minecraft.getInstance().options.hideGui = false);
        }

        /** 每秒记一行她在想什么（失败时看得出卡在哪）。 */
        private boolean trace() {
            if (c.tickCount % 20 == 0 && c.mind() != null) {
                System.out.println("[companion] owner target t=" + c.tickCount + " d=" + String.format("%.2f", c.distanceTo(cow)) + " cow=" + cow.getHealth()
                        + " " + c.mind().describe() + " | " + CompanionBlade.tally());
            }
            return true;
        }
    }

    /**
     * 两只寻常的神狐（打刀、一身铁甲，同自然生成的样子；不带弓）一起冲她来：她得打得过
     * （用户：沿用 Boss 出招太慢，两只普通神狐都打不过）。神狐重伤会逃，逃走的也算打赢。连打 {@link #ROUNDS} 轮（每轮满血、新的两只；
     * 上一轮倒下了就重新叫出来），至少赢 {@link #WINS} 轮：近战有随机（双方的格挡），只看一轮说明不了什么。
     */
    @LDLRegisterClient(name = "companion_vs_foxes", group = "shiraume_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class VsFoxes extends CompanionLifeViews {
        private static final Vec3 EYE = new Vec3(-6.5, FLOOR + 4.6, -4.5), LOOK = new Vec3(.5, FLOOR + 1, 2.5), START = new Vec3(.5, FLOOR + 1, .5);
        static final int ROUNDS = 3, WINS = 2;
        private final java.util.List<dev.logos.dimension.npc.DivineFox> foxes = new java.util.ArrayList<>();
        private ShiraumeCompanion c;
        private long began;
        private int wins;

        @Override
        public void define(ScenarioBuilder s) {
            stage(s, GameType.SURVIVAL).runCommand("difficulty normal")
                    .server("主人站上石台", ctx -> {
                        onPillar(ctx.server().overworld(), ctx.player(), EYE, LOOK);
                        c = null;
                        wins = 0;
                    })
                    .step("对准", ctx -> aimClient(LOOK));
            for (int round = 1; round <= ROUNDS; round++) {
                int r = round;
                s.server("第 " + r + " 轮：她满血回到原地（倒下了就重新叫出来）", ctx -> {
                            foxes.forEach(f -> f.discard());
                            foxes.clear();
                            if (c == null || !c.isAlive() || c.isRemoved()) {
                                if (c != null) c.discard();
                                Companions.wake(ctx.player());
                                c = summonAt(ctx.player(), START);
                            }
                            c.setHealth(c.getMaxHealth());
                            c.teleportTo(START.x, START.y, START.z);
                        })
                        .waitUntilServer("第 " + r + " 轮：入场播完", ctx -> !"summon".equals(c.npcMiscAnimation()))
                        .server("第 " + r + " 轮：两只神狐冲她来", ctx -> {
                            ServerLevel level = ctx.server().overworld();
                            foxes.add(fox(level, new Vec3(-2.5, FLOOR + 1, 5.5), r));
                            foxes.add(fox(level, new Vec3(3.5, FLOOR + 1, 5.5), r + 1));
                            foxes.forEach(f -> f.setTarget(c));
                            began = level.getGameTime();
                            System.out.println("[companion] round " + r + " start, " + CompanionBlade.tally());
                        });
                if (r == 1) s.ticks(70).screenshot("companion_vs_foxes");
                s.waitUntilServer("第 " + r + " 轮：两只都打倒了（或逃走了），或者她倒下了", ctx -> {
                            long t = ctx.server().overworld().getGameTime() - began;
                            if (t % 20 == 0) System.out.println("[companion] t=" + t + " " + c.mind().current() + " drawn=" + c.blade().drawn() + " move="
                                    + (c.blade().move() == null ? "-" : c.blade().move().name()) + " cast=" + c.casting() + " hp=" + c.getHealth() + " foxes "
                                    + foxes.stream().map(f -> String.format("d=%.1f hp=%.1f %s%s", f.distanceTo(c), f.getHealth(),
                                            f.katanaMove() == null ? "-" : f.katanaMove().name(), f.fleeing() ? " fled" : "")).toList());
                            return !c.isAlive() || c.isDeadOrDying() || foxes.stream().allMatch(f -> !f.isAlive() || f.fleeing());
                        })
                        .server("第 " + r + " 轮：记下输赢", ctx -> {
                            long ticks = ctx.server().overworld().getGameTime() - began;
                            boolean won = c.isAlive() && !c.isDeadOrDying();
                            if (won) wins++;
                            System.out.println("[companion] round " + r + (won ? " WON" : " LOST") + ": " + ticks + " ticks, her health " + c.getHealth() + "/"
                                    + c.getMaxHealth() + ", foxes " + foxes.stream().map(f -> f.isAlive() ? (f.fleeing() ? "fled " : "") + f.getHealth() : "dead").toList()
                                    + ", blade damage " + c.foxfireDamage() * CompanionBlade.BLADE + ", " + CompanionBlade.tally());
                        });
            }
            s.checkServer("三轮里至少赢两轮", ctx -> wins >= WINS)
                    .teardown("恢复界面", ctx -> Minecraft.getInstance().options.hideGui = false);
        }

        /** 一只寻常的神狐：同 {@code DivineFox.finalizeSpawn} 的打刀和铁甲，不带弓。 */
        static dev.logos.dimension.npc.DivineFox fox(ServerLevel level, Vec3 at, int katana) {
            var f = dev.logos.dimension.npc.NpcEntities.DIVINE_FOX.get().create(level, EntitySpawnReason.COMMAND);
            f.setAppearance(dev.logos.dimension.npc.NpcParts.DIVINE_FOX.random(level.getRandom()));
            f.setKatana(katana);
            f.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
            f.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
            f.setItemSlot(net.minecraft.world.entity.EquipmentSlot.LEGS, new ItemStack(Items.IRON_LEGGINGS));
            f.setItemSlot(net.minecraft.world.entity.EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
            f.snapTo(at.x, at.y, at.z, 180, 0);
            f.setPersistenceRequired();
            level.addFreshEntity(f);
            return f;
        }
    }

    @LDLRegisterClient(name = "companion_guard", group = "shiraume_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Guard extends CompanionLifeViews {
        private static final Vec3 EYE = new Vec3(-4.5, FLOOR + 2.62, -1.5), LOOK = new Vec3(4.5, FLOOR + 1.5, 2.5);
        private int before;
        private Mob skeleton;
        private ShiraumeCompanion c;

        @Override
        public void define(ScenarioBuilder s) {
            // 骷髅不动，由测试替它朝主人放一箭：骷髅自己的 AI 会在挨了她的打后改打她，那时射的就不是主人了
            stage(s, GameType.SURVIVAL).runCommand("difficulty normal")
                    .server("主人站在地上；一只不动的骷髅在十格外", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        ServerPlayer p = ctx.player();
                        p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 20 * 120, 4, false, false));
                        aim(p, EYE, LOOK);
                        c = summonAt(p, new Vec3(-2.5, FLOOR + 1, -2.5));
                        skeleton = spawn(level, EntityType.SKELETON, new Vec3(5.5, FLOOR + 1, 3.5), 135);
                        skeleton.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
                        skeleton.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
                        skeleton.setNoAi(true);
                    })
                    .step("对准", ctx -> aimClient(LOOK))
                    .waitUntilServer("入场播完", ctx -> !"summon".equals(c.npcMiscAnimation()))
                    .server("骷髅朝主人放一箭", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        ServerPlayer p = ctx.player();
                        before = CompanionMind.intercepts();
                        var arrow = new net.minecraft.world.entity.projectile.arrow.Arrow(level, skeleton, new ItemStack(Items.ARROW), new ItemStack(Items.BOW));
                        Vec3 to = p.position().add(0, p.getBbHeight() * .6, 0).subtract(arrow.position());
                        arrow.shoot(to.x, to.y + to.horizontalDistance() * .05, to.z, 1.6f, 0);
                        level.addFreshEntity(arrow);
                    })
                    .waitUntilServer("她截下这支射向主人的箭", ctx -> CompanionMind.intercepts() > before)
                    .ticks(2)
                    .screenshot("companion_guard_deflect")
                    .waitUntilServer("再去收拾射手：定为目标", ctx -> c.mind().foe() == skeleton)
                    .waitUntilServer("射手挨了她的打", ctx -> skeleton.getHealth() < skeleton.getMaxHealth())
                    .teardown("恢复界面", ctx -> Minecraft.getInstance().options.hideGui = false);
        }
    }

    @LDLRegisterClient(name = "companion_execute", group = "shiraume_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Execute extends CompanionLifeViews {
        private static final Vec3 EYE = new Vec3(-5.5, FLOOR + 3.6, -3.5), LOOK = new Vec3(.5, FLOOR + 2, 3.5);
        private Mob ravager;
        private int before;

        @Override
        public void define(ScenarioBuilder s) {
            stage(s, GameType.SURVIVAL).runCommand("difficulty normal")
                    .server("主人站上石台；一只不动的劫掠兽", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        onPillar(level, ctx.player(), EYE, LOOK);
                        summonAt(ctx.player(), new Vec3(-1.5, FLOOR + 1, .5));
                        ravager = spawn(level, EntityType.RAVAGER, new Vec3(.5, FLOOR + 1, 4.5), 180);
                        ravager.setNoAi(true);
                        before = CompanionBlade.executions();
                    }).ticks(10)
                    .step("对准", ctx -> aimClient(LOOK))
                    .server("主人的流火印攒满五枚，爆开", ctx -> {
                        for (int i = 0; i < FoxMarks.FULL; i++) FoxMarks.add(ctx.player(), ravager);
                    })
                    .checkServer("可以处决了", ctx -> FoxMarks.executable(ravager))
                    .waitUntilServer("她接上处决", ctx -> CompanionBlade.executions() > before)
                    .ticks(14)
                    .screenshot("companion_execute")
                    .waitUntilServer("处决结束", ctx -> !Companions.out(ctx.player()).blade().executing())
                    .checkServer("这次爆开用掉了", ctx -> !FoxMarks.executable(ravager))
                    .teardown("恢复界面", ctx -> Minecraft.getInstance().options.hideGui = false);
        }
    }

    // ---------------------------------------------------------------- 日常

    @LDLRegisterClient(name = "companion_feed", group = "shiraume_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Feed extends CompanionLifeViews {
        private static final Vec3 EYE = new Vec3(.5, FLOOR + 2.62, -1.6), LOOK = new Vec3(.5, FLOOR + 1.6, 1.2);
        private static final BlockPos BUSH = new BlockPos(4, FLOOR + 1, 4);
        private ShiraumeCompanion c;
        private int meals, picked;
        private float health;

        @Override
        public void define(ScenarioBuilder s) {
            stage(s, GameType.SURVIVAL, 45).runCommand("difficulty peaceful")
                    .server("站好，召唤", ctx -> {
                        ServerPlayer p = ctx.player();
                        aim(p, EYE, LOOK);
                        c = summonAt(p, new Vec3(.5, FLOOR + 1, 1.2));
                        meals = ShiraumeCompanion.meals();
                    })
                    .waitUntilServer("入场播完", ctx -> !"summon".equals(c.npcMiscAnimation()))
                    .step("对准", ctx -> aimClient(LOOK))
                    .server("递给她一颗甜浆果", ctx -> {
                        ServerPlayer p = ctx.player();
                        Companions.setSatiety(p, 8);
                        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SWEET_BERRIES, 4));
                        c.interact(p, InteractionHand.MAIN_HAND, Vec3.ZERO);
                    })
                    .checkServer("她接过去吃", ctx -> c.eating() && ctx.player().getMainHandItem().getCount() == 3)
                    .ticks(16)
                    .screenshot("companion_eat")
                    .waitUntilServer("咽下去", ctx -> ShiraumeCompanion.meals() > meals)
                    .checkServer("一起吃饭加了好感，饱了一些", ctx -> ctx.player().getData(Companions.BOND).affection() > 0
                            && ctx.player().getData(Companions.BOND).satiety() > 8)
                    .waitUntilServer("吃完", ctx -> !c.eating())
                    .server("递给她腐肉", ctx -> {
                        ctx.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.ROTTEN_FLESH));
                        c.interact(ctx.player(), InteractionHand.MAIN_HAND, Vec3.ZERO);
                    })
                    .checkServer("她摇头不吃", ctx -> "refuse".equals(c.npcMiscAnimation()) && ctx.player().getMainHandItem().getCount() == 1)
                    .ticks(25)
                    // 饿了：自己去摘熟了的浆果（浆果丛扎不到她）
                    .server("一丛熟了的甜浆果；她饿了", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        level.setBlockAndUpdate(BUSH.below(), Blocks.GRASS_BLOCK.defaultBlockState());
                        level.setBlockAndUpdate(BUSH, Blocks.SWEET_BERRY_BUSH.defaultBlockState().setValue(SweetBerryBushBlock.AGE, 3));
                        ServerPlayer p = ctx.player();
                        Companions.setSatiety(p, 2);
                        picked = ShiraumeCompanion.picked();
                        meals = ShiraumeCompanion.meals();
                        health = c.getHealth();
                    })
                    .waitUntilServer("她去摘了", ctx -> ShiraumeCompanion.picked() > picked)
                    .checkServer("浆果摘了，丛回到没结果的样子", ctx -> ctx.server().overworld().getBlockState(BUSH).getValue(SweetBerryBushBlock.AGE) == 1)
                    .waitUntilServer("摘下来先吃一颗", ctx -> ShiraumeCompanion.meals() > meals)
                    .checkServer("浆果丛没扎到她", ctx -> c.getHealth() >= health)
                    .checkServer("多的放进了包袱", ctx -> Companions.pouch(ctx.player()).countItem(Items.SWEET_BERRIES) > 0)
                    .teardown("恢复界面", ctx -> Minecraft.getInstance().options.hideGui = false);
        }
    }

    /**
     * 东边是神狐聚落露天风吕的格局（池底含水石半砖、下面地热石，半砖上一格水，约一格半深），西边是一格深的浅池。
     * 主人先泡深的：她浮在水里、水面停在肩头，一直泡着不起身（用户：一格半深的露天温泉里她被水淹没、退出泡温泉）；
     * 主人换到浅池：她起身走过去，坐在池底泡。
     */
    @LDLRegisterClient(name = "companion_soak", group = "shiraume_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Soak extends CompanionLifeViews {
        /** 主人泡在深池、浅池正中那一格（脚底）。 */
        private static final Vec3 IN_DEEP = new Vec3(2.5, FLOOR + .5, 1.5), IN_SHALLOW = new Vec3(-2.5, FLOOR + 1, 1.5);
        /** 两个池子的水面（一格水，上面是空气）。 */
        private static final double SURFACE = FLOOR + 1 + 8 / 9.0;
        private ShiraumeCompanion c;
        private int ends;

        @Override
        public void define(ScenarioBuilder s) {
            stage(s, GameType.SURVIVAL, 55).runCommand("difficulty peaceful")
                    .server("深池（含水半砖上一格水）和浅池（地热石上一格水），主人泡在深池里", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        var slab = Blocks.SMOOTH_STONE_SLAB.defaultBlockState();
                        for (int x = -4; x <= 4; x++)
                            for (int z = -1; z <= 3; z++) {
                                boolean deep = x >= 1 && x <= 3 && z >= 0 && z <= 2, shallow = x >= -3 && x <= -1 && z >= 0 && z <= 2;
                                if (deep) {
                                    level.setBlockAndUpdate(new BlockPos(x, FLOOR - 1, z), FoxWildsBlocks.HOT_SPRING_STONE.get().defaultBlockState());
                                    level.setBlockAndUpdate(new BlockPos(x, FLOOR, z), slab.setValue(net.minecraft.world.level.block.SlabBlock.WATERLOGGED, true));
                                    level.setBlockAndUpdate(new BlockPos(x, FLOOR + 1, z), Blocks.WATER.defaultBlockState());
                                } else if (shallow) {
                                    level.setBlockAndUpdate(new BlockPos(x, FLOOR, z), FoxWildsBlocks.HOT_SPRING_STONE.get().defaultBlockState());
                                    level.setBlockAndUpdate(new BlockPos(x, FLOOR + 1, z), Blocks.WATER.defaultBlockState());
                                } else {
                                    level.setBlockAndUpdate(new BlockPos(x, FLOOR + 1, z), slab);
                                }
                            }
                        ServerPlayer p = ctx.player();
                        c = summonAt(p, new Vec3(.5, FLOOR + 1, -3.5));
                        aim(p, IN_DEEP.add(0, p.getEyeHeight(), 0), new Vec3(-.5, FLOOR + 1, -2.5));
                    })
                    .waitUntilServer("她也下到深池里泡着", ctx -> c.soaking() && "soak".equals(c.npcMiscAnimation()) && c.getX() > 1)
                    .ticks(30)
                    .checkServer("浮在水里，水面停在肩头（不沉到池底）", ctx -> {
                        System.out.println("[companion] soak deep: y=" + (c.getY() - FLOOR) + " want " + (SURFACE - CompanionMind.SOAK_WATERLINE - FLOOR));
                        return Math.abs(c.getY() - (SURFACE - CompanionMind.SOAK_WATERLINE)) < .05;
                    })
                    .server("回头看她", ctx -> {
                        ends = CompanionMind.soakEnds();
                        aim(ctx.player(), IN_DEEP.add(0, ctx.player().getEyeHeight(), 0), c.position().add(0, .7, 0));
                    })
                    .step("对准", ctx -> aimClient(clientCompanions().get(0).position().add(0, .7, 0)))
                    .ticks(20)
                    .screenshot("companion_soak")
                    // 呛水要 15 秒（300 刻）：泡过这么久还在泡，也没呛着
                    .ticks(320)
                    .checkServer("一直泡着没起身，也没呛水", ctx -> c.soaking() && CompanionMind.soakEnds() == ends && c.getAirSupply() == c.getMaxAirSupply()
                            && c.getHealth() >= c.getMaxHealth())
                    .server("主人换到浅池", ctx -> aim(ctx.player(), IN_SHALLOW.add(0, ctx.player().getEyeHeight(), 0), new Vec3(2.5, FLOOR + 1, 1.5)))
                    .waitUntilServer("她起身走过去，在浅池里泡", ctx -> c.soaking() && c.getX() < 0)
                    .ticks(30)
                    .checkServer("浅池里坐在池底", ctx -> Math.abs(c.getY() - (FLOOR + 1)) < .05)
                    .teardown("恢复界面", ctx -> Minecraft.getInstance().options.hideGui = false);
        }
    }

    @LDLRegisterClient(name = "companion_lantern", group = "shiraume_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Lantern extends CompanionLifeViews {
        private ShiraumeCompanion c;

        @Override
        public void define(ScenarioBuilder s) {
            stage(s, GameType.SURVIVAL, 60).runCommand("difficulty peaceful")
                    .server("露天召唤", ctx -> {
                        ServerPlayer p = ctx.player();
                        p.getAbilities().flying = false;
                        p.onUpdateAbilities();
                        p.teleportTo(-1.5, FLOOR + 1, -1.5);
                        c = summonAt(p, new Vec3(1.5, FLOOR + 1, 1.5));
                    })
                    .waitUntilServer("入场播完", ctx -> !"summon".equals(c.npcMiscAnimation()))
                    .ticks(20)
                    .checkServer("露天不点灯", ctx -> !c.lanternOn() && CompanionLight.count(ctx.server().overworld()) == 0)
                    .server("把两人罩进石头屋子：没有天光", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        for (int x = -4; x <= 4; x++)
                            for (int z = -4; z <= 4; z++)
                                for (int y = FLOOR + 1; y <= FLOOR + 5; y++) {
                                    boolean wall = Math.abs(x) == 4 || Math.abs(z) == 4 || y == FLOOR + 5;
                                    if (wall) level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState());
                                }
                    })
                    .waitUntilServer("她身边飘起狐火灯", ctx -> CompanionLight.count(ctx.server().overworld()) > 0 && c.lanternOn())
                    .server("看她", ctx -> aim(ctx.player(), ctx.player().getEyePosition(), c.position().add(0, .9, 0)))
                    .step("对准", ctx -> aimClient(clientCompanions().get(0).position().add(0, .9, 0)))
                    .ticks(25)
                    .check("客户端里那团狐火亮起来了", ctx -> clientCompanions().get(0).lanternGlow(0) > .9f)
                    .screenshot("companion_lantern")
                    .ticks(35)
                    .screenshot("companion_lantern_2")
                    .server("潜行右键她", ctx -> {
                        ServerPlayer p = ctx.player();
                        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                        p.setShiftKeyDown(true);
                        c.interact(p, InteractionHand.MAIN_HAND, Vec3.ZERO);
                        p.setShiftKeyDown(false);
                    })
                    .checkServer("打开了她的包袱", ctx -> ctx.player().containerMenu instanceof ChestMenu)
                    .server("关上", ctx -> ctx.player().closeContainer())
                    .server("收回她", ctx -> Companions.dismiss(ctx.player()))
                    .waitUntilServer("灯拆掉了", ctx -> CompanionLight.count(ctx.server().overworld()) == 0)
                    .teardown("恢复界面", ctx -> Minecraft.getInstance().options.hideGui = false);
        }
    }

    // ---------------------------------------------------------------- 装束

    /**
     * 装束（第 8.10 节）：平时巫女服；主人泡温泉，她下水泡着时换上浴衣「梅灯夜渡」（客户端隐藏巫女服的组）；主人出浴后她还穿着；
     * 一打起来就换回巫女服。命令也能换。
     */
    @LDLRegisterClient(name = "companion_outfit", group = "shiraume_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Outfit extends CompanionLifeViews {
        private static final Vec3 IN_POOL = new Vec3(-1.5, FLOOR + 1, 1.5), DRY = new Vec3(-1.5, FLOOR + 1, -2.5);
        private ShiraumeCompanion c;

        private static dev.logos.dimension.companion.CompanionOutfit clientOutfit() {
            return clientCompanions().isEmpty() ? null : clientCompanions().get(0).outfit();
        }

        @Override
        public void define(ScenarioBuilder s) {
            stage(s, GameType.SURVIVAL, 55).runCommand("difficulty normal")
                    .server("三乘三的浅温泉，主人泡在里面", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        for (int x = -3; x <= 1; x++)
                            for (int z = 0; z <= 4; z++) {
                                boolean pool = x >= -2 && x <= 0 && z >= 1 && z <= 3;
                                if (pool) {
                                    level.setBlockAndUpdate(new BlockPos(x, FLOOR, z), FoxWildsBlocks.HOT_SPRING_STONE.get().defaultBlockState());
                                    level.setBlockAndUpdate(new BlockPos(x, FLOOR + 1, z), Blocks.WATER.defaultBlockState());
                                } else {
                                    level.setBlockAndUpdate(new BlockPos(x, FLOOR + 1, z), Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
                                }
                            }
                        ServerPlayer p = ctx.player();
                        p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 20 * 120, 4, false, false));
                        c = summonAt(p, new Vec3(2.5, FLOOR + 1, -1.5));
                        aim(p, IN_POOL.add(0, p.getEyeHeight(), 0), new Vec3(-.5, FLOOR + 1, 3.5));
                    })
                    .waitUntil("同步到客户端", ctx -> clientOutfit() != null)
                    .check("平时穿巫女服", ctx -> clientOutfit() == dev.logos.dimension.companion.CompanionOutfit.MIKO)
                    .waitUntilServer("她下水泡着，换上了浴衣", ctx -> c.soaking() && c.outfit() == dev.logos.dimension.companion.CompanionOutfit.UMEAKARI)
                    .server("看她", ctx -> aim(ctx.player(), IN_POOL.add(0, ctx.player().getEyeHeight(), 0), c.position().add(0, .7, 0)))
                    .step("对准", ctx -> aimClient(clientCompanions().get(0).position().add(0, .7, 0)))
                    .ticks(20)
                    .check("客户端也是浴衣", ctx -> clientOutfit() == dev.logos.dimension.companion.CompanionOutfit.UMEAKARI)
                    .screenshot("companion_outfit_soak")
                    .server("主人出浴，站到岸上", ctx -> aim(ctx.player(), DRY.add(0, ctx.player().getEyeHeight(), 0), new Vec3(-.5, FLOOR + 1, 1.5)))
                    .waitUntilServer("她也出来了", ctx -> !c.soaking() && !c.isInWater())
                    .ticks(40)
                    .server("看她", ctx -> aim(ctx.player(), ctx.player().getEyePosition(), c.position().add(0, .8, 0)))
                    .step("对准", ctx -> aimClient(clientCompanions().get(0).position().add(0, .8, 0)))
                    .ticks(10)
                    .checkServer("出浴后还穿着浴衣", ctx -> c.outfit() == dev.logos.dimension.companion.CompanionOutfit.UMEAKARI)
                    .screenshot("companion_outfit_yukata")
                    .server("一只尸壳冲她来", ctx -> spawn(ctx.server().overworld(), EntityType.HUSK, c.position().add(3, 0, 0), 90).setTarget(c))
                    .waitUntilServer("一打起来换回巫女服", ctx -> c.outfit() == dev.logos.dimension.companion.CompanionOutfit.MIKO)
                    .server("清掉尸壳", ctx -> ctx.server().overworld().getEntitiesOfClass(Mob.class, AREA, m -> m.getType() == EntityType.HUSK).forEach(Mob::discard))
                    .ticks(40)
                    .server("用命令换上浴衣（以主人的身份）", ctx -> ctx.server().getCommands().performPrefixedCommand(
                            ctx.server().createCommandSourceStack().withEntity(ctx.player()).withLevel(ctx.player().level()), "alchemy companion outfit umeakari"))
                    .checkServer("命令也能换上浴衣", ctx -> c.outfit() == dev.logos.dimension.companion.CompanionOutfit.UMEAKARI)
                    .teardown("恢复界面", ctx -> Minecraft.getInstance().options.hideGui = false);
        }
    }
}
