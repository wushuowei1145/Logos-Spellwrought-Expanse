package dev.logos.dimension.music;

import dev.logos.dimension.DimensionBlocks;
import dev.logos.dimension.LogosDimension;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * 星辰花琴凳的注册与生物那一边（docs/ORCHESTRA.md 第 14 节）：
 * 拿着手持乐器右键坐在琴凳上的生物，乐器交到它主手上（原来拿着的东西先替它收着，必定掉落、不会丢）；
 * 空手右键它，把乐器拿回来，原来的东西还给它。
 */
public final class Benches {
    private static final DeferredRegister<EntityType<?>> ENTITY_TYPES = DeferredRegister.create(Registries.ENTITY_TYPE, LogosDimension.ID);
    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, LogosDimension.ID);

    public static final DeferredBlock<StarbloomBenchBlock> BENCH = DimensionBlocks.BLOCKS.registerBlock("starbloom_bench", StarbloomBenchBlock::new,
        p -> p.mapColor(MapColor.COLOR_RED).strength(1.0f).sound(SoundType.WOOD).noOcclusion());
    public static final DeferredItem<?> BENCH_ITEM = DimensionBlocks.ITEMS.registerSimpleBlockItem("starbloom_bench", BENCH);
    public static final DeferredHolder<EntityType<?>, EntityType<BenchSeat>> SEAT = ENTITY_TYPES.register("bench_seat",
        () -> EntityType.Builder.<BenchSeat>of(BenchSeat::new, MobCategory.MISC).sized(0.25f, 0.25f).noSummon().clientTrackingRange(10)
            .updateInterval(20).build(ResourceKey.create(Registries.ENTITY_TYPE, LogosDimension.id("bench_seat"))));
    /** 生物主手原来拿着的东西：交给它乐器时替它收着，拿回乐器时还给它，死了照样掉出来。 */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<ItemStack>> STOWED = ATTACHMENTS.register("stowed_main_hand",
        () -> AttachmentType.builder(() -> ItemStack.EMPTY).serialize(ItemStack.OPTIONAL_CODEC.fieldOf("item"), s -> !s.isEmpty()).build());

    private Benches() {}

    public static void register(IEventBus bus) {
        ENTITY_TYPES.register(bus);
        ATTACHMENTS.register(bus);
        NeoForge.EVENT_BUS.addListener(Benches::interact);
        NeoForge.EVENT_BUS.addListener(Benches::drops);
    }

    /** 右键坐在琴凳上的生物：拿着手持乐器时交给它，空手时把它的乐器拿回来。两边都取消，免得生物自己的交互（交易、对话）也跑一遍。 */
    private static void interact(PlayerInteractEvent.EntityInteract event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        if (!(event.getTarget() instanceof Mob mob) || !(mob.getVehicle() instanceof BenchSeat seat)) return;
        Player player = event.getEntity();
        ItemStack held = player.getMainHandItem();
        boolean give = held.getItem() instanceof HandheldInstrumentItem;
        boolean take = held.isEmpty() && mob.getMainHandItem().getItem() instanceof HandheldInstrumentItem;
        if (!give && !take) return;
        if (!event.getLevel().isClientSide()) {
            if (give) give(mob, held, player);
            else takeBack(mob, player);
            StarbloomBenchBlock.refresh(event.getLevel(), seat.bench());
        }
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.SUCCESS);
    }

    /**
     * 把一件手持乐器交到生物主手上；它原来拿着乐器就把那件还给玩家，拿着别的就替它收着
     * （已经替它收着一件时——例如僵尸自己捡起了别的东西——手里这件掉在它脚下，不会丢）。
     */
    public static void give(Mob mob, ItemStack instrument, Player from) {
        ItemStack old = mob.getMainHandItem();
        if (old.getItem() instanceof HandheldInstrumentItem) {
            if (!from.getInventory().add(old.copy())) from.drop(old.copy(), false);
        } else if (!old.isEmpty()) {
            if (mob.getData(STOWED).isEmpty()) mob.setData(STOWED, old.copy());
            else if (mob.level() instanceof ServerLevel level) mob.spawnAtLocation(level, old.copy());
        }
        mob.setItemSlot(EquipmentSlot.MAINHAND, instrument.copyWithCount(1));
        mob.setGuaranteedDrop(EquipmentSlot.MAINHAND);
        if (!from.getAbilities().instabuild) instrument.shrink(1);
    }

    /** 把生物手里的乐器拿回来，替它收着的东西还给它。 */
    public static void takeBack(Mob mob, Player to) {
        ItemStack instrument = mob.getMainHandItem().copy();
        ItemStack stowed = mob.getData(STOWED);
        mob.setItemSlot(EquipmentSlot.MAINHAND, stowed.copy());
        mob.setDropChance(EquipmentSlot.MAINHAND, 0.085f);
        mob.removeData(STOWED);
        if (!to.getInventory().add(instrument)) to.drop(instrument, false);
    }

    /** 替生物收着的东西：死的时候照样掉出来。 */
    private static void drops(LivingDropsEvent event) {
        if (!(event.getEntity() instanceof Mob mob) || !mob.hasData(STOWED)) return;
        ItemStack stowed = mob.getData(STOWED);
        if (stowed.isEmpty()) return;
        event.getDrops().add(new ItemEntity(mob.level(), mob.getX(), mob.getY(), mob.getZ(), stowed.copy()));
        mob.removeData(STOWED);
    }
}
