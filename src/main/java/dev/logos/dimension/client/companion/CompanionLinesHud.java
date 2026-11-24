package dev.logos.dimension.client.companion;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.companion.CompanionLines;
import java.util.ArrayDeque;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import org.jspecify.annotations.Nullable;

/**
 * 角色台词的字幕：画面下方居中，说话人的名字用它自己的颜色（白梅淡粉、精灵公主嫩绿），一句话停一会儿后淡出。
 * 新的一句在旧的那句至少停了 {@link #MIN_SHOWN} 刻后顶掉它（战斗里两句挨得很近时不会一闪就没）；太长的折成几行。
 * 过场（{@code CameraShots}）会把界面整个藏起来，字幕仍要看得见，所以画在 {@code RenderGuiEvent.Pre} 里、被取消了也画；
 * 位置在状态栏上面，遮幅的下黑边之上。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class CompanionLinesHud {
    private static final int FADE = 8, MIN_SHOWN = 30, QUEUE = 2;
    private static CompanionLines.@Nullable Line line;
    private static final ArrayDeque<CompanionLines.Line> NEXT = new ArrayDeque<>();
    private static long shownAt;

    private CompanionLinesHud() {}

    @SubscribeEvent
    static void packets(RegisterClientPayloadHandlersEvent event) {
        event.register(CompanionLines.Line.TYPE, (payload, context) -> context.enqueueWork(() -> receive(payload)));
    }

    private static void receive(CompanionLines.Line payload) {
        var level = Minecraft.getInstance().level;
        long now = level == null ? 0 : level.getGameTime();
        if (line != null && now - shownAt < Math.min(MIN_SHOWN, line.ticks())) {
            if (NEXT.size() >= QUEUE) NEXT.removeFirst();
            NEXT.addLast(payload);
            return;
        }
        show(payload, now);
    }

    private static void show(CompanionLines.Line payload, long now) {
        line = payload;
        shownAt = now;
    }

    @SubscribeEvent
    static void tick(ClientTickEvent.Post event) {
        var level = Minecraft.getInstance().level;
        if (level == null || NEXT.isEmpty()) return;
        long now = level.getGameTime();
        if (line == null || now - shownAt >= Math.min(MIN_SHOWN, line.ticks())) show(NEXT.removeFirst(), now);
    }

    @SubscribeEvent
    static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        line = null;
        NEXT.clear();
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

    /** 名字的颜色：服务端给了就用；同伴的台词按键里的同伴 ID（{@code line.logos_dimension.<同伴>.……}）取她的主色。 */
    private static int color(CompanionLines.Line line) {
        if (line.color() != CompanionLines.STYLE_COLOR) return line.color();
        String[] parts = line.key().split("[.]");
        return CompanionStyle.of(parts.length > 2 ? parts[2] : "shiraume").accent();
    }

    private static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        var mc = Minecraft.getInstance();
        if (line == null || mc.level == null || mc.options.hideGui) return;
        float age = mc.level.getGameTime() - shownAt + delta.getGameTimeDeltaPartialTick(false);
        if (age > line.ticks()) return;
        float alpha = Mth.clamp(Math.min(age / 4f, (line.ticks() - age) / FADE), 0, 1);
        if (alpha <= .02f) return;
        Component name = Component.translatable(line.name());
        Component text = Component.translatable(line.key(), line.args().toArray());
        var font = mc.font;
        int w = g.guiWidth(), h = g.guiHeight();
        int nameW = font.width(name), gap = 6;
        int room = Math.max(80, Math.min(w - 40, 360) - nameW - gap);
        var rows = font.split(text, room);
        int textW = 0;
        for (FormattedCharSequence row : rows) textW = Math.max(textW, font.width(row));
        int total = nameW + gap + textW, lineH = font.lineHeight + 2;
        int x = (w - total) / 2, y = h - 72 - (rows.size() - 1) * lineH;
        int a = Math.round(alpha * 255);
        g.fill(x - 6, y - 4, x + total + 6, y + rows.size() * lineH + 2, (Math.round(alpha * 0x90) << 24) | 0x101014);
        g.text(font, name, x, y, (a << 24) | (color(line) & 0xFFFFFF));
        for (int i = 0; i < rows.size(); i++) g.text(font, rows.get(i), x + nameW + gap, y + i * lineH, (a << 24) | 0xF2EEE6);
    }
}
