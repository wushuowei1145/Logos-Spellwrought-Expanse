package dev.logos.dimension.foxfire;

/**
 * 狐火的颜色（docs/FOXFIRE.md）。神狐一族的狐火是幽蓝的，白梅的是独一份的淡粉（她的刀流火燃的也是这种）。
 * 两种只有颜色不同，伤害类型、规则都一样，所以不拆成两种伤害类型：颜色跟着这一下走（谁放的、哪把刀）。
 */
public enum FoxfireFlame {
    /** 神狐一族：幽蓝的鬼火。物品表面火焰的色相转半圈（同 {@code DivineFoxRenderer.GHOST_HUE}）。 */
    KITSUNE(0x3FA8FF, 0xDDF2FF, .5f),
    /** 白梅：淡粉，同流火刀身上的狐火（{@code RyukaClient.PINK_HUE}）。 */
    SHIRAUME(0xF4A6CF, 0xFFF0F7, .85f);

    /** 外焰的颜色（RGB）。 */
    public final int core;
    /** 焰心的颜色（RGB）。 */
    public final int bright;
    /** 换给 Logos 物品表面火焰（{@code SurfaceAura.Style.FIRE}）的色相，单位是圈。 */
    public final float hue;

    FoxfireFlame(int core, int bright, float hue) {
        this.core = core;
        this.bright = bright;
        this.hue = hue;
    }

    /** 按网络里的序号取，越界时取第一种。 */
    public static FoxfireFlame byId(int id) {
        FoxfireFlame[] all = values();
        return id >= 0 && id < all.length ? all[id] : KITSUNE;
    }
}
