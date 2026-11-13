package dev.logos.dimension.npc;

import java.util.EnumSet;

import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;

/** 灵猫的日常：坐下打盹（夜里坐着睡）、找亲近的玩家蹭蹭、站着时伸懒腰或洗脸。 */
public final class MysticalCatGoals {
    private MysticalCatGoals() {}

    /** 随时可能在原地坐下发呆；夜里很快坐下睡觉，一坐就是很久。被更高优先级的事（逃跑、跟着鱼走）打断就站起来。 */
    public static final class Sit extends Goal {
        private final MysticalCat cat;

        public Sit(MysticalCat cat) {
            this.cat = cat;
            setFlags(EnumSet.of(Flag.MOVE, Flag.JUMP));
        }

        @Override
        public boolean canUse() {
            if (cat.sitting() || !cat.onGround() || cat.isInWater() || cat.acting()) return false;
            return cat.getRandom().nextInt(cat.sleepy() ? 60 : 500) == 0;
        }

        @Override
        public void start() {
            var r = cat.getRandom();
            cat.sitDown(cat.sleepy() ? 1800 + r.nextInt(1800) : 300 + r.nextInt(600));
        }

        @Override
        public boolean canContinueToUse() {
            return cat.sitting();
        }

        @Override
        public void stop() {
            if (cat.sitting()) cat.standUp();
        }
    }

    /** 隔一阵子走到亲近的玩家身边（两格内），抬头看着、喵一声，再在旁边坐一会儿。 */
    public static final class Visit extends Goal {
        private final MysticalCat cat;
        private Player target;
        private int cooldown = 200, timeout;

        public Visit(MysticalCat cat) {
            this.cat = cat;
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            if (cooldown > 0) {
                cooldown--;
                return false;
            }
            if (cat.sitting() || cat.sleepy()) return false;
            Player near = cat.level().getNearestPlayer(cat, 14);
            if (near == null || near.isSpectator() || !cat.fondOf(near) || cat.distanceTo(near) < 3) return false;
            target = near;
            return true;
        }

        @Override
        public void start() {
            timeout = 200;
            cat.getNavigation().moveTo(target, 0.75);
        }

        @Override
        public boolean canContinueToUse() {
            return target != null && target.isAlive() && timeout > 0 && cat.distanceTo(target) > 2.0;
        }

        @Override
        public void tick() {
            timeout--;
            cat.getLookControl().setLookAt(target, 30, 30);
            if (timeout % 20 == 0) cat.getNavigation().moveTo(target, 0.75);
        }

        @Override
        public void stop() {
            if (target != null && cat.distanceTo(target) <= 2.5) {
                cat.getNavigation().stop();
                cat.getLookControl().setLookAt(target);
                cat.playAmbientSound();
                cat.playAction("greet");
                cat.mood(MysticalCat.HAPPY, 40);
            }
            target = null;
            cooldown = 1200 + cat.getRandom().nextInt(1200);
        }
    }

    /** 站定的时候偶尔伸个懒腰、舔舔爪子洗脸；动作期间不走动。 */
    public static final class Idle extends Goal {
        private final MysticalCat cat;
        private int ticks;

        public Idle(MysticalCat cat) {
            this.cat = cat;
            setFlags(EnumSet.of(Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            return !cat.sitting() && !cat.acting() && cat.onGround() && cat.getNavigation().isDone() && cat.getRandom().nextInt(400) == 0;
        }

        @Override
        public void start() {
            String action = cat.getRandom().nextBoolean() ? "stretch" : "wash";
            cat.playAction(action);
            ticks = action.equals("stretch") ? 48 : 40;
        }

        @Override
        public boolean canContinueToUse() {
            return ticks > 0;
        }

        @Override
        public void tick() {
            ticks--;
        }
    }
}
