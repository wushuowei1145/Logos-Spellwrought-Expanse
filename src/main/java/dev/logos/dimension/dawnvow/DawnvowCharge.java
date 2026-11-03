package dev.logos.dimension.dawnvow;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.item.LongswordItem;
import dev.logos.runtime.PlayerMagic;
import dev.logos.runtime.SurfaceAura;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import dev.logos.lang.Element;
import dev.logos.runtime.Effects;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.jspecify.annotations.Nullable;

/**
 * 曦誓的蓄力彩蛋（致敬《Fate》里的誓约胜利之剑）。长剑的蓄力斩照旧，曦誓另外：
 * <ul>
 *   <li>蓄力斩劈下时带出一道贴地的剑气（{@link SwordWave#small}）；</li>
 *   <li>按住超过 {@link #GRAND_TICKS} 刻（7 秒）进入大型蓄力：站定不能移动，四周升起金色光点，身上出现金色的蓄力光；
 *       从这时起到蓄满共付 {@link #COST} 魔力，按刻平摊，不够时大型蓄力中断、回到普通蓄力；</li>
 *   <li>第 {@link #BLADE_TICKS} 刻（12 秒）剑身发出长长的金光；</li>
 *   <li>第 {@link #FULL_TICKS} 刻（15 秒）蓄满：剑身喷出金色流火（Logos 的物品表面火焰，转成金色）；</li>
 *   <li>蓄满后松手劈下，放出巨型剑气（{@link SwordWave#grand}）。没蓄满就松手，只放普通剑气，已付的魔力不退。</li>
 *   <li>巨型剑气放出后 {@link #BLESS_TICKS} 刻（60 秒）内，剑身的长金光不散：手持曦誓时攻击距离多 {@link #BLESS_REACH} 格，
 *       每一下命中另加 {@link #BLESS_DAMAGE} 点物理伤害。</li>
 * </ul>
 * 只看招式状态机给的招式名（{@code charge_start}、{@code charge_hold}、{@code charge_release}），不改长剑的动作。
 */
public final class DawnvowCharge {
    public static final int GRAND_TICKS = 140, BLADE_TICKS = 240, FULL_TICKS = 300;
    public static final double COST = 1000;
    /** 每刻扣的魔力：从进入大型蓄力到蓄满平摊。 */
    public static final double DRAIN = COST / (FULL_TICKS - GRAND_TICKS);
    /** 巨型剑气每刻走多少格：64 格十六刻走完。 */
    public static final double GRAND_SPEED = 4;
    /** 蓄满时剑身火焰转的色相（Logos 的火焰原本是橙色，转一点成金色）。 */
    public static final float FLAME_HUE = 0.045f;
    private static final Identifier ROOTED = LogosDimension.id("dawnvow_rooted"), REACH = LogosDimension.id("dawnvow_blessed_reach");
    /** 放出巨型剑气后剑身金光保留的刻数，期间多出的攻击距离与每击的额外物理伤害。 */
    public static final int BLESS_TICKS = 1200;
    public static final double BLESS_REACH = 3, BLESS_DAMAGE = 15;
    /** 多远的玩家看得到蓄力和剑气。 */
    private static final double VIEW = 160;

    private static final Map<UUID, Charge> CHARGES = new HashMap<>();
    /** 剑身金光保留到哪一刻。 */
    private static final Map<UUID, Long> BLESSED = new HashMap<>();

    private DawnvowCharge() {}

    private static final class Charge {
        final ItemStack stack;
        final long start;
        boolean grand, failed, full, released, fired;
        double paid;
        @Nullable SurfaceAura saved;

        Charge(ItemStack stack, long start) { this.stack = stack; this.start = start; }
    }

    public static void register(IEventBus bus) {
        bus.addListener((RegisterPayloadHandlersEvent event) -> event.registrar("1").playToClient(DawnvowPayload.TYPE, DawnvowPayload.CODEC));
        NeoForge.EVENT_BUS.addListener(SwordWave::tick);
        NeoForge.EVENT_BUS.addListener(SwordWave::stopped);
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> { CHARGES.clear(); BLESSED.clear(); });
        NeoForge.EVENT_BUS.addListener(DawnvowCharge::login);
        NeoForge.EVENT_BUS.addListener(DawnvowCharge::playerTick);
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> BLESSED.remove(event.getEntity().getUUID()));
    }

    public static boolean dawnvow(ItemStack stack) { return stack.is(LongswordItem.DAWNVOW.get()); }

    /** 这次蓄力按了多少刻；没有在蓄力时为 -1（世界测试用）。 */
    public static long charged(ServerPlayer p) {
        Charge c = CHARGES.get(p.getUUID());
        return c == null || c.released ? -1 : p.level().getGameTime() - c.start;
    }

    public static boolean grand(ServerPlayer p) {
        Charge c = CHARGES.get(p.getUUID());
        return c != null && c.grand;
    }

    /** 这次蓄力已经付了多少魔力（世界测试用；魔力池自己还在回复，看余额对不上）。 */
    public static double paid(ServerPlayer p) {
        Charge c = CHARGES.get(p.getUUID());
        return c == null ? 0 : c.paid;
    }

    /** 剑身的长金光还在（巨型剑气放出后 60 秒内）。 */
    public static boolean blessed(ServerPlayer p) {
        Long until = BLESSED.get(p.getUUID());
        return until != null && p.level().getGameTime() < until;
    }

    /** 让剑身的长金光保留 {@link #BLESS_TICKS} 刻（巨型剑气放出时调用；世界测试也直接调用）。 */
    public static void bless(ServerPlayer p) {
        long until = p.level().getGameTime() + BLESS_TICKS;
        BLESSED.put(p.getUUID(), until);
        send((ServerLevel) p.level(), p.position(), DawnvowPayload.of(DawnvowPayload.BLESS, p.getId(), until));
    }

    /** 金光在时，手持曦誓才多出攻击距离；离开曦誓、时间到或死亡就收回。 */
    private static void playerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        Long until = BLESSED.get(p.getUUID());
        if (until != null && (!p.isAlive() || p.level().getGameTime() >= until)) {
            BLESSED.remove(p.getUUID());
            until = null;
        }
        AttributeInstance reach = p.getAttribute(Attributes.ENTITY_INTERACTION_RANGE);
        if (reach == null) return;
        boolean on = until != null && dawnvow(p.getMainHandItem());
        if (on && !reach.hasModifier(REACH)) reach.addTransientModifier(new AttributeModifier(REACH, BLESS_REACH, AttributeModifier.Operation.ADD_VALUE));
        else if (!on && reach.hasModifier(REACH)) reach.removeModifier(REACH);
    }

    /** 长剑招式命中了目标之后：金光在时另加 {@link #BLESS_DAMAGE} 点物理伤害。 */
    public static void hit(ServerPlayer p, net.minecraft.world.entity.Entity target) {
        if (blessed(p) && dawnvow(p.getMainHandItem()) && target instanceof net.minecraft.world.entity.LivingEntity living && living.isAlive())
            Effects.followUp(p, living, BLESS_DAMAGE, Element.PHYSICAL);
    }

    public static boolean full(ServerPlayer p) {
        Charge c = CHARGES.get(p.getUUID());
        return c != null && c.full;
    }

    /** 发给附近、和服务端协商过本附属频道的玩家（游戏测试里的假玩家没有）。 */
    private static void send(ServerLevel level, Vec3 at, DawnvowPayload payload) {
        for (var player : level.players())
            if (player instanceof ServerPlayer sp && sp.distanceToSqr(at) <= VIEW * VIEW && sp.connection.hasChannel(DawnvowPayload.TYPE))
                PacketDistributor.sendToPlayer(sp, payload);
    }

    /** 招式状态机每刻报告手持曦誓的玩家当前的招式。 */
    public static void tick(ServerPlayer p, ItemStack stack, String move) {
        ServerLevel level = (ServerLevel) p.level();
        long now = level.getGameTime();
        Charge c = CHARGES.get(p.getUUID());
        switch (move) {
            case "charge_start", "charge_hold" -> {
                if (c == null || c.released || c.stack != stack) {
                    if (c != null) finish(p, c);
                    c = new Charge(stack, now);
                    CHARGES.put(p.getUUID(), c);
                }
                charging(level, p, c, now - c.start);
            }
            case "charge_release" -> { if (c != null) c.released = true; }
            default -> { if (c != null) finish(p, c); }
        }
    }

    private static void charging(ServerLevel level, ServerPlayer p, Charge c, long t) {
        if (c.failed || c.full) return;
        if (!c.grand) {
            if (t < GRAND_TICKS) return;
            if (!pay(p, c)) return;
            c.grand = true;
            root(p, true);
            send(level, p.position(), DawnvowPayload.of(DawnvowPayload.GRAND, p.getId(), c.start));
            level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 1.5f, .8f);
            return;
        }
        if (!pay(p, c)) {
            // 魔力不够了：回到普通蓄力，已付的不退
            c.grand = false;
            root(p, false);
            send(level, p.position(), DawnvowPayload.of(DawnvowPayload.STOP, p.getId(), c.start));
            return;
        }
        if (t == BLADE_TICKS) level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 2, .6f);
        if (t >= FULL_TICKS) {
            c.full = true;
            c.saved = SurfaceAura.of(c.stack);
            SurfaceAura.set(c.stack, SurfaceAura.Style.FIRE, FLAME_HUE);
            send(level, p.position(), DawnvowPayload.of(DawnvowPayload.FULL, p.getId(), c.start));
            level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BEACON_POWER_SELECT, SoundSource.PLAYERS, 2, .7f);
            level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BELL_RESONATE, SoundSource.PLAYERS, 2, 1.2f);
        } else if (t % 40 == 0) {
            level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BEACON_AMBIENT, SoundSource.PLAYERS, 1.5f, 1 + (t - GRAND_TICKS) / 200f);
        }
    }

    /** 付这一刻的魔力；不够时提示并记下本次蓄力不再进入大型蓄力。 */
    private static boolean pay(ServerPlayer p, Charge c) {
        double owed = Math.min(DRAIN, COST - c.paid);
        if (owed <= 0) return true;
        var magic = PlayerMagic.of(p);
        if (magic.mana < owed) {
            PlayerMagic.alert(p, Component.translatable("message.logos_dimension.dawnvow.mana", (int) COST, (int) magic.mana).withStyle(ChatFormatting.RED));
            c.failed = true;
            return false;
        }
        magic.mana -= owed;
        c.paid += owed;
        return true;
    }

    /** 蓄力斩接触剑刃的一刻：放出剑气。 */
    public static void contact(ServerPlayer p, String move) {
        Charge c = CHARGES.get(p.getUUID());
        if (!"charge_release".equals(move) || c == null || !c.released || c.fired) return;
        c.fired = true;
        ServerLevel level = (ServerLevel) p.level();
        if (!c.full) {
            SwordWave.small(level, p);
            return;
        }
        Vec3 dir = SwordWave.flat(p.getLookAngle());
        Vec3 from = p.position().add(dir.scale(1.2)).with(net.minecraft.core.Direction.Axis.Y, p.getY());
        float[] path = SwordWave.grand(level, p, from, dir);
        double length = (path.length - 1) * SwordWave.PATH_STEP;
        send(level, from.add(dir.scale(length / 2)), new DawnvowPayload(DawnvowPayload.RELEASE, p.getId(), level.getGameTime(), from, dir, path));
        bless(p);
        level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.TRIDENT_THUNDER.value(), SoundSource.PLAYERS, 4, .9f);
        level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 2, .5f);
    }

    /** 蓄力结束（放出后收势完、取消、换手或离开）：解开站定、收起火焰、告诉客户端收掉效果。 */
    private static void finish(ServerPlayer p, Charge c) {
        CHARGES.remove(p.getUUID());
        root(p, false);
        if (c.full) SurfaceAura.set(c.stack, c.saved == null ? null : c.saved.style(), c.saved == null ? 0 : c.saved.hue());
        // 中途松手也会劈出普通剑气（fired），身上的光同样要收掉；放出巨型剑气时客户端已经收了，再收一次无妨
        if (c.grand) send((ServerLevel) p.level(), p.position(), DawnvowPayload.of(DawnvowPayload.STOP, p.getId(), c.start));
    }

    /** 招式状态被清掉（收剑完、死亡、离线、换维度）。 */
    public static void end(ServerPlayer p) {
        Charge c = CHARGES.get(p.getUUID());
        if (c != null) finish(p, c);
    }

    private static void root(ServerPlayer p, boolean on) {
        root(p.getAttribute(Attributes.MOVEMENT_SPEED), on);
        root(p.getAttribute(Attributes.JUMP_STRENGTH), on);
    }

    private static void root(@Nullable AttributeInstance attribute, boolean on) {
        if (attribute == null) return;
        if (on) attribute.addOrUpdateTransientModifier(new AttributeModifier(ROOTED, -1, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        else attribute.removeModifier(ROOTED);
    }

    /** 上次蓄满时服务器关掉或崩溃，剑上可能还留着金色火焰：登录时去掉。 */
    private static void login(PlayerEvent.PlayerLoggedInEvent event) {
        var inventory = event.getEntity().getInventory();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            SurfaceAura aura = SurfaceAura.of(stack);
            if (dawnvow(stack) && aura != null && aura.style() == SurfaceAura.Style.FIRE && aura.hue() == FLAME_HUE) SurfaceAura.set(stack, null, 0);
        }
    }
}
