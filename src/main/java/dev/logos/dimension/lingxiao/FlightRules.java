package dev.logos.dimension.lingxiao;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;

/** 御剑的速率、逐刻消耗、输入归一化和剑尖朝向，客户端不能传入任意速度或魔力数值。 */
public final class FlightRules {
    public static final double MANA_PER_SECOND = 10;
    public static final double MANA_PER_TICK = MANA_PER_SECOND / 20;
    /** 最大移动速度（格/刻），约每秒 14 格。 */
    public static final double SPEED = .7;
    public static final float ATTACK_SPEED = 2.4f;
    public static final double SEAT_HEIGHT = .18;
    /** 剑尖转向的最快速度（度/刻）、俯仰的最快速度（度/刻）和最大俯仰（度）。 */
    public static final float TURN_RATE = 14, PITCH_RATE = 5, MAX_PITCH = 25;
    /** 飞行方向与视线夹角超过这个角度时算往回退：剑尖仍朝视线、倒着飞，不在脚下掉头。 */
    public static final float RETREAT = 100;

    /**
     * 这一刻扣完后的余额：消耗加上 Logos 这一刻的自然回复（回复照常加回来，净减少就是每刻 0.5、每秒 10）。
     * 不够一刻时不扣余额，返回 NaN 让服务端结束飞行。
     */
    public static double drain(double mana, double regeneration) {
        double cost = MANA_PER_TICK + (Double.isFinite(regeneration) ? Math.max(0, regeneration) : 0);
        return Double.isFinite(mana) && mana >= cost ? mana - cost : Double.NaN;
    }

    /** 前后沿视线飞行，左右水平侧移，跳跃上升；潜行在载具逻辑里直接下剑。 */
    public static Vec3 movement(Input input, Vec3 look, float yaw) {
        int forward = (input.forward() ? 1 : 0) - (input.backward() ? 1 : 0);
        int sideways = (input.left() ? 1 : 0) - (input.right() ? 1 : 0);
        double angle = Math.toRadians(yaw);
        Vec3 left = new Vec3(Math.cos(angle), 0, Math.sin(angle));
        Vec3 desired = look.scale(forward).add(left.scale(sideways)).add(0, input.jump() ? 1 : 0, 0);
        return desired.lengthSqr() > 1 ? desired.normalize().scale(SPEED) : desired.scale(SPEED);
    }

    /** 悬停时剑尖离视线超过这个角度（度）就跟着转，和原版身体跟头转一样。 */
    public static final float HOVER_LAG = 50;

    /**
     * 剑尖该朝的偏航角（原版约定）：水平飞得够快时顺着飞行方向；往回退时仍朝视线 {@code view}；
     * 悬停或只在升降时保持当前朝向 {@code current}，视线转开超过 {@link #HOVER_LAG} 度才跟过去。
     */
    public static float targetYaw(Vec3 velocity, float view, float current) {
        if (Math.hypot(velocity.x, velocity.z) < SPEED * .15) {
            float off = Mth.wrapDegrees(view - current);
            return Math.abs(off) <= HOVER_LAG ? current : view - Math.signum(off) * HOVER_LAG;
        }
        float travel = (float) Math.toDegrees(Math.atan2(-velocity.x, velocity.z));
        return Math.abs(Mth.wrapDegrees(travel - view)) <= RETREAT ? travel : view;
    }

    /**
     * 剑尖该有的俯仰（原版约定，正值剑尖朝下）：顺着剑尖方向飞时跟着升降抬头低头，倒着飞时反过来；
     * 垂直升降按半速的水平量算，最多 {@link #MAX_PITCH} 度，人还站得住。
     */
    public static float targetPitch(Vec3 velocity, float yaw) {
        double angle = Math.toRadians(yaw);
        double along = -Math.sin(angle) * velocity.x + Math.cos(angle) * velocity.z;
        double pitch = -Math.toDegrees(Math.atan2(velocity.y, Math.max(Math.abs(along), SPEED * .5)));
        if (along < -1e-4) pitch = -pitch;
        return (float) Math.clamp(pitch, -MAX_PITCH, MAX_PITCH);
    }

    private FlightRules() {}
}
