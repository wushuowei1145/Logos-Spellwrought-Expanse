package dev.logos.dimension.gametest;

import com.mojang.authlib.GameProfile;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.cloudsea.FoxWildsBlocks;
import dev.logos.dimension.cloudsea.Omikuji;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.clock.ClockTimeMarkers;
import net.minecraft.world.clock.WorldClock;
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

/** 一天一抽：同一天抽不到第二支，日出换天，睡觉跳到天亮也算新的一天；每个维度按自己的时钟算，换维度不能多抽。 */
public final class OmikujiGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS = DeferredRegister.create(Registries.TEST_FUNCTION, LogosDimension.ID);
    private static final BlockPos BOX = new BlockPos(2, 1, 2);

    public static void register(IEventBus bus) {
        var tests = Map.<String, Consumer<GameTestHelper>>of(
            "omikuji_turns_at_sunrise", OmikujiGameTests::turnsAtSunrise,
            "omikuji_sleep_starts_a_new_day", OmikujiGameTests::sleepStartsANewDay,
            "omikuji_each_clock_counts_alone", OmikujiGameTests::eachClockCountsAlone);
        tests.forEach((name, test) -> FUNCTIONS.register(name, () -> test));
        FUNCTIONS.register(bus);
        bus.addListener((RegisterGameTestsEvent event) -> {
            // 各测试拨的是同一个世界时钟：每个测试单独一个环境，分批依次跑，免得同批的测试互相改时间
            for (var name : tests.keySet()) {
                var env = event.registerEnvironment(LogosDimension.id(name), new TestEnvironmentDefinition.AllOf());
                event.registerTest(LogosDimension.id(name), new FunctionGameTestInstance(
                    ResourceKey.create(Registries.TEST_FUNCTION, LogosDimension.id(name)), new TestData<>(env,
                        Identifier.withDefaultNamespace("empty"), 100, 0, true, Rotation.NONE, false, 1, 1, false, 8)));
            }
        });
    }

    private static ServerPlayer player(GameTestHelper h) {
        var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "omikuji-test"), false);
        var p = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        h.getLevel().getServer().getPlayerList().placeNewPlayer(connection, p, cookie);
        p.connection.markClientLoaded();
        p.setGameMode(GameType.SURVIVAL);
        p.setPos(h.absoluteVec(new Vec3(2.5, 1, 0.8)));
        return p;
    }

    private static Holder<WorldClock> clock(ServerLevel level) {
        return level.dimensionType().defaultClock().orElseThrow();
    }

    /** 把测试维度的时钟拨到下一天的第 t 刻。 */
    private static void time(GameTestHelper h, long t) {
        long now = h.getLevel().getDefaultClockTime();
        h.getLevel().getServer().clockManager().setTotalTicks(clock(h.getLevel()), now - Math.floorMod(now, 24000L) + 24000 + t);
    }

    /** 真的右键一次签箱，返回背包里签纸的张数，确认方块走的就是这套判断。 */
    private static int use(GameTestHelper h, ServerPlayer p) {
        BlockPos at = h.absolutePos(BOX);
        h.getLevel().getBlockState(at).useWithoutItem(h.getLevel(), p, new BlockHitResult(at.getCenter(), Direction.UP, at, false));
        return p.getInventory().countItem(FoxWildsBlocks.OMIKUJI.get());
    }

    private static void turnsAtSunrise(GameTestHelper h) {
        h.setBlock(BOX, FoxWildsBlocks.OMIKUJI_BOX.get().defaultBlockState());
        var p = player(h);
        try {
            time(h, 1000);
            h.assertTrue(use(h, p) == 1, "今天第一次应当抽到一支");
            h.assertTrue(use(h, p) == 1, "同一刻再点不该再给");
            h.getLevel().getServer().clockManager().addTicks(clock(h.getLevel()), 22999);
            h.assertTrue(use(h, p) == 1, "日出前最后一刻还是同一天");
            h.getLevel().getServer().clockManager().addTicks(clock(h.getLevel()), 1);
            h.assertTrue(use(h, p) == 2, "日出换天后应当能再抽");
        } finally {
            h.getLevel().getServer().getPlayerList().remove(p);
        }
        h.succeed();
    }

    /** 睡觉只拨维度时钟、不动游戏总刻数；原来按总刻数算，所以睡醒了还是抽不到。 */
    private static void sleepStartsANewDay(GameTestHelper h) {
        h.setBlock(BOX, FoxWildsBlocks.OMIKUJI_BOX.get().defaultBlockState());
        var p = player(h);
        try {
            time(h, 18000);
            h.assertTrue(use(h, p) == 1, "晚上抽一支");
            long gameTime = h.getLevel().getGameTime();
            h.assertTrue(h.getLevel().getServer().clockManager().moveToTimeMarker(clock(h.getLevel()), ClockTimeMarkers.WAKE_UP_FROM_SLEEP), "主世界时钟应当有睡醒的时间点");
            h.assertTrue(h.getLevel().getGameTime() == gameTime, "睡醒不该动游戏总刻数");
            h.assertTrue(use(h, p) == 2, "睡到天亮应当能再抽");
        } finally {
            h.getLevel().getServer().getPlayerList().remove(p);
        }
        h.succeed();
    }

    /** 远行大陆和主世界的时钟走得不一样：来回换维度不能反复抽。 */
    private static void eachClockCountsAlone(GameTestHelper h) {
        var p = player(h);
        ServerLevel cloudsea = h.getLevel().getServer().getLevel(LogosDimension.CLOUDSEA);
        Vec3 high = new Vec3(0.5, 300, 0.5);
        try {
            Omikuji.draw(p, h.getLevel().getRandom());
            h.assertTrue(Omikuji.drawnToday(p), "主世界今天抽过了");
            DimensionTravel.teleport(p, cloudsea, high, 0, 0);
            h.assertTrue(!Omikuji.drawnToday(p), "远行大陆的这一天还没抽过");
            Omikuji.draw(p, h.getLevel().getRandom());
            h.assertTrue(Omikuji.drawnToday(p), "远行大陆今天也抽过了");
            DimensionTravel.teleport(p, h.getLevel(), h.absoluteVec(new Vec3(2.5, 1, 0.8)), 0, 0);
            h.assertTrue(Omikuji.drawnToday(p), "回到主世界不该能再抽");
            DimensionTravel.teleport(p, cloudsea, high, 0, 0);
            h.assertTrue(Omikuji.drawnToday(p), "再回远行大陆也不该能再抽");
        } finally {
            h.getLevel().getServer().getPlayerList().remove(p);
        }
        h.succeed();
    }

    private OmikujiGameTests() {
    }
}
