package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.client.companion.CompanionPanelScreen;
import dev.logos.dimension.companion.BaggedWorldTreeItem;
import dev.logos.dimension.companion.CompanionMind;
import dev.logos.dimension.companion.Companions;
import dev.logos.dimension.companion.ElfPrincessCompanion;
import dev.logos.dimension.companion.ShiraumeCompanion;
import dev.logos.dimension.item.WorldtreeBowItem;
import dev.logos.lang.Element;
import dev.logos.runtime.ManaWrap;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/**
 * 实际客户端检查精灵公主（docs/ELF_PRINCESS_COMPANION.md），一组 {@code elf_princess_companion}：
 * <ul>
 * <li>{@code princess_summon}：还没立契时右键一袋空白的袋装世界树——袋子写上名字、立契，她从花苞里出来；再用一次收回。</li>
 * <li>{@code princess_bow}：十几格外两只僵尸冲主人来，她拿出弓、锁定、放追踪箭；一只贴到跟前，她横着拉弓散射、往后跳开；
 *     主人一直没挨她的箭。</li>
 * <li>{@code princess_heal}：主人掉到四成血，她伸手治疗；第二印以后，主人快撑不住又被僵尸盯着，先给护盾。</li>
 * <li>{@code princess_seals}：四枚叶之契——对劫掠兽放荆棘缠绕、给在打它的主人的手缠上生命之力；主人掉到两成血，万分之一一下回一半。</li>
 * <li>{@code princess_with_shiraume}：白梅和她一起在外面（叫出她时白梅不会被送回去，要新的 Logos）；白梅掉到一半血，她给白梅治疗。</li>
 * <li>{@code princess_withdraw}：她挨了致命的一下不死：跪下、荷花长出来合拢、沉进地里，回到袋子里歇着；袋子上转起冷却。</li>
 * <li>{@code princess_talk}：潜行右键说话（中文拍）；聊天、详细数据三页（法术强度、能力、往事）。</li>
 * </ul>
 */
public abstract class PrincessViews extends CompanionViews {
    /** 主人站在台子南边看她：她在台子正中。 */
    static final Vec3 EYE = new Vec3(.5, FLOOR + 2.62, -4.5), LOOK = new Vec3(.5, FLOOR + 1.3, .5);

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("elf_princess_companion").defaultTimeoutMs(60_000).scenarioTimeoutMs(180_000);
    }

    /** 台子（同白梅的）；清掉精灵公主、她的契、袋子的冷却和背包。 */
    static ScenarioBuilder princessStage(ScenarioBuilder s, GameType mode) {
        return stage(s, mode).server("清掉精灵公主", ctx -> {
            ServerPlayer p = ctx.player();
            Companions.dismiss(p, Companions.PRINCESS_DEF);
            ctx.server().overworld().getEntitiesOfClass(ElfPrincessCompanion.class, AREA.inflate(8)).forEach(Entity::discard);
            p.setData(Companions.PRINCESS_BOND, Companions.Bond.NONE);
            p.getCooldowns().removeCooldown(p.getCooldowns().getCooldownGroup(BaggedWorldTreeItem.blank()));
            p.getInventory().clearContent();
            p.removeAllEffects();
            p.setHealth(p.getMaxHealth());
            p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 20 * 150, 4, false, false));
        });
    }

    /** 立契并在 {@code at} 叫她出来。 */
    static ElfPrincessCompanion summonPrincess(ServerPlayer p, Vec3 at) {
        Companions.grantPact(p, Companions.PRINCESS_DEF);
        Companions.summon(p, Companions.PRINCESS_DEF, at);
        ElfPrincessCompanion c = Companions.princess(p);
        if (c == null) throw new IllegalStateException("没召唤出来");
        return c;
    }

    // ---------------------------------------------------------------- 召唤

    @LDLRegisterClient(name = "princess_summon", group = "elf_princess_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Summon extends PrincessViews {
        private ElfPrincessCompanion c;

        @Override
        public void define(ScenarioBuilder s) {
            princessStage(s, GameType.SURVIVAL).runCommand("difficulty peaceful")
                    .server("站好，手里一袋空白的袋装世界树", ctx -> {
                        ServerPlayer p = ctx.player();
                        aim(p, EYE, LOOK);
                        p.setItemInHand(InteractionHand.MAIN_HAND, BaggedWorldTreeItem.blank());
                    })
                    .step("对准", ctx -> aimClient(LOOK))
                    .ticks(10)
                    .server("右键用它", ctx -> {
                        ServerPlayer p = ctx.player();
                        BaggedWorldTreeItem.ITEM.get().use(p.level(), p, InteractionHand.MAIN_HAND);
                        c = Companions.princess(p);
                        // 叫出来那一刻挪到台子正中，好拍
                        if (c != null) c.teleportTo(SPOT.x, SPOT.y, SPOT.z);
                    })
                    .checkServer("立了契，这一袋写上了主人的名字，她在入场", ctx -> {
                        ServerPlayer p = ctx.player();
                        return Companions.bond(p, Companions.PRINCESS_DEF).pact() && BaggedWorldTreeItem.boundTo(p.getMainHandItem(), p)
                                && c != null && "summon".equals(c.npcMiscAnimation());
                    })
                    .ticks(8).screenshot("princess_summon_sprout")
                    .ticks(16).screenshot("princess_summon_bud")
                    .ticks(20).screenshot("princess_summon_bloom")
                    .ticks(8).screenshot("princess_summon_scatter")
                    .ticks(14).screenshot("princess_summon_greet")
                    .waitUntilServer("入场播完", ctx -> !"summon".equals(c.npcMiscAnimation()))
                    .checkServer("平时手里空着（打起来才拿弓）", ctx -> c.getMainHandItem().isEmpty())
                    .server("再用一次袋子：收回", ctx -> {
                        ServerPlayer p = ctx.player();
                        p.getCooldowns().removeCooldown(p.getCooldowns().getCooldownGroup(p.getMainHandItem()));
                        BaggedWorldTreeItem.ITEM.get().use(p.level(), p, InteractionHand.MAIN_HAND);
                    })
                    .waitUntilServer("她挥手、化作花瓣散去", ctx -> c.isRemoved() && Companions.princess(ctx.player()) == null)
                    .checkServer("收回不算倒下：不用歇", ctx -> Companions.restLeft(ctx.player(), Companions.PRINCESS_DEF) == 0);
        }
    }

    // ---------------------------------------------------------------- 弓

    @LDLRegisterClient(name = "princess_bow", group = "elf_princess_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Bow extends PrincessViews {
        private static final Vec3 SIDE_EYE = new Vec3(-6.5, FLOOR + 2.62, 2.5), SIDE_LOOK = new Vec3(.5, FLOOR + 1.3, 6.5);
        private ElfPrincessCompanion c;
        private Mob z1, z2, z3;
        private int homing, fans;
        private float health;

        @Override
        public void define(ScenarioBuilder s) {
            princessStage(s, GameType.SURVIVAL).runCommand("difficulty normal")
                    .server("站好、召唤", ctx -> {
                        ServerPlayer p = ctx.player();
                        aim(p, SIDE_EYE, SIDE_LOOK);
                        c = summonPrincess(p, SPOT);
                    })
                    .step("对准", ctx -> aimClient(SIDE_LOOK))
                    .waitUntilServer("入场播完", ctx -> !"summon".equals(c.npcMiscAnimation()))
                    .server("十几格外两只僵尸冲着主人来", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        ServerPlayer p = ctx.player();
                        health = p.getHealth();
                        homing = ElfPrincessCompanion.homingShot();
                        z1 = helmeted(CompanionLifeViews.spawn(level, EntityType.ZOMBIE, new Vec3(-1.5, FLOOR + 1, 13.5), 180));
                        z2 = helmeted(CompanionLifeViews.spawn(level, EntityType.ZOMBIE, new Vec3(3.5, FLOOR + 1, 14.5), 180));
                        z1.setTarget(p);
                        z2.setTarget(p);
                    })
                    .waitUntilServer("她拿出世界树之弓、拉弓", ctx -> c.isUsingItem() && c.getMainHandItem().is(WorldtreeBowItem.ITEM.get()))
                    .ticks(8).screenshot("princess_bow_draw")
                    .waitUntilServer("放箭：追踪箭飞向僵尸", ctx -> ElfPrincessCompanion.homingShot() > homing)
                    .ticks(4).screenshot("princess_bow_volley")
                    .waitUntilServer("她站到主人身后：离两只僵尸都比主人远", ctx -> !z1.isAlive() && !z2.isAlive()
                            || (!z1.isAlive() || c.distanceTo(z1) > ctx.player().distanceTo(z1)) && (!z2.isAlive() || c.distanceTo(z2) > ctx.player().distanceTo(z2)))
                    .waitUntilServer("两只僵尸都倒下（戴着帽子不会晒着，是她射倒的）", ctx -> !z1.isAlive() && !z2.isAlive())
                    .server("一只僵尸贴到她跟前", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        fans = ElfPrincessCompanion.fans();
                        Vec3 at = c.position().add(0, 0, 2.2);
                        z3 = helmeted(CompanionLifeViews.spawn(level, EntityType.ZOMBIE, at, 180));
                        z3.setTarget(c);
                    })
                    .waitUntilServer("横着拉弓散射", ctx -> ElfPrincessCompanion.fans() > fans)
                    .screenshot("princess_bow_fan")
                    .waitUntilServer("放完往后跳开", ctx -> c.distanceTo(z3) > 3.6)
                    .waitUntilServer("僵尸倒下", ctx -> !z3.isAlive())
                    .checkServer("主人一直没挨她的箭（扇形箭也绕开主人）", ctx -> ctx.player().getHealth() >= health)
                    .waitUntilServer("打完了，她把弓收起来", ctx -> c.getMainHandItem().isEmpty());
        }
    }

    /** 戴上皮帽子：白天不着火，倒下只能是她射的。 */
    static Mob helmeted(Mob m) {
        m.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new ItemStack(net.minecraft.world.item.Items.LEATHER_HELMET));
        m.setDropChance(net.minecraft.world.entity.EquipmentSlot.HEAD, 0);
        return m;
    }

    // ---------------------------------------------------------------- 治疗、护盾

    @LDLRegisterClient(name = "princess_heal", group = "elf_princess_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Heal extends PrincessViews {
        private ElfPrincessCompanion c;
        private Mob zombie;
        private int mends, shields, breaths;
        private float health;

        @Override
        public void define(ScenarioBuilder s) {
            princessStage(s, GameType.SURVIVAL).runCommand("difficulty normal")
                    .server("站好、召唤、盖两枚叶之契；主人只剩四成血", ctx -> {
                        ServerPlayer p = ctx.player();
                        p.removeAllEffects();
                        aim(p, EYE, LOOK);
                        c = summonPrincess(p, SPOT);
                        for (int i = 0; i < 2; i++) Companions.addSeal(p, Companions.PRINCESS_DEF);
                    })
                    .step("对准", ctx -> aimClient(LOOK))
                    .waitUntilServer("入场播完", ctx -> !"summon".equals(c.npcMiscAnimation()))
                    .server("主人只剩四成血", ctx -> {
                        ServerPlayer p = ctx.player();
                        p.setHealth(8);
                        health = p.getHealth();
                        mends = ElfPrincessCompanion.mends();
                        breaths = ElfPrincessCompanion.breaths();
                    })
                    .waitUntilServer("她伸手治疗", ctx -> ElfPrincessCompanion.mends() > mends)
                    .screenshot("princess_heal")
                    .checkServer("主人回了一口血（5 × 她的法强）", ctx -> ctx.player().getHealth() >= health + 4.9f)
                    .waitUntilServer("常青之息：站在她身边受了伤，隔一会儿自己回一点血", ctx -> ElfPrincessCompanion.breaths() > breaths)
                    .server("主人只剩三成血，一只僵尸盯着主人", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        ServerPlayer p = ctx.player();
                        p.setHealth(6);
                        p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 20 * 60, 4, false, false));
                        shields = ElfPrincessCompanion.shields();
                        zombie = CompanionLifeViews.spawn(level, EntityType.ZOMBIE, new Vec3(.5, FLOOR + 1, 7.5), 180);
                        zombie.setTarget(p);
                    })
                    .waitUntilServer("叶之护：先给主人一层护盾", ctx -> ElfPrincessCompanion.shields() > shields && ctx.player().getAbsorptionAmount() > 0)
                    .screenshot("princess_shield");
        }
    }

    // ---------------------------------------------------------------- 契印：荆棘、缠绕、万分之一

    @LDLRegisterClient(name = "princess_seals", group = "elf_princess_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Seals extends PrincessViews {
        private static final Vec3 HIGH_EYE = new Vec3(.5, FLOOR + 6.5, -5.5), HIGH_LOOK = new Vec3(.5, FLOOR + 1, 5.5);
        private ElfPrincessCompanion c;
        private Mob ravager;
        private int snares, blesses, chances;

        @Override
        public void define(ScenarioBuilder s) {
            princessStage(s, GameType.SURVIVAL).runCommand("difficulty normal")
                    .server("站好、召唤、盖四枚叶之契", ctx -> {
                        ServerPlayer p = ctx.player();
                        aim(p, EYE, LOOK);
                        c = summonPrincess(p, new Vec3(-1.5, FLOOR + 1, -2.5));
                        for (int i = 0; i < 4; i++) Companions.addSeal(p, Companions.PRINCESS_DEF);
                    })
                    .step("对准", ctx -> aimClient(LOOK))
                    .waitUntilServer("入场播完", ctx -> !"summon".equals(c.npcMiscAnimation()))
                    .server("一只不动的劫掠兽（100 血）就在前面，主人砍它一下", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        ServerPlayer p = ctx.player();
                        snares = ElfPrincessCompanion.snares();
                        blesses = ElfPrincessCompanion.blesses();
                        ravager = CompanionLifeViews.spawn(level, EntityType.RAVAGER, new Vec3(.5, FLOOR + 1, 4.5), 180);
                        ravager.setNoAi(true);
                        p.attack(ravager);
                    })
                    .waitUntilServer("荆棘缠绕：荆棘在劫掠兽脚边绕一圈", ctx -> ElfPrincessCompanion.snares() > snares)
                    .server("从高处看", ctx -> CompanionLifeViews.onPillar(ctx.server().overworld(), ctx.player(), HIGH_EYE, HIGH_LOOK))
                    .step("对准", ctx -> aimClient(HIGH_LOOK))
                    .ticks(24).screenshot("princess_thorns")
                    .server("主人再砍它一下（主人在打强敌）", ctx -> ctx.player().attack(ravager))
                    .waitUntilServer("生命缠绕：主人的手缠上生命之力", ctx -> ElfPrincessCompanion.blesses() > blesses
                            && ManaWrap.of(ctx.player()) != null && ManaWrap.of(ctx.player()).element() == Element.LIFE)
                    .server("劫掠兽收掉；主人只剩两成血", ctx -> {
                        ServerPlayer p = ctx.player();
                        ravager.discard();
                        p.removeAllEffects();
                        p.setHealth(4);
                        chances = ElfPrincessCompanion.chances();
                    })
                    .waitUntilServer("万分之一：一下回了一半的血", ctx -> ElfPrincessCompanion.chances() > chances && ctx.player().getHealth() >= 13.9f)
                    .screenshot("princess_chance")
                    .server("又掉到两成", ctx -> {
                        ctx.player().setHealth(4);
                        chances = ElfPrincessCompanion.chances();
                    })
                    .ticks(40)
                    .checkServer("一天只有一次", ctx -> ElfPrincessCompanion.chances() == chances);
        }
    }

    // ---------------------------------------------------------------- 和白梅一起

    @LDLRegisterClient(name = "princess_with_shiraume", group = "elf_princess_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class WithShiraume extends PrincessViews {
        private ElfPrincessCompanion c;
        private ShiraumeCompanion shiraume;
        private int mends;
        private float health;

        @Override
        public void define(ScenarioBuilder s) {
            princessStage(s, GameType.SURVIVAL).runCommand("difficulty peaceful")
                    .server("站好；先叫白梅，再叫精灵公主", ctx -> {
                        ServerPlayer p = ctx.player();
                        aim(p, EYE, LOOK);
                        Companions.grantPact(p, Companions.SHIRAUME_DEF);
                        Companions.summon(p, Companions.SHIRAUME_DEF, new Vec3(2.5, FLOOR + 1, .5));
                        shiraume = Companions.out(p);
                        c = summonPrincess(p, new Vec3(-1.5, FLOOR + 1, .5));
                    })
                    .step("对准", ctx -> aimClient(LOOK))
                    .waitUntilServer("两位的入场都播完", ctx -> !"summon".equals(c.npcMiscAnimation()) && !"summon".equals(shiraume.npcMiscAnimation()))
                    .ticks(20)
                    .checkServer("叫出精灵公主时白梅没被送回去：两位都在外面", ctx -> shiraume != null && shiraume.isAlive() && !shiraume.leaving()
                            && Companions.out(ctx.player()) == shiraume && Companions.princess(ctx.player()) == c)
                    .checkServer("Logos 也认两位都是主人的长期同伴", ctx -> dev.logos.runtime.Familiars.of(ctx.player()).containsAll(List.of(shiraume, c)))
                    .screenshot("princess_with_shiraume")
                    .server("白梅掉到一半血", ctx -> {
                        shiraume.setHealth(shiraume.getMaxHealth() * .5f);
                        health = shiraume.getHealth();
                        mends = ElfPrincessCompanion.mends();
                    })
                    .waitUntilServer("精灵公主给白梅治疗", ctx -> ElfPrincessCompanion.mends() > mends && shiraume.getHealth() > health)
                    .screenshot("princess_heals_shiraume");
        }
    }

    // ---------------------------------------------------------------- 撤退

    @LDLRegisterClient(name = "princess_withdraw", group = "elf_princess_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Withdraw extends PrincessViews {
        private ElfPrincessCompanion c;
        private int withdrawn;

        @Override
        public void define(ScenarioBuilder s) {
            princessStage(s, GameType.SURVIVAL).runCommand("difficulty peaceful")
                    .server("站好、召唤", ctx -> {
                        ServerPlayer p = ctx.player();
                        aim(p, EYE, LOOK);
                        c = summonPrincess(p, SPOT);
                        p.setItemInHand(InteractionHand.MAIN_HAND, BaggedWorldTreeItem.bound(p));
                    })
                    .step("对准", ctx -> aimClient(LOOK))
                    .waitUntilServer("入场播完", ctx -> !"summon".equals(c.npcMiscAnimation()))
                    .server("她挨了致命的一下", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        withdrawn = ElfPrincessCompanion.withdrawn();
                        c.invulnerableTime = 0;
                        c.hurtServer(level, level.damageSources().generic(), 1000);
                    })
                    .checkServer("没有死：世界树把她收回去（撤退）", ctx -> c.isAlive() && !c.isDeadOrDying() && c.withdrawing()
                            && "withdraw".equals(c.npcMiscAnimation()))
                    .checkServer("撤退时打不到", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        c.invulnerableTime = 0;
                        return !c.hurtServer(level, level.damageSources().generic(), 5);
                    })
                    .ticks(26).screenshot("princess_withdraw_bloom")
                    .ticks(18).screenshot("princess_withdraw_closing")
                    .ticks(12).screenshot("princess_withdraw_sink")
                    .waitUntilServer("花苞沉进地里，她回到袋子里", ctx -> c.isRemoved() && ElfPrincessCompanion.withdrawn() > withdrawn)
                    .checkServer("要歇五分钟，不会自动叫回", ctx -> {
                        ServerPlayer p = ctx.player();
                        var b = Companions.bond(p, Companions.PRINCESS_DEF);
                        return Companions.restLeft(p, Companions.PRINCESS_DEF) > 5900 && !b.comeBack() && b.out().isEmpty();
                    })
                    .checkServer("袋子上转起冷却（还要歇多久就看它）", ctx -> ctx.player().getCooldowns().isOnCooldown(BaggedWorldTreeItem.blank()))
                    .checkServer("还在歇着，叫不出来", ctx -> Companions.summon(ctx.player(), Companions.PRINCESS_DEF, null) != null);
        }
    }

    // ---------------------------------------------------------------- 说话、面板

    @LDLRegisterClient(name = "princess_talk", group = "elf_princess_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Talk extends PrincessViews {
        private static final Vec3 TALK_EYE = new Vec3(.5, FLOOR + 2.62, -1.9), AT = new Vec3(.5, FLOOR + 1, 1.2);
        private ElfPrincessCompanion c;
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
            princessStage(s, GameType.SURVIVAL).runCommand("difficulty peaceful")
                    .server("站好、召唤", ctx -> {
                        ServerPlayer p = ctx.player();
                        p.removeAllEffects();
                        aim(p, TALK_EYE, AT.add(0, .9, 0));
                        c = summonPrincess(p, AT);
                    })
                    .step("对准", ctx -> aimClient(AT.add(0, .9, 0)))
                    .waitUntilServer("入场播完", ctx -> !"summon".equals(c.npcMiscAnimation()))
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
                    .screenshot("princess_talk_open")
                    .step("聊天", ctx -> CompanionTalkViews.talk().choose("chat")).ticks(6)
                    .check("她自己的话题", ctx -> CompanionTalkViews.talk().options().equals(List.of("chat.bag", "chat.food", "chat.forest", "chat.mission",
                            "chat.past", "chat.leaves", "chat.us", "chat.rest", "back")))
                    .step("森林在哭泣？", ctx -> CompanionTalkViews.talk().choose("chat.forest"))
                    .waitUntil("她回话", ctx -> CompanionTalkViews.said("forest_1"))
                    .step("跳到最后", ctx -> CompanionTalkViews.talk().skip()).ticks(6)
                    .screenshot("princess_talk_forest")
                    .step("返回 → 详细数据", ctx -> {
                        CompanionTalkViews.talk().skip();
                        CompanionTalkViews.talk().skip();
                        CompanionTalkViews.talk().choose("back");
                        CompanionTalkViews.talk().choose("stats");
                    })
                    .waitUntil("角色面板打开", ctx -> CompanionTalkViews.panel() != null)
                    .ticks(20)
                    .check("面板上的数：生命 38（自己 32，继承主人 20 的三成）、法术强度 1（自己 0.7，继承三成）", ctx -> {
                        var sheet = CompanionTalkViews.panel().parent().sheet();
                        return Math.abs(sheet.stat("health").value() - 38) < .01 && Math.abs(sheet.stat("spell_power").value() - 1) < .01;
                    })
                    .screenshot("princess_panel_stats")
                    .step("契印 · 能力", ctx -> CompanionTalkViews.panel().tab(CompanionPanelScreen.Tab.SKILLS)).ticks(4)
                    .screenshot("princess_panel_skills")
                    .step("往事", ctx -> CompanionTalkViews.panel().tab(CompanionPanelScreen.Tab.MEMORIES)).ticks(4)
                    .screenshot("princess_panel_memories")
                    .step("关掉", ctx -> Minecraft.getInstance().setScreen(null)).ticks(4)
                    .step("切回英文", ctx -> language("en_us")).waitUntil("资源重载完", ctx -> reload.isDone());
        }
    }
}
