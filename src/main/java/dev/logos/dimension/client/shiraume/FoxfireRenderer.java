package dev.logos.dimension.client.shiraume;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.logos.client.ShaderPackOverlay;
import dev.logos.client.VfxParts;
import dev.logos.dimension.client.dissolve.Dissolves;
import dev.logos.dimension.foxfire.FoxfireFlame;
import dev.logos.dimension.shiraume.FoxfireBolt;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;

/** 狐火飞弹：白梅独一份的淡粉狐火（docs/FOXFIRE.md），白的芯、淡粉的外焰，一团朝向镜头的光，带泛光；拖着樱花瓣与淡粉的灰（粒子在实体里放）。 */
public final class FoxfireRenderer extends EntityRenderer<FoxfireBolt, FoxfireRenderer.State> {
    private static final RenderType GLOW = VfxParts.add("heal/glow");
    private static final RenderType FLAME = VfxParts.add("fire_impact/glow");
    private static final SoftGlow BLOOM = new SoftGlow();

    public static final class State extends EntityRenderState {
        float age;
    }

    public FoxfireRenderer(EntityRendererProvider.Context context) {
        super(context);
        shadowRadius = 0;
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(FoxfireBolt foxfire, State state, float partial) {
        super.extractRenderState(foxfire, state, partial);
        state.age = foxfire.tickCount + partial;
    }

    @Override
    public void submit(State state, PoseStack pose, SubmitNodeCollector out, CameraRenderState camera) {
        flame(pose, out, VfxParts.View.of(camera), new Vec3(0, .3, 0), new Vec3(state.x, state.y + .3, state.z), state.age, 1, state.age * .2f, 1, 1);
        super.submit(state, pose, out, camera);
    }

    /**
     * 一团淡粉狐火（飞弹和白梅身边的狐火灯共用）：{@code at} 是相对 {@code pose} 的位置，{@code world} 是同一点的世界坐标（泛光用）；
     * {@code size} 1 是飞弹的大小，{@code spin} 是外焰转到的角度，{@code alpha} 整体的淡入淡出，{@code core} 白芯的浓淡（飞弹 1）。
     */
    public static void flame(PoseStack pose, SubmitNodeCollector out, VfxParts.View view, Vec3 at, Vec3 world, float age, float size, float spin,
                             float alpha, float core) {
        SubmitNodeCollector glow = ShaderPackOverlay.collector(out);
        float pulse = 1 + .15f * (float) Math.sin(age * .9f);
        glow.submitCustomGeometry(pose, FLAME, (p, buf) -> VfxParts.sprite(p, buf, view, at, .75f * size * pulse, spin,
                VfxParts.argb(.85f * alpha, FoxfireFlame.SHIRAUME.core)));
        glow.submitCustomGeometry(pose, GLOW, (p, buf) -> {
            VfxParts.sprite(p, buf, view, at, .5f * size * pulse, 0, VfxParts.argb(.9f * alpha, 0xF7B9DC));
            VfxParts.sprite(p, buf, view, at, .24f * size, 0, VfxParts.argb(alpha * core, 0xFFFFFF));
        });
        if (!Dissolves.shadowPass()) {
            BLOOM.begin(view).disc(world, .7f * size * pulse, VfxParts.argb(.75f * alpha, FoxfireFlame.SHIRAUME.core));
            BLOOM.flush();
        }
    }
}
