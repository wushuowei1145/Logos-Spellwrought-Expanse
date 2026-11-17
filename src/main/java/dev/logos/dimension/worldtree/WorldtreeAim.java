package dev.logos.dimension.worldtree;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 世界树之弓的锁定框：拉弓后在视线前方展开一个矩形框，框在镜头的切平面上（屏幕上就是一个矩形，与视野大小无关），
 * 框内 48 格以内、看得见的敌人每 0.2 秒锁定一个，最多 16 个，先锁还没锁过的。服务端判定，客户端按同样的数画框。
 */
public final class WorldtreeAim {
    /** 锁定距离（格）。 */
    public static final double RANGE = 48;
    /** 每隔几刻锁定一个（0.2 秒）。 */
    public static final int INTERVAL = 4;
    public static final int MAX_LOCKS = 16;
    /** 原版弓拉满的刻数：到这时中间出现实箭。 */
    public static final int FULL_TICKS = 20;
    /** 框从无到完全展开的刻数。 */
    public static final int GROW_TICKS = 10;
    /** 框完全展开时左右、上下各偏离视线多少度（切平面上的半宽、半高按 tan 算）。 */
    public static final float HALF_WIDTH = 30, HALF_HEIGHT = 18;

    private WorldtreeAim() {}

    /** 框展开的比例 0–1（缓出）。 */
    public static float frame(float ticks) {
        float t = Mth.clamp(ticks / GROW_TICKS, 0, 1);
        return 1 - (1 - t) * (1 - t) * (1 - t);
    }

    /**
     * 目标点在框坐标里的位置：x、y 是切平面上相对框半宽、半高的比例（-1–1 在框内），z 是沿视线的距离；在身后时为空。
     */
    public static Optional<Vec3> local(Vec3 eye, float yRot, float xRot, Vec3 point) {
        Vec3 forward = Vec3.directionFromRotation(xRot, yRot);
        Vec3 right = forward.cross(new Vec3(0, 1, 0));
        if (right.lengthSqr() < 1e-8) right = Vec3.directionFromRotation(0, yRot + 90).scale(-1);
        right = right.normalize();
        Vec3 up = right.cross(forward).normalize();
        Vec3 d = point.subtract(eye);
        double z = d.dot(forward);
        if (z <= .1) return Optional.empty();
        double x = d.dot(right) / z / Math.tan(Math.toRadians(HALF_WIDTH));
        double y = d.dot(up) / z / Math.tan(Math.toRadians(HALF_HEIGHT));
        return Optional.of(new Vec3(x, y, z));
    }

    /** 在展开到 scale 的框里。 */
    public static boolean inside(Vec3 local, float scale) {
        return Math.abs(local.x) <= scale && Math.abs(local.y) <= scale;
    }

    /** 可以锁定的敌人：怪物，或正盯着这名玩家的生物；不锁玩家、自己的宠物和同队的。 */
    public static boolean enemy(Player player, Entity e) {
        if (!(e instanceof LivingEntity living) || e == player || e instanceof Player || !living.isAlive() || e.isSpectator()) return false;
        if (e.isAlliedTo(player) || e instanceof OwnableEntity owned && owned.getOwner() == player || living.isInvisible()) return false;
        return e instanceof Enemy || e instanceof Mob mob && mob.getTarget() == player;
    }

    /** 这一刻该锁谁：框里、范围内、看得见的敌人里，被锁次数最少的；一样多时离框中心最近的。 */
    public static Optional<LivingEntity> pick(Player player, WorldtreeDraw draw, float scale) {
        Vec3 eye = player.getEyePosition();
        List<LivingEntity> all = player.level().getEntitiesOfClass(LivingEntity.class, new AABB(eye, eye).inflate(RANGE), e -> enemy(player, e));
        record Candidate(LivingEntity entity, int locks, double offset) {}
        return all.stream()
                .filter(e -> e.getBoundingBox().getCenter().distanceTo(eye) <= RANGE && player.hasLineOfSight(e))
                .map(e -> local(eye, player.getYRot(), player.getXRot(), e.getBoundingBox().getCenter())
                        .filter(p -> inside(p, scale))
                        .map(p -> new Candidate(e, draw.count(e.getId()), p.x * p.x + p.y * p.y)).orElse(null))
                .filter(c -> c != null)
                .min(Comparator.comparingInt(Candidate::locks).thenComparingDouble(Candidate::offset))
                .map(Candidate::entity);
    }

    /** 每支追踪箭的生命伤害：锁定越多越高，1 个 3 点，每多一个加 0.25，16 个时每支 6.75。 */
    public static float homingDamage(int locks) {
        return 3 + .25f * (Math.max(1, locks) - 1);
    }
}
