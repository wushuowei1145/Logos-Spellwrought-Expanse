package dev.logos.dimension.gametest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.foxfire.Foxfire;
import dev.logos.dimension.foxfire.FoxfireAttachments;
import dev.logos.dimension.foxfire.FoxfireCommands;
import dev.logos.dimension.foxfire.FoxfireFlame;
import dev.logos.dimension.foxfire.FoxfireRules;
import dev.logos.dimension.foxfire.FoxfireWraps;
import dev.logos.dimension.npc.NpcEntities;
import dev.logos.lang.Compiler;
import dev.logos.lang.Element;
import dev.logos.lang.LogosError;
import dev.logos.lang.Spell;
import dev.logos.runtime.Casting;
import dev.logos.runtime.Effects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 狐火（docs/FOXFIRE.md）：就是奥秘伤害，只是伤害类型、命中特效和附带效果不同；同族不怕；藏着的词只有调试和附属自己用得了。
 * 只跑这些：{@code -PdimensionTests=logos_dimension:foxfire_*}。
 */
public final class FoxfireGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS = DeferredRegister.create(Registries.TEST_FUNCTION, LogosDimension.ID);

    public static void register(IEventBus bus) {
        Map<String, Consumer<GameTestHelper>> tests = new LinkedHashMap<>();
        tests.put("foxfire_is_arcane_with_its_own_type", FoxfireGameTests::isArcaneWithItsOwnType);
        tests.put("foxfire_kin_take_a_quarter", FoxfireGameTests::kinTakeAQuarter);
        tests.put("foxfire_burns_then_goes_out", FoxfireGameTests::burnsThenGoesOut);
        tests.put("foxfire_word_is_hidden_from_authoring", FoxfireGameTests::wordIsHiddenFromAuthoring);
        tests.put("foxfire_spell_damage_burns_as_foxfire", FoxfireGameTests::spellDamageBurnsAsFoxfire);
        tests.put("foxfire_plain_wrap_hits_as_foxfire", FoxfireGameTests::plainWrapHitsAsFoxfire);
        tests.put("foxfire_bewitch_drops_the_target", FoxfireGameTests::bewitchDropsTheTarget);
        tests.put("foxfire_word_refused_in_book_page", FoxfireGameTests::wordRefusedInBookPage);
        tests.put("foxfire_vein_is_marked_and_burns", FoxfireGameTests::veinIsMarkedAndBurns);
        tests.put("foxfire_word_refused_by_sage", FoxfireGameTests::wordRefusedBySage);
        tests.forEach((name, test) -> FUNCTIONS.register(name, () -> test));
        FUNCTIONS.register(bus);
        bus.addListener((RegisterGameTestsEvent event) -> {
            // 每个测试单独一批：狐火横扫能扫出十几格，同一批里会扫到隔壁测试的生物
            for (var name : tests.keySet()) {
                var env = event.registerEnvironment(LogosDimension.id(name), new TestEnvironmentDefinition.AllOf());
                event.registerTest(LogosDimension.id(name), new FunctionGameTestInstance(
                        ResourceKey.create(Registries.TEST_FUNCTION, LogosDimension.id(name)), new TestData<>(env,
                        Identifier.withDefaultNamespace("empty"), 200, 0, true, Rotation.NONE, false, 1, 1, false, 8)));
            }
        });
    }

    private static Husk attacker(GameTestHelper h) {
        floor(h);
        return h.spawnWithNoFreeWill(EntityType.HUSK, new BlockPos(0, 1, 0));
    }

    /** 测试场地底下铺一层石头：游脉要贴着地面爬，生物也别往下掉。 */
    private static void floor(GameTestHelper h) {
        for (int x = -2; x <= 6; x++)
            for (int z = -2; z <= 7; z++) h.setBlock(new BlockPos(x, 0, z), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
    }

    private static IronGolem golem(GameTestHelper h, int x) {
        return h.spawnWithNoFreeWill(EntityType.IRON_GOLEM, new BlockPos(x, 1, 3));
    }

    private static float lost(LivingEntity e) {
        return e.getMaxHealth() - e.getHealth();
    }

    /** 同一下：狐火和奥秘掉的血一样（都不看护甲），只是伤害类型不同；狐火打中后缠身。 */
    private static void isArcaneWithItsOwnType(GameTestHelper h) {
        var husk = attacker(h);
        var a = golem(h, 1);
        var b = golem(h, 4);
        Foxfire.deal(husk, a, 4, FoxfireFlame.KITSUNE);
        Effects.damage(husk, b, 4, Element.ARCANE);
        h.assertTrue(a.getLastDamageSource() != null && a.getLastDamageSource().is(Foxfire.DAMAGE), "狐火的伤害类型不是 logos_dimension:foxfire：" + a.getLastDamageSource());
        h.assertTrue(b.getLastDamageSource() != null && b.getLastDamageSource().is(DamageTypes.INDIRECT_MAGIC), "普通奥秘不该变成狐火：" + b.getLastDamageSource());
        h.assertTrue(Math.abs(lost(a) - lost(b)) < 1e-4 && lost(a) > 3.9f, "狐火和奥秘掉的血不一样：" + lost(a) + " / " + lost(b));
        h.assertTrue(FoxfireRules.burning(a) == FoxfireFlame.KITSUNE, "狐火打中后没有缠身");
        h.assertTrue(FoxfireRules.burning(b) == null, "普通奥秘也缠身了");
        h.succeed();
    }

    /** 同族（神狐）受到的狐火只剩四分之一，再按神狐对魔法的四成减免；也不会缠身。 */
    private static void kinTakeAQuarter(GameTestHelper h) {
        var husk = attacker(h);
        var fox = NpcEntities.DIVINE_FOX.get().create(h.getLevel(), net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        h.assertTrue(fox != null, "生不出神狐");
        Vec3 at = h.absoluteVec(new Vec3(2.5, 1, 3.5));
        fox.snapTo(at.x, at.y, at.z, 0, 0);
        fox.setNoAi(true);
        h.getLevel().addFreshEntity(fox);
        float before = fox.getHealth();
        Foxfire.deal(husk, fox, 8, FoxfireFlame.KITSUNE);
        float taken = before - fox.getHealth();
        h.assertTrue(Math.abs(taken - 8 * FoxfireRules.KIN_TAKEN * .6f) < 1e-3, "神狐受到的狐火不是 8 × 1/4 × 0.6：" + taken);
        h.assertTrue(FoxfireRules.burning(fox) == null, "同族不该狐火缠身");
        fox.discard();
        h.succeed();
    }

    /** 缠身三秒，每秒一点狐火；烧完就灭。 */
    private static void burnsThenGoesOut(GameTestHelper h) {
        var husk = attacker(h);
        var golem = golem(h, 2);
        Foxfire.deal(husk, golem, 4, FoxfireFlame.SHIRAUME);
        float afterHit = golem.getHealth();
        h.assertTrue(FoxfireRules.burning(golem) == FoxfireFlame.SHIRAUME, "没有缠上淡粉的狐火");
        h.runAfterDelay(FoxfireRules.BURN_TICKS + 5, () -> {
            float burned = afterHit - golem.getHealth();
            h.assertTrue(Math.abs(burned - 3 * FoxfireRules.BURN_DAMAGE) < 1e-3, "三秒缠身应当烧掉 3 点，实际 " + burned);
            h.assertTrue(FoxfireRules.burning(golem) == null && !golem.hasData(FoxfireAttachments.BURN), "缠身烧完没有灭");
            h.assertTrue(golem.getLastDamageSource() != null && golem.getLastDamageSource().is(Foxfire.DAMAGE), "缠身烧的不是狐火");
            h.succeed();
        });
    }

    /** {@code foxfire} 在调试和附属自己的术式里能用；玩家自己写（编纂台、书、发射器、书灵）时和写错的词一样，编译缓存里有也不行。 */
    private static void wordIsHiddenFromAuthoring(GameTestHelper h) {
        String src = "(spell :name \"狐火测试\" (damage caster 1 foxfire))";
        Spell spell = Casting.compile(src);
        h.assertTrue(Foxfire.is(spell), "调试编译出来的不是狐火术式");
        h.assertTrue(!Foxfire.is(Casting.compile("(spell :name \"奥秘测试\" (damage caster 1 arcane))")), "奥秘术式被当成了狐火");
        boolean refused = false;
        try {
            Foxfire.authoring(src, () -> Casting.compile(src));
        } catch (LogosError expected) {
            refused = true;
            h.assertTrue(expected.getMessage().contains("foxfire"), "报错没有指出是哪个词：" + expected.getMessage());
        }
        h.assertTrue(refused, "编译缓存里有狐火术式时，玩家照着写也通过了");
        refused = false;
        try {
            Foxfire.authoring(() -> Compiler.compile("(spell :name \"狐火测试二\" (damage caster 2 foxfire))"));
        } catch (LogosError expected) {
            refused = true;
        }
        h.assertTrue(refused, "玩家自己写的 foxfire 通过了编译");
        for (String page : FoxfireCommands.PAGES.values()) h.assertTrue(Foxfire.is(Casting.compile(page)), "调试页编译出来不是狐火术式");
        // 编纂台打开一张狐火术式页：画出来的图里是奥秘，存回去就是普通的奥秘术式
        var drawn = dev.logos.graph.GraphCompiler.compile(dev.logos.graph.GraphBuilder.build(spell));
        h.assertTrue(drawn.spell() != null && !Foxfire.is(drawn.spell()) && drawn.source() != null && !drawn.source().contains(Foxfire.WORD),
                "编纂台里打开狐火术式页再存回去，还是狐火：" + drawn.source() + " / " + drawn.error());
        h.succeed();
    }

    /** 狐火术式（射线打中）：奥秘伤害换成狐火，施法者不是白梅所以是幽蓝。 */
    private static void spellDamageBurnsAsFoxfire(GameTestHelper h) {
        var husk = attacker(h);
        var golem = golem(h, 0);
        Vec3 eye = husk.getEyePosition(), aim = golem.getBoundingBox().getCenter().subtract(eye);
        husk.setYRot((float) Math.toDegrees(Math.atan2(-aim.x, aim.z)));
        husk.setXRot((float) -Math.toDegrees(Math.atan2(aim.y, Math.hypot(aim.x, aim.z))));
        husk.setYHeadRot(husk.getYRot());
        Spell spell = Casting.compile("(spell :name \"狐火射线\" :mana 20 (ray :range 12 (on-hit (damage target 5 foxfire))))");
        Casting.castAs(husk, spell, 20, 0, golem, null);
        h.runAfterDelay(2, () -> {
            h.assertTrue(golem.getLastDamageSource() != null && golem.getLastDamageSource().is(Foxfire.DAMAGE), "狐火术式打出的不是狐火：" + golem.getLastDamageSource());
            h.assertTrue(FoxfireRules.burning(golem) == FoxfireFlame.KITSUNE, "狐火术式打中后没有缠上幽蓝的狐火");
            h.succeed();
        });
    }

    /** 狐火横扫（调试页）：Logos 的游脉被认出是狐火术式的（客户端据此换颜色），扫到的靶子挨的是狐火。 */
    private static void veinIsMarkedAndBurns(GameTestHelper h) {
        var husk = attacker(h);
        var golem = golem(h, 0);
        Vec3 eye = husk.getEyePosition(), aim = golem.getBoundingBox().getCenter().subtract(eye);
        husk.setYRot((float) Math.toDegrees(Math.atan2(-aim.x, aim.z)));
        husk.setYHeadRot(husk.getYRot());
        int before = dev.logos.dimension.foxfire.FoxfireCue.veinMarks();
        var inst = Casting.castAs(husk, Casting.compile(FoxfireCommands.PAGES.get("sweep")), 40, 0, golem, null);
        h.assertTrue(inst != null && !inst.dead, "狐火横扫施放失败");
        h.runAfterDelay(4, () -> h.assertTrue(dev.logos.dimension.foxfire.FoxfireCue.veinMarks() > before,
                "狐火术式的游脉没有被认出来：术式 dead=" + inst.dead + " 形体数=" + inst.objects + " 魔力=" + inst.mana + " 狐火术式=" + Foxfire.is(inst.spell)));
        h.runAfterDelay(30, () -> {
            h.assertTrue(golem.getLastDamageSource() != null && golem.getLastDamageSource().is(Foxfire.DAMAGE), "游脉扫到的不是狐火：" + golem.getLastDamageSource());
            h.succeed();
        });
    }

    /** 附属给的狐火普通缠绕（以后少女白梅给玩家缠的）：每下附带的伤害算狐火，颜色跟着给的人。 */
    private static void plainWrapHitsAsFoxfire(GameTestHelper h) {
        var husk = attacker(h);
        var golem = golem(h, 2);
        FoxfireWraps.wrap(husk, FoxfireFlame.SHIRAUME, 3, 10);
        h.assertTrue(FoxfireWraps.plain(husk) == FoxfireFlame.SHIRAUME && FoxfireWraps.flame(husk) == FoxfireFlame.SHIRAUME, "没有记下淡粉的狐火缠绕");
        Effects.followUp(husk, golem, 3, Element.ARCANE);
        h.assertTrue(golem.getLastDamageSource() != null && golem.getLastDamageSource().is(Foxfire.DAMAGE), "缠绕附带的不是狐火：" + golem.getLastDamageSource());
        h.assertTrue(FoxfireRules.burning(golem) == FoxfireFlame.SHIRAUME, "缠绕附带的狐火不是淡粉");
        FoxfireWraps.wrap(husk, FoxfireFlame.SHIRAUME, 0, 0);
        h.assertTrue(FoxfireWraps.flame(husk) == null, "解开以后还算狐火缠绕");
        h.succeed();
    }

    /** 书与笔誊写载页（真的走 Logos 的载页右键）：写 foxfire 誊不上，写 arcane 照常。 */
    @SuppressWarnings("removal")
    private static void wordRefusedInBookPage(GameTestHelper h) {
        var player = h.makeMockServerPlayerInLevel();
        try {
            h.assertTrue(!inscribe(h, player, "(spell :name \"书上的狐火\" (damage caster 1 foxfire))"), "书上写的 foxfire 誊上了载页");
            h.assertTrue(inscribe(h, player, "(spell :name \"书上的奥秘\" (damage caster 1 arcane))"), "书上写的 arcane 誊不上（测试本身有问题）");
        } finally {
            h.getLevel().getServer().getPlayerList().remove(player);
        }
        h.succeed();
    }

    private static boolean inscribe(GameTestHelper h, net.minecraft.server.level.ServerPlayer player, String text) {
        var book = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WRITABLE_BOOK);
        book.set(net.minecraft.core.component.DataComponents.WRITABLE_BOOK_CONTENT,
                new net.minecraft.world.item.component.WritableBookContent(java.util.List.of(net.minecraft.server.network.Filterable.passThrough(text))));
        var page = new net.minecraft.world.item.ItemStack(dev.logos.item.PageTier.BASIC.blank());
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, page);
        player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, book);
        page.getItem().use(h.getLevel(), player, net.minecraft.world.InteractionHand.MAIN_HAND);
        boolean written = false;
        for (var stack : player.getInventory()) written |= dev.logos.item.SpellPages.isInscribed(stack);
        player.getInventory().clearContent();
        return written;
    }

    /** 书灵替玩家写的术式（它的检查一步）：foxfire 和写错的词一样。 */
    private static void wordRefusedBySage(GameTestHelper h) {
        try {
            var check = Class.forName("dev.logos.sage.SageAgent").getDeclaredMethod("check", String.class, java.util.Set.class, int.class);
            check.setAccessible(true);
            java.util.Set<String> runes = new java.util.HashSet<>();
            dev.logos.lang.Runes.all().forEach(r -> runes.add(r.name));
            Object refused = check.invoke(null, "(spell :name \"书灵的狐火\" (damage caster 1 foxfire))", runes, 1000);
            Object accepted = check.invoke(null, "(spell :name \"书灵的奥秘\" (damage caster 1 arcane))", runes, 1000);
            var spell = refused.getClass().getDeclaredMethod("spell");
            spell.setAccessible(true);
            h.assertTrue(spell.invoke(refused) == null, "书灵写的 foxfire 通过了检查");
            h.assertTrue(spell.invoke(accepted) != null, "书灵写的 arcane 没通过检查（测试本身有问题）");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("当前 Logos 没有 SageAgent.check", e);
        }
        h.succeed();
    }

    /** 惑：怪物被打中后丢掉目标，一阵子里找不了新目标；过后照常。 */
    private static void bewitchDropsTheTarget(GameTestHelper h) {
        var husk = attacker(h);
        Mob zombie = h.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(2, 1, 3));
        var golem = golem(h, 4);
        zombie.setTarget(golem);
        FoxfireRules.alwaysBewitch = true;
        try {
            Foxfire.deal(husk, zombie, 2, FoxfireFlame.KITSUNE);
        } finally {
            FoxfireRules.alwaysBewitch = false;
        }
        h.assertTrue(zombie.getTarget() == null && FoxfireRules.bewitched(zombie), "被惑的怪物没有丢掉目标");
        zombie.setTarget(golem);
        h.assertTrue(zombie.getTarget() == null, "被惑时又找到了目标");
        h.runAfterDelay(FoxfireRules.BEWITCH_TICKS + 2, () -> {
            zombie.setTarget(golem);
            h.assertTrue(zombie.getTarget() == golem, "惑过了还找不了目标");
            h.succeed();
        });
    }
}
