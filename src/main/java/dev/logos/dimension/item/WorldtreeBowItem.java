package dev.logos.dimension.item;

import dev.logos.dimension.DimensionBlocks;
import dev.logos.dimension.worldtree.Worldtree;
import dev.logos.dimension.worldtree.WorldtreeAim;
import dev.logos.dimension.worldtree.WorldtreeArrow;
import dev.logos.dimension.worldtree.WorldtreeDraw;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * 世界树之弓（精灵公主的弓）：原版的拉弓姿势与三段拉弓模型。拉弓时在视线前方展开藤蔓框，框里 48 格内的敌人每 0.2 秒锁定一个
 * （每个 5 点魔力，最多 16 个，先锁没锁过的），每锁一个弓上多一支虚影箭、响一声往上走的叮。拉到原版弓拉满的时间（1 秒），
 * 有箭、有 10 点魔力时中间出现一支实箭。松手时朝每个锁定发一支追踪箭（生命伤害，锁得越多每支越痛），
 * 有实箭时再向前射出一支笔直的高速箭（生命伤害并施加 5 秒生命易伤 I，扣 10 点魔力、用掉背包里的一支箭）。
 * 只有中间那支实箭用箭。
 * <p>按住左键是横着拉弓（{@link #beginSideways}）：蓄力一样快、一样每 0.2 秒攒一支虚影箭（每支 5 点魔力）、1 秒出实箭，
 * 但没有框、不锁定、不响；松手时所有箭在水平方向扇形射出，不追踪，穿过并伤害碰到的每个目标。
 * 骷髅等生物拿着它仍是原版弓。
 */
public final class WorldtreeBowItem extends BowItem {
    public static final int LOCK_MANA = 5, STRAIGHT_MANA = 10;
    /** 锁定的叮要很轻（维护者试玩：原来太响）。 */
    public static final float LOCK_VOLUME = .5f;
    /** 追踪箭离弦时的速度（格/刻），之后在 {@link WorldtreeArrow} 里加到最终速度；横着拉的扇形箭的速度。 */
    public static final float LAUNCH_SPEED = 1.3f, SPREAD_SPEED = 2.6f;
    public static final DeferredItem<WorldtreeBowItem> ITEM = DimensionBlocks.ITEMS.registerItem("worldtree_bow",
            WorldtreeBowItem::new, p -> p.durability(384).enchantable(1));

    public WorldtreeBowItem(Properties properties) { super(properties); }
    public static void init() {}

    /** 不要求背包里有箭：锁定只要魔力，箭只给中间那支实箭用。 */
    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        InteractionResult hooked = EventHooks.onArrowNock(stack, level, player, hand, !player.getProjectile(stack).isEmpty());
        if (hooked != null) return hooked;
        player.startUsingItem(hand);
        return InteractionResult.CONSUME;
    }

    @Override
    public void onUseTick(Level level, LivingEntity user, ItemStack stack, int remaining) {
        if (!(level instanceof ServerLevel server) || !(user instanceof ServerPlayer player)) return;
        int ticks = getUseDuration(stack, user) - remaining;
        long now = server.getGameTime();
        WorldtreeDraw draw = stack.get(Worldtree.DRAW.get());
        if (draw == null) draw = WorldtreeDraw.begin(now - ticks, false);
        if (ticks >= WorldtreeAim.FULL_TICKS && !draw.hasFull() && canAfford(player, STRAIGHT_MANA) && !player.getProjectile(stack).isEmpty())
            draw = draw.withFull(now);
        if (ticks > 0 && ticks % WorldtreeAim.INTERVAL == 0 && draw.locks().size() < WorldtreeAim.MAX_LOCKS
                // 实箭出现后，锁定只用它那 10 点以外的魔力
                && canAfford(player, LOCK_MANA + (draw.hasFull() ? STRAIGHT_MANA : 0))) {
            if (draw.sideways()) {
                // 横着拉：不锁定，只攒一支虚影箭
                spend(player, LOCK_MANA);
                draw = draw.lock(-1, now);
            } else {
                var target = WorldtreeAim.pick(player, draw, WorldtreeAim.frame(ticks));
                if (target.isPresent()) {
                    spend(player, LOCK_MANA);
                    draw = draw.lock(target.get().getId(), now);
                    var sound = Worldtree.LOCK_SOUNDS.get(draw.locks().size() - 1).get();
                    server.playSound(null, player.getX(), player.getEyeY(), player.getZ(), sound, SoundSource.PLAYERS, LOCK_VOLUME, 1);
                }
            }
        }
        if (!draw.equals(stack.get(Worldtree.DRAW.get()))) stack.set(Worldtree.DRAW.get(), draw);
    }

    @Override
    public boolean releaseUsing(ItemStack stack, Level level, LivingEntity user, int remaining) {
        WorldtreeDraw draw = stack.remove(Worldtree.DRAW.get());
        if (!(user instanceof ServerPlayer player) || !(level instanceof ServerLevel server) || draw == null) return false;
        ItemStack ammo = player.getProjectile(stack);
        int ticks = EventHooks.onArrowLoose(stack, level, player, getUseDuration(stack, user) - remaining, !ammo.isEmpty());
        if (ticks < 0) return false;
        boolean fired = false;
        Vec3 eye = player.getEyePosition(), forward = player.getLookAngle();
        Vec3 right = forward.cross(new Vec3(0, 1, 0));
        right = right.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : right.normalize();
        float damage = WorldtreeAim.homingDamage(draw.locks().size());
        // 横着拉：所有箭在水平方向扇形射出（绕自己的“上”转），不追踪、穿透
        Vec3 up = right.cross(forward).normalize();
        for (int k = 0; k < draw.locks().size() && draw.sideways(); k++) {
            WorldtreeArrow arrow = WorldtreeArrow.spread(server, player, stack, damage);
            Vec3 dir = rotate(forward, up, Math.toRadians(fan(k)));
            arrow.setPos(eye.x + forward.x * .6, eye.y - .1 + forward.y * .6, eye.z + forward.z * .6);
            arrow.setDeltaMovement(dir.scale(SPREAD_SPEED));
            server.addFreshEntity(arrow);
            fired = true;
        }
        for (int k = 0; k < draw.locks().size() && !draw.sideways(); k++) {
            if (!(server.getEntity(draw.locks().get(k).entity()) instanceof LivingEntity target) || !target.isAlive()) continue;
            WorldtreeArrow arrow = WorldtreeArrow.homing(server, player, stack, target, damage);
            // 从弓上那支虚影箭的位置散开飞出，再拐向目标
            Vec3 dir = rotate(forward, right, Math.toRadians(fan(k)));
            arrow.setPos(eye.x + forward.x * .6, eye.y - .1 + forward.y * .6, eye.z + forward.z * .6);
            arrow.setDeltaMovement(dir.scale(LAUNCH_SPEED));
            server.addFreshEntity(arrow);
            fired = true;
        }
        if (draw.hasFull() && !ammo.isEmpty() && canAfford(player, STRAIGHT_MANA)) {
            List<ItemStack> drawn = draw(stack, ammo, player);
            if (!drawn.isEmpty()) {
                spend(player, STRAIGHT_MANA);
                WorldtreeArrow arrow = WorldtreeArrow.straight(server, player, stack, drawn.getFirst(), draw.sideways());
                arrow.shootFromRotation(player, player.getXRot(), player.getYRot(), 0, WorldtreeArrow.STRAIGHT_SPEED, 0);
                server.addFreshEntity(arrow);
                fired = true;
            }
        }
        if (!fired) return false;
        server.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ARROW_SHOOT, SoundSource.PLAYERS, 1, 1);
        stack.hurtAndBreak(1, player, player.getUsedItemHand().asEquipmentSlot());
        player.awardStat(Stats.ITEM_USED.get(this));
        return true;
    }

    /** 第 k 支虚影箭（从 0 起）在弓面上偏离中间那根箭的角度：上下交替，一次比一次远，每档 3°（16 支时到 ±24°）。 */
    public static float fan(int k) {
        return (k % 2 == 0 ? 1 : -1) * 3f * (k / 2 + 1);
    }

    /** 绕 axis 转 angle（右手法则）。 */
    private static Vec3 rotate(Vec3 v, Vec3 axis, double angle) {
        double c = Math.cos(angle), s = Math.sin(angle);
        return v.scale(c).add(axis.cross(v).scale(s)).add(axis.scale(axis.dot(v) * (1 - c)));
    }

    /** 左键横着拉弓（客户端按下左键时发来）：手里是这把弓、没在拉，就开始拉，并记下是横着拉的。 */
    public static void beginSideways(ServerPlayer player) {
        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof WorldtreeBowItem) || player.isUsingItem() || player.isSpectator()) return;
        stack.set(Worldtree.DRAW.get(), WorldtreeDraw.begin(player.level().getGameTime(), true));
        player.startUsingItem(InteractionHand.MAIN_HAND);
    }

    /** 拉弓被打断（切走、放下）：清掉锁定，弓上不留虚影。 */
    @Override
    public void onStopUsing(ItemStack stack, LivingEntity entity, int count) {
        if (!entity.level().isClientSide()) stack.remove(Worldtree.DRAW.get());
    }

    @Override
    public void inventoryTick(ItemStack stack, ServerLevel level, Entity entity, EquipmentSlot slot) {
        super.inventoryTick(stack, level, entity, slot);
        if (stack.has(Worldtree.DRAW.get()) && !(entity instanceof LivingEntity living && living.isUsingItem() && living.getUseItem() == stack))
            stack.remove(Worldtree.DRAW.get());
    }

    /** 锁定、出现实箭只改组件，不播放换手动画。 */
    @Override
    public boolean shouldCauseReequipAnimation(ItemStack oldStack, ItemStack newStack, boolean slotChanged) {
        return slotChanged || !ItemStack.isSameItem(oldStack, newStack);
    }

    private static boolean canAfford(ServerPlayer player, int mana) {
        return player.hasInfiniteMaterials() || LogosNatureBridge.mana(player) >= mana;
    }

    private static void spend(ServerPlayer player, int mana) {
        if (!player.hasInfiniteMaterials()) LogosNatureBridge.mana(player, LogosNatureBridge.mana(player) - mana);
    }
}
