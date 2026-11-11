package dev.logos.dimension.client.music;

import dev.logos.dimension.music.InstrumentKind;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 演奏时乐器和双手的摆放。单位是模型像素，坐标是玩家模型的根：−Y 向上、−Z 向前、+X 是身体左侧。
 * 按右手惯用设计，左手惯用时整幅画面左右镜像：
 * 小提琴夹在下巴下、搁在非惯用手一侧的肩上（跟着身体），非惯用手托琴颈，惯用手横着运弓；
 * 长笛横在嘴边、伸向惯用手一侧（跟着头）；小号举在嘴前（跟着头），惯用手在活塞旁。
 * 姿势（{@link HandheldPoses}）让双臂伸向这里给的两个手位，乐器层按同一份摆放画乐器，手就总落在乐器上。
 */
final class HeldRig {
    /** 乐器（和小提琴的弓）从「居中的物品像素」到根坐标像素的变换，以及惯用手、非惯用手该到的位置。 */
    record Place(Matrix4f instrument, @Nullable Matrix4f bow, Vector3f mainHand, Vector3f offHand) {}

    private static final float DEG = (float) (Math.PI / 180);
    /** 弓的物品坐标到琴的物品坐标：弓的 X（弓毛一侧）朝琴背、Y（弓尖）沿琴的横向、Z 沿琴身。 */
    private static final Matrix4f BOW_ON_VIOLIN = new Matrix4f(0, 0, 1, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1);
    /** 镜像时把模型自身也翻过来（沿它自己的对称面），这样画出来仍是正的模型，不会里外颠倒。 */
    private static final Vector3f FLIP_X = new Vector3f(-1, 1, 1), FLIP_Z = new Vector3f(1, 1, -1);

    private HeldRig() {}

    /**
     * @param ahead 姿势阶段为真：原版在摆完手臂之后才加蹲下的偏移（身体前倾、头和肩下移），这里先算进去；
     *              画乐器时模型已经摆好，取现成的就行
     * @param move 最近在弹为 1（弓来回、乐器轻摆），否则 0
     */
    static Place place(InstrumentKind kind, HumanoidModel<?> model, boolean crouching, boolean ahead, boolean leftHanded, float t, float move) {
        boolean crouch = crouching && ahead;
        return switch (kind) {
            case VIOLIN -> violin(frame(model.body, crouch ? 3.2f : 0, crouch ? 0.5f : model.body.xRot), leftHanded, t, move);
            case FLUTE -> flute(frame(model.head, crouch ? 4.2f : 0, model.head.xRot), leftHanded, t, move);
            default -> trumpet(frame(model.head, crouch ? 4.2f : 0, model.head.xRot), leftHanded, t, move);
        };
    }

    private static Matrix4f frame(ModelPart part, float drop, float xRot) {
        return new Matrix4f().translate(part.x, part.y + drop, part.z).rotateZYX(part.zRot, part.yRot, xRot);
    }

    /** 乐器上的 pivot 点（物品像素 0–16）放到 anchor，先缩放再转向。 */
    private static Matrix4f local(Vector3f anchor, Matrix4f rot, float scale, float px, float py, float pz) {
        return new Matrix4f().translate(anchor).mul(rot).scale(scale).translate(8 - px, 8 - py, 8 - pz);
    }

    private static Matrix4f world(Matrix4f frame, Matrix4f local, boolean mirror, Vector3f flip) {
        Matrix4f m = new Matrix4f(frame);
        if (mirror) m.scale(-1, 1, 1);
        m.mul(local);
        if (mirror) m.scale(flip);
        return m;
    }

    private static Vector3f point(Matrix4f frame, Matrix4f local, boolean mirror, float x, float y, float z) {
        Vector3f v = local.transformPosition(new Vector3f(x - 8, y - 8, z - 8));
        if (mirror) v.x = -v.x;
        return frame.transformPosition(v);
    }

    private static Place violin(Matrix4f body, boolean mirror, float t, float move) {
        // 琴身从下巴往前、往非惯用手一侧斜出 40°，琴头略低，琴面朝惯用手一侧翻 15°（迎着弓）；腮托那一端贴在下巴左下
        Matrix4f rot = new Matrix4f().rotateY(-40 * DEG).rotateX(10 * DEG).rotateX(-90 * DEG).rotateY(15 * DEG);
        Matrix4f violin = local(new Vector3f(2.5f, 0.5f, -4.2f), rot, 0.65f, 8, 1, 9);
        // 弓毛搭在琴马和指板之间的弦上，弓杆横过琴身，弓根在惯用手一侧；停着时搭在靠弓尖的一段（手不挡琴），在弹时来回拉
        float along = 10.5f + 3.5f * move * (float) Math.sin(t * 0.3f);
        Vector3f contact = violin.transformPosition(new Vector3f(0, 5 - 8, 6.4f - 8));
        Matrix4f bow = local(contact, new Matrix4f(rot).mul(BOW_ON_VIOLIN), 0.65f, 8.35f, along, 8);
        return new Place(world(body, violin, mirror, FLIP_X), world(body, bow, mirror, FLIP_Z),
            point(body, bow, mirror, 5.1f, 1.8f, 8),        // 惯用手捏着弓根，在弓杆背面
            point(body, violin, mirror, 8, 13, 11));         // 非惯用手从下面托着琴颈
    }

    private static Place flute(Matrix4f head, boolean mirror, float t, float move) {
        // 吹孔贴在下唇下，笛身伸向惯用手一侧，略朝前、略往下
        float sway = 2 * DEG * move * (float) Math.sin(t * 0.12f);
        Matrix4f rot = new Matrix4f().rotateY(-20 * DEG + sway).rotateZ(-8 * DEG).rotateZ(180 * DEG);
        Matrix4f flute = local(new Vector3f(0, -1.4f, -4.4f), rot, 0.75f, 2.8f, 8, 8);
        return new Place(world(head, flute, mirror, FLIP_Z), null,
            point(head, flute, mirror, 11.5f, 5.3f, 8),     // 惯用手在笛尾那一段的下面
            point(head, flute, mirror, 6.5f, 5.3f, 8));     // 非惯用手靠近吹孔
    }

    private static Place trumpet(Matrix4f head, boolean mirror, float t, float move) {
        // 号嘴贴着嘴唇，号身朝前、略往下；王室旗垂在导管下
        float lift = 1.5f * DEG * move * (float) Math.sin(t * 0.15f);
        Matrix4f rot = new Matrix4f().rotateX(8 * DEG - lift).rotateY(90 * DEG).rotateX(180 * DEG);
        Matrix4f trumpet = local(new Vector3f(0, -2, -4.1f), rot, 0.65f, 0.4f, 9, 8);
        return new Place(world(head, trumpet, mirror, FLIP_Z), null,
            point(head, trumpet, mirror, 7.4f, 9.5f, 11),    // 惯用手在活塞外侧
            point(head, trumpet, mirror, 7.0f, 8.0f, 5));    // 非惯用手握着另一侧
    }
}
