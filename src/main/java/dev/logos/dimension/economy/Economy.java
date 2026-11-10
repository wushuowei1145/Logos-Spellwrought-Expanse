package dev.logos.dimension.economy;

import dev.logos.dimension.LogosDimension;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import dev.logos.dimension.cloudsea.JourneySeasons;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.TriState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 异世界经济的注册与服务端运行：钱币、万国钱囊、兑换台，市场随主世界时钟推进，
 * 打开面板的玩家每五秒收到一次行情。设计说明见 docs/ECONOMY.md。
 */
public final class Economy {
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(LogosDimension.ID);
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(LogosDimension.ID);
    private static final DeferredRegister.DataComponents COMPONENTS = DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, LogosDimension.ID);

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<WalletContents>> WALLET = COMPONENTS.registerComponentType(
            "wallet", b -> b.persistent(WalletContents.CODEC).networkSynchronized(WalletContents.STREAM_CODEC));
    private static final Map<Currency, List<DeferredItem<CoinItem>>> COINS = new EnumMap<>(Currency.class);
    public static final DeferredItem<WalletItem> WALLET_ITEM = ITEMS.registerItem("wallet", WalletItem::new,
            p -> p.stacksTo(1).fireResistant().rarity(Rarity.UNCOMMON));
    public static final DeferredBlock<ExchangeCounterBlock> EXCHANGE = BLOCKS.registerBlock("exchange_counter", ExchangeCounterBlock::new,
            p -> p.mapColor(MapColor.WOOD).strength(2.5f).sound(SoundType.WOOD));
    public static final DeferredBlock<BankBlock> BANK = BLOCKS.registerBlock("bank_counter", BankBlock::new,
            p -> p.mapColor(MapColor.COLOR_RED).strength(3.5f).sound(SoundType.WOOD));
    static {
        for (var c : Currency.values()) {
            var list = new ArrayList<DeferredItem<CoinItem>>();
            for (var d : c.denominations) list.add(ITEMS.registerItem(c.id + "_" + d.name(), p -> new CoinItem(p, c, d)));
            COINS.put(c, list);
        }
        ITEMS.registerSimpleBlockItem("exchange_counter", EXCHANGE);
        ITEMS.registerSimpleBlockItem("bank_counter", BANK);
    }

    /** 货币页的内容：钱囊、兑换台、钱庄，再按国别列钱币。页本身由 {@code AddonTabs} 注册。 */
    public static void creativeItems(CreativeModeTab.Output out) {
        out.accept(WALLET_ITEM.get());
        out.accept(EXCHANGE.get());
        out.accept(BANK.get());
        COINS.values().forEach(list -> list.forEach(out::accept));
    }

    public static CoinItem coin(Currency c, int denomination) { return COINS.get(c).get(denomination).get(); }

    public static void register(IEventBus bus) {
        ITEMS.register(bus); BLOCKS.register(bus); COMPONENTS.register(bus);
        bus.addListener((RegisterPayloadHandlersEvent event) -> event.registrar("1")
                .playToClient(MarketPayload.TYPE, MarketPayload.CODEC)
                .playToClient(BankPayload.TYPE, BankPayload.CODEC)
                .playToClient(RailgunPayload.TYPE, RailgunPayload.CODEC)
                .playToClient(TickerPayload.TYPE, TickerPayload.CODEC)
                .playToServer(EconomyActionPayload.TYPE, EconomyActionPayload.CODEC, Economy::handle)
                .playToServer(BankOrderPayload.TYPE, BankOrderPayload.CODEC, Economy::handleBank));
        NeoForge.EVENT_BUS.addListener(Economy::tick);
        NeoForge.EVENT_BUS.addListener(Economy::pickup);
        NeoForge.EVENT_BUS.addListener(Railgun::sweep);
        NeoForge.EVENT_BUS.addListener(Railgun::stopped);
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> { WATCHERS.remove(e.getEntity().getUUID()); TICKER.remove(e.getEntity().getUUID()); });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent e) -> { if (e.getEntity() instanceof ServerPlayer player) Bank.login(player); });
        NeoForge.EVENT_BUS.addListener(EconomyCommands::register);
    }

    // ---------------------------------------------------------------- 面板与行情推送

    /** 谁开着哪个面板：兑换台和钱庄记方块位置，钱囊记哪只手。 */
    private record Watch(int screen, int hand, BlockPos pos) {}
    private static final Map<UUID, Watch> WATCHERS = new HashMap<>();
    /** 打开了屏幕角落行情条的玩家；身上有钱囊时才推送。 */
    private static final java.util.Set<UUID> TICKER = new java.util.HashSet<>();
    private static final double REACH = 8;

    /**
     * 市场、钱庄和兑换商用远行大陆的时钟：经济在那里，玩家看到的天色也是它。
     * 26.1 起每个维度可以有自己的时钟，在远行大陆里执行 /time set 只改它；以前读主世界时钟，柜台上的时间就对不上。
     */
    public static long clock(MinecraftServer server) {
        var clock = server.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).get(JourneySeasons.CLOCK);
        return clock.isPresent() ? server.clockManager().getTotalTicks(clock.get()) : server.overworld().getOverworldClockTime();
    }

    /** 让市场追到当前时刻；时钟往回拨过就把钱庄里记的时间一起挪。 */
    public static FxMarket market(MinecraftServer server) {
        var market = FxMarketData.market(server);
        long before = market.last();
        long shift = market.advance(clock(server));
        if (shift != 0) Bank.rebase(server, shift);
        if (market.last() != before) FxMarketData.touch(server);
        return market;
    }

    /** 兑换台、钱囊面板：行情快照后面跟一份账户快照（不打开钱庄），用来显示挂单、持仓和提醒。 */
    static void send(ServerPlayer player, int screen, int hand, BlockPos pos) {
        var server = player.level().getServer();
        PacketDistributor.sendToPlayer(player, MarketPayload.of(market(server), screen, hand, pos, clock(server)),
                BankPayload.of(Bank.of(server).account(player.getUUID()), false, pos));
    }

    /** 钱庄面板：先发行情（只刷新），再发账户；open 时客户端打开钱庄。 */
    static void sendBank(ServerPlayer player, BlockPos pos, boolean open) {
        var server = player.level().getServer();
        PacketDistributor.sendToPlayer(player, MarketPayload.of(market(server), MarketPayload.UPDATE, 0, pos, clock(server)),
                BankPayload.of(Bank.of(server).account(player.getUUID()), open, pos));
    }

    public static void openBank(ServerPlayer player, BlockPos pos) {
        WATCHERS.put(player.getUUID(), new Watch(BANK_SCREEN, 0, pos.immutable()));
        Bank.seen(player);
        sendBank(player, pos, true);
    }
    private static final int BANK_SCREEN = 3;

    public static void openExchange(ServerPlayer player, BlockPos pos) {
        WATCHERS.put(player.getUUID(), new Watch(MarketPayload.EXCHANGE, 0, pos.immutable()));
        send(player, MarketPayload.EXCHANGE, 0, pos);
    }

    public static void openWallet(ServerPlayer player, InteractionHand hand) {
        WATCHERS.put(player.getUUID(), new Watch(MarketPayload.WALLET, hand.ordinal(), BlockPos.ZERO));
        send(player, MarketPayload.WALLET, hand.ordinal(), BlockPos.ZERO);
    }

    private static void tick(ServerTickEvent.Post event) {
        var server = event.getServer();
        if (server.getTickCount() % FxMarket.STEP != 0) return;
        Bank.update(server, market(server), clock(server));
        for (var it = WATCHERS.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            var player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) { it.remove(); continue; }
            var w = entry.getValue();
            if (w.screen == BANK_SCREEN) sendBank(player, w.pos, false);
            else send(player, MarketPayload.UPDATE, w.hand, w.pos);
        }
        for (var player : server.getPlayerList().getPlayers()) milestones(player);
        for (var id : TICKER) {
            var player = server.getPlayerList().getPlayer(id);
            if (player != null && !Purse.wallet(player).isEmpty())
                PacketDistributor.sendToPlayer(player, TickerPayload.of(market(server), Bank.of(server).account(id)));
        }
    }

    // ---------------------------------------------------------------- 剧情与探索推动的基本面

    private static final ResourceKey<Structure> FOX_SANCTUARY = ResourceKey.create(Registries.STRUCTURE, LogosDimension.id("fox_sanctuary"));

    /** 第一位走进神狐聚落的玩家打开了与狐族的通商，狐钱基本面永久上调。 */
    private static void milestones(ServerPlayer player) {
        var level = player.level();
        if (level.dimension() != LogosDimension.CLOUDSEA) return;
        var server = level.getServer();
        var market = FxMarketData.market(server);
        if (market.discovered.contains("fox_sanctuary")) return;
        var structure = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getValue(FOX_SANCTUARY);
        if (structure == null || !level.structureManager().getStructureAt(player.blockPosition(), structure).isValid()) return;
        if (market.milestone("fox_sanctuary", Currency.FOX, .03, clock(server))) FxMarketData.touch(server);
    }

    // ---------------------------------------------------------------- 捡钱入囊

    private static void pickup(ItemEntityPickupEvent.Pre event) {
        var entity = event.getItemEntity();
        var player = event.getPlayer();
        if (player.level().isClientSide() || !(entity.getItem().getItem() instanceof CoinItem) || entity.hasPickUpDelay()) return;
        var wallet = Purse.wallet(player);
        if (wallet.isEmpty() || !Purse.contents(wallet).autoCollect()) return;
        var stack = entity.getItem().copy();
        int count = stack.getCount();
        if (!Purse.deposit(wallet, stack)) return;
        player.take(entity, count);
        entity.discard();
        WalletItem.ding(player);
        event.setCanPickup(TriState.FALSE);
    }

    // ---------------------------------------------------------------- 面板操作

    private static void handle(EconomyActionPayload p, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        if (p.action() == EconomyActionPayload.CLOSE) { WATCHERS.remove(player.getUUID()); return; }
        if (p.action() == EconomyActionPayload.TICKER) { if (p.amount() > 0) TICKER.add(player.getUUID()); else TICKER.remove(player.getUUID()); return; }
        var watch = WATCHERS.get(player.getUUID());
        if (p.action() == EconomyActionPayload.TRADE) {
            if (watch == null || watch.screen != MarketPayload.EXCHANGE) return;
            trade(player, watch.pos, p.a(), p.b(), p.amount());
            send(player, MarketPayload.UPDATE, 0, watch.pos);
            return;
        }
        if (p.hand() < 0 || p.hand() > 1) return;
        var wallet = player.getItemInHand(InteractionHand.values()[p.hand()]);
        if (!(wallet.getItem() instanceof WalletItem)) return;
        var currencies = Currency.values();
        switch (p.action()) {
            case EconomyActionPayload.WITHDRAW -> {
                if (p.a() < 0 || p.a() >= currencies.length) return;
                if (Purse.withdraw(player, wallet, currencies[p.a()], p.b(), (int) Math.clamp(p.amount(), 1, 64)) > 0) WalletItem.ding(player);
            }
            case EconomyActionPayload.DEPOSIT -> { if (Purse.depositAll(player, wallet) > 0) WalletItem.ding(player); }
            case EconomyActionPayload.LOCK -> {
                if (p.a() < 0 || p.a() >= currencies.length) return;
                Purse.set(wallet, Purse.contents(wallet).toggleLock(currencies[p.a()]));
            }
            case EconomyActionPayload.AUTO -> Purse.set(wallet, Purse.contents(wallet).withAutoCollect(!Purse.contents(wallet).autoCollect()));
            default -> {}
        }
    }

    /**
     * 账户操作分三类：存取与持仓管理要在钱庄柜台旁，现货挂单要在兑换台旁，撤单和价格提醒开着哪个面板都行。
     */
    private static void handleBank(BankOrderPayload p, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        var watch = WATCHERS.get(player.getUUID());
        if (watch == null) return;
        var currencies = Currency.values();
        if (p.currency() < 0 || p.currency() >= currencies.length) return;
        var c = currencies[p.currency()];
        int a = p.action();
        boolean anywhere = a == BankOrderPayload.CANCEL || a == BankOrderPayload.ALERT || a == BankOrderPayload.UNALERT;
        if (!anywhere) {
            var need = a == BankOrderPayload.LIMIT_SPOT ? MarketPayload.EXCHANGE : BANK_SCREEN;
            var block = a == BankOrderPayload.LIMIT_SPOT ? EXCHANGE.get() : (net.minecraft.world.level.block.Block) BANK.get();
            if (watch.screen != need) return;
            if (!player.level().getBlockState(watch.pos).is(block) || player.distanceToSqr(watch.pos.getCenter()) > REACH * REACH) {
                player.sendOverlayMessage(Component.translatable("message.logos_dimension.fx.too_far"));
                return;
            }
        }
        switch (a) {
            case BankOrderPayload.DEPOSIT -> Bank.deposit(player, c, p.amount());
            case BankOrderPayload.WITHDRAW -> Bank.withdraw(player, c, p.amount());
            case BankOrderPayload.OPEN -> Bank.open(player, c, p.side(), p.amount(), p.leverage(), p.stop(), p.take(), p.trail());
            case BankOrderPayload.CLOSE -> Bank.close(player, p.id());
            case BankOrderPayload.REDUCE -> Bank.reduce(player, p.id(), Math.clamp(p.amount(), 1, 1000) / 1000.0);
            case BankOrderPayload.CLOSE_ALL -> Bank.closeAll(player);
            case BankOrderPayload.ADD_MARGIN -> Bank.addMargin(player, p.id(), p.amount());
            case BankOrderPayload.INCREASE -> Bank.increase(player, p.id(), p.amount(), p.leverage());
            case BankOrderPayload.MODIFY -> Bank.modify(player, p.id(), p.stop(), p.take(), p.trail());
            case BankOrderPayload.LIMIT_OPEN -> Bank.placeOpen(player, c, p.side(), p.amount(), p.leverage(), p.price(), p.stop(), p.take(), p.trail());
            case BankOrderPayload.LIMIT_SPOT -> Bank.placeSpot(player, c, p.side(), p.amount(), p.price());
            case BankOrderPayload.CANCEL -> Bank.cancel(player, p.id());
            case BankOrderPayload.ALERT -> Bank.addAlert(player, c, p.price());
            case BankOrderPayload.UNALERT -> Bank.removeAlert(player, p.id());
            default -> {}
        }
        if (watch.screen == BANK_SCREEN) sendBank(player, watch.pos, false);
        else send(player, MarketPayload.UPDATE, watch.hand, watch.pos);
    }

    /** 在兑换台成交：人要在台子旁边，台子还在，兑换商开着门，钱也够。 */
    public static boolean trade(ServerPlayer player, BlockPos pos, int a, int b, long amount) {
        var currencies = Currency.values();
        if (a < 0 || b < 0 || a >= currencies.length || b >= currencies.length || a == b || amount <= 0) return false;
        var level = player.level();
        if (!level.getBlockState(pos).is(EXCHANGE.get()) || player.distanceToSqr(pos.getCenter()) > REACH * REACH) {
            player.sendOverlayMessage(Component.translatable("message.logos_dimension.fx.too_far"));
            return false;
        }
        var server = level.getServer();
        if (!FxMarket.open(clock(server))) {
            player.sendOverlayMessage(Component.translatable("message.logos_dimension.fx.closed"));
            return false;
        }
        Currency from = currencies[a], to = currencies[b];
        var market = market(server);
        if (market.quote(from, to, amount).received() <= 0) {
            player.sendOverlayMessage(Component.translatable("message.logos_dimension.fx.too_small"));
            return false;
        }
        if (FxMarket.tooLarge(from, to, amount, market.rate(from), market.rate(to))) {
            player.sendOverlayMessage(Component.translatable("message.logos_dimension.bank.too_deep"));
            return false;
        }
        if (!Purse.spend(player, from, amount)) {
            player.sendOverlayMessage(Component.translatable("message.logos_dimension.fx.not_enough", Component.translatable(from.key())));
            return false;
        }
        long received = market.trade(from, to, amount);
        Purse.receive(player, to, received);
        FxMarketData.touch(server);
        player.playSound(SoundEvents.CHAIN_PLACE, .7f, 1.3f);
        player.sendOverlayMessage(Component.translatable("message.logos_dimension.fx.traded", Money.format(from, amount), Money.format(to, received)));
        return true;
    }

    private Economy() {}
}
