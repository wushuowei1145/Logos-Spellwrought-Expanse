package dev.logos.dimension.jinghong;

import dev.logos.dimension.LogosDimension;
import dev.logos.lang.Element;
import dev.logos.runtime.Effects;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/**
 * 惊鸿的剑阵（服务端）：持剑时身后扇形浮着五把剑（docs/JINGHONG.md）。
 * <ul>
 *   <li>左键砍中目标：每把闲着的剑各自有 {@link #FOLLOW_CHANCE} 的概率升起、从随机方向极快地穿过目标追加一击，再闪回头顶落回原位。</li>
 *   <li>弹剑（格挡开始 0.3 秒内挡下）：随机 {@link #PARRY_MIN}–{@link #PARRY_MAX} 把剑依次扑向被弹开的攻击方；
 *       普通格挡有 {@link #BLOCK_CHANCE} 的概率飞出一把。</li>
 *   <li>被触发过的剑激活 {@link #ACTIVE_TICKS} 刻：发光，之后的追击另加 Logos 物理元素伤害。</li>
 *   <li>格挡中按左键掷剑：手里的剑笔直掷向目标，再飞回手里；激活的剑同时出手、每把穿刺 {@link #VOLLEY_PASSES} 次；
 *       五把都激活时是万剑归宗。这一招之后清除所有激活。</li>
 * </ul>
 * 伤害、概率、目标都在这里；客户端只收 {@link SwordArrayPayload} 按 {@link SwordFlight} 的时间轴画。
 */
@EventBusSubscriber(modid = LogosDimension.ID)
public final class SwordArray {
    public static final ResourceKey<DamageType> DAMAGE = ResourceKey.create(Registries.DAMAGE_TYPE, LogosDimension.id("sword_array"));

    // ---- 数值：先定的，等维护者试玩再调。乘数都乘在玩家的攻击伤害属性上（惊鸿是铁剑，6）。
    /** 左键砍中时每把闲着的剑追击的概率。 */
    public static final double FOLLOW_CHANCE = 0.2;
    /** 普通格挡（不是弹剑）飞出一把剑的概率。 */
    public static final double BLOCK_CHANCE = 0.5;
    /** 弹剑后反击的剑数（含两端，随机）。 */
    public static final int PARRY_MIN = 2, PARRY_MAX = 5;
    /** 被触发后保持激活的刻（15 秒）。 */
    public static final int ACTIVE_TICKS = 300;
    /** 一次追击、掷剑、一把虚影剑、万剑归宗最后一贯的伤害乘数。 */
    public static final double STRIKE = 0.5, THROW = 1.0, PHANTOM = 0.25, PLUNGE = 2.5;
    /** 激活的剑每次追击另加的 Logos 物理伤害；万剑归宗最后一贯另加的（都再乘施法者的物理元素强度）。 */
    public static final double STRIKE_PHYSICAL = 2, PLUNGE_PHYSICAL = 15;
    /** 掷剑时激活的剑协同出手，每把穿过目标的次数。 */
    public static final int VOLLEY_PASSES = 2;
    /** 追击、反击的最远距离；掷剑找目标的距离；没有目标时剑飞出去的距离（格）。 */
    public static final double RANGE = 24, THROW_RANGE = 24, THROW_MISS = 16;
    /** 掷剑的辅助瞄准：准星没对着时，取离视线这么多度以内最近的生物。 */
    private static final double AIM_ASSIST = 6;
    /** 测试用：按玩家代替追击和普通格挡的概率（UI 场景、世界测试要确定地触发；世界测试并行跑，所以按玩家记）。 */
    private static final Map<UUID, Double> TEST_CHANCE = new java.util.concurrent.ConcurrentHashMap<>();

    private enum Kind { STRIKE, THROW, PHANTOM, PLUNGE }
    private record Pending(UUID player, int target, long at, Kind kind, boolean lit) {}

    /** 一名玩家身后五把剑的状态：激活到哪一刻、飞出去要到哪一刻才回到原位（游戏刻）。 */
    private static final class State {
        final long[] activeUntil = new long[SwordFlight.FAN];
        final long[] busyUntil = new long[SwordFlight.FAN];
    }
    private static final Map<UUID, State> STATES = new HashMap<>();
    private static final List<Pending> PENDING = new ArrayList<>();

    private SwordArray() {}

    @SubscribeEvent static void packets(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToClient(SwordArrayPayload.Active.TYPE, SwordArrayPayload.Active.CODEC);
        registrar.playToClient(SwordArrayPayload.Strike.TYPE, SwordArrayPayload.Strike.CODEC);
        registrar.playToClient(SwordArrayPayload.Throw.TYPE, SwordArrayPayload.Throw.CODEC);
        registrar.playToClient(SwordArrayPayload.Finale.TYPE, SwordArrayPayload.Finale.CODEC);
    }

    private static State state(ServerPlayer player) { return STATES.computeIfAbsent(player.getUUID(), u -> new State()); }
    private static long now(ServerPlayer player) { return player.level().getGameTime(); }

    /** 第 {@code sword} 把剑现在激活着。 */
    public static boolean active(ServerPlayer player, int sword) {
        var s = STATES.get(player.getUUID());
        return s != null && s.activeUntil[sword] > now(player);
    }
    /** 激活着的剑数。 */
    public static int activeCount(ServerPlayer player) {
        int n = 0;
        for (int i = 0; i < SwordFlight.FAN; i++) if (active(player, i)) n++;
        return n;
    }
    /** 第 {@code sword} 把剑飞出去了、还没回到原位。 */
    public static boolean busy(ServerPlayer player, int sword) {
        var s = STATES.get(player.getUUID());
        return s != null && s.busyUntil[sword] > now(player);
    }

    // ---------------------------------------------------------------- 触发

    /** 左键这一刀砍中了 {@code target}（这一刀的第一个目标）：闲着的剑各自掷骰子追击。 */
    public static void followUp(ServerPlayer player, Entity target) {
        if (!valid(player, target)) return;
        var s = state(player); long now = now(player);
        double chance = TEST_CHANCE.getOrDefault(player.getUUID(), FOLLOW_CHANCE);
        int k = 0;
        for (int i = 0; i < SwordFlight.FAN; i++)
            if (s.busyUntil[i] <= now && player.getRandom().nextDouble() < chance) launch(player, s, i, target, now + 2L * k++, 1, true);
    }

    /** 挡下了一击：弹剑时随机 2–5 把剑依次扑向攻击方，普通格挡有一半的概率飞出一把。 */
    public static void blocked(ServerPlayer player, DamageSource source, boolean parried) {
        Entity attacker = source.getEntity();
        if (!valid(player, attacker)) return;
        var s = state(player); long now = now(player);
        var random = player.getRandom();
        int count;
        if (parried) count = PARRY_MIN + random.nextInt(PARRY_MAX - PARRY_MIN + 1);
        else count = random.nextDouble() < TEST_CHANCE.getOrDefault(player.getUUID(), BLOCK_CHANCE) ? 1 : 0;
        var idle = idle(s, now);
        // 随机挑：打乱闲着的剑，取前 count 把
        for (int i = idle.size() - 1; i > 0; i--) { int j = random.nextInt(i + 1); var t = idle.get(i); idle.set(i, idle.get(j)); idle.set(j, t); }
        for (int k = 0; k < Math.min(count, idle.size()); k++) launch(player, s, idle.get(k), attacker, now + 2L * k, 1, true);
    }

    /**
     * 掷剑出手（掷剑动作的 release）：手里的剑掷向目标，激活且闲着的剑同时出手；五把都激活且都在原位时是万剑归宗。
     * 有目标时这一招之后清除所有激活（还在飞的剑回到原位时熄灭）；没有目标时剑飞出去再回来，激活保留。
     */
    public static void throwSword(ServerPlayer player) {
        var s = state(player); long now = now(player);
        var target = throwTarget(player);
        var level = (ServerLevel) player.level();
        level.playSound(null, player.getX(), player.getEyeY(), player.getZ(), SoundEvents.TRIDENT_THROW.value(), SoundSource.PLAYERS, 1, 1.3f);
        if (target == null) {
            var from = player.getEyePosition();
            var end = from.add(player.getLookAngle().scale(THROW_MISS));
            var block = level.clip(new ClipContext(from, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
            var at = block.getLocation();
            send(player, new SwordArrayPayload.Throw(player.getUUID(), -1, now, at.x, at.y, at.z));
            return;
        }
        PENDING.add(new Pending(player.getUUID(), target.getId(), now + SwordFlight.THROW_HIT, Kind.THROW, false));
        send(player, new SwordArrayPayload.Throw(player.getUUID(), target.getId(), now, 0, 0, 0));
        List<Integer> lit = new ArrayList<>();
        for (int i = 0; i < SwordFlight.FAN; i++) if (s.activeUntil[i] > now && s.busyUntil[i] <= now) lit.add(i);
        if (lit.size() == SwordFlight.FAN) {
            finale(player, s, target, now);
            return;
        }
        for (int k = 0; k < lit.size(); k++) launch(player, s, lit.get(k), target, now + 1 + k, VOLLEY_PASSES, false);
        // 这一招之后清除激活：出手的剑飞完回位时熄灭，其余（正在飞的、没激活的）回到原位就熄灭。
        for (int i = 0; i < SwordFlight.FAN; i++) s.activeUntil[i] = Math.min(s.activeUntil[i], Math.max(s.busyUntil[i], now + 1));
        sendActive(player, s);
    }

    private static void finale(ServerPlayer player, State s, Entity target, long now) {
        for (int i = 0; i < SwordFlight.FAN; i++) {
            s.busyUntil[i] = now + SwordFlight.FINALE_HOME;
            // 一直亮到回位，然后清除
            s.activeUntil[i] = now + SwordFlight.FINALE_HOME;
        }
        for (int m = 0; m < SwordFlight.PHANTOM_HITS; m++)
            PENDING.add(new Pending(player.getUUID(), target.getId(), now + SwordFlight.phantomHit(m), Kind.PHANTOM, true));
        PENDING.add(new Pending(player.getUUID(), target.getId(), now + SwordFlight.PLUNGE_HIT, Kind.PLUNGE, true));
        send(player, new SwordArrayPayload.Finale(player.getUUID(), target.getId(), now, player.getRandom().nextInt()));
        sendActive(player, s);
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.TRIDENT_RIPTIDE_3.value(), SoundSource.PLAYERS, 1, 1.2f);
    }

    /**
     * 第 {@code sword} 把剑在 {@code start} 刻出发打 {@code target}，穿过 {@code passes} 次；{@code refresh} 为真时这次触发让它激活
     * （刷新时长；掷剑协同出手时为假，激活由掷剑自己清除）。出发时已经激活的剑带物理伤害。
     */
    private static void launch(ServerPlayer player, State s, int sword, Entity target, long start, int passes, boolean refresh) {
        boolean lit = s.activeUntil[sword] > start;
        var random = player.getRandom();
        float[] dirs = new float[3 * passes];
        for (int p = 0; p < passes; p++) {
            var d = direction(random);
            dirs[3 * p] = (float) d.x; dirs[3 * p + 1] = (float) d.y; dirs[3 * p + 2] = (float) d.z;
            PENDING.add(new Pending(player.getUUID(), target.getId(), start + SwordFlight.hit(p), Kind.STRIKE, lit));
        }
        s.busyUntil[sword] = start + SwordFlight.duration(passes);
        boolean wasLit = s.activeUntil[sword] > now(player);
        if (refresh) s.activeUntil[sword] = Math.max(s.activeUntil[sword], start + ACTIVE_TICKS);
        send(player, new SwordArrayPayload.Strike(player.getUUID(), sword, target.getId(), start, dirs, lit));
        sendActive(player, s);
        var level = player.level();
        level.playSound(null, player.getX(), player.getEyeY(), player.getZ(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, .45f, 1.9f);
        if (refresh && !wasLit) {
            // 新亮起一把：清脆的一声，亮的越多越高
            int lit2 = 0;
            for (int i = 0; i < SwordFlight.FAN; i++) if (s.activeUntil[i] > now(player)) lit2++;
            level.playSound(null, player.getX(), player.getEyeY(), player.getZ(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS,
                    .9f, .8f + .15f * lit2);
        }
    }

    /** 穿过目标的方向：从斜上方随机一个方位扑下来（俯角 10°–55°）。 */
    public static Vec3 direction(RandomSource random) {
        double az = random.nextDouble() * Math.PI * 2, el = Math.toRadians(10 + 45 * random.nextDouble());
        return new Vec3(Math.cos(el) * Math.cos(az), -Math.sin(el), Math.cos(el) * Math.sin(az));
    }

    private static List<Integer> idle(State s, long now) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < SwordFlight.FAN; i++) if (s.busyUntil[i] <= now) out.add(i);
        return out;
    }

    private static boolean valid(ServerPlayer player, Entity target) {
        return target instanceof LivingEntity living && living.isAlive() && target != player && !target.isSpectator()
                && target.level() == player.level() && target.distanceTo(player) <= RANGE
                && !(target instanceof net.minecraft.world.entity.TamableAnimal pet && pet.isOwnedBy(player));
    }

    /** 掷剑的目标：准星对着的生物（碰到方块为止）；没有就取离视线 {@link #AIM_ASSIST} 度以内、看得见的最近的。 */
    public static LivingEntity throwTarget(ServerPlayer player) {
        var level = player.level();
        var from = player.getEyePosition(); var look = player.getLookAngle();
        var to = from.add(look.scale(THROW_RANGE));
        var block = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        var end = block.getLocation();
        var hit = ProjectileUtil.getEntityHitResult(level, player, from, end,
                player.getBoundingBox().expandTowards(end.subtract(from)).inflate(1), e -> !e.isSpectator() && e.isPickable(), 0);
        if (hit != null && hit.getEntity() instanceof LivingEntity living && valid(player, living)) return living;
        double cos = Math.cos(Math.toRadians(AIM_ASSIST));
        LivingEntity best = null; double bestDot = cos;
        for (var e : level.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(THROW_RANGE), e -> valid(player, e))) {
            var d = e.getBoundingBox().getCenter().subtract(from);
            double dist = d.length();
            if (dist < 1e-3 || dist > THROW_RANGE) continue;
            double dot = d.dot(look) / dist;
            if (dot > bestDot && player.hasLineOfSight(e)) { best = e; bestDot = dot; }
        }
        return best;
    }

    /**
     * 掷剑时挡不住：横剑的架势已经收了，但右键还按着（原版仍在“使用”），这里把格挡取消。不停掉原版的使用：
     * 停了的话客户端会自己预测成仍在使用、不再发右键，掷剑结束后就回不到格挡；不停的话掷剑播完自动回到格挡。
     */
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.HIGHEST)
    static void noBlockWhileThrowing(net.neoforged.neoforge.event.entity.living.LivingShieldBlockEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && player.getMainHandItem().getItem() instanceof dev.logos.dimension.item.JinghongSwordItem
                && dev.logos.dimension.item.JinghongSwordItem.THROW.equals(dev.logos.dimension.longsword.LongswordCombat.move(player)))
            event.setBlocked(false);
    }

    // ---------------------------------------------------------------- 结算

    @SubscribeEvent static void tick(ServerTickEvent.Post event) {
        if (PENDING.isEmpty()) return;
        var server = event.getServer();
        var due = new ArrayList<Pending>();
        PENDING.removeIf(p -> {
            var player = server.getPlayerList().getPlayer(p.player);
            if (player == null) return true;
            if (p.at > now(player)) return false;
            due.add(p);
            return true;
        });
        for (var p : due) resolve(server.getPlayerList().getPlayer(p.player), p);
    }

    private static void resolve(ServerPlayer player, Pending p) {
        if (player == null || !player.isAlive()) return;
        var level = (ServerLevel) player.level();
        if (!(level.getEntity(p.target) instanceof LivingEntity target) || !target.isAlive() || target.distanceTo(player) > RANGE + 8) return;
        double attack = player.getAttributeValue(Attributes.ATTACK_DAMAGE);
        var at = target.getBoundingBox().getCenter();
        switch (p.kind) {
            case STRIKE -> {
                hurt(level, player, target, STRIKE * attack);
                if (p.lit && target.isAlive()) Effects.followUp(player, target, STRIKE_PHYSICAL, Element.PHYSICAL);
                level.sendParticles(ParticleTypes.CRIT, at.x, at.y, at.z, 8, .25, .25, .25, .35);
                level.playSound(null, at.x, at.y, at.z, SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.PLAYERS, .55f, 1.5f);
            }
            case THROW -> {
                hurt(level, player, target, THROW * attack);
                level.sendParticles(ParticleTypes.CRIT, at.x, at.y, at.z, 12, .3, .3, .3, .4);
                level.playSound(null, at.x, at.y, at.z, SoundEvents.TRIDENT_HIT, SoundSource.PLAYERS, .9f, 1.2f);
            }
            case PHANTOM -> {
                hurt(level, player, target, PHANTOM * attack);
                level.sendParticles(ParticleTypes.ENCHANTED_HIT, at.x, at.y, at.z, 6, .3, .3, .3, .2);
                level.playSound(null, at.x, at.y, at.z, SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, .35f, 1.6f + .3f * level.getRandom().nextFloat());
            }
            case PLUNGE -> {
                hurt(level, player, target, PLUNGE * attack);
                if (target.isAlive()) Effects.followUp(player, target, PLUNGE_PHYSICAL, Element.PHYSICAL);
                level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, at.x, target.getY() + .2, at.z, 1, 0, 0, 0, 0);
                for (int i = 0; i < 16; i++) {
                    double a = Math.PI * 2 * i / 16;
                    level.sendParticles(ParticleTypes.SWEEP_ATTACK, at.x + Math.cos(a) * 1.6, target.getY() + .3, at.z + Math.sin(a) * 1.6, 1, 0, 0, 0, 0);
                }
                level.playSound(null, at.x, at.y, at.z, SoundEvents.TRIDENT_THUNDER.value(), SoundSource.PLAYERS, .9f, 1.3f);
                level.playSound(null, at.x, at.y, at.z, SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, .5f, .6f);
            }
        }
    }

    /** 剑阵的伤害：算在玩家头上（击杀、仇恨），伤害类型 {@code logos_dimension:sword_array}（不吃受击冷却、不击退）。 */
    private static void hurt(ServerLevel level, ServerPlayer player, LivingEntity target, double amount) {
        if (amount <= 0) return;
        var type = level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(DAMAGE);
        target.hurtServer(level, new DamageSource(type, player), (float) amount);
    }

    // ---------------------------------------------------------------- 同步

    private static void send(ServerPlayer player, CustomPacketPayload payload) {
        // 模拟玩家没有协商客户端通道；只给观看此区块且支持剑阵包的连接同步。
        for (var viewer : ((ServerLevel) player.level()).players())
            if ((viewer == player || viewer.getChunkTrackingView().contains(player.chunkPosition()))
                    && NetworkRegistry.hasChannel(viewer.connection, payload.type().id()))
                PacketDistributor.sendToPlayer(viewer, payload);
    }
    private static void sendActive(ServerPlayer player, State s) {
        send(player, new SwordArrayPayload.Active(player.getUUID(), s.activeUntil.clone()));
    }

    @SubscribeEvent static void tracking(PlayerEvent.StartTracking event) {
        if (event.getEntity() instanceof ServerPlayer viewer && event.getTarget() instanceof ServerPlayer target) {
            var s = STATES.get(target.getUUID());
            if (s != null && NetworkRegistry.hasChannel(viewer.connection, SwordArrayPayload.Active.TYPE.id()))
                PacketDistributor.sendToPlayer(viewer, new SwordArrayPayload.Active(target.getUUID(), s.activeUntil.clone()));
        }
    }
    /** 死了、离线、换维度：剑阵清零。 */
    private static void reset(ServerPlayer player) {
        var s = STATES.remove(player.getUUID());
        PENDING.removeIf(p -> p.player.equals(player.getUUID()));
        if (s != null) send(player, new SwordArrayPayload.Active(player.getUUID(), new long[SwordFlight.FAN]));
    }
    @SubscribeEvent static void death(LivingDeathEvent event) { if (event.getEntity() instanceof ServerPlayer player) reset(player); }
    @SubscribeEvent static void dimension(PlayerEvent.PlayerChangedDimensionEvent event) { if (event.getEntity() instanceof ServerPlayer player) reset(player); }
    @SubscribeEvent static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        STATES.remove(event.getEntity().getUUID());
        TEST_CHANCE.remove(event.getEntity().getUUID());
        PENDING.removeIf(p -> p.player.equals(event.getEntity().getUUID()));
    }
    @SubscribeEvent static void stopped(ServerStoppedEvent event) { STATES.clear(); PENDING.clear(); TEST_CHANCE.clear(); }

    /** 测试用：这名玩家的追击与普通格挡概率定成 {@code chance}；null 恢复正常。 */
    public static void testChance(ServerPlayer player, Double chance) {
        if (chance == null) TEST_CHANCE.remove(player.getUUID()); else TEST_CHANCE.put(player.getUUID(), chance);
    }
    /** 测试用：直接把某几把剑设成激活（不出手）。 */
    public static void activateForTest(ServerPlayer player, int count) {
        var s = state(player); long now = now(player);
        for (int i = 0; i < SwordFlight.FAN; i++) s.activeUntil[i] = i < count ? now + ACTIVE_TICKS : 0;
        sendActive(player, s);
    }
    /** 测试用：剑阵结算完、都回到原位了。 */
    public static boolean settled(ServerPlayer player) {
        var s = STATES.get(player.getUUID());
        if (s == null) return true;
        for (long b : s.busyUntil) if (b > now(player)) return false;
        return PENDING.stream().noneMatch(p -> p.player.equals(player.getUUID()));
    }
}
