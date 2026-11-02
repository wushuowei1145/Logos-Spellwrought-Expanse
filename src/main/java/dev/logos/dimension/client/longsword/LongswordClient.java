package dev.logos.dimension.client.longsword;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.combat.CombatPose;
import dev.logos.dimension.combat.Moveset;
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
    /** 上一个招式，用于进入新招式时从它在切换那一刻的姿势过渡。 */
    private static final Map<UUID, LongswordPayload> PREVIOUS = new HashMap<>();
    public static final ContextKey<Frame> FRAME = new ContextKey<>(LogosDimension.id("longsword_frame"));
    private static int repeat;
    public record Frame(int variant, LongswordRig.Sample sample, net.minecraft.client.renderer.item.ItemStackRenderState effects) {}

    public static LongswordPayload pose(UUID player) { return STATES.get(player); }
    @SubscribeEvent static void packets(RegisterClientPayloadHandlersEvent event) {
        event.register(LongswordPayload.TYPE, (p, ctx) -> {
            if (p.cleared()) { STATES.remove(p.player()); PREVIOUS.remove(p.player()); return; }
            var old = STATES.put(p.player(), p);
            // 开始追踪时会重发当前招式，那不是换招，不覆盖过渡来源。
            if (old != null && !(old.move().equals(p.move()) && old.started() == p.started())) PREVIOUS.put(p.player(), old);
        });
    }
    @SubscribeEvent static void logout(ClientPlayerNetworkEvent.LoggingOut event) { STATES.clear(); PREVIOUS.clear(); repeat = 0; }
    @SubscribeEvent static void reload(AddClientReloadListenersEvent event) {
        event.addListener(LogosDimension.id("longsword_rigs"),
                (net.minecraft.server.packs.resources.ResourceManagerReloadListener) manager -> LongswordRig.clear());
    }
    @SubscribeEvent static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) { STATES.clear(); PREVIOUS.clear(); return; }
        if (mc.screen == null && mc.options.keyAttack.isDown() && !mc.player.isUsingItem() && LongswordItem.variant(mc.player.getMainHandItem()) >= 0
                && STATES.containsKey(mc.player.getUUID())) {
            if (repeat-- <= 0) { ClientPacketDistributor.sendToServer(new LongswordPayload.Attack()); repeat = 2; }
        } else repeat = 0;
    }
    @SubscribeEvent static void input(InputEvent.InteractionKeyMappingTriggered event) {
        var mc = Minecraft.getInstance();
        if (mc.player == null) return;
        var pose=STATES.get(mc.player.getUUID());
        if (pose!=null && pose.sheathing() && (event.isAttack() || event.isUseItem())) {
            event.setCanceled(true); event.setSwingHand(false); return;
        }
        if (LongswordItem.variant(mc.player.getMainHandItem()) < 0) return;
        if (event.isUseItem() && (pose==null || !pose.acceptsUse())) {
            event.setCanceled(true); event.setSwingHand(false); return;
        }
        // 服务端还没回报持剑动作（例如招式数据缺失）时不拦截左键，长剑按原版物品攻击。
        if (pose == null) return;
        if (event.isAttack()) {
            event.setCanceled(true); event.setSwingHand(false);
            // 按下这一刻已经发过；按住时每 3 刻再发一次，单击不会因为同一刻补发而被缓冲成两刀。
            ClientPacketDistributor.sendToServer(new LongswordPayload.Attack()); repeat = 2;
        } else if (event.getHand() == InteractionHand.OFF_HAND) {
            event.setCanceled(true); event.setSwingHand(false);
        }
    }
    /** 骨架或动作取不到时为 null，此时保持原版渲染。 */
    private static LongswordRig.Sample sample(LongswordPayload p, boolean firstPerson, double now) {
        var rig = LongswordRig.get(p.variant(), firstPerson);
        if (rig == null) return null;
        var pose = pose(rig, p, now);
        if (pose == null) return null;
        // 进入新招式的前 blend 秒，从上一招式在切换那一刻的姿势平滑过渡过来。
        var previous = PREVIOUS.get(p.player());
        double blendTicks = p.blend() * 20, age = now - p.started();
        if (previous != null && blendTicks > 0 && age < blendTicks) {
            var from = pose(rig, previous, p.started());
            if (from != null) {
                float w = (float) Math.clamp(age / blendTicks, 0, 1);
                pose = from.blend(pose, w * w * (3 - 2 * w));
            }
        }
        return rig.sample(pose);
    }
    private static CombatPose pose(LongswordRig rig, LongswordPayload p, double time) {
        double length = rig.length(p.clip());
        if (length < 0) return null;
        return rig.pose(p.clip(), (float) Moveset.playbackSeconds(p.playback(), length, time - p.started(), p.speed(), p.offset()));
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
