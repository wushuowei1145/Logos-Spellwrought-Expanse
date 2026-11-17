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
    public static class State extends BlockEntityRenderState { float time, yaw, surge; boolean defeated; }
    public QueenHeartRenderer(BlockEntityRendererProvider.Context context) {}
    @SubscribeEvent static void register(EntityRenderersEvent.RegisterRenderers e) {
        e.registerBlockEntityRenderer(ElvenBlight.HEART_ENTITY.get(), QueenHeartRenderer::new);
        e.registerBlockEntityRenderer(ElvenBlight.NODE_ENTITY.get(), c -> new CorruptionCoreRenderer(c, LogosDimension.id("textures/entity/blight_node_palette.png")));
        e.registerEntityRenderer(ElvenBlight.QUEEN.get(), c -> new NpcRenderer<>(c, "corrupted_elf_queen"));
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
    }
    @Override public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
        meshes();
        float beat = state.defeated ? 0 : Math.max(SuspendedHeart.beat(state.time),state.surge);
        float scale = 3f * (1 + .035f * beat) / 16;
        pose.pushPose();
        pose.translate(.5, 0, .5);
        pose.mulPose(Axis.YP.rotationDegrees(-state.yaw));
        collector.submitCustomGeometry(pose, TYPE, (p, buf) -> SuspendedHeart.draw(p, buf, state.lightCoords, state.time, state.defeated));
        pose.scale(scale, scale * (state.defeated ? .8f : 1), scale);
        pose.translate(-shape.n, 0, -shape.n);
        collector.submitCustomGeometry(pose, TYPE, (p, buf) -> shell.draw(p, buf, state.lightCoords, state.defeated ? .12f : .45f + beat * .55f));
        pose.popPose();
    }
    public static java.util.List<net.minecraft.core.BlockPos> attachmentPoints(net.minecraft.core.BlockPos core, net.minecraft.core.Direction facing) {
        double angle = Math.toRadians(facing.toYRot()), c = Math.cos(angle), s = Math.sin(angle);
        return SuspendedHeart.attachments().stream().map(p -> net.minecraft.core.BlockPos.containing(core.getX()+.5+c*p.x-s*p.z,core.getY()+p.y,core.getZ()+.5+s*p.x+c*p.z)).toList();
    }
    @Override public AABB getRenderBoundingBox(QueenHeart heart) { return new AABB(heart.getBlockPos()).inflate(27, 0, 27).expandTowards(0, 30, 0); }
    @Override public int getViewDistance() { return 128; }
}
