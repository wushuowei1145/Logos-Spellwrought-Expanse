package dev.logos.dimension.client.hero;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.logos.client.ShaderPackOverlay;
import dev.logos.client.VfxParts;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.client.screenfx.PostVfx;
import dev.logos.dimension.client.ultimate.UltimateClient;
import dev.logos.dimension.ultimate.HeroFinisherTimeline;
import dev.logos.dimension.ultimate.UltimateChain;
import dev.logos.dimension.ultimate.UltimatePayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import org.jspecify.annotations.Nullable;

/**
 * 「誓约锁定」（规范 5.1）：汇流时锁住的主目标头顶一枚暖金色的星芒，慢慢转、一下一下地脉动，地上一圈细细的金环，
 * 玩家一眼就知道最后一击会砸向谁。只画在主人自己的画面上（状态包 {@link UltimatePayloads.Status#lock()} 只发给他），
 * 放最后一击以后跟到魔弹砸下那一刻再淡掉。不写字。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class VowLockMarker {
    private static final int GOLD = 0xFFD27A, PALE = 0xFFF1C9, DEEP = 0xC98A1E;

    private VowLockMarker() {}

    /** 此刻标在谁头上（实体 ID）、亮几成；不标时是 null（测试也用）。 */
    public static float @Nullable [] state(float partial) {
        UltimatePayloads.Status s = UltimateClient.status();
        if (s.lock() < 0) return null;
        UltimateChain.Phase phase = s.chainPhase();
        float alpha;
        if (phase == UltimateChain.Phase.CONVERGED) {
            // 汇流开头淡入，快到头时一闪一闪地催
            float left = s.converged() - UltimateClient.since(partial);
            float elapsed = UltimateChain.CONVERGE - left;
            alpha = Mth.clamp(elapsed / 6, 0, 1) * (left < 30 ? .65f + .35f * Mth.cos(left * .6f) : 1);
        } else if (phase == UltimateChain.Phase.FINISHER) {
            var player = Minecraft.getInstance().player;
            HeroFinisherShow show = player == null ? null : HeroFinisherShow.of(player.getId());
            float age = show == null ? 0 : show.age(partial);
            alpha = 1 - HeroFinisherTimeline.smooth(HeroFinisherTimeline.IMPACT - 3, HeroFinisherTimeline.IMPACT, age);
        } else {
            return null;
        }
        return alpha <= 0 ? null : new float[]{s.lock(), alpha};
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void submit(SubmitCustomGeometryEvent event) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        float[] st = state(partial);
        if (st == null) return;
        Entity target = mc.level.getEntity((int) st[0]);
        if (target == null || !target.isAlive()) return;
        float a = st[1];
        float t = mc.level.getGameTime() + partial;
        var camera = event.getLevelRenderState().cameraRenderState;
        VfxParts.View view = VfxParts.View.of(camera);
        PoseStack stack = event.getPoseStack();
        SubmitNodeCollector out = ShaderPackOverlay.collector(event.getSubmitNodeCollector());
        Vec3 feet = target.getPosition(partial);
        Vec3 star = feet.add(0, target.getBbHeight() + .75 + .08 * Mth.sin(t * .15f), 0);
        float pulse = .85f + .15f * Mth.sin(t * .4f);
        stack.pushPose();
        stack.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
        layer("heal/glow", stack, out, (p, buf) -> {
            VfxParts.sprite(p, buf, view, star, .7f * pulse, 0, VfxParts.argb(.22f * a, GOLD));
            VfxParts.sprite(p, buf, view, star, .16f, 0, VfxParts.argb(.8f * a, 0xFFFFFF));
        });
        // 星芒底下垫一层半透明的深金：白天的亮天上也认得出是一颗金色的星
        if (PostVfx.collecting()) drawBody(stack.last(), PostVfx.blend(PostVfx.logos("arcane_impact/star"), false), view, star, pulse, t, a);
        else out.submitCustomGeometry(stack, VfxParts.blend("arcane_impact/star"), (p, buf) -> drawBody(p, buf, view, star, pulse, t, a));
        layer("arcane_impact/star", stack, out, (p, buf) -> {
            VfxParts.sprite(p, buf, view, star, .8f * pulse, t * .03f, VfxParts.argb(a, GOLD));
            VfxParts.sprite(p, buf, view, star, .38f, -t * .05f + .4f, VfxParts.argb(.75f * a, PALE));
        });
        float r = Math.max(.7f, target.getBbWidth() * .9f);
        layer("heal/ring", stack, out, (p, buf) -> VfxParts.flat(p, buf, feet.add(0, .06, 0), new Vec3(1, 0, 0), new Vec3(0, 0, 1), r * pulse, t * .02f,
                VfxParts.argb(.6f * a, GOLD)));
        stack.popPose();
    }

    private static void drawBody(PoseStack.Pose p, com.mojang.blaze3d.vertex.VertexConsumer buf, VfxParts.View view, Vec3 star, float pulse, float t, float a) {
        VfxParts.sprite(p, buf, view, star, .85f * pulse, t * .03f, VfxParts.argb(.75f * a, DEEP));
    }

    private static void layer(String texture, PoseStack stack, SubmitNodeCollector out, SubmitNodeCollector.CustomGeometryRenderer draw) {
        if (PostVfx.collecting()) draw.render(stack.last(), PostVfx.add(PostVfx.logos(texture), false));
        else out.submitCustomGeometry(stack, VfxParts.add(texture), draw);
    }
}
