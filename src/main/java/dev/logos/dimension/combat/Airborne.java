package dev.logos.dimension.combat;

import dev.logos.dimension.LogosDimension;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 击飞（浮空）状态：被长枪上挑挑中的目标升到半空、停一会儿再落下。体积越大，停得越短、升得越低。
 *
 * <p>非玩家的生物在空中什么也做不了：期间临时关掉 AI（原版的 NoAI，连带不受重力、不自己移动），由这里按刻把它沿
 * “上升—悬停”的路线移动（{@link Entity#move}，碰到天花板会停），结束时恢复原来的 AI 设置，交给原版重力落下。
 * 期间加在实体上的标签保证卸载、崩溃后再加载时也会恢复。
 * <p>玩家可以照常行动：给一个向上的初速度，到顶后临时把重力属性归零悬停，结束时撤掉。服务端检测“非法飞行”是悬空 80 刻踢人，
 * 玩家的浮空总长限制在 {@link #PLAYER_MAX_TICKS} 刻以内。
 * <p>下砸（{@link #slam}）提前结束状态，并按原版摔落流程（{@code fallOn} → {@code causeFallDamage}，摔落伤害类型，
 * 摔落保护、免疫摔落的生物照常生效）结算一次高额摔落伤害。
 */
@EventBusSubscriber(modid = LogosDimension.ID)
public final class Airborne {
    /** 不会被挑飞的实体（Boss 等）。 */
    public static final TagKey<EntityType<?>> IMMUNE = TagKey.create(Registries.ENTITY_TYPE, LogosDimension.id("airborne_immune"));
    private static final String TAG = "logos_dimension.airborne", NO_AI_TAG = "logos_dimension.airborne_no_ai";
    private static final Identifier FLOAT = LogosDimension.id("airborne_float");
    /** 原版玩家的体积（0.6 × 1.8 × 0.6），以它为基准换算体型。 */
    private static final double REFERENCE_VOLUME = 0.6 * 0.6 * 1.8;
    /** 玩家体型的浮空时长与高度；体型系数按体积的立方根反比，夹在 0.35–1.5 倍。 */
    static final int BASE_TICKS = 32, MIN_TICKS = 10, MAX_TICKS = 48;
    static final double BASE_HEIGHT = 1.9, MIN_HEIGHT = 0.9, MAX_HEIGHT = 2.3;
    /** 上升用的刻数（之后悬停）。 */
    static final int RISE_TICKS = 7;
    /** 玩家浮空的总刻数上限（低于服务端踢人的 80 刻）。 */
    public static final int PLAYER_MAX_TICKS = 70;
    /** 连刺每命中一下延长的刻数，与延长后剩余时间的上限。 */
    public static final int EXTEND_TICKS = 3, EXTEND_CAP = 24;
    /** 下砸额外计入的摔落距离（格）；原版按 摔落距离 − 3 计伤害。 */
    public static final double SLAM_FALL = 12;

    private static final class State {
        final LivingEntity entity;
        final UUID by;
        final double baseY, height;
        final long start;
        long end;
        final boolean changedAi;
        double bump;
        State(LivingEntity entity, UUID by, double baseY, double height, long start, long end, boolean changedAi) {
            this.entity = entity; this.by = by; this.baseY = baseY; this.height = height; this.start = start; this.end = end; this.changedAi = changedAi;
        }
    }
    private static final Map<LivingEntity, State> STATES = new IdentityHashMap<>();

    private Airborne() {}

    /** 体型系数：玩家体型为 1，越大越小。 */
    public static double sizeFactor(double width, double height) {
        double volume = Math.max(width * width * height, 1e-3);
        return Math.clamp(Math.cbrt(REFERENCE_VOLUME / volume), 0.35, 1.5);
    }
    /** 浮空时长（刻）。 */
    public static int durationTicks(double width, double height) {
        return (int) Math.clamp(Math.round(BASE_TICKS * sizeFactor(width, height)), MIN_TICKS, MAX_TICKS);
    }
    /** 升起的高度（格）。 */
    public static double liftHeight(double width, double height) {
        return Math.clamp(BASE_HEIGHT * Math.sqrt(sizeFactor(width, height)), MIN_HEIGHT, MAX_HEIGHT);
    }

    public static boolean canLaunch(LivingEntity e) {
        if (!e.isAlive() || e.getType().builtInRegistryHolder().is(IMMUNE) || e.isPassenger() || e.isVehicle()) return false;
        if (e instanceof ServerPlayer player) return !player.isSpectator() && !player.getAbilities().flying;
        return e instanceof Mob;
    }

    /** 挑飞；已经在空中时重新计时。返回是否进入（或保持）浮空。 */
    public static boolean launch(LivingEntity e, ServerPlayer by) {
        if (!canLaunch(e)) return false;
        long now = e.level().getGameTime();
        int duration = durationTicks(e.getBbWidth(), e.getBbHeight());
        var state = STATES.get(e);
        if (state != null) {
            state.end = Math.max(state.end, cap(state, now + duration));
            return true;
        }
        boolean changedAi = false;
        if (e instanceof Mob mob) {
            changedAi = !mob.isNoAi();
            mob.setNoAi(true);
            mob.getNavigation().stop();
            mob.addTag(TAG);
            if (changedAi) mob.addTag(NO_AI_TAG);
        }
        double height = liftHeight(e.getBbWidth(), e.getBbHeight());
        state = new State(e, by.getUUID(), e.getY(), height, now, now + duration, changedAi);
        state.end = cap(state, state.end);
        STATES.put(e, state);
        e.resetFallDistance();
        e.setDeltaMovement(Vec3.ZERO);
        if (e instanceof ServerPlayer player) {
            player.setDeltaMovement(0, launchSpeed(height), 0);
            player.hurtMarked = true;
        }
        return true;
    }

    /** 玩家浮空不超过 {@link #PLAYER_MAX_TICKS}。 */
    private static long cap(State state, long end) {
        return state.entity instanceof ServerPlayer ? Math.min(end, state.start + PLAYER_MAX_TICKS) : end;
    }

    /** 原版重力 0.08、空气阻力 0.98 下，升到 height 格所需的初速度。 */
    static double launchSpeed(double height) {
        double lo = 0, hi = 2;
        for (int i = 0; i < 40; i++) {
            double v = (lo + hi) / 2, y = 0, u = v;
            for (int t = 0; t < 40 && u > 0; t++) { y += u; u = (u - 0.08) * 0.98; }
            if (y < height) lo = v; else hi = v;
        }
        return (lo + hi) / 2;
    }

    public static boolean is(Entity e) { return e instanceof LivingEntity living && STATES.containsKey(living); }

    /** 剩余浮空刻数；不在空中为 0。 */
    public static long remaining(Entity e) {
        var state = e instanceof LivingEntity living ? STATES.get(living) : null;
        return state == null ? 0 : Math.max(0, state.end - e.level().getGameTime());
    }

    /** 连刺命中：浮空延长一点，并被顶高一点点。 */
    public static void extend(LivingEntity e) {
        var state = STATES.get(e);
        if (state == null) return;
        long now = e.level().getGameTime();
        state.end = Math.max(state.end, cap(state, Math.min(state.end + EXTEND_TICKS, now + EXTEND_CAP)));
        state.bump = Math.min(state.bump + 0.06, 0.3);
    }

    /** 这名玩家挑起、离 center 不超过 range 格的浮空目标。 */
    public static List<LivingEntity> launchedBy(ServerPlayer player, double range) {
        List<LivingEntity> out = new ArrayList<>();
        for (var state : STATES.values())
            if (state.by.equals(player.getUUID()) && state.entity.level() == player.level() && state.entity.isAlive()
                    && state.entity.distanceTo(player) <= range) out.add(state.entity);
        return out;
    }

    /** 下砸：结束浮空，把目标砸回地面并按原版摔落结算。不在空中时什么也不做，返回 false。 */
    public static boolean slam(LivingEntity e) {
        var state = STATES.remove(e);
        if (state == null) return false;
        restore(state);
        // 下砸这一枪刚打中它，受击冷却会吞掉紧接着的摔落伤害；这是同一招的两段，先清掉冷却。
        e.invulnerableTime = 0;
        e.fallDistance = SLAM_FALL;
        if (e instanceof ServerPlayer player) {
            // 玩家的移动由客户端决定：给一个很快的下落速度，落地时服务端照常结算摔落。
            player.setDeltaMovement(0, -1.8, 0);
            player.hurtMarked = true;
        } else {
            // 直接移到地面：move 碰到地面时原版结算摔落（落地距离 + SLAM_FALL）。
            double before = e.getY();
            e.move(MoverType.SELF, new Vec3(0, -(e.getY() - state.baseY) - 2.5, 0));
            e.setDeltaMovement(0, -0.6, 0);
            // 原版只在实体自己算移动时（有 AI 的生物）才在 move 里结算摔落；本来就关着 AI 的（训练木桩、雕像）
            // 照原版 checkFallDamage 的做法补一次：交给脚下方块的 fallOn（默认即 causeFallDamage，摔落伤害类型）。
            if (e.onGround() && e.fallDistance > 0) {
                e.fallDistance += before - e.getY();
                var pos = e.getOnPosLegacy();
                var block = e.level().getBlockState(pos);
                block.getBlock().fallOn(e.level(), block, pos, e, e.fallDistance);
                e.resetFallDistance();
            }
        }
        return true;
    }

    /** 原本就关着 AI 的生物（例如训练木桩）自己不会动，浮空结束时放回原来的高度；其余的交给原版重力。 */
    private static void land(State state) {
        if (state.entity instanceof Mob mob && mob.isNoAi() && mob.getY() > state.baseY)
            mob.move(MoverType.SELF, new Vec3(0, state.baseY - mob.getY(), 0));
    }

    private static void restore(State state) {
        var e = state.entity;
        if (e instanceof Mob mob) {
            if (state.changedAi) mob.setNoAi(false);
            mob.removeTag(TAG);
            mob.removeTag(NO_AI_TAG);
        }
        var gravity = e.getAttribute(Attributes.GRAVITY);
        if (gravity != null) gravity.removeModifier(FLOAT);
        e.resetFallDistance();
    }

    @SubscribeEvent static void tick(ServerTickEvent.Post event) {
        if (STATES.isEmpty()) return;
        for (var state : new ArrayList<>(STATES.values())) {
            var e = state.entity;
            if (e.isRemoved() || !e.isAlive()) { STATES.remove(e); restore(state); continue; }
            long now = e.level().getGameTime(), age = now - state.start;
            if (now >= state.end) { STATES.remove(e); restore(state); land(state); continue; }
            if (e instanceof ServerPlayer player) {
                // 到顶：停住，重力归零悬停到结束。
                if (age == RISE_TICKS + 1) {
                    var gravity = player.getAttribute(Attributes.GRAVITY);
                    if (gravity != null && !gravity.hasModifier(FLOAT))
                        gravity.addTransientModifier(new AttributeModifier(FLOAT, -1, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
                    var v = player.getDeltaMovement();
                    player.setDeltaMovement(v.x, 0, v.z);
                    player.hurtMarked = true;
                }
                continue;
            }
            double h;
            if (age < RISE_TICKS) {
                double u = (age + 1) / (double) RISE_TICKS;
                h = state.height * (1 - (1 - u) * (1 - u));
            } else {
                h = state.height + 0.05 * Math.sin((age - RISE_TICKS) * 0.3);
            }
            state.bump *= 0.85;
            double dy = state.baseY + h + state.bump - e.getY();
            e.setDeltaMovement(Vec3.ZERO);
            if (Math.abs(dy) > 1e-4) e.move(MoverType.SELF, new Vec3(0, dy, 0));
            e.resetFallDistance();
        }
    }

    @SubscribeEvent static void leave(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof LivingEntity e) {
            var state = STATES.remove(e);
            if (state != null) restore(state);
        }
    }
    @SubscribeEvent static void death(LivingDeathEvent event) {
        var state = STATES.remove(event.getEntity());
        if (state != null) restore(state);
    }
    /** 浮空中途被卸载或服务器崩溃：再加载时按标签恢复原来的 AI。 */
    @SubscribeEvent static void join(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Mob mob) || STATES.containsKey(mob)) return;
        if (!mob.entityTags().contains(TAG)) return;
        if (mob.entityTags().contains(NO_AI_TAG)) mob.setNoAi(false);
        mob.removeTag(TAG);
        mob.removeTag(NO_AI_TAG);
    }
    @SubscribeEvent static void stopped(ServerStoppedEvent event) { STATES.clear(); }
}
