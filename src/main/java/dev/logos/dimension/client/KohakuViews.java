package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.LogosMod;
import dev.logos.dimension.client.companion.CompanionPanelScreen;
import dev.logos.dimension.companion.CompanionMind;
import dev.logos.dimension.companion.CompanionTactic;
import dev.logos.dimension.companion.Companions;
import dev.logos.dimension.companion.KohakuCompanion;
import dev.logos.dimension.companion.PawSovereignItem;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/**
 * 实际客户端检查琥珀（docs/KOHAKU_COMPANION.md），一组 {@code kohaku_companion}：
 * <ul>
 * <li>{@code kohaku_summon}：还没立契时右键一枚空白的爪印金镑——金镑压上名字、立契，地上转开法阵，她从阵心蹦出来，拄着紫晶长杖；再用一次收回。</li>
 * <li>{@code kohaku_fight}：十几格外两只僵尸冲主人来，她先给自己罩上琥珀护膜，站到主人斜后方，用一滴就够把它们打倒；主人一直没挨她的打。</li>
 * <li>{@code kohaku_passive}：不参与攻击时她不施法，僵尸冲过来她还是朝它哈气；一只幻翼扑向主人，她一哈气，幻翼丢了目标飞走。</li>
 * <li>{@code kohaku_staff}：包袱里放一根回响魔杖，她换上你给的；拿走了换回自己的；盖三枚爪之契，她自己的换成炼金长杖。</li>
 * <li>{@code kohaku_withdraw}：她挨了致命的一下不死：倒下（为同伴打到最后），地上转开法阵把她卷回金镑里；金镑上转起冷却。</li>
 * <li>{@code kohaku_talk}：潜行右键说话（中文拍）；聊天、详细数据三页（生命、法术强度、魔力；能力；往事）。</li>
 * </ul>
 */
public abstract class KohakuViews extends CompanionViews {
    /** 主人站在台子南边看她：她在台子正中。 */
    static final Vec3 EYE = new Vec3(.5, FLOOR + 2.62, -4.5), LOOK = new Vec3(.5, FLOOR + 1.3, .5);

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("kohaku_companion").defaultTimeoutMs(60_000).scenarioTimeoutMs(180_000);
    }

    /** 台子（同白梅的）；清掉琥珀、她的契、金镑的冷却和背包。 */
    static ScenarioBuilder kohakuStage(ScenarioBuilder s, GameType mode) {
        return stage(s, mode).server("清掉琥珀", ctx -> {
            ServerPlayer p = ctx.player();
            Companions.dismiss(p, Companions.KOHAKU_DEF);
            ctx.server().overworld().getEntitiesOfClass(KohakuCompanion.class, AREA.inflate(8)).forEach(Entity::discard);
            ctx.server().overworld().getEntitiesOfClass(Mob.class, AREA.inflate(24), m -> !(m instanceof dev.logos.dimension.companion.CompanionEntity))
                    .forEach(Entity::discard);
            p.setData(Companions.KOHAKU_BOND, Companions.Bond.NONE);
            p.setData(Companions.KOHAKU_POUCH, new dev.logos.dimension.companion.CompanionPouch());
            p.setData(Companions.KOHAKU_DAYS, dev.logos.dimension.companion.KohakuDays.NONE);
            KohakuCompanion.luckyForTest(false);
            KohakuCompanion.sleepForTest(null, null);
            p.getCooldowns().removeCooldown(p.getCooldowns().getCooldownGroup(PawSovereignItem.blank()));
            p.getInventory().clearContent();
            p.removeAllEffects();
            p.setHealth(p.getMaxHealth());
            p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 20 * 150, 4, false, false));
        });
    }

    /** 立契并在 {@code at} 叫她出来。 */
    static KohakuCompanion summonKohaku(ServerPlayer p, Vec3 at) {
        Companions.grantPact(p, Companions.KOHAKU_DEF);
        Companions.summon(p, Companions.KOHAKU_DEF, at);
        KohakuCompanion c = Companions.kohaku(p);
        if (c == null) throw new IllegalStateException("没召唤出来");
        return c;
    }

    /** 法阵转完、入场动画播完。 */
    static boolean arrived(KohakuCompanion c) {
        return !c.inCircle() && !"summon".equals(c.npcMiscAnimation()) && !c.hiddenForSummon();
    }

    // ---------------------------------------------------------------- 召唤

    @LDLRegisterClient(name = "kohaku_summon", group = "kohaku_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Summon extends KohakuViews {
        private KohakuCompanion c;

        @Override
        public void define(ScenarioBuilder s) {
            kohakuStage(s, GameType.SURVIVAL).runCommand("difficulty peaceful")
                    .server("站好，手里一枚空白的爪印金镑", ctx -> {
                        ServerPlayer p = ctx.player();
                        aim(p, EYE, LOOK);
                        p.setItemInHand(InteractionHand.MAIN_HAND, PawSovereignItem.blank());
                    })
                    .step("对准", ctx -> aimClient(LOOK))
                    .ticks(10)
                    .server("右键用它", ctx -> {
                        ServerPlayer p = ctx.player();
                        PawSovereignItem.ITEM.get().use(p.level(), p, InteractionHand.MAIN_HAND);
                        c = Companions.kohaku(p);
                        if (c != null) c.teleportTo(SPOT.x, SPOT.y, SPOT.z);
                    })
                    .checkServer("立了契，这一枚压上了主人的名字；地上的炼成阵在转，她还没出来", ctx -> {
                        ServerPlayer p = ctx.player();
                        return Companions.bond(p, Companions.KOHAKU_DEF).pact() && PawSovereignItem.boundTo(p.getMainHandItem(), p) && c != null
                                && c.inCircle() && c.hiddenForSummon() && c.circleAge(0) >= 0;
                    })
                    .checkServer("入场时长杖收着", ctx -> c.getMainHandItem().isEmpty())
                    .ticks(12).screenshot("kohaku_summon_circle")
                    .waitUntilServer("法阵转开，她蹦出来", ctx -> "summon".equals(c.npcMiscAnimation()) && !c.hiddenForSummon())
                    .ticks(3).screenshot("kohaku_summon_pop")
                    .ticks(10).screenshot("kohaku_summon_land")
                    .ticks(30).screenshot("kohaku_summon_yawn")
                    .waitUntilServer("入场播完", ctx -> arrived(c))
                    .waitUntilServer("拿出她自己的紫晶长杖拄着", ctx -> c.getMainHandItem().is(LogosMod.CRYSTAL_STAFF.get()))
                    .checkServer("法阵收起来了", ctx -> c.circleAge(0) < 0)
                    .ticks(10).screenshot("kohaku_summon_staff")
                    .server("再用一次金镑：收回", ctx -> {
                        ServerPlayer p = ctx.player();
                        p.getCooldowns().removeCooldown(p.getCooldowns().getCooldownGroup(p.getMainHandItem()));
                        PawSovereignItem.ITEM.get().use(p.level(), p, InteractionHand.MAIN_HAND);
                    })
                    .waitUntilServer("她挥手、散去", ctx -> c.isRemoved() && Companions.kohaku(ctx.player()) == null)
                    .checkServer("收回不算倒下：不用歇", ctx -> Companions.restLeft(ctx.player(), Companions.KOHAKU_DEF) == 0);
        }
    }

    // ---------------------------------------------------------------- 打

    @LDLRegisterClient(name = "kohaku_fight", group = "kohaku_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Fight extends KohakuViews {
        private static final Vec3 SIDE_EYE = new Vec3(-6.5, FLOOR + 2.62, 2.5), SIDE_LOOK = new Vec3(.5, FLOOR + 1.3, 6.5);
        private KohakuCompanion c;
        private Mob z1, z2;
        private int wards, casts;
        private float health;

        @Override
        public void define(ScenarioBuilder s) {
            kohakuStage(s, GameType.SURVIVAL).runCommand("difficulty normal")
                    .server("站好、召唤", ctx -> {
                        ServerPlayer p = ctx.player();
                        aim(p, SIDE_EYE, SIDE_LOOK);
                        c = summonKohaku(p, SPOT);
                    })
                    .step("对准", ctx -> aimClient(SIDE_LOOK))
                    .waitUntilServer("入场播完", ctx -> arrived(c))
                    .ticks(40)
                    .server("十几格外两只僵尸冲着主人来", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        ServerPlayer p = ctx.player();
                        health = p.getHealth();
                        wards = KohakuCompanion.wards();
                        casts = KohakuCompanion.casts();
                        z1 = PrincessViews.helmeted(CompanionLifeViews.spawn(level, EntityType.ZOMBIE, new Vec3(-1.5, FLOOR + 1, 13.5), 180));
                        z2 = PrincessViews.helmeted(CompanionLifeViews.spawn(level, EntityType.ZOMBIE, new Vec3(3.5, FLOOR + 1, 14.5), 180));
                        z1.setTarget(p);
                        z2.setTarget(p);
                    })
                    .waitUntilServer("她拄杖凝护膜", ctx -> "cast_ward".equals(move(c)))
                    .ticks(11).screenshot("kohaku_fight_ward")
                    .waitUntilServer("琥珀护膜罩上了：一倍生命上限", ctx -> KohakuCompanion.wards() > wards && c.getAbsorptionAmount() >= c.getMaxHealth() - .5f)
                    .waitUntilServer("杖尖一点：一滴就够", ctx -> "cast_staff".equals(move(c)))
                    .ticks(8).screenshot("kohaku_fight_cast")
                    .waitUntilServer("放出去了", ctx -> KohakuCompanion.casts() > casts)
                    .ticks(3).screenshot("kohaku_fight_drop")
                    .waitUntilServer("她站在主人斜后方：离两只僵尸都比主人远", ctx -> !z1.isAlive() && !z2.isAlive()
                            || (!z1.isAlive() || c.distanceTo(z1) > ctx.player().distanceTo(z1)) && (!z2.isAlive() || c.distanceTo(z2) > ctx.player().distanceTo(z2)))
                    .waitUntilServer("两只僵尸都倒下（戴着帽子不会晒着，是她打倒的）", ctx -> !z1.isAlive() && !z2.isAlive())
                    .checkServer("主人一直没挨她的打（弹射物碰到主人不伤）", ctx -> ctx.player().getHealth() >= health)
                    .checkServer("魔力花掉了一些，正在回", ctx -> c.mana() < c.maxMana());
        }
    }

    /** 叫出来时魔力是满的（不算之后换上的杖加的上限）。 */
    @LDLRegisterClient(name = "kohaku_mana", group = "kohaku_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Mana extends KohakuViews {
        private KohakuCompanion c;

        @Override
        public void define(ScenarioBuilder s) {
            kohakuStage(s, GameType.SURVIVAL).runCommand("difficulty peaceful")
                    .server("召唤", ctx -> c = summonKohaku(ctx.player(), SPOT))
                    .checkServer("一出来魔力就是满的（身上的加上已经往杖里充的）", ctx -> c.available() >= c.maxMana() - 1 && c.maxMana() > 400);
        }
    }

    static String move(KohakuCompanion c) {
        var m = c.npcCombatMove();
        return m == null ? "" : m.animation();
    }

    // ---------------------------------------------------------------- 不参与攻击：哈气、幻翼

    @LDLRegisterClient(name = "kohaku_passive", group = "kohaku_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Passive extends KohakuViews {
        private KohakuCompanion c;
        private Mob zombie;
        private Phantom phantom;
        private int hisses, casts, scared;

        @Override
        public void define(ScenarioBuilder s) {
            kohakuStage(s, GameType.SURVIVAL).runCommand("difficulty normal")
                    .server("站好、召唤、行动策略：不参与攻击", ctx -> {
                        ServerPlayer p = ctx.player();
                        aim(p, EYE, LOOK);
                        c = summonKohaku(p, SPOT);
                        var b = Companions.bond(p, Companions.KOHAKU_DEF);
                        p.setData(Companions.KOHAKU_BOND, b.withTactic(CompanionTactic.PASSIVE));
                    })
                    .step("对准", ctx -> aimClient(LOOK))
                    .waitUntilServer("入场播完", ctx -> arrived(c))
                    .server("一只僵尸从前面冲过来", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        hisses = KohakuCompanion.hisses();
                        casts = KohakuCompanion.casts();
                        zombie = PrincessViews.helmeted(CompanionLifeViews.spawn(level, EntityType.ZOMBIE, new Vec3(.5, FLOOR + 1, 9.5), 180));
                        zombie.setTarget(ctx.player());
                    })
                    .waitUntilServer("她朝僵尸哈气", ctx -> KohakuCompanion.hisses() > hisses && "act_guard".equals(c.npcMiscAnimation()))
                    .checkServer("哈气时右手空着：长杖收起来了", ctx -> c.getMainHandItem().isEmpty())
                    .ticks(6).screenshot("kohaku_passive_hiss")
                    .checkServer("没有施法打它", ctx -> KohakuCompanion.casts() == casts)
                    .waitUntilServer("哈完又拄着长杖", ctx -> !"act_guard".equals(c.npcMiscAnimation()) && c.getMainHandItem().is(LogosMod.CRYSTAL_STAFF.get()))
                    .server("僵尸走开，一只幻翼扑向主人", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        zombie.discard();
                        scared = KohakuCompanion.phantomsScared();
                        phantom = (Phantom) CompanionLifeViews.spawn(level, EntityType.PHANTOM, new Vec3(.5, FLOOR + 7, 8.5), 180);
                        phantom.setTarget(ctx.player());
                    })
                    .waitUntilServer("她一哈气，幻翼不敢扑下来：丢了目标、往上飞开", ctx -> KohakuCompanion.phantomsScared() > scared && phantom.getTarget() == null)
                    .ticks(4).screenshot("kohaku_passive_phantom")
                    .server("收掉幻翼", ctx -> phantom.discard());
        }
    }

    // ---------------------------------------------------------------- 长杖

    @LDLRegisterClient(name = "kohaku_staff", group = "kohaku_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Staff extends KohakuViews {
        private KohakuCompanion c;

        @Override
        public void define(ScenarioBuilder s) {
            kohakuStage(s, GameType.SURVIVAL).runCommand("difficulty peaceful")
                    .server("站好、召唤", ctx -> {
                        ServerPlayer p = ctx.player();
                        aim(p, EYE, LOOK);
                        c = summonKohaku(p, SPOT);
                    })
                    .step("对准", ctx -> aimClient(LOOK))
                    .waitUntilServer("入场播完", ctx -> arrived(c))
                    .waitUntilServer("拄着紫晶长杖", ctx -> c.getMainHandItem().is(LogosMod.CRYSTAL_STAFF.get()))
                    .server("包袱里放一根回响魔杖", ctx -> Companions.pouch(ctx.player(), Companions.KOHAKU_DEF).setItem(0, new ItemStack(LogosMod.ECHO_WAND.get())))
                    .waitUntilServer("她换上你给的（哪怕不是长杖）", ctx -> c.getMainHandItem().is(LogosMod.ECHO_WAND.get()))
                    .ticks(6).screenshot("kohaku_staff_yours")
                    .server("拿走", ctx -> Companions.pouch(ctx.player(), Companions.KOHAKU_DEF).setItem(0, ItemStack.EMPTY))
                    .waitUntilServer("换回她自己的", ctx -> c.getMainHandItem().is(LogosMod.CRYSTAL_STAFF.get()))
                    .server("盖三枚爪之契", ctx -> {
                        for (int i = 0; i < 3; i++) Companions.addSeal(ctx.player(), Companions.KOHAKU_DEF);
                    })
                    .waitUntilServer("她自己换成炼金长杖", ctx -> c.getMainHandItem().is(LogosMod.TRANSMUTED_STAFF.get()))
                    .ticks(6).screenshot("kohaku_staff_transmuted")
                    .checkServer("魔力上限算上了炼金长杖的 300", ctx -> c.maxMana() >= 400 + 300 + 300 - .5);
        }
    }

    // ---------------------------------------------------------------- 撤退

    @LDLRegisterClient(name = "kohaku_withdraw", group = "kohaku_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Withdraw extends KohakuViews {
        private KohakuCompanion c;
        private int withdrawn;

        @Override
        public void define(ScenarioBuilder s) {
            kohakuStage(s, GameType.SURVIVAL).runCommand("difficulty peaceful")
                    .server("站好、召唤", ctx -> {
                        ServerPlayer p = ctx.player();
                        aim(p, EYE, LOOK);
                        c = summonKohaku(p, SPOT);
                        p.setItemInHand(InteractionHand.MAIN_HAND, PawSovereignItem.bound(p));
                    })
                    .step("对准", ctx -> aimClient(LOOK))
                    .waitUntilServer("入场播完", ctx -> arrived(c))
                    .server("她挨了致命的一下", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        withdrawn = KohakuCompanion.withdrawn();
                        c.invulnerableTime = 0;
                        c.hurtServer(level, level.damageSources().generic(), 1000);
                    })
                    .checkServer("没有死：她在撤退", ctx -> c.isAlive() && !c.isDeadOrDying() && c.withdrawing())
                    .checkServer("撤退时打不到", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        c.invulnerableTime = 0;
                        return !c.hurtServer(level, level.damageSources().generic(), 5);
                    })
                    .waitUntilServer("受击以后倒下（为同伴打到了最后）", ctx -> "death".equals(c.npcMiscAnimation()))
                    .ticks(40).screenshot("kohaku_withdraw_kneel")
                    .ticks(55).screenshot("kohaku_withdraw_slump")
                    .waitUntilServer("倒下以后地上转开炼成阵", ctx -> c.circleAge(0) >= 0)
                    .ticks(8).screenshot("kohaku_withdraw_circle")
                    .ticks(10).screenshot("kohaku_withdraw_column")
                    .ticks(10).screenshot("kohaku_withdraw_gone")
                    .waitUntilServer("法阵把她卷回金镑里", ctx -> c.isRemoved() && KohakuCompanion.withdrawn() > withdrawn)
                    .checkServer("要歇五分钟，不会自动叫回", ctx -> {
                        ServerPlayer p = ctx.player();
                        var b = Companions.bond(p, Companions.KOHAKU_DEF);
                        return Companions.restLeft(p, Companions.KOHAKU_DEF) > 5800 && !b.comeBack() && b.out().isEmpty();
                    })
                    .checkServer("金镑上转起冷却（还要歇多久就看它）", ctx -> ctx.player().getCooldowns().isOnCooldown(PawSovereignItem.blank()))
                    .checkServer("还在歇着，叫不出来", ctx -> Companions.summon(ctx.player(), Companions.KOHAKU_DEF, null) != null);
        }
    }

    // ---------------------------------------------------------------- 说话、面板

    @LDLRegisterClient(name = "kohaku_talk", group = "kohaku_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Talk extends KohakuViews {
        private static final Vec3 TALK_EYE = new Vec3(.5, FLOOR + 2.62, -1.9), AT = new Vec3(.5, FLOOR + 1, 1.2);
        private KohakuCompanion c;
        private java.util.concurrent.CompletableFuture<Void> reload = java.util.concurrent.CompletableFuture.completedFuture(null);

        private void language(String code) {
            var mc = Minecraft.getInstance();
            mc.getLanguageManager().setSelected(code);
            mc.options.languageCode = code;
            reload = mc.reloadResourcePacks();
        }

        @Override
        public void define(ScenarioBuilder s) {
            s.step("切成中文", ctx -> language("zh_cn")).waitUntil("资源重载完", ctx -> reload.isDone());
            kohakuStage(s, GameType.SURVIVAL).runCommand("difficulty peaceful")
                    .server("站好、召唤", ctx -> {
                        ServerPlayer p = ctx.player();
                        p.removeAllEffects();
                        aim(p, TALK_EYE, AT.add(0, .9, 0));
                        c = summonKohaku(p, AT);
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
                    .checkServer("她站住、看着主人", ctx -> c.talking() && c.mind().current() == CompanionMind.Kind.TALK)
                    .step("字出完", ctx -> CompanionTalkViews.talk().skip()).ticks(6)
                    .screenshot("kohaku_talk_open")
                    .step("聊天", ctx -> CompanionTalkViews.talk().choose("chat")).ticks(6)
                    .check("她自己的话题", ctx -> CompanionTalkViews.talk().options().equals(List.of("chat.coin", "chat.food", "chat.alchemy",
                            "chat.critique", "chat.lazy", "chat.city", "chat.money", "chat.allowance", "chat.enforcers", "chat.past", "chat.seals", "chat.us",
                            "chat.outfit", "chat.rest", "back")))
                    .step("炼金是什么样的？", ctx -> CompanionTalkViews.talk().choose("chat.alchemy"))
                    .waitUntil("她回话", ctx -> CompanionTalkViews.said("alchemy_1"))
                    .step("跳到最后", ctx -> CompanionTalkViews.talk().skip()).ticks(6)
                    .screenshot("kohaku_talk_alchemy")
                    .step("返回 → 详细数据", ctx -> {
                        CompanionTalkViews.talk().skip();
                        CompanionTalkViews.talk().skip();
                        CompanionTalkViews.talk().choose("back");
                        CompanionTalkViews.talk().choose("stats");
                    })
                    .waitUntil("角色面板打开", ctx -> CompanionTalkViews.panel() != null)
                    .ticks(20)
                    .check("面板上的数：生命 28（自己 24，继承主人 20 的两成）、法术强度 1.2、魔力一行、护甲 0", ctx -> {
                        var sheet = CompanionTalkViews.panel().parent().sheet();
                        return Math.abs(sheet.stat("health").value() - 28) < .01 && Math.abs(sheet.stat("spell_power").value() - 1.2) < .01
                                && sheet.stat("mana").value() > 400 && sheet.stat("armor").value() < .01
                                && sheet.stats().stream().noneMatch(st -> st.id().equals("attack"));
                    })
                    .screenshot("kohaku_panel_stats")
                    .step("契印 · 能力", ctx -> CompanionTalkViews.panel().tab(CompanionPanelScreen.Tab.SKILLS)).ticks(4)
                    .screenshot("kohaku_panel_skills")
                    .step("往事", ctx -> CompanionTalkViews.panel().tab(CompanionPanelScreen.Tab.MEMORIES)).ticks(4)
                    .screenshot("kohaku_panel_memories")
                    .step("关掉", ctx -> Minecraft.getInstance().setScreen(null)).ticks(4)
                    .step("切回英文", ctx -> language("en_us")).waitUntil("资源重载完", ctx -> reload.isDone());
        }
    }
}
