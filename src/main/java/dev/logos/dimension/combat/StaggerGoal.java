package dev.logos.dimension.combat;

import java.util.Collections;
import java.util.EnumSet;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.WrappedGoal;

/** 被弹反的僵直：占住移动和视线，其他行为停下来，僵直结束自己退出。优先级要高于 NPC 的其他行为（神狐用 0）。 */
public final class StaggerGoal<T extends Mob & Parry.Reactive> extends Goal {
    private static final EnumSet<Flag> FLAGS = EnumSet.of(Flag.MOVE, Flag.LOOK);
    private final T mob;

    public StaggerGoal(T mob) {
        this.mob = mob;
        setFlags(FLAGS);
    }

    /**
     * 被弹开的当刻调用，让僵直马上生效：行为选择器隔一刻才切换行为，不打断的话追击行为还会多跑一下，可能正好触发突进。
     * 同时去掉水平速度（比如正在箭步）。
     */
    public static void interrupt(Mob mob, GoalSelector goals) {
        for (WrappedGoal goal : goals.getAvailableGoals())
            if (goal.isRunning() && !Collections.disjoint(goal.getFlags(), FLAGS)) goal.stop();
        halt(mob);
    }

    private static void halt(Mob mob) {
        mob.getNavigation().stop();
        mob.setSprinting(false);
        mob.setDeltaMovement(0, mob.getDeltaMovement().y, 0);
    }

    @Override
    public boolean canUse() {
        return mob.staggered();
    }

    @Override
    public void start() {
        halt(mob);
    }
}
