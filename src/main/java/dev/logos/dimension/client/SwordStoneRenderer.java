package dev.logos.dimension.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.dawnvow.Dawnvow;
import dev.logos.dimension.dawnvow.SwordStoneBlock;
import dev.logos.dimension.dawnvow.SwordStoneBlockEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import org.jspecify.annotations.Nullable;

/**
 * 石座里的曦誓：用剑自己的物品模型倒插进石冠，剑面朝石座朝向，微微偏斜。
 * 有人在拔时剑按同步的拔起高度往上提并轻轻颤动，松手后慢慢沉回去。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public class SwordStoneRenderer implements BlockEntityRenderer<SwordStoneBlockEntity, SwordStoneRenderer.State> {
    /** 剑的物品模型缩放：整把约 1.6 格，剑身约一格，下半截埋在石里。 */
    private static final float SCALE = 0.9f;
    /** 剑尖在方块里的高度（石冠顶在 10/16）。 */
    private static final float TIP_Y = 0.2f;
    /** 物品模型里剑尖的高度（模型单位 24.4，中心在 8）。 */
    private static final float MODEL_TIP = (24.4f - 8f) / 16f;
    /** 完全拔起时剑往上提多少格；真正拔出前剑只露到这里。 */
    private static final float MAX_LIFT = 0.42f;
    private final ItemModelResolver items;

    public static class State extends BlockEntityRenderState {
        final ItemStackRenderState sword = new ItemStackRenderState();
        Direction facing = Direction.NORTH;
        float lift, time;
        boolean straining;
    }

    public SwordStoneRenderer(BlockEntityRendererProvider.Context context) {
        this.items = context.itemModelResolver();
    }

    @SubscribeEvent
    static void register(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(Dawnvow.STONE_ENTITY.get(), SwordStoneRenderer::new);
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(SwordStoneBlockEntity stone, State state, float partialTicks, Vec3 camera,
                                   ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(stone, state, partialTicks, camera, breakProgress);
        state.facing = stone.getBlockState().getValue(SwordStoneBlock.FACING);
        items.updateForTopItem(state.sword, stone.sword(), ItemDisplayContext.NONE, stone.getLevel(), null, (int) stone.getBlockPos().asLong());
        state.lift = stone.shownLift(partialTicks);
        state.straining = stone.straining();
        state.time = stone.getLevel() == null ? 0 : (stone.getLevel().getGameTime() % 24000) + partialTicks;
    }

    @Override
    public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
        if (state.sword.isEmpty()) return;
        pose.pushPose();
        float shake = state.straining ? 0.012f * (0.4f + state.lift) : 0;
        pose.translate(0.5f + shake * Mth.sin(state.time * 2.9f), TIP_Y + MODEL_TIP * SCALE + state.lift * MAX_LIFT, 0.5f + shake * Mth.cos(state.time * 3.7f));
        // 模型的剑面朝 +z；转到石座朝向，再倒过来让剑尖朝下，带一点偏斜
        pose.mulPose(Axis.YP.rotationDegrees(-state.facing.toYRot()));
        pose.mulPose(Axis.XP.rotationDegrees(4f));
        pose.mulPose(Axis.ZP.rotationDegrees(180f + 3f));
        pose.scale(SCALE, SCALE, SCALE);
        state.sword.submit(pose, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
        pose.popPose();
    }

    @Override
    public boolean shouldRenderOffScreen() {
        return true;
    }
}
