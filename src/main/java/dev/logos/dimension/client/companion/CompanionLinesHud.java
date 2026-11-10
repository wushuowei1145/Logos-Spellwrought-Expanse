package dev.logos.dimension.client.companion;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.companion.CompanionLines;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import org.jspecify.annotations.Nullable;

/**
 * 同伴台词的字幕：画面下方居中，「白梅」的名字用淡粉色，一句话停一会儿后淡出。新的一句顶掉旧的。
 * 过场（{@code CameraShots}）会把界面整个藏起来，字幕仍要看得见，所以画在 {@code RenderGuiEvent.Pre} 里、被取消了也画；
 * 位置在状态栏上面，遮幅的下黑边之上。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class CompanionLinesHud {
    private static final int FADE = 8;
    private static CompanionLines.@Nullable Line line;
    private static long shownAt;

    private CompanionLinesHud() {}

    @SubscribeEvent
    static void packets(RegisterClientPayloadHandlersEvent event) {
        event.register(CompanionLines.Line.TYPE, (payload, context) -> context.enqueueWork(() -> {
            line = payload;
            var level = Minecraft.getInstance().level;
            shownAt = level == null ? 0 : level.getGameTime();
        }));
    }

    @SubscribeEvent
    static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        line = null;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    static void hud(RenderGuiEvent.Pre event) {
        render(event.getGuiGraphics(), event.getPartialTick());
    }

    /** 测试用：屏幕上现在是哪一句（没有为 null）。 */
    public static @Nullable String showing() {
        var mc = Minecraft.getInstance();
        if (line == null || mc.level == null || mc.level.getGameTime() - shownAt > line.ticks()) return null;
        return line.key();
    }

    private static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        var mc = Minecraft.getInstance();
        if (line == null || mc.level == null || mc.options.hideGui) return;
        float age = mc.level.getGameTime() - shownAt + delta.getGameTimeDeltaPartialTick(false);
        if (age > line.ticks()) return;
        float alpha = Mth.clamp(Math.min(age / 4f, (line.ticks() - age) / FADE), 0, 1);
        if (alpha <= .02f) return;
        Component name = Component.translatable("entity.logos_dimension.shiraume_companion");
        Component text = Component.translatable(line.key());
        var font = mc.font;
        int w = g.guiWidth(), h = g.guiHeight();
        int nameW = font.width(name), textW = font.width(text), gap = 6;
        int total = nameW + gap + textW;
        int x = (w - total) / 2, y = h - 72;
        int a = Math.round(alpha * 255);
        g.fill(x - 6, y - 4, x + total + 6, y + 12, (Math.round(alpha * 0x90) << 24) | 0x101014);
        g.text(font, name, x, y, (a << 24) | 0xF7B6D6);
        g.text(font, text, x + nameW + gap, y, (a << 24) | 0xF2EEE6);
    }
}
