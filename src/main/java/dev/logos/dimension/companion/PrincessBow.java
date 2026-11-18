package dev.logos.dimension.companion;

import dev.logos.dimension.item.WorldtreeBowItem;
import dev.logos.dimension.worldtree.Worldtree;
import dev.logos.dimension.worldtree.WorldtreeAim;
import dev.logos.dimension.worldtree.WorldtreeArrow;
import dev.logos.dimension.worldtree.WorldtreeDraw;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 精灵公主手里的世界树之弓（docs/ELF_PRINCESS_COMPANION.md 第 3 节；弓本身见 docs/WORLDTREE_BOW.md）。和玩家用的是同一把弓、同一种箭，
 * 拉弓状态也挂在她手里那把弓的组件上（{@link WorldtreeDraw}），所以弓上一样会凝出虚影箭、拉满出现实箭。不同的是：
 * <ul>
 *   <li>在她手里蓄力更快：{@link #FULL} 刻拉满（玩家 20 刻），每 {@link #LOCK_EVERY} 刻锁定一个（玩家 4 刻）；不花魔力、不用背包里的箭。</li>
 *   <li>锁定模式（敌人远）：锁她视线前方框里的敌人；不锁主人、主人的宠物和使魔、别的同伴；「攻击玩家」开着时也锁和主人起冲突的玩家。
 *       同一个目标一轮最多锁 {@link #PER_TARGET} 次，框里的都锁过了就放。</li>
 *   <li>散射模式（敌人近身）：横着拉弓，{@link #FULL} 刻后扇形射出、穿透，紧接着往后一跳拉开（{@link #backstep}）。</li>
 * </ul>
 */
final class PrincessBow {
    /** 拉满（中间出现实箭）的刻数；每隔几刻锁定一个；一轮最多拉多久。 */
    static final int FULL = 12, LOCK_EVERY = 2, MAX_DRAW = 30;
    /** 同一个目标一轮最多锁几次。 */
    static final int PER_TARGET = 2;
    /** 两轮之间至少隔多久；散射之后隔多久才能再散射。 */
    static final int VOLLEY_GAP = 18, FAN_GAP = 40;
    /** 锁定的距离（格）。 */
    static final double RANGE = 40;
    /** 后跳：往后多快、往上多少；后跳动作多长（刻）。 */
    static final double BACKSTEP_SPEED = .85, BACKSTEP_UP = .36;
    static final int BACKSTEP_TICKS = 9;
    /** 锁定的叮在她手里更轻。 */
    static final float LOCK_VOLUME = .25f;

    private final ElfPrincessCompanion c;
    private long start = -1, nextVolley, nextFan, backstepEnd = -1;
    private boolean fan;
    private @Nullable LivingEntity aim;
    /** 测试用：放过几轮锁定、几轮散射、射出过几支追踪箭。 */
    static int volleys, fans, homing;

    PrincessBow(ElfPrincessCompanion c) {
        this.c = c;
    }

    boolean drawing() {
        return start >= 0;
    }

    /** 能起一轮锁定（没在拉、过了间隔）。 */
    boolean volleyReady(long now) {
        return !drawing() && now >= nextVolley;
    }

    boolean fanReady(long now) {
        return !drawing() && now >= nextFan;
    }

    boolean backstepping(long now) {
        return now < backstepEnd;
    }

    /** 对着 {@code target} 开始拉弓；{@code sideways} 是散射（横着拉）。弓不在手里时先拿出来。 */
    boolean draw(LivingEntity target, boolean sideways) {
        if (drawing() || c.eating() || c.magic.casting() || !(c.level() instanceof ServerLevel level)) return false;
        long now = level.getGameTime();
        c.fought(now);
        ItemStack bow = c.getMainHandItem();
        if (!(bow.getItem() instanceof WorldtreeBowItem)) return false;
        bow.set(Worldtree.DRAW.get(), WorldtreeDraw.begin(now, sideways));
        c.startUsingItem(InteractionHand.MAIN_HAND);
        start = now;
        fan = sideways;
        aim = target;
        c.getNavigation().stop();
        c.mood.cut();
        if (sideways) c.setMove("bow_fan|" + now + "|1|false");
        return true;
    }

    /** 放下弓（被打断、撤退、离开）：清掉弓上的虚影。 */
    void cancel() {
        if (start < 0) return;
        start = -1;
        aim = null;
        ItemStack bow = c.getMainHandItem();
        bow.remove(Worldtree.DRAW.get());
        if (c.isUsingItem()) c.stopUsingItem();
        if (c.move().startsWith("bow_fan")) c.setMove("");
    }

    void tick(ServerLevel level, long now) {
        if (backstepEnd >= 0 && now >= backstepEnd) {
            backstepEnd = -1;
            if (c.move().startsWith("backstep")) c.setMove("");
        }
        if (start < 0) return;
        ItemStack bow = c.getMainHandItem();
        WorldtreeDraw draw = bow.get(Worldtree.DRAW.get());
        if (draw == null || !(bow.getItem() instanceof WorldtreeBowItem) || aim == null || !aim.isAlive() || !c.canHarm(aim)) {
            cancel();
            return;
        }
        int t = (int) (now - start);
        face(aim);
        if (t >= FULL && !draw.hasFull()) draw = draw.withFull(now);
        boolean more = true;
        if (t > 0 && t % LOCK_EVERY == 0 && draw.locks().size() < WorldtreeAim.MAX_LOCKS) {
            if (fan) {
                draw = draw.lock(-1, now);
            } else {
                Optional<LivingEntity> pick = pick(level, draw);
                if (pick.isPresent()) {
                    draw = draw.lock(pick.get().getId(), now);
                    var sound = Worldtree.LOCK_SOUNDS.get(draw.locks().size() - 1).get();
                    level.playSound(null, c.getX(), c.getEyeY(), c.getZ(), sound, SoundSource.NEUTRAL, LOCK_VOLUME, 1);
                } else {
                    more = false;
                }
            }
        }
        if (!draw.equals(bow.get(Worldtree.DRAW.get()))) bow.set(Worldtree.DRAW.get(), draw);
        boolean done = fan ? t >= FULL : t >= MAX_DRAW || t >= FULL && (!more || draw.locks().size() >= WorldtreeAim.MAX_LOCKS);
        if (done) release(level, now, draw);
    }

    /** 转过去正对着目标，视线也对上（渲染器按视线把两臂连着弓抬起来）。 */
    private void face(LivingEntity target) {
        c.getLookControl().setLookAt(target, 60, 60);
        c.lookAt(target, 360, 60);
        c.yBodyRot = c.getYRot();
        c.setYHeadRot(c.getYRot());
    }

    /** 放箭：锁定的每一个一支追踪箭，再加中间一支实箭；散射是所有箭横着扇形射出、穿透。 */
    private void release(ServerLevel level, long now, WorldtreeDraw draw) {
        ItemStack bow = c.getMainHandItem();
        LivingEntity target = aim;
        start = -1;
        aim = null;
        bow.remove(Worldtree.DRAW.get());
        c.stopUsingItem();
        if (target == null) return;
        Vec3 eye = c.getEyePosition();
        Vec3 forward = target.getBoundingBox().getCenter().subtract(eye);
        forward = forward.lengthSqr() < 1e-6 ? c.getLookAngle() : forward.normalize();
        Vec3 right = forward.cross(new Vec3(0, 1, 0));
        right = right.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : right.normalize();
        Vec3 up = right.cross(forward).normalize();
        Vec3 from = eye.add(forward.scale(.6)).add(0, -.1, 0);
        float damage = WorldtreeAim.homingDamage(draw.locks().size());
        int fired = 0;
        for (int k = 0; k < draw.locks().size(); k++) {
            WorldtreeArrow arrow;
            if (fan) {
                // 横着拉：绕自己的「上」转开，水平扇形，不追踪、穿透
                arrow = WorldtreeArrow.spread(level, c, bow, damage);
                arrow.setDeltaMovement(rotate(forward, up, Math.toRadians(WorldtreeBowItem.fan(k))).scale(WorldtreeBowItem.SPREAD_SPEED));
            } else {
                if (!(level.getEntity(draw.locks().get(k).entity()) instanceof LivingEntity locked) || !locked.isAlive()) continue;
                arrow = WorldtreeArrow.homing(level, c, bow, locked, damage);
                // 从弓上那支虚影箭的位置散开飞出，再拐向目标
                arrow.setDeltaMovement(rotate(forward, right, Math.toRadians(WorldtreeBowItem.fan(k))).scale(WorldtreeBowItem.LAUNCH_SPEED));
                homing++;
            }
            arrow.setPos(from.x, from.y, from.z);
            level.addFreshEntity(arrow);
            fired++;
        }
        if (draw.hasFull()) {
            WorldtreeArrow arrow = WorldtreeArrow.straight(level, c, bow, new ItemStack(Items.ARROW), fan);
            arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
            arrow.setPos(from.x, from.y, from.z);
            arrow.shoot(forward.x, forward.y, forward.z, WorldtreeArrow.STRAIGHT_SPEED, 0);
            level.addFreshEntity(arrow);
            fired++;
        }
        if (fired > 0) level.playSound(null, c.getX(), c.getY(), c.getZ(), SoundEvents.ARROW_SHOOT, SoundSource.NEUTRAL, 1, 1.15f);
        if (fan) {
            fans++;
            nextFan = now + FAN_GAP;
            nextVolley = now + 6;
            if (c.move().startsWith("bow_fan")) c.setMove("");
            backstep(level, now, target);
        } else {
            volleys++;
            nextVolley = now + VOLLEY_GAP;
        }
    }

    /** 往后一跳，离开 {@code from}：往主人那边偏一点，不往悬崖、岩浆里跳。 */
    void backstep(ServerLevel level, long now, LivingEntity from) {
        Vec3 away = c.position().subtract(from.position()).multiply(1, 0, 1);
        if (away.lengthSqr() < 1e-4) away = Vec3.directionFromRotation(0, c.getYRot() + 180);
        away = away.normalize();
        ServerPlayer p = c.ownerPlayer();
        if (p != null) {
            Vec3 toOwner = p.position().subtract(c.position()).multiply(1, 0, 1);
            if (toOwner.lengthSqr() > 1) away = away.add(toOwner.normalize().scale(.35)).normalize();
        }
        // 落点脚下要有地面，不然换个方向（往旁边跳）
        Vec3 land = c.position().add(away.scale(2.6));
        if (!safe(level, land)) {
            Vec3 side = new Vec3(-away.z, 0, away.x);
            away = safe(level, c.position().add(side.scale(2.6))) ? side : safe(level, c.position().subtract(side.scale(2.6))) ? side.scale(-1) : null;
        }
        if (away == null) return;
        c.setDeltaMovement(away.scale(BACKSTEP_SPEED).add(0, BACKSTEP_UP, 0));
        c.hurtMarked = true;
        c.setMove("backstep|" + now + "|1|false");
        backstepEnd = now + BACKSTEP_TICKS;
    }

    /** 这一点往下三格内有站得住的地面，而且不是岩浆。 */
    private boolean safe(ServerLevel level, Vec3 at) {
        net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.containing(at);
        for (int dy = 1; dy >= -3; dy--) {
            net.minecraft.core.BlockPos feet = pos.above(dy);
            if (!level.getFluidState(feet).isEmpty() && level.getFluidState(feet).is(net.minecraft.tags.FluidTags.LAVA)) return false;
            if (level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), net.minecraft.core.Direction.UP)
                    && level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()) return true;
        }
        return false;
    }

    /**
     * 这一刻锁谁：她视线前方框里（同玩家的锁定框）、{@link #RANGE} 格内、看得见的敌人里，被锁次数最少的；一样多时离框中心最近的。
     * 一个目标一轮最多 {@link #PER_TARGET} 次。
     */
    private Optional<LivingEntity> pick(ServerLevel level, WorldtreeDraw draw) {
        Vec3 eye = c.getEyePosition();
        List<LivingEntity> all = level.getEntitiesOfClass(LivingEntity.class, new AABB(eye, eye).inflate(RANGE), this::enemy);
        record Candidate(LivingEntity entity, int locks, double offset) {}
        return all.stream()
                .filter(e -> e.getBoundingBox().getCenter().distanceTo(eye) <= RANGE && draw.count(e.getId()) < PER_TARGET && c.hasLineOfSight(e))
                .map(e -> WorldtreeAim.local(eye, c.getYRot(), c.getXRot(), e.getBoundingBox().getCenter())
                        .filter(p -> WorldtreeAim.inside(p, 1))
                        .map(p -> new Candidate(e, draw.count(e.getId()), p.x * p.x + p.y * p.y)).orElse(null))
                .filter(x -> x != null)
                .min(Comparator.comparingInt(Candidate::locks).thenComparingDouble(Candidate::offset))
                .map(Candidate::entity);
    }

    /**
     * 她锁的敌人：她伤得到的（不是主人、主人的宠物和使魔、别的同伴；玩家只在「攻击玩家」开着时）里，怪物、正冲着她或主人来的、
     * 她正在对付的；玩家只锁和主人起冲突的那几个（打主人的、主人在打的、打她的）。
     */
    boolean enemy(Entity e) {
        if (!(e instanceof LivingEntity l) || e == c || !l.isAlive() || e.isSpectator() || l.isInvisible() || !c.canHarm(e)) return false;
        LivingEntity foe = c.mind() == null ? null : c.mind().foe();
        if (e == foe) return true;
        ServerPlayer owner = c.ownerPlayer();
        if (e instanceof Player p) return owner != null && quarrel(owner, p);
        if (e instanceof Mob m && (m.getTarget() == c || owner != null && m.getTarget() == owner)) return true;
        return e instanceof Enemy;
    }

    /** {@code p} 和主人起了冲突：最近打过主人、被主人打过，或者打过她。 */
    private boolean quarrel(ServerPlayer owner, Player p) {
        OwnerWatch w = OwnerWatch.of(owner);
        long now = c.level().getGameTime();
        UUID id = p.getUUID();
        boolean ownerSide = id.equals(w.attacker) && now - w.attackerAt < CompanionMind.MEMORY * 3
                || id.equals(w.attacked) && now - w.attackedAt < CompanionMind.MEMORY * 3;
        return ownerSide || c.getLastHurtByMob() == p && c.tickCount - c.getLastHurtByMobTimestamp() < CompanionMind.MEMORY * 3;
    }

    /** 绕 axis 转 angle（右手法则）。 */
    static Vec3 rotate(Vec3 v, Vec3 axis, double angle) {
        double cos = Math.cos(angle), sin = Math.sin(angle);
        return v.scale(cos).add(axis.cross(v).scale(sin)).add(axis.scale(axis.dot(v) * (1 - cos)));
    }

    /** 给命令 info 看的。 */
    String describe(long now) {
        if (drawing()) return (fan ? "fan " : "lock ") + (now - start);
        return backstepping(now) ? "backstep" : "-";
    }
}
