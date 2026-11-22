package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.LogosMod;
import dev.logos.dimension.companion.Companions;
import dev.logos.dimension.companion.KohakuCompanion;
import dev.logos.item.PageContainer;
import dev.logos.item.PageTier;
import dev.logos.item.SpellPages;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/**
 * 实际客户端检查琥珀的术式（docs/KOHAKU_COMPANION.md 第 5、7 节，第二步），也在 {@code kohaku_companion} 一组：
 * <ul>
 * <li>{@code kohaku_spells_crowd}：八枚爪之契，三只僵尸扎堆冲主人来——她先罩护膜、开场随机放一个支援（伞或附魔速成），
 *     把强敌封进琥珀，再画炼成阵·星辰花；主人没挨她的打。</li>
 * <li>{@code kohaku_spells_care}：四枚，主人只剩四成血、身边没有敌人：她有余力，在主人脚下铺温室的午后，主人的血回上来。</li>
 * <li>{@code kohaku_your_pages}：包袱里放一根回响长杖，挂着一页你写的单体攻击和一页带附身的；她只认得前一页，打僵尸时先用你的那页。</li>
 * <li>{@code kohaku_cat_eye}：一枚，半夜在外面：她递给主人一瓶猫眼药剂，主人有了夜视。</li>
 * </ul>
 */
public abstract class KohakuSpellViews extends KohakuViews {
    /** 从侧后方看：主人、她和冲过来的那一堆都在画面里。 */
    static final Vec3 SIDE_EYE = new Vec3(-7.5, FLOOR + 3.2, -1.5), SIDE_LOOK = new Vec3(.5, FLOOR + 1, 6.5);

    /** 立契、叫出来、盖 {@code seals} 枚爪之契。 */
    static KohakuCompanion sealed(ServerPlayer p, int seals) {
        KohakuCompanion c = summonKohaku(p, SPOT);
        for (int i = 0; i < seals; i++) Companions.addSeal(p, Companions.KOHAKU_DEF);
        return c;
    }

    // ---------------------------------------------------------------- 扎堆、强敌、开场的支援

    @LDLRegisterClient(name = "kohaku_spells_crowd", group = "kohaku_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Crowd extends KohakuSpellViews {
        private KohakuCompanion c;
        private Mob[] zombies;
        private int amber, starbloom, support;
        private float health;

        @Override
        public void define(ScenarioBuilder s) {
            kohakuStage(s, GameType.SURVIVAL).runCommand("difficulty normal")
                    .server("站好、召唤、八枚爪之契", ctx -> {
                        ServerPlayer p = ctx.player();
                        aim(p, SIDE_EYE, SIDE_LOOK);
                        c = sealed(p, 8);
                    })
                    .step("对准", ctx -> aimClient(SIDE_LOOK))
                    .waitUntilServer("入场播完", ctx -> arrived(c))
                    .waitUntilServer("换上天穹长杖", ctx -> c.getMainHandItem().is(LogosMod.FIRMAMENT_STAFF.get()))
                    .server("魔力加满、杖里充满；三只僵尸扎堆冲主人来", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        ServerPlayer p = ctx.player();
                        c.topUp();
                        health = p.getHealth();
                        amber = KohakuCompanion.casts("time_amber");
                        starbloom = KohakuCompanion.casts("starbloom");
                        support = KohakuCompanion.casts("misty_umbrella") + KohakuCompanion.casts("quick_enchant");
                        zombies = new Mob[]{
                                PrincessViews.helmeted(CompanionLifeViews.spawn(level, EntityType.ZOMBIE, new Vec3(-.5, FLOOR + 1, 12.5), 180)),
                                PrincessViews.helmeted(CompanionLifeViews.spawn(level, EntityType.ZOMBIE, new Vec3(1.5, FLOOR + 1, 12.5), 180)),
                                PrincessViews.helmeted(CompanionLifeViews.spawn(level, EntityType.ZOMBIE, new Vec3(.5, FLOOR + 1, 14), 180))};
                        for (Mob z : zombies) z.setTarget(p);
                    })
                    .waitUntilServer("开场随机放了一个支援（缥缈之伞或附魔速成）",
                            ctx -> KohakuCompanion.casts("misty_umbrella") + KohakuCompanion.casts("quick_enchant") > support)
                    .ticks(6).screenshot("kohaku_spells_support")
                    .waitUntilServer("强敌（一下子围上来三个）先封进琥珀", ctx -> KohakuCompanion.casts("time_amber") > amber)
                    .ticks(4).screenshot("kohaku_spells_amber")
                    .waitUntilServer("在扎堆的地方画炼成阵·星辰花", ctx -> KohakuCompanion.casts("starbloom") > starbloom)
                    .ticks(12).screenshot("kohaku_spells_starbloom")
                    .waitUntilServer("三只都倒下", ctx -> {
                        for (Mob z : zombies) if (z.isAlive()) return false;
                        return true;
                    })
                    .checkServer("主人一直没挨她的打", ctx -> ctx.player().getHealth() >= health);
        }
    }

    // ---------------------------------------------------------------- 有余力：照顾伤着的

    @LDLRegisterClient(name = "kohaku_spells_care", group = "kohaku_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Care extends KohakuSpellViews {
        private KohakuCompanion c;
        private int greenhouse;
        private float health;

        @Override
        public void define(ScenarioBuilder s) {
            // 简单难度：和平难度下主人会自己回血，几秒就不算伤员了
            kohakuStage(s, GameType.SURVIVAL).runCommand("difficulty easy")
                    .server("站好、召唤、四枚爪之契", ctx -> {
                        ServerPlayer p = ctx.player();
                        p.removeAllEffects();
                        aim(p, EYE, LOOK);
                        c = sealed(p, 4);
                    })
                    .step("对准", ctx -> aimClient(LOOK))
                    .waitUntilServer("入场播完", ctx -> arrived(c))
                    .server("主人只剩四成血（饿着，不会自己回血）", ctx -> {
                        ServerPlayer p = ctx.player();
                        greenhouse = KohakuCompanion.casts("greenhouse");
                        // 刚盖了四枚，上限涨了、身上的还是按零枚加满的：先加满，她才有余力
                        c.topUp();
                        p.getFoodData().setFoodLevel(10);
                        p.getFoodData().setSaturation(0);
                        p.setHealth(p.getMaxHealth() * .4f);
                        health = p.getHealth();
                    })
                    .waitUntilServer("她有余力：在主人脚下铺温室的午后", ctx -> KohakuCompanion.casts("greenhouse") > greenhouse)
                    .ticks(30).screenshot("kohaku_spells_greenhouse")
                    .waitUntilServer("主人的血回上来", ctx -> ctx.player().getHealth() > health + 3)
                    .server("喂饱主人", ctx -> {
                        ctx.player().getFoodData().setFoodLevel(20);
                        ctx.player().getFoodData().setSaturation(5);
                    });
        }
    }

    // ---------------------------------------------------------------- 你给的术式

    @LDLRegisterClient(name = "kohaku_your_pages", group = "kohaku_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class YourPages extends KohakuSpellViews {
        /** 你写的一页单体攻击（火焰小弹），和一页带附身的（她用不了）。 */
        static final String STRIKE = "(spell :name \"小火弹\" :mana 60 :trigger cast (projectile :speed 2 (on-hit (if (is target (kind enemies)) (damage target 4 fire)))))";
        static final String POSSESS = "(spell :name \"附身\" :mana 60 :trigger cast (possess (foe) 5))";
        private KohakuCompanion c;
        private Mob zombie;
        private int yours, drops;

        @Override
        public void define(ScenarioBuilder s) {
            kohakuStage(s, GameType.SURVIVAL).runCommand("difficulty normal")
                    .server("站好、召唤；包袱里放一根回响长杖，挂着你写的两页", ctx -> {
                        ServerPlayer p = ctx.player();
                        aim(p, SIDE_EYE, SIDE_LOOK);
                        c = summonKohaku(p, SPOT);
                        ItemStack staff = new ItemStack(LogosMod.ECHO_STAFF.get());
                        PageContainer.set(staff, 0, SpellPages.inscribed(PageTier.ECHO, STRIKE, null));
                        PageContainer.set(staff, 1, SpellPages.inscribed(PageTier.ECHO, POSSESS, null));
                        Companions.pouch(p, Companions.KOHAKU_DEF).setItem(0, staff);
                    })
                    .step("对准", ctx -> aimClient(SIDE_LOOK))
                    .waitUntilServer("入场播完", ctx -> arrived(c))
                    .waitUntilServer("她拿上你给的回响长杖", ctx -> c.getMainHandItem().is(LogosMod.ECHO_STAFF.get()))
                    .checkServer("杖上两页，她只认得那页单体攻击（带附身的不算）", ctx -> c.yourPageCount() == 1)
                    .server("一只僵尸冲主人来", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        c.topUp();
                        yours = KohakuCompanion.yoursCasts();
                        drops = KohakuCompanion.casts("one_drop");
                        zombie = PrincessViews.helmeted(CompanionLifeViews.spawn(level, EntityType.ZOMBIE, new Vec3(.5, FLOOR + 1, 12.5), 180));
                        zombie.setTarget(ctx.player());
                    })
                    .waitUntilServer("她放了你写的那页", ctx -> KohakuCompanion.yoursCasts() > yours)
                    .checkServer("同一种意图先用你的：那之前没放她自己的一滴就够", ctx -> KohakuCompanion.casts("one_drop") == drops)
                    .ticks(4).screenshot("kohaku_your_pages")
                    .waitUntilServer("僵尸倒下", ctx -> !zombie.isAlive());
        }
    }

    // ---------------------------------------------------------------- 猫眼药剂

    @LDLRegisterClient(name = "kohaku_cat_eye", group = "kohaku_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class CatEye extends KohakuSpellViews {
        private KohakuCompanion c;
        private int eyes;

        @Override
        public void define(ScenarioBuilder s) {
            kohakuStage(s, GameType.SURVIVAL).runCommand("difficulty peaceful")
                    .server("站好、召唤、一枚爪之契", ctx -> {
                        ServerPlayer p = ctx.player();
                        p.removeAllEffects();
                        aim(p, EYE, LOOK);
                        c = sealed(p, 1);
                    })
                    .step("对准", ctx -> aimClient(LOOK))
                    .waitUntilServer("入场播完", ctx -> arrived(c))
                    .server("记下", ctx -> eyes = KohakuCompanion.casts("cat_eye"))
                    .runCommand("time set midnight")
                    .waitUntilServer("半夜外面黑：她递给主人一瓶猫眼药剂", ctx -> KohakuCompanion.casts("cat_eye") > eyes)
                    .waitUntilServer("主人有了夜视", ctx -> ctx.player().hasEffect(MobEffects.NIGHT_VISION))
                    .ticks(4).screenshot("kohaku_cat_eye")
                    .runCommand("time set noon");
        }
    }
}
