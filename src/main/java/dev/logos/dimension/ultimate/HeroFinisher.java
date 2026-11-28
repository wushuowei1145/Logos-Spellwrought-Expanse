package dev.logos.dimension.ultimate;

import static dev.logos.dimension.ultimate.HeroFinisherTimeline.APEX;
import static dev.logos.dimension.ultimate.HeroFinisherTimeline.CONVERGE;
import static dev.logos.dimension.ultimate.HeroFinisherTimeline.FALL;
import static dev.logos.dimension.ultimate.HeroFinisherTimeline.FLAME;
import static dev.logos.dimension.ultimate.HeroFinisherTimeline.IMPACT;
import static dev.logos.dimension.ultimate.HeroFinisherTimeline.LUNGE;
import static dev.logos.dimension.ultimate.HeroFinisherTimeline.REVEAL;
import static dev.logos.dimension.ultimate.HeroFinisherTimeline.RISE;
import static dev.logos.dimension.ultimate.HeroFinisherTimeline.THROW;

import dev.logos.dimension.camera.CameraPoint;
import dev.logos.dimension.screenfx.ScreenEffects;
import dev.logos.dimension.screenfx.ScreenFlash;
import dev.logos.runtime.PillarBurst;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.common.Tags;

/**
 * 玩家空手的最后一击「星穹誓约 · 羁绊焚天炮」在服务端的一半（docs/ULTIMATES.md 第六节，时间表 {@link HeroFinisherTimeline}）：
 * 面朝锁住的目标，Cut 5 握着魔弹冲过去（最多 {@link HeroFinisherTimeline#MAX_LUNGE} 格，路上有悬崖、岩浆、墙就停在前面），
 * 第 {@link HeroFinisherTimeline#IMPACT} 刻砸下：结算伤害、给下令的玩家放满规格的四拍黑白闪；随后 Logos 的 {@code PillarBurst} 冲天而起。
 * 镜头、魔弹、光流、通天火柱这些画面在客户端（{@code HeroFinisherShow}），所有看得见的人都有世界里的那一半。
 *
 * <p>伤害：{@link StandInFinisher#base}（{@code max(近战攻击, 法强 × 6)}）经 {@link UltimateCast#hit} 乘最后一击的倍率、吃满全队留下的馈赠；
 * 落点 {@link #SPLASH_RADIUS} 格内别的敌人吃 {@link #SPLASH} 成。不额外耗魔，不碰任何方块，只打 {@link UltimateTargets#harmable} 的。
 */
public final class HeroFinisher implements UltimateScript {
    /** 落点周围多远的敌人一起挨（格）、挨几成。 */
    static final double SPLASH_RADIUS = 6;
    static final float SPLASH = .5f;
    /** 起跳的初速度（原版跳跃）。 */
    static final double HOP = .42;

    /** 冲刺：从哪、往哪、冲几格（0 是站着不动）。 */
    private Vec3 from = Vec3.ZERO, dir = new Vec3(0, 0, 1);
    private double travel;
    private double vy;
    /** 砸下去的那一点（火柱的脚下）：砸下那一刻定住，之后目标被掀飞也不跟着走。 */
    private Vec3 burst = Vec3.ZERO;

    @Override
    public int length() {
        return HeroFinisherTimeline.LENGTH;
    }

    @Override
    public int impact() {
        return IMPACT;
    }

    @Override
    public void start(UltimateCast cast) {
        ServerPlayer owner = cast.owner();
        Vec3 aim = cast.aim();
        // 面朝要砸的那一点（客户端的姿势和镜头按同一个方向摆）
        if (aim.subtract(owner.position()).horizontalDistanceSqr() > .01)
            owner.lookAt(EntityAnchorArgument.Anchor.EYES, aim.add(0, owner.getEyeHeight() * .7, 0));
    }

    @Override
    public void tick(UltimateCast cast, int t) {
        ServerPlayer owner = cast.owner();
        ServerLevel level = cast.level();
        Vec3 hand = owner.position().add(0, 1.4, 0);
        switch (t) {
            case FLAME -> {
                sound(level, hand, SoundEvents.FLINTANDSTEEL_USE, .7f, 1.25f);
                sound(level, hand, SoundEvents.AMETHYST_BLOCK_CHIME, .6f, 1.6f);
            }
            case REVEAL -> sound(level, hand, SoundEvents.BEACON_ACTIVATE, 1.4f, 1.3f);
            case REVEAL + 8 -> sound(level, hand, SoundEvents.AMETHYST_BLOCK_RESONATE, 1.2f, 1.2f);
            case CONVERGE -> sound(level, hand, SoundEvents.BEACON_POWER_SELECT, 1.8f, .9f);
            case CONVERGE + 3, CONVERGE + 9, CONVERGE + 15, CONVERGE + 20 ->
                    sound(level, hand, SoundEvents.RESPAWN_ANCHOR_CHARGE, 1.4f, .8f + (t - CONVERGE) * .03f);
            case APEX -> {
                sound(level, hand, SoundEvents.CONDUIT_ACTIVATE, 2.4f, .6f);
                sound(level, hand, SoundEvents.BEACON_AMBIENT, 2.4f, .5f);
            }
            case APEX + 9 -> sound(level, hand, SoundEvents.BEACON_AMBIENT, 2.4f, .55f);
            case LUNGE -> {
                planLunge(cast);
                sound(level, hand, SoundEvents.TRIDENT_RIPTIDE_3.value(), 2.2f, .9f);
                sound(level, hand, SoundEvents.PHANTOM_SWOOP, 2, .7f);
            }
            case THROW -> sound(level, hand, SoundEvents.BREEZE_WIND_CHARGE_BURST.value(), 2.5f, .6f);
            case IMPACT -> impactNow(cast);
            case RISE -> {
                Vec3 at = burst;
                // Logos 的火柱只是脚下的一小截骨架（通天的那根在客户端画）；屋里、洞里再小一点，不把人罩进去
                PillarBurst.play(level, at, enclosed(level, at) ? .8f : 1.5f);
                sound(level, at, SoundEvents.FIRECHARGE_USE, 6, .45f);
                sound(level, at, SoundEvents.BLAZE_SHOOT, 5, .5f);
            }
            case RISE + 8 -> sound(level, burst, SoundEvents.GENERIC_EXPLODE.value(), 5, .4f);
            case RISE + 16, RISE + 26 -> sound(level, burst, SoundEvents.FIRE_AMBIENT, 6, .55f);
            case FALL -> sound(level, owner.position(), SoundEvents.AMETHYST_CLUSTER_BREAK, 1.6f, 1.4f);
            case FALL + 4, FALL + 9 -> sound(level, owner.position(), SoundEvents.AMETHYST_BLOCK_CHIME, 1.2f, 1.2f + (t - FALL) * .04f);
            case FALL + 12 -> sound(level, owner.position(), SoundEvents.PLAYER_LEVELUP, .6f, 1.2f);
            default -> {}
        }
        if (t >= LUNGE && t < THROW) push(owner, t);
        else if (t == THROW && travel > 0) stop(owner);
    }

    @Override
    public void end(UltimateCast cast, boolean interrupted) {
        cast.owner().resetFallDistance();
    }

    // ---------------------------------------------------------------- 砸下

    private void impactNow(UltimateCast cast) {
        ServerPlayer owner = cast.owner();
        ServerLevel level = cast.level();
        LivingEntity target = cast.target();
        Vec3 at = ground(level, cast.aim());
        burst = at;
        ScreenEffects.flash(owner, ScreenFlash.at(CameraPoint.world(at.add(0, 1.5, 0))));
        sound(level, at, SoundEvents.GENERIC_EXPLODE.value(), 6, .5f);
        sound(level, at, SoundEvents.LIGHTNING_BOLT_THUNDER, 8, .6f);
        sound(level, at, SoundEvents.WARDEN_SONIC_BOOM, 4, .55f);
        float base = StandInFinisher.base(owner);
        List<LivingEntity> hit = new ArrayList<>();
        if (target != null) {
            cast.hit(target, base);
            hit.add(target);
        }
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(at, at).inflate(SPLASH_RADIUS),
                e -> !hit.contains(e) && e.distanceToSqr(at) <= SPLASH_RADIUS * SPLASH_RADIUS && UltimateTargets.harmable(owner, owner, e))) {
            cast.hit(e, base * SPLASH);
            hit.add(e);
        }
        // 冲击把敌人掀起来（Boss 不掀）；定住的敌人照样会飞
        for (LivingEntity e : hit) {
            if (!e.isAlive() || e.is(Tags.EntityTypes.BOSSES)) continue;
            Vec3 away = e.position().subtract(at);
            away = away.horizontalDistanceSqr() < 1e-4 ? Vec3.ZERO : new Vec3(away.x, 0, away.z).normalize().scale(.35);
            e.setDeltaMovement(e.getDeltaMovement().add(away.x, .75, away.z));
            e.hurtMarked = true;
        }
    }

    // ---------------------------------------------------------------- 冲刺

    /** 量一下能冲多远：朝目标冲到离它 {@link HeroFinisherTimeline#REACH} 格，路上有悬崖、岩浆、火、墙就停在前面。 */
    private void planLunge(UltimateCast cast) {
        ServerPlayer owner = cast.owner();
        from = owner.position();
        Vec3 d = cast.aim().subtract(from);
        d = new Vec3(d.x, 0, d.z);
        double dist = d.length();
        travel = 0;
        vy = 0;
        if (dist < 1e-3 || owner.isPassenger() || owner.isFallFlying()) return;
        dir = d.scale(1 / dist);
        double want = Math.clamp(dist - HeroFinisherTimeline.REACH, 0, HeroFinisherTimeline.MAX_LUNGE);
        travel = safe(cast.level(), owner, from, dir, want);
        if (travel < 1) travel = 0;
        else vy = owner.getAbilities().flying ? 0 : HOP;
    }

    /** 这一刻的速度：水平按冲刺曲线分到每一刻，竖直照原版的跳跃（重力 0.08、阻力 0.98）。玩家的移动在客户端，速度要发过去。 */
    private void push(ServerPlayer owner, int t) {
        if (travel <= 0) return;
        float a = HeroFinisherTimeline.lunge(t), b = HeroFinisherTimeline.lunge(t + 1);
        Vec3 step = dir.scale(travel * (b - a));
        owner.setDeltaMovement(step.x, vy, step.z);
        owner.hurtMarked = true;
        owner.resetFallDistance();
        if (!owner.getAbilities().flying) vy = (vy - .08) * .98;
    }

    private void stop(ServerPlayer owner) {
        owner.setDeltaMovement(0, Math.min(0, vy), 0);
        owner.hurtMarked = true;
    }

    /** 从 {@code from} 沿 {@code dir} 最多走 {@code want} 格，每半格看一下：脚下有地、不是岩浆火堆、身子塞得下，才算走得到。 */
    static double safe(ServerLevel level, ServerPlayer owner, Vec3 from, Vec3 dir, double want) {
        double ok = 0, ground = from.y;
        AABB box = owner.getBoundingBox().move(from.scale(-1));
        for (double s = .5; s <= want + 1e-6; s += .5) {
            Vec3 at = from.add(dir.scale(s));
            Vec3 top = new Vec3(at.x, ground + 1.2, at.z);
            var down = level.clip(new ClipContext(top, new Vec3(at.x, ground - 2.5, at.z), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY,
                    CollisionContext.empty()));
            if (down.getType() == HitResult.Type.MISS) break;
            double y = down.getLocation().y;
            BlockPos feet = BlockPos.containing(at.x, y + .01, at.z);
            if (hazard(level, feet) || hazard(level, feet.below()) || !level.getFluidState(feet).isEmpty()) break;
            if (!level.noCollision(owner, box.move(at.x, y + .01, at.z))) break;
            ground = y;
            ok = s;
        }
        return ok;
    }

    /** {@code at} 往下 4 格以内的地面（目标浮在空中时火柱也从地上起）；没有地面就是它自己。 */
    static Vec3 ground(ServerLevel level, Vec3 at) {
        var down = level.clip(new ClipContext(at.add(0, .5, 0), at.add(0, -4, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                CollisionContext.empty()));
        return down.getType() == HitResult.Type.MISS ? at : down.getLocation();
    }

    /** {@code at} 头顶 12 格内就有方块（屋里、洞里）。 */
    static boolean enclosed(ServerLevel level, Vec3 at) {
        var up = level.clip(new ClipContext(at.add(0, 1, 0), at.add(0, 13, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                CollisionContext.empty()));
        return up.getType() != HitResult.Type.MISS;
    }

    /** 砸下去的那一点（砸下之前是 0）。 */
    public Vec3 burst() {
        return burst;
    }

    private static boolean hazard(ServerLevel level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        return level.getFluidState(pos).is(FluidTags.LAVA) || s.is(BlockTags.FIRE) || s.is(BlockTags.CAMPFIRES) || s.is(Blocks.MAGMA_BLOCK)
                || s.is(Blocks.CACTUS) || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.POWDER_SNOW) || s.is(Blocks.WITHER_ROSE);
    }

    private static void sound(ServerLevel level, Vec3 at, SoundEvent sound, float volume, float pitch) {
        level.playSound(null, at.x, at.y, at.z, sound, SoundSource.PLAYERS, volume, pitch);
    }

    private static void sound(ServerLevel level, Vec3 at, Holder<SoundEvent> sound, float volume, float pitch) {
        level.playSound(null, at.x, at.y, at.z, sound, SoundSource.PLAYERS, volume, pitch);
    }
}
