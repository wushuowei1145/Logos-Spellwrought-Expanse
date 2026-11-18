package dev.logos.dimension.companion;

import dev.logos.dimension.DimensionBlocks;
import dev.logos.dimension.LogosDimension;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 袋装世界树（docs/ELF_PRINCESS_COMPANION.md 第 2 节）：布袋子里装着一捧土，土上长着一株迷你世界树，是精灵公主的信物。
 * 她撤退时就是回到这棵小树里休养。
 * <ul>
 *   <li>写着名字的（绑定）：召唤她的道具。右键叫她出来或收回，潜行右键让她留在原地或跟上。她在树里歇着时，物品上的冷却就是还要歇多久
 *       （不另外显示文字）。</li>
 *   <li>空白的：已经和她立契的人拿着它右键她，盖一枚叶之契（用掉，小树多长一片叶子）。还没和她立契的人右键用它，这一袋就成了他的，
 *       她第一次从里面出来（加入方式定下来之前的临时做法）。</li>
 * </ul>
 * 图标是代码画的 16×16（{@code tools/bagged_world_tree_icon.py}）。
 */
public class BaggedWorldTreeItem extends Item {
    private static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, LogosDimension.ID);

    /** 袋子上写的名字：主人的 UUID 和当时的名字（同形代）。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<KatashiroItem.Owner>> OWNER = COMPONENTS.registerComponentType(
            "bagged_world_tree_owner", b -> b.persistent(KatashiroItem.Owner.CODEC).networkSynchronized(KatashiroItem.Owner.STREAM_CODEC));
    public static final DeferredItem<BaggedWorldTreeItem> ITEM = DimensionBlocks.ITEMS.registerItem("bagged_world_tree",
            p -> new BaggedWorldTreeItem(p.stacksTo(16)));

    public BaggedWorldTreeItem(Properties properties) {
        super(properties);
    }

    static void register(IEventBus bus) {
        COMPONENTS.register(bus);
    }

    /** 写着 {@code owner} 名字的一袋。 */
    public static ItemStack bound(Player owner) {
        ItemStack stack = new ItemStack(ITEM.get());
        stack.set(OWNER.get(), new KatashiroItem.Owner(owner.getUUID(), owner.getName().getString()));
        return stack;
    }

    public static ItemStack blank() {
        return new ItemStack(ITEM.get());
    }

    public static boolean isBlank(ItemStack stack) {
        return stack.is(ITEM.get()) && !stack.has(OWNER.get());
    }

    public static boolean boundTo(ItemStack stack, Player player) {
        KatashiroItem.Owner o = stack.is(ITEM.get()) ? stack.get(OWNER.get()) : null;
        return o != null && o.id().equals(player.getUUID());
    }

    /** {@code player} 身上有没有写着自己名字的一袋。 */
    public static boolean carries(Player player) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) if (boundTo(inv.getItem(i), player)) return true;
        return false;
    }

    /** 她回到树里歇着：袋子上转起冷却，转完就能再叫（物品冷却是给人看的进度，不另外显示文字）。 */
    static void resting(ServerPlayer p, int ticks) {
        if (ticks > 0) p.getCooldowns().addCooldown(blank(), ticks);
    }

    /** 上线时补上还没歇完的冷却（物品冷却不存档）。 */
    static void refresh(ServerPlayer p) {
        resting(p, (int) Companions.restLeft(p, Companions.PRINCESS_DEF));
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (!(player instanceof ServerPlayer sp)) return InteractionResult.SUCCESS;
        ItemStack stack = player.getItemInHand(hand);
        CompanionDef d = Companions.PRINCESS_DEF;
        KatashiroItem.Owner o = stack.get(OWNER.get());
        if (o == null) {
            // 还没和她立契：这一袋成了他的，她第一次从里面出来。已经立契了：空白的袋子是拿去给她盖叶之契的
            if (Companions.bond(sp, d).pact()) {
                rustle(sp, false);
                return InteractionResult.CONSUME;
            }
            Companions.grantPact(sp, d);
            ItemStack mine = bound(sp);
            if (stack.getCount() == 1) {
                player.setItemInHand(hand, mine);
            } else {
                stack.shrink(1);
                if (!sp.getInventory().add(mine)) sp.drop(mine, false);
            }
            Companions.summon(sp, d, null);
            sp.getCooldowns().addCooldown(stack, 20);
            return InteractionResult.SUCCESS;
        }
        if (!o.id().equals(sp.getUUID())) {
            rustle(sp, false);
            return InteractionResult.FAIL;
        }
        // 袋子在手，契就在（存档出过问题、或被命令清掉时补上）
        if (!Companions.bond(sp, d).pact()) Companions.grantPact(sp, d);
        CompanionEntity c = Companions.out(sp, d);
        if (c != null) {
            if (sp.isShiftKeyDown()) c.toggleStay(sp);
            else Companions.dismiss(sp, d);
        } else if (Companions.summon(sp, d, null) != null) {
            // 叫不出来（还在树里歇着）：袋子沙沙响一下，冷却转起来就是还要歇多久
            rustle(sp, true);
            refresh(sp);
            return InteractionResult.FAIL;
        }
        sp.getCooldowns().addCooldown(stack, 20);
        return InteractionResult.SUCCESS;
    }

    /** 袋子沙沙响一下（叫不出来、不是你的）：几片小叶子从手边落下，没有文字。 */
    private static void rustle(ServerPlayer p, boolean resting) {
        if (!(p.level() instanceof ServerLevel level)) return;
        Vec3 hand = p.getEyePosition().add(p.getLookAngle().scale(.6)).add(0, -.4, 0);
        level.sendParticles(new DustParticleOptions(resting ? 0x7FA36B : 0xB3A27A, .8f), hand.x, hand.y, hand.z, 6, .12, .1, .12, 0);
        level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.AZALEA_LEAVES_STEP, SoundSource.PLAYERS, .7f, resting ? .8f : 1.3f);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> out, TooltipFlag flag) {
        KatashiroItem.Owner o = stack.get(OWNER.get());
        if (o == null) {
            out.accept(Component.translatable("tooltip.logos_dimension.bagged_world_tree.blank").withStyle(ChatFormatting.GRAY));
            out.accept(Component.translatable("tooltip.logos_dimension.bagged_world_tree.blank_use").withStyle(ChatFormatting.DARK_GRAY));
            return;
        }
        out.accept(Component.translatable("tooltip.logos_dimension.bagged_world_tree.owner", o.name()).withStyle(ChatFormatting.GRAY));
        out.accept(Component.translatable("tooltip.logos_dimension.bagged_world_tree.use").withStyle(ChatFormatting.DARK_GRAY));
    }
}
