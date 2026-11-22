package dev.logos.dimension.client.jiansui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.client.LogosSkyOverlay;
import dev.logos.dimension.client.vfx.DawnvowShake;
import dev.logos.dimension.jiansui.JianSuiBlocks;
import dev.logos.dimension.jiansui.JianSuiCue;
import dev.logos.dimension.jiansui.JianSuiFight;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

/**
 * 剑祟这一战的画面提示（{@link JianSuiCue}）：标题、巨剑贯落的光线与剑、插剑时镜头一抖、按世界坐标爬开的发光裂缝、
 * 贯穿古剑崩出去再落下。发光的几何都在 {@code SubmitCustomGeometryEvent} 里交给 Logos 的光影叠加层（实体渲染器里交的会被它清掉）。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class JianSuiCueClient {
    private static final Identifier GLOW = LogosDimension.id("textures/environment/jian_sui_fall_glow.png");
    /** 光缝、巨剑的淡青白。 */
    private static final int SEAM = 0xC8F2FA;
    private static final List<JianSuiCue> LIVE = new ArrayList<>();
    /** 还亮着的裂缝（单独放：要记最后一次收到的时刻，样子也只生成一次）。 */
    private static final List<Crack> CRACKS = new ArrayList<>();
    /** 这些提示属于哪个世界：{@code mc.level} 换了一个（换维度、换世界）就全部作废。 */
    private static @Nullable Level shown;
    private static long titleAt = Long.MIN_VALUE;
    /** 客户端场景用：标题停在第几刻（小于 0 不停）。 */
    private static float titleHeld = -1;

    private JianSuiCueClient() {}

    @SubscribeEvent
    static void payloads(RegisterClientPayloadHandlersEvent event) {
        event.register(JianSuiCue.TYPE, (cue, context) -> context.enqueueWork(() -> receive(cue)));
    }

    /** 收到一条提示（客户端场景也直接调它）。 */
    public static void receive(JianSuiCue cue) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        sync(mc.level);
        switch (cue.kind()) {
            case JianSuiCue.TITLE -> titleAt = cue.time();
            case JianSuiCue.IMPACT -> DawnvowShake.at(cue.from(), 1f);
            case JianSuiCue.CRACKS -> crack(cue, mc.level.getGameTime());
            // 终结里撬了一下：撬剑的样子、剑祟的反应都在 JianSuiFinisherClient
            case JianSuiCue.PRY_BEAT -> JianSuiFinisherClient.beat(cue);
            case JianSuiCue.FINISHER_END -> {
                // 崩出以后才截断的：飞出去的贯穿古剑也收掉（服务端把它放回剑祟身上了）
                JianSuiFinisherClient.end(cue);
                LIVE.removeIf(c -> c.kind() == JianSuiCue.AXIAL_BURST && c.from().distanceTo(cue.from()) < 4);
            }
            case JianSuiCue.REFLECT -> {
                // 被弹回去的那柄不再扎在碎块上；同一批里别的剑照常落下
                JianSuiCue parried = parried(cue);
                if (parried != null) LIVE.remove(parried);
                LIVE.add(cue);
            }
            default -> {
                LIVE.add(cue);
                if (LIVE.size() > 96) LIVE.removeFirst();
            }
        }
    }

    /**
     * 同一处的裂缝只留一份。同样大的是重发（服务端隔一阵发一次，后来的人、重连的人也收得到）：只记下这次收到的时刻，
     * 留着原来那份（时刻不变，不重新爬）；大小不同的换掉；value 不大于 0 时收起这一处。
     */
    private static void crack(JianSuiCue cue, long now) {
        for (Crack k : CRACKS) {
            if (cue.value() > 0 && k.cue.value() == cue.value() && k.cue.from().distanceToSqr(cue.from()) < .25) {
                k.seen = now;
                return;
            }
        }
        CRACKS.removeIf(k -> k.cue.from().distanceToSqr(cue.from()) < .25);
        if (cue.value() > 0) CRACKS.add(new Crack(cue, now));
    }

    /** 被弹反的那柄：落点和弹开的地方是同一个坐标；落点一样的几柄里，取正好在这一刻落到的那柄。 */
    private static @Nullable JianSuiCue parried(JianSuiCue reflect) {
        JianSuiCue best = null;
        long gap = Long.MAX_VALUE;
        for (JianSuiCue c : LIVE) {
            if (c.kind() != JianSuiCue.GIANT_SWORD || c.to().distanceToSqr(reflect.from()) >= 1e-6) continue;
            long g = Math.abs(c.time() + JianSuiFight.SWORD_AIM + JianSuiFight.SWORD_FLIGHT - reflect.time());
            if (g < gap) {
                best = c;
                gap = g;
            }
        }
        return best;
    }

    /** 客户端场景用：让标题停在 {@code age} 刻的样子，传负数放开。 */
    public static void holdTitle(float age) {
        titleHeld = age;
    }

    /** 客户端场景用：最新那一处大裂缝（石台上的）爬了多少刻；没有时是负数。 */
    static double crackAge() {
        double now = now(0);
        return CRACKS.stream().filter(k -> k.cue.value() > 4).mapToDouble(k -> now - k.cue.time()).min().orElse(-1);
    }

    /** 客户端场景用：现在画着几条这一种的提示；标题算一条（正在显示时）。 */
    static long count(int kind) {
        var level = Minecraft.getInstance().level;
        if (kind == JianSuiCue.TITLE) return level != null && titleAt != Long.MIN_VALUE && level.getGameTime() - titleAt <= GONE ? 1 : 0;
        if (kind == JianSuiCue.CRACKS) return CRACKS.size();
        return LIVE.stream().filter(c -> c.kind() == kind).count();
    }

    /** 客户端场景用：正在蓄光（还没射出）的巨剑有几柄。 */
    static long aiming() {
        return swords(Double.NEGATIVE_INFINITY, JianSuiFight.SWORD_AIM, false);
    }

    /** 客户端场景用：正在射下来的巨剑有几柄。 */
    static long flying() {
        return swords(JianSuiFight.SWORD_AIM, JianSuiFight.SWORD_AIM + JianSuiFight.SWORD_FLIGHT, false);
    }

    /** 客户端场景用：扎住、立在碎块上（还没散）的巨剑有几柄。 */
    static long stuck() {
        int landed = JianSuiFight.SWORD_AIM + JianSuiFight.SWORD_FLIGHT;
        return swords(landed, landed + STUCK, true);
    }

    /** 出手后 [{@code from}, {@code until}) 刻的巨剑有几柄；{@code stuck} 时只数扎住的。 */
    private static long swords(double from, double until, boolean stuck) {
        double now = now(0);
        return LIVE.stream().filter(c -> c.kind() == JianSuiCue.GIANT_SWORD && (!stuck || c.value() > .5f))
                .filter(c -> now - c.time() >= from && now - c.time() < until).count();
    }

    @SubscribeEvent
    static void tick(ClientTickEvent.Post event) {
        var level = Minecraft.getInstance().level;
        sync(level);
        // 隔两次重发都没收到的裂缝收掉：打完、重置时没收到「收起」的人，也不会一直亮着
        if (level != null) CRACKS.removeIf(k -> level.getGameTime() - k.seen > 2L * JianSuiFight.CRACK_REFRESH);
    }

    @SubscribeEvent
    static void loggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
        shown = null;
    }

    /** {@code mc.level} 换了一个（换维度、换世界）：这边记着的提示、裂缝全部作废。 */
    private static void sync(@Nullable Level level) {
        if (level == shown) return;
        shown = level;
        clear();
    }

    private static void clear() {
        LIVE.clear();
        CRACKS.clear();
        titleAt = Long.MIN_VALUE;
    }

    /** 客户端场景用：清掉上一个场景留下的提示（同一个维度里换场景时客户端世界不换，不会自己清）。 */
    static void reset() {
        clear();
    }

    private static double now(float partial) {
        var level = Minecraft.getInstance().level;
        // 先转成 double 再加：游戏刻大了以后 long + float 会丢掉零点几刻
        return level == null ? 0 : (double) level.getGameTime() + partial;
    }

    // ---------------------------------------------------------------- 标题（第 3.2 节：6.5 秒出现，7.5 秒淡出，8 秒开打时没了）

    /** 淡入、完全消失、开始淡出（刻，从标题出现算）：开打（{@link JianSuiFight#ENTRANCE_END}）那一刻正好没了，淡出半秒。 */
    static final float IN = 10, GONE = JianSuiFight.ENTRANCE_END - JianSuiFight.TITLE_AT, OUT_AT = GONE - 10;
    /** 骨白的字外面一圈墨色的晕：雾底场地亮，没有这圈晕时淡色的字在亮雾前看不清。 */
    private static final int AMBER = 0xFFB238, PALE = 0xF2EEE6, DIM = 0x14171B;

    /** 运镜隐藏界面时会取消 {@code RenderGuiEvent.Pre}，所以收已取消的事件，排在遮幅之后画（同白梅的标题）。 */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    static void title(RenderGuiEvent.Pre event) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        float age = titleHeld >= 0 ? titleHeld
                : titleAt == Long.MIN_VALUE ? -1 : (float) (mc.level.getGameTime() - titleAt) + mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        if (age < 0 || age > GONE) return;
        drawTitle(event.getGuiGraphics(), mc.font, age);
    }

    /**
     * 画面正中两个大字「剑祟」，从疏到密地收拢、淡入；外面一圈暗灰的晕，中间骨白。下面一道琥珀色的细线（眼睛的颜色）
     * 从中间往两边长出来，再下面一行小字「封印的恶念剑骸」。
     */
    static void drawTitle(GuiGraphicsExtractor g, Font font, float age) {
        float in = Mth.clamp(age / IN, 0, 1), out = 1 - Mth.clamp((age - OUT_AT) / (GONE - OUT_AT), 0, 1);
        float ease = in * in * (3 - 2 * in);
        float alpha = ease * out;
        if (alpha <= .01f) return;
        int w = g.guiWidth(), h = g.guiHeight();
        float cx = w / 2f, cy = h / 2f - h * .04f;
        String name = Component.translatable("gui.logos_dimension.jian_sui.title_name").getString();
        int[] codes = name.codePoints().toArray();
        int[] widths = new int[codes.length];
        for (int i = 0; i < codes.length; i++) widths[i] = font.width(new String(Character.toChars(codes[i])));
        boolean few = codes.length <= 3;
        float gap = Mth.lerp(ease, few ? 2.4f : .9f, few ? .55f : .12f) * font.lineHeight;
        float total = 0;
        for (int i = 0; i < widths.length; i++) total += widths[i] + (i > 0 ? gap : 0);
        float big = Math.min(Math.max(3.5f, h / 46f), w * .6f / Math.max(1, total));
        float x = cx - total * big / 2f;
        for (int i = 0; i < codes.length; i++) {
            String ch = new String(Character.toChars(codes[i]));
            glyph(g, font, ch, x, cy - font.lineHeight * big / 2f, big, alpha);
            x += (widths[i] + gap) * big;
        }
        float line = total * big * .62f * in;
        float ly = cy + font.lineHeight * big / 2f + big * 1.5f;
        var pose = g.pose();
        pose.pushMatrix();
        pose.translate(cx - line / 2f, ly);
        pose.scale(Math.max(1, line), Math.max(1, big * .26f));
        g.fill(0, 0, 1, 1, argb(alpha * .85f, AMBER));
        pose.popMatrix();
        String sub = Component.translatable("gui.logos_dimension.jian_sui.title_epithet").getString();
        float small = big * .32f;
        glyph(g, font, sub, cx - font.width(sub) * small / 2f, ly + big * 1.3f, small, alpha * .9f);
    }

    /** 一段字：外面错开画八遍墨色的晕，中间骨白。 */
    private static void glyph(GuiGraphicsExtractor g, Font font, String ch, float x, float y, float scale, float alpha) {
        float r = scale * .35f;
        for (int k = 0; k < 8; k++) {
            double a = k * Math.PI / 4;
            text(g, font, ch, x + (float) Math.cos(a) * r, y + (float) Math.sin(a) * r, scale, argb(alpha * .4f, DIM));
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

    private static int argb(float a, int rgb) {
        return Mth.clamp(Math.round(a * 255), 0, 255) << 24 | rgb & 0xFFFFFF;
    }

    // ---------------------------------------------------------------- 世界里的几何

    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void submit(SubmitCustomGeometryEvent event) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        sync(mc.level);
        if (LIVE.isEmpty() && CRACKS.isEmpty()) return;
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        double now = now(partial);
        Vec3 cam = event.getLevelRenderState().cameraRenderState.pos;
        SubmitNodeCollector glow = LogosSkyOverlay.collector(event.getSubmitNodeCollector());
        PoseStack pose = event.getPoseStack();
        LIVE.removeIf(c -> now - c.time() > life(c) || c.kind() == JianSuiCue.AXIAL_BURST && landed(mc.level, c, now));
        for (JianSuiCue c : LIVE) {
            double age = now - c.time();
            if (age < 0) continue;
            switch (c.kind()) {
                case JianSuiCue.GIANT_SWORD -> giantSword(pose, glow, c, age, cam);
                case JianSuiCue.AXIAL_BURST -> burst(pose, event.getSubmitNodeCollector(), glow, c, age, cam);
                case JianSuiCue.REFLECT -> reflect(pose, glow, c, age, cam);
                default -> { }
            }
        }
        for (Crack k : CRACKS) {
            double age = now - k.cue.time();
            if (age >= 0) cracks(pose, glow, mc.level, k, age, cam);
        }
    }

    private static double life(JianSuiCue c) {
        return switch (c.kind()) {
            case JianSuiCue.GIANT_SWORD -> JianSuiFight.SWORD_AIM + JianSuiFight.SWORD_FLIGHT + STUCK + 20;
            case JianSuiCue.AXIAL_BURST -> BURST_LAND + 60;
            case JianSuiCue.REFLECT -> JianSuiFight.REFLECT_FLIGHT + 14;
            default -> 40;
        };
    }

    // ---- 巨剑（第 10.3 节）

    /** 扎住以后立多久（刻），之后碎成光点散掉。 */
    static final float STUCK = 160;
    /** 巨剑渲染的缩放：手中古剑放大四倍（剑祟本身渲染 0.5，网格 1 单位 = 1/16 格）。 */
    static final float GIANT = 4 * .5f / 16;
    /** 网格里剑尖的高度（单位）。 */
    static final float HELD_TIP = 50;

    /**
     * 蓄光：从出手的地方到落点连出一道细光，光点沿线往剑祟那头聚，线越来越粗越来越亮（{@value JianSuiFight#SWORD_AIM} 刻）。
     * 射出：一柄放大四倍、淡青白半透明的手中古剑沿线射下来；扎住的斜立 8 秒再散掉，没扎住的穿过去掉下去、淡掉。
     * 扎住的剑尖扎进碎块里，剑格离碎块面一格高，和服务端那格看不见的剑格台阶对齐（{@link JianSuiFight#guardBack}）。
     */
    private static void giantSword(PoseStack pose, SubmitNodeCollector out, JianSuiCue c, double age, Vec3 cam) {
        Vec3 a = c.from(), b = c.to();
        double aim = JianSuiFight.SWORD_AIM, flight = JianSuiFight.SWORD_FLIGHT;
        Vec3 dir = b.subtract(a).normalize();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        if (age < aim) {
            float k = (float) (age / aim);
            float width = .03f + .2f * k * k;
            int alpha = Math.round(60 + 160 * k);
            // 光线停在落点前两格（被瞄准的人站在落点上，光线贴着镜头会糊成一大片），落点在地上画一圈越收越紧的光
            double length = b.distanceTo(a);
            Vec3 end = b.subtract(dir.scale(Math.min(2.0, length * .5)));
            out.submitCustomGeometry(pose, RenderTypes.beaconBeam(GLOW, true), (p, v) -> {
                beam(p, v, a, end, cam, width, alpha << 24 | SEAM);
                for (int i = 0; i < 7; i++) {
                    double f = (i / 7.0 - age / aim * 1.6) % 1;
                    if (f < 0) f += 1;
                    Vec3 at = end.add(a.subtract(end).scale(f));
                    beam(p, v, at, at.add(dir.scale(-.7)), cam, width * 2.4f, Math.round(alpha * .8f) << 24 | 0xFFFFFF);
                }
                ring(p, v, b.add(0, .04, 0), cam, 1.6f - .9f * k, .05f + .07f * k, Math.round(80 + 150 * k) << 24 | SEAM);
            });
        } else {
            double after = age - aim;
            boolean stuck = c.value() > .5f;
            double t = after / flight;
            // 扎住的：剑尖停在落点（碎块面）往里 SWORD_GUARD - guardBack 格；太平的服务端不放剑格，剑尖就停在落点上
            double buried = stuck && dir.y <= -.2 ? JianSuiFight.SWORD_GUARD - JianSuiFight.guardBack(dir) : 0;
            Vec3 rest = b.add(dir.scale(buried));
            Vec3 tip = stuck ? a.add(rest.subtract(a).scale(Math.min(1, t))) : a.add(b.subtract(a).scale(t));
            float fade;
            if (stuck) fade = after < flight + STUCK ? 1 : (float) Math.max(0, 1 - (after - flight - STUCK) / 20);
            else fade = t <= 1 ? 1 : (float) Math.max(0, 1 - (t - 1) / 2.5);
            if (fade > 0) {
                pose.pushPose();
                pose.translate(tip.x, tip.y, tip.z);
                pose.mulPose(new Quaternionf().rotationTo(0, 1, 0, (float) dir.x, (float) dir.y, (float) dir.z));
                pose.scale(GIANT, GIANT, GIANT);
                pose.translate(0, -HELD_TIP, 0);
                int alpha = Math.round(fade * (t < 1.3 ? 190 : 140));
                JianSuiSwords.submitGlow(pose, out, JianSuiSwords.HELD, alpha << 24 | SEAM);
                pose.popPose();
            }
            // 扎住的那一下，落点（碎块面上）一圈光；散掉时露在外面的那截剑身上冒一串光点
            if (stuck && after >= flight && after < flight + 10) {
                float k = (float) ((after - flight) / 10);
                int alpha = Math.round((1 - k) * 200);
                out.submitCustomGeometry(pose, RenderTypes.beaconBeam(GLOW, true), (p, v) -> ring(p, v, b.add(0, .05, 0), cam, .6f + 2.4f * k, .25f, alpha << 24 | SEAM));
            }
            if (stuck && after > flight + STUCK - 10 && fade > 0) {
                RandomSource rng = RandomSource.create(c.time());
                float k = (float) ((after - flight - STUCK + 10) / 30);
                double exposed = 6.5 - buried;
                out.submitCustomGeometry(pose, RenderTypes.beaconBeam(GLOW, true), (p, v) -> {
                    for (int i = 0; i < 24; i++) {
                        double f = rng.nextDouble() * exposed;
                        Vec3 at = b.subtract(dir.scale(f)).add((rng.nextFloat() - .5) * .6, k * (1 + rng.nextFloat() * 2), (rng.nextFloat() - .5) * .6);
                        float s = .07f + rng.nextFloat() * .08f;
                        beam(p, v, at.subtract(0, s, 0), at.add(0, s, 0), cam, s, Math.round(fade * 220) << 24 | 0xFFFFFF);
                    }
                });
            }
        }
        pose.popPose();
    }

    /** 被弹反的巨剑：剑尖朝着剑祟原路飞回去，打在它胸口碎成一圈光。 */
    private static void reflect(PoseStack pose, SubmitNodeCollector out, JianSuiCue c, double age, Vec3 cam) {
        Vec3 a = c.from(), b = c.to();
        Vec3 dir = b.subtract(a).normalize();
        double flight = JianSuiFight.REFLECT_FLIGHT;
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        if (age < flight) {
            Vec3 tip = a.add(b.subtract(a).scale(age / flight));
            pose.pushPose();
            pose.translate(tip.x, tip.y, tip.z);
            pose.mulPose(new Quaternionf().rotationTo(0, 1, 0, (float) dir.x, (float) dir.y, (float) dir.z));
            pose.scale(GIANT, GIANT, GIANT);
            pose.translate(0, -HELD_TIP, 0);
            JianSuiSwords.submitGlow(pose, out, JianSuiSwords.HELD, 190 << 24 | SEAM);
            pose.popPose();
        } else {
            float k = (float) Math.min(1, (age - flight) / 14);
            int alpha = Math.round((1 - k) * 230);
            out.submitCustomGeometry(pose, RenderTypes.beaconBeam(GLOW, true), (p, v) -> {
                ring(p, v, b, cam, .4f + 3.2f * k, .3f * (1 - k) + .05f, alpha << 24 | SEAM);
                for (int i = 0; i < 12; i++) {
                    double t = i * Math.PI * 2 / 12;
                    Vec3 o = new Vec3(Math.cos(t), (i % 3 - 1) * .5, Math.sin(t)).scale(1.2 + 2.8 * k);
                    beam(p, v, b.add(o.scale(.6)), b.add(o), cam, .06f, alpha << 24 | 0xFFFFFF);
                }
            });
        }
        pose.popPose();
    }

    /** 一条朝着镜头展开的光带；绕法也朝着镜头（这层剔除背面，反过来就看不见了）。 */
    private static void beam(PoseStack.Pose p, VertexConsumer v, Vec3 a, Vec3 b, Vec3 cam, float width, int color) {
        Vec3 along = b.subtract(a);
        Vec3 side = along.cross(cam.subtract(a));
        if (side.lengthSqr() < 1e-9) return;
        side = side.normalize().scale(width);
        quad(p, v, a.subtract(side), a.add(side), b.add(side), b.subtract(side), color);
    }

    /** 一圈平放的光环；绕法按镜头在它上面还是下面定，两面都看得见。 */
    private static void ring(PoseStack.Pose p, VertexConsumer v, Vec3 c, Vec3 cam, float radius, float width, int color) {
        boolean below = cam.y < c.y;
        int n = 32;
        for (int i = 0; i < n; i++) {
            double a0 = i * Math.PI * 2 / n, a1 = (i + 1) * Math.PI * 2 / n;
            Vec3 o0 = new Vec3(Math.cos(a0), 0, Math.sin(a0)), o1 = new Vec3(Math.cos(a1), 0, Math.sin(a1));
            Vec3 in0 = c.add(o0.scale(radius - width)), in1 = c.add(o1.scale(radius - width));
            Vec3 out0 = c.add(o0.scale(radius + width)), out1 = c.add(o1.scale(radius + width));
            if (below) quad(p, v, out0, out1, in1, in0, color);
            else quad(p, v, in0, in1, out1, out0, color);
        }
    }

    private static void quad(PoseStack.Pose p, VertexConsumer v, Vec3 a, Vec3 b, Vec3 c, Vec3 d, int color) {
        vertex(p, v, a, color);
        vertex(p, v, b, color);
        vertex(p, v, c, color);
        vertex(p, v, d, color);
    }

    private static void vertex(PoseStack.Pose p, VertexConsumer v, Vec3 at, int color) {
        v.addVertex(p, (float) at.x, (float) at.y, (float) at.z).setColor(color).setUv(.5f, .5f).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(p, 0, 1, 0);
    }

    // ---- 裂缝（第 9、10.4、14 节）

    /** 裂缝的一段：两头（还没贴地）、沿着它那一枝离中心多远（起、止，格）。 */
    private record Segment(Vec3 from, Vec3 to, float start, float end) {}

    /**
     * 一处裂缝。样子按满半径（value）只生成一次，种子由中心的格子定，所有人看到的一样；每段记着沿着自己那一枝离中心多远，
     * 爬的时候只画已经爬到的段。两头贴到地面上的位置也只找一次，碰到还没到的区块时，隔一会儿再找那几处。
     */
    private static final class Crack {
        final JianSuiCue cue;
        /** 最后一次收到（重发也算）时本机的游戏刻。 */
        long seen;
        final List<Segment> segments = new ArrayList<>();
        /** 每段两头贴地以后的位置（第 i 段是 2i、2i + 1）；null 是那一列的区块还没到。 */
        final Vec3[] ground;
        /** 到这一刻再去找没贴上地的点；都贴好了是 {@link Long#MAX_VALUE}。 */
        long retry = Long.MIN_VALUE;

        Crack(JianSuiCue cue, long seen) {
            this.cue = cue;
            this.seen = seen;
            Vec3 at = cue.from();
            RandomSource rng = RandomSource.create(BlockPos.containing(at).asLong());
            int roots = cue.value() < 4 ? 3 : 9;
            float step = cue.value() < 4 ? .35f : .8f;
            for (int i = 0; i < roots; i++) branch(rng, at, rng.nextFloat() * Mth.TWO_PI, 0, cue.value(), step, segments, 0);
            ground = new Vec3[segments.size() * 2];
        }

        void project(Level level) {
            long now = level.getGameTime();
            if (now < retry) return;
            boolean missing = false;
            for (int i = 0; i < ground.length; i++) {
                if (ground[i] != null) continue;
                Segment s = segments.get(i / 2);
                ground[i] = onGround(level, i % 2 == 0 ? s.from() : s.to());
                missing |= ground[i] == null;
            }
            retry = missing ? now + 10 : Long.MAX_VALUE;
        }
    }

    /**
     * 发光的裂缝：从中心往外爬、分叉，贴在地面上（每段取那一列最高的实心方块的顶面），2.5 秒爬到 value 格远，
     * 之后暗暗地亮着（给后来的人看路）。
     */
    private static void cracks(PoseStack pose, SubmitNodeCollector out, Level level, Crack k, double age, Vec3 cam) {
        JianSuiCue c = k.cue;
        if (c.from().distanceToSqr(cam) > 160 * 160) return;
        k.project(level);
        float grow = (float) Math.min(1, age / 50.0), radius = c.value() * grow;
        float bright = age < 50 ? 1 : Math.max(.4f, 1 - (float) (age - 50) / 80);
        float pulse = .85f + .15f * Mth.sin((float) age * .08f);
        float halfWidth = c.value() < 4 ? .05f : .09f;
        int color = Math.round(220 * bright * pulse) << 24 | SEAM;
        List<Segment> segments = k.segments;
        Vec3[] ground = k.ground;
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        out.submitCustomGeometry(pose, RenderTypes.beaconBeam(GLOW, true), (p, v) -> {
            for (int i = 0; i < segments.size(); i++) {
                Segment s = segments.get(i);
                Vec3 a = ground[2 * i], b = ground[2 * i + 1];
                if (s.start() >= radius || a == null || b == null || Math.abs(a.y - b.y) > 1.2) continue;
                Vec3 side = new Vec3(b.z - a.z, 0, a.x - b.x);
                if (side.lengthSqr() < 1e-6) continue;
                side = side.normalize().scale(halfWidth);
                // 正爬着的那一段按爬到的地方截短
                if (s.end() > radius) b = a.lerp(b, (radius - s.start()) / (s.end() - s.start()));
                quad(p, v, a.subtract(side), b.subtract(side), b.add(side), a.add(side), color);
            }
        });
        pose.popPose();
    }

    private static void branch(RandomSource rng, Vec3 from, float angle, float dist, float max, float step, List<Segment> out, int depth) {
        Vec3 p = from;
        float d = dist;
        // 只在收到时生成一次：上限放宽到九条根都长得出来（52 格的裂缝平均一千四百段）
        while (d < max && out.size() < 2500) {
            float s = step * (1 + rng.nextFloat() * .9f);
            angle += (rng.nextFloat() - .5f) * .7f;
            Vec3 next = p.add(Mth.cos(angle) * s, 0, Mth.sin(angle) * s);
            out.add(new Segment(p, next, d, d + s));
            p = next;
            d += s;
            if (depth < 2 && rng.nextFloat() < .12f) branch(rng, p, angle + (rng.nextBoolean() ? .9f : -.9f), d, max * (.6f + rng.nextFloat() * .3f), step, out, depth + 1);
        }
    }

    /**
     * 这一点所在那一列最高的实心方块顶面（从上面 3 格往下找 6 格）；找不到的贴着原来的高度。
     * 那一列的区块还没到时是 null（客户端世界的 {@code hasChunkAt} 总是真，要问区块缓存）。
     */
    private static @Nullable Vec3 onGround(Level level, Vec3 p) {
        BlockPos base = BlockPos.containing(p.x, p.y + 3, p.z);
        if (!level.getChunkSource().hasChunk(base.getX() >> 4, base.getZ() >> 4)) return null;
        for (int dy = 0; dy < 7; dy++) {
            BlockPos q = base.below(dy);
            var state = level.getBlockState(q);
            // 看不见的剑格台阶不算地面（8 秒就拆，裂缝的点算一次就一直用）
            if (state.is(JianSuiBlocks.SWORD_GUARD.get())) continue;
            var shape = state.getCollisionShape(level, q);
            if (!shape.isEmpty()) return new Vec3(p.x, q.getY() + shape.max(net.minecraft.core.Direction.Axis.Y) + .015, p.z);
        }
        return p;
    }

    // ---- 贯穿古剑崩出（第 11 节）

    /** 上飞、不见、下落（刻）；落下来的那一刻 = {@value #BURST_LAND}，和服务端放遗剑方块的那一刻对齐。 */
    static final double BURST_UP = 30, BURST_GONE = 20, BURST_FALL = 12, BURST_LAND = BURST_UP + BURST_GONE + BURST_FALL;

    /** 落下来以后看到遗剑方块已经在了，就交给方块画。 */
    private static boolean landed(Level level, JianSuiCue c, double now) {
        return now - c.time() >= BURST_LAND && level.getBlockState(BlockPos.containing(c.to().add(0, .1, 0))).is(JianSuiBlocks.AXIAL_SWORD.get());
    }

    /** 贯穿古剑崩出去：旋转着往上飞进雾里（拖着一点光），再落下来插在插剑的地方，摆法和遗剑方块一样。 */
    private static void burst(PoseStack pose, SubmitNodeCollector raw, SubmitNodeCollector glow, JianSuiCue c, double age, Vec3 cam) {
        Vec3 from = c.from(), to = c.to();
        pose.pushPose();
        if (age < BURST_UP) {
            double k = age / BURST_UP;
            Vec3 at = from.add(0, 26 * (1 - (1 - k) * (1 - k)), 0);
            pose.translate(at.x - cam.x, at.y - cam.y, at.z - cam.z);
            pose.mulPose(Axis.XP.rotationDegrees((float) (age * 38)));
            pose.scale(1f / 32, 1f / 32, 1f / 32);
            pose.translate(0, -20, 0);
            JianSuiSwords.submit(pose, raw, LightCoordsUtil.FULL_BRIGHT, JianSuiSwords.AXIAL, 0xFFFFFFFF);
            JianSuiSwords.submitGlow(pose, glow, JianSuiSwords.AXIAL, Math.round((float) (1 - k) * 160) << 24 | SEAM);
        } else if (age >= BURST_UP + BURST_GONE) {
            double k = Math.min(1, (age - BURST_UP - BURST_GONE) / BURST_FALL);
            Vec3 at = to.add(0, 20 * (1 - k * k), 0);
            pose.translate(at.x - cam.x, at.y - cam.y, at.z - cam.z);
            AxialSwordRenderer.place(pose);
            int light = LightCoordsUtil.FULL_BRIGHT;
            var level = Minecraft.getInstance().level;
            if (level != null) light = net.minecraft.client.renderer.LevelRenderer.getLightCoords(level, BlockPos.containing(to.add(0, .5, 0)));
            JianSuiSwords.submit(pose, raw, light, JianSuiSwords.AXIAL, 0xFFFFFFFF);
            JianSuiSwords.submitGlow(pose, glow, JianSuiSwords.AXIAL, 150 << 24 | SEAM);
        }
        pose.popPose();
    }
}
