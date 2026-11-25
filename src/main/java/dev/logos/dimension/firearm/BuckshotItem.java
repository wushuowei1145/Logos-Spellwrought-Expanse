package dev.logos.dimension.firearm;

import dev.logos.dimension.DimensionBlocks;
import dev.logos.lang.Element;
import dev.logos.runtime.Effects;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * 霰弹：一发八颗弹丸，散成一个锥形，近处威力大、远了衰减；锥形里的几个目标都会挨。
 * 同一个目标挨的几颗合成一次伤害（物理元素，Logos 的 {@code Effects.followUp}，不吃受击冷却），击退按挨了几颗算。
 * 数值在 {@link Buckshot}，试玩后再调（docs/FIREARMS.md 第 5 节）。
 */
public final class BuckshotItem extends Item implements Ammo.Item {
    // 数值和衰减在 Buckshot 里（不依赖注册表，单元测试直接测）。
    public static final DeferredItem<BuckshotItem> ITEM = DimensionBlocks.ITEMS.registerItem("buckshot_shell", BuckshotItem::new);
    private static final Ammo AMMO = BuckshotItem::fire;

    public BuckshotItem(Properties properties) { super(properties.stacksTo(64)); }
    public static void init() {}
    @Override public Ammo ammo() { return AMMO; }

    private static Ammo.ShotResult fire(Ammo.Shot shot) {
        var level = shot.level();
        var shooter = shot.shooter();
        var random = shooter.getRandom();
        Map<LivingEntity, double[]> struck = new LinkedHashMap<>();
        List<Ammo.Trace> traces = new ArrayList<>();
        for (int i = 0; i < Buckshot.PELLETS; i++) {
            var dir = cone(shot.direction(), Buckshot.SPREAD * shot.spread(), random);
            var end = shot.origin().add(dir.scale(Buckshot.RANGE));
            var block = level.clip(new ClipContext(shot.origin(), end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, shooter));
            var stop = block.getType() == HitResult.Type.MISS ? end : block.getLocation();
            var hit = ProjectileUtil.getEntityHitResult(level, shooter, shot.origin(), stop,
                    new AABB(shot.origin(), stop).inflate(1), e -> target(shooter, e), .1f);
            if (hit != null && hit.getEntity() instanceof LivingEntity living) {
                double d = shot.origin().distanceTo(hit.getLocation());
                var sum = struck.computeIfAbsent(living, k -> new double[2]);
                sum[0] += Buckshot.PELLET_DAMAGE * Buckshot.falloff(d) * shot.damage();
                sum[1]++;
                traces.add(new Ammo.Trace(hit.getLocation(), Ammo.Trace.ENTITY, 0));
            } else if (block.getType() != HitResult.Type.MISS) {
                traces.add(new Ammo.Trace(stop, Ammo.Trace.BLOCK, Block.getId(level.getBlockState(block.getBlockPos()))));
            } else traces.add(new Ammo.Trace(end, Ammo.Trace.MISS, 0));
        }
        var look = shot.direction();
        for (var entry : struck.entrySet()) {
            var target = entry.getKey();
            Effects.followUp(shooter, target, entry.getValue()[0], Element.PHYSICAL);
            target.knockback(Math.min(1.2, .12 * entry.getValue()[1]), -look.x, -look.z);
        }
        level.playSound(null, shooter.getX(), shooter.getEyeY(), shooter.getZ(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, .55f, 1.75f);
        level.playSound(null, shooter.getX(), shooter.getEyeY(), shooter.getZ(), SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.PLAYERS, 1f, .6f);
        return new Ammo.ShotResult(List.copyOf(traces));
    }

    /** 打得到谁：活着、能被点中的生物，不打自己、不打自己的宠物、不打同乘的。 */
    private static boolean target(LivingEntity shooter, Entity e) {
        if (e == shooter || !e.isAlive() || e.isSpectator() || !e.isPickable() || e.isPassengerOfSameVehicle(shooter)) return false;
        return !(e instanceof TamableAnimal pet && pet.isOwnedBy(shooter));
    }

    /** 以 {@code dir} 为轴、半角 {@code degrees} 的锥形里均匀取一个方向。 */
    static Vec3 cone(Vec3 dir, double degrees, RandomSource random) {
        double r = Math.toRadians(degrees) * Math.sqrt(random.nextDouble());
        double a = random.nextDouble() * Math.PI * 2;
        var up = Math.abs(dir.y) > .99 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
        var u = dir.cross(up).normalize();
        var v = u.cross(dir).normalize();
        return dir.scale(Math.cos(r)).add(u.scale(Math.sin(r) * Math.cos(a))).add(v.scale(Math.sin(r) * Math.sin(a))).normalize();
    }
}
