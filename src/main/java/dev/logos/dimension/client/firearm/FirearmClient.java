package dev.logos.dimension.client.firearm;

import com.mojang.blaze3d.platform.InputConstants;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.client.longsword.LongswordClient;
import dev.logos.dimension.client.longsword.LongswordRig;
import dev.logos.dimension.combat.Moveset;
import dev.logos.dimension.firearm.BuckshotItem;
import dev.logos.dimension.firearm.FirearmPayloads;
import dev.logos.dimension.firearm.Firearms;
import dev.logos.dimension.longsword.LongswordPayload;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.client.settings.IKeyConflictContext;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

/**
 * 铳的客户端（docs/FIREARMS.md）：
 * <ul>
 *   <li>R 键：拿着铳时短按换弹，长按（{@link #HOLD_TICKS} 刻）打开弹药轮盘。R 也是 Logos 术式轮盘的键，拿着铳时归铳，
 *       副手拿着法杖也不弹术式轮盘。</li>
 *   <li>转轮里看得见还剩几发：打掉的那几发子弹骨骼缩成 0（{@link #rounds}）；换弹动作里新转轮满弹、旧转轮按换之前的发数。</li>
 *   <li>开枪：铳口火光和烟由铳统一画，弹道和命中交给弹药自己的画法（{@link AmmoVisuals}）。</li>
 *   <li>第一人称举枪瞄准时不画原版准星（{@link #aiming}）。</li>
 * </ul>
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class FirearmClient {
    private FirearmClient() {}

    public static final KeyMapping.Category CATEGORY = new KeyMapping.Category(LogosDimension.id("firearms"));
    /** 只在主手拿着铳、没开界面时生效，和 Logos 的术式轮盘共用 R 不算冲突。 */
    private static final IKeyConflictContext HOLDING = new IKeyConflictContext() {
        @Override public boolean isActive() {
            var mc = Minecraft.getInstance();
            return mc.screen == null && holding(mc.player);
        }
        @Override public boolean conflicts(IKeyConflictContext other) { return this == other; }
    };
    public static final KeyMapping RELOAD = new KeyMapping("key.logos_dimension.reload", HOLDING, InputConstants.Type.KEYSYM,
            InputConstants.KEY_R, CATEGORY);
    /** 按住多少刻算长按（0.3 秒）。 */
    static final int HOLD_TICKS = 6;
    private static int held;
    private static boolean wheel;

    static boolean holding(Player player) { return player != null && Firearms.firearm(player.getMainHandItem()) != null; }

    @SubscribeEvent static void keys(RegisterKeyMappingsEvent event) {
        event.registerCategory(CATEGORY);
        event.register(RELOAD);
    }

    @SubscribeEvent static void packets(RegisterClientPayloadHandlersEvent event) {
        event.register(FirearmPayloads.Shot.TYPE, (p, ctx) -> ctx.enqueueWork(() -> shot(p)));
        AmmoVisuals.register(BuckshotItem.ITEM.getId(), BuckshotVisuals::draw);
    }

    @SubscribeEvent static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        while (RELOAD.consumeClick()) { /* 按下由 isDown 判断，点击计数不用 */ }
        if (mc.player == null || !holding(mc.player)) { held = 0; wheel = false; return; }
        boolean down = mc.screen == null && RELOAD.isDown();
        if (down) {
            held++;
            if (held == HOLD_TICKS && !wheel) {
                wheel = true;
                var kinds = Firearms.kinds(mc.player, mc.player.getMainHandItem());
                if (!kinds.isEmpty()) mc.setScreen(new AmmoWheelScreen(kinds, Firearms.load(mc.player.getMainHandItem()).selected()));
            }
            return;
        }
        // 松开：没到长按、也没开过轮盘，就是短按换弹。轮盘开着时松开由轮盘自己确认。
        if (held > 0 && held < HOLD_TICKS && !wheel) ClientPacketDistributor.sendToServer(new FirearmPayloads.Reload());
        if (!(mc.screen instanceof AmmoWheelScreen)) { held = 0; wheel = false; }
    }

    /** 拿着铳时 R 归铳：副手的法杖不再弹出 Logos 的术式轮盘。 */
    @SubscribeEvent static void screen(ScreenEvent.Opening event) {
        if (event.getNewScreen() instanceof dev.logos.client.SpellWheelScreen && holding(Minecraft.getInstance().player)) event.setCanceled(true);
    }

    /** 第一人称举枪瞄准（招式名以 aim 开头：瞄准、瞄准开枪、瞄准空响）时，照门和准星就是准星。 */
    public static boolean aiming() {
        var mc = Minecraft.getInstance();
        if (mc.player == null || !holding(mc.player) || !mc.options.getCameraType().isFirstPerson()) return false;
        var pose = LongswordClient.pose(mc.player.getUUID());
        return pose != null && pose.move().startsWith("aim");
    }

    /** 瞄准时不画原版准星，免得挡住照门和准星。 */
    @SubscribeEvent static void crosshair(RenderGuiLayerEvent.Pre event) {
        if (event.getName().equals(VanillaGuiLayers.CROSSHAIR) && aiming()) event.setCanceled(true);
    }

    // ---------------------------------------------------------------- 转轮里的子弹

    /**
     * 转轮里的子弹（骨骼缩成 0 就是空膛）。转轮最上面一格对着枪膛，{@code Round0} 是下一发：还剩 n 发时 {@code Round0..n-1} 有弹。
     * <p>开一枪转一格（{@code 360°/容量}）：动作里扳击锤时把转轮从 0 转到 60°。客户端过了 {@code fire} 那一刻就按开完这一枪的发数摆子弹，
     * 同时把转轮往回拨一格（{@link #turn}），两者相抵和开枪前一模一样（刚打空的那格在最上面）；扳击锤时转回 0，
     * 正好接上下一段动作的 0，不会再往回转一格（#302）。连开几枪（扇击）每过一个 {@code fire} 多拨一格。
     * <p>换弹动作里，{@code feed} 之前左手拿着的新转轮满弹，被顶出去的旧转轮（备用转轮）按换之前的发数。
     */
    public static LongswordRig.Sample rounds(LongswordPayload p, LongswordRig rig, LongswordRig.Sample sample, double now) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || rig.ownBones().get("Round0") == null) return sample;
        var player = mc.level.getPlayerByUUID(p.player());
        if (player == null || !holding(player)) return sample;
        var gun = player.getMainHandItem();
        var firearm = Firearms.firearm(gun);
        double length = rig.length(p.clip());
        double t = length < 0 ? 0 : Moveset.playbackSeconds(p.playback(), length, now - p.started(), p.speed(), p.offset());
        float[] feed = rig.markers(p.clip(), "feed"), fire = rig.markers(p.clip(), "fire");
        int passed = 0;
        while (passed < fire.length && t + 1e-4 >= fire[passed]) passed++;
        int rounds = predicted(p, Firearms.load(gun).rounds(), passed);
        boolean reloading = feed.length > 0 && t < feed[0];
        Map<String, Matrix4f> bones = new HashMap<>(sample.bones());
        if (passed > 0) turn(bones, rig, -360f / firearm.capacity() * passed);
        for (int k = 0; k < 6; k++) {
            hide(bones, "Round" + k, !(reloading ? k < firearm.capacity() : k < rounds));
            hide(bones, "SpareRound" + k, !(reloading && k < rounds));
        }
        return new LongswordRig.Sample(Map.copyOf(bones), sample.bodyRotation(), sample.weapon(), sample.turn(), sample.rightLeg(), sample.leftLeg());
    }

    /** 转轮（连同里面的子弹）绕自身轴转 {@code degrees}（同动作里的 cyl_spin，绕网格 +Y）。骨骼矩阵是静止姿势到这一帧的变换，右乘静止姿势里的转动。 */
    private static void turn(Map<String, Matrix4f> bones, LongswordRig rig, float degrees) {
        var cylinder = rig.ownBones().get("Cylinder");
        if (cylinder == null) return;
        var c = new Vector3f(cylinder.pivot()).mul(rig.weaponScale()).add(rig.weaponPivot());
        var r = new Matrix4f().translation(c).rotateY((float) Math.toRadians(degrees)).translate(-c.x, -c.y, -c.z);
        for (var entry : bones.entrySet())
            if (entry.getKey().equals("Cylinder") || entry.getKey().startsWith("Round")) entry.setValue(new Matrix4f(entry.getValue()).mul(r));
    }

    /**
     * 每个玩家：这段动作里过了几次扣扳机、服务端同步过来扣掉了几发。客户端过了 {@code fire} 时刻，服务端扣发数要晚一两刻才到；
     * 这几刻里按“还欠着的”先少画一发，不然扇击时每一枪都会闪一下多出来的子弹。上一段动作欠着的（第五下那一枪还没同步就接了扇击）带进下一段，只带一次。
     */
    private static final class Count { double started = Double.NaN; int last, own, debt, got; }
    private static final Map<java.util.UUID, Count> COUNTS = new HashMap<>();

    private static int predicted(LongswordPayload p, int synced, int passed) {
        var c = COUNTS.computeIfAbsent(p.player(), u -> new Count());
        if (c.started != p.started()) {
            c.debt = Double.isNaN(c.started) ? 0 : Math.max(0, c.own - Math.max(0, c.got - c.debt));
            c.started = p.started();
            c.own = 0; c.got = 0; c.last = synced;
        }
        if (synced < c.last) c.got += c.last - synced;
        c.own = passed;
        // 装填过（发数变多）或已经打空：之前的账都结清，空膛扣扳机不会欠下什么。
        if (synced > c.last || synced == 0) { c.debt = 0; c.got = c.own; }
        c.last = synced;
        return Math.max(0, synced - Math.max(0, c.debt + c.own - c.got));
    }

    private static void hide(Map<String, Matrix4f> bones, String bone, boolean hidden) {
        var m = bones.get(bone);
        if (hidden && m != null) bones.put(bone, new Matrix4f(m).scale(0));
    }

    // ---------------------------------------------------------------- 铳口与开枪

    private record Muzzle(Vec3 at, long time) {}
    private static final Map<Integer, Muzzle> MUZZLES = new HashMap<>();

    /** 第三人称画完骨架时记下铳口在世界里的位置（{@code pose} 是骨架空间到相机相对坐标的变换）。 */
    public static void reportThirdPerson(int entity, Matrix4fc pose, LongswordRig rig, LongswordRig.Sample sample) {
        var mc = Minecraft.getInstance();
        var muzzle = rig.ownBones().get("MuzzleLocator");
        if (muzzle == null) return;
        if (mc.getCameraEntity() != null && entity == mc.getCameraEntity().getId() && mc.options.getCameraType().isFirstPerson()) return;
        var at = pose.transformPosition(LongswordRig.onWeapon(sample, new Vector3f(muzzle.pivot())));
        var camera = mc.gameRenderer.getMainCamera().position();
        MUZZLES.put(entity, new Muzzle(camera.add(at.x, at.y, at.z), System.currentTimeMillis()));
    }

    /** 第一人称：{@code toHand} 是骨架空间到进入手部渲染时坐标的变换（镜头坐标）。 */
    public static void reportFirstPerson(int entity, Matrix4fc toHand, LongswordRig rig, LongswordRig.Sample sample) {
        var mc = Minecraft.getInstance();
        var muzzle = rig.ownBones().get("MuzzleLocator");
        if (muzzle == null) return;
        var camera = mc.gameRenderer.getMainCamera();
        var at = toHand.transformPosition(LongswordRig.onWeapon(sample, new Vector3f(muzzle.pivot())));
        camera.rotation().transform(at);
        MUZZLES.put(entity, new Muzzle(camera.position().add(at.x, at.y, at.z), System.currentTimeMillis()));
    }

    private static void shot(FirearmPayloads.Shot p) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        var shooter = mc.level.getEntity(p.shooter());
        var recent = MUZZLES.get(p.shooter());
        Vec3 muzzle = recent != null && System.currentTimeMillis() - recent.time < 250 ? recent.at
                : shooter != null ? p.origin().add(shooter.getLookAngle().scale(1.2)) : p.origin();
        var look = shooter != null ? shooter.getLookAngle() : new Vec3(0, 0, 1);
        flash(mc.level, muzzle, look);
        AmmoVisuals.get(p.ammo()).draw(mc.level, muzzle, p.traces());
    }

    /** 铳口火光与烟（所有弹药共用）。 */
    private static void flash(net.minecraft.client.multiplayer.ClientLevel level, Vec3 at, Vec3 look) {
        var r = level.getRandom();
        for (int i = 0; i < 6; i++)
            level.addParticle(ParticleTypes.FLAME, at.x, at.y, at.z, look.x * .25 + (r.nextDouble() - .5) * .08,
                    look.y * .25 + (r.nextDouble() - .5) * .08, look.z * .25 + (r.nextDouble() - .5) * .08);
        // 小烟，不用大烟：第一人称瞄准时大烟正挡在准星前面。
        for (int i = 0; i < 5; i++)
            level.addParticle(ParticleTypes.SMOKE, at.x + look.x * .3, at.y + look.y * .3, at.z + look.z * .3,
                    look.x * .1 + (r.nextDouble() - .5) * .03, .02, look.z * .1 + (r.nextDouble() - .5) * .03);
        level.addParticle(ParticleTypes.POOF, at.x, at.y, at.z, look.x * .1, look.y * .1, look.z * .1);
    }
}
