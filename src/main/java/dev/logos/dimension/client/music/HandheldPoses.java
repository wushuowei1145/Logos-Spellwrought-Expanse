package dev.logos.dimension.client.music;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.music.HandheldInstrumentItem;
import dev.logos.dimension.music.InstrumentKind;
import dev.logos.dimension.music.InstrumentSeat;
import dev.logos.dimension.music.Instruments;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.ClientAvatarEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.component.DataComponents;
import net.minecraft.util.Mth;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.asm.enumextension.EnumProxy;
import net.neoforged.neoforge.client.IArmPoseTransformer;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.client.renderstate.AvatarRenderStateModifier;
import net.neoforged.neoforge.client.renderstate.RegisterRenderStateModifiersEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 手持乐器的演奏姿势：只在「使用中」（举起来演奏）时接管双臂和乐器的画法，平时完全原版（docs/COMBAT_SKELETON.md 第 1 节）。
 * 「使用中」本来就会同步给附近的人，所以别人那里也看得到，不用另发包。
 * 乐器不再挂在手上：小提琴夹在下巴下（跟着身体），长笛、小号在嘴边（跟着头），由这里的玩家层按 {@link HeldRig}
 * 画出来（items/held/），双臂再伸向乐器上的手位——小提琴是经典的样子，非惯用手托琴、惯用手运弓。两只手里原本拿的东西这时不画。
 * 最近 0.6 秒有音时弓来回拉、管乐器轻轻起伏，不弹时静止。姿势经 META-INF/enumextensions.json 加进 {@link HumanoidModel.ArmPose}。
 *
 * <p>坐在大提琴、低音提琴后面（骑着 {@link InstrumentSeat}）时也在这里：乐器是方块、不画，只画琴弓；
 * 双腿分开夹着大提琴（低音提琴站着拉，腿伸直），左手按琴颈、右手运弓，那件乐器最近在响时弓来回拉（纸带、红石放的也算）。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class HandheldPoses {
    public static final EnumProxy<HumanoidModel.ArmPose> VIOLIN = new EnumProxy<>(HumanoidModel.ArmPose.class,
        true, true, (IArmPoseTransformer) (m, s, a) -> pose(InstrumentKind.VIOLIN, m, s));
    public static final EnumProxy<HumanoidModel.ArmPose> FLUTE = new EnumProxy<>(HumanoidModel.ArmPose.class,
        true, true, (IArmPoseTransformer) (m, s, a) -> pose(InstrumentKind.FLUTE, m, s));
    public static final EnumProxy<HumanoidModel.ArmPose> TRUMPET = new EnumProxy<>(HumanoidModel.ArmPose.class,
        true, true, (IArmPoseTransformer) (m, s, a) -> pose(InstrumentKind.TRUMPET, m, s));
    public static final EnumProxy<HumanoidModel.ArmPose> VIOLA = new EnumProxy<>(HumanoidModel.ArmPose.class,
        true, true, (IArmPoseTransformer) (m, s, a) -> pose(InstrumentKind.VIOLA, m, s));
    public static final EnumProxy<HumanoidModel.ArmPose> OBOE = new EnumProxy<>(HumanoidModel.ArmPose.class,
        true, true, (IArmPoseTransformer) (m, s, a) -> pose(InstrumentKind.OBOE, m, s));
    public static final EnumProxy<HumanoidModel.ArmPose> BASSOON = new EnumProxy<>(HumanoidModel.ArmPose.class,
        true, true, (IArmPoseTransformer) (m, s, a) -> pose(InstrumentKind.BASSOON, m, s));
    public static final EnumProxy<HumanoidModel.ArmPose> CELLO = new EnumProxy<>(HumanoidModel.ArmPose.class,
        true, true, (IArmPoseTransformer) (m, s, a) -> pose(InstrumentKind.CELLO, m, s));
    public static final EnumProxy<HumanoidModel.ArmPose> DOUBLE_BASS = new EnumProxy<>(HumanoidModel.ArmPose.class,
        true, true, (IArmPoseTransformer) (m, s, a) -> pose(InstrumentKind.DOUBLE_BASS, m, s));

    private static HumanoidModel.ArmPose poseOf(InstrumentKind kind) {
        return switch (kind) {
            case VIOLIN -> VIOLIN.getValue();
            case FLUTE -> FLUTE.getValue();
            case VIOLA -> VIOLA.getValue();
            case OBOE -> OBOE.getValue();
            case BASSOON -> BASSOON.getValue();
            case CELLO -> CELLO.getValue();
            case DOUBLE_BASS -> DOUBLE_BASS.getValue();
            default -> TRUMPET.getValue();
        };
    }

    /** 正在演奏的乐器：种类、要画的模型（弓弦乐器另有弓；坐着拉的乐器是方块，instrument 为 null）、最近是否在弹。 */
    /** bow：弓弦乐器的弓；工位上敲的乐器借这一格放琴槌（{@link StationPoses}）。 */
    record Held(InstrumentKind kind, @Nullable ItemStackRenderState instrument, @Nullable ItemStackRenderState bow, float move) {}

    static final ContextKey<Held> HELD = new ContextKey<>(LogosDimension.id("held_instrument"));
    /** 画乐器用的物品：模型换成 items/held/<乐器>.json（小提琴不带弓）和 items/held/violin_bow.json。 */
    private static final Map<InstrumentKind, ItemStack> BODIES = new EnumMap<>(InstrumentKind.class);
    private static @Nullable ItemStack bow;

    private HandheldPoses() {}

    @SubscribeEvent
    static void extensions(RegisterClientExtensionsEvent event) {
        IClientItemExtensions ext = new IClientItemExtensions() {
            @Override
            public HumanoidModel.@Nullable ArmPose getArmPose(LivingEntity entity, InteractionHand hand, ItemStack stack) {
                if (!entity.isUsingItem() || entity.getUsedItemHand() != hand) return null;
                if (!(stack.getItem() instanceof HandheldInstrumentItem item)) return null;
                return poseOf(item.kind());
            }
        };
        event.registerItem(ext, Instruments.VIOLIN.get(), Instruments.FLUTE.get(), Instruments.TRUMPET.get(),
            Instruments.VIOLA.get(), Instruments.OBOE.get(), Instruments.BASSOON.get());
    }

    @SubscribeEvent
    static void renderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(Instruments.SEAT.get(), net.minecraft.client.renderer.entity.NoopRenderer::new);
        event.registerEntityRenderer(dev.logos.dimension.music.Benches.SEAT.get(), net.minecraft.client.renderer.entity.NoopRenderer::new);
    }

    @SubscribeEvent
    static void modifiers(RegisterRenderStateModifiersEvent event) {
        event.registerAvatarEntityModifier(new AvatarRenderStateModifier() {
            @Override
            public <T extends Avatar & ClientAvatarEntity> void accept(T avatar, AvatarRenderState state) {
                state.setRenderData(HELD, held(avatar, state));
            }
        });
    }

    @SubscribeEvent
    static void layers(EntityRenderersEvent.AddLayers event) {
        for (var skin : event.getSkins()) {
            var renderer = event.getPlayerRenderer(skin);
            if (renderer != null) renderer.addLayer(new Layer(renderer));
        }
    }

    private static @Nullable Held held(LivingEntity entity, AvatarRenderState state) {
        // 坐在星辰花琴凳上：手持乐器、键盘、竖琴都按琴凳那一套来（同原版人形生物、附属 NPC）
        if (entity.getVehicle() instanceof dev.logos.dimension.music.BenchSeat) return MusicianPoses.prepare(entity, state);
        state.setRenderData(MusicianPoses.MUSICIAN, null);
        if (entity.getVehicle() instanceof InstrumentSeat seat && seat.kind() != null) return seated(entity, state, seat);
        if (!entity.isUsingItem() || !(entity.getUseItem().getItem() instanceof HandheldInstrumentItem item)) return null;
        // 乐器由下面的层画在下巴、嘴边；两只手原本拿的（包括乐器本身）都不画
        state.rightHandItemState.clear();
        state.leftHandItemState.clear();
        boolean playing = MusicClient.recentlyPlayed(entity.getId()) || VoiceBank.holding(MusicClient.heldKey(entity.getId()));
        return heldFor(item.kind(), entity, playing ? 1 : 0);
    }

    /** 要画的乐器（和弓）的物品状态：模型换成 items/held/ 下拿在手上演奏的那一份。 */
    static Held heldFor(InstrumentKind kind, LivingEntity entity, float move) {
        var resolver = Minecraft.getInstance().getItemModelResolver();
        ItemStackRenderState body = new ItemStackRenderState();
        resolver.updateForLiving(body, body(kind), ItemDisplayContext.NONE, entity);
        ItemStackRenderState bowState = null;
        if (kind == InstrumentKind.VIOLIN || kind == InstrumentKind.VIOLA) {
            bowState = new ItemStackRenderState();
            resolver.updateForLiving(bowState, bow(), ItemDisplayContext.NONE, entity);
        }
        return new Held(kind, body, bowState, move);
    }

    /** 坐（站）在大提琴、低音提琴后：只有弓要画（乐器是方块）。 */
    static Held bowFor(InstrumentKind kind, LivingEntity entity, float move) {
        ItemStackRenderState bowState = new ItemStackRenderState();
        Minecraft.getInstance().getItemModelResolver().updateForLiving(bowState, bow(), ItemDisplayContext.NONE, entity);
        return new Held(kind, null, bowState, move);
    }

    /** 坐在弦乐器后面：乐器是方块，只画弓；手里原本拿的不画；姿势挂在右臂上（按右手运弓布置，左撇子也一样）。 */
    private static Held seated(LivingEntity entity, AvatarRenderState state, InstrumentSeat seat) {
        InstrumentKind kind = seat.kind();
        state.rightHandItemState.clear();
        state.leftHandItemState.clear();
        state.rightArmPose = poseOf(kind);
        state.leftArmPose = HumanoidModel.ArmPose.EMPTY;
        ItemStackRenderState bowState = new ItemStackRenderState();
        Minecraft.getInstance().getItemModelResolver().updateForLiving(bowState, bow(), ItemDisplayContext.NONE, entity);
        return new Held(kind, null, bowState, VoiceBank.recentlyPlayed(seat.instrument()) ? 1 : 0);
    }

    private static ItemStack body(InstrumentKind kind) {
        return BODIES.computeIfAbsent(kind, k -> {
            ItemStack stack = new ItemStack(switch (k) {
                case VIOLIN -> Instruments.VIOLIN.get();
                case FLUTE -> Instruments.FLUTE.get();
                case VIOLA -> Instruments.VIOLA.get();
                case OBOE -> Instruments.OBOE.get();
                case BASSOON -> Instruments.BASSOON.get();
                default -> Instruments.TRUMPET.get();
            });
            stack.set(DataComponents.ITEM_MODEL, LogosDimension.id("held/" + k.id()));
            return stack;
        });
    }

    private static ItemStack bow() {
        if (bow == null) {
            bow = new ItemStack(Instruments.VIOLIN.get());
            bow.set(DataComponents.ITEM_MODEL, LogosDimension.id("held/violin_bow"));
        }
        return bow;
    }

    /** 双臂伸向乐器上的两个手位（原版只为拿着乐器的那只手调用两手姿势，所以两只手臂都在这里摆，同原版拉弓）。 */
    static void pose(InstrumentKind kind, HumanoidModel<?> model, HumanoidRenderState state) {
        if (kind.seated()) {
            seatedPose(kind, model, state);
            return;
        }
        Held held = state.getRenderData(HELD);
        float move = held == null ? 0f : held.move();
        boolean leftHanded = state.mainArm == HumanoidArm.LEFT;
        HeldRig.Place place = HeldRig.place(kind, model, state.isCrouching, true, leftHanded, state.ageInTicks, move);
        // 蹲下时原版在这之后把肩下移 3.2、手臂再往前抬 0.4，这里先扣掉
        float drop = state.isCrouching ? 3.2f : 0, lift = state.isCrouching ? 0.4f : 0;
        reach(leftHanded ? model.leftArm : model.rightArm, place.mainHand(), drop, lift, leftHanded ? 1 : -1);
        reach(leftHanded ? model.rightArm : model.leftArm, place.offHand(), drop, lift, leftHanded ? -1 : 1);
    }

    /** 坐（站）在弦乐器后面：右手运弓、左手按琴颈；大提琴双腿分开夹着琴，低音提琴站着拉、腿伸直。 */
    private static void seatedPose(InstrumentKind kind, HumanoidModel<?> model, HumanoidRenderState state) {
        Held held = state.getRenderData(HELD);
        HeldRig.Place place = HeldRig.seated(kind, model, state.ageInTicks, held == null ? 0 : held.move());
        reach(model.rightArm, place.mainHand(), 0, 0, -1);
        reach(model.leftArm, place.offHand(), 0, 0, 1);
        if (kind == InstrumentKind.DOUBLE_BASS) {
            model.rightLeg.xRot = 0;
            model.leftLeg.xRot = 0;
            model.rightLeg.yRot = 0.1f;
            model.leftLeg.yRot = -0.1f;
        } else {
            // 琴往左倾，琴身偏在左膝一侧：右腿分得少一点，两膝都贴着琴
            model.rightLeg.xRot = -1.35f;
            model.leftLeg.xRot = -1.35f;
            model.rightLeg.yRot = 0.42f;
            model.leftLeg.yRot = -0.5f;
        }
        model.rightLeg.zRot = 0;
        model.leftLeg.zRot = 0;
    }

    /**
     * 手臂指向手位。原版手臂沿自身 +Y 垂下，先绕 X 再绕 Y 转（ModelPart 按 ZYX 合成），所以方向
     * (sin x·sin y, cos x, sin x·cos y) 反解得 x、y。手臂中线在支点外侧 1 像素（side）。
     */
    static void reach(ModelPart arm, Vector3f hand, float drop, float lift, float side) {
        float dx = hand.x - (arm.x + side), dy = hand.y - (arm.y + drop), dz = hand.z - arm.z;
        float len = Mth.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-3f) return;
        arm.xRot = -(float) Math.acos(Mth.clamp(dy / len, -1, 1)) - lift;
        arm.yRot = (float) Math.atan2(-dx, -dz);
        arm.zRot = 0;
    }

    /** 把乐器（和弓）画在 {@link HeldRig} 算好的位置上。 */
    private static final class Layer extends RenderLayer<AvatarRenderState, PlayerModel> {
        Layer(RenderLayerParent<AvatarRenderState, PlayerModel> parent) {
            super(parent);
        }

        @Override
        public void submit(PoseStack pose, SubmitNodeCollector collector, int light, AvatarRenderState state, float yRot, float xRot) {
            Held held = state.getRenderData(HELD);
            if (held == null || state.isInvisible) return;
            PlayerModel model = getParentModel();
            HeldRig.Place place = held.kind().seated() ? HeldRig.seated(held.kind(), model, state.ageInTicks, held.move())
                : HeldRig.place(held.kind(), model, state.isCrouching, false, state.mainArm == HumanoidArm.LEFT, state.ageInTicks, held.move());
            if (held.instrument() != null && place.instrument() != null) draw(pose, collector, light, state, model, place.instrument(), held.instrument());
            if (held.bow() != null && place.bow() != null) draw(pose, collector, light, state, model, place.bow(), held.bow());
        }

        private static void draw(PoseStack pose, SubmitNodeCollector collector, int light, AvatarRenderState state, PlayerModel model,
                                 Matrix4f pixels, ItemStackRenderState item) {
            drawOn(pose, collector, light, state.outlineColor, model, pixels, item);
        }
    }

    /** 把一件乐器（或弓）画在人形模型根坐标的 pixels 处（玩家和原版人形生物共用）。 */
    static void drawOn(PoseStack pose, SubmitNodeCollector collector, int light, int outline, HumanoidModel<?> model,
                       Matrix4f pixels, ItemStackRenderState item) {
        pose.pushPose();
        model.root().translateAndRotate(pose);
        // HeldRig 用像素；物品模型以方块为单位、已居中
        pose.scale(1 / 16f, 1 / 16f, 1 / 16f);
        pose.mulPose(pixels);
        pose.scale(16, 16, 16);
        item.submit(pose, collector, light, OverlayTexture.NO_OVERLAY, outline);
        pose.popPose();
    }
}
