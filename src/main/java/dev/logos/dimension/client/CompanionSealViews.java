package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import com.mojang.authlib.GameProfile;
import dev.logos.dimension.companion.CompanionMind;
import dev.logos.dimension.companion.CompanionStats;
import dev.logos.dimension.companion.Companions;
import dev.logos.dimension.companion.ShiraumeCompanion;
import dev.logos.dimension.foxfire.Foxfire;
import dev.logos.dimension.ryuka.FoxMarks;
import dev.logos.dimension.shiraume.FoxfireBolt;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * 实际客户端检查契印的第二到第四印和这一轮的数值、防御（docs/SHIRAUME_COMPANION.md 第 5、8.3 节），同一组 {@code shiraume_companion}：
 * <ul>
 * <li>{@code companion_seals}：盖四枚契印——第三枚教会主人狐火之言（客户端也知道）；她自己的生命、护甲、韧性跟着长；狐火弹打中减速、
 *     留狐火印；主人穿着带保护 IV 的鞘翅时她少挨一截；金苹果给她和玩家吃一样的效果；残血被贴着时守一阵就闪开；
 *     坐在船里闪不回主人身边，连着几次就回形代里；
 * <li>{@code companion_pvp}：别的玩家打主人——「攻击玩家」关着时她不管，开着时把那个玩家当敌人。
 * </ul>
 */
public abstract class CompanionSealViews extends CompanionViews {

    @LDLRegisterClient(name = "companion_seals", group = "shiraume_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Seals extends CompanionSealViews {
        private static final Vec3 EYE = new Vec3(.5, FLOOR + 2.62, -3.6), LOOK = new Vec3(.5, FLOOR + 1.2, 2.5);
        private ShiraumeCompanion c;
        private Mob golem, zombie;
        private int meals, escapes, recalls;
        private float health, plain, guarded, protection;

        @Override
        public void define(ScenarioBuilder s) {
            stage(s, GameType.SURVIVAL).runCommand("difficulty normal")
                    .server("站好，召唤，盖四枚契印", ctx -> {
                        ServerPlayer p = ctx.player();
                        p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 20 * 120, 4, false, false));
                        aim(p, EYE, LOOK);
                        Foxfire.forget(p);
                        Companions.grantPact(p);
                        c = summonAt(p, new Vec3(-1.5, FLOOR + 1, .5));
                        for (int i = 0; i < 4; i++) Companions.addSeal(p);
                    })
                    .step("对准", ctx -> aimClient(LOOK))
                    .waitUntilServer("入场播完", ctx -> !"summon".equals(c.npcMiscAnimation()))
                    .checkServer("第三枚契印教会了主人狐火之言", ctx -> Foxfire.knows(ctx.player()))
                    .waitUntil("客户端也知道学会了（编纂台的卡片库据此列出狐火）", ctx -> Foxfire.HIDDEN.viewerKnows.getAsBoolean())
                    .checkServer("四枚契印：她自己的生命 60、护甲 8、韧性 2，继承六成二（主人 20 血 → +12.4）", ctx ->
                            Math.abs(c.getMaxHealth() - 72.4f) < .05 && Math.abs(c.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR) - 8) < .01
                                    && Math.abs(c.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ARMOR_TOUGHNESS) - 2) < .01)
                    .checkServer("流火一刀的倍数在中间：比没盖契印高，比九尾全亮低", ctx ->
                            c.bladeDamage() > c.foxfireDamage() * CompanionStats.blade(0) && c.bladeDamage() < c.foxfireDamage() * CompanionStats.blade(9))
                    // 第四印：狐火弹减速、留印
                    .server("一只不动的铁傀儡；她朝它放一发狐火弹", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        golem = CompanionLifeViews.spawn(level, EntityType.IRON_GOLEM, new Vec3(1.5, FLOOR + 1, 5.5), 180);
                        golem.setNoAi(true);
                        Vec3 from = c.getEyePosition();
                        FoxfireBolt.companion(level, c, from, golem.getBoundingBox().getCenter().subtract(from), golem, c.boltDamage());
                    })
                    .waitUntilServer("打中：铁傀儡减速，身上一枚狐火印", ctx -> golem.hasEffect(MobEffects.SLOWNESS) && FoxMarks.count(golem) >= 1)
                    .screenshot("companion_brand")
                    // 继承保护类魔咒：鞘翅不加护甲，只看魔咒
                    .server("主人什么都没穿，她挨一下 10 点", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        golem.discard();
                        c.setHealth(c.getMaxHealth());
                        c.invulnerableTime = 0;
                        float before = c.getHealth();
                        c.hurtServer(level, level.damageSources().generic(), 10);
                        plain = before - c.getHealth();
                    })
                    .server("主人穿上保护 IV 的鞘翅，她再挨一下", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        ServerPlayer p = ctx.player();
                        ItemStack elytra = new ItemStack(Items.ELYTRA);
                        elytra.enchant(ctx.server().registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.PROTECTION), 4);
                        p.setItemSlot(EquipmentSlot.CHEST, elytra);
                        protection = CompanionStats.protection(level, p, level.damageSources().generic(), c.seals());
                        c.setHealth(c.getMaxHealth());
                        c.invulnerableTime = 0;
                        float before = c.getHealth();
                        c.hurtServer(level, level.damageSources().generic(), 10);
                        guarded = before - c.getHealth();
                        p.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
                    })
                    .checkServer("保护 IV 按六成二继承：少挨的那一截同原版的算法", ctx -> protection > 2 && guarded < plain
                            && Math.abs(guarded - plain * (1 - protection / 25)) < .05)
                    // 吃东西：效果同玩家
                    .server("她半血；递给她一个金苹果（饱着也吃）", ctx -> {
                        ServerPlayer p = ctx.player();
                        Companions.setSatiety(p, 20);
                        c.setHealth(c.getMaxHealth() * .5f);
                        health = c.getHealth();
                        meals = ShiraumeCompanion.meals();
                        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.GOLDEN_APPLE));
                        c.interact(p, InteractionHand.MAIN_HAND, Vec3.ZERO);
                    })
                    .checkServer("她接过去吃", ctx -> c.eating())
                    .waitUntilServer("咽下去", ctx -> ShiraumeCompanion.meals() > meals)
                    .checkServer("同玩家吃金苹果：生命恢复、伤害吸收；回的血 = 饱食度 4 + 饱和度 9.6", ctx ->
                            c.hasEffect(MobEffects.REGENERATION) && c.hasEffect(MobEffects.ABSORPTION) && c.getHealth() >= health + 13.5f)
                    .waitUntilServer("吃完", ctx -> !c.eating())
                    // 残血被贴着：守一阵就闪开
                    .server("她只剩两成血，伤害吸收清掉；一只僵尸贴着她", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        c.removeAllEffects();
                        c.setHealth(c.getMaxHealth() * .2f);
                        escapes = CompanionMind.escapes();
                        zombie = CompanionLifeViews.spawn(level, EntityType.ZOMBIE, c.position().add(1.2, 0, 0), 90);
                        zombie.setTarget(c);
                    })
                    .waitUntilServer("凝刀守了一阵还贴着：闪到主人身后拉开", ctx -> CompanionMind.escapes() > escapes)
                    .checkServer("闪开了：主人挡在她和僵尸中间", ctx -> {
                        System.out.println("[companion] escape: her " + c.position() + " zombie " + zombie.position() + " owner " + ctx.player().position());
                        // 僵尸可能已经追到主人跟前：她比主人离僵尸远一截，又在主人身边
                        return c.distanceTo(zombie) > ctx.player().distanceTo(zombie) + 1 && c.distanceTo(ctx.player()) < 4;
                    })
                    .server("收掉僵尸", ctx -> zombie.discard())
                    // 困在船里：连着闪不回去就回形代
                    .server("她坐上一条船；主人走到 29 格外的石台上", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        ServerPlayer p = ctx.player();
                        var boat = EntityType.OAK_BOAT.create(level, EntitySpawnReason.COMMAND);
                        boat.snapTo(.5, FLOOR + 1, 2.5, 0, 0);
                        level.addFreshEntity(boat);
                        c.startRiding(boat, true, false);
                        for (int x = -30; x <= -27; x++)
                            for (int z = -1; z <= 2; z++) level.setBlockAndUpdate(new BlockPos(x, FLOOR, z), Blocks.SMOOTH_STONE.defaultBlockState());
                        recalls = CompanionMind.stuckRecalls();
                        aim(p, new Vec3(-28.5, FLOOR + 2.62, .5), new Vec3(.5, FLOOR + 1, 2.5));
                    })
                    .checkServer("她坐在船里", ctx -> c.isPassenger())
                    .waitUntilServer("连着闪不回去：她回形代里歇着", ctx -> CompanionMind.stuckRecalls() > recalls && Companions.out(ctx.player()) == null)
                    .server("拆掉船和石台", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class, AREA, e -> e.getType() == EntityType.OAK_BOAT).forEach(b -> b.discard());
                        for (int x = -30; x <= -27; x++)
                            for (int z = -1; z <= 2; z++) level.setBlockAndUpdate(new BlockPos(x, FLOOR, z), Blocks.AIR.defaultBlockState());
                    })
                    .teardown("恢复界面", ctx -> Minecraft.getInstance().options.hideGui = false);
        }
    }

    @LDLRegisterClient(name = "companion_pvp", group = "shiraume_companion", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Pvp extends CompanionSealViews {
        private static final Vec3 EYE = new Vec3(.5, FLOOR + 2.62, -3.6), LOOK = new Vec3(.5, FLOOR + 1.2, 2.5);
        private ShiraumeCompanion c;
        private ServerPlayer rival;

        @Override
        public void define(ScenarioBuilder s) {
            stage(s, GameType.SURVIVAL).runCommand("difficulty normal")
                    .server("站好，召唤；另一个玩家站在三格外", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        ServerPlayer p = ctx.player();
                        aim(p, EYE, LOOK);
                        c = summonAt(p, new Vec3(-1.5, FLOOR + 1, .5));
                        var cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), "rival"), false);
                        rival = new ServerPlayer(ctx.server(), level, cookie.gameProfile(), cookie.clientInformation());
                        var connection = new Connection(PacketFlow.SERVERBOUND);
                        new EmbeddedChannel(connection);
                        ctx.server().getPlayerList().placeNewPlayer(connection, rival, cookie);
                        rival.connection.markClientLoaded();
                        rival.setGameMode(GameType.SURVIVAL);
                        rival.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 20 * 120, 4, false, false));
                        rival.teleportTo(level, 1.5, FLOOR + 1, 3.5, java.util.Set.of(), 180, 0, true);
                    })
                    .step("对准", ctx -> aimClient(LOOK))
                    .waitUntilServer("入场播完", ctx -> !"summon".equals(c.npcMiscAnimation()))
                    .server("那个玩家打了主人一下（攻击玩家关着）", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        ctx.player().invulnerableTime = 0;
                        ctx.player().hurtServer(level, level.damageSources().playerAttack(rival), 1);
                    })
                    .ticks(30)
                    .checkServer("关着：她不把玩家当敌人", ctx -> c.mind().foe() != rival && !c.canHarm(rival))
                    .server("打开「攻击玩家」；那个玩家又打了主人一下", ctx -> {
                        ServerLevel level = ctx.server().overworld();
                        ServerPlayer p = ctx.player();
                        p.setData(Companions.BOND, p.getData(Companions.BOND).withPvp(true));
                        p.invulnerableTime = 0;
                        p.hurtServer(level, level.damageSources().playerAttack(rival), 1);
                    })
                    .waitUntilServer("开着：她把打主人的玩家当敌人", ctx -> c.mind().foe() == rival && c.canHarm(rival))
                    .ticks(20)
                    .screenshot("companion_pvp")
                    .checkServer("主人自己她从来不打", ctx -> !c.canHarm(ctx.player()))
                    .server("送走那个玩家", ctx -> ctx.server().getPlayerList().remove(rival))
                    .teardown("恢复界面", ctx -> Minecraft.getInstance().options.hideGui = false);
        }
    }
}
