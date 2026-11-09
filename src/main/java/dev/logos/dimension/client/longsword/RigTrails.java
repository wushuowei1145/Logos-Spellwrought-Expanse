package dev.logos.dimension.client.longsword;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.logos.dimension.client.trail.TrailConfigs;
import dev.logos.dimension.client.trail.TrailStyle;
import dev.logos.dimension.client.trail.TrailStyles;
import dev.logos.dimension.client.trail.WeaponTrails;
import dev.logos.dimension.combat.CombatWeapon;
import dev.logos.dimension.combat.Moveset;
import dev.logos.dimension.longsword.LongswordPayload;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 玩家骨架的刀光（docs/WEAPON_TRAILS.md）：每帧把上一帧到这一帧之间按动作补满取样（约 1/240 秒一点），
 * 刀身两端从武器网格换到骨架、再换到世界（第三人称）或手部坐标（第一人称），送进 {@link WeaponTrails}。
 * 因为每一点都是按动作在那一刻的真实姿势取的，刀光就是刀身真正扫过的面，帧率低也不会折成直线。
 *
 * <p>出不出光：招式在 {@code combat/trails/<动作组>.json} 里列了时段就按时段，没列就看刀尖速度（第三人称骨架里量，
 * 不含走路、转身）。样式：招式指定 → 武器网格指定 → 动作组默认；拿刀的手缠着魔力时换成那种元素的刀光（{@code fixed} 的除外）。
 */
final class RigTrails {
    /** 帧间补点的间隔（秒）与一帧最多补几点。 */
    private static final double STEP = 1 / 240.0;
    private static final int MAX_STEPS = 16;
    /** 两次送点隔了这么久（秒，比如刚进入视野），不补点，从这一刻起画。 */
    private static final double GAP = .25;

    private record Key(int entity, boolean firstPerson) {}
    /** 上一次送点的时刻与那时第三人称骨架里的刀尖（量速度用）。 */
    private record Last(double time, Vector3f tip) {}
    private static final Map<Key, Last> LAST = new HashMap<>();

    private RigTrails() {}

    /** 第三人称：{@code pose} 把骨架坐标换成相对镜头的世界坐标（武器层画剑时的那个姿势）。 */
    static void thirdPerson(LongswordPayload p, int entity, double nowTicks, Matrix4fc pose, boolean shown) {
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().position();
        feed(p, entity, false, nowTicks, new Matrix4f(pose), camera, WeaponTrails.Space.WORLD, shown);
    }

    /**
     * 第一人称：{@code toHand} 把骨架坐标换成手部渲染开始时的坐标（{@code 进入时姿势⁻¹ × 画剑时姿势}），
     * 画的时候在进入时的姿势里调 {@link #submitHand}。
     */
    static void firstPerson(LongswordPayload p, int entity, double nowTicks, Matrix4fc toHand, boolean shown) {
        feed(p, entity, true, nowTicks, new Matrix4f(toHand), Vec3.ZERO, WeaponTrails.Space.HAND, shown);
    }

    /** 场景检查用：这道刀光现在的样式。 */
    static dev.logos.dimension.client.trail.@Nullable TrailStyle style(int entity, boolean firstPerson) {
        return WeaponTrails.styleOf(new Key(entity, firstPerson));
    }

    static void submitHand(int entity, PoseStack pose, SubmitNodeCollector out) {
        WeaponTrails.submitHand(new Key(entity, true), pose, out);
    }

    private static void feed(LongswordPayload p, int entity, boolean firstPerson, double nowTicks, Matrix4f toSpace, Vec3 origin,
                             WeaponTrails.Space space, boolean shown) {
        CombatWeapon weapon = CombatWeapon.byId(p.variant());
        if (weapon == null) return;
        TrailConfigs.Set set = TrailConfigs.set(weapon.animations());
        TrailConfigs.Blade blade = set == null ? null : set.blade(weapon.mesh());
        LongswordRig rig = LongswordRig.get(p.variant(), firstPerson), tpRig = LongswordRig.get(p.variant(), false);
        if (blade == null || set == null || rig == null || tpRig == null) return;
        Key key = new Key(entity, firstPerson);
        TrailConfigs.Clip clip = set.clip(p.clip());
        TrailStyle style = TrailStyles.get(style(set, clip, blade, entity));
        if (style == null) return;

        double now = nowTicks / 20;
        Last last = LAST.get(key);
        double start = last == null || now - last.time > GAP || now <= last.time ? now : last.time;
        int steps = start == now ? 1 : Math.min(MAX_STEPS, Math.max(1, (int) Math.ceil((now - start) / STEP)));
        Vector3f prevTip = last == null || start == now ? null : last.tip;
        double prevTime = start;
        Vector3f bladeFrom = blade.at(blade.from()), bladeTo = blade.at(blade.to());
        for (int i = 1; i <= steps; i++) {
            double t = start == now ? now : start + (now - start) * i / steps;
            // 第三人称骨架里的刀尖：量速度（两种视角一样的判断）
            var tp = LongswordClient.sample(p, false, t * 20);
            if (tp == null) return;
            Vector3f tpTip = LongswordRig.onWeapon(tp, bladeTo);
            if (prevTip == null) {
                var before = LongswordClient.sample(p, false, (t - 1 / 60.0) * 20);
                prevTip = before == null ? tpTip : LongswordRig.onWeapon(before, bladeTo);
                prevTime = t - 1 / 60.0;
            }
            float speed = tpTip.distance(prevTip) / 16f / (float) Math.max(1e-4, t - prevTime);
            boolean emit = shown && emits(p, tpRig, clip, set, t * 20, speed);
            var sample = firstPerson ? LongswordClient.sample(p, true, t * 20) : tp;
            if (sample == null) return;
            Vector3f base = toSpace.transformPosition(LongswordRig.onWeapon(sample, bladeFrom));
            Vector3f tip = toSpace.transformPosition(LongswordRig.onWeapon(sample, bladeTo));
            WeaponTrails.feed(key, space, t, origin.add(base.x, base.y, base.z), origin.add(tip.x, tip.y, tip.z), emit, style);
            prevTip = tpTip;
            prevTime = t;
        }
        LAST.put(key, new Last(now, prevTip));
        if (LAST.size() > 64) LAST.entrySet().removeIf(e -> now - e.getValue().time > 5);
    }

    private static boolean emits(LongswordPayload p, LongswordRig rig, TrailConfigs.Clip clip, TrailConfigs.Set set, double ticks, float speed) {
        if (clip.none()) return false;
        if (clip.windows().isEmpty()) return speed >= set.speed();
        double length = rig.length(p.clip());
        if (length < 0) return false;
        double seconds = Moveset.playbackSeconds(p.playback(), length, ticks - p.started(), p.speed(), p.offset());
        for (float[] w : clip.windows()) if (seconds >= w[0] && seconds <= w[1]) return true;
        return false;
    }

    /** 样式名：招式 → 武器 → 动作组；缠着魔力且样式不固定时换成那种元素的。 */
    private static Identifier style(TrailConfigs.Set set, TrailConfigs.Clip clip, TrailConfigs.Blade blade, int entity) {
        Identifier id = clip.style() != null ? clip.style() : blade.style() != null ? blade.style() : set.style();
        var level = Minecraft.getInstance().level;
        LivingEntity holder = level != null && level.getEntity(entity) instanceof LivingEntity living ? living : null;
        return TrailStyles.resolve(id, set.fixed(), holder);
    }

    static void clear() { LAST.clear(); }
}
