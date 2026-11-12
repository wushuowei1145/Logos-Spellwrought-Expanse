package dev.logos.dimension.gametest;

import com.mojang.authlib.GameProfile;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.ServerConfig;
import dev.logos.dimension.explosion.ExplosionMagic;
import dev.logos.dimension.explosion.ExplosionStaffItem;
import dev.logos.dimension.explosion.ExplosionStaffPage;
import dev.logos.dimension.explosion.ExplosionTimeline;
import dev.logos.item.PageContainer;
import dev.logos.item.PageTier;
import dev.logos.item.SpellPages;
import dev.logos.lang.Intent;
import dev.logos.lang.Spell;
import dev.logos.runtime.Casting;
import dev.logos.runtime.FreeManaEntity;
import dev.logos.runtime.Limits;
import dev.logos.runtime.PlayerMagic;
import io.netty.channel.embedded.EmbeddedChannel;
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
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
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

/**
 * 爆裂魔法：魔力不够不能吟唱、副手不行；吟唱 400 刻注满 6400 魔力，中途放弃的魔力散成游离魔力；
 * 爆炸半径内的生物不管多硬都死、外面的被掀开活着；开了破坏地形时挖出坑、坑中心到基岩、半径外不动；关了时一块不动。
 * 半径在测试里调到最小的 16 格，测试之间留足间距。
 */
public final class ExplosionGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS = DeferredRegister.create(Registries.TEST_FUNCTION, LogosDimension.ID);
    private static final int RADIUS = 16;

    public static void register(IEventBus bus) {
        var tests = Map.<String, Consumer<GameTestHelper>>of(
                "explosion_charge", ExplosionGameTests::charge,
                "explosion_kills_and_carves", h -> blast(h, true),
                "explosion_keeps_terrain_when_off", h -> blast(h, false),
                "explosion_staff_page", ExplosionGameTests::page);
        tests.forEach((name, test) -> FUNCTIONS.register(name, () -> test));
        FUNCTIONS.register(bus);
        bus.addListener((RegisterGameTestsEvent event) -> {
            var env = event.registerEnvironment(LogosDimension.id("explosion"), new TestEnvironmentDefinition.AllOf());
            // 爆炸会杀光、挖空周围几十格，测试之间隔开
            for (String name : tests.keySet()) event.registerTest(LogosDimension.id(name), new FunctionGameTestInstance(
                    ResourceKey.create(Registries.TEST_FUNCTION, LogosDimension.id(name)),
                    new TestData<>(env, Identifier.withDefaultNamespace("empty"), 800, 0, true, Rotation.NONE, false, 1, 1, false, 96)));
        });
    }

    static ServerPlayer player(GameTestHelper h, Vec3 at, float pitch) {
        var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "explosion-test"), false);
        var p = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        h.getLevel().getServer().getPlayerList().placeNewPlayer(connection, p, cookie);
        p.connection.markClientLoaded();
        p.setGameMode(GameType.SURVIVAL);
        p.getInventory().clearContent();
        p.snapTo(at.x, at.y, at.z, 0, pitch);
        p.setYHeadRot(0);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ExplosionStaffItem.ITEM.get()));
        return p;
    }

    /** 吟唱：门槛、注满、放弃。 */
    private static void charge(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos o = h.absolutePos(new BlockPos(1, 1, 1));
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 12; z++) level.setBlockAndUpdate(o.offset(x, -1, z), Blocks.STONE.defaultBlockState());
        // 往前下方看：准星落在几格外的石头上
        var p = player(h, Vec3.atBottomCenterOf(o), 30);
        var staff = p.getMainHandItem();
        var magic = PlayerMagic.of(p);
        magic.maxMana = 10000;
        magic.mana = 6000;
        h.assertTrue(staff.getItem().use(level, p, InteractionHand.MAIN_HAND) == InteractionResult.FAIL && ExplosionMagic.paid(p) < 0,
                "不到 6400 魔力不能吟唱");
        magic.mana = 7000;
        p.setItemInHand(InteractionHand.OFF_HAND, staff.copy());
        h.assertTrue(staff.getItem().use(level, p, InteractionHand.OFF_HAND) == InteractionResult.FAIL, "副手不能吟唱");
        p.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
        h.assertTrue(staff.getItem().use(level, p, InteractionHand.MAIN_HAND) == InteractionResult.CONSUME && p.isUsingItem(), "魔力够就开始吟唱");
        h.assertTrue(ExplosionMagic.target(p) != null && ExplosionMagic.target(p).distanceTo(p.position()) < 12, "目的地是准星指的地面");
        for (int t = 0; t < ExplosionMagic.CHARGE_TICKS - 1; t++) staff.getItem().onUseTick(level, p, staff, 72000 - t);
        h.assertTrue(!ExplosionMagic.full(p), "还差一刻没满");
        h.assertTrue(Math.abs(magic.mana - (7000 - ExplosionMagic.DRAIN * (ExplosionMagic.CHARGE_TICKS - 1))) < 1e-3, "每刻注入 16 魔力");
        staff.getItem().onUseTick(level, p, staff, 72000 - ExplosionMagic.CHARGE_TICKS);
        h.assertTrue(ExplosionMagic.full(p) && Math.abs(ExplosionMagic.paid(p) - ExplosionMagic.MANA) < 1e-6, "400 刻注满 6400 魔力");
        h.assertTrue(Math.abs(magic.mana - 600) < 1e-3, "注满后剩 600 魔力");
        // 不放出去：中断，注入的魔力散成游离魔力
        p.stopUsingItem();
        h.assertTrue(ExplosionMagic.paid(p) < 0, "中断后不再吟唱");
        var free = level.getEntitiesOfClass(FreeManaEntity.class, new AABB(o).inflate(6));
        h.assertTrue(!free.isEmpty(), "注入的 6400 魔力散成游离魔力");
        free.forEach(Entity::discard);
        p.discard();
        h.succeed();
    }

    /**
     * 在 Logos 眼里爆裂魔杖只有一张贤者级载页，意图是攻击、一次注入 6400、谁都背得动；不是玩家的生物拿着它施放这张载页时：
     * 没注满不放、魔力不丢（这只僵尸没有魔力池，散成游离魔力），注满了放出真正的爆裂魔法，范围内连它自己一起全灭。
     */
    private static void page(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ItemStack staff = new ItemStack(ExplosionStaffItem.ITEM.get());
        ExplosionStaffPage.ensure(staff);
        ItemStack page = PageContainer.get(staff, 0);
        h.assertTrue(PageContainer.size(staff) == 1 && SpellPages.isInscribed(page) && SpellPages.tier(page) == PageTier.SAGE, "一张写好的贤者级载页");
        Spell spell = SpellPages.spell(page);
        h.assertTrue(spell != null && ExplosionStaffPage.is(spell), "载页上是爆裂魔杖的术式");
        h.assertTrue(spell.profile().primary() == Intent.STRIKE, "术式的意图是攻击，实际是 " + spell.profile().primary());
        h.assertTrue(Math.abs(Casting.injection(spell, PageTier.SAGE, 0).mana() - ExplosionMagic.MANA) < 1e-6, "一次注入 6400");
        h.assertTrue(spell.stability() <= Limits.DEFAULT_STABILITY, "默认稳定度就背得动");

        Vec3 c = Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(1, 1, 1)));
        Mob caster = mob(level, EntityType.ZOMBIE, c);
        caster.setItemInHand(InteractionHand.MAIN_HAND, staff);
        Mob foe = mob(level, EntityType.COW, c.add(0, 0, 6));
        int before = ExplosionMagic.blasts();
        Casting.castAs(caster, spell, 3000, PageTier.SAGE.recovery(), foe, null, true);
        h.assertTrue(ExplosionMagic.blasts() == before, "没注满不放");
        var free = level.getEntitiesOfClass(FreeManaEntity.class, new AABB(BlockPos.containing(c)).inflate(4));
        h.assertTrue(!free.isEmpty(), "没注满时注入的魔力不丢：没有魔力池的散成游离魔力");
        free.forEach(Entity::discard);
        h.assertTrue(foe.isAlive(), "载页上的小魔弹也没有放出去");
        // 注满：半径调小、不挖地形，只在这一次放出时生效（放出时就记下了）
        boolean savedTerrain = ServerConfig.EXPLOSION_BREAKS_TERRAIN.get();
        int savedRadius = ServerConfig.EXPLOSION_RADIUS.get();
        ServerConfig.EXPLOSION_BREAKS_TERRAIN.set(false);
        ServerConfig.EXPLOSION_RADIUS.set(RADIUS);
        Casting.castAs(caster, spell, ExplosionMagic.MANA, PageTier.SAGE.recovery(), foe, null, true);
        ServerConfig.EXPLOSION_BREAKS_TERRAIN.set(savedTerrain);
        ServerConfig.EXPLOSION_RADIUS.set(savedRadius);
        h.assertTrue(ExplosionMagic.blasts() == before + 1, "注满就放出爆裂魔法");
        h.runAfterDelay(ExplosionTimeline.FULL_AT + 4, () -> h.succeedWhen(RealTimeWait.paced(() ->
                h.assertTrue(!foe.isAlive() && !caster.isAlive(), "目标和站在范围里的施法者都死了"))));
    }

    private static Mob mob(ServerLevel level, EntityType<? extends Mob> type, Vec3 at) {
        Mob mob = type.create(level, EntitySpawnReason.COMMAND);
        mob.setNoAi(true);
        mob.snapTo(at.x, at.y, at.z, 0, 0);
        level.addFreshEntity(mob);
        return mob;
    }

    /** 放一次爆炸：半径内的生物都死，半径外的牛被掀开；{@code terrain} 时挖坑。 */
    private static void blast(GameTestHelper h, boolean terrain) {
        ServerLevel level = h.getLevel();
        boolean savedTerrain = ServerConfig.EXPLOSION_BREAKS_TERRAIN.get();
        int savedRadius = ServerConfig.EXPLOSION_RADIUS.get();
        ServerConfig.EXPLOSION_BREAKS_TERRAIN.set(terrain);
        ServerConfig.EXPLOSION_RADIUS.set(RADIUS);
        // 一块 41×41 的石台，从世界底部之上一直垫到测试位置上方 20 格：坑够深，不受测试摆在哪个高度影响
        BlockPos c = h.absolutePos(new BlockPos(1, 21, 1));
        int bottom = level.getMinY() + 1;
        for (int x = -20; x <= 20; x++) for (int z = -20; z <= 20; z++) for (int y = bottom; y < c.getY(); y++)
            level.setBlock(new BlockPos(c.getX() + x, y, c.getZ() + z), Blocks.STONE.defaultBlockState(), 2);
        level.setBlockAndUpdate(c.offset(6, 0, 0), Blocks.CHEST.defaultBlockState());
        Vec3 center = Vec3.atBottomCenterOf(c);
        Mob zombie = mob(level, EntityType.ZOMBIE, center.add(4, 0, 0));
        zombie.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, new ItemStack(Items.NETHERITE_HELMET));
        Mob golem = mob(level, EntityType.IRON_GOLEM, center.add(-10, 0, 3));
        Mob edge = mob(level, EntityType.ZOMBIE, center.add(0, 0, -14));
        // 半径外、石台上：被气浪掀飞，摔下去也不能死
        Mob outside = mob(level, EntityType.COW, center.add(0, 0, 20));
        outside.setInvulnerable(true);
        var item = new net.minecraft.world.entity.item.ItemEntity(level, center.x + 2, center.y + 1, center.z, new ItemStack(Items.DIAMOND));
        level.addFreshEntity(item);
        ExplosionMagic.fire(level, center, null);
        h.runAfterDelay(ExplosionTimeline.FULL_AT + 4, () -> h.succeedWhen(RealTimeWait.paced(() -> {
            h.assertTrue(!zombie.isAlive() && !golem.isAlive() && !edge.isAlive(), "爆炸半径内的生物都死了（戴下界合金头盔、铁傀儡也一样）");
            h.assertTrue(!item.isAlive(), "地上原有的掉落物被清掉");
            h.assertTrue(outside.isAlive(), "半径外的牛活着");
            h.assertTrue(Math.abs(outside.getZ() - (center.z + 20)) > .5 || outside.getDeltaMovement().lengthSqr() > 1e-3, "半径外的牛被气浪掀开");
            if (terrain) {
                h.assertTrue(ExplosionMagic.carved() > 1000, "坑挖完了");
                h.assertTrue(level.getBlockState(c.below()).isAir(), "爆点脚下被挖空");
                h.assertTrue(level.getBlockState(new BlockPos(c.getX(), bottom, c.getZ())).isAir(), "坑中心一直挖到世界底部（基岩之上）");
                h.assertTrue(level.getBlockState(c.offset(6, 0, 0)).isAir(), "箱子没了");
                h.assertTrue(level.getBlockState(c.offset(19, -1, 0)).is(Blocks.STONE), "半径外的石头不动");
                h.assertTrue(level.getBlockState(new BlockPos(c.getX() + 12, bottom, c.getZ())).is(Blocks.STONE), "坑往外渐浅：离中心 12 格处挖不到底");
            } else {
                h.assertTrue(level.getBlockState(c.below()).is(Blocks.STONE), "关掉破坏地形时不挖");
                h.assertTrue(level.getBlockState(c.offset(6, 0, 0)).is(Blocks.CHEST), "关掉破坏地形时箱子还在");
            }
            ServerConfig.EXPLOSION_BREAKS_TERRAIN.set(savedTerrain);
            ServerConfig.EXPLOSION_RADIUS.set(savedRadius);
            outside.discard();
        })));
    }
}
