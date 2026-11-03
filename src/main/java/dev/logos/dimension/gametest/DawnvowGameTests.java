package dev.logos.dimension.gametest;

import com.mojang.authlib.GameProfile;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.dawnvow.Dawnvow;
import dev.logos.dimension.dawnvow.DawnvowTrial;
import dev.logos.dimension.dawnvow.SwordStoneBlockEntity;
import dev.logos.dimension.item.LongswordItem;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** 石中剑：插着剑时打不坏；破晓按住能拔出、其他时候拔不动；一人一誓；插回空石座解除誓约。 */
public final class DawnvowGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS = DeferredRegister.create(Registries.TEST_FUNCTION, LogosDimension.ID);
    private static final BlockPos STONE = new BlockPos(2, 1, 2);

    public static void register(IEventBus bus) {
        var tests = Map.<String, Consumer<GameTestHelper>>of(
            "dawnvow_unbreakable", DawnvowGameTests::unbreakable,
            "dawnvow_draw_at_dawn", DawnvowGameTests::drawAtDawn,
            "dawnvow_unmoved_at_noon", DawnvowGameTests::unmovedAtNoon,
            "dawnvow_one_vow", DawnvowGameTests::oneVow,
            "dawnvow_let_go", DawnvowGameTests::letGo,
            "dawnvow_return", DawnvowGameTests::giveBack);
        tests.forEach((name, test) -> FUNCTIONS.register(name, () -> test));
        FUNCTIONS.register(bus);
        bus.addListener((RegisterGameTestsEvent event) -> {
            // 各测试拨的是同一个世界时钟：每个测试单独一个环境，分批依次跑，免得同批的测试互相改时间
            for (var name : tests.keySet()) {
                var env = event.registerEnvironment(LogosDimension.id(name), new TestEnvironmentDefinition.AllOf());
                event.registerTest(LogosDimension.id(name), new FunctionGameTestInstance(
                ResourceKey.create(Registries.TEST_FUNCTION, LogosDimension.id(name)), new TestData<>(env,
                    Identifier.withDefaultNamespace("empty"), 200, 0, true, Rotation.NONE, false, 1, 1, false, 8)));
            }
        });
    }

    private static ServerPlayer player(GameTestHelper h) {
        var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "dawnvow-test"), false);
        var p = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        h.getLevel().getServer().getPlayerList().placeNewPlayer(connection, p, cookie);
        p.connection.markClientLoaded();
        p.setGameMode(GameType.SURVIVAL);
        p.setPos(h.absoluteVec(new Vec3(2.5, 1, 0.8)));
        return p;
    }

    private static void close(GameTestHelper h, ServerPlayer p) {
        h.getLevel().getServer().getPlayerList().remove(p);
    }

    /** 把测试维度的时钟拨到一天里的第 t 刻。 */
    private static void time(GameTestHelper h, long t) {
        var clock = h.getLevel().dimensionType().defaultClock().orElseThrow();
        long now = h.getLevel().getDefaultClockTime();
        h.getLevel().getServer().clockManager().setTotalTicks(clock, now - Math.floorMod(now, 24000L) + 24000 + t);
    }

    private static void placeStone(GameTestHelper h) {
        h.setBlock(STONE, Dawnvow.STONE.get().defaultBlockState());
    }

    /** 像按住右键一样，每 4 刻点一次石座，共 ticks 刻。 */
    private static void hold(GameTestHelper h, ServerPlayer p, int from, int ticks) {
        BlockPos at = h.absolutePos(STONE);
        for (int t = 0; t <= ticks; t += 4) {
            h.runAfterDelay(from + t, () -> {
                var state = h.getLevel().getBlockState(at);
                state.useWithoutItem(h.getLevel(), p, new BlockHitResult(at.getCenter(), Direction.UP, at, false));
            });
        }
    }

    private static boolean holds(ServerPlayer p) {
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) if (p.getInventory().getItem(i).is(LongswordItem.DAWNVOW.get())) return true;
        return false;
    }

    private static void unbreakable(GameTestHelper h) {
        placeStone(h);
        BlockPos at = h.absolutePos(STONE);
        var state = h.getLevel().getBlockState(at);
        h.assertTrue(state.getDestroySpeed(h.getLevel(), at) < 0, "插着剑的石座应当和基岩一样无法挖掘");
        h.assertTrue(state.getBlock().getExplosionResistance() >= 3_600_000, "插着剑的石座应当不怕爆炸");
        h.assertTrue(state.getLightEmission(h.getLevel(), at) == 14, "插着剑的石座应当像火把一样亮");
        h.assertTrue(h.getLevel().getBlockEntity(at) instanceof SwordStoneBlockEntity stone && stone.sword().is(LongswordItem.DAWNVOW.get()),
            "新放的石座里应当有一把曦誓");
        var empty = Dawnvow.EMPTY_STONE.get().defaultBlockState();
        h.assertTrue(empty.getDestroySpeed(h.getLevel(), at) > 0 && empty.getLightEmission(h.getLevel(), at) == 0, "空石座应当可以挖、不发光");
        h.succeed();
    }

    private static void drawAtDawn(GameTestHelper h) {
        placeStone(h);
        time(h, 23500);
        var p = player(h);
        h.assertTrue(DawnvowTrial.dawn(h.getLevel()), "23500 刻应当算破晓");
        hold(h, p, 1, DawnvowTrial.PULL_TICKS + 8);
        h.runAfterDelay(DawnvowTrial.PULL_TICKS + 14, () -> {
            try {
                h.assertBlockPresent(Dawnvow.EMPTY_STONE.get(), STONE);
                h.assertTrue(holds(p), "拔出的曦誓应当进背包");
                h.assertTrue(DawnvowTrial.sworn(p), "拔出后应当立下誓约");
            } finally {
                close(h, p);
            }
            h.succeed();
        });
    }

    private static void unmovedAtNoon(GameTestHelper h) {
        placeStone(h);
        time(h, 6000);
        var p = player(h);
        h.assertFalse(DawnvowTrial.dawn(h.getLevel()), "正午不应当算破晓");
        hold(h, p, 1, DawnvowTrial.PULL_TICKS * 2);
        h.runAfterDelay(DawnvowTrial.PULL_TICKS * 2 + 8, () -> {
            try {
                h.assertBlockPresent(Dawnvow.STONE.get(), STONE);
                h.assertFalse(holds(p), "正午不应当拔得出来");
                h.assertFalse(DawnvowTrial.sworn(p), "没拔出来不应当立誓");
            } finally {
                close(h, p);
            }
            h.succeed();
        });
    }

    private static void oneVow(GameTestHelper h) {
        placeStone(h);
        time(h, 0);
        var p = player(h);
        p.setData(Dawnvow.SWORN, true);
        hold(h, p, 1, DawnvowTrial.PULL_TICKS + 8);
        h.runAfterDelay(DawnvowTrial.PULL_TICKS + 14, () -> {
            try {
                h.assertBlockPresent(Dawnvow.STONE.get(), STONE);
                h.assertFalse(holds(p), "已立过誓的玩家不应当再拔出一把");
            } finally {
                close(h, p);
            }
            h.succeed();
        });
    }

    /** 中途松手超过半秒，进度从头算：两段各拔一半，合起来也拔不出来。 */
    private static void letGo(GameTestHelper h) {
        placeStone(h);
        time(h, 23000);
        var p = player(h);
        int half = DawnvowTrial.PULL_TICKS / 2;
        hold(h, p, 1, half);
        hold(h, p, half + 1 + DawnvowTrial.LET_GO_TICKS + 8, half);
        h.runAfterDelay(DawnvowTrial.PULL_TICKS + DawnvowTrial.LET_GO_TICKS + 20, () -> {
            try {
                h.assertBlockPresent(Dawnvow.STONE.get(), STONE);
                h.assertFalse(holds(p), "松过手的两段不应当合起来拔出");
            } finally {
                close(h, p);
            }
            h.succeed();
        });
    }

    private static void giveBack(GameTestHelper h) {
        h.setBlock(STONE, Dawnvow.EMPTY_STONE.get().defaultBlockState());
        var p = player(h);
        p.setData(Dawnvow.SWORN, true);
        ItemStack sword = new ItemStack(LongswordItem.DAWNVOW.get());
        sword.set(DataComponents.CUSTOM_NAME, Component.literal("晨星"));
        p.setItemInHand(InteractionHand.MAIN_HAND, sword);
        BlockPos at = h.absolutePos(STONE);
        try {
            h.getLevel().getBlockState(at).useItemOn(p.getMainHandItem(), h.getLevel(), p, InteractionHand.MAIN_HAND,
                new BlockHitResult(at.getCenter(), Direction.UP, at, false));
            h.assertBlockPresent(Dawnvow.STONE.get(), STONE);
            h.assertTrue(p.getMainHandItem().isEmpty(), "插回去的剑应当离手");
            h.assertFalse(DawnvowTrial.sworn(p), "插回剑应当解除誓约");
            h.assertTrue(h.getLevel().getBlockEntity(at) instanceof SwordStoneBlockEntity stone && "晨星".equals(stone.sword().getHoverName().getString()),
                "石座里应当是玩家插回的那把剑");
        } finally {
            close(h, p);
        }
        h.succeed();
    }

    private DawnvowGameTests() {
    }
}
