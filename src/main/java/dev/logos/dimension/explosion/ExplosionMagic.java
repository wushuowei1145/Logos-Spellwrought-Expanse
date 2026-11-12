package dev.logos.dimension.explosion;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.ServerConfig;
import dev.logos.runtime.FreeManaEntity;
import dev.logos.runtime.PlayerMagic;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.jspecify.annotations.Nullable;

/**
 * 爆裂魔法（致敬《为美好的世界献上祝福》惠惠的爆裂魔法）。手持 {@link ExplosionStaffItem} 在主手按住右键吟唱：
 * <ul>
 *   <li>开始时身上要有 {@link #MANA} 魔力（创造模式不要），之后每刻注入 {@link #DRAIN}，{@link #CHARGE_TICKS} 刻（20 秒）注满；
 *       中途魔力被别处用掉、不够这一刻的份时吟唱停住，直到魔力回来；</li>
 *   <li>目的地是准星指的地方，最远 {@link #RANGE} 格，吟唱中跟着准星缓缓移动；目的地上空先后出现一串红色法阵；</li>
 *   <li>注满后松手放出爆裂魔法（{@link ExplosionBlast}）；没注满就松手、换手、死亡或离开，吟唱中断，已注入的魔力散成游离魔力（与 Logos 的法杖相同）。</li>
 *   <li>放完之后魔力耗尽，施法者趴下 30 秒（{@link ExplosionExhaustion}）。</li>
 * </ul>
 * 爆炸半径和是否破坏地形在服务端配置的 {@code [explosion]} 一节。说明见 docs/EXPLOSION_MAGIC.md。
 */
public final class ExplosionMagic {
    public static final double MANA = 6400;
    public static final int CHARGE_TICKS = 400;
    public static final double DRAIN = MANA / CHARGE_TICKS;
    /** 目的地最远多少格。 */
    public static final double RANGE = 160;
    /** 多远的玩家收得到吟唱和爆炸的特效。 */
    public static final double VIEW = 512;
    /** 目的地每刻最多挪多少格：准星扫过去时法阵跟着滑过去，不会一下跳走。 */
    private static final double FOLLOW = 3;
    public static final ResourceKey<DamageType> DAMAGE = ResourceKey.create(Registries.DAMAGE_TYPE, LogosDimension.id("explosion_magic"));

    private static final Map<UUID, Charge> CHARGES = new HashMap<>();
    private static final List<ExplosionBlast> BLASTS = new ArrayList<>();

    private ExplosionMagic() {}

    /** 一次吟唱。{@code free}：创造模式，不扣魔力。 */
    static final class Charge {
        final long start;
        final boolean free;
        Vec3 target;
        double paid;
        boolean full;
        int circles;

        Charge(long start, Vec3 target, boolean free) {
            this.start = start;
            this.target = target;
            this.free = free;
        }

        float progress() {
            return (float) Mth.clamp(paid / MANA, 0, 1);
        }
    }

    public static void register(IEventBus bus) {
        bus.addListener((RegisterPayloadHandlersEvent event) -> event.registrar("1").playToClient(ExplosionPayload.TYPE, ExplosionPayload.CODEC));
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> BLASTS.removeIf(b -> !b.tick()));
        ExplosionExhaustion.register();
        ExplosionStaffPage.register(bus);
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> {
            CHARGES.clear();
            BLASTS.clear();
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer p) cancel(p);
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerChangedDimensionEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer p) cancel(p);
        });
        NeoForge.EVENT_BUS.addListener((LivingDeathEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer p) cancel(p);
        });
    }

    // ---- 吟唱 ------------------------------------------------------------------------------------

    /** 开始吟唱；不行时告诉玩家原因并返回 false。 */
    public static boolean begin(ServerPlayer p, ItemStack staff, InteractionHand hand) {
        cancel(p);
        if (hand != InteractionHand.MAIN_HAND) {
            PlayerMagic.alert(p, Component.translatable("message.logos_dimension.explosion.main_hand").withStyle(ChatFormatting.YELLOW));
            return false;
        }
        boolean free = p.hasInfiniteMaterials();
        double mana = PlayerMagic.of(p).mana;
        if (!free && mana < MANA) {
            PlayerMagic.alert(p, Component.translatable("message.logos_dimension.explosion.mana", (int) MANA, (int) mana).withStyle(ChatFormatting.RED));
            return false;
        }
        Vec3 target = aim(p);
        if (target == null) {
            PlayerMagic.alert(p, Component.translatable("message.logos_dimension.explosion.no_target", (int) RANGE).withStyle(ChatFormatting.YELLOW));
            return false;
        }
        ServerLevel level = p.level();
        Charge c = new Charge(level.getGameTime(), target, free);
        CHARGES.put(p.getUUID(), c);
        sync(p, c);
        sound(level, p.position(), SoundEvents.BEACON_ACTIVATE, 1.6f, .55f);
        sound(level, p.position(), SoundEvents.AMETHYST_BLOCK_RESONATE, 1.2f, .6f);
        return true;
    }

    /** 按住的每一刻：注入魔力、跟着准星挪目的地、发进度。 */
    public static void tick(ServerPlayer p, ItemStack staff) {
        Charge c = CHARGES.get(p.getUUID());
        if (c == null) return;
        ServerLevel level = p.level();
        long now = level.getGameTime(), t = now - c.start;
        if (!c.full) {
            double owed = Math.min(DRAIN, MANA - c.paid);
            if (c.free) {
                c.paid += owed;
            } else {
                PlayerMagic m = PlayerMagic.of(p);
                if (m.mana + 1e-9 >= owed) {
                    m.mana = Math.max(0, m.mana - owed);
                    c.paid += owed;
                } else if (t % 20 == 0) {
                    PlayerMagic.alert(p, Component.translatable("message.logos_dimension.explosion.stalled").withStyle(ChatFormatting.RED));
                }
            }
            if (c.paid >= MANA - 1e-6) {
                c.paid = MANA;
                c.full = true;
                sync(p, c);
                sound(level, p.position(), SoundEvents.BEACON_POWER_SELECT, 2, .7f);
                sound(level, p.position(), SoundEvents.BELL_RESONATE, 2, 1.3f);
                sound(level, p.position(), SoundEvents.AMETHYST_BLOCK_CHIME, 2, 1.6f);
                // 魔力从身上涌出的那一下气浪
                sound(level, p.position(), SoundEvents.BREEZE_WIND_CHARGE_BURST.value(), 2, .55f);
                sound(level, p.position(), SoundEvents.WIND_CHARGE_BURST.value(), 1.6f, .7f);
            }
        }
        if (t % 2 == 0) {
            Vec3 aim = aim(p);
            if (aim != null) c.target = approach(c.target, aim, FOLLOW * 2);
        }
        int circles = circles(c.progress());
        if (circles > c.circles) {
            c.circles = circles;
            sound(level, p.position(), SoundEvents.RESPAWN_ANCHOR_CHARGE, 1.4f, .5f + .12f * circles);
        }
        if (!c.full && t % 30 == 0) sound(level, p.position(), SoundEvents.BEACON_AMBIENT, 1.4f, .6f + .9f * c.progress());
        if (t % 20 == 10 && c.target.distanceTo(p.position()) < radius() + 4) {
            PlayerMagic.alert(p, Component.translatable("message.logos_dimension.explosion.too_close").withStyle(ChatFormatting.GOLD));
        }
        if (t % 2 == 0 && !c.full) sync(p, c);
        else if (c.full && t % 10 == 0) sync(p, c);
    }

    /** 松手：注满了就放出爆裂魔法并返回 true；没注满留给 {@link #cancel}。 */
    public static boolean release(ServerPlayer p) {
        Charge c = CHARGES.get(p.getUUID());
        if (c == null || !c.full) return false;
        CHARGES.remove(p.getUUID());
        fire(p.level(), c.target, p);
        // 魔力耗尽：球体散尽后趴下
        ExplosionExhaustion.schedule(p, ExplosionTimeline.GONE_AT);
        return true;
    }

    /** 吟唱没放出去就结束了：已注入的魔力散成游离魔力。 */
    public static void cancel(ServerPlayer p) {
        Charge c = CHARGES.remove(p.getUUID());
        if (c == null) return;
        ServerLevel level = p.level();
        send(level, p.position(), c.target, ExplosionPayload.stop(p.getId()));
        sound(level, p.position(), SoundEvents.FIRE_EXTINGUISH, 1, .5f);
        sound(level, p.position(), SoundEvents.BEACON_DEACTIVATE, 1.2f, .7f);
        if (c.free || c.paid <= 0) return;
        FreeManaEntity.release(level, p.getEyePosition().add(p.getLookAngle().scale(.8)), c.paid);
        PlayerMagic.alert(p, Component.translatable("message.logos_dimension.explosion.fizzled", (int) c.paid).withStyle(ChatFormatting.GRAY));
    }

    /** 在 {@code target} 放出爆裂魔法（松手、指令和世界测试都走这里）。{@code caster} 可以为 null。 */
    public static void fire(ServerLevel level, Vec3 target, @Nullable ServerPlayer caster) {
        double radius = radius();
        boolean terrain = ServerConfig.EXPLOSION_BREAKS_TERRAIN.get()
                && (caster == null || !caster.blockActionRestricted(level, BlockPos.containing(target), caster.gameMode.getGameModeForPlayer()));
        long seed = level.getRandom().nextLong();
        BLASTS.add(new ExplosionBlast(level, target, radius, caster == null ? null : caster.getUUID(), seed, terrain));
        Vec3 from = caster == null ? target : caster.position();
        send(level, from, target, ExplosionPayload.release(caster == null ? -1 : caster.getId(), target, (float) radius, seed));
        if (caster != null) {
            sound(level, from, SoundEvents.EVOKER_CAST_SPELL, 2, .6f);
            sound(level, from, SoundEvents.BEACON_DEACTIVATE, 2, .5f);
        }
    }

    public static double radius() {
        return ServerConfig.EXPLOSION_RADIUS.get();
    }

    // ---- 世界测试和指令看的状态 ----------------------------------------------------------------------

    /** 这次吟唱注入了多少魔力；没在吟唱时为 -1。 */
    public static double paid(ServerPlayer p) {
        Charge c = CHARGES.get(p.getUUID());
        return c == null ? -1 : c.paid;
    }

    public static boolean full(ServerPlayer p) {
        Charge c = CHARGES.get(p.getUUID());
        return c != null && c.full;
    }

    public static @Nullable Vec3 target(ServerPlayer p) {
        Charge c = CHARGES.get(p.getUUID());
        return c == null ? null : c.target;
    }

    /** 还在进行中的爆炸数。 */
    public static int blasts() {
        return BLASTS.size();
    }

    /** 最近一次挖完的坑挖掉了多少方块；还没有时为 -1（世界测试和客户端场景看）。 */
    public static long carved() {
        return lastCarved;
    }

    static long lastCarved = -1;

    // ---- 小工具 ----------------------------------------------------------------------------------

    /** 吟唱进度到 {@code progress} 时目的地上空已经出现了几个法阵。 */
    public static int circles(float progress) {
        int n = ExplosionTimeline.CIRCLE_HEIGHT.length;
        return Mth.clamp((int) Math.floor((progress - .04f) / .8f * n) + 1, 0, n);
    }

    /** 准星指的地方：看着方块就是方块上那一点；看着天空就从最远处往下落到地面（那一列没加载时为 null）。 */
    static @Nullable Vec3 aim(ServerPlayer p) {
        ServerLevel level = p.level();
        Vec3 eye = p.getEyePosition(), end = eye.add(p.getLookAngle().scale(RANGE));
        var hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, p));
        if (hit.getType() != HitResult.Type.MISS) return hit.getLocation();
        BlockPos column = BlockPos.containing(end);
        if (!level.hasChunkAt(column)) return null;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, column.getX(), column.getZ());
        if (y <= level.getMinY()) return null;
        return new Vec3(end.x, y, end.z);
    }

    private static Vec3 approach(Vec3 from, Vec3 to, double step) {
        Vec3 d = to.subtract(from);
        double len = d.length();
        return len <= step ? to : from.add(d.scale(step / len));
    }

    private static void sync(ServerPlayer p, Charge c) {
        send(p.level(), p.position(), c.target, ExplosionPayload.charge(p.getId(), c.progress(), c.full, c.target, (float) radius()));
    }

    /** 发给离施法者或目的地 {@link #VIEW} 格以内、和服务端协商过本附属频道的玩家（游戏测试里的假玩家没有）。 */
    private static void send(ServerLevel level, Vec3 a, Vec3 b, ExplosionPayload payload) {
        for (ServerPlayer sp : level.players()) {
            if (!sp.connection.hasChannel(ExplosionPayload.TYPE)) continue;
            if (sp.distanceToSqr(a) <= VIEW * VIEW || sp.distanceToSqr(b) <= VIEW * VIEW) PacketDistributor.sendToPlayer(sp, payload);
        }
    }

    private static void sound(ServerLevel level, Vec3 at, SoundEvent sound, float volume, float pitch) {
        level.playSound(null, at.x, at.y, at.z, sound, SoundSource.PLAYERS, volume, pitch);
    }
}
