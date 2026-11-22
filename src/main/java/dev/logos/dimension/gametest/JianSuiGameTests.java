package dev.logos.dimension.gametest;

import com.mojang.authlib.GameProfile;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.jiansui.JianSui;
import dev.logos.dimension.jiansui.JianSuiEntities;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.LinkedHashMap;
import java.util.List;
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
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 剑祟（docs/JIAN_SUI_BOSS.md）的世界测试：三招剑招的命中、格挡、破盾、弹反。玩家用嵌入连接的假玩家，站在石头地上、位置钉住
 * （同白梅的测试）。剑祟由测试指定出哪一招（{@code scripted}：不自己出招）。
 */
public final class JianSuiGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS = DeferredRegister.create(Registries.TEST_FUNCTION, LogosDimension.ID);

    private JianSuiGameTests() {}

    public static void register(IEventBus bus) {
        Map<String, Consumer<GameTestHelper>> tests = new LinkedHashMap<>();
        tests.put("jian_sui_tafeng_physical", JianSuiGameTests::tafengPhysical);
        tests.put("jian_sui_huifeng_breaks_guard", JianSuiGameTests::huifengBreaksGuard);
        tests.put("jian_sui_feiyan_breaks_guard", JianSuiGameTests::feiyanBreaksGuard);
        tests.put("jian_sui_tafeng_parried", JianSuiGameTests::tafengParried);
        tests.put("jian_sui_bar_floor", JianSuiGameTests::barFloor);
        tests.put("jian_sui_arena_flow", JianSuiArenaGameTests::flow);
        tests.put("jian_sui_giant_parry", JianSuiArenaGameTests::giantParry);
        tests.put("jian_sui_finisher_pry", JianSuiArenaGameTests::finisherPry);
        tests.forEach((name, test) -> FUNCTIONS.register(name, () -> test));
        FUNCTIONS.register(bus);
        bus.addListener((RegisterGameTestsEvent event) -> {
            for (var name : tests.keySet()) {
                var env = event.registerEnvironment(LogosDimension.id(name), new TestEnvironmentDefinition.AllOf());
                int ticks = switch (name) {
                    case "jian_sui_arena_flow" -> 3600;
                    case "jian_sui_giant_parry", "jian_sui_finisher_pry" -> 1200;
                    default -> 300;
                };
                event.registerTest(LogosDimension.id(name), new FunctionGameTestInstance(
                        ResourceKey.create(Registries.TEST_FUNCTION, LogosDimension.id(name)), new TestData<>(env,
                        Identifier.withDefaultNamespace("empty"), ticks, 0, true, Rotation.NONE, false, 1, 1, false, 24)));
            }
        });
    }

    private static List<ChunkPos> ground(GameTestHelper h) {
        for (int x = -4; x <= 18; x++)
            for (int z = -6; z <= 10; z++) h.setBlock(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
        BlockPos a = h.absolutePos(new BlockPos(-4, 0, -6)), b = h.absolutePos(new BlockPos(18, 0, 10));
        List<ChunkPos> chunks = new java.util.ArrayList<>();
        for (int cx = Math.min(a.getX(), b.getX()) >> 4; cx <= Math.max(a.getX(), b.getX()) >> 4; cx++)
            for (int cz = Math.min(a.getZ(), b.getZ()) >> 4; cz <= Math.max(a.getZ(), b.getZ()) >> 4; cz++) {
                chunks.add(new ChunkPos(cx, cz));
                h.getLevel().setChunkForced(cx, cz, true);
            }
        return chunks;
    }

    /** 一块石头地；玩家站在 (2, 1, 2) 面朝 +X，嵌入连接，逐刻更新装备与冷却，位置钉住。 */
    private record Fixture(GameTestHelper h, ServerPlayer p, Vec3 origin, List<ChunkPos> chunks) {
        static Fixture create(GameTestHelper h, ItemStack main) {
            var chunks = ground(h);
            var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "jian-sui-test"), false);
            var p = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
            var connection = new Connection(PacketFlow.SERVERBOUND);
            new EmbeddedChannel(connection);
            h.getLevel().getServer().getPlayerList().placeNewPlayer(connection, p, cookie);
            p.connection.markClientLoaded();
            p.setGameMode(GameType.SURVIVAL);
            p.tickCount = 1;
            p.setNoGravity(true);
            var origin = h.absoluteVec(new Vec3(2.5, 1, 2.5));
            p.setPos(origin);
            p.setYRot(-90);
            p.setYHeadRot(-90);
            p.setXRot(0);
            p.setItemInHand(InteractionHand.MAIN_HAND, main);
            h.onEachTick(() -> {
                p.tickCount = 1;
                p.doTick();
                p.setPos(origin);
                p.setDeltaMovement(Vec3.ZERO);
                p.setYRot(-90);
                p.setYHeadRot(-90);
                p.setXRot(0);
            });
            return new Fixture(h, p, origin, chunks);
        }

        void at(int delay, Runnable action) {
            h.runAfterDelay(delay, () -> {
                try {
                    action.run();
                } catch (RuntimeException | Error e) {
                    close();
                    throw e;
                }
            });
        }

        void use() {
            p.getMainHandItem().use(h.getLevel(), p, InteractionHand.MAIN_HAND);
        }

        void close() {
            p.stopUsingItem();
            h.getLevel().getServer().getPlayerList().remove(p);
            for (ChunkPos c : chunks) h.getLevel().setChunkForced(c.x(), c.z(), false);
        }

        void succeed() {
            close();
            h.succeed();
        }
    }

    /** 剑祟站在玩家正前方 {@code distance} 格、面朝玩家，不自己出招。 */
    private static JianSui boss(Fixture f, double distance) {
        var level = f.h.getLevel();
        JianSui boss = JianSuiEntities.JIAN_SUI.get().create(level, EntitySpawnReason.COMMAND);
        Vec3 at = f.origin.add(distance, 0, 0);
        boss.snapTo(at.x, at.y, at.z, 90, 0);
        boss.setYHeadRot(90);
        boss.setYBodyRot(90);
        level.addFreshEntity(boss);
        boss.setScripted(true);
        return boss;
    }

    /** 踏风打中：原版的生物近战伤害（物理），护甲照算：穿着钻石胸甲掉的血比 9 少，但确实掉了。 */
    private static void tafengPhysical(GameTestHelper h) {
        var f = Fixture.create(h, new ItemStack(Items.STICK));
        f.p.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
        var boss = boss(f, 2.2);
        f.at(10, () -> boss.play("tafeng"));
        f.at(45, () -> {
            float lost = f.p.getMaxHealth() - f.p.getHealth();
            var source = f.p.getLastDamageSource();
            h.assertTrue(lost > 0, "踏风没有打中");
            h.assertTrue(lost < 9, "护甲没有算进去：掉了 " + lost);
            h.assertTrue(source != null && source.is(DamageTypes.MOB_ATTACK) && source.getEntity() == boss, "伤害不是剑祟的近战：" + source);
            h.assertTrue(!source.is(DamageTypeTags.BYPASSES_ARMOR), "伤害不看护甲");
            boss.discard();
            f.succeed();
        });
    }

    /** 举盾挡回风（过了弹刀的那一瞬）：横掠那一刀没弹反就破盾；盾放下了，贴地反切那一刀照样砍中（最多掉这一刀的血）。 */
    private static void huifengBreaksGuard(GameTestHelper h) {
        var f = Fixture.create(h, new ItemStack(Items.SHIELD));
        var boss = boss(f, 2.0);
        f.at(5, f::use);
        f.at(30, () -> {
            h.assertTrue(f.p.isBlocking(), "盾没有举起来");
            boss.play("huifeng");
        });
        f.at(60, () -> {
            float lost = f.p.getMaxHealth() - f.p.getHealth();
            h.assertTrue(f.p.getCooldowns().isOnCooldown(f.p.getMainHandItem()), "回风挡下来没有破盾");
            h.assertTrue(lost <= 6.01f, "横掠挡下了还掉了血：" + f.p.getHealth());
            h.assertTrue(lost > 0, "盾破了以后贴地反切那一刀没有砍中");
            boss.discard();
            f.succeed();
        });
    }

    /** 举盾挡飞燕：挡下的这一刀不掉血，但没弹反就破盾（第 6.2 节）。 */
    private static void feiyanBreaksGuard(GameTestHelper h) {
        var f = Fixture.create(h, new ItemStack(Items.SHIELD));
        var boss = boss(f, 2.6);
        f.at(5, f::use);
        f.at(30, () -> {
            h.assertTrue(f.p.isBlocking(), "盾没有举起来");
            boss.play("feiyan");
        });
        f.at(60, () -> {
            h.assertTrue(f.p.getCooldowns().isOnCooldown(f.p.getMainHandItem()), "飞燕挡下来没有破盾");
            h.assertTrue(f.p.getHealth() == f.p.getMaxHealth(), "飞燕挡下了还掉了血：" + f.p.getHealth());
            boss.discard();
            f.succeed();
        });
    }

    /** 打刀在踏风落下前一刻举刀：弹反，剑祟仰身僵住（parried），失衡加 30，人没掉血。 */
    private static void tafengParried(GameTestHelper h) {
        var f = Fixture.create(h, new ItemStack(dev.logos.dimension.item.UchigatanaItem.INK.get()));
        var boss = boss(f, 2.2);
        List<String> moves = new java.util.ArrayList<>();
        h.onEachTick(() -> {
            String m = boss.move();
            if (moves.isEmpty() || !moves.getLast().equals(m)) moves.add(m);
        });
        // 打刀拔刀要三十来刻：过了之后再起招，接触前两刻举刀
        f.at(27, () -> boss.play("tafeng"));
        f.at(40, f::use);
        f.at(70, () -> {
            h.assertTrue(moves.contains("parried"), "没有被弹反：" + moves);
            h.assertTrue(f.p.getHealth() == f.p.getMaxHealth(), "弹反了还掉了血：" + f.p.getHealth());
            h.assertTrue(boss.imbalance() >= 25, "弹反没有加失衡：" + boss.imbalance());
            boss.discard();
            f.succeed();
        });
    }

    /** 每刻的伤害有上限（当前这条血的四分之一），一条血打空停在 1 并换成第二条（没有场地的剑祟）。 */
    private static void barFloor(GameTestHelper h) {
        var f = Fixture.create(h, new ItemStack(Items.STICK));
        var boss = boss(f, 4);
        boss.setScripted(false);
        f.at(5, () -> {
            // 同一刻里连打两下（处决、每刻补刀的情形）：两下加起来也只算四分之一（上限按护甲前的伤害算）。
            // 没有上限时第二下会把第一条血打空、换成第二条，所以也看阶段
            boss.hurtServer(h.getLevel(), h.getLevel().damageSources().playerAttack(f.p), 100);
            float first = boss.getHealth();
            boss.invulnerableTime = 0;
            boss.hurtServer(h.getLevel(), h.getLevel().damageSources().playerAttack(f.p), 10000);
            float lost = JianSui.HEALTH_ONE - boss.getHealth();
            // 护甲把伤害打了个折：从第一下（100）量出折扣，两下加起来应该正好是「四分之一 × 折扣」
            float armor = (JianSui.HEALTH_ONE - first) / 100f;
            h.assertTrue(boss.phase() == 1, "一刻里连打两下就把第一条血打空了");
            h.assertTrue(Math.abs(lost - JianSui.HEALTH_ONE * .25f * armor) < 1, "一刻的上限不是四分之一（按护甲前算）：掉了 " + lost + "，折扣 " + armor);
            h.assertTrue(first - boss.getHealth() > 1, "同一刻的第二下被整个吞了（上限还没满）：" + first + " → " + boss.getHealth());
        });
        // 之后每刻一下，每下都被截到四分之一：再四刻打空
        for (int i = 0; i < 6; i++) f.at(7 + i, () -> {
            boss.invulnerableTime = 0;
            boss.hurtServer(h.getLevel(), h.getLevel().damageSources().playerAttack(f.p), 200);
        });
        f.at(30, () -> {
            h.assertTrue(boss.isAlive(), "第一条血打空就死了");
            h.assertTrue(boss.phase() == 2, "没有换成第二条血：阶段 " + boss.phase());
            h.assertTrue(Math.abs(boss.getMaxHealth() - JianSui.HEALTH_TWO) < 1, "第二条血不是 550：" + boss.getMaxHealth());
            boss.discard();
            f.succeed();
        });
    }
}
