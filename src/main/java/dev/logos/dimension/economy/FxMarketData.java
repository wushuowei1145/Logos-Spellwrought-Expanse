package dev.logos.dimension.economy;

import dev.logos.dimension.LogosDimension;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** 全服只有一个市场，存在主世界的存档数据里。 */
public final class FxMarketData extends SavedData {
    private static final SavedDataType<FxMarketData> TYPE = new SavedDataType<>(LogosDimension.id("fx_market"), FxMarketData::new,
            CompoundTag.CODEC.xmap(tag -> new FxMarketData(FxMarket.load(tag)), data -> data.market.save()));
    private FxMarket market;

    private FxMarketData() { this(new FxMarket(System.nanoTime())); }
    private FxMarketData(FxMarket market) { this.market = market; }

    public static FxMarketData of(MinecraftServer server) { return server.overworld().getDataStorage().computeIfAbsent(TYPE); }
    public static FxMarket market(MinecraftServer server) { return of(server).market; }

    /** 改动市场后调用，保证下次存档写入。 */
    public static void touch(MinecraftServer server) { of(server).setDirty(); }

    /** 管理员重置：换一个新种子，汇率回到锚定值。 */
    public static void reset(MinecraftServer server, long clock) {
        var data = of(server);
        data.market = new FxMarket(System.nanoTime());
        data.market.advance(clock);
        data.setDirty();
    }
}
