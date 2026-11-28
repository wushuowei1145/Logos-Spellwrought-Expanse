package dev.logos.dimension.item;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.combat.Airborne;
import dev.logos.dimension.combat.Moveset;
import dev.logos.dimension.combat.Wielder;
import dev.logos.dimension.dragonfire.Dragonfire;
import dev.logos.dimension.foxfire.FoxfireCue;
import dev.logos.dimension.foxfire.FoxfireFlame;
import dev.logos.lang.Element;
import dev.logos.runtime.Vfx;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jspecify.annotations.Nullable;

/**
 * 炽焰长剑的战技与龙炎结算（见 docs/CRIMSON_FLAME_LONGSWORD.md）。这把剑的伤害全是龙炎（docs/DRAGONFIRE.md）：
 * <ul>
 *   <li>每一剑（{@link #strike}）不走原版近战，换成同样点数的龙炎：护甲、抗火、火焰保护都挡不住，打进去的烧掉魔力。
 *       连招前三式不点缠身，带火的招式（四式、龙牙、突刺、旋风、十文字、横扫、坠星）点龙炎缠身；</li>
 *   <li>身法前提（{@link #allows}）：空中 {@code airborne -> 炽翼坠星破}、疾跑 {@code sprint -> 炽焰旋风斩}、潜行 {@code sneak -> 破晓炎枪}；</li>
 *   <li>突进与空中位移（{@link #combatTick}）：突刺疾冲、旋风前掠、红莲前踏与坠星高跃下砸。冲刺时的火星由客户端按同步的招式自己画
 *       （{@code client/CrimsonFlameClient}），这里不发粒子；</li>
 *   <li>战技（{@link #onSwing}）：横扫 {@code flame_sweep} 向前方 72° 扇区扫出五道贴地的龙炎游脉，
 *       空降 {@code skyfall_judgement} 在落点炸开一圈龙炎并挑飞。</li>
 * </ul>
 * 龙炎不用原版粒子，也不点原版的火：画面全走龙炎自己的提示（{@link FoxfireCue}，颜色 {@link FoxfireFlame#DRAGON}）。
 * 玩家和拉塞塔一样（{@link Wielder}，docs/WEAPON_AI.md）：她跑的就是玩家这份招式文件。
 */
@EventBusSubscriber(modid = LogosDimension.ID)
public final class CrimsonFlameSkills {
    /** 红莲劫焰斩扫出的龙炎游脉：贴地前推 11 格、总张角 72°（五道每隔 18°）。 */
    static final double WAVE_RANGE = 11.0, WAVE_SPEED = 1.15, WAVE_WIDTH = 1.9, WAVE_HEIGHT = 1.8, WAVE_DAMAGE = 10.0;
    private static final double STEP = 1.0, DROP = 4.0, LIFT = 0.05, SKIN = 0.05, STRIDE = 0.5;
    static final double SKYFALL_RADIUS = 3.8, SKYFALL_DAMAGE = 12.0;
    private static final double[] FAN_ANGLES_DEG = {-36.0, -18.0, 0.0, 18.0, 36.0};
    /** 带火的招式：打中点龙炎缠身。连招前三式只打龙炎、不缠身，免得每一剑都把缠身叠满。 */
    private static final Set<String> BURNING = Set.of("cataclysm_4", "dragon_fang", "crimson_thrust", "vortex_rush",
            "cross_rend", "flame_sweep", "skyfall_judgement");

    /** 与 Logos（正数）及 SwordWave（-1_000_000）错开的负数持续特效编号。 */
    private static int nextVeinId = -2_000_000;

    private record DashState(String move, Vec3 dir, long start) {}
    private static final Map<UUID, DashState> DASHES = new HashMap<>();
    private static final List<FlameWave> WAVES = new ArrayList<>();

    private CrimsonFlameSkills() {}

    /** 身法派生条件判断，供招式文件中的 {@code require} 字段调用。 */
    public static boolean allows(Wielder w, String requirement) {
        return switch (requirement) {
            case "airborne" -> w.airborne();
            case "sprint" -> w.intends("sprint");
            case "sneak" -> w.intends("sneak") && w.entity().onGround();
            default -> false;
        };
    }

    /**
     * 这一剑对 {@code target} 打出的龙炎：点数同近战（{@link Wielder#attackDamage}：玩家是攻击力乘攻击蓄力），招式的伤害倍数在
     * 受伤事件里照常乘上（{@code combat/CombatHits}）。带火的招式缠身，逆鳞的拉塞塔每一剑都缠身。玩家的剑照原版每打中一下掉一点耐久。
     * 返回是否打进去了。
     */
    public static boolean strike(Wielder w, LivingEntity target, Moveset.Move move) {
        LivingEntity user = w.entity();
        float before = target.getHealth() + target.getAbsorptionAmount();
        boolean died = Dragonfire.deal(user, target, w.attackDamage(), BURNING.contains(move.name()) || w.ignites());
        // Logos 的 Effects.damage 只在打死时返回真，打没打进去看血量（含伤害吸收）有没有掉
        boolean hurt = died || target.getHealth() + target.getAbsorptionAmount() < before;
        if (hurt && w.player() instanceof ServerPlayer player && !player.hasInfiniteMaterials())
            player.getMainHandItem().hurtAndBreak(1, player, EquipmentSlot.MAINHAND);
        return hurt;
    }

    /** 招式每刻位移推进；返回这一刻是不是在这几招的冲刺、起落里（拉塞塔这时不另往目标带步）。 */
    public static boolean combatTick(Wielder w, String move) {
        LivingEntity user = w.entity();
        UUID id = user.getUUID();
        boolean propelled = "crimson_thrust".equals(move) || "vortex_rush".equals(move)
                || "flame_sweep".equals(move) || "skyfall_judgement".equals(move);
        if (!propelled) {
            DASHES.remove(id);
            return false;
        }
        if ("flame_sweep".equals(move) && user.isUsingItem()) {
            user.stopUsingItem();
        }
        if ("skyfall_judgement".equals(move)) {
            user.resetFallDistance();
        }

        long now = user.level().getGameTime();
        DashState state = DASHES.get(id);
        if (state == null || !state.move.equals(move)) {
            state = new DashState(move, w.flatAim(), now);
            DASHES.put(id, state);
        }
        long t = now - state.start;

        if ("crimson_thrust".equals(move)) {
            if (t < 2 || t > 5) return false;
            if (!user.horizontalCollision && !hasEnemyAhead(w, state.dir, 1.7)) pushHorizontal(user, state.dir, 1.15);
        } else if ("vortex_rush".equals(move)) {
            if (t < 2 || t > 10) return false;
            if (!user.horizontalCollision && !hasEnemyAhead(w, state.dir, 1.6)) pushHorizontal(user, state.dir, 0.78);
        } else if ("flame_sweep".equals(move)) {
            if (t < 4 || t > 7) return false;
            if (!user.horizontalCollision && !hasEnemyAhead(w, state.dir, 1.8)) pushHorizontal(user, state.dir, 0.55);
        } else {
            Vec3 vel = user.getDeltaMovement();
            if (t >= 1 && t <= 4) {
                user.setDeltaMovement(vel.x * 0.8 + state.dir.x * 0.24, Math.max(vel.y, 0.42), vel.z * 0.8 + state.dir.z * 0.24);
                user.hurtMarked = true;
            } else if (t >= 7 && t <= 10) {
                user.setDeltaMovement(vel.x * 0.4 + state.dir.x * 0.45, -1.15, vel.z * 0.4 + state.dir.z * 0.45);
                user.hurtMarked = true;
            } else return false;
        }
        return true;
    }

    /** 招式接触帧：战技音效、龙炎游脉与落点的龙炎。 */
    public static void onSwing(Wielder w, Moveset.Move move) {
        if (w.entity().level() instanceof ServerLevel level) skill(level, w.entity(), w.flatAim(), move.name(), w::mayHit);
    }

    /**
     * 这一招接触时的战技：横扫扫出五道龙炎游脉，坠星在落点炸开一圈龙炎并挑飞，突刺、旋风、十文字放一团龙炎。
     * {@code forward} 是水平的朝向；{@code hits} 挑打得到的（{@link Wielder#mayHit}：玩家不打自己的宠物，同伴只打她当作敌人的）。
     */
    public static void skill(ServerLevel level, LivingEntity user, Vec3 forward, String move, Predicate<LivingEntity> hits) {
        switch (move) {
            case "flame_sweep" -> {
                level.playSound(null, user.getX(), user.getY() + 1, user.getZ(), SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 1.1f, 0.85f);
                level.playSound(null, user.getX(), user.getY() + 1, user.getZ(), SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, 1.0f, 0.9f);
                flameArc(level, user, forward);
                // 同一记横扫的五道游脉共享已命中集合，防止贴脸敌人单帧吃满五次。
                Set<UUID> sharedStruck = new HashSet<>();
                Vec3 originProbe = user.position().add(forward.scale(1.1)).add(0, STEP + LIFT, 0);
                Vec3 startGround = groundBelow(level, user, originProbe);
                Vec3 origin = startGround != null ? startGround : user.position();
                for (double deg : FAN_ANGLES_DEG) {
                    Vec3 rayDir = rotateHorizontal(forward, Math.toRadians(deg));
                    WAVES.add(new FlameWave(level, user, origin, rayDir, sharedStruck, hits));
                }
            }
            case "skyfall_judgement" -> {
                Vec3 ahead = user.position().add(forward.scale(2.0)).add(0, STEP + LIFT, 0);
                Vec3 ground = groundBelow(level, user, ahead);
                Vec3 impact = ground != null ? ground : user.position().add(forward.scale(1.6));
                // 落点炸开一大团龙炎，外面一圈六小团
                FoxfireCue.burst(level, impact.add(0, 0.6, 0), FoxfireFlame.DRAGON, 2.5f);
                for (int i = 0; i < 6; i++) {
                    double a = i * Math.PI / 3;
                    FoxfireCue.burst(level, impact.add(Math.cos(a) * 2.2, 0.35, Math.sin(a) * 2.2), FoxfireFlame.DRAGON, 1.1f);
                }
                level.playSound(null, impact.x, impact.y, impact.z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 1.4f, 0.85f);
                level.playSound(null, impact.x, impact.y, impact.z, SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 1.1f, 0.75f);
                AABB area = new AABB(impact, impact).inflate(SKYFALL_RADIUS);
                for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, area, hits::test)) {
                    if (e.getBoundingBox().getCenter().distanceToSqr(impact) > SKYFALL_RADIUS * SKYFALL_RADIUS) continue;
                    Dragonfire.deal(user, e, SKYFALL_DAMAGE);
                    Airborne.launch(e, user);
                }
            }
            case "crimson_thrust" -> {
                // 玩家照原样顺着视线；同伴按水平朝向，剑尖在眼睛下面一点
                Vec3 tip = user instanceof ServerPlayer ? user.getEyePosition().add(user.getLookAngle().scale(2.4))
                        : user.getEyePosition().add(forward.scale(2.4)).add(0, -0.2, 0);
                FoxfireCue.burst(level, tip, FoxfireFlame.DRAGON, 1.0f);
                level.playSound(null, tip.x, tip.y, tip.z, SoundEvents.TRIDENT_THROW.value(), SoundSource.PLAYERS, 0.9f, 1.25f);
                level.playSound(null, tip.x, tip.y, tip.z, SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 0.7f, 1.3f);
            }
            case "vortex_rush", "cross_rend" -> {
                Vec3 center = user.position().add(forward.scale(1.4)).add(0, 1.0, 0);
                FoxfireCue.burst(level, center, FoxfireFlame.DRAGON, 0.9f);
                level.playSound(null, center.x, center.y, center.z, SoundEvents.BLAZE_SHOOT, SoundSource.PLAYERS, 0.8f, 1.05f);
            }
            default -> {}
        }
    }

    /** 带火的招式（打中点龙炎缠身）：四式、龙牙、突刺、旋风、十文字、横扫、坠星。 */
    public static boolean burning(String move) {
        return BURNING.contains(move);
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) {
        WAVES.removeIf(w -> w.owner.isRemoved() || w.step());
    }

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) {
        WAVES.clear();
        DASHES.clear();
    }

    /** 供自动化测试检查当前活跃的龙炎游脉数量。 */
    public static int liveWaves() { return WAVES.size(); }

    private static Vec3 rotateHorizontal(Vec3 dir, double radians) {
        double c = Math.cos(radians), s = Math.sin(radians);
        return new Vec3(dir.x * c - dir.z * s, 0, dir.x * s + dir.z * c).normalize();
    }

    private static void pushHorizontal(LivingEntity user, Vec3 dir, double speed) {
        Vec3 cur = user.getDeltaMovement();
        user.setDeltaMovement(dir.x * speed, Math.min(cur.y, 0.08), dir.z * speed);
        user.hurtMarked = true;
    }

    private static boolean hasEnemyAhead(Wielder w, Vec3 dir, double dist) {
        AABB box = w.entity().getBoundingBox().expandTowards(dir.scale(dist)).inflate(0.5);
        return !w.entity().level().getEntitiesOfClass(LivingEntity.class, box, w::mayHit).isEmpty();
    }

    /** 横扫出手时身前的一道龙炎弧：沿 90° 的弧放五小团。 */
    private static void flameArc(ServerLevel level, LivingEntity user, Vec3 forward) {
        for (int i = -2; i <= 2; i++) {
            Vec3 ray = rotateHorizontal(forward, Math.toRadians(i * 22.5));
            FoxfireCue.burst(level, user.position().add(ray.scale(2.4)).add(0, 0.8, 0), FoxfireFlame.DRAGON, 0.8f);
        }
    }

    static @Nullable Vec3 groundBelow(ServerLevel level, Entity p, Vec3 at) {
        BlockHitResult hit = level.clip(new ClipContext(at, at.add(0, -(STEP + DROP + 1), 0),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, p));
        return hit.getType() == HitResult.Type.MISS || hit.isInside() ? null : hit.getLocation();
    }

    /**
     * 贴地前推的龙炎游脉：能跨一格台阶、顺坡下探四格，沿途打出龙炎并缠身。画法借 Logos 的奥秘游脉，
     * 每刻先告诉客户端它是龙炎（{@link FoxfireCue#vein}），客户端就画成龙炎的火舌和火星。
     */
    private static final class FlameWave {
        final ServerLevel level;
        final LivingEntity owner;
        final Predicate<LivingEntity> hits;
        final int id = nextVeinId--;
        final Vec3 dir;
        final Set<UUID> struck;
        Vec3 pos;
        double travelled;

        FlameWave(ServerLevel level, LivingEntity owner, Vec3 start, Vec3 dir, Set<UUID> struck, Predicate<LivingEntity> hits) {
            this.level = level;
            this.owner = owner;
            this.hits = hits;
            this.pos = start;
            this.dir = dir;
            this.struck = struck;
            show();
        }

        private void show() {
            FoxfireCue.vein(level, id, pos, FoxfireFlame.DRAGON);
            Vfx.vein(level, id, 0, pos, dir, Direction.UP, Element.ARCANE, WAVE_WIDTH, WAVE_HEIGHT, 0);
        }

        private boolean stop(boolean wall) {
            FoxfireCue.vein(level, id, pos, FoxfireFlame.DRAGON);
            Vfx.veinEnd(level, id, 0, pos, Element.ARCANE, wall);
            return true;
        }

        boolean step() {
            for (double left = WAVE_SPEED; left > 1e-9; ) {
                double ds = Math.min(STRIDE, left);
                left -= ds;
                BlockHitResult ceiling = level.clip(new ClipContext(pos.add(0, LIFT, 0), pos.add(0, STEP + LIFT, 0),
                        ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, owner));
                Vec3 up = ceiling.getType() == HitResult.Type.MISS ? pos.add(0, STEP + LIFT, 0) : ceiling.getLocation().add(0, -LIFT, 0);
                Vec3 ahead = up.add(dir.scale(ds));
                BlockHitResult wall = level.clip(new ClipContext(up, ahead.add(dir.scale(SKIN)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, owner));
                boolean blocked = wall.getType() != HitResult.Type.MISS;
                Vec3 reach = blocked ? wall.getLocation().subtract(dir.scale(SKIN)) : ahead;
                BlockHitResult ground = level.clip(new ClipContext(reach, reach.add(0, -(STEP + DROP + LIFT), 0),
                        ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, owner));
                if (ground.isInside()) return stop(true);
                if (ground.getType() == HitResult.Type.MISS) return stop(false);
                Vec3 next = ground.getLocation();
                strike(pos, next);
                pos = next;
                travelled += ds;
                if (blocked) return stop(true);
                if (travelled >= WAVE_RANGE) return stop(false);
            }
            show();
            return false;
        }

        private void strike(Vec3 a, Vec3 b) {
            AABB box = new AABB(a, b).inflate(WAVE_WIDTH / 2, 0, WAVE_WIDTH / 2).expandTowards(0, WAVE_HEIGHT, 0);
            for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, e -> e != owner && e.isAlive() && hits.test(e) && !struck.contains(e.getUUID()))) {
                struck.add(e.getUUID());
                Dragonfire.deal(owner, e, WAVE_DAMAGE);
            }
        }
    }
}
