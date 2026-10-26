package dev.logos.dimension.item;

import dev.logos.dimension.DimensionBlocks;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.registries.DeferredItem;

/** 四段蓄力弓；弹药仍经原版的附魔、药水与光灵箭路径创建。 */
public final class NaturesForceItem extends BowItem {
    public static final DeferredItem<NaturesForceItem> ITEM = DimensionBlocks.ITEMS.registerItem("natures_force",
            NaturesForceItem::new, p -> p.durability(384).enchantable(1));

    public NaturesForceItem(Properties properties) { super(properties); }
    public static void init() {}

    @Override
    public void onUseTick(Level level, LivingEntity shooter, ItemStack stack, int remaining) {
        if (level.isClientSide()) return;
        int limit = 4;
        if (shooter instanceof ServerPlayer player && !player.hasInfiniteMaterials()) {
            double mana = LogosNatureBridge.mana(player);
            limit = mana >= 10 ? 4 : mana >= 5 ? 3 : 2;
            if (getUseDuration(stack, shooter) - remaining >= NatureCharge.FULL_CHARGE && mana < 10) limit = 2;
        }
        if (shooter.getData(NatureAttachments.CHARGE_LIMIT) != limit) shooter.setData(NatureAttachments.CHARGE_LIMIT, limit);
    }

    @Override
    public boolean releaseUsing(ItemStack bow, Level level, LivingEntity shooter, int remaining) {
        int ticks = getUseDuration(bow, shooter) - remaining;
        ItemStack ammo = shooter.getProjectile(bow);
        if (shooter instanceof Player player) {
            ticks = EventHooks.onArrowLoose(bow, level, player, ticks, !ammo.isEmpty());
        } else if (ammo.isEmpty()) ammo = new ItemStack(Items.ARROW);
        if (ticks < 0 || getPowerForTime(ticks) < .1f || ammo.isEmpty()) return false;
        if (level instanceof ServerLevel server) {
            double previousMana = shooter instanceof ServerPlayer player ? LogosNatureBridge.mana(player) : 0;
            // 魔力不足只降至普通满弓；不阻断射击，也不扣除残余魔力。
            if (shooter instanceof ServerPlayer player && !LogosNatureBridge.pay(player, NatureCharge.mana(ticks))) ticks = 20;
            List<ItemStack> projectiles = shooter instanceof Player ? draw(bow, ammo, shooter) : List.of(ammo.copyWithCount(1));
            if (projectiles.isEmpty()) {
                if (shooter instanceof ServerPlayer player) LogosNatureBridge.mana(player, previousMana);
                return false;
            }
            shoot(server, shooter, shooter.getUsedItemHand(), bow, projectiles, NatureCharge.speed(ticks),
                    NatureCharge.empowered(ticks) ? 0 : 1, ticks >= NatureCharge.EMPOWERED, null);
        }
        level.playSound(null, shooter.getX(), shooter.getY(), shooter.getZ(), SoundEvents.ARROW_SHOOT,
                SoundSource.PLAYERS, 1, 1);
        if (shooter instanceof Player player) player.awardStat(Stats.ITEM_USED.get(this));
        return true;
    }

    @Override
    public AbstractArrow customArrow(AbstractArrow arrow, ItemStack ammo, ItemStack weapon) {
        ((NatureArrow) arrow).natureStage(2);
        return arrow;
    }

    @Override
    protected void shootProjectile(LivingEntity shooter, Projectile projectile, int index, float speed,
                                   float uncertainty, float angle, LivingEntity target) {
        if (projectile instanceof AbstractArrow arrow) {
            int stage = speed >= 8 ? 4 : speed >= 3 ? 3 : 2;
            ((NatureArrow) arrow).natureStage(stage);
            // 原版物理伤害乘以速度，强化箭仅提高飞速，不能让物理伤害随之翻倍。
            ((NatureArrow) arrow).natureDamageScale(stage == 4 ? 3d / 8d : stage == 2 ? .9 : 1);
            if (!(shooter instanceof Player)) arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
        }
        if (target == null) super.shootProjectile(shooter, projectile, index, speed, uncertainty, angle, null);
        else {
            double dx = target.getX() - projectile.getX(), dz = target.getZ() - projectile.getZ();
            double dy = target.getY(.333333333333) - projectile.getY();
            projectile.shoot(dx, dy + (speed >= 8 ? 0 : Math.sqrt(dx * dx + dz * dz) * .2), dz, speed, uncertainty);
        }
    }

    /** 骷髅原版 AI 释放时调用；普通生物无限箭、无魔力消耗且禁止拾取复制。 */
    public void shootMob(ServerLevel level, LivingEntity shooter, LivingEntity target, ItemStack bow, InteractionHand hand) {
        ItemStack ammo = shooter.getProjectile(bow);
        if (ammo.isEmpty()) ammo = new ItemStack(Items.ARROW);
        shoot(level, shooter, hand, bow, List.of(ammo.copyWithCount(1)), 8, 0, false, target);
        shooter.playSound(SoundEvents.SKELETON_SHOOT, 1, 1);
    }
}
