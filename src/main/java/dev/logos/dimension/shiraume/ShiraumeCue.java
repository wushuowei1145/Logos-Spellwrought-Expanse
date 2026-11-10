package dev.logos.dimension.shiraume;

import dev.logos.dimension.LogosDimension;
import io.netty.buffer.ByteBuf;
import java.util.Collection;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 白梅战里只给画面用的提示（docs/SHIRAUME_BOSS.md 第 9 节），客户端在 {@code client/shiraume/ShiraumeClient} 处理：
 * 色散、镜头抖动、红光、标题、光柱、双眼红光、弹刀火花。没有协商这个通道的连接（例如 GameTest 的假玩家）不发。
 *
 * @param kind   见下面的常量
 * @param entity 相关的实体（双眼红光），没有为 -1
 * @param time   开始的游戏刻（红光、标题、光柱按它对时）
 * @param pos    位置（抖动、光柱的中心、红光蔓延的起点）
 * @param values 其余参数，含义随 kind
 */
public record ShiraumeCue(int kind, int entity, long time, Vec3 pos, float[] values) implements CustomPacketPayload {
    /** 色散：values[0] 强度（0–1）。 */
    public static final int ABERRATION = 0;
    /** 镜头抖动：pos 抖动的来源，values[0] 强度。 */
    public static final int SHAKE = 1;
    /** 红光：values[0] 1 开 0 关，values[1..6] 封印之间的范围（minX minY minZ maxX maxY maxZ），pos 蔓延的起点，time 开始的刻。 */
    public static final int MOOD = 2;
    /** 标题「九尾妖狐 白梅」：time 出现的刻。 */
    public static final int TITLE = 3;
    /** 变身的光柱：pos 中心（脚下），time 冲起的刻，values 是九道细光柱的 (x, z)。 */
    public static final int PILLAR = 4;
    /** 双眼红光：entity 是谁。 */
    public static final int EYES = 5;
    /** 弹刀的火花：pos 位置，values[0] 1 是被弹反（大）、0 是挡下。 */
    public static final int SPARK = 6;
    /** 落点标记（狐形大跳）：pos 落点，values[0] 半径，values[1] 持续刻数。 */
    public static final int MARK = 7;
    /** 速度感（缩地、投技的冲刺）：entity 是冲过来的她，values[0] 强度（冲向的那个人 1，旁人小一些），values[1] 持续刻数。 */
    public static final int SPEED = 8;
    /** 清除增益：entity 是被清掉的人，values 是清掉的效果的颜色（RGB）。 */
    public static final int PURGE = 9;
    /** 破盾：pos 位置。 */
    public static final int GUARD_BREAK = 10;
    /** 锁定术师：entity 是被盯上的人，values[0] 1 盯上、0 放开。 */
    public static final int LOCK = 11;
    /** 斩法那一刀：entity 是挨刀的人，pos 位置，values[0] 斩下的魔力。 */
    public static final int COUNTER = 12;
    /** 狂暴大跳落地的狐火冲击：pos 圆心，time 放出的刻；半径照 {@code ShiraumeBeast.ringRadius} 算。 */
    public static final int RING = 13;
    /** 一字斩的剑气：entity 是她，pos 起点，time 放出的刻，values 是方向 (x, z)；走法照 {@code Shiraume.WAVE_*}。 */
    public static final int WAVE = 14;

    public static final Type<ShiraumeCue> TYPE = new Type<>(LogosDimension.id("shiraume_cue"));
    public static final StreamCodec<ByteBuf, ShiraumeCue> CODEC = StreamCodec.of((b, c) -> {
        ByteBufCodecs.VAR_INT.encode(b, c.kind);
        ByteBufCodecs.VAR_INT.encode(b, c.entity + 1);
        ByteBufCodecs.VAR_LONG.encode(b, c.time);
        b.writeDouble(c.pos.x); b.writeDouble(c.pos.y); b.writeDouble(c.pos.z);
        ByteBufCodecs.VAR_INT.encode(b, c.values.length);
        for (float v : c.values) b.writeFloat(v);
    }, b -> {
        int kind = ByteBufCodecs.VAR_INT.decode(b);
        int entity = ByteBufCodecs.VAR_INT.decode(b) - 1;
        long time = ByteBufCodecs.VAR_LONG.decode(b);
        Vec3 pos = new Vec3(b.readDouble(), b.readDouble(), b.readDouble());
        int n = ByteBufCodecs.VAR_INT.decode(b);
        if (n < 0 || n > 64) throw new IllegalArgumentException("白梅提示的参数太多：" + n);
        float[] values = new float[n];
        for (int i = 0; i < n; i++) values[i] = b.readFloat();
        return new ShiraumeCue(kind, entity, time, pos, values);
    });

    @Override public Type<ShiraumeCue> type() { return TYPE; }

    // ---------------------------------------------------------------- 发送

    public static void send(ServerPlayer player, ShiraumeCue cue) {
        if (player.connection != null && player.connection.hasChannel(TYPE)) PacketDistributor.sendToPlayer(player, cue);
    }

    public static void send(Collection<ServerPlayer> players, ShiraumeCue cue) {
        for (ServerPlayer p : players) send(p, cue);
    }

    /** 发给 {@code at} 附近 {@code range} 格内的玩家。 */
    public static void near(ServerLevel level, Vec3 at, double range, ShiraumeCue cue) {
        for (ServerPlayer p : level.players()) if (p.position().distanceToSqr(at) <= range * range) send(p, cue);
    }

    public static ShiraumeCue aberration(float strength) {
        return new ShiraumeCue(ABERRATION, -1, 0, Vec3.ZERO, new float[] {strength});
    }

    public static ShiraumeCue shake(Vec3 at, float power) {
        return new ShiraumeCue(SHAKE, -1, 0, at, new float[] {power});
    }

    public static ShiraumeCue mood(boolean on, AABB box, Vec3 from, long start) {
        return new ShiraumeCue(MOOD, -1, start, from, new float[] {on ? 1 : 0,
                (float) box.minX, (float) box.minY, (float) box.minZ, (float) box.maxX, (float) box.maxY, (float) box.maxZ});
    }

    public static ShiraumeCue title(long start) {
        return new ShiraumeCue(TITLE, -1, start, Vec3.ZERO, new float[0]);
    }

    public static ShiraumeCue pillar(Vec3 center, long start, float[] seals) {
        return new ShiraumeCue(PILLAR, -1, start, center, seals);
    }

    public static ShiraumeCue eyes(Entity who) {
        return new ShiraumeCue(EYES, who.getId(), who.level().getGameTime(), who.position(), new float[0]);
    }

    public static ShiraumeCue spark(Vec3 at, boolean parried) {
        return new ShiraumeCue(SPARK, -1, 0, at, new float[] {parried ? 1 : 0});
    }

    public static ShiraumeCue mark(Vec3 at, float radius, int ticks, long start) {
        return new ShiraumeCue(MARK, -1, start, at, new float[] {radius, ticks});
    }

    public static ShiraumeCue speed(Entity who, float strength, int ticks) {
        return new ShiraumeCue(SPEED, who.getId(), who.level().getGameTime(), who.position(), new float[] {strength, ticks});
    }

    public static ShiraumeCue purge(Entity who, float[] colours) {
        return new ShiraumeCue(PURGE, who.getId(), who.level().getGameTime(), who.position(), colours);
    }

    public static ShiraumeCue guardBreak(Vec3 at) {
        return new ShiraumeCue(GUARD_BREAK, -1, 0, at, new float[0]);
    }

    public static ShiraumeCue lock(Entity who, boolean on) {
        return new ShiraumeCue(LOCK, who.getId(), who.level().getGameTime(), who.position(), new float[] {on ? 1 : 0});
    }

    public static ShiraumeCue counter(Entity who, Vec3 at, float mana) {
        return new ShiraumeCue(COUNTER, who.getId(), who.level().getGameTime(), at, new float[] {mana});
    }

    public static ShiraumeCue ring(Vec3 center, long start) {
        return new ShiraumeCue(RING, -1, start, center, new float[0]);
    }

    public static ShiraumeCue wave(Entity who, Vec3 from, Vec3 dir, long start) {
        return new ShiraumeCue(WAVE, who.getId(), start, from, new float[] {(float) dir.x, (float) dir.z});
    }
}
