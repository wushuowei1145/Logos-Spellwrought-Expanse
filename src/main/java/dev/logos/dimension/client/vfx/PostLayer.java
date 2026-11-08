package dev.logos.dimension.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.logos.client.VfxParts;
import dev.logos.dimension.client.screenfx.PostVfx;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

/**
 * 曦誓特效的一层贴图（Logos 的 {@code logos:textures/vfx/...}）。平常照旧交给世界画；曦誓的后期把周围调暗时
 * （{@link DawnvowScreen}），交给 {@link PostVfx} 在调暗之后再画，光点和剑气就不会跟着暗下去。
 */
record PostLayer(RenderType world, Identifier texture, boolean additive) {
    /** 加法发光。 */
    static PostLayer add(String path) {
        return new PostLayer(VfxParts.add(path), PostVfx.logos(path), true);
    }

    /** 半透明混合。 */
    static PostLayer blend(String path) {
        return new PostLayer(VfxParts.blend(path), PostVfx.logos(path), false);
    }

    void submit(PoseStack pose, SubmitNodeCollector out, SubmitNodeCollector.CustomGeometryRenderer draw) {
        if (PostVfx.collecting()) draw.render(pose.last(), additive ? PostVfx.add(texture, false) : PostVfx.blend(texture, false));
        else out.submitCustomGeometry(pose, world, draw);
    }
}
