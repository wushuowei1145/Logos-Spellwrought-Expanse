package dev.logos.dimension.client.jiansui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.jiansui.FallStage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 第三阶段「坠渊」的画面（docs/JIAN_SUI_BOSS.md 第 10.2、13 节），围着 {@link FallStage} 画：
 * <ul>
 *   <li>外面那一层：像信标光柱那样画、但改成圆形的几层圆筒，贴图一直往上滚。最外是不透明的岩壁，里面两层半透明的雾；
 *       顶上一片发亮的淡色雾（刚掉下来时穿过的那层渊雾），底下一片黑，只透出一点淡青白的冷光。所有层按同一个速度往上滚，
 *       远近不同自然有视差。壳在真洞壁里面，所以洞壁永远看不到。</li>
 *   <li>只看的碎块（{@link FallProps}）：原版方块模型拼的断柱、地板块、魂灯、断链，各自慢慢翻转，不往上飘。</li>
 *   <li>往上飞的东西：镜头周围几格的尘土、魂火的火星一直往上流，拉成短线；隔十几秒有一大块东西从下面擦着场景冲上去。</li>
 * </ul>
 * 用原版的信标光柱和实体材质画（光影包认得它们），发光的小东西交给 Logos 的光影包叠加层（同其他特效）。
 */
public final class FallStageRenderer extends EntityRenderer<FallStage, FallStageRenderer.State> {
    private static final Identifier ROCK = LogosDimension.id("textures/environment/jian_sui_fall_rock.png");
    private static final Identifier MIST = LogosDimension.id("textures/environment/jian_sui_fall_mist.png");
    private static final Identifier GLOW = LogosDimension.id("textures/environment/jian_sui_fall_glow.png");
    private static final Identifier STREAK = LogosDimension.id("textures/environment/jian_sui_fall_streak.png");
    /** 壳的上下沿（相对实体，实体在世界 -38.5）：顶在 -21，底在 -56，都在真洞顶、洞底里面。 */
    static final float TOP = 17.5f, BOTTOM = -17.5f;
    /** 一圈分多少段。 */
    private static final int SEGMENTS = 96;
    /** 一层：半径、贴图一张多高（格）、一圈铺几张、颜色（顶上的那一圈；越往下越暗）、是否半透明、贴图横向慢慢漂多快。 */
    private record Layer(float radius, float tile, int repeat, int topColor, int bottomColor, boolean translucent, Identifier texture, float drift) {}
    private static final Layer[] LAYERS = {
        new Layer(34.8f, 64, 5, 0xFFD6DEE3, 0xFF2A3038, false, ROCK, 0),
        new Layer(32.2f, 24, 8, 0x98EEF3F6, 0x20808A94, true, MIST, .0021f),
        new Layer(29.4f, 30, 9, 0x3CF4F8FA, 0x08A0AAB4, true, STREAK, -.0033f),
    };
    /** 顶上亮雾、底下冷光的颜色。 */
    private static final int HAZE = 0xFFEEF3F6, HAZE_EDGE = 0xFFB4C2CC, ABYSS = 0xFF0A0E12, ABYSS_EDGE = 0xFF050608, COLD = 0x5578E0F0;
    /** 镜头周围往上飞的东西：多大范围、每格立方里大约几颗。 */
    private static final int MOTE_CELL = 3, MOTE_RANGE = 3;
    /** 擦身而过：多久一次（刻）、在离轴多远、比四周快几倍。 */
    private static final int PASS_PERIOD = 280;
    private static final float PASS_RADIUS = 26.5f, PASS_SPEED = 2.4f;
    private static final String[] PASSING = {"pillar_c", "chain", "slab_c", "pillar_a"};
    /** 每个场景各自累计往下掉了多远（格）：速度会变（终结时停住），所以不能直接拿时间乘。渲染器和外面那一层共用。 */
    private static final Map<Integer, double[]> FALLEN = new HashMap<>();

    public static final class State extends EntityRenderState {
        int id;
        float rotationDegrees;
        /** 累计掉了多远（格）、这一刻的速度（格/秒）、游戏时间（刻，带小数）。 */
        double scroll;
        float speed;
        double time;
        final Vector3f entity = new Vector3f();
    }

    public FallStageRenderer(EntityRendererProvider.Context context) {
        super(context);
        shadowRadius = 0;
    }

    @Override public State createRenderState() { return new State(); }

    /** 壳有七十多格宽，不按实体的判定箱剔除：镜头在附近就画。 */
    @Override public boolean shouldRender(FallStage stage, Frustum frustum, double x, double y, double z) {
        return stage.distanceToSqr(x, y, z) < 96 * 96;
    }

    @Override public void extractRenderState(FallStage stage, State state, float partial) {
        super.extractRenderState(stage, state, partial);
        state.id = stage.getId();
        state.rotationDegrees = switch (stage.rotationIndex()) { case 1 -> 90; case 2 -> 180; case 3 -> 270; default -> 0; };
        fill(stage, state, partial);
    }

    /** 这一帧的累计下坠距离、速度、时间、位置（渲染器和外面那一层都用）。 */
    static void fill(FallStage stage, State state, float partial) {
        double now = stage.level().getGameTime() + partial;
        float speed = stage.speedAt(now);
        double[] f = FALLEN.computeIfAbsent(stage.getId(), k -> new double[] {now, 0});
        double dt = Mth.clamp(now - f[0], 0, 20);
        f[1] += dt / 20 * speed;
        f[0] = now;
        if (FALLEN.size() > 8) FALLEN.keySet().removeIf(id -> stage.level().getEntity(id) == null);
        state.id = stage.getId();
        state.rotationDegrees = switch (stage.rotationIndex()) { case 1 -> 90; case 2 -> 180; case 3 -> 270; default -> 0; };
        state.scroll = f[1];
        state.speed = speed;
        state.time = now;
        state.entity.set((float) stage.getX(), (float) stage.getY(), (float) stage.getZ());
    }

    /** 实体渲染器只画只看的碎块和擦身而过的大块（普通方块的画法，光影包照常打光）；外面那一层见 {@link #submitShell}。 */
    @Override public void submit(State state, PoseStack pose, SubmitNodeCollector raw, CameraRenderState camera) {
        props(state, pose, raw);
        passBy(state, pose, raw);
    }

    /**
     * 外面那一层和往上飞的东西：在 {@code SubmitCustomGeometryEvent} 里（最低优先级，Logos 的光影叠加层 begin 之后）交，
     * 交给它的收集器：开着光影包时在光影包合成完画面之后再画（照样和世界比深度），光影包的程序管不着，五种画面环境里一个样子；
     * 没开光影包时就是普通的世界几何。实体渲染器交得太早，会被叠加层的 begin 清掉，所以不在那里画。
     * {@code pose} 已经移到场景实体的位置（相对镜头）。
     */
    static void submitShell(State state, PoseStack pose, SubmitNodeCollector out, CameraRenderState camera) {
        double scroll = state.scroll % 4096;
        for (Layer layer : LAYERS) {
            float drift = (float) (state.time * layer.drift % 1);
            out.submitCustomGeometry(pose, RenderTypes.beaconBeam(layer.texture, layer.translucent), (p, v) -> wall(p, v, layer, scroll, drift));
        }
        // 顶：发亮的雾，中间最亮；壳和顶之间一圈渐变的雾把接缝藏起来
        out.submitCustomGeometry(pose, RenderTypes.beaconBeam(GLOW, false), (p, v) -> {
            disk(p, v, TOP, 35.5f, HAZE, HAZE_EDGE, true);
            disk(p, v, BOTTOM, 35.5f, ABYSS, ABYSS_EDGE, false);
        });
        out.submitCustomGeometry(pose, RenderTypes.beaconBeam(GLOW, true), (p, v) -> {
            skirt(p, v, 34.2f, TOP - 15f, TOP, 0x00E8EEF2, 0xFFEEF3F6, scroll);
            skirt(p, v, 34.2f, BOTTOM, BOTTOM + 8f, 0xFF07090B, 0x0007090B, scroll);
        });
        // 擦过的光：偶尔一道淡淡的光带从下往上扫过岩壁，亮度变化不到一成
        double band = state.time % BAND_PERIOD;
        if (band < BAND_TICKS) {
            float y = Mth.lerp((float) (band / BAND_TICKS), BOTTOM - 6, TOP + 6);
            out.submitCustomGeometry(pose, RenderTypes.beaconBeam(GLOW, true), (p, v) -> skirt(p, v, 34.4f, y - 3, y + 3, 0x00FFFFFF, 0x16FFFFFF, 0));
        }
        // 底下透出的冷光：胸口光缝的淡青白
        out.submitCustomGeometry(pose, RenderTypes.beaconBeam(GLOW, true), (p, v) -> flat(p, v, BOTTOM + .3f, 22, COLD));
        motes(state, pose, out, camera);
    }

    /** 一层圆筒：从壳底到壳顶，按高度分几段，越往下越暗；贴图按累计掉的距离往上滚。 */
    private static void wall(PoseStack.Pose p, VertexConsumer v, Layer layer, double scroll, float drift) {
        int bands = 6;
        for (int i = 0; i < SEGMENTS; i++) {
            double a0 = i * Math.PI * 2 / SEGMENTS, a1 = (i + 1) * Math.PI * 2 / SEGMENTS;
            float x0 = (float) (Math.cos(a0) * layer.radius), z0 = (float) (Math.sin(a0) * layer.radius);
            float x1 = (float) (Math.cos(a1) * layer.radius), z1 = (float) (Math.sin(a1) * layer.radius);
            float u0 = (float) i / SEGMENTS * layer.repeat + drift, u1 = (float) (i + 1) / SEGMENTS * layer.repeat + drift;
            for (int b = 0; b < bands; b++) {
                float yb = Mth.lerp((float) b / bands, BOTTOM, TOP), yt = Mth.lerp((float) (b + 1) / bands, BOTTOM, TOP);
                float vt = (float) ((-yt + scroll) / layer.tile), vb = (float) ((-yb + scroll) / layer.tile);
                int ct = shade(layer, (float) (b + 1) / bands), cb = shade(layer, (float) b / bands);
                vertex(p, v, x0, yt, z0, u0, vt, ct);
                vertex(p, v, x0, yb, z0, u0, vb, cb);
                vertex(p, v, x1, yb, z1, u1, vb, cb);
                vertex(p, v, x1, yt, z1, u1, vt, ct);
            }
        }
    }

    private static int shade(Layer layer, float k) {
        // 光从上面来：顶上那一段最亮，往下越来越暗，最下面一段接近全黑
        float e = k * k * (3 - 2 * k);
        return lerpColor(layer.bottomColor, layer.topColor, (float) Math.pow(e, .55));
    }

    /** 顶或底的圆盘：一圈圈的环，中间颜色 {@code center}，边上 {@code edge}；顶朝下、底朝上。 */
    private static void disk(PoseStack.Pose p, VertexConsumer v, float y, float radius, int center, int edge, boolean facingDown) {
        int rings = 5;
        for (int r = 0; r < rings; r++) {
            float ra = radius * r / rings, rb = radius * (r + 1) / rings;
            int ca = lerpColor(center, edge, (float) r / rings), cb = lerpColor(center, edge, (float) (r + 1) / rings);
            for (int i = 0; i < SEGMENTS / 2; i++) {
                double a0 = i * Math.PI * 4 / SEGMENTS, a1 = (i + 1) * Math.PI * 4 / SEGMENTS;
                float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
                if (facingDown) {
                    vertex(p, v, ra * c0, y, ra * s0, .5f, .5f, ca);
                    vertex(p, v, rb * c0, y, rb * s0, .5f, .5f, cb);
                    vertex(p, v, rb * c1, y, rb * s1, .5f, .5f, cb);
                    vertex(p, v, ra * c1, y, ra * s1, .5f, .5f, ca);
                } else {
                    vertex(p, v, ra * c0, y, ra * s0, .5f, .5f, ca);
                    vertex(p, v, ra * c1, y, ra * s1, .5f, .5f, ca);
                    vertex(p, v, rb * c1, y, rb * s1, .5f, .5f, cb);
                    vertex(p, v, rb * c0, y, rb * s0, .5f, .5f, cb);
                }
            }
        }
    }

    /** 一圈渐变的雾带（用圆斑贴图正中的纯白）：从 {@code y0}（颜色 c0）到 {@code y1}（颜色 c1），把壳和顶、底的接缝藏起来。 */
    private static void skirt(PoseStack.Pose p, VertexConsumer v, float radius, float y0, float y1, int c0, int c1, double scroll) {
        for (int i = 0; i < SEGMENTS; i++) {
            double a0 = i * Math.PI * 2 / SEGMENTS, a1 = (i + 1) * Math.PI * 2 / SEGMENTS;
            float x0 = (float) (Math.cos(a0) * radius), z0 = (float) (Math.sin(a0) * radius);
            float x1 = (float) (Math.cos(a1) * radius), z1 = (float) (Math.sin(a1) * radius);
            // 取圆斑贴图正中那一点（纯白不透明），颜色和透明度全由顶点给
            vertex(p, v, x0, y1, z0, .5f, .5f, c1);
            vertex(p, v, x0, y0, z0, .5f, .5f, c0);
            vertex(p, v, x1, y0, z1, .5f, .5f, c0);
            vertex(p, v, x1, y1, z1, .5f, .5f, c1);
        }
    }

    /** 平放的一大片光（底下的冷光）。 */
    private static void flat(PoseStack.Pose p, VertexConsumer v, float y, float half, int color) {
        vertex(p, v, -half, y, -half, 0, 0, color);
        vertex(p, v, -half, y, half, 0, 1, color);
        vertex(p, v, half, y, half, 1, 1, color);
        vertex(p, v, half, y, -half, 1, 0, color);
    }

    private static void vertex(PoseStack.Pose p, VertexConsumer v, float x, float y, float z, float u, float vv, int color) {
        v.addVertex(p, x, y, z).setColor(color).setUv(u, vv).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(p, 0, 1, 0);
    }

    /** 只看的碎块：各自斜着放、慢慢翻转；断链来回摆。光照取所在那一格（再暗也留一点），魂灯整块全亮。 */
    private static void props(State state, PoseStack pose, SubmitNodeCollector out) {
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        double seconds = state.time / 20;
        float turn = state.rotationDegrees;
        double ca = Math.cos(Math.toRadians(turn)), sa = Math.sin(Math.toRadians(turn));
        for (FallProps.Prop prop : FallProps.props()) {
            var piece = FallProps.piece(prop.piece());
            if (piece == null) continue;
            List<net.minecraft.client.resources.model.geometry.BakedQuad> quads = FallProps.quads(piece);
            if (quads.isEmpty()) continue;
            // 画布方向 → 世界方向：同 TaixuanSite.turn（顺时针 90° 时 (x, z) → (-z, x)）
            float wx = (float) (prop.at().x * ca - prop.at().z * sa), wz = (float) (prop.at().x * sa + prop.at().z * ca);
            float y = prop.at().y - state.entity.y;
            pose.pushPose();
            pose.translate(wx, y, wz);
            pose.mulPose(Axis.YP.rotationDegrees(-turn));
            pose.mulPose(new Quaternionf().rotationXYZ((float) Math.toRadians(prop.tilt().x), (float) Math.toRadians(prop.tilt().y), (float) Math.toRadians(prop.tilt().z)));
            if (prop.sway() != 0) {
                pose.mulPose(Axis.YP.rotationDegrees((float) (seconds * prop.speed() + prop.phase())));
                pose.mulPose(Axis.XP.rotationDegrees((float) (Math.sin(seconds * .55 + prop.phase()) * prop.sway())));
            } else {
                pose.mulPose(new Quaternionf().rotationAxis((float) Math.toRadians(seconds * prop.speed() + prop.phase()), prop.axis().x, prop.axis().y, prop.axis().z));
            }
            int light = prop.piece().startsWith("lantern") ? LightCoordsUtil.pack(12, 0)
                    : LightCoordsUtil.max(LevelRenderer.getLightCoords(level, BlockPos.containing(state.entity.x + wx, prop.at().y, state.entity.z + wz)), LightCoordsUtil.pack(9, 0));
            out.submitItem(pose, ItemDisplayContext.NONE, light, OverlayTexture.NO_OVERLAY, 0, new int[0], quads, ItemStackRenderState.FoilType.NONE);
            pose.popPose();
        }
    }

    /** 擦过的光带：多久一次、扫过整个壳要几刻。 */
    private static final int BAND_PERIOD = 130, BAND_TICKS = 34;

    /** 擦身而过：每隔 {@link #PASS_PERIOD} 刻，一大块东西从壳底下冲上来、从顶上的亮雾里消失。 */
    private static void passBy(State state, PoseStack pose, SubmitNodeCollector out) {
        long cycle = (long) Math.floor(state.time / PASS_PERIOD);
        double age = state.time - cycle * PASS_PERIOD;
        float y = (float) (BOTTOM - 14 + age / 20 * state.speed * PASS_SPEED);
        if (y > TOP + 14 || state.speed < .5f) return;
        var piece = FallProps.piece(PASSING[(int) Math.floorMod(cycle, PASSING.length)]);
        if (piece == null) return;
        double angle = Math.floorMod(cycle * 2654435761L, 1000L) / 1000.0 * Math.PI * 2;
        pose.pushPose();
        pose.translate(Math.cos(angle) * PASS_RADIUS, y, Math.sin(angle) * PASS_RADIUS);
        pose.mulPose(Axis.YP.rotationDegrees((float) Math.toDegrees(-angle)));
        pose.mulPose(Axis.ZP.rotationDegrees((float) (age * 1.7)));
        out.submitItem(pose, ItemDisplayContext.NONE, LightCoordsUtil.pack(8, 0), OverlayTexture.NO_OVERLAY, 0, new int[0], FallProps.quads(piece), ItemStackRenderState.FoilType.NONE);
        pose.popPose();
    }

    /**
     * 镜头周围往上飞的尘土和魂火的火星：按世界坐标分格，每格按格子编号散几颗，所以人走动时它们不跟着人走；
     * 往上流的速度是四周的 0.8～1.3 倍，越快拉得越长。离镜头 2 格以内的不画（贴脸的一大片很晃）。
     */
    private static void motes(State state, PoseStack pose, SubmitNodeCollector out, CameraRenderState camera) {
        if (state.speed < .3f) return;
        double effects = Minecraft.getInstance().options.screenEffectScale().get();
        if (effects <= 0) return;
        Vec3 cam = camera.pos;
        int cx = Mth.floor(cam.x / MOTE_CELL), cy = Mth.floor(cam.y / MOTE_CELL), cz = Mth.floor(cam.z / MOTE_CELL);
        Vector3f right = new Vector3f(1, 0, 0);
        if (camera.orientation != null) camera.orientation.transform(right);
        right.y = 0;
        if (right.lengthSquared() < 1e-4f) right.set(1, 0, 0);
        right.normalize();
        float rx = right.x, rz = right.z;
        out.submitCustomGeometry(pose, RenderTypes.beaconBeam(GLOW, true), (p, v) -> {
            for (int gx = cx - MOTE_RANGE; gx <= cx + MOTE_RANGE; gx++)
                for (int gy = cy - MOTE_RANGE; gy <= cy + MOTE_RANGE; gy++)
                    for (int gz = cz - MOTE_RANGE; gz <= cz + MOTE_RANGE; gz++) {
                        long h = hash(gx, gy, gz);
                        int count = (int) (h & 1) + ((h >>> 9 & 3) == 0 ? 1 : 0);
                        for (int k = 0; k < count; k++) {
                            long hk = hash(h, k);
                            float fx = (hk & 0xFF) / 255f, fz = (hk >>> 8 & 0xFF) / 255f, fy = (hk >>> 16 & 0xFF) / 255f;
                            float pace = .8f + (hk >>> 24 & 0xFF) / 255f * .5f;
                            double y = gy * MOTE_CELL + Math.floorMod((long) Math.floor((fy * MOTE_CELL + state.scroll * pace) * 64), MOTE_CELL * 64L) / 64.0;
                            double x = (gx + fx) * MOTE_CELL, z = (gz + fz) * MOTE_CELL;
                            double dx = x - cam.x, dy = y - cam.y, dz = z - cam.z;
                            double d2 = dx * dx + dy * dy + dz * dz;
                            if (d2 < 4 || d2 > 81) continue;
                            boolean ember = (hk >>> 33 & 7) == 0;
                            float fade = (float) Math.min(1, (81 - d2) / 30) * (float) Math.min(1, (d2 - 4) / 6);
                            float len = .25f + state.speed * pace * .045f, width = ember ? .05f : .07f;
                            int rgb = ember ? 0x7FE6FF : 0xC8C2B4;
                            int alpha = (int) ((ember ? 200 : 110) * fade * effects);
                            if (alpha < 4) continue;
                            int color = (alpha << 24) | rgb;
                            float bx = (float) (x - state.entity.x), by = (float) (y - state.entity.y), bz = (float) (z - state.entity.z);
                            float ox = rx * width, oz = rz * width;
                            vertex(p, v, bx - ox, by + len, bz - oz, 0, 0, color & 0x00FFFFFF);
                            vertex(p, v, bx - ox, by, bz - oz, 0, 1, color);
                            vertex(p, v, bx + ox, by, bz + oz, 1, 1, color);
                            vertex(p, v, bx + ox, by + len, bz + oz, 1, 0, color & 0x00FFFFFF);
                        }
                    }
        });
    }

    private static long hash(long a, long b) {
        long h = a * 0x9E3779B97F4A7C15L + b * 0xC2B2AE3D27D4EB4FL;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        return h ^ h >>> 32;
    }

    private static long hash(int x, int y, int z) {
        return hash(hash(x, y), z);
    }

    private static int lerpColor(int a, int b, float k) {
        int aa = a >>> 24, ar = a >> 16 & 255, ag = a >> 8 & 255, ab = a & 255;
        int ba = b >>> 24, br = b >> 16 & 255, bg = b >> 8 & 255, bb = b & 255;
        return Math.round(Mth.lerp(k, aa, ba)) << 24 | Math.round(Mth.lerp(k, ar, br)) << 16 | Math.round(Mth.lerp(k, ag, bg)) << 8 | Math.round(Mth.lerp(k, ab, bb));
    }
}
