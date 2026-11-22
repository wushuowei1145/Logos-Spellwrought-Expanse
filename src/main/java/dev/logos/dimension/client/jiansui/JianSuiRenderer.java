package dev.logos.dimension.client.jiansui;

import com.geckolib.cache.model.GeoBone;
import com.geckolib.cache.model.GeoQuad;
import com.geckolib.cache.model.GeoVertex;
import com.geckolib.cache.model.cuboid.CuboidGeoBone;
import com.geckolib.cache.model.cuboid.GeoCube;
import com.geckolib.constant.dataticket.DataTicket;
import com.geckolib.renderer.base.BoneSnapshots;
import com.geckolib.renderer.base.GeoRenderState;
import com.geckolib.renderer.base.RenderPassInfo;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.logos.dimension.client.NpcRenderer;
import dev.logos.dimension.client.dissolve.Dissolves;
import dev.logos.dimension.client.shiraume.SoftGlow;
import dev.logos.dimension.jiansui.JianSui;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 剑祟的渲染（docs/JIAN_SUI_BOSS.md 第 5.1、6.3 节）：身体照 NPC 的通用渲染；
 * <ul>
 *   <li>手中古剑不并进模型，画在空的 {@code Weapon} 骨骼上（{@link JianSuiSwords}），掷出去时不画；</li>
 *   <li>看人只转眼珠：头不跟着视线转，竖瞳（{@code LeftPupil}、{@code RightPupil}）在眼眶的椭圆里平移过去
 *       （椭圆和眼眶的斜度见仓库外的 {@code eye_tracking.json}），超出椭圆的角度由服务端把整个身子转过去补；</li>
 *   <li>出手前双眼闪一次琥珀光（第 5.1 节），画在竖瞳上；</li>
 *   <li>藏起来的剑（背剑飞出去、贯穿古剑被撬出来）不画对应的骨骼；</li>
 *   <li>终结里每撬一下（{@link JianSuiFinisherClient#reaction}）：两只手沿着「被撬」动作本身的方向多抓一下（顿一下再回来），
 *       贯穿古剑沿剑身往外顶出一截，面具正面多一道深色的裂纹（画在面具那根骨骼上，跟着头走）。</li>
 * </ul>
 */
public class JianSuiRenderer<R extends LivingEntityRenderState & GeoRenderState> extends NpcRenderer<JianSui, R> {
    private static final DataTicket<Integer> HIDDEN = DataTicket.create("logos_dimension_jian_sui_hidden", Integer.class);
    private static final DataTicket<float[]> PUPILS = DataTicket.create("logos_dimension_jian_sui_pupils", float[].class);
    private static final DataTicket<Float> FLASH = DataTicket.create("logos_dimension_jian_sui_flash", Float.class);
    /** 终结里被撬的样子：手抽一下多大、贯穿古剑顶出多少格、面具裂了几道（见 {@link JianSuiFinisherClient.Reaction}）。 */
    private static final DataTicket<float[]> PRY = DataTicket.create("logos_dimension_jian_sui_pry", float[].class);
    /** 被撬时抽一下：两臂、两前臂沿动作本身的转角多转几成（动作里两手已经伸着去抓刺进来的东西）。 */
    private static final float TWITCH = .22f;
    private static final String[] TWITCH_BONES = {"LeftArm", "RightArm", "LeftForeArm", "RightForeArm"};
    /**
     * 贯穿古剑往外顶的方向：沿剑身从胸口指向头顶的剑柄（按模型里剑身的斜度量的；Blockbench 空间换到 GeckoLib 时 X 取反）。
     * 骨骼的平移按模型单位：渲染缩放 0.5，一格是 32 个单位。
     */
    private static final Vector3f AXIAL_OUT = new Vector3f(.027f, 1, -.092f).normalize();
    private static final float UNITS_PER_BLOCK = 16 / MODEL_SCALE;
    /**
     * 面具上的三道裂纹，一道一道多出来：画在面具朝前最大的那一块（额头那一圈）上，u 横、v 从上往下（0–1），
     * 都从额头正中、贯穿古剑穿出来的地方往下裂。
     */
    private static final float[][] CRACKS = {
            {.47f, 0, .43f, .22f, .36f, .38f, .39f, .55f, .30f, .78f, .27f, 1},
            {.53f, 0, .58f, .18f, .66f, .31f, .63f, .50f, .74f, .70f, .80f, .93f},
            {.50f, 0, .52f, .20f, .47f, .42f, .53f, .62f, .49f, .84f, .51f, 1}};
    /** 裂纹的颜色（乘在面具贴图上，只压暗）；宽度、离面具多远，都按那一块的宽度算（不管模型单位）。 */
    private static final int CRACK_COLOR = 0xFF2C2824;
    private static final float CRACK_WIDTH = .022f, CRACK_LIFT = .006f;
    /** 雾底场地很暗：Boss 最低按方块光这么亮画，看得清起手。 */
    static final int MIN_LIGHT = 10;
    /** 竖瞳的椭圆（Blockbench 空间、Head 坐标）：u 横、v 竖两根轴，两个半径，静止时的位置（eye_tracking.json）。 */
    private static final Vector3f LEFT_U = new Vector3f(.94807f, -.25644f, .18817f), LEFT_V = new Vector3f(.2836f, .94939f, -.13504f);
    private static final Vector3f RIGHT_U = new Vector3f(.96681f, .17284f, -.18817f), RIGHT_V = new Vector3f(-.19978f, .97049f, -.13504f);
    private static final float LEFT_RU = 1.088f, RIGHT_RU = .985f, RADIUS_V = .243f;
    /** 左右偏多少度、上下偏多少度时竖瞳走到椭圆的边。 */
    private static final float YAW_FULL = 50, PITCH_FULL = 35;
    /** 双眼闪光多久（刻）。 */
    private static final float FLASH_TICKS = 14;
    private static final SoftGlow EYE_GLOW = new SoftGlow();
    /** 竖瞳的位置，每只剑祟各一份，帧间平滑地追过去（闪光的那一刻直接锁死）。 */
    private static final Map<Integer, float[]> SMOOTH = new HashMap<>();

    public JianSuiRenderer(EntityRendererProvider.Context context) {
        super(context, "jian_sui");
        this.shadowRadius = .9f;
    }

    @Override
    protected int getBlockLightLevel(JianSui boss, net.minecraft.core.BlockPos pos) {
        return Math.max(MIN_LIGHT, super.getBlockLightLevel(boss, pos));
    }

    @Override
    public void addRenderData(JianSui boss, Void relatedObject, R state, float partialTick) {
        super.addRenderData(boss, relatedObject, state, partialTick);
        state.addGeckolibData(HIDDEN, boss.hidden());
        float since = (float) (boss.level().getGameTime() - boss.flashTick()) + partialTick;
        state.addGeckolibData(FLASH, since);
        state.addGeckolibData(PUPILS, pupils(boss, partialTick, since < 2));
        var pry = JianSuiFinisherClient.reaction(boss, partialTick);
        if (pry != null) state.addGeckolibData(PRY, new float[] {pry.twitch(), pry.push(), pry.cracks()});
    }

    /** 竖瞳要走到的地方：(a, b) 是沿眼眶 u、v 两根轴的偏移，按目标相对身子的偏航、俯仰算，限在椭圆里。 */
    private static float[] pupils(JianSui boss, float partialTick, boolean snap) {
        Entity target = boss.lookTarget();
        float a = 0, b = 0;
        if (target != null) {
            Vec3 eye = boss.getEyePosition(partialTick), to = target.getEyePosition(partialTick).subtract(eye);
            float yaw = Mth.lerp(partialTick, boss.yBodyRotO, boss.yBodyRot) * Mth.DEG_TO_RAD;
            Vec3 forward = new Vec3(-Mth.sin(yaw), 0, Mth.cos(yaw)), right = new Vec3(-Mth.cos(yaw), 0, -Mth.sin(yaw));
            double flat = Math.sqrt(to.x * to.x + to.z * to.z);
            float yawRel = (float) Math.toDegrees(Math.atan2(to.dot(right), to.dot(forward)));
            float pitchRel = (float) Math.toDegrees(Math.atan2(to.y, Math.max(1e-4, flat)));
            a = Mth.clamp(yawRel / YAW_FULL, -1, 1);
            b = Mth.clamp(pitchRel / PITCH_FULL, -1, 1);
            float len = (float) Math.sqrt(a * a + b * b);
            if (len > 1) { a /= len; b /= len; }
        }
        float[] at = SMOOTH.computeIfAbsent(boss.getId(), k -> new float[2]);
        float k = snap ? 1 : .35f;
        at[0] += (a - at[0]) * k;
        at[1] += (b - at[1]) * k;
        if (SMOOTH.size() > 32) SMOOTH.clear();
        return new float[] {at[0], at[1]};
    }

    @Override
    public void adjustModelBonesForRender(RenderPassInfo<R> info, BoneSnapshots snapshots) {
        // 头不跟着视线转（看人只转眼珠）：通用渲染按视线摆头，先把视线清零
        R state = info.renderState();
        state.xRot = 0;
        state.yRot = 0;
        super.adjustModelBonesForRender(info, snapshots);
        int hidden = info.getOrDefaultGeckolibData(HIDDEN, 0);
        for (int i = 0; i < 5; i++) if ((hidden & (2 << i)) != 0) snapshots.ifPresent("BackSword" + i, s -> s.skipRender(true));
        if ((hidden & JianSui.HIDE_AXIAL) != 0) snapshots.ifPresent("AxialSword", s -> s.skipRender(true));
        float[] p = info.getOrDefaultGeckolibData(PUPILS, null);
        if (p != null) {
            pupil(snapshots, "LeftPupil", LEFT_U, LEFT_V, LEFT_RU, p);
            pupil(snapshots, "RightPupil", RIGHT_U, RIGHT_V, RIGHT_RU, p);
        }
        float[] pry = info.getOrDefaultGeckolibData(PRY, null);
        if (pry != null) {
            float grab = 1 + TWITCH * pry[0];
            if (pry[0] > 0) for (String name : TWITCH_BONES) snapshots.ifPresent(name, s -> s.setRotX(s.getRotX() * grab));
            float d = pry[1] * UNITS_PER_BLOCK;
            if (d > 0) snapshots.ifPresent("AxialSword", s -> {
                s.setTranslateX(s.getTranslateX() + AXIAL_OUT.x * d);
                s.setTranslateY(s.getTranslateY() + AXIAL_OUT.y * d);
                s.setTranslateZ(s.getTranslateZ() + AXIAL_OUT.z * d);
            });
        }
    }

    /** 把一只竖瞳挪到椭圆里 (a, b) 那一点（椭圆以它静止的位置为中心），Blockbench 空间换到 GeckoLib 时 X 取反。 */
    private static void pupil(BoneSnapshots snapshots, String bone, Vector3f u, Vector3f v, float radiusU, float[] p) {
        float da = p[0] * radiusU, db = p[1] * RADIUS_V;
        float x = u.x * da + v.x * db, y = u.y * da + v.y * db, z = u.z * da + v.z * db;
        snapshots.ifPresent(bone, s -> {
            s.setTranslateX(s.getTranslateX() - x);
            s.setTranslateY(s.getTranslateY() + y);
            s.setTranslateZ(s.getTranslateZ() + z);
        });
    }

    @Override
    public void adjustRenderPose(RenderPassInfo<R> info) {
        super.adjustRenderPose(info);
        var bones = info.model().boneLookup().get();
        GeoBone weapon = bones.get("Weapon");
        if (weapon != null && (info.getOrDefaultGeckolibData(HIDDEN, 0) & JianSui.HIDE_HELD) == 0) info.addPerBoneRender(weapon, this::submitSword);
        if (info.getOrDefaultGeckolibData(FLASH, 99f) < FLASH_TICKS) {
            GeoBone left = bones.get("LeftPupil"), right = bones.get("RightPupil");
            if (left != null) info.addPerBoneRender(left, (i, b, out) -> eye(i));
            if (right != null) info.addPerBoneRender(right, (i, b, out) -> eye(i));
        }
        float[] pry = info.getOrDefaultGeckolibData(PRY, null);
        if (pry != null && pry[2] > 0 && bones.get("MaskShell") instanceof CuboidGeoBone mask)
            info.addPerBoneRender(mask, (i, b, out) -> cracks(i, mask, out, pry[2]));
    }

    private void submitSword(RenderPassInfo<R> info, GeoBone bone, SubmitNodeCollector out) {
        JianSuiSwords.submitOnBone(info.poseStack(), out, info.packedLight(), JianSuiSwords.HELD, 1, 0xFFFFFFFF);
    }

    /** 双眼的琥珀光：竖瞳的位置换成相对镜头的世界坐标，交给泛光一团星芒，一下亮起、很快暗下去。 */
    private void eye(RenderPassInfo<R> info) {
        if (Dissolves.shadowPass()) return;
        float age = info.getOrDefaultGeckolibData(FLASH, 99f) / FLASH_TICKS;
        float k = age < .2f ? age / .2f : 1 - (age - .2f) / .8f;
        if (k <= .01f) return;
        Vector3f p = info.poseStack().last().pose().transformPosition(0, 0, -.6f / 16, new Vector3f());
        var cam = Minecraft.getInstance().gameRenderer.getMainCamera();
        var view = new dev.logos.client.VfxParts.View(cam.position(), new Vec3(cam.leftVector()).scale(-1), new Vec3(cam.upVector()));
        SoftGlow glow = EYE_GLOW.begin(view);
        Vec3 at = cam.position().add(p.x, p.y, p.z);
        glow.star(at, .26f * (.6f + .4f * k), 1.1f * k, dev.logos.client.VfxParts.argb(Math.min(1, k * 1.2f), 0xFFB238));
        glow.disc(at, .09f, dev.logos.client.VfxParts.argb(k, 0xFFF4D8));
        glow.flush();
    }

    /** 面具朝前最大的那一块：{@code MaskShell} 的方块里朝前（-Z）、面积最大的那个面。 */
    private record MaskFace(GeoCube cube, GeoQuad quad) {}

    private static @Nullable MaskFace maskFace(CuboidGeoBone mask) {
        MaskFace best = null;
        double area = 0;
        for (GeoCube cube : mask.cubes) {
            for (GeoQuad q : cube.quads()) {
                if (q == null || q.normalZ() > -.5f) continue;
                float[] b = bounds(q);
                double a = (double) (b[1] - b[0]) * (b[3] - b[2]);
                if (a > area) {
                    area = a;
                    best = new MaskFace(cube, q);
                }
            }
        }
        return best;
    }

    /** 一个面（方块自己的坐标里是平的）的范围：x 最小、最大，y 最小、最大。 */
    private static float[] bounds(GeoQuad q) {
        float[] b = {Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE};
        for (GeoVertex v : q.vertices()) {
            b[0] = Math.min(b[0], v.posX());
            b[1] = Math.max(b[1], v.posX());
            b[2] = Math.min(b[2], v.posY());
            b[3] = Math.max(b[3], v.posY());
        }
        return b;
    }

    /**
     * 面具上的裂纹：{@code count} 道（带小数时最后一道正裂到几成），贴在那一面上、往外抬一点点，和面具一样受光。
     * 姿势照 GeckoLib 画这个方块时的变换（骨骼 → 方块的枢轴和转角），所以裂纹跟着头、跟着面具的斜度走。
     */
    private void cracks(RenderPassInfo<R> info, CuboidGeoBone mask, SubmitNodeCollector out, float count) {
        MaskFace face = maskFace(mask);
        if (face == null) return;
        int light = info.packedLight();
        out.submitCustomGeometry(info.poseStack(), RenderTypes.entityCutout(JianSuiSwords.TEXTURE), (pose, vertices) -> {
            PoseStack ps = new PoseStack();
            ps.last().set(pose);
            mask.translateAwayFromPivotPoint(ps);
            face.cube().translateToPivotPoint(ps);
            face.cube().rotate(ps);
            face.cube().translateAwayFromPivotPoint(ps);
            crackLines(ps.last(), vertices, face.quad(), count, light);
        });
    }

    private static void crackLines(PoseStack.Pose p, VertexConsumer out, GeoQuad q, float count, int light) {
        GeoVertex[] vs = q.vertices();
        float[] b = bounds(q);
        float width = b[1] - b[0], height = b[3] - b[2], z = vs[0].posZ();
        // 用这一面中间那一点的贴图（面具本身的颜色），乘上深色
        float u = 0, v = 0;
        for (GeoVertex x : vs) {
            u += x.texU() / vs.length;
            v += x.texV() / vs.length;
        }
        float half = width * CRACK_WIDTH / 2, lift = width * CRACK_LIFT;
        float nx = q.normalX(), ny = q.normalY(), nz = q.normalZ();
        for (int i = 0; i < CRACKS.length && i < count; i++) {
            float[] c = CRACKS[i];
            // 从额头往下裂：按这一面真正的宽高量整道多长，裂到哪就画到哪
            float total = 0;
            for (int k = 2; k < c.length; k += 2) total += (float) Math.hypot((c[k] - c[k - 2]) * width, (c[k + 1] - c[k - 1]) * height);
            float left = total * Math.min(1, count - i);
            for (int k = 2; k < c.length && left > 0; k += 2) {
                float ax = Mth.lerp(c[k - 2], b[0], b[1]), ay = Mth.lerp(c[k - 1], b[3], b[2]);
                float bx = Mth.lerp(c[k], b[0], b[1]), by = Mth.lerp(c[k + 1], b[3], b[2]);
                float len = (float) Math.hypot(bx - ax, by - ay);
                if (len < 1e-6f) continue;
                float sx = -(by - ay) / len * half, sy = (bx - ax) / len * half;
                if (len > left) {
                    bx = ax + (bx - ax) * left / len;
                    by = ay + (by - ay) * left / len;
                }
                left -= len;
                crackVertex(p, out, ax - sx, ay - sy, z, lift, u, v, light, nx, ny, nz);
                crackVertex(p, out, ax + sx, ay + sy, z, lift, u, v, light, nx, ny, nz);
                crackVertex(p, out, bx + sx, by + sy, z, lift, u, v, light, nx, ny, nz);
                crackVertex(p, out, bx - sx, by - sy, z, lift, u, v, light, nx, ny, nz);
            }
        }
    }

    private static void crackVertex(PoseStack.Pose p, VertexConsumer out, float x, float y, float z, float lift, float u, float v, int light, float nx, float ny, float nz) {
        out.addVertex(p, x + nx * lift, y + ny * lift, z + nz * lift).setColor(CRACK_COLOR).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light).setNormal(p, nx, ny, nz);
    }
}
