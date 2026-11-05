package dev.logos.dimension.combat;

import java.util.*;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 与具体模型无关的一帧姿势：每根骨骼相对静止姿势的位移、旋转（Blockbench 空间的 ZYX 欧拉角，度）与缩放。
 * 没写到的骨骼保持静止姿势；骨架里没有的骨骼被忽略，所以同一帧姿势可以套到任何符合标准的骨架上。
 */
public final class CombatPose {
    public record Joint(Vector3f position, Vector3f rotation, Vector3f scale) {
        static Joint rest() { return new Joint(new Vector3f(), new Vector3f(), new Vector3f(1)); }
    }
    private static final float RADIANS = (float) Math.PI / 180;
    private final Map<String, Joint> joints = new HashMap<>();

    public static CombatPose sample(CombatClip clip, float seconds) {
        var pose = new CombatPose();
        for (var bone : clip.bones()) {
            var joint = Joint.rest();
            clip.sample(bone, CombatClip.Channel.POSITION, seconds, joint.position);
            clip.sample(bone, CombatClip.Channel.ROTATION, seconds, joint.rotation);
            clip.sample(bone, CombatClip.Channel.SCALE, seconds, joint.scale);
            pose.joints.put(bone, joint);
        }
        return pose;
    }

    public Set<String> bones() { return joints.keySet(); }
    public Joint joint(String bone) { return joints.get(bone); }
    /** 去掉某根骨骼的关节（它回到静止姿势）后的副本。 */
    public CombatPose without(String bone) {
        var out = copy(this);
        out.joints.remove(bone);
        return out;
    }
    /** 骨骼的欧拉角（度）；没有时为零。 */
    public Vector3f rotation(String bone) { var j = joints.get(bone); return j == null ? new Vector3f() : new Vector3f(j.rotation); }

    /** 从本姿势向 to 过渡 weight（0–1）：位移、缩放线性，旋转走四元数球面插值，避免欧拉角绕远路。 */
    public CombatPose blend(CombatPose to, float weight) {
        if (weight <= 0) return copy(this);
        if (weight >= 1) return copy(to);
        var out = new CombatPose();
        Set<String> names = new HashSet<>(joints.keySet()); names.addAll(to.joints.keySet());
        for (var name : names) {
            var a = joints.getOrDefault(name, Joint.rest()); var b = to.joints.getOrDefault(name, Joint.rest());
            var q = quaternion(a.rotation).slerp(quaternion(b.rotation), weight);
            var euler = q.getEulerAnglesZYX(new Vector3f()).mul(1 / RADIANS);
            out.joints.put(name, new Joint(new Vector3f(a.position).lerp(b.position, weight), euler, new Vector3f(a.scale).lerp(b.scale, weight)));
        }
        return out;
    }
    private static CombatPose copy(CombatPose source) {
        var out = new CombatPose();
        source.joints.forEach((k, j) -> out.joints.put(k, new Joint(new Vector3f(j.position), new Vector3f(j.rotation), new Vector3f(j.scale))));
        return out;
    }
    private static Quaternionf quaternion(Vector3f degrees) {
        return new Quaternionf().rotateZ(degrees.z * RADIANS).rotateY(degrees.y * RADIANS).rotateX(degrees.x * RADIANS);
    }

    /** 骨骼局部矩阵：绕枢轴先缩放、再按 Z、Y、X 顺序旋转，最后加上位移。 */
    public static Matrix4f local(Vector3f pivot, Joint joint) {
        var m = new Matrix4f().translation(new Vector3f(pivot).add(joint.position))
                .rotateZ(joint.rotation.z * RADIANS).rotateY(joint.rotation.y * RADIANS).rotateX(joint.rotation.x * RADIANS);
        if (joint.scale.x != 1 || joint.scale.y != 1 || joint.scale.z != 1) m.scale(joint.scale);
        return m.translate(-pivot.x, -pivot.y, -pivot.z);
    }

    /** 骨架中每根骨骼的模型空间矩阵（Blockbench 空间、像素单位）。 */
    public Map<String, Matrix4f> matrices(CombatSkeleton skeleton) {
        Map<String, Matrix4f> out = new HashMap<>();
        for (var bone : skeleton.bones()) {
            var m = local(bone.pivot(), joints.getOrDefault(bone.name(), Joint.rest()));
            if (bone.parent() != null) m = new Matrix4f(out.get(bone.parent())).mul(m);
            out.put(bone.name(), m);
        }
        return out;
    }
}
