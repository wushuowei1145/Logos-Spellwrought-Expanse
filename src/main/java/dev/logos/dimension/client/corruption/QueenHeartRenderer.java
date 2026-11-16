package dev.logos.dimension.client.corruption;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.client.NpcRenderer;
import dev.logos.dimension.elven.blight.ElvenBlight;
import dev.logos.dimension.elven.blight.QueenHeart;
import dev.logos.dimension.elven.blight.QueenHeartBlock;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import org.jspecify.annotations.Nullable;

/** 无家具树心上方的悬吊核心，缓慢搏动，分叉血管一直连接到天然树壁。 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public class QueenHeartRenderer implements BlockEntityRenderer<QueenHeart, QueenHeartRenderer.State> {
    private static final RenderType TYPE = RenderTypes.entityCutout(LogosDimension.id("textures/entity/queen_heart_palette.png"));
    private static CoreMesh shell;
    private static CoreShape shape;
    public static class State extends BlockEntityRenderState { float time, yaw, surge, collapse, phaseAge; boolean defeated; double drop; Vec3 queen; int phase, severed; }
    public QueenHeartRenderer(BlockEntityRendererProvider.Context context) {}
    @SubscribeEvent static void register(EntityRenderersEvent.RegisterRenderers e) {
        e.registerBlockEntityRenderer(ElvenBlight.HEART_ENTITY.get(), QueenHeartRenderer::new);
        e.registerBlockEntityRenderer(ElvenBlight.NODE_ENTITY.get(), c -> new CorruptionCoreRenderer(c, LogosDimension.id("textures/entity/blight_node_palette.png")));
        e.registerEntityRenderer(ElvenBlight.QUEEN.get(), dev.logos.dimension.client.QueenRenderer::new);
        e.registerEntityRenderer(ElvenBlight.TETHER.get(), QueenTetherRenderer::new);
        // 公主的模型以后另做；现在用森林精灵的渲染器和动画（外观由实体给的零件决定）。
        e.registerEntityRenderer(ElvenBlight.PRINCESS.get(), ctx -> new dev.logos.dimension.client.NpcRenderer<>(ctx, "forest_elf"));
    }
    private static void meshes() {
        if (shell != null) return;
        shape = SuspendedHeart.body().half();
        shell = CoreMesh.of(shape, 2);
    }
    @Override public State createRenderState() { return new State(); }
    @Override public void extractRenderState(QueenHeart heart, State state, float partial, Vec3 camera, ModelFeatureRenderer.@Nullable CrumblingOverlay breaking) {
        BlockEntityRenderer.super.extractRenderState(heart, state, partial, camera, breaking);
        state.time = heart.getLevel() == null ? 0 : (heart.getLevel().getGameTime() % 100000) + partial;
        state.yaw = heart.getBlockState().getValue(QueenHeartBlock.FACING).toYRot();
        state.surge=dev.logos.dimension.client.QueenEntranceClient.corePulse(heart.getBlockPos());
        state.defeated = heart.getBlockState().getValue(QueenHeartBlock.DEFEATED);
        state.drop = 0; state.queen = null; state.phase = 1; state.collapse = 0; state.severed = 0;
        var level = net.minecraft.client.Minecraft.getInstance().level;
        if (level != null) for (var e : level.entitiesForRendering()) if (e instanceof dev.logos.dimension.elven.blight.CorruptedElfQueen q && heart.getBlockPos().equals(q.heartPosition())) {
            state.phase = q.phase(); state.phaseAge = q.phaseAge(partial); state.drop = q.corePosition(partial).y - heart.getBlockPos().getY(); state.severed = q.brokenRoots();
            if (q.phase() == 4) state.collapse = dev.logos.dimension.elven.blight.QueenBattleRules.smooth(q.phaseAge(partial) / 80);
            if (q.transitioning()) state.surge = Math.max(state.surge, (float) Math.exp(-Math.pow((state.phaseAge - 24) / 10, 2)));
            state.queen = q.getPosition(partial).subtract(heart.getBlockPos().getBottomCenter()); break;
        }
    }
    @Override public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
        meshes();
        if (state.defeated) return;
        float beat = Math.max(SuspendedHeart.beat(state.time * (state.phase == 3 ? 3f : state.phase >= 2 ? 1.6f : 1)),state.surge);
        float scale = 3f * (1 + .035f * beat) / 16;
        pose.pushPose();
        pose.translate(.5, 0, .5);
        if (state.queen != null && state.phase < 4) {
            for (int i = 0; i < 3; i++) {
                final int n = i;
                // 第五刀之后线一根根断开（狂乱一开始）。
                float sever = state.phase == 3 ? dev.logos.dimension.elven.blight.QueenBattleRules.smooth((state.phaseAge - 2 - n * 3) / 12) : 0;
                if (sever >= .999f) continue;
                var top = new Vec3((n-1)*.7, 3.8 + state.drop, 0);
                var end = state.queen.add((n-1)*.5, 3.4 - n*.5, 0).lerp(top, sever);
                collector.submitCustomGeometry(pose, TYPE, (p, buf) -> QueenTetherRenderer.vessel(p, buf,
                        top, end, .075 * (1 - sever), state.time, state.lightCoords));
            }
        }
        // 狂乱：整颗心在抖。
        if (state.phase == 3) pose.translate(Math.sin(state.time * 2.3) * .07, Math.sin(state.time * 3.1) * .04, Math.cos(state.time * 1.9) * .07);
        pose.mulPose(Axis.YP.rotationDegrees(-state.yaw));
        collector.submitCustomGeometry(pose, TYPE, (p, buf) -> SuspendedHeart.draw(p, buf, state.lightCoords, state.time, false, state.drop, state.collapse, state.severed));
        pose.translate(0, state.drop, 0);
        pose.scale(scale, scale * (state.defeated ? .8f : 1), scale);
        pose.translate(-shape.n, 0, -shape.n);
        collector.submitCustomGeometry(pose, TYPE, (p, buf) -> shell.draw(p, buf, state.lightCoords, state.defeated ? .12f : .45f + beat * .55f));
        pose.popPose();
    }
    public static java.util.List<net.minecraft.core.BlockPos> attachmentPoints(net.minecraft.core.BlockPos core, net.minecraft.core.Direction facing) {
        double angle = Math.toRadians(facing.toYRot()), c = Math.cos(angle), s = Math.sin(angle);
        return SuspendedHeart.attachments().stream().map(p -> net.minecraft.core.BlockPos.containing(core.getX()+.5+c*p.x-s*p.z,core.getY()+p.y,core.getZ()+.5+s*p.x+c*p.z)).toList();
    }
    @Override public AABB getRenderBoundingBox(QueenHeart heart) { return new AABB(heart.getBlockPos()).inflate(27, 7, 27).expandTowards(0, 30, 0); }
    @Override public int getViewDistance() { return 128; }
}
