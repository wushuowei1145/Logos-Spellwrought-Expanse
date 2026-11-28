package dev.logos.dimension.client.ultimate;

import dev.logos.dimension.ultimate.UltimatePayloads;
import org.jspecify.annotations.Nullable;

/**
 * 一段奥义在客户端的画面（docs/ULTIMATES.md「画面」）：服务端开演时发来 {@link UltimatePayloads.Cast}，客户端按演的人在她的档案里
 * 找到演出工厂（同伴的 {@code CompanionStyle#ultimate}，玩家的 {@code HeroCutin#finishers}），新开一个，
 * 每个客户端刻调 {@link #tick}、每帧调 {@link #frame}，演完或被打断时调 {@link #end}。
 *
 * <p>这里只放客户端才做得了的：后期通道、贴在世界里的特效、跟着镜头走的东西。动作、伤害、运镜（{@code CameraShots}）、
 * 黑白闪（{@code ScreenEffects}）这些服务端能定的写在服务端脚本里，所有看得见的人都一样。
 */
public interface UltimateShow {
    /** 她的档案里放的：每次开演新开一个。 */
    @FunctionalInterface
    interface Factory {
        /** 这一次的画面；返回 null 表示这一次没有客户端画面。 */
        @Nullable UltimateShow begin(UltimatePayloads.Cast cast);
    }

    /** 没有客户端画面（专属演出做好以前）。 */
    Factory NONE = cast -> null;

    /** 第 {@code age} 个客户端刻（0 起，开演那一刻是 0）。返回 false 时提前收起。 */
    boolean tick(UltimatePayloads.Cast cast, int age);

    /** 每帧（{@code age} 带着帧内插值）：要按帧画的东西在这里交给各自的渲染器。 */
    default void frame(UltimatePayloads.Cast cast, float age) {}

    /**
     * 她自己的全屏后期（浮世绘、画面凝进琥珀、碎镜……）在这里画：每帧在所有后期之后、过场叠层（{@link UltimateFilm}）之前调，
     * 不用去改公共的渲染顺序。只有这一段是本机玩家下的令时才该画（{@code UltimateFilm#mine}）。
     */
    default void screen(UltimatePayloads.Cast cast, float age) {}

    /** 收起：演完、被打断、离开世界。 */
    default void end(UltimatePayloads.Cast cast, boolean interrupted) {}
}
