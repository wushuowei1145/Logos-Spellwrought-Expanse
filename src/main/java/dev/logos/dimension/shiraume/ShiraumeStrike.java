package dev.logos.dimension.shiraume;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.combat.BossStrike;
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
 * 白梅的刀（和狐形的牙、爪、尾）怎么打到人（docs/SHIRAUME_BOSS.md 第 4.3 节）。格挡、弹反、破盾、清增益的通用部分在
 * {@link BossStrike}（剑祟也用）；这里只定白梅自己的：
 * <ul>
 *   <li>挡不住时是 Logos 的<b>奥秘</b>伤害（{@link Effects#damage}，元素抗性照常算），不放 Logos 的命中特效；</li>
 *   <li>狐形的攻击用自己的伤害类型（标签 {@code logos_dimension:unparryable}）做格挡判定：盾能挡，弹刀的窗口里也只算挡下；</li>
 *   <li>破盾、清增益的样子是她的淡粉（{@link ShiraumeCue}）。</li>
 * </ul>
 */
public final class ShiraumeStrike {
    public static final ResourceKey<DamageType> BEAST = ResourceKey.create(Registries.DAMAGE_TYPE, LogosDimension.id("shiraume_beast"));
    public static final ResourceKey<DamageType> EXECUTION = ResourceKey.create(Registries.DAMAGE_TYPE, LogosDimension.id("shiraume_execution"));
    /** 斩法（锁定的术师蓄力时那一刀）：伤害等于对方已经蓄进去的魔力，不看护甲、附魔与抗性，盾也挡不住。 */
    public static final ResourceKey<DamageType> COUNTER = ResourceKey.create(Registries.DAMAGE_TYPE, LogosDimension.id("shiraume_counter"));
    /** 破盾、清增益时她的淡粉。 */
    static final BossStrike.Look LOOK = new BossStrike.Look() {
        @Override public void guardBreak(ServerLevel level, Vec3 at) { ShiraumeCue.near(level, at, 64, ShiraumeCue.guardBreak(at)); }

        @Override public void purge(ServerLevel level, LivingEntity target, float[] colours) {
            Vec3 at = target.position().add(0, target.getBbHeight() * .6, 0);
            ShiraumeCue.near(level, at, 64, ShiraumeCue.purge(target, colours));
        }
    };

    public enum Result { MISS, HIT, BLOCKED, PARRIED }

    private ShiraumeStrike() {}

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
        BossStrike.Result r = BossStrike.strike(boss, self::takeParried, target, damage, melee,
                (l, t, d) -> Effects.damage(boss, t, d, Element.ARCANE), guardBreak, purge, LOOK);
        return Result.valueOf(r.name());
    }

    /** 破盾：拿在手里格挡的东西几秒用不了（同原版斧头），流火（魔力护体）除外。返回是否破了。 */
    public static boolean breakGuard(ServerLevel level, LivingEntity target, ItemStack blocking) {
        return BossStrike.breakGuard(level, target, blocking, LOOK);
    }

    /** 随机清除 {@code target} 身上最多 {@code count} 个增益药水效果，返回清掉几个。 */
    public static int purge(ServerLevel level, LivingEntity target, int count) {
        return BossStrike.purge(level, target, count, LOOK);
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

    /** 原版 {@code applyItemBlocking} 的判断（不结算），见 {@link BossStrike#blocks}。 */
    static boolean blocks(LivingEntity target, DamageSource source, float damage) {
        return BossStrike.blocks(target, source, damage);
    }

    /** 身前扇形里能打到的生物（不打她自己的另一种形态），见 {@link BossStrike#targets}。 */
    public static List<LivingEntity> targets(Mob boss, double reach, double cone) {
        return BossStrike.targets(boss, reach, cone, e -> e instanceof ShiraumeBoss);
    }
}
