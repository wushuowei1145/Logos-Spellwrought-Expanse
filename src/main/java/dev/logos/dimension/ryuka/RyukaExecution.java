package dev.logos.dimension.ryuka;

import dev.logos.dimension.combat.Airborne;
import dev.logos.dimension.execution.ExecutionShot;
import dev.logos.dimension.execution.InteractiveExecutions;
import dev.logos.dimension.longsword.LongswordCombat;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;

/**
 * 上挑砍中之后（docs/RYUKA.md 第 3.4 节）：目标的狐火印爆开后 {@link FoxMarks#WINDOW} 刻之内处决它（处决一开始就用掉这次爆开），
 * 否则挑飞（同群誓·狼牙长枪的上挑，{@link Airborne}）。
 * 处决是可交互的（{@link InteractiveExecutions}）：目标被抛到半空，画面变黑白，{@link #WINDOW} 刻里处决的人每点一下左键砍一刀
 * （{@link #SLASH} 点狐火，两刀至少隔 {@link #GAP} 刻），时间到了追加一刀重击（{@link #FINAL} 点），再把它拍到地上，
 * 另算 {@link #FALL} 点摔落伤害。特写只给处决的人和被处决的玩家看，旁人只看到它在半空被刀光砍。
 * 一次上挑只处决一个（准星对着的优先），同一刀砍中的其他目标照常挑飞。
 */
public final class RyukaExecution implements InteractiveExecutions.Handler {
    public static final String MOVE = "execution", END = "execution_end";
    /** 留给点击的时长（刻）和两刀最短的间隔（刻）。 */
    public static final int WINDOW = 40, GAP = 1;
    public static final float SLASH = 11, FINAL = 11, FALL = 25;
    /** 旁人看到的刀光颜色：流火的淡粉。 */
    public static final int TINT = 0xF7B6D6;
    private static final RyukaExecution HANDLER = new RyukaExecution();

    private RyukaExecution() {}

    /** 上挑砍中 {@code target}：爆开过还在窗口里、挑得起来、这一下还没处决过别人时处决它，否则挑飞。 */
    public static void rise(ServerPlayer player, LivingEntity target) {
        if (!target.isAlive()) return;
        if (FoxMarks.executable(target) && Airborne.canLaunch(target) && !InteractiveExecutions.executing(player)
                && InteractiveExecutions.target(target) == null && start(player, target)) return;
        if (Airborne.launch(target, player))
            player.level().playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.PLAYER_ATTACK_KNOCKBACK, SoundSource.PLAYERS, 1, .8f);
    }

    /** 处决 {@code target}（测试也从这里进）。 */
    public static boolean start(ServerPlayer player, LivingEntity target) {
        var shot = ExecutionShot.of(player.getRandom().nextInt()).live(WINDOW, TINT);
        if (!InteractiveExecutions.start(player, target, shot, GAP, HANDLER)) return false;
        FoxMarks.consume(target);
        LongswordCombat.force(player, MOVE);
        player.level().playSound(null, target.getX(), target.getY(), target.getZ(), SoundEvents.PLAYER_ATTACK_KNOCKBACK, SoundSource.PLAYERS, 1.2f, .6f);
        return true;
    }

    @Override public void slash(ServerPlayer executor, LivingEntity target, int index) {
        // 被定住的生物不走刻，受击冷却不会自己退：每刀之前清掉，不然第二刀起都被冷却吞掉
        target.invulnerableTime = 0;
        Ryuka.hit(executor, target, SLASH, false);
    }

    @Override public void finish(ServerPlayer executor, LivingEntity target, int slashes) {
        LongswordCombat.force(executor, END);
        target.invulnerableTime = 0;
        Ryuka.hit(executor, target, FINAL, false);
    }

    @Override public void impact(ServerPlayer executor, LivingEntity target) {
        if (!target.isAlive() || !(target.level() instanceof ServerLevel level)) return;
        target.invulnerableTime = 0;
        target.resetFallDistance();
        target.hurtServer(level, target.damageSources().fall(), FALL);
    }

    @Override public void end(ServerPlayer executor, LivingEntity target, boolean completed) {
        if (MOVE.equals(LongswordCombat.move(executor))) LongswordCombat.force(executor, "idle");
    }
}
