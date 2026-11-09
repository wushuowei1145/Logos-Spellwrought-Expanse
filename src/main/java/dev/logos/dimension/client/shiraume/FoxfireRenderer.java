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
        VfxParts.View view = VfxParts.View.of(camera);
        SubmitNodeCollector glow = ShaderPackOverlay.collector(out);
        Vec3 at = new Vec3(0, .3, 0);
        float pulse = 1 + .15f * (float) Math.sin(state.age * .9f);
        glow.submitCustomGeometry(pose, FLAME, (p, buf) -> VfxParts.sprite(p, buf, view, at, .75f * pulse, state.age * .2f, VfxParts.argb(.85f, FoxfireFlame.SHIRAUME.core)));
        glow.submitCustomGeometry(pose, GLOW, (p, buf) -> {
            VfxParts.sprite(p, buf, view, at, .5f * pulse, 0, VfxParts.argb(.9f, 0xF7B9DC));
            VfxParts.sprite(p, buf, view, at, .24f, 0, VfxParts.argb(1, 0xFFFFFF));
        });
        if (!Dissolves.shadowPass()) {
            Vec3 world = new Vec3(state.x, state.y + .3, state.z);
            BLOOM.begin(view).disc(world, .7f * pulse, VfxParts.argb(.75f, FoxfireFlame.SHIRAUME.core));
            BLOOM.flush();
        }
        super.submit(state, pose, out, camera);
    }
}
