package dev.logos.dimension.companion;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 白梅护着主人的两件事（docs/SHIRAUME_COMPANION.md 第 8.3、8.5 节）：
 * <ul>
 *   <li>第一印「形代替身」：主人受到致命伤时，身上写着自己名字的形代替他挡下（每个游戏日一次）；
 *   <li>截下射向主人的弹射物：{@link #incoming} 判断这一发会不会在短时间里打中主人，心智的反射层每刻看。
 * </ul>
 */
public final class CompanionGuard {
    /** 往后看多少刻：这段时间里会擦到主人身体的弹射物才截。 */
    public static final int LOOKAHEAD = 10;
    /** 离主人的身体多近算打中（格）。 */
    public static final double HIT_MARGIN = .45;

    private CompanionGuard() {}

    /** 主人要死了：形代替身生效就返回真（调用方取消这次死亡）。 */
    static boolean saveOwner(ServerPlayer p, DamageSource source) {
        var b = p.getData(Companions.BOND);
        if (!b.pact() || !CompanionBond.unlocked(b.seals(), CompanionBond.GUARD_SEAL) || source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)
                || !KatashiroItem.carries(p) || !(p.level() instanceof ServerLevel level)) return false;
        long day = level.getOverworldClockTime() / 24000;
        if (b.guardDay() == day) return false;
        p.setHealth(Math.max(2, p.getMaxHealth() * .1f));
        p.invulnerableTime = 40;
        p.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 100, 1));
        p.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, 40, 4));
        p.setData(Companions.BOND, b.withGuard(day).withAffection(b.affection() + CompanionBond.GUARDED));
        Katashiro.drop(level, p.position().add(p.getLookAngle().multiply(1, 0, 1).normalize().scale(.6)));
        level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.TOTEM_USE, SoundSource.PLAYERS, .6f, 1.6f);
        CompanionLines.say(p, "guarded", 70);
        return true;
    }

    /**
     * 这一发弹射物会不会在 {@link #LOOKAHEAD} 刻里擦到 {@code body}（主人的碰撞箱放大 {@link #HIT_MARGIN}）：
     * 按现在的速度、受重力（{@code gravity}，每刻往下加多少）逐刻往前推。返回打中前的那一点，不会打中为 null。
     */
    public static @Nullable Vec3 incoming(Vec3 pos, Vec3 velocity, double gravity, AABB body) {
        if (velocity.lengthSqr() < .04) return null;
        AABB hit = body.inflate(HIT_MARGIN);
        Vec3 p = pos, v = velocity;
        for (int i = 0; i < LOOKAHEAD; i++) {
            Vec3 next = p.add(v);
            if (hit.contains(next) || hit.clip(p, next).isPresent()) return p;
            p = next;
            v = v.add(0, -gravity, 0).scale(.99);
        }
        return null;
    }

    /** 弹射物的重力（原版大多数箭 0.05，火球、风弹 0）。 */
    static double gravity(Projectile projectile) {
        return projectile.isNoGravity() ? 0 : projectile instanceof net.minecraft.world.entity.projectile.arrow.AbstractArrow ? .05 : .03;
    }
}
