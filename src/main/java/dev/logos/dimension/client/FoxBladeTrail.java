package dev.logos.dimension.client;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.client.dissolve.Dissolves;
import dev.logos.dimension.client.longsword.LongswordRig;
import dev.logos.dimension.client.trail.BoneTrails;
import dev.logos.dimension.client.trail.TrailConfigs;
import dev.logos.dimension.client.trail.WeaponTrails;
import dev.logos.dimension.combat.CombatWeapon;
import dev.logos.dimension.foxfire.FoxfireFlame;
import dev.logos.dimension.foxfire.FoxfireWraps;
import dev.logos.dimension.npc.DivineFox;
import dev.logos.dimension.npc.NpcAnimated;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 神狐打刀的刀光（docs/WEAPON_TRAILS.md）：刀身骨骼 {@code katana_<配色>_blade} 的渲染回调里，把刀网格的刀身换到骨骼姿势里，
 * 交给 {@link BoneTrails}。神狐的刀和玩家的打刀是同一套网格（{@code fox_uchigatana_<配色>}）、同一套招式，
 * 所以刀身范围、哪一招出光都读玩家打刀的 {@code combat/trails/uchigatana.json}，不另写一份。
 *
 * <p>出不出光看神狐此刻的打刀招式（{@link DivineFox#npcCombatMove()}，去掉 {@code katana_} 前缀就是动作名）：
 * 设置里没列出的待机、拔刀、收刀、格挡、被弹反都不出光，拿和弓、刀在鞘里时根本不送点；列出的招式（四段连砍、弹刀、反击）
 * 写了时段的按时段，按速度自动出光的只在动作 timeline 里 {@code contact} 的前后出光、这段里刀尖一旦够快就亮到这段结束——
 * 神狐举刀过顶、收刀回身时刀尖也很快，只看速度会把蓄势也画成一道（没有 contact 的招式，如弹刀，才整招按速度）。招式的时刻从本机看到换招起算，
 * 并跳过动画控制器换招时那一刻的直线过渡（那不是挥砍，画出来是一块平板）。
 *
 * <p>样式：神狐的刀造成的是物理伤害（生物的普通攻击），缠着魔力时换成那种元素的刀光。神狐自己缠的是狐火
 * （{@link FoxfireWraps}：按 Logos 算是奥秘缠绕，刀身画的却是幽蓝的狐火），这时换成那种颜色的狐火刀光并固定，
 * 不让 Logos 按奥秘换成紫色——神狐一族是幽蓝的 {@link #KITSUNE}，白梅给的淡粉狐火用共用的 {@code foxfire}。
 */
final class FoxBladeTrail {
    /** 神狐的刀用的动作组（与玩家的打刀相同）。 */
    static final String ANIMATIONS = "uchigatana";
    /** 神狐一族幽蓝的狐火刀光。 */
    static final Identifier KITSUNE = LogosDimension.id("foxfire_kitsune");
    /** 白梅淡粉的狐火刀光（共用样式）。 */
    static final Identifier SHIRAUME = LogosDimension.id("foxfire");
    /** 四套刀装的武器网格名（按刀装下标），每帧用，不拼字符串。 */
    private static final String[] MESHES = DivineFox.KATANAS.stream().map(v -> "fox_uchigatana_" + v).toArray(String[]::new);

    /**
     * 本帧这把刀的刀光：在 addRenderData 里按实体算好，骨骼回调里只做坐标换算。
     * {@code base}/{@code tip} 是刀身两端在刀身骨骼里的坐标（格，已乘好刀的缩放）。
     * {@code window}：按招式算出的出光时段——假为不出光，真为出光，null 为刀尖够快（{@code speed} 格/秒）才出光；
     * {@code latch} 为真时（接触前后的那一段）一旦出光就亮到这段结束。
     */
    record Frame(String key, DivineFox holder, Vector3f base, Vector3f tip, Identifier style, boolean fixed,
                 @Nullable Boolean window, boolean latch, float speed) {
        /**
         * 骨骼回调里调：{@code pose} 是刀身骨骼此刻的姿势（{@code poseStack.last().pose()}）。
         * <ul>
         * <li>接触前后那一段里刀尖一旦够快就一直出光到这段结束：神狐的一刀往往两三帧就扫完，扫完后刀尖慢下来，
         *     只看每帧的速度会在中间断开一下，收尾那一截另起一段，看起来像错开的两片。</li>
         * <li>神狐的动作是逐帧烘焙的欧拉角，个别相邻两帧之间角度表示翻了一面（如 X 从 72° 跳到 219°），
         *     GeckoLib 线性插值时那一两帧刀会闪到别处；这种帧刀尖快得不可能（{@link #POP_SPEED}），不送进刀光。</li>
         * </ul>
         */
        void feed(Matrix4fc pose) {
            // 光影包阴影通道里的姿势是太阳视角的，量出来的刀尖不对（BoneTrails 也会丢掉这一次）
            if (Dissolves.shadowPass()) return;
            var mc = Minecraft.getInstance();
            Vector3f t = pose.transformPosition(new Vector3f(tip));
            Vec3 relative = mc.gameRenderer.getMainCamera().position().add(t.x, t.y, t.z)
                    .subtract(holder.getPosition(mc.getDeltaTracker().getGameTimeDeltaPartialTick(false)));
            double now = WeaponTrails.now();
            Tip last = LAST_TIP.get(key);
            double dt = last == null ? 0 : now - last.time;
            // 刀尖相对神狐脚下的速度（走路、突进不算）；隔得太久（刚进入视野）或同一帧画第二次时不算
            double tipSpeed = dt > 1e-4 && dt < .1 ? relative.distanceTo(last.relative) / dt : 0;
            // 闪帧不记，下一帧仍和最近一次正常的帧比
            if (window != Boolean.FALSE && tipSpeed > POP_SPEED) return;
            boolean on = window != null ? window : latch && last != null && last.on || tipSpeed >= speed;
            if (LAST_TIP.size() >= 256) LAST_TIP.clear();
            LAST_TIP.put(key, new Tip(now, relative, on));
            BoneTrails.feed(key, holder, pose, base, tip, style, fixed, on, speed);
        }
    }

    /** 刀尖相对神狐快过这么多（格/秒）就是动作数据里欧拉角翻面造成的闪帧（真正挥砍最快约 55 格/秒）。 */
    private static final double POP_SPEED = 75;
    private record Tip(double time, Vec3 relative, boolean on) {}
    /** 每把刀上一次送进刀光的刀尖（相对神狐脚下），以及那时是否在出光。 */
    private static final Map<String, Tip> LAST_TIP = new HashMap<>();
    /** 自动出光的招式在接触时刻之前、之后多少秒内出光（真正的一刀在接触前约 0.03–0.1 秒刀尖最快）。 */
    private static final double BEFORE_CONTACT = .12, AFTER_CONTACT = .05;
    /** 换招时动画控制器的过渡（刻），同 NpcAnimations 里 combat 控制器的过渡长度。 */
    private static final double TRANSITION_TICKS = 1;
    /** 每只神狐：本机第一次看到当前招式时，实体的刻数（含帧内插值）。 */
    private record Seen(NpcAnimated.CombatMove move, double at) {}
    private static final Map<Integer, Seen> SEEN = new HashMap<>();
    /** 这一帧在出光时段里的神狐（场景检查用）。 */
    private static final Set<Integer> EMITTING = new HashSet<>();

    private FoxBladeTrail() {}

    /** 场景检查用：这只神狐这一帧是否在出光的时段里（还要刀尖够快才真的出光）。 */
    static boolean emitting(int entity) {
        return EMITTING.contains(entity);
    }

    /** 场景检查用：这只神狐的刀光的键（{@code WeaponTrails.styleOf}）。 */
    static String key(int entity) {
        return "fox_katana:" + entity;
    }

    /**
     * 这只神狐本帧的刀光；刀不在手上、没有刀光设置时为 null。{@code scale} 是刀网格单位换成骨骼里的格
     * （刀 × 1.6 / 16，同刀身狐火的换算：物品模型的原点平移抵消后，网格原点就是骨骼原点，刀尖朝 +Y）。
     */
    static @Nullable Frame of(DivineFox fox, float partialTick, float scale) {
        TrailConfigs.Set set = TrailConfigs.set(ANIMATIONS);
        if (set == null) return null;
        TrailConfigs.Blade blade = set.blade(MESHES[fox.katanaVariant()]);
        if (blade == null) return null;
        var move = fox.npcCombatMove();
        String clipName = move != null && move.animation().startsWith(DivineFox.KATANA_PREFIX)
                ? move.animation().substring(DivineFox.KATANA_PREFIX.length()) : null;
        TrailConfigs.Clip clip = clipName == null ? null : set.clip(clipName);
        double ticks = clipTicks(fox, move, partialTick);
        Boolean window;
        boolean latch = false;
        // 换招时动画控制器先用一刻从上一招的姿势直线过渡到这一招的起手（NpcAnimations 的 combat 控制器），这段不是挥砍，不出光
        if (clip == null || clip.none() || ticks < TRANSITION_TICKS) window = Boolean.FALSE;
        else {
            double seconds = (ticks - TRANSITION_TICKS) / 20 * move.speed();
            if (!clip.windows().isEmpty()) window = inWindow(clip.windows(), seconds);
            else {
                // 按速度自动出光的招式：只在动作文件 timeline 里 contact 的前后出光，这段里再看刀尖速度。
                // 神狐的刀尖在蓄势、收势时也走得很快（举刀过顶、收刀回身），只看速度会把蓄势也画成一道，和真正的一刀叠在一起
                float[] contacts = contacts(fox, clipName);
                latch = contacts.length > 0 && nearContact(contacts, seconds);
                window = contacts.length == 0 || latch ? null : Boolean.FALSE;
            }
        }
        if (window == Boolean.FALSE) EMITTING.remove(fox.getId());
        else {
            if (EMITTING.size() >= 256) EMITTING.clear();
            EMITTING.add(fox.getId());
        }
        // 样式：招式 → 网格 → 动作组；狐火缠绕换成那种颜色的狐火（固定），其他缠绕交给 BoneTrails 按元素换
        Identifier style = clip != null && clip.style() != null ? clip.style() : blade.style() != null ? blade.style() : set.style();
        boolean fixed = set.fixed();
        FoxfireFlame flame = fixed ? null : FoxfireWraps.flame(fox);
        if (flame != null) {
            style = flame == FoxfireFlame.SHIRAUME ? SHIRAUME : KITSUNE;
            fixed = true;
        }
        return new Frame(key(fox.getId()), fox, new Vector3f(0, blade.from() * scale, 0), new Vector3f(0, blade.to() * scale, 0),
                style, fixed, window, latch, set.speed());
    }

    /**
     * 这一招在本机从头播了多少刻：客户端看到招式换了（动画名或开始刻变了）的那一刻，动画控制器从头播放，
     * 所以从那一刻算，而不是按服务端的开始刻（同步晚到一两刻）。用实体自己的刻数，暂停时不走。没有招式时为 -1。
     */
    private static double clipTicks(DivineFox fox, NpcAnimated.CombatMove move, float partialTick) {
        double age = fox.tickCount + partialTick;
        if (move == null) {
            SEEN.remove(fox.getId());
            return -1;
        }
        Seen seen = SEEN.get(fox.getId());
        if (seen == null || !move.equals(seen.move) || age < seen.at) {
            // 只是记一个数，神狐多了（或换了世界）整个清掉重记也无妨
            if (SEEN.size() >= 256) SEEN.clear();
            seen = new Seen(move, age);
            SEEN.put(fox.getId(), seen);
        }
        return age - seen.at;
    }

    /** 写了出光时段的招式：此刻（招式里的秒）是否在某一段里。 */
    private static boolean inWindow(List<float[]> windows, double seconds) {
        for (float[] w : windows) if (seconds >= w[0] && seconds <= w[1]) return true;
        return false;
    }

    /** 这一招的接触时刻（招式里的秒）：神狐的打刀动作与玩家的同源，读玩家骨架的动作文件（同一份 timeline）；没有时为空。 */
    private static float[] contacts(DivineFox fox, String clip) {
        LongswordRig rig = LongswordRig.get(CombatWeapon.id(fox.getMainHandItem()), false);
        return rig == null ? new float[0] : rig.markers(clip, "contact");
    }

    private static boolean nearContact(float[] contacts, double seconds) {
        for (float c : contacts) if (seconds >= c - BEFORE_CONTACT && seconds <= c + AFTER_CONTACT) return true;
        return false;
    }
}
