package dev.logos.dimension.client.screenfx;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.screenfx.ScreenCover;
import dev.logos.dimension.screenfx.ScreenCoverPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import org.jspecify.annotations.Nullable;

/**
 * 本机的全屏白、全屏黑（{@link ScreenCover}）：画在界面层最上面，按游戏刻计时。运镜藏界面时会取消
 * {@code RenderGuiEvent.Pre}，所以收已取消的事件（同开场标题）；盖满时把界面也藏起来。换维度或世界时作废。
 *
 * <p>开了「隐藏闪电的闪烁」时，盖上的那一段先慢后快（盖满的时刻不变，服务端的时序不受影响），看上去更慢。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class ScreenCoverRenderer {
    private static @Nullable ScreenCover cover;
    /** 盖在哪个维度上（只记维度，不留着整个世界）：换了维度就作废，退出世界时清掉。 */
    private static @Nullable ResourceKey<Level> dimension;
    private static long startTick;
    /** 客户端场景用：停在第几刻上（小于 0 不停）。 */
    private static float held = -1;

    private ScreenCoverRenderer() {}

    @SubscribeEvent
    static void payloads(RegisterClientPayloadHandlersEvent event) {
        event.register(ScreenCoverPayload.TYPE, (payload, context) -> context.enqueueWork(() -> play(payload.cover())));
    }

    /** 本机开始盖一次（也给客户端场景直接调）。 */
    public static void play(ScreenCover next) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        cover = next;
        dimension = mc.level.dimension();
        startTick = mc.level.getGameTime();
    }

    @SubscribeEvent
    static void loggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        cover = null;
        dimension = null;
    }

    /** 客户端场景用：让它停在第 {@code age} 刻的样子，传负数放开。 */
    public static void hold(float age) {
        held = age;
    }

    /** 现在盖住了多少（0..1）。 */
    public static float alpha(float partialTick) {
        var mc = Minecraft.getInstance();
        if (cover == null || mc.level == null) return 0;
        if (!mc.level.dimension().equals(dimension)) {
            // 换了维度：这一次作废
            cover = null;
            return 0;
        }
        float age = held >= 0 ? held : mc.level.getGameTime() - startTick + partialTick;
        if (age >= cover.total()) {
            if (held < 0) cover = null;
            return 0;
        }
        float a = cover.alpha(age);
        // 隐藏闪电的闪烁：盖上的一段改成先慢后快，盖满的时刻不变
        if (age < cover.in() && mc.options.hideLightningFlash().get()) {
            float k = age / cover.in();
            a = k * k * k;
        }
        return a;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    static void hud(RenderGuiEvent.Pre event) {
        var mc = Minecraft.getInstance();
        float a = alpha(mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
        if (a <= .002f || cover == null) return;
        var g = event.getGuiGraphics();
        int argb = (Math.round(a * 255) << 24) | (cover.rgb() & 0xFFFFFF);
        g.fill(0, 0, g.guiWidth(), g.guiHeight(), argb);
        // 盖满时界面也一起藏起来（快捷栏、准星不该浮在一片白上）
        if (a > .96f) event.setCanceled(true);
    }
}
