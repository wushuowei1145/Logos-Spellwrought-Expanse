package dev.logos.dimension.client.music;

import dev.logos.dimension.music.InstrumentKind;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import org.joml.Matrix3f;
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
    /** 乐器（和弓）从「居中的物品像素」到根坐标像素的变换，以及惯用手、非惯用手该到的位置；坐着拉的乐器是方块，instrument 为 null。 */
    record Place(@Nullable Matrix4f instrument, @Nullable Matrix4f bow, Vector3f mainHand, Vector3f offHand) {}

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
        Matrix4f body = frame(model.body, crouch ? 3.2f : 0, crouch ? 0.5f : model.body.xRot);
        Matrix4f head = frame(model.head, crouch ? 4.2f : 0, model.head.xRot);
        return place(kind, body, head, leftHanded, t, move);
    }

    /**
     * 按身体和头的坐标系（玩家模型根坐标的像素）摆乐器和双手。玩家、原版人形生物用模型的部件；
     * 附属 NPC 用自己的骨架换算到同一套坐标（{@link NpcMusician}），所以手位、乐器位置都是同一份。
     */
    static Place place(InstrumentKind kind, Matrix4f body, Matrix4f head, boolean leftHanded, float t, float move) {
        return switch (kind) {
            case VIOLIN -> bowedUnderChin(body, leftHanded, t, move, 0.65f, new Vector3f(2.5f, 0.5f, -4.2f));
            case VIOLA -> bowedUnderChin(body, leftHanded, t, move, 0.75f, new Vector3f(2.8f, 0.9f, -4.3f));
            case FLUTE -> flute(head, leftHanded, t, move);
            case OBOE -> oboe(head, leftHanded, t, move);
            case BASSOON -> bassoon(body, leftHanded, t, move);
            default -> trumpet(head, leftHanded, t, move);
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

    /** 小提琴、中提琴（中提琴大一圈，同一个模型放大）。 */
    private static Place bowedUnderChin(Matrix4f body, boolean mirror, float t, float move, float scale, Vector3f chin) {
        // 琴身从下巴往前、往非惯用手一侧斜出 40°，琴头略低，琴面朝惯用手一侧翻 15°（迎着弓）；腮托那一端贴在下巴左下
        Matrix4f rot = new Matrix4f().rotateY(-40 * DEG).rotateX(10 * DEG).rotateX(-90 * DEG).rotateY(15 * DEG);
        Matrix4f violin = local(chin, rot, scale, 8, 1, 9);
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

    /** 双簧管：哨片含在嘴里，管身朝前下方斜出（与身体约成 40°），两手一上一下扶着管身。 */
    private static Place oboe(Matrix4f head, boolean mirror, float t, float move) {
        float a = (50 + 1.5f * move * (float) Math.sin(t * 0.15f)) * DEG;
        float sa = (float) Math.sin(a), ca = (float) Math.cos(a);
        // 物品 +Y（哨片一头）朝后上、-Y（喇叭口）朝前下；按键那面（-Z）朝前上；X 朝身体右侧
        Matrix4f rot = new Matrix4f(-1, 0, 0, 0, 0, -sa, ca, 0, 0, ca, sa, 0, 0, 0, 0, 1);
        Matrix4f oboe = local(new Vector3f(0, -1.6f, -4.3f), rot, 0.72f, 8, 15.6f, 8);
        return new Place(world(head, oboe, mirror, FLIP_X), null,
            point(head, oboe, mirror, 8, 5, 10.8f),          // 惯用手在下半段
            point(head, oboe, mirror, 8, 11, 10.8f));        // 非惯用手在上半段
    }

    /**
     * 巴松管：斜挎在身前，管靴在惯用手一侧的胯前，管身离竖直 30°、喇叭口越过非惯用手一侧的肩膀；
     * 嘴边那一段管身在脸的外侧，弯管从那里伸到嘴里（哨片尖 = tools/instrument_models/winds.py 的 BASSOON_REED）。
     */
    private static Place bassoon(Matrix4f body, boolean mirror, float t, float move) {
        float sway = 1.5f * DEG * move * (float) Math.sin(t * 0.12f);
        Vector3f up = new Vector3f(0.5f, -0.866f, 0).rotateZ(sway).normalize();
        Vector3f back = new Vector3f(0, 0, 1);
        Vector3f side = new Vector3f(up).cross(back).normalize();
        // 物品 X、Y、Z 依次落到 side、up（管身往喇叭口）、back（朝演奏者）
        Matrix4f rot = new Matrix4f(side.x, side.y, side.z, 0, up.x, up.y, up.z, 0, back.x, back.y, back.z, 0, 0, 0, 0, 1);
        Matrix4f bassoon = local(new Vector3f(0, -1.8f, -4.4f), rot, 0.9f, 12.33f, 12.1f, 12.5f);
        return new Place(world(body, bassoon, mirror, FLIP_X), null,
            point(body, bassoon, mirror, 9.4f, 0.5f, 10.6f),  // 惯用手从外侧托着管靴（托手架那里）
            point(body, bassoon, mirror, 6.6f, 7.5f, 10.6f)); // 非惯用手在翼管靠里的一侧
    }

    /**
     * 坐着（大提琴）或站着（低音提琴）拉的琴在朝北放置的方块模型里的摆放，与 tools/instrument_models/strings.py 的 SEATED 是同一组数：
     * 琴按竖直、正面朝北画在自己的坐标里（琴脚尖在原点，y 沿琴身向上，x 向东，z 朝琴背），先后仰 lean°（琴头朝演奏者）、
     * 再向左倾 tilt°（琴头朝西），琴脚尖放在 endpin（方块像素）。contact 是弓毛压在弦上的点；左手沿指板与琴颈中间那条线
     * （z = neckZ，y 在 neckLow–neckHigh）从琴枕往下，找第一个直臂够得着的位置。
     */
    private record Layout(Vector3f endpin, float lean, float tilt, Vector3f contact, float neckZ, float neckLow, float neckHigh, float bowScale) {}

    private static final Layout CELLO = new Layout(new Vector3f(8.8f, 0, 2.1f), 25, 16, new Vector3f(0, 11.5f, -2.5f), -1.0f, 12.5f, 21.5f, 0.85f);
    private static final Layout BASS = new Layout(new Vector3f(7, 0, 3.2f), 14, 10, new Vector3f(0, 13.6f, -3.4f), -1.5f, 15.0f, 26.5f, 0.8f);
    /** 手臂从肩上的支点到手心（模型像素）：原版手臂长 12，手心在末端往上一点。 */
    private static final float GRIP = 9;

    /**
     * 坐着（大提琴）或站着（低音提琴）演奏的方块乐器：乐器是方块模型、不跟着人动，这里只算琴弓和两只手。
     * 坐标先按朝北放置的方块模型写，再换到玩家模型的根坐标：演奏者面朝北、站在 {@link #seatFeet} 处，
     * 玩家模型整体缩小到 15/16，模型的 y 从脖子往下量（脚在 +24）。
     * 右手瞄准弓根该在的地方，在那条线上一臂远处握住弓根，弓总从琴弦上的 contact 那一点过；左手按在指板上一臂远的位置。
     */
    static Place seated(InstrumentKind kind, HumanoidModel<?> model, float t, float move) {
        Vector3f feet = seatFeet(kind);
        Vector3f right = fromModel(new Vector3f(model.rightArm.x - 1, model.rightArm.y, model.rightArm.z), feet);
        Vector3f left = fromModel(new Vector3f(model.leftArm.x + 1, model.leftArm.y, model.leftArm.z), feet);
        Place p = seatedBlock(kind, right, left, GRIP * MODEL_SCALE, t, move);
        return new Place(null, toModelMatrix(feet).mul(p.bow()), toModel(p.mainHand(), feet), toModel(p.offHand(), feet));
    }

    /**
     * 同 {@link #seated}，但都在朝北放置的方块模型坐标（像素）里：肩的位置、手臂长（肩到手心）由骨架给出，
     * 所以原版人形模型和附属 NPC 的骨架（{@link NpcMusician}）共用。返回的琴弓矩阵把弓的物品像素放到方块像素里。
     * 惯用手的手位就是画出来的弓根（离肩一臂远）：原版直臂指过去正好落在那里，NPC 的两段手臂按它解 IK，手总捏着弓根。
     * 弓根「该在」的点（target）对 NPC 常常比一臂远，按它解时手被收回到肩的正前方、只跟着它上下动，弓却在左右拉。
     */
    static Place seatedBlock(InstrumentKind kind, Vector3f rightShoulder, Vector3f leftShoulder, float reach, float t, float move) {
        Layout l = kind == InstrumentKind.DOUBLE_BASS ? BASS : CELLO;
        Matrix3f r = new Matrix3f().rotationZYX(l.tilt() * DEG, 0, l.lean() * DEG);
        Vector3f contact = onInstrument(l, r, l.contact());
        Vector3f right = r.transform(new Vector3f(1, 0, 0));    // 沿琴的横向朝演奏者右手边（弓根一侧，方块坐标的东）
        Vector3f hair = r.transform(new Vector3f(0, 0, 1));     // 弓毛朝着琴弦（琴背的方向）
        // 在弹时弓沿弓杆来回拉
        float along = 10.5f + 3.5f * move * (float) Math.sin(t * 0.3f);
        Vector3f target = new Vector3f(right).mul((along - 1.8f) * l.bowScale()).add(contact);
        Vector3f grip = new Vector3f(target).sub(rightShoulder).normalize().mul(reach).add(rightShoulder);
        Vector3f stick = new Vector3f(contact).sub(grip).normalize();                       // 弓根 → 弓尖
        Vector3f face = new Vector3f(hair).sub(new Vector3f(stick).mul(hair.dot(stick))).normalize();
        Vector3f third = new Vector3f(face).cross(stick);
        Matrix4f rot = new Matrix4f(face.x, face.y, face.z, 0, stick.x, stick.y, stick.z, 0, third.x, third.y, third.z, 0, 0, 0, 0, 1);
        Matrix4f bow = local(grip, rot, l.bowScale(), 8.1f, 1.8f, 8);
        return new Place(null, bow, grip, neckHand(l, r, leftShoulder, reach));
    }

    /** 方块像素 → 玩家模型根坐标的矩阵（{@link #toModel} 的矩阵形式）。 */
    static Matrix4f toModelMatrix(Vector3f feet) {
        return new Matrix4f().translate(0, 24, 0).scale(-1 / MODEL_SCALE, -1 / MODEL_SCALE, 1 / MODEL_SCALE).translate(-feet.x, -feet.y, -feet.z);
    }

    /** 玩家模型根坐标（像素）→ 方块像素（{@link #toModel} 的逆）。 */
    static Vector3f fromModel(Vector3f model, Vector3f feet) {
        return new Vector3f(feet.x - model.x * MODEL_SCALE, feet.y + (24 - model.y) * MODEL_SCALE, feet.z + model.z * MODEL_SCALE);
    }

    /**
     * 琴槌、琴锤：模型的握点 (8, grip, 8) 放在 hand，模型 +Y（槌杆）指向 target，按长度缩放到槌头中心（模型 y = head）正好落在 target。
     * 坐标单位随调用方（方块像素或模型像素）。
     */
    static Matrix4f along(Vector3f hand, Vector3f target, float grip, float head) {
        Vector3f stick = new Vector3f(target).sub(hand);
        float len = stick.length();
        if (len < 1e-4f) stick.set(0, -1, 0);
        else stick.div(len);
        Vector3f helper = Math.abs(stick.y) > 0.9f ? new Vector3f(1, 0, 0) : new Vector3f(0, 1, 0);
        Vector3f x = new Vector3f(helper).cross(stick).normalize();
        Vector3f z = new Vector3f(x).cross(stick).normalize();
        Matrix4f rot = new Matrix4f(x.x, x.y, x.z, 0, stick.x, stick.y, stick.z, 0, z.x, z.y, z.z, 0, 0, 0, 0, 1);
        float scale = Math.max(0.5f, Math.min(1.4f, len / (head - grip)));
        return local(hand, rot, scale, 8, grip, 8);
    }

    /** 左手：沿指板从琴枕往下，第一个离肩正好一臂远的点；整条指板都够不着就按在最下端（方块像素）。 */
    private static Vector3f neckHand(Layout l, Matrix3f r, Vector3f shoulder, float reach) {
        Vector3f p = new Vector3f();
        for (float y = l.neckHigh(); y >= l.neckLow(); y -= 0.25f) {
            p = onInstrument(l, r, new Vector3f(0, y, l.neckZ()));
            if (p.distance(shoulder) >= reach) return p;
        }
        return p;
    }

    private static Vector3f onInstrument(Layout l, Matrix3f r, Vector3f local) {
        return r.transform(new Vector3f(local)).add(l.endpin());
    }

    /** 玩家模型相对世界缩小的比例（原版画玩家时 scale 0.9375）。 */
    static final float MODEL_SCALE = 0.9375f;

    /** 演奏者双脚在方块模型里的位置（像素），见 {@link dev.logos.dimension.music.SeatedInstrumentBlock#feet}。 */
    static Vector3f seatFeet(InstrumentKind kind) {
        var f = dev.logos.dimension.music.SeatedInstrumentBlock.feet(kind);
        return new Vector3f((float) f.x, (float) f.y, (float) f.z);
    }

    /** 方块模型坐标（像素）→ 玩家模型根坐标（像素）。 */
    static Vector3f toModel(Vector3f block, Vector3f feet) {
        return new Vector3f(-(block.x - feet.x) / MODEL_SCALE, 24 - (block.y - feet.y) / MODEL_SCALE, (block.z - feet.z) / MODEL_SCALE);
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
