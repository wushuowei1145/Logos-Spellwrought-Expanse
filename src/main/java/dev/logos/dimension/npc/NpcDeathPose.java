package dev.logos.dimension.npc;

import net.minecraft.world.entity.LivingEntity;

/**
 * 死亡后锁住朝向（docs/NPC_ANIMATIONS.md 第 3 节）。
 *
 * <p>原版不会在死亡时冻结朝向：每刻身体都会转向移动方向（致命一击的击退还会让尸体滑一小段）并被头的朝向拉着走，
 * 服务端同步过来的转角也会继续推动。原版侧倒只有 1 秒看不出来，自带死亡动画、在地上躺几秒的 NPC 就会趴着慢慢转。
 *
 * <p>用法：NPC 记一个死亡时的朝向（初值 {@link Float#NaN}），在 {@code tick()} 里 {@code super.tick()} 之后调用
 * {@link #hold}，服务端与客户端都调用；活着时把记录清回 NaN。
 */
public final class NpcDeathPose {
    /**
     * 死亡中每刻调用：把身体、头和实体本身的偏航（连同上一刻的值，免得渲染插值）都固定为死亡时的朝向。
     *
     * @param yaw 记下的朝向；NaN 表示刚死，取此刻的身体朝向
     * @return 要继续保持的朝向，NPC 存起来下一刻再传进来
     */
    public static float hold(LivingEntity mob, float yaw) {
        if (Float.isNaN(yaw)) yaw = mob.yBodyRot;
        mob.setYRot(yaw);
        mob.yRotO = yaw;
        mob.yBodyRot = yaw;
        mob.yBodyRotO = yaw;
        mob.setYHeadRot(yaw);
        mob.yHeadRotO = yaw;
        return yaw;
    }

    private NpcDeathPose() {}
}
