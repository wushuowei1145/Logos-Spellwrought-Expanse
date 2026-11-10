package dev.logos.dimension.shiraume;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.foxfire.Foxfire;
import dev.logos.dimension.foxfire.FoxfireFlame;
import dev.logos.lang.Element;
import dev.logos.runtime.Effects;
import dev.logos.dimension.item.RyukaItem;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Util;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
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
    /** 斩法（锁定的术师蓄力时那一刀）：伤害等于对方已经蓄进去的魔力，不看护甲、附魔与抗性，盾也挡不住。 */
    public static final ResourceKey<DamageType> COUNTER = ResourceKey.create(Registries.DAMAGE_TYPE, LogosDimension.id("shiraume_counter"));
    /** 破盾：盾（打刀、古木之拥……）几秒用不了，同原版斧头。 */
    public static final float GUARD_BREAK_SECONDS = 5;
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
        return strike(boss, self, target, damage, parryable, false, 0);
    }

    /**
     * 同上，另加两样：{@code guardBreak} 格挡下来却没有弹反时破盾（拿流火格挡的除外）；{@code purge} 打中时随机清除几个增益效果。
     * 打中是指这一下真的让对方掉了血（Logos 的伤害只在打死时才返回真，不能用它判断）。
     */
    public static Result strike(Mob boss, ShiraumeBoss self, LivingEntity target, float damage, boolean parryable, boolean guardBreak, int purge) {
        if (!(boss.level() instanceof ServerLevel level) || !target.isAlive()) return Result.MISS;
        DamageSource melee = parryable ? level.damageSources().mobAttack(boss)
                : new DamageSource(level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(BEAST), boss);
        // 连招里上一刀刚砍过这个人：原版的受击冷却会把这一刀吞掉
        if (target.getLastHurtByMob() == boss) target.invulnerableTime = 0;
        if (blocks(target, melee, damage)) {
            ItemStack blocking = target.getItemBlockingWith();
            target.hurtServer(level, melee, damage);
            boolean parried = self.takeParried();
            if (!parried && guardBreak && blocking != null) breakGuard(level, target, blocking);
            return parried ? Result.PARRIED : Result.BLOCKED;
        }
        STRIKING.set(boss);
        try {
            float before = target.getHealth() + target.getAbsorptionAmount();
            boolean killed = Effects.damage(boss, target, damage, Element.ARCANE);
            boolean landed = killed || target.getHealth() + target.getAbsorptionAmount() < before - 1e-4f;
            if (landed && purge > 0) purge(level, target, purge);
            return landed ? Result.HIT : Result.MISS;
        } finally {
            STRIKING.remove();
        }
    }

    /** 破盾：拿在手里格挡的东西几秒用不了（同原版斧头），流火（魔力护体）除外。返回是否破了。 */
    public static boolean breakGuard(ServerLevel level, LivingEntity target, ItemStack blocking) {
        if (blocking.getItem() instanceof RyukaItem) return false;
        BlocksAttacks blocks = blocking.get(DataComponents.BLOCKS_ATTACKS);
        if (blocks != null) blocks.disable(level, target, GUARD_BREAK_SECONDS, blocking);
        else {
            if (target instanceof Player p) p.getCooldowns().addCooldown(blocking, Math.round(GUARD_BREAK_SECONDS * 20));
            target.stopUsingItem();
        }
        Vec3 at = target.position().add(0, target.getBbHeight() * .6, 0);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.SHIELD_BREAK.value(), SoundSource.HOSTILE, 1.4f, .7f);
        ShiraumeCue.near(level, at, 64, ShiraumeCue.guardBreak(at));
        return true;
    }

    /** 随机清除 {@code target} 身上最多 {@code count} 个增益药水效果，返回清掉几个。 */
    public static int purge(ServerLevel level, LivingEntity target, int count) {
        List<MobEffectInstance> good = new ArrayList<>(target.getActiveEffects().stream().filter(e -> e.getEffect().value().isBeneficial()).toList());
        if (good.isEmpty() || count <= 0) return 0;
        Util.shuffle(good, level.getRandom());
        int n = Math.min(count, good.size());
        float[] colours = new float[n];
        for (int i = 0; i < n; i++) {
            colours[i] = good.get(i).getEffect().value().getColor();
            target.removeEffect(good.get(i).getEffect());
        }
        Vec3 at = target.position().add(0, target.getBbHeight() * .6, 0);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.GLASS_BREAK, SoundSource.HOSTILE, 1.2f, 1.3f);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.AMETHYST_BLOCK_BREAK, SoundSource.HOSTILE, 1.0f, .8f);
        ShiraumeCue.near(level, at, 64, ShiraumeCue.purge(target, colours));
        return n;
    }

    /**
     * 斩法的伤害来源：记着对方蓄进去的魔力，死亡消息照着写
     * （「X 蓄了 N 点魔力的术式被白梅一刀斩断……」）。
     */
    public static DamageSource counter(ServerLevel level, Entity boss, double mana) {
        return new CounterHit(level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(COUNTER), boss, mana);
    }

    private static final class CounterHit extends DamageSource {
        private final double mana;

        CounterHit(net.minecraft.core.Holder<DamageType> type, Entity boss, double mana) {
            super(type, boss);
            this.mana = mana;
        }

        @Override
        public Component getLocalizedDeathMessage(LivingEntity killed) {
            Entity by = getEntity();
            String amount = mana == Math.rint(mana) ? String.valueOf((long) mana) : String.format(java.util.Locale.ROOT, "%.1f", mana);
            return Component.translatable("death.attack." + type().msgId(), killed.getDisplayName(),
                    by == null ? Component.literal("?") : by.getDisplayName(), amount);
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
