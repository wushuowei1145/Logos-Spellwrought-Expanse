package dev.logos.dimension.client.ultimate;

import dev.logos.dimension.ultimate.UltimatePayloads;
import org.jspecify.annotations.Nullable;

/**
 * 还没做专属过场的人先用的画面（配服务端的占位脚本）：起手压暗一下，镜头绕着她转小半圈推近；出手那一拍切到目标，
 * 往外喷的速度线加一下抖动；最后用她的东西擦镜回到玩家视角。也是 {@link UltimateFilm} 最小的用法。
 */
public final class StandInShow implements UltimateShow {
    private final UltimateFilm film;

    private StandInShow(UltimateFilm film) {
        this.film = film;
    }

    /** 擦镜用 {@code wipe}，颜色在 {@code color}、{@code color2} 之间。 */
    public static UltimateShow.Factory of(UltimateFilm.Wipe wipe, int color, int color2) {
        return cast -> {
            int length = cast.length(), impact = Math.clamp(cast.impact(), 1, Math.max(1, length - 2));
            int wipeAt = Math.max(impact + 2, length - 10);
            UltimateFilm film = UltimateFilm.of(cast)
                    .darken(0, impact, 3, 4, .45f)
                    .cut(0, impact, s -> s.look(s.around(s.chest(), s.lerp(35, 115), s.lerp(3.2f, 2.2f), .35f), s.chest(), s.lerp(0, 7), 48))
                    .cut(impact, length, s -> s.look(s.around(s.targetChest(), s.lerp(-25, 15), 5.5f, 1.6f), s.targetChest(), 0, 62))
                    .lines(impact - 1, impact + 7, 2, UltimateFilm.Lines.RADIAL, .85f, 0, 0xFFFFFF)
                    .shake(impact, impact + 6, 2, .18f)
                    .wipe(wipeAt, length, wipe, color, color2);
            return film.play() ? new StandInShow(film) : null;
        };
    }

    @Override
    public boolean tick(UltimatePayloads.Cast cast, int age) {
        return age < cast.length();
    }

    @Override
    public void end(UltimatePayloads.Cast cast, boolean interrupted) {
        if (interrupted) film.stop();
    }

    /** 测试用。 */
    public @Nullable UltimateFilm film() {
        return film;
    }
}
