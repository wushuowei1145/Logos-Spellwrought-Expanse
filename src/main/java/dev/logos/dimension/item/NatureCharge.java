package dev.logos.dimension.item;

/** 自然之力的蓄力参数集中在此，客户端贴图使用同一刻数。 */
public final class NatureCharge {
    public static final int FULL_DRAW = 20;
    public static final int EMPOWERED = 30;
    public static final int FULL_CHARGE = 40;

    public static int stage(int ticks) {
        return ticks >= FULL_CHARGE ? 4 : ticks >= EMPOWERED ? 3 : ticks >= FULL_DRAW ? 2 : 1;
    }

    public static boolean empowered(int ticks) { return ticks >= FULL_CHARGE; }
    public static int mana(int ticks) { return ticks >= FULL_CHARGE ? 10 : ticks >= EMPOWERED ? 5 : 0; }
    public static float speed(int ticks) {
        float pull = Math.max(0, ticks) / 20f;
        return empowered(ticks) ? 8 : ticks >= EMPOWERED ? 3 : Math.min(1, (pull * pull + 2 * pull) / 3) * 2.7f;
    }
    private NatureCharge() {}
}
