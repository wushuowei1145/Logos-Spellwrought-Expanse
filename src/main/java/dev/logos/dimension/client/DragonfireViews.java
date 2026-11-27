package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.ScenarioBuilder;
import com.lowdragmc.lowdraglib2.uitest.ScenarioOptions;
import com.lowdragmc.lowdraglib2.uitest.UIScenario;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.client.foxfire.FoxfireClient;
import dev.logos.dimension.dragonfire.Dragonfire;
import dev.logos.dimension.dragonfire.DragonfireCommands;
import dev.logos.dimension.foxfire.FoxfireCue;
import dev.logos.dimension.foxfire.FoxfireFlame;
import dev.logos.dimension.foxfire.FoxfireWraps;
import dev.logos.lang.Element;
import dev.logos.runtime.Casting;
import dev.logos.runtime.Effects;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/**
 * 龙炎的实机场景（本地开发用，docs/DRAGONFIRE.md）：玩家施放龙炎横扫（施法圈、游脉），龙炎打中靶子（命中、缠身），
 * 手里的剑缠着龙炎，附带的一下；玩家自己龙炎缠身（画面底边）。逐段截图，并检查两端的状态与客户端收到的提示。
 */
@LDLRegisterClient(name = "dragonfire_views", group = "dragonfire", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
public class DragonfireViews implements UIScenario {
    static final int FLOOR = FoxCombatViews.FLOOR;
    private static Husk target, caster;

    @Override
    public void configure(ScenarioOptions o) {
        o.tags("dragonfire").defaultTimeoutMs(120_000).scenarioTimeoutMs(600_000);
    }

    @Override
    public void define(ScenarioBuilder s) {
        FoxCombatViews.begin(s)
                .step("界面", ctx -> {
                    Minecraft.getInstance().options.hideGui = false;
                    FoxfireClient.clear();
                })
                .server("靶子", ctx -> {
                    ServerLevel level = ctx.server().overworld();
                    FoxCombatViews.arena(level);
                    target = EntityType.HUSK.create(level, EntitySpawnReason.COMMAND);
                    target.snapTo(.5, FLOOR + 1, -2.2, 0, 0);
                    target.setNoAi(true);
                    target.setPersistenceRequired();
                    target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(300);
                    target.setHealth(300);
                    level.addFreshEntity(target);
                    var player = ctx.player();
                    player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    FoxCombatViews.camera(player, new Vec3(-1.6, FLOOR + 2.62, .6), new Vec3(3, FLOOR + 1, .6));
                })
                .ticks(20)
                // 同狐火场景的横扫：站在地上的施法者朝空地扫一道（玩家站在台面上，游脉贴不到地）
                .server("施法者朝空地施放龙炎横扫", ctx -> {
                    ServerLevel level = ctx.server().overworld();
                    caster = EntityType.HUSK.create(level, EntitySpawnReason.COMMAND);
                    caster.snapTo(.5, FLOOR + 1, .6, -90, 0);
                    caster.setNoAi(true);
                    caster.setPersistenceRequired();
                    caster.setYHeadRot(-90);
                    caster.setYBodyRot(-90);
                    caster.setXRot(10);
                    level.addFreshEntity(caster);
                    var spell = Casting.compile(DragonfireCommands.PAGES.get("sweep"));
                    Casting.castAs(caster, spell, 40, 0, null, null);
                    FoxCombatViews.camera(ctx.player(), new Vec3(.5, FLOOR + 4.2, 4.8), new Vec3(2.8, FLOOR + 1, .6));
                })
                .ticks(5)
                .screenshot("dragonfire_sweep")
                .check("客户端：施法圈换成了龙炎", ctx -> FoxfireClient.received(FoxfireCue.CAST) > 0)
                .check("客户端：收到了龙炎游脉", ctx -> FoxfireClient.received(FoxfireCue.VEIN) > 0)
                .check("客户端：游脉一路冒的是龙炎", ctx -> FoxfireClient.veinLooks() > 0)
                .ticks(6)
                .screenshot("dragonfire_sweep_late")
                .server("龙炎打中靶子", ctx -> {
                    caster.discard();
                    FoxCombatViews.camera(ctx.player(), new Vec3(-1.6, FLOOR + 2.62, .6), target.getBoundingBox().getCenter());
                    Dragonfire.deal(ctx.player(), target, 4);
                })
                .ticks(2)
                .screenshot("dragonfire_hit")
                .checkServer("靶子挨的是龙炎，正在缠身", ctx -> target.getLastDamageSource() != null
                        && target.getLastDamageSource().is(Dragonfire.DAMAGE) && Dragonfire.burning(target))
                .check("客户端：命中换成了龙炎", ctx -> FoxfireClient.received(FoxfireCue.HIT) > 0)
                .ticks(16)
                .screenshot("dragonfire_burn")
                .server("手上缠着龙炎", ctx -> {
                    var player = ctx.player();
                    player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_SWORD));
                    Dragonfire.wrap(player, 3, 60);
                })
                .ticks(20)
                .check("客户端：手里的剑燃着龙炎", ctx -> FoxfireClient.wrapFires() > 0
                        && Minecraft.getInstance().player != null && FoxfireWraps.flame(Minecraft.getInstance().player) == FoxfireFlame.DRAGON)
                .screenshot("dragonfire_wrap_hand")
                .server("缠绕附带的一下", ctx -> {
                    target.invulnerableTime = 0;
                    Effects.followUp(ctx.player(), target, 3, Element.ARCANE);
                })
                .ticks(2)
                .checkServer("附带的是龙炎", ctx -> target.getLastDamageSource() != null && target.getLastDamageSource().is(Dragonfire.DAMAGE))
                .screenshot("dragonfire_wrap_hit")
                .server("玩家自己龙炎缠身", ctx -> {
                    var player = ctx.player();
                    Dragonfire.wrap(player, 0, 0);
                    player.setGameMode(GameType.SURVIVAL);
                    player.getAttribute(Attributes.MAX_HEALTH).addOrReplacePermanentModifier(
                            new AttributeModifier(LogosDimension.id("dragonfire_test"), 180, AttributeModifier.Operation.ADD_VALUE));
                    player.setHealth(player.getMaxHealth());
                    Dragonfire.deal(target, player, 2);
                })
                .ticks(10)
                .check("客户端：自己在龙炎缠身", ctx -> Minecraft.getInstance().player != null && Dragonfire.burning(Minecraft.getInstance().player))
                .screenshot("dragonfire_burning_view")
                .server("还原玩家", ctx -> {
                    var player = ctx.player();
                    player.setGameMode(GameType.CREATIVE);
                    player.getAttribute(Attributes.MAX_HEALTH).removeModifier(LogosDimension.id("dragonfire_test"));
                })
                .teardown("还原", ctx -> {
                    FoxfireClient.clear();
                    Minecraft.getInstance().options.hideGui = false;
                });
    }
}
