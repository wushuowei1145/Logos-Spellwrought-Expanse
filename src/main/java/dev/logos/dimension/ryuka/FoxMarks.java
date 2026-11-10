package dev.logos.dimension.ryuka;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.foxfire.Foxfire;
import dev.logos.dimension.foxfire.FoxfireCue;
import dev.logos.dimension.foxfire.FoxfireFlame;
import dev.logos.dimension.foxfire.FoxfireRules;
import io.netty.buffer.ByteBuf;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * 狐火印（docs/RYUKA.md 第 3.2 节）：流火每伤到一个目标一次，就在它身上留一枚印；攒满 {@link #FULL} 枚时一齐爆开，
 * 打出一下高额的淡粉狐火（{@link #BURST}），随后目标狐火缠身 {@link #BURN_TICKS} 刻（平时的刀只缠 3 秒）。
 * 爆开之后 {@link #WINDOW} 刻里目标可以被处决（{@code RyukaExecution}）；处决一开始就用掉这次爆开，要再处决就再攒满一次。
 * <ul>
 *   <li>印跟着目标走，谁的流火打的都算；最后一枚是谁打的，爆开就记在谁名下。</li>
 *   <li>隔 {@link #LIFE} 刻没有再添印，身上的印就散了，从头攒。</li>
 *   <li>只有流火的刀（连招、突刺、上挑、樱花刀气）留印；爆开、缠身每秒烧的、处决的刀都不留。</li>
 *   <li>同族（神狐、白梅）也会被留印、爆开，伤害照狐火的规矩只剩四分之一，也不会缠身。</li>
 * </ul>
 */
public final class FoxMarks {
    /** 攒满几枚爆开。 */
    public static final int FULL = 5;
    /** 隔多久没再添印就散了（刻）。 */
    public static final int LIFE = 120;
    /** 爆开那一下的狐火伤害。 */
    public static final float BURST = 30;
    /** 爆开之后狐火缠身多久（刻）。 */
    public static final int BURN_TICKS = 160;
    /** 爆开之后多久之内可以处决（刻），和爆开后的缠身一样长。 */
    public static final int WINDOW = 160;

    private record Marks(int count, long last) {}
    private static final Map<LivingEntity, Marks> MARKS = new WeakHashMap<>();
    /** 爆开过、还能被处决的目标，到哪一刻为止。 */
    private static final Map<LivingEntity, Long> OPEN = new WeakHashMap<>();
    /** 测试用：一共爆开过几次。 */
    private static int bursts;

    private FoxMarks() {}

    static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(Payload.TYPE, Payload.CODEC);
    }

    /** {@code target} 身上现在有几枚印（散了的不算）。 */
    public static int count(LivingEntity target) {
        Marks m = MARKS.get(target);
        return m == null || target.level().getGameTime() - m.last > LIFE ? 0 : m.count;
    }

    public static int bursts() { return bursts; }

    /** 清掉 {@code target} 身上的印和爆开后的处决窗口（测试用）。 */
    public static void clear(LivingEntity target) {
        boolean had = MARKS.remove(target) != null;
        if (OPEN.remove(target) != null) sync(target, 0, Payload.CLOSED);
        else if (had) sync(target, 0, 0);
    }

    /** {@code target} 爆开过，现在还能被处决。 */
    public static boolean executable(LivingEntity target) {
        Long until = OPEN.get(target);
        return until != null && target.level().getGameTime() < until;
    }

    /** 处决开始：用掉这次爆开（身上新攒的印照旧）。 */
    public static void consume(LivingEntity target) {
        if (OPEN.remove(target) != null) sync(target, count(target), Payload.CLOSED);
    }

    /** 流火的这一下打进去了：添一枚印，满了就爆开。返回爆开没有。 */
    public static boolean add(ServerPlayer by, LivingEntity target) {
        if (!target.isAlive() || !(target.level() instanceof ServerLevel level)) return false;
        long now = level.getGameTime();
        int count = count(target) + 1;
        if (count < FULL) {
            MARKS.put(target, new Marks(count, now));
            sync(target, count, 0);
            return false;
        }
        MARKS.remove(target);
        OPEN.put(target, now + WINDOW);
        sync(target, 0, Payload.BURST);
        burst(by, target, level);
        return true;
    }

    /** 爆开：一团大的淡粉狐火，高额伤害，之后狐火缠身。 */
    private static void burst(ServerPlayer by, LivingEntity target, ServerLevel level) {
        bursts++;
        Vec3 center = target.getBoundingBox().getCenter();
        float size = Math.max(1.6f, target.getBbHeight() * 1.2f);
        FoxfireCue.burst(level, center, FoxfireFlame.SHIRAUME, size);
        level.playSound(null, center.x, center.y, center.z, SoundEvents.SOUL_ESCAPE.value(), SoundSource.PLAYERS, 1.2f, 1.4f);
        level.playSound(null, center.x, center.y, center.z, SoundEvents.FIRECHARGE_USE, SoundSource.PLAYERS, .8f, 1.3f);
        // 紧跟在流火那一刀后面，目标还在受击冷却里：不清掉的话这一下只算比刚才那刀多出来的部分
        target.invulnerableTime = 0;
        // 惑照常有机会，但缠身单独点（时长比平时的长）
        Foxfire.deal(by, target, BURST, new Foxfire.Context(FoxfireFlame.SHIRAUME, true, false));
        if (target.isAlive()) FoxfireRules.burn(target, FoxfireFlame.SHIRAUME, by, BURN_TICKS);
    }

    private static void sync(LivingEntity target, int count, int event) {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(target, new Payload(target.getId(), count, event));
    }

    /**
     * 告诉客户端目标身上有几枚印，以及发生了什么：{@link #BURST} 刚爆开（之后 {@link FoxMarks#WINDOW} 刻里可以处决），
     * {@link #CLOSED} 处决窗口被用掉或清掉。印和窗口到点散掉由客户端按同样的时长自己算。
     */
    public record Payload(int entity, int count, int event) implements CustomPacketPayload {
        public static final int BURST = 1, CLOSED = 2;
        public static final Type<Payload> TYPE = new Type<>(LogosDimension.id("ryuka_marks"));
        public static final StreamCodec<ByteBuf, Payload> CODEC = StreamCodec.of((b, p) -> {
            b.writeInt(p.entity);
            b.writeByte(p.count);
            b.writeByte(p.event);
        }, b -> new Payload(b.readInt(), b.readUnsignedByte(), b.readUnsignedByte()));
        public boolean burst() { return event == BURST; }
        public boolean closed() { return event == CLOSED; }
        @Override public Type<Payload> type() { return TYPE; }
    }
}
