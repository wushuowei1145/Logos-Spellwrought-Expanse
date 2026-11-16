package dev.logos.dimension.companion;

import dev.logos.dimension.LogosDimension;
import io.netty.buffer.ByteBuf;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * 角色的台词：服务端发一句（语言键），客户端在画面下方像字幕一样显示「名字　……」一段时间（{@code CompanionLinesHud}）。
 * 台词都在语言文件里（白梅是 {@code line.logos_dimension.shiraume.*}），好改。
 * <p>状态和提示只能这样从角色嘴里说出来，或者让玩家自己从画面上看出来；不往动作栏、聊天栏、血条下面写状态。
 */
public final class CompanionLines {
    /** 白梅的名字和颜色（默认的说话人）。 */
    public static final String SHIRAUME = "entity.logos_dimension.shiraume_companion";
    public static final int SHIRAUME_COLOR = 0xF7B6D6;
    /** 战斗里说的话多远以内的玩家听得见。 */
    public static final double RANGE = 48;

    /** 一句台词：{@code name} 是说话人名字的语言键，{@code color} 是名字的颜色，{@code args} 按字符串填进 {@code key}。 */
    public record Line(String name, int color, String key, List<String> args, int ticks) implements CustomPacketPayload {
        public static final Type<Line> TYPE = new Type<>(LogosDimension.id("companion_line"));
        public static final StreamCodec<ByteBuf, Line> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Line::name,
                ByteBufCodecs.INT, Line::color,
                ByteBufCodecs.STRING_UTF8, Line::key,
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list(8)), Line::args,
                ByteBufCodecs.VAR_INT, Line::ticks,
                Line::new);

        @Override
        public Type<Line> type() {
            return TYPE;
        }
    }

    /** 测试用：最近发给谁的哪一句。 */
    private static volatile String last = "";
    /** 每个说话人每一句上次说的时刻（{@link #speakEvery} 的冷却）。 */
    private static final Map<Entity, Map<String, Long>> SAID = new WeakHashMap<>();

    private CompanionLines() {}

    static void register(IEventBus bus) {
        bus.addListener((RegisterPayloadHandlersEvent event) -> event.registrar("2").playToClient(Line.TYPE, Line.CODEC));
    }

    /** 给 {@code player} 看白梅的一句台词：{@code line.logos_dimension.shiraume.<id>}，显示 {@code ticks} 刻。 */
    public static void say(ServerPlayer player, String id, int ticks) {
        send(player, new Line(SHIRAUME, SHIRAUME_COLOR, "line.logos_dimension.shiraume." + id, List.of(), ticks));
    }

    /** {@code speaker} 对附近（{@link #RANGE} 格内）的玩家说一句：名字 {@code name}（语言键），颜色 {@code color}。 */
    public static void speak(Entity speaker, String name, int color, String key, int ticks, Object... args) {
        speakEvery(speaker, name, color, key, ticks, 0, args);
    }

    /** 同上，但同一个人同一句 {@code gap} 刻内不重复说。返回这次说了没有。 */
    public static boolean speakEvery(Entity speaker, String name, int color, String key, int ticks, int gap, Object... args) {
        if (!(speaker.level() instanceof ServerLevel world)) return false;
        long now = world.getGameTime();
        var said = SAID.computeIfAbsent(speaker, ignored -> new HashMap<>());
        Long before = said.get(key);
        if (gap > 0 && before != null && now - before < gap) return false;
        said.put(key, now);
        var text = new String[args.length];
        for (int i = 0; i < args.length; i++) text[i] = String.valueOf(args[i]);
        var line = new Line(name, color, key, List.of(text), ticks);
        last = speaker.getUUID() + "|" + key;
        for (ServerPlayer p : world.players()) if (p.distanceToSqr(speaker) < RANGE * RANGE) send(p, line);
        return true;
    }

    /** 这个人说过这一句没有（新的一场用 {@link #forget} 清掉）。 */
    public static boolean said(Entity speaker, String key) {
        var said = SAID.get(speaker);
        return said != null && said.containsKey(key);
    }

    public static void forget(Entity speaker) {
        SAID.remove(speaker);
    }

    private static void send(ServerPlayer player, Line line) {
        last = player.getUUID() + "|" + line.key();
        if (player.connection.hasChannel(Line.TYPE)) PacketDistributor.sendToPlayer(player, line);
    }

    public static String last() {
        return last;
    }
}
