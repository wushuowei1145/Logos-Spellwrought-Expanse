package dev.logos.dimension.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.serialization.MapCodec;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.item.AncientEmbraceCharge;
import dev.logos.dimension.item.AncientEmbraceItem;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterSpecialModelRendererEvent;
import org.joml.Vector3fc;

/** 使用原版盾变换与原版模型提交；仅点亮的叶片使用独立纹理和全亮光照。 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class AncientEmbraceRenderer implements SpecialModelRenderer<Integer> {
    public static final ModelLayerLocation LAYER = new ModelLayerLocation(LogosDimension.id("ancient_embrace"), "main");
    private static final Identifier TEXTURE = LogosDimension.id("textures/entity/shield/ancient_embrace.png");
    private static final Identifier GLOW = LogosDimension.id("textures/entity/shield/ancient_embrace_glow.png");
    private final AncientEmbraceModel normal, emissive;
    public AncientEmbraceRenderer(BakingContext context) {
        normal = new AncientEmbraceModel(context.entityModelSet().bakeLayer(LAYER), false);
        emissive = new AncientEmbraceModel(context.entityModelSet().bakeLayer(LAYER), true);
    }
    @Override public Integer extractArgument(ItemStack stack) {
        var level = Minecraft.getInstance().level;
        return level == null ? 0 : stack.getOrDefault(AncientEmbraceItem.CHARGE.get(), AncientEmbraceCharge.EMPTY).activeLeaves(level.getGameTime());
    }
    @Override public void submit(Integer leaves, PoseStack poses, SubmitNodeCollector collector, int light, int overlay, boolean foil, int outline) {
        int charge = leaves == null ? 0 : Math.clamp(leaves, 0, AncientEmbraceCharge.MAX);
        collector.submitModel(normal, charge, poses, TEXTURE, light, overlay, outline, null);
        if (charge > 0) collector.submitModel(emissive, charge, poses, RenderTypes.entityTranslucentEmissive(GLOW), 0xF000F0, overlay, outline, null);
        if (foil) {
            collector.submitModel(normal, charge, poses, RenderTypes.entityGlint(), light, overlay, outline, null);
            if (charge > 0) collector.submitModel(emissive, charge, poses, RenderTypes.entityGlint(), 0xF000F0, overlay, outline, null);
        }
    }
    @Override public void getExtents(Consumer<Vector3fc> output) {
        // GUI 包围盒总是取完整盾牌，不随充能或提交顺序变化。
        normal.setupAnim(0); normal.root().getExtentsForGui(new PoseStack(), output);
    }
    @SubscribeEvent static void layer(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(LAYER, AncientEmbraceModel::createBodyLayer);
    }
    @SubscribeEvent static void renderer(RegisterSpecialModelRendererEvent event) {
        event.register(LogosDimension.id("ancient_embrace"), Unbaked.CODEC);
    }
    public record Unbaked() implements SpecialModelRenderer.Unbaked<Integer> {
        public static final MapCodec<Unbaked> CODEC = MapCodec.unit(new Unbaked());
        @Override public MapCodec<Unbaked> type() { return CODEC; }
        @Override public AncientEmbraceRenderer bake(BakingContext context) { return new AncientEmbraceRenderer(context); }
    }
}
