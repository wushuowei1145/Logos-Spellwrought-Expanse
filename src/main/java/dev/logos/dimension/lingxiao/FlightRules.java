package dev.logos.dimension.lingxiao;

import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;

/** 御剑的速率、逐刻消耗与输入归一化，客户端不能传入任意速度或魔力数值。 */
public final class FlightRules {
    public static final double MANA_PER_SECOND = 10;
    public static final double MANA_PER_TICK = MANA_PER_SECOND / 20;
    public static final double SPEED = .45;
    public static final float ATTACK_SPEED = 2.4f;
    public static final double SEAT_HEIGHT = .18;

    /** 不足一刻消耗时不扣余额，返回 NaN 让服务端结束飞行。 */
    public static double drain(double mana) {
        return Double.isFinite(mana) && mana >= MANA_PER_TICK ? mana - MANA_PER_TICK : Double.NaN;
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

    private FlightRules() {}
}
