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
public final class NaturesForceView implements UIScenario {
    private int arrowId;
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
                    ctx.player().setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(NaturesForceItem.ITEM.get()));
                    ctx.player().setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.ARROW, 64));
                })
                .waitUntil("弓到达客户端", ctx -> Minecraft.getInstance().player.getMainHandItem().is(NaturesForceItem.ITEM.get()))
                .ticks(40).screenshot("nature_bow_idle")
                .step("实际右键拉弓", ctx -> {
                    var mc = Minecraft.getInstance(); mc.options.keyUse.setDown(true); mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                }).ticks(45)
                .check("四阶模型属性", ctx -> {
                    var mc = Minecraft.getInstance();
                    return new NatureChargeProperty().get(mc.player.getMainHandItem(), mc.level, mc.player, 0) == 4;
                }).screenshot("nature_bow_full_charge")
                .step("释放真实弓箭", ctx -> { var mc = Minecraft.getInstance(); mc.options.keyUse.setDown(false); mc.gameMode.releaseUsingItem(mc.player); })
                .server("布置近景箭与生命拖尾", ctx -> {
                    var p = ctx.player();
                    var arrow = ((ArrowItem) Items.ARROW).createArrow(p.level(), new ItemStack(Items.ARROW), p, p.getMainHandItem());
                    ((NatureArrow) arrow).natureStage(4);
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
                .teardown("恢复界面", ctx -> { Minecraft.getInstance().options.hideGui = false; Minecraft.getInstance().options.keyUse.setDown(false); });
    }
}
