package dev.logos.dimension.music.orchestra;

import dev.logos.dimension.DimensionBlocks;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.music.Instruments;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 乐团：指挥台、指挥棒的注册与服务端的处理（docs/ORCHESTRA.md）。 */
public final class Orchestra {
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
        DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, LogosDimension.ID);
    private static final DeferredRegister.DataComponents COMPONENTS =
        DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, LogosDimension.ID);

    public static final DeferredBlock<PodiumBlock> PODIUM = DimensionBlocks.BLOCKS.registerBlock("hexen_podium", PodiumBlock::new,
        p -> p.mapColor(MapColor.COLOR_BROWN).strength(2.0f).sound(SoundType.WOOD).noOcclusion());
    public static final DeferredItem<?> PODIUM_ITEM = DimensionBlocks.ITEMS.registerSimpleBlockItem("hexen_podium", PODIUM);
    public static final DeferredItem<BatonItem> BATON = DimensionBlocks.ITEMS.registerItem("hexen_baton", p -> new BatonItem(p.stacksTo(1)));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PodiumBlockEntity>> PODIUM_ENTITY =
        BLOCK_ENTITIES.register("podium", () -> new BlockEntityType<>(PodiumBlockEntity::new, PODIUM.get()));
    /** 指挥棒绑定的指挥台。 */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<GlobalPos>> BATON_PODIUM =
        COMPONENTS.registerComponentType("baton_podium", b -> b.persistent(GlobalPos.CODEC).networkSynchronized(GlobalPos.STREAM_CODEC));

    private Orchestra() {}

    public static void register(IEventBus bus) {
        BLOCK_ENTITIES.register(bus);
        COMPONENTS.register(bus);
        bus.addListener(Orchestra::payloads);
        // 指挥状态下左键不攻击生物、不挖方块：客户端已经不发，这里防别的客户端或延迟
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.player.AttackEntityEvent e) -> {
            if (PodiumBlock.conducting(e.getEntity())) e.setCanceled(true);
        });
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock e) -> {
            if (PodiumBlock.conducting(e.getEntity())) e.setCanceled(true);
        });
    }

    private static void payloads(RegisterPayloadHandlersEvent event) {
        var r = event.registrar("1");
        r.playToServer(EnsemblePayloads.PodiumControl.TYPE, EnsemblePayloads.PodiumControl.CODEC, (p, c) -> c.enqueueWork(() -> control(p, c)));
        r.playToClient(EnsemblePayloads.EnsembleState.TYPE, EnsemblePayloads.EnsembleState.CODEC);
    }

    public static void tell(Player player, String key, Object... args) {
        if (player instanceof ServerPlayer sp) sp.sendSystemMessage(Component.translatable("message.logos_dimension.orchestra." + key, args), true);
    }

    private static void control(EnsemblePayloads.PodiumControl p, IPayloadContext c) {
        if (!(c.player() instanceof ServerPlayer player) || !player.level().isLoaded(p.podium())) return;
        PodiumBlockEntity be = PodiumBlock.entityAt(player.level(), p.podium());
        if (be == null || player.distanceToSqr(be.standAt()) > Instruments.REACH * Instruments.REACH) return;
        switch (p.action()) {
            case EnsemblePayloads.PodiumControl.START -> {
                if (!be.isPlaying() && !be.start(p.a())) tell(player, "cannot_start");
            }
            case EnsemblePayloads.PodiumControl.STOP -> be.stop();
            case EnsemblePayloads.PodiumControl.GATHER -> {
                if (be.isPlaying()) tell(player, "busy");
                else tell(player, "gathered", be.gather(), be.members().size());
            }
            case EnsemblePayloads.PodiumControl.REASSIGN -> be.reassign();
            case EnsemblePayloads.PodiumControl.ASSIGN -> be.assign(p.a(), p.b());
            case EnsemblePayloads.PodiumControl.UNLOCK -> be.unlock(p.a());
            case EnsemblePayloads.PodiumControl.REMOVE -> be.remove(p.a());
            case EnsemblePayloads.PodiumControl.TEMPO -> be.setTempo(p.a());
            case EnsemblePayloads.PodiumControl.GAIN -> be.setGain(p.a(), p.b());
            case EnsemblePayloads.PodiumControl.MUTE -> be.toggleMute(p.a());
            case EnsemblePayloads.PodiumControl.SOLO -> be.toggleSolo(p.a());
            case EnsemblePayloads.PodiumControl.EJECT -> {
                if (be.isPlaying()) {
                    tell(player, "busy");
                    return;
                }
                ItemStack out = be.takeScore();
                if (!out.isEmpty() && !player.getInventory().add(out)) player.drop(out, false);
            }
            default -> {}
        }
    }

    /** 指挥棒右键指挥台：没绑定这座时绑定；绑定了就开始 / 停止演奏。 */
    public static void batonOnPodium(ItemStack stack, ServerLevel level, BlockPos main, Player player) {
        GlobalPos here = GlobalPos.of(level.dimension(), main);
        if (!here.equals(stack.get(BATON_PODIUM.get()))) {
            stack.set(BATON_PODIUM.get(), here);
            tell(player, "bound");
            return;
        }
        if (!(level.getBlockEntity(main) instanceof PodiumBlockEntity be)) return;
        if (be.isPlaying()) {
            be.stop();
            tell(player, "stopped");
        } else if (be.start(0)) {
            tell(player, "started");
        } else {
            tell(player, be.score().isEmpty() ? "no_score" : "cannot_start");
        }
    }

    /** 指挥棒右键乐器：加进（或移出）指挥棒绑定的乐团。 */
    public static void batonOnInstrument(ItemStack stack, ServerLevel level, BlockPos instrument, Player player) {
        GlobalPos bound = stack.get(BATON_PODIUM.get());
        if (bound == null || !bound.dimension().equals(level.dimension())) {
            tell(player, "unbound");
            return;
        }
        if (!level.isLoaded(bound.pos()) || !(level.getBlockEntity(bound.pos()) instanceof PodiumBlockEntity be)) {
            tell(player, "podium_gone");
            return;
        }
        if (be.isPlaying()) {
            tell(player, "busy");
            return;
        }
        tell(player, be.toggle(instrument), be.members().size(), PodiumBlockEntity.MAX_MEMBERS);
    }
}
