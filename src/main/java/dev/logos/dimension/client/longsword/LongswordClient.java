package dev.logos.dimension.client.longsword;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.item.LongswordItem;
import dev.logos.dimension.longsword.*;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.ClientAvatarEntity;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.HumanoidArm;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.client.renderstate.*;

/** 快照驱动本机与旁观者的同一动作，第一人称保留素材中的靠右根骨位移。 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class LongswordClient {
    private static final Map<UUID, LongswordPayload> STATES = new HashMap<>();
    public static final ContextKey<Frame> FRAME = new ContextKey<>(LogosDimension.id("longsword_frame"));
    private static int repeat;
    public record Frame(int variant, LongswordRig.Sample sample, net.minecraft.client.renderer.item.ItemStackRenderState effects) {}

    public static LongswordPayload pose(UUID player) { return STATES.get(player); }
    @SubscribeEvent static void packets(RegisterClientPayloadHandlersEvent event) {
        event.register(LongswordPayload.TYPE, (p, ctx) -> {
            if (p.phase() == LongswordPhase.CLEAR) STATES.remove(p.player()); else STATES.put(p.player(), p);
        });
    }
    @SubscribeEvent static void logout(ClientPlayerNetworkEvent.LoggingOut event) { STATES.clear(); repeat = 0; }
    @SubscribeEvent static void reload(AddClientReloadListenersEvent event) {
        event.addListener(LogosDimension.id("longsword_rigs"),
                (net.minecraft.server.packs.resources.ResourceManagerReloadListener) manager -> LongswordRig.clear());
    }
    @SubscribeEvent static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) { STATES.clear(); return; }
        if (mc.screen == null && mc.options.keyAttack.isDown() && !mc.player.isUsingItem() && LongswordItem.variant(mc.player.getMainHandItem()) >= 0) {
            if (repeat-- <= 0) { ClientPacketDistributor.sendToServer(new LongswordPayload.Attack()); repeat = 2; }
        } else repeat = 0;
    }
    @SubscribeEvent static void input(InputEvent.InteractionKeyMappingTriggered event) {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;
        var pose=STATES.get(mc.player.getUUID());
        if (pose!=null && pose.phase()==LongswordPhase.SHEATHE && (event.isAttack() || event.isUseItem())) {
            event.setCanceled(true); event.setSwingHand(false); return;
        }
        if (LongswordItem.variant(mc.player.getMainHandItem()) < 0) return;
        if (event.isUseItem() && (pose==null || pose.phase()!=LongswordPhase.IDLE)) {
            event.setCanceled(true); event.setSwingHand(false); return;
        }
        if (event.isAttack()) {
            event.setCanceled(true); event.setSwingHand(false);
            ClientPacketDistributor.sendToServer(new LongswordPayload.Attack());
        } else if (event.getHand() == InteractionHand.OFF_HAND) {
            event.setCanceled(true); event.setSwingHand(false);
        }
    }
    /** 骨架或动作取不到时为 null，此时保持原版渲染。 */
    private static LongswordRig.Sample sample(LongswordPayload p, boolean firstPerson, double now) {
        var rig = LongswordRig.get(p.variant(), firstPerson);
        if (rig == null) return null;
        double age = Math.max(0, now - p.started());
        return rig.sample(p.phase().clip(age), p.phase().seconds(age, p.rate(), p.reverse()));
    }
    @SubscribeEvent static void modifiers(RegisterRenderStateModifiersEvent event) {
        event.registerAvatarEntityModifier(new AvatarRenderStateModifier() {
            @Override public <T extends Avatar & ClientAvatarEntity> void accept(T avatar, AvatarRenderState state) {
                var p = STATES.get(avatar.getUUID());
                var level = Minecraft.getInstance().level;
                var sample = p == null || level == null || state.isSpectator ? null : sample(p, false, level.getGameTime() + state.partialTick);
                state.setRenderData(FRAME, sample == null ? null : new Frame(p.variant(), sample, state.getMainHandItemState()));
            }
        });
    }
    @SubscribeEvent static void layers(EntityRenderersEvent.AddLayers event) {
        for (var skin : event.getSkins()) {
            var renderer = event.getPlayerRenderer(skin);
            if (renderer != null) renderer.addLayer(new SwordLayer(renderer,event.getContext().getEquipmentRenderer()));
        }
    }
    @SubscribeEvent static void hand(RenderHandEvent event) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.player.isSpectator()) return;
        var p = STATES.get(mc.player.getUUID());
        if (p == null) return;
        var sample=sample(p,true,mc.level.getGameTime()+event.getPartialTick());
        if (sample == null) return;
        event.setCanceled(true);
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        var pose = event.getPoseStack(); pose.pushPose();
        pose.mulPose(Axis.XP.rotationDegrees(2));
        pose.scale(1/16f, 1/16f, 1/16f);
        pose.translate(0, -29, .6f);
        if (mc.player.getMainArm() == HumanoidArm.LEFT) pose.scale(-1,1,1);
        LongswordRig.get(p.variant(), true).submit(sample, pose,
                event.getSubmitNodeCollector(), event.getPackedLight(), mc.player.getSkin().body().texturePath(), !mc.player.isInvisible());
        LongswordAuras.firstPerson(mc.player,sample,pose,event.getSubmitNodeCollector());
        pose.popPose();
    }
    private static final class SwordLayer extends RenderLayer<AvatarRenderState, PlayerModel> {
        private final net.minecraft.client.renderer.entity.layers.EquipmentLayerRenderer equipment;
        private final LongswordArmorModel armor=new LongswordArmorModel();
        SwordLayer(RenderLayerParent<AvatarRenderState, PlayerModel> parent,net.minecraft.client.renderer.entity.layers.EquipmentLayerRenderer equipment) {
            super(parent);this.equipment=equipment;
        }
        @Override public void submit(PoseStack pose, SubmitNodeCollector collector, int light, AvatarRenderState state, float yRot, float xRot) {
            var frame = state.getRenderData(FRAME);
            if (frame == null) return;
            pose.pushPose();
            pose.mulPose(LongswordStance.base(state.isCrouching,1/16f));
            pose.translate(0, 1.5f, 0);
            pose.scale(state.mainArm == HumanoidArm.LEFT ? 1/16f : -1/16f, -1/16f, 1/16f);
            LongswordRig.get(frame.variant, false).submit(frame.sample, pose, collector, light, state.skin.body().texturePath(), !state.isInvisible);
            LongswordAuras.submit(frame.effects,frame.sample,pose,collector);
            pose.popPose();
            var equippable=state.chestEquipment.get(net.minecraft.core.component.DataComponents.EQUIPPABLE);
            if(equippable!=null && equippable.slot()==net.minecraft.world.entity.EquipmentSlot.CHEST && equippable.assetId().isPresent()) {
                pose.pushPose();
                pose.mulPose(LongswordStance.base(state.isCrouching,1/16f));
                if(state.mainArm==HumanoidArm.LEFT)pose.scale(-1,1,1);
                equipment.renderLayers(net.minecraft.client.resources.model.EquipmentClientInfo.LayerType.HUMANOID,
                        equippable.assetId().get(),armor,frame,state.chestEquipment,pose,collector,light,state.outlineColor);
                pose.popPose();
            }
        }
    }
}
