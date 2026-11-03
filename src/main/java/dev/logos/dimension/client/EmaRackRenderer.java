package dev.logos.dimension.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.cloudsea.EmaRackBlock;
import dev.logos.dimension.cloudsea.EmaItem;
import dev.logos.dimension.cloudsea.EmaRackBlockEntity;
import dev.logos.dimension.cloudsea.EmaWish;
import dev.logos.dimension.fox.FoxSanctuary;
import net.minecraft.client.gui.Font;
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
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import org.jspecify.annotations.Nullable;

import java.util.List;

/** 绘马挂：玩家挂上的三块绘马画在框的最外面一排，写下的愿望（旧存档是铁砧上的名字）写在绘马上。 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public class EmaRackRenderer implements BlockEntityRenderer<EmaRackBlockEntity, EmaRackRenderer.State> {
    private static final float[] SLOT_X = {-0.3f, 0f, 0.3f};
    private final ItemModelResolver items;
    private final Font font;

    public static class State extends BlockEntityRenderState {
        final @Nullable ItemStackRenderState[] items = new ItemStackRenderState[EmaRackBlockEntity.SLOTS];
        final @Nullable List<FormattedCharSequence>[] lines = new List[EmaRackBlockEntity.SLOTS];
        Direction facing = Direction.NORTH;
    }

    public EmaRackRenderer(BlockEntityRendererProvider.Context context) {
        this.items = context.itemModelResolver();
        this.font = context.font();
    }

    @SubscribeEvent
    static void register(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(FoxSanctuary.EMA_RACK.get(), EmaRackRenderer::new);
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(EmaRackBlockEntity rack, State state, float partialTicks, Vec3 camera,
                                   ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(rack, state, partialTicks, camera, breakProgress);
        state.facing = rack.getBlockState().getValue(EmaRackBlock.FACING);
        for (int i = 0; i < EmaRackBlockEntity.SLOTS; i++) {
            ItemStack stack = rack.getItems().get(i);
            state.items[i] = null;
            state.lines[i] = null;
            if (stack.isEmpty()) continue;
            ItemStackRenderState item = new ItemStackRenderState();
            items.updateForTopItem(item, stack, ItemDisplayContext.FIXED, rack.getLevel(), null, i);
            state.items[i] = item;
            EmaWish wish = stack.get(EmaItem.WISH.get());
            Component name = wish != null ? Component.literal(wish.text().replace('\n', ' ')) : stack.get(DataComponents.CUSTOM_NAME);
            if (name != null) state.lines[i] = font.split(name, 46);
        }
    }

    @Override
    public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
        for (int i = 0; i < EmaRackBlockEntity.SLOTS; i++) {
            ItemStackRenderState item = state.items[i];
            if (item == null) continue;
            pose.pushPose();
            // 局部 +z 朝绘马挂正面
            pose.translate(0.5f, 0.5f, 0.5f);
            pose.mulPose(Axis.YP.rotationDegrees(-state.facing.toYRot()));
            pose.translate(SLOT_X[i], 0.12f + (i == 1 ? 0.04f : 0f), 0.2f);
            pose.pushPose();
            pose.mulPose(Axis.YP.rotationDegrees(180));
            pose.scale(0.34f, 0.34f, 0.34f);
            item.submit(pose, collector, state.lightCoords, OverlayTexture.NO_OVERLAY, 0);
            pose.popPose();
            List<FormattedCharSequence> lines = state.lines[i];
            if (lines != null) {
                pose.translate(0f, -0.02f, 0.03f);
                pose.scale(0.0042f, -0.0042f, 0.0042f);
                for (int l = 0; l < Math.min(3, lines.size()); l++) {
                    FormattedCharSequence line = lines.get(l);
                    collector.submitText(pose, -font.width(line) / 2f, (l - 1) * 9f, line, false, Font.DisplayMode.POLYGON_OFFSET,
                        state.lightCoords, 0xFF3A2414, 0, 0);
                }
            }
            pose.popPose();
        }
    }
}
