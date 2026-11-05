package dev.logos.dimension.npc;

import com.geckolib.animatable.GeoEntity;

import net.minecraft.resources.Identifier;

/**
 * 使用 {@link NpcAnimations} 动画驱动的 NPC 实体实现此接口（实体本身须是 {@code LivingEntity}）。
 *
 * <p>动画文件按 GeckoLib 默认路径放在 {@code geckolib/animations/entity/<名>.animation.json}，
 * 动画名用车万女仆的同名动画（{@code idle}、{@code walk} 等，见 docs/NPC_ANIMATIONS.md）。
 */
public interface NpcAnimated extends GeoEntity {
    /** 动画资源名，与 NpcRenderer 的资源名相同，例如 {@code logos_dimension:elodie}。 */
    Identifier npcAnimationAsset();

    /** 是否处于坐下姿势（对应 TLM 的 {@code sit}）；由实体自己的状态决定。 */
    default boolean isNpcSitting() {
        return false;
    }

    /**
     * 剧情动作（对应 TLM 的 misc 控制器，例如 {@code wave}、{@code talk}）；没有时返回 null。
     * 名字不在动画文件里时不播放。
     */
    default String npcMiscAnimation() {
        return null;
    }

    /**
     * 是否在瞄准（拉弓、伸手施法）：为真时渲染器把视线的俯仰与相对身体的偏航叠加到 {@code Arm} 骨骼上，
     * 双臂连同手里的弓一起指向视线方向。动画里只做水平的姿势。
     */
    default boolean npcAiming() {
        return false;
    }

    /**
     * 当前替换常态眼嘴的动画专用表情（零件表 {@code expressions} 里的 id），没有为 null。
     * 渲染器按它显示表情、隐藏常态五官（{@link NpcParts.Race#hiddenGroups(java.util.Map, String)}）。
     */
    default String npcExpression() {
        return null;
    }

    /**
     * 正在播放的战斗动作（例如打刀的 {@code katana_slash_1}）：服务端招式状态机换招时更新，
     * {@code start} 是换招的游戏刻，换招即从头播放；{@code speed} 是播放速度。没有时返回 null。
     * 由 {@code combat} 控制器播放，盖过手持、挥手与剧情动作的同一骨骼。
     */
    default CombatMove npcCombatMove() {
        return null;
    }

    /** 一段战斗动作：动画名、开始的游戏刻、播放速度、是否循环（循环的一直播，其余播完停在末帧）。 */
    record CombatMove(String animation, long start, float speed, boolean loop) {}
}
