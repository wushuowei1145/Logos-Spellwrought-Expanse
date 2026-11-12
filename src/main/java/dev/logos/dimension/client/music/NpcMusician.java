package dev.logos.dimension.client.music;

import com.geckolib.animatable.GeoAnimatable;
import com.geckolib.cache.model.BakedGeoModel;
import com.geckolib.cache.model.GeoBone;
import com.geckolib.constant.dataticket.DataTicket;
import com.geckolib.renderer.base.BoneSnapshots;
import com.geckolib.renderer.base.GeoRenderState;
import com.geckolib.renderer.base.GeoRenderer;
import com.geckolib.renderer.base.RenderPassInfo;
import com.geckolib.renderer.layer.GeoRenderLayer;
import com.geckolib.renderer.layer.builtin.BlockAndItemGeoLayer;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.logos.dimension.music.StarbloomBenchBlock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 附属 NPC（车万女仆骨架：森林精灵、神狐，以后的 NPC 也一样，见 docs/NPC_MODELS.md 第 2 节）坐在星辰花琴凳上演奏。
 *
 * <p>手位和乐器的位置与玩家是同一份（{@link MusicianPoses}、{@link HeldRig}），这里只做换算：
 * <ul>
 * <li>手持乐器的坐标以肩线为准：玩家模型的肩在脖子下 2 像素，按两段手臂的总长和玩家一臂（9 像素）之比放大，量到骨架的 {@code Arm}；</li>
 * <li>键盘、竖琴的坐标在琴凳里，1 像素 = 2 个模型单位（NPC 按 2 倍制作），双脚在座位上。</li>
 * </ul>
 * 然后用两骨 IK：肩（{@code RightArm}）→ 肘（{@code RightForeArm}）→ 手的挂点（{@code RightHandLocator}），肘往外、往下弯，
 * 正好够到手位。腿坐下（大腿向前、小腿垂下），整个人往下挪到胯落在凳面上；躯干摆正；手里拿着的乐器不画，改画在手位上。
 * 骨骼缺了任何一根就不动它（退回原来的动画）。
 */
public final class NpcMusician {
    public static final DataTicket<MusicianPoses.Musician> MUSICIAN = DataTicket.create("logos_dimension_musician", MusicianPoses.Musician.class);
    public static final DataTicket<HandheldPoses.Held> HELD = DataTicket.create("logos_dimension_musician_held", HandheldPoses.Held.class);
    public static final DataTicket<Boolean> LEFT_HANDED = DataTicket.create("logos_dimension_musician_left_handed", Boolean.class);
    private static final float HALF_PI = (float) (Math.PI / 2);
    /** 玩家模型一臂（肩的支点到手心）的像素：同 {@link HeldRig} 的 GRIP。 */
    private static final float PLAYER_REACH = 9;
    /** 凳面比坐的人双脚高多少个模型单位（12 像素 × 2）。 */
    private static final float SEAT_UNITS = (float) ((StarbloomBenchBlock.TOP - StarbloomBenchBlock.FEET.y) * 2);
    /** 弹键盘时上身前倾（弧度，绕腰 {@code UpBody}）：车万女仆骨架的手臂只有 7.5 像素左右，坐直了只够得着正前方的琴键。 */
    private static final float KEYS_LEAN = 0.3f;
    /** 弹竖琴时上身略前倾。 */
    private static final float HARP_LEAN = 0.1f;
    /** 键盘、竖琴的手位够不着时左右收回到这个比例的臂长以内（肘留一点弯）。 */
    private static final float REACH = 0.97f;
    /** 大腿平伸时有一半厚度在支点下面（森林精灵、神狐的大腿厚 3.8 单位）：整个人再抬高这么多，大腿压在凳面上而不是陷进去。 */
    private static final float THIGH_HALF = 1.9f;
    /** 膝盖伸出凳面前沿多少像素，小腿垂在凳子前面。 */
    private static final float KNEE_OVERHANG = 0.75f;

    private NpcMusician() {}

    /** 骨架上要用到的东西（按模型缓存）。side：角色的左手边在 +X 时为 1。 */
    private record Skeleton(GeoBone rightArm, GeoBone rightFore, GeoBone leftArm, GeoBone leftFore, Vector3f rightShoulder, Vector3f leftShoulder,
                            float rightUpper, float rightLower, float leftUpper, float leftLower, Vector3f armLine, float scale, float side, float hip,
                            float thigh) {
        /** 玩家模型根坐标（像素）→ 骨架的模型单位。 */
        Vector3f canonical(Vector3f c) {
            return new Vector3f(armLine.x + side * scale * c.x, armLine.y - scale * (c.y - 2), armLine.z + scale * c.z);
        }

        Matrix4f canonicalMatrix() {
            return new Matrix4f().translate(armLine).scale(side * scale, -scale, scale).translate(0, -2, 0);
        }

        /** 关联了乐器的琴凳坐标（像素）→ 骨架的模型单位（整个人挪到 root 之前的坐标；坐的人往乐器那边挪过）。 */
        Vector3f bench(Vector3f b, Vector3f feet, Vector3f root) {
            return new Vector3f(-side * (b.x - feet.x) * 2, (b.y - feet.y) * 2 - root.y, (b.z - feet.z) * 2 - root.z);
        }

        /**
         * 坐下时整个人挪多少（模型单位，给 {@code Root}）：往上挪到大腿压在凳面上；往前挪到膝盖伸出凳面前沿，小腿垂在凳子前面
         * （这副骨架的大腿比原版短，坐在凳子正中时小腿会插进凳子里）。关联了乐器的琴凳上坐的人已经往前挪过。
         */
        Vector3f root(boolean linked) {
            float edge = (float) (StarbloomBenchBlock.FEET.z - StarbloomBenchBlock.FRONT_EDGE) - (linked ? StarbloomBenchBlock.LINKED_FORWARD : 0);
            float forward = Math.max(0, (edge + KNEE_OVERHANG) * 2 - thigh);
            return new Vector3f(0, SEAT_UNITS - hip + THIGH_HALF, -forward);
        }
    }

    private static final Map<BakedGeoModel, Optional<Skeleton>> SKELETONS = new WeakHashMap<>();

    private static @Nullable Skeleton skeleton(BakedGeoModel model) {
        return SKELETONS.computeIfAbsent(model, NpcMusician::build).orElse(null);
    }

    private static Optional<Skeleton> build(BakedGeoModel model) {
        GeoBone rArm = model.getBone("RightArm").orElse(null), rFore = model.getBone("RightForeArm").orElse(null);
        GeoBone lArm = model.getBone("LeftArm").orElse(null), lFore = model.getBone("LeftForeArm").orElse(null);
        GeoBone leg = model.getBone("LeftLeg").orElse(null), shin = model.getBone("LeftLowerLeg").orElse(null);
        if (rArm == null || rFore == null || lArm == null || lFore == null || leg == null) return Optional.empty();
        float hip = pivot(leg).y, knee = shin == null ? hip - 6 : pivot(shin).y;
        Vector3f rs = pivot(rArm), ls = pivot(lArm), re = pivot(rFore), le = pivot(lFore);
        Vector3f rg = grip(model, "RightHandLocator", "RightHand", re), lg = grip(model, "LeftHandLocator", "LeftHand", le);
        float ru = rs.distance(re), rl = re.distance(rg), lu = ls.distance(le), ll = le.distance(lg);
        Vector3f line = model.getBone("Arm").map(NpcMusician::pivot).orElse(new Vector3f(rs).add(ls).mul(0.5f));
        float side = Math.signum(ls.x - rs.x);
        return Optional.of(new Skeleton(rArm, rFore, lArm, lFore, rs, ls, ru, rl, lu, ll, line, (ru + rl) / PLAYER_REACH,
            side == 0 ? 1 : side, hip, hip - knee));
    }

    private static Vector3f pivot(GeoBone bone) {
        return new Vector3f(bone.pivotX(), bone.pivotY(), bone.pivotZ());
    }

    /** 手心：手的挂点；没有挂点时取手腕往下一点。 */
    private static Vector3f grip(BakedGeoModel model, String locator, String hand, Vector3f elbow) {
        Optional<GeoBone> l = model.getBone(locator);
        if (l.isPresent()) return pivot(l.get());
        Vector3f wrist = model.getBone(hand).map(NpcMusician::pivot).orElse(new Vector3f(elbow).sub(0, 8, 0));
        return wrist.sub(0, 3, 0);
    }

    /** 从实体取这一帧的演奏者（在演奏层的 addRenderData 里调）。 */
    static void addRenderData(LivingEntity entity, GeoRenderState state) {
        MusicianPoses.Musician m = MusicianPoses.of(entity);
        if (m == null) return;
        state.addGeckolibData(MUSICIAN, m);
        state.addGeckolibData(LEFT_HANDED, entity.getMainArm() == HumanoidArm.LEFT);
        if (m.mode() == MusicianPoses.Mode.HANDHELD) state.addGeckolibData(HELD, HandheldPoses.heldFor(m.kind(), entity, m.move()));
    }

    /** 在动画之后摆骨骼（NpcRenderer.adjustModelBonesForRender 调）：坐下、摆正、两臂够到手位。 */
    public static void pose(RenderPassInfo<?> info, BoneSnapshots snapshots) {
        MusicianPoses.Musician m = info.getOrDefaultGeckolibData(MUSICIAN, null);
        if (m == null) return;
        Skeleton sk = skeleton(info.model());
        if (sk == null) return;
        Vector3f root = sk.root(m.mode() != MusicianPoses.Mode.HANDHELD);
        snapshots.ifPresent("Root", s -> s.setTranslation(root.x, root.y, root.z));
        // 躯干摆正（总转角为 0，IK 按静止姿势的支点算）；弹键盘、竖琴时腰往前倾
        float lean = switch (m.mode()) {
            case KEYS -> KEYS_LEAN;
            case HARP -> HARP_LEAN;
            case HANDHELD -> 0;
        };
        GeoBone waist = lean == 0 ? null : info.model().getBone("UpBody").orElse(null);
        for (String name : List.of("AllBody", "UpBody", "UpperBody", "Arm")) {
            float x = waist != null && name.equals("UpBody") ? -lean : 0;
            info.model().getBone(name).ifPresent(bone -> {
                var s = snapshots.get(bone);
                if (s != null) s.setRotation(x - bone.baseRotX(), -bone.baseRotY(), -bone.baseRotZ());
            });
        }
        for (String bone : List.of("RightHand", "LeftHand")) {
            snapshots.ifPresent(bone, s -> s.setRotation(0, 0, 0));
        }
        // 坐下：大腿向前平伸、略分开，小腿垂下
        sit(snapshots, info.model(), "RightLeg", HALF_PI, -0.1f * sk.side());
        sit(snapshots, info.model(), "LeftLeg", HALF_PI, 0.1f * sk.side());
        sit(snapshots, info.model(), "RightLowerLeg", -HALF_PI, 0);
        sit(snapshots, info.model(), "LeftLowerLeg", -HALF_PI, 0);
        Vector3f right, left;
        if (m.mode() == MusicianPoses.Mode.HANDHELD) {
            boolean leftHanded = info.getOrDefaultGeckolibData(LEFT_HANDED, false);
            float t = info.renderState() instanceof LivingEntityRenderState living ? living.ageInTicks : 0;
            HeldRig.Place place = HeldRig.place(m.kind(), new Matrix4f(), new Matrix4f(), leftHanded, t, m.move());
            Vector3f main = sk.canonical(place.mainHand()), off = sk.canonical(place.offHand());
            right = leftHanded ? off : main;
            left = leftHanded ? main : off;
        } else if (m.left() != null && m.right() != null) {
            right = sk.bench(m.right(), m.feet(), root);
            left = sk.bench(m.left(), m.feet(), root);
        } else {
            return;
        }
        // 肘往外、往下、略往后
        Vector3f rightPole = new Vector3f(-sk.side() * 0.5f, -1, 0.35f), leftPole = new Vector3f(sk.side() * 0.5f, -1, 0.35f);
        if (waist != null) {
            // 手臂挂在前倾的腰上：手位、肘的方向都转回腰的静止坐标里再解
            Vector3f pivot = pivot(waist);
            Matrix3f back = new Matrix3f().rotationX(lean);
            right = back.transform(right.sub(pivot)).add(pivot);
            left = back.transform(left.sub(pivot)).add(pivot);
            back.transform(rightPole);
            back.transform(leftPole);
        }
        if (m.mode() != MusicianPoses.Mode.HANDHELD) {
            right = withinReach(right, sk.rightShoulder(), sk.rightUpper() + sk.rightLower());
            left = withinReach(left, sk.leftShoulder(), sk.leftUpper() + sk.leftLower());
        }
        solve(snapshots, sk.rightArm(), sk.rightFore(), sk.rightShoulder(), sk.rightUpper(), sk.rightLower(), right, rightPole);
        solve(snapshots, sk.leftArm(), sk.leftFore(), sk.leftShoulder(), sk.leftUpper(), sk.leftLower(), left, leftPole);
    }

    /**
     * 键盘、竖琴：手位离肩太远时只在左右方向收回到够得着的地方（手仍在琴键、琴弦那一层上，跟着音高的方向挪）；
     * 正前方都够不着时停在正前方，手臂伸直指过去。
     */
    private static Vector3f withinReach(Vector3f target, Vector3f shoulder, float length) {
        Vector3f d = new Vector3f(target).sub(shoulder);
        float max = REACH * length;
        float plane = d.y * d.y + d.z * d.z;
        float lateral = plane < max * max ? (float) Math.sqrt(max * max - plane) : 0;
        d.x = Math.max(-lateral, Math.min(lateral, d.x));
        return d.add(shoulder);
    }

    private static void sit(BoneSnapshots snapshots, BakedGeoModel model, String name, float x, float y) {
        model.getBone(name).ifPresent(bone -> {
            var s = snapshots.get(bone);
            if (s != null) s.setRotation(x - bone.baseRotX(), y - bone.baseRotY(), -bone.baseRotZ());
        });
    }

    /**
     * 两骨 IK：上臂从肩 shoulder 指向肘，前臂从肘指向手位 target（够不着就伸直指过去）。肘落在 pole 那一侧。
     * 骨骼的转动按 GeckoLib 的顺序合成（先 X、再 Y、再 Z，即 Rz·Ry·Rx），是相对骨骼静止姿势（手臂竖直下垂）的总转角，
     * 减去骨骼自带的转角就是这一帧要给的值。
     */
    private static void solve(BoneSnapshots snapshots, GeoBone upper, GeoBone fore, Vector3f shoulder, float l1, float l2, Vector3f target, Vector3f pole) {
        Vector3f d = new Vector3f(target).sub(shoulder);
        float reach = d.length();
        if (reach < 1e-4f) return;
        float dist = Math.max(Math.abs(l1 - l2) + 1e-3f, Math.min(l1 + l2 - 1e-3f, reach));
        Vector3f u = d.div(reach);
        float cosA = (l1 * l1 + dist * dist - l2 * l2) / (2 * l1 * dist);
        float a = (float) Math.acos(Math.max(-1, Math.min(1, cosA)));
        Vector3f p = new Vector3f(pole).sub(new Vector3f(u).mul(pole.dot(u)));
        if (p.lengthSquared() < 1e-6f) p.set(0, 0, 1).sub(new Vector3f(u).mul(u.z));
        p.normalize();
        Vector3f elbow = new Vector3f(u).mul(l1 * (float) Math.cos(a)).add(new Vector3f(p).mul(l1 * (float) Math.sin(a))).add(shoulder);
        Vector3f hand = new Vector3f(u).mul(dist).add(shoulder);
        Vector3f along = new Vector3f(elbow).sub(shoulder).normalize();
        Vector3f forearm = new Vector3f(hand).sub(elbow).normalize();
        Vector3f bend = new Vector3f(forearm).sub(new Vector3f(along).mul(forearm.dot(along)));
        if (bend.lengthSquared() < 1e-6f) bend.set(p);
        bend.normalize();
        // 上臂的局部坐标：手臂沿 -Y，前臂往 -Z 弯（前臂绕 X 正转就是往前弯）
        Vector3f y = new Vector3f(along).negate(), z = new Vector3f(bend).negate(), x = new Vector3f(y).cross(z);
        Matrix3f r = new Matrix3f(x, y, z);
        float ry = (float) Math.asin(Math.max(-1, Math.min(1, -r.m02())));
        float rx = (float) Math.atan2(r.m12(), r.m22());
        float rz = (float) Math.atan2(r.m01(), r.m00());
        var us = snapshots.get(upper);
        if (us != null) us.setRotation(rx - upper.baseRotX(), ry - upper.baseRotY(), rz - upper.baseRotZ());
        float flex = (float) Math.acos(Math.max(-1, Math.min(1, along.dot(forearm))));
        var fs = snapshots.get(fore);
        if (fs != null) fs.setRotation(flex - fore.baseRotX(), -fore.baseRotY(), -fore.baseRotZ());
    }

    /** 演奏时画乐器（和弓）、不画手里原本拿着的东西。挂在 NPC 渲染器上，排在手持物品层之后。 */
    public static final class Layer<T extends LivingEntity & GeoAnimatable, R extends GeoRenderState> extends GeoRenderLayer<T, Void, R> {
        public Layer(GeoRenderer<T, Void, R> renderer) {
            super(renderer);
        }

        @Override
        public void addRenderData(T animatable, Void relatedObject, R state, float partialTick) {
            NpcMusician.addRenderData(animatable, state);
            MusicianPoses.Musician m = state.getOrDefaultGeckolibData(MUSICIAN, (MusicianPoses.Musician) null);
            // 手里原本拿着的东西不画：拉琴时换成手持乐器，弹键盘、竖琴时两只手都在琴上
            if (m == null) return;
            List<BlockAndItemGeoLayer.RenderData> contents = state.getOrDefaultGeckolibData(BlockAndItemGeoLayer.CONTENTS, (List<BlockAndItemGeoLayer.RenderData>) null);
            if (contents == null) return;
            state.addGeckolibData(BlockAndItemGeoLayer.CONTENTS, contents.stream()
                .filter(d -> !d.boneName().equals("RightHandLocator") && !d.boneName().equals("LeftHandLocator")).toList());
        }

        @Override
        public void submitRenderTask(RenderPassInfo<R> info, SubmitNodeCollector collector) {
            MusicianPoses.Musician m = info.getOrDefaultGeckolibData(MUSICIAN, null);
            HandheldPoses.Held held = info.getOrDefaultGeckolibData(HELD, null);
            if (m == null || held == null || info.getModelRenderMatrixPose() == null) return;
            Skeleton sk = skeleton(info.model());
            if (sk == null) return;
            boolean leftHanded = info.getOrDefaultGeckolibData(LEFT_HANDED, false);
            float t = info.renderState() instanceof LivingEntityRenderState living ? living.ageInTicks : 0;
            HeldRig.Place place = HeldRig.place(held.kind(), new Matrix4f(), new Matrix4f(), leftHanded, t, held.move());
            PoseStack pose = new PoseStack();
            pose.last().set(info.getModelRenderMatrixPose());
            pose.scale(1 / 16f, 1 / 16f, 1 / 16f);
            Vector3f root = sk.root(m.mode() != MusicianPoses.Mode.HANDHELD);
            pose.translate(root.x, root.y, root.z);
            pose.mulPose(sk.canonicalMatrix());
            if (held.instrument() != null && place.instrument() != null) draw(pose, collector, info.packedLight(), place.instrument(), held.instrument());
            if (held.bow() != null && place.bow() != null) draw(pose, collector, info.packedLight(), place.bow(), held.bow());
        }

        private static void draw(PoseStack pose, SubmitNodeCollector collector, int light, Matrix4f pixels, ItemStackRenderState item) {
            pose.pushPose();
            pose.mulPose(pixels);
            pose.scale(16, 16, 16);
            item.submit(pose, collector, light, OverlayTexture.NO_OVERLAY, 0);
            pose.popPose();
        }
    }
}
