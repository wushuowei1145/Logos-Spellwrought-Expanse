package dev.logos.dimension.client.companion;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.logos.client.ShaderPackOverlay;
import dev.logos.client.VfxParts;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.client.foxfire.FoxfireClient;
import dev.logos.dimension.client.screenfx.GlowBloom;
import dev.logos.dimension.client.screenfx.PostVfx;
import dev.logos.dimension.companion.LacertaUltimate;
import dev.logos.dimension.foxfire.FoxfireFlame;
import dev.logos.dimension.ultimate.UltimatePayloads;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import org.joml.Quaternionf;
import org.jspecify.annotations.Nullable;

/**
 * 拉塞塔奥义在世界里的特效（docs/LACERTA_COMPANION.md 第 22 节），看得见她的人都画（不只下令的那位）：
 * <ul>
 *   <li>冲阵：脚下拖出的熔金火星，身前一圈白色音爆环；</li>
 *   <li>横扫：绕她 270° 的白芯赤金涂抹刀光；挑空：一道斜着撩上天的赤金弧；</li>
 *   <li>登穹到坠下：举过头顶的剑暴涨出三倍长的炽白赤金龙炎巨刃，坠下时脚下一道炽白气锥；</li>
 *   <li>裂地：地面以落点为心崩开蛛网似的炽白光缝（慢慢冷成暗红）、一圈冲击环、一根冲天的炽白光柱，
 *       四五十块焦黑碎岩（地面方块自己的贴图，压成焦黑）往四周和高空崩飞、悬在半空，最后散掉；</li>
 *   <li>余下：裂缝里往上蹿的火星、慢慢升起的焦烟。</li>
 * </ul>
 * 碎岩是实心的方块几何，在世界里画（挡得住后面的光）；发光的部分在 {@link PostVfx} 收的时候交给它（不跟着雾和光影包走），
 * 不收的时候照旧在世界里画；亮的地方再交一份给 {@link GlowBloom} 泛光。全是客户端的几何，不碰任何方块。
 * 时间按客户端世界的游戏刻走（和服务端脚本、过场同一张表 {@link LacertaUltimate.Beats}），放完淡掉。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class LacertaUltimateFx {
    static final int CORAL = 0xE0566B, GOLD = 0xFF9B3D, AMBER = 0xF2B544, WHITE = 0xFFF8EE, PALE = 0xFFE7C0, EMBER_RED = 0x8A1A10,
            CHAR = 0x3B302D, HOT = 0xFF8A50, SMOKE = 0x2A2224, FLAME = 0xEE7A30;
    /** 放完以后再留几刻淡掉（碎岩缩没、裂缝凉透）。 */
    static final int FADE = 12;
    /** 光柱亮几刻：完整版到第 7 镜结束，缩短版到擦镜。 */
    static final int PILLAR_FULL = 28, PILLAR_SHORT = 16;
    /** 光柱多高、最粗多粗（格）。 */
    static final float PILLAR_HEIGHT = 18, PILLAR_RADIUS = 1.1f;
    /** 碎岩多少块（规范 40–60）：{@link #ROCKS_MIN} 起，按种子再多几块。 */
    static final int ROCKS_MIN = 44, ROCKS_SPREAD = 13;
    /** 碎岩不往这一边崩（左右各多少度）：裂地和拉远的镜头在她身后偏右（{@link #lane}），碎岩扑到镜头上会糊满一屏。 */
    static final float LANE = 40;
    /** 剑和龙炎巨刃：举过头顶时手在多高；巨刃最长多长。 */
    static final float HANDS = 1.75f, BLADE = 5.2f;

    private static final List<LacertaUltimateFx> ACTIVE = new ArrayList<>();
    /** 测试用：一共崩飞过多少块碎岩、开过几道裂缝。 */
    private static int rocksSpawned, cracksSpawned;

    private record Rock(Vec3 from, Vec3 vel, float size, Quaternionf turn, Vec3 spin, TextureAtlasSprite sprite) {
        /** 崩飞以后 {@code a} 刻在哪：往上冲、越来越慢、在半空悬一会儿，再慢慢落下来（不落到地下）。 */
        Vec3 at(float a) {
            float h = (1 - (float) Math.exp(-a * .09)) / .09f, v = (1 - (float) Math.exp(-a * .11)) / .11f;
            double y = Math.max(0, vel.y * v - .0035 * a * a);
            return from.add(vel.x * h, y, vel.z * h);
        }
    }

    private record Crack(List<Vec3> points, float width) {}

    private record Puff(float born, float life, Vec3 from, Vec3 vel, float size, float roll, int frame) {}

    final UltimatePayloads.Cast cast;
    final LacertaUltimate.Beats beats;
    private final Vec3 center, dir, right, home;
    private final long born;
    private final RandomSource random;
    private final List<Rock> rocks = new ArrayList<>();
    private final List<Crack> cracks = new ArrayList<>();
    private final List<Puff> smoke = new ArrayList<>();
    private int lastTick = -1;
    private boolean ending;

    LacertaUltimateFx(UltimatePayloads.Cast cast, LacertaUltimate.Beats beats) {
        this.cast = cast;
        this.beats = beats;
        this.random = RandomSource.create(cast.seed());
        ClientLevel level = Minecraft.getInstance().level;
        this.born = level == null ? 0 : level.getGameTime();
        // 和服务端脚本一样：目标脚下（飞在天上的砸它下面的地）、从她指向落点
        this.center = level == null ? cast.aim() : ground(level, cast.aim(), 24);
        Entity e = level == null ? null : level.getEntity(cast.performer());
        this.home = e == null ? cast.aim() : e.position();
        Vec3 flat = center.subtract(home).multiply(1, 0, 1);
        if (flat.lengthSqr() > 1e-3) {
            this.dir = flat.normalize();
        } else {
            float yaw = e == null ? 0 : e.getYRot() * Mth.DEG_TO_RAD;
            this.dir = new Vec3(-Mth.sin(yaw), 0, Mth.cos(yaw));
        }
        this.right = new Vec3(-dir.z, 0, dir.x);
    }

    Vec3 center() {
        return center;
    }

    Vec3 dir() {
        return dir;
    }

    Vec3 right() {
        return right;
    }

    Vec3 home() {
        return home;
    }

    static void add(LacertaUltimateFx fx) {
        ACTIVE.add(fx);
    }

    static void remove(LacertaUltimateFx fx) {
        ACTIVE.remove(fx);
    }

    /** 从落点看，裂地和拉远的镜头在哪一边（她身后偏右，同 {@code LacertaUltimateShow} 的机位）。 */
    Vec3 lane() {
        return dir.scale(-.86).add(right.scale(.5)).normalize();
    }

    /** 演完了：再留 {@link #FADE} 刻淡掉。 */
    void fadeOut() {
        ending = true;
    }

    /** 测试用。 */
    public static int active() {
        return ACTIVE.size();
    }

    public static int rocksSpawned() {
        return rocksSpawned;
    }

    public static int cracksSpawned() {
        return cracksSpawned;
    }

    /** 这一次崩飞了几块碎岩（测试用）。 */
    public int rocks() {
        return rocks.size();
    }

    private float age(float partial) {
        ClientLevel level = Minecraft.getInstance().level;
        return level == null ? 0 : level.getGameTime() - born + partial;
    }

    private @Nullable Entity performer() {
        ClientLevel level = Minecraft.getInstance().level;
        return level == null ? null : level.getEntity(cast.performer());
    }

    // ---------------------------------------------------------------- 每刻：火星、碎岩、烟

    @SubscribeEvent
    static void tickAll(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            ACTIVE.clear();
            return;
        }
        if (mc.isPaused()) return;
        for (LacertaUltimateFx fx : List.copyOf(ACTIVE)) {
            int now = (int) fx.age(0);
            while (fx.lastTick < now) fx.step(mc.level, ++fx.lastTick);
            // 演完再淡 FADE 刻；万一没收到「演完」，最多多留两秒
            if (fx.ending && now > fx.cast.length() + FADE || now > fx.cast.length() + 40) ACTIVE.remove(fx);
        }
    }

    @SubscribeEvent
    static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        ACTIVE.clear();
    }

    /** 第 {@code t} 刻（每刻一次）。 */
    private void step(ClientLevel level, int t) {
        LacertaUltimate.Beats b = beats;
        Entity e = performer();
        Vec3 at = e == null ? null : e.position();
        if (at != null && !b.condensed() && t < b.dash()) {
            // 冲阵：剑尖拖在身后擦出一路火星
            for (int i = 0; i < 4; i++) {
                Vec3 p = at.subtract(dir.scale(.6 + random.nextDouble() * .8)).add(right.scale(-.35 + random.nextDouble() * .2)).add(0, .08, 0);
                Vec3 v = dir.scale(-.12 - random.nextDouble() * .1).add(0, .06 + random.nextDouble() * .12, 0).add(spread(.06));
                FoxfireClient.ember(FoxfireFlame.DRAGON, true, p, v, 8 + random.nextInt(6), .05f + random.nextFloat() * .04f);
            }
            FoxfireClient.jet(FoxfireFlame.DRAGON, at.subtract(dir.scale(.9)).add(0, .15, 0), dir.scale(-.35), 5, .35f);
        }
        if (at != null && t >= b.rise() + 2 && t < b.dive()) {
            // 蓄势：火星往举起的剑上聚
            Vec3 tip = at.add(0, HANDS + 1.5, 0);
            Vec3 off = spread(1).normalize().scale(1.4 + random.nextDouble());
            FoxfireClient.ember(FoxfireFlame.DRAGON, true, tip.add(off), off.scale(-1 / 7d), 7, .06f);
        }
        if (at != null && t >= b.dive() && t < b.impact()) {
            // 坠下：裹着一身龙炎，火舌往上甩在身后
            for (int i = 0; i < 4; i++) {
                Vec3 p = at.add(spread(.35)).add(0, random.nextDouble() * 1.4, 0);
                FoxfireClient.jet(FoxfireFlame.DRAGON, p, new Vec3(0, .55 + random.nextDouble() * .3, 0).add(spread(.05)), 4 + random.nextInt(3),
                        .35f + random.nextFloat() * .2f);
            }
        }
        if (t == b.impact()) burst(level);
        if (t > b.impact() && t < b.length()) {
            // 裂缝里往上蹿的火星，坑里慢慢升起的焦烟
            if (!cracks.isEmpty()) {
                Crack c = cracks.get(random.nextInt(cracks.size()));
                Vec3 p = c.points.get(1 + random.nextInt(c.points.size() - 1));
                FoxfireClient.ember(FoxfireFlame.DRAGON, true, p.add(spread(.2)), new Vec3(0, .08 + random.nextDouble() * .1, 0).add(spread(.03)),
                        14 + random.nextInt(10), .05f + random.nextFloat() * .04f);
            }
            if (t > b.impact() + 4 && smoke.size() < 60 && random.nextFloat() < .8f) {
                float a = random.nextFloat() * Mth.TWO_PI, d = 3.5f * random.nextFloat();
                Vec3 p = center.add(Mth.cos(a) * d, .4, Mth.sin(a) * d);
                Vec3 v = new Vec3(Mth.cos(a) * .03, .035 + random.nextDouble() * .04, Mth.sin(a) * .03);
                smoke.add(new Puff(t, 26 + random.nextInt(18), p, v, 1.4f + random.nextFloat() * 1.4f, random.nextFloat() * 6.3f, random.nextInt(4)));
            }
        }
        smoke.removeIf(p -> t - p.born > p.life);
    }

    /** 裂地那一下：碎岩崩飞、地面裂开、火星炸开。 */
    private void burst(ClientLevel level) {
        int count = ROCKS_MIN + random.nextInt(ROCKS_SPREAD);
        Vec3 lane = lane();
        float laneAt = (float) Mth.atan2(lane.z, lane.x), half = LANE * Mth.DEG_TO_RAD;
        for (int i = 0; i < count; i++) {
            // 绕开镜头那一边：从那一道缺口的边上起，在剩下的一圈里随便挑
            float a = laneAt + half + random.nextFloat() * (Mth.TWO_PI - 2 * half);
            float d = .8f + 4.2f * (float) Math.sqrt(random.nextFloat());
            double x = center.x + Mth.cos(a) * d, z = center.z + Mth.sin(a) * d;
            Vec3 foot = ground(level, new Vec3(x, center.y, z), 4);
            TextureAtlasSprite sprite = VfxParts.blockSprite(level, x, foot.y - .5, z);
            if (sprite == null) continue;
            float heavy = random.nextFloat();
            float size = .15f + .7f * heavy * heavy;
            Vec3 out = new Vec3(Mth.cos(a), 0, Mth.sin(a));
            double up = (.6 + .5 * random.nextDouble()) * (1.1 - .45 * heavy);
            double spreadOut = (.12 + .35 * random.nextDouble()) * (1.2 - d / 6);
            Vec3 vel = out.scale(spreadOut).add(0, up, 0);
            Quaternionf turn = new Quaternionf().rotateXYZ(random.nextFloat() * 6.3f, random.nextFloat() * 6.3f, random.nextFloat() * 6.3f);
            Vec3 spin = spread(.5).scale(1 - .5 * heavy);
            rocks.add(new Rock(foot.add(0, size * .5, 0), vel, size, turn, spin, sprite));
        }
        rocksSpawned += rocks.size();
        int n = 9 + random.nextInt(3);
        for (int i = 0; i < n; i++) {
            float a = (i + random.nextFloat() * .6f) / n * Mth.TWO_PI;
            float length = 3.5f + 3.5f * random.nextFloat();
            List<Vec3> pts = new ArrayList<>();
            Vec3 p = center;
            pts.add(ground(level, p, 2).add(0, .04, 0));
            int segments = 5;
            for (int k = 0; k < segments; k++) {
                a += (random.nextFloat() - .5f) * .9f;
                p = p.add(Mth.cos(a) * length / segments, 0, Mth.sin(a) * length / segments);
                pts.add(ground(level, p, 2).add(0, .04, 0));
            }
            cracks.add(new Crack(pts, .16f + .1f * random.nextFloat()));
        }
        cracksSpawned += cracks.size();
        for (int i = 0; i < 46; i++) {
            Vec3 v = spread(1).multiply(1, 0, 1).normalize().scale(.15 + random.nextDouble() * .35).add(0, .25 + random.nextDouble() * .45, 0);
            FoxfireClient.ember(FoxfireFlame.DRAGON, true, center.add(0, .3, 0), v, 16 + random.nextInt(12), .06f + random.nextFloat() * .05f);
        }
        for (int i = 0; i < 10; i++) {
            Vec3 v = spread(.2).add(0, .45 + random.nextDouble() * .3, 0);
            FoxfireClient.jet(FoxfireFlame.DRAGON, center.add(spread(.8)).add(0, .2, 0), v, 6 + random.nextInt(4), .6f + random.nextFloat() * .4f);
        }
    }

    // ---------------------------------------------------------------- 每帧：交几何

    /** 在 Logos 之后提交（Logos 在这个事件里先 begin ShaderPackOverlay）。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void submitAll(SubmitCustomGeometryEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || ACTIVE.isEmpty()) return;
        CameraRenderState camera = event.getLevelRenderState().cameraRenderState;
        float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        PoseStack pose = event.getPoseStack();
        SubmitNodeCollector raw = event.getSubmitNodeCollector(), out = ShaderPackOverlay.collector(raw);
        var layers = VfxParts.layered(raw, out);
        VfxParts.View view = VfxParts.View.of(camera);
        pose.pushPose();
        pose.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
        for (LacertaUltimateFx fx : List.copyOf(ACTIVE)) fx.submit(view, pt, pose, out, layers);
        pose.popPose();
    }

    private void submit(VfxParts.View view, float pt, PoseStack pose, SubmitNodeCollector out, IntFunction<OrderedSubmitNodeCollector> layers) {
        float a = age(pt);
        LacertaUltimate.Beats b = beats;
        Entity e = performer();
        Vec3 at = e == null ? null : e.getPosition(pt);
        // 收尾：整体淡掉
        float fade = 1 - VfxParts.smooth(b.length(), b.length() + FADE, a);
        if (fade <= 0) return;
        if (at != null && !b.condensed()) {
            sonicRing(view, pose, out, at, a);
            sweepSmear(view, pose, out, at, a);
            ascendArc(view, pose, out, at, a);
        }
        if (at != null) {
            blade(view, pose, out, at, a);
            diveCone(view, pose, out, at, a);
        }
        if (a >= b.impact()) {
            float k = a - b.impact();
            shockRing(pose, out, k);
            cracks(view, pose, out, k, fade);
            pillar(view, pose, out, k);
            rocks(view, pose, layers, k, fade);
            smoke(view, pose, layers, a, fade);
        }
    }

    /** 冲阵：身前一圈白色音爆环，往外扩开淡掉。 */
    private void sonicRing(VfxParts.View view, PoseStack pose, SubmitNodeCollector out, Vec3 at, float a) {
        float k = (a - 1) / 6;
        if (k < 0 || k > 1) return;
        Vec3 c = at.add(0, .8, 0).add(dir.scale(1.2 + 1.5 * k));
        float r = .5f + 2.2f * (1 - (1 - k) * (1 - k));
        int color = VfxParts.argb((1 - k) * .85f, WHITE);
        glow(pose, out, "heal/ring", (p, buf) -> VfxParts.flat(p, buf, c, right, new Vec3(0, 1, 0), r, 0, color));
    }

    /** 横扫：绕她 270° 的涂抹刀光（白芯、赤金的边），砍完淡掉。 */
    private void sweepSmear(VfxParts.View view, PoseStack pose, SubmitNodeCollector out, Vec3 at, float a) {
        LacertaUltimate.Beats b = beats;
        float from = b.sweep() + 3, hit = b.sweepHit() + .5f, gone = b.sweepHit() + 4;
        if (a < from || a > gone) return;
        float p = VfxParts.smooth(from, hit, a);
        float alpha = 1 - VfxParts.smooth(hit, gone, a);
        float head = 135 - 270 * p, tail = Math.min(135, head + 40 + 180 * p);
        Vec3 c = at.add(0, .95, 0);
        arc(view, pose, out, alpha, (s) -> LacertaUltimateShow.ring(c, dir, right, Mth.lerp(s, tail, head), 2.5).add(0, .12 * Mth.sin(s * 3.1f), 0),
                .95f, .38f);
    }

    /** 挑空：从右前下方斜撩上天的一道赤金弧。 */
    private void ascendArc(VfxParts.View view, PoseStack pose, SubmitNodeCollector out, Vec3 at, float a) {
        LacertaUltimate.Beats b = beats;
        float from = b.ascendHit() - 2, top = b.ascendHit() + .5f, gone = b.ascendHit() + 6;
        if (a < from || a > gone) return;
        float p = VfxParts.smooth(from, top, a);
        float alpha = 1 - VfxParts.smooth(top, gone, a);
        Vec3 c = at.add(0, 1, 0).add(dir.scale(.6));
        float head = -40 + 150 * p, tail = Math.max(-40, head - 30 - 90 * p);
        arc(view, pose, out, alpha, (s) -> {
            float deg = Mth.lerp(s, tail, head);
            double r = Math.toRadians(deg);
            return c.add(dir.scale(Math.cos(r) * 2.3)).add(0, Math.sin(r) * 2.3, 0).add(right.scale(.8 - 1.6 * (deg + 40) / 150));
        }, 1.1f, .4f);
    }

    /** 一道弧（{@code at(s)}，s 从尾 0 到头 1）：外面一层赤金、中间一条白芯，越往尾巴越细越淡。 */
    private void arc(VfxParts.View view, PoseStack pose, SubmitNodeCollector out, float alpha, java.util.function.Function<Float, Vec3> at, float wide,
                     float core) {
        if (alpha <= .01f) return;
        int n = 18;
        // 赤金的边是半透明的底（亮天上也是赤金），白芯和一层薄金是加法
        body(pose, out, "heal/glow", (p, buf) -> {
            for (int i = 0; i < n; i++) {
                float s0 = i / (float) n, s1 = (i + 1) / (float) n;
                float w = Mth.lerp(s1, .3f, 1);
                VfxParts.streak(p, buf, view, at.apply(s0), at.apply(s1), wide * w, VfxParts.argb(alpha * w * .7f, VfxParts.lerpColor(CORAL, FLAME, s1)));
            }
        });
        glow(pose, out, "heal/glow", (p, buf) -> {
            for (int i = 0; i < n; i++) {
                float s0 = i / (float) n, s1 = (i + 1) / (float) n;
                float w = Mth.lerp(s1, .3f, 1);
                VfxParts.streak(p, buf, view, at.apply(s0), at.apply(s1), wide * w * .55f, VfxParts.argb(alpha * w * .3f, GOLD));
                VfxParts.streak(p, buf, view, at.apply(s0), at.apply(s1), core * w * .7f, VfxParts.argb(alpha * w * .8f, WHITE));
            }
        });
        Vec3 tip = at.apply(1f);
        bloom(view, tip, 1.6f, VfxParts.argb(alpha * .7f, GOLD));
    }

    /** 登穹到坠下：举过头顶的剑暴涨成三倍长的龙炎巨刃；砍下时转到身前朝下，插进地里后淡掉。 */
    private void blade(VfxParts.View view, PoseStack pose, SubmitNodeCollector out, Vec3 at, float a) {
        LacertaUltimate.Beats b = beats;
        float start = b.rise() + 2, end = b.impact() + 3;
        if (a < start || a > end) return;
        float grow = VfxParts.smooth(start, b.apex(), a);
        float alpha = VfxParts.smooth(start, start + 2, a) * (1 - VfxParts.smooth(b.impact(), end, a));
        float swing = VfxParts.smooth(b.strike(), b.impact(), a);
        Vec3 up = new Vec3(0, 1, 0);
        Vec3 overhead = up.scale(.85).subtract(dir.scale(.5)).normalize(), down = dir.scale(.55).subtract(up.scale(.85)).normalize();
        Vec3 point = overhead.lerp(down, swing).normalize();
        Vec3 hands = at.add(0, Mth.lerp(swing, HANDS, 1.0f), 0).add(dir.scale(.4 * swing));
        float length = Mth.lerp(grow, 1.8f, BLADE);
        Vec3 tip = hands.add(point.scale(length));
        float flicker = .85f + .15f * Mth.sin(a * 2.7f);
        body(pose, out, "heal/glow", (p, buf) -> {
            VfxParts.streak(p, buf, view, hands, tip, .85f * flicker, VfxParts.argb(alpha * .5f, CORAL));
            VfxParts.streak(p, buf, view, hands, tip, .5f, VfxParts.argb(alpha * .7f, FLAME));
        });
        glow(pose, out, "heal/glow", (p, buf) -> {
            VfxParts.streak(p, buf, view, hands, tip, .32f, VfxParts.argb(alpha * .35f, GOLD));
            VfxParts.streak(p, buf, view, hands, tip, .12f, VfxParts.argb(alpha * .85f, WHITE));
        });
        for (int i = 1; i <= 3; i++) bloom(view, hands.add(point.scale(length * i / 3.0)), 1.3f, VfxParts.argb(alpha * .6f, GOLD));
    }

    /** 坠下：剑尖正下方被压出的一道炽白气锥。 */
    private void diveCone(VfxParts.View view, PoseStack pose, SubmitNodeCollector out, Vec3 at, float a) {
        LacertaUltimate.Beats b = beats;
        if (a < b.dive() || a > b.impact()) return;
        float k = VfxParts.smooth(b.dive(), b.impact(), a);
        float alpha = VfxParts.smooth(b.dive(), b.dive() + 2, a);
        Vec3 top = at.add(0, .3, 0), bottom = at.add(0, -1.5 - 1.8 * k, 0);
        body(pose, out, "heal/glow", (p, buf) -> VfxParts.streak(p, buf, view, top, bottom, 1.5f, VfxParts.argb(alpha * .45f, CORAL)));
        glow(pose, out, "heal/glow", (p, buf) -> VfxParts.streak(p, buf, view, top, bottom, .5f, VfxParts.argb(alpha * .5f, PALE)));
        bloom(view, bottom, 1.8f, VfxParts.argb(alpha * .7f, PALE));
    }

    /** 裂地：一圈贴地飞快推开的冲击环。 */
    private void shockRing(PoseStack pose, SubmitNodeCollector out, float k) {
        float t = k / 10;
        if (t > 1) return;
        float r = 1 + 11 * (1 - (1 - t) * (1 - t));
        int color = VfxParts.argb((1 - t) * .9f, PALE);
        Vec3 c = center.add(0, .12, 0);
        glow(pose, out, "heal/ring", (p, buf) -> VfxParts.flat(p, buf, c, new Vec3(1, 0, 0), new Vec3(0, 0, 1), r, 0, color));
        // 地上那一团光：底下垫半透明的暖色，加法的只留薄薄一层（亮地面上加法只会叠成白的）
        int under = VfxParts.argb((1 - t) * .45f, FLAME), glow = VfxParts.argb((1 - t) * .1f, GOLD);
        body(pose, out, "heal/glow", (p, buf) -> VfxParts.flat(p, buf, c.add(0, .04, 0), new Vec3(1, 0, 0), new Vec3(0, 0, 1), 4 + 2 * t, 0, under));
        glow(pose, out, "heal/glow", (p, buf) -> VfxParts.flat(p, buf, c.add(0, .05, 0), new Vec3(1, 0, 0), new Vec3(0, 0, 1), 3 + 2 * t, 0, glow));
    }

    /** 地缝：几刻里从落点往外裂开，先是炽白，转金，再慢慢冷成暗红。 */
    private void cracks(VfxParts.View view, PoseStack pose, SubmitNodeCollector out, float k, float fade) {
        if (cracks.isEmpty()) return;
        float open = Math.min(1, k / 3);
        int color = k < 4 ? WHITE : k < 20 ? VfxParts.lerpColor(WHITE, AMBER, (k - 4) / 16) : VfxParts.lerpColor(AMBER, EMBER_RED, Math.min(1, (k - 20) / 24));
        float alpha = fade * (k < 20 ? 1 : Mth.lerp(Math.min(1, (k - 20) / 24), 1, .6f));
        // 光缝：底下一条半透明的暖色（亮地面上也看得出是火），上面一条细细的加法亮芯
        int under = VfxParts.lerpColor(FLAME, EMBER_RED, Math.min(1, k / 40));
        body(pose, out, "heal/glow", (p, buf) -> eachSegment(open, (a0, a1, w) -> flatSegment(p, buf, a0, a1, w * 2.6f, VfxParts.argb(alpha * .6f, under))));
        int core = VfxParts.lerpColor(color, WHITE, k < 20 ? .35f : .1f);
        glow(pose, out, "heal/glow", (p, buf) -> eachSegment(open, (a0, a1, w) -> flatSegment(p, buf, a0, a1, w * .8f, VfxParts.argb(alpha * .75f, core))));
        // 裂缝里喷出的一道道细光
        if (k < 20) {
            float shaft = (1 - k / 20) * VfxParts.smooth(0, 2, k);
            glow(pose, out, "heal/glow", (p, buf) -> {
                for (Crack c : cracks) {
                    Vec3 base = c.points.get(Math.min(c.points.size() - 1, 2));
                    VfxParts.streak(p, buf, view, base, base.add(0, 2.5 + 2 * c.width * 10, 0), .4f, VfxParts.argb(shaft * .3f, GOLD));
                }
            });
        }
        for (Crack c : cracks) {
            Vec3 mid = c.points.get(c.points.size() / 2);
            bloom(view, mid, 1.1f, VfxParts.argb(alpha * (k < 20 ? .55f : .25f) * open, color));
        }
    }

    /** 冲天的炽白光柱：几刻里从地面冲上去、变粗，外面裹一层赤金和一层淡淡的白光幕，最后散掉。 */
    private void pillar(VfxParts.View view, PoseStack pose, SubmitNodeCollector out, float k) {
        int life = beats.condensed() ? PILLAR_SHORT : PILLAR_FULL;
        float show = VfxParts.smooth(0, 1.5f, k) * (1 - VfxParts.smooth(life - 6, life, k));
        if (show <= .003f) return;
        float h = PILLAR_HEIGHT * VfxParts.smooth(0, 5, k);
        float rp = Mth.lerp(VfxParts.smooth(0, 8, k), .5f, PILLAR_RADIUS);
        float t = k;
        Vec3 base = center.add(0, -.2, 0), top = center.add(0, h, 0);
        // 底下垫一层半透明的暖金、一圈珊瑚红的边：亮天上加法只会越叠越白，光柱的颜色要靠它们。
        // 上面的加法层薄、偏金，炽白只留正中一条细芯（PostVfx 先画半透明的、再画加法的，世界里那条路也是这样叠）
        body(pose, out, "heal/glow", (p, buf) -> {
            VfxParts.streak(p, buf, view, base, top, rp * 2.6f, VfxParts.argb(show * .45f, CORAL));
            VfxParts.streak(p, buf, view, base, top, rp * 1.7f, VfxParts.argb(show * .7f, FLAME));
        });
        glow(pose, out, "charge/pillar", (p, buf) -> {
            VfxParts.cylinder(p, buf, base, rp * 2f, h, 40, t * .01f, 3, -t * .05f, 2, VfxParts.argb(show * .08f, GOLD), VfxParts.argb(0, GOLD));
            // 圆柱前后两面都叠上去，白芯要更细更淡
            VfxParts.cylinder(p, buf, base, rp * .38f, h, 32, -t * .012f, 3, -t * .2f, 2, VfxParts.argb(show * .35f, WHITE), VfxParts.argb(show * .2f, PALE));
        });
        glow(pose, out, "pillar_burst/flame", (p, buf) ->
                VfxParts.cylinder(p, buf, base, rp, h, 40, t * .02f, 3, -t * .14f, 3, VfxParts.argb(show * .45f, GOLD), VfxParts.argb(show * .2f, CORAL)));
        glow(pose, out, "heal/glow", (p, buf) -> {
            VfxParts.streak(p, buf, view, base, top, rp * 1.3f, VfxParts.argb(show * .2f, GOLD));
            VfxParts.streak(p, buf, view, base, top, rp * .35f, VfxParts.argb(show * .5f, WHITE));
        });
        for (int i = 0; i < 5; i++) bloom(view, base.add(0, h * i / 4.0, 0), rp * 1.6f, VfxParts.argb(show * .5f, GOLD));
    }

    /** 焦黑碎岩：刚崩飞时红热，转眼烧成焦黑，悬在半空，最后缩没。 */
    private void rocks(VfxParts.View view, PoseStack stack, IntFunction<OrderedSubmitNodeCollector> layers, float k, float fade) {
        if (rocks.isEmpty()) return;
        float life = beats.length() - beats.impact() + FADE;
        float shrink = 1 - VfxParts.smooth(life - FADE - 8, life, k);
        if (shrink <= 0) return;
        layers.apply(0).submitCustomGeometry(stack, VfxParts.BLOCKS, (pose, buf) -> {
            for (Rock r : rocks) {
                Vec3 at = r.at(k);
                // 贴到镜头跟前的淡掉，不糊满一屏
                float alpha = VfxParts.fade(view.cam(), at, 1.2, 3);
                if (alpha <= 0) continue;
                Quaternionf turn = new Quaternionf(r.turn).rotateXYZ((float) r.spin.x * k, (float) r.spin.y * k, (float) r.spin.z * k);
                TextureAtlasSprite s = r.sprite;
                float du = (s.getU1() - s.getU0()) / 4, dv = (s.getV1() - s.getV0()) / 4;
                int tint = VfxParts.lerpColor(HOT, CHAR, VfxParts.smooth(0, 7, k));
                VfxParts.cube(pose, buf, at, r.size * shrink, turn, s.getU0() + du, s.getV0() + dv, s.getU0() + du * 3, s.getV0() + dv * 3, tint, alpha);
            }
        });
    }

    /** 焦烟：半透明的大烟团，在世界里画，跟着周围的光照。 */
    private void smoke(VfxParts.View view, PoseStack stack, IntFunction<OrderedSubmitNodeCollector> layers, float a, float fade) {
        if (smoke.isEmpty()) return;
        layers.apply(1).submitCustomGeometry(stack, VfxParts.blend("potion/smoke"), (pose, buf) -> {
            for (Puff p : smoke) {
                float age = a - p.born;
                float k = age / p.life;
                if (k < 0 || k > 1) continue;
                Vec3 at = p.from.add(p.vel.scale(age));
                float size = p.size * (1 + .9f * k);
                float alpha = .4f * fade * VfxParts.smooth(0, .15f, k) * (1 - VfxParts.smooth(.5f, 1, k)) * VfxParts.fade(view.cam(), at, size * .5, size * 1.5);
                if (alpha <= .003f) continue;
                // 2×2 的烟图：每一格往里收一点取，免得采到隔壁那一格、烟团带出方方的边
                float u0 = (p.frame % 2) * .5f + .03f, v0 = (p.frame / 2) * .5f + .03f;
                int color = VfxParts.lerpColor(0xB0503A, SMOKE, VfxParts.smooth(0, .3f, k));
                VfxParts.sprite(pose, buf, view, at, size, size, p.roll + age * .01f, u0, v0, u0 + .44f, v0 + .44f, VfxParts.argb(alpha, color));
            }
        });
    }

    // ---------------------------------------------------------------- 工具

    /** 一层发光：{@link PostVfx} 收的时候交给它（不跟着雾、光影包和世界的明暗走），不收时照旧在世界里画（同曦誓的 PostLayer）。 */
    private static void glow(PoseStack pose, SubmitNodeCollector out, String texture, SubmitNodeCollector.CustomGeometryRenderer draw) {
        if (PostVfx.collecting()) draw.render(pose.last(), PostVfx.add(PostVfx.logos(texture), false));
        else out.submitCustomGeometry(pose, VfxParts.add(texture), draw);
    }

    /** 一层半透明（暖色的底）：同 {@link #glow}，收的时候交 {@link PostVfx}，不收时在世界里画。 */
    private static void body(PoseStack pose, SubmitNodeCollector out, String texture, SubmitNodeCollector.CustomGeometryRenderer draw) {
        if (PostVfx.collecting()) draw.render(pose.last(), PostVfx.blend(PostVfx.logos(texture), false));
        else out.submitCustomGeometry(pose, VfxParts.blend(texture), draw);
    }

    @FunctionalInterface
    private interface Segment {
        void draw(Vec3 a, Vec3 b, float width);
    }

    /** 每道裂缝已经裂开（{@code open} 成）的每一段：从落点往外，越往外越细。 */
    private void eachSegment(float open, Segment segment) {
        for (Crack c : cracks) {
            int segs = c.points.size() - 1;
            float shown = open * segs;
            for (int i = 0; i < segs && i < shown; i++) {
                Vec3 a0 = c.points.get(i), a1 = c.points.get(i + 1);
                a1 = a0.lerp(a1, Math.min(1, shown - i));
                segment.draw(a0, a1, c.width * (1 - .55f * i / segs));
            }
        }
    }

    /** 再交一份给泛光（坐标相对镜头）。 */
    private static void bloom(VfxParts.View view, Vec3 at, float size, int argb) {
        if ((argb >>> 24) < 4) return;
        Vec3 cam = view.cam();
        GlowBloom.sprite((float) (at.x - cam.x), (float) (at.y - cam.y), (float) (at.z - cam.z), size, argb);
    }

    /** 贴地的一段：从 {@code a} 到 {@code b}、宽 {@code width} 的平面条。 */
    private static void flatSegment(PoseStack.Pose pose, VertexConsumer buf, Vec3 a, Vec3 b, float width, int argb) {
        Vec3 along = b.subtract(a);
        double len = along.length();
        if (len < 1e-3) return;
        Vec3 side = new Vec3(-along.z, 0, along.x).normalize().scale(width / 2);
        VfxParts.flat(pose, buf, a.add(b).scale(.5), along.scale(.5), side, 1, 0, argb);
    }

    private Vec3 spread(double r) {
        return new Vec3((random.nextDouble() - .5) * 2 * r, (random.nextDouble() - .5) * 2 * r, (random.nextDouble() - .5) * 2 * r);
    }

    /** {@code at} 这一列往下 {@code depth} 格内的地面（同服务端脚本）。 */
    static Vec3 ground(Level level, Vec3 at, double depth) {
        Vec3 from = at.add(0, 1.5, 0), to = at.add(0, -depth, 0);
        var hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
        return hit.getType() == HitResult.Type.MISS ? at : new Vec3(at.x, hit.getLocation().y, at.z);
    }
}
