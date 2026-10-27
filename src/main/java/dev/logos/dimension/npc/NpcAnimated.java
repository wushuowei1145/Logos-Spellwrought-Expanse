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
}
