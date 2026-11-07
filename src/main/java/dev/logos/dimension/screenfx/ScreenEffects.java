package dev.logos.dimension.screenfx;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * 全屏后期的服务端入口：技能、方块、Boss 想让玩家闪一下，就在服务端调 {@link #flash}。
 * 只影响这个玩家的画面。电影蒙版跟着运镜走，见 {@code CameraShot.cinema()}。用法和限制见 docs/SCREEN_EFFECTS.md。
 */
public final class ScreenEffects {
    private ScreenEffects() {}

    public static void register(IEventBus bus) {
        bus.addListener((RegisterPayloadHandlersEvent event) -> event.registrar("1").playToClient(ScreenFlashPayload.TYPE, ScreenFlashPayload.CODEC));
    }

    public static void flash(ServerPlayer player, ScreenFlash flash) {
        if (player.connection.hasChannel(ScreenFlashPayload.TYPE)) PacketDistributor.sendToPlayer(player, new ScreenFlashPayload(flash));
    }
}
