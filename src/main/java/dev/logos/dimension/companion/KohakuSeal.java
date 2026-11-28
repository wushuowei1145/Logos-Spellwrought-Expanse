package dev.logos.dimension.companion;

import dev.logos.dimension.LogosDimension;
import io.netty.buffer.ByteBuf;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * 琥珀奥义的凝时（docs/KOHAKU_COMPANION.md 第 20 节）：阵里的普通敌怪被封进琥珀里一阵——不走、不跳、不追、不打（打出去的伤害作废），
 * 只用临时的属性修饰，存档时不会留下来。时间到了就放开；接力馈赠「炼金回流」可以把它按住（{@link #hold}），直到下一段打完。
 *
 * <p>看起来：客户端在被封的那只身上画一层琥珀晶壳（{@link Payload} 告诉看得见它的玩家封了多久），放开时晶壳碎掉。
 * 不用 Logos 的时停：那个会把这段时间里挨的伤害攒到最后一起结算，接在后面的奥义（龙炎碎甲的穿甲、狐火的二次引爆）就对不上了。
 */
@EventBusSubscriber(modid = LogosDimension.ID)
public final class KohakuSeal {
    private static final Identifier HOLD = LogosDimension.id("kohaku_seal");
    private static final List<Holder<Attribute>> STILL = List.of(Attributes.MOVEMENT_SPEED, Attributes.FLYING_SPEED, Attributes.JUMP_STRENGTH);
    /** 发给客户端的「按住了，什么时候放另说」。 */
    public static final int HELD = -1;

    /**
     * 一只被封住的：还剩多少刻（{@link #HELD} 是被馈赠按住了，0 是放开）。
     */
    public record Payload(int entity, int ticks) implements CustomPacketPayload {
        public static final Type<Payload> TYPE = new Type<>(LogosDimension.id("kohaku_seal"));
        public static final StreamCodec<ByteBuf, Payload> CODEC = StreamCodec.of((b, p) -> {
            ByteBufCodecs.VAR_INT.encode(b, p.entity);
            ByteBufCodecs.VAR_INT.encode(b, p.ticks + 1);
        }, b -> new Payload(ByteBufCodecs.VAR_INT.decode(b), ByteBufCodecs.VAR_INT.decode(b) - 1));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static final class Seal {
        final LivingEntity entity;
        long until;
        int holds;

        Seal(LivingEntity entity) {
            this.entity = entity;
        }
    }

    private static final Map<UUID, Seal> SEALS = new HashMap<>();

    private KohakuSeal() {}

    @SubscribeEvent
    static void payloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToClient(Payload.TYPE, Payload.CODEC);
    }

    /** 把 {@code e} 封 {@code ticks} 刻；已经封着的取长的那个。 */
    public static void seal(LivingEntity e, int ticks) {
        if (!(e.level() instanceof ServerLevel level)) return;
        Seal s = SEALS.computeIfAbsent(e.getUUID(), id -> new Seal(e));
        s.until = Math.max(s.until, level.getGameTime() + ticks);
        still(e);
        send(s, level.getGameTime());
    }

    /** {@code e} 此刻被封着。 */
    public static boolean sealed(Entity e) {
        return SEALS.containsKey(e.getUUID());
    }

    /** 被封着的有几只（测试用）。 */
    public static int count() {
        return SEALS.size();
    }

    /** 按住：时间到了也不放，直到 {@link #release}（接力馈赠交出以后，下一段打完之前）。 */
    static void hold(UUID id) {
        Seal s = SEALS.get(id);
        if (s == null) return;
        s.holds++;
        if (s.entity.level() instanceof ServerLevel level) send(s, level.getGameTime());
    }

    /** 放开一次按住；时间早就到了的这时候放开。 */
    static void release(UUID id) {
        Seal s = SEALS.get(id);
        if (s == null || s.holds <= 0) return;
        s.holds--;
        if (s.entity.level() instanceof ServerLevel level) send(s, level.getGameTime());
    }

    @SubscribeEvent
    static void tick(ServerTickEvent.Post event) {
        if (SEALS.isEmpty()) return;
        for (Iterator<Seal> it = SEALS.values().iterator(); it.hasNext(); ) {
            Seal s = it.next();
            LivingEntity e = s.entity;
            if (!e.isAlive() || e.isRemoved() || !(e.level() instanceof ServerLevel level)) {
                free(e);
                it.remove();
                continue;
            }
            if (s.holds <= 0 && level.getGameTime() >= s.until) {
                free(e);
                it.remove();
                PacketDistributor.sendToPlayersTrackingEntity(e, new Payload(e.getId(), 0));
                Vec3 c = e.position().add(0, e.getBbHeight() / 2, 0);
                level.sendParticles(new DustParticleOptions(KohakuCompanion.AMBER, 1f), c.x, c.y, c.z, 16, e.getBbWidth() / 2, e.getBbHeight() / 3,
                        e.getBbWidth() / 2, 0);
                level.playSound(null, c.x, c.y, c.z, SoundEvents.AMETHYST_CLUSTER_BREAK, SoundSource.NEUTRAL, .5f, 1.6f);
                continue;
            }
            still(e);
            if (e instanceof Mob m) {
                m.getNavigation().stop();
                m.setTarget(null);
                m.setAggressive(false);
                m.stopUsingItem();
            }
            Vec3 v = e.getDeltaMovement();
            e.setDeltaMovement(0, Math.min(v.y, 0), 0);
        }
    }

    /** 被封着的打出去的伤害作废（它直接打的，或者它射出去的）。 */
    @SubscribeEvent
    static void damage(LivingIncomingDamageEvent event) {
        if (SEALS.isEmpty()) return;
        DamageSource source = event.getSource();
        for (Entity e : new Entity[]{source.getEntity(), source.getDirectEntity()}) {
            if (e != null && e != event.getEntity() && sealed(e)) {
                event.setCanceled(true);
                return;
            }
        }
    }

    @SubscribeEvent
    static void stopped(ServerStoppedEvent event) {
        SEALS.clear();
    }

    private static void still(LivingEntity e) {
        for (Holder<Attribute> a : STILL) {
            AttributeInstance inst = e.getAttribute(a);
            if (inst != null && !inst.hasModifier(HOLD))
                inst.addTransientModifier(new AttributeModifier(HOLD, -1, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
    }

    private static void free(LivingEntity e) {
        for (Holder<Attribute> a : STILL) {
            AttributeInstance inst = e.getAttribute(a);
            if (inst != null) inst.removeModifier(HOLD);
        }
    }

    private static void send(Seal s, long now) {
        int left = s.holds > 0 ? HELD : (int) Math.max(1, s.until - now);
        PacketDistributor.sendToPlayersTrackingEntity(s.entity, new Payload(s.entity.getId(), left));
    }
}
