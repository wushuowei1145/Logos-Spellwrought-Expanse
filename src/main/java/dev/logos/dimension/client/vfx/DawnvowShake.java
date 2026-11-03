package dev.logos.dimension.client.vfx;

import dev.logos.dimension.LogosDimension;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * 巨型剑气爆发时的镜头抖动：在原本的视角上叠一点偏航、俯仰与翻滚的抖动，每刻衰减。离爆点越近越重，
 * {@link #RANGE} 格外不抖；按原版「屏幕效果」设置缩放，关掉它就不抖。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class DawnvowShake {
    /** 多远以内会抖，与抖到最重的距离。 */
    private static final double RANGE = 96, NEAR = 16;
    /** 最重时三个方向各摆多少度。 */
    private static final float YAW = 2.4f, PITCH = 3.2f, ROLL = 2.2f;
    /** 每刻剩下多少。 */
    private static final float DECAY = .86f;
    private static float strength;
    private static long seed;

    private DawnvowShake() {}

    /** 在 {@code at} 处爆开，按本机镜头离它多远加上一次抖动（{@code power} 为最重时的强度，1 为满）。 */
    public static void at(Vec3 at, float power) {
        Entity camera = Minecraft.getInstance().getCameraEntity();
        if (camera == null) return;
        double d = camera.position().distanceTo(at);
        float k = 1 - (float) Mth.clamp((d - NEAR) / (RANGE - NEAR), 0, 1);
        strength = Math.max(strength, power * k);
        seed++;
    }

    /** 当前强度（客户端场景检查用）。 */
    public static float strength() { return strength; }

    @SubscribeEvent static void tick(ClientTickEvent.Post event) {
        if (Minecraft.getInstance().isPaused()) return;
        strength = strength < .01f ? 0 : strength * DECAY;
    }

    @SubscribeEvent static void angles(ViewportEvent.ComputeCameraAngles event) {
        if (strength <= 0) return;
        float scale = strength * Minecraft.getInstance().options.screenEffectScale().get().floatValue();
        if (scale <= 0) return;
        double t = event.getPartialTick() + (Minecraft.getInstance().level == null ? 0 : Minecraft.getInstance().level.getGameTime());
        float a = (float) t * 2.1f + seed, b = (float) t * 2.7f + seed * 1.7f, c = (float) t * 1.9f + seed * 2.3f;
        event.setYaw(event.getYaw() + YAW * scale * (Mth.sin(a) + .5f * Mth.sin(a * 2.3f)));
        event.setPitch(event.getPitch() + PITCH * scale * (Mth.sin(b) + .5f * Mth.sin(b * 1.7f)));
        event.setRoll(event.getRoll() + ROLL * scale * Mth.sin(c));
    }
}
