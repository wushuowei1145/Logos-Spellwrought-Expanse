package dev.logos.dimension.client.explosion;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.logos.client.ShaderPackOverlay;
import dev.logos.client.VfxParts;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.explosion.ExplosionPayload;
import dev.logos.dimension.explosion.ExplosionStaffItem;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import org.jspecify.annotations.Nullable;

/**
 * 爆裂魔法的客户端：收服务端的吟唱、中断、释放，建、推进、绘制 {@link ChargeView} 和 {@link BlastView}；
 * 全屏的调色、球体和闪光由 {@link ExplosionScreen} 读这里的状态来画；爆炸时镜头的震动也在这里。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class ExplosionClient {
    /** 这么多刻没收到吟唱的进度，就当它断了（施法者走出范围）。 */
    private static final int STALE = 40;
    private static final Map<Integer, ChargeView> CHARGES = new HashMap<>();
    private static final Map<Integer, Float> HEARD = new HashMap<>();
    private static final List<ChargeView> ENDED = new ArrayList<>();
    private static final List<BlastView> BLASTS = new ArrayList<>();
    private static @Nullable Level shown;
    private static long ticks;
    /** 镜头震动的强度与种子。 */
    private static float shake;
    private static int shakeSeed;
    /** 客户端场景截图用：停住特效自己的时钟。 */
    private static boolean frozen;
    /** 贴图载过没有（每次启动一遍）。 */
    private static boolean warmed;
    /** 自己是不是被爆裂魔法累趴着（服务端说了算，见 ExplosionExhaustion）。 */
    private static boolean exhausted;
    /** 趴下的姿势是不是这里设的：只撤自己设的，不碰别的模组设的。 */
    private static boolean forcedHere;

    private ExplosionClient() {}

    @SubscribeEvent
    static void payloads(RegisterClientPayloadHandlersEvent event) {
        event.register(ExplosionPayload.TYPE, (p, context) -> receive(p));
    }

    static void receive(ExplosionPayload p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        sync(mc.level);
        float now = ticks;
        switch (p.kind()) {
            case ExplosionPayload.CHARGE -> {
                ChargeView c = CHARGES.get(p.caster());
                if (c == null) {
                    c = new ChargeView(p.caster(), p.target(), p.radius(), now);
                    CHARGES.put(p.caster(), c);
                }
                c.update(p.progress(), p.full(), p.target(), p.radius(), now);
                HEARD.put(p.caster(), now);
            }
            case ExplosionPayload.STOP -> {
                ChargeView c = CHARGES.remove(p.caster());
                if (c != null) {
                    c.enter(ChargeView.Phase.FIZZLED, now);
                    ENDED.add(c);
                }
                stopOwn(mc, p.caster());
            }
            case ExplosionPayload.RELEASE -> {
                ChargeView c = CHARGES.remove(p.caster());
                SkyCircles circles = null;
                if (c != null) {
                    circles = c.circles;
                    c.enter(ChargeView.Phase.RELEASED, now);
                    ENDED.add(c);
                }
                BLASTS.add(new BlastView(p.target(), p.radius(), p.seed(), p.caster(), circles, now));
                stopOwn(mc, p.caster());
            }
            case ExplosionPayload.EXHAUST -> {
                // 只发给施法者自己
                exhausted = p.seed() > 0;
                exhaust(mc);
            }
            default -> {}
        }
    }

    /** 按服务端说的趴下或起来（原版的趴下姿势）。 */
    private static void exhaust(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null) return;
        if (exhausted) {
            if (p.getForcedPose() != Pose.SWIMMING) {
                p.setForcedPose(Pose.SWIMMING);
                p.setSprinting(false);
                forcedHere = true;
            }
        } else if (forcedHere) {
            if (p.getForcedPose() == Pose.SWIMMING) p.setForcedPose(null);
            forcedHere = false;
        }
    }

    /** 累趴着时挡掉跳和疾跑：趴着的人不会一蹦一蹦的。 */
    @SubscribeEvent
    static void input(MovementInputUpdateEvent event) {
        if (!exhausted) return;
        Input k = event.getInput().keyPresses;
        event.getInput().keyPresses = new Input(k.forward(), k.backward(), k.left(), k.right(), false, k.shift(), false);
    }

    /** 自己是不是被爆裂魔法累趴着（客户端场景看）。 */
    public static boolean exhausted() {
        return exhausted;
    }

    /** 自己这边的吟唱已由服务端结束。 */
    private static void stopOwn(Minecraft mc, int caster) {
        LocalPlayer p = mc.player;
        if (p != null && p.getId() == caster && p.isUsingItem() && p.getUseItem().is(ExplosionStaffItem.ITEM.get())) p.stopUsingItem();
    }

    private static void sync(Level level) {
        if (level == shown) return;
        clear();
        shown = level;
    }

    /** 全部丢掉（换世界；客户端场景从头开始）。 */
    public static void clear() {
        CHARGES.clear();
        HEARD.clear();
        ENDED.clear();
        BLASTS.clear();
        shake = 0;
    }

    /** 这一帧有没有要画的（没有时后期一个通道都不跑）。 */
    public static boolean active() {
        return !CHARGES.isEmpty() || !ENDED.isEmpty() || !BLASTS.isEmpty();
    }

    static long ticks() {
        return ticks;
    }

    /** 这一帧的刻内进度；时钟停住时为 0，画面完全不动。 */
    static float partial() {
        return frozen ? 0 : Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
    }

    /** 客户端场景用：停住（true）或放开爆裂魔法特效的时钟，截图时画面停在那一刻。 */
    public static void freeze(boolean stop) {
        frozen = stop;
    }

    // ---- 客户端场景看的状态 ------------------------------------------------------------------------

    public static int charges() { return CHARGES.size(); }

    public static int blasts() { return BLASTS.size(); }

    /** 某人吟唱在本机看到的进度；没在吟唱时为 -1。 */
    public static float progress(int caster) {
        ChargeView c = CHARGES.get(caster);
        return c == null ? -1 : c.progress(0, ticks);
    }

    /** 最近一次爆炸在本机已经过了多少刻；没有时为 -1。 */
    public static float blastAge() {
        return BLASTS.isEmpty() ? -1 : BLASTS.getLast().age(ticks, 0);
    }

    // ---- 推进与绘制 ------------------------------------------------------------------------------

    @SubscribeEvent
    static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            clear();
            shown = null;
            exhausted = forcedHere = false;
            return;
        }
        sync(mc.level);
        // 换维度后客户端是一个新的玩家对象：每刻看一眼，还在累趴着就再设上
        exhaust(mc);
        if (!warmed) {
            warmed = true;
            for (String t : Draw.TEXTURES) mc.getTextureManager().getTexture(dev.logos.dimension.client.screenfx.PostVfx.logos(t));
            mc.getTextureManager().getTexture(SkyCircles.DISC);
        }
        if (mc.isPaused() || frozen) return;
        step(mc);
    }

    /**
     * 客户端场景用：时钟停着时，一刻一刻地往前推，直到最近一次爆炸走到第 {@code age} 刻（中间每刻该冒的火花、该触发的黑白闪都照常发生），
     * 截图就能准确地停在时间轴的某一刻。
     */
    public static void advanceTo(float age) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        for (int guard = 0; guard < 2000 && !BLASTS.isEmpty() && BLASTS.getLast().age(ticks, 0) < age; guard++) step(mc);
    }

    private static void step(Minecraft mc) {
        ticks++;
        float now = ticks;
        shake = shake < .01f ? 0 : shake * .88f;
        CHARGES.entrySet().removeIf(e -> {
            Float heard = HEARD.get(e.getKey());
            if (heard != null && now - heard <= STALE) return false;
            e.getValue().enter(ChargeView.Phase.FIZZLED, now);
            ENDED.add(e.getValue());
            HEARD.remove(e.getKey());
            return true;
        });
        for (ChargeView c : CHARGES.values()) c.tick(mc.level, now);
        ENDED.removeIf(c -> !c.tick(mc.level, now));
        BLASTS.removeIf(b -> !b.tick(mc.level, now));
    }

    /** 在 Logos 之后提交（Logos 在这个事件里先 begin ShaderPackOverlay）。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void submit(SubmitCustomGeometryEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !active()) return;
        CameraRenderState camera = event.getLevelRenderState().cameraRenderState;
        float pt = partial();
        PoseStack pose = event.getPoseStack();
        SubmitNodeCollector raw = event.getSubmitNodeCollector(), out = ShaderPackOverlay.collector(raw);
        var layers = VfxParts.layered(raw, out);
        VfxParts.View view = VfxParts.View.of(camera);
        float now = ticks;
        pose.pushPose();
        pose.translate(-camera.pos.x, -camera.pos.y, -camera.pos.z);
        for (ChargeView c : CHARGES.values()) c.submit(mc.level, view, pt, now, pose, layers);
        for (ChargeView c : ENDED) c.submit(mc.level, view, pt, now, pose, layers);
        for (BlastView b : BLASTS) b.submit(mc.level, view, pt, now, pose, layers);
        pose.popPose();
    }

    // ---- 交给全屏后期 ------------------------------------------------------------------------------

    /** 周围变暗多少：所有吟唱里最重的那个；刚放出去、火柱还没落地时保持吟唱时的暗。 */
    static float dim(Vec3 eye, float pt) {
        float d = 0;
        for (ChargeView c : CHARGES.values()) d = Math.max(d, c.dim(eye, pt, ticks));
        for (ChargeView c : ENDED) d = Math.max(d, c.dim(eye, pt, ticks));
        for (BlastView b : BLASTS) {
            float age = b.age(ticks, pt);
            float near = 1 - Draw.smooth(64, 192, (float) eye.distanceTo(b.center));
            d = Math.max(d, .6f * near * (1 - Draw.smooth(8, 14, age)));
        }
        return d;
    }

    /** 自己蓄满后、第一人称看着时，画面四周往上流的蓝光有多强（0~1）。 */
    static float aura(float pt) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getCameraEntity() != mc.player || !mc.options.getCameraType().isFirstPerson()) return 0;
        ChargeView c = CHARGES.get(mc.player.getId());
        float held = c == null ? -1 : c.heldAge(ticks + pt);
        return held < 0 ? 0 : Draw.smooth(0, 10, held);
    }

    /** 蓄满那一下从身上推出去的空气（一层看不见的球壳）。 */
    record Shell(Vec3 center, float radius, float strength) {}

    /** 最强的那一层正在扩开的球壳；没有时为 null。球心就在眼前的人（施法者自己）看不到，不算。 */
    static @Nullable Shell chargeShell(Vec3 eye, float pt) {
        Shell best = null;
        for (ChargeView c : CHARGES.values()) {
            float held = c.heldAge(ticks + pt);
            if (held < 0 || held > 22) continue;
            Vec3 at = c.chest();
            if (eye.distanceTo(at) < 3) continue;
            float k = held / 22;
            float strength = (1 - k) * Draw.smooth(0, .06f, k) * .9f;
            if (best == null || strength > best.strength()) best = new Shell(at, (1.5f + 20 * (1 - (1 - k) * (1 - k))) * c.bodyScale(), strength);
        }
        return best;
    }

    /** 最该画的那一次爆炸：离得最近、还在进行的。 */
    static @Nullable BlastView primary(Vec3 eye) {
        BlastView best = null;
        double bestD = Double.MAX_VALUE;
        for (BlastView b : BLASTS) {
            double d = eye.distanceTo(b.center) - b.radius;
            if (d < bestD) {
                bestD = d;
                best = b;
            }
        }
        return best;
    }

    // ---- 镜头震动 --------------------------------------------------------------------------------

    /** 在 {@code at} 处震一下：爆炸半径以内最重，六倍半径外不震。 */
    static void shake(Vec3 at, float radius, float power) {
        var camera = Minecraft.getInstance().getCameraEntity();
        if (camera == null) return;
        double d = camera.position().distanceTo(at);
        float k = 1 - Draw.smooth(radius, radius * 6, (float) d);
        if (power * k > shake) {
            shake = power * k;
            shakeSeed++;
        }
    }

    @SubscribeEvent
    static void camera(ViewportEvent.ComputeCameraAngles event) {
        if (shake <= 0) return;
        float scale = shake * Minecraft.getInstance().options.screenEffectScale().get().floatValue();
        if (scale <= 0) return;
        float t = (float) (ticks + event.getPartialTick());
        float a = t * 2.3f + shakeSeed, b = t * 2.9f + shakeSeed * 1.7f, c = t * 2.1f + shakeSeed * 2.3f;
        event.setYaw(event.getYaw() + 2.2f * scale * (Mth.sin(a) + .5f * Mth.sin(a * 2.3f)));
        event.setPitch(event.getPitch() + 3f * scale * (Mth.sin(b) + .5f * Mth.sin(b * 1.7f)));
        event.setRoll(event.getRoll() + 2f * scale * Mth.sin(c));
    }
}
