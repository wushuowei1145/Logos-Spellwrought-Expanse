package dev.logos.dimension.gametest;

import com.mojang.authlib.GameProfile;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.firearm.AmmoBench;
import dev.logos.dimension.firearm.AmmoBlueprintItem;
import dev.logos.dimension.firearm.AmmoBenchBlockEntity;
import dev.logos.dimension.firearm.AmmoPressRecipe;
import dev.logos.dimension.firearm.BlankRoundItem;
import dev.logos.dimension.firearm.BuckshotItem;
import dev.logos.dimension.firearm.EtchedSpell;
import dev.logos.dimension.firearm.Firearms;
import dev.logos.dimension.firearm.GunLoad;
import dev.logos.dimension.firearm.RifleRoundItem;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.core.registries.BuiltInRegistries;
import dev.logos.dimension.firearm.RoundEffects;
import dev.logos.dimension.item.GunaxeItem;
import dev.logos.lang.Status;
import dev.logos.runtime.StatusData;
import net.minecraft.network.chat.contents.TranslatableContents;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * 铸弹台压弹（配方、一批八发、出货格、漏斗只进料只出弹），以及三种普通弹打出去的分工：
 * 步枪弹打得远、只打第一个，下界合金弹头多穿一个，空弹头不伤人（docs/FIREARMS.md 第 5、6 节）。
 */
public final class FirearmGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS = DeferredRegister.create(Registries.TEST_FUNCTION, LogosDimension.ID);

    public static void register(IEventBus bus) {
        Map<String, Consumer<GameTestHelper>> tests = new LinkedHashMap<>();
        tests.put("ammo_bench_presses_batches", FirearmGameTests::press);
        tests.put("ammo_bench_hoppers_feed_and_take", FirearmGameTests::automation);
        tests.put("rifle_round_reaches_past_buckshot", FirearmGameTests::reach);
        tests.put("netherite_rifle_round_pierces_one_more", FirearmGameTests::pierce);
        tests.put("blank_round_does_no_harm", FirearmGameTests::blank);
        // 术式弹（docs/FIREARMS.md 第 9 节）
        tests.put("ammo_bench_burns_mana_fuel", FirearmGameTests::fuel);
        tests.put("spell_rounds_etch_a_batch", FirearmGameTests::etchBatch);
        tests.put("spell_rounds_refuse_what_the_tier_cannot_hold", FirearmGameTests::etchLimits);
        tests.put("etched_rifle_round_casts_on_hit", FirearmGameTests::etchedRifle);
        tests.put("etched_buckshot_casts_once_per_target", FirearmGameTests::etchedBuckshot);
        tests.put("etched_round_impact_and_expire", FirearmGameTests::impactAndExpire);
        tests.put("etched_blank_round_only_casts", FirearmGameTests::etchedBlank);
        // 改装弹（docs/FIREARMS.md 第 10 节）
        tests.put("ammo_bench_modifies_a_batch", FirearmGameTests::modBatch);
        tests.put("incendiary_round_sets_fire", FirearmGameTests::incendiary);
        tests.put("mercury_round_banes_undead", FirearmGameTests::mercury);
        tests.put("firework_round_bursts_where_it_lands", FirearmGameTests::firework);
        tests.forEach((name, test) -> FUNCTIONS.register(name, () -> test));
        FUNCTIONS.register(bus);
        bus.addListener((RegisterGameTestsEvent event) -> {
            var env = event.registerEnvironment(LogosDimension.id("firearms"), new TestEnvironmentDefinition.AllOf());
            // 射击的几个要四十格的靶道，留足间距。
            for (String name : tests.keySet()) event.registerTest(LogosDimension.id(name), new FunctionGameTestInstance(
                    ResourceKey.create(Registries.TEST_FUNCTION, LogosDimension.id(name)),
                    new TestData<>(env, Identifier.withDefaultNamespace("empty"), 300, 0, true, Rotation.NONE, false, 1, 1, false, 48)));
        });
    }

    private static AmmoBenchBlockEntity bench(GameTestHelper h) {
        var pos = h.absolutePos(new BlockPos(1, 1, 1));
        h.getLevel().setBlockAndUpdate(pos, AmmoBench.BENCH.get().defaultBlockState());
        return (AmmoBenchBlockEntity) h.getLevel().getBlockEntity(pos);
    }

    private static void put(AmmoBenchBlockEntity bench, int slot, Item item, int count) {
        bench.inputs().set(slot, ItemResource.of(new ItemStack(item)), count);
    }

    /** 纸壳、火药、铁锭各两份：每两秒压出一批八发铁霰弹，压完两批停下。 */
    private static void press(GameTestHelper h) {
        var bench = bench(h);
        var level = h.getLevel();
        h.assertTrue(AmmoPressRecipe.usable(level, AmmoBenchBlockEntity.HEAD, new ItemStack(Items.IRON_INGOT)), "铁锭能当弹头");
        h.assertTrue(!AmmoPressRecipe.usable(level, AmmoBenchBlockEntity.HEAD, new ItemStack(Items.DIRT)), "泥土不能当弹头");
        h.assertTrue(!AmmoPressRecipe.usable(level, AmmoBenchBlockEntity.CASING, new ItemStack(Items.GUNPOWDER)), "火药不能当弹壳");
        put(bench, AmmoBenchBlockEntity.CASING, Items.PAPER, 2);
        put(bench, AmmoBenchBlockEntity.POWDER, Items.GUNPOWDER, 2);
        put(bench, AmmoBenchBlockEntity.HEAD, Items.IRON_INGOT, 2);
        h.assertTrue(bench.pressing().is(BuckshotItem.ITEM.get()) && bench.pressing().getCount() == 8, "纸壳加铁弹头压铁霰弹，一批八发");
        h.runAfterDelay(30, () -> h.assertTrue(bench.output().getAmountAsInt(0) == 0 && bench.progress() > .5f, "一批要两秒，还在压"));
        h.runAfterDelay(45, () -> h.assertTrue(bench.output().getAmountAsInt(0) == 8 && bench.inputs().getAmountAsInt(0) == 1, "第一批八发，材料各扣一份"));
        h.succeedWhen(() -> {
            h.assertTrue(bench.output().getAmountAsInt(0) == 16, "压完两批");
            h.assertTrue(bench.output().getResource(0).getItem() == BuckshotItem.ITEM.get(), "出的是铁霰弹");
            for (int i = 0; i < 3; i++) h.assertTrue(bench.inputs().getAmountAsInt(i) == 0, "材料用光");
            h.assertTrue(bench.progress() == 0 && bench.pressing().isEmpty(), "没料了就停");
        });
    }

    /** 漏斗只能往三格送料（各进各的格），只能从出货格取弹；出货格里是别的弹时不压。 */
    private static void automation(GameTestHelper h) {
        var bench = bench(h);
        var pipe = bench.automation();
        try (var tx = Transaction.openRoot()) {
            h.assertTrue(pipe.insert(ItemResource.of(new ItemStack(Items.GUNPOWDER)), 4, tx) == 4, "火药送得进");
            h.assertTrue(pipe.insert(ItemResource.of(new ItemStack(Items.PAPER)), 4, tx) == 4, "纸送得进");
            h.assertTrue(pipe.insert(ItemResource.of(new ItemStack(Items.NETHERITE_SCRAP)), 4, tx) == 4, "下界合金碎片送得进");
            h.assertTrue(pipe.insert(ItemResource.of(new ItemStack(Items.DIRT)), 4, tx) == 0, "泥土送不进");
            h.assertTrue(pipe.extract(ItemResource.of(new ItemStack(Items.PAPER)), 4, tx) == 0, "材料取不出来");
            tx.commit();
        }
        h.assertTrue(bench.inputs().getResource(AmmoBenchBlockEntity.CASING).getItem() == Items.PAPER, "纸进了弹壳格");
        h.assertTrue(bench.inputs().getResource(AmmoBenchBlockEntity.POWDER).getItem() == Items.GUNPOWDER, "火药进了火药格");
        h.assertTrue(bench.inputs().getResource(AmmoBenchBlockEntity.HEAD).getItem() == Items.NETHERITE_SCRAP, "碎片进了弹头格");
        h.runAfterDelay(45, () -> {
            h.assertTrue(bench.output().getResource(0).getItem() == BuckshotItem.NETHERITE.get(), "压出下界合金霰弹");
            try (var tx = Transaction.openRoot()) {
                int got = pipe.extract(ItemResource.of(new ItemStack(BuckshotItem.NETHERITE.get())), 64, tx);
                h.assertTrue(got == 8, "漏斗取走八发，取到 " + got);
                tx.commit();
            }
            // 出货格里放着别的弹：放不下这一批，不压。
            bench.output().set(0, ItemResource.of(new ItemStack(RifleRoundItem.IRON.get())), 1);
        });
        h.runAfterDelay(120, () -> {
            h.assertTrue(bench.output().getAmountAsInt(0) == 1 && bench.inputs().getAmountAsInt(0) == 3, "出货格被别的弹占着就不压");
            h.succeed();
        });
    }

    private static ServerPlayer shooter(GameTestHelper h, BlockPos at, ItemStack round) {
        var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "firearm-test"), false);
        var p = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        h.getLevel().getServer().getPlayerList().placeNewPlayer(connection, p, cookie);
        p.connection.markClientLoaded();
        p.setGameMode(GameType.SURVIVAL);
        p.getInventory().clearContent();
        // 朝 +X 看
        p.snapTo(at.getX() + .5, at.getY(), at.getZ() + .5, -90, 0);
        p.setYHeadRot(-90);
        var gun = new ItemStack(GunaxeItem.ITEM.get());
        gun.set(Firearms.LOAD.get(), new GunLoad(round.copyWithCount(1), round.copyWithCount(1), 6));
        p.setItemInHand(InteractionHand.MAIN_HAND, gun);
        return p;
    }

    /** 一条石头地面的靶道，往 +X 方向 {@code length} 格。 */
    private static BlockPos range(GameTestHelper h, int length) {
        var level = h.getLevel();
        var o = h.absolutePos(new BlockPos(2, 1, 4));
        for (int x = -2; x <= length; x++) for (int z = -2; z <= 2; z++) {
            level.setBlockAndUpdate(o.offset(x, -1, z), Blocks.STONE.defaultBlockState());
            for (int y = 0; y <= 4; y++) level.setBlockAndUpdate(o.offset(x, y, z), Blocks.AIR.defaultBlockState());
        }
        return o;
    }

    private static IronGolem golem(GameTestHelper h, BlockPos o, double x) {
        var golem = EntityType.IRON_GOLEM.create(h.getLevel(), EntitySpawnReason.COMMAND);
        golem.setNoAi(true);
        golem.snapTo(o.getX() + x + .5, o.getY(), o.getZ() + .5, 90, 0);
        h.getLevel().addFreshEntity(golem);
        return golem;
    }

    /** 三十格外的木桩：瞄准打霰弹够不着，打铁步枪弹掉 12 × 铳斧威力 0.85 点。 */
    private static void reach(GameTestHelper h) {
        var o = range(h, 34);
        var golem = golem(h, o, 30);
        float full = golem.getHealth();
        var buck = shooter(h, o, new ItemStack(BuckshotItem.ITEM.get()));
        h.assertTrue(Firearms.fire(buck, buck.getMainHandItem(), true, 1, 1), "霰弹开得了枪");
        h.assertTrue(golem.getHealth() == full, "霰弹够不着三十格");
        buck.discard();
        var rifle = shooter(h, o, new ItemStack(RifleRoundItem.IRON.get()));
        h.assertTrue(Firearms.fire(rifle, rifle.getMainHandItem(), true, 1, 1), "步枪弹开得了枪");
        float lost = full - golem.getHealth();
        h.assertTrue(Math.abs(lost - 12 * .85) < .5, "铁步枪弹在三十格满伤，掉了 " + lost);
        h.assertTrue(Firearms.load(rifle.getMainHandItem()).rounds() == 5, "少一发");
        rifle.discard();
        h.succeed();
    }

    /** 两个木桩前后站成一线：铁步枪弹只打前面那个，下界合金弹头连后面的一起打穿。 */
    private static void pierce(GameTestHelper h) {
        var o = range(h, 14);
        var front = golem(h, o, 6);
        var back = golem(h, o, 10);
        float full = front.getHealth();
        var iron = shooter(h, o, new ItemStack(RifleRoundItem.IRON.get()));
        Firearms.fire(iron, iron.getMainHandItem(), true, 1, 1);
        h.assertTrue(front.getHealth() < full && back.getHealth() == full, "铁弹头停在第一个");
        iron.discard();
        float frontLeft = front.getHealth();
        var netherite = shooter(h, o, new ItemStack(RifleRoundItem.NETHERITE.get()));
        Firearms.fire(netherite, netherite.getMainHandItem(), true, 1, 1);
        float frontLost = frontLeft - front.getHealth(), backLost = full - back.getHealth();
        h.assertTrue(Math.abs(frontLost - 12 * 1.4 * .85) < .5, "下界合金弹头 ×1.4，前面掉了 " + frontLost);
        h.assertTrue(Math.abs(backLost - 12 * 1.4 * .85) < .5, "穿过去打中后面那个，掉了 " + backLost);
        netherite.discard();
        h.succeed();
    }

    /** 空弹头贴脸开枪：照样扣一发，但木桩一点血不掉。 */
    private static void blank(GameTestHelper h) {
        var o = range(h, 6);
        var golem = golem(h, o, 3);
        float full = golem.getHealth();
        var p = shooter(h, o, new ItemStack(BlankRoundItem.ITEM.get()));
        h.assertTrue(Firearms.fire(p, p.getMainHandItem(), true, 2, 1), "空弹头也能开枪");
        h.assertTrue(golem.getHealth() == full, "空弹头不伤人");
        h.assertTrue(Firearms.load(p.getMainHandItem()).rounds() == 5, "照样少一发");
        p.discard();
        h.succeed();
    }

    // ---------------------------------------------------------------- 术式弹

    private static ItemStack etched(Item round, String source, int tier, double mana) {
        var stack = new ItemStack(round);
        stack.set(Firearms.ETCHED.get(), new EtchedSpell(source, tier, mana));
        return stack;
    }

    private static void slot(AmmoBenchBlockEntity bench, int slot, ItemStack stack, int count) {
        bench.etching().set(slot, ItemResource.of(stack), count);
    }

    /** 做不了时的原因（语言键的最后一段）。 */
    private static String problem(AmmoBenchBlockEntity bench) {
        var plan = bench.etchPlan();
        if (plan.problem() == null) return "ok";
        var key = ((TranslatableContents) plan.problem().getContents()).getKey();
        return key.substring(key.lastIndexOf('.') + 1);
    }

    /** 两颗青金石放进补魔格：各 50 魔力，整个烧进台子。 */
    private static void fuel(GameTestHelper h) {
        var bench = bench(h);
        bench.fuel().set(0, ItemResource.of(new ItemStack(Items.LAPIS_LAZULI)), 2);
        h.assertTrue(bench.mana() == 0, "空台子");
        h.succeedWhen(() -> h.assertTrue(bench.mana() == 100 && bench.fuel().getAmountAsInt(0) == 0, "烧进 100 魔力，现在 " + bench.mana()));
    }

    /** 第二档（紫水晶 + 金锭，每发最多 30）刻一批：十发铁步枪弹刻出八发，扣一份材料和八发的注入，剩两发等下一份材料。 */
    private static void etchBatch(GameTestHelper h) {
        var bench = bench(h);
        String src = "(spell :name \"火弹\" :mana 20 (rifle-round (on-hit (damage target 5 fire))))";
        bench.receiveMana(300);
        slot(bench, AmmoBenchBlockEntity.BLUEPRINT, AmmoBlueprintItem.of(src), 1);
        slot(bench, AmmoBenchBlockEntity.ROUNDS, new ItemStack(RifleRoundItem.IRON.get()), 10);
        slot(bench, AmmoBenchBlockEntity.CATALYST, new ItemStack(Items.AMETHYST_SHARD), 1);
        slot(bench, AmmoBenchBlockEntity.CONDUCTOR, new ItemStack(Items.GOLD_INGOT), 1);
        h.assertTrue(problem(bench).equals("ok") && bench.etchPlan().count() == 8, "一批八发：" + problem(bench));
        h.succeedWhen(() -> {
            var out = bench.etched().getResource(0).toStack(bench.etched().getAmountAsInt(0));
            h.assertTrue(out.getCount() == 8 && out.is(RifleRoundItem.IRON.get()), "刻出八发铁步枪弹");
            h.assertTrue(new EtchedSpell(src, 2, 20).equals(out.get(Firearms.ETCHED.get())), "刻着图纸原文、第二档、每发 20：" + out.get(Firearms.ETCHED.get()));
            h.assertTrue(RoundEffects.spell(2).equals(out.get(Firearms.FINISH.get())), "外观是第二档（紫水晶尖）");
            h.assertTrue(Math.abs(bench.mana() - 140) < 1e-6, "扣了八发的注入，剩 " + bench.mana());
            h.assertTrue(bench.etching().getAmountAsInt(AmmoBenchBlockEntity.ROUNDS) == 2, "剩两发没刻");
            h.assertTrue(bench.etching().getAmountAsInt(AmmoBenchBlockEntity.BLUEPRINT) == 1, "图纸不消耗");
            h.assertTrue(problem(bench).equals("no_materials"), "材料用完就停：" + problem(bench));
        });
    }

    /** 第一档每发最多 10：图纸要 20 刻不了普通弹，空弹头加倍刚好装得下；图纸弹种不对、魔力不够也不刻。 */
    private static void etchLimits(GameTestHelper h) {
        var bench = bench(h);
        String rifle = "(spell :mana 20 (rifle-round (on-hit (damage target 5))))";
        bench.receiveMana(15);
        slot(bench, AmmoBenchBlockEntity.BLUEPRINT, AmmoBlueprintItem.of(rifle), 1);
        slot(bench, AmmoBenchBlockEntity.ROUNDS, new ItemStack(BuckshotItem.ITEM.get()), 4);
        slot(bench, AmmoBenchBlockEntity.CONDUCTOR, new ItemStack(Items.GOLD_INGOT), 1);
        h.assertTrue(problem(bench).equals("wrong_round"), "步枪弹图纸刻不了霰弹：" + problem(bench));
        slot(bench, AmmoBenchBlockEntity.ROUNDS, new ItemStack(RifleRoundItem.IRON.get()), 4);
        h.assertTrue(problem(bench).equals("over_capacity"), "第一档装不下 20：" + problem(bench));
        String blank = "(spell :mana 20 (blank-round (on-hit (damage target 5))))";
        slot(bench, AmmoBenchBlockEntity.BLUEPRINT, AmmoBlueprintItem.of(blank), 1);
        slot(bench, AmmoBenchBlockEntity.ROUNDS, new ItemStack(BlankRoundItem.ITEM.get()), 4);
        h.assertTrue(problem(bench).equals("no_mana"), "空弹头第一档装得下 20，但台子只有 15：" + problem(bench));
        bench.receiveMana(30);
        h.assertTrue(problem(bench).equals("ok") && bench.etchPlan().count() == 2, "45 魔力够刻两发：" + problem(bench));
        h.succeed();
    }

    /** 刻了「命中时 5 点火焰」的铁步枪弹：十格外的木桩挨 12 × 0.85 的物理伤害，再挨 5 点火焰。 */
    private static void etchedRifle(GameTestHelper h) {
        var o = range(h, 14);
        var golem = golem(h, o, 10);
        float full = golem.getHealth();
        var p = shooter(h, o, etched(RifleRoundItem.IRON.get(), "(spell :mana 20 (rifle-round (on-hit (damage target 5 fire))))", 2, 20));
        h.assertTrue(Firearms.fire(p, p.getMainHandItem(), true, 1, 1), "开得了枪");
        float lost = full - golem.getHealth();
        h.assertTrue(Math.abs(lost - (12 * .85 + 5)) < .3, "物理 10.2 加术式 5，实际掉了 " + lost);
        p.discard();
        h.succeed();
    }

    /** 霰弹几颗打中同一个木桩：命中生物只触发一次。 */
    private static void etchedBuckshot(GameTestHelper h) {
        var o = range(h, 6);
        var golem = golem(h, o, 2);
        var p = shooter(h, o, etched(BuckshotItem.ITEM.get(), "(spell :mana 10 (buckshot-round (on-hit (mark target #shell))))", 1, 10));
        Firearms.fire(p, p.getMainHandItem(), true, 1, 1);
        double marks = StatusData.stacks(golem, new Status("#shell"));
        h.assertTrue(marks == 1, "一个目标一次：" + marks);
        p.discard();
        h.succeed();
    }

    /** 什么都没打中：打在墙上触发命中方块，朝天飞到尽头触发消散。 */
    private static void impactAndExpire(GameTestHelper h) {
        var o = range(h, 8);
        for (int y = 0; y <= 3; y++) for (int z = -1; z <= 1; z++) h.getLevel().setBlockAndUpdate(o.offset(5, y, z), Blocks.STONE.defaultBlockState());
        var round = etched(RifleRoundItem.IRON.get(), "(spell :mana 10 (rifle-round (on-impact (mark caster #impact)) (on-expire (mark caster #expire))))", 1, 10);
        var p = shooter(h, o, round);
        Firearms.fire(p, p.getMainHandItem(), true, 1, 1);
        h.assertTrue(StatusData.stacks(p, new Status("#impact")) == 1 && StatusData.stacks(p, new Status("#expire")) == 0, "打在墙上");
        p.setXRot(-90);
        Firearms.fire(p, p.getMainHandItem(), true, 1, 1);
        h.assertTrue(StatusData.stacks(p, new Status("#expire")) == 1, "朝天飞到尽头");
        p.discard();
        h.succeed();
    }

    /** 刻了「命中时 6 点」的空弹头：没有弹丸的物理伤害，只有术式的 6 点，第五下的 ×2 也不乘术式。 */
    private static void etchedBlank(GameTestHelper h) {
        var o = range(h, 8);
        var golem = golem(h, o, 5);
        float full = golem.getHealth();
        var p = shooter(h, o, etched(BlankRoundItem.ITEM.get(), "(spell :mana 20 (blank-round (on-hit (damage target 6))))", 1, 20));
        Firearms.fire(p, p.getMainHandItem(), true, 2, 1);
        float lost = full - golem.getHealth();
        h.assertTrue(Math.abs(lost - 6) < .2, "只有术式的 6 点，实际 " + lost);
        p.discard();
        h.succeed();
    }

    // ---------------------------------------------------------------- 改装弹

    private static ItemStack modded(Item round, String finish) {
        var stack = new ItemStack(round);
        stack.set(Firearms.FINISH.get(), finish);
        return stack;
    }

    /** 十发铁步枪弹加一份硫粉改出八发燃烧弹，硫粉扣一份；水银瓶改完把空瓶留在台子上。 */
    private static void modBatch(GameTestHelper h) {
        var bench = bench(h);
        bench.modding().set(AmmoBenchBlockEntity.MOD_ROUNDS, ItemResource.of(new ItemStack(RifleRoundItem.IRON.get())), 10);
        bench.modding().set(AmmoBenchBlockEntity.ADDITIVE, ItemResource.of(new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse("logos:sulfur_powder")))), 1);
        h.assertTrue(bench.modPlan().count() == 8, "一批八发：" + bench.modPlan().problem());
        h.runAfterDelay(50, () -> {
            var out = bench.modded().getResource(0).toStack(bench.modded().getAmountAsInt(0));
            h.assertTrue(out.getCount() == 8 && RoundEffects.SULFUR.equals(out.get(Firearms.FINISH.get())), "八发燃烧弹：" + out);
            h.assertTrue(!out.has(Firearms.ETCHED.get()), "改装弹没有术式");
            h.assertTrue(bench.modding().getAmountAsInt(AmmoBenchBlockEntity.ADDITIVE) == 0, "硫粉扣了一份");
            h.assertTrue(!AmmoBenchBlockEntity.moddable(out), "改装过的不能再改");
            // 换水银瓶改剩下的两发：空瓶留在台子上。
            bench.modded().set(0, ItemResource.EMPTY, 0);
            bench.modding().set(AmmoBenchBlockEntity.ADDITIVE, ItemResource.of(new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse("logos:mercury_bottle")))), 1);
        });
        h.succeedWhen(() -> {
            var out = bench.modded().getResource(0).toStack(bench.modded().getAmountAsInt(0));
            h.assertTrue(out.getCount() == 2 && RoundEffects.MERCURY.equals(out.get(Firearms.FINISH.get())), "剩下两发改成水银弹：" + out);
            var bottles = h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(bench.getBlockPos()).inflate(3),
                    e -> e.getItem().is(Items.GLASS_BOTTLE));
            h.assertTrue(!bottles.isEmpty(), "空瓶留在台子上");
        });
    }

    /** 燃烧弹打中的木桩着火。 */
    private static void incendiary(GameTestHelper h) {
        var o = range(h, 8);
        var golem = golem(h, o, 5);
        var p = shooter(h, o, modded(RifleRoundItem.IRON.get(), RoundEffects.SULFUR));
        Firearms.fire(p, p.getMainHandItem(), true, 1, 1);
        h.assertTrue(golem.isOnFire(), "着火了");
        p.discard();
        h.succeed();
    }

    /** 水银弹：打尸壳（亡灵）伤害 ×1.5 并上虚弱；打铁傀儡（不是亡灵）和普通弹一样。 */
    private static void mercury(GameTestHelper h) {
        var o = range(h, 8);
        float[] lost = new float[2];
        for (int i = 0; i < 2; i++) {
            var husk = EntityType.HUSK.create(h.getLevel(), EntitySpawnReason.COMMAND);
            husk.setNoAi(true);
            husk.snapTo(o.getX() + 6.5, o.getY(), o.getZ() + .5, 90, 0);
            h.getLevel().addFreshEntity(husk);
            float full = husk.getHealth();
            var p = shooter(h, o, i == 0 ? new ItemStack(RifleRoundItem.IRON.get()) : modded(RifleRoundItem.IRON.get(), RoundEffects.MERCURY));
            Firearms.fire(p, p.getMainHandItem(), true, 1, 1);
            lost[i] = full - husk.getHealth();
            if (i == 1) h.assertTrue(husk.hasEffect(MobEffects.WEAKNESS), "尸壳挨了水银弹，虚弱");
            p.discard();
            husk.discard();
        }
        h.assertTrue(Math.abs(lost[1] / lost[0] - RoundEffects.MERCURY_BANE) < .05, "对亡灵 ×1.5：普通 " + lost[0] + "，水银 " + lost[1]);
        var golem = golem(h, o, 6);
        float full = golem.getHealth();
        var p = shooter(h, o, modded(RifleRoundItem.IRON.get(), RoundEffects.MERCURY));
        Firearms.fire(p, p.getMainHandItem(), true, 1, 1);
        h.assertTrue(Math.abs(full - golem.getHealth() - 12 * .85) < .3 && !golem.hasEffect(MobEffects.WEAKNESS), "铁傀儡不是亡灵：照常伤害，没有虚弱");
        p.discard();
        h.succeed();
    }

    /** 烟火弹打在墙上：落点出现一枚烟火，下一刻炸开，伤到旁边的木桩。 */
    private static void firework(GameTestHelper h) {
        var o = range(h, 8);
        for (int y = 0; y <= 3; y++) for (int z = -2; z <= 2; z++) h.getLevel().setBlockAndUpdate(o.offset(6, y, z), Blocks.STONE.defaultBlockState());
        var golem = golem(h, o, 4);
        golem.snapTo(o.getX() + 4.5, o.getY(), o.getZ() + 2.5, 90, 0);
        float full = golem.getHealth();
        var p = shooter(h, o, modded(RifleRoundItem.IRON.get(), RoundEffects.FIREWORK));
        Firearms.fire(p, p.getMainHandItem(), true, 1, 1);
        var rockets = h.getLevel().getEntitiesOfClass(FireworkRocketEntity.class, new AABB(o.offset(6, 1, 0)).inflate(2));
        h.assertTrue(!rockets.isEmpty(), "墙上的落点出现一枚烟火");
        p.discard();
        h.succeedWhen(() -> {
            h.assertTrue(h.getLevel().getEntitiesOfClass(FireworkRocketEntity.class, new AABB(o.offset(6, 1, 0)).inflate(2)).isEmpty(), "烟火炸开了");
            h.assertTrue(golem.getHealth() < full, "旁边的木桩挨了烟火");
        });
    }
}
