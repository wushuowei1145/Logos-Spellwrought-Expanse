package dev.logos.dimension.client.shiraume;

import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.animation.AnimationController;
import com.geckolib.animation.state.AnimationPoint;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.logos.dimension.client.trail.BoneTrails;
import dev.logos.dimension.client.trail.TrailConfigs;
import dev.logos.dimension.item.RyukaItem;
import dev.logos.dimension.npc.NpcAnimated;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 白梅手里（狐形是嘴里）流火的刀光（docs/WEAPON_TRAILS.md「给 NPC 的刀加刀光」）：在 {@code Weapon} 骨骼回调里把刀身两端
 * 换到世界坐标交给 {@link BoneTrails}。刀身范围、样式与玩家的流火读同一份 {@code combat/trails/ryuka.json}。
 *
 * <p>出不出光按模型上正在播的战斗动作与它播到的秒数：只有挥刀的那一段出光（{@link #WINDOWS}，取自
 * tools/shiraume_moves.py 里每一刀 {@code arc} 的起止），起手、蓄势、定住、待机、走路都不出；刀还没凝完（起身时）也不出。
 * 时刻取 GeckoLib {@code combat} 控制器自己的（画面上的真实时刻），不按招式的开始刻推算：客户端收到换招要晚一两刻，
 * 而一刀只挥 0.1 秒左右，按开始刻推算会落在挥刀前的停顿里，扫出的面是零。
 */
public final class ShiraumeTrails {
    /** 播战斗动作的 GeckoLib 控制器名（{@link dev.logos.dimension.npc.NpcAnimations#registerControllers}）。 */
    private static final String COMBAT = "combat";

    /**
     * 各招出光的时段（动作秒，[开始, 结束]）：GeckoLib 动画名 → 挥刀的那一段。人形的招式名带前缀 {@code ryuka_}，
     * 狐形带 {@code fox_}；招式文件里写了 {@code clip} 的（{@code tsuki_kiriage} 播 {@code tsuki}）按动画名算。
     */
    static final Map<String, float[]> WINDOWS = Map.ofEntries(
            Map.entry("ryuka_kesa", new float[]{.64f, .77f}),
            Map.entry("ryuka_gyaku", new float[]{.52f, .65f}),
            Map.entry("ryuka_yoko", new float[]{.72f, .87f}),
            Map.entry("ryuka_turn_sweep", new float[]{.26f, .39f}),
            Map.entry("ryuka_tsuki", new float[]{.56f, .66f}),
            Map.entry("ryuka_ichimonji_cut", new float[]{.27f, .37f}),
            Map.entry("ryuka_ichimonji_cut_combo", new float[]{.27f, .37f}),
            Map.entry("ryuka_tsubame_1", new float[]{.42f, .53f}),
            Map.entry("ryuka_tsubame_2", new float[]{.34f, .45f}),
            Map.entry("ryuka_sanren_1", new float[]{.32f, .43f}),
            Map.entry("ryuka_sanren_2", new float[]{.12f, .23f}),
            Map.entry("ryuka_sanren_3", new float[]{.27f, .4f}),
            Map.entry("ryuka_kiriage", new float[]{.24f, .35f}),
            Map.entry("ryuka_deflect", new float[]{.04f, .13f}),
            Map.entry("ryuka_recover_slash", new float[]{.15f, .26f}),
            Map.entry("ryuka_throw_kiriage", new float[]{.04f, .15f}),
            // 狐形：叼着刀把头往左一甩、再横着扫到右边
            Map.entry("fox_bite_slash", new float[]{.58f, .76f}));

    private ShiraumeTrails() {}

    /** 动画 {@code animation} 出光的时段（秒，[开始, 结束]）；不出光的动画为 null。 */
    public static float @Nullable [] window(String animation) {
        float[] w = WINDOWS.get(animation);
        return w == null ? null : w.clone();
    }

    /** 动画 {@code animation} 播到 {@code seconds} 秒时是不是正在挥刀的那一段。 */
    static boolean swinging(@Nullable String animation, double seconds) {
        float[] w = animation == null ? null : WINDOWS.get(animation);
        return w != null && seconds >= w[0] && seconds <= w[1];
    }

    /** 这只 NPC 的模型此刻是不是正在挥刀。 */
    static boolean swinging(Entity npc) {
        AnimationPoint point = point(npc);
        return point != null && swinging(point.animation().name(), point.animTime());
    }

    /** 模型上的战斗动作正在播 {@code animation} 时，它播到的秒数；没在播这个动作时为 -1（场景截图挑时刻用）。 */
    public static double clipTime(Entity npc, String animation) {
        AnimationPoint point = point(npc);
        return point != null && point.animation().name().equals(animation) ? point.animTime() : -1;
    }

    /**
     * GeckoLib {@code combat} 控制器（{@link dev.logos.dimension.npc.NpcAnimations}）当前的动画与时刻。没有战斗动作时
     * （比如狐形倒下）控制器停住，最后的时刻会留着，这时不算。
     */
    private static @Nullable AnimationPoint point(Entity npc) {
        if (!(npc instanceof NpcAnimated animated) || animated.npcCombatMove() == null) return null;
        AnimatableManager<?> manager = animated.getAnimatableInstanceCache().getManagerForId(npc.getId());
        AnimationController<?> combat = manager == null ? null : manager.getAnimationControllers().get(COMBAT);
        return combat == null ? null : combat.getCurrentAnimationPoint();
    }

    /**
     * 在 {@code Weapon} 骨骼回调里送一次刀身。{@code pose} 是回调此刻的姿势（模型像素 / 16，原点在骨骼枢轴）；
     * {@code offset} 是网格原点相对枢轴的位置（网格单位），与 {@link RyukaOnBone#submit} 的相同。
     * {@code emit} 为假时只断开当前这一道（刀还在凝聚、没在挥刀）。
     */
    static void feed(PoseStack pose, Vector3f offset, int entity, boolean emit) {
        var level = Minecraft.getInstance().level;
        var holder = level == null ? null : level.getEntity(entity);
        TrailConfigs.Set set = TrailConfigs.set(RyukaItem.WEAPON.animations());
        TrailConfigs.Blade blade = set == null ? null : set.blade(RyukaItem.WEAPON.mesh());
        if (holder == null || blade == null) return;
        float k = RyukaOnBone.SCALE / 16;
        // 刀身两端：流火向刀背弯，刀尖偏向 −X（combat/trails/ryuka.json 的 tip_x，和玩家手里的一样）
        Vector3f base = blade.at(blade.from()).add(offset).mul(k);
        Vector3f tip = blade.at(blade.to()).add(offset).mul(k);
        BoneTrails.feed("shiraume_ryuka:" + entity, holder, pose.last().pose(), base, tip,
                blade.style() != null ? blade.style() : set.style(), set.fixed(), emit, set.speed());
    }
}
