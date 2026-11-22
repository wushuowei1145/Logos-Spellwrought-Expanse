package dev.logos.dimension.jiansui;

import dev.logos.dimension.LogosDimension;
import io.netty.buffer.ByteBuf;
import java.util.Collection;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 剑祟这一战给画面的提示（docs/JIAN_SUI_BOSS.md 第 15 节）：服务端只说「什么时候、在哪、什么」，样子全在客户端。
 * {@code from}、{@code to} 是两个世界坐标，{@code time} 是开始的游戏刻，{@code value} 按种类用。
 * 残骸眼睛亮一下用剑祟自己同步的闪光（{@code JianSui#flash}），不走这里。
 */
public record JianSuiCue(int kind, long time, Vec3 from, Vec3 to, float value) implements CustomPacketPayload {
    /** 标题：画面正中大字「剑祟」，下面一行小字。 */
    public static final int TITLE = 0;
    /** 巨剑贯落：from 是出手的地方，to 是瞄准的落点；光沿这条线聚 {@value JianSuiFight#SWORD_AIM} 刻，再一柄巨剑射下来。value：1 扎住碎块、0 穿过去掉下去。 */
    public static final int GIANT_SWORD = 1;
    /** 发光的裂缝：from 是中心，value 是爬到的半径（格），从 time 起爬开；value 不大于 0 时收起这一处的裂缝。 */
    public static final int CRACKS = 2;
    /** 插剑那一下：from 处镜头抖。 */
    public static final int IMPACT = 3;
    /** 贯穿古剑崩出：from 是剑从哪飞出，to 是落下插住的地方（遗剑方块的底面正中）。 */
    public static final int AXIAL_BURST = 4;
    /** 巨剑被弹反：from 是被弹开的地方（原来的落点），to 是剑祟胸口；剑原路飞回去，{@value JianSuiFight#REFLECT_FLIGHT} 刻打在它身上碎掉。 */
    public static final int REFLECT = 5;
    /**
     * 终结里撬了一下（按的或者自己撬的）：from 是剑祟胸口（刺进去的地方），to 是贯穿古剑的剑柄，value 是第几下（0 起）。
     * 客户端：镜头轻轻一抖，剑祟两只手抽一下、贯穿古剑顶出一截、面具多裂一道，撬的人往下一压，都顿一下。
     */
    public static final int PRY_BEAT = 6;
    /** 终结被提前截断、剑祟还留着（命令跳回前面的状态）：from 是剑祟胸口。客户端不再摆撬剑的姿势，收掉每一下的反应。 */
    public static final int FINISHER_END = 7;

    public static final Type<JianSuiCue> TYPE = new Type<>(LogosDimension.id("jian_sui_cue"));
    private static final StreamCodec<ByteBuf, Vec3> VEC = StreamCodec.composite(
            ByteBufCodecs.DOUBLE, Vec3::x, ByteBufCodecs.DOUBLE, Vec3::y, ByteBufCodecs.DOUBLE, Vec3::z, Vec3::new);
    public static final StreamCodec<ByteBuf, JianSuiCue> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, JianSuiCue::kind, ByteBufCodecs.VAR_LONG, JianSuiCue::time, VEC, JianSuiCue::from, VEC, JianSuiCue::to,
            ByteBufCodecs.FLOAT, JianSuiCue::value, JianSuiCue::new);

    @Override public Type<JianSuiCue> type() { return TYPE; }

    public static void send(Collection<ServerPlayer> players, JianSuiCue cue) {
        for (ServerPlayer p : players) if (p.connection.hasChannel(TYPE)) PacketDistributor.sendToPlayer(p, cue);
    }

    /** {@code at} 周围 {@code range} 格内的玩家。 */
    public static void near(ServerLevel level, Vec3 at, double range, JianSuiCue cue) {
        send(level.players().stream().filter(p -> p.position().distanceToSqr(at) < range * range).toList(), cue);
    }
}
