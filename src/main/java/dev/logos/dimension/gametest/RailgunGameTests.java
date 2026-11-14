package dev.logos.dimension.gametest;

import com.mojang.authlib.GameProfile;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.economy.Currency;
import dev.logos.dimension.economy.Economy;
import dev.logos.dimension.economy.Railgun;
import dev.logos.lang.Element;
import dev.logos.runtime.ManaWrap;
import dev.logos.runtime.PlayerMagic;
import io.netty.channel.embedded.EmbeddedChannel;
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
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 超电磁炮彩蛋：只有缠着雷电、魔力够才弹得出硬币；射线打穿软方块、停在黑曜石前，沿途生物受伤，箱子不动。 */
public final class RailgunGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS = DeferredRegister.create(Registries.TEST_FUNCTION, LogosDimension.ID);

    public static void register(IEventBus bus) {
        var tests = Map.<String, Consumer<GameTestHelper>>of(
                "railgun_needs_lightning_and_mana", RailgunGameTests::gate,
                "railgun_pierces_soft_blocks", RailgunGameTests::shot);
        tests.forEach((name, test) -> FUNCTIONS.register(name, () -> test));
        FUNCTIONS.register(bus);
        bus.addListener((RegisterGameTestsEvent event) -> {
            var env = event.registerEnvironment(LogosDimension.id("railgun"), new TestEnvironmentDefinition.AllOf());
            // 射线会拆掉周围的方块，留足间距
            for (String name : tests.keySet()) event.registerTest(LogosDimension.id(name), new FunctionGameTestInstance(
                    ResourceKey.create(Registries.TEST_FUNCTION, LogosDimension.id(name)),
                    new TestData<>(env, Identifier.withDefaultNamespace("empty"), 200, 0, true, Rotation.NONE, false, 1, 1, false, 32)));
        });
    }

    private static ServerPlayer player(GameTestHelper h, BlockPos at) {
        var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "railgun-test"), false);
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
        var magic = PlayerMagic.of(p);
        magic.baseMaxMana = 1000;
        magic.mana = 250;
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Economy.coin(Currency.IMPERIAL, 1), 2));
        return p;
    }

    private static void gate(GameTestHelper h) {
        var p = player(h, h.absolutePos(new BlockPos(1, 1, 1)));
        var level = h.getLevel();
        h.assertTrue(Railgun.use(level, p, InteractionHand.MAIN_HAND) == InteractionResult.PASS, "没缠雷电时银元只是钱");
        ManaWrap.wrap(p, Element.FIRE, 1, 60);
        h.assertTrue(Railgun.use(level, p, InteractionHand.MAIN_HAND) == InteractionResult.PASS, "缠着火焰不行");
        ManaWrap.wrap(p, Element.LIGHTNING, 1, 60);
        PlayerMagic.of(p).mana = 150;
        h.assertTrue(Railgun.use(level, p, InteractionHand.MAIN_HAND) == InteractionResult.FAIL && !p.isUsingItem(), "魔力不够不能蓄力");
        h.assertTrue(PlayerMagic.of(p).mana == 150, "魔力不够时不扣");
        var copper = new ItemStack(Economy.coin(Currency.IMPERIAL, 0));
        p.setItemInHand(InteractionHand.OFF_HAND, copper);
        PlayerMagic.of(p).mana = 250;
        h.assertTrue(copper.getItem().use(level, p, InteractionHand.OFF_HAND) == InteractionResult.PASS, "只有银元有彩蛋");
        h.assertTrue(Railgun.use(level, p, InteractionHand.MAIN_HAND) == InteractionResult.CONSUME && p.isUsingItem(), "缠着雷电、魔力够就开始蓄力");
        h.assertTrue(PlayerMagic.of(p).mana == 50, "开始蓄力付 200 魔力");
        p.releaseUsingItem();
        h.assertTrue(p.getMainHandItem().getCount() == 2, "提前松手不花硬币");
        p.discard();
        h.succeed();
    }

    private static void shot(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos o = h.absolutePos(new BlockPos(2, 1, 8));
        // 地面、一堵石墙、墙后的僵尸和箱子、黑曜石墙，以及藏在黑曜石后面的石头
        for (int x = -2; x <= 18; x++) for (int z = -5; z <= 5; z++) level.setBlockAndUpdate(o.offset(x, -1, z), Blocks.STONE.defaultBlockState());
        for (int y = 0; y <= 3; y++) for (int z = -1; z <= 1; z++) level.setBlockAndUpdate(o.offset(5, y, z), Blocks.STONE.defaultBlockState());
        for (int y = 0; y <= 4; y++) for (int z = -2; z <= 2; z++) level.setBlockAndUpdate(o.offset(14, y, z), Blocks.OBSIDIAN.defaultBlockState());
        level.setBlockAndUpdate(o.offset(16, 1, 0), Blocks.STONE.defaultBlockState());
        level.setBlockAndUpdate(o.offset(9, 0, 3), Blocks.CHEST.defaultBlockState());
        var zombie = EntityType.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
        zombie.setNoAi(true);
        zombie.snapTo(o.getX() + 9.5, o.getY(), o.getZ() + .5, 90, 0);
        level.addFreshEntity(zombie);
        var behind = EntityType.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
        behind.setNoAi(true);
        behind.snapTo(o.getX() + 16.5, o.getY() + 2, o.getZ() + .5, 90, 0);
        level.addFreshEntity(behind);

        var p = player(h, o);
        ManaWrap.wrap(p, Element.LIGHTNING, 1, 60);
        h.assertTrue(Railgun.use(level, p, InteractionHand.MAIN_HAND) == InteractionResult.CONSUME, "开始蓄力");
        var stack = p.getMainHandItem();
        stack.getItem().onUseTick(level, p, stack, 72000 - (Railgun.FIRE_TICK - 1));
        h.assertTrue(p.isUsingItem() && zombie.isAlive(), "硬币还没落回来时不发射");
        stack.getItem().onUseTick(level, p, stack, 72000 - Railgun.FIRE_TICK);
        h.assertTrue(!p.isUsingItem(), "发射后结束蓄力");
        h.assertTrue(!zombie.isAlive(), "射线上的僵尸吃 100 点物理伤害");
        h.assertTrue(behind.isAlive() && behind.getHealth() == behind.getMaxHealth(), "黑曜石后面的僵尸不受伤");
        h.assertTrue(p.getMainHandItem().getCount() == 1, "打出去一枚硬币");
        h.assertTrue(p.getCooldowns().isOnCooldown(p.getMainHandItem()), "发射后有冷却");
        h.runAfterDelay(6, () -> {
            for (int y = 0; y <= 3; y++) h.assertTrue(level.getBlockState(o.offset(5, y, 0)).isAir(), "石墙在射线上被掀掉 y=" + y);
            h.assertTrue(level.getBlockState(o.offset(12, -1, 0)).isAir(), "射线下方的地面被掀掉");
            h.assertTrue(level.getBlockState(o.offset(0, -1, 0)).is(Blocks.STONE), "射手脚下不挖空");
            h.assertTrue(level.getBlockState(o.offset(14, 1, 0)).is(Blocks.OBSIDIAN), "黑曜石挡得住");
            h.assertTrue(level.getBlockState(o.offset(16, 1, 0)).is(Blocks.STONE), "黑曜石后面的方块不动");
            h.assertTrue(level.getBlockState(o.offset(9, 0, 3)).is(Blocks.CHEST), "箱子不拆");
            h.assertTrue(level.getBlockState(o.offset(5, -1, 5)).is(Blocks.STONE),
                    "半径 4 格外的方块不动");
            p.discard();
            h.succeed();
        });
    }
}
