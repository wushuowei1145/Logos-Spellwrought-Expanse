package dev.logos.dimension.gametest;

import com.mojang.authlib.GameProfile;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.item.JinghongSwordItem;
import dev.logos.dimension.jinghong.SwordArray;
import dev.logos.dimension.jinghong.SwordFlight;
import dev.logos.dimension.longsword.LongswordCombat;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.*;
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
import net.minecraft.world.entity.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 真实服务端检查惊鸿剑阵：追击的伤害与激活、弹剑和普通格挡飞几把、掷剑协同与清除激活、万剑归宗（docs/JINGHONG.md）。 */
public final class JinghongGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS = DeferredRegister.create(Registries.TEST_FUNCTION, LogosDimension.ID);
    public static void register(IEventBus bus) {
        var tests = Map.<String, Consumer<GameTestHelper>>ofEntries(
                Map.entry("jinghong_follow", JinghongGameTests::follow),
                Map.entry("jinghong_block", JinghongGameTests::block),
                Map.entry("jinghong_parry", JinghongGameTests::parry),
                Map.entry("jinghong_throw_guard", JinghongGameTests::throwGuard),
                Map.entry("jinghong_volley", JinghongGameTests::volley),
                Map.entry("jinghong_finale", JinghongGameTests::finale),
                Map.entry("jinghong_miss", JinghongGameTests::miss));
        tests.forEach((n, t) -> FUNCTIONS.register(n, () -> t));
        FUNCTIONS.register(bus);
        bus.addListener((RegisterGameTestsEvent e) -> {
            var env = e.registerEnvironment(LogosDimension.id("jinghong"), new TestEnvironmentDefinition.AllOf());
            for (var n : tests.keySet()) e.registerTest(LogosDimension.id(n), new FunctionGameTestInstance(
                    ResourceKey.create(Registries.TEST_FUNCTION, LogosDimension.id(n)), new TestData<>(env,
                    Identifier.withDefaultNamespace("empty"), 160, 0, true, Rotation.NONE, false, 1, 1, false, 16)));
        });
    }

    private static final Map<Long, Integer> FORCED = new HashMap<>();
    /** 一名真实玩家（嵌入连接）拿着惊鸿面朝 +X，前方 2.3 格一只不动的铁傀儡（100 血、不怕击退）。 */
    private static final class Fixture {
        final GameTestHelper h; final ServerPlayer p; final LivingEntity target; final Vec3 origin; final List<Long> chunks = new ArrayList<>(); boolean closed;
        Fixture(GameTestHelper h) {
            this.h = h;
            var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "jinghong-test"), false);
            p = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
            var connection = new Connection(PacketFlow.SERVERBOUND); new EmbeddedChannel(connection);
            h.getLevel().getServer().getPlayerList().placeNewPlayer(connection, p, cookie); p.connection.markClientLoaded();
            p.setGameMode(GameType.SURVIVAL); p.tickCount = 1; p.setNoGravity(true);
            origin = h.absoluteVec(new Vec3(4, 10, 4));
            int cx = BlockPos.containing(origin).getX() >> 4, cz = BlockPos.containing(origin).getZ() >> 4;
            for (int x = cx - 1; x <= cx + 1; x++) for (int z = cz - 1; z <= cz + 1; z++) {
                long key = new net.minecraft.world.level.ChunkPos(x, z).pack(); chunks.add(key);
                if (FORCED.merge(key, 1, Integer::sum) == 1) h.getLevel().setChunkForced(x, z, true);
            }
            p.setPos(origin); p.setYRot(-90); p.setYHeadRot(-90); p.setXRot(0);
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(JinghongSwordItem.ITEM.get()));
            target = mob(origin.add(2.3, 0, 0));
            h.onEachTick(() -> { if (closed) return; p.tickCount = 1; p.doTick(); p.setYRot(-90); p.setYHeadRot(-90); p.setXRot(0); });
        }
        LivingEntity mob(Vec3 position) {
            var e = EntityType.IRON_GOLEM.create(h.getLevel(), EntitySpawnReason.COMMAND);
            e.setNoAi(true); e.setNoGravity(true); e.setPos(position); h.getLevel().addFreshEntity(e); return e;
        }
        void at(int tick, Runnable action) { h.runAfterDelay(tick, () -> { try { action.run(); } catch (RuntimeException | Error e) { close(); throw e; } }); }
        int busy() { int n = 0; for (int i = 0; i < SwordFlight.FAN; i++) if (SwordArray.busy(p, i)) n++; return n; }
        void use() { p.getMainHandItem().use(h.getLevel(), p, InteractionHand.MAIN_HAND); }
        void close() {
            if (closed) return; closed = true; SwordArray.testChance(p, null); target.discard();
            h.getLevel().getServer().getPlayerList().remove(p);
            for (long key : chunks) {
                int count = FORCED.get(key) - 1;
                if (count == 0) { FORCED.remove(key); var c = net.minecraft.world.level.ChunkPos.unpack(key); h.getLevel().setChunkForced(c.x(), c.z(), false); }
                else FORCED.put(key, count);
            }
        }
        void success() { close(); h.succeed(); }
    }

    /** 左键砍中：概率定为 1 时五把剑都追击（错开两刻出发），每把 3 点，砍中这一刀 6 点；被触发的五把都激活。 */
    private static void follow(GameTestHelper h) {
        var f = new Fixture(h);
        f.at(30, () -> {
            h.assertTrue("idle".equals(LongswordCombat.move(f.p)), "尚未完成拔剑");
            SwordArray.testChance(f.p, 1.0);
            LongswordCombat.attack(f.p);
        });
        f.at(40, () -> h.assertTrue(f.busy() == SwordFlight.FAN, "砍中后五把剑没有都飞出去：" + f.busy()));
        // 最后一把在 2×4 刻后出发，再过 FIRST_HIT 刻穿过目标
        f.at(36 + 8 + SwordFlight.FIRST_HIT + 2, () -> {
            float lost = 100 - f.target.getHealth();
            h.assertTrue(Math.abs(lost - (6 + SwordFlight.FAN * 3)) < .01, "追击伤害不对（应为砍中 6 + 五把各 3）：" + lost);
            h.assertTrue(SwordArray.activeCount(f.p) == SwordFlight.FAN, "被触发的剑没有激活：" + SwordArray.activeCount(f.p));
            f.success();
        });
    }

    /** 弹剑飞出 2–5 把；普通格挡按概率飞出一把。 */
    private static void block(GameTestHelper h) {
        var f = new Fixture(h);
        f.at(30, () -> {
            SwordArray.blocked(f.p, f.p.damageSources().mobAttack(f.target), true);
            h.assertTrue(f.busy() >= SwordArray.PARRY_MIN && f.busy() <= SwordArray.PARRY_MAX, "弹剑后反击的剑数不在 2–5：" + f.busy());
        });
        f.at(30 + SwordFlight.duration(1) + 2 * 5 + 2, () -> {
            h.assertTrue(f.busy() == 0, "反击的剑没有全部回位");
            h.assertTrue(f.target.getHealth() < 100, "反击没有伤到攻击方");
            SwordArray.testChance(f.p, 1.0);
            SwordArray.blocked(f.p, f.p.damageSources().mobAttack(f.target), false);
            h.assertTrue(f.busy() == 1, "普通格挡概率为 1 时应只飞一把：" + f.busy());
            SwordArray.testChance(f.p, 0.0);
        });
        f.at(30 + 2 * SwordFlight.duration(1) + 2 * 5 + 4, () -> {
            SwordArray.blocked(f.p, f.p.damageSources().mobAttack(f.target), false);
            h.assertTrue(f.busy() == 0, "普通格挡概率为 0 时不该飞剑");
            f.success();
        });
    }

    /** 真实的弹剑：右键架剑，下一刻挨靶子一拳，转入弹剑、不掉血，身后 2–5 把剑反击。 */
    private static void parry(GameTestHelper h) {
        var f = new Fixture(h);
        f.at(30, () -> { h.assertTrue("idle".equals(LongswordCombat.move(f.p)), "尚未完成拔剑"); f.use(); });
        f.at(31, () -> {
            h.assertTrue("guard".equals(LongswordCombat.move(f.p)), "右键没有进入格挡：" + LongswordCombat.move(f.p));
            h.assertTrue(f.p.isBlocking(), "进入格挡时原版没有在格挡");
            float health = f.p.getHealth();
            f.p.hurtServer(h.getLevel(), f.p.damageSources().mobAttack(f.target), 6);
            h.assertTrue("parry".equals(LongswordCombat.move(f.p)), "架剑之初挨一拳没有弹剑：" + LongswordCombat.move(f.p));
            h.assertTrue(f.p.getHealth() == health, "弹剑还掉了血");
            h.assertTrue(f.busy() >= SwordArray.PARRY_MIN, "弹剑后没有剑反击：" + f.busy());
            f.success();
        });
    }

    /** 格挡中按左键掷剑：掷剑时挡不住；右键一直按着，掷剑播完自动回到格挡。 */
    private static void throwGuard(GameTestHelper h) {
        var f = new Fixture(h);
        f.at(30, f::use);
        f.at(31, () -> { h.assertTrue("guard".equals(LongswordCombat.move(f.p)), "没有进入格挡"); LongswordCombat.attack(f.p); });
        f.at(32, () -> {
            h.assertTrue(JinghongSwordItem.THROW.equals(LongswordCombat.move(f.p)), "格挡中按左键没有掷剑：" + LongswordCombat.move(f.p));
            h.assertTrue(f.p.isUsingItem(), "掷剑时不该停掉原版的使用（否则回不到格挡）");
            float health = f.p.getHealth();
            f.p.hurtServer(h.getLevel(), f.p.damageSources().mobAttack(f.target), 4);
            h.assertTrue(f.p.getHealth() < health, "掷剑时还挡住了");
        });
        f.at(31 + 20, () -> { h.assertTrue("guard".equals(LongswordCombat.move(f.p)), "掷剑后没有回到格挡：" + LongswordCombat.move(f.p)); f.success(); });
    }

    /** 两把激活时掷剑：手里的剑 6 点，两把剑各穿两次、每次 3 点再加物理；飞完之后激活全部清除。 */
    private static void volley(GameTestHelper h) {
        var f = new Fixture(h);
        f.at(30, () -> { SwordArray.activateForTest(f.p, 2); SwordArray.throwSword(f.p); });
        f.at(31, () -> h.assertTrue(f.busy() == 2, "激活的两把没有协同出手：" + f.busy()));
        f.at(30 + 2 + SwordFlight.duration(SwordArray.VOLLEY_PASSES) + 1, () -> {
            float lost = 100 - f.target.getHealth();
            h.assertTrue(lost >= 6 + 2 * SwordArray.VOLLEY_PASSES * 3 + .01, "协同出手的伤害不够（还应有物理伤害）：" + lost);
            h.assertTrue(SwordArray.activeCount(f.p) == 0, "掷剑之后激活没有清除：" + SwordArray.activeCount(f.p));
            h.assertTrue(SwordArray.settled(f.p), "剑阵没有收回");
            f.success();
        });
    }

    /** 五把都激活时掷剑是万剑归宗：虚影剑十下、五剑一贯，结束后清除激活。 */
    private static void finale(GameTestHelper h) {
        var f = new Fixture(h);
        f.at(30, () -> { SwordArray.activateForTest(f.p, SwordFlight.FAN); SwordArray.throwSword(f.p); });
        f.at(31, () -> h.assertTrue(f.busy() == SwordFlight.FAN, "万剑归宗时五把剑都该出手"));
        f.at(30 + SwordFlight.PLUNGE_HIT - 1, () -> {
            float lost = 100 - f.target.getHealth();
            double expect = 6 + SwordFlight.PHANTOM_HITS * SwordArray.PHANTOM * 6;
            h.assertTrue(Math.abs(lost - expect) < .01, "贯下之前应是掷剑加十下虚影剑：" + lost + " 应为 " + expect);
        });
        f.at(30 + SwordFlight.FINALE_HOME + 1, () -> {
            float lost = 100 - f.target.getHealth();
            h.assertTrue(lost >= 6 + SwordFlight.PHANTOM_HITS * SwordArray.PHANTOM * 6 + SwordArray.PLUNGE * 6, "五剑一贯没有结算：" + lost);
            h.assertTrue(SwordArray.activeCount(f.p) == 0, "万剑归宗之后激活没有清除");
            h.assertTrue(SwordArray.settled(f.p), "剑阵没有收回");
            f.success();
        });
    }

    /** 没有目标时掷剑：剑飞出去再回来，激活保留。 */
    private static void miss(GameTestHelper h) {
        var f = new Fixture(h);
        f.target.discard();
        f.at(30, () -> { SwordArray.activateForTest(f.p, 3); SwordArray.throwSword(f.p); h.assertTrue(f.busy() == 0, "没有目标时身后的剑不该出手"); });
        f.at(45, () -> { h.assertTrue(SwordArray.activeCount(f.p) == 3, "没有目标时激活不该清除：" + SwordArray.activeCount(f.p)); f.success(); });
    }
}
