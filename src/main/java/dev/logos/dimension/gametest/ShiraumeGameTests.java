package dev.logos.dimension.gametest;

import com.mojang.authlib.GameProfile;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.item.LongswordItem;
import dev.logos.dimension.item.RyukaItem;
import dev.logos.dimension.longsword.LongswordCombat;
import dev.logos.dimension.shiraume.Shiraume;
import dev.logos.dimension.shiraume.ShiraumeBeast;
import dev.logos.dimension.shiraume.ShiraumeEntities;
import dev.logos.dimension.shiraume.ShiraumeStrike;
import dev.logos.runtime.Casting;
import dev.logos.runtime.Charging;
import dev.logos.runtime.PlayerMagic;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.LinkedHashMap;
import java.util.List;
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
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.golem.IronGolem;
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
 * 白梅战这一轮的服务端机制（docs/SHIRAUME_BOSS.md）：一字斩破盾、流火挡不破，突き清两个增益，拴不住，
 * 术式打够了被盯上、蓄力时挨斩法，狐形大跳落在标记的地方。
 * 只跑这些：{@code -PdimensionTests=logos_dimension:shiraume_*}。
 */
public final class ShiraumeGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS = DeferredRegister.create(Registries.TEST_FUNCTION, LogosDimension.ID);

    public static void register(IEventBus bus) {
        Map<String, Consumer<GameTestHelper>> tests = new LinkedHashMap<>();
        tests.put("shiraume_guard_break", ShiraumeGameTests::guardBreak);
        tests.put("shiraume_guard_break_spares_ryuka", ShiraumeGameTests::guardBreakSparesRyuka);
        tests.put("shiraume_thrust_purges_two", ShiraumeGameTests::thrustPurgesTwo);
        tests.put("shiraume_cannot_be_leashed", ShiraumeGameTests::cannotBeLeashed);
        tests.put("shiraume_spell_lock_and_counter", ShiraumeGameTests::spellLockAndCounter);
        tests.put("shiraume_counter_death_message", ShiraumeGameTests::counterDeathMessage);
        tests.put("shiraume_beast_pounce_lands_on_mark", ShiraumeGameTests::beastPounceLandsOnMark);
        tests.put("shiraume_beast_bite_parried", ShiraumeGameTests::beastBiteParried);
        tests.put("shiraume_beast_paw_breaks_guard", ShiraumeGameTests::beastPawBreaksGuard);
        tests.put("shiraume_beast_pounce_land_breaks_guard", ShiraumeGameTests::beastPounceLandBreaksGuard);
        tests.put("shiraume_rage_ring_jumpable", ShiraumeGameTests::rageRingJumpable);
        tests.put("shiraume_ichimonji_wave", ShiraumeGameTests::ichimonjiWave);
        tests.put("shiraume_ichimonji_turns_when_behind", ShiraumeGameTests::ichimonjiTurnsWhenBehind);
        tests.forEach((name, test) -> FUNCTIONS.register(name, () -> test));
        FUNCTIONS.register(bus);
        bus.addListener((RegisterGameTestsEvent event) -> {
            // 每个测试单独一批：她会冲、会跳，同一批里会打到隔壁
            for (var name : tests.keySet()) {
                var env = event.registerEnvironment(LogosDimension.id(name), new TestEnvironmentDefinition.AllOf());
                event.registerTest(LogosDimension.id(name), new FunctionGameTestInstance(
                        ResourceKey.create(Registries.TEST_FUNCTION, LogosDimension.id(name)), new TestData<>(env,
                        Identifier.withDefaultNamespace("empty"), 300, 0, true, Rotation.NONE, false, 1, 1, false, 24)));
            }
        });
    }

    /**
     * 铺一块石头地（相对 x -4–18、z -6–10），把它跨的区块强制加载：测试框架只替测试结构本身那一小块保持实体刻，
     * 她冲出去、跳出去进了别的区块就不动了。返回强制加载的区块，测完放开。
     */
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

    private static void release(GameTestHelper h, List<ChunkPos> chunks) {
        for (ChunkPos c : chunks) h.getLevel().setChunkForced(c.x(), c.z(), false);
    }

    /** 一块石头地；玩家站在 (2, 1, 2) 面朝 +X，嵌入连接，逐刻更新装备与冷却，位置钉住。 */
    private record Fixture(GameTestHelper h, ServerPlayer p, Vec3 origin, List<ChunkPos> chunks) {
        static Fixture create(GameTestHelper h, ItemStack main) {
            return create(h, main, new Vec3(2.5, 1, 2.5));
        }

        /** 玩家钉在相对位置 {@code at}（不落地：y 高出地面就是跳在半空）。 */
        static Fixture create(GameTestHelper h, ItemStack main, Vec3 at) {
            var chunks = ground(h);
            var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "shiraume-test"), false);
            var p = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
            var connection = new Connection(PacketFlow.SERVERBOUND);
            new EmbeddedChannel(connection);
            h.getLevel().getServer().getPlayerList().placeNewPlayer(connection, p, cookie);
            p.connection.markClientLoaded();
            p.setGameMode(GameType.SURVIVAL);
            p.tickCount = 1;
            p.setNoGravity(true);
            var origin = h.absoluteVec(at);
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
            release(h, chunks);
        }

        void succeed() {
            close();
            h.succeed();
        }
    }

    /** 人形白梅站在玩家正前方 {@code distance} 格、面朝玩家；{@code scripted} 时不自己出招、不挨打。 */
    private static Shiraume human(Fixture f, double distance, boolean scripted) {
        var level = f.h.getLevel();
        Shiraume boss = ShiraumeEntities.SHIRAUME.get().create(level, EntitySpawnReason.COMMAND);
        Vec3 at = f.origin.add(distance, 0, 0);
        boss.snapTo(at.x, at.y, at.z, 90, 0);
        boss.setYHeadRot(90);
        boss.setYBodyRot(90);
        level.addFreshEntity(boss);
        boss.setScripted(scripted);
        return boss;
    }

    private static long beneficial(ServerPlayer p) {
        return p.getActiveEffects().stream().filter(e -> e.getEffect().value().isBeneficial()).count();
    }

    /** 举盾挡下一字斩（过了弹刀的那一瞬）：没弹反就破盾——盾进入冷却、放下来，人没掉血。 */
    private static void guardBreak(GameTestHelper h) {
        var f = Fixture.create(h, new ItemStack(Items.SHIELD));
        var boss = human(f, 3, true);
        f.at(5, f::use);
        f.at(30, () -> {
            h.assertTrue(f.p.isBlocking(), "盾没有举起来");
            boss.play("ichimonji_cut");
        });
        f.at(50, () -> {
            h.assertTrue(f.p.getHealth() == f.p.getMaxHealth(), "举着盾还掉了血：" + f.p.getHealth());
            h.assertTrue(f.p.getCooldowns().isOnCooldown(f.p.getMainHandItem()), "一字斩挡下来没有破盾");
            h.assertTrue(!f.p.isBlocking(), "破盾之后还举着盾");
            h.assertTrue(boss.waves() == 0, "一字斩被挡下了还挥出剑气");
            boss.discard();
            f.succeed();
        });
    }

    /** 拿流火挡一字斩：流火（魔力护体）不会被破。 */
    private static void guardBreakSparesRyuka(GameTestHelper h) {
        var f = Fixture.create(h, new ItemStack(RyukaItem.ITEM.get()));
        var m = PlayerMagic.of(f.p);
        m.maxMana = m.mana = 1000;
        m.trace = false;
        var boss = human(f, 3, true);
        f.at(30, () -> {
            f.use();
            h.assertTrue(f.p.isBlocking(), "流火没有立即格挡：" + LongswordCombat.move(f.p));
        });
        f.at(60, () -> boss.play("ichimonji_cut"));
        f.at(80, () -> {
            h.assertTrue(!f.p.getCooldowns().isOnCooldown(f.p.getMainHandItem()), "流火被一字斩破了");
            h.assertTrue(f.p.isUsingItem(), "流火挡下一字斩之后没在格挡了");
            boss.discard();
            f.succeed();
        });
    }

    /** 突き打中：随机清掉两个增益（前后两下都中也只清两个），坏的效果留着，人掉了血。 */
    private static void thrustPurgesTwo(GameTestHelper h) {
        var f = Fixture.create(h, new ItemStack(Items.STICK));
        f.p.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(200);
        f.p.setHealth(200);
        for (var e : List.of(MobEffects.SPEED, MobEffects.STRENGTH, MobEffects.REGENERATION, MobEffects.FIRE_RESISTANCE))
            f.p.addEffect(new MobEffectInstance(e, 2400, 0));
        f.p.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 2400, 0));
        var boss = human(f, 4, true);
        f.at(5, () -> {
            h.assertTrue(beneficial(f.p) == 4, "增益没加上：" + f.p.getActiveEffects());
            boss.play("tsuki");
        });
        f.at(45, () -> {
            h.assertTrue(f.p.getHealth() < f.p.getMaxHealth(), "突き没有打中");
            h.assertTrue(beneficial(f.p) == 2, "突き清掉的增益不是两个：还剩 " + f.p.getActiveEffects());
            h.assertTrue(f.p.hasEffect(MobEffects.SLOWNESS), "坏的效果也被清掉了");
            boss.discard();
            f.succeed();
        });
    }

    /** 栓绳拴不住她（两种形态）：拿着拴绳右键她，拴绳还在手里，她也没被拴上。 */
    private static void cannotBeLeashed(GameTestHelper h) {
        var f = Fixture.create(h, new ItemStack(Items.LEAD));
        var boss = human(f, 2, true);
        ShiraumeBeast beast = ShiraumeEntities.BEAST.get().create(h.getLevel(), EntitySpawnReason.COMMAND);
        Vec3 at = f.origin.add(0, 0, 4);
        beast.snapTo(at.x, at.y, at.z, 0, 0);
        h.getLevel().addFreshEntity(beast);
        beast.setScripted(true);
        f.at(3, () -> {
            h.assertTrue(!boss.canBeLeashed() && !beast.canBeLeashed(), "她能被拴绳拴住");
            boss.interact(f.p, InteractionHand.MAIN_HAND, boss.position());
            beast.interact(f.p, InteractionHand.MAIN_HAND, beast.position());
            h.assertTrue(!boss.isLeashed() && !beast.isLeashed(), "拴绳右键之后被拴上了");
            h.assertTrue(f.p.getMainHandItem().is(Items.LEAD), "拴绳被用掉了");
            boss.discard();
            beast.discard();
            f.succeed();
        });
    }

    /**
     * 对付术师：同一个玩家的术式打中她五次，她就盯上他；他一蓄力，她冲过来斩下满是狐火的一刀，
     * 伤害就是他已经蓄进去的魔力（至少 8 点），蓄力被打断，伤害类型是斩法。
     */
    private static void spellLockAndCounter(GameTestHelper h) {
        var sword = new ItemStack(LongswordItem.BLACKSTEEL.get());
        sword.set(net.minecraft.core.component.DataComponents.CONTAINER, net.minecraft.world.item.component.ItemContainerContents.fromItems(List.of(
                dev.logos.item.SpellPages.inscribed(dev.logos.item.PageTier.CRYSTAL, "(spell :name \"蓄\" :mana 40 (mark caster #charged))", null))));
        var f = Fixture.create(h, sword);
        var m = PlayerMagic.of(f.p);
        m.maxMana = m.mana = 1000;
        m.trace = false;
        var health = f.p.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
        health.setBaseValue(200);
        f.p.setHealth(200);
        var boss = human(f, 7, false);
        boss.setNoAi(true);
        // 射线打她（她空着、正对着时会把术式拨开，但打过来就算一次）
        var spell = Casting.compile("(spell :name \"刺\" :mana 5 (ray :range 14 (on-hit (damage target 1 arcane))))");
        for (int i = 0; i < 5; i++) {
            f.at(4 + i * 12, () -> {
                boss.invulnerableTime = 0;
                Casting.castAs(f.p, spell, 5, 0, boss, null);
            });
        }
        f.at(66, () -> h.assertTrue(f.p.getUUID().equals(boss.counter().locked()), "术式打中五次还没盯上：" + boss.counter().locked()));
        // 拔剑之后：举剑格挡、左键开始蓄力；她这时才动
        f.at(70, f::use);
        f.at(73, () -> LongswordCombat.attack(f.p));
        f.at(76, () -> {
            h.assertTrue(Charging.of(f.p) != null, "长剑没有开始蓄力：" + LongswordCombat.move(f.p));
            boss.setNoAi(false);
        });
        f.at(120, () -> {
            float lost = f.p.getMaxHealth() - f.p.getHealth();
            h.assertTrue(f.p.getLastDamageSource() != null && f.p.getLastDamageSource().is(ShiraumeStrike.COUNTER),
                    "蓄力时没挨斩法：最后的伤害 " + f.p.getLastDamageSource() + "，她在 " + boss.move());
            h.assertTrue(lost >= 8 - 1e-3, "斩法不到 8 点：" + lost);
            h.assertTrue(Charging.of(f.p) == null, "挨了斩法蓄力还在");
            boss.discard();
            f.succeed();
        });
    }

    /** 斩法的死亡消息：「X 蓄着的 N 点魔力还没放出去，就被 白梅 连人带术一刀斩断了」，N 是斩下的魔力。 */
    private static void counterDeathMessage(GameTestHelper h) {
        var f = Fixture.create(h, new ItemStack(Items.STICK));
        var boss = human(f, 3, true);
        var message = ShiraumeStrike.counter(h.getLevel(), boss, 37.5).getLocalizedDeathMessage(f.p);
        h.assertTrue(message.getContents() instanceof TranslatableContents t && t.getKey().equals("death.attack.logos_dimension.shiraume_counter")
                && t.getArgs().length == 3 && "37.5".equals(String.valueOf(t.getArgs()[2])), "斩法的死亡消息不对：" + message);
        var whole = ShiraumeStrike.counter(h.getLevel(), boss, 40).getLocalizedDeathMessage(f.p);
        h.assertTrue(whole.getContents() instanceof TranslatableContents t && "40".equals(String.valueOf(t.getArgs()[2])), "整数的魔力带了小数：" + whole);
        boss.discard();
        f.succeed();
    }

    /** 狐形站在玩家正前方 {@code distance} 格、面朝玩家，不走 AI（招式状态机照常跑、能被弹反）。 */
    private static ShiraumeBeast beast(Fixture f, double distance) {
        ShiraumeBeast beast = ShiraumeEntities.BEAST.get().create(f.h.getLevel(), EntitySpawnReason.COMMAND);
        Vec3 at = f.origin.add(distance, 0, 0);
        beast.snapTo(at.x, at.y, at.z, 90, 0);
        beast.setYHeadRot(90);
        beast.setYBodyRot(90);
        f.h.getLevel().addFreshEntity(beast);
        beast.setNoAi(true);
        return beast;
    }

    /** 狐形的咬击甩刀被刚架起的打刀弹开：她仰头一晃（recoil），玩家不掉血、刀也没被破。 */
    private static void beastBiteParried(GameTestHelper h) {
        var f = Fixture.create(h, new ItemStack(dev.logos.dimension.item.UchigatanaItem.INK.get()));
        var beast = beast(f, 3.4);
        List<String> moves = new java.util.ArrayList<>();
        h.onEachTick(() -> {
            String m = beast.move();
            if (moves.isEmpty() || !moves.getLast().equals(m)) moves.add(m);
        });
        // 打刀拔刀要三十来刻：过了之后再举刀，刚举起两刻时咬过来
        f.at(27, () -> beast.play("bite_slash"));
        f.at(34, f::use);
        f.at(50, () -> {
            h.assertTrue(moves.contains("recoil"), "咬击被弹开没有仰头一晃：招式 " + moves + "，玩家 " + LongswordCombat.move(f.p));
            h.assertTrue(f.p.getHealth() == f.p.getMaxHealth(), "弹开了还掉血：" + f.p.getHealth());
            h.assertTrue(!f.p.getCooldowns().isOnCooldown(f.p.getMainHandItem()), "弹反了还破盾");
            beast.discard();
            f.succeed();
        });
    }

    /** 狐形的前爪挡下来却没弹反：破盾。 */
    private static void beastPawBreaksGuard(GameTestHelper h) {
        var f = Fixture.create(h, new ItemStack(Items.SHIELD));
        var beast = beast(f, 3.2);
        f.at(5, f::use);
        f.at(30, () -> {
            h.assertTrue(f.p.isBlocking(), "盾没有举起来");
            beast.play("paw_swipe");
        });
        f.at(55, () -> {
            h.assertTrue(f.p.getCooldowns().isOnCooldown(f.p.getMainHandItem()), "前爪挡下来没有破盾");
            beast.discard();
            f.succeed();
        });
    }

    /** 举盾站在大跳的落点旁边（面朝落下来的她）：没弹反就破盾。 */
    private static void beastPounceLandBreaksGuard(GameTestHelper h) {
        var f = Fixture.create(h, new ItemStack(Items.SHIELD));
        IronGolem golem = h.spawnWithNoFreeWill(EntityType.IRON_GOLEM, new BlockPos(5, 1, 2));
        ShiraumeBeast beast = ShiraumeEntities.BEAST.get().create(h.getLevel(), EntitySpawnReason.COMMAND);
        Vec3 at = h.absoluteVec(new Vec3(15.5, 1, 2.5));
        beast.snapTo(at.x, at.y, at.z, 90, 0);
        h.getLevel().addFreshEntity(beast);
        beast.setScripted(true);
        f.at(5, f::use);
        f.at(10, () -> {
            beast.setTarget(golem);
            beast.play("pounce_crouch");
        });
        f.at(75, () -> {
            h.assertTrue(f.p.getCooldowns().isOnCooldown(f.p.getMainHandItem()), "大跳落地挡下来没有破盾：她在 " + beast.move() + "，落在 " + beast.position());
            beast.discard();
            golem.discard();
            f.succeed();
        });
    }

    /**
     * 狂暴时大跳落地放出狐火冲击：一圈往外扩散，脚贴着地的人挨一下狐火，跳在半空（脚离地一格半）的人躲过去；
     * 扩散到头散掉。
     */
    private static void rageRingJumpable(GameTestHelper h) {
        var grounded = Fixture.create(h, new ItemStack(Items.STICK), new Vec3(2.5, 1, 2.5));
        var jumping = Fixture.create(h, new ItemStack(Items.STICK), new Vec3(10.5, 2.5, 10.5));
        IronGolem golem = h.spawnWithNoFreeWill(EntityType.IRON_GOLEM, new BlockPos(10, 1, 2));
        ShiraumeBeast beast = ShiraumeEntities.BEAST.get().create(h.getLevel(), EntitySpawnReason.COMMAND);
        Vec3 at = h.absoluteVec(new Vec3(16.5, 1, 2.5));
        beast.snapTo(at.x, at.y, at.z, 90, 0);
        h.getLevel().addFreshEntity(beast);
        // 打到八分之一血以下：狂暴
        beast.hurtServer(h.getLevel(), h.getLevel().damageSources().generic(), beast.getHealth() - beast.getMaxHealth() * .1f);
        beast.setScripted(true);
        int[] seen = {0};
        h.onEachTick(() -> seen[0] = Math.max(seen[0], beast.rings()));
        grounded.at(3, () -> {
            h.assertTrue(beast.enraged(), "没有狂暴");
            beast.setTarget(golem);
            beast.play("pounce_crouch");
        });
        grounded.at(120, () -> {
            h.assertTrue(seen[0] > 0, "狂暴的大跳落地没有放出狐火冲击");
            h.assertTrue(grounded.p.getHealth() < grounded.p.getMaxHealth() && grounded.p.getLastDamageSource() != null
                    && grounded.p.getLastDamageSource().is(dev.logos.dimension.foxfire.Foxfire.DAMAGE), "贴地的人没挨狐火冲击：" + grounded.p.getLastDamageSource());
            h.assertTrue(jumping.p.getHealth() == jumping.p.getMaxHealth(), "跳在半空的人也挨了狐火冲击");
            h.assertTrue(beast.rings() == 0, "狐火冲击扩散到头没散掉");
            beast.discard();
            golem.discard();
            jumping.close();
            grounded.succeed();
        });
    }

    /** 一字斩够不着人（没被弹开也没被挡下）：挥出剑气，沿着地面飞过去打中九格外的人。 */
    private static void ichimonjiWave(GameTestHelper h) {
        var f = Fixture.create(h, new ItemStack(Items.STICK));
        var boss = human(f, 9, true);
        int[] seen = {0};
        h.onEachTick(() -> seen[0] = Math.max(seen[0], boss.waves()));
        f.at(5, () -> boss.play("ichimonji_cut"));
        f.at(40, () -> {
            h.assertTrue(seen[0] == 1, "一字斩没挥出剑气：最多 " + seen[0] + " 道");
            h.assertTrue(f.p.getHealth() < f.p.getMaxHealth(), "剑气没打中九格外的人");
            h.assertTrue(boss.waves() == 0, "剑气走到头没散掉");
            boss.discard();
            f.succeed();
        });
    }

    /** 一字斩举刀蓄力时有人绕到背后：不等斩下，直接转身横扫。 */
    private static void ichimonjiTurnsWhenBehind(GameTestHelper h) {
        var f = Fixture.create(h, new ItemStack(Items.STICK));
        var boss = human(f, 2, false);
        // 她背对玩家（面朝 +X），玩家就在她背后两格
        f.at(2, () -> {
            boss.setYRot(-90);
            boss.setYBodyRot(-90);
            boss.setYHeadRot(-90);
            boss.play("ichimonji_raise");
        });
        f.at(8, () -> {
            h.assertTrue(boss.move().equals("turn_sweep"), "举刀时玩家在背后没有转身横扫：她在 " + boss.move());
            boss.discard();
            f.succeed();
        });
    }

    /** 狐形大跳：按弧线走，落在起跳时标出的地方（目标脚下），落地后交还重力。 */
    private static void beastPounceLandsOnMark(GameTestHelper h) {
        // 场边站一个玩家：没有玩家时这几个区块不跑实体刻
        var f = Fixture.create(h, new ItemStack(Items.STICK));
        IronGolem golem = h.spawnWithNoFreeWill(EntityType.IRON_GOLEM, new BlockPos(12, 1, 7));
        ShiraumeBeast beast = ShiraumeEntities.BEAST.get().create(h.getLevel(), EntitySpawnReason.COMMAND);
        Vec3 at = h.absoluteVec(new Vec3(0.5, 1, 7.5));
        beast.snapTo(at.x, at.y, at.z, -90, 0);
        h.getLevel().addFreshEntity(beast);
        beast.setScripted(true);
        Vec3 mark = h.absoluteVec(new Vec3(12.5, 1, 7.5));
        double[] top = {Double.NEGATIVE_INFINITY};
        java.util.List<String> moves = new java.util.ArrayList<>();
        h.onEachTick(() -> {
            String m = beast.move();
            if (moves.isEmpty() || !moves.getLast().equals(m)) moves.add(m);
            if (m.equals("pounce_air")) top[0] = Math.max(top[0], beast.getY() - mark.y);
        });
        h.runAfterDelay(3, () -> {
            beast.setTarget(golem);
            beast.play("pounce_crouch");
        });
        h.runAfterDelay(60, () -> {
            Vec3 p = beast.position();
            double flat = Math.hypot(p.x - mark.x, p.z - mark.z);
            h.assertTrue(top[0] > 1.2, "大跳没有腾空：最高 " + top[0] + " 格，招式 " + moves + "，游戏刻 " + h.getLevel().getGameTime()
                    + "，她的刻数 " + beast.tickCount + "，已移除 " + beast.isRemoved() + "，世界里找得到 " + (h.getLevel().getEntity(beast.getId()) != null));
            if (!(flat < .75 && Math.abs(p.y - mark.y) < .1)) {
                StringBuilder walls = new StringBuilder();
                for (int x = -4; x <= 18; x++)
                    for (int y = 0; y <= 10; y++)
                        for (int z = -2; z <= 8; z++)
                            if (h.getBlockState(new BlockPos(x, y, z)).is(Blocks.BARRIER) && (y == 1 || x == 6 || z == 2)) walls.append(' ').append(x).append(',').append(y).append(',').append(z);
                h.fail("大跳没落在标记上：落在 " + p + "，标记 " + mark + "，水平差 " + flat + "；屏障" + walls);
            }
            h.assertTrue(!beast.isNoGravity(), "落地之后还关着重力");
            beast.discard();
            golem.discard();
            f.succeed();
        });
    }
}
