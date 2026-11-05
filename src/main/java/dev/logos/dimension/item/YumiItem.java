package dev.logos.dimension.item;

import dev.logos.dimension.DimensionBlocks;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.combat.CombatWeapon;
import dev.logos.dimension.longsword.LongswordCombat;
import dev.logos.dimension.npc.DivineFox;
import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.registries.DeferredItem;

/**
 * 神狐和弓（四套配色）：玩家按弓道的射法拉弓。按住右键先搭箭、把弓举过头顶（打起し），再引分け拉到会；
 * 松开右键放箭，之后是返弦与残心。举弓时松手不放箭，弓倒放回原处。箭飞得快、伤害高，弹药、附魔与药水箭仍走原版弓的流程。
 * <p>
 * 玩家和神狐用这套动作（神狐的见 {@code FoxBowGoal}）；骷髅等生物拿着它时它就是一把原版弓（{@link BowItem}，原版远程 AI 与射箭）。
 */
public final class YumiItem extends BowItem implements CombatWeapon.Holder {
    private static final net.minecraft.resources.Identifier MOVESET = LogosDimension.id("yumi");
    public static final DeferredItem<YumiItem> BAMBOO = register("bamboo", 7);
    public static final DeferredItem<YumiItem> VERMILION = register("vermilion", 8);
    public static final DeferredItem<YumiItem> INDIGO = register("indigo", 9);
    public static final DeferredItem<YumiItem> CHESTNUT = register("chestnut", 10);
    /** 搭箭与打起し的时长（刻，招式 uchiokoshi 的 0.45 秒）：这段时间里松手不放箭。 */
    public static final int RAISE_TICKS = 9;
    /** 引分け的时长（刻，招式 hikiwake 的 0.6 秒）：从举到头顶到拉满。 */
    public static final int DRAW_TICKS = 12;
    /** 拉满时的箭速（原版弓 3.0）与箭的基础伤害（原版箭 2.0）。原版伤害按 箭速 × 基础伤害 取整。 */
    public static final float FULL_SPEED = 4.5f;
    public static final double BASE_DAMAGE = 2.4;
    /** 拉满时的散布（原版弓 1.0）。 */
    public static final float SPREAD = 0.5f;
    private final CombatWeapon weapon;

    private static DeferredItem<YumiItem> register(String finish, int id) {
        String name = "fox_yumi_" + finish;
        var weapon = CombatWeapon.register(id, name, "yumi", MOVESET);
        return DimensionBlocks.ITEMS.registerItem(name, p -> new YumiItem(p, weapon));
    }
    public YumiItem(Properties p, CombatWeapon weapon) {
        super(p.durability(384).enchantable(1).component(DataComponents.LORE, new ItemLore(List.of(
                Component.translatable("tooltip.logos_dimension.yumi.draw"),
                Component.translatable("tooltip.logos_dimension.yumi.arrow")))));
        this.weapon = weapon;
    }
    public static void init() {}
    /** 随机一把（神狐背的弓）。 */
    public static YumiItem random(RandomSource random) {
        return switch (random.nextInt(4)) {
            case 1 -> VERMILION.get();
            case 2 -> INDIGO.get();
            case 3 -> CHESTNUT.get();
            default -> BAMBOO.get();
        };
    }
    /** 配色名（物品名 {@code fox_yumi_<配色>}）。 */
    public String finish() { return weapon.mesh().substring("fox_yumi_".length()); }
    @Override public CombatWeapon combatWeapon() { return weapon; }

    /** 拉弓的力度 0–1：举弓的那几刻为 0，之后按原版弓的曲线在 {@link #DRAW_TICKS} 刻内拉满。 */
    public static float power(int ticks) {
        float x = Mth.clamp((ticks - RAISE_TICKS) / (float) DRAW_TICKS, 0, 1);
        return (x * x + 2 * x) / 3;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.FAIL;
        ItemStack stack = player.getItemInHand(hand);
        boolean ammo = !player.getProjectile(stack).isEmpty();
        InteractionResult hooked = EventHooks.onArrowNock(stack, level, player, hand, ammo);
        if (hooked != null) return hooked;
        if (!player.hasInfiniteMaterials() && !ammo) return InteractionResult.FAIL;
        // 招式状态机不接右键（例如残心还没收完）时不开始拉弓。
        if (player instanceof ServerPlayer server && !LongswordCombat.charge(server)) return InteractionResult.FAIL;
        player.startUsingItem(hand);
        return InteractionResult.CONSUME;
    }

    @Override
    public boolean releaseUsing(ItemStack stack, Level level, LivingEntity user, int remaining) {
        if (!(user instanceof Player player)) return false;
        // 松手那一刻的招式：还在举弓（uchiokoshi）就不放箭，招式文件让弓倒放回去。
        String move = player instanceof ServerPlayer server ? LongswordCombat.move(server) : null;
        if (player instanceof ServerPlayer server) LongswordCombat.release(server);
        ItemStack projectile = player.getProjectile(stack);
        if (projectile.isEmpty()) return false;
        int ticks = EventHooks.onArrowLoose(stack, level, player, getUseDuration(stack, user) - remaining, true);
        if (ticks <= RAISE_TICKS || "uchiokoshi".equals(move)) return false;
        // 刚开始引分け就松手也射得出去，只是很弱。
        float power = Math.max(0.12f, power(ticks));
        List<ItemStack> fired = draw(stack, projectile, player);
        if (level instanceof ServerLevel server && !fired.isEmpty())
            shoot(server, player, player.getUsedItemHand(), stack, fired, power * FULL_SPEED, SPREAD, power >= 1, null);
        level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ARROW_SHOOT, SoundSource.PLAYERS,
                1, 1 / (level.getRandom().nextFloat() * 0.4f + 1.2f) + power * 0.5f);
        player.awardStat(Stats.ITEM_USED.get(this));
        return true;
    }

    /** 玩家和神狐射出的箭：基础伤害提高，并标记成和弓的箭（客户端画成长长的矢）。其他生物射的箭保持原版。 */
    @Override
    public AbstractArrow customArrow(AbstractArrow arrow, ItemStack ammo, ItemStack weapon) {
        if (arrow.getOwner() instanceof Player || arrow.getOwner() instanceof DivineFox) {
            arrow.setBaseDamage(BASE_DAMAGE);
            arrow.setData(NatureAttachments.YUMI_ARROW, true);
        }
        return arrow;
    }
}
