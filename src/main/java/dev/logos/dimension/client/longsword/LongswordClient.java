package dev.logos.dimension.client.longsword;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.combat.CombatPose;
import dev.logos.dimension.combat.Moveset;
import dev.logos.dimension.combat.CombatWeapon;
import dev.logos.dimension.client.vfx.DawnvowBlade;
import dev.logos.dimension.client.vfx.DawnvowClient;
import dev.logos.dimension.client.vfx.DawnvowSummon;
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
    /**
     * {@code move} 与 {@code time} 是取样的招式与时刻（曦誓拔剑、收剑的光点要用）；施法剑另有 {@code tip}（武器网格里的剑尖，
     * 不是施法剑时为 null）与剑身铭文发光的强弱 {@code glow}、颜色 {@code glowColor}（见 {@link SwordCastClient}）。
     */
    public record Frame(int variant, LongswordRig.Sample sample, net.minecraft.client.renderer.item.ItemStackRenderState effects,
                        LongswordPayload move, double time, org.joml.Vector3f tip, float glow, int glowColor) {}

    public static LongswordPayload pose(UUID player) { return STATES.get(player); }
    @SubscribeEvent static void packets(RegisterClientPayloadHandlersEvent event) {
        event.register(LongswordPayload.TYPE, (p, ctx) -> {
            if (p.cleared()) { STATES.remove(p.player()); PREVIOUS.remove(p.player()); return; }
            var old = STATES.put(p.player(), p);
            // 开始追踪时会重发当前招式，那不是换招，不覆盖过渡来源。
            if (old != null && !(old.move().equals(p.move()) && old.started() == p.started())) PREVIOUS.put(p.player(), old);
        });
    }
    @SubscribeEvent static void logout(ClientPlayerNetworkEvent.LoggingOut event) { STATES.clear(); PREVIOUS.clear(); repeat = 0; RyukaClient.clear(); }
    @SubscribeEvent static void reload(AddClientReloadListenersEvent event) {
        event.addListener(LogosDimension.id("longsword_rigs"),
                (net.minecraft.server.packs.resources.ResourceManagerReloadListener) manager -> LongswordRig.clear());
    }
    /** 按住右键时原版会吞掉左键；当前招式接受左键（弹刀后接突刺）时先取走这次点击发给服务端。 */
    @SubscribeEvent static void attackWhileUsing(ClientTickEvent.Pre event) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null || !mc.player.isUsingItem() || CombatWeapon.id(mc.player.getMainHandItem()) < 0) return;
        var pose = STATES.get(mc.player.getUUID());
        if (pose == null || !pose.acceptsAttack()) return;
        while (mc.options.keyAttack.consumeClick()) { ClientPacketDistributor.sendToServer(new LongswordPayload.Attack()); repeat = 2; }
    }
    @SubscribeEvent static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) { STATES.clear(); PREVIOUS.clear(); return; }
        var own = STATES.get(mc.player.getUUID());
        if (mc.screen == null && mc.options.keyAttack.isDown() && own != null && (!mc.player.isUsingItem() || own.acceptsAttack())
                && CombatWeapon.id(mc.player.getMainHandItem()) >= 0) {
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
        if (CombatWeapon.id(mc.player.getMainHandItem()) < 0) return;
        if (event.isUseItem() && (pose==null || !pose.acceptsUse())) {
            event.setCanceled(true); event.setSwingHand(false); return;
        }
        // 服务端还没回报持剑动作（例如招式数据缺失）时不拦截左键，武器按原版物品攻击。
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
                double now = level == null ? 0 : level.getGameTime() + state.partialTick;
                var sample = p == null || level == null || state.isSpectator ? null : sample(p, false, now);
                if (RyukaClient.is(p)) RyukaClient.observe(avatar, now);
                Frame frame = null;
                if (sample != null) {
                    var rig = LongswordRig.get(p.variant(), false);
                    // 第三人称施法时剑尖跟着视线俯仰（第一人称的剑本来就跟着镜头）。
                    sample = rig.aim(sample, SwordCastClient.aim(p, now, state.xRot));
                    // 侧身拉弓时双臂和弓跟着视线俯仰（第一人称的弓本来就跟着镜头）；随转身淡入淡出。流火的回旋斩只是转一圈，不俯仰。
                    float sideways = net.minecraft.util.Mth.wrapDegrees(sample.turn());
                    if (sideways != 0 && !RyukaClient.is(p)) sample = rig.aimDraw(sample, -state.xRot * Math.min(1, Math.abs(sideways) / 90));
                    float glow = rig.glows() ? SwordCastClient.glow(avatar, p, now, state.partialTick) : 0;
                    frame = new Frame(p.variant(), sample, state.getMainHandItemState(), p, now,
                            SwordCastClient.tip(avatar.getMainHandItem()), glow, glow > 0 ? SwordCastClient.color(avatar) : 0);
                }
                // 转身按 ±180° 算：回旋斩转满一圈（360°）就是没转。
                if (frame != null && net.minecraft.util.Mth.wrapDegrees(frame.sample().turn()) != 0) {
                    float turn = net.minecraft.util.Mth.wrapDegrees(frame.sample().turn());
                    // 弓道侧身：原版身体（连同双腿和这层骨架）整个转过去，头保持原来的朝向，仍看准星。
                    // 动作里的转身是 Blockbench 绕 +Y 的角（-90 = 靶在左手一侧）。骨架这一层按 X、Y 取反画进原版模型，
                    // 原版又按 180 - bodyRot 转身体，所以原版身体朝向要加上 -turn；左手惯用时骨架只取反 Y，转向相反。
                    float yaw = -turn * (state.mainArm == HumanoidArm.LEFT ? -1 : 1);
                    // 原版身体停着时会落后视线至多 50°；转身随动作淡入，转满时身体以视线为准，箭始终指着准星。
                    float settle = Math.min(1, Math.abs(turn) / 90);
                    state.bodyRot += state.yRot * settle + yaw;
                    state.yRot -= state.yRot * settle + yaw;
                }
                state.setRenderData(FRAME, frame);
                state.setRenderData(DawnvowBlade.GLOW, DawnvowClient.blade(avatar.getId(), state.partialTick));
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
        double now=mc.level.getGameTime()+event.getPartialTick();
        var sample=sample(p,true,now);
        if (sample == null) return;
        event.setCanceled(true);
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        var pose = event.getPoseStack(); pose.pushPose();
        pose.mulPose(Axis.XP.rotationDegrees(2));
        pose.scale(1/16f, 1/16f, 1/16f);
        pose.translate(0, -29, .6f);
        if (mc.player.getMainArm() == HumanoidArm.LEFT) pose.scale(-1,1,1);
        boolean shown = DawnvowSummon.visible(p, now);
        var rig = LongswordRig.get(p.variant(), true);
        boolean ryuka = RyukaClient.is(p);
        if (ryuka) {
            // 流火：手臂照常画，刀交给它自己（凝聚、散去、只剩刀柄）；刀身不完整时不加狐火
            RyukaClient.observe(mc.player, now);
            rig.submit(sample, pose, event.getSubmitNodeCollector(), event.getPackedLight(), mc.player.getSkin(), !mc.player.isInvisible(), false);
            RyukaClient.submit(rig, p, mc.player.getId(), now, sample, pose, event.getSubmitNodeCollector(), event.getPackedLight(), true);
            shown = RyukaClient.whole(rig, p, mc.player.getId(), now);
        } else rig.submit(sample, pose,
                event.getSubmitNodeCollector(), event.getPackedLight(), mc.player.getSkin(), !mc.player.isInvisible(), shown);
        if (rig.glows() && shown) {
            float glow = SwordCastClient.glow(mc.player, p, now, event.getPartialTick());
            if (glow > 0) rig.submitGlow(sample, pose, event.getSubmitNodeCollector(), true, glow, SwordCastClient.color(mc.player));
        }
        var tip = SwordCastClient.tip(mc.player.getMainHandItem());
        if (tip != null) SwordCastClient.reportFirstPerson(mc.player.getId(), pose.last().pose(), LongswordRig.onWeapon(sample, tip));
        if (shown) LongswordAuras.firstPerson(mc.player,sample,pose,event.getSubmitNodeCollector(),ryuka);
        DawnvowSummon.submit(p, now, sample, pose, event.getSubmitNodeCollector(), true);
        if (shown) DawnvowBlade.submit(DawnvowClient.blade(mc.player.getId(), event.getPartialTick()), sample, pose, event.getSubmitNodeCollector(), true,
                mc.player.tickCount + event.getPartialTick());
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
            boolean shown = DawnvowSummon.visible(frame.move, frame.time);
            var rig = LongswordRig.get(frame.variant, false);
            boolean ryuka = RyukaClient.is(frame.move);
            if (ryuka) {
                rig.submit(frame.sample, pose, collector, light, state.skin, !state.isInvisible, false);
                if (!state.isInvisible) RyukaClient.submit(rig, frame.move, state.id, frame.time, frame.sample, pose, collector, light, false);
                shown = !state.isInvisible && RyukaClient.whole(rig, frame.move, state.id, frame.time);
                if (shown) RyukaClient.pinkFire(frame.effects);
            } else rig.submit(frame.sample, pose, collector, light, state.skin, !state.isInvisible, shown);
            dev.logos.dimension.client.YumiClient.reportArrow(state.id, pose.last().pose(), frame.sample);
            if (shown && frame.glow > 0) rig.submitGlow(frame.sample, pose, collector, false, frame.glow, frame.glowColor);
            if (frame.tip != null) SwordCastClient.reportThirdPerson(state.id, pose.last().pose(), LongswordRig.onWeapon(frame.sample, frame.tip));
            DawnvowSummon.submit(frame.move, frame.time, frame.sample, pose, collector, false);
            if (shown) {
                LongswordAuras.submit(frame.effects,frame.sample,pose,collector);
                Float glow = state.getRenderData(DawnvowBlade.GLOW);
                if (glow != null) DawnvowBlade.submit(glow, frame.sample, pose, collector, false, state.ageInTicks);
            }
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
