package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.DimensionTravel;
import dev.logos.dimension.item.*;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** 普通实际客户端：持弓、四阶蓄力贴图、同步箭纹理和 Logos 生命拖尾。 */
@LDLRegisterClient(name = "natures_force", group = "natures_force", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
public class NaturesForceView implements UIScenario {
    private int arrowId;
    protected InteractionHand hand() { return InteractionHand.MAIN_HAND; }
    @Override public void configure(ScenarioOptions o) { o.tags("natures_force").defaultTimeoutMs(60000).scenarioTimeoutMs(180000); }
    @Override public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("time set day").runCommand("difficulty peaceful")
                .step("设置离屏视口", ctx -> {
                    var mc = Minecraft.getInstance();
                    var w = mc.getWindow(); w.setWindowed(1280, 720); w.setWidth(1280); w.setHeight(720);
                    mc.getMainRenderTarget().resize(1280, 720); mc.gameRenderer.resize(1280, 720); mc.resizeGui();
                    mc.options.hideGui = false; mc.options.fov().set(60); mc.options.renderDistance().set(8); mc.options.broadcastOptions();
                })
                .server("布置弓检查台", ctx -> {
                    var level = ctx.server().overworld();
                    for (int x = -10; x <= 10; x++) for (int z = -10; z <= 12; z++) {
                        level.setBlockAndUpdate(new BlockPos(x, 99, z), Blocks.MOSS_BLOCK.defaultBlockState());
                        for (int y = 100; y < 108; y++) level.removeBlock(new BlockPos(x, y, z), false);
                    }
                    DimensionTravel.teleport(ctx.player(), level, new Vec3(.5, 100, 7), 180, 0);
                    ctx.player().setItemInHand(hand(), new ItemStack(NaturesForceItem.ITEM.get()));
                    ctx.player().setItemInHand(hand() == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND, new ItemStack(Items.ARROW, 64));
                })
                .waitUntil("弓到达客户端", ctx -> Minecraft.getInstance().player.getItemInHand(hand()).is(NaturesForceItem.ITEM.get()))
                .check("前三阶保留普通药水光灵箭纹理", ctx -> originalTextures())
                .check("第四阶不用原版暴击粒子", ctx -> nativeParticles(3) == 4 && nativeParticles(4) == 0)
                .ticks(40).screenshot("nature_bow_idle")
                .step("实际右键拉弓", ctx -> {
                    var mc = Minecraft.getInstance(); mc.options.keyUse.setDown(true); mc.gameMode.useItem(mc.player, hand());
                }).ticks(45)
                .check("四阶模型属性", ctx -> {
                    var mc = Minecraft.getInstance();
                    return new NatureChargeProperty().get(mc.player.getItemInHand(hand()), mc.level, mc.player, 0) == 4;
                }).screenshot("nature_bow_full_charge")
                .step("观察第三人称持弓方向", ctx -> Minecraft.getInstance().options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT))
                .ticks(3).screenshot("nature_bow_third_person")
                .step("恢复第一人称", ctx -> Minecraft.getInstance().options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON))
                .step("释放真实弓箭", ctx -> { var mc = Minecraft.getInstance(); mc.options.keyUse.setDown(false); mc.gameMode.releaseUsingItem(mc.player); })
                .server("布置近景箭与生命拖尾", ctx -> {
                    var p = ctx.player();
                    var arrow = ((ArrowItem) Items.ARROW).createArrow(p.level(), new ItemStack(Items.ARROW), p, p.getItemInHand(hand()));
                    ((NatureArrow) arrow).natureStage(4);
                    arrow.setCritArrow(true);
                    arrow.setPos(-2, 101.5, 2);
                    arrow.setYRot(-90);
                    arrow.setDeltaMovement(.12, 0, 0);
                    p.level().addFreshEntity(arrow); arrowId = arrow.getId();
                }).ticks(15)
                .check("箭阶段跨网络同步", ctx -> {
                    var entity = Minecraft.getInstance().level.getEntity(arrowId);
                    return entity instanceof AbstractArrow && ((NatureArrow) entity).natureStage() == 4;
                })
                .check("指定箭纹理进入最终渲染状态", ctx -> {
                    var mc = Minecraft.getInstance();
                    var entity = mc.level.getEntity(arrowId);
                    var renderer = mc.getEntityRenderDispatcher().getRenderer(entity);
                    var state = (net.minecraft.client.renderer.entity.state.ArrowRenderState) renderer.createRenderState(entity, 0);
                    return NatureArrowRendering.texture(state, net.minecraft.resources.Identifier.withDefaultNamespace("textures/entity/projectiles/arrow.png"))
                            .equals(NatureArrowRendering.TEXTURE);
                })
                .check("Logos 拖尾已收到", ctx -> {
                    try { return !((List<?>) Class.forName("dev.logos.client.LogosFx").getMethod("drawnTrails").invoke(null)).isEmpty(); }
                    catch (ReflectiveOperationException error) { throw new IllegalStateException("读取 Logos 拖尾检查失败", error); }
                })
                .check("截图有实际分辨率", ctx -> Minecraft.getInstance().getMainRenderTarget().width == 1280)
                .screenshot("nature_arrow_life_trail")
                .ticks(85)
                .check("近景强化箭自然到期移除", ctx -> Minecraft.getInstance().level.getEntity(arrowId) == null)
                .teardown("恢复界面", ctx -> { Minecraft.getInstance().options.hideGui = false; Minecraft.getInstance().options.keyUse.setDown(false); Minecraft.getInstance().options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON); });
    }

    /** 直接观察一次客户端箭刻实际生成的原版粒子，确保暴击伤害标记与飞行粒子独立。 */
    private static int nativeParticles(int stage) {
        var mc = Minecraft.getInstance();
        mc.particleEngine.clearParticles();
        var arrow = ((ArrowItem) Items.ARROW).createArrow(mc.level, new ItemStack(Items.ARROW), mc.player, new ItemStack(NaturesForceItem.ITEM.get()));
        ((NatureArrow) arrow).natureStage(stage);
        arrow.setCritArrow(true); arrow.setDeltaMovement(0, 0, -.1);
        arrow.tick();
        try {
            var field = net.minecraft.client.particle.ParticleEngine.class.getDeclaredField("particlesToAdd");
            field.setAccessible(true);
            return ((java.util.Queue<?>) field.get(mc.particleEngine)).size();
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("检查客户端箭粒子失败", error); }
        finally { arrow.discard(); mc.particleEngine.clearParticles(); }
    }

    private static boolean originalTextures() {
        var mc = Minecraft.getInstance();
        for (var item : new net.minecraft.world.item.Item[]{Items.ARROW, Items.TIPPED_ARROW, Items.SPECTRAL_ARROW}) {
            var ammo = new ItemStack(item);
            if (item == Items.TIPPED_ARROW) ammo.set(net.minecraft.core.component.DataComponents.POTION_CONTENTS,
                    new net.minecraft.world.item.alchemy.PotionContents(net.minecraft.world.item.alchemy.Potions.SLOWNESS));
            var fallback = net.minecraft.resources.Identifier.withDefaultNamespace("textures/entity/projectiles/" +
                    (item == Items.TIPPED_ARROW ? "arrow_tipped" : item == Items.SPECTRAL_ARROW ? "arrow_spectral" : "arrow") + ".png");
            for (int stage = 1; stage <= 4; stage++) {
                var arrow = ((ArrowItem) item).createArrow(mc.level, ammo, mc.player, new ItemStack(NaturesForceItem.ITEM.get()));
                ((NatureArrow) arrow).natureStage(stage);
                var renderer = mc.getEntityRenderDispatcher().getRenderer(arrow);
                var state = (net.minecraft.client.renderer.entity.state.ArrowRenderState) renderer.createRenderState(arrow, 0);
                boolean matches = NatureArrowRendering.texture(state, fallback).equals(stage == 4 ? NatureArrowRendering.TEXTURE : fallback);
                arrow.discard();
                if (!matches) return false;
            }
        }
        return true;
    }

    @LDLRegisterClient(name = "natures_force_left", group = "natures_force", registry = UIScenario.REGISTRY, environment = RegistrationEnvironment.DEV_ONLY)
    public static class Left extends NaturesForceView {
        @Override protected InteractionHand hand() { return InteractionHand.OFF_HAND; }
    }
}
