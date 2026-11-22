package dev.logos.dimension.client.companion;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.logos.dimension.client.dissolve.Dissolves;
import dev.logos.dimension.client.longsword.LongswordRig;
import dev.logos.dimension.companion.KohakuCompanion;
import java.util.Map;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 把凝辉画在琥珀的右手（{@code RightHand} 骨骼）上（docs/KOHAKU_COMPANION.md 第 6 节）：用战斗网格
 * {@code combat/weapons/vesperglass_greatsword.json}（{@link LongswordRig}，同白梅的流火 {@code RyukaOnBone}），所以凝聚、消散、自发光
 * 都和别的战斗武器一样。模型里没有武器骨骼，剑不并进模型。
 *
 * <p>骨骼回调里的姿势以「模型像素 / 16」为单位，原点在骨骼的枢轴上（手腕），已经带着手臂的动作。剑握在拳心（{@link #GRIP}），
 * 剑身顺着手臂往外伸、再往前倾一点（{@link #ORIENT}），剑面朝上下，横扫时刃口在前。
 */
public final class VesperglassOnBone {
    /** 网格单位 → 模型像素（同白梅流火的 2.4，进游戏对过）：剑长大约她身高的四分之三。 */
    static final float SCALE = 2.4f;
    /** 拳心相对手腕（RightHand 枢轴）的位置（模型像素）：同 RightHandLocator。 */
    static final Vector3f GRIP = new Vector3f(0, -2.5f, -1.26f);
    /** 网格 +Y（剑尖）转到手臂往外（手的 -Y）再往前倾 30°；网格 X（剑宽）转到手的前后，剑面朝上下。 */
    static final Quaternionf ORIENT = new Quaternionf().rotateX((float) Math.toRadians(30)).mul(
            // 按列：网格 X → 手的 +Z，网格 Y → 手的 -Y，网格 Z → 手的 +X
            new Quaternionf().setFromNormalized(new org.joml.Matrix3f(0, 0, 1, 0, -1, 0, 1, 0, 0)));
    /** 凝聚、消散边缘的颜色：琥珀。 */
    static final int EDGE = 0xE8A043;

    /** 测试用：画过几帧剑。 */
    private static int draws;

    public static int draws() {
        return draws;
    }

    private VesperglassOnBone() {}

    /**
     * 画剑：{@code state} 小于 1 时是凝聚的进度（从柄往尖长出来），等于 1 是完整的剑（加自发光），大于 1 时是碎掉的进度（{@code state - 1}，从尖往柄散掉）。
     */
    static void submit(PoseStack pose, SubmitNodeCollector out, int light, float state, Object key) {
        LongswordRig rig = LongswordRig.get(KohakuCompanion.vesperglassWeapon(), false);
        if (rig == null) return;
        draws++;
        float wsp = rig.weaponScale();
        Vector3f ref = rig.weaponPivot();
        Matrix4f toMesh = new Matrix4f().scale(1 / 16f).translate(GRIP).rotate(ORIENT).scale(SCALE / wsp).translate(-ref.x, -ref.y, -ref.z);
        Matrix4f weapon = new Matrix4f(toMesh).translate(ref).scale(wsp);
        var sample = new LongswordRig.Sample(Map.of("Weapon", toMesh), new Vector3f(), weapon, 0, new Vector3f(), new Vector3f());
        if (state < 1) {
            rig.submitDissolving(sample, pose, out, light, Math.max(0, state), true, false, EDGE, key);
            return;
        }
        if (state > 1) {
            rig.submitDissolving(sample, pose, out, light, Math.min(1, state - 1), false, false, EDGE, key);
            return;
        }
        rig.submit(sample, pose, out, light, null, false, true, false);
        if (!Dissolves.shadowPass()) rig.submitGlow(sample, pose, out, false, .55f, 0xFFFFFF);
    }

    /**
     * 凝辉动作开始后 {@code seconds} 秒剑的样子（同 {@link #submit} 的 {@code state}），没有剑为 -1：0.05–0.3 秒凝聚，到 0.85 秒完整，
     * 之后 0.15 秒碎掉（同模型 conjure_sweep 的 conjure、shatter 标记）。
     */
    static float state(float seconds) {
        if (seconds < .05f || seconds >= 1f) return -1;
        if (seconds < .3f) return (seconds - .05f) / .25f;
        if (seconds < .85f) return 1;
        return 1 + Math.min(1, (seconds - .85f) / .15f);
    }
}
