package dev.logos.dimension.ryuka;

import dev.logos.dimension.longsword.LongswordCombat;
import dev.logos.runtime.PlayerMagic;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 平一文字の構え蓄够了松开左键：闪身往前突进（docs/RYUKA.md 第 3.3 节）。
 * 招式文件里突进是 {@link #MOVE}，这里在它开始的那一刻付魔力、放下格挡，之后每刻把人往视线的水平方向推出去；
 * 前面撞上一个生物就停在它面前，走满 {@link #TICKS} 刻（约 {@link #DISTANCE} 格）或撞墙也停，两种都接上挑（{@link #RISE}）。
 * 魔力不够时不突进，回到待机。
 */
public final class RyukaDash {
    public static final String MOVE = "dash", RISE = "rise";
    /** 招式文件里上挑的 effect 名：砍中的挑飞，狐火缠身的处决（{@link RyukaExecution}）。 */
    public static final String RISE_EFFECT = "ryuka_rise";
    /** 突进另付的魔力（上挑那一刀照常再付一刀的）。 */
    public static final double COST = 20;
    /** 每刻推出去多快（格/刻）、推几刻。 */
    public static final double SPEED = 1.6;
    public static final int TICKS = 5;
    public static final double DISTANCE = SPEED * TICKS;

    private record Dash(Vec3 dir, long start) {}
    private static final Map<UUID, Dash> DASHES = new HashMap<>();

    private RyukaDash() {}

    /** 拿着流火的每一刻（{@code move} 是当前招式）：进突进那一刻开始，之后推人、看前面有没有目标。 */
    public static void tick(ServerPlayer player, String move) {
        UUID id = player.getUUID();
        Dash dash = DASHES.get(id);
        if (!MOVE.equals(move)) {
            if (dash != null) DASHES.remove(id);
            return;
        }
        long now = player.level().getGameTime();
        if (dash == null) {
            if (!pay(player)) {
                player.level().playSound(null, player.getX(), player.getY() + 1, player.getZ(), SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, .4f, 1.6f);
                LongswordCombat.force(player, "idle");
                return;
            }
            if (player.isUsingItem()) player.stopUsingItem();
            Vec3 look = player.getLookAngle();
            Vec3 dir = new Vec3(look.x, 0, look.z);
            if (dir.lengthSqr() < 1e-6) dir = Vec3.directionFromRotation(0, player.getYRot());
            dash = new Dash(dir.normalize(), now);
            DASHES.put(id, dash);
            player.level().playSound(null, player.getX(), player.getY() + 1, player.getZ(), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, .9f, .6f);
            player.level().playSound(null, player.getX(), player.getY() + 1, player.getZ(), SoundEvents.BREEZE_JUMP, SoundSource.PLAYERS, .7f, 1.3f);
        }
        long t = now - dash.start;
        LivingEntity ahead = ahead(player, dash.dir);
        if (t >= TICKS || ahead != null || t > 0 && player.horizontalCollision) {
            stop(player);
            DASHES.remove(id);
            LongswordCombat.force(player, RISE);
            return;
        }
        player.setDeltaMovement(dash.dir.x * SPEED, Math.min(player.getDeltaMovement().y, 0), dash.dir.z * SPEED);
        player.hurtMarked = true;
        if (player.level() instanceof ServerLevel level)
            level.sendParticles(ParticleTypes.CHERRY_LEAVES, player.getX(), player.getY() + .9, player.getZ(), 4, .3, .4, .3, 0);
    }

    /** 正在突进（测试用）。 */
    public static boolean dashing(ServerPlayer player) {
        return DASHES.containsKey(player.getUUID());
    }

    private static boolean pay(ServerPlayer player) {
        if (player.isCreative() || player.isSpectator()) return true;
        var magic = PlayerMagic.of(player);
        if (magic.mana < COST) return false;
        magic.mana -= COST;
        return true;
    }

    private static void stop(ServerPlayer player) {
        player.setDeltaMovement(0, Math.min(player.getDeltaMovement().y, 0), 0);
        player.hurtMarked = true;
    }

    /** 这一刻再往前推一步会撞上的生物（最近的那个）：盒子是人的判定箱往突进方向拉长一步多一点。 */
    static @Nullable LivingEntity ahead(ServerPlayer player, Vec3 dir) {
        // 两侧放宽 0.4 格：擦着过去的也算撞上
        AABB box = player.getBoundingBox().expandTowards(dir.scale(SPEED * 1.3)).inflate(.4, 0, .4);
        return player.level().getEntitiesOfClass(LivingEntity.class, box, e -> e != player && e.isAlive() && !e.isSpectator() && e.isPickable()
                        && !(e instanceof TamableAnimal pet && pet.isOwnedBy(player)) && !e.isPassengerOfSameVehicle(player)
                        && e.position().subtract(player.position()).dot(dir) > 0)
                .stream().min(Comparator.comparingDouble(e -> e.distanceToSqr(player))).orElse(null);
    }
}
