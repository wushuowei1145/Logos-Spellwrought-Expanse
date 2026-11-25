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
     * 把打掉的子弹藏起来（骨骼缩成 0）。转轮最上面一格对着枪膛，开一枪转 60°：平时还剩 n 发时 Round0..n-1 有弹；
     * 开枪动作里过了 {@code fire} 那一刻，刚打出的 Round0 已经空了、还没转过去，所以藏 Round0 和 Round(n+1) 以后的。
     * 换弹动作里，左手拿着的新转轮满弹，被顶出去的旧转轮（备用转轮）按换之前的发数。
     */
    public static LongswordRig.Sample rounds(LongswordPayload p, LongswordRig rig, LongswordRig.Sample sample, double now) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || rig.ownBones().get("Round0") == null) return sample;
        var player = mc.level.getPlayerByUUID(p.player());
        if (player == null || !holding(player)) return sample;
        var gun = player.getMainHandItem();
        var firearm = Firearms.firearm(gun);
        int rounds = Firearms.load(gun).rounds();
        double length = rig.length(p.clip());
        double t = length < 0 ? 0 : Moveset.playbackSeconds(p.playback(), length, now - p.started(), p.speed(), p.offset());
        float[] feed = rig.markers(p.clip(), "feed"), fire = rig.markers(p.clip(), "fire");
        boolean[] live = new boolean[6], spare = new boolean[6];
        if (feed.length > 0 && t < feed[0]) {
            for (int k = 0; k < 6; k++) { live[k] = k < firearm.capacity(); spare[k] = k < rounds; }
        } else if (fire.length > 0 && t >= fire[0]) {
            for (int k = 1; k < 6; k++) live[k] = k <= rounds;
        } else {
            for (int k = 0; k < 6; k++) live[k] = k < rounds;
        }
        Map<String, Matrix4f> bones = new HashMap<>(sample.bones());
        for (int k = 0; k < 6; k++) {
            hide(bones, "Round" + k, !live[k]);
            hide(bones, "SpareRound" + k, !spare[k]);
        }
        return new LongswordRig.Sample(Map.copyOf(bones), sample.bodyRotation(), sample.weapon(), sample.turn(), sample.rightLeg(), sample.leftLeg());
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
