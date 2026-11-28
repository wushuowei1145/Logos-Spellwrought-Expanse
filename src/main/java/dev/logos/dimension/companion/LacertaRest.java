package dev.logos.dimension.companion;

import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 拉塞塔陪你睡、陪你坐（docs/LACERTA_COMPANION.md 第 8.1 节，接入方案第七节第 1 条）。心智里的 LOUNGE（分比通用的「休息」高）。
 * <ul>
 *   <li>你躺下：按羁绊挑睡姿，在你的床边（床头那一格的旁边）：
 *     <ul>
 *       <li>生疏：骑士守夜 {@code sleep_watch}——单膝跪着、背对着床朝外警戒，回头看你；</li>
 *       <li>熟悉：正坐打盹 {@code sleep}——面朝床；</li>
 *       <li>亲近：身边依偎小憩 {@code sleep_rest}——淑女侧坐，左边朝着床、往你这边倚；</li>
 *       <li>信赖、羁绊：共枕入眠 {@code sleep_together_down} → {@code sleep_together} → 醒来 {@code sleep_together_up}——右侧卧，
 *           脸朝着你，头在枕头那一头；旁边就是一张空床的话躺在那张床上。</li>
 *     </ul>
 *     你睡下 5 秒夜晚就跳过去了：她还没走到就直接到那一处躺好（不让你醒来时看见她走着走着一下子躺下）。</li>
 *   <li>你站着不动（发呆、挂机）：走到你右手边，淑女侧坐（主状态 {@code sit}，表情照常）；你一动她就站起来。</li>
 * </ul>
 */
final class LacertaRest {
    /** 你的睡眠计时到这么多（满 100 夜晚就跳过去）时她还没躺好：直接到那一处躺下。 */
    static final int CATCH_UP_SLEEP = 90;
    /** 走到离那一处多近算到了。 */
    static final double ARRIVE = .6;
    /** 坐在你右手边多远。 */
    static final double SIT_SIDE = 1.2;

    /** 睡姿四档（按羁绊）。 */
    enum Tier {
        WATCH("sleep_watch"), DOZE("sleep"), REST("sleep_rest"), TOGETHER("sleep_together");

        final String clip;

        Tier(String clip) {
            this.clip = clip;
        }

        static Tier of(CompanionBond.Stage s) {
            return switch (s) {
                case STRANGER -> WATCH;
                case FAMILIAR -> DOZE;
                case CLOSE -> REST;
                default -> TOGETHER;
            };
        }
    }

    /** 床边的一处：位置和她朝哪（yaw）。 */
    record Spot(Vec3 at, float yaw) {}

    /** 此刻在做什么：没有、坐着、在床边（走过去或已经躺好）。 */
    enum Pose { NONE, SIT, NIGHT }

    /** 测试用：强行定这一晚的睡姿（null 照羁绊）。 */
    static @Nullable Tier forceTier;

    private final LacertaCompanion c;
    private Pose pose = Pose.NONE;
    private boolean ownerAsleep, posed;
    private @Nullable Tier tier;
    private @Nullable Vec3 spot;
    private float yaw;
    private long posedAt;
    /** 上一晚的睡姿（醒来时按它起身）。 */
    private @Nullable Tier lastTier;

    LacertaRest(LacertaCompanion c) {
        this.c = c;
    }

    Pose pose() {
        return pose;
    }

    boolean seated() {
        return pose == Pose.SIT && posed;
    }

    @Nullable Tier lastTier() {
        return lastTier;
    }

    String describePose() {
        if (pose == Pose.SIT) return posed ? "sit" : "going_sit";
        if (pose == Pose.NIGHT && tier != null) return posed ? tier.name().toLowerCase(Locale.ROOT) : "going_" + tier.name().toLowerCase(Locale.ROOT);
        return "none";
    }

    // ---------------------------------------------------------------- 打分（LacertaMind）

    /**
     * LOUNGE 的分：你睡着了（身边太平）1.05，压过通用的「休息」；你站着发呆、挂机（太平、没让她留在原地、你踩在地上）0.45；不然 0。
     */
    double score(ServerPlayer p, OwnerWatch.Activity doing, boolean calm) {
        if (!calm) return 0;
        if (p.isSleeping()) return 1.05;
        boolean still = doing == OwnerWatch.Activity.IDLE || doing == OwnerWatch.Activity.AFK;
        if (still && !c.staying() && p.onGround() && !p.isInWater() && !c.isInWater() && !c.level().isRainingAt(c.blockPosition())) return .45;
        return 0;
    }

    // ---------------------------------------------------------------- 做

    /** 心智选了 LOUNGE：你睡着就去床边，不然去你身边坐下。 */
    void act(ServerLevel level, ServerPlayer p, long now) {
        if (p.isSleeping()) {
            if (pose != Pose.NIGHT) begin(level, p);
            if (!posed && spot != null) approach(level, now, p.getSleepTimer() >= CATCH_UP_SLEEP);
            return;
        }
        if (pose == Pose.NIGHT) return;
        if (pose != Pose.SIT) {
            pose = Pose.SIT;
            posed = false;
            spot = sitSpot(level, p);
            yaw = p.yBodyRot;
        }
        if (spot == null) return;
        if (!posed) {
            approach(level, now, false);
            if (posed) c.setSitting(true);
        } else {
            c.getNavigation().stop();
            c.setYRot(Mth.approachDegrees(c.getYRot(), yaw, 10));
            c.yBodyRot = c.getYRot();
        }
    }

    /** 每刻：你一醒她就起来（不等下一次打分）。 */
    void tick(ServerLevel level, ServerPlayer p, long now) {
        if (pose == Pose.NIGHT && !p.isSleeping()) rise(level, false);
        else if (pose == Pose.NIGHT && !posed && spot != null && p.getSleepTimer() >= CATCH_UP_SLEEP) approach(level, now, true);
        ownerAsleep = p.isSleeping();
    }

    /** 你躺下：定这一晚的睡姿和床边那一处。 */
    private void begin(ServerLevel level, ServerPlayer p) {
        pose = Pose.NIGHT;
        posed = false;
        c.setSitting(false);
        tier = forceTier != null ? forceTier : Tier.of(CompanionBond.Stage.of(c.bond(p).affection()));
        BlockPos bed = p.getSleepingPos().orElse(null);
        Spot plan = bed == null ? null : bedSpot(level, bed, tier);
        if (plan == null) {
            // 找不到床边能待的地方：就在你身边，脸朝着你
            Vec3 at = Companions.spotNear(level, p, 1.6, c);
            spot = at;
            yaw = (float) (Mth.atan2(p.getZ() - at.z, p.getX() - at.x) * Mth.RAD_TO_DEG) - 90;
            return;
        }
        spot = plan.at();
        yaw = plan.yaw();
    }

    /** 走过去；到了（或 {@code snap}：夜晚要跳过去了）就摆好姿势。 */
    private void approach(ServerLevel level, long now, boolean snap) {
        Vec3 at = spot;
        if (at == null) return;
        double d = c.position().distanceTo(at);
        if (d > ARRIVE && !snap) {
            if (now % 10 == 0 || c.getNavigation().isDone()) c.getNavigation().moveTo(at.x, at.y, at.z, 1);
            return;
        }
        c.getNavigation().stop();
        c.teleportTo(at.x, at.y, at.z);
        c.setYRot(yaw);
        c.yBodyRot = yaw;
        c.setYHeadRot(yaw);
        c.setDeltaMovement(Vec3.ZERO);
        posed = true;
        posedAt = now;
        if (pose == Pose.NIGHT) {
            lastTier = tier;
            c.mood.curl(true);
        }
    }

    /** 起来（你醒了、来了敌人、心智换了别的事）：{@code quiet} 时直接站起来，不播醒来的样子。 */
    void rise(ServerLevel level, boolean quiet) {
        boolean wasNight = pose == Pose.NIGHT && posed;
        pose = Pose.NONE;
        posed = false;
        spot = null;
        c.setSitting(false);
        if (wasNight) {
            if (quiet) c.lacertaMood().skipWake();
            c.mood.curl(false);
        }
    }

    /** 睡着时 misc 播哪一段：共枕时先躺下（{@code sleep_together_down}，2.6 秒，停在最后一帧）再睡。 */
    String sleepAnimation(long now) {
        Tier t = tier != null ? tier : Tier.DOZE;
        if (t == Tier.TOGETHER && now - posedAt < c.mood.length("sleep_together_down")) return "sleep_together_down";
        return t.clip;
    }

    // ---------------------------------------------------------------- 在哪

    /**
     * 床边那一处和朝向：床头那一格的侧边，先挑床朝向顺时针的那一侧（共枕时这一侧头才在枕头那边），
     * 站不下就换另一侧；共枕时那一侧是一张空床就躺在那张床上。都不行返回 null。
     */
    static @Nullable Spot bedSpot(ServerLevel level, BlockPos head, Tier tier) {
        BlockState state = level.getBlockState(head);
        if (!(state.getBlock() instanceof BedBlock)) return null;
        Direction facing = state.getValue(BedBlock.FACING);
        if (state.getValue(BedBlock.PART) != BedPart.HEAD) head = head.relative(facing);
        for (Direction side : new Direction[]{facing.getClockWise(), facing.getCounterClockWise()}) {
            BlockPos at = head.relative(side);
            Direction toBed = side.getOpposite();
            Vec3 d = new Vec3(toBed.getStepX(), 0, toBed.getStepZ());
            Vec3 f = new Vec3(facing.getStepX(), 0, facing.getStepZ());
            BlockState there = level.getBlockState(at);
            boolean spare = there.getBlock() instanceof BedBlock && there.getValue(BedBlock.FACING) == facing && there.getValue(BedBlock.PART) == BedPart.HEAD;
            if (tier == Tier.TOGETHER) {
                // 右侧卧、脸朝床：头朝她的右边，只有床朝向顺时针那一侧头才在枕头那头
                if (side != facing.getClockWise()) continue;
                Vec3 base = spare ? Vec3.atBottomCenterOf(at).add(0, .5625, 0) : standable(level, at) ? Vec3.atBottomCenterOf(at) : null;
                if (base == null) continue;
                return new Spot(base.subtract(f.scale(.5)), yawFacing(d));
            }
            if (spare || !standable(level, at)) continue;
            float yaw = switch (tier) {
                case WATCH -> yawFacing(d.scale(-1));     // 背对床朝外守着
                case DOZE -> yawFacing(d);                // 面朝床
                default -> yawLeftTo(d);                  // 侧坐，左边朝着床
            };
            return new Spot(Vec3.atBottomCenterOf(at), yaw);
        }
        return null;
    }

    /** 你右手边能坐的那一处（站不下就在你身边找一处）。 */
    private @Nullable Vec3 sitSpot(ServerLevel level, ServerPlayer p) {
        float r = p.yBodyRot * Mth.DEG_TO_RAD;
        Vec3 right = new Vec3(-Mth.cos(r), 0, -Mth.sin(r));
        Vec3 want = p.position().add(right.scale(SIT_SIDE));
        BlockPos at = BlockPos.containing(want.x, p.getY() + .2, want.z);
        if (standable(level, at)) return new Vec3(want.x, at.getY(), want.z);
        return Companions.spotNear(level, p, SIT_SIDE + .4, c);
    }

    /** 这一格站得下：脚下是实心的面，这一格和上面一格是空的。 */
    static boolean standable(ServerLevel level, BlockPos at) {
        return level.getBlockState(at.below()).isFaceSturdy(level, at.below(), Direction.UP)
                && level.getBlockState(at).getCollisionShape(level, at).isEmpty()
                && level.getBlockState(at.above()).getCollisionShape(level, at.above()).isEmpty();
    }

    /** 面朝 {@code d}（水平）的 yaw：她的前方 = (-sin yaw, cos yaw)。 */
    static float yawFacing(Vec3 d) {
        return (float) (Mth.atan2(-d.x, d.z) * Mth.RAD_TO_DEG);
    }

    /** 左边朝着 {@code d} 的 yaw：她的左边 = (cos yaw, sin yaw)。 */
    static float yawLeftTo(Vec3 d) {
        return (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG);
    }
}
