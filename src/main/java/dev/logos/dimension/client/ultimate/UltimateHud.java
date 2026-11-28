package dev.logos.dimension.client.ultimate;

import dev.logos.dimension.client.companion.CompanionStyle;
import dev.logos.dimension.client.companion.CompanionUi;
import dev.logos.dimension.ultimate.UltimateChain;
import dev.logos.dimension.ultimate.UltimatePayloads;
import java.util.List;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * 侧边小队（规范 2.2，docs/ULTIMATES.md「侧边小队」）：画面左边一列，每位在场同伴一枚小圆（她的纹、她的主色），一个字都不写。
 * <ul>
 *   <li>好了：外圈亮着她的主色；冷却中：外圈压暗，盖一块随冷却转走的扇形；</li>
 *   <li>这一轮放过了：外圈换成暖金、发光；正在演：一圈一圈往外跳；</li>
 *   <li>接力窗口里：没放的那几位外面一圈暖金的弧，跟着窗口缩短；放过的连一条流动的金线到没放的；</li>
 *   <li>魔力不够：她那一圈红闪两下；没人能放：整列抖一下；</li>
 *   <li>汇流：屏幕底边一条参战同伴主色混成的流光，跟着汇流时间变淡。</li>
 * </ul>
 * 连携切入盖着画面的那一段，界面整个藏起来，这一列也跟着藏。
 */
public final class UltimateHud {
    static final int GOLD = 0xFFF2D58A, RED = 0xFFE85050;
    /** 小圆的半径、上下间距（界面像素）。 */
    static final float R = 9, GAP = 25;

    private UltimateHud() {}

    static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        var mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null || mc.level == null) return;
        UltimatePayloads.Status s = UltimateClient.status();
        List<UltimatePayloads.Member> members = s.members();
        if (members.isEmpty()) return;
        float partial = delta.getGameTimeDeltaPartialTick(false);
        float since = UltimateClient.since(partial);
        float time = mc.level.getGameTime() + partial;
        int n = members.size();
        float shake = UltimateClient.shake() > 0 ? (float) Math.sin(time * 2.6) * UltimateClient.shake() * .4f : 0;
        float x = 14 + R + shake;
        float top = g.guiHeight() / 2f - (n - 1) * GAP / 2f - 16;
        UltimateChain.Phase phase = s.chainPhase();
        boolean window = phase == UltimateChain.Phase.WINDOW;
        float windowLeft = window ? Math.clamp((s.window() - since) / UltimateChain.WINDOW, 0, 1) : 0;
        if (window) {
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    if (members.get(i).released() && !members.get(j).released()) thread(g, x + R + 1, top + i * GAP, top + j * GAP, time);
                }
            }
        }
        for (int i = 0; i < n; i++) member(g, members.get(i), x, top + i * GAP, since, time, window, windowLeft);
        if (phase == UltimateChain.Phase.CONVERGED) band(g, members, Math.clamp((s.converged() - since) / UltimateChain.CONVERGE, 0, 1), time);
    }

    private static void member(GuiGraphicsExtractor g, UltimatePayloads.Member m, float x, float y, float since, float time, boolean window, float windowLeft) {
        CompanionStyle st = CompanionStyle.of(m.id());
        float left = Math.max(0, m.left() - since);
        boolean cooling = left > 0;
        boolean ready = !cooling && !m.released() && !m.performing();
        CompanionUi.disc(g, x, y, R, CompanionUi.alpha(st.night0(), .82f));
        if (m.performing()) {
            float pulse = (time % 12) / 12;
            CompanionUi.ring(g, x, y, R + 2 + pulse * 6, 1.2f, CompanionUi.alpha(st.accent(), .7f * (1 - pulse)));
        }
        if (m.released()) {
            CompanionUi.glow(g, x, y, R + 4, CompanionUi.alpha(GOLD, .12f), 4);
            CompanionUi.ring(g, x, y, R, 1.6f, GOLD);
        } else if (ready) {
            CompanionUi.glow(g, x, y, R + 3, CompanionUi.alpha(st.accent(), .08f + .04f * (float) Math.sin(time * .2)), 3);
            CompanionUi.ring(g, x, y, R, 1.6f, st.accent());
        } else {
            CompanionUi.ring(g, x, y, R, 1.2f, CompanionUi.alpha(st.accent(), .4f));
        }
        st.crest().draw(g, x, y, R * .62f, cooling ? CompanionUi.alpha(st.accent(), .45f) : m.released() ? GOLD : st.accent());
        if (cooling && m.cooldown() > 0) pie(g, x, y, R - 1, left / m.cooldown(), 0x99000000);
        if (window && !m.released()) arc(g, x, y, R + 3.5f, 1.4f, -Math.PI / 2, windowLeft * Math.PI * 2, GOLD);
        int flash = UltimateClient.flash(m.id());
        if (flash > 0) CompanionUi.ring(g, x, y, R + 1.5f, 2, CompanionUi.alpha(RED, (flash / 5) % 2 == 0 ? .9f : .2f));
    }

    /** 放过的连到没放的：往右鼓的一条金线，亮点顺着线往下一位流。 */
    private static void thread(GuiGraphicsExtractor g, float x, float y0, float y1, float time) {
        float bulge = 6 + Math.abs(y1 - y0) * .15f;
        int steps = Math.max(6, (int) (Math.abs(y1 - y0) / 1.5f));
        for (int k = 0; k <= steps; k++) {
            float t = k / (float) steps;
            float px = x + 4 * t * (1 - t) * bulge, py = y0 + (y1 - y0) * t;
            float glow = .3f + .7f * (.5f + .5f * (float) Math.sin(t * 12 - time * .6f));
            g.fill(Math.round(px), Math.round(py), Math.round(px) + 1, Math.round(py) + 1, CompanionUi.alpha(GOLD, glow));
        }
    }

    /**
     * 汇流：屏幕底边一条参战同伴主色混成的流光。最底下一道是她们的深主色（白天、雪地这种亮底上也看得见），
     * 上面一层主色，再上面一层淡淡的光晕；亮的地方顺着屏幕流。
     */
    private static void band(GuiGraphicsExtractor g, List<UltimatePayloads.Member> members, float left, float time) {
        int w = g.guiWidth(), h = g.guiHeight(), n = members.size();
        for (int x = 0; x < w; x += 3) {
            float u = (x / (float) w * n + time * .05f) % n;
            CompanionStyle a = CompanionStyle.of(members.get((int) u % n).id()), b = CompanionStyle.of(members.get(((int) u + 1) % n).id());
            float t = u - (int) u;
            int deep = CompanionUi.mix(a.accentDeep(), b.accentDeep(), t), color = CompanionUi.mix(a.accent(), b.accent(), t);
            float shimmer = .65f + .35f * (float) Math.sin(x * .07f - time * .5f);
            g.fill(x, h - 2, x + 3, h, CompanionUi.alpha(deep, .85f * left));
            g.fill(x, h - 4, x + 3, h - 2, CompanionUi.alpha(color, .85f * left * shimmer));
            g.fill(x, h - 10, x + 3, h - 4, CompanionUi.alpha(color, .22f * left * shimmer));
        }
    }

    /** 从正上方顺时针盖住 {@code frac} 的扇形（冷却还剩几成）。 */
    static void pie(GuiGraphicsExtractor g, float cx, float cy, float r, float frac, int color) {
        if (frac <= 0) return;
        if (frac >= 1) {
            CompanionUi.disc(g, cx, cy, r, color);
            return;
        }
        double sweep = frac * Math.PI * 2;
        for (int y = (int) Math.floor(cy - r); y <= (int) Math.ceil(cy + r); y++) {
            float dy = y + .5f - cy;
            int run = Integer.MIN_VALUE;
            for (int x = (int) Math.floor(cx - r); x <= (int) Math.ceil(cx + r) + 1; x++) {
                float dx = x + .5f - cx;
                double a = Math.atan2(dx, -dy);
                if (a < 0) a += Math.PI * 2;
                boolean in = dx * dx + dy * dy <= r * r && a <= sweep;
                if (in && run == Integer.MIN_VALUE) run = x;
                else if (!in && run != Integer.MIN_VALUE) {
                    g.fill(run, y, x, y + 1, color);
                    run = Integer.MIN_VALUE;
                }
            }
        }
    }

    /** 圆弧：从 {@code start}（弧度，0 是正右、往下转）转过 {@code sweep}。颜色要不透明，小方块叠在一起才不会深一块浅一块。 */
    static void arc(GuiGraphicsExtractor g, float cx, float cy, float r, float w, double start, double sweep, int color) {
        if (sweep <= 0) return;
        int steps = Math.max(8, (int) Math.ceil(sweep * r * 1.5));
        float half = w / 2;
        for (int i = 0; i <= steps; i++) {
            double a = start + sweep * i / steps;
            float px = cx + (float) Math.cos(a) * (r - half), py = cy + (float) Math.sin(a) * (r - half);
            int x0 = Math.round(px - half), y0 = Math.round(py - half);
            g.fill(x0, y0, Math.max(x0 + 1, Math.round(px + half)), Math.max(y0 + 1, Math.round(py + half)), color);
        }
    }
}
