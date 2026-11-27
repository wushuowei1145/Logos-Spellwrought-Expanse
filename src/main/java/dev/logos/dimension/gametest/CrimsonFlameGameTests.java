package dev.logos.dimension.gametest;

import com.mojang.authlib.GameProfile;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.combat.Airborne;
import dev.logos.dimension.dragonfire.Dragonfire;
import dev.logos.dimension.item.CrimsonFlameLongswordItem;
import dev.logos.dimension.longsword.LongswordCombat;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.LinkedHashMap;
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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 炽焰长剑的伤害都是龙炎（docs/CRIMSON_FLAME_LONGSWORD.md）：普通一剑、横扫的游脉、坠星的落点。
 * 只跑这些：{@code -PdimensionTests=logos_dimension:crimson_flame_*}。
 */
public final class CrimsonFlameGameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS = DeferredRegister.create(Registries.TEST_FUNCTION, LogosDimension.ID);

    public static void register(IEventBus bus) {
        Map<String, Consumer<GameTestHelper>> tests = new LinkedHashMap<>();
        tests.put("crimson_flame_cut_is_dragonfire", CrimsonFlameGameTests::cutIsDragonfire);
        tests.put("crimson_flame_sweep_veins_burn", CrimsonFlameGameTests::sweepVeinsBurn);
        tests.put("crimson_flame_skyfall_dragonfire", CrimsonFlameGameTests::skyfallDragonfire);
        tests.forEach((name, test) -> FUNCTIONS.register(name, () -> test));
        FUNCTIONS.register(bus);
        bus.addListener((RegisterGameTestsEvent event) -> {
            // 每个测试单独一批：游脉扫出 11 格
            for (var name : tests.keySet()) {
                var env = event.registerEnvironment(LogosDimension.id(name), new TestEnvironmentDefinition.AllOf());
                event.registerTest(LogosDimension.id(name), new FunctionGameTestInstance(
                        ResourceKey.create(Registries.TEST_FUNCTION, LogosDimension.id(name)), new TestData<>(env,
                        Identifier.withDefaultNamespace("empty"), 160, 0, true, Rotation.NONE, false, 1, 1, false, 8)));
            }
        });
    }

    /** 拿着炽焰长剑、面朝 +X 的假玩家，每刻钉在 {@code at}（{@code air} 为真时悬空、不受重力）。 */
    private static ServerPlayer player(GameTestHelper h, Vec3 at, boolean air) {
        var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "crimson-test"), false);
        var p = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        h.getLevel().getServer().getPlayerList().placeNewPlayer(connection, p, cookie);
        p.connection.markClientLoaded();
        p.setGameMode(GameType.SURVIVAL);
        p.tickCount = 1;
        p.setNoGravity(air);
        Vec3 origin = h.absoluteVec(at);
        p.setPos(origin);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(CrimsonFlameLongswordItem.ITEM.get()));
        // 嵌入连接没有真实网络循环：逐世界刻更新装备和冷却，钉住位置和朝向（冲刺类招式的位移不参与检查）
        h.onEachTick(() -> {
            p.tickCount = 1;
            p.doTick();
            p.setPos(origin);
            p.setDeltaMovement(Vec3.ZERO);
            p.setYRot(-90);
            p.setYHeadRot(-90);
            p.setXRot(0);
            if (!air) p.setOnGround(true);
        });
        return p;
    }

    private static void floor(GameTestHelper h) {
        for (int x = -1; x <= 7; x++)
            for (int z = -1; z <= 7; z++) h.setBlock(new BlockPos(x, 0, z), Blocks.STONE.defaultBlockState());
    }

    private static Husk husk(GameTestHelper h, Vec3 at, boolean air) {
        var husk = EntityType.HUSK.create(h.getLevel(), EntitySpawnReason.COMMAND);
        h.assertTrue(husk != null, "生不出尸壳");
        Vec3 abs = h.absoluteVec(at);
        husk.snapTo(abs.x, abs.y, abs.z, 90, 0);
        husk.setNoAi(true);
        husk.setNoGravity(air);
        h.getLevel().addFreshEntity(husk);
        return husk;
    }

    private static void done(GameTestHelper h, ServerPlayer p, LivingEntity... targets) {
        p.stopUsingItem();
        for (var t : targets) t.discard();
        h.getLevel().getServer().getPlayerList().remove(p);
        h.succeed();
    }

    private static boolean dragonfire(LivingEntity e) {
        DamageSource last = e.getLastDamageSource();
        return last != null && last.is(Dragonfire.DAMAGE);
    }

    private static float lost(LivingEntity e) {
        return e.getMaxHealth() - e.getHealth();
    }

    /** 一式打穿满身钻石甲：攻击力 8 原样是龙炎，掉一点耐久；一式不点缠身。 */
    private static void cutIsDragonfire(GameTestHelper h) {
        floor(h);
        var p = player(h, new Vec3(2, 1, 3), false);
        var husk = husk(h, new Vec3(4.3, 1, 3), false);
        for (var slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET})
            husk.setItemSlot(slot, new ItemStack(switch (slot) {
                case HEAD -> Items.DIAMOND_HELMET;
                case CHEST -> Items.DIAMOND_CHESTPLATE;
                case LEGS -> Items.DIAMOND_LEGGINGS;
                default -> Items.DIAMOND_BOOTS;
            }));
        // 拔剑 1.1 秒；攻击蓄力满了再出一式
        h.runAfterDelay(40, () -> {
            h.assertTrue("idle".equals(LongswordCombat.move(p)), "没拔完剑：" + LongswordCombat.move(p));
            LongswordCombat.attack(p);
        });
        h.runAfterDelay(52, () -> {
            h.assertTrue(dragonfire(husk), "一式打出的不是龙炎：" + husk.getLastDamageSource());
            h.assertTrue(Math.abs(lost(husk) - 8) < .01, "钻石甲减了龙炎，或伤害不是攻击力 8：掉了 " + lost(husk));
            h.assertTrue(p.getMainHandItem().getDamageValue() == 1, "没掉一点耐久：" + p.getMainHandItem().getDamageValue());
            h.assertFalse(Dragonfire.burning(husk), "一式不该点龙炎缠身");
            h.assertFalse(husk.isOnFire(), "龙炎不该点原版的火");
            done(h, p, husk);
        });
    }

    /** 格挡中左键打出红莲劫焰斩：6 格外的尸壳只被游脉打到，吃到 10 点龙炎并缠身。 */
    private static void sweepVeinsBurn(GameTestHelper h) {
        floor(h);
        var p = player(h, new Vec3(1, 1, 3), false);
        var husk = husk(h, new Vec3(7, 1, 3), false);
        h.runAfterDelay(40, () -> p.getMainHandItem().use(h.getLevel(), p, InteractionHand.MAIN_HAND));
        h.runAfterDelay(44, () -> {
            h.assertTrue("guard".equals(LongswordCombat.move(p)), "右键没进格挡：" + LongswordCombat.move(p));
            LongswordCombat.attack(p);
        });
        h.runAfterDelay(47, () -> h.assertTrue("flame_sweep".equals(LongswordCombat.move(p)), "格挡中左键没打出横扫：" + LongswordCombat.move(p)));
        h.runAfterDelay(70, () -> {
            h.assertTrue(dragonfire(husk), "游脉打出的不是龙炎：" + husk.getLastDamageSource());
            h.assertTrue(lost(husk) >= 10 - .01 && lost(husk) < 13, "游脉该打 10 点（另有缠身每秒 1.5）：掉了 " + lost(husk));
            h.assertTrue(Dragonfire.burning(husk), "游脉没点龙炎缠身");
            h.assertFalse(husk.isOnFire(), "龙炎不该点原版的火");
            done(h, p, husk);
        });
    }

    /** 空中左键炽翼坠星破：落点一圈 12 点龙炎，挑飞并缠身。 */
    private static void skyfallDragonfire(GameTestHelper h) {
        var p = player(h, new Vec3(2, 6, 3), true);
        // 悬空时落点取身前 1.6 格（脚下找不到地），靶子就放在那里。落点 12 点加上接触的一剑会打死尸壳，用铁傀儡
        var husk = EntityType.IRON_GOLEM.create(h.getLevel(), EntitySpawnReason.COMMAND);
        h.assertTrue(husk != null, "生不出铁傀儡");
        Vec3 at = h.absoluteVec(new Vec3(3.6, 6, 3));
        husk.snapTo(at.x, at.y, at.z, 90, 0);
        husk.setNoAi(true);
        husk.setNoGravity(true);
        h.getLevel().addFreshEntity(husk);
        h.runAfterDelay(40, () -> {
            h.assertTrue("idle".equals(LongswordCombat.move(p)), "没拔完剑：" + LongswordCombat.move(p));
            LongswordCombat.attack(p);
        });
        h.runAfterDelay(43, () -> h.assertTrue("skyfall_judgement".equals(LongswordCombat.move(p)), "空中左键没打出坠星：" + LongswordCombat.move(p)));
        h.runAfterDelay(56, () -> {
            h.assertTrue(dragonfire(husk), "坠星打出的不是龙炎：" + husk.getLastDamageSource());
            h.assertTrue(lost(husk) >= 12 - .01, "坠星落点该打 12 点：掉了 " + lost(husk));
            h.assertTrue(Dragonfire.burning(husk), "坠星没点龙炎缠身");
            h.assertTrue(Airborne.is(husk), "坠星没把目标挑飞");
            done(h, p, husk);
        });
    }
}
