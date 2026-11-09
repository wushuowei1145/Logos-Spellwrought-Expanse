package dev.logos.dimension.client.longsword;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.client.trail.BoneTrails;
import dev.logos.dimension.client.trail.WeaponTrails;
import dev.logos.dimension.combat.CombatWeapon;
import dev.logos.dimension.item.LingxiaoSwordItem;
import dev.logos.dimension.lingxiao.FlightRules;
import dev.logos.dimension.lingxiao.FlyingSword;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 御剑时脚下的剑：载具自己画，不跟骑手的骨架。剑尖朝同步来的偏航和俯仰（服务端让它顺着飞行方向转，见 {@link FlightRules}），
 * 剑面朝上，网格里的踏脚点落在两脚中间。骑手的起飞动作带 {@code handoff} 标记，播到那一刻之前剑还在手里，这里不画。
 * 飞起来时剑格处横着拖出一道淡淡的剑气（{@code combat/trail_styles/lingxiao_flight.json}，见 docs/WEAPON_TRAILS.md）。
 */
public final class FlyingSwordRenderer extends EntityRenderer<FlyingSword, FlyingSwordRenderer.State> {
    /** 剑放大的倍数和踏脚点在网格里的高度，与 tools/lingxiao_moves.py 的 FLIGHT_SCALE、FOOT_ON_BLADE 一致。 */
    public static final float SCALE = 1.8f, FOOT_ON_BLADE = 6.7f;
    /** 剑身半厚（网格单位）：剑面顶住鞋底。 */
    private static final float HALF_THICKNESS = .21f;
    /** 剑穗离开剑柄延长线的最大角度（度），再大就甩进剑柄里了。 */
    private static final float MAX_SWING = 115;
    /** 网格坐标换到剑的局部坐标：剑尖 +Y 朝前（+Z），剑面法线 +Z 朝上（+Y），刃宽 X 落在 -X（列主序）。 */
    private static final Matrix4f LAY_FLAT = new Matrix4f(-1, 0, 0, 0, 0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 1);
    /**
     * 御剑的剑气：剑格处横跨剑身的一段（网格单位，两侧各约一掌宽），剑往前飞时扫出一道平铺在剑后的带子。
     * 剑尖朝着飞行方向，所以剑身自己的两端只会沿一条线走、扫不出面，要取横着的一段。
     */
    private static final Vector3f TRAIL_LEFT = new Vector3f(-2.6f, 2.3f, 0), TRAIL_RIGHT = new Vector3f(2.6f, 2.3f, 0);
    private static final Identifier TRAIL_STYLE = LogosDimension.id("lingxiao_flight");
    /** 飞得比这快（格/秒）才拖剑气，悬停、慢慢挪不拖。 */
    private static final float TRAIL_SPEED = 4;
    /** 隔了这么久（秒）没送剑气就先断开：帧率很低时两帧之间也可能有零点一秒多，不能取太小。 */
    private static final double TRAIL_GAP = .25;
    /** 上一次送剑气的时刻：剑被视锥剔除、隔了一阵才又画时，先断开旧的一道，免得一条直线连过去。 */
    private final Map<Integer, Double> trailFed = new HashMap<>();

    public static final class State extends EntityRenderState {
        int entity = -1, weapon = -1;
        float yaw, pitch;
        boolean shown;
        final Vector3f velocity = new Vector3f();
    }

    public FlyingSwordRenderer(EntityRendererProvider.Context context) {
        super(context);
        shadowRadius = 0;
    }

    @Override public State createRenderState() { return new State(); }

    @Override protected AABB getBoundingBoxForCulling(FlyingSword sword) { return sword.swordBounds(); }

    @Override public void extractRenderState(FlyingSword sword, State state, float partial) {
        super.extractRenderState(sword, state, partial);
        state.entity = sword.getId();
        state.yaw = sword.getViewYRot(partial);
        state.pitch = sword.getViewXRot(partial);
        var rider = sword.getFirstPassenger();
        int weapon = rider instanceof LivingEntity living ? CombatWeapon.id(living.getMainHandItem()) : -1;
        state.weapon = weapon >= 0 ? weapon : LingxiaoSwordItem.WEAPON.id();
        var level = Minecraft.getInstance().level;
        double now = level == null ? 0 : level.getGameTime() + partial;
        state.shown = rider == null || LongswordClient.handedOff(rider.getUUID(), now);
        state.velocity.set((float) (sword.getX() - sword.xo), (float) (sword.getY() - sword.yo), (float) (sword.getZ() - sword.zo));
    }

    @Override public void submit(State state, PoseStack pose, SubmitNodeCollector out, CameraRenderState camera) {
        var rig = state.shown ? LongswordRig.get(state.weapon, false) : null;
        if (rig != null) {
            float s = rig.weaponScale() * SCALE / 16;
            var rotation = new Quaternionf().rotationY((float) Math.toRadians(-state.yaw)).rotateX((float) Math.toRadians(state.pitch));
            var mesh = new Matrix4f().translate(0, (float) FlightRules.SEAT_HEIGHT - HALF_THICKNESS * s, 0).rotate(rotation)
                    .mul(LAY_FLAT).scale(s).translate(0, -FOOT_ON_BLADE, 0);
            rig.submitLoose(pose, out, state.lightCoords, mesh, tassels(rig, state, rotation));
            trail(state, new Matrix4f(pose.last().pose()).mul(mesh));
        }
        super.submit(state, pose, out, camera);
    }

    /** 送一次剑气：{@code toCamera} 把网格坐标换成相对镜头的世界坐标。 */
    private void trail(State state, Matrix4f toCamera) {
        var level = Minecraft.getInstance().level;
        var sword = level == null ? null : level.getEntity(state.entity);
        if (sword == null) return;
        String key = "lingxiao_flight:" + state.entity;
        double now = WeaponTrails.now();
        Double last = trailFed.put(state.entity, now);
        if (trailFed.size() > 16) trailFed.entrySet().removeIf(e -> now - e.getValue() > 5);
        if (last != null && now - last > TRAIL_GAP)
            BoneTrails.feed(key, sword, toCamera, TRAIL_LEFT, TRAIL_RIGHT, TRAIL_STYLE, true, false, 0);
        boolean emit = state.velocity.length() * 20 > TRAIL_SPEED;
        BoneTrails.feed(key, sword, toCamera, TRAIL_LEFT, TRAIL_RIGHT, TRAIL_STYLE, true, emit, 0);
    }

    /**
     * 剑穗：朝下垂，飞得越快越往后飘，再加一点越往下越大的抖动。每节的朝向在网格坐标里算好，
     * 按父节的末端接上（网格坐标里的变换，交给 {@link LongswordRig#submitLoose}）。
     */
    private static Map<String, Matrix4f> tassels(LongswordRig rig, State state, Quaternionf rotation) {
        Map<String, Matrix4f> out = new HashMap<>();
        Map<String, Vector3f> posed = new HashMap<>();
        Map<String, Quaternionf> turned = new HashMap<>();
        float speed = state.velocity.length(), pace = Math.min(1.5f, speed / (float) FlightRules.SPEED);
        var hang = new Vector3f(0, -1, 0);
        if (speed > 1e-4f) hang.add(new Vector3f(state.velocity).normalize().mul(-1.6f * pace));
        hang.normalize();
        var side = rotation.transform(new Vector3f(1, 0, 0));
        var toLocal = new Quaternionf(rotation).conjugate();
        var rest = new Vector3f(0, -1, 0);
        for (var entry : rig.ownBones().entrySet()) {
            String name = entry.getKey();
            if (!name.startsWith("Tassel")) continue;
            var bone = entry.getValue();
            int depth = 0;
            for (var up = rig.ownBones().get(bone.parent()); up != null; up = rig.ownBones().get(up.parent())) depth++;
            float phase = state.ageInTicks * .45f + depth * .9f + (name.contains("Left") ? 0 : 1.7f);
            float flutter = (.12f + .25f * Math.min(1, pace)) * (depth + 1) / 3f;
            var world = new Vector3f(hang).add(new Vector3f(side).mul((float) Math.sin(phase) * flutter))
                    .add(0, (float) Math.cos(phase * 1.3f) * flutter * .5f, 0).normalize();
            // 世界方向 → 剑的局部坐标 → 网格坐标（LAY_FLAT 的转置）。
            var local = toLocal.transform(world);
            var dir = cone(new Vector3f(-local.x, local.z, local.y), rest);
            var q = new Quaternionf().rotationTo(rest, dir);
            var at = new Vector3f(bone.pivot());
            var parent = posed.get(bone.parent());
            if (parent != null) {
                var parentPivot = rig.ownBones().get(bone.parent()).pivot();
                at = new Vector3f(parent).add(turned.get(bone.parent()).transform(new Vector3f(bone.pivot()).sub(parentPivot)));
            }
            out.put(name, new Matrix4f().translation(at).rotate(q).translate(-bone.pivot().x, -bone.pivot().y, -bone.pivot().z));
            posed.put(name, at);
            turned.put(name, q);
        }
        return out;
    }

    /** 把方向限在离剑柄延长线 {@link #MAX_SWING} 度以内。 */
    private static Vector3f cone(Vector3f dir, Vector3f rest) {
        float limit = (float) Math.toRadians(MAX_SWING), angle = rest.angle(dir);
        if (angle <= limit) return dir;
        var axis = new Vector3f(rest).cross(dir);
        if (axis.lengthSquared() < 1e-8f) axis.set(1, 0, 0);
        return new Quaternionf().rotationAxis(limit, axis.normalize()).transform(new Vector3f(rest));
    }
}
