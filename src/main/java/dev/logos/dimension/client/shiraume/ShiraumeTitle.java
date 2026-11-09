package dev.logos.dimension.client.shiraume;

import dev.logos.dimension.LogosDimension;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * 开场标题（docs/SHIRAUME_BOSS.md 第 2 节）：镜头拉到玩家肩后时，画面正中出现「九尾妖狐」与大字「白梅」，
 * 中间一道细细的朱线，下面一行小字しらうめ。大字从疏到密地收拢、淡入，停两秒多，淡出。
 * 画在运镜的遮幅之上，运镜隐藏界面时照样画；标题出现时视角收窄一点，远处的她在画面里大一些。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class ShiraumeTitle {
    /** 淡入、开始淡出、完全消失（刻）。 */
    static final float IN = 14, OUT_AT = 52, GONE = 68;
    private static final int RED = 0xC8323C, PALE = 0xF4E6EC, PINK = 0xF4A6CF;

    /** 客户端场景用：停在第几刻上（小于 0 不停）。 */
    private static float held = -1;

    private ShiraumeTitle() {}

    /** 客户端场景用：让标题停在 {@code age} 刻的样子，传负数放开。 */
    public static void hold(float age) {
        held = age;
    }

    /** 运镜隐藏界面时会取消 {@code RenderGuiEvent.Pre}（之后的界面层都不画），所以收已取消的事件，排在遮幅之后画。 */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    static void hud(RenderGuiEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        float age = held >= 0 ? held : ShiraumeClient.titleAge(mc.getDeltaTracker().getGameTimeDeltaPartialTick(false));
        if (age < 0 || age > GONE) return;
        draw(event.getGuiGraphics(), mc.font, age);
    }

    static void draw(GuiGraphicsExtractor g, Font font, float age) {
        float in = Mth.clamp(age / IN, 0, 1), out = 1 - Mth.clamp((age - OUT_AT) / (GONE - OUT_AT), 0, 1);
        float alpha = in * in * (3 - 2 * in) * out;
        if (alpha <= .01f) return;
        int w = g.guiWidth(), h = g.guiHeight();
        float cx = w / 2f, cy = h / 2f - h * .04f;
        // 大字：两个字，从疏到密地收拢
        String name = Component.translatable("gui.logos_dimension.shiraume.title_name").getString();
        int[] widths = name.codePoints().map(cp -> font.width(new String(Character.toChars(cp)))).toArray();
        // 两个汉字时字距疏朗；字多（英文名）时收紧，整行不超过画面宽度的六成
        boolean few = widths.length <= 3;
        float gap = Mth.lerp(in * in * (3 - 2 * in), few ? 2.4f : .9f, few ? .55f : .12f) * font.lineHeight;
        float total = 0;
        for (int i = 0; i < widths.length; i++) total += widths[i] + (i > 0 ? gap : 0);
        float big = Math.min(Math.max(3.5f, h / 46f), w * .6f / Math.max(1, total));
        float x = cx - total * big / 2f;
        int i = 0;
        for (int cp : name.codePoints().toArray()) {
            String ch = new String(Character.toChars(cp));
            glyph(g, font, ch, x, cy - font.lineHeight * big / 2f, big, alpha);
            x += (widths[i++] + gap) * big;
        }
        // 上面的称号
        String epithet = Component.translatable("gui.logos_dimension.shiraume.title_epithet").getString();
        float small = big * .36f;
        text(g, font, epithet, cx - font.width(epithet) * small / 2f, cy - font.lineHeight * big / 2f - font.lineHeight * small * 1.6f, small,
                argb(alpha * .9f, PALE));
        // 朱线（从中间往两边长出来）与假名
        float line = total * big * .62f * in;
        float ly = cy + font.lineHeight * big / 2f + big * 1.6f;
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(cx - line / 2f, ly);
        pose.scale(Math.max(1, line), Math.max(1, big * .28f));
        g.fill(0, 0, 1, 1, argb(alpha * .85f, RED));
        pose.popMatrix();
        String kana = Component.translatable("gui.logos_dimension.shiraume.title_kana").getString();
        float tiny = big * .3f;
        text(g, font, kana, cx - font.width(kana) * tiny / 2f, ly + big * 1.2f, tiny, argb(alpha * .85f, PINK));
    }

    /** 一个大字：外面一圈暗红的晕（错开画几遍），中间亮白。 */
    private static void glyph(GuiGraphicsExtractor g, Font font, String ch, float x, float y, float scale, float alpha) {
        float r = scale * .35f;
        for (int k = 0; k < 8; k++) {
            double a = k * Math.PI / 4;
            text(g, font, ch, x + (float) Math.cos(a) * r, y + (float) Math.sin(a) * r, scale, argb(alpha * .22f, RED));
        }
        text(g, font, ch, x, y, scale, argb(alpha, PALE));
    }

    private static void text(GuiGraphicsExtractor g, Font font, String s, float x, float y, float scale, int argb) {
        if ((argb >>> 24) < 4) return;
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(x, y);
        pose.scale(scale, scale);
        g.text(font, s, 0, 0, argb, false);
        pose.popMatrix();
    }

    /** 标题出现时视角从原来的收到 {@link #TITLE_FOV} 度，淡出时放回去。 */
    static final float TITLE_FOV = 44;

    @SubscribeEvent
    static void fov(net.neoforged.neoforge.client.event.ViewportEvent.ComputeFov event) {
        float age = held >= 0 ? held : ShiraumeClient.titleAge((float) event.getPartialTick());
        if (age < 0 || age > GONE + 10) return;
        float in = Mth.clamp(age / IN, 0, 1), out = 1 - Mth.clamp((age - OUT_AT) / (GONE + 10 - OUT_AT), 0, 1);
        float k = in * in * (3 - 2 * in) * out;
        event.setFOV(Mth.lerp(k, event.getFOV(), Math.min(event.getFOV(), TITLE_FOV)));
    }

    private static int argb(float alpha, int rgb) {
        return Mth.clamp(Math.round(alpha * 255), 0, 255) << 24 | rgb & 0xFFFFFF;
    }
}
