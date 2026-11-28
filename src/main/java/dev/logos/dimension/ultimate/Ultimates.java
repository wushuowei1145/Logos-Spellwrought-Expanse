package dev.logos.dimension.ultimate;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.companion.CompanionDef;
import dev.logos.dimension.companion.CompanionEntity;
import dev.logos.dimension.companion.CompanionLines;
import dev.logos.dimension.cutin.ChainCutins;
import dev.logos.dimension.item.LogosNatureBridge;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.jspecify.annotations.Nullable;

/**
 * 奥义的服务端入口（docs/ULTIMATES.md）：玩家下令（{@link #order}）、扣魔力、记冷却、每刻推进正在演的段（{@link UltimateScript}），
 * 推进这一轮连携（{@link UltimateChain}）：凑齐在场的全员就放连携切入（{@link ChainCutins#play}），换场后进汇流，
 * 玩家再下令（或汇流超时）放他自己的最后一击（{@link HeroUltimate}）。
 *
 * <p>每位同伴的奥义写在她的档案（{@link CompanionDef#ultimate}），玩家的写在 {@link HeroUltimate#PLAYER}；这里不写死谁怎么演。
 */
public final class Ultimates {
    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS = DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, LogosDimension.ID);

    /** 主人身上每位同伴的奥义冷却。 */
    public static final Supplier<AttachmentType<UltimateCooldowns>> COOLDOWNS = ATTACHMENTS.register("ultimate_cooldowns",
            () -> AttachmentType.builder(() -> UltimateCooldowns.NONE).serialize(UltimateCooldowns.CODEC.fieldOf("ready_at")).copyOnDeath().build());

    /**
     * 同伴的奥义演不演过场（docs/ULTIMATES.md 第三节「同伴的过场先关着」）。现在关着，过场的代码都留着，改成 true 就回来。
     * 关着时她照样演完她那一段：动作、世界里的特效、伤害、馈赠、接力、排队都不变，下令的玩家和别人一样只看世界里的那一半，
     * 镜头不被接管，不盖叠层和她自己的全屏后期，不闪冲击帧；主人和同伴照常受伤，敌人不定住，别的同伴照常打。
     * 连携切入和玩家的最后一击不看这个开关，总是有过场。
     */
    public static final boolean COMPANION_FILMS = false;
    /** 此刻用的值：平时就是 {@link #COMPANION_FILMS}，测试里各位角色的过场场景临时打开。每一段开演时定下，演到一半改不影响它。 */
    public static volatile boolean companionFilms = COMPANION_FILMS;

    /** 过场期间主人身边、目标身边多远以内的敌人定住（格）。 */
    static final double FREEZE_RADIUS = 24;
    private static final Identifier FROZEN = LogosDimension.id("ultimate_frozen");
    /** 平时多少刻给主人发一次状态（变了的时候立刻发）。 */
    static final int STATUS_EVERY = 10;
    /** 缺魔时她那句话显示多久。 */
    static final int LINE_TICKS = 60;

    /** 下令的结果（测试和客户端反馈用）。 */
    public enum Outcome {
        /** 一位同伴开演了。 */
        STARTED,
        /** 前一段还在演：记下了，这一段放完就轮到她。 */
        QUEUED,
        /** 汇流里：放了玩家的最后一击。 */
        FINISHER,
        /** 没人能放，或者魔力不够（已经告诉玩家了）。 */
        DENIED,
        /** 身边没有同伴、正在切入，什么都没做。 */
        IGNORED
    }

    /** 一位玩家这一轮的连携和正在演的段。 */
    private static final class Party {
        final UltimateChain chain = new UltimateChain();
        final List<UltimateCast> casts = new ArrayList<>();
        /** 这一轮最近一段打的那个（汇流时锁住它）。 */
        @Nullable UUID lead;
        /** 汇流时锁住的主目标（实体 ID）。 */
        int lock = -1;
        boolean dirty = true;
        /** 上一次发给他的状态里有没有同伴（没有了要再发一次空的，侧边小队才收起来）。 */
        boolean shown;
        /** 前一段还在演的时候点的同伴（按的顺序），这一段放完一个个接上。 */
        final ArrayDeque<String> queue = new ArrayDeque<>();
        /** 过场期间定住的敌人。 */
        final Map<UUID, Mob> frozen = new HashMap<>();
    }

    private static final Map<UUID, Party> PARTIES = new HashMap<>();
    /** 正在演的（同伴，或者放最后一击的玩家）→ 那一段。 */
    private static final Map<UUID, UltimateCast> PERFORMING = new HashMap<>();

    private Ultimates() {}

    public static void register(IEventBus bus) {
        ATTACHMENTS.register(bus);
        bus.addListener((RegisterPayloadHandlersEvent event) -> {
            var registrar = event.registrar("1");
            registrar.playToServer(UltimatePayloads.Order.TYPE, UltimatePayloads.Order.CODEC, (payload, context) -> {
                if (context.player() instanceof ServerPlayer p) order(p, payload.companion());
            });
            registrar.playToClient(UltimatePayloads.Status.TYPE, UltimatePayloads.Status.CODEC);
            registrar.playToClient(UltimatePayloads.Cast.TYPE, UltimatePayloads.Cast.CODEC);
            registrar.playToClient(UltimatePayloads.End.TYPE, UltimatePayloads.End.CODEC);
            registrar.playToClient(UltimatePayloads.Denied.TYPE, UltimatePayloads.Denied.CODEC);
        });
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> tick(e.getServer()));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p) drop(p);
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerChangedDimensionEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p) drop(p);
        });
        NeoForge.EVENT_BUS.addListener((LivingDeathEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p) drop(p);
        });
        NeoForge.EVENT_BUS.addListener((LivingIncomingDamageEvent e) -> {
            if (!e.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY) && (shielded(e.getEntity()) || frozen(e.getSource()))) e.setCanceled(true);
        });
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> {
            PARTIES.clear();
            PERFORMING.clear();
        });
    }

    // ---------------------------------------------------------------- 查询

    /** {@code e} 正在演奥义（同伴的心智这时停着）。 */
    public static boolean performing(Entity e) {
        return PERFORMING.containsKey(e.getUUID());
    }

    /** 她的心智这一刻停着：她在演奥义，或者主人正在看过场（过场期间整个小队都停下来）。 */
    public static boolean paused(CompanionEntity c) {
        if (performing(c)) return true;
        Party party = c.ownerId() == null ? null : PARTIES.get(c.ownerId());
        return party != null && cinematic(party);
    }

    /** {@code owner} 正在看奥义的过场（同伴的、切入、最后一击）：这时他和他的同伴不受伤，附近的敌人定住。 */
    public static boolean cinematic(ServerPlayer owner) {
        Party party = PARTIES.get(owner.getUUID());
        return party != null && cinematic(party);
    }

    private static boolean cinematic(Party party) {
        for (UltimateCast cast : party.casts) if (cast.filmed()) return true;
        return party.chain.phase() == UltimateChain.Phase.CUTIN;
    }

    /** 过场期间不受伤的：看过场的主人和他的同伴。 */
    private static boolean shielded(LivingEntity e) {
        UUID owner = e instanceof ServerPlayer p ? p.getUUID() : e instanceof CompanionEntity c ? c.ownerId() : null;
        Party party = owner == null ? null : PARTIES.get(owner);
        return party != null && cinematic(party);
    }

    /** 打出这一下的是被定住的敌人（直接打的，或者它射出来的）。 */
    private static boolean frozen(DamageSource source) {
        for (Entity e : new Entity[]{source.getEntity(), source.getDirectEntity()}) {
            if (e != null && frozen(e)) return true;
        }
        return false;
    }

    /** {@code e} 被过场定住了。 */
    public static boolean frozen(Entity e) {
        for (Party party : PARTIES.values()) if (party.frozen.containsKey(e.getUUID())) return true;
        return false;
    }

    /** 已经点了、等着轮到的同伴（测试用）。 */
    public static List<String> queued(ServerPlayer owner) {
        Party party = PARTIES.get(owner.getUUID());
        return party == null ? List.of() : List.copyOf(party.queue);
    }

    /** {@code e} 正在演的那一段；没在演是 null。 */
    public static @Nullable UltimateCast cast(Entity e) {
        return PERFORMING.get(e.getUUID());
    }

    /** {@code owner} 这一轮连携走到哪了。 */
    public static UltimateChain.Phase phase(ServerPlayer owner) {
        Party party = PARTIES.get(owner.getUUID());
        return party == null ? UltimateChain.Phase.IDLE : party.chain.phase();
    }

    /** 这一轮放过的同伴（按顺序）。 */
    public static List<String> released(ServerPlayer owner) {
        Party party = PARTIES.get(owner.getUUID());
        return party == null ? List.of() : party.chain.released();
    }

    /** 在场能放奥义的同伴：召唤在外面、活着、没在撤退、{@link ChainCutins#RANGE} 格以内，按队伍顺序（和连携切入点名一样）。 */
    public static List<CompanionEntity> present(ServerPlayer owner) {
        List<CompanionEntity> out = new ArrayList<>();
        for (CompanionEntity c : ChainCutins.members(owner)) if (!c.leaving()) out.add(c);
        return out;
    }

    /** 冷却还要多少刻。 */
    public static long cooldown(ServerPlayer owner, CompanionDef d) {
        return owner.getData(COOLDOWNS).left(d.id(), owner.level().getGameTime());
    }

    // ---------------------------------------------------------------- 下令

    /**
     * 玩家下令：{@code requested} 是指名的同伴 ID，空串时由这里挑（准星对着的那一位，否则按队伍顺序第一位能放的）。
     * 汇流里下令是放玩家自己的最后一击。
     */
    public static Outcome order(ServerPlayer owner, String requested) {
        Party party = party(owner);
        if (party.chain.phase() == UltimateChain.Phase.CONVERGED) return finish(owner, party) ? Outcome.FINISHER : Outcome.IGNORED;
        if (party.chain.phase() != UltimateChain.Phase.IDLE && party.chain.phase() != UltimateChain.Phase.WINDOW) return Outcome.IGNORED;
        List<CompanionEntity> present = present(owner);
        if (present.isEmpty()) return Outcome.IGNORED;
        long now = owner.level().getGameTime();
        CompanionEntity pick = choose(owner, party, present, requested, now);
        if (pick == null) {
            deny(owner, requested, UltimatePayloads.Denied.NOT_READY);
            return Outcome.DENIED;
        }
        CompanionDef d = pick.def();
        UltimateDef ultimate = d.ultimate();
        if (!party.casts.isEmpty()) {
            // 前一段还在演（有没有过场都一样）：先看够不够，够就记下，这一段放完再开（扣魔、冷却到那时才算）
            UltimateChain.Stage next = party.chain.next(present.size());
            int cost = cost(party, ultimate, new UltimateChain.Stage(next.index() + party.queue.size(), false, present.size()));
            if (!affordable(owner, cost)) return noMana(owner, pick);
            party.queue.add(d.id());
            party.dirty = true;
            return Outcome.QUEUED;
        }
        int cost = cost(party, ultimate, party.chain.next(present.size()));
        if (!pay(owner, cost)) return noMana(owner, pick);
        UltimateChain.Stage stage = party.chain.order(d.id(), present.size(), now);
        owner.setData(COOLDOWNS, owner.getData(COOLDOWNS).with(d.id(), now + ultimate.cooldown()));
        start(owner, party, pick, d, d.id(), stage, ultimate.script(stage), UltimateTargets.pick(owner, pick));
        return Outcome.STARTED;
    }

    private static Outcome noMana(ServerPlayer owner, CompanionEntity pick) {
        CompanionLines.say(pick, owner, "ultimate_no_mana", LINE_TICKS);
        deny(owner, pick.def().id(), UltimatePayloads.Denied.NO_MANA);
        return Outcome.DENIED;
    }

    private static @Nullable CompanionEntity choose(ServerPlayer owner, Party party, List<CompanionEntity> present, String requested, long now) {
        List<CompanionEntity> ready = new ArrayList<>();
        for (CompanionEntity c : present) if (ready(owner, party, c, now)) ready.add(c);
        if (!requested.isEmpty()) {
            for (CompanionEntity c : ready) if (c.def().id().equals(requested)) return c;
            return null;
        }
        CompanionEntity aimed = UltimateTargets.companionInSight(owner, ready);
        if (aimed != null) return aimed;
        return ready.isEmpty() ? null : ready.getFirst();
    }

    private static boolean ready(ServerPlayer owner, Party party, CompanionEntity c, long now) {
        String id = c.def().id();
        return party.chain.open(id) && owner.getData(COOLDOWNS).left(id, now) == 0 && !performing(c) && !party.queue.contains(id);
    }

    /** 此刻给她下令要花多少魔力：基准 × 第几段的折扣 × 前面交过来的馈赠。 */
    private static int cost(Party party, UltimateDef ultimate, UltimateChain.Stage stage) {
        double factor = UltimateChain.manaFactor(stage.index());
        for (RelayGift g : party.chain.gifts(stage)) factor *= g.manaFactor(stage);
        return (int) Math.round(ultimate.mana() * factor);
    }

    /** 扣主人的魔力；不够时什么都不扣（不写字，缺魔由她说一句、画面上闪一下）。创造模式不花。 */
    private static boolean pay(ServerPlayer owner, int cost) {
        if (!affordable(owner, cost)) return false;
        if (owner.hasInfiniteMaterials() || cost <= 0) return true;
        LogosNatureBridge.mana(owner, LogosNatureBridge.mana(owner) - cost);
        return true;
    }

    private static boolean affordable(ServerPlayer owner, int cost) {
        return owner.hasInfiniteMaterials() || cost <= 0 || LogosNatureBridge.mana(owner) >= cost;
    }

    private static void deny(ServerPlayer owner, String companion, int reason) {
        if (owner.connection.hasChannel(UltimatePayloads.Denied.TYPE))
            PacketDistributor.sendToPlayer(owner, new UltimatePayloads.Denied(companion, reason));
    }

    private static void start(ServerPlayer owner, Party party, LivingEntity performer, @Nullable CompanionDef def, String key, UltimateChain.Stage stage,
                              UltimateScript script, @Nullable LivingEntity target) {
        UltimateCast cast = new UltimateCast(owner, performer, def, key, party.chain, stage, script, owner.getRandom().nextLong(), target,
                UltimateTargets.aim(owner), stage.finisher() || companionFilms);
        party.casts.add(cast);
        PERFORMING.put(performer.getUUID(), cast);
        if (target != null) party.lead = target.getUUID();
        party.dirty = true;
        script.start(cast);
        if (!stage.finisher()) cast.say("ultimate_cast", LINE_TICKS);
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(performer, new UltimatePayloads.Cast(performer.getId(), owner.getId(), key,
                target == null ? -1 : target.getId(), cast.aim(), stage.index(), stage.finisher(), UltimateDef.condensed(stage), cast.filmed(), stage.members(),
                cast.seed(), script.length(), script.impact()));
    }

    // ---------------------------------------------------------------- 每刻

    private static void tick(MinecraftServer server) {
        long now = server.overworld().getGameTime();
        for (Iterator<Map.Entry<UUID, Party>> it = PARTIES.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            ServerPlayer owner = server.getPlayerList().getPlayer(entry.getKey());
            if (owner == null) {
                abort(entry.getValue());
                it.remove();
                continue;
            }
            step(owner, entry.getValue(), owner.level().getGameTime());
        }
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            Party party = PARTIES.get(p.getUUID());
            if (now % STATUS_EVERY == 0 || party != null && party.dirty) status(p);
        }
    }

    private static void step(ServerPlayer owner, Party party, long now) {
        for (UltimateCast cast : List.copyOf(party.casts)) {
            LivingEntity performer = cast.performer();
            if (!performer.isAlive() || performer.isRemoved() || performer.level() != owner.level()
                    || performer instanceof CompanionEntity c && c.leaving()) {
                end(owner, party, cast, true);
                continue;
            }
            int t = cast.age;
            if (t == cast.script().impact()) impact(owner, party, cast, now);
            cast.script().tick(cast, t);
            cast.age++;
            if (cast.age >= cast.script().length()) end(owner, party, cast, false);
        }
        // 前一段放完了：接上它演着的时候点的下一位
        while (party.casts.isEmpty() && !party.queue.isEmpty()) order(owner, party.queue.poll());
        List<String> present = new ArrayList<>();
        for (CompanionEntity c : present(owner)) present.add(c.def().id());
        switch (party.chain.tick(present, now)) {
            case TIMEOUT -> clear(owner, party);
            case CUTIN -> {
                ChainCutins.play(owner);
                party.dirty = true;
            }
            case CONVERGE -> converge(owner, party);
            case AUTO_FINISH -> finish(owner, party);
            case NONE -> {}
        }
        if (cinematic(party)) freeze(owner, party);
        else if (!party.frozen.isEmpty()) unfreeze(party);
    }

    /**
     * 过场期间定住主人身边、各段目标身边的敌人（敌对生物、冲着主人或同伴来的；Boss 不定，反正打不到人）：
     * 不走、不追、不打，被奥义打飞照样会飞。只用临时的属性修饰，存档时不会留下来。
     */
    private static void freeze(ServerPlayer owner, Party party) {
        List<Vec3> around = new ArrayList<>();
        around.add(owner.position());
        for (UltimateCast cast : party.casts) around.add(cast.aim());
        for (Vec3 at : around) {
            for (Mob m : owner.level().getEntitiesOfClass(Mob.class, new AABB(at, at).inflate(FREEZE_RADIUS), m -> freezable(owner, m)))
                party.frozen.putIfAbsent(m.getUUID(), m);
        }
        for (Iterator<Mob> it = party.frozen.values().iterator(); it.hasNext(); ) {
            Mob m = it.next();
            if (!m.isAlive() || m.isRemoved()) {
                it.remove();
                continue;
            }
            m.getNavigation().stop();
            m.setTarget(null);
            m.setAggressive(false);
            hold(m, Attributes.MOVEMENT_SPEED);
            hold(m, Attributes.FLYING_SPEED);
        }
    }

    private static boolean freezable(ServerPlayer owner, Mob m) {
        if (!m.isAlive() || m instanceof CompanionEntity || m.is(Tags.EntityTypes.BOSSES)) return false;
        LivingEntity t = m.getTarget();
        return m instanceof Enemy || t == owner || t instanceof CompanionEntity c && owner.getUUID().equals(c.ownerId());
    }

    private static void hold(Mob m, Holder<Attribute> attribute) {
        AttributeInstance inst = m.getAttribute(attribute);
        if (inst != null && !inst.hasModifier(FROZEN))
            inst.addTransientModifier(new AttributeModifier(FROZEN, -1, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    }

    private static void unfreeze(Party party) {
        for (Mob m : party.frozen.values()) {
            for (Holder<Attribute> attribute : List.of(Attributes.MOVEMENT_SPEED, Attributes.FLYING_SPEED)) {
                AttributeInstance inst = m.getAttribute(attribute);
                if (inst != null) inst.removeModifier(FROZEN);
            }
        }
        party.frozen.clear();
    }

    /** 出手：窗口从这一刻算，交出馈赠，后面还有人要接时她喊一声。 */
    private static void impact(ServerPlayer owner, Party party, UltimateCast cast, long now) {
        party.chain.impact(now);
        party.dirty = true;
        if (cast.stage().finisher() || !party.chain.relaying()) return;
        RelayGift gift = cast.script().gift(cast);
        if (gift != null) {
            party.chain.give(gift, cast.stage());
            gift.handed(cast);
        }
        List<String> released = party.chain.released();
        for (CompanionEntity c : present(owner)) {
            if (!released.contains(c.def().id())) {
                cast.say("ultimate_relay", LINE_TICKS);
                return;
            }
        }
    }

    private static void end(ServerPlayer owner, Party party, UltimateCast cast, boolean interrupted) {
        party.casts.remove(cast);
        PERFORMING.remove(cast.performer().getUUID());
        party.dirty = true;
        cast.script().end(cast, interrupted);
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(cast.performer(), new UltimatePayloads.End(cast.performer().getId(), interrupted));
        if (cast.stage().finisher()) clear(owner, party);
        else party.chain.ended(cast.stage());
    }

    /** 切入换场：锁住这一轮最近打的那个（{@link UltimateChain#CONVERGE} 刻的强迟缓），玩家进汇流。 */
    private static void converge(ServerPlayer owner, Party party) {
        LivingEntity lead = party.lead == null ? null : owner.level().getEntity(party.lead) instanceof LivingEntity l && l.isAlive() ? l : null;
        if (lead == null) lead = UltimateTargets.near(owner, owner, owner.position(), UltimateTargets.NEAR);
        if (lead != null) {
            lead.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, UltimateChain.CONVERGE, 4, false, false, true), owner);
            party.lock = lead.getId();
            party.lead = lead.getUUID();
        }
        party.dirty = true;
    }

    /** 汇流里放玩家的最后一击：按主手拿的武器大类挑他档案里的那一段，打锁住的主目标。 */
    private static boolean finish(ServerPlayer owner, Party party) {
        if (party.chain.phase() != UltimateChain.Phase.CONVERGED) return false;
        UltimateChain.Stage stage = party.chain.finish();
        FinisherTemplate template = HeroUltimate.PLAYER.route(FinisherTemplate.of(owner.getMainHandItem()));
        LivingEntity target = owner.level().getEntity(party.lock) instanceof LivingEntity l && l.isAlive() ? l : UltimateTargets.pick(owner, owner);
        start(owner, party, owner, null, "hero/" + template.id, stage, HeroUltimate.PLAYER.script(template), target);
        return true;
    }

    /** 这一轮结束：清掉馈赠、放过的名单、锁定。 */
    private static void clear(ServerPlayer owner, Party party) {
        for (RelayGift g : party.chain.reset()) g.cleared(owner.level());
        party.queue.clear();
        party.lock = -1;
        party.lead = null;
        party.dirty = true;
    }

    /** 主人下线、死亡、换世界：正在演的都打断，这一轮作废（冷却照旧）。 */
    private static void drop(ServerPlayer owner) {
        Party party = PARTIES.remove(owner.getUUID());
        if (party == null) return;
        for (UltimateCast cast : List.copyOf(party.casts)) {
            party.casts.remove(cast);
            PERFORMING.remove(cast.performer().getUUID());
            cast.script().end(cast, true);
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(cast.performer(), new UltimatePayloads.End(cast.performer().getId(), true));
        }
        for (RelayGift g : party.chain.reset()) g.cleared(owner.level());
        party.queue.clear();
        unfreeze(party);
        if (owner.connection.hasChannel(UltimatePayloads.Status.TYPE)) PacketDistributor.sendToPlayer(owner, UltimatePayloads.Status.EMPTY);
    }

    private static void abort(Party party) {
        for (UltimateCast cast : party.casts) {
            PERFORMING.remove(cast.performer().getUUID());
            cast.script().end(cast, true);
        }
        party.casts.clear();
        party.queue.clear();
        unfreeze(party);
    }

    private static Party party(ServerPlayer owner) {
        return PARTIES.computeIfAbsent(owner.getUUID(), id -> new Party());
    }

    // ---------------------------------------------------------------- 状态

    /** 给主人发一份状态：在场的同伴怎么样、连携走到哪了。 */
    private static void status(ServerPlayer owner) {
        Party party = PARTIES.get(owner.getUUID());
        List<CompanionEntity> present = present(owner);
        if (present.isEmpty() && (party == null || !party.shown)) return;
        if (party == null) party = party(owner);
        party.dirty = false;
        long now = owner.level().getGameTime();
        UltimateCooldowns cooldowns = owner.getData(COOLDOWNS);
        List<String> released = party.chain.released();
        double mana = owner.hasInfiniteMaterials() ? Double.MAX_VALUE : LogosNatureBridge.mana(owner);
        UltimateChain.Stage next = party.chain.next(present.size());
        List<UltimatePayloads.Member> members = new ArrayList<>();
        for (CompanionEntity c : present) {
            CompanionDef d = c.def();
            int cost = cost(party, d.ultimate(), next);
            members.add(new UltimatePayloads.Member(d.id(), c.getId(), d.ultimate().cooldown(), (int) cooldowns.left(d.id(), now),
                    released.contains(d.id()), performing(c), party.queue.contains(d.id()), cost, mana >= cost));
        }
        party.shown = !members.isEmpty();
        if (!owner.connection.hasChannel(UltimatePayloads.Status.TYPE)) return;
        PacketDistributor.sendToPlayer(owner, new UltimatePayloads.Status(party.chain.phase().ordinal(), party.chain.windowLeft(now),
                party.chain.convergedLeft(now), party.lock, members));
    }

    /** 测试用：清掉 {@code owner} 的冷却和这一轮连携。 */
    public static void reset(ServerPlayer owner) {
        drop(owner);
        owner.setData(COOLDOWNS, UltimateCooldowns.NONE);
    }
}
