package dev.logos.dimension.companion;

import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 拉塞塔陪你睡、陪你坐（docs/LACERTA_COMPANION.md 第 8.1 节，接入方案第七节第 1、2 条）。心智里的 LOUNGE（分比通用的「休息」高）。
 * <ul>
 *   <li>你躺下：按羁绊挑睡姿，在你的床边（床头那一格的旁边）：
 *     <ul>
 *       <li>生疏：骑士守夜 {@code sleep_watch}——单膝跪着、背对着床朝外警戒，回头看你；</li>
 *       <li>熟悉：正坐打盹 {@code sleep}——面朝床；</li>
 *       <li>亲近：身边依偎小憩 {@code sleep_rest}——淑女侧坐，左边朝着床、往你这边倚；</li>
 *       <li>信赖、羁绊：共枕入眠 {@code sleep_together_down} → {@code sleep_together} → 醒来 {@code sleep_together_up}——右侧卧，
 *           脸朝着你，头在枕头那一头；旁边就是一张空床的话躺在那张床上。</li>
 *     </ul>
 *     你睡下 5 秒夜晚就跳过去了：她还没走到就直接到那一处躺好（不让你醒来时看见她走着走着一下子躺下）。
 *     一整夜睡过去（夜晚跳过了）醒来，你身上有她的「绯翼余温」（{@link LacertaWarmth}）。</li>
 *   <li>你站着不动（发呆、挂机）：身边有她的宝藏（{@link LacertaHoard}）就坐到宝藏上守着；不然走到你右手边，淑女侧坐（主状态 {@code sit}）；你一动她就站起来。</li>
 *   <li>巨龙形态：你睡下、挂机、在宝藏边闲着时，她盘成一圈（{@code sleep}），怀里留着的空位（模型 {@code sleep_pocket}）正好圈住你的床、你、
 *       或那块宝藏；你发呆时她在你右边坐下（{@code sit}），晴天露天时整条趴平晒太阳（{@code lie_flat}）。地方不够盘不下时就不摆。</li>
 * </ul>
 */
final class LacertaRest {
    /** 你的睡眠计时到这么多（满 100 夜晚就跳过去）时她还没躺好：直接到那一处躺下。 */
    static final int CATCH_UP_SLEEP = 90;
    /** 走到离那一处多近算到了；坐上宝藏时水平多近就跳上去；巨龙多近就直接摆好。 */
    static final double ARRIVE = .6, HOP = 1.8, DRAGON_ARRIVE = 3;
    /** 坐在你右手边多远；巨龙坐 / 趴在你右边多远（她的中心）。 */
    static final double SIT_SIDE = 1.2, DRAGON_SIDE = 4;
    /** 巨龙 {@code sleep} 怀里空位的中心：她的左边、身后多少格（模型 {@code sleep_pocket} 的 (-50, 0, 25) 单位，2 倍模型缩 0.5）。 */
    static final double POCKET_LEFT = .78, POCKET_BACK = .39;
    /** 床上的你：怀里空位对准床头那一格往床尾挪多少（躺着的你的身子中间）。 */
    static final double BED_MID = .5;

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

    /** 此刻在做什么：没有、坐在你身边、在床边、守着宝藏、巨龙盘成一圈陪挂机的你、巨龙趴着晒太阳。 */
    enum Pose { NONE, SIT, NIGHT, HOARD, CURL, SUN }

    /** 测试用：强行定这一晚的睡姿（null 照羁绊）。 */
    static @Nullable Tier forceTier;

    private final LacertaCompanion c;
    private Pose pose = Pose.NONE;
    private boolean posed, sleptLong;
    private @Nullable Tier tier;
    private @Nullable Vec3 spot;
    private float yaw;
    private long posedAt, hoardLooked = -1000, guardedDay = -1;
    private @Nullable BlockPos hoard, hoardFound;
    /** 巨龙这一下摆的是哪一段（{@code sleep} 盘睡、{@code lie_flat} 趴着）。 */
    private String dragonClip = "sleep";
    /** 上一晚的睡姿（醒来时按它起身）。 */
    private @Nullable Tier lastTier;
    /** 测试用：守着宝藏加过几次羁绊、宣告过几次。 */
    static int hoardBonds, hoardClaims;

    LacertaRest(LacertaCompanion c) {
        this.c = c;
    }

    Pose pose() {
        return pose;
    }

    /** 摆好了姿势（坐、睡、守宝藏、盘着、趴着）：待在那一处，不被挤开。有人骑着、飞着时不算。 */
    boolean anchored() {
        return posed && spot != null && pose != Pose.NONE && !c.isVehicle() && !c.isFlying();
    }

    boolean seated() {
        return (pose == Pose.SIT || pose == Pose.HOARD) && posed;
    }

    @Nullable Tier lastTier() {
        return lastTier;
    }

    /** 守着的宝藏（摆好了才有）。 */
    @Nullable BlockPos hoard() {
        return pose == Pose.HOARD && posed ? hoard : null;
    }

    String describePose() {
        if (pose == Pose.NIGHT && tier != null && !c.isDragonForm()) {
            return posed ? tier.name().toLowerCase(Locale.ROOT) : "going_" + tier.name().toLowerCase(Locale.ROOT);
        }
        String name = pose.name().toLowerCase(Locale.ROOT);
        return pose == Pose.NONE ? "none" : posed ? name : "going_" + name;
    }

    // ---------------------------------------------------------------- 打分（LacertaMind）

    /**
     * LOUNGE 的分：你睡着了（身边太平）1.05，压过通用的「休息」；你站着发呆、挂机（太平、没让她留在原地、你踩在地上）时身边有宝藏 0.6，
     * 不然 0.45；不然 0。
     */
    double score(ServerLevel level, ServerPlayer p, OwnerWatch.Activity doing, boolean calm, long now) {
        if (!calm) return 0;
        if (p.isSleeping()) return 1.05;
        boolean still = doing == OwnerWatch.Activity.IDLE || doing == OwnerWatch.Activity.AFK;
        if (!still || c.staying() || !p.onGround() || p.isInWater() || c.isInWater() || c.level().isRainingAt(c.blockPosition())) return 0;
        if (now - hoardLooked >= 40) {
            hoardLooked = now;
            hoardFound = LacertaHoard.near(level, p);
        }
        return hoardFound != null ? .6 : .45;
    }

    // ---------------------------------------------------------------- 做

    /** 心智选了 LOUNGE：你睡着就去床边，不然守宝藏、去你身边坐下（巨龙：盘睡、晒太阳、坐）。 */
    void act(ServerLevel level, ServerPlayer p, long now) {
        if (p.isSleeping()) {
            if (pose != Pose.NIGHT) begin(level, p);
            if (!posed && spot != null) approach(level, now, p.getSleepTimer() >= CATCH_UP_SLEEP);
            return;
        }
        if (pose == Pose.NIGHT) return;
        Pose want = dayPose(level, p, now);
        // 换了要摆的、或上次没找到能待的地方（隔两秒再找一次）
        if (pose != want || spot == null && now % 40 == 0) {
            if (pose != Pose.NONE) rise(level, true);
            plan(level, p, want);
        }
        if (spot == null) return;
        if (!posed) {
            approach(level, now, false);
            return;
        }
        c.getNavigation().stop();
        if (pose == Pose.SIT && !c.isDragonForm()) {
            c.setYRot(Mth.approachDegrees(c.getYRot(), yaw, 10));
            c.yBodyRot = c.getYRot();
        }
        if (pose == Pose.HOARD) guard(level, p, now);
    }

    /** 白天闲着摆什么：有宝藏守宝藏；巨龙：你挂机就盘睡圈住你，晴天露天趴着晒太阳，不然坐下。 */
    private Pose dayPose(ServerLevel level, ServerPlayer p, long now) {
        if (hoardFound != null && LacertaHoard.isHoard(level, hoardFound)) return Pose.HOARD;
        if (!c.isDragonForm()) return Pose.SIT;
        if (OwnerWatch.of(p).still(now) >= OwnerWatch.AFK_TICKS) return Pose.CURL;
        boolean sunny = !level.isDarkOutside() && !level.isRaining() && level.canSeeSky(c.blockPosition().above(2));
        return sunny ? Pose.SUN : Pose.SIT;
    }

    /** 定这一下的那一处和朝向。 */
    private void plan(ServerLevel level, ServerPlayer p, Pose want) {
        pose = want;
        posed = false;
        spot = null;
        hoard = null;
        switch (want) {
            case HOARD -> {
                hoard = hoardFound;
                if (hoard == null) return;
                if (c.isDragonForm()) {
                    planCurl(level, hoardCenter(level, hoard), p.getYRot(), hoard);
                } else {
                    LacertaHoard.Seat seat = LacertaHoard.seat(level, hoard, p.position());
                    if (seat == null) return;
                    spot = seat.at();
                    yaw = seat.yaw();
                }
            }
            case CURL -> planCurl(level, p.position(), p.getYRot());
            case SIT, SUN -> {
                if (c.isDragonForm()) {
                    dragonClip = want == Pose.SUN ? "lie_flat" : "sit";
                    float r = p.yBodyRot * Mth.DEG_TO_RAD;
                    Vec3 at = p.position().add(new Vec3(-Mth.cos(r), 0, -Mth.sin(r)).scale(DRAGON_SIDE));
                    if (dragonFits(level, at)) {
                        spot = at;
                        yaw = p.yBodyRot;
                    }
                } else {
                    spot = sitSpot(level, p);
                    yaw = p.yBodyRot;
                }
            }
            default -> {}
        }
    }

    /** 巨龙盘睡：怀里的空位对准 {@code pocket}，先试和 {@code yaw} 同向，再转 90° 试，盘得下的那一个。 */
    private void planCurl(ServerLevel level, Vec3 pocket, float yaw, BlockPos... ignore) {
        dragonClip = "sleep";
        float base = Math.round(yaw / 90f) * 90f;
        for (int i = 0; i < 4; i++) {
            float y = base + i * 90;
            Vec3 at = curlAt(pocket, y);
            if (!dragonFits(level, at, ignore)) continue;
            spot = at;
            this.yaw = y;
            return;
        }
    }

    /**
     * 每刻：你一醒她就起来（不等下一次打分）；一整夜睡过去了，醒来给你绯翼余温。摆好了就待在那一处（不被床上的你、别的生物挤开），
     * 身子不跟着转头转（床边、宝藏、巨龙）。
     */
    void tick(ServerLevel level, ServerPlayer p, long now) {
        // 有人骑上来、她飞起来了：起身（骑着时心智不想事，不然一直摆着那个姿势）
        if (pose != Pose.NONE && (c.isVehicle() || c.isFlying())) {
            rise(level, true);
            return;
        }
        if (anchored()) {
            Vec3 at = spot;
            if (Math.abs(c.getX() - at.x) > .01 || Math.abs(c.getZ() - at.z) > .01) c.setPos(at.x, c.getY(), at.z);
            c.setDeltaMovement(0, Math.min(0, c.getDeltaMovement().y), 0);
            if (pose == Pose.NIGHT || pose == Pose.HOARD || c.isDragonForm()) {
                c.setYRot(yaw);
                c.yBodyRot = yaw;
            }
        }
        if (pose == Pose.NIGHT && posed && p.isSleeping() && p.isSleepingLongEnough()) sleptLong = true;
        if (pose == Pose.NIGHT && !p.isSleeping()) {
            boolean full = sleptLong;
            rise(level, false);
            if (full) LacertaWarmth.give(p, CompanionBond.Stage.of(c.bond(p).affection()).ordinal());
        } else if (pose == Pose.NIGHT && !posed && spot != null && p.getSleepTimer() >= CATCH_UP_SLEEP) {
            approach(level, now, true);
        }
    }

    /** 你躺下：定这一晚的睡姿和床边那一处（巨龙：盘成一圈把你的床圈在怀里）。 */
    private void begin(ServerLevel level, ServerPlayer p) {
        pose = Pose.NIGHT;
        posed = false;
        sleptLong = false;
        spot = null;
        c.setSitting(false);
        tier = forceTier != null ? forceTier : Tier.of(CompanionBond.Stage.of(c.bond(p).affection()));
        BlockPos bed = p.getSleepingPos().orElse(null);
        if (c.isDragonForm()) {
            if (bed != null && level.getBlockState(bed).getBlock() instanceof BedBlock) {
                BlockState state = level.getBlockState(bed);
                Direction facing = state.getValue(BedBlock.FACING);
                BlockPos head = state.getValue(BedBlock.PART) == BedPart.HEAD ? bed : bed.relative(facing);
                Vec3 f = new Vec3(facing.getStepX(), 0, facing.getStepZ());
                planCurl(level, Vec3.atBottomCenterOf(head).subtract(f.scale(BED_MID)), facing.toYRot(), head, head.relative(facing.getOpposite()));
            } else {
                planCurl(level, p.position(), p.getYRot());
            }
            return;
        }
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

    /** 走过去；到了（或 {@code snap}：夜晚要跳过去了）就摆好姿势。坐上宝藏、巨龙离得够近就直接摆好。 */
    private void approach(ServerLevel level, long now, boolean snap) {
        Vec3 at = spot;
        if (at == null) return;
        double d = c.position().distanceTo(at);
        double flat = Math.sqrt(Math.pow(c.getX() - at.x, 2) + Math.pow(c.getZ() - at.z, 2));
        boolean near = c.isDragonForm() ? d <= DRAGON_ARRIVE : pose == Pose.HOARD ? flat <= HOP && Math.abs(c.getY() - at.y) <= 2.5 : d <= ARRIVE;
        if (!near && !snap) {
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
        boolean dragon = c.isDragonForm();
        if (pose == Pose.NIGHT) lastTier = tier;
        if (dragon && dragonClip.equals("sit") || !dragon && (pose == Pose.SIT || pose == Pose.HOARD)) c.setSitting(true);
        else c.mood.curl(true);
        if (pose == Pose.HOARD && hoard != null && c.ownerPlayer() instanceof ServerPlayer p && LacertaHoard.claim(level, p, hoard)) {
            hoardClaims++;
            CompanionLines.say(c, p, "hoard_claim", 60);
            c.mood.talk("face_smug", now, 80);
        }
    }

    /** 守着宝藏：今天头一次守满一会儿 +2 羁绊。 */
    private void guard(ServerLevel level, ServerPlayer p, long now) {
        if (now - posedAt < LacertaHoard.GUARD) return;
        long day = level.getOverworldClockTime() / 24000;
        if (day == guardedDay || LacertaDays.of(p).hoardDay() == day) return;
        guardedDay = day;
        p.setData(Companions.LACERTA_DAYS, LacertaDays.of(p).withHoardDay(day));
        var b = c.bond(p);
        c.setBond(p, b.withAffection(b.affection() + LacertaHoard.BOND));
        hoardBonds++;
    }

    /** 起来（你醒了、来了敌人、心智换了别的事、变身）：{@code quiet} 时直接站起来，不播醒来的样子。 */
    void rise(ServerLevel level, boolean quiet) {
        boolean curled = posed && (pose == Pose.NIGHT || pose == Pose.CURL || pose == Pose.SUN || pose == Pose.HOARD && c.isDragonForm()
                || pose == Pose.SIT && c.isDragonForm() && !dragonClip.equals("sit"));
        pose = Pose.NONE;
        posed = false;
        spot = null;
        hoard = null;
        c.setSitting(false);
        if (curled) {
            if (quiet) c.lacertaMood().skipWake();
            c.mood.curl(false);
        }
    }

    /** 睡着时 misc 播哪一段：巨龙照摆的那一段；共枕时先躺下（{@code sleep_together_down}，2.6 秒，停在最后一帧）再睡。 */
    String sleepAnimation(long now) {
        if (c.isDragonForm()) return dragonClip;
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

    /** 巨龙圈宝藏：怀里空位对准宝藏的水平中心；宝藏凸在地上就在它的那一层，嵌在地里就在它上面一层。 */
    private static Vec3 hoardCenter(BlockGetter level, BlockPos hoard) {
        boolean sticksOut = false;
        for (Direction side : Direction.Plane.HORIZONTAL) sticksOut |= LacertaHoard.sideFree(level, hoard, side);
        return Vec3.atBottomCenterOf(sticksOut ? hoard : hoard.above());
    }

    /** 巨龙盘睡时站在哪：怀里空位（她的左边 {@link #POCKET_LEFT}、身后 {@link #POCKET_BACK}）正好在 {@code pocket}。 */
    static Vec3 curlAt(Vec3 pocket, float yaw) {
        double r = Math.toRadians(yaw);
        Vec3 forward = new Vec3(-Math.sin(r), 0, Math.cos(r)), left = new Vec3(Math.cos(r), 0, Math.sin(r));
        return pocket.subtract(left.scale(POCKET_LEFT)).add(forward.scale(POCKET_BACK));
    }

    /** 巨龙在 {@code at} 待得下：脚下中间是实心的，身子那一块（3.2×2.4）里除了 {@code ignore}（床、宝藏）都是空的。 */
    static boolean dragonFits(BlockGetter level, Vec3 at, BlockPos... ignore) {
        BlockPos below = BlockPos.containing(at.x, at.y - .5, at.z);
        if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) return false;
        AABB box = LacertaDragon.SIZE.makeBoundingBox(at).deflate(.05);
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
            boolean skip = false;
            for (BlockPos i : ignore) skip |= i.equals(pos);
            if (!skip && !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) return false;
        }
        return true;
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
