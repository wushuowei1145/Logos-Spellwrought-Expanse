package dev.logos.dimension.companion;

import dev.logos.dimension.LogosDimension;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * 友人帐（docs/COMPANIONS.md 第 7 节）：所有同伴一人一页，立过契的画她本人，还没立契的是一扇黑窗。在这里点一下就能叫她出来、收回，
 * 信物丢了也不用只靠命令。按键在客户端（{@code CompanionBookClient}，默认 K），这里是服务端：每一页的状态、叫出来和收回。
 */
public final class CompanionBook {
    private CompanionBook() {}

    /**
     * 友人帐上的一页。{@code pact}：立过契（没立契的只画黑窗，别的都不发）；{@code out}：此刻在外面；{@code rest}：还要歇多少秒（倒下、撤退后）；
     * {@code outfit}、{@code seals}：画她时穿哪一套、拿什么（琥珀的杖按契印换）；{@code stage}：羁绊段（{@link CompanionBond.Stage} 的名字）。
     */
    public record Page(String id, boolean pact, boolean out, int rest, String outfit, int seals, String stage) {
        static final StreamCodec<ByteBuf, Page> CODEC = StreamCodec.composite(ByteBufCodecs.STRING_UTF8, Page::id, ByteBufCodecs.BOOL, Page::pact,
                ByteBufCodecs.BOOL, Page::out, ByteBufCodecs.VAR_INT, Page::rest, ByteBufCodecs.STRING_UTF8, Page::outfit, ByteBufCodecs.VAR_INT,
                Page::seals, ByteBufCodecs.STRING_UTF8, Page::stage, Page::new);
    }

    /** 服务端 → 客户端：每一页（按 {@link Companions#ALL} 的顺序）；{@link #OPEN} 打开友人帐，{@link #DONE} 刚叫出来或收回了一位（客户端合上，好看她出来）。 */
    public record State(List<Page> pages, int flags) implements CustomPacketPayload {
        public static final Type<State> TYPE = new Type<>(LogosDimension.id("companion_book"));
        public static final StreamCodec<ByteBuf, State> CODEC = StreamCodec.composite(Page.CODEC.apply(ByteBufCodecs.list()), State::pages,
                ByteBufCodecs.VAR_INT, State::flags, State::new);

        public boolean opens() {
            return (flags & OPEN) != 0;
        }

        public boolean done() {
            return (flags & DONE) != 0;
        }

        @Override
        public Type<State> type() {
            return TYPE;
        }
    }

    /** 客户端 → 服务端：{@link #OPEN_BOOK} 翻开友人帐；{@link #CALL} 叫 {@code companion} 出来（在外面就收回）。 */
    public record Ask(String action, String companion) implements CustomPacketPayload {
        public static final Type<Ask> TYPE = new Type<>(LogosDimension.id("companion_book_ask"));
        public static final StreamCodec<ByteBuf, Ask> CODEC = StreamCodec.composite(ByteBufCodecs.STRING_UTF8, Ask::action, ByteBufCodecs.STRING_UTF8,
                Ask::companion, Ask::new);

        @Override
        public Type<Ask> type() {
            return TYPE;
        }
    }

    public static final int OPEN = 1, DONE = 2;
    public static final String OPEN_BOOK = "open", CALL = "call";

    static void register(IEventBus bus) {
        bus.addListener((RegisterPayloadHandlersEvent event) -> {
            var registrar = event.registrar("1");
            registrar.playToClient(State.TYPE, State.CODEC);
            registrar.playToServer(Ask.TYPE, Ask.CODEC, (payload, context) -> {
                if (context.player() instanceof ServerPlayer p) ask(p, payload);
            });
        });
    }

    static void ask(ServerPlayer p, Ask a) {
        if (CALL.equals(a.action())) {
            boolean done = call(p, a.companion());
            send(p, done ? DONE : 0);
        } else {
            send(p, OPEN);
        }
    }

    /** 叫 {@code id} 出来，在外面就收回；没立契、还在歇着时什么都不做。返回做了没有。 */
    public static boolean call(ServerPlayer p, String id) {
        CompanionDef d = Companions.def(id);
        if (d == null || !Companions.bond(p, d).pact()) return false;
        if (Companions.out(p, d) != null) return Companions.dismiss(p, d);
        return Companions.summon(p, d, null) == null;
    }

    private static void send(ServerPlayer p, int flags) {
        if (p.connection.hasChannel(State.TYPE)) PacketDistributor.sendToPlayer(p, new State(pages(p), flags));
    }

    /** 每一页（按 {@link Companions#ALL} 的顺序）。 */
    public static List<Page> pages(ServerPlayer p) {
        List<Page> out = new ArrayList<>();
        for (CompanionDef d : Companions.ALL) {
            var bond = Companions.bond(p, d);
            if (!bond.pact()) {
                out.add(new Page(d.id(), false, false, 0, "", 0, ""));
                continue;
            }
            CompanionEntity c = Companions.out(p, d);
            int rest = (int) ((Companions.restLeft(p, d) + 19) / 20);
            out.add(new Page(d.id(), true, c != null, rest, outfit(p, d, c), bond.seals(), CompanionBond.Stage.of(bond.affection()).name()));
        }
        return out;
    }

    /** 画她时穿哪一套：琥珀的记在你身上（对话里换的）；白梅在外面就是她身上那一套，不然巫女服；精灵公主、拉塞塔只有一套。 */
    private static String outfit(ServerPlayer p, CompanionDef d, CompanionEntity c) {
        if (d == Companions.KOHAKU_DEF) return p.getData(Companions.KOHAKU_DAYS).outfit();
        if (c instanceof ShiraumeCompanion s) return s.outfit().id;
        if (d == Companions.SHIRAUME_DEF) return CompanionOutfit.MIKO.id;
        if (d == Companions.LACERTA_DEF) return LacertaTalk.OUTFIT;
        return "";
    }
}
