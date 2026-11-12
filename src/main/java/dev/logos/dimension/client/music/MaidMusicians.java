package dev.logos.dimension.client.music;

import com.github.tartaricacid.simplebedrockmodel.client.bedrock.model.BedrockPart;
import com.github.tartaricacid.touhoulittlemaid.api.animation.IAnimation;
import com.github.tartaricacid.touhoulittlemaid.api.event.client.AddMaidLayerEvent;
import com.github.tartaricacid.touhoulittlemaid.api.event.client.DefaultGeckoAnimationEvent;
import com.github.tartaricacid.touhoulittlemaid.client.model.bedrock.EntityMaidModel;
import com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.EntityMaidRenderer;
import com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.state.EntityMaidRenderState;
import com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.state.ModelType;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.logos.dimension.LogosDimension;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.HumanoidArm;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.renderstate.RegisterRenderStateModifiersEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 车万女仆坐上星辰花琴凳、站到乐器工位上时的演奏姿势（docs/ORCHESTRA.md 第 16 节）。只在装了车万女仆时编译、注册
 * （{@code LogosDimension} 按名字找到 {@link #init}），用的都是女仆模组公开的接口：
 *
 * <ul>
 * <li><b>基岩版模型</b>（女仆模组自带的模型都是）：在渲染状态里给这只女仆的动画列表末尾加一段 {@link IAnimation}，
 *     在女仆模组自己的动画之后摆双臂、双腿、整个人的高度；手位就是玩家和其他生物用的那一份（{@link MusicianPoses.Musician}，
 *     「演奏者面朝北」的方块像素），这里换算到女仆模型的像素（模型按 {@code render_entity_scale} 缩放）。
 *     女仆的手臂和原版一样是直的、从肩上的支点垂下，所以沿用原版的转法（{@link HandheldPoses#reach}）。
 *     手持乐器、琴弓、琴槌由 {@link AddMaidLayerEvent.Legacy} 加的一层画出来。</li>
 * <li><b>GeckoLib 模型</b>：骨骼由女仆模组在别的线程里算好，没有公开的办法在那之后改。这里只用
 *     {@link DefaultGeckoAnimationEvent} 加三段默认动作（按骑着的实体：{@code vehicle#logos_dimension:bench_seat} 琴凳上弹、
 *     {@code instrument_seat} 坐着拉大提琴、{@code standing_post} 站着拉、敲；按同名的实体标签），手不跟着音走；模型包自己写了同名动作时用模型包的。</li>
 * </ul>
 */
public final class MaidMusicians {
    /** 基岩版女仆模型手臂从肩上的支点到手心（模型像素）：模板的手臂长 8。 */
    private static final float GRIP = 7;
    /** 大腿一半的粗（模型像素）：模板的腿 3 宽。 */
    private static final float THIGH_HALF = 1.5f;

    /** 测试用（{@link MaidHallViews}）：基岩版模型的演奏姿势摆过几次。 */
    static int posed;

    private MaidMusicians() {}

    /** 测试用：一只女仆，model 为 null 时是默认模型。 */
    static net.minecraft.world.entity.Mob testMaid(net.minecraft.server.level.ServerLevel level, @Nullable String model) {
        EntityMaid maid = EntityMaid.TYPE.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        if (maid == null) throw new IllegalStateException("cannot create a maid");
        if (model != null) maid.setModelId(model);
        return maid;
    }

    public static void init(IEventBus modBus) {
        modBus.addListener(MaidMusicians::modifiers);
        NeoForge.EVENT_BUS.addListener(MaidMusicians::legacyLayer);
        NeoForge.EVENT_BUS.addListener(MaidMusicians::geckoClips);
    }

    private static void modifiers(RegisterRenderStateModifiersEvent event) {
        event.<EntityMaid, EntityMaidRenderState>registerEntityModifier(EntityMaidRenderer.class, MaidMusicians::prepare);
    }

    /** 女仆模组提取完渲染状态之后：记下演奏者，基岩版模型在动画列表末尾加上 {@link #POSE}，手里原本的东西不画。 */
    private static void prepare(EntityMaid maid, EntityMaidRenderState state) {
        if (state.modelType != ModelType.SIMPLE_BEDROCK) {
            // GeckoLib 模型的姿势是默认动作（见类说明），手里的东西照女仆模组的画
            state.setRenderData(MusicianPoses.MUSICIAN, null);
            state.setRenderData(HandheldPoses.HELD, null);
            return;
        }
        HandheldPoses.Held held = MusicianPoses.prepare(maid, state);
        state.setRenderData(HandheldPoses.HELD, held);
        if (state.getRenderData(MusicianPoses.MUSICIAN) == null) return;
        List<IAnimation<EntityMaidRenderState>> animations = new ArrayList<>(state.animations);
        animations.add(POSE);
        state.animations = animations;
    }

    private static void legacyLayer(AddMaidLayerEvent.Legacy event) {
        event.getRenderer().addLayer(new Layer(event.getRenderer()));
    }

    private static void geckoClips(DefaultGeckoAnimationEvent event) {
        event.addAnimation(DefaultGeckoAnimationEvent.AnimationType.MAID, LogosDimension.id("animation/maid_musician.animation.json"));
    }

    // —— 基岩版模型 ——

    /** 女仆模型里的换算：模型像素（y 向下、脚在 +24）按 scale 缩放到方块像素，整个人（根骨骼）抬高 lift 方块像素。 */
    private record Frame(float scale, float lift, Vector3f feet) {
        /** 演奏者坐标（方块像素）→ 根骨骼里的模型像素（女仆的手臂、腿、头都直接挂在根上）。 */
        Vector3f toRoot(Vector3f block) {
            return new Vector3f(-(block.x - feet.x) / scale, 24 - (block.y - feet.y - lift) / scale, (block.z - feet.z) / scale);
        }

        Vector3f fromRoot(Vector3f model) {
            return new Vector3f(feet.x - model.x * scale, feet.y + lift + (24 - model.y) * scale, feet.z + model.z * scale);
        }

        /** 方块像素 → 根骨骼的模型像素（画琴弓用）。 */
        Matrix4f toRootMatrix() {
            return new Matrix4f().translate(0, 24, 0).scale(-1 / scale, -1 / scale, 1 / scale).translate(-feet.x, -feet.y - lift, -feet.z);
        }
    }

    private static @Nullable Frame frame(EntityMaidRenderState state, MusicianPoses.Musician m, HashMap<String, BedrockPart> models) {
        float scale = state.modelInfo == null ? 1 : state.modelInfo.getRenderEntityScale();
        if (scale <= 0) return null;
        float lift = 0;
        BedrockPart leg = models.get("legRight");
        if (!m.standing() && leg != null) {
            // 坐着：大腿压在凳面上（髋 = 凳面 + 半条大腿粗）；站着：脚落在地上
            float hip = (24 - leg.getInitialPose().y()) * scale;
            lift = m.seat() + THIGH_HALF * scale - hip;
        }
        return new Frame(scale, lift, m.feet());
    }

    /** 加在女仆模组的动画之后：根骨骼的高度、双臂、双腿。 */
    private static final IAnimation<EntityMaidRenderState> POSE = (state, models) -> {
        MusicianPoses.Musician m = state.getRenderData(MusicianPoses.MUSICIAN);
        if (m == null) return;
        Frame f = frame(state, m, models);
        BedrockPart root = IAnimation.root(models);
        if (f == null || root == null) return;
        posed++;
        // 女仆模组坐下时把整个人往下挪 0.3 格（为它自己的坐垫），这里换成琴凳、工位的高度
        root.offsetX = 0;
        root.offsetY = -f.lift() / (16 * f.scale());
        root.offsetZ = 0;
        root.xRot = root.yRot = root.zRot = 0;
        BedrockPart left = models.get("armLeft"), right = models.get("armRight");
        switch (m.mode()) {
            case HANDHELD -> handheld(state, m, models, left, right);
            case BOWED -> {
                HeldRig.Place p = bowed(m, f, left, right, state.ageInTicks);
                if (p != null) {
                    reach(right, f.toRoot(p.mainHand()), -1);
                    reach(left, f.toRoot(p.offHand()), 1);
                }
            }
            case MALLETS -> {
                mallet(left, m.left() == null ? null : aim(f.toRoot(m.left())), 1);
                mallet(right, m.right() == null ? null : aim(f.toRoot(m.right())), -1);
            }
            default -> {
                if (m.left() != null) reach(left, f.toRoot(m.left()), 1);
                if (m.right() != null) reach(right, f.toRoot(m.right()), -1);
            }
        }
        legs(m, models.get("legLeft"), models.get("legRight"));
    };

    /** 手持乐器：身体、头的坐标系换到女仆模型上（脖子在头的支点），乐器和双手的位置同玩家（{@link HeldRig#place}）。 */
    private static void handheld(EntityMaidRenderState state, MusicianPoses.Musician m, HashMap<String, BedrockPart> models,
                                 @Nullable BedrockPart left, @Nullable BedrockPart right) {
        HeldRig.Place p = handheldPlace(state, m, models);
        if (p == null) return;
        boolean leftHanded = state.mainArm == HumanoidArm.LEFT;
        reach(leftHanded ? left : right, p.mainHand(), leftHanded ? 1 : -1);
        reach(leftHanded ? right : left, p.offHand(), leftHanded ? -1 : 1);
    }

    private static HeldRig.@Nullable Place handheldPlace(EntityMaidRenderState state, MusicianPoses.Musician m, HashMap<String, BedrockPart> models) {
        BedrockPart head = models.get("head");
        if (head == null) return null;
        BedrockPart body = models.get("body");
        Matrix4f bodyFrame = new Matrix4f().translate(head.x, head.y, head.z);
        if (body != null) bodyFrame.rotateZYX(body.zRot, body.yRot, body.xRot);
        Matrix4f headFrame = new Matrix4f().translate(head.x, head.y, head.z).rotateZYX(head.zRot, head.yRot, head.xRot);
        return HeldRig.place(m.kind(), bodyFrame, headFrame, state.mainArm == HumanoidArm.LEFT, state.ageInTicks, m.move());
    }

    /** 大提琴、低音提琴：肩的位置换到方块像素，琴弓和两只手的位置同玩家（{@link HeldRig#seatedBlock}）。 */
    private static HeldRig.@Nullable Place bowed(MusicianPoses.Musician m, Frame f, @Nullable BedrockPart left, @Nullable BedrockPart right, float t) {
        if (left == null || right == null) return null;
        Vector3f rs = f.fromRoot(new Vector3f(right.x - 1, right.y, right.z)), ls = f.fromRoot(new Vector3f(left.x + 1, left.y, left.z));
        return HeldRig.seatedBlock(m.kind(), rs, ls, GRIP * f.scale(), t, m.move());
    }

    /** 同 {@link MusicianPoses#malletAim}，按女仆的手臂长缩小：手臂指向槌头上方往回一点，槌从手里斜着落下。 */
    private static Vector3f aim(Vector3f head) {
        float k = GRIP / 9;
        return new Vector3f(head.x, head.y - 5 * k, head.z + 3 * k);
    }

    private static void mallet(@Nullable BedrockPart arm, @Nullable Vector3f aim, float side) {
        if (arm == null) return;
        if (aim != null) {
            reach(arm, aim, side);
        } else {
            // 不拿槌的手垂在身旁
            arm.xRot = 0;
            arm.yRot = 0;
            arm.zRot = -0.08f * side;
        }
    }

    private static void reach(@Nullable ModelPart arm, Vector3f hand, float side) {
        if (arm != null) HandheldPoses.reach(arm, hand, 0, 0, side);
    }

    /** 腿：站着伸直；大提琴两膝分开夹着琴；键盘、竖琴、定音鼓前往前下方斜伸（平伸会插进乐器）；手持乐器照女仆模组的坐姿。 */
    private static void legs(MusicianPoses.Musician m, @Nullable BedrockPart left, @Nullable BedrockPart right) {
        if (left == null || right == null) return;
        if (m.standing()) {
            set(left, 0, -0.08f, 0);
            set(right, 0, 0.08f, 0);
        } else if (m.mode() == MusicianPoses.Mode.BOWED) {
            set(left, -1.35f, -0.5f, 0);
            set(right, -1.35f, 0.42f, 0);
        } else if (m.mode() != MusicianPoses.Mode.HANDHELD) {
            set(left, -1.05f, -0.12f, 0);
            set(right, -1.05f, 0.12f, 0);
        }
    }

    private static void set(ModelPart part, float x, float y, float z) {
        part.xRot = x;
        part.yRot = y;
        part.zRot = z;
    }

    /** 手持乐器、琴弓、琴槌：画在根骨骼的像素里（女仆模组的手持物品那一层同样先走一遍根骨骼）。 */
    private static final class Layer extends RenderLayer<EntityMaidRenderState, EntityMaidModel> {
        Layer(EntityMaidRenderer renderer) {
            super(renderer);
        }

        @Override
        public void submit(PoseStack pose, SubmitNodeCollector collector, int light, EntityMaidRenderState state, float yRot, float xRot) {
            HandheldPoses.Held held = state.getRenderData(HandheldPoses.HELD);
            MusicianPoses.Musician m = state.getRenderData(MusicianPoses.MUSICIAN);
            if (held == null || m == null || state.isInvisible || state.modelType != ModelType.SIMPLE_BEDROCK) return;
            EntityMaidModel model = getParentModel();
            HashMap<String, BedrockPart> parts = new HashMap<>();
            for (String name : new String[]{"armLeft", "armRight", "legRight", "head", "body"}) {
                if (model.getPart(name) != SimpleBedrockParts.EMPTY) parts.put(name, model.getPart(name));
            }
            Frame f = frame(state, m, parts);
            if (f == null) return;
            switch (m.mode()) {
                case HANDHELD -> {
                    HeldRig.Place p = handheldPlace(state, m, parts);
                    if (p == null) return;
                    if (held.instrument() != null && p.instrument() != null) draw(pose, collector, light, state, model, p.instrument(), held.instrument());
                    if (held.bow() != null && p.bow() != null) draw(pose, collector, light, state, model, p.bow(), held.bow());
                }
                case BOWED -> {
                    HeldRig.Place p = bowed(m, f, parts.get("armLeft"), parts.get("armRight"), state.ageInTicks);
                    if (p != null && p.bow() != null && held.bow() != null) {
                        draw(pose, collector, light, state, model, f.toRootMatrix().mul(p.bow()), held.bow());
                    }
                }
                case MALLETS -> {
                    if (held.bow() == null) return;
                    for (boolean left : new boolean[]{true, false}) {
                        Vector3f target = left ? m.left() : m.right();
                        BedrockPart arm = parts.get(left ? "armLeft" : "armRight");
                        if (target == null || arm == null) continue;
                        Vector3f t = f.toRoot(target);
                        Vector3f shoulder = new Vector3f(arm.x + (left ? 1 : -1), arm.y, arm.z);
                        Vector3f hand = aim(t).sub(shoulder).normalize().mul(GRIP).add(shoulder);
                        draw(pose, collector, light, state, model,
                            HeldRig.along(hand, t, StationPoses.toolGrip(m.kind()), StationPoses.toolHead(m.kind())), held.bow());
                    }
                }
                default -> { }
            }
        }

        private static void draw(PoseStack pose, SubmitNodeCollector collector, int light, EntityMaidRenderState state, EntityMaidModel model,
                                 Matrix4f pixels, ItemStackRenderState item) {
            pose.pushPose();
            model.root().translateAndRotate(pose);
            pose.scale(1 / 16f, 1 / 16f, 1 / 16f);
            pose.mulPose(pixels);
            pose.scale(16, 16, 16);
            item.submit(pose, collector, light, OverlayTexture.NO_OVERLAY, state.outlineColor);
            pose.popPose();
        }
    }

    /** 女仆模组找不到部件时给的空部件。 */
    private static final class SimpleBedrockParts {
        static final BedrockPart EMPTY = com.github.tartaricacid.touhoulittlemaid.client.model.bedrock.SimpleBedrockEntityModel.EMPTY;
    }
}
