package dev.logos.dimension.companion;

import dev.logos.dimension.LogosDimension;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * 同伴的台词：服务端发一句（语言键），客户端在画面下方像字幕一样显示「白梅：……」一段时间（{@code CompanionLinesHud}）。
 * 台词都在语言文件里（{@code line.logos_dimension.shiraume.*}），好改。
 */
public final class CompanionLines {
    public record Line(String key, int ticks) implements CustomPacketPayload {
        public static final Type<Line> TYPE = new Type<>(LogosDimension.id("companion_line"));
        public static final StreamCodec<ByteBuf, Line> CODEC = StreamCodec.composite(ByteBufCodecs.STRING_UTF8, Line::key,
                ByteBufCodecs.VAR_INT, Line::ticks, Line::new);

        @Override
        public Type<Line> type() {
            return TYPE;
        }
    }

    /** 测试用：最近发给谁的哪一句。 */
    private static volatile String last = "";

    private CompanionLines() {}

    static void register(IEventBus bus) {
        bus.addListener((RegisterPayloadHandlersEvent event) -> event.registrar("1").playToClient(Line.TYPE, Line.CODEC));
    }

    /** 给 {@code player} 看一句台词：{@code line.logos_dimension.shiraume.<id>}，显示 {@code ticks} 刻。 */
    public static void say(ServerPlayer player, String id, int ticks) {
        String key = "line.logos_dimension.shiraume." + id;
        last = player.getUUID() + "|" + key;
        if (player.connection.hasChannel(Line.TYPE)) PacketDistributor.sendToPlayer(player, new Line(key, ticks));
    }

    public static String last() {
        return last;
    }
}
