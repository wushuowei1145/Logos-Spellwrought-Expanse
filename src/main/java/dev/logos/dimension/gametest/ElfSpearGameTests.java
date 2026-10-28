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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.*;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 精灵长矛实际调用原版射线与动能组件，验证成功物理命中之后才发生的印记与生命追加。 */
public final class ElfSpearGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS = DeferredRegister.create(Registries.TEST_FUNCTION, LogosDimension.ID);
    public static void register(IEventBus bus) {
        var tests = Map.<String, Consumer<GameTestHelper>>ofEntries(
                Map.entry("elf_spear_stats", ElfSpearGameTests::stats),
                Map.entry("elf_spear_jab", ElfSpearGameTests::jabMark),
                Map.entry("elf_spear_burst", ElfSpearGameTests::burst),
                Map.entry("elf_spear_no_mana", ElfSpearGameTests::noMana),
                Map.entry("elf_spear_plain_charge", ElfSpearGameTests::plainCharge),
                Map.entry("elf_spear_mark_owner_expiry", ElfSpearGameTests::markRules),
                Map.entry("elf_spear_vulnerability", ElfSpearGameTests::vulnerability),
                Map.entry("elf_spear_resistance", ElfSpearGameTests::resistance),
                Map.entry("elf_spear_range_wall", ElfSpearGameTests::rangeWall),
                Map.entry("elf_spear_blocked", ElfSpearGameTests::blocked),
                Map.entry("elf_spear_mark_save", ElfSpearGameTests::saveMark),
                Map.entry("elf_spear_many", ElfSpearGameTests::many),
                Map.entry("elf_spear_offhand_delay", ElfSpearGameTests::offhandDelay),
                Map.entry("elf_spear_guard_reverse", ElfSpearGameTests::guardReverse),
                Map.entry("elf_spear_guard_no_charge", ElfSpearGameTests::guardNoCharge),
                Map.entry("elf_spear_guard_break", ElfSpearGameTests::guardBreak));
        tests.forEach((name, test) -> FUNCTIONS.register(name, () -> test)); FUNCTIONS.register(bus);
        bus.addListener((RegisterGameTestsEvent event) -> {
            var env = event.registerEnvironment(LogosDimension.id("elf_spear"), new TestEnvironmentDefinition.AllOf());
            for (var name : tests.keySet()) event.registerTest(LogosDimension.id(name), new FunctionGameTestInstance(
                    ResourceKey.create(Registries.TEST_FUNCTION, LogosDimension.id(name)), new TestData<>(env,
                    Identifier.withDefaultNamespace("empty"), 200, 0, true, Rotation.NONE, false, 1, 1, false, 16)));
        });
    }

    /** 仅把服务端已知移动速度设为可控值；射线、距离、起手、伤害、附魔与命中均走实际原版组件。 */
    private static final class MovingPlayer extends ServerPlayer {
        Vec3 known = Vec3.ZERO;
        MovingPlayer(ServerLevel level, CommonListenerCookie cookie) { super(level.getServer(), level, cookie.gameProfile(), cookie.clientInformation()); }
        @Override public Vec3 getKnownMovement() { return known; }
        @Override public Vec3 getKnownSpeed() { return known; }
    }
    private static MovingPlayer player(GameTestHelper h) {
        var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "spear-test"), false);
        var p = new MovingPlayer(h.getLevel(), cookie);
        var connection = new Connection(PacketFlow.SERVERBOUND); new EmbeddedChannel(connection);
        h.getLevel().getServer().getPlayerList().placeNewPlayer(connection, p, cookie);
        p.connection.markClientLoaded(); p.setGameMode(GameType.SURVIVAL); p.setNoGravity(true);
        // GameTest 手动推进实体刻不经过正常计数器；避开 tickCount=0 的岛下影雾全量同步。
        // 模拟连接没有协商附属网络通道，不能向它发送这项与武器无关的登录状态。
        p.tickCount = 1;
        p.setPos(h.absoluteVec(new Vec3(4, 10, 4))); p.setYRot(-90); p.setXRot(0);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ElfSpearItem.ITEM.get()));
        for (int i = 0; i < 20; i++) p.doTick();
        p.setYHeadRot(-90); LogosNatureBridge.mana(p, 20);
        return p;
    }
    private record Fixture(GameTestHelper h, MovingPlayer p, LivingEntity target) implements AutoCloseable {
        static Fixture create(GameTestHelper h) {
            var p = player(h);
            var target = EntityType.IRON_GOLEM.create(h.getLevel(), EntitySpawnReason.COMMAND);
            target.setNoAi(true); target.setNoGravity(true); target.setPos(p.position().add(3, 0, 0));
            h.getLevel().addFreshEntity(target);
            return new Fixture(h, p, target);
        }
        void jab() {
            p.stopUsingItem(); p.known = Vec3.ZERO;
            // 同一世界刻内补齐攻击冷却，不把重复模拟刻产生的回血/回魔或实体推挤混入单次攻击费用。
            double mana = LogosNatureBridge.mana(p); var origin = p.position();
            for (int i = 0; i < 14; i++) { p.setDeltaMovement(Vec3.ZERO); p.doTick(); p.setPos(origin); }
            LogosNatureBridge.mana(p, mana);
            p.setYRot(-90); p.setYHeadRot(-90); p.setXRot(0);
            target.invulnerableTime = 0; target.setDeltaMovement(Vec3.ZERO);
            p.getMainHandItem().get(DataComponents.PIERCING_WEAPON).attack(p, EquipmentSlot.MAINHAND);
        }
        void resetTarget() { target.setHealth(100); target.invulnerableTime = 0; target.setDeltaMovement(Vec3.ZERO); }
        void charge(double speed) { charge(speed, InteractionHand.MAIN_HAND, 12); }
        void charge(double speed, InteractionHand hand, int ticks) {
            p.setYRot(-90); p.setYHeadRot(-90); p.setXRot(0);
            p.stopUsingItem(); p.startUsingItem(hand); p.known = new Vec3(speed / 20, 0, 0);
            var stack = p.getItemInHand(hand);
            stack.get(DataComponents.KINETIC_WEAPON).damageEntities(stack, 72000 - ticks, p, hand.asEquipmentSlot());
        }
        boolean marked() { return target.hasData(NatureAttachments.SPROUT_MARK) && target.getData(NatureAttachments.SPROUT_MARK).belongsTo(p.getUUID(), h.getLevel().getGameTime()); }
        @Override public void close() { target.discard(); p.stopUsingItem(); h.getLevel().getServer().getPlayerList().remove(p); }
    }
    private static void health(GameTestHelper h, LivingEntity target, double expected) {
        h.assertTrue(Math.abs(target.getHealth() - expected) < .001, "生命值应为 " + expected + "，实际 " + target.getHealth());
    }
    private static void stats(GameTestHelper h) {
        try (var f = Fixture.create(h)) {
            h.assertTrue(Math.abs(f.p.getAttributeValue(Attributes.ATTACK_DAMAGE) - 4) < .0001, "不是钻石矛四点戳刺伤害");
            h.assertTrue(Math.abs(f.p.getCurrentItemAttackStrengthDelay() - 13) < .001, "不是木矛十三刻攻速");
            h.assertTrue(f.p.getMainHandItem().get(DataComponents.KINETIC_WEAPON).equals(new ItemStack(Items.IRON_SPEAR).get(DataComponents.KINETIC_WEAPON)), "冲撞参数偏离铁长矛");
            h.assertTrue(f.p.getMainHandItem().is(net.minecraft.tags.ItemTags.SPEARS), "缺少原版长矛标签");
            f.jab(); h.assertTrue(f.p.cannotAttackWithItem(f.p.getMainHandItem(), 0), "攻击后冷却未重置");
            for (int i = 0; i < 13; i++) f.p.doTick();
            h.assertTrue(!f.p.cannotAttackWithItem(f.p.getMainHandItem(), 0), "十三刻后攻击未恢复");
        } h.succeed();
    }
    private static void jabMark(GameTestHelper h) {
        try (var f = Fixture.create(h)) {
            f.jab(); health(h, f.target, 96);
            h.assertTrue(f.marked(), "左键没有种下自己的印记");
            h.assertTrue(f.target.getData(NatureAttachments.SPROUT_MARK).expiresAt() == h.getLevel().getGameTime() + 120, "印记不是六秒");
            h.assertTrue(LogosNatureBridge.mana(f.p) == 20 && !f.target.hasEffect(LogosNatureBridge.lifeVulnerability().orElseThrow()), "普通戳刺扣魔力或提前施加易伤");
        } h.succeed();
    }
    private static void burst(GameTestHelper h) {
        for (int speed : new int[]{10, 20}) try (var f = Fixture.create(h)) {
            f.jab(); f.resetTarget(); f.charge(speed);
            // 原版 0.95 是 float；20 格/秒时乘积略低于 19，向下取整后物理伤害为 19。
            health(h, f.target, speed == 10 ? 84 : 75);
            h.assertTrue(LogosNatureBridge.mana(f.p) == 15 && !f.marked(), "触发没有恰好扣五魔力和印记");
            var effect = f.target.getEffect(LogosNatureBridge.lifeVulnerability().orElseThrow());
            h.assertTrue(effect != null && effect.getAmplifier() == 0 && effect.getDuration() == 80, "易伤不是一级四秒");
            f.resetTarget(); f.charge(10); health(h, f.target, 90);
            h.assertTrue(LogosNatureBridge.mana(f.p) == 15, "无印记重复扣魔力");
        } h.succeed();
    }
    private static void noMana(GameTestHelper h) {
        try (var f = Fixture.create(h)) {
            f.jab(); f.resetTarget(); LogosNatureBridge.mana(f.p, 4); f.charge(10);
            health(h, f.target, 90);
            h.assertTrue(f.marked() && LogosNatureBridge.mana(f.p) == 4, "魔力不足不应扣资源");
            h.assertTrue(!f.target.hasEffect(LogosNatureBridge.lifeVulnerability().orElseThrow()), "魔力不足仍施加易伤");
            f.resetTarget(); LogosNatureBridge.mana(f.p, 5); f.charge(10); health(h, f.target, 84);
            h.assertTrue(LogosNatureBridge.mana(f.p) == 0 && !f.marked(), "恰好五点魔力不能触发");
        } h.succeed();
    }
    private static void plainCharge(GameTestHelper h) {
        try (var f = Fixture.create(h)) {
            f.charge(10); health(h, f.target, 90);
            h.assertTrue(!f.marked() && LogosNatureBridge.mana(f.p) == 20, "普通冲撞自行种印或扣魔力");
        } h.succeed();
    }
    private static void markRules(GameTestHelper h) {
        try (var f = Fixture.create(h)) {
            for (var mark : new SproutMark[]{new SproutMark(UUID.randomUUID(), h.getLevel().getGameTime() + 120), new SproutMark(f.p.getUUID(), h.getLevel().getGameTime())}) {
                f.target.setData(NatureAttachments.SPROUT_MARK, mark); f.resetTarget(); f.charge(10); health(h, f.target, 90);
                h.assertTrue(LogosNatureBridge.mana(f.p) == 20, "消耗了他人或到期印记的魔力");
            }
        } h.succeed();
    }
    private static void vulnerability(GameTestHelper h) {
        try (var f = Fixture.create(h)) {
            var effect = LogosNatureBridge.lifeVulnerability().orElseThrow();
            for (int amplifier = 0; amplifier <= 1; amplifier++) {
                f.target.addEffect(new MobEffectInstance(effect, 200, amplifier)); f.jab(); f.resetTarget(); f.charge(10);
                health(h, f.target, amplifier == 0 ? 82.8 : 81);
                h.assertTrue(f.target.getEffect(effect).getAmplifier() == amplifier, "覆盖了更强易伤或逐次叠层");
            }
        } h.succeed();
    }
    private static void resistance(GameTestHelper h) {
        try (var f = Fixture.create(h)) {
            var attribute = BuiltInRegistries.ATTRIBUTE.get(Identifier.fromNamespaceAndPath("logos", "life_spell_resistance")).orElseThrow();
            f.target.getAttribute(attribute).setBaseValue(.5);
            f.jab(); f.resetTarget(); f.charge(10); health(h, f.target, 87);
        } h.succeed();
    }
    private static void rangeWall(GameTestHelper h) {
        try (var f = Fixture.create(h)) {
            var wall = BlockPos.containing(f.p.position().add(1, 1.5, 0));
            h.getLevel().setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState()); f.jab();
            h.assertTrue(f.target.getHealth() == 100, "穿墙戳刺造成伤害：" + f.target.getHealth());
            h.assertTrue(!f.marked(), "穿墙种下印记"); h.getLevel().removeBlock(wall, false);
            for (double distance : new double[]{.3, 8}) {
                f.target.setPos(f.p.position().add(distance, 0, 0)); f.jab();
                h.assertTrue(f.target.getHealth() == 100, "距离 " + distance + " 仍造成伤害：" + f.target.getHealth());
                h.assertTrue(!f.marked(), "贴脸或超出射程仍能种印");
            }
        } h.succeed();
    }
    private static void blocked(GameTestHelper h) {
        try (var f = Fixture.create(h)) {
            f.target.setInvulnerable(true); f.jab(); health(h, f.target, 100);
            f.target.setData(NatureAttachments.SPROUT_MARK, new SproutMark(f.p.getUUID(), h.getLevel().getGameTime() + 120));
            f.charge(10); h.assertTrue(f.marked() && LogosNatureBridge.mana(f.p) == 20, "伤害被拒绝时消耗了印记");
            f.target.discard();
            var defender = player(h);
            try {
                defender.setPos(f.p.position().add(3, 0, 0)); defender.setYRot(90);
                defender.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.SHIELD)); defender.startUsingItem(InteractionHand.MAIN_HAND);
                for (int i = 0; i < 10; i++) defender.doTick();
                defender.setData(NatureAttachments.SPROUT_MARK, new SproutMark(f.p.getUUID(), h.getLevel().getGameTime() + 120));
                f.charge(10); health(h, defender, 20);
                h.assertTrue(defender.getData(NatureAttachments.SPROUT_MARK).active(h.getLevel().getGameTime()) && LogosNatureBridge.mana(f.p) == 20, "盾牌完全格挡仍触发生命伤害");
            } finally { h.getLevel().getServer().getPlayerList().remove(defender); }
        } h.succeed();
    }
    private static void saveMark(GameTestHelper h) {
        try (var f = Fixture.create(h)) {
            f.jab(); var before = f.target.getData(NatureAttachments.SPROUT_MARK);
            var out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, h.getLevel().registryAccess()); f.target.saveWithoutId(out);
            f.target.removeData(NatureAttachments.SPROUT_MARK);
            f.target.load(TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), out.buildResult()));
            h.assertTrue(f.target.getData(NatureAttachments.SPROUT_MARK).equals(before), "保存重载刷新了期限或丢失归属");
        } h.succeed();
    }
    private static void many(GameTestHelper h) {
        try (var f = Fixture.create(h)) {
            var second = EntityType.IRON_GOLEM.create(h.getLevel(), EntitySpawnReason.COMMAND);
            second.setNoAi(true); second.setNoGravity(true); second.setPos(f.p.position().add(4.4, 0, 0)); h.getLevel().addFreshEntity(second);
            try {
                f.jab(); health(h, second, 96);
                f.resetTarget(); second.setHealth(100); second.invulnerableTime = 0; second.setDeltaMovement(Vec3.ZERO);
                f.charge(10); health(h, f.target, 84); health(h, second, 84);
                h.assertTrue(LogosNatureBridge.mana(f.p) == 10, "穿刺两名目标应分别消耗五魔力");
            } finally { second.discard(); }
        } h.succeed();
    }
    private static void offhandDelay(GameTestHelper h) {
        try (var f = Fixture.create(h)) {
            f.jab(); f.resetTarget(); f.p.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(ElfSpearItem.ITEM.get()));
            f.charge(10, InteractionHand.OFF_HAND, 11); health(h, f.target, 100);
            h.assertTrue(f.marked() && LogosNatureBridge.mana(f.p) == 20, "起手未完成就触发");
            f.charge(10, InteractionHand.OFF_HAND, 12); health(h, f.target, 84);
        } h.succeed();
    }
    private static void reverse(Fixture f) {
        f.p.stopUsingItem();
        f.p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(AncientEmbraceItem.ITEM.get()));
        f.p.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(ElfSpearItem.ITEM.get()));
        for (int i = 0; i < 20; i++) f.p.doTick();
        f.p.setYRot(-90); f.p.setYHeadRot(-90); f.p.setXRot(0); LogosNatureBridge.mana(f.p, 20);
        f.target.setPos(f.p.position().add(3, 0, 0));
    }
    private static void stabPacket(Fixture f) {
        f.p.connection.handlePlayerAction(new net.minecraft.network.protocol.game.ServerboundPlayerActionPacket(
                net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.STAB,
                BlockPos.ZERO, net.minecraft.core.Direction.DOWN));
    }
    private static void guardReverse(GameTestHelper h) {
        try (var f = Fixture.create(h)) {
            reverse(f); stabPacket(f); health(h, f.target, 96);
            h.assertTrue(f.marked() && !f.p.isUsingItem() && f.p.swingingArm == InteractionHand.OFF_HAND, "反手左键没有普通戳刺或挥错手");
            h.assertTrue(f.p.getAttributeValue(Attributes.ATTACK_DAMAGE) == 1 && f.p.getAttributeValue(Attributes.ATTACK_SPEED) == 4,
                    "副手矛属性残留为常驻加成");
            f.target.invulnerableTime = 0; stabPacket(f); health(h, f.target, 96);
            f.p.getMainHandItem().use(h.getLevel(), f.p, InteractionHand.MAIN_HAND);
            h.assertTrue(f.p.isBlocking() && f.p.getUseItem().is(AncientEmbraceItem.ITEM.get()), "反手右键未正常举盾");
            var origin = f.p.position();
            for (int i = 0; i < 14; i++) { f.p.setDeltaMovement(Vec3.ZERO); f.p.doTick(); f.p.setPos(origin); }
            f.p.setYRot(-90); f.p.setYHeadRot(-90); f.resetTarget(); f.target.setPos(origin.add(3, 0, 0));
            int elapsed = f.p.getTicksUsingItem(); double mana = LogosNatureBridge.mana(f.p);
            stabPacket(f); health(h, f.target, 96);
            h.assertTrue(f.p.isBlocking() && f.p.getTicksUsingItem() == elapsed && f.marked()
                    && LogosNatureBridge.mana(f.p) == mana && !f.target.hasEffect(LogosNatureBridge.lifeVulnerability().orElseThrow()),
                    "反手举盾戳刺中断盾、误耗印记或造成生命追加");
            h.assertTrue(f.p.getAttributeValue(Attributes.ATTACK_DAMAGE) == 1 && f.p.getAttributeValue(Attributes.ATTACK_SPEED) == 4,
                    "反手举盾戳刺留下攻击属性");
            f.target.invulnerableTime = 0; stabPacket(f); health(h, f.target, 96);
        } h.succeed();
    }
    private static void guardNoCharge(GameTestHelper h) {
        for (boolean swapped : new boolean[]{false, true}) try (var f = Fixture.create(h)) {
            if (swapped) reverse(f);
            else f.p.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(AncientEmbraceItem.ITEM.get()));
            var shieldHand = swapped ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
            var shield = f.p.getItemInHand(shieldHand);
            shield.use(h.getLevel(), f.p, shieldHand);
            var mark = new SproutMark(f.p.getUUID(), h.getLevel().getGameTime() + 120);
            f.target.setData(NatureAttachments.SPROUT_MARK, mark); double mana = LogosNatureBridge.mana(f.p);
            f.p.known = new Vec3(1, 0, 0);
            shield.onUseTick(h.getLevel(), f.p, 72000 - 40); health(h, f.target, 100);
            h.assertTrue(f.p.isBlocking() && f.target.getData(NatureAttachments.SPROUT_MARK).equals(mark)
                    && LogosNatureBridge.mana(f.p) == mana, "双持举盾错误触发动能冲撞或生命追加");
            var effects = shield.getOrDefault(DataComponents.USE_EFFECTS, net.minecraft.world.item.component.UseEffects.DEFAULT);
            var vanilla = new ItemStack(Items.SHIELD).getOrDefault(DataComponents.USE_EFFECTS, net.minecraft.world.item.component.UseEffects.DEFAULT);
            h.assertTrue(effects.equals(vanilla) && effects.speedMultiplier() < 1, "未保留原版举盾减速");
        } h.succeed();
    }
    private static void guardBreak(GameTestHelper h) {
        try (var f = Fixture.create(h)) {
            reverse(f); var shield = f.p.getMainHandItem();
            f.p.getOffhandItem().setDamageValue(f.p.getOffhandItem().getMaxDamage() - 1);
            stabPacket(f);
            h.assertTrue(f.p.getOffhandItem().isEmpty() && f.p.getMainHandItem() == shield
                    && shield.is(AncientEmbraceItem.ITEM.get()) && shield.getDamageValue() == 0,
                    "副手矛损毁误删或损坏主手盾");
        } h.succeed();
    }
    private ElfSpearGameTests() {}
}
