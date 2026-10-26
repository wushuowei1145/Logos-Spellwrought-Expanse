package dev.logos.dimension.gametest;

import com.mojang.authlib.GameProfile;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.item.*;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.*;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 使用真实世界和 Logos 运行时，检查射击资源、弹道、药效、生命伤害与生物发射。 */
public final class NaturesForceGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS = DeferredRegister.create(Registries.TEST_FUNCTION, LogosDimension.ID);

    public static void register(IEventBus bus) {
        var tests = Map.<String, Consumer<GameTestHelper>>of(
                "nature_player_costs", NaturesForceGameTests::playerCosts,
                "nature_flight", NaturesForceGameTests::flight,
                "nature_lifetime", NaturesForceGameTests::lifetime,
                "nature_potion_damage", h -> hit(h, true),
                "nature_spectral_damage", h -> hit(h, false),
                "nature_mob", NaturesForceGameTests::mob,
                "nature_vanilla_unchanged", NaturesForceGameTests::vanilla,
                "nature_persistence", NaturesForceGameTests::persistence,
                "nature_unloaded_chunk", NaturesForceGameTests::unloaded,
                "nature_mob_ai", NaturesForceGameTests::mobAi);
        tests.forEach((name, test) -> FUNCTIONS.register(name, () -> test));
        FUNCTIONS.register(bus);
        bus.addListener((RegisterGameTestsEvent event) -> {
            var env = event.registerEnvironment(LogosDimension.id("nature"), new TestEnvironmentDefinition.AllOf());
            for (String name : tests.keySet()) event.registerTest(LogosDimension.id(name), new FunctionGameTestInstance(
                    ResourceKey.create(Registries.TEST_FUNCTION, LogosDimension.id(name)), new TestData<>(env,
                    Identifier.withDefaultNamespace("empty"), 180, 0, true, Rotation.NONE, false, 1, 1, false, 16)));
        });
    }

    private static ServerPlayer player(GameTestHelper h) {
        var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "nature-test"), false);
        var player = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        h.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        player.connection.markClientLoaded();
        player.setGameMode(GameType.SURVIVAL);
        player.setPos(h.absoluteVec(new Vec3(4, 15, 4)));
        return player;
    }

    private static void playerCosts(GameTestHelper h) {
        var p = player(h);
        var bowItem = NaturesForceItem.ITEM.get();
        try {
            for (int ticks : new int[]{20, 30, 40}) {
                var bow = new ItemStack(bowItem);
                p.setItemInHand(InteractionHand.MAIN_HAND, bow);
                p.getInventory().clearContent();
                p.setItemInHand(InteractionHand.MAIN_HAND, bow);
                var ammo = new ItemStack(Items.ARROW, 3);
                p.setItemInHand(InteractionHand.OFF_HAND, ammo);
                LogosNatureBridge.mana(p, 100);
                p.startUsingItem(InteractionHand.MAIN_HAND);
                h.assertTrue(bowItem.releaseUsing(bow, h.getLevel(), p, 72000 - ticks), "合法蓄力没有发射");
                h.assertTrue(ammo.getCount() == 2, "没有恰好消耗一支箭");
                h.assertTrue(LogosNatureBridge.mana(p) == 100 - NatureCharge.mana(ticks), "第三、四阶魔力费用不正确");
                var arrows = h.getLevel().getEntitiesOfClass(AbstractArrow.class, p.getBoundingBox().inflate(3));
                h.assertTrue(arrows.size() == 1, "发射数量不正确");
                var arrow = arrows.getFirst();
                h.assertTrue(((NatureArrow) arrow).natureStage() == NatureCharge.stage(ticks), "射出箭矢阶段不正确");
                h.assertTrue(Math.abs(arrow.getDeltaMovement().length() - NatureCharge.speed(ticks)) < .1, "射速不正确");
                arrow.discard();
            }
            var bow = new ItemStack(bowItem);
            p.setItemInHand(InteractionHand.MAIN_HAND, bow);
            var ammo = new ItemStack(Items.ARROW, 3);
            p.setItemInHand(InteractionHand.OFF_HAND, ammo);
            LogosNatureBridge.mana(p, 7);
            p.startUsingItem(InteractionHand.MAIN_HAND);
            h.assertTrue(bowItem.releaseUsing(bow, h.getLevel(), p, 71960), "魔力不足应降为第二阶");
            var arrow = h.getLevel().getEntitiesOfClass(AbstractArrow.class, p.getBoundingBox().inflate(3)).getFirst();
            h.assertTrue(((NatureArrow) arrow).natureStage() == 2 && !arrow.isNoGravity(), "魔力不足仍射出了强化箭");
            h.assertTrue(LogosNatureBridge.mana(p) == 7 && ammo.getCount() == 2, "降阶射击不应扣魔力，但应消耗箭");
            arrow.discard();
            p.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            p.startUsingItem(InteractionHand.MAIN_HAND);
            h.assertTrue(!bowItem.releaseUsing(bow, h.getLevel(), p, 71960), "无箭仍能生存射击");
            h.assertTrue(LogosNatureBridge.mana(p) == 7, "无箭不应扣除魔力");
        } finally { h.getLevel().getServer().getPlayerList().remove(p); }
        h.succeed();
    }

    private static AbstractArrow arrow(GameTestHelper h) {
        var owner = EntityType.SKELETON.create(h.getLevel(), EntitySpawnReason.COMMAND);
        owner.setPos(h.absoluteVec(new Vec3(4, 15, 4)));
        var bow = new ItemStack(NaturesForceItem.ITEM.get());
        var arrow = ((ArrowItem) Items.ARROW).createArrow(h.getLevel(), new ItemStack(Items.ARROW), owner, bow);
        ((NatureArrow) arrow).natureStage(4);
        ((NatureArrow) arrow).natureDamageScale(3d / 8d);
        arrow.setCritArrow(false);
        return arrow;
    }

    private static void flight(GameTestHelper h) {
        var a = arrow(h);
        a.setPos(h.absoluteVec(new Vec3(4, 15, 4)));
        a.setDeltaMovement(.3, 0, .4);
        var original = a.position();
        for (int i = 0; i < 5; i++) a.tick();
        h.assertTrue(!a.isRemoved(), "已加载区块中的箭提前消失");
        h.assertTrue(a.getDeltaMovement().distanceTo(new Vec3(.3, 0, .4)) < .00001, "强化箭受到重力或阻力");
        h.assertTrue(a.position().distanceTo(original.add(1.5, 0, 2)) < .00001, "强化箭没有沿直线飞行");
        a.discard(); h.succeed();
    }

    private static void lifetime(GameTestHelper h) {
        var a = arrow(h);
        a.setDeltaMovement(Vec3.ZERO);
        h.getLevel().addFreshEntity(a);
        // 无玩家的 GameTest 不保证实体所在区块持续模拟，明确触发到期检查。
        h.runAtTickTime(78, () -> { a.tick(); h.assertTrue(!a.isRemoved(), "未满四秒就到期"); });
        h.runAtTickTime(82, () -> { a.tick(); h.assertTrue(a.isRemoved(), "强化箭四秒后仍未消失，世界时钟 " + h.getLevel().getGameTime()); h.succeed(); });
    }

    private static void hit(GameTestHelper h, boolean potion) {
        var level = h.getLevel();
        var owner = EntityType.SKELETON.create(level, EntitySpawnReason.COMMAND);
        owner.setPos(h.absoluteVec(new Vec3(4, 15, 4)));
        var target = EntityType.IRON_GOLEM.create(level, EntitySpawnReason.COMMAND);
        target.setNoAi(true); target.setNoGravity(true);
        target.setPos(owner.position().add(4, 0, 0));
        level.addFreshEntity(target);
        for (int stage : new int[]{2, 3}) {
            var ordinary = ((ArrowItem) Items.ARROW).createArrow(level, new ItemStack(Items.ARROW), owner, new ItemStack(NaturesForceItem.ITEM.get()));
            ((NatureArrow) ordinary).natureStage(stage);
            ((NatureArrow) ordinary).natureDamageScale(stage == 2 ? .9 : 1);
            ordinary.setCritArrow(false); ordinary.setDeltaMovement(stage == 2 ? 2.7 : 3, 0, 0);
            // 先走一刻靠近，再命中；前面三阶不应获得生命追加伤害。
            level.addFreshEntity(ordinary); ordinary.tick();
            if (!ordinary.isRemoved()) ordinary.tick();
            h.assertTrue(target.getHealth() == (stage == 2 ? 95 : 94), "普通阶段伤害错误或提前附加了生命伤害：" + stage);
            ordinary.discard(); target.setHealth(100); target.invulnerableTime = 0;
        }
        var ammo = new ItemStack(potion ? Items.TIPPED_ARROW : Items.SPECTRAL_ARROW);
        if (potion) ammo.set(DataComponents.POTION_CONTENTS, new PotionContents(Potions.SLOWNESS));
        var a = ((ArrowItem) ammo.getItem()).createArrow(level, ammo, owner, new ItemStack(NaturesForceItem.ITEM.get()));
        ((NatureArrow) a).natureStage(4); ((NatureArrow) a).natureDamageScale(3d / 8d);
        a.setCritArrow(false); a.setDeltaMovement(8, 0, 0);
        level.addFreshEntity(a);
        a.tick();
        h.assertTrue(target.getHealth() == 84, "强化命中应造成六点物理及十点 Logos 生命伤害，实际剩余 " + target.getHealth());
        h.assertTrue(target.hasEffect(potion ? MobEffects.SLOWNESS : MobEffects.GLOWING), "原版箭矢药效丢失");
        target.discard(); a.discard(); h.succeed();
    }

    private static void mob(GameTestHelper h) {
        var mob = EntityType.SKELETON.create(h.getLevel(), EntitySpawnReason.COMMAND);
        mob.setPos(h.absoluteVec(new Vec3(4, 15, 4)));
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(NaturesForceItem.ITEM.get()));
        var target = EntityType.IRON_GOLEM.create(h.getLevel(), EntitySpawnReason.COMMAND);
        target.setPos(mob.position().add(10, 0, 0));
        mob.performRangedAttack(target, 1);
        var a = h.getLevel().getEntitiesOfClass(AbstractArrow.class, mob.getBoundingBox().inflate(3)).getFirst();
        h.assertTrue(((NatureArrow) a).natureStage() == 4 && a.pickup == AbstractArrow.Pickup.DISALLOWED, "生物没有射出禁止拾取的第四阶箭");
        h.assertTrue(Math.abs(a.getDeltaMovement().length() - 8) < .001 && a.isNoGravity(), "生物发射速度或重力错误");
        a.discard(); h.succeed();
    }

    private static void vanilla(GameTestHelper h) {
        var a = arrow(h);
        ((NatureArrow) a).natureStage(0);
        a.setDeltaMovement(.3, 0, .4);
        a.tick();
        h.assertTrue(a.getDeltaMovement().y < 0 && a.getDeltaMovement().length() < .5, "原版箭的阻力或重力被全局修改");
        a.discard(); h.succeed();
    }

    private static void persistence(GameTestHelper h) {
        var a = arrow(h);
        var out = net.minecraft.world.level.storage.TagValueOutput.createWithContext(net.minecraft.util.ProblemReporter.DISCARDING, h.getLevel().registryAccess());
        a.saveWithoutId(out);
        var tag = out.buildResult();
        long deadline = tag.getLongOr("logos_dimension:nature_expires_at", 0);
        h.assertTrue(deadline == h.getLevel().getGameTime() + 80, "保存的到期时间应从发射时起算四秒");
        var restored = arrow(h);
        restored.load(net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING, h.getLevel().registryAccess(), tag));
        h.assertTrue(((NatureArrow) restored).natureStage() == 4 && restored.isNoGravity(), "重载丢失第四阶或无重力状态");
        var second = net.minecraft.world.level.storage.TagValueOutput.createWithContext(net.minecraft.util.ProblemReporter.DISCARDING, h.getLevel().registryAccess());
        restored.saveWithoutId(second);
        h.assertTrue(second.buildResult().getLongOr("logos_dimension:nature_expires_at", 0) == deadline, "重载重置了箭的寿命");
        tag.putLong("logos_dimension:nature_expires_at", h.getLevel().getGameTime() - 1);
        restored.load(net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING, h.getLevel().registryAccess(), tag));
        restored.tick();
        h.assertTrue(restored.isRemoved(), "已到期的存档箭没有在首刻删除");
        a.discard(); h.succeed();
    }

    private static void unloaded(GameTestHelper h) {
        var a = arrow(h);
        a.setPos(2_000_000, 150, 2_000_000);
        a.setDeltaMovement(8, 0, 0);
        var pos = BlockPos.containing(a.position().add(8, 0, 0));
        h.assertTrue(!h.getLevel().hasChunkAt(pos), "测试目的区块必须尚未加载");
        a.tick();
        h.assertTrue(a.isRemoved() && !h.getLevel().hasChunkAt(pos), "箭进入了未加载区块或强制加载区块");
        h.succeed();
    }

    private static void mobAi(GameTestHelper h) {
        var level = h.getLevel();
        var mob = EntityType.SKELETON.create(level, EntitySpawnReason.COMMAND);
        mob.setNoAi(true); mob.setNoGravity(true);
        mob.setPos(h.absoluteVec(new Vec3(4, 15, 4)));
        mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(NaturesForceItem.ITEM.get()));
        var target = EntityType.IRON_GOLEM.create(level, EntitySpawnReason.COMMAND);
        target.setPos(mob.position().add(10, 0, 0));
        mob.setTarget(target);
        var goal = new net.minecraft.world.entity.ai.goal.RangedBowAttackGoal<>(mob, 1, 20, 15);
        h.assertTrue(goal.canUse(), "原版远程 AI 没有识别自然之力");
        mob.startUsingItem(InteractionHand.MAIN_HAND);
        for (int i = 0; i < 25; i++) { mob.tick(); goal.tick(); }
        h.assertTrue(level.getEntitiesOfClass(AbstractArrow.class, mob.getBoundingBox().inflate(3)).isEmpty(), "生物未到四十刻就释放自然之力");
        for (int i = 25; i < 42; i++) { mob.tick(); goal.tick(); }
        var arrows = level.getEntitiesOfClass(AbstractArrow.class, mob.getBoundingBox().inflate(3));
        h.assertTrue(arrows.size() == 1, "四十刻后原版远程 AI 没有释放自然之力");
        arrows.forEach(AbstractArrow::discard); h.succeed();
    }

    private NaturesForceGameTests() {}
}
