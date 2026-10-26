package dev.logos.dimension.gametest;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import com.mojang.authlib.GameProfile;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.elven.ElvenBlocks;
import dev.logos.dimension.item.NaturesForceItem;
import dev.logos.dimension.npc.ElfHealGoal;
import dev.logos.dimension.npc.ForestElf;
import dev.logos.dimension.npc.NpcEntities;
import dev.logos.dimension.npc.NpcParts;
import io.netty.channel.embedded.EmbeddedChannel;
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
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 森林精灵的世界测试：结构生成时的初始化、上下绳梯、单体与群体治疗（真实 Logos 运行时）、
 * 作为使魔治疗玩家、和僵尸互相为敌。
 */
public final class ForestElfGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS = DeferredRegister.create(Registries.TEST_FUNCTION, LogosDimension.ID);

    public static void register(IEventBus bus) {
        var tests = Map.<String, Consumer<GameTestHelper>>of(
                "forest_elf_structure_spawn", ForestElfGameTests::structureSpawn,
                "forest_elf_rope_climb", ForestElfGameTests::ropeClimb,
                "forest_elf_rope_descend", ForestElfGameTests::ropeDescend,
                "forest_elf_heal_one", ForestElfGameTests::healOne,
                "forest_elf_heal_group", ForestElfGameTests::healGroup,
                "forest_elf_familiar_heals_player", ForestElfGameTests::familiarHealsPlayer,
                "forest_elf_zombie_enmity", ForestElfGameTests::zombieEnmity);
        tests.forEach((name, test) -> FUNCTIONS.register(name, () -> test));
        FUNCTIONS.register(bus);
        bus.addListener((RegisterGameTestsEvent event) -> {
            var env = event.registerEnvironment(LogosDimension.id("forest_elf"), new TestEnvironmentDefinition.AllOf());
            for (String name : tests.keySet()) event.registerTest(LogosDimension.id(name), new FunctionGameTestInstance(
                    ResourceKey.create(Registries.TEST_FUNCTION, LogosDimension.id(name)), new TestData<>(env,
                    Identifier.withDefaultNamespace("empty"), 400, 0, true, Rotation.NONE, false, 1, 1, false, 16)));
        });
    }

    private static ForestElf elf(GameTestHelper h, int x, int y, int z, EntitySpawnReason reason) {
        ForestElf elf = NpcEntities.FOREST_ELF.get().create(h.getLevel(), reason);
        elf.snapTo(h.absoluteVec(new Vec3(x + .5, y, z + .5)));
        elf.finalizeSpawn(h.getLevel(), h.getLevel().getCurrentDifficultyAt(elf.blockPosition()), reason, null);
        h.getLevel().addFreshEntity(elf);
        keepTicking(h, elf);
        return elf;
    }

    /**
     * 测试服务器把测试放在很远的随机位置，区块有时还没进入实体运算就跑完了（实体一动不动）。
     * 那样的刻里手动替它跑一刻，区块正常运算时不重复。
     */
    private static void keepTicking(GameTestHelper h, Mob mob) {
        h.onEachTick(() -> {
            if (mob.isAlive() && !h.getLevel().isPositionEntityTicking(mob.blockPosition())) {
                mob.setOldPosAndRot();
                mob.tickCount++;
                mob.tick();
            }
        });
    }

    /**
     * 测试用玩家。同一批测试共用一个世界，留下的生存玩家会被别的测试里的僵尸当成目标，
     * 所以只需要「附近有人」的测试用创造模式，测完治疗玩家的测试把玩家移走。
     */
    private static ServerPlayer player(GameTestHelper h, Vec3 at, GameType mode) {
        var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "elf-test"), false);
        var player = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        h.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player, cookie);
        player.connection.markClientLoaded();
        player.setGameMode(mode);
        player.setPos(h.absoluteVec(at));
        return player;
    }

    private static void floor(GameTestHelper h, int size) {
        for (int x = 0; x < size; x++) for (int z = 0; z < size; z++) h.setBlock(x, 0, z, Blocks.STONE);
    }

    /** 结构放置（STRUCTURE）：外观合法且没有互斥零件、拿自然之力、常驻、有住处范围。 */
    private static void structureSpawn(GameTestHelper h) {
        floor(h, 6);
        for (int i = 0; i < 8; i++) {
            ForestElf elf = elf(h, 1 + i % 4, 1, 1 + i / 4, EntitySpawnReason.STRUCTURE);
            Map<String, String> a = elf.npcAppearance();
            h.assertTrue(a.equals(NpcParts.FOREST_ELF.sanitize(a)), "外观不合法：" + a);
            h.assertFalse("hair_hooded".equals(a.get("hair")) && "headwear_twig_crown".equals(a.get("headwear")), "兜帽和枝冠同时出现");
            h.assertTrue(elf.getMainHandItem().getItem() instanceof NaturesForceItem, "没拿自然之力");
            h.assertTrue(elf.isPersistenceRequired(), "结构里的精灵不常驻");
            h.assertTrue(elf.hasHome(), "结构里的精灵没有住处范围");
        }
        h.succeed();
    }

    /** 树屋的样子：平台在 6 格高处，只靠 (3, 1..8, 5) 的绳梯连到地面。 */
    private static void treehouse(GameTestHelper h) {
        floor(h, 8);
        int top = 6;
        for (int x = 2; x <= 5; x++) for (int z = 1; z <= 4; z++) h.setBlock(x, top, z, Blocks.OAK_PLANKS);
        h.setBlock(3, top + 3, 5, Blocks.OAK_LOG);
        for (int y = top + 2; y >= 1; y--) h.setBlock(3, y, 5, ElvenBlocks.ROPE.get());
    }

    /** 绳梯向上：精灵从地面走过去、爬上平台。 */
    private static void ropeClimb(GameTestHelper h) {
        treehouse(h);
        ForestElf elf = elf(h, 3, 1, 7, EntitySpawnReason.COMMAND);
        elf.getMainHandItem().setCount(0);
        BlockPos goal = h.absolutePos(new BlockPos(3, 7, 2));
        h.onEachTick(() -> {
            if (elf.getNavigation().isDone()) elf.getNavigation().moveTo(goal.getX() + .5, goal.getY(), goal.getZ() + .5, 1.0);
        });
        h.succeedWhen(() -> h.assertTrue(elf.getY() >= goal.getY() - .05 && elf.onGround() && elf.blockPosition().getZ() <= goal.getZ() + 1,
                "精灵还没爬上平台：" + elf.position()));
    }

    /** 绳梯向下：精灵从平台走到绳梯、滑到地面，不摔伤。 */
    private static void ropeDescend(GameTestHelper h) {
        treehouse(h);
        ForestElf elf = elf(h, 3, 7, 2, EntitySpawnReason.COMMAND);
        elf.getMainHandItem().setCount(0);
        BlockPos goal = h.absolutePos(new BlockPos(3, 1, 7));
        h.onEachTick(() -> {
            if (elf.getNavigation().isDone()) elf.getNavigation().moveTo(goal.getX() + .5, goal.getY(), goal.getZ() + .5, 1.0);
        });
        h.succeedWhen(() -> {
            h.assertTrue(elf.getY() < goal.getY() + .05 && elf.onGround() && elf.blockPosition().getZ() >= goal.getZ() - 1,
                    "精灵还没下到地面：" + elf.position());
            h.assertTrue(elf.getHealth() == elf.getMaxHealth(), "精灵是摔下来的：" + elf.getHealth());
        });
    }

    /** 单个同伴受伤：施法者用抚生之触治疗它，付 20 魔力。 */
    private static void healOne(GameTestHelper h) {
        floor(h, 8);
        player(h, new Vec3(1, 1, 1), GameType.CREATIVE);
        ForestElf caster = elf(h, 2, 1, 2, EntitySpawnReason.COMMAND);
        ForestElf hurt = elf(h, 5, 1, 2, EntitySpawnReason.COMMAND);
        hurt.setHealth(10);
        h.succeedWhen(() -> {
            h.assertTrue(hurt.getHealth() > 10, "受伤的精灵没被治疗：" + hurt.getHealth());
            h.assertTrue(caster.mana() < ForestElf.MAX_MANA || hurt.mana() < ForestElf.MAX_MANA, "没有扣魔力");
        });
    }

    /** 两个同伴受伤：用生息之环，两个都回血，付 100 魔力。 */
    private static void healGroup(GameTestHelper h) {
        floor(h, 8);
        player(h, new Vec3(1, 1, 1), GameType.CREATIVE);
        List<ForestElf> elves = List.of(elf(h, 2, 1, 2, EntitySpawnReason.COMMAND), elf(h, 5, 1, 2, EntitySpawnReason.COMMAND),
                elf(h, 2, 1, 5, EntitySpawnReason.COMMAND));
        elves.get(1).setHealth(8);
        elves.get(2).setHealth(8);
        h.succeedWhen(() -> {
            h.assertTrue(elves.get(1).getHealth() > 8 && elves.get(2).getHealth() > 8, "群体治疗没生效");
            h.assertTrue(elves.stream().anyMatch(e -> e.mana() <= ForestElf.MAX_MANA - ElfHealGoal.GROUP_COST + 10), "没有付群体治疗的魔力");
        });
    }

    /** 作为玩家的使魔：把玩家视为友方，治疗受伤的玩家。 */
    private static void familiarHealsPlayer(GameTestHelper h) {
        floor(h, 8);
        ServerPlayer player = player(h, new Vec3(5.5, 1, 2.5), GameType.SURVIVAL);
        ForestElf elf = elf(h, 2, 1, 2, EntitySpawnReason.COMMAND);
        try {
            Class.forName("dev.logos.runtime.Familiars").getMethod("bindForTest", Mob.class, ServerPlayer.class).invoke(null, elf, player);
        } catch (ReflectiveOperationException error) {
            throw h.assertionException(net.minecraft.network.chat.Component.literal("当前 Logos 没有 Familiars.bindForTest：" + error));
        }
        player.setHealth(8);
        h.succeedWhen(() -> {
            h.assertTrue(player.getHealth() > 8, "使魔精灵没治疗玩家：" + player.getHealth());
            h.getLevel().getServer().getPlayerList().remove(player);
        });
    }

    /** 阵营：僵尸以精灵为目标，精灵也以僵尸为目标。 */
    private static void zombieEnmity(GameTestHelper h) {
        floor(h, 12);
        ForestElf elf = elf(h, 2, 1, 2, EntitySpawnReason.COMMAND);
        Zombie zombie = EntityType.ZOMBIE.create(h.getLevel(), EntitySpawnReason.COMMAND);
        zombie.snapTo(h.absoluteVec(new Vec3(9.5, 1, 9.5)));
        zombie.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.LEATHER_HELMET));
        // 测的是双方选目标：精灵不拿弓（强化箭会把僵尸击飞出测试场地），双方给足生命（无敌的生物不会被当成敌人）
        elf.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, net.minecraft.world.item.ItemStack.EMPTY);
        for (var mob : List.of(zombie, elf)) {
            mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);
            mob.setHealth(1000);
        }
        h.getLevel().addFreshEntity(zombie);
        keepTicking(h, zombie);
        // 僵尸被打后会转为还手，所以记录「曾经」选中对方
        boolean[] seen = new boolean[2];
        h.onEachTick(() -> {
            seen[0] |= zombie.getTarget() == elf;
            seen[1] |= elf.getTarget() == zombie;
        });
        h.succeedWhen(() -> {
            h.assertTrue(seen[0], "僵尸没把精灵当目标：" + zombie.getTarget());
            h.assertTrue(seen[1], "精灵没把僵尸当目标：" + elf.getTarget());
        });
    }

    private ForestElfGameTests() {}
}
