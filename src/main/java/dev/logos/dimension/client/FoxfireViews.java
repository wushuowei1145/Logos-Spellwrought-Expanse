package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.client.foxfire.FoxfireClient;
import dev.logos.dimension.combat.Moveset;
import dev.logos.dimension.foxfire.Foxfire;
import dev.logos.dimension.foxfire.FoxfireCue;
import dev.logos.dimension.foxfire.FoxfireFlame;
import dev.logos.dimension.foxfire.FoxfireRules;
import dev.logos.dimension.foxfire.FoxfireWraps;
import dev.logos.dimension.npc.DivineFox;
import dev.logos.dimension.npc.LogosMagicBridge;
import dev.logos.lang.Element;
import dev.logos.runtime.Effects;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/**
 * 狐火的实机场景（本地开发用，docs/FOXFIRE.md）：神狐施放狐火缠绕（脚下的狐火圈、刀身幽蓝的火），砍中靶子
 * （幽蓝的命中、横扫游脉、缠身）；玩家手上缠着白梅给的淡粉狐火（以后少女白梅给的就是这种），附带的一下是淡粉；
 * 玩家自己被狐火打中（惑的画面边缘、缠身的画面底边）；怪物被惑，头上绕着狐火。逐段截图，并检查两端的状态与客户端收到的提示。
 */
@LDLRegisterClient(name = "foxfire_views", group = "foxfire", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
public class FoxfireViews implements UIScenario {
    static final int FLOOR = FoxCombatViews.FLOOR;
    private static DivineFox fox;
    private static Husk target;
    private static Husk wanderer;
    private static int firesBefore;

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("foxfire").defaultTimeoutMs(120_000).scenarioTimeoutMs(600_000);
    }

    private static Husk target(ServerLevel level, double x, double z) {
        Husk husk = EntityType.HUSK.create(level, EntitySpawnReason.COMMAND);
        husk.snapTo(x, FLOOR + 1, z, 0, 0);
        husk.setNoAi(true);
        husk.setPersistenceRequired();
        husk.getAttribute(Attributes.MAX_HEALTH).setBaseValue(300);
        husk.setHealth(300);
        level.addFreshEntity(husk);
        return husk;
    }

    /** 让 {@code who} 面朝 {@code at}（不动的生物不会自己转）。 */
    private static void face(LivingEntity who, Vec3 at) {
        Vec3 d = at.subtract(who.getEyePosition());
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        who.setYRot(yaw);
        who.setYHeadRot(yaw);
        who.setYBodyRot(yaw);
        who.setXRot((float) -Math.toDegrees(Math.atan2(d.y, d.horizontalDistance())));
    }

    @Override
    public void define(ScenarioBuilder s) {
        FoxCombatViews.begin(s)
                .step("界面", ctx -> {
                    Minecraft.getInstance().options.hideGui = false;
                    FoxfireClient.clear();
                })
                .server("神狐与靶子", ctx -> {
                    ServerLevel level = ctx.server().overworld();
                    FoxCombatViews.arena(level);
                    target = target(level, .5, -2.2);
                    fox = FoxCombatViews.fox(level, .5, .6, DivineFox.KAGURA, false);
                    fox.setKatana(1);
                    face(fox, target.getBoundingBox().getCenter());
                    fox.setTarget(target);
                    fox.drawKatana();
                    FoxCombatViews.camera(ctx.player(), new Vec3(3.4, FLOOR + 1.9, -.4), new Vec3(.3, FLOOR + 1.1, -.6));
                })
                .waitUntil("神狐同步", ctx -> FoxCombatViews.foxes().size() == 1).ticks(100)
                .ticks(36)
                .server("缠上狐火", ctx -> {
                    firesBefore = DivineFoxRenderer.bladeFires();
                    if (!LogosMagicBridge.cast(fox, LogosMagicBridge.FOX_FIRE_WRAP, false, 100, target))
                        throw new IllegalStateException("狐火缠绕施放失败（foxfire 没编译过？）");
                })
                .ticks(3)
                .screenshot("foxfire_cast")
                .checkServer("服务端：神狐手上是幽蓝的狐火缠绕", ctx -> FoxfireWraps.flame(fox) == FoxfireFlame.KITSUNE)
                .ticks(25)
                .check("客户端：施法圈换成了狐火，刀身燃着狐火", ctx -> FoxfireClient.received(FoxfireCue.CAST) > 0
                        && DivineFoxRenderer.bladeFires() > firesBefore)
                .screenshot("foxfire_fox_wrap")
                .server("神狐砍中靶子", ctx -> {
                    fox.katanaInput(Moveset.Input.ATTACK);
                    fox.doHurtTarget(ctx.server().overworld(), target);
                })
                .ticks(2)
                .screenshot("foxfire_fox_hit")
                .ticks(4)
                .screenshot("foxfire_fox_sweep")
                .checkServer("靶子挨的是狐火，正在缠身", ctx -> target.getLastDamageSource() != null
                        && target.getLastDamageSource().is(Foxfire.DAMAGE) && FoxfireRules.burning(target) == FoxfireFlame.KITSUNE)
                .check("客户端：命中换成了狐火", ctx -> FoxfireClient.received(FoxfireCue.HIT) > 0)
                .ticks(14)
                .screenshot("foxfire_burn")
                // 横扫游脉：靶子就站在弧上时，Logos 的游脉第一步就打中、整条不画；这里朝空地扫一道看颜色
                .server("神狐朝空地横扫", ctx -> {
                    face(fox, fox.getEyePosition().add(4, -1, 0));
                    var spell = dev.logos.runtime.Casting.compile(dev.logos.dimension.foxfire.FoxfireCommands.PAGES.get("sweep"));
                    dev.logos.runtime.Casting.castAs(fox, spell, 40, 0, null, null);
                    FoxCombatViews.camera(ctx.player(), new Vec3(.5, FLOOR + 4.2, 4.8), new Vec3(2.8, FLOOR + 1, .6));
                })
                .ticks(5)
                .screenshot("foxfire_sweep")
                .check("客户端：游脉一路冒的是狐火", ctx -> FoxfireClient.received(FoxfireCue.VEIN) > 0 && FoxfireClient.veinLooks() > 0)
                .ticks(6)
                .screenshot("foxfire_sweep_late")
                // 玩家手上缠着白梅给的淡粉狐火（以后少女白梅给玩家缠的就是这种）
                .server("手上缠着淡粉狐火", ctx -> {
                    var player = ctx.player();
                    fox.discard();
                    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_SWORD));
                    FoxfireWraps.wrap(player, FoxfireFlame.SHIRAUME, 3, 60);
                    // 站在台面上（眼高 1.62），之后切成生存模式也不会掉下去
                    FoxCombatViews.camera(player, new Vec3(-1.6, FLOOR + 2.62, .6), target.getBoundingBox().getCenter());
                })
                .ticks(20)
                .check("客户端：手里的剑燃着淡粉狐火", ctx -> FoxfireClient.wrapFires() > 0)
                .screenshot("foxfire_pink_wrap_hand")
                .server("缠绕附带的一下", ctx -> {
                    target.invulnerableTime = 0;
                    Effects.followUp(ctx.player(), target, 3, Element.ARCANE);
                })
                .ticks(2)
                .screenshot("foxfire_pink_hit")
                .checkServer("附带的是淡粉的狐火", ctx -> FoxfireRules.burning(target) == FoxfireFlame.SHIRAUME)
                // 玩家自己挨了狐火：惑的画面边缘，缠身的画面底边
                .server("玩家被狐火打中", ctx -> {
                    var player = ctx.player();
                    FoxfireWraps.wrap(player, FoxfireFlame.SHIRAUME, 0, 0);
                    player.setGameMode(GameType.SURVIVAL);
                    player.getAttribute(Attributes.MAX_HEALTH).addOrReplacePermanentModifier(
                            new AttributeModifier(LogosDimension.id("foxfire_test"), 180, AttributeModifier.Operation.ADD_VALUE));
                    player.setHealth(player.getMaxHealth());
                    Foxfire.deal(target, player, 2, FoxfireFlame.KITSUNE);
                })
                .ticks(4)
                .check("客户端：惑", ctx -> FoxfireClient.received(FoxfireCue.BEWITCH) > 0)
                .screenshot("foxfire_bewitched_view")
                .ticks(24)
                .screenshot("foxfire_burning_view")
                // 怪物被惑：丢了目标，头上绕着狐火
                .server("怪物被惑", ctx -> {
                    ServerLevel level = ctx.server().overworld();
                    var player = ctx.player();
                    player.setGameMode(GameType.CREATIVE);
                    // 尸壳：白天不会自己着火（普通僵尸会烧起来，分不清是不是狐火）
                    wanderer = EntityType.HUSK.create(level, EntitySpawnReason.COMMAND);
                    wanderer.snapTo(3.5, FLOOR + 1, -3.5, 0, 0);
                    wanderer.setPersistenceRequired();
                    level.addFreshEntity(wanderer);
                    wanderer.setTarget(target);
                    FoxfireRules.alwaysBewitch = true;
                    try {
                        Foxfire.deal(player, wanderer, 1, FoxfireFlame.KITSUNE);
                    } finally {
                        FoxfireRules.alwaysBewitch = false;
                    }
                    FoxCombatViews.camera(player, new Vec3(1.2, FLOOR + 2.4, .2), wanderer.position().add(0, 1.6, 0));
                })
                .checkServer("服务端：丢了目标", ctx -> wanderer.getTarget() == null && FoxfireRules.bewitched(wanderer))
                .ticks(6)
                .check("客户端：头上绕着狐火", ctx -> FoxfireClient.received(FoxfireCue.DAZE) > 0)
                .screenshot("foxfire_daze")
                .teardown("还原", ctx -> {
                    FoxfireRules.alwaysBewitch = false;
                    FoxfireClient.clear();
                    Minecraft.getInstance().options.hideGui = false;
                });
    }
}
