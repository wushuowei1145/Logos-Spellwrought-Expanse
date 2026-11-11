package dev.logos.dimension.client.music;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.music.HandheldInstrumentItem;
import dev.logos.dimension.music.InstrumentKind;
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
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class HandheldPoses {
    public static final EnumProxy<HumanoidModel.ArmPose> VIOLIN = new EnumProxy<>(HumanoidModel.ArmPose.class,
        true, true, (IArmPoseTransformer) (m, s, a) -> pose(InstrumentKind.VIOLIN, m, s));
    public static final EnumProxy<HumanoidModel.ArmPose> FLUTE = new EnumProxy<>(HumanoidModel.ArmPose.class,
        true, true, (IArmPoseTransformer) (m, s, a) -> pose(InstrumentKind.FLUTE, m, s));
    public static final EnumProxy<HumanoidModel.ArmPose> TRUMPET = new EnumProxy<>(HumanoidModel.ArmPose.class,
        true, true, (IArmPoseTransformer) (m, s, a) -> pose(InstrumentKind.TRUMPET, m, s));

    /** 正在演奏的乐器：种类、要画的模型（小提琴另有弓）、最近是否在弹。 */
    record Held(InstrumentKind kind, ItemStackRenderState instrument, @Nullable ItemStackRenderState bow, float move) {}

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
                return switch (item.kind()) {
                    case VIOLIN -> VIOLIN.getValue();
                    case FLUTE -> FLUTE.getValue();
                    default -> TRUMPET.getValue();
                };
            }
        };
        event.registerItem(ext, Instruments.VIOLIN.get(), Instruments.FLUTE.get(), Instruments.TRUMPET.get());
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
        if (!entity.isUsingItem() || !(entity.getUseItem().getItem() instanceof HandheldInstrumentItem item)) return null;
        // 乐器由下面的层画在下巴、嘴边；两只手原本拿的（包括乐器本身）都不画
        state.rightHandItemState.clear();
        state.leftHandItemState.clear();
        var resolver = Minecraft.getInstance().getItemModelResolver();
        ItemStackRenderState body = new ItemStackRenderState();
        resolver.updateForLiving(body, body(item.kind()), ItemDisplayContext.NONE, entity);
        ItemStackRenderState bowState = null;
        if (item.kind() == InstrumentKind.VIOLIN) {
            bowState = new ItemStackRenderState();
            resolver.updateForLiving(bowState, bow(), ItemDisplayContext.NONE, entity);
        }
        return new Held(item.kind(), body, bowState, MusicClient.recentlyPlayed(entity.getId()) ? 1 : 0);
    }

    private static ItemStack body(InstrumentKind kind) {
        return BODIES.computeIfAbsent(kind, k -> {
            ItemStack stack = new ItemStack(switch (k) {
                case VIOLIN -> Instruments.VIOLIN.get();
                case FLUTE -> Instruments.FLUTE.get();
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
    private static void pose(InstrumentKind kind, HumanoidModel<?> model, HumanoidRenderState state) {
        int id = state instanceof AvatarRenderState avatar ? avatar.id : -1;
        float move = MusicClient.recentlyPlayed(id) ? 1f : 0f;
        boolean leftHanded = state.mainArm == HumanoidArm.LEFT;
        HeldRig.Place place = HeldRig.place(kind, model, state.isCrouching, true, leftHanded, state.ageInTicks, move);
        // 蹲下时原版在这之后把肩下移 3.2、手臂再往前抬 0.4，这里先扣掉
        float drop = state.isCrouching ? 3.2f : 0, lift = state.isCrouching ? 0.4f : 0;
        reach(leftHanded ? model.leftArm : model.rightArm, place.mainHand(), drop, lift, leftHanded ? 1 : -1);
        reach(leftHanded ? model.rightArm : model.leftArm, place.offHand(), drop, lift, leftHanded ? -1 : 1);
    }

    /**
     * 手臂指向手位。原版手臂沿自身 +Y 垂下，先绕 X 再绕 Y 转（ModelPart 按 ZYX 合成），所以方向
     * (sin x·sin y, cos x, sin x·cos y) 反解得 x、y。手臂中线在支点外侧 1 像素（side）。
     */
    private static void reach(ModelPart arm, Vector3f hand, float drop, float lift, float side) {
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
            HeldRig.Place place = HeldRig.place(held.kind(), model, state.isCrouching, false,
                state.mainArm == HumanoidArm.LEFT, state.ageInTicks, held.move());
            draw(pose, collector, light, state, model, place.instrument(), held.instrument());
            if (held.bow() != null && place.bow() != null) draw(pose, collector, light, state, model, place.bow(), held.bow());
        }

        private static void draw(PoseStack pose, SubmitNodeCollector collector, int light, AvatarRenderState state, PlayerModel model,
                                 Matrix4f pixels, ItemStackRenderState item) {
            pose.pushPose();
            model.root().translateAndRotate(pose);
            // HeldRig 用像素；物品模型以方块为单位、已居中
            pose.scale(1 / 16f, 1 / 16f, 1 / 16f);
            pose.mulPose(pixels);
            pose.scale(16, 16, 16);
            item.submit(pose, collector, light, OverlayTexture.NO_OVERLAY, state.outlineColor);
            pose.popPose();
        }
    }
}
