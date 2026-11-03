package dev.logos.dimension.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.logos.client.ShaderPackOverlay;
import dev.logos.client.VfxParts;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.economy.Currency;
import dev.logos.dimension.economy.Economy;
import dev.logos.dimension.economy.Railgun;
import dev.logos.dimension.economy.RailgunPayload;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.asm.enumextension.EnumProxy;
import net.neoforged.neoforge.client.IArmPoseTransformer;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import org.jspecify.annotations.Nullable;

/**
 * 超电磁炮的客户端：收到服务端的三个时刻就建、收特效，每刻推进、每帧绘制；蓄力时手臂伸直指向准星，
 * 第一人称把手抬到画面中间；发射时镜头震一下。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class RailgunClient {
    private static final Map<Integer, RailgunCharge> CHARGES = new HashMap<>();
    private static final List<RailgunShot> SHOTS = new ArrayList<>();
    private static final int SHOT_CAP = 6;
    /** 伸臂用的刻数；开炮后手臂再伸 FOLLOW 刻，最后 LOWER 刻放下。 */
    private static final float RAISE_TICKS = 4, FOLLOW = 14, LOWER = 5;
    /** 发射时镜头震多久、多猛（度）。 */
    private static final float SHAKE_TICKS = 10, SHAKE_DEGREES = 1.4f;
    private static Level shown;
    private static float shake;
    private static long shakeAt;

    /** 蓄力时的手臂姿势，经 META-INF/enumextensions.json 加进 {@link HumanoidModel.ArmPose}。 */
    public static final EnumProxy<HumanoidModel.ArmPose> POSE = new EnumProxy<>(HumanoidModel.ArmPose.class,
            false, false, (IArmPoseTransformer) RailgunClient::poseArm);

    private RailgunClient() {}

    // ---- 收包 ------------------------------------------------------------------------------------

    @SubscribeEvent
    static void payloads(RegisterClientPayloadHandlersEvent event) {
        event.register(RailgunPayload.TYPE, (p, context) -> receive(p));
    }

    static void receive(RailgunPayload p) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) return;
        sync(level);
        Entity e = level.getEntity(p.entity());
        switch (p.kind()) {
            case RailgunPayload.CHARGE -> { if (e != null) CHARGES.put(p.entity(), new RailgunCharge(level, e, p.offhand())); }
            case RailgunPayload.CANCEL -> {
                var charge = CHARGES.get(p.entity());
                if (charge != null) charge.cancel(level);
                stopOwn(mc, p.entity());
            }
            case RailgunPayload.FIRE -> {
                CHARGES.remove(p.entity());
                if (SHOTS.size() >= SHOT_CAP) SHOTS.removeFirst();
                var shot = new RailgunShot(level, e, p.offhand(), p.from(), p.to(), p.struck());
                SHOTS.add(shot);
                stopOwn(mc, p.entity());
                if (mc.player != null) {
                    double d = mc.player.getEyePosition().distanceTo(shot.muzzle());
                    float kick = mc.player.getId() == p.entity() ? 1 : (float) Math.max(0, .5 * (1 - d / 24));
                    if (kick > 0) { shake = Math.max(shake * fadeShake(level, 0), kick); shakeAt = level.getGameTime(); }
                }
            }
            default -> {}
        }
    }

    /** 自己这边的蓄力已由服务端结束（发射或魔力不足）。 */
    private static void stopOwn(Minecraft mc, int entity) {
        LocalPlayer p = mc.player;
        if (p != null && p.getId() == entity && p.isUsingItem() && Railgun.coin(p.getUseItem())) p.stopUsingItem();
    }

    private static void sync(Level level) {
        if (level == shown) return;
        CHARGES.clear();
        SHOTS.clear();
        shake = 0;
        shown = level;
    }

    /** 有没有在蓄力（客户端场景检查用）。 */
    public static boolean charging(int entity) { return CHARGES.containsKey(entity); }

    public static int shots() { return SHOTS.size(); }

    // ---- 推进与绘制 ------------------------------------------------------------------------------

    @SubscribeEvent
    static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) { CHARGES.clear(); SHOTS.clear(); shown = null; return; }
        sync(mc.level);
        if (mc.isPaused()) return;
        CHARGES.values().removeIf(c -> !c.tick(mc.level));
        SHOTS.removeIf(s -> !s.tick(mc.level));
    }

    /** 在 Logos 之后提交（Logos 在这个事件里先 begin ShaderPackOverlay）。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void submit(SubmitCustomGeometryEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || CHARGES.isEmpty() && SHOTS.isEmpty()) return;
        CameraRenderState camera = event.getLevelRenderState().cameraRenderState;
        float pt = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        PoseStack pose = event.getPoseStack();
        SubmitNodeCollector raw = event.getSubmitNodeCollector(), out = ShaderPackOverlay.collector(raw);
        var layers = VfxParts.layered(raw, out);
        VfxParts.View view = VfxParts.View.of(camera);
        pose.pushPose();
        pose.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
        for (RailgunCharge c : CHARGES.values()) c.submit(mc.level, view, pt, pose, layers);
        for (RailgunShot s : SHOTS) s.submit(mc.level, view, pt, pose, layers);
        pose.popPose();
    }

    /** 发射时镜头的一下震动，几刻内平息。 */
    @SubscribeEvent
    static void camera(ViewportEvent.ComputeCameraAngles event) {
        Minecraft mc = Minecraft.getInstance();
        if (shake <= 0 || mc.level == null) return;
        float t = (float) (mc.level.getGameTime() - shakeAt + event.getPartialTick());
        float a = fadeShake(mc.level, (float) event.getPartialTick()) * SHAKE_DEGREES;
        if (a <= 0) { shake = 0; return; }
        event.setPitch(event.getPitch() + a * Mth.sin(t * 2.9f));
        event.setYaw(event.getYaw() + a * .7f * Mth.cos(t * 3.7f));
        event.setRoll(event.getRoll() + a * .5f * Mth.sin(t * 4.3f + 1));
    }

    private static float fadeShake(Level level, float pt) {
        float p = (level.getGameTime() - shakeAt + pt) / SHAKE_TICKS;
        return p >= 1 ? 0 : shake * (1 - p) * (1 - p);
    }

    // ---- 手的位置 --------------------------------------------------------------------------------

    /** 拳头在世界里的位置和手臂指的方向；{@code own} 表示这是镜头自己的第一人称，拳头贴在画面右下。 */
    record Grip(Vec3 fist, Vec3 look, Vec3 right, Vec3 up, boolean own) {}

    static Grip grip(Entity e, boolean offhand, float pt) {
        Minecraft mc = Minecraft.getInstance();
        Vec3 look = e.getViewVector(pt);
        Vec3 right = look.cross(new Vec3(0, 1, 0));
        float yaw = e.getViewYRot(pt) * Mth.DEG_TO_RAD;
        right = right.lengthSqr() < 1e-6 ? new Vec3(-Math.cos(yaw), 0, -Math.sin(yaw)) : right.normalize();
        Vec3 up = right.cross(look);
        int side = arm(e, offhand) == HumanoidArm.RIGHT ? 1 : -1;
        boolean own = e == mc.getCameraEntity() && mc.options.getCameraType().isFirstPerson();
        if (own) {
            // 和 handInView 抬起后的持物位置对上
            Vec3 fist = e.getEyePosition(pt).add(look.scale(.86)).add(right.scale(.36 * side)).add(up.scale(-.36));
            return new Grip(fist, look, right, up, true);
        }
        float body = (e instanceof LivingEntity l ? Mth.rotLerp(pt, l.yBodyRotO, l.yBodyRot) : e.getViewYRot(pt)) * Mth.DEG_TO_RAD;
        double scale = e instanceof LivingEntity l ? l.getScale() : 1;
        Vec3 bodyRight = new Vec3(-Math.cos(body), 0, -Math.sin(body));
        Vec3 shoulder = e.getPosition(pt).add(0, (e.isCrouching() ? 1.05 : 1.3) * scale, 0).add(bodyRight.scale(.33 * side * scale));
        return new Grip(shoulder.add(look.scale(.62 * scale)), look, right, up, false);
    }

    private static HumanoidArm arm(Entity e, boolean offhand) {
        HumanoidArm main = e instanceof Player p ? p.getMainArm() : HumanoidArm.RIGHT;
        return offhand ? main.getOpposite() : main;
    }

    // ---- 手臂姿势 --------------------------------------------------------------------------------

    @SubscribeEvent
    static void extensions(RegisterClientExtensionsEvent event) {
        event.registerItem(new IClientItemExtensions() {
            @Override
            public HumanoidModel.@Nullable ArmPose getArmPose(LivingEntity entity, InteractionHand hand, ItemStack stack) {
                return aiming(entity, hand) ? POSE.getValue() : null;
            }

            @Override
            public boolean applyForgeHandTransform(PoseStack pose, LocalPlayer player, HumanoidArm arm, ItemStack stack, float partialTick,
                    float equipProcess, float swingProcess) {
                InteractionHand hand = arm == player.getMainArm() ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
                if (!aiming(player, hand)) return false;
                handInView(pose, player, arm, partialTick, equipProcess);
                return true;
            }
        }, Economy.coin(Currency.IMPERIAL, 1));
    }

    private static boolean aiming(LivingEntity entity, InteractionHand hand) {
        if (entity.isUsingItem() && entity.getUsedItemHand() == hand && CHARGES.containsKey(entity.getId())) return true;
        RailgunShot shot = followThrough(entity.getId());
        return shot != null && shot.offhand() == (hand == InteractionHand.OFF_HAND);
    }

    /** 刚开过炮、手臂还伸着的那一发。 */
    private static @Nullable RailgunShot followThrough(int entity) {
        Level level = Minecraft.getInstance().level;
        if (level == null) return null;
        for (RailgunShot s : SHOTS) if (s.shooter() == entity && s.age(level, 0) < FOLLOW) return s;
        return null;
    }

    /** 手臂伸出的程度 0~1：蓄力时几刻内伸直，开炮后保持一会儿再放下。 */
    private static float raised(int entity, float ticksUsing) {
        RailgunShot shot = followThrough(entity);
        Minecraft mc = Minecraft.getInstance();
        if (shot != null) return 1 - VfxParts.smooth(FOLLOW - LOWER, FOLLOW, shot.age(mc.level, mc.getDeltaTracker().getGameTimeDeltaPartialTick(false)));
        float t = Mth.clamp(ticksUsing / RAISE_TICKS, 0, 1);
        return t * t * (3 - 2 * t);
    }

    /** 第三人称：持币的手臂伸直，指向准星。 */
    private static void poseArm(HumanoidModel<?> model, HumanoidRenderState state, HumanoidArm arm) {
        ModelPart part = arm == HumanoidArm.RIGHT ? model.rightArm : model.leftArm;
        float k = raised(state instanceof AvatarRenderState avatar ? avatar.id : -1, state.ticksUsingItem);
        part.xRot = Mth.lerp(k, part.xRot, model.head.xRot - Mth.HALF_PI);
        part.yRot = Mth.lerp(k, part.yRot, model.head.yRot);
        part.zRot = Mth.lerp(k, part.zRot, 0);
    }

    /** 第一人称：原版持物位置往画面中间抬、往前送。 */
    private static void handInView(PoseStack pose, LocalPlayer player, HumanoidArm arm, float partialTick, float equipProcess) {
        int side = arm == HumanoidArm.RIGHT ? 1 : -1;
        float k = raised(player.getId(), player.getTicksUsingItem() + partialTick);
        pose.translate(side * .56f, -.52f + equipProcess * -.6f, -.72f);
        pose.translate(-side * .16f * k, .12f * k, -.14f * k);
        pose.mulPose(Axis.XP.rotationDegrees(-10 * k));
    }
}
