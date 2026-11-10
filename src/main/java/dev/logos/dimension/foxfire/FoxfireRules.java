package dev.logos.dimension.foxfire;

import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.corruption.mob.CorruptionMobs;
import dev.logos.dimension.foxfire.FoxfireAttachments.Burn;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import org.jspecify.annotations.Nullable;

/**
 * 狐火打中之后的事（docs/FOXFIRE.md 第 3 节）：
 * <ul>
 *   <li><b>狐火缠身</b>：目标身上燃几秒狐火，每秒烧一点狐火伤害。它不是原版的火：水浇不灭，抗火挡不住，也不点燃方块。
 *       再被打中只续上时间，不叠层。</li>
 *   <li><b>同族</b>：神狐一族（实体标签 {@code logos_dimension:foxfire_kin}）受到的狐火只剩四分之一，不会缠身也不会被惑；
 *       被腐化的同族反而更怕，受到的多一半，缠身也烧得更久——狐火能净化它们。</li>
 *   <li><b>惑</b>：怪物被打中时有机会丢掉目标、原地打转一会儿（Boss 不会；同一只隔一阵才会再被惑）；
 *       玩家被打中时画面边缘泛起一圈狐火色，一秒内退掉，只是看着。</li>
 * </ul>
 */
@EventBusSubscriber(modid = LogosDimension.ID)
public final class FoxfireRules {
    /** 同族受到的狐火只剩这么多。 */
    public static final float KIN_TAKEN = .25f;
    /** 被腐化的同族受到的狐火多这么多。 */
    public static final float CORRUPTED_TAKEN = 1.5f;
    /** 狐火缠身烧多少刻（被腐化的同族烧得更久），每秒烧多少。 */
    public static final int BURN_TICKS = 60, CORRUPTED_BURN_TICKS = 120;
    public static final double BURN_DAMAGE = 1;
    /** 惑：怪物被打中时的机会、丢掉目标多少刻、同一只至少隔多少刻才会再被惑。 */
    public static final float BEWITCH_CHANCE = .25f;
    public static final int BEWITCH_TICKS = 30, BEWITCH_AGAIN = 120;
    /** 测试用：惑必定发生。 */
    public static boolean alwaysBewitch;

    private record Bewitched(long until, long again) {}
    private static final Map<Mob, Bewitched> BEWITCHED = new WeakHashMap<>();

    private FoxfireRules() {}

    /** 不怕狐火的同族（没被腐化）。 */
    public static boolean kin(LivingEntity e) {
        return e.is(Foxfire.KIN) && !CorruptionMobs.isCorrupted(e);
    }

    /** 被腐化的同族：狐火能净化它们。 */
    public static boolean corruptedKin(LivingEntity e) {
        return e.is(Foxfire.KIN) && CorruptionMobs.isCorrupted(e);
    }

    /** 狐火打进去了（{@code attacker} 是记在谁名下）。 */
    static void landed(@Nullable Entity attacker, LivingEntity target, FoxfireFlame flame, boolean burn) {
        if (!(target.level() instanceof ServerLevel level) || kin(target) || !target.isAlive()) return;
        if (burn) burn(target, flame, attacker, corruptedKin(target) ? CORRUPTED_BURN_TICKS : BURN_TICKS);
        if (target instanceof ServerPlayer player) FoxfireCue.send(player, FoxfireCue.bewitch(flame));
        else if (target instanceof Mob mob) bewitch(level, mob, flame);
    }

    /** 让 {@code target} 狐火缠身 {@code ticks} 刻：已经在烧就续上（取长的），不叠层。 */
    public static void burn(LivingEntity target, FoxfireFlame flame, @Nullable Entity burner, int ticks) {
        if (kin(target)) return;
        long until = target.level().getGameTime() + ticks;
        Burn old = target.hasData(FoxfireAttachments.BURN) ? target.getData(FoxfireAttachments.BURN) : null;
        if (old != null && old.until() > until) until = old.until();
        UUID who = burner != null ? burner.getUUID() : old != null ? old.burner() : null;
        target.setData(FoxfireAttachments.BURN, new Burn(flame, until, who));
    }

    /** 正在狐火缠身的颜色，没在烧为 null。客户端也能用。 */
    public static @Nullable FoxfireFlame burning(LivingEntity e) {
        if (!e.hasData(FoxfireAttachments.BURN)) return null;
        Burn b = e.getData(FoxfireAttachments.BURN);
        return b.until() > e.level().getGameTime() ? b.flame() : null;
    }

    private static void bewitch(ServerLevel level, Mob mob, FoxfireFlame flame) {
        if (mob.is(Tags.EntityTypes.BOSSES)) return;
        long now = level.getGameTime();
        Bewitched was = BEWITCHED.get(mob);
        if (was != null && now < was.again()) return;
        if (!alwaysBewitch && mob.getRandom().nextFloat() >= BEWITCH_CHANCE) return;
        BEWITCHED.put(mob, new Bewitched(now + BEWITCH_TICKS, now + BEWITCH_AGAIN));
        mob.setTarget(null);
        mob.getBrain().eraseMemory(MemoryModuleType.ATTACK_TARGET);
        mob.getNavigation().stop();
        if (mob instanceof PathfinderMob walker) {
            Vec3 to = DefaultRandomPos.getPos(walker, 5, 2);
            if (to != null) walker.getNavigation().moveTo(to.x, to.y, to.z, 1.1);
        }
        FoxfireCue.daze(level, mob, flame, BEWITCH_TICKS);
    }

    /** 这只怪物正被惑着（找不了目标）。 */
    public static boolean bewitched(Mob mob) {
        Bewitched b = BEWITCHED.get(mob);
        return b != null && mob.level().getGameTime() < b.until();
    }

    @SubscribeEvent
    static void retarget(LivingChangeTargetEvent event) {
        if (event.getNewAboutToBeSetTarget() != null && event.getEntity() instanceof Mob mob && bewitched(mob)) event.setCanceled(true);
    }

    @SubscribeEvent
    static void incoming(LivingIncomingDamageEvent event) {
        if (!event.getSource().is(Foxfire.DAMAGE)) return;
        LivingEntity t = event.getEntity();
        if (kin(t)) event.setAmount(event.getAmount() * KIN_TAKEN);
        else if (corruptedKin(t)) event.setAmount(event.getAmount() * CORRUPTED_TAKEN);
    }

    /** 缠身：每秒烧一下（算在点火的人名下；点火的人不在了就算在最后打它的人名下，都没有就只是看着烧）。 */
    @SubscribeEvent
    static void tick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof LivingEntity t) || !(t.level() instanceof ServerLevel level) || !t.hasData(FoxfireAttachments.BURN)) return;
        Burn burn = t.getData(FoxfireAttachments.BURN);
        long now = level.getGameTime();
        if (now > burn.until() || !t.isAlive() || kin(t)) {
            t.removeData(FoxfireAttachments.BURN);
            return;
        }
        if ((burn.until() - now) % 20 != 0) return;
        LivingEntity by = burn.burner() != null && level.getEntity(burn.burner()) instanceof LivingEntity l && l.isAlive() ? l : t.getLastHurtByMob();
        if (by != null && by != t) Foxfire.deal(by, t, BURN_DAMAGE, new Foxfire.Context(burn.flame(), false));
        if (now >= burn.until()) t.removeData(FoxfireAttachments.BURN);
    }
}
