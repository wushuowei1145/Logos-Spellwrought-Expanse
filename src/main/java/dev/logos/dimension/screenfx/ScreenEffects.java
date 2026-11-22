package dev.logos.dimension.screenfx;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * 全屏后期的服务端入口：技能、方块、Boss 想让玩家闪一下，就在服务端调 {@link #flash}；要全屏白、全屏黑盖住换场景，调 {@link #cover}。
 * 只影响这个玩家的画面。电影蒙版跟着运镜走，见 {@code CameraShot.cinema()}。用法和限制见 docs/SCREEN_EFFECTS.md。
 */
public final class ScreenEffects {
    private ScreenEffects() {}

    public static void register(IEventBus bus) {
        bus.addListener((RegisterPayloadHandlersEvent event) -> {
            var registrar = event.registrar("1");
            registrar.playToClient(ScreenFlashPayload.TYPE, ScreenFlashPayload.CODEC);
            registrar.playToClient(ScreenCoverPayload.TYPE, ScreenCoverPayload.CODEC);
        });
    }

    public static void flash(ServerPlayer player, ScreenFlash flash) {
        if (player.connection.hasChannel(ScreenFlashPayload.TYPE)) PacketDistributor.sendToPlayer(player, new ScreenFlashPayload(flash));
    }

    /**
     * 盖上一次全屏白或黑（{@link ScreenCover}）：按刻计时，盖满的那几刻里服务端可以传送、换场景。
     * 只影响这个玩家；没装附属的客户端收不到，调用方的时序照常走。
     */
    public static void cover(ServerPlayer player, ScreenCover cover) {
        if (player.connection.hasChannel(ScreenCoverPayload.TYPE)) PacketDistributor.sendToPlayer(player, new ScreenCoverPayload(cover));
    }
}
