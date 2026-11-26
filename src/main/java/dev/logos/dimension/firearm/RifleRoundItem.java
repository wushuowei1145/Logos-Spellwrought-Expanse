package dev.logos.dimension.firearm;

import dev.logos.dimension.DimensionBlocks;
import dev.logos.lang.Element;
import dev.logos.runtime.Effects;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * 步枪弹：一发一颗，沿视线直射，射程远、远处几乎不衰减，打中第一个目标就停；下界合金弹头多穿一个。
 * 和霰弹的分工是远近：贴脸不如霰弹全中，二十格外远胜霰弹。伤害是物理元素（Logos 的 {@code Effects.followUp}，不吃受击冷却）。
 * 数值在 {@link Rifle}，金属倍数在 {@link RoundMetal}（docs/FIREARMS.md 第 5 节）。
 */
public final class RifleRoundItem extends Item implements Ammo.Item {
    public static final DeferredItem<RifleRoundItem> COPPER = DimensionBlocks.ITEMS.registerItem("copper_rifle_round", p -> new RifleRoundItem(p, RoundMetal.COPPER));
    public static final DeferredItem<RifleRoundItem> IRON = DimensionBlocks.ITEMS.registerItem("iron_rifle_round", p -> new RifleRoundItem(p, RoundMetal.IRON));
    public static final DeferredItem<RifleRoundItem> NETHERITE = DimensionBlocks.ITEMS.registerItem("netherite_rifle_round",
            p -> new RifleRoundItem(p.fireResistant(), RoundMetal.NETHERITE));

    private final RoundMetal metal;
    private final Ammo ammo;

    public RifleRoundItem(Properties properties, RoundMetal metal) {
        super(properties.stacksTo(64));
        this.metal = metal;
        this.ammo = shot -> fire(shot, metal);
    }
    public static void init() {}
    public RoundMetal metal() { return metal; }
    @Override public Ammo ammo() { return ammo; }

    // 刻了术式的步枪弹（SpellRounds）：名字带术式名、说明写档位和注入、带附魔光泽。
    @Override public Component getName(ItemStack stack) { return SpellRounds.name(stack, super.getName(stack)); }
    @Override public boolean isFoil(ItemStack stack) { return SpellRounds.etched(stack) || super.isFoil(stack); }
    @Override public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> builder, TooltipFlag flag) {
        SpellRounds.tooltip(stack, builder);
    }

    private static Ammo.ShotResult fire(Ammo.Shot shot, RoundMetal metal) {
        var level = shot.level();
        var shooter = shot.shooter();
        var from = shot.origin();
        var dir = BuckshotItem.cone(shot.direction(), Rifle.SPREAD * shot.spread(), shooter.getRandom());
        var end = from.add(dir.scale(Rifle.RANGE));
        var block = level.clip(new ClipContext(from, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, shooter));
        var stop = block.getType() == HitResult.Type.MISS ? end : block.getLocation();
        List<Ammo.Trace> traces = new ArrayList<>();
        var carried = SpellRounds.carry(shot, SpellRounds.RIFLE);
        List<SpellRounds.Struck> hits = new ArrayList<>();
        int left = 1 + metal.pierce;
        for (var hit : struck(level, shooter, from, stop)) {
            if (left == 0) break;
            left--;
            double damage = Rifle.DAMAGE * metal.damage * Rifle.falloff(Math.sqrt(hit.distance())) * shot.damage();
            // 改装弹（RoundEffects）：水银弹打亡灵、腐败生物伤害加成，打中后燃烧弹点火、水银弹上虚弱。
            Effects.followUp(shooter, hit.entity(), damage * RoundEffects.factor(shot.round(), hit.entity()), Element.PHYSICAL);
            hit.entity().knockback(Rifle.KNOCKBACK, -dir.x, -dir.z);
            RoundEffects.hit(shot.round(), shooter, hit.entity());
            traces.add(new Ammo.Trace(hit.at(), Ammo.Trace.ENTITY, 0));
            hits.add(new SpellRounds.Struck(hit.entity(), hit.at()));
        }
        // 没被生物挡完：子弹接着飞到方块上，或者飞到射程尽头。
        if (left > 0) {
            traces.add(block.getType() == HitResult.Type.MISS ? new Ammo.Trace(end, Ammo.Trace.MISS, 0)
                    : new Ammo.Trace(stop, Ammo.Trace.BLOCK, Block.getId(level.getBlockState(block.getBlockPos()))));
        }
        SpellRounds.strike(carried, level, dir, hits, block.getType() == HitResult.Type.MISS ? null : block, end);
        // 烟火弹在落点炸开：打中的第一个生物，或者方块上，或者射程尽头。
        RoundEffects.landed(shot.round(), level, shooter, !hits.isEmpty() ? hits.getFirst().at() : stop);
        level.playSound(null, shooter.getX(), shooter.getEyeY(), shooter.getZ(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, .4f, 1.95f);
        level.playSound(null, shooter.getX(), shooter.getEyeY(), shooter.getZ(), SoundEvents.FIREWORK_ROCKET_BLAST, SoundSource.PLAYERS, 1f, .9f);
        return new Ammo.ShotResult(List.copyOf(traces));
    }

    /** 一颗子弹从 {@code from} 飞到 {@code to} 一路碰到的生物，近的在前；{@code distance} 是距离的平方。 */
    record Hit(LivingEntity entity, Vec3 at, double distance) {}

    static List<Hit> struck(ServerLevel level, LivingEntity shooter, Vec3 from, Vec3 to) {
        List<Hit> hits = new ArrayList<>();
        for (var e : level.getEntities(shooter, new AABB(from, to).inflate(1), e -> BuckshotItem.target(shooter, e))) {
            if (!(e instanceof LivingEntity living)) continue;
            e.getBoundingBox().inflate(e.getPickRadius() + .1).clip(from, to)
                    .ifPresent(at -> hits.add(new Hit(living, at, from.distanceToSqr(at))));
        }
        hits.sort(Comparator.comparingDouble(Hit::distance));
        return hits;
    }
}
