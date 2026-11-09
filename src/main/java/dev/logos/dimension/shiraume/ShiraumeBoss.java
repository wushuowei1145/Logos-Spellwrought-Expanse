package dev.logos.dimension.shiraume;

import dev.logos.dimension.foxfire.FoxfireBearer;
import dev.logos.dimension.foxfire.FoxfireFlame;
import org.jspecify.annotations.Nullable;

/**
 * 白梅的两种形态（人形 {@link Shiraume}、狐形 {@link ShiraumeBeast}）共有的部分，供场地（{@link ShiraumeFight}）和命中结算用。
 * 她放的狐火是独一份的淡粉（docs/FOXFIRE.md）。
 */
public interface ShiraumeBoss extends FoxfireBearer {
    @Override
    default FoxfireFlame foxfireFlame() {
        return FoxfireFlame.SHIRAUME;
    }

    /** 刚才这一下有没有被弹反；读一次就清掉（{@link ShiraumeStrike#strike} 在格挡之后读）。 */
    boolean takeParried();

    /** 所在的封印之间（杀生石位置的 {@code BlockPos.asLong}）；不在封印之间里（命令召出来的）为 null。 */
    @Nullable Long arena();

    void setArena(@Nullable Long arena);

    /** 过场里：不动、不受伤，招式由场地指定。 */
    boolean scripted();

    void setScripted(boolean scripted);

    /** 休息中（场地里没有活着的玩家）：跪坐或趴着，不受伤，慢慢回血。 */
    boolean resting();

    void setResting(boolean resting);

    /** 由场地强制换一招（过场的跪坐、起身、跪地、拍地……）。 */
    void play(String move);

    /** 当前招式名（没有时为空串）。 */
    String move();

    /** 打开或收起 Boss 血条；{@code fill} 小于 1 时血条从这个比例往上涨（变身后的狐形）。 */
    void showBar(boolean shown, float fill);
}
