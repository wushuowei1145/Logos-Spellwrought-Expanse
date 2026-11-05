package dev.logos.dimension.gametest;

import com.mojang.authlib.GameProfile;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.cloudsea.FoxWildsBlocks;
import dev.logos.dimension.cloudsea.Omikuji;
import dev.logos.dimension.cloudsea.OmikujiRackBlock;
import dev.logos.dimension.luck.Luck;
import dev.logos.dimension.luck.LuckKind;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
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
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.Arrays;
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
            "omikuji_each_clock_counts_alone", OmikujiGameTests::eachClockCountsAlone,
            "omikuji_fortune_lasts_until_sunrise", OmikujiGameTests::fortuneLastsUntilSunrise,
            "omikuji_milk_and_death_keep_fortune", OmikujiGameTests::milkAndDeathKeepFortune,
            "omikuji_only_todays_bad_slip_cleanses", OmikujiGameTests::onlyTodaysBadSlipCleanses);
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

    private static Holder<MobEffect> effect(String id) {
        return BuiltInRegistries.MOB_EFFECT.get(LogosDimension.id(id)).orElseThrow();
    }

    /** 这支签今天带了几项运势。 */
    private static long kinds(ServerPlayer p, int level) {
        return Arrays.stream(LuckKind.values()).filter(k -> Luck.level(p, k) == level).count();
    }

    private static LuckKind badKind(ServerPlayer p) {
        return Arrays.stream(LuckKind.values()).filter(k -> Luck.level(p, k) < 0).findFirst().orElseThrow();
    }

    private static void fortuneLastsUntilSunrise(GameTestHelper h) {
        var p = player(h);
        var random = h.getLevel().getRandom();
        try {
            time(h, 1000);
            // 身上已有赛钱箱给的低级福运：大吉的幸运 II 也要盖得上去
            p.addEffect(new MobEffectInstance(MobEffects.LUCK, 600, 0));
            ItemStack paper = Omikuji.draw(p, Omikuji.Fortune.DAIKICHI, random);
            h.assertTrue(kinds(p, 2) == 2, "大吉应当带两项二级运势");
            h.assertTrue(paper.get(DataComponents.LORE).lines().size() == 3, "签纸上应当有签文和两项运势");
            var luck = p.getEffect(MobEffects.LUCK);
            h.assertTrue(luck != null && luck.getAmplifier() == 1, "大吉应当带幸运 II");
            h.assertTrue(luck.getDuration() == 23000, "幸运应当正好管到日出，实际 " + luck.getDuration());
            LuckKind kind = Arrays.stream(LuckKind.values()).filter(k -> Luck.level(p, k) == 2).findFirst().orElseThrow();
            var shown = p.getEffect(effect(kind.id + "_luck"));
            h.assertTrue(shown != null && shown.getAmplifier() == 1 && !shown.isVisible() && shown.showIcon(), "运势要有图标、没有粒子");

            // 睡到天亮：管到日出的运势就此结束
            h.getLevel().getServer().clockManager().moveToTimeMarker(clock(h.getLevel()), ClockTimeMarkers.WAKE_UP_FROM_SLEEP);
            h.assertTrue(Luck.level(p, kind) == 0, "日出后运势应当读不到了");
            Luck.sync(p);
            h.assertTrue(!p.hasEffect(effect(kind.id + "_luck")), "日出后图标应当消失");

            Omikuji.draw(p, Omikuji.Fortune.SUEKICHI, random);
            h.assertTrue(kinds(p, 0) == LuckKind.values().length && p.hasEffect(MobEffects.LUCK), "末吉只有幸运，不带运势");
            for (int i = 0; i < 40; i++) {
                Omikuji.draw(p, Omikuji.Fortune.DAIKYO, random);
                h.assertTrue(kinds(p, -2) == 2, "大凶应当带两项二级凶运");
                h.assertTrue(Luck.level(p, LuckKind.LOST) == 0, "失物运没有凶的一面");
            }
        } finally {
            h.getLevel().getServer().getPlayerList().remove(p);
        }
        h.succeed();
    }

    private static void milkAndDeathKeepFortune(GameTestHelper h) {
        var p = player(h);
        var list = h.getLevel().getServer().getPlayerList();
        try {
            time(h, 1000);
            Omikuji.draw(p, Omikuji.Fortune.KYO, h.getLevel().getRandom());
            LuckKind kind = badKind(p);
            var icon = effect(kind.id + "_misfortune");
            h.assertTrue(p.hasEffect(icon) && p.hasEffect(MobEffects.UNLUCK), "凶签应当带图标与霉运");
            p.removeAllEffects();
            h.assertTrue(!p.hasEffect(icon) && Luck.level(p, kind) == -1, "牛奶清掉图标，记录还在");
            Luck.sync(p);
            h.assertTrue(p.hasEffect(icon) && p.hasEffect(MobEffects.UNLUCK), "牛奶清掉的应当补回来");

            p = list.respawn(p, false, Entity.RemovalReason.KILLED);
            h.assertTrue(Luck.level(p, kind) == -1, "死亡不该洗掉运势");
            h.assertTrue(Omikuji.drawnToday(p), "死亡不该重置今天抽过");
            Luck.sync(p);
            h.assertTrue(p.hasEffect(icon), "重生后图标应当补回来");
        } finally {
            list.remove(p);
        }
        h.succeed();
    }

    private static void onlyTodaysBadSlipCleanses(GameTestHelper h) {
        BlockPos at = h.absolutePos(BOX);
        h.setBlock(BOX, FoxWildsBlocks.OMIKUJI_RACK.get().defaultBlockState());
        var p = player(h);
        var random = h.getLevel().getRandom();
        var hit = new BlockHitResult(at.getCenter(), Direction.SOUTH, at, false);
        Consumer<ItemStack> tie = paper -> h.getLevel().getBlockState(at).useItemOn(paper, h.getLevel(), p, InteractionHand.MAIN_HAND, hit);
        try {
            time(h, 1000);
            ItemStack yesterday = Omikuji.draw(p, Omikuji.Fortune.KYO, random);
            time(h, 1000);
            ItemStack today = Omikuji.draw(p, Omikuji.Fortune.KYO, random);
            LuckKind kind = badKind(p);

            tie.accept(yesterday);
            h.assertTrue(yesterday.isEmpty() && Luck.level(p, kind) == -1 && p.hasEffect(MobEffects.UNLUCK), "昨天的凶签解不了今天的");
            ItemStack good = new ItemStack(FoxWildsBlocks.OMIKUJI.get());
            good.set(Omikuji.SLIP.get(), new Omikuji.Slip(true, today.get(Omikuji.SLIP.get()).clock(), today.get(Omikuji.SLIP.get()).day()));
            tie.accept(good);
            h.assertTrue(good.isEmpty() && Luck.level(p, kind) == -1, "吉签解不了凶运");
            ItemStack old = new ItemStack(FoxWildsBlocks.OMIKUJI.get());
            tie.accept(old);
            h.assertTrue(old.isEmpty() && Luck.level(p, kind) == -1, "老存档的签解不了凶运");
            h.assertTrue(h.getLevel().getBlockState(at).getValue(OmikujiRackBlock.TIED) == 3, "系上去的签都该挂在绳上");

            tie.accept(today);
            h.assertTrue(today.isEmpty() && h.getLevel().getBlockState(at).getValue(OmikujiRackBlock.TIED) == 3, "绳上满了也还能系");
            h.assertTrue(Luck.level(p, kind) == 0 && !p.hasEffect(MobEffects.UNLUCK) && !p.hasEffect(effect(kind.id + "_misfortune")), "今天的凶签应当解掉今天的凶运");
            Luck.sync(p);
            h.assertTrue(!p.hasEffect(MobEffects.UNLUCK) && !p.hasEffect(effect(kind.id + "_misfortune")), "解掉之后不该再补回来");
        } finally {
            h.getLevel().getServer().getPlayerList().remove(p);
        }
        h.succeed();
    }

    private OmikujiGameTests() {
    }
}
