package dev.logos.dimension.gametest;

import com.mojang.authlib.GameProfile;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.dawnvow.DawnvowCharge;
import dev.logos.dimension.dawnvow.SwordWave;
import dev.logos.dimension.item.LongswordItem;
import dev.logos.runtime.PlayerMagic;
import dev.logos.runtime.SurfaceAura;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
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
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 曦誓的蓄力彩蛋：蓄力斩带出贴地剑气；按住 7 秒起站定、按刻扣魔力，15 秒蓄满冒金色火焰；
 * 蓄满放出的巨型剑气直接击杀低血量生物、穿过中等的、在高血量生物处爆开；魔力不够时退回普通蓄力。
 */
public final class DawnvowChargeGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS = DeferredRegister.create(Registries.TEST_FUNCTION, LogosDimension.ID);
    /** 拔剑（1.2 秒）之后开始蓄力的那一刻。 */
    private static final int USE = 30;

    public static void register(IEventBus bus) {
        var tests = Map.<String, Consumer<GameTestHelper>>of(
            "dawnvow_charge_small_wave", DawnvowChargeGameTests::smallWave,
            "dawnvow_charge_grand", DawnvowChargeGameTests::grand,
            "dawnvow_charge_out_of_mana", DawnvowChargeGameTests::outOfMana,
            "dawnvow_charge_carves", DawnvowChargeGameTests::carves,
            "dawnvow_charge_blessing", DawnvowChargeGameTests::blessing,
            "dawnvow_charge_blessed_wave", DawnvowChargeGameTests::blessedWave);
        tests.forEach((name, test) -> FUNCTIONS.register(name, () -> test));
        FUNCTIONS.register(bus);
        bus.addListener((RegisterGameTestsEvent event) -> {
            var env = event.registerEnvironment(LogosDimension.id("dawnvow_charge"), new TestEnvironmentDefinition.AllOf());
            for (var name : tests.keySet()) event.registerTest(LogosDimension.id(name), new FunctionGameTestInstance(
                ResourceKey.create(Registries.TEST_FUNCTION, LogosDimension.id(name)), new TestData<>(env,
                Identifier.withDefaultNamespace("empty"), 460, 0, true, Rotation.NONE, false, 1, 1, false, 16)));
        });
    }

    /** 一位拿着曦誓、面朝 +x、钉在原地的模拟玩家，脚下一条石头地面。 */
    private record Fixture(GameTestHelper h, ServerPlayer p, Vec3 origin) {
        static Fixture create(GameTestHelper h, double mana) {
            var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "dawnvow-charge"), false);
            var p = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
            var connection = new Connection(PacketFlow.SERVERBOUND);
            new EmbeddedChannel(connection);
            h.getLevel().getServer().getPlayerList().placeNewPlayer(connection, p, cookie);
            p.connection.markClientLoaded();
            p.setGameMode(GameType.SURVIVAL);
            p.tickCount = 1;
            Vec3 origin = h.absoluteVec(new Vec3(2.5, 4, 2.5));
            // 剑气贴地走，脚下铺一条 24 格长的石头地面
            for (int x = -2; x <= 22; x++) for (int z = -3; z <= 3; z++)
                h.getLevel().setBlockAndUpdate(BlockPos.containing(origin.add(x, -1, z)), Blocks.STONE.defaultBlockState());
            p.setPos(origin);
            look(p);
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(LongswordItem.DAWNVOW.get()));
            PlayerMagic.of(p).maxMana = Math.max(PlayerMagic.of(p).maxMana, mana);
            PlayerMagic.of(p).mana = mana;
            // 嵌入连接没有真实网络循环：逐刻推进玩家，并钉在原地朝 +x
            h.onEachTick(() -> { p.tickCount = 1; p.doTick(); p.setPos(origin); p.setDeltaMovement(Vec3.ZERO); look(p); });
            return new Fixture(h, p, origin);
        }

        private static void look(ServerPlayer p) { p.setYRot(-90); p.setYHeadRot(-90); p.setXRot(0); }

        void at(int delay, Runnable action) {
            h.runAfterDelay(delay, () -> { try { action.run(); } catch (RuntimeException | Error e) { close(); throw e; } });
        }

        void use() { p.getMainHandItem().use(h.getLevel(), p, InteractionHand.MAIN_HAND); }

        <T extends LivingEntity> T spawn(EntityType<T> type, double ahead) {
            T e = type.create(h.getLevel(), EntitySpawnReason.COMMAND);
            if (e instanceof Mob mob) mob.setNoAi(true);
            e.setNoGravity(true);
            e.setPos(origin.add(ahead, 0, 0));
            h.getLevel().addFreshEntity(e);
            return e;
        }

        double speed() { return p.getAttributeValue(Attributes.MOVEMENT_SPEED); }

        void close() { h.getLevel().getServer().getPlayerList().remove(p); }

        void succeed() { close(); h.succeed(); }
    }

    /** 蓄力 1 秒松手：劈下时一道贴地剑气，八格外的铁傀儡受 15 点伤害，不扣魔力。 */
    private static void smallWave(GameTestHelper h) {
        var f = Fixture.create(h, 100);
        var golem = f.spawn(EntityType.IRON_GOLEM, 8);
        f.at(USE, f::use);
        f.at(USE + 20, () -> f.p.releaseUsingItem());
        f.at(USE + 60, () -> {
            h.assertTrue(Math.abs(golem.getHealth() - (100 - SwordWave.SMALL_DAMAGE)) < .01, "剑气没有打中八格外的铁傀儡：生命 " + golem.getHealth());
            h.assertTrue(PlayerMagic.of(f.p).mana >= 100, "普通蓄力斩不应扣魔力：" + PlayerMagic.of(f.p).mana);
            h.assertTrue(SwordWave.live() == 0, "剑气走完没有结束");
            golem.discard();
            f.succeed();
        });
    }

    /**
     * 按满 15 秒：7 秒起站定、按刻扣魔力，蓄满时剑上冒金色火焰、共扣 1000；松手放出巨型剑气：
     * 六格的铁傀儡（100）受 50 穿过，九格的尸壳（20）直接击杀，三十格的监守者（500）挡下剑气、爆闪后光柱 200。
     */
    private static void grand(GameTestHelper h) {
        var f = Fixture.create(h, 2000);
        f.at(USE, f::use);
        f.at(USE + DawnvowCharge.GRAND_TICKS - 5, () -> h.assertTrue(!DawnvowCharge.grand(f.p) && f.speed() > 0, "7 秒前就进入了大型蓄力"));
        f.at(USE + DawnvowCharge.GRAND_TICKS + 10, () -> {
            h.assertTrue(DawnvowCharge.grand(f.p), "按住 7 秒没有进入大型蓄力：已蓄 " + DawnvowCharge.charged(f.p));
            h.assertTrue(f.speed() == 0, "大型蓄力时还能移动：速度 " + f.speed());
            double paid = DawnvowCharge.paid(f.p);
            h.assertTrue(paid >= 9 * DawnvowCharge.DRAIN && paid <= 12 * DawnvowCharge.DRAIN, "大型蓄力没有按刻扣魔力：已付 " + paid);
        });
        f.at(USE + DawnvowCharge.FULL_TICKS + 5, () -> {
            h.assertTrue(DawnvowCharge.full(f.p), "按住 15 秒没有蓄满");
            var aura = SurfaceAura.of(f.p.getMainHandItem());
            h.assertTrue(aura != null && aura.style() == SurfaceAura.Style.FIRE && aura.hue() == DawnvowCharge.FLAME_HUE, "蓄满时剑上没有金色火焰");
            h.assertTrue(Math.abs(DawnvowCharge.paid(f.p) - DawnvowCharge.COST) < .01, "蓄满共扣的魔力不是 1000：" + DawnvowCharge.paid(f.p));
        });
        LivingEntity[] t = new LivingEntity[3];
        f.at(USE + DawnvowCharge.FULL_TICKS + 8, () -> {
            t[0] = f.spawn(EntityType.IRON_GOLEM, 6);
            t[1] = f.spawn(EntityType.HUSK, 9);
            t[2] = f.spawn(EntityType.WARDEN, 30);
            f.p.releaseUsingItem();
        });
        // 劈下接触 0.54 秒，剑气 4 格每刻走到 29 格，再 4 刻光柱
        f.at(USE + DawnvowCharge.FULL_TICKS + 8 + 11 + 8 + SwordWave.PILLAR_DELAY + 3, () -> {
            h.assertTrue(Math.abs(t[0].getHealth() - (100 - SwordWave.PIERCE_DAMAGE)) < .01, "铁傀儡没有受 50 点伤害：" + t[0].getHealth());
            h.assertTrue(!t[1].isAlive(), "生命 20 的尸壳没有被直接击杀：" + t[1].getHealth());
            h.assertTrue(Math.abs(t[2].getHealth() - (500 - SwordWave.BURST_DAMAGE)) < .01, "监守者没有吃到光柱的 200 伤害：" + t[2].getHealth());
        });
        f.at(USE + DawnvowCharge.FULL_TICKS + 8 + 40, () -> {
            h.assertTrue(f.speed() > 0, "放出后没有解除站定");
            h.assertTrue(SurfaceAura.of(f.p.getMainHandItem()) == null, "放出后剑上的金色火焰没有收掉");
            for (var e : t) e.discard();
            f.succeed();
        });
    }

    /** 魔力只有 500：大型蓄力扣到不够时中断，解除站定、不会蓄满，已付的（500 加上这期间回复的）不退。 */
    private static void outOfMana(GameTestHelper h) {
        var f = Fixture.create(h, 500);
        f.at(USE, f::use);
        f.at(USE + DawnvowCharge.GRAND_TICKS + 20, () -> h.assertTrue(DawnvowCharge.grand(f.p) && f.speed() == 0, "有魔力时没有进入大型蓄力"));
        f.at(USE + DawnvowCharge.GRAND_TICKS + 100, () -> {
            h.assertTrue(!DawnvowCharge.grand(f.p) && f.speed() > 0, "魔力不够时没有中断大型蓄力");
            h.assertTrue(DawnvowCharge.paid(f.p) < DawnvowCharge.COST && DawnvowCharge.paid(f.p) >= 500, "魔力不够时付的不对：已付 " + DawnvowCharge.paid(f.p));
        });
        f.at(USE + DawnvowCharge.FULL_TICKS + 5, () -> {
            h.assertTrue(!DawnvowCharge.full(f.p) && SurfaceAura.of(f.p.getMainHandItem()) == null, "魔力不够也蓄满了");
            f.p.releaseUsingItem();
        });
        f.at(USE + DawnvowCharge.FULL_TICKS + 40, f::succeed);
    }

    /**
     * 巨型剑气贴地走：六格处三格高的泥土墙被削开（墙下的地面不动），十格处一格高的石台阶迈上去（台阶本身不动），
     * 十六格处黑曜石墙劈不开，剑气停在它前面爆开。
     */
    private static void carves(GameTestHelper h) {
        var f = Fixture.create(h, 0);
        var level = h.getLevel();
        BlockPos o = BlockPos.containing(f.origin);
        for (int z = -2; z <= 2; z++) {
            for (int y = 0; y < 3; y++) level.setBlockAndUpdate(o.offset(6, y, z), Blocks.DIRT.defaultBlockState());
            level.setBlockAndUpdate(o.offset(10, 0, z), Blocks.STONE.defaultBlockState());
            for (int y = 0; y < 3; y++) level.setBlockAndUpdate(o.offset(16, y, z), Blocks.OBSIDIAN.defaultBlockState());
        }
        f.at(5, () -> {
            Vec3 dir = new Vec3(1, 0, 0);
            float[] path = SwordWave.grand(level, f.p, f.origin.add(1.2, 0, 0), dir);
            double length = (path.length - 1) * SwordWave.PATH_STEP;
            h.assertTrue(length > 13 && length < 15, "剑气没有停在黑曜石墙前：长 " + length);
            h.assertTrue(Math.abs(SwordWave.heightAt(path, 9.0) - (f.origin.y + 1)) < .01, "一格高的台阶没有迈上去");
        });
        f.at(5 + 6 + SwordWave.PILLAR_DELAY + 2, () -> {
            for (int y = 0; y < 3; y++) h.assertTrue(level.getBlockState(o.offset(6, y, 0)).isAir(), "泥土墙第 " + y + " 层没有削开");
            h.assertTrue(level.getBlockState(o.offset(6, -1, 0)).is(Blocks.STONE), "墙下的地面被削掉了");
            h.assertTrue(level.getBlockState(o.offset(10, 0, 0)).is(Blocks.STONE), "迈上去的台阶被削掉了");
            h.assertTrue(level.getBlockState(o.offset(16, 0, 0)).is(Blocks.OBSIDIAN), "黑曜石被削掉了");
            Vec3 burst = SwordWave.lastBurst();
            h.assertTrue(burst != null && Math.abs(burst.x - (f.origin.x + 15)) < 1 && Math.abs(burst.y - f.origin.y) < .01,
                    "爆点不在黑曜石墙前的地面上：" + burst);
            f.succeed();
        });
    }

    /** 放出巨型剑气后的 60 秒：攻击距离多 3 格，四格半外的铁傀儡也砍得到（准星正对，暴击），每下另加 15 点；命中锥形张角 90°。 */
    private static void blessing(GameTestHelper h) {
        var f = Fixture.create(h, 0);
        var golem = f.spawn(EntityType.IRON_GOLEM, 4.5);
        f.at(USE, () -> DawnvowCharge.bless(f.p));
        f.at(USE + 2, () -> {
            h.assertTrue(DawnvowCharge.blessed(f.p), "没有进入剑身金光状态");
            double reach = f.p.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
            h.assertTrue(Math.abs(reach - (3 + DawnvowCharge.BLESS_REACH)) < .01, "攻击距离没有变长：" + reach);
            h.assertTrue(dev.logos.dimension.longsword.LongswordCombat.cone(f.p) == DawnvowCharge.BLESS_CONE, "金光在时命中锥形没有放宽到 90°");
            dev.logos.dimension.longsword.LongswordCombat.attack(f.p);
        });
        f.at(USE + 20, () -> {
            h.assertTrue(Math.abs(golem.getHealth() - (100 - 7 * 1.5 - DawnvowCharge.BLESS_DAMAGE)) < .01, "四格半外没有砍中（准星正对，暴击 10.5）或没有额外 15 点：" + golem.getHealth());
            golem.discard();
            f.succeed();
        });
    }

    /** 金光在时蓄力斩的剑气走两倍远：二十格外的铁傀儡（平时 16 格够不着）也受 15 点。 */
    private static void blessedWave(GameTestHelper h) {
        var f = Fixture.create(h, 100);
        var golem = f.spawn(EntityType.IRON_GOLEM, 20);
        f.at(USE - 2, () -> DawnvowCharge.bless(f.p));
        f.at(USE, f::use);
        f.at(USE + 20, () -> f.p.releaseUsingItem());
        f.at(USE + 60, () -> {
            h.assertTrue(Math.abs(golem.getHealth() - (100 - SwordWave.SMALL_DAMAGE)) < .01, "金光在时剑气没有走到二十格外：生命 " + golem.getHealth());
            golem.discard();
            f.succeed();
        });
    }

    private DawnvowChargeGameTests() {}
}
