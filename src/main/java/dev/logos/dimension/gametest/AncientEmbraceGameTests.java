package dev.logos.dimension.gametest;

import com.mojang.authlib.GameProfile;
import com.mojang.serialization.JsonOps;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.item.*;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
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
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/** 真实伤害路径验证盾前摇、弹反边界、收盾治疗、耐久与资源，不直接调用充能回调。 */
public final class AncientEmbraceGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS = DeferredRegister.create(Registries.TEST_FUNCTION, LogosDimension.ID);
    public static void register(IEventBus bus) {
        var tests = Map.<String, Consumer<GameTestHelper>>ofEntries(
                Map.entry("ancient_embrace_template", AncientEmbraceGameTests::template),
                Map.entry("ancient_embrace_instant", AncientEmbraceGameTests::instant),
                Map.entry("ancient_embrace_perfect", AncientEmbraceGameTests::perfect),
                Map.entry("ancient_embrace_heal", AncientEmbraceGameTests::heal),
                Map.entry("ancient_embrace_resources", AncientEmbraceGameTests::resources),
                Map.entry("ancient_embrace_cooldown", AncientEmbraceGameTests::cooldown),
                Map.entry("ancient_embrace_expiry_save", AncientEmbraceGameTests::expirySave),
                Map.entry("ancient_embrace_direction_bypass", AncientEmbraceGameTests::directionBypass),
                Map.entry("ancient_embrace_disable", AncientEmbraceGameTests::disable),
                Map.entry("ancient_embrace_creative", AncientEmbraceGameTests::creative));
        tests.forEach((name, test) -> FUNCTIONS.register(name, () -> test)); FUNCTIONS.register(bus);
        bus.addListener((RegisterGameTestsEvent event) -> {
            var env = event.registerEnvironment(LogosDimension.id("ancient_embrace"), new TestEnvironmentDefinition.AllOf());
            for (var name : tests.keySet()) event.registerTest(LogosDimension.id(name), new FunctionGameTestInstance(
                    ResourceKey.create(Registries.TEST_FUNCTION, LogosDimension.id(name)), new TestData<>(env,
                    Identifier.withDefaultNamespace("empty"), 200, 0, true, Rotation.NONE, false, 1, 1, false, 16)));
        });
    }
    private record Fixture(GameTestHelper h, ServerPlayer p, LivingEntity attacker) implements AutoCloseable {
        static Fixture create(GameTestHelper h) {
            var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "shield-test"), false);
            var p = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
            var connection = new Connection(PacketFlow.SERVERBOUND); new EmbeddedChannel(connection);
            h.getLevel().getServer().getPlayerList().placeNewPlayer(connection, p, cookie);
            p.connection.markClientLoaded(); p.setGameMode(GameType.SURVIVAL); p.tickCount = 1; p.setNoGravity(true);
            p.setPos(h.absoluteVec(new Vec3(4, 10, 4))); p.setYRot(-90); p.setYHeadRot(-90); p.setXRot(0);
            p.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(AncientEmbraceItem.ITEM.get()));
            LogosNatureBridge.mana(p, 20);
            var attacker = EntityType.IRON_GOLEM.create(h.getLevel(), EntitySpawnReason.COMMAND);
            attacker.setNoAi(true); attacker.setNoGravity(true); attacker.setPos(p.position().add(3, 0, 0)); h.getLevel().addFreshEntity(attacker);
            return new Fixture(h, p, attacker);
        }
        ItemStack shield() { return p.getOffhandItem(); }
        void raise(int ticks) {
            p.stopUsingItem(); p.startUsingItem(InteractionHand.OFF_HAND);
            double mana = LogosNatureBridge.mana(p); float health = p.getHealth(); var pos = p.position();
            for (int i = 0; i < ticks; i++) { p.doTick(); p.setPos(pos); p.setDeltaMovement(Vec3.ZERO); }
            p.setYRot(-90); p.setYHeadRot(-90); p.setXRot(0);
            LogosNatureBridge.mana(p, mana); p.setHealth(health);
        }
        void hit() {
            p.invulnerableTime = 0;
            p.hurtServer(h.getLevel(), h.getLevel().damageSources().mobAttack(attacker), 4);
        }
        int leaves() { return shield().getOrDefault(AncientEmbraceItem.CHARGE.get(), AncientEmbraceCharge.EMPTY).activeLeaves(h.getLevel().getGameTime()); }
        void full() { raise(5); for (int i = 0; i < 5; i++) hit(); }
        @Override public void close() { p.stopUsingItem(); attacker.discard(); h.getLevel().getServer().getPlayerList().remove(p); }
    }
    private static void template(GameTestHelper h) {
        try (var f = Fixture.create(h)) {
            var ours = f.shield().get(DataComponents.BLOCKS_ATTACKS); var vanilla = new ItemStack(Items.SHIELD).get(DataComponents.BLOCKS_ATTACKS);
            h.assertTrue(ours.blockDelayTicks() == 0 && vanilla.blockDelayTicks() == 5, "只有古木之拥应取消五刻前摇");
            h.assertTrue(ours.damageReductions().equals(vanilla.damageReductions()) && ours.itemDamage().equals(vanilla.itemDamage())
                    && ours.disableCooldownScale() == vanilla.disableCooldownScale() && ours.bypassedBy().equals(vanilla.bypassedBy()), "原版方向、绕盾或禁盾参数改变");
            h.assertTrue(f.shield().getMaxDamage() == 336, "未沿用原版三百三十六耐久");
        } h.succeed();
    }
    private static void instant(GameTestHelper h) {
        try (var f = Fixture.create(h)) {
            f.raise(0); f.hit(); h.assertTrue(f.p.getHealth() == 20 && f.leaves() == 2, "举盾当刻未格挡或未奖励两片叶");
            f.p.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD)); f.raise(0); f.hit();
            h.assertTrue(f.p.getHealth() == 16, "原版盾前摇被意外取消");
        } h.succeed();
    }
    private static void perfect(GameTestHelper h) {
        for (int ticks : new int[]{0, 4, 5, 10}) try (var f = Fixture.create(h)) {
            f.raise(ticks); f.hit();
            h.assertTrue(f.leaves() == (ticks < 5 ? 2 : 1), "弹反窗口错误：" + ticks + "刻亮" + f.leaves() + "片叶");
            h.assertTrue(f.shield().getDamageValue() == 5 && LogosNatureBridge.mana(f.p) == 20, "普通格挡改变原版耐久或扣魔力");
        }
        try (var f = Fixture.create(h)) {
            f.raise(0); f.hit(); f.hit(); f.hit();
            h.assertTrue(f.leaves() == 5, "三次极限格挡应封顶五片叶");
            var before = f.shield().get(AncientEmbraceItem.CHARGE.get()); f.hit();
            h.assertTrue(f.shield().get(AncientEmbraceItem.CHARGE.get()).equals(before), "满充格挡不应继续蓄积或刷新到期时间");
        } h.succeed();
    }
    private static void heal(GameTestHelper h) {
        try (var f = Fixture.create(h)) {
            f.p.setHealth(10); f.full(); h.assertTrue(f.leaves() == 5 && f.p.getHealth() == 10, "五次格挡未蓄满或提前回血");
            f.p.releaseUsingItem();
            h.assertTrue(f.p.getHealth() == 14 && LogosNatureBridge.mana(f.p) == 15 && f.leaves() == 0, "收盾没有恢复四点并消耗五魔力与充能");
            h.assertTrue(f.p.getData(AncientEmbraceItem.COOLDOWN) == h.getLevel().getGameTime() + 160, "治疗没有八秒冷却");
            f.raise(0); f.p.releaseUsingItem(); h.assertTrue(f.p.getHealth() == 14, "空充能重复治疗");
        } h.succeed();
    }
    private static void resources(GameTestHelper h) {
        try (var f = Fixture.create(h)) {
            f.full(); f.p.releaseUsingItem(); h.assertTrue(f.leaves() == 5 && LogosNatureBridge.mana(f.p) == 20, "满血消耗了生机或魔力");
            f.p.setHealth(10); LogosNatureBridge.mana(f.p, 4); f.raise(0); f.p.releaseUsingItem();
            h.assertTrue(f.leaves() == 5 && f.p.getHealth() == 10 && LogosNatureBridge.mana(f.p) == 4, "魔力不足消耗了资源");
            LogosNatureBridge.mana(f.p, 5); f.raise(0); f.p.releaseUsingItem();
            h.assertTrue(f.leaves() == 0 && f.p.getHealth() == 14 && LogosNatureBridge.mana(f.p) == 0, "恰好五魔力不能治疗");
        } h.succeed();
    }
    private static void cooldown(GameTestHelper h) {
        try (var f = Fixture.create(h)) {
            f.p.setHealth(10); f.full(); f.p.releaseUsingItem();
            f.p.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(AncientEmbraceItem.ITEM.get()));
            f.raise(0); f.hit(); h.assertTrue(f.leaves() == 0, "换盾绕过冷却蓄能");
            f.p.setData(AncientEmbraceItem.COOLDOWN, h.getLevel().getGameTime()); f.hit();
            h.assertTrue(f.leaves() == 2, "冷却结束不能重新蓄能");
        } h.succeed();
    }
    private static void expirySave(GameTestHelper h) {
        try (var f = Fixture.create(h)) {
            f.full(); var before = f.shield().get(AncientEmbraceItem.CHARGE.get());
            var ops = h.getLevel().registryAccess().createSerializationContext(JsonOps.INSTANCE);
            var saved = ItemStack.CODEC.encodeStart(ops, f.shield()).getOrThrow();
            var loaded = ItemStack.CODEC.parse(ops, saved).getOrThrow();
            h.assertTrue(before.equals(loaded.get(AncientEmbraceItem.CHARGE.get())), "物品保存丢失叶片或刷新绝对期限");
            loaded.set(AncientEmbraceItem.CHARGE.get(), new AncientEmbraceCharge(5, h.getLevel().getGameTime()));
            f.p.setItemInHand(InteractionHand.OFF_HAND, loaded); f.p.setHealth(10); f.raise(0); f.p.releaseUsingItem();
            h.assertTrue(f.p.getHealth() == 10 && LogosNatureBridge.mana(f.p) == 20 && f.leaves() == 0, "过期充能仍能治疗");
        } h.succeed();
    }
    private static void directionBypass(GameTestHelper h) {
        try (var f = Fixture.create(h)) {
            f.raise(0); f.attacker.setPos(f.p.position().add(-3, 0, 0)); f.hit();
            h.assertTrue(f.leaves() == 0 && f.p.getHealth() == 16, "背后攻击仍被格挡或充能");
            f.p.invulnerableTime = 0; f.attacker.setPos(f.p.position().add(3, 0, 0));
            f.p.hurtServer(h.getLevel(), h.getLevel().damageSources().sonicBoom(f.attacker), 1);
            h.assertTrue(f.leaves() == 0 && f.p.getHealth() == 15, "绕盾伤害仍充能");
        } h.succeed();
    }
    private static void disable(GameTestHelper h) {
        try (var f = Fixture.create(h)) {
            f.p.setHealth(10); f.full();
            f.shield().get(DataComponents.BLOCKS_ATTACKS).disable(h.getLevel(), f.p, 5, f.shield());
            h.assertTrue(!f.p.isUsingItem() && f.p.getCooldowns().isOnCooldown(f.shield()), "未保留原版禁盾");
            h.assertTrue(f.p.getHealth() == 10 && f.leaves() == 5 && LogosNatureBridge.mana(f.p) == 20, "强制禁盾误触治疗");
        } h.succeed();
    }
    private static void creative(GameTestHelper h) {
        try (var f = Fixture.create(h)) {
            f.p.setHealth(10); f.full(); f.p.setGameMode(GameType.CREATIVE); LogosNatureBridge.mana(f.p, 0); f.p.releaseUsingItem();
            h.assertTrue(f.p.getHealth() == 14 && f.leaves() == 0 && LogosNatureBridge.mana(f.p) == 0, "创造模式治疗没有免魔力");
        } h.succeed();
    }
    private AncientEmbraceGameTests() {}
}
