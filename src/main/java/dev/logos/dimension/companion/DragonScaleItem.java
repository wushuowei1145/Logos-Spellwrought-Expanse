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
 * 誓约逆鳞（docs/LACERTA_COMPANION.md 第 3.2 节）：拉塞塔亲手从最贴近心口处褪下的一片真龙之鳞，珊瑚红的鳞面勾着一圈金纹，鳞心一簇微微跳动的琥珀火光。
 * <ul>
 *   <li>写着你名字的（绑定）：召唤她的信物。右键唤她破空而来或请她回鳞歇息，潜行右键让她留在原地或跟上。她在鳞里歇着（龙眠）时，
 *       物品上的冷却就是还要歇多久（不显示文字），指尖冒一缕带着余温的轻烟。</li>
 *   <li>空白的：已经和她立契的人拿着它右键她，她轻吹一口龙息烙下纹路，盖一枚鳞之契（用掉）。还没和她立契的人右键用它，这一片就成了他的，
 *       她第一次破空而来（初遇剧情做好之前的临时做法；创造模式「工具」页里有）。</li>
 * </ul>
 */
public class DragonScaleItem extends Item {
    private static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, LogosDimension.ID);

    /** 鳞上烙着的名字：主人的 UUID 和当时的名字（同形代）。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<KatashiroItem.Owner>> OWNER = COMPONENTS.registerComponentType(
            "dragon_scale_owner", b -> b.persistent(KatashiroItem.Owner.CODEC).networkSynchronized(KatashiroItem.Owner.STREAM_CODEC));
    public static final DeferredItem<DragonScaleItem> ITEM = DimensionBlocks.ITEMS.registerItem("dragon_scale", p -> new DragonScaleItem(p.stacksTo(16)));

    public DragonScaleItem(Properties properties) {
        super(properties);
    }

    static void register(IEventBus bus) {
        COMPONENTS.register(bus);
    }

    /** 烙着 {@code owner} 名字的一片。 */
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

    /** {@code player} 身上有没有烙着自己名字的一片。 */
    public static boolean carries(Player player) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) if (boundTo(inv.getItem(i), player)) return true;
        return false;
    }

    /** 她回到鳞里龙眠：鳞上转起冷却，转完就能再叫。 */
    static void resting(ServerPlayer p, int ticks) {
        if (ticks > 0) p.getCooldowns().addCooldown(blank(), ticks);
    }

    /** 上线时补上还没歇完的冷却（物品冷却不存档）。 */
    static void refresh(ServerPlayer p) {
        resting(p, (int) Companions.restLeft(p, Companions.LACERTA_DEF));
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (!(player instanceof ServerPlayer sp)) return InteractionResult.SUCCESS;
        ItemStack stack = player.getItemInHand(hand);
        CompanionDef d = Companions.LACERTA_DEF;
        KatashiroItem.Owner o = stack.get(OWNER.get());
        if (o == null) {
            // 还没和她立契：这一片成了他的，她第一次破空而来。已经立契了：空白的鳞是拿去给她盖鳞之契的
            if (Companions.bond(sp, d).pact()) {
                ember(sp, false);
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
            ember(sp, false);
            return InteractionResult.FAIL;
        }
        // 鳞在手，契就在（存档出过问题、或被命令清掉时补上）
        if (!Companions.bond(sp, d).pact()) Companions.grantPact(sp, d);
        CompanionEntity c = Companions.out(sp, d);
        if (c != null) {
            if (sp.isShiftKeyDown()) c.toggleStay(sp);
            else Companions.dismiss(sp, d);
        } else if (Companions.summon(sp, d, null) != null) {
            // 叫不出来（她还在龙眠）：指尖冒一缕带余温的轻烟，冷却转起来就是还要歇多久
            ember(sp, true);
            refresh(sp);
            return InteractionResult.FAIL;
        }
        sp.getCooldowns().addCooldown(stack, 20);
        return InteractionResult.SUCCESS;
    }

    /** 叫不出来、不是你的：指尖一缕带着余温的轻烟和几点火星，没有文字。 */
    private static void ember(ServerPlayer p, boolean resting) {
        if (!(p.level() instanceof ServerLevel level)) return;
        Vec3 hand = p.getEyePosition().add(p.getLookAngle().scale(.6)).add(0, -.4, 0);
        level.sendParticles(new DustParticleOptions(resting ? 0x8A5A4A : LacertaCompanion.CORAL, .7f), hand.x, hand.y, hand.z, 6, .1, .1, .1, 0);
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.SMOKE, hand.x, hand.y + .1, hand.z, 3, .05, .1, .05, .01);
        level.playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, .25f, resting ? 1.4f : 1.9f);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> out, TooltipFlag flag) {
        KatashiroItem.Owner o = stack.get(OWNER.get());
        if (o == null) {
            out.accept(Component.translatable("tooltip.logos_dimension.dragon_scale.blank").withStyle(ChatFormatting.GRAY));
            out.accept(Component.translatable("tooltip.logos_dimension.dragon_scale.blank_use").withStyle(ChatFormatting.DARK_GRAY));
            return;
        }
        out.accept(Component.translatable("tooltip.logos_dimension.dragon_scale.owner", o.name()).withStyle(ChatFormatting.GRAY));
        out.accept(Component.translatable("tooltip.logos_dimension.dragon_scale.use").withStyle(ChatFormatting.DARK_GRAY));
    }
}
