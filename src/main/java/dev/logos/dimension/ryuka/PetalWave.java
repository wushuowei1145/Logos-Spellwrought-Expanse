package dev.logos.dimension.ryuka;

import dev.logos.dimension.LogosDimension;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 落樱终式的刀气：一道新月形的樱花刀光，沿出刀时的视线（含俯仰）每刻飞 {@link #SPEED} 格，最远 {@link #RANGE} 格，撞到方块停下。
 * 新月宽 {@link #HALF_WIDTH} × 2 格，穿过路上的所有生物，每个只打一次 {@link #DAMAGE} 点奥秘伤害（{@link Ryuka#strike}，同样不能被盾挡、斩杀化作花瓣）。
 * 不伤挥刀的人、同乘的和自己的宠物。画面是服务端发的粒子：粉色的光点勾出新月，原版樱花瓣从刀光上飘落。
 */
@EventBusSubscriber(modid = LogosDimension.ID)
public final class PetalWave {
    public static final double RANGE = 14, SPEED = 1.1, HALF_WIDTH = 1.7, DAMAGE = 12;
    /** 新月往后弯的深度（格）、离视线的高度（格，从眼睛往下）。 */
    private static final double BOW = .55, DROP = .35;
    /** 新月绕前进方向倾斜（度）：终式是从右上斩向左下。 */
    private static final double ROLL = -28;
    private static final DustParticleOptions PINK = new DustParticleOptions(0xF7B9DC, 1.1f), WHITE = new DustParticleOptions(0xFFF4FA, .8f);
    private static final List<Wave> WAVES = new ArrayList<>();

    private PetalWave() {}

    public static int active() { return WAVES.size(); }

    public static void launch(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) return;
        Vec3 dir = player.getLookAngle().normalize();
        Vec3 from = player.getEyePosition().add(0, -DROP, 0).add(dir.scale(.6));
        WAVES.add(new Wave(level, player, from, dir));
        level.playSound(null, from.x, from.y, from.z, SoundEvents.CHERRY_LEAVES_BREAK, SoundSource.PLAYERS, 1.4f, .8f);
        level.playSound(null, from.x, from.y, from.z, SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1, .6f);
    }

    @SubscribeEvent
    static void tick(ServerTickEvent.Post event) {
        WAVES.removeIf(Wave::step);
    }

    @SubscribeEvent
    static void stopped(ServerStoppedEvent event) { WAVES.clear(); }

    private static final class Wave {
        final ServerLevel level;
        final ServerPlayer owner;
        final Vec3 dir, right;
        Vec3 at;
        double travelled;
        final Set<UUID> hit = new HashSet<>();

        Wave(ServerLevel level, ServerPlayer owner, Vec3 from, Vec3 dir) {
            this.level = level;
            this.owner = owner;
            this.at = from;
            this.dir = dir;
            Vec3 side = dir.cross(new Vec3(0, 1, 0));
            if (side.lengthSqr() < 1e-6) side = new Vec3(1, 0, 0);
            side = side.normalize();
            Vec3 top = side.cross(dir).normalize();
            double r = Math.toRadians(ROLL);
            this.right = side.scale(Math.cos(r)).add(top.scale(Math.sin(r)));
        }

        /** 推进一刻；结束返回 true。 */
        boolean step() {
            if (owner.isRemoved() || owner.level() != level) return true;
            Vec3 next = at.add(dir.scale(SPEED));
            var block = level.clip(new ClipContext(at, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, owner));
            boolean blocked = block.getType() != HitResult.Type.MISS;
            if (blocked) next = block.getLocation();
            hit(at, next);
            draw(next);
            travelled += at.distanceTo(next);
            at = next;
            if (blocked || travelled >= RANGE) {
                level.sendParticles(ParticleTypes.CHERRY_LEAVES, at.x, at.y, at.z, 14, HALF_WIDTH * .6, .5, HALF_WIDTH * .6, 0);
                level.playSound(null, at.x, at.y, at.z, SoundEvents.CHERRY_LEAVES_FALL, SoundSource.PLAYERS, 1, 1.2f);
                return true;
            }
            return false;
        }

        /** 新月扫过的这一段里还没打过的生物。 */
        void hit(Vec3 from, Vec3 to) {
            AABB box = new AABB(from, to).inflate(HALF_WIDTH + .5);
            for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, box, this::mayHit)) {
                if (hit.contains(e.getUUID())) continue;
                // 生物离这一段的中线（前进方向）不超过半宽加它自己的半宽
                Vec3 c = e.getBoundingBox().getCenter();
                double s = Math.clamp(c.subtract(from).dot(dir), 0, from.distanceTo(to));
                Vec3 nearest = from.add(dir.scale(s));
                if (c.distanceTo(nearest) > HALF_WIDTH + e.getBbWidth() / 2 + e.getBbHeight() / 4) continue;
                hit.add(e.getUUID());
                Ryuka.strike(owner, e, DAMAGE);
            }
        }

        boolean mayHit(LivingEntity e) {
            if (e == owner || !e.isAlive() || e.isSpectator() || e.isPassengerOfSameVehicle(owner)) return false;
            return !(e instanceof TamableAnimal pet && pet.isOwnedBy(owner));
        }

        /** 新月：两头往后弯，粉色光点勾边，刀光中段亮白，樱花瓣从上面飘下。 */
        void draw(Vec3 center) {
            int points = 13;
            for (int i = 0; i < points; i++) {
                double k = i / (points - 1.0) * 2 - 1;
                double a = k * Math.toRadians(72);
                Vec3 p = center.add(right.scale(Math.sin(a) * HALF_WIDTH)).subtract(dir.scale((1 - Math.cos(a)) * BOW * HALF_WIDTH));
                level.sendParticles(Math.abs(k) < .45 ? WHITE : PINK, p.x, p.y, p.z, 1, .03, .03, .03, 0);
                if (i % 3 == 1) level.sendParticles(ParticleTypes.CHERRY_LEAVES, p.x, p.y, p.z, 1, .15, .1, .15, 0);
            }
            level.sendParticles(ParticleTypes.END_ROD, center.x, center.y, center.z, 1, .2, .05, .2, .005);
        }
    }
}
