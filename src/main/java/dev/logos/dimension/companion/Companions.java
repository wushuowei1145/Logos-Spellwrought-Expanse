package dev.logos.dimension.companion;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.dissolve.DissolveShape;
import dev.logos.dimension.dissolve.Dissolving;
import dev.logos.dimension.foxfire.FoxfireFlame;
import dev.logos.dimension.shiraume.ShiraumeEntities;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.jspecify.annotations.Nullable;

/**
 * 白梅（少女形态）作为玩家同伴的入口（docs/SHIRAUME_COMPANION.md）：实体注册、玩家身上的「契」、召唤与收回，
 * 以及喂给玩家模型（{@link OwnerWatch}）的事件。
 *
 * <p>她跟着主人走：主人下线、死亡时悄悄收起，上线、重生时再叫出来；主人换维度时在新维度重新现身。
 * 打倒之后要在形代里休息 {@link #REST_TICKS} 刻才能再叫。
 */
public final class Companions {
    private static final DeferredRegister<EntityType<?>> TYPES = DeferredRegister.create(Registries.ENTITY_TYPE, LogosDimension.ID);
    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, LogosDimension.ID);

    /** 发顶约 1.24 格、耳尖约 1.37 格（2 倍制作，渲染缩放 0.5）；眼睛约 0.95 格。 */
    public static final Supplier<EntityType<ShiraumeCompanion>> SHIRAUME = TYPES.register("shiraume_companion", () ->
            EntityType.Builder.of(ShiraumeCompanion::new, MobCategory.MISC).sized(.6f, 1.3f).eyeHeight(.95f).clientTrackingRange(10)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, LogosDimension.id("shiraume_companion"))));

    /** 她倒下时留在原地、替她烧掉的形代。不存档。 */
    public static final Supplier<EntityType<Katashiro>> KATASHIRO = TYPES.register("shiraume_katashiro", () ->
            EntityType.Builder.<Katashiro>of(Katashiro::new, MobCategory.MISC).sized(.4f, .1f).clientTrackingRange(8).noSave()
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, LogosDimension.id("shiraume_katashiro"))));

    /** 打倒后在形代里休息多久（5 分钟）。 */
    public static final int REST_TICKS = 6000;

    /**
     * 玩家和白梅的契：她此刻在不在外面（{@code out}）、休息到哪一刻、找回了几条尾巴（尾之契，第一版恒为 0）、
     * 被悄悄收起时是否该再叫出来（下线、死亡、换维度之后）。死亡不清除。
     */
    public record Bond(Optional<UUID> out, long restUntil, int tails, boolean comeBack) {
        public static final Bond NONE = new Bond(Optional.empty(), 0, 0, false);
        static final Codec<Bond> CODEC = RecordCodecBuilder.create(i -> i.group(
                UUIDUtil.CODEC.optionalFieldOf("out").forGetter(Bond::out),
                Codec.LONG.optionalFieldOf("rest_until", 0L).forGetter(Bond::restUntil),
                Codec.INT.optionalFieldOf("tails", 0).forGetter(Bond::tails),
                Codec.BOOL.optionalFieldOf("come_back", false).forGetter(Bond::comeBack)).apply(i, Bond::new));

        Bond withOut(@Nullable UUID id) {
            return new Bond(Optional.ofNullable(id), restUntil, tails, comeBack);
        }

        Bond withRest(long until) {
            return new Bond(out, until, tails, comeBack);
        }

        Bond withComeBack(boolean back) {
            return new Bond(out, restUntil, tails, back);
        }
    }

    public static final Supplier<AttachmentType<Bond>> BOND = ATTACHMENTS.register("shiraume_bond",
            () -> AttachmentType.builder(() -> Bond.NONE).serialize(Bond.CODEC.fieldOf("bond")).copyOnDeath().build());

    /** 等到下一刻再叫出来的主人（上线、重生、换维度之后）：主人 UUID → 还要等几刻。 */
    private static final Map<UUID, Integer> PENDING = new HashMap<>();

    private Companions() {}

    public static void register(IEventBus bus) {
        TYPES.register(bus);
        ATTACHMENTS.register(bus);
        bus.addListener((EntityAttributeCreationEvent event) -> event.put(SHIRAUME.get(), ShiraumeCompanion.createAttributes().build()));
        NeoForge.EVENT_BUS.addListener(Companions::onBreak);
        NeoForge.EVENT_BUS.addListener(Companions::onPlace);
        NeoForge.EVENT_BUS.addListener(Companions::onDamaged);
        NeoForge.EVENT_BUS.addListener(Companions::onDeath);
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p) stow(p, true);
            OwnerWatch.forget(e.getEntity().getUUID());
            PENDING.remove(e.getEntity().getUUID());
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p) comeBackLater(p, 40);
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerRespawnEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p && !e.isEndConquered()) comeBackLater(p, 20);
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerChangedDimensionEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p && p.getData(BOND).comeBack()) comeBackLater(p, 10);
        });
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> tickPending(e.getServer()));
    }

    // ---------------------------------------------------------------- 召唤与收回

    /** 她此刻在外面的那一位（只在主人所在的维度找）。 */
    public static @Nullable ShiraumeCompanion out(ServerPlayer owner) {
        Optional<UUID> id = owner.getData(BOND).out();
        if (id.isEmpty()) return null;
        Entity e = owner.level().getEntity(id.get());
        return e instanceof ShiraumeCompanion c && c.isAlive() ? c : null;
    }

    /** 还要休息多少刻（0 表示随时能叫）。 */
    public static long restLeft(ServerPlayer owner) {
        return Math.max(0, owner.getData(BOND).restUntil() - owner.level().getGameTime());
    }

    /** 叫她出来，在主人身边；失败时返回原因。{@code quiet} 为真时不提示（自动叫回）。 */
    public static @Nullable Component summon(ServerPlayer owner, boolean quiet) {
        return summon(owner, quiet, null);
    }

    /** 叫她出来；{@code at} 不为空时就在那里现身（测试用），否则在主人身边。 */
    public static @Nullable Component summon(ServerPlayer owner, boolean quiet, @Nullable Vec3 at) {
        ServerLevel level = owner.level();
        if (out(owner) != null) return Component.translatable("message.logos_dimension.companion.already_out");
        long left = restLeft(owner);
        if (left > 0) return Component.translatable("message.logos_dimension.companion.resting", (left + 19) / 20);
        ShiraumeCompanion c = SHIRAUME.get().create(level, EntitySpawnReason.MOB_SUMMONED);
        if (c == null) return Component.literal("?");
        if (at == null) at = spotNear(level, owner, 2.2, c);
        float yaw = (float) (Mth.atan2(owner.getZ() - at.z, owner.getX() - at.x) * Mth.RAD_TO_DEG) - 90;
        c.snapTo(at.x, at.y, at.z, yaw, 0);
        c.setYHeadRot(yaw);
        c.setYBodyRot(yaw);
        c.bind(owner);
        // 先定好入场动画再放进世界：客户端第一次看到她时就是入场的样子
        c.startSummon();
        level.addFreshEntity(c);
        owner.setData(BOND, owner.getData(BOND).withOut(c.getUUID()).withComeBack(true));
        appearBurst(level, c.position().add(0, .75, 0));
        return null;
    }

    /** 让她回去：挥挥手，化作花瓣散去。 */
    public static boolean dismiss(ServerPlayer owner) {
        ShiraumeCompanion c = out(owner);
        owner.setData(BOND, owner.getData(BOND).withOut(null).withComeBack(false));
        if (c == null) return false;
        c.leave();
        return true;
    }

    /** 悄悄收起（主人下线、死亡、换维度）：不放特效，之后自动叫回。 */
    static void stow(ServerPlayer owner, boolean comeBack) {
        ShiraumeCompanion c = out(owner);
        if (c != null) c.discard();
        Bond b = owner.getData(BOND);
        owner.setData(BOND, b.withOut(null).withComeBack(comeBack && (c != null || b.comeBack())));
    }

    /** 她倒下了：记下休息到哪一刻；之后不会自动叫回。 */
    static void fell(ServerPlayer owner, ShiraumeCompanion c) {
        Bond b = owner.getData(BOND);
        if (b.out().isPresent() && !b.out().get().equals(c.getUUID())) return;
        owner.setData(BOND, b.withOut(null).withRest(owner.level().getGameTime() + REST_TICKS).withComeBack(false));
    }

    /** 测试用：清掉休息时间。 */
    public static void wake(ServerPlayer owner) {
        owner.setData(BOND, owner.getData(BOND).withRest(0));
    }

    private static void comeBackLater(ServerPlayer p, int ticks) {
        if (p.getData(BOND).comeBack()) PENDING.put(p.getUUID(), ticks);
    }

    private static void tickPending(net.minecraft.server.MinecraftServer server) {
        if (PENDING.isEmpty()) return;
        List<UUID> due = new ArrayList<>();
        PENDING.replaceAll((id, t) -> t - 1);
        PENDING.forEach((id, t) -> {
            if (t <= 0) due.add(id);
        });
        for (UUID id : due) {
            PENDING.remove(id);
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null || !p.isAlive() || !p.getData(BOND).comeBack()) continue;
            ShiraumeCompanion old = out(p);
            if (old != null) continue;
            p.setData(BOND, p.getData(BOND).withOut(null));
            summon(p, true);
        }
    }

    /** 换维度时旧的那位留在了原来的维度：在新维度现身之前先收掉。 */
    static void dropStale(ServerPlayer owner, ShiraumeCompanion c) {
        Optional<UUID> id = owner.getData(BOND).out();
        if (id.isEmpty() || !id.get().equals(c.getUUID())) c.discard();
    }

    // ---------------------------------------------------------------- 位置与特效

    /** 主人身边 {@code radius} 格左右、脚下踩得住、站得下的一点；找不到就是主人脚下。 */
    static Vec3 spotNear(ServerLevel level, Player owner, double radius, Entity who) {
        double base = Math.toRadians(owner.getYRot() + 90);
        for (int i = 0; i < 16; i++) {
            double a = base + (i % 2 == 0 ? 1 : -1) * (i / 2) * Math.PI / 8 + Math.PI / 4;
            Vec3 p = owner.position().add(Math.cos(a) * radius, 0, Math.sin(a) * radius);
            BlockPos at = BlockPos.containing(p);
            for (int dy = 2; dy >= -3; dy--) {
                BlockPos feet = at.above(dy);
                if (level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP)
                        && level.noCollision(who, who.getDimensions(who.getPose()).makeBoundingBox(Vec3.atBottomCenterOf(feet)))) {
                    return new Vec3(p.x, feet.getY(), p.z);
                }
            }
        }
        return owner.position();
    }

    /** 召唤时的一团淡粉狐火：樱花瓣、淡粉的尘，一声狐狸的闪现声。 */
    static void appearBurst(ServerLevel level, Vec3 at) {
        level.sendParticles(ParticleTypes.CHERRY_LEAVES, at.x, at.y, at.z, 40, .45, .6, .45, .04);
        level.sendParticles(new DustParticleOptions(FoxfireFlame.SHIRAUME.core, 1.6f), at.x, at.y, at.z, 30, .35, .45, .35, 0);
        level.sendParticles(ParticleTypes.END_ROD, at.x, at.y, at.z, 8, .2, .3, .2, .03);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.FOX_TELEPORT, SoundSource.NEUTRAL, 1, 1.35f);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.NEUTRAL, 1.2f, 1.2f);
    }

    /** 离场：花瓣消散（docs/DISSOLVE.md）。 */
    static void dissolve(ShiraumeCompanion c, int ticks) {
        Dissolving.entity(c, DissolveShape.parse("down"), ticks, ShiraumeEntities.CHERRY | Dissolving.PETALS);
    }

    // ---------------------------------------------------------------- 玩家模型的事件

    private static void onBreak(BreakBlockEvent e) {
        if (e.getPlayer() instanceof ServerPlayer p) OwnerWatch.of(p).mined(p.level().getGameTime());
    }

    private static void onPlace(BlockEvent.EntityPlaceEvent e) {
        if (e.getEntity() instanceof ServerPlayer p) OwnerWatch.of(p).built(p.level().getGameTime());
    }

    private static void onDamaged(LivingDamageEvent.Post e) {
        LivingEntity victim = e.getEntity();
        long now = victim.level().getGameTime();
        Entity by = e.getSource().getEntity();
        if (victim instanceof ServerPlayer p) {
            OwnerWatch.of(p).hurt(now, e.getInflictedDamage(), by instanceof LivingEntity l && l != p ? l : null,
                    e.getSource().is(net.minecraft.tags.DamageTypeTags.IS_FALL));
        }
        if (by instanceof ServerPlayer p && victim != p) OwnerWatch.of(p).attacked(now, victim);
        if (e.getSource().getDirectEntity() instanceof Projectile proj && proj.getOwner() instanceof ServerPlayer p && victim != p) {
            OwnerWatch.of(p).attacked(now, victim);
        }
    }

    private static void onDeath(LivingDeathEvent e) {
        LivingEntity dead = e.getEntity();
        if (dead instanceof ServerPlayer p) {
            stow(p, true);
            return;
        }
        Entity by = e.getSource().getEntity();
        if (by instanceof ServerPlayer p) OwnerWatch.of(p).killed(p.level().getGameTime(), dead);
        else if (by instanceof ShiraumeCompanion c && c.ownerPlayer() != null) OwnerWatch.of(c.ownerPlayer()).killed(dead.level().getGameTime(), dead);
    }
}
