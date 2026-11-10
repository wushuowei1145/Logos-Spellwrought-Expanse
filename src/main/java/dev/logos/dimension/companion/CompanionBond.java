package dev.logos.dimension.companion;

/**
 * 好感、饱腹和契印的规矩（docs/SHIRAUME_COMPANION.md 第 6、8.3、8.7 节）。都是纯函数，单元测试直接测。
 *
 * <p>羁绊靠一起经历的事情长出来，不靠刷：同一件事一天里头几次加得多，之后加得很少。
 */
public final class CompanionBond {
    /** 饱腹的上限、低于多少算饿；她在外面时每过 {@link #HUNGER_TICKS} 刻降 1（在形代里歇着不饿）。 */
    public static final float SATIETY_MAX = 20, HUNGRY = 6;
    public static final int HUNGER_TICKS = 1200;
    /** 一起吃饭：喜欢的、普通能吃的各加多少好感（一天里的头一次、她不饿时）。 */
    public static final float MEAL_FAVORITE = 6, MEAL_LIKED = 2;
    /** 摸头（一天头 {@link #PATS_PER_DAY} 次才加）、一起泡温泉（一天一次）、形代替你挡了一次致命伤。 */
    public static final float PAT = 1, SOAK = 4, GUARDED = 10;
    public static final int PATS_PER_DAY = 5;
    /** 第一印的机制：形代替身。 */
    public static final int GUARD_SEAL = 1;

    private CompanionBond() {}

    /** 一起吃一顿加多少好感：喜欢的多；一天里第一顿全额、第二顿六成、之后一成五；她饿着的时候多五成。 */
    public static float meal(boolean favorite, int mealsToday, float satiety) {
        float base = favorite ? MEAL_FAVORITE : MEAL_LIKED;
        float fade = mealsToday <= 0 ? 1 : mealsToday == 1 ? .6f : .15f;
        return base * fade * (satiety < HUNGRY ? 1.5f : 1);
    }

    /** 摸一次头加多少好感。 */
    public static float pat(int patsToday) {
        return patsToday < PATS_PER_DAY ? PAT : 0;
    }

    /** 吃下 {@code nutrition} 点的东西后的饱腹。 */
    public static float fed(float satiety, int nutrition) {
        return Math.min(SATIETY_MAX, satiety + Math.max(1, nutrition));
    }

    /** 好感走到了哪一段（给 info 和以后的台词用）。 */
    public enum Stage {
        STRANGER(0), FAMILIAR(40), CLOSE(150), TRUSTED(400), BONDED(1000);

        public final float from;

        Stage(float from) {
            this.from = from;
        }

        public static Stage of(float affection) {
            Stage out = STRANGER;
            for (Stage s : values()) if (affection >= s.from) out = s;
            return out;
        }
    }

    /** 盖了 {@code seals} 枚印，第 {@code seal} 印的机制解锁了没有。 */
    public static boolean unlocked(int seals, int seal) {
        return seals >= seal;
    }
}
