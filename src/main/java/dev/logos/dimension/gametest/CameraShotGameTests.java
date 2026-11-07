package dev.logos.dimension.gametest;

import com.mojang.authlib.GameProfile;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.camera.CameraShots;
import dev.logos.dimension.camera.CameraTestBlock;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
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
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 运镜在服务端的部分：测试方块只认镐，机位被方块挡住时停在它前面。 */
public final class CameraShotGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS = DeferredRegister.create(Registries.TEST_FUNCTION, LogosDimension.ID);

    private CameraShotGameTests() {}

    public static void register(IEventBus bus) {
        var tests = new LinkedHashMap<String, Consumer<GameTestHelper>>();
        tests.put("camera_test_block_pickaxe_only", CameraShotGameTests::pickaxeOnly);
        tests.put("camera_reach_stops_at_blocks", CameraShotGameTests::reachStopsAtBlocks);
        tests.forEach((name, test) -> FUNCTIONS.register(name, () -> test));
        FUNCTIONS.register(bus);
        bus.addListener((RegisterGameTestsEvent event) -> {
            for (var name : tests.keySet()) {
                var env = event.registerEnvironment(LogosDimension.id(name), new TestEnvironmentDefinition.AllOf());
                event.registerTest(LogosDimension.id(name), new FunctionGameTestInstance(
                        ResourceKey.create(Registries.TEST_FUNCTION, LogosDimension.id(name)), new TestData<>(env,
                        Identifier.withDefaultNamespace("empty"), 200, 0, true, Rotation.NONE, false, 1, 1, false, 8)));
            }
        });
    }

    private static void check(GameTestHelper h, boolean ok, String message) {
        h.assertTrue(ok, Component.literal(message));
    }

    private static ServerPlayer player(GameTestHelper h, BlockPos at) {
        var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "camera-test"), false);
        var p = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        h.getLevel().getServer().getPlayerList().placeNewPlayer(connection, p, cookie);
        p.connection.markClientLoaded();
        p.setPos(at.getBottomCenter());
        return p;
    }

    /** 拿 {@code tool} 以 {@code mode} 拆一次，返回方块还在不在。每次都重新放好。 */
    private static boolean survives(GameTestHelper h, ServerPlayer p, BlockPos at, GameType mode, ItemStack tool) {
        h.getLevel().setBlockAndUpdate(at, CameraTestBlock.BLOCK.get().defaultBlockState());
        p.setGameMode(mode);
        p.setItemInHand(InteractionHand.MAIN_HAND, tool);
        p.gameMode.destroyBlock(at);
        return h.getLevel().getBlockState(at).is(CameraTestBlock.BLOCK.get());
    }

    private static void pickaxeOnly(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos at = h.absolutePos(new BlockPos(1, 2, 1));
        ServerPlayer p = player(h, at.east(2));
        try {
            var state = CameraTestBlock.BLOCK.get().defaultBlockState();
            level.setBlockAndUpdate(at, state);

            // 生存模式下挖掘进度只有镐才涨
            p.setGameMode(GameType.SURVIVAL);
            for (ItemStack tool : new ItemStack[] {ItemStack.EMPTY, new ItemStack(Items.DIAMOND_AXE), new ItemStack(Items.DIAMOND_SHOVEL), new ItemStack(Items.DIAMOND_SWORD)}) {
                p.setItemInHand(InteractionHand.MAIN_HAND, tool);
                check(h, state.getDestroyProgress(p, level, at) == 0, "不是镐却有挖掘进度：" + tool);
            }
            for (ItemStack tool : new ItemStack[] {new ItemStack(Items.WOODEN_PICKAXE), new ItemStack(Items.NETHERITE_PICKAXE)}) {
                p.setItemInHand(InteractionHand.MAIN_HAND, tool);
                check(h, state.getDestroyProgress(p, level, at) > 0, "镐没有挖掘进度：" + tool);
            }

            // 真正拆的时候也一样，创造模式不看进度，同样要拦
            check(h, survives(h, p, at, GameType.SURVIVAL, ItemStack.EMPTY), "生存模式空手拆掉了");
            check(h, survives(h, p, at, GameType.SURVIVAL, new ItemStack(Items.DIAMOND_AXE)), "生存模式用斧拆掉了");
            check(h, survives(h, p, at, GameType.CREATIVE, ItemStack.EMPTY), "创造模式空手拆掉了");
            check(h, !survives(h, p, at, GameType.CREATIVE, new ItemStack(Items.DIAMOND_PICKAXE)), "创造模式用镐拆不掉");
            check(h, !survives(h, p, at, GameType.SURVIVAL, new ItemStack(Items.DIAMOND_PICKAXE)), "生存模式用镐拆不掉");
            var drops = level.getEntitiesOfClass(ItemEntity.class, new AABB(at).inflate(2), e -> e.getItem().is(CameraTestBlock.BLOCK.get().asItem()));
            check(h, !drops.isEmpty(), "用镐拆掉后没有掉落自身");

            check(h, state.getBlock().getExplosionResistance() > 1200, "爆炸能炸掉");
            h.succeed();
        } finally {
            level.getServer().getPlayerList().remove(p);
        }
    }

    private static void reachStopsAtBlocks(GameTestHelper h) {
        var level = h.getLevel();
        BlockPos base = h.absolutePos(new BlockPos(1, 2, 1));
        Vec3 from = base.getCenter(), to = from.add(0, 10, 0);
        for (int y = 0; y <= 11; y++) level.setBlockAndUpdate(base.above(y), Blocks.AIR.defaultBlockState());

        check(h, CameraShots.reach(level, from, to).equals(to), "没有遮挡时应当走到头");

        level.setBlockAndUpdate(base.above(4), Blocks.STONE.defaultBlockState());
        Vec3 stopped = CameraShots.reach(level, from, to);
        check(h, Math.abs(stopped.y - (base.getY() + 4 - .35)) < 1e-6, "应当停在石头下方 0.35 格：" + stopped);
        check(h, stopped.x == from.x && stopped.z == from.z, "只该沿路线缩短：" + stopped);

        // 玻璃和树叶看得穿也算挡路；蜘蛛网没有碰撞，不挡
        level.setBlockAndUpdate(base.above(4), Blocks.GLASS.defaultBlockState());
        check(h, CameraShots.reach(level, from, to).y < base.getY() + 4, "玻璃应当挡住镜头");
        level.setBlockAndUpdate(base.above(4), Blocks.COBWEB.defaultBlockState());
        check(h, CameraShots.reach(level, from, to).equals(to), "蜘蛛网不该挡住镜头");

        // 头顶紧贴方块时留不出距离，就停在原地，不能往反方向退
        level.setBlockAndUpdate(base.above(1), Blocks.STONE.defaultBlockState());
        Vec3 tight = CameraShots.reach(level, base.above(1).getBottomCenter().add(0, -.1, 0), base.above(1).getBottomCenter().add(0, 9.9, 0));
        check(h, Math.abs(tight.y - (base.getY() + .9)) < 1e-6, "紧贴方块时应当原地不动：" + tight);
        h.succeed();
    }
}
