package dev.logos.dimension.shiraume;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.foxfire.Foxfire;
import dev.logos.dimension.foxfire.FoxfireFlame;
import dev.logos.lang.Element;
import dev.logos.runtime.Effects;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BlocksAttacks;
import net.minecraft.world.phys.Vec3;

/**
 * 白梅的刀（和狐形的牙、爪、尾）怎么打到人（docs/SHIRAUME_BOSS.md 第 4.3 节）：
 * <ol>
 *   <li>先照原版近战看挡不挡得住：对方举着带格挡组件的东西（盾牌、打刀、流火、黑钢长剑的格挡），正面朝着她。
 *       挡得住就照原版结算这一下，盾掉耐久、魔力护盾扣魔力、弹刀的窗口里就是弹反（弹反发生在格挡事件里，见 {@code LongswordCombat}）。</li>
 *   <li>挡不住：Logos 的<b>奥秘</b>伤害（{@link Effects#damage}，元素抗性照常算），但不放 Logos 的命中特效
 *       （{@code RyukaBurstMixin} 看 {@link #striking()}）。</li>
 * </ol>
 * 狐形的攻击用自己的伤害类型（标签 {@code logos_dimension:unparryable}）做格挡判定：盾能挡，弹刀的窗口里也只算挡下。
 */
public final class ShiraumeStrike {
    public static final ResourceKey<DamageType> BEAST = ResourceKey.create(Registries.DAMAGE_TYPE, LogosDimension.id("shiraume_beast"));
    public static final ResourceKey<DamageType> EXECUTION = ResourceKey.create(Registries.DAMAGE_TYPE, LogosDimension.id("shiraume_execution"));
    /** 带这个标签的伤害被格挡时不进入弹刀（{@code LongswordCombat.blocked}）。 */
    public static final TagKey<DamageType> UNPARRYABLE = TagKey.create(Registries.DAMAGE_TYPE, LogosDimension.id("unparryable"));
    private static final ThreadLocal<LivingEntity> STRIKING = new ThreadLocal<>();

    public enum Result { MISS, HIT, BLOCKED, PARRIED }

    private ShiraumeStrike() {}

    /** 正在结算白梅的一下（Logos 的命中特效跳过）。 */
    public static boolean striking() { return STRIKING.get() != null; }

    /**
     * 一下打到 {@code target}。{@code parryable} 为假时（狐形）格挡照常，但不会被弹反。
     * 返回结果；被弹反时 {@link ShiraumeBoss#takeParried()} 已经由格挡事件置上。
     */
    public static Result strike(Mob boss, ShiraumeBoss self, LivingEntity target, float damage, boolean parryable) {
        if (!(boss.level() instanceof ServerLevel level) || !target.isAlive()) return Result.MISS;
        DamageSource melee = parryable ? level.damageSources().mobAttack(boss)
                : new DamageSource(level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(BEAST), boss);
        // 连招里上一刀刚砍过这个人：原版的受击冷却会把这一刀吞掉
        if (target.getLastHurtByMob() == boss) target.invulnerableTime = 0;
        if (blocks(target, melee, damage)) {
            target.hurtServer(level, melee, damage);
            return self.takeParried() ? Result.PARRIED : Result.BLOCKED;
        }
        STRIKING.set(boss);
        try {
            return Effects.damage(boss, target, damage, Element.ARCANE) ? Result.HIT : Result.MISS;
        } finally {
            STRIKING.remove();
        }
    }

    /**
     * 狐火飞弹打中（已经看过挡不挡得住）：她独一份的淡粉狐火（docs/FOXFIRE.md），本质还是奥秘伤害；
     * 和刀、牙不同，打中时有一团淡粉的狐火，之后狐火缠身。
     */
    public static boolean foxfire(Mob boss, LivingEntity target, float damage) {
        if (target.getLastHurtByMob() == boss) target.invulnerableTime = 0;
        return Foxfire.deal(boss, target, damage, FoxfireFlame.SHIRAUME);
    }

    /** 原版 {@code applyItemBlocking} 的判断（不结算）：举着带格挡组件的东西、过了举盾延迟、来源在可挡的角度里。 */
    static boolean blocks(LivingEntity target, DamageSource source, float damage) {
        ItemStack blocking = target.getItemBlockingWith();
        if (blocking == null) return false;
        BlocksAttacks blocks = blocking.get(DataComponents.BLOCKS_ATTACKS);
        if (blocks == null) return false;
        Vec3 from = source.getSourcePosition();
        double angle = Math.PI;
        if (from != null) {
            float yaw = target.getYHeadRot() * Mth.DEG_TO_RAD;
            Vec3 view = new Vec3(-Mth.sin(yaw), 0, Mth.cos(yaw));
            Vec3 to = from.subtract(target.position());
            to = new Vec3(to.x, 0, to.z).normalize();
            angle = Math.acos(Mth.clamp(to.dot(view), -1, 1));
        }
        return blocks.resolveBlockedDamage(source, damage, angle) > 0;
    }

    /**
     * 身前扇形里能打到的生物，近的在前：锁定的目标、对她出过手的、玩家；不打别的 Boss 形态与旁观者。
     * {@code reach} 从身体中心算，{@code cone} 是整个张角（度）；贴身（一格内）不论方向。
     */
    public static List<LivingEntity> targets(Mob boss, double reach, double cone) {
        Vec3 center = boss.position().add(0, boss.getBbHeight() * .4, 0);
        float yaw = boss.getYRot() * Mth.DEG_TO_RAD;
        Vec3 forward = new Vec3(-Mth.sin(yaw), 0, Mth.cos(yaw));
        double half = Math.toRadians(Math.max(10, cone) / 2);
        List<LivingEntity> out = new ArrayList<>();
        for (LivingEntity e : boss.level().getEntitiesOfClass(LivingEntity.class, boss.getBoundingBox().inflate(reach + 1),
                e -> e != boss && e.isAlive() && !(e instanceof ShiraumeBoss) && !e.isSpectator())) {
            if (e instanceof Player p && (p.isCreative() || p.isSpectator())) continue;
            if (!(e instanceof Player) && e != boss.getTarget() && e.getLastHurtByMob() != boss && boss.getLastHurtByMob() != e) continue;
            Vec3 to = e.position().add(0, e.getBbHeight() * .5, 0).subtract(center);
            double flat = Math.sqrt(to.x * to.x + to.z * to.z);
            if (flat - e.getBbWidth() * .5 > reach || Math.abs(to.y) > reach) continue;
            if (flat > 1.2 && Math.acos(Mth.clamp((to.x * forward.x + to.z * forward.z) / flat, -1, 1)) > half) continue;
            out.add(e);
        }
        out.sort(Comparator.comparingDouble(e -> e.distanceToSqr(boss)));
        return out;
    }
}
