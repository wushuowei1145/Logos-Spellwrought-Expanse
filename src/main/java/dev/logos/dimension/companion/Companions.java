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
import net.neoforged.bus.api.EventPriority;
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
 * 同伴的入口（docs/COMPANIONS.md）：实体注册、玩家身上每位同伴的「契」与包袱、召唤与收回，以及喂给玩家模型（{@link OwnerWatch}）的事件。
 * 现在有两位：白梅（{@link #SHIRAUME_DEF}，docs/SHIRAUME_COMPANION.md）和精灵公主（{@link #PRINCESS_DEF}，docs/ELF_PRINCESS_COMPANION.md）。
 *
 * <p>她们跟着主人走：主人下线、死亡时悄悄收起，上线、重生时再叫出来；主人换维度时在新维度重新现身。
 * 倒下（或撤退）之后要歇 {@link CompanionDef#restTicks} 刻才能再叫。Logos 的长期同伴一人一个，所以同一时间只有一位在外面：
 * 叫出另一位时，Logos 把前一位送回去（{@link CompanionEntity#sendHome}）。
 */
public final class Companions {
    private static final DeferredRegister<EntityType<?>> TYPES = DeferredRegister.create(Registries.ENTITY_TYPE, LogosDimension.ID);
    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, LogosDimension.ID);

    /** 发顶约 1.24 格、耳尖约 1.37 格（2 倍制作，渲染缩放 0.5）；眼睛约 0.95 格。 */
    public static final Supplier<EntityType<ShiraumeCompanion>> SHIRAUME = TYPES.register("shiraume_companion", () ->
            EntityType.Builder.of(ShiraumeCompanion::new, MobCategory.MISC).sized(.6f, 1.3f).eyeHeight(.95f).clientTrackingRange(10)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, LogosDimension.id("shiraume_companion"))));

    /** 精灵公主：发顶约 1.31 格、呆毛尖约 1.37 格（2 倍制作，渲染缩放 0.5）；眼睛约 1.02 格。 */
    public static final Supplier<EntityType<ElfPrincessCompanion>> ELF_PRINCESS = TYPES.register("elf_princess_companion", () ->
            EntityType.Builder.of(ElfPrincessCompanion::new, MobCategory.MISC).sized(.6f, 1.35f).eyeHeight(1.02f).clientTrackingRange(10)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, LogosDimension.id("elf_princess_companion"))));

    /** 琥珀：发顶约 1.24 格、耳尖约 1.4 格（2 倍制作，渲染缩放 0.5）；眼睛约 0.96 格。 */
    public static final Supplier<EntityType<KohakuCompanion>> KOHAKU = TYPES.register("kohaku_companion", () ->
            EntityType.Builder.of(KohakuCompanion::new, MobCategory.MISC).sized(.6f, 1.3f).eyeHeight(.96f).clientTrackingRange(10)
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, LogosDimension.id("kohaku_companion"))));

    /** 她倒下时留在原地、替她烧掉的形代。不存档。 */
    public static final Supplier<EntityType<Katashiro>> KATASHIRO = TYPES.register("shiraume_katashiro", () ->
            EntityType.Builder.<Katashiro>of(Katashiro::new, MobCategory.MISC).sized(.4f, .1f).clientTrackingRange(8).noSave()
                    .build(ResourceKey.create(Registries.ENTITY_TYPE, LogosDimension.id("shiraume_katashiro"))));

    /** 打倒后在形代里休息多久（5 分钟）。 */
    public static final int REST_TICKS = 6000;

    /**
     * 玩家和一位同伴的契（白梅的见 docs/SHIRAUME_COMPANION.md 第 8 节）。死亡不清除。每位同伴各一份（{@link CompanionDef#bond}）。
     * <ul>
     *   <li>{@code pact}：立过契；
     *   <li>{@code out}、{@code restUntil}、{@code comeBack}：她此刻在不在外面、休息到哪一刻、被悄悄收起后是否该再叫出来；
     *   <li>{@code seals}：契印（白梅的叫尾之契）；
     *   <li>{@code affection}：好感，一起经历的事情慢慢加；{@code satiety}：她的饱腹（0–{@link CompanionBond#SATIETY_MAX}）；
     *   <li>{@code mealDay}、{@code meals}：最近一次一起吃饭的游戏日、那天吃了几次；{@code soakDay}：最近一次一起泡温泉的日子；
     *       {@code guardDay}：每天一次的护主机制最近一次用掉的日子（白梅：形代替身；精灵公主：万分之一）；{@code patDay}、{@code pats}：同理，摸头；
     *   <li>{@code tactic}：行动策略（{@link CompanionTactic}，对话里改）；{@code pvp}：攻击玩家。
     * </ul>
     */
    public record Bond(boolean pact, Optional<UUID> out, long restUntil, int seals, boolean comeBack, float affection, float satiety,
                       long mealDay, int meals, long soakDay, long guardDay, long patDay, int pats, String tactic, boolean pvp) {
        public static final Bond NONE = new Bond(false, Optional.empty(), 0, 0, false, 0, CompanionBond.SATIETY_MAX, -1, 0, -1, -1, -1, 0,
                CompanionTactic.ADAPTIVE.id, false);
        static final Codec<Bond> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.BOOL.optionalFieldOf("pact", false).forGetter(Bond::pact),
                UUIDUtil.CODEC.optionalFieldOf("out").forGetter(Bond::out),
                Codec.LONG.optionalFieldOf("rest_until", 0L).forGetter(Bond::restUntil),
                Codec.INT.optionalFieldOf("seals", 0).forGetter(Bond::seals),
                Codec.BOOL.optionalFieldOf("come_back", false).forGetter(Bond::comeBack),
                Codec.FLOAT.optionalFieldOf("affection", 0f).forGetter(Bond::affection),
                Codec.FLOAT.optionalFieldOf("satiety", CompanionBond.SATIETY_MAX).forGetter(Bond::satiety),
                Codec.LONG.optionalFieldOf("meal_day", -1L).forGetter(Bond::mealDay),
                Codec.INT.optionalFieldOf("meals", 0).forGetter(Bond::meals),
                Codec.LONG.optionalFieldOf("soak_day", -1L).forGetter(Bond::soakDay),
                Codec.LONG.optionalFieldOf("guard_day", -1L).forGetter(Bond::guardDay),
                Codec.LONG.optionalFieldOf("pat_day", -1L).forGetter(Bond::patDay),
                Codec.INT.optionalFieldOf("pats", 0).forGetter(Bond::pats),
                Codec.STRING.optionalFieldOf("tactic", CompanionTactic.ADAPTIVE.id).forGetter(Bond::tactic),
                Codec.BOOL.optionalFieldOf("pvp", false).forGetter(Bond::pvp)).apply(i, Bond::new));

        Bond withOut(@Nullable UUID id) {
            return new Bond(pact, Optional.ofNullable(id), restUntil, seals, comeBack, affection, satiety, mealDay, meals, soakDay, guardDay, patDay, pats, tactic, pvp);
        }

        Bond withRest(long until) {
            return new Bond(pact, out, until, seals, comeBack, affection, satiety, mealDay, meals, soakDay, guardDay, patDay, pats, tactic, pvp);
        }

        Bond withComeBack(boolean back) {
            return new Bond(pact, out, restUntil, seals, back, affection, satiety, mealDay, meals, soakDay, guardDay, patDay, pats, tactic, pvp);
        }

        Bond withPact(boolean made) {
            return new Bond(made, out, restUntil, seals, comeBack, affection, satiety, mealDay, meals, soakDay, guardDay, patDay, pats, tactic, pvp);
        }

        Bond withSeals(int n) {
            return new Bond(pact, out, restUntil, Math.max(0, n), comeBack, affection, satiety, mealDay, meals, soakDay, guardDay, patDay, pats, tactic, pvp);
        }

        Bond withAffection(float a) {
            return new Bond(pact, out, restUntil, seals, comeBack, Math.max(0, a), satiety, mealDay, meals, soakDay, guardDay, patDay, pats, tactic, pvp);
        }

        Bond withSatiety(float s) {
            return new Bond(pact, out, restUntil, seals, comeBack, affection, Mth.clamp(s, 0, CompanionBond.SATIETY_MAX), mealDay, meals, soakDay,
                    guardDay, patDay, pats, tactic, pvp);
        }

        Bond withMeal(long day, int count) {
            return new Bond(pact, out, restUntil, seals, comeBack, affection, satiety, day, count, soakDay, guardDay, patDay, pats, tactic, pvp);
        }

        Bond withSoak(long day) {
            return new Bond(pact, out, restUntil, seals, comeBack, affection, satiety, mealDay, meals, day, guardDay, patDay, pats, tactic, pvp);
        }

        Bond withGuard(long day) {
            return new Bond(pact, out, restUntil, seals, comeBack, affection, satiety, mealDay, meals, soakDay, day, patDay, pats, tactic, pvp);
        }

        Bond withPats(long day, int count) {
            return new Bond(pact, out, restUntil, seals, comeBack, affection, satiety, mealDay, meals, soakDay, guardDay, day, count, tactic, pvp);
        }

        public Bond withTactic(CompanionTactic t) {
            return new Bond(pact, out, restUntil, seals, comeBack, affection, satiety, mealDay, meals, soakDay, guardDay, patDay, pats, t.id, pvp);
        }

        /** 作战策略里「攻击玩家」开着没有。 */
        public Bond withPvp(boolean on) {
            return new Bond(pact, out, restUntil, seals, comeBack, affection, satiety, mealDay, meals, soakDay, guardDay, patDay, pats, tactic, on);
        }

        public CompanionTactic tacticOf() {
            return CompanionTactic.byId(tactic);
        }

        /** 今天（游戏日 {@code day}）已经一起吃过几次饭。 */
        public int mealsOn(long day) {
            return mealDay == day ? meals : 0;
        }

        public int patsOn(long day) {
            return patDay == day ? pats : 0;
        }
    }

    /** 白梅的契（附件 ID 从第一版起就是 {@code shiraume_bond}）。 */
    public static final Supplier<AttachmentType<Bond>> BOND = bond("shiraume_bond");
    /** 白梅的包袱：存在主人身上，所以她倒下、被收回、换维度都不会丢（第 8.8 节）。 */
    public static final Supplier<AttachmentType<CompanionPouch>> POUCH = pouch("shiraume_pouch");
    public static final Supplier<AttachmentType<Bond>> PRINCESS_BOND = bond("elf_princess_bond");
    public static final Supplier<AttachmentType<CompanionPouch>> PRINCESS_POUCH = pouch("elf_princess_pouch");
    public static final Supplier<AttachmentType<Bond>> KOHAKU_BOND = bond("kohaku_bond");
    public static final Supplier<AttachmentType<CompanionPouch>> KOHAKU_POUCH = pouch("kohaku_pouch");

    private static Supplier<AttachmentType<Bond>> bond(String id) {
        return ATTACHMENTS.register(id, () -> AttachmentType.builder(() -> Bond.NONE).serialize(Bond.CODEC.fieldOf("bond")).copyOnDeath().build());
    }

    private static Supplier<AttachmentType<CompanionPouch>> pouch(String id) {
        return ATTACHMENTS.register(id, () -> AttachmentType.builder(CompanionPouch::new).serialize(CompanionPouch.CODEC.fieldOf("items")).copyOnDeath().build());
    }

    public static final CompanionDef SHIRAUME_DEF = new CompanionDef(ShiraumeTalk.ID, SHIRAUME, BOND, POUCH, CompanionStats.SHIRAUME, REST_TICKS,
            ShiraumeTalk.MAX_SEALS);
    public static final CompanionDef PRINCESS_DEF = new CompanionDef(ElfPrincessCompanion.ID, ELF_PRINCESS, PRINCESS_BOND, PRINCESS_POUCH,
            ElfPrincessCompanion.GROWTH, ElfPrincessCompanion.REST_TICKS, ElfPrincessCompanion.MAX_SEALS);
    /** 全部同伴（登录、重生、换维度、下线时一位位看）。 */
    public static final CompanionDef KOHAKU_DEF = new CompanionDef(KohakuCompanion.ID, KOHAKU, KOHAKU_BOND, KOHAKU_POUCH,
            KohakuCompanion.GROWTH, KohakuCompanion.REST_TICKS, KohakuCompanion.MAX_SEALS);
    public static final List<CompanionDef> ALL = List.of(SHIRAUME_DEF, PRINCESS_DEF, KOHAKU_DEF);

    /** 等到下一刻再叫出来的同伴（上线、重生、换维度之后）：「主人 UUID|同伴 ID」 → 还要等几刻。 */
    private static final Map<String, Integer> PENDING = new HashMap<>();

    private Companions() {}

    public static void register(IEventBus bus) {
        TYPES.register(bus);
        ATTACHMENTS.register(bus);
        KatashiroItem.register(bus);
        BaggedWorldTreeItem.register(bus);
        PawSovereignItem.register(bus);
        CompanionLines.register(bus);
        CompanionTalk.register(bus);
        ShiraumeTalk.init();
        PrincessTalk.init();
        KohakuTalk.init();
        bus.addListener((EntityAttributeCreationEvent event) -> {
            event.put(SHIRAUME.get(), ShiraumeCompanion.createAttributes().build());
            event.put(ELF_PRINCESS.get(), ElfPrincessCompanion.createAttributes().build());
            event.put(KOHAKU.get(), KohakuCompanion.createAttributes().build());
        });
        NeoForge.EVENT_BUS.addListener(Companions::onBreak);
        NeoForge.EVENT_BUS.addListener(Companions::onPlace);
        NeoForge.EVENT_BUS.addListener(Companions::onDamaged);
        NeoForge.EVENT_BUS.addListener(Companions::onProtect);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOW, Companions::onDeath);
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p) stowAll(p, true);
            OwnerWatch.forget(e.getEntity().getUUID());
            PENDING.keySet().removeIf(k -> k.startsWith(e.getEntity().getUUID() + "|"));
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p) {
                for (CompanionDef d : ALL) comeBackLater(p, d, 40);
                learn(p, false);
                BaggedWorldTreeItem.refresh(p);
                PawSovereignItem.refresh(p);
            }
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerRespawnEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p && !e.isEndConquered()) for (CompanionDef d : ALL) comeBackLater(p, d, 20);
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerChangedDimensionEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p) for (CompanionDef d : ALL) if (bond(p, d).comeBack()) comeBackLater(p, d, 10);
        });
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> tickPending(e.getServer()));
        NeoForge.EVENT_BUS.addListener(CompanionLight::tick);
    }

    /** ID 对应的同伴；没有为 null。 */
    public static @Nullable CompanionDef def(String id) {
        for (CompanionDef d : ALL) if (d.id().equals(id)) return d;
        return null;
    }

    public static Bond bond(Player owner, CompanionDef d) {
        return owner.getData(d.bond().get());
    }

    static void setBond(Player owner, CompanionDef d, Bond b) {
        owner.setData(d.bond().get(), b);
    }

    // ---------------------------------------------------------------- 召唤与收回

    /** {@code d} 此刻在外面的那一位（只在主人所在的维度找）。 */
    public static @Nullable CompanionEntity out(ServerPlayer owner, CompanionDef d) {
        Optional<UUID> id = bond(owner, d).out();
        if (id.isEmpty()) return null;
        Entity e = owner.level().getEntity(id.get());
        return e instanceof CompanionEntity c && c.def() == d && c.isAlive() ? c : null;
    }

    /** 白梅此刻在外面的那一位。 */
    public static @Nullable ShiraumeCompanion out(ServerPlayer owner) {
        return (ShiraumeCompanion) out(owner, SHIRAUME_DEF);
    }

    /** 精灵公主此刻在外面的那一位。 */
    public static @Nullable ElfPrincessCompanion princess(ServerPlayer owner) {
        return (ElfPrincessCompanion) out(owner, PRINCESS_DEF);
    }

    /** 琥珀此刻在外面的那一位。 */
    public static @Nullable KohakuCompanion kohaku(ServerPlayer owner) {
        return (KohakuCompanion) out(owner, KOHAKU_DEF);
    }

    /** 还要休息多少刻（0 表示随时能叫）。 */
    public static long restLeft(ServerPlayer owner, CompanionDef d) {
        return Math.max(0, bond(owner, d).restUntil() - owner.level().getGameTime());
    }

    public static long restLeft(ServerPlayer owner) {
        return restLeft(owner, SHIRAUME_DEF);
    }

    public static CompanionPouch pouch(Player owner, CompanionDef d) {
        return owner.getData(d.pouch().get());
    }

    public static CompanionPouch pouch(Player owner) {
        return pouch(owner, SHIRAUME_DEF);
    }

    /** 叫白梅出来，在主人身边；失败时返回原因。 */
    public static @Nullable Component summon(ServerPlayer owner, boolean quiet) {
        return summon(owner, SHIRAUME_DEF, null);
    }

    /** 叫白梅出来；{@code at} 不为空时就在那里现身（测试用），否则在主人身边。 */
    public static @Nullable Component summon(ServerPlayer owner, boolean quiet, @Nullable Vec3 at) {
        return summon(owner, SHIRAUME_DEF, at);
    }

    /** 叫 {@code d} 出来；{@code at} 不为空时就在那里现身（测试用），否则在主人身边。失败时返回原因（给命令看，游戏里不显示）。 */
    public static @Nullable Component summon(ServerPlayer owner, CompanionDef d, @Nullable Vec3 at) {
        ServerLevel level = owner.level();
        if (out(owner, d) != null) return Component.translatable("message.logos_dimension.companion.already_out");
        long left = restLeft(owner, d);
        if (left > 0) return Component.translatable("message.logos_dimension.companion.resting", (left + 19) / 20);
        CompanionEntity c = d.type().get().create(level, EntitySpawnReason.MOB_SUMMONED);
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
        join(owner, c);
        c.appearBurst(level, c.position().add(0, .75, 0));
        return null;
    }

    /** 她（召唤出来的，或刚立契的）成了主人在外面的同伴：记在契上，交给 Logos 当作长期同伴（同一位只有一个；不同的同伴可以一起在外面，最多 4 位）。 */
    static void join(ServerPlayer owner, CompanionEntity c) {
        CompanionDef d = c.def();
        setBond(owner, d, bond(owner, d).withOut(c.getUUID()).withComeBack(true));
        LogosCompanionBridge.bind(c, owner);
    }

    /** 让白梅回去：挥挥手，化作花瓣散去。 */
    public static boolean dismiss(ServerPlayer owner) {
        return dismiss(owner, SHIRAUME_DEF);
    }

    /** 让 {@code d} 回去：挥挥手，散去。 */
    public static boolean dismiss(ServerPlayer owner, CompanionDef d) {
        CompanionEntity c = out(owner, d);
        setBond(owner, d, bond(owner, d).withOut(null).withComeBack(false));
        if (c == null) return false;
        c.leave();
        return true;
    }

    /** 悄悄收起（主人下线、死亡）：不放特效，之后自动叫回。 */
    static void stowAll(ServerPlayer owner, boolean comeBack) {
        for (CompanionDef d : ALL) stow(owner, d, comeBack);
    }

    static void stow(ServerPlayer owner, CompanionDef d, boolean comeBack) {
        CompanionEntity c = out(owner, d);
        if (c != null) c.discard();
        Bond b = bond(owner, d);
        setBond(owner, d, b.withOut(null).withComeBack(comeBack && (c != null || b.comeBack())));
    }

    /** 她倒下（或撤退）了：记下休息到哪一刻；之后不会自动叫回。 */
    static void fell(ServerPlayer owner, CompanionEntity c) {
        CompanionDef d = c.def();
        Bond b = bond(owner, d);
        if (b.out().isPresent() && !b.out().get().equals(c.getUUID())) return;
        setBond(owner, d, b.withOut(null).withRest(owner.level().getGameTime() + d.restTicks()).withComeBack(false));
    }

    /** 测试用：清掉白梅的休息时间。 */
    public static void wake(ServerPlayer owner) {
        wake(owner, SHIRAUME_DEF);
    }

    public static void wake(ServerPlayer owner, CompanionDef d) {
        setBond(owner, d, bond(owner, d).withRest(0));
    }

    /** 测试与命令用：和白梅立契（不走封印之间）。 */
    public static void grantPact(ServerPlayer owner) {
        grantPact(owner, SHIRAUME_DEF);
    }

    public static void grantPact(ServerPlayer owner, CompanionDef d) {
        setBond(owner, d, bond(owner, d).withPact(true));
    }

    /** 给白梅盖一枚契印（空白形代、命令）；返回盖完后的枚数。 */
    public static int addSeal(ServerPlayer owner) {
        return addSeal(owner, SHIRAUME_DEF);
    }

    /** 给 {@code d} 盖一枚契印；返回盖完后的枚数。白梅第三枚时教主人狐火之言。 */
    public static int addSeal(ServerPlayer owner, CompanionDef d) {
        Bond b = bond(owner, d);
        setBond(owner, d, b.withSeals(b.seals() + 1));
        CompanionEntity c = out(owner, d);
        if (c != null) CompanionStats.apply(c, owner, b.seals() + 1);
        if (d == SHIRAUME_DEF) learn(owner, true);
        return b.seals() + 1;
    }

    /**
     * 白梅第三印（{@link CompanionBond#FOXFIRE_SEAL}）：主人学会狐火之言，自己写的术式里可以写 {@code foxfire}。
     * 刚学会时（{@code tell}）在屏幕上说一声；登录时补教（命令改过契印、旧存档）不说。
     */
    static void learn(ServerPlayer owner, boolean tell) {
        Bond b = owner.getData(BOND);
        if (!b.pact() || !CompanionBond.unlocked(b.seals(), CompanionBond.FOXFIRE_SEAL)) return;
        if (!dev.logos.dimension.foxfire.Foxfire.teach(owner) || !tell) return;
        owner.sendSystemMessage(net.minecraft.network.chat.Component.translatable("message.logos_dimension.companion.foxfire_learned")
                .withStyle(net.minecraft.ChatFormatting.LIGHT_PURPLE));
    }

    /** 设白梅的饱腹（命令和测试用）。 */
    public static void setSatiety(ServerPlayer owner, float satiety) {
        setSatiety(owner, SHIRAUME_DEF, satiety);
    }

    public static void setSatiety(ServerPlayer owner, CompanionDef d, float satiety) {
        setBond(owner, d, bond(owner, d).withSatiety(satiety));
    }

    /** 好感加 {@code amount}（可以是负的）。 */
    static void affection(ServerPlayer owner, CompanionDef d, float amount) {
        Bond b = bond(owner, d);
        setBond(owner, d, b.withAffection(b.affection() + amount));
    }

    private static String key(ServerPlayer p, CompanionDef d) {
        return p.getUUID() + "|" + d.id();
    }

    private static void comeBackLater(ServerPlayer p, CompanionDef d, int ticks) {
        if (bond(p, d).comeBack()) PENDING.put(key(p, d), ticks);
    }

    private static void tickPending(net.minecraft.server.MinecraftServer server) {
        if (PENDING.isEmpty()) return;
        List<String> due = new ArrayList<>();
        PENDING.replaceAll((id, t) -> t - 1);
        PENDING.forEach((id, t) -> {
            if (t <= 0) due.add(id);
        });
        for (String k : due) {
            PENDING.remove(k);
            int bar = k.indexOf('|');
            CompanionDef d = def(k.substring(bar + 1));
            ServerPlayer p;
            try {
                p = server.getPlayerList().getPlayer(UUID.fromString(k.substring(0, bar)));
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (d == null || p == null || !p.isAlive() || !bond(p, d).comeBack()) continue;
            if (out(p, d) != null) continue;
            setBond(p, d, bond(p, d).withOut(null));
            summon(p, d, null);
        }
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

    /** 白梅召唤时的一团淡粉狐火：樱花瓣、淡粉的尘，一声狐狸的闪现声。 */
    static void appearBurst(ServerLevel level, Vec3 at) {
        level.sendParticles(ParticleTypes.CHERRY_LEAVES, at.x, at.y, at.z, 40, .45, .6, .45, .04);
        level.sendParticles(new DustParticleOptions(FoxfireFlame.SHIRAUME.core, 1.6f), at.x, at.y, at.z, 30, .35, .45, .35, 0);
        level.sendParticles(ParticleTypes.END_ROD, at.x, at.y, at.z, 8, .2, .3, .2, .03);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.FOX_TELEPORT, SoundSource.NEUTRAL, 1, 1.35f);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.NEUTRAL, 1.2f, 1.2f);
    }

    /** 白梅离场：花瓣消散（docs/DISSOLVE.md）。 */
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

    /**
     * 继承主人的保护类魔咒（docs/SHIRAUME_COMPANION.md 第 5.1 节）：她挨打时，按主人身上的魔咒对这一下算保护（同原版，火焰保护只挡火……），
     * 乘继承比例，在护甲之后再减（同原版的先后）。
     */
    private static void onProtect(net.neoforged.neoforge.event.entity.living.LivingDamageEvent.Pre e) {
        if (!(e.getEntity() instanceof CompanionEntity c) || !(c.level() instanceof ServerLevel level) || e.getNewDamage() <= 0) return;
        ServerPlayer owner = c.ownerPlayer();
        if (owner == null) return;
        float protection = CompanionStats.protection(level, owner, e.getSource(), c.def().growth().armorRatio(c.seals()));
        if (protection > 0) e.setNewDamage(net.minecraft.world.damagesource.CombatRules.getDamageAfterMagicAbsorb(e.getNewDamage(), protection));
    }

    private static void onDeath(LivingDeathEvent e) {
        LivingEntity dead = e.getEntity();
        // 精灵公主不会死：世界树把她收回去休养（撤退）
        if (dead instanceof ElfPrincessCompanion princess && princess.level() instanceof ServerLevel level) {
            e.setCanceled(true);
            princess.startWithdraw(level);
            return;
        }
        // 琥珀也不会死：倒下以后法阵把她卷回爪印金镑里
        if (dead instanceof KohakuCompanion kohaku && kohaku.level() instanceof ServerLevel level) {
            e.setCanceled(true);
            kohaku.startWithdraw(level);
            return;
        }
        if (dead instanceof ServerPlayer p) {
            // 白梅第一印：形代替身（每个游戏日一次）
            if (CompanionGuard.saveOwner(p, e.getSource())) {
                e.setCanceled(true);
                return;
            }
            OwnerWatch.of(p).died(p.level().getGameTime(), p.position());
            stowAll(p, true);
            return;
        }
        Entity by = e.getSource().getEntity();
        if (by instanceof ServerPlayer p) OwnerWatch.of(p).killed(p.level().getGameTime(), dead);
        else if (by instanceof CompanionEntity c && c.ownerPlayer() != null) OwnerWatch.of(c.ownerPlayer()).killed(dead.level().getGameTime(), dead);
    }
}
