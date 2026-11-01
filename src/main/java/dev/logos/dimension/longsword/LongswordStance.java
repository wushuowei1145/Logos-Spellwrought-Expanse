package dev.logos.dimension.longsword;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/** 先保留原版潜行的弯腰与下沉，再在同一上半身坐标系内叠加长剑动作。 */
public final class LongswordStance {
    public record BodyPose(Matrix4f transform, Vector3f neckOffset) {}
    private LongswordStance() {}

    /** units 为像素单位到目标坐标的比例：模型用 1，渲染栈用 1/16。 */
    public static Matrix4f base(boolean crouching, float units) {
        return crouching ? new Matrix4f().translation(0,3.2f*units,0).rotateX(.5f) : new Matrix4f();
    }
    public static BodyPose body(Vector3f degrees, boolean leftHanded, boolean crouching) {
        float radians=(float)Math.PI/180, mirror=leftHanded?-1:1;
        var action=new Matrix4f().translation(0,6,0).rotateZ(degrees.z*radians*mirror)
                .rotateY(-degrees.y*radians*mirror).rotateX(-degrees.x*radians).translate(0,-6,0);
        var stance=base(crouching,1);
        // 头保留原版看向与潜行位置，只跟随动作引起的颈部位移。
        var neck=stance.transformDirection(action.getTranslation(new Vector3f()));
        return new BodyPose(stance.mul(action),neck);
    }
}
