package dev.logos.dimension.gametest;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import com.mojang.authlib.GameProfile;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.elven.ElvenBlocks;
import dev.logos.dimension.item.NaturesForceItem;
import dev.logos.dimension.item.NatureArrow;
import dev.logos.dimension.item.ElfSpearItem;
import dev.logos.dimension.item.AncientEmbraceItem;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
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
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
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
        var tests = new java.util.HashMap<String, Consumer<GameTestHelper>>(Map.of(
                "forest_elf_structure_spawn", ForestElfGameTests::structureSpawn,
                "forest_elf_rope_climb", ForestElfGameTests::ropeClimb,
                "forest_elf_rope_descend", ForestElfGameTests::ropeDescend,
                "forest_elf_heal_one", ForestElfGameTests::healOne,
                "forest_elf_heal_group", ForestElfGameTests::healGroup,
                "forest_elf_familiar_heals_player", ForestElfGameTests::familiarHealsPlayer,
                "forest_elf_zombie_enmity", ForestElfGameTests::zombieEnmity,
                "forest_elf_weapon_only_in_combat", ForestElfGameTests::weaponOnlyInCombat,
                "forest_elf_death_lingers", ForestElfGameTests::deathLingers));
        tests.put("forest_elf_spear_shield_vanilla", h -> spearCombat(h, false, true));
        tests.put("forest_elf_spear_shield_custom", h -> spearCombat(h, true, true));
        tests.put("forest_elf_spear_alone", h -> spearCombat(h, false, false));
        tests.put("forest_elf_shield_reaction", ForestElfGameTests::shieldReaction);
        tests.put("forest_elf_shield_delay", ForestElfGameTests::shieldDelay);
        tests.put("forest_elf_shield_disable", ForestElfGameTests::shieldDisable);
        tests.put("forest_elf_top_guards", ForestElfGameTests::topGuards);
        tests.put("forest_elf_bow_shield_recovery", ForestElfGameTests::bowShieldRecovery);
        tests.put("forest_elf_gear_stow_vanilla", h -> gearStow(h, false));
        tests.put("forest_elf_gear_stow_custom", h -> gearStow(h, true));
        tests.put("forest_elf_arrow_passthrough", h -> arrowPassthrough(h, Items.ARROW));
        tests.put("forest_elf_potion_arrow_passthrough", h -> arrowPassthrough(h, Items.TIPPED_ARROW));
        tests.put("forest_elf_spectral_arrow_passthrough", h -> arrowPassthrough(h, Items.SPECTRAL_ARROW));
        tests.put("forest_elf_arrow_collision_scope", ForestElfGameTests::arrowCollisionScope);
        tests.forEach((name, test) -> FUNCTIONS.register(name, () -> test));
        FUNCTIONS.register(bus);
        bus.addListener((RegisterGameTestsEvent event) -> {
            var env = event.registerEnvironment(LogosDimension.id("forest_elf"), new TestEnvironmentDefinition.AllOf());
            for (String name : tests.keySet()) event.registerTest(LogosDimension.id(name), new FunctionGameTestInstance(
                    ResourceKey.create(Registries.TEST_FUNCTION, LogosDimension.id(name)), new TestData<>(env,
                    Identifier.withDefaultNamespace("empty"), 400, 0, true, Rotation.NONE, false, 1, 1, false, 32)));
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
     * 测试服务器把测试放在很远的随机位置，区块可能已标为运算中，但新实体尚未进入运算列表。
     * 比较实体实际刻数，只在上一轮至今没被世界推进时补一刻；不能仅依赖区块状态。
     */
    private static void keepTicking(GameTestHelper h, Mob mob) {
        int[] lastTick = {mob.tickCount};
        h.onEachTick(() -> {
            // 死亡中的生物也要推进（死亡计时、消失），直到被移除
            if (!mob.isRemoved() && mob.tickCount == lastTick[0]) {
                mob.setOldPosAndRot();
                mob.tickCount++;
                mob.tick();
            }
            lastTick[0] = mob.tickCount;
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
        for (int x = 0; x < size; x++) for (int z = 0; z < size; z++) {
            h.setBlock(x, 0, z, Blocks.STONE);
            for (int y = 1; y <= 10; y++) h.setBlock(x, y, z, Blocks.AIR);
        }
    }

    private static Zombie dummy(GameTestHelper h, double x, double z) {
        Zombie mob = EntityType.ZOMBIE.create(h.getLevel(), EntitySpawnReason.COMMAND);
        mob.snapTo(h.absoluteVec(new Vec3(x, 1, z)));
        mob.setNoAi(true);
        mob.setPersistenceRequired();
        mob.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.LEATHER_HELMET));
        mob.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000);
        mob.setHealth(1000);
        h.getLevel().addFreshEntity(mob);
        keepTicking(h, mob);
        return mob;
    }

    /** 从贴身盲区开始，真正后撤、刺中并持续举盾，覆盖原版装备和自定义装备。 */
    private static void spearCombat(GameTestHelper h, boolean custom, boolean shield) {
        floor(h, 16);
        ForestElf elf = elf(h, 7, 1, 7, EntitySpawnReason.COMMAND);
        elf.setWeapon(new ItemStack(custom ? ElfSpearItem.ITEM.get() : Items.IRON_SPEAR));
        if (shield) elf.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(custom ? AncientEmbraceItem.ITEM.get() : Items.SHIELD));
        Zombie target = dummy(h, 7.5, 8.5);
        elf.setTarget(target);
        Vec3 start = elf.position();
        boolean[] hit = {false}, guarded = {false}, outer = {false};
        h.onEachTick(() -> {
            // 木桩不被击退，避免把击退误判为精灵后撤。
            target.setDeltaMovement(Vec3.ZERO);
            target.setPos(h.absoluteVec(new Vec3(7.5, 1, 8.5)));
            double distance = Math.sqrt(target.getBoundingBox().distanceToSqr(elf.getEyePosition()));
            var range = elf.getAttackRangeWith(elf.getMainHandItem());
            outer[0] |= distance >= range.effectiveMaxRange(elf) - .55 && distance <= range.effectiveMaxRange(elf) + .125;
            if (target.getHealth() < 1000) {
                hit[0] = true;
                if (shield && elf.isBlocking()) {
                    // 从正面实际打一次，验证刺击期间盾没有被收起。
                    float hp = elf.getHealth();
                    elf.invulnerableTime = 0;
                    elf.hurtServer(h.getLevel(), h.getLevel().damageSources().mobAttack(target), 3);
                    guarded[0] |= elf.getHealth() == hp;
                }
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(hit[0], "还没有实际刺中目标，距离=" + elf.distanceTo(target) + " 位置=" + elf.position()
                    + " 目标正确=" + (elf.getTarget() == target) + " 战斗=" + elf.isAggressive() + " 路径=" + elf.getNavigation().getPath()
                    + " 可见=" + elf.hasLineOfSight(target) + " 范围=" + elf.getAttackRangeWith(elf.getMainHandItem())
                    + " 终点=" + (elf.getNavigation().getPath() == null ? "null" : elf.getNavigation().getPath().getTarget())
                    + " 末节点=" + (elf.getNavigation().getPath() == null ? "null" : elf.getNavigation().getPath().getEndNode())
                    + " 落地=" + elf.onGround() + " 导航完成=" + elf.getNavigation().isDone()
                    + " 后方脚下=" + h.getLevel().getBlockState(elf.blockPosition().offset(0,-1,-1)));
            h.assertTrue(start.distanceTo(elf.position()) > .7, "没有从贴身盲区后撤");
            h.assertTrue(outer[0], "没有回到长矛有效射程外沿");
            h.assertTrue(!shield || guarded[0], "戳刺时盾没有真正挡住伤害");
        });
    }

    private static ForestElf shieldElf(GameTestHelper h, boolean custom) {
        floor(h, 10);
        ForestElf elf = elf(h, 4, 1, 4, EntitySpawnReason.COMMAND);
        elf.setNoAi(true);
        elf.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(custom ? AncientEmbraceItem.ITEM.get() : Items.SHIELD));
        elf.setYRot(0);
        elf.setYHeadRot(0);
        return elf;
    }

    /** 未预先举盾，古木盾第一击就起盾；背后攻击仍然造成伤害。 */
    private static void shieldReaction(GameTestHelper h) {
        ForestElf elf = shieldElf(h, true);
        Zombie front = dummy(h, 4.5, 7.5);
        elf.hurtServer(h.getLevel(), h.getLevel().damageSources().mobAttack(front), 3);
        h.assertTrue(elf.isBlocking() && elf.getHealth() == 20, "古木盾没在首击起盾格挡");
        Zombie rear = dummy(h, 4.5, 1.5);
        elf.invulnerableTime = 0;
        elf.hurtServer(h.getLevel(), h.getLevel().damageSources().mobAttack(rear), 3);
        h.assertTrue(elf.getHealth() < 20, "背后攻击不应被挡住");
        h.succeed();
    }

    /** 原版盾的五刻前摇保留，前摇后才生效。 */
    private static void shieldDelay(GameTestHelper h) {
        ForestElf elf = shieldElf(h, false);
        Zombie front = dummy(h, 4.5, 7.5);
        elf.hurtServer(h.getLevel(), h.getLevel().damageSources().mobAttack(front), 3);
        h.assertTrue(elf.isUsingItem() && !elf.isBlocking() && elf.getHealth() < 20, "原版盾的起手前摇被跳过");
        h.runAfterDelay(7, () -> {
            float hp = elf.getHealth();
            elf.invulnerableTime = 0;
            elf.hurtServer(h.getLevel(), h.getLevel().damageSources().mobAttack(front), 3);
            h.assertTrue(elf.isBlocking() && elf.getHealth() == hp, "前摇结束后没有挡住伤害");
            h.succeed();
        });
    }

    /** 斧头禁盾后，紧接着的一击不能被自动重新举盾挡住。 */
    private static void shieldDisable(GameTestHelper h) {
        ForestElf elf = shieldElf(h, true);
        Zombie front = dummy(h, 4.5, 7.5);
        front.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
        elf.hurtServer(h.getLevel(), h.getLevel().damageSources().mobAttack(front), 3);
        h.assertTrue(!elf.isUsingItem() && elf.getHealth() == 20, "斧头没有禁用盾牌");
        elf.invulnerableTime = 0;
        elf.hurtServer(h.getLevel(), h.getLevel().damageSources().mobAttack(front), 3);
        h.assertTrue(elf.getHealth() < 20 && !elf.isBlocking(), "禁盾后又立即举起盾牌");
        h.succeed();
    }

    /** 直接读取打包模板中的两位顶楼守卫，并走真实结构初始化与存档重载。 */
    private static void topGuards(GameTestHelper h) {
        var template = h.getLevel().getStructureManager().getOrCreate(LogosDimension.id("elven/tile_3_2_3"));
        var entities = template.save(new CompoundTag()).getListOrEmpty("entities");
        int count = 0;
        for (var entry : entities) {
            CompoundTag nbt = ((CompoundTag) entry).getCompoundOrEmpty("nbt");
            if (!nbt.getBooleanOr("WorldTreeGuard", false)) continue;
            ForestElf elf = NpcEntities.FOREST_ELF.get().create(h.getLevel(), EntitySpawnReason.STRUCTURE);
            elf.load(TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), nbt));
            elf.snapTo(h.absoluteVec(new Vec3(2 + count, 1, 2)));
            elf.finalizeSpawn(h.getLevel(), h.getLevel().getCurrentDifficultyAt(elf.blockPosition()), EntitySpawnReason.STRUCTURE, null);
            h.assertTrue(elf.weapon().is(ElfSpearItem.ITEM.get()) && elf.shield().is(AncientEmbraceItem.ITEM.get())
                    && elf.getMainHandItem().isEmpty() && elf.getOffhandItem().isEmpty(), "顶楼守卫没有收起默认矛盾装备");
            h.assertTrue(elf.isPersistenceRequired() && elf.hasHome(), "守卫没有常驻或住处");
            var saved = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, h.getLevel().registryAccess());
            elf.saveWithoutId(saved);
            ForestElf loaded = NpcEntities.FOREST_ELF.get().create(h.getLevel(), EntitySpawnReason.LOAD);
            loaded.load(TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), saved.buildResult()));
            h.assertTrue(loaded.getMainHandItem().isEmpty() && loaded.getOffhandItem().isEmpty()
                    && loaded.weapon().is(ElfSpearItem.ITEM.get()) && loaded.shield().is(AncientEmbraceItem.ITEM.get()),
                    "守卫存档重载后丢失装备或没有保持收起状态");
            count++;
        }
        h.assertTrue(count == 2, "顶楼守卫数量不为二：" + count);
        h.succeed();
    }

    /** 盾牌计时不能被当成拉弓计时；短暂防御后仍按原有弓箭逻辑射击。 */
    private static void bowShieldRecovery(GameTestHelper h) {
        floor(h, 16);
        ForestElf elf = elf(h, 3, 1, 3, EntitySpawnReason.COMMAND);
        elf.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(AncientEmbraceItem.ITEM.get()));
        Zombie target = dummy(h, 3.5, 12.5);
        elf.setTarget(target);
        elf.setYHeadRot(0);
        elf.hurtServer(h.getLevel(), h.getLevel().damageSources().mobAttack(target), 3);
        h.assertTrue(elf.isBlocking(), "弓手受击没举盾");
        h.runAfterDelay(25, () -> h.assertTrue(target.getHealth() == 1000 && !hasArrow(elf), "举盾被错误地算成满弓并放箭"));
        // 远处测试区的投射物可能尚未进入世界运算；确认真实箭实体已射出即可，不用其是否推进来判断弓 AI。
        h.succeedWhen(() -> h.assertTrue((target.getHealth() < 1000 || hasArrow(elf)) && !elf.isBlocking(),
                "短暂防御结束后没有恢复射箭 " + bowState(elf, target)));
    }

    private static boolean hasArrow(ForestElf elf) {
        return !elf.level().getEntitiesOfClass(AbstractArrow.class, elf.getBoundingBox().inflate(48), arrow -> arrow.getOwner() == elf).isEmpty();
    }

    /** 直接换掉默认弓，验证平静收起、战斗拿出、五秒警戒、再收起及物品数据存档。 */
    private static void gearStow(GameTestHelper h, boolean custom) {
        floor(h, 12);
        ForestElf elf = elf(h, 3, 1, 3, EntitySpawnReason.COMMAND);
        elf.setNoAi(true);
        ItemStack spear = new ItemStack(custom ? ElfSpearItem.ITEM.get() : Items.IRON_SPEAR);
        ItemStack shield = new ItemStack(custom ? AncientEmbraceItem.ITEM.get() : Items.SHIELD);
        spear.setDamageValue(3);
        shield.setDamageValue(7);
        shield.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("保留的盾牌"));
        elf.setItemSlot(EquipmentSlot.MAINHAND, spear);
        elf.setItemSlot(EquipmentSlot.OFFHAND, shield);
        Zombie target = dummy(h, 8.5, 8.5);
        h.runAfterDelay(3, () -> {
            h.assertTrue(elf.getMainHandItem().isEmpty() && elf.getOffhandItem().isEmpty(), "平静时没有收起长矛和盾");
            h.assertTrue(elf.weapon() == spear && elf.shield() == shield, "外部换装后仍藏着原来的弓，或复制了装备");
            elf.hurtServer(h.getLevel(), h.getLevel().damageSources().mobAttack(target), 3);
            h.assertTrue(elf.getOffhandItem() == shield && elf.isUsingItem(), "装备收起时遭受攻击，没有立即拿出盾尝试格挡");
            elf.setTarget(target);
        });
        h.runAfterDelay(10, () -> {
            h.assertTrue(elf.getMainHandItem() == spear && elf.getOffhandItem() == shield && elf.isBlocking(), "战斗时没有拿出整套矛盾");
            target.discard();
            elf.setTarget(null);
        });
        h.runAfterDelay(50, () -> h.assertTrue(elf.getMainHandItem() == spear && elf.getOffhandItem() == shield, "警戒期内提前收起装备"));
        h.runAfterDelay(120, () -> {
            h.assertTrue(elf.getMainHandItem().isEmpty() && elf.getOffhandItem().isEmpty() && !elf.isUsingItem(), "警戒结束后没有收起整套装备");
            var saved = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, h.getLevel().registryAccess());
            elf.saveWithoutId(saved);
            ForestElf loaded = NpcEntities.FOREST_ELF.get().create(h.getLevel(), EntitySpawnReason.LOAD);
            loaded.load(TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), saved.buildResult()));
            h.assertTrue(loaded.weapon().is(spear.getItem()) && loaded.weapon().getDamageValue() == 3
                    && loaded.shield().is(shield.getItem()) && loaded.shield().getDamageValue() == 7
                    && loaded.shield().get(DataComponents.CUSTOM_NAME).getString().equals("保留的盾牌"), "收起装备存档后数据丢失");
            // 模拟上一版存档中手持长矛、内部还藏着默认弓的状态。
            loaded.setItemSlot(EquipmentSlot.MAINHAND, spear.copy());
            loaded.setItemSlot(EquipmentSlot.OFFHAND, shield.copy());
            var legacy = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, h.getLevel().registryAccess());
            loaded.saveWithoutId(legacy);
            legacy.store("Weapon", ItemStack.CODEC, new ItemStack(NaturesForceItem.ITEM.get()));
            ForestElf migrated = NpcEntities.FOREST_ELF.get().create(h.getLevel(), EntitySpawnReason.LOAD);
            migrated.load(TagValueInput.create(ProblemReporter.DISCARDING, h.getLevel().registryAccess(), legacy.buildResult()));
            migrated.aiStep();
            h.assertTrue(migrated.getMainHandItem().isEmpty() && migrated.getOffhandItem().isEmpty()
                    && migrated.weapon().is(spear.getItem()) && migrated.shield().is(shield.getItem()), "旧存档换装未迁移，仍被默认弓阻止收起");
            h.succeed();
        });
    }

    private static String bowState(ForestElf elf, Zombie target) {
        return "tick=" + elf.tickCount + " hp=" + elf.getHealth() + " target=" + (elf.getTarget() == target)
                + " hand=" + elf.getMainHandItem() + " stored=" + elf.weapon() + " using=" + elf.getUseItem()
                + " useTicks=" + elf.getTicksUsingItem() + " visible=" + elf.hasLineOfSight(target)
                + " distance=" + elf.distanceTo(target) + " targetHp=" + target.getHealth();
    }

    /** 结构放置（STRUCTURE）：外观合法且没有互斥零件、拿自然之力、常驻、有住处范围。 */
    private static void structureSpawn(GameTestHelper h) {
        floor(h, 6);
        for (int i = 0; i < 8; i++) {
            ForestElf elf = elf(h, 1 + i % 4, 1, 1 + i / 4, EntitySpawnReason.STRUCTURE);
            Map<String, String> a = elf.npcAppearance();
            h.assertTrue(a.equals(NpcParts.FOREST_ELF.sanitize(a)), "外观不合法：" + a);
            h.assertFalse("hair_hooded".equals(a.get("hair")) && "headwear_twig_crown".equals(a.get("headwear")), "兜帽和枝冠同时出现");
            h.assertTrue(elf.weapon().getItem() instanceof NaturesForceItem, "没有自然之力");
            h.assertTrue(elf.getMainHandItem().isEmpty(), "平时不该把弓拿在手上");
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
        zombie.setPersistenceRequired();
        zombie.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.LEATHER_HELMET));
        // 测的是双方选目标：精灵不拿弓（强化箭会把僵尸击飞出测试场地），双方给足生命（无敌的生物不会被当成敌人）
        elf.setWeapon(net.minecraft.world.item.ItemStack.EMPTY);
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

    /** 平时弓收着；有敌人时拿出来并拉弓；敌人没了、警戒期过后再收起来。 */
    private static void weaponOnlyInCombat(GameTestHelper h) {
        floor(h, 12);
        ForestElf elf = elf(h, 2, 1, 2, EntitySpawnReason.COMMAND);
        h.assertTrue(elf.getMainHandItem().isEmpty(), "平时不该把弓拿在手上");
        Zombie zombie = EntityType.ZOMBIE.create(h.getLevel(), EntitySpawnReason.COMMAND);
        zombie.snapTo(h.absoluteVec(new Vec3(9.5, 1, 9.5)));
        zombie.setPersistenceRequired();
        zombie.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.LEATHER_HELMET));
        zombie.setNoAi(true);
        h.getLevel().addFreshEntity(zombie);
        int[] phase = {0};
        int[] calm = {0};
        h.onEachTick(() -> {
            // 拿出来的弓真的能拉（换装备不会打断拉弓）
            if (phase[0] == 0 && elf.getTarget() == zombie && elf.isUsingItem() && elf.getUseItem().getItem() instanceof NaturesForceItem) {
                phase[0] = 1;
                zombie.discard();
            } else if (phase[0] == 1 && elf.getTarget() == null) {
                phase[0] = 2;
            } else if (phase[0] == 2) {
                calm[0]++;
                // 警戒期内弓还拿着
                if (calm[0] == ForestElf.ALERT_TICKS / 2 && elf.getMainHandItem().isEmpty())
                    throw h.assertionException(net.minecraft.network.chat.Component.literal("警戒期还没过就收起了弓"));
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(phase[0] == 2 && calm[0] > ForestElf.ALERT_TICKS, "还没走完：战斗 → 警戒 → 平静（" + phase[0] + "） " + bowState(elf, zombie));
            h.assertTrue(elf.getMainHandItem().isEmpty() && elf.weapon().getItem() instanceof NaturesForceItem, "警戒过后没把弓收起来");
        });
    }

    /** 死亡：受击与死亡动画播完前不消失；死亡动画开始时换上茫然挂泪的表情；倒下后不再转动；DEATH_TICKS 刻后消失。 */
    private static void deathLingers(GameTestHelper h) {
        floor(h, 6);
        ForestElf elf = elf(h, 2, 1, 2, EntitySpawnReason.COMMAND);
        elf.hurtServer(h.getLevel(), h.getLevel().damageSources().generic(), 1000);
        h.assertTrue(elf.isDeadOrDying(), "一击没有致命");
        float[] facing = {Float.NaN};
        int[] seen = {0};
        h.onEachTick(() -> {
            if (elf.isRemoved()) return;
            // 模拟击退滑动和头转向：倒下后朝向仍要保持不变
            elf.setDeltaMovement(.15, 0, .2);
            elf.setYHeadRot(elf.getYHeadRot() + 30);
            // 死后第一刻结束时才记下朝向，从第二刻起比较
            if (elf.deathTime < 2) return;
            if (Float.isNaN(facing[0])) facing[0] = elf.yBodyRot;
            else if (Math.abs(elf.yBodyRot - facing[0]) > .01f || Math.abs(elf.getYRot() - facing[0]) > .01f)
                throw h.assertionException(net.minecraft.network.chat.Component.literal("倒下后转了方向：" + facing[0] + " → " + elf.yBodyRot));
            seen[0] = elf.deathTime;
            boolean face = ForestElf.DEATH_EXPRESSION.equals(elf.npcExpression());
            if (face != elf.deathTime >= ForestElf.DEATH_FACE_TICKS)
                throw h.assertionException(net.minecraft.network.chat.Component.literal("表情切换时间不对：" + elf.deathTime + " " + face));
        });
        h.succeedWhen(() -> {
            h.assertTrue(elf.isRemoved(), "还没消失：" + elf.deathTime);
            // 测试每刻的检查可能排在精灵这一刻之前或之后，最后看到的死亡刻数差一刻
            h.assertTrue(seen[0] >= ForestElf.DEATH_TICKS - 2, "消失得太早：" + seen[0]);
        });
    }

    /** 自然之力箭同一刻穿过两个同族，再命中后方敌人；药效也不能落在同伴身上。 */
    private static void arrowPassthrough(GameTestHelper h, net.minecraft.world.item.Item ammoItem) {
        floor(h, 12);
        ForestElf shooter = elf(h, 1, 1, 2, EntitySpawnReason.COMMAND);
        ForestElf first = elf(h, 3, 1, 2, EntitySpawnReason.COMMAND);
        ForestElf second = elf(h, 5, 1, 2, EntitySpawnReason.COMMAND);
        for (ForestElf elf : List.of(shooter, first, second)) elf.setNoAi(true);
        Zombie target = dummy(h, 8.5, 2.5);
        ItemStack ammo = new ItemStack(ammoItem);
        if (ammoItem == Items.TIPPED_ARROW) ammo.set(DataComponents.POTION_CONTENTS, new PotionContents(Potions.SLOWNESS));
        AbstractArrow arrow = elfArrow(h, shooter, target, ammo);
        h.assertTrue(arrow.getOwner() == shooter && ((NatureArrow) arrow).natureStage() == 4, "没有发射精灵的自然之力强化箭");
        arrow.tick();
        for (ForestElf ally : List.of(first, second)) {
            h.assertTrue(ally.getHealth() == ally.getMaxHealth(), "箭伤到了挡在前面的精灵");
            h.assertFalse(ally.hasEffect(MobEffects.SLOWNESS) || ally.hasEffect(MobEffects.GLOWING), "同伴受到箭矢药效");
            h.assertTrue(ally.getDeltaMovement().lengthSqr() == 0, "同伴受到箭矢击退");
        }
        h.assertTrue(target.getHealth() < 1000, "穿过同伴后没有在同一刻命中敌人");
        if (ammoItem != Items.ARROW) h.assertTrue(target.hasEffect(ammoItem == Items.TIPPED_ARROW ? MobEffects.SLOWNESS : MobEffects.GLOWING), "穿过同伴后丢失了箭矢药效");
        arrow.discard();
        // 同伴后面有墙时，仍由墙挡住，不能连方块也穿过。
        for (int y = 1; y <= 3; y++) h.setBlock(7, y, 2, Blocks.STONE);
        float health = target.getHealth();
        target.invulnerableTime = 0;
        arrow = elfArrow(h, shooter, target, ammo);
        arrow.tick();
        h.assertTrue(target.getHealth() == health && arrow.getX() < h.absolutePos(new BlockPos(8, 1, 2)).getX(), "箭穿过了同伴后面的墙");
        arrow.discard();
        h.succeed();
    }

    private static AbstractArrow elfArrow(GameTestHelper h, ForestElf shooter, Zombie target, ItemStack ammo) {
        var bow = NaturesForceItem.ITEM.get();
        if (ammo.is(Items.ARROW)) {
            bow.shootMob(h.getLevel(), shooter, target, new ItemStack(bow), InteractionHand.MAIN_HAND);
            return h.getLevel().getEntitiesOfClass(AbstractArrow.class, shooter.getBoundingBox().inflate(1)).getFirst();
        }
        // 生物默认只提供普通箭；另行创建两种原版特效箭，验证同一碰撞规则与命中药效。
        var arrow = ((ArrowItem) ammo.getItem()).createArrow(h.getLevel(), ammo, shooter, new ItemStack(bow));
        bow.customArrow(arrow, ammo, new ItemStack(bow));
        ((NatureArrow) arrow).natureStage(4);
        ((NatureArrow) arrow).natureDamageScale(3d / 8d);
        arrow.shoot(target.getX() - arrow.getX(), target.getY(1d / 3d) - arrow.getY(), target.getZ() - arrow.getZ(), 8, 0);
        h.getLevel().addFreshEntity(arrow);
        return arrow;
    }

    /** 只豁免精灵的自然之力箭：精灵普通弓、骷髅和玩家的箭仍可命中精灵。 */
    private static void arrowCollisionScope(GameTestHelper h) {
        floor(h, 8);
        ForestElf target = elf(h, 4, 1, 2, EntitySpawnReason.COMMAND);
        target.setNoAi(true);
        target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100);
        ForestElf elf = NpcEntities.FOREST_ELF.get().create(h.getLevel(), EntitySpawnReason.COMMAND);
        elf.setPos(h.absoluteVec(new Vec3(1.5, 1, 2.5)));
        var skeleton = EntityType.SKELETON.create(h.getLevel(), EntitySpawnReason.COMMAND);
        skeleton.setPos(elf.position());
        var player = player(h, new Vec3(1.5, 1, 5.5), GameType.SURVIVAL);
        try {
            for (var owner : List.of(elf, skeleton, player)) {
                target.setHealth(target.getMaxHealth());
                target.invulnerableTime = 0;
                target.setDeltaMovement(Vec3.ZERO);
                ItemStack bow = new ItemStack(owner == elf ? Items.BOW : NaturesForceItem.ITEM.get());
                var arrow = ((ArrowItem) Items.ARROW).createArrow(h.getLevel(), new ItemStack(Items.ARROW), owner, bow);
                if (owner != elf) ((NatureArrow) arrow).natureStage(4);
                arrow.setPos(elf.getX(), target.getY() + .8, target.getZ());
                arrow.setDeltaMovement(6, 0, 0);
                h.getLevel().addFreshEntity(arrow);
                arrow.tick();
                h.assertTrue(target.getHealth() < target.getMaxHealth(), "误让其他来源的箭穿过精灵：" + owner.getType());
                arrow.discard();
            }
        } finally {
            h.getLevel().getServer().getPlayerList().remove(player);
        }
        h.succeed();
    }

    private ForestElfGameTests() {}
}
