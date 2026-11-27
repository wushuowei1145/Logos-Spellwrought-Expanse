package dev.logos.dimension.gametest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.dragonfire.Dragonfire;
import dev.logos.dimension.dragonfire.DragonfireCommands;
import dev.logos.dimension.dragonfire.DragonfireRules;
import dev.logos.dimension.foxfire.Foxfire;
import dev.logos.dimension.foxfire.FoxfireAttachments;
import dev.logos.dimension.foxfire.FoxfireFlame;
import dev.logos.dimension.foxfire.FoxfireRules;
import dev.logos.dimension.foxfire.FoxfireWraps;
import dev.logos.dimension.magic.HiddenWord;
import dev.logos.dimension.npc.DivineFox;
import dev.logos.dimension.npc.NpcEntities;
import dev.logos.lang.Compiler;
import dev.logos.lang.Element;
import dev.logos.lang.LogosError;
import dev.logos.lang.Spell;
import dev.logos.runtime.Casting;
import dev.logos.runtime.Effects;
import dev.logos.runtime.SpellModifiers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 龙炎（docs/DRAGONFIRE.md）：奥秘的子类型，没有原版火的弱点、不吃女巫的魔法减免，但 Logos 的奥秘抗性照算；打中掉魔力、
 * 很难灭的缠身；龙族只受四分之一；藏着的词能学会。只跑这些：{@code -PdimensionTests=logos_dimension:dragonfire_*}。
 */
public final class DragonfireGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS = DeferredRegister.create(Registries.TEST_FUNCTION, LogosDimension.ID);

    public static void register(IEventBus bus) {
        Map<String, Consumer<GameTestHelper>> tests = new LinkedHashMap<>();
        tests.put("dragonfire_is_arcane_with_its_own_type", DragonfireGameTests::isArcaneWithItsOwnType);
        tests.put("dragonfire_ignores_fire_defences", DragonfireGameTests::ignoresFireDefences);
        tests.put("dragonfire_ignores_witch_resistance", DragonfireGameTests::ignoresWitchResistance);
        tests.put("dragonfire_arcane_resistance_applies", DragonfireGameTests::arcaneResistanceApplies);
        tests.put("dragonfire_drains_mana", DragonfireGameTests::drainsMana);
        tests.put("dragonfire_burn_stacks", DragonfireGameTests::burnStacks);
        tests.put("dragonfire_burns_then_goes_out", DragonfireGameTests::burnsThenGoesOut);
        tests.put("dragonfire_kin_take_a_quarter", DragonfireGameTests::kinTakeAQuarter);
        tests.put("dragonfire_word_is_hidden_until_taught", DragonfireGameTests::wordIsHiddenUntilTaught);
        tests.put("dragonfire_word_in_book_page_and_sage", DragonfireGameTests::wordInBookPageAndSage);
        tests.put("dragonfire_spell_and_wrap_hit_as_dragonfire", DragonfireGameTests::spellAndWrapHitAsDragonfire);
        tests.forEach((name, test) -> FUNCTIONS.register(name, () -> test));
        FUNCTIONS.register(bus);
        bus.addListener((RegisterGameTestsEvent event) -> {
            // 每个测试单独一批：末影龙很大，游脉扫得远
            for (var name : tests.keySet()) {
                var env = event.registerEnvironment(LogosDimension.id(name), new TestEnvironmentDefinition.AllOf());
                int maxTicks = name.equals("dragonfire_burns_then_goes_out") ? 800 : 200;
                event.registerTest(LogosDimension.id(name), new FunctionGameTestInstance(
                        ResourceKey.create(Registries.TEST_FUNCTION, LogosDimension.id(name)), new TestData<>(env,
                        Identifier.withDefaultNamespace("empty"), maxTicks, 0, true, Rotation.NONE, false, 1, 1, false, 8)));
            }
        });
    }

    private static Husk attacker(GameTestHelper h) {
        floor(h);
        return h.spawnWithNoFreeWill(EntityType.HUSK, new BlockPos(0, 1, 0));
    }

    private static void floor(GameTestHelper h) {
        for (int x = -2; x <= 6; x++)
            for (int z = -2; z <= 7; z++) h.setBlock(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
    }

    private static IronGolem golem(GameTestHelper h, int x) {
        return h.spawnWithNoFreeWill(EntityType.IRON_GOLEM, new BlockPos(x, 1, 3));
    }

    private static DivineFox fox(GameTestHelper h, double x) {
        var fox = NpcEntities.DIVINE_FOX.get().create(h.getLevel(), EntitySpawnReason.COMMAND);
        h.assertTrue(fox != null, "生不出神狐");
        Vec3 at = h.absoluteVec(new Vec3(x, 1, 3.5));
        fox.snapTo(at.x, at.y, at.z, 0, 0);
        fox.setNoAi(true);
        h.getLevel().addFreshEntity(fox);
        return fox;
    }

    private static float lost(LivingEntity e) {
        return e.getMaxHealth() - e.getHealth();
    }

    private static boolean near(float a, double b) {
        return Math.abs(a - b) < 1e-3;
    }

    /** 同一下：龙炎和奥秘掉的血一样，只是伤害类型不同；龙炎打中后缠身，奥秘不会。 */
    private static void isArcaneWithItsOwnType(GameTestHelper h) {
        var husk = attacker(h);
        var a = golem(h, 1);
        var b = golem(h, 4);
        Dragonfire.deal(husk, a, 4);
        Effects.damage(husk, b, 4, Element.ARCANE);
        h.assertTrue(a.getLastDamageSource() != null && a.getLastDamageSource().is(Dragonfire.DAMAGE), "龙炎的伤害类型不是 logos_dimension:dragonfire：" + a.getLastDamageSource());
        h.assertTrue(b.getLastDamageSource() != null && b.getLastDamageSource().is(DamageTypes.INDIRECT_MAGIC), "普通奥秘不该变成龙炎：" + b.getLastDamageSource());
        h.assertTrue(near(lost(a), lost(b)) && lost(a) > 3.9f, "龙炎和奥秘掉的血不一样：" + lost(a) + " / " + lost(b));
        h.assertTrue(Dragonfire.burning(a), "龙炎打中后没有缠身");
        h.assertTrue(FoxfireRules.burning(b) == null, "普通奥秘也缠身了");
        h.succeed();
    }

    /** 原版火的弱点都没有：天生不怕火的、抗火、护甲加保护 IV，龙炎照样全额；同样的奥秘被保护减掉一大截。 */
    private static void ignoresFireDefences(GameTestHelper h) {
        var husk = attacker(h);
        var protection = h.getLevel().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.PROTECTION);
        Mob[] armored = new Mob[2];
        for (int i = 0; i < 2; i++) {
            armored[i] = h.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(1 + i * 3, 1, 3));
            for (var slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
                ItemStack piece = new ItemStack(switch (slot) {
                    case HEAD -> Items.DIAMOND_HELMET;
                    case CHEST -> Items.DIAMOND_CHESTPLATE;
                    case LEGS -> Items.DIAMOND_LEGGINGS;
                    default -> Items.DIAMOND_BOOTS;
                });
                piece.enchant(protection, 4);
                armored[i].setItemSlot(slot, piece);
            }
            armored[i].addEffect(new MobEffectInstance(MobEffects.FIRE_RESISTANCE, 200));
        }
        Dragonfire.deal(husk, armored[0], 4);
        Effects.damage(husk, armored[1], 4, Element.ARCANE);
        h.assertTrue(near(lost(armored[0]), 4), "护甲、保护、抗火减了龙炎：掉了 " + lost(armored[0]));
        h.assertTrue(lost(armored[1]) < 3, "保护 IV 没减奥秘（测试本身有问题）：掉了 " + lost(armored[1]));
        var blaze = h.spawnWithNoFreeWill(EntityType.BLAZE, new BlockPos(2, 1, 6));
        Dragonfire.deal(husk, blaze, 4);
        h.assertTrue(near(lost(blaze), 4), "烈焰人不怕火，也不该不怕龙炎：掉了 " + lost(blaze));
        h.succeed();
    }

    /** 女巫对魔法只受一成半，神狐只受六成（都看 witch_resistant_to）；龙炎不在里面，全额。 */
    private static void ignoresWitchResistance(GameTestHelper h) {
        var husk = attacker(h);
        var a = h.spawnWithNoFreeWill(EntityType.WITCH, new BlockPos(1, 1, 3));
        var b = h.spawnWithNoFreeWill(EntityType.WITCH, new BlockPos(4, 1, 3));
        Dragonfire.deal(husk, a, 4);
        Effects.damage(husk, b, 4, Element.ARCANE);
        h.assertTrue(near(lost(a), 4), "女巫减了龙炎：掉了 " + lost(a));
        h.assertTrue(lost(b) < 1, "女巫没减奥秘（测试本身有问题）：掉了 " + lost(b));
        var fox = fox(h, 2.5);
        Dragonfire.deal(husk, fox, 4);
        h.assertTrue(near(lost(fox), 4), "神狐对魔法的减免也减了龙炎：掉了 " + lost(fox));
        fox.discard();
        h.succeed();
    }

    /** Logos 的奥秘抗性照算：一半奥秘抗性的靶子只受一半龙炎。 */
    private static void arcaneResistanceApplies(GameTestHelper h) {
        var husk = attacker(h);
        var golem = golem(h, 2);
        var resistance = golem.getAttribute(SpellModifiers.resistanceAttribute(Element.ARCANE));
        h.assertTrue(resistance != null, "铁傀儡没有奥秘抗性这个属性");
        resistance.addTransientModifier(new AttributeModifier(LogosDimension.id("test_arcane_resistance"), .5, AttributeModifier.Operation.ADD_VALUE));
        Dragonfire.deal(husk, golem, 4);
        h.assertTrue(near(lost(golem), 2), "一半奥秘抗性的靶子受到的龙炎不是一半：掉了 " + lost(golem));
        h.succeed();
    }

    /** 伤及灵魂：每打进去一点龙炎烧掉一点魔力；同样的奥秘不掉魔力。 */
    private static void drainsMana(GameTestHelper h) {
        var husk = attacker(h);
        var a = fox(h, 1.5);
        var b = fox(h, 4.5);
        float before = a.mana();
        Dragonfire.deal(husk, a, 4, false);
        Effects.damage(husk, b, 4, Element.ARCANE);
        h.assertTrue(near(before - a.mana(), 4 * DragonfireRules.MANA_PER_DAMAGE), "龙炎打进去 4 点，魔力应当少 4，实际少 " + (before - a.mana()));
        h.assertTrue(near(b.mana(), before), "普通奥秘也掉了魔力");
        h.assertTrue(!Dragonfire.burning(a), "说了不点缠身，还是缠身了");
        a.discard();
        b.discard();
        h.succeed();
    }

    /** 很难灭：每打中一下往上叠时间（最多叠到上限）；燃着龙炎时点不上狐火。 */
    private static void burnStacks(GameTestHelper h) {
        var husk = attacker(h);
        var golem = golem(h, 2);
        long now = h.getLevel().getGameTime();
        Dragonfire.deal(husk, golem, 1);
        golem.invulnerableTime = 0;
        Dragonfire.deal(husk, golem, 1);
        long until = golem.getData(FoxfireAttachments.BURN).until();
        h.assertTrue(until == now + 2L * DragonfireRules.BURN_TICKS, "打中两下应当烧 " + 2 * DragonfireRules.BURN_TICKS + " 刻，实际 " + (until - now));
        for (int i = 0; i < 5; i++) Dragonfire.burn(golem, husk, DragonfireRules.BURN_TICKS);
        until = golem.getData(FoxfireAttachments.BURN).until();
        h.assertTrue(until == now + DragonfireRules.MAX_BURN_TICKS, "叠满应当烧 " + DragonfireRules.MAX_BURN_TICKS + " 刻，实际 " + (until - now));
        FoxfireRules.burn(golem, FoxfireFlame.KITSUNE, husk, FoxfireRules.BURN_TICKS);
        h.assertTrue(Dragonfire.burning(golem), "狐火盖掉了龙炎缠身");
        h.succeed();
    }

    /** 缠身每秒烧一下龙炎，烧完就灭。同狐火的缠身测试，先等两只所在的区块进入实体走刻。 */
    private static void burnsThenGoesOut(GameTestHelper h) {
        floor(h);
        BlockPos huskAt = new BlockPos(0, 1, 0), golemAt = new BlockPos(2, 1, 3);
        var chunks = forceChunks(h, huskAt, golemAt);
        IronGolem[] golem = {null};
        float[] afterHit = {0};
        h.startSequence()
                .thenWaitUntil(entitiesTicking(h, huskAt, golemAt))
                .thenExecute(() -> {
                    var husk = h.spawnWithNoFreeWill(EntityType.HUSK, huskAt);
                    golem[0] = golem(h, golemAt.getX());
                    Dragonfire.deal(husk, golem[0], 4);
                    afterHit[0] = golem[0].getHealth();
                    h.assertTrue(Dragonfire.burning(golem[0]), "没有龙炎缠身");
                })
                .thenExecuteAfter(DragonfireRules.BURN_TICKS + 5, () -> {
                    float burned = afterHit[0] - golem[0].getHealth();
                    double want = DragonfireRules.BURN_TICKS / 20 * DragonfireRules.BURN_DAMAGE;
                    h.assertTrue(Math.abs(burned - want) < 1e-3, "缠身应当烧掉 " + want + " 点，实际 " + burned);
                    h.assertTrue(FoxfireRules.burning(golem[0]) == null && !golem[0].hasData(FoxfireAttachments.BURN), "缠身烧完没有灭");
                    h.assertTrue(golem[0].getLastDamageSource() != null && golem[0].getLastDamageSource().is(Dragonfire.DAMAGE), "缠身烧的不是龙炎");
                    for (ChunkPos c : chunks) h.getLevel().setChunkForced(c.x(), c.z(), false);
                })
                .thenSucceed();
    }

    private static List<ChunkPos> forceChunks(GameTestHelper h, BlockPos... at) {
        List<ChunkPos> added = new ArrayList<>();
        for (BlockPos p : at) {
            ChunkPos c = ChunkPos.containing(h.absolutePos(p));
            if (!added.contains(c) && h.getLevel().setChunkForced(c.x(), c.z(), true)) added.add(c);
        }
        return added;
    }

    private static Runnable entitiesTicking(GameTestHelper h, BlockPos... at) {
        return RealTimeWait.paced(() -> {
            for (BlockPos p : at) {
                ChunkPos c = ChunkPos.containing(h.absolutePos(p));
                h.assertTrue(h.getLevel().areEntitiesActuallyLoadedAndTicking(c), "区块 " + c + " 应当进入实体走刻");
            }
        });
    }

    /**
     * 龙族（末影龙）受到的龙炎只剩四分之一，也不缠身。末影龙只认玩家打的（或 always_hurts_ender_dragons），
     * 身体还要先折算一遍，所以拿同样的一下奥秘比：龙炎正好是它的四分之一。
     */
    @SuppressWarnings("removal")
    private static void kinTakeAQuarter(GameTestHelper h) {
        floor(h);
        var player = h.makeMockServerPlayerInLevel();
        try {
            var a = h.spawnWithNoFreeWill(EntityType.ENDER_DRAGON, new BlockPos(2, 4, 3));
            var b = h.spawnWithNoFreeWill(EntityType.ENDER_DRAGON, new BlockPos(2, 4, 3));
            Dragonfire.deal(player, a, 8);
            Effects.damage(player, b, 8, Element.ARCANE);
            h.assertTrue(lost(b) > 0, "奥秘没打疼末影龙（测试本身有问题）");
            h.assertTrue(Math.abs(lost(a) - lost(b) * DragonfireRules.KIN_TAKEN) < 1e-3, "末影龙受到的龙炎不是奥秘的四分之一：" + lost(a) + " / " + lost(b));
            h.assertTrue(!Dragonfire.burning(a), "龙族不该龙炎缠身");
            a.discard();
            b.discard();
        } finally {
            h.getLevel().getServer().getPlayerList().remove(player);
        }
        h.succeed();
    }

    /** {@code dragonfire} 调试能用；玩家没学会时写不了（编译缓存里有也不行），学会了照常；会狐火不等于会龙炎。 */
    private static void wordIsHiddenUntilTaught(GameTestHelper h) {
        String src = "(spell :name \"龙炎测试\" (damage caster 1 dragonfire))";
        Spell spell = Casting.compile(src);
        h.assertTrue(Dragonfire.is(spell) && !Foxfire.is(spell), "调试编译出来的不是龙炎术式");
        h.assertTrue(!Dragonfire.is(Casting.compile("(spell :name \"奥秘测试\" (damage caster 1 arcane))")), "奥秘术式被当成了龙炎");
        boolean refused = false;
        try {
            HiddenWord.authoring(w -> false, src, () -> Casting.compile(src));
        } catch (LogosError expected) {
            refused = true;
            h.assertTrue(expected.getMessage().contains("dragonfire"), "报错没有指出是哪个词：" + expected.getMessage());
        }
        h.assertTrue(refused, "编译缓存里有龙炎术式时，玩家照着写也通过了");
        refused = false;
        try {
            HiddenWord.authoring(w -> w == Foxfire.HIDDEN, () -> Compiler.compile("(spell :name \"龙炎测试二\" (damage caster 2 dragonfire))"));
        } catch (LogosError expected) {
            refused = true;
        }
        h.assertTrue(refused, "只会狐火的玩家写的 dragonfire 通过了编译");
        Spell learned = HiddenWord.authoring(w -> w == Dragonfire.HIDDEN, () -> Compiler.compile("(spell :name \"龙炎测试三\" (damage caster 3 dragonfire))"));
        h.assertTrue(Dragonfire.is(learned), "学会了龙炎之言，写出来的不是龙炎术式");
        refused = false;
        try {
            HiddenWord.authoring(w -> w == Dragonfire.HIDDEN, () -> Compiler.compile("(spell :name \"狐火测试四\" (damage caster 2 foxfire))"));
        } catch (LogosError expected) {
            refused = true;
        }
        h.assertTrue(refused, "只会龙炎的玩家写的 foxfire 通过了编译");
        for (String page : DragonfireCommands.PAGES.values()) h.assertTrue(Dragonfire.is(Casting.compile(page)), "调试页编译出来不是龙炎术式");
        h.succeed();
    }

    /** 书与笔（真的走载页右键）和书灵：没学会时拒绝 dragonfire，学会了（{@link Dragonfire#teach}）照常。 */
    @SuppressWarnings("removal")
    private static void wordInBookPageAndSage(GameTestHelper h) {
        var player = h.makeMockServerPlayerInLevel();
        try {
            h.assertTrue(!inscribe(h, player, "(spell :name \"书上的龙炎\" (damage caster 1 dragonfire))"), "没学会时书上写的 dragonfire 誊上了载页");
            h.assertTrue(Dragonfire.teach(player) && Dragonfire.knows(player), "教不会龙炎之言");
            h.assertTrue(inscribe(h, player, "(spell :name \"学会了的龙炎\" (damage caster 1 dragonfire))"), "学会了龙炎之言，书上写的 dragonfire 还是誊不上");
            Dragonfire.forget(player);
            h.assertTrue(!Dragonfire.knows(player), "忘不掉龙炎之言");
        } finally {
            h.getLevel().getServer().getPlayerList().remove(player);
        }
        try {
            var check = Class.forName("dev.logos.sage.SageAgent").getDeclaredMethod("check", String.class, java.util.Set.class, int.class);
            check.setAccessible(true);
            java.util.Set<String> runes = new java.util.HashSet<>(dev.logos.lang.Unlocks.all());
            Object refused = check.invoke(null, "(spell :name \"书灵的龙炎\" (damage caster 1 dragonfire))", runes, 1000);
            var spell = refused.getClass().getDeclaredMethod("spell");
            spell.setAccessible(true);
            h.assertTrue(spell.invoke(refused) == null, "书灵写的 dragonfire 通过了检查");
            runes.add(Dragonfire.UNLOCK);
            Object learned = check.invoke(null, "(spell :name \"书灵的龙炎二\" (damage caster 1 dragonfire))", runes, 1000);
            h.assertTrue(spell.invoke(learned) != null, "搭档学会了龙炎之言，书灵写的 dragonfire 还是没通过");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("当前 Logos 没有 SageAgent.check", e);
        }
        h.succeed();
    }

    private static boolean inscribe(GameTestHelper h, net.minecraft.server.level.ServerPlayer player, String text) {
        var book = new ItemStack(Items.WRITABLE_BOOK);
        book.set(net.minecraft.core.component.DataComponents.WRITABLE_BOOK_CONTENT,
                new net.minecraft.world.item.component.WritableBookContent(List.of(net.minecraft.server.network.Filterable.passThrough(text))));
        var page = new ItemStack(dev.logos.item.PageTier.BASIC.blank());
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, page);
        player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, book);
        page.getItem().use(h.getLevel(), player, net.minecraft.world.InteractionHand.MAIN_HAND);
        boolean written = false;
        for (var stack : player.getInventory()) written |= dev.logos.item.SpellPages.isInscribed(stack);
        player.getInventory().clearContent();
        return written;
    }

    /** 龙炎术式（射线打中）和附属给的龙炎普通缠绕：打出的都是龙炎，缠身。 */
    private static void spellAndWrapHitAsDragonfire(GameTestHelper h) {
        var husk = attacker(h);
        var golem = golem(h, 0);
        var other = golem(h, 4);
        Vec3 eye = husk.getEyePosition(), aim = golem.getBoundingBox().getCenter().subtract(eye);
        husk.setYRot((float) Math.toDegrees(Math.atan2(-aim.x, aim.z)));
        husk.setXRot((float) -Math.toDegrees(Math.atan2(aim.y, Math.hypot(aim.x, aim.z))));
        husk.setYHeadRot(husk.getYRot());
        Spell spell = Casting.compile("(spell :name \"龙炎射线\" :mana 20 (ray :range 12 (on-hit (damage target 5 dragonfire))))");
        h.assertTrue(Foxfire.flameOf(spell, husk) == FoxfireFlame.DRAGON, "龙炎术式的颜色不是龙炎");
        Casting.castAs(husk, spell, 20, 0, golem, null);
        Dragonfire.wrap(husk, 3, 10);
        h.assertTrue(FoxfireWraps.plain(husk) == FoxfireFlame.DRAGON, "没有记下龙炎缠绕");
        Effects.followUp(husk, other, 3, Element.ARCANE);
        h.assertTrue(other.getLastDamageSource() != null && other.getLastDamageSource().is(Dragonfire.DAMAGE), "缠绕附带的不是龙炎：" + other.getLastDamageSource());
        h.assertTrue(Dragonfire.burning(other), "缠绕附带的龙炎没有缠身");
        Dragonfire.wrap(husk, 0, 0);
        h.runAfterDelay(2, () -> {
            h.assertTrue(golem.getLastDamageSource() != null && golem.getLastDamageSource().is(Dragonfire.DAMAGE), "龙炎术式打出的不是龙炎：" + golem.getLastDamageSource());
            h.assertTrue(Dragonfire.burning(golem), "龙炎术式打中后没有缠身");
            h.succeed();
        });
    }
}
