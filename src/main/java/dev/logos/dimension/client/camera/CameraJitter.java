package dev.logos.dimension.client.camera;

import dev.logos.dimension.LogosDimension;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.jspecify.annotations.Nullable;

/**
 * 镜头很轻的平移颤动（只挪位置，不转角度、不横滚）：下坠时一直轻轻地上下抖（docs/JIAN_SUI_BOSS.md 第 10.2 节）。
 * 现有的镜头抖动（{@code DawnvowShake}）只转角度，平移要在镜头摆好以后加，由 {@code CameraShotMixin} 在每帧最后调 {@link #offset}。
 *
 * <p>谁要抖就每刻调一次 {@link #want}（强度 0..1），不调了就慢慢停。按原版「屏幕效果」滑条缩放；关掉「视角摇晃」就不抖。
 * 幅度只有几厘米，镜头不会因此穿进方块。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class CameraJitter {
    /** 强度为 1 时上下摆多少格。 */
    private static final double AMPLITUDE = .028;
    private static float wanted, amount;

    private CameraJitter() {}

    /** 这一刻想要多大的颤动（0..1）；同一刻里多处调用取最大的。 */
    public static void want(float strength) {
        wanted = Math.max(wanted, Mth.clamp(strength, 0, 1));
    }

    @SubscribeEvent
    static void tick(ClientTickEvent.Post event) {
        if (Minecraft.getInstance().isPaused()) return;
        amount += (wanted - amount) * .15f;
        if (amount < .002f) amount = 0;
        wanted = 0;
    }

    /** 这一帧镜头位置要加的偏移；不抖时返回 null。 */
    public static @Nullable Vec3 offset(float partialTick) {
        var mc = Minecraft.getInstance();
        if (amount <= 0 || mc.level == null || !mc.options.bobView().get()) return null;
        double scale = amount * mc.options.screenEffectScale().get() * AMPLITUDE;
        if (scale <= 0) return null;
        double t = (mc.level.getGameTime() + partialTick) / 20.0;
        // 两个不成倍数的频率叠起来，不会看出规律；横向只有一点
        double y = Math.sin(t * 2 * Math.PI * 7.3) * .6 + Math.sin(t * 2 * Math.PI * 11.9 + 1.3) * .4;
        double x = Math.sin(t * 2 * Math.PI * 5.1 + .7) * .25, z = Math.sin(t * 2 * Math.PI * 6.7 + 2.1) * .25;
        return new Vec3(x * scale, y * scale, z * scale);
    }
}
