package dev.logos.dimension.client.dissolve;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.logos.client.ShaderPackOverlay;
import dev.logos.client.VfxParts;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

/**
 * 消散中的目标往这里提交。实体的渲染器和物品的层照常工作，提交的东西在这里被切：模型、模型部件和自定义几何（GeckoLib 的）
 * 马上写进 {@link DissolveCapture}，留下的部分用同一个渲染类型当自定义几何画；烘焙好的物品面换成 {@link DissolveMesh} 里还留着的格子，仍然交给原版。
 * 所以盔甲、手持物、渲染层都跟着消散，不用知道它们是什么。名牌、拴绳这些原样通过。
 */
final class DissolveCollector implements SubmitNodeCollector {
    /** 一个目标的所有提交共用的东西。 */
    private static final class Target {
        final Dissolve dissolve;
        /** 从姿势之后的空间到图案空间。 */
        final Matrix4fc toPattern;
        /** 第一人称时从姿势之后的空间到相对镜头的世界坐标轴；已经是的话为 null。 */
        final @Nullable Matrix4fc toWorld;
        /** 单独一个物品：格子的模型空间就是图案空间，所以扫过的方向跟着物品走，不管怎么拿。 */
        final boolean itemSpace;
        final boolean firstPerson, particles;
        final float now;
        final Vec3 camera;
        final long frame;
        final int tick;
        final DissolveCapture capture = new DissolveCapture();
        final QuadInstance instance = new QuadInstance();

        Target(Dissolve dissolve, Matrix4fc toPattern, @Nullable Matrix4fc toWorld, boolean itemSpace, boolean firstPerson, float now, long frame, int tick) {
            this.dissolve = dissolve;
            this.toPattern = toPattern;
            this.toWorld = toWorld;
            this.itemSpace = itemSpace;
            this.firstPerson = firstPerson;
            this.particles = !Dissolves.shadowPass();
            this.now = now;
            this.camera = Minecraft.getInstance().gameRenderer.getMainCamera().position();
            this.frame = frame;
            this.tick = tick;
        }

        boolean gone() { return dissolve.gone(now); }
    }

    private final SubmitNodeCollector root;
    private final OrderedSubmitNodeCollector out;
    private final Target target;

    private DissolveCollector(SubmitNodeCollector root, OrderedSubmitNodeCollector out, Target target) {
        this.root = root;
        this.out = out;
        this.target = target;
    }

    /** 离镜头 {@code x y z} 的实体，脚底是图案原点。 */
    static DissolveCollector entity(SubmitNodeCollector out, Dissolve dissolve, double x, double y, double z, PoseStack pose, float now, long frame, int tick) {
        Matrix4f toPattern = new Matrix4f().translation((float) -x, (float) -y, (float) -z).mul(new Matrix4f(pose.last().pose()).invert());
        return new DissolveCollector(out, out, new Target(dissolve, toPattern, null, false, false, now, frame, tick));
    }

    /** 单独一个物品，{@code pose} 是手的姿势。 */
    static DissolveCollector item(SubmitNodeCollector out, Dissolve dissolve, ItemDisplayContext context, PoseStack pose, float now, long frame, int tick) {
        Matrix4f toPattern = new Matrix4f(pose.last().pose()).invert();
        Matrix4f toWorld = context.firstPerson() ? Dissolve.firstPersonToWorld(Minecraft.getInstance().gameRenderer.getMainCamera().rotation()) : null;
        return new DissolveCollector(out, out, new Target(dissolve, toPattern, toWorld, true, context.firstPerson(), now, frame, tick));
    }

    @Override public OrderedSubmitNodeCollector order(int order) { return new DissolveCollector(root, root.order(order), target); }

    /** 带着 {@link #flushItems} 进入绘制阶段，自己不写顶点。 */
    private static final RenderType FLUSH = VfxParts.add("surface/fire_loop");
    /** 物品图集和附魔光效：这一轮画过的物品顶点都在里面。 */
    private static final RenderType[] ITEMS = {Sheets.cutoutBlockItemSheet(), Sheets.translucentBlockItemSheet(), Sheets.cutoutItemSheet(),
            Sheets.translucentItemSheet(), RenderTypes.glint(), RenderTypes.glintTranslucent(), RenderTypes.entityGlint()};

    /**
     * 现在就把手上的物品画掉，发光才会落在物品上面。物品的顶点在固定缓冲里，一轮结束才画；第一人称那一轮先画所有特性再画固定缓冲，
     * 不提前的话物品会盖住贴在自己表面的东西。提前画固定缓冲只是把它往前挪。
     */
    private static void flushItems() {
        var buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        for (RenderType type : ITEMS) buffers.endBatch(type);
    }

    /** 发光条，叠在它贴着的格子上。第一人称时物品的固定缓冲先画，做法和 Logos 的魔力缠绕一样。 */
    private void glow(PoseStack pose, Dissolve.Glow glow) {
        if (glow.empty() || !target.particles) return;
        glow.bloom(target.firstPerson);
        SubmitNodeCollector over = ShaderPackOverlay.item(root, target.firstPerson);
        if (target.firstPerson && over == root) root.order(0).submitCustomGeometry(pose, FLUSH, (p, buf) -> flushItems());
        glow.submit(pose, target.firstPerson ? over.order(1) : over);
    }

    private void cut(PoseStack pose, RenderType type) {
        Dissolve.Glow glow = new Dissolve.Glow();
        DissolveCapture.Left left = target.capture.cut(target.dissolve, DissolveSkin.of(type), target.toPattern, target.toWorld, target.now,
                glow, target.particles, target.camera, target.frame, target.tick);
        if (!left.empty()) out.submitCustomGeometry(pose, type, (p, buf) -> left.draw(buf));
        glow(pose, glow);
    }

    // ---- 要切的 ----------------------------------------------------------------------------------

    @Override public <S> void submitModel(Model<? super S> model, S state, PoseStack pose, RenderType type, int light, int overlay, int tint,
            @Nullable TextureAtlasSprite sprite, int outline, ModelFeatureRenderer.@Nullable CrumblingOverlay crumbling) {
        if (target.gone()) return;
        model.setupAnim(state);
        model.renderToBuffer(pose, sprite == null ? target.capture : sprite.wrap(target.capture), light, overlay, tint);
        cut(pose, type);
    }

    @Override public void submitModelPart(ModelPart part, PoseStack pose, RenderType type, int light, int overlay, @Nullable TextureAtlasSprite sprite,
            boolean sheeted, boolean hasFoil, int tint, ModelFeatureRenderer.@Nullable CrumblingOverlay crumbling, int outline) {
        if (target.gone()) return;
        part.render(pose, sprite == null ? target.capture : sprite.wrap(target.capture), light, overlay, tint);
        cut(pose, type);
    }

    @Override public void submitCustomGeometry(PoseStack pose, RenderType type, SubmitNodeCollector.CustomGeometryRenderer renderer) {
        if (target.gone()) return;
        if (type.mode() != VertexFormat.Mode.QUADS) {
            out.submitCustomGeometry(pose, type, renderer);
            return;
        }
        renderer.render(pose.last(), target.capture);
        cut(pose, type);
    }

    @Override public void submitBlockModel(PoseStack pose, RenderType type, List<BlockStateModelPart> parts, int[] tints, int light, int overlay, int outline) {
        if (target.gone()) return;
        QuadInstance instance = target.instance;
        instance.setLightCoords(light);
        instance.setOverlayCoords(overlay);
        for (BlockStateModelPart part : parts) {
            for (Direction direction : Direction.values()) capture(pose, part.getQuads(direction), tints, instance);
            capture(pose, part.getQuads(null), tints, instance);
        }
        cut(pose, type);
    }

    private void capture(PoseStack pose, List<BakedQuad> quads, int[] tints, QuadInstance instance) {
        for (BakedQuad quad : quads) {
            int layer = quad.materialInfo().tintIndex();
            instance.setColor(layer >= 0 && layer < tints.length ? tints[layer] : -1);
            target.capture.putBakedQuad(pose.last(), quad, instance);
        }
    }

    @Override public void submitItem(PoseStack pose, ItemDisplayContext context, int light, int overlay, int outline, int[] tints,
            List<BakedQuad> quads, ItemStackRenderState.FoilType foil) {
        if (target.gone()) return;
        DissolveMesh mesh = DissolveMesh.of(quads, target.particles ? DissolveMesh.Detail.FINE : DissolveMesh.Detail.TEXEL);
        if (mesh == null) return;
        Matrix4fc toPattern = target.itemSpace ? new Matrix4f() : new Matrix4f(target.toPattern).mul(pose.last().pose());
        Dissolve.Glow glow = new Dissolve.Glow();
        List<BakedQuad> left = target.dissolve.left(mesh, mesh.noise, pose.last(), toPattern, target.toWorld, target.now, glow, target.particles,
                target.camera, target.frame, target.tick);
        if (!left.isEmpty()) out.submitItem(pose, context, light, overlay, outline, tints, left, foil);
        glow(pose, glow);
    }

    // ---- 目标消失之前原样通过 -----------------------------------------------------------------------

    @Override public void submitShadow(PoseStack pose, float radius, List<EntityRenderState.ShadowPiece> pieces) {
        if (!target.gone()) out.submitShadow(pose, radius, pieces);
    }

    @Override public void submitFlame(PoseStack pose, EntityRenderState state, Quaternionf rotation) {
        if (!target.gone()) out.submitFlame(pose, state, rotation);
    }

    @Override public void submitMovingBlock(PoseStack pose, MovingBlockRenderState state) {
        if (!target.gone()) out.submitMovingBlock(pose, state);
    }

    @Override public void submitBreakingBlockModel(PoseStack pose, BlockStateModel model, long seed, int progress) {
        if (!target.gone()) out.submitBreakingBlockModel(pose, model, seed, progress);
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
