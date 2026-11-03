package dev.logos.dimension.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.paint.PaintSurfaceBlock;
import dev.logos.dimension.paint.PaintSurfaceBlockEntity;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import org.jspecify.annotations.Nullable;

/**
 * 漆面渲染：不画半透明色层，而是取被刷木方块自己的粒子贴图（通常是木头侧面纹理），
 * 按油漆颜色整体染色、不透明地画在贴面上——等于直接给木纹换颜色，纹理清晰不糊。
 * 形状按 COVER 变体裁切，UV 取贴图对应区域，木纹与下方方块自然衔接。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public class PaintSurfaceRenderer implements BlockEntityRenderer<PaintSurfaceBlockEntity, PaintSurfaceRenderer.State> {
    /** 高度 16 = 一格时的漆层厚度。 */
    private static final float THICKNESS = 0.25f;

    public static class State extends BlockEntityRenderState {
        int color = 0xFFFFFFFF;
        Direction facing = Direction.NORTH;
        PaintSurfaceBlock.Cover cover = PaintSurfaceBlock.Cover.FULL;
        TextView sprite = null;
    }

    /** 渲染期只用到精灵的 UV 范围，避免每帧再查模型管理器。 */
    public record TextView(float u0, float v0, float u1, float v1) {
    }

    public PaintSurfaceRenderer(BlockEntityRendererProvider.Context context) {
    }

    @SubscribeEvent
    static void register(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(
            (net.minecraft.world.level.block.entity.BlockEntityType<PaintSurfaceBlockEntity>) (net.minecraft.world.level.block.entity.BlockEntityType<?>) dev.logos.dimension.paint.PaintSystem.SURFACE_ENTITY.get(),
            PaintSurfaceRenderer::new);
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(PaintSurfaceBlockEntity be, State state, float partialTicks, Vec3 camera,
                                   ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(be, state, partialTicks, camera, breakProgress);
        state.color = be.paint();
        var blockState = be.getBlockState();
        state.facing = blockState.getValue(PaintSurfaceBlock.FACING);
        state.cover = blockState.getValue(PaintSurfaceBlock.COVER);
        state.sprite = null;
        var level = be.getLevel();
        if (level != null) {
            var target = level.getBlockState(be.getBlockPos().relative(state.facing));
            var material = Minecraft.getInstance().getModelManager().getBlockStateModelSet().getParticleMaterial(target);
            if (material != null) {
                TextureAtlasSprite sprite = material.sprite();
                state.sprite = new TextView(sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1());
            }
        }
    }

    @Override
    public void submit(State state, PoseStack pose, SubmitNodeCollector collector, CameraRenderState camera) {
        TextView sprite = state.sprite;
        if (sprite == null) return;
        int r = (state.color >> 16) & 0xFF, g = (state.color >> 8) & 0xFF, b = state.color & 0xFF;
        int light = state.lightCoords;
        List<float[]> boxes = boxes(state.cover);
        collector.order(-1).submitCustomGeometry(pose, Sheets.cutoutBlockSheet(), (p, buffer) -> {
            for (float[] box : boxes) {
                float x0 = box[0], y0 = box[1], x1 = box[2], y1 = box[3], depth = box[4];
                for (int i = 0; i < 4; i++) {
                    float lx = switch (i) { case 0, 3 -> x0; default -> x1; };
                    float ly = i < 2 ? y0 : y1;
                    float[] world = world(state.facing, lx, ly, depth);
                    float u = sprite.u0() + (sprite.u1() - sprite.u0()) * (lx / 16f);
                    float v = sprite.v0() + (sprite.v1() - sprite.v0()) * (1f - ly / 16f);
                    float[] normal = normal(state.facing);
                    buffer.addVertex(p, world[0], world[1], world[2])
                        .setColor(r, g, b, 255).setUv(u, v)
                        .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
                        .setNormal(p, normal[0], normal[1], normal[2]);
                }
            }
        });
    }

    /** 局部 (lx, ly, 深度) → 方块内坐标 [0,1]。 */
    private static float[] world(Direction facing, float lx, float ly, float depth) {
        float l = lx / 16f, u = ly / 16f, d = depth / 16f;
        return switch (facing) {
            case NORTH -> new float[]{l, u, d};
            case SOUTH -> new float[]{1f - l, u, 1f - d};
            case EAST -> new float[]{1f - d, u, l};
            case WEST -> new float[]{d, u, 1f - l};
            case UP -> new float[]{l, d, 1f - u};
            case DOWN -> new float[]{l, 1f - d, u};
        };
    }

    private static float[] normal(Direction facing) {
        return switch (facing) {
            case NORTH -> new float[]{0, 0, 1};
            case SOUTH -> new float[]{0, 0, -1};
            case EAST -> new float[]{-1, 0, 0};
            case WEST -> new float[]{1, 0, 0};
            case UP -> new float[]{0, 1, 0};
            case DOWN -> new float[]{0, -1, 0};
        };
    }

    /** 每个元素：{x0, y0, x1, y1, 距贴面深度}；深度 8 的是楼梯高台后退的那一截。 */
    private static List<float[]> boxes(PaintSurfaceBlock.Cover cover) {
        return switch (cover) {
            case FULL -> List.of(new float[]{0, 0, 16, 16, THICKNESS});
            case HALF_BOTTOM -> List.of(new float[]{0, 0, 16, 8, THICKNESS});
            case HALF_TOP -> List.of(new float[]{0, 8, 16, 16, THICKNESS});
            case FRONT_STEP -> List.of(new float[]{0, 0, 16, 8, THICKNESS},
                                       new float[]{0, 8, 16, 16, 8f + THICKNESS});
            case FRONT_STEP_UD -> List.of(new float[]{0, 8, 16, 16, THICKNESS},
                                          new float[]{0, 0, 16, 8, 8f + THICKNESS});
            case SIDE_LEFT -> List.of(new float[]{0, 0, 16, 8, THICKNESS},
                                      new float[]{0, 8, 8, 16, THICKNESS});
            case SIDE_RIGHT -> List.of(new float[]{0, 0, 16, 8, THICKNESS},
                                       new float[]{8, 8, 16, 16, THICKNESS});
            case SIDE_LEFT_UD -> List.of(new float[]{0, 8, 16, 16, THICKNESS},
                                         new float[]{0, 0, 8, 8, THICKNESS});
            case SIDE_RIGHT_UD -> List.of(new float[]{0, 8, 16, 16, THICKNESS},
                                          new float[]{8, 0, 16, 8, THICKNESS});
            case STRIP_Y0 -> List.of(new float[]{0, 0, 16, 8, THICKNESS});
            case STRIP_Y1 -> List.of(new float[]{0, 8, 16, 16, THICKNESS});
            case STRIP_X0 -> List.of(new float[]{0, 0, 8, 16, THICKNESS});
            case STRIP_X1 -> List.of(new float[]{8, 0, 16, 16, THICKNESS});
        };
    }
}
