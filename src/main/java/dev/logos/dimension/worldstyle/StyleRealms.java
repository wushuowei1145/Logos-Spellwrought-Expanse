package dev.logos.dimension.worldstyle;

import dev.logos.dimension.LogosDimension;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * 一个世界里的所有画风区域，随存档保存：结构、方块、Boss 想让某块地方换画风，就在服务端调 {@link #add}。
 * 进出区域由客户端按镜头位置判断，这里只负责存和同步。用法和限制见 docs/WORLD_STYLES.md。
 */
public final class StyleRealms extends SavedData {
    private static final com.mojang.serialization.Codec<StyleRealms> CODEC = StyleRealm.CODEC.listOf().xmap(StyleRealms::new, data -> data.realms);
    private static final SavedDataType<StyleRealms> TYPE = new SavedDataType<>(LogosDimension.id("style_realms"), StyleRealms::new, CODEC);
    private final List<StyleRealm> realms;

    private StyleRealms() {
        this(List.of());
    }

    private StyleRealms(List<StyleRealm> realms) {
        this.realms = new ArrayList<>(realms);
    }

    public static void register(IEventBus bus) {
        bus.addListener((RegisterPayloadHandlersEvent event) -> event.registrar("1").playToClient(StyleRealmsPayload.TYPE, StyleRealmsPayload.CODEC));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> send(event.getEntity()));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerChangedDimensionEvent event) -> send(event.getEntity()));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerRespawnEvent event) -> send(event.getEntity()));
    }

    private static StyleRealms of(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    public static List<StyleRealm> in(ServerLevel level) {
        return List.copyOf(of(level).realms);
    }

    public static void add(ServerLevel level, StyleRealm realm) {
        StyleRealms data = of(level);
        data.realms.add(realm);
        data.setDirty();
        sync(level);
    }

    public static void clear(ServerLevel level) {
        StyleRealms data = of(level);
        data.realms.clear();
        data.setDirty();
        sync(level);
    }

    private static void sync(ServerLevel level) {
        for (ServerPlayer player : level.players()) send(player);
    }

    private static void send(net.minecraft.world.entity.player.Player player) {
        if (player instanceof ServerPlayer server && server.connection.hasChannel(StyleRealmsPayload.TYPE)) {
            PacketDistributor.sendToPlayer(server, new StyleRealmsPayload(in(server.level())));
        }
    }
}
