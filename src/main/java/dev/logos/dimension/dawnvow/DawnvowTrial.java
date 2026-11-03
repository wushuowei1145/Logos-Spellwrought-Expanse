package dev.logos.dimension.dawnvow;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

/**
 * 拔剑的规矩。传说里只有被选中的人拔得出石中剑；这里人人都能拔，但要选对时候、而且一人只拔一次：
 *
 * <ul>
 *   <li><b>破晓</b>：只有日出前后（一天的 22500–1500 刻，约现实 2.5 分钟）剑才松动。那时斜照在剑上的光最亮，
 *       其他时候按住拔也只能让剑颤一下，然后提示“光太淡”。</li>
 *   <li><b>按住</b>：按住右键连续拔满 {@link #PULL_TICKS} 刻；松手超过半秒就要从头来。</li>
 *   <li><b>一人一誓</b>：每位玩家只能从石中拔出一次曦誓，多处山洞的剑留给别人。把曦誓插回任何空石座即解除誓约。</li>
 * </ul>
 *
 * 没有昼夜的维度（下界、末地）里永远等不到破晓，剑拔不出来。
 */
public final class DawnvowTrial {
    /** 破晓时连续拔满这么多刻（3 秒）才拔出来。 */
    public static final int PULL_TICKS = 60;
    /** 不在破晓时，拔这么久（1.5 秒）剑只颤一下，随后提示。 */
    public static final int RESIST_TICKS = 30;
    /** 两次点按之间隔得超过这么多刻就算松手，进度清零。原版按住右键每 4 刻点一次。 */
    public static final int LET_GO_TICKS = 10;
    /** 破晓的窗口：一天里的刻数（0 为日出后不久、23000 前后太阳升起）。 */
    public static final int DAWN_START = 22500, DAWN_END = 1500;

    /** 一天中的第几刻（按维度默认的时钟）；没有昼夜的维度为 -1。 */
    public static int dayTime(Level level) {
        if (level.dimensionType().defaultClock().isEmpty()) return -1;
        return (int) Math.floorMod(level.getDefaultClockTime(), 24000L);
    }

    public static boolean dawn(Level level) {
        int t = dayTime(level);
        return t >= DAWN_START || t >= 0 && t < DAWN_END;
    }

    /** 破晓的光有多亮（0–1）：窗口中央最亮，两端渐暗，其余时间为 0。客户端的斜光用它加亮。 */
    public static float dawnGlow(Level level, float partialTick) {
        int t = dayTime(level);
        if (t < 0) return 0;
        float since = Math.floorMod(t - DAWN_START, 24000) + partialTick, span = 24000 - DAWN_START + DAWN_END;
        if (since >= span) return 0;
        float k = since / span;
        return Mth.sin(k * Mth.PI);
    }

    public static boolean sworn(ServerPlayer player) {
        return player.getData(Dawnvow.SWORN);
    }

    static void swear(ServerPlayer player) {
        player.setData(Dawnvow.SWORN, true);
    }

    static void release(ServerPlayer player) {
        player.setData(Dawnvow.SWORN, false);
    }

    private DawnvowTrial() {
    }
}
