package dev.logos.dimension.elven.blight;

import net.minecraft.world.entity.LivingEntity;

/** 仅替换女王自己的生命法术颜色；嵌套触发玩家法术时先退出，再可靠恢复外层状态。 */
public final class QueenSpellContext {
    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> false);
    private QueenSpellContext() {}
    public static boolean active() { return ACTIVE.get(); }
    public static void run(LivingEntity caster, Runnable action) {
        boolean previous = ACTIVE.get();
        ACTIVE.set(caster instanceof CorruptedElfQueen);
        try { action.run(); } finally { if (previous) ACTIVE.set(true); else ACTIVE.remove(); }
    }
}
