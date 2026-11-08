package dev.logos.dimension.client.corruption;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.corruption.mob.CorruptionMobs;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.entity.SilverfishRenderer;
import net.minecraft.client.renderer.entity.SkeletonRenderer;
import net.minecraft.client.renderer.entity.SlimeRenderer;
import net.minecraft.client.renderer.entity.ZombieRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

/**
 * 被腐败的生物的样子，对任何用模型画的生物都管用，不用一个个适配：模型压暗、偏紫；同一个模型再画两遍，
 * 一遍是斑驳的腐败（{@code corruption_overlay.png}，按模型自己的贴图坐标铺上去），一遍是会像心跳一样明暗的发光脉
 * （{@code corruption_overlay_veins.png}，全亮、叠加）。盔甲和头颅这些也是模型，所以一起变；手上的物品不变。
 *
 * <p>入口在 {@code DissolveEntityMixin}：实体的渲染状态做好时记下它是不是被腐败的，提交时换成这里的收集器。
 * 套在消散的收集器外面，所以被腐败的生物消散时腐败的样子也一起消散。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class CorruptedLook {
    private CorruptedLook() {}

    static final Identifier OVERLAY = LogosDimension.id("textures/entity/corruption_overlay.png");
    static final Identifier VEINS = LogosDimension.id("textures/entity/corruption_overlay_veins.png");
    private static final RenderType SKIN = RenderTypes.entityTranslucent(OVERLAY), GLOW = RenderTypes.eyes(VEINS);
    /** 压暗、带一点紫：乘到模型原来的颜色上。 */
    static final int DARKEN = 0xFF5E4A68;
    /** 心跳的周期（毫秒），和核心差不多。 */
    private static final long BEAT_MS = 1600;

    private static final Set<EntityRenderState> CORRUPTED = Collections.newSetFromMap(new IdentityHashMap<>());

    @SubscribeEvent
    static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(CorruptionMobs.FILTH_ZOMBIE.get(), ZombieRenderer::new);
        event.registerEntityRenderer(CorruptionMobs.FILTH_SKELETON.get(), SkeletonRenderer::new);
        event.registerEntityRenderer(CorruptionMobs.FILTH_SILVERFISH.get(), SilverfishRenderer::new);
        event.registerEntityRenderer(CorruptionMobs.FILTH_SLIME.get(), SlimeRenderer::new);
    }

    /** 实体的渲染状态做好了：被腐败的记下来。 */
    public static void extracted(Entity entity, EntityRenderState state) {
        if (CorruptionMobs.isCorrupted(entity)) CORRUPTED.add(state);
    }

    /** 这个渲染状态往哪里提交：被腐败的换成加腐败样子的收集器。 */
    public static SubmitNodeCollector collector(SubmitNodeCollector out, EntityRenderState state) {
        return CORRUPTED.isEmpty() || !CORRUPTED.contains(state) ? out : new Collector(out, out);
    }

    public static boolean corrupted(EntityRenderState state) {
        return CORRUPTED.contains(state);
    }

    /** 渲染状态在一帧开始之前就做好了，所以到这一帧结束才忘掉。 */
    @SubscribeEvent
    static void onFrameEnd(RenderFrameEvent.Post event) {
        CORRUPTED.clear();
    }

    /** 发光脉的颜色：随心跳在 55% 到 100% 之间。 */
    private static int veinColor() {
        float t = (Util.getMillis() % BEAT_MS) / (float) BEAT_MS;
        double beat = Math.exp(-Math.pow(t / .06, 2)) + Math.exp(-Math.pow((t - 1) / .06, 2)) + .6 * Math.exp(-Math.pow((t - .2) / .06, 2));
        int g = (int) (255 * (.55 + .45 * Math.min(1, beat)));
        return ARGB.color(255, g, g, g);
    }

    private static final class Collector implements SubmitNodeCollector {
        private final SubmitNodeCollector root;
        private final OrderedSubmitNodeCollector out;

        Collector(SubmitNodeCollector root, OrderedSubmitNodeCollector out) {
            this.root = root;
            this.out = out;
        }

        @Override public OrderedSubmitNodeCollector order(int order) { return new Collector(root, root.order(order)); }

        @Override public <S> void submitModel(Model<? super S> model, S state, PoseStack pose, RenderType type, int light, int overlay, int tint,
                @Nullable TextureAtlasSprite sprite, int outline, ModelFeatureRenderer.@Nullable CrumblingOverlay crumbling) {
            out.submitModel(model, state, pose, type, light, overlay, ARGB.multiply(tint, DARKEN), sprite, outline, crumbling);
            // 图集上的模型（告示牌、旗帜这类）贴图坐标是图集的，铺不上去
            if (sprite != null) return;
            out.submitModel(model, state, pose, SKIN, light, overlay, -1, null, 0, null);
            out.submitModel(model, state, pose, GLOW, LightCoordsUtil.FULL_BRIGHT, overlay, veinColor(), null, 0, null);
        }

        @Override public void submitModelPart(ModelPart part, PoseStack pose, RenderType type, int light, int overlay, @Nullable TextureAtlasSprite sprite,
                boolean sheeted, boolean hasFoil, int tint, ModelFeatureRenderer.@Nullable CrumblingOverlay crumbling, int outline) {
            out.submitModelPart(part, pose, type, light, overlay, sprite, sheeted, hasFoil, ARGB.multiply(tint, DARKEN), crumbling, outline);
            if (sprite != null) return;
            out.submitModelPart(part, pose, SKIN, light, overlay, null, false, false, -1, null, 0);
            out.submitModelPart(part, pose, GLOW, LightCoordsUtil.FULL_BRIGHT, overlay, null, false, false, veinColor(), null, 0);
        }

        // ---- 原样通过 -------------------------------------------------------------------------------

        @Override public void submitCustomGeometry(PoseStack pose, RenderType type, SubmitNodeCollector.CustomGeometryRenderer renderer) {
            out.submitCustomGeometry(pose, type, renderer);
        }

        @Override public void submitBlockModel(PoseStack pose, RenderType type, List<BlockStateModelPart> parts, int[] tints, int light, int overlay, int outline) {
            out.submitBlockModel(pose, type, parts, tints, light, overlay, outline);
        }

        @Override public void submitItem(PoseStack pose, ItemDisplayContext context, int light, int overlay, int outline, int[] tints,
                List<BakedQuad> quads, ItemStackRenderState.FoilType foil) {
            out.submitItem(pose, context, light, overlay, outline, tints, quads, foil);
        }

        @Override public void submitShadow(PoseStack pose, float radius, List<EntityRenderState.ShadowPiece> pieces) { out.submitShadow(pose, radius, pieces); }

        @Override public void submitFlame(PoseStack pose, EntityRenderState state, Quaternionf rotation) { out.submitFlame(pose, state, rotation); }

        @Override public void submitMovingBlock(PoseStack pose, MovingBlockRenderState state) { out.submitMovingBlock(pose, state); }

        @Override public void submitBreakingBlockModel(PoseStack pose, BlockStateModel model, long seed, int progress) {
            out.submitBreakingBlockModel(pose, model, seed, progress);
        }

        @Override public void submitNameTag(PoseStack pose, @Nullable Vec3 attachment, int offset, Component name, boolean seeThrough, int light, double distanceSq, CameraRenderState camera) {
            out.submitNameTag(pose, attachment, offset, name, seeThrough, light, distanceSq, camera);
        }

        @Override public void submitText(PoseStack pose, float x, float y, FormattedCharSequence text, boolean dropShadow, Font.DisplayMode mode, int light, int color, int background, int outline) {
            out.submitText(pose, x, y, text, dropShadow, mode, light, color, background, outline);
        }

        @Override public void submitLeash(PoseStack pose, EntityRenderState.LeashState state) { out.submitLeash(pose, state); }

        @Override public void submitParticleGroup(SubmitNodeCollector.ParticleGroupRenderer renderer) { out.submitParticleGroup(renderer); }
    }
}
