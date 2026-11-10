package dev.logos.dimension.music;

import dev.logos.dimension.DimensionBlocks;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.ServerConfig;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.jspecify.annotations.Nullable;

/**
 * 魔女之森乐器：方块、物品、纸带数据组件、复制配方、采样声音事件，以及服务端的网络处理
 * （实时演奏转发、播放控制、纸带上传与下载）。设计见 docs/HEXENWALD_INSTRUMENTS.md。
 */
public final class Instruments {
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
        DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, LogosDimension.ID);
    private static final DeferredRegister.DataComponents COMPONENTS =
        DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, LogosDimension.ID);
    private static final DeferredRegister<RecipeSerializer<?>> RECIPES =
        DeferredRegister.create(Registries.RECIPE_SERIALIZER, LogosDimension.ID);
    private static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, LogosDimension.ID);

    /** 演奏者离乐器中心多远以内才能弹、才能操作纸带。 */
    public static final double REACH = 8.0;

    public static final DeferredBlock<GrandPianoBlock> GRAND_PIANO = DimensionBlocks.BLOCKS.registerBlock(
        "starbloom_grand_piano", GrandPianoBlock::new,
        p -> p.mapColor(MapColor.QUARTZ).strength(2.5f).sound(SoundType.WOOD).noOcclusion().pushReaction(PushReaction.BLOCK));
    public static final DeferredBlock<ConcertHarpBlock> CONCERT_HARP = DimensionBlocks.BLOCKS.registerBlock(
        "hexen_concert_harp", ConcertHarpBlock::new,
        p -> p.mapColor(MapColor.GOLD).strength(1.5f).sound(SoundType.WOOD).noOcclusion().pushReaction(PushReaction.BLOCK));

    public static final DeferredBlock<dev.logos.dimension.music.organ.OrganConsoleBlock> ORGAN_CONSOLE = DimensionBlocks.BLOCKS.registerBlock(
        "hexen_organ_console", dev.logos.dimension.music.organ.OrganConsoleBlock::new,
        p -> p.mapColor(MapColor.COLOR_BROWN).strength(2.5f).sound(SoundType.WOOD).noOcclusion().pushReaction(PushReaction.BLOCK));
    public static final DeferredBlock<net.minecraft.world.level.block.Block> ORGAN_WIND_CHEST = DimensionBlocks.BLOCKS.registerBlock(
        "organ_wind_chest", net.minecraft.world.level.block.Block::new,
        p -> p.mapColor(MapColor.COLOR_BROWN).strength(2.0f).sound(SoundType.WOOD));
    public static final DeferredBlock<dev.logos.dimension.music.organ.OrganPipeBlock> ORGAN_TIN_PIPE = DimensionBlocks.BLOCKS.registerBlock(
        "organ_tin_pipe", p -> new dev.logos.dimension.music.organ.OrganPipeBlock(p, 3.5),
        p -> p.mapColor(MapColor.METAL).strength(2.0f).sound(SoundType.COPPER).noOcclusion());
    public static final DeferredBlock<dev.logos.dimension.music.organ.OrganPipeBlock> ORGAN_WOOD_PIPE = DimensionBlocks.BLOCKS.registerBlock(
        "organ_wood_pipe", p -> new dev.logos.dimension.music.organ.OrganPipeBlock(p, 4.0),
        p -> p.mapColor(MapColor.COLOR_BROWN).strength(1.5f).sound(SoundType.WOOD).noOcclusion());
    public static final DeferredBlock<dev.logos.dimension.music.organ.OrganTrumpetBlock> ORGAN_TRUMPET_PIPE = DimensionBlocks.BLOCKS.registerBlock(
        "organ_trumpet_pipe", dev.logos.dimension.music.organ.OrganTrumpetBlock::new,
        p -> p.mapColor(MapColor.GOLD).strength(2.0f).sound(SoundType.COPPER).noOcclusion());

    public static final DeferredItem<?> GRAND_PIANO_ITEM = DimensionBlocks.ITEMS.registerSimpleBlockItem("starbloom_grand_piano", GRAND_PIANO);
    public static final DeferredItem<?> CONCERT_HARP_ITEM = DimensionBlocks.ITEMS.registerSimpleBlockItem("hexen_concert_harp", CONCERT_HARP);

    public static final DeferredItem<?> ORGAN_CONSOLE_ITEM = DimensionBlocks.ITEMS.registerSimpleBlockItem("hexen_organ_console", ORGAN_CONSOLE);
    public static final DeferredItem<?> ORGAN_WIND_CHEST_ITEM = DimensionBlocks.ITEMS.registerSimpleBlockItem("organ_wind_chest", ORGAN_WIND_CHEST);
    public static final DeferredItem<?> ORGAN_TIN_PIPE_ITEM = DimensionBlocks.ITEMS.registerSimpleBlockItem("organ_tin_pipe", ORGAN_TIN_PIPE);
    public static final DeferredItem<?> ORGAN_WOOD_PIPE_ITEM = DimensionBlocks.ITEMS.registerSimpleBlockItem("organ_wood_pipe", ORGAN_WOOD_PIPE);
    public static final DeferredItem<?> ORGAN_TRUMPET_PIPE_ITEM = DimensionBlocks.ITEMS.registerSimpleBlockItem("organ_trumpet_pipe", ORGAN_TRUMPET_PIPE);

    public static final DeferredItem<Item> BLANK_ROLL = DimensionBlocks.ITEMS.registerItem("blank_music_roll", p -> new Item(p.stacksTo(16)));
    public static final DeferredItem<Item> MUSIC_ROLL_ITEM = DimensionBlocks.ITEMS.registerItem("music_roll", p -> new MusicRollItem(p.stacksTo(1)));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<InstrumentBlockEntity>> INSTRUMENT_ENTITY =
        BLOCK_ENTITIES.register("instrument", () -> new BlockEntityType<>(InstrumentBlockEntity::new, GRAND_PIANO.get(), CONCERT_HARP.get(), ORGAN_CONSOLE.get()));

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<RollInfo>> MUSIC_ROLL =
        COMPONENTS.registerComponentType("music_roll", b -> b.persistent(RollInfo.CODEC).networkSynchronized(RollInfo.STREAM_CODEC));

    public static final DeferredHolder<RecipeSerializer<?>, RecipeSerializer<RollCopyRecipe>> ROLL_COPY =
        RECIPES.register("music_roll_copy", () -> RollCopyRecipe.SERIALIZER);

    static {
        for (InstrumentKind kind : InstrumentKind.values()) {
            if (kind.synthesized()) continue;
            for (int s : kind.samples()) {
                var id = kind.soundId(s);
                SOUNDS.register(id.getPath(), () -> SoundEvent.createVariableRangeEvent(id));
            }
        }
    }

    public static void register(IEventBus bus) {
        BLOCK_ENTITIES.register(bus);
        COMPONENTS.register(bus);
        RECIPES.register(bus);
        SOUNDS.register(bus);
        bus.addListener(Instruments::payloads);
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent e) -> RollStore.open(e.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> {
            RollStore.close();
            uploads.clear();
            buckets.clear();
            uploadTimes.clear();
        });
        NeoForge.EVENT_BUS.addListener(Instruments::watch);
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> {
            uploads.remove(e.getEntity().getUUID());
            buckets.remove(e.getEntity().getUUID());
        });
    }

    public static boolean isRoll(ItemStack stack) {
        return stack.is(BLANK_ROLL.get()) || stack.is(MUSIC_ROLL_ITEM.get());
    }

    private static void payloads(RegisterPayloadHandlersEvent event) {
        var r = event.registrar("1");
        r.playToServer(MusicPayloads.LiveNotes.TYPE, MusicPayloads.LiveNotes.CODEC, (p, c) -> c.enqueueWork(() -> live(p, c)));
        r.playToServer(MusicPayloads.Control.TYPE, MusicPayloads.Control.CODEC, (p, c) -> c.enqueueWork(() -> control(p, c)));
        r.playToServer(MusicPayloads.RollRequest.TYPE, MusicPayloads.RollRequest.CODEC, (p, c) -> c.enqueueWork(() -> request(p, c)));
        r.playToServer(MusicPayloads.Upload.TYPE, MusicPayloads.Upload.CODEC, (p, c) -> c.enqueueWork(() -> upload(p, c)));
        r.playToClient(MusicPayloads.LiveNotesOut.TYPE, MusicPayloads.LiveNotesOut.CODEC);
        r.playToClient(MusicPayloads.Playback.TYPE, MusicPayloads.Playback.CODEC);
        r.playToClient(MusicPayloads.RollData.TYPE, MusicPayloads.RollData.CODEC);
    }

    public static boolean inReach(ServerPlayer player, InstrumentBlockEntity be) {
        return player.level() == be.getLevel() && player.distanceToSqr(be.soundCenter()) <= REACH * REACH;
    }

    private static @Nullable InstrumentBlockEntity reachable(ServerPlayer player, BlockPos pos) {
        if (!player.level().isLoaded(pos)) return null;
        InstrumentBlockEntity be = InstrumentBlock.entityAt(player.level(), pos);
        return be != null && inReach(player, be) ? be : null;
    }

    private static void tell(ServerPlayer player, String key, Object... args) {
        player.sendSystemMessage(Component.translatable("message.logos_dimension.instrument." + key, args), true);
    }

    // —— 实时演奏 ——

    /** 每个玩家的实时事件令牌桶：平均每秒 128 个，瞬间最多 64 个（一批的上限）。 */
    private static final Map<UUID, double[]> buckets = new HashMap<>();

    private static boolean take(ServerPlayer player, int n, long gameTime) {
        double[] b = buckets.computeIfAbsent(player.getUUID(), k -> new double[]{128, gameTime});
        b[0] = Math.min(128, b[0] + (gameTime - (long) b[1]) * 128 / 20.0);
        b[1] = gameTime;
        if (b[0] < n) return false;
        b[0] -= n;
        return true;
    }

    private static void live(MusicPayloads.LiveNotes p, IPayloadContext c) {
        if (!(c.player() instanceof ServerPlayer player)) return;
        InstrumentBlockEntity be = reachable(player, p.pos());
        if (be == null || !player.getUUID().equals(be.performer())) return;
        if (p.notes().isEmpty() || !take(player, p.notes().size(), player.level().getGameTime())) return;
        List<MusicPayloads.LiveNote> clean = new ArrayList<>(p.notes().size());
        for (MusicPayloads.LiveNote n : p.notes()) {
            if (n.pitch() > MusicPayloads.PEDAL || n.velocity() > 127) continue;
            clean.add(new MusicPayloads.LiveNote(Math.min(1000, n.offsetMs()), n.pitch(), n.velocity()));
        }
        if (clean.isEmpty()) return;
        Vec3 at = be.soundCenter();
        PacketDistributor.sendToPlayersNear((ServerLevel) player.level(), player, at.x, at.y, at.z, be.hearingRange(),
            new MusicPayloads.LiveNotesOut(be.getBlockPos(), player.getId(), false, clean));
    }

    /** 演奏者离开：让附近的人那里全部松开。 */
    static void release(InstrumentBlockEntity be, @Nullable ServerPlayer player) {
        be.setPerformer(null);
        if (be.getLevel() instanceof ServerLevel level) {
            Vec3 at = be.soundCenter();
            PacketDistributor.sendToPlayersNear(level, null, at.x, at.y, at.z, be.hearingRange(),
                new MusicPayloads.LiveNotesOut(be.getBlockPos(), player == null ? -1 : player.getId(), true, List.of()));
        }
    }

    // —— 操作 ——

    private static void control(MusicPayloads.Control p, IPayloadContext c) {
        if (!(c.player() instanceof ServerPlayer player)) return;
        InstrumentBlockEntity be = reachable(player, p.pos());
        if (be == null) return;
        switch (p.action()) {
            case MusicPayloads.Control.CLAIM -> {
                UUID current = be.performer();
                if (current == null || current.equals(player.getUUID())) {
                    be.setPerformer(player.getUUID());
                } else {
                    ServerPlayer other = player.level().getServer().getPlayerList().getPlayer(current);
                    if (other == null || !inReach(other, be)) {
                        be.setPerformer(player.getUUID());
                    } else {
                        tell(player, "busy", other.getDisplayName());
                    }
                }
            }
            case MusicPayloads.Control.RELEASE -> {
                if (player.getUUID().equals(be.performer())) release(be, player);
            }
            case MusicPayloads.Control.PLAY -> {
                if (!be.play()) tell(player, "no_roll");
            }
            case MusicPayloads.Control.PAUSE -> be.pause();
            case MusicPayloads.Control.STOP -> be.stopPlayback();
            case MusicPayloads.Control.EJECT -> {
                ItemStack out = be.takeRoll();
                if (!out.isEmpty() && !player.getInventory().add(out)) player.drop(out, false);
            }
            case MusicPayloads.Control.PART_MASK -> be.setPartMask(p.arg());
            case MusicPayloads.Control.SIGNAL_MODE -> be.setStopWithSignal(p.arg() != 0);
            case MusicPayloads.Control.STOPS -> {
                if (be.kind() == InstrumentKind.PIPE_ORGAN) be.setStops(p.arg());
            }
            case MusicPayloads.Control.REVERB -> {
                if (be.kind() == InstrumentKind.PIPE_ORGAN) be.setReverbMode(p.arg());
            }
            default -> { }
        }
    }

    // —— 纸带下载 ——

    private static void request(MusicPayloads.RollRequest p, IPayloadContext c) {
        if (!(c.player() instanceof ServerPlayer player)) return;
        // 和实时演奏共用令牌桶：一次请求算 16 个事件，避免被刷
        if (!take(player, 16, player.level().getGameTime())) return;
        byte[] data = RollStore.load(p.id());
        PacketDistributor.sendToPlayer(player, new MusicPayloads.RollData(p.id(), data == null ? new byte[0] : data));
    }

    // —— 纸带上传 ——

    private static final class Session {
        final int id, total, purpose;
        final BlockPos pos;
        final byte[][] chunks;
        int received, bytes;
        final long started;

        Session(int id, int total, int purpose, BlockPos pos, long started) {
            this.id = id;
            this.total = total;
            this.purpose = purpose;
            this.pos = pos;
            this.chunks = new byte[total][];
            this.started = started;
        }
    }

    private static final Map<UUID, Session> uploads = new HashMap<>();
    private static final Map<UUID, ArrayDeque<Long>> uploadTimes = new HashMap<>();

    private static void upload(MusicPayloads.Upload p, IPayloadContext c) {
        if (!(c.player() instanceof ServerPlayer player)) return;
        long now = player.level().getGameTime();
        Session s = uploads.get(player.getUUID());
        if (s == null || s.id != p.uploadId() || now - s.started > 20 * 30) {
            if (p.total() < 1 || p.total() > MusicPayloads.MAX_UPLOAD_CHUNKS || p.index() != 0) {
                uploads.remove(player.getUUID());
                return;
            }
            s = new Session(p.uploadId(), p.total(), p.purpose(), p.pos(), now);
            uploads.put(player.getUUID(), s);
        }
        if (p.index() < 0 || p.index() >= s.total || p.total() != s.total || s.chunks[p.index()] != null) {
            uploads.remove(player.getUUID());
            return;
        }
        s.bytes += p.chunk().length;
        if (s.bytes > MusicRoll.MAX_BYTES) {
            uploads.remove(player.getUUID());
            tell(player, "too_big");
            return;
        }
        s.chunks[p.index()] = p.chunk();
        if (++s.received < s.total) return;
        uploads.remove(player.getUUID());
        byte[] all = new byte[s.bytes];
        int at = 0;
        for (byte[] chunk : s.chunks) {
            System.arraycopy(chunk, 0, all, at, chunk.length);
            at += chunk.length;
        }
        finish(player, s.purpose, s.pos, all, now);
    }

    private static void finish(ServerPlayer player, int purpose, BlockPos pos, byte[] data, long now) {
        boolean op = player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
        if (!ServerConfig.INSTRUMENTS_ALLOW_UPLOAD.get() && !op) {
            tell(player, "upload_disabled");
            return;
        }
        ArrayDeque<Long> times = uploadTimes.computeIfAbsent(player.getUUID(), k -> new ArrayDeque<>());
        while (!times.isEmpty() && now - times.peekFirst() > 20 * 60) times.pollFirst();
        if (!op && times.size() >= ServerConfig.INSTRUMENTS_UPLOADS_PER_MINUTE.get()) {
            tell(player, "upload_rate");
            return;
        }
        InstrumentBlockEntity be = reachable(player, pos);
        if (be == null) return;
        MusicRoll roll;
        byte[] encoded;
        try {
            roll = MusicRoll.decode(data);
            if (roll.author().isEmpty()) roll = roll.withMeta(roll.title(), player.getGameProfile().name());
            encoded = roll.encode();
        } catch (MusicRoll.FormatException e) {
            tell(player, "bad_roll", e.getMessage());
            return;
        }
        long hash = MusicRoll.hash(encoded);
        if (purpose == MusicPayloads.Upload.TEMPORARY) {
            times.addLast(now);
            UUID id = RollStore.saveTemporary(encoded);
            be.playTemporary(id, hash, roll.durationMs());
            return;
        }
        if (!isRoll(be.roll())) {
            tell(player, "need_blank");
            return;
        }
        UUID id;
        try {
            id = RollStore.save(encoded);
        } catch (IOException e) {
            tell(player, "save_failed");
            return;
        }
        times.addLast(now);
        ItemStack punched = new ItemStack(MUSIC_ROLL_ITEM.get());
        punched.set(MUSIC_ROLL.get(), RollInfo.of(id, roll, hash));
        be.setRoll(punched);
        tell(player, "written", roll.title().isEmpty() ? "—" : roll.title());
    }

    // —— 后来的人 ——

    private static void watch(ChunkWatchEvent.Watch event) {
        for (BlockEntity be : event.getChunk().getBlockEntities().values()) {
            if (be instanceof InstrumentBlockEntity instrument && instrument.hasPlayback()) instrument.sendState(event.getPlayer());
        }
    }

    private Instruments() {}
}
