package dev.logos.dimension.gametest;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.dissolve.DissolveShape;
import dev.logos.dimension.dissolve.Dissolving;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 消散在服务端的部分：东西放完才拿走、站住不动，方块立刻离开世界，手上的东西放完才清掉。 */
public final class DissolveGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS = DeferredRegister.create(Registries.TEST_FUNCTION, LogosDimension.ID);
    private static final int TICKS = 20;

    private DissolveGameTests() {}

    public static void register(IEventBus bus) {
        var tests = new LinkedHashMap<String, Consumer<GameTestHelper>>();
        tests.put("dissolve_entity_goes_when_done", DissolveGameTests::entity);
        tests.put("dissolve_blocks_leave_at_once", DissolveGameTests::blocks);
        tests.put("dissolve_held_clears_the_hand_when_done", DissolveGameTests::held);
        tests.put("dissolve_command_takes_blocks", DissolveGameTests::command);
        tests.put("dissolve_killed_slime_splits_once", DissolveGameTests::killedSlime);
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

    private static void entity(GameTestHelper h) {
        Pig pig = h.spawn(EntityType.PIG, new BlockPos(1, 2, 1));
        Dissolving.entity(pig, DissolveShape.noise(), TICKS, Dissolving.EMBER);
        check(h, pig.isNoAi(), "消散时应当站住不动");
        h.runAfterDelay(TICKS / 2, () -> check(h, pig.isAlive(), "还没放完不能拿走"));
        h.succeedWhen(() -> check(h, !pig.isAlive(), "放完应当拿走"));
    }

    /** 正在看的那只史莱姆、它分裂了几次、有没有分出空的（以前就是这个空的让服务器崩溃）。 */
    private static net.minecraft.world.entity.monster.Slime watched;
    private static int splits;
    private static boolean nullChild, listening;

    /**
     * 流火斩杀会让尸体消散（花散）。大史莱姆被打死后原版 20 刻时自己移除并分裂；消散比这长时，到点再拿走一次不能让它再分裂：
     * 以前第二次分裂时它已经被移除，分出来的全是空的，原版把空的交给 addFreshEntity，服务器崩溃。
     * 测试服务器把这个异常记进日志后接着跑，所以这里看 NeoForge 的分裂事件：只分裂一次，没有空的。
     */
    private static void killedSlime(GameTestHelper h) {
        if (!listening) {
            listening = true;
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.living.MobSplitEvent event) -> {
                if (event.getParent() != watched) return;
                splits++;
                if (event.getChildren().contains(null)) nullChild = true;
            });
        }
        var slime = h.spawn(EntityType.SLIME, new BlockPos(2, 2, 2));
        slime.setSize(4, true);
        watched = slime;
        splits = 0;
        nullChild = false;
        slime.hurtServer(h.getLevel(), h.getLevel().damageSources().genericKill(), Float.MAX_VALUE);
        check(h, slime.isDeadOrDying(), "史莱姆没被打死");
        Dissolving.entity(slime, DissolveShape.noise(), 10, Dissolving.EMBER);
        // 原版在死后第 20 刻做的事（移除并分裂）。直接做，不等实体刻：测试里实体什么时候开始走刻不一定，等的话有时消散先到点，就碰不到这个问题
        slime.remove(net.minecraft.world.entity.Entity.RemovalReason.KILLED);
        h.runAfterDelay(20, () -> {
            check(h, slime.isRemoved(), "史莱姆没有被拿走");
            check(h, splits == 1 && !nullChild, "应当只分裂一次、不分出空的：分裂 " + splits + " 次" + (nullChild ? "，分出了空的" : ""));
            watched = null;
            h.succeed();
        });
    }

    private static void blocks(GameTestHelper h) {
        BlockPos stone = new BlockPos(1, 2, 1), air = new BlockPos(2, 2, 1);
        h.setBlock(stone, Blocks.STONE);
        Dissolving.blocks(h.getLevel(), List.of(h.absolutePos(stone), h.absolutePos(air)), DissolveShape.sphere(.5f, .5f, .5f), TICKS, Dissolving.EMBER);
        h.assertBlockPresent(Blocks.AIR, stone);
        h.succeed();
    }

    /** 真的跑一次命令：一片方块被拿走，范围太大或者形状名不对时什么都不动。 */
    private static void command(GameTestHelper h) {
        BlockPos a = h.absolutePos(new BlockPos(1, 2, 1)), b = h.absolutePos(new BlockPos(2, 2, 1));
        h.setBlock(new BlockPos(1, 2, 1), Blocks.STONE);
        h.setBlock(new BlockPos(2, 2, 1), Blocks.STONE);
        var server = h.getLevel().getServer();
        var source = server.createCommandSourceStack().withLevel(h.getLevel()).withSuppressedOutput();
        String box = a.getX() + " " + a.getY() + " " + a.getZ() + " " + b.getX() + " " + b.getY() + " " + b.getZ();
        server.getCommands().performPrefixedCommand(source, "alchemy dissolve blocks " + box + " nonsense");
        h.assertBlockPresent(Blocks.STONE, new BlockPos(1, 2, 1));
        server.getCommands().performPrefixedCommand(source, "alchemy dissolve blocks " + a.getX() + " " + a.getY() + " " + a.getZ() + " " + (a.getX() + 20) + " " + (a.getY() + 20) + " " + (a.getZ() + 20) + " noise");
        h.assertBlockPresent(Blocks.STONE, new BlockPos(1, 2, 1));
        server.getCommands().performPrefixedCommand(source, "alchemy dissolve blocks " + box + " noise 1");
        h.assertBlockPresent(Blocks.AIR, new BlockPos(1, 2, 1));
        h.assertBlockPresent(Blocks.AIR, new BlockPos(2, 2, 1));
        h.succeed();
    }

    private static void held(GameTestHelper h) {
        Zombie zombie = h.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(1, 2, 1));
        zombie.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        zombie.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        Dissolving.held(zombie, InteractionHand.MAIN_HAND, DissolveShape.parse("tip"), TICKS, Dissolving.EMBER);
        h.runAfterDelay(TICKS / 2, () -> check(h, !zombie.getMainHandItem().isEmpty(), "还没放完不能清"));
        h.succeedWhen(() -> {
            check(h, zombie.getMainHandItem().isEmpty(), "放完应当清掉这只手");
            check(h, zombie.isAlive(), "拿东西的人不消散");
            check(h, !zombie.getOffhandItem().isEmpty(), "另一只手不受影响");
        });
    }
}
