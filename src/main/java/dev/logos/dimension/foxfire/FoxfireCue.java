package dev.logos.dimension.foxfire;

import java.util.HashMap;
import java.util.Map;

import dev.logos.dimension.LogosDimension;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jspecify.annotations.Nullable;

/**
 * 狐火只给画面用的提示，客户端在 {@code client/foxfire/FoxfireClient} 处理。没有协商这个通道的连接（GameTest 的假玩家）不发。
 *
 * @param kind   见下面的常量
 * @param flame  颜色（{@link FoxfireFlame} 的序号）
 * @param entity 相关的实体或 Logos 游脉的编号，没有为 -1
 * @param pos    位置
 * @param dir    朝向（命中：从施法者到目标），没有为零
 * @param size   大小（命中：按目标的身形）
 */
public record FoxfireCue(int kind, int flame, int entity, Vec3 pos, Vec3 dir, float size) implements CustomPacketPayload {
    /** 打中：换掉 Logos 的奥秘命中特效，在目标身上炸开一团狐火。 */
    public static final int HIT = 0;
    /** 狐火术式施放时脚下的一圈（换掉 Logos 的奥秘施法圈）。 */
    public static final int CAST = 1;
    /** Logos 的游脉 {@code entity} 是狐火术式的：客户端把它画成狐火的颜色。 */
    public static final int VEIN = 2;
    /** 惑：被狐火打中的玩家画面边缘泛起一圈狐火色，一秒内退掉。 */
    public static final int BEWITCH = 3;
    /** 惑：怪物 {@code entity} 头上绕着几点狐火，它正丢了目标在原地打转。 */
    public static final int DAZE = 4;

    /** 同 Logos 的特效范围。 */
    public static final double RANGE = 96;

    public static final Type<FoxfireCue> TYPE = new Type<>(LogosDimension.id("foxfire_cue"));
    public static final StreamCodec<ByteBuf, FoxfireCue> CODEC = StreamCodec.of((b, c) -> {
        ByteBufCodecs.VAR_INT.encode(b, c.kind);
        ByteBufCodecs.VAR_INT.encode(b, c.flame);
        ByteBufCodecs.VAR_INT.encode(b, c.entity + 1);
        b.writeDouble(c.pos.x);
        b.writeDouble(c.pos.y);
        b.writeDouble(c.pos.z);
        b.writeFloat((float) c.dir.x);
        b.writeFloat((float) c.dir.y);
        b.writeFloat((float) c.dir.z);
        b.writeFloat(c.size);
    }, b -> new FoxfireCue(ByteBufCodecs.VAR_INT.decode(b), ByteBufCodecs.VAR_INT.decode(b), ByteBufCodecs.VAR_INT.decode(b) - 1,
            new Vec3(b.readDouble(), b.readDouble(), b.readDouble()), new Vec3(b.readFloat(), b.readFloat(), b.readFloat()), b.readFloat()));

    @Override
    public Type<FoxfireCue> type() {
        return TYPE;
    }

    public FoxfireFlame flameKind() {
        return FoxfireFlame.byId(flame);
    }

    // ---------------------------------------------------------------- 发送

    public static void send(ServerPlayer player, FoxfireCue cue) {
        if (player.connection != null && player.connection.hasChannel(TYPE)) PacketDistributor.sendToPlayer(player, cue);
    }

    /** 发给 {@code at} 附近 {@code range} 格内的玩家。 */
    public static void near(ServerLevel level, Vec3 at, double range, FoxfireCue cue) {
        for (ServerPlayer p : level.players()) if (p.position().distanceToSqr(at) <= range * range) send(p, cue);
    }

    /** {@code t} 被狐火打中（{@code from} 是施法者的眼睛，没有为 null）：同 Logos 命中特效的位置、朝向和大小。 */
    public static void hit(ServerLevel level, Entity t, @Nullable Vec3 from, FoxfireFlame flame) {
        Vec3 c = t.getBoundingBox().getCenter();
        Vec3 dir = from == null || from.distanceToSqr(c) < 1e-6 ? Vec3.ZERO : c.subtract(from).normalize();
        float scale = (float) Math.max(.6, Math.min(2.5, Math.max(t.getBbWidth(), t.getBbHeight() * .6) / .9));
        near(level, c, RANGE, new FoxfireCue(HIT, flame.ordinal(), t.getId(), c, dir, scale));
    }

    public static void cast(ServerLevel level, Vec3 at, FoxfireFlame flame) {
        near(level, at, RANGE, new FoxfireCue(CAST, flame.ordinal(), -1, at, Vec3.ZERO, 1));
    }

    public static FoxfireCue bewitch(FoxfireFlame flame) {
        return new FoxfireCue(BEWITCH, flame.ordinal(), -1, Vec3.ZERO, Vec3.ZERO, 1);
    }

    public static void daze(ServerLevel level, Entity mob, FoxfireFlame flame, int ticks) {
        near(level, mob.position(), RANGE, new FoxfireCue(DAZE, flame.ordinal(), mob.getId(), mob.position(), Vec3.ZERO, ticks));
    }

    /** 每条游脉上次说过的刻：一秒说一次，后来走近的人也认得出。 */
    private static final Map<Integer, Long> VEINS = new HashMap<>();
    /** 测试用：认出过几次狐火术式的游脉。 */
    private static int veinMarks;

    public static int veinMarks() {
        return veinMarks;
    }

    /** 狐火术式里符文自己放的一团（例如缠上手时那一下）：同打中的样子，只是不跟着谁。 */
    public static void burst(ServerLevel level, Vec3 at, FoxfireFlame flame, float size) {
        near(level, at, RANGE, new FoxfireCue(HIT, flame.ordinal(), -1, at, Vec3.ZERO, size));
    }

    /** Logos 的游脉 {@code id} 是狐火术式的（它每刻发一次头的位置之前调用）。 */
    public static void vein(ServerLevel level, int id, Vec3 near, FoxfireFlame flame) {
        veinMarks++;
        long now = level.getGameTime();
        Long last = VEINS.get(id);
        if (last != null && now - last < 20 && now >= last) return;
        if (VEINS.size() > 256) VEINS.values().removeIf(t -> now - t > 200 || t > now);
        VEINS.put(id, now);
        near(level, near, RANGE + 32, new FoxfireCue(VEIN, flame.ordinal(), id, near, Vec3.ZERO, 1));
    }
}
