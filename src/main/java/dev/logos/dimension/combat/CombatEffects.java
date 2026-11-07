package dev.logos.dimension.combat;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * 招式文件里 {@code effect} 字段的附加效果（docs/COMBAT_SKELETON.md 第 5 节）。伤害本身仍走原版 {@code player.attack}，
 * 这里只在命中前后加上武器自己的规则：
 * <ul>
 *   <li>{@code launch}：长枪上挑，命中的目标进入 {@link Airborne 击飞}。</li>
 *   <li>{@code flurry}：长枪连刺，打浮空的目标伤害翻倍、每下延长一点浮空。</li>
 *   <li>{@code slam}：长枪转枪下砸，自己挑起的浮空目标即使不在锥形里也会被砸中；砸中的浮空目标摔回地面、受高额摔落伤害。</li>
 * </ul>
 */
public final class CombatEffects {
    public static final String LAUNCH = "launch", FLURRY = "flurry", SLAM = "slam";
    /** 招式是弹刀：转入它的那一下格挡不消耗耐久，近战的攻击方进入僵直（{@link Parry}）。没有接触，只由 {@code LongswordCombat} 读。 */
    public static final String PARRY = "parry";
    /** 下砸时把自己挑起的浮空目标也算进来的距离（格，攻击距离之外再加）。 */
    private static final double SLAM_EXTRA_RANGE = 2;

    private CombatEffects() {}

    /** 结算命中之前：下砸把自己挑起的浮空目标补进目标列表，并在枪尖落地处砸出尘土。 */
    public static void contact(ServerPlayer player, Moveset.Move move, List<Entity> targets) {
        if (!SLAM.equals(move.effect())) return;
        for (var e : Airborne.launchedBy(player, player.entityInteractionRange() + SLAM_EXTRA_RANGE))
            if (!targets.contains(e) && player.hasLineOfSight(e)) targets.add(e);
        if (player.level() instanceof ServerLevel level) {
            var look = player.getLookAngle();
            var flat = new Vec3(look.x, 0, look.z);
            flat = flat.lengthSqr() < 1e-6 ? Vec3.directionFromRotation(0, player.getYRot()) : flat.normalize();
            var at = player.position().add(flat.scale(2.2));
            var ground = BlockPos.containing(at.x, at.y - 0.2, at.z);
            var state = level.getBlockState(ground);
            if (!state.isAir()) level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, state), at.x, ground.getY() + 1.05, at.z,
                    40, 0.7, 0.05, 0.7, 0.2);
            level.sendParticles(ParticleTypes.SWEEP_ATTACK, at.x, ground.getY() + 1.2, at.z, 1, 0, 0, 0, 0);
            level.playSound(null, at.x, at.y, at.z, SoundEvents.MACE_SMASH_GROUND_HEAVY, SoundSource.PLAYERS, 1, 0.9f);
        }
    }

    /** 落空时是否不放原版挥空的声音（连刺一秒八下，挥空声太吵）。 */
    public static boolean quietWhiff(Moveset.Move move) { return FLURRY.equals(move.effect()) || SLAM.equals(move.effect()); }

    /** 这一下对该目标的额外伤害倍数（乘在招式的 damage 上）。 */
    public static float multiplier(Moveset.Move move, Entity target) {
        return FLURRY.equals(move.effect()) && Airborne.is(target) ? 2 : 1;
    }

    /** 命中之后的效果。 */
    public static void hit(ServerPlayer player, Entity target, Moveset.Move move) {
        if (move.effect() == null || !(target instanceof LivingEntity living) || !living.isAlive()) return;
        switch (move.effect()) {
            case LAUNCH -> {
                if (Airborne.launch(living, player))
                    player.level().playSound(null, living.getX(), living.getY(), living.getZ(), SoundEvents.PLAYER_ATTACK_KNOCKBACK,
                            SoundSource.PLAYERS, 1, 0.8f);
            }
            case FLURRY -> Airborne.extend(living);
            case SLAM -> Airborne.slam(living);
            default -> {}
        }
    }
}
