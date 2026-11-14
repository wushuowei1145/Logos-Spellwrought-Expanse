package dev.logos.dimension.client.companion;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.util.Mth;

/**
 * 对话和角色面板共用的画法：都是一格一格填出来的（圆、梅鉢、狐火、菱形、光晕、渐隐的线），不用贴图，
 * 放大缩小都是清楚的像素边，和游戏的画风一致。
 */
public final class CompanionUi {
    private CompanionUi() {}

    /** 改颜色的不透明度（0–1，乘在原来的上面）。 */
    public static int alpha(int argb, float a) {
        int al = Math.round(((argb >>> 24) & 0xFF) * Mth.clamp(a, 0, 1));
        return (al << 24) | (argb & 0xFFFFFF);
    }

    /** 两种颜色之间插一个（含不透明度）。 */
    public static int mix(int a, int b, float t) {
        t = Mth.clamp(t, 0, 1);
        int r = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            int ca = (a >>> shift) & 0xFF, cb = (b >>> shift) & 0xFF;
            r |= Math.round(ca + (cb - ca) * t) << shift;
        }
        return r;
    }

    /** 几个圆的并集，一行一行填（重叠的地方不会更深）。{@code circles} 每三个数是一个圆：x、y、半径。 */
    public static void discs(GuiGraphicsExtractor g, int color, float... circles) {
        float top = Float.MAX_VALUE, bottom = -Float.MAX_VALUE;
        for (int i = 0; i < circles.length; i += 3) {
            top = Math.min(top, circles[i + 1] - circles[i + 2]);
            bottom = Math.max(bottom, circles[i + 1] + circles[i + 2]);
        }
        float[] lo = new float[circles.length / 3], hi = new float[circles.length / 3];
        for (int y = (int) Math.floor(top); y <= (int) Math.ceil(bottom); y++) {
            float cy = y + .5f;
            int n = 0;
            for (int i = 0; i < circles.length; i += 3) {
                float dy = cy - circles[i + 1], r = circles[i + 2];
                if (Math.abs(dy) > r) continue;
                float dx = (float) Math.sqrt(r * r - dy * dy);
                lo[n] = circles[i] - dx;
                hi[n] = circles[i] + dx;
                n++;
            }
            spans(g, y, lo, hi, n, color);
        }
    }

    /** 一行里几段 [lo, hi) 的并集，排好序合并后各填一次。 */
    private static void spans(GuiGraphicsExtractor g, int y, float[] lo, float[] hi, int n, int color) {
        if (n == 0) return;
        for (int i = 1; i < n; i++) {
            for (int j = i; j > 0 && lo[j] < lo[j - 1]; j--) {
                float t = lo[j]; lo[j] = lo[j - 1]; lo[j - 1] = t;
                t = hi[j]; hi[j] = hi[j - 1]; hi[j - 1] = t;
            }
        }
        float a = lo[0], b = hi[0];
        for (int i = 1; i <= n; i++) {
            if (i < n && lo[i] <= b) {
                b = Math.max(b, hi[i]);
                continue;
            }
            int x0 = Math.round(a), x1 = Math.round(b);
            if (x1 > x0) g.fill(x0, y, x1, y + 1, color);
            if (i < n) {
                a = lo[i];
                b = hi[i];
            }
        }
    }

    public static void disc(GuiGraphicsExtractor g, float cx, float cy, float r, int color) {
        discs(g, color, cx, cy, r);
    }

    /** 圆环（外半径 {@code r}，宽 {@code w}）。 */
    public static void ring(GuiGraphicsExtractor g, float cx, float cy, float r, float w, int color) {
        for (int y = (int) Math.floor(cy - r); y <= (int) Math.ceil(cy + r); y++) {
            float dy = y + .5f - cy;
            if (Math.abs(dy) > r) continue;
            float outer = (float) Math.sqrt(r * r - dy * dy);
            float ri = r - w;
            if (Math.abs(dy) >= ri) {
                g.fill(Math.round(cx - outer), y, Math.round(cx + outer), y + 1, color);
            } else {
                float inner = (float) Math.sqrt(ri * ri - dy * dy);
                g.fill(Math.round(cx - outer), y, Math.round(cx - inner), y + 1, color);
                g.fill(Math.round(cx + inner), y, Math.round(cx + outer), y + 1, color);
            }
        }
    }

    /** 椭圆（脚下的台子）。 */
    public static void ellipse(GuiGraphicsExtractor g, float cx, float cy, float rx, float ry, int color) {
        for (int y = (int) Math.floor(cy - ry); y <= (int) Math.ceil(cy + ry); y++) {
            float dy = (y + .5f - cy) / ry;
            if (Math.abs(dy) > 1) continue;
            float half = rx * (float) Math.sqrt(1 - dy * dy);
            g.fill(Math.round(cx - half), y, Math.round(cx + half), y + 1, color);
        }
    }

    /** 梅鉢：五片圆的花瓣绕着正中一个小圆（白梅的纹）。 */
    public static void umebachi(GuiGraphicsExtractor g, float cx, float cy, float r, int color) {
        float petal = r * .36f, dist = r * .62f;
        float[] c = new float[6 * 3];
        for (int i = 0; i < 5; i++) {
            double a = Math.toRadians(-90 + 72 * i);
            c[i * 3] = cx + (float) Math.cos(a) * dist;
            c[i * 3 + 1] = cy + (float) Math.sin(a) * dist;
            c[i * 3 + 2] = petal;
        }
        c[15] = cx;
        c[16] = cy;
        c[17] = r * .26f;
        discs(g, color, c);
    }

    /** 柔和的圆形光晕：一圈圈叠上去，越往里越亮。 */
    public static void glow(GuiGraphicsExtractor g, float cx, float cy, float r, int color, int steps) {
        for (int i = 0; i < steps; i++) disc(g, cx, cy, r * (1 - i / (float) steps), color);
    }

    /** 一小簇狐火（尾尖的灯）：下面圆、往上收成尖。{@code lit} 时亮着、白芯；不然只描一圈暗的。 */
    public static void flame(GuiGraphicsExtractor g, float cx, float cy, float s, int color, boolean lit) {
        float top = cy - s * 2.3f;
        for (int y = (int) Math.floor(top); y <= (int) Math.ceil(cy + s); y++) {
            float yc = y + .5f;
            float half;
            if (yc >= cy - s * .2f) {
                float dy = yc - cy;
                if (Math.abs(dy) > s) continue;
                half = (float) Math.sqrt(s * s - dy * dy);
            } else {
                float k = (yc - top) / (cy - s * .2f - top);
                if (k <= 0) continue;
                half = s * (float) Math.pow(k, .75) * .98f;
            }
            g.fill(Math.round(cx - half), y, Math.round(cx + half), y + 1, lit ? color : alpha(color, .28f));
        }
        if (lit) disc(g, cx, cy + s * .1f, s * .45f, 0xF0FFFFFF);
    }

    /** 菱形（选项前的小点）。 */
    public static void diamond(GuiGraphicsExtractor g, int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int w = r - Math.abs(dy);
            g.fill(cx - w, cy + dy, cx + w + 1, cy + dy + 1, color);
        }
    }

    /** 从 {@code x0} 往右渐隐的横线。 */
    public static void fadeLine(GuiGraphicsExtractor g, int x0, int x1, int y, int color) {
        int n = Math.max(1, (x1 - x0) / 4);
        for (int i = 0; i < n; i++) {
            int a = x0 + (x1 - x0) * i / n, b = x0 + (x1 - x0) * (i + 1) / n;
            g.fill(a, y, b, y + 1, alpha(color, 1 - i / (float) n));
        }
    }

    /** 进度条：底色、填满的那一段，两头各一格亮边。 */
    public static void bar(GuiGraphicsExtractor g, int x, int y, int w, int h, float frac, int fg, int bg) {
        g.fill(x, y, x + w, y + h, bg);
        int f = Math.round(w * Mth.clamp(frac, 0, 1));
        if (f > 0) {
            g.fill(x, y, x + f, y + h, fg);
            g.fill(x, y, x + f, y + 1, mix(fg, 0xFFFFFFFF, .35f));
        }
    }

    /** 能力的图标：一个圆，里面一个字（锁着时是灰的问号）。 */
    public static void glyph(GuiGraphicsExtractor g, Font font, int cx, int cy, float r, Component ch, int color, boolean locked) {
        disc(g, cx, cy, r, locked ? 0xFF3A3446 : color);
        ring(g, cx, cy, r, 1, locked ? 0xFF56506A : mix(color, 0xFFFFFFFF, .4f));
        Component c = locked ? Component.literal("?") : ch;
        int w = font.width(c);
        g.text(font, c, cx - w / 2, cy - 4, locked ? 0xFF8C84A0 : 0xFF1A1226, false);
    }

    /** 不该落在行首的标点（句号、逗号、右引号、省略号……）。 */
    static final String CLOSING = "。，、．！？：；」』）】〉》…—～·.,!?:;)]";

    /**
     * 按宽度排行。中日文逐字断（原版只在空格处断，「第 4 枚」会从空格那里断开），数字、英文单词、百分号不拆开；
     * 句号、逗号、右引号不落到行首，挂在上一行末尾（悬挂标点）。没有中日文的照原版按单词断。
     */
    public static List<String> wrap(Font font, Component text, int width) {
        String s = text.getString();
        if (s.codePoints().noneMatch(CompanionUi::cjk)) {
            List<String> out = new ArrayList<>();
            for (FormattedText t : font.getSplitter().splitLines(text, width, Style.EMPTY)) out.add(t.getString());
            return out;
        }
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        int w = 0;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i), j = i + Character.charCount(cp);
            if (word(cp)) while (j < s.length() && word(s.codePointAt(j))) j += Character.charCount(s.codePointAt(j));
            String unit = s.substring(i, j);
            i = j;
            if (cp == 10) {
                out.add(line.toString());
                line.setLength(0);
                w = 0;
                continue;
            }
            if (cp == ' ' && line.isEmpty()) continue;
            int uw = font.width(unit);
            boolean hang = unit.length() == 1 && CLOSING.indexOf(unit.charAt(0)) >= 0;
            if (w + uw > width && !line.isEmpty() && !hang) {
                out.add(line.toString().stripTrailing());
                line.setLength(0);
                w = 0;
                if (cp == ' ') continue;
            }
            line.append(unit);
            w += uw;
        }
        if (!line.isEmpty()) out.add(line.toString());
        return out;
    }

    /** 中日文（汉字、假名、全角标点）。 */
    static boolean cjk(int cp) {
        Character.UnicodeScript script = Character.UnicodeScript.of(cp);
        return script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA
                || cp >= 0x3000 && cp <= 0x303F || cp >= 0xFF00 && cp <= 0xFFEF;
    }

    /** 连在一起不拆开的：英文字母、数字和数字里的符号。 */
    private static boolean word(int cp) {
        return cp < 128 && (Character.isLetterOrDigit(cp) || cp == '%' || cp == '.' || cp == '/' || cp == '+' || cp == '-' || cp == 39);
    }

    /** 放大 {@code scale} 倍画字（左上角在 x、y）。 */
    public static void bigText(GuiGraphicsExtractor g, Font font, Component text, int x, int y, int color, float scale, boolean shadow) {
        g.pose().pushMatrix();
        g.pose().translate(x, y);
        g.pose().scale(scale, scale);
        g.text(font, text, 0, 0, color, shadow);
        g.pose().popMatrix();
    }
}
