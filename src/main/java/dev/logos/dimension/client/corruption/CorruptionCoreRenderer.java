package dev.logos.dimension.client.corruption;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.corruption.Corruption;
import dev.logos.dimension.corruption.CorruptionCoreBlockEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import org.jspecify.annotations.Nullable;

/**
 * 腐败核心：按成长阶段画体素肉瘤（{@link CoreShape}），像心跳一样一鼓一鼓、脉一亮一暗；3 级的眼珠盯着最近的玩家，
 * 偶尔眨眼。离镜头远时换粗一档的造型。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public class CorruptionCoreRenderer implements BlockEntityRenderer<CorruptionCoreBlockEntity, CorruptionCoreRenderer.State> {
    static final Identifier TEXTURE = LogosDimension.id("textures/entity/corruption_core.png");
    private static final RenderType TYPE = RenderTypes.entitySolid(TEXTURE);
    /** 离镜头多远（格）换粗一档。 */
    private static final double FAR = 24;
    /** 一次心跳多少刻：咚、咚，然后停一下。 */
    private static final float BEAT = 32;

    /** 每级两档：[级][0 细 / 1 粗]，第一次用到时才生成。 */
    private static final CoreMesh[][] BODIES = new CoreMesh[4][2];
    private static final CoreShape[] SHAPES = new CoreShape[4];
    private static final CoreMesh[] EYES = new CoreMesh[2];

    public static class State extends BlockEntityRenderState {
        int stage;
        float bodyYaw, eyeYaw, eyePitch, blink, grown, time;
        boolean far;
    }

    public CorruptionCoreRenderer(BlockEntityRendererProvider.Context context) {}

    @SubscribeEvent
    static void register(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(Corruption.CORE_ENTITY.get(), CorruptionCoreRenderer::new);
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(CorruptionCoreBlockEntity core, State state, float partial, Vec3 camera,
                                   ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(core, state, partial, camera, breakProgress);
        state.stage = core.stage();
        state.bodyYaw = core.bodyYaw(partial);
        state.eyeYaw = core.eyeYaw(partial);
        state.eyePitch = core.eyePitch(partial);
        state.blink = core.blink(partial);
        state.grown = core.grown(partial);
        state.time = core.age() + partial;
        state.far = camera.distanceToSqr(Vec3.atCenterOf(core.getBlockPos())) > FAR * FAR;
    }

    static CoreShape shape(int stage) {
        if (SHAPES[stage] == null) SHAPES[stage] = CoreShape.flesh(stage);
        return SHAPES[stage];
    }

    private static CoreMesh body(int stage, boolean far) {
        int lod = far ? 1 : 0;
        if (BODIES[stage][lod] == null) {
            CoreShape s = shape(stage);
            BODIES[stage][lod] = far ? CoreMesh.of(s.half(), 2) : CoreMesh.of(s, 1);
        }
        return BODIES[stage][lod];
    }

    private static CoreMesh eye(boolean far) {
        int lod = far ? 1 : 0;
        if (EYES[lod] == null) {
            CoreShape ball = CoreShape.eyeball(shape(3).eyeRadius);
            EYES[lod] = far ? CoreMesh.of(ball.half(), 2) : CoreMesh.of(ball, 1);
        }
        return EYES[lod];
    }

    /** 这一级近处和远处的造型各有多少个面（客户端场景记开销用），3 级算上眼珠。 */
    public static int[] quads(int stage) {
        int eye = stage == 3 ? eye(false).quads : 0, eyeFar = stage == 3 ? eye(true).quads : 0;
        return new int[] {body(stage, false).quads + eye, body(stage, true).quads + eyeFar};
    }

    /** 心跳：两下一高一低，0 到 1。 */
    static float beat(float time) {
        float t = (time % BEAT) / BEAT;
        return (float) (Math.exp(-Math.pow(t / .06, 2)) + Math.exp(-Math.pow((t - 1) / .06, 2)) + .6 * Math.exp(-Math.pow((t - .2) / .06, 2)));
    }

    @Override
    public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
        CoreShape s = shape(state.stage);
        CoreMesh mesh = body(state.stage, state.far);
        float beat = beat(state.time);
        // 刚长大时先缩一点再鼓出来
        float pop = Mth.lerp(1 - (1 - state.grown) * (1 - state.grown), .82f, 1) + .07f * Mth.sin(state.grown * Mth.PI);
        float scale = (1 + .025f * beat) * pop / 16f;
        float glow = .82f + .18f * Math.min(1, beat);
        pose.pushPose();
        pose.translate(.5f, 0, .5f);
        pose.mulPose(Axis.YP.rotation(state.bodyYaw));
        pose.scale(scale, scale, scale);
        pose.translate(-s.n / 2f, 0, -s.n / 2f);
        collector.submitCustomGeometry(pose, TYPE, (p, buf) -> mesh.draw(p, buf, state.lightCoords, glow));
        if (s.eye != null) {
            CoreMesh ball = eye(state.far);
            float r = s.eyeRadius, m = (float) Math.ceil(r) * 2 + 2;
            pose.pushPose();
            pose.translate(s.eye[0], s.eye[1], s.eye[2]);
            pose.mulPose(Axis.YP.rotation(state.eyeYaw));
            pose.mulPose(Axis.XP.rotation(state.eyePitch));
            // 眨眼：上下压扁
            pose.scale(1, 1 - .85f * state.blink, 1);
            pose.translate(-m / 2, -m / 2, -m / 2);
            collector.submitCustomGeometry(pose, TYPE, (p, buf) -> ball.draw(p, buf, state.lightCoords, 1));
            pose.popPose();
        }
        pose.popPose();
    }

    /** 3 级连根有 3 格多宽、2 格多高。 */
    @Override
    public AABB getRenderBoundingBox(CorruptionCoreBlockEntity core) {
        return new AABB(core.getBlockPos()).inflate(3.6, 0, 3.6).expandTowards(0, 3, 0);
    }
}
