package dev.logos.dimension.corruption;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 腐败核心的方块实体。什么都不存，只在客户端记动画：身体慢慢把裂口转向最近的玩家，眼珠在裂口里快速盯住他，
 * 隔一阵眨一下眼；长到下一级时鼓一下。几级、多大由方块状态 {@link CorruptionCoreBlock#STAGE} 决定。
 */
public class CorruptionCoreBlockEntity extends BlockEntity {
    /** 看多远的玩家。 */
    static final double SIGHT = 24;
    /** 眼珠中心离方块底部多高（格），按 3 级的肉瘤算，盯人的方向从这里起算。 */
    public static final double EYE_HEIGHT = 1.25;
    /** 身体每刻最多转多少（弧度），眼珠在裂口里最多偏多少。 */
    private static final float BODY_TURN = .035f, EYE_YAW = .7f, EYE_PITCH = .6f;
    private static final int BLINK = 4, GROW = 12;

    private float bodyYaw, lastBodyYaw, eyeYaw, lastEyeYaw, eyePitch, lastEyePitch;
    private int blink, nextBlink = 60, grow = GROW, lastStage = -1, age;

    public CorruptionCoreBlockEntity(BlockPos pos, BlockState state) {
        super(Corruption.CORE_ENTITY.get(), pos, state);
        // 每个核心初始朝向不一样，几个核心摆在一起不会长得一模一样
        bodyYaw = lastBodyYaw = (float) (Math.floorMod(pos.asLong() * 0x9E3779B97F4A7C15L, 6283L) / 1000.0);
    }

    public int stage() {
        return getBlockState().getValue(CorruptionCoreBlock.STAGE);
    }

    void clientTick() {
        if (level == null) return;
        age++;
        lastBodyYaw = bodyYaw;
        lastEyeYaw = eyeYaw;
        lastEyePitch = eyePitch;
        int stage = stage();
        if (stage != lastStage) {
            // 第一次看到时不鼓，之后每长一级鼓一下
            grow = lastStage < 0 ? GROW : 0;
            lastStage = stage;
        }
        if (grow < GROW) grow++;
        if (blink > 0) blink--;
        else if (--nextBlink <= 0) {
            blink = BLINK;
            nextBlink = 80 + level.getRandom().nextInt(160);
        }
        BlockPos p = getBlockPos();
        double ex = p.getX() + .5, ey = p.getY() + EYE_HEIGHT, ez = p.getZ() + .5;
        Player target = level.getNearestPlayer(ex, ey, ez, SIGHT, EntitySelector.NO_SPECTATORS);
        float wantYaw, wantPitch;
        if (target != null && stage == 3) {
            double dx = target.getX() - ex, dy = target.getEyeY() - ey, dz = target.getZ() - ez;
            float toward = (float) Mth.atan2(dx, dz);
            bodyYaw += Mth.clamp(Mth.wrapDegrees((toward - bodyYaw) * Mth.RAD_TO_DEG) * Mth.DEG_TO_RAD, -BODY_TURN, BODY_TURN);
            wantYaw = Mth.clamp(Mth.wrapDegrees((toward - bodyYaw) * Mth.RAD_TO_DEG) * Mth.DEG_TO_RAD, -EYE_YAW, EYE_YAW);
            wantPitch = Mth.clamp((float) -Mth.atan2(dy, Math.sqrt(dx * dx + dz * dz)), -EYE_PITCH, EYE_PITCH);
        } else {
            // 没人时眼珠慢慢四处看
            wantYaw = .45f * Mth.sin(age * .021f);
            wantPitch = .15f * Mth.sin(age * .037f);
        }
        eyeYaw += (wantYaw - eyeYaw) * .35f;
        eyePitch += (wantPitch - eyePitch) * .35f;
    }

    public float bodyYaw(float partial) { return Mth.rotLerpRad(partial, lastBodyYaw, bodyYaw); }

    public float eyeYaw(float partial) { return Mth.lerp(partial, lastEyeYaw, eyeYaw); }

    public float eyePitch(float partial) { return Mth.lerp(partial, lastEyePitch, eyePitch); }

    /** 眼皮合上多少，0 睁开、1 闭上。 */
    public float blink(float partial) {
        if (blink <= 0) return 0;
        float t = (BLINK - blink + partial) / BLINK;
        return Mth.sin(Mth.clamp(t, 0, 1) * Mth.PI);
    }

    /** 刚长到这一级的鼓胀，0 刚长、1 平复。 */
    public float grown(float partial) { return Mth.clamp((grow + partial) / GROW, 0, 1); }

    public int age() { return age; }
}
