package dev.logos.dimension.shiraume;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import dev.logos.dimension.foxfire.FoxfireCue;
import dev.logos.dimension.foxfire.FoxfireFlame;
import dev.logos.runtime.Charging;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 对付术师（docs/SHIRAUME_BOSS.md 第 4.8 节）：同一个玩家的术式在 {@link #WINDOW} 刻里打中她 {@link #HITS} 次，她就盯上这个人
 * （他头上浮起一点淡粉的狐火，动作栏提示）。之后这个人一蓄力，她就冲过去挥出满是狐火的一刀「斩法」：
 * 伤害等于他已经蓄进去的魔力，不看护甲，盾也挡不住；蓄力被打断，蓄进去的魔力散成游离魔力。
 * 斩完隔 {@link #AGAIN} 刻才会再斩；被盯上的人死了、走远了或换了维度就放开。两种形态各有一份，变身时人形的交给狐形。
 */
public final class ShiraumeCounter {
    static final int HITS = 5, WINDOW = 600, AGAIN = 200;
    /** 多远之内的蓄力她会冲过去；走出这么远的两倍就放开。 */
    static final double RANGE = 28;
    /** 蓄得很少时这一刀至少这么疼。 */
    static final float MIN_DAMAGE = 8;

    private final Map<UUID, ArrayDeque<Long>> hits = new HashMap<>();
    private @Nullable UUID locked;
    private long again;
    /** 冲过去时对方已经蓄进去的魔力（到跟前时他刚好放掉了，就按这个算）。 */
    private double mana;
    private @Nullable ServerPlayer target;

    /** 盯上的人，没有为 null。 */
    public @Nullable UUID locked() {
        return locked;
    }

    /** 正冲过去要斩的人。 */
    @Nullable ServerPlayer target() {
        return target;
    }

    /** 她挨了一下 {@code by} 的术式。 */
    void spellHit(ServerLevel level, Mob boss, ServerPlayer by, long now) {
        if (by.isCreative() || by.isSpectator()) return;
        ArrayDeque<Long> q = hits.computeIfAbsent(by.getUUID(), k -> new ArrayDeque<>());
        q.addLast(now);
        while (!q.isEmpty() && now - q.peekFirst() > WINDOW) q.removeFirst();
        if (q.size() >= HITS && !by.getUUID().equals(locked)) lock(level, boss, by);
    }

    void lock(ServerLevel level, Mob boss, ServerPlayer p) {
        if (locked != null && !locked.equals(p.getUUID())) release(level);
        locked = p.getUUID();
        hits.clear();
        ShiraumeCue.near(level, p.position(), 96, ShiraumeCue.lock(p, true));
        p.sendOverlayMessage(Component.translatable("message.logos_dimension.shiraume.locked").withStyle(ChatFormatting.LIGHT_PURPLE));
        level.playSound(null, boss.getX(), boss.getEyeY(), boss.getZ(), SoundEvents.FOX_SCREECH, SoundSource.HOSTILE, 1.6f, 1.5f);
    }

    /** 放开盯上的人（他死了、走远了、战斗结束）。 */
    void release(ServerLevel level) {
        if (locked == null) return;
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(locked);
        if (p != null) ShiraumeCue.near(level, p.position(), 96, ShiraumeCue.lock(p, false));
        locked = null;
        target = null;
    }

    /**
     * 盯着的时候每两秒把头顶的标记重发一次：客户端五秒没收到就不画了，她没了、被移走了、放开的提示没收到，
     * 标记都不会一直挂在那人头上。
     */
    void refresh(ServerLevel level, long now) {
        if (locked == null || now % 40 != 0) return;
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(locked);
        if (p != null) ShiraumeCue.near(level, p.position(), 96, ShiraumeCue.lock(p, true));
    }

    /** 变身时把人形的这一份交给狐形（盯上的人、冷却）。 */
    void inherit(ShiraumeCounter from) {
        hits.putAll(from.hits);
        locked = from.locked;
        again = from.again;
    }

    /**
     * 盯上的人此刻正在蓄力、在 {@link #RANGE} 格内、冷却也过了：返回他并记下他蓄了多少（她这就冲过去），否则 null。
     */
    @Nullable ServerPlayer charging(ServerLevel level, Mob boss, long now) {
        if (locked == null) return null;
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(locked);
        if (p == null || !p.isAlive() || p.level() != level || p.isSpectator() || boss.distanceToSqr(p) > 4 * RANGE * RANGE) {
            release(level);
            return null;
        }
        if (now < again || p.isCreative() || boss.distanceToSqr(p) > RANGE * RANGE) return null;
        Charging.Charge c = Charging.of(p);
        if (c == null || c.injected <= 0) return null;
        mana = c.injected;
        target = p;
        again = now + AGAIN;
        return p;
    }

    /**
     * 那一刀砍到了：伤害等于此刻已经蓄进去的魔力（他刚放掉就按冲过去时记下的），打断蓄力。
     * 返回是否斩中（人跑出 {@code reach} 格就落空）。
     */
    boolean cut(ServerLevel level, Mob boss, double reach) {
        ServerPlayer p = target;
        target = null;
        if (p == null || !p.isAlive() || p.isCreative() || p.isSpectator() || p.level() != level) return false;
        Vec3 to = p.position().subtract(boss.position());
        if (Math.sqrt(to.x * to.x + to.z * to.z) > reach) return false;
        Charging.Charge c = Charging.of(p);
        double m = Math.max(mana, c == null ? 0 : c.injected);
        if (c != null) Charging.interrupt(p);
        float damage = (float) Math.max(MIN_DAMAGE, m);
        p.invulnerableTime = 0;
        p.hurtServer(level, ShiraumeStrike.counter(level, boss, m), damage);
        Vec3 at = p.position().add(0, p.getBbHeight() * .6, 0);
        FoxfireCue.hit(level, p, boss.getEyePosition(), FoxfireFlame.SHIRAUME);
        ShiraumeCue.near(level, at, 96, ShiraumeCue.counter(p, at, (float) m));
        level.playSound(null, at.x, at.y, at.z, SoundEvents.TRIDENT_RIPTIDE_3.value(), SoundSource.HOSTILE, 1.6f, 1.3f);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.FIRECHARGE_USE, SoundSource.HOSTILE, 1.4f, .6f);
        return true;
    }
}
