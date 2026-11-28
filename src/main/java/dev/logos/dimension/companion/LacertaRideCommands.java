package dev.logos.dimension.companion;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** 骑巨龙时客户端发过来的按键（{@link LacertaRide.Command}）：挥爪、喷龙炎、停。 */
public final class LacertaRideCommands {
    private LacertaRideCommands() {}

    public static CustomPacketPayload claw() {
        return new LacertaRide.Command(LacertaRide.Command.CLAW);
    }

    public static CustomPacketPayload breath() {
        return new LacertaRide.Command(LacertaRide.Command.BREATH);
    }

    public static CustomPacketPayload breathStop() {
        return new LacertaRide.Command(LacertaRide.Command.BREATH_STOP);
    }
}
