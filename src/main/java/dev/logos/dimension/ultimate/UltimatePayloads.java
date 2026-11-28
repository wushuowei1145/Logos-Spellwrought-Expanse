package dev.logos.dimension.ultimate;

import dev.logos.dimension.LogosDimension;
import io.netty.buffer.ByteBuf;
import java.util.List;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;

/** 奥义的几种包（docs/ULTIMATES.md「包」）。 */
public final class UltimatePayloads {
    private UltimatePayloads() {}

    /**
     * 玩家下令（奥义键、号令轮盘、直接热键）：{@code companion} 是指名的同伴，空串时由服务端挑（准星对着的、按队伍顺序的下一位）。
     * 汇流里收到时放玩家自己的最后一击。
     */
    public record Order(String companion) implements CustomPacketPayload {
        public static final Type<Order> TYPE = new Type<>(LogosDimension.id("ultimate_order"));
        public static final StreamCodec<ByteBuf, Order> CODEC = ByteBufCodecs.STRING_UTF8.map(Order::new, Order::companion);

        @Override public Type<Order> type() { return TYPE; }
    }

    /**
     * 一位在场同伴的奥义此刻怎么样（侧边小队、号令轮盘按它画）。
     *
     * @param id         同伴 ID
     * @param entity     她的实体 ID
     * @param cooldown   冷却一共多少刻
     * @param left       冷却还剩多少刻
     * @param released   这一轮连携里放过了
     * @param performing 正在演
     * @param queued     已经点了她，等正在放的那段过场放完就轮到她
     * @param cost       此刻下令要花多少魔力（算过连携折扣和馈赠）
     * @param affordable 主人的魔力够不够
     */
    public record Member(String id, int entity, int cooldown, int left, boolean released, boolean performing, boolean queued, int cost,
                         boolean affordable) {
        static final StreamCodec<ByteBuf, Member> CODEC = StreamCodec.of((buf, m) -> {
            ByteBufCodecs.STRING_UTF8.encode(buf, m.id);
            ByteBufCodecs.VAR_INT.encode(buf, m.entity);
            ByteBufCodecs.VAR_INT.encode(buf, m.cooldown);
            ByteBufCodecs.VAR_INT.encode(buf, m.left);
            ByteBufCodecs.BOOL.encode(buf, m.released);
            ByteBufCodecs.BOOL.encode(buf, m.performing);
            ByteBufCodecs.BOOL.encode(buf, m.queued);
            ByteBufCodecs.VAR_INT.encode(buf, m.cost);
            ByteBufCodecs.BOOL.encode(buf, m.affordable);
        }, buf -> new Member(ByteBufCodecs.STRING_UTF8.decode(buf), ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf),
                ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.BOOL.decode(buf), ByteBufCodecs.BOOL.decode(buf), ByteBufCodecs.BOOL.decode(buf),
                ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.BOOL.decode(buf)));

        public boolean ready() {
            return left == 0 && !released && !performing && !queued;
        }
    }

    /**
     * 发给主人：连携走到哪了、每位在场同伴怎么样。变了就发，平时每半秒一次（有同伴在场时）。
     *
     * @param phase     {@link UltimateChain.Phase} 的序号
     * @param window    接力窗口还剩多少刻
     * @param converged 汇流还剩多少刻
     * @param lock      汇流时锁住的主目标（实体 ID，-1 是没有）
     * @param members   在场的同伴，按队伍顺序
     */
    public record Status(int phase, int window, int converged, int lock, List<Member> members) implements CustomPacketPayload {
        public static final Type<Status> TYPE = new Type<>(LogosDimension.id("ultimate_status"));
        public static final StreamCodec<ByteBuf, Status> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Status::phase,
                ByteBufCodecs.VAR_INT, Status::window,
                ByteBufCodecs.VAR_INT, Status::converged,
                ByteBufCodecs.VAR_INT, Status::lock,
                Member.CODEC.apply(ByteBufCodecs.list(8)), Status::members,
                Status::new);
        public static final Status EMPTY = new Status(0, 0, 0, -1, List.of());

        public Status {
            members = List.copyOf(members);
        }

        @Override public Type<Status> type() { return TYPE; }

        public UltimateChain.Phase chainPhase() {
            return UltimateChain.Phase.values()[Math.clamp(phase, 0, UltimateChain.Phase.values().length - 1)];
        }
    }

    /**
     * 开演：发给看得见演的人的玩家，客户端按 {@code key} 找画面（同伴的 {@code CompanionStyle#ultimate}，玩家的 {@code HeroCutin#finishers}）。
     *
     * @param performer 谁在演（实体 ID）
     * @param owner     为谁演（下令的玩家的实体 ID）：只有他的镜头被过场接管，别人只看世界里的那一半
     * @param key       同伴 ID，或者 {@code hero/<武器大类>}
     * @param target    打谁（实体 ID，-1 是没有）
     * @param aim       没有目标时往哪放
     * @param stage     连携里第几段
     * @param finisher  是不是玩家的最后一击
     * @param condensed 演缩短版（连携里第二位起，{@link UltimateDef#condensed}）
     * @param members   这一轮在场几位同伴
     * @param seed      随机种子（和服务端脚本同一个）
     * @param length    一共几刻
     * @param impact    第几刻出手
     */
    public record Cast(int performer, int owner, String key, int target, Vec3 aim, int stage, boolean finisher, boolean condensed, int members, long seed,
                       int length, int impact) implements CustomPacketPayload {
        public static final Type<Cast> TYPE = new Type<>(LogosDimension.id("ultimate_cast"));
        public static final StreamCodec<ByteBuf, Cast> CODEC = StreamCodec.of((buf, c) -> {
            ByteBufCodecs.VAR_INT.encode(buf, c.performer);
            ByteBufCodecs.VAR_INT.encode(buf, c.owner);
            ByteBufCodecs.STRING_UTF8.encode(buf, c.key);
            ByteBufCodecs.VAR_INT.encode(buf, c.target);
            ByteBufCodecs.DOUBLE.encode(buf, c.aim.x);
            ByteBufCodecs.DOUBLE.encode(buf, c.aim.y);
            ByteBufCodecs.DOUBLE.encode(buf, c.aim.z);
            ByteBufCodecs.VAR_INT.encode(buf, c.stage);
            ByteBufCodecs.BOOL.encode(buf, c.finisher);
            ByteBufCodecs.BOOL.encode(buf, c.condensed);
            ByteBufCodecs.VAR_INT.encode(buf, c.members);
            ByteBufCodecs.VAR_LONG.encode(buf, c.seed);
            ByteBufCodecs.VAR_INT.encode(buf, c.length);
            ByteBufCodecs.VAR_INT.encode(buf, c.impact);
        }, buf -> new Cast(ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.STRING_UTF8.decode(buf), ByteBufCodecs.VAR_INT.decode(buf),
                new Vec3(ByteBufCodecs.DOUBLE.decode(buf), ByteBufCodecs.DOUBLE.decode(buf), ByteBufCodecs.DOUBLE.decode(buf)),
                ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.BOOL.decode(buf), ByteBufCodecs.BOOL.decode(buf), ByteBufCodecs.VAR_INT.decode(buf),
                ByteBufCodecs.VAR_LONG.decode(buf),
                ByteBufCodecs.VAR_INT.decode(buf), ByteBufCodecs.VAR_INT.decode(buf)));

        @Override public Type<Cast> type() { return TYPE; }
    }

    /** 演完或被打断：客户端收起这一段的画面。 */
    public record End(int performer, boolean interrupted) implements CustomPacketPayload {
        public static final Type<End> TYPE = new Type<>(LogosDimension.id("ultimate_end"));
        public static final StreamCodec<ByteBuf, End> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, End::performer, ByteBufCodecs.BOOL, End::interrupted, End::new);

        @Override public Type<End> type() { return TYPE; }
    }

    /** 下令没成：{@link #NOT_READY} 没人能放（都在冷却、都放过了），{@link #NO_MANA} 魔力不够。客户端只出画面和声音，不写字。 */
    public record Denied(String companion, int reason) implements CustomPacketPayload {
        public static final int NOT_READY = 0, NO_MANA = 1;
        public static final Type<Denied> TYPE = new Type<>(LogosDimension.id("ultimate_denied"));
        public static final StreamCodec<ByteBuf, Denied> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, Denied::companion, ByteBufCodecs.VAR_INT, Denied::reason, Denied::new);

        @Override public Type<Denied> type() { return TYPE; }
    }
}
