package dev.logos.dimension.client.trail;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

/**
 * NPC 的刀光入口（docs/WEAPON_TRAILS.md）：刀是 GeckoLib 模型里的一根骨骼时，在那根骨骼的渲染回调里调 {@link #feed}。
 * 骨骼回调只给得出这一帧的姿势，所以这里按帧送点，由 {@link WeaponTrails#feedSmoothed} 在帧间补成圆弧。
 *
 * <p>出不出光可以由调用方按动作名与时刻决定（传 true / false），也可以传 null 让这里看刀尖速度：
 * 量的是刀尖相对持刀者脚下的速度，所以走路、被击退不算挥刀。
 */
public final class BoneTrails {
    /** 帧间补点：刀尖每走这么多格补一个。 */
    private static final float STEP = .12f;
    /** 两次送点隔了这么久（秒，比如被视锥剔除后又回来），先断开，不跨着空档连一条直的。 */
    private static final double GAP = .25;
    private record Last(double time, Vec3 relative) {}
    private static final Map<Object, Last> LAST = new HashMap<>();

    private BoneTrails() {}

    /**
     * {@code pose}：骨骼回调此刻的姿势（把骨骼坐标换成相对镜头的世界坐标，即 {@code poseStack.last().pose()}）；
     * {@code base}/{@code tip}：刀身两端在这个姿势里的坐标（已乘好模型缩放）。
     * {@code style}：武器默认样式；{@code fixed} 为假时持刀者缠着魔力就换成那种元素的刀光。
     * {@code emit} 为 null 时刀尖相对持刀者的速度到 {@code speed}（格/秒）才出光。
     */
    public static void feed(Object key, Entity holder, Matrix4fc pose, Vector3fc base, Vector3fc tip, Identifier style, boolean fixed,
                            @Nullable Boolean emit, float speed) {
        TrailStyle resolved = TrailStyles.get(TrailStyles.resolve(style, fixed, holder instanceof LivingEntity living ? living : null));
        if (resolved == null) return;
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().position();
        Vector3f b = pose.transformPosition(new Vector3f(base)), t = pose.transformPosition(new Vector3f(tip));
        Vec3 worldBase = camera.add(b.x, b.y, b.z), worldTip = camera.add(t.x, t.y, t.z);
        double now = WeaponTrails.now();
        float pt = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
        Vec3 relative = worldTip.subtract(holder.getPosition(pt));
        boolean on;
        if (emit != null) on = emit;
        else {
            Last last = LAST.get(key);
            double dt = last == null ? 0 : now - last.time;
            on = dt > 1e-4 && dt < .25 && relative.distanceTo(last.relative) / dt >= speed;
        }
        Last previous = LAST.get(key);
        if (previous != null && now - previous.time > GAP)
            WeaponTrails.feedSmoothed(key, WeaponTrails.Space.WORLD, now, worldBase, worldTip, false, resolved, STEP);
        LAST.put(key, new Last(now, relative));
        if (LAST.size() > 128) LAST.entrySet().removeIf(e -> now - e.getValue().time > 5);
        WeaponTrails.feedSmoothed(key, WeaponTrails.Space.WORLD, now, worldBase, worldTip, on, resolved, STEP);
    }

    static void clear() { LAST.clear(); }
}
