package dev.logos.dimension.companion;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.logos.dimension.DimensionBlocks;
import dev.logos.dimension.LogosDimension;
import io.netty.buffer.ByteBuf;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 白梅的形代（docs/SHIRAUME_COMPANION.md 第 8.3 节）：她用断掉的御札折的纸人，是她的信物。
 * <ul>
 *   <li>写着名字的（绑定）：立契时给的，召唤她的道具。右键叫她出来或收回，潜行右键让她待命或跟上；
 *   <li>空白的：已经立契的人再打倒一块杀生石的残影时得到，拿着它右键她就盖一枚契印（用掉）；可以送给别的已经立契的人。
 * </ul>
 */
public class KatashiroItem extends Item {
    private static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, LogosDimension.ID);

    /** 写在形代上的名字：主人的 UUID 和当时的名字（给说明看）。 */
    public record Owner(UUID id, String name) {
        static final Codec<Owner> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.fieldOf("id").forGetter(Owner::id), Codec.STRING.fieldOf("name").forGetter(Owner::name)).apply(i, Owner::new));
        static final StreamCodec<ByteBuf, Owner> STREAM_CODEC = StreamCodec.composite(UUIDUtil.STREAM_CODEC, Owner::id,
                ByteBufCodecs.STRING_UTF8, Owner::name, Owner::new);
    }

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Owner>> OWNER = COMPONENTS.registerComponentType(
            "katashiro_owner", b -> b.persistent(Owner.CODEC).networkSynchronized(Owner.STREAM_CODEC));
    public static final DeferredItem<KatashiroItem> ITEM = DimensionBlocks.ITEMS.registerItem("shiraume_katashiro",
            p -> new KatashiroItem(p.stacksTo(16)));

    public KatashiroItem(Properties properties) {
        super(properties);
    }

    static void register(IEventBus bus) {
        COMPONENTS.register(bus);
    }

    /** 写着 {@code owner} 名字的形代。 */
    public static ItemStack bound(Player owner) {
        ItemStack stack = new ItemStack(ITEM.get());
        stack.set(OWNER.get(), new Owner(owner.getUUID(), owner.getName().getString()));
        return stack;
    }

    public static ItemStack blank() {
        return new ItemStack(ITEM.get());
    }

    public static boolean isBlank(ItemStack stack) {
        return stack.is(ITEM.get()) && !stack.has(OWNER.get());
    }

    public static boolean boundTo(ItemStack stack, Player player) {
        Owner o = stack.is(ITEM.get()) ? stack.get(OWNER.get()) : null;
        return o != null && o.id().equals(player.getUUID());
    }

    /** {@code player} 身上有没有写着自己名字的形代。 */
    public static boolean carries(Player player) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) if (boundTo(inv.getItem(i), player)) return true;
        return false;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (!(player instanceof ServerPlayer sp)) return InteractionResult.SUCCESS;
        ItemStack stack = player.getItemInHand(hand);
        Owner o = stack.get(OWNER.get());
        if (o == null) {
            sp.sendOverlayMessage(Component.translatable(sp.getData(Companions.BOND).pact()
                    ? "message.logos_dimension.katashiro.blank_hint" : "message.logos_dimension.katashiro.unknown"));
            return InteractionResult.CONSUME;
        }
        if (!o.id().equals(sp.getUUID())) {
            sp.sendOverlayMessage(Component.translatable("message.logos_dimension.katashiro.not_yours", o.name()));
            return InteractionResult.FAIL;
        }
        // 形代在手，契就在（存档出过问题、或被命令清掉时补上）
        if (!sp.getData(Companions.BOND).pact()) Companions.grantPact(sp);
        ShiraumeCompanion c = Companions.out(sp);
        if (c != null) {
            if (sp.isShiftKeyDown()) c.toggleStay(sp);
            else Companions.dismiss(sp);
        } else {
            Component failed = Companions.summon(sp, false);
            if (failed != null) sp.sendOverlayMessage(failed);
        }
        sp.getCooldowns().addCooldown(stack, 20);
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, TooltipDisplay display, Consumer<Component> out, TooltipFlag flag) {
        Owner o = stack.get(OWNER.get());
        if (o == null) {
            out.accept(Component.translatable("tooltip.logos_dimension.shiraume_katashiro.blank").withStyle(ChatFormatting.GRAY));
            out.accept(Component.translatable("tooltip.logos_dimension.shiraume_katashiro.blank_use").withStyle(ChatFormatting.DARK_GRAY));
            return;
        }
        out.accept(Component.translatable("tooltip.logos_dimension.shiraume_katashiro.owner", o.name()).withStyle(ChatFormatting.GRAY));
        out.accept(Component.translatable("tooltip.logos_dimension.shiraume_katashiro.use").withStyle(ChatFormatting.DARK_GRAY));
    }
}
