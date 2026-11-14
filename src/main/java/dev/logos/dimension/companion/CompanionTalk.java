package dev.logos.dimension.companion;

import dev.logos.dimension.LogosDimension;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.jspecify.annotations.Nullable;

/**
 * 和同伴说话（docs/SHIRAUME_COMPANION.md 第 8.13 节）：主人潜行右键她，打开对话；选项见 {@link TalkMenu}，她说什么、做什么由同伴自己
 * （{@link Talker}）决定。这里只管通用的部分：一人同时只和一位说话；离开 {@link #RANGE} 格、她被打断（来了敌人）、她走了就结束；
 * 说着话时每秒把档案（{@link CompanionSheet}）重发一次，角色面板上的数是活的；看包袱时对话先放下，关上包袱再回来。
 */
public final class CompanionTalk {
    /** 能说话的同伴。 */
    public interface Talker {
        /** 哪一位（语言键、选项树、界面风格都按它找）。 */
        String talkId();

        /** {@code p} 这一刻能不能和她说话（她是 {@code p} 的同伴、在外面、没倒下、没在离开）。 */
        boolean canTalk(ServerPlayer p);

        CompanionSheet talkSheet(ServerPlayer p);

        /** 刚开口时她说的话。 */
        List<Line> talkGreeting(ServerPlayer p);

        /** 选了 {@code option}（树里的 {@code SERVER} 项）：做事、回话。 */
        Reply talkPick(ServerPlayer p, String option);

        /** 开始、结束说话（她站住看着人、回去做自己的事）。{@code bye}：主人说了「一会再聊」。 */
        void talkStarted(ServerPlayer p);

        void talkEnded(ServerPlayer p, boolean bye);

        /** 正说着话来了敌人，话先放一放：她说这一句（字幕）。 */
        default void talkInterrupted(ServerPlayer p) {}

        /** 看完包袱回到对话时说的话（可以为 null）。 */
        default @Nullable Line talkBack(ServerPlayer p) {
            return null;
        }
    }

    /** 她说的一句：语言键和填进去的参数（以 {@code #} 开头的参数本身也是语言键）。 */
    public record Line(String key, List<String> args) {
        static final StreamCodec<ByteBuf, Line> CODEC = StreamCodec.composite(ByteBufCodecs.STRING_UTF8, Line::key,
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), Line::args, Line::new);

        public static Line of(String key, Object... args) {
            List<String> out = new ArrayList<>(args.length);
            for (Object a : args) out.add(String.valueOf(a));
            return new Line(key, List.copyOf(out));
        }
    }

    /** 选项的结果：她说的话；{@code close} 说完就结束（话改用字幕说）；{@code handoff} 服务端打开了别的界面（包袱），关上以后回到对话。 */
    public record Reply(List<Line> lines, boolean close, boolean handoff) {
        public static final Reply NONE = new Reply(List.of(), false, false);

        public static Reply say(List<Line> lines) {
            return new Reply(List.copyOf(lines), false, false);
        }

        public static Reply say(Line... lines) {
            return new Reply(List.of(lines), false, false);
        }

        public static Reply closing() {
            return new Reply(List.of(), true, false);
        }

        public static Reply away() {
            return new Reply(List.of(), false, true);
        }
    }

    /** 服务端 → 客户端：档案和她刚说的话；{@link #OPEN} 打开对话，{@link #END} 结束（客户端关掉对话和面板）。 */
    public record Update(CompanionSheet sheet, List<Line> lines, int flags) implements CustomPacketPayload {
        public static final Type<Update> TYPE = new Type<>(LogosDimension.id("companion_talk"));
        public static final StreamCodec<ByteBuf, Update> CODEC = StreamCodec.composite(CompanionSheet.CODEC, Update::sheet,
                Line.CODEC.apply(ByteBufCodecs.list()), Update::lines, ByteBufCodecs.VAR_INT, Update::flags, Update::new);

        public boolean opens() {
            return (flags & OPEN) != 0;
        }

        public boolean ends() {
            return (flags & END) != 0;
        }

        @Override
        public Type<Update> type() {
            return TYPE;
        }
    }

    /** 客户端 → 服务端：选了哪一项（{@link #CLOSED}：主人关掉了对话）。 */
    public record Pick(int entity, String option) implements CustomPacketPayload {
        public static final Type<Pick> TYPE = new Type<>(LogosDimension.id("companion_talk_pick"));
        public static final StreamCodec<ByteBuf, Pick> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_INT, Pick::entity,
                ByteBufCodecs.STRING_UTF8, Pick::option, Pick::new);

        @Override
        public Type<Pick> type() {
            return TYPE;
        }
    }

    public static final int OPEN = 1, END = 2;
    public static final String CLOSED = "$close";
    /** 离她这么远就不说了；每隔这么多刻重发一次档案。 */
    public static final double RANGE = 8;
    static final int REFRESH = 20;

    private record Session(int entity, long since) {}

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    /** 正在看包袱（对话先放下，关上包袱再回来）的人。 */
    private static final Set<UUID> AWAY = new HashSet<>();

    private CompanionTalk() {}

    static void register(IEventBus bus) {
        bus.addListener((RegisterPayloadHandlersEvent event) -> {
            var registrar = event.registrar("1");
            registrar.playToClient(Update.TYPE, Update.CODEC);
            registrar.playToServer(Pick.TYPE, Pick.CODEC, (payload, context) -> {
                if (context.player() instanceof ServerPlayer p) pick(p, payload);
            });
        });
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> tick(e.getServer()));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> {
            SESSIONS.remove(e.getEntity().getUUID());
            AWAY.remove(e.getEntity().getUUID());
        });
        NeoForge.EVENT_BUS.addListener((PlayerContainerEvent.Close e) -> {
            if (e.getEntity() instanceof ServerPlayer p && AWAY.remove(p.getUUID())) back(p);
        });
    }

    // ---------------------------------------------------------------- 开始、结束

    /** {@code p} 和 {@code e} 说话：打开对话。她不是能说话的同伴、或者现在不能说时返回假。 */
    public static boolean begin(ServerPlayer p, Entity e) {
        if (!(e instanceof Talker t) || !t.canTalk(p)) return false;
        Session old = SESSIONS.get(p.getUUID());
        if (old != null && old.entity() != e.getId()) end(p, false);
        SESSIONS.put(p.getUUID(), new Session(e.getId(), p.level().getGameTime()));
        AWAY.remove(p.getUUID());
        t.talkStarted(p);
        send(p, t, t.talkGreeting(p), OPEN);
        return true;
    }

    /** 结束 {@code p} 的对话（她回去做自己的事，客户端关掉对话）。 */
    public static void end(ServerPlayer p, boolean bye) {
        Session s = SESSIONS.remove(p.getUUID());
        AWAY.remove(p.getUUID());
        if (s == null) return;
        Entity e = p.level().getEntity(s.entity());
        if (e instanceof Talker t) {
            t.talkEnded(p, bye);
            send(p, t, List.of(), END);
        } else if (p.connection.hasChannel(Update.TYPE)) {
            PacketDistributor.sendToPlayer(p, new Update(empty(s.entity()), List.of(), END));
        }
    }

    /** 和 {@code e} 说着话的那个人：话先放一放（来了敌人）。 */
    public static void interrupt(Entity e) {
        ServerPlayer p = partner(e);
        if (p == null) return;
        if (e instanceof Talker t) t.talkInterrupted(p);
        end(p, false);
    }

    /** 正在和 {@code e} 说话的人（没有为 null）。 */
    public static @Nullable ServerPlayer partner(Entity e) {
        if (e.level().getServer() == null) return null;
        for (var entry : SESSIONS.entrySet()) {
            if (entry.getValue().entity() != e.getId()) continue;
            ServerPlayer p = e.level().getServer().getPlayerList().getPlayer(entry.getKey());
            if (p != null && p.level() == e.level()) return p;
        }
        return null;
    }

    public static boolean talking(ServerPlayer p) {
        return SESSIONS.containsKey(p.getUUID());
    }

    // ---------------------------------------------------------------- 选项

    static void pick(ServerPlayer p, Pick pick) {
        Session s = SESSIONS.get(p.getUUID());
        if (s == null || s.entity() != pick.entity()) return;
        if (CLOSED.equals(pick.option())) {
            end(p, false);
            return;
        }
        if (!(p.level().getEntity(s.entity()) instanceof Talker t) || !t.canTalk(p)) {
            end(p, false);
            return;
        }
        TalkMenu.Option o = TalkMenu.find(TalkMenu.of(t.talkId()), pick.option());
        if (o == null) return;
        if (o.kind() == TalkMenu.Kind.CLOSE) {
            end(p, true);
            return;
        }
        if (o.kind() != TalkMenu.Kind.SERVER) return;
        Reply r = t.talkPick(p, o.id());
        if (r.close()) {
            end(p, false);
            return;
        }
        if (r.handoff()) {
            AWAY.add(p.getUUID());
            return;
        }
        send(p, t, r.lines(), 0);
    }

    /** 包袱关上了：回到对话。 */
    private static void back(ServerPlayer p) {
        Session s = SESSIONS.get(p.getUUID());
        if (s == null) return;
        if (!(p.level().getEntity(s.entity()) instanceof Talker t) || !t.canTalk(p)) {
            end(p, false);
            return;
        }
        Line line = t.talkBack(p);
        send(p, t, line == null ? List.of() : List.of(line), OPEN);
    }

    // ---------------------------------------------------------------- 每刻

    private static void tick(MinecraftServer server) {
        if (SESSIONS.isEmpty()) return;
        long now = server.overworld().getGameTime();
        for (UUID id : List.copyOf(SESSIONS.keySet())) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            Session s = SESSIONS.get(id);
            if (p == null || s == null) {
                SESSIONS.remove(id);
                continue;
            }
            Entity e = p.level().getEntity(s.entity());
            if (!(e instanceof Talker t) || !t.canTalk(p) || e.distanceTo(p) > RANGE || !p.isAlive()) {
                end(p, false);
                continue;
            }
            if ((now - s.since()) % REFRESH == 0 && !AWAY.contains(id)) send(p, t, List.of(), 0);
        }
    }

    private static void send(ServerPlayer p, Talker t, List<Line> lines, int flags) {
        last = lines.isEmpty() ? last : lines.get(lines.size() - 1).key();
        if (p.connection.hasChannel(Update.TYPE)) PacketDistributor.sendToPlayer(p, new Update(t.talkSheet(p), List.copyOf(lines), flags));
    }

    /** 测试用：她最近说的一句（语言键）。 */
    private static volatile String last = "";

    public static String last() {
        return last;
    }

    /** 实体已经不在时，结束用的空档案。 */
    static CompanionSheet empty(int entity) {
        return new CompanionSheet(entity, "", "", List.of(), List.of(), 0, 0, "", 0, 0, 0, false, CompanionTactic.ADAPTIVE.id, 0, 0, 0);
    }
}
