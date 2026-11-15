package dev.logos.dimension.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;

/** 白梅与女王共用的入场排版：疏字收拢、淡红晕、朱线和下方小字。 */
public final class BossTitle {
    public static final float IN = 14, OUT_AT = 52, GONE = 68;
    private static final int RED = 0xC8323C, PALE = 0xF4E6EC, PINK = 0xF4A6CF;

    private BossTitle() {}

    public static void draw(GuiGraphicsExtractor g, Font font, float age, Component name,
                            @Nullable Component epithet, Component subtitle) {
        if (age < 0 || age > GONE) return;
        float in = Mth.clamp(age / IN, 0, 1);
        float out = 1 - Mth.clamp((age - OUT_AT) / (GONE - OUT_AT), 0, 1);
        float eased = in * in * (3 - 2 * in), alpha = eased * out;
        if (alpha <= .01f) return;
        int w = g.guiWidth(), h = g.guiHeight();
        float cx = w / 2f, cy = h / 2f - h * .04f;
        var style = name.getStyle();
        Component[] chars = name.getString().codePoints()
                .mapToObj(cp -> (Component) Component.literal(new String(Character.toChars(cp))).setStyle(style))
                .toArray(Component[]::new);
        int[] widths = java.util.Arrays.stream(chars).mapToInt(font::width).toArray();
        boolean few = chars.length <= 3;
        float gap = Mth.lerp(eased, few ? 2.4f : .9f, few ? .55f : .12f) * font.lineHeight;
        float total = 0;
        for (int i = 0; i < widths.length; i++) total += widths[i] + (i > 0 ? gap : 0);
        float big = Math.min(Math.max(3.5f, h / 46f), w * .6f / Math.max(1, total));
        float x = cx - total * big / 2f;
        for (int i = 0; i < chars.length; i++) {
            glyph(g, font, chars[i], x, cy - font.lineHeight * big / 2f, big, alpha);
            x += (widths[i] + gap) * big;
        }
        if (epithet != null) {
            float small = big * .36f;
            text(g, font, epithet, cx - font.width(epithet) * small / 2f,
                    cy - font.lineHeight * big / 2f - font.lineHeight * small * 1.6f,
                    small, argb(alpha * .9f, PALE));
        }
        float line = total * big * .62f * in;
        float ly = cy + font.lineHeight * big / 2f + big * 1.6f;
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(cx - line / 2f, ly);
        pose.scale(Math.max(1, line), Math.max(1, big * .28f));
        g.fill(0, 0, 1, 1, argb(alpha * .85f, RED));
        pose.popMatrix();
        float tiny = big * .3f;
        text(g, font, subtitle, cx - font.width(subtitle) * tiny / 2f, ly + big * 1.2f,
                tiny, argb(alpha * .85f, PINK));
    }

    private static void glyph(GuiGraphicsExtractor g, Font font, Component ch,
                              float x, float y, float scale, float alpha) {
        float r = scale * .35f;
        for (int k = 0; k < 8; k++) {
            double angle = k * Math.PI / 4;
            text(g, font, ch, x + (float) Math.cos(angle) * r, y + (float) Math.sin(angle) * r,
                    scale, argb(alpha * .22f, RED));
        }
        text(g, font, ch, x, y, scale, argb(alpha, PALE));
    }

    private static void text(GuiGraphicsExtractor g, Font font, Component s,
                             float x, float y, float scale, int color) {
        if ((color >>> 24) < 4) return;
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(x, y);
        pose.scale(scale, scale);
        g.text(font, s, 0, 0, color, false);
        pose.popMatrix();
    }

    private static int argb(float alpha, int rgb) {
        return Mth.clamp(Math.round(alpha * 255), 0, 255) << 24 | rgb & 0xFFFFFF;
    }
}
