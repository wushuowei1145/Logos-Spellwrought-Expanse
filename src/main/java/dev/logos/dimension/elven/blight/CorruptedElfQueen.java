package dev.logos.dimension.elven.blight;

import com.geckolib.animatable.instance.AnimatableInstanceCache;
import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.util.GeckoLibUtil;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.corruption.Corruption;
import dev.logos.dimension.corruption.CorruptionContact;
import dev.logos.dimension.combat.*;
import dev.logos.dimension.npc.NpcAnimated;
import dev.logos.dimension.npc.NpcAnimations;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** 坐在悬吊核心下的华服守护者。命中由服务端结算，前摇锁定朝向。 */
public class CorruptedElfQueen extends PathfinderMob implements NpcAnimated, MoveRunner.Actor {
    private static final Identifier MOVESET = LogosDimension.id("corrupted_queen"), CORE_MOVESET = LogosDimension.id("queen_core");
    public static final int RISE_TICKS = 40;
    private static final EntityDataAccessor<String> MOVE = SynchedEntityData.defineId(CorruptedElfQueen.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> PHASE = SynchedEntityData.defineId(CorruptedElfQueen.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> BROKEN = SynchedEntityData.defineId(CorruptedElfQueen.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Long> PHASE_TIME = SynchedEntityData.defineId(CorruptedElfQueen.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<BlockPos> HEART_POS = SynchedEntityData.defineId(CorruptedElfQueen.class, EntityDataSerializers.BLOCK_POS);
    private static final EntityDataAccessor<Boolean> BOUND = SynchedEntityData.defineId(CorruptedElfQueen.class, EntityDataSerializers.BOOLEAN);
    /** 周围还在供养她的腐败核心数（核心庇护），每秒刷新一次，同步给血条。 */
    private static final EntityDataAccessor<Integer> WARD = SynchedEntityData.defineId(CorruptedElfQueen.class, EntityDataSerializers.INT);
    /** 抗性表 data/logos_dimension/creature_traits/corrupted_queen.json，血管根结也用这一份。 */
    public static final Identifier TRAITS = LogosDimension.id("corrupted_queen");
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private final Set<UUID> struck = new HashSet<>();
    private @Nullable BlockPos heart;
    private @Nullable ServerBossEvent bar;
    private String action = "", clientAction = "";
    private @Nullable CombatMove clientMove;
    private int elapsed, wait = 20, sequence;
    private boolean introPlayed;
    private float strain;
    private int endingTick, absentTicks;
    private @Nullable MoveRunner runner, coreRunner;
    private boolean endingSent, purified;
    private final Set<UUID> endingViewers = new HashSet<>();
    private final Set<UUID> pressureStruck = new HashSet<>();
    private Vec3 pressureAim = Vec3.ZERO;
    private Vec3 aim = Vec3.ZERO, origin = Vec3.ZERO;
    private Vec3 spellPoint = Vec3.ZERO;
    private double mana = 600;

    public CorruptedElfQueen(EntityType<? extends CorruptedElfQueen> type, Level world) {
        super(type, world);
        setPersistenceRequired();
        xpReward = 80;
        if (world instanceof ServerLevel server) traits(server).apply(this);
    }

    public CreatureTraits traits(ServerLevel world) { return CreatureTraits.of(world.getServer(), TRAITS); }
    /** 正在供养她的腐败核心数。 */
    public int ward() { return entityData.get(WARD); }
    /** 数一次周围的腐败核心；平时每秒一次，测试可以直接调用。 */
    public void refreshWard(ServerLevel world) {
        var ward = traits(world).ward();
        entityData.set(WARD, ward == null ? 0 : dev.logos.dimension.corruption.CorruptionNetworks.get(world).coresWithin(blockPosition(), ward.radius()));
    }
    /** 受到的伤害先按抗性表乘，再扣核心庇护（虚空等 {@code bypasses_blight_ward} 标签里的不扣）。血管根结也走这里。 */
    public float incoming(ServerLevel world, DamageSource source, float amount) {
        var traits = traits(world);
        amount = traits.adjust(source, amount);
        var ward = traits.ward();
        if (ward != null && !source.is(BYPASSES_WARD)) amount *= (float) (1 - ward.reduction(ward()));
        return amount;
    }
    public static final net.minecraft.tags.TagKey<net.minecraft.world.damagesource.DamageType> BYPASSES_WARD =
            net.minecraft.tags.TagKey.create(Registries.DAMAGE_TYPE, LogosDimension.id("bypasses_blight_ward"));

    public static AttributeSupplier.Builder attributes() {
        return PathfinderMob.createMobAttributes().add(Attributes.MAX_HEALTH, QueenBattleRules.HEALTH).add(Attributes.MOVEMENT_SPEED, .28)
                .add(Attributes.ARMOR, 6).add(Attributes.KNOCKBACK_RESISTANCE, 1).add(Attributes.FOLLOW_RANGE, 40).add(Attributes.STEP_HEIGHT, 1);
    }

    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder); builder.define(MOVE, ""); builder.define(PHASE, 1); builder.define(BROKEN, 0); builder.define(PHASE_TIME, 0L); builder.define(HEART_POS, BlockPos.ZERO); builder.define(BOUND, false); builder.define(WARD, 0);
    }

    public void guard(BlockPos at) { heart = at.immutable(); entityData.set(HEART_POS, heart); entityData.set(BOUND, true); play("queen_guard"); }
    public void engage(ServerPlayer target) {
        if (!action.equals("queen_guard")) return;
        setTarget(target);
        boolean first=!introPlayed;
        introPlayed=true;
        play(first?"queen_entrance":"queen_rise");
        if(first) QueenEntrances.play(this,heart==null?blockPosition().above(7):heart);
        if (heart != null && level().getBlockEntity(heart) instanceof QueenHeart core) core.engaged(getUUID());
    }
    public String action() { return action; }
    public int actionTick() { return elapsed; }
    public boolean entering() { return action.equals("queen_rise") || action.equals("queen_entrance"); }
    public boolean introPlayed() { return introPlayed; }
    public double mana() { return mana; }
    public boolean payMana(double amount) { if (mana < amount) return false; mana -= amount; return true; }
    public int phase() { return entityData.get(PHASE); }
    public float phaseAge(float partial) { return level().getGameTime() - entityData.get(PHASE_TIME) + partial; }
    public int brokenRoots() { return entityData.get(BROKEN); }
    /** 控制韧性：阶段一、二里打她攒的硬直值（抗性和核心庇护算过之后）。 */
    public float strain() { return strain; }
    public @Nullable BlockPos heartPosition() { return entityData.get(BOUND) ? entityData.get(HEART_POS) : heart; }
    public int endingTick() { return endingTick; }
    public boolean transitioning() { String move = entityData.get(MOVE); return move.startsWith("queen_chain_break|") || move.startsWith("queen_core_fall|"); }
    public boolean rootsOpen() { return !transitioning() && (phase() == 3 || entityData.get(MOVE).startsWith("queen_resist|") || entityData.get(MOVE).startsWith("queen_aid|")); }
    public Vec3 arena() {
        var at = heartPosition();
        return at != null && level().getBlockEntity(at) instanceof QueenHeart core ? core.guardianPosition() : level().isClientSide() ? position() : origin;
    }
    public Vec3 corePosition(float partial) {
        var at = heartPosition();
        Vec3 top = at == null ? arena().add(0, 7, 0) : at.getBottomCenter();
        return top.add(0, QueenBattleRules.coreDrop(phase(), level().getGameTime() - entityData.get(PHASE_TIME) + partial), 0);
    }
    /** 根结只在本阶段第一次断裂时结算，杜绝扫击同时命中重复计数。 */
    public void sever(int slot, DamageSource source) {
        if (!(level() instanceof ServerLevel world) || slot < 0 || slot >= 3 || !rootsOpen() || QueenBattleRules.severed(brokenRoots(), slot)) return;
        entityData.set(BROKEN, brokenRoots() | 1 << slot);
        float amount = QueenBattleRules.rootDamage(phase());
        if (amount > 0 && brokenRoots() == 7) amount = getHealth();
        if (amount > 0) coreHurt(world, source, amount);
        else message(world, "root_cut");
        sound(world, SoundEvents.WOOD_BREAK, .6f);
        if (phase() == 2 && rootsOpen()) play("queen_aid");
    }
    public boolean damageCore(ServerLevel world, DamageSource source, float amount) {
        if (transitioning() || !QueenBattleRules.coreExposed(phase(), brokenRoots()) || !(source.getEntity() instanceof LivingEntity) || !Float.isFinite(amount) || amount <= 0) return false;
        coreHurt(world, source, Math.min(amount, QueenBattleRules.HEALTH));
        return true;
    }
    private void coreHurt(ServerLevel world, DamageSource source, float amount) {
        float next = Math.max(0, getHealth() - amount);
        // 女王保留一个生命值演完结局，真正的击败标记只在自爆时写入。
        setHealth(Math.max(1, next));
        if (next <= 0) {
            if (phase() < 3) { int nextPhase = phase() + 1; setHealth(getMaxHealth()); changePhase(nextPhase); message(world, "phase" + nextPhase); }
            else { changePhase(4); endingTick = 0; play("queen_sacrifice"); }
        }
    }
    private void changePhase(int phase) {
        entityData.set(PHASE, phase); entityData.set(PHASE_TIME, level().getGameTime()); entityData.set(BROKEN, 0);
        strain = 0; coreRunner = null; struck.clear();
        if (phase == 3) { setPos(arena().add(0, 0, -3.5)); setTarget(null); setYRot(0); yBodyRot = 0; play("queen_core_fall"); }
        else if (phase == 2) play("queen_chain_break");
    }
    private void message(ServerLevel world, String key) {
        for (ServerPlayer p : world.players()) if (p.distanceToSqr(arena()) < 48 * 48)
            p.sendOverlayMessage(Component.translatable("message.logos_dimension.queen." + key));
    }
    public List<QueenTether> tethers(ServerLevel world) {
        return world.getEntitiesOfClass(QueenTether.class, getBoundingBox().inflate(42), e -> e.belongsTo(this) && e.isAlive());
    }
    private void ensureTethers(ServerLevel world) {
        var existing = tethers(world);
        for (int slot = 0; slot < (phase() == 3 ? 4 : 3); slot++) {
            final int n = slot;
            if (slot < 3 && QueenBattleRules.severed(brokenRoots(), slot) || existing.stream().anyMatch(e -> e.slot() == n && e.spawnPhase() == phase())) continue;
            var root = ElvenBlight.TETHER.get().create(world, net.minecraft.world.entity.EntitySpawnReason.TRIGGERED);
            if (root == null) continue;
            root.bind(this, slot);
            if (slot == 3) root.setPos(corePosition(0));
            else {
                double angle = slot * Math.PI * 2 / 3 + .4;
                Vec3 p = arena().add(Math.cos(angle) * 7, 0, Math.sin(angle) * 7);
                // 只在实际空位上暴露根结，不删玩家放置的方块、不隔墙提供弱点。
                if (!world.getBlockState(BlockPos.containing(p)).getCollisionShape(world, BlockPos.containing(p)).isEmpty()) continue;
                root.setPos(p);
            }
            world.addFreshEntity(root);
        }
    }
    /** 核心也是普通 MoveRunner，只把几何、庇护区和原版伤害结算留在机制层。 */
    private final MoveRunner.Actor coreActor = new MoveRunner.Actor() {
        @Override public double attackSpeed() { return 1; }
        @Override public void moveStarted(MoveRunner r) {
            pressureStruck.clear();
            if (level() instanceof ServerLevel world) {
                var target = world.players().stream().filter(p -> canFight(p) && p.distanceToSqr(arena()) < 32 * 32)
                        .min(java.util.Comparator.comparingDouble(p -> p.distanceToSqr(arena()))).orElse(null);
                if (target != null) pressureAim = target.position().subtract(arena()).multiply(1, 0, 1).normalize();
            }
        }
        @Override public void contact(MoveRunner r) { if (level() instanceof ServerLevel world) coreContact(world, r); }
    };
    private void coreAttack(ServerLevel world) {
        if (world.players().stream().noneMatch(p -> canFight(p) && p.distanceToSqr(arena()) < 32 * 32)) { coreRunner = null; return; }
        var set = Movesets.get(world.getServer(), CORE_MOVESET);
        if (set == null) { coreRunner = null; return; }
        if (coreRunner == null || coreRunner.moveset() != set) coreRunner = new MoveRunner(set, coreActor, world.getGameTime());
        coreRunner.tick(world.getGameTime());
        int t = (int) coreRunner.age(world.getGameTime());
        Vec3 center = arena();
        if (phase() == 2 && rootsOpen() && t % 5 == 0) for (int i = 0; i < 24; i++) {
            double a = i * Math.PI / 12;
            world.sendParticles(ParticleTypes.HAPPY_VILLAGER, getX() + Math.cos(a) * 3.2, getY() + .15, getZ() + Math.sin(a) * 3.2, 1, 0, 0, 0, 0);
        }
        if ("line".equals(coreRunner.move().effect()) && t < coreRunner.move().contact() * 20 && t % 4 == 0)
            for (int i = 1; i < 20; i++) warning(world, center.add(pressureAim.scale(i)), .12);
    }
    private void coreContact(ServerLevel world, MoveRunner r) {
        Vec3 center = arena();
        boolean line = "line".equals(r.move().effect());
        double radius = r.move().contacts().get(r.contactIndex()) * 20 * .45 + 1;
        if (line) {
            for (int i = 1; i < 20; i++) {
                Vec3 p = center.add(pressureAim.scale(i));
                world.sendParticles(new net.minecraft.core.particles.DustParticleOptions(0xB7233C, 1), p.x, p.y + .6, p.z, 2, .25, .6, .25, .01);
            }
            sound(world, SoundEvents.EVOKER_CAST_SPELL, .5f);
        } else for (int i = 0; i < 48; i++) {
            double a = i * Math.PI / 24;
            world.sendParticles(ParticleTypes.CRIMSON_SPORE, center.x + Math.cos(a) * radius, center.y + .15, center.z + Math.sin(a) * radius, 1, 0, 0, 0, 0);
        }
        for (ServerPlayer p : world.players()) {
            if (!canFight(p) || p.distanceToSqr(center) > 22 * 22 || phase() == 2 && rootsOpen() && p.distanceToSqr(this) < 3.2 * 3.2) continue;
            Vec3 d = p.position().subtract(center);
            double along = d.dot(pressureAim), across = Math.abs(d.x * pressureAim.z - d.z * pressureAim.x);
            boolean touches = line ? along > 0 && along < 20 && across < 1.1 && Math.abs(d.y) < 2
                    : Math.abs(Math.hypot(d.x, d.z) - radius) < .65 && d.y > -1 && d.y < .65;
            if (touches && hasLineOfSight(p) && pressureStruck.add(p.getUUID())) {
                float scale = world.getDifficulty() == net.minecraft.world.Difficulty.HARD ? 1.25f : world.getDifficulty() == net.minecraft.world.Difficulty.EASY ? .75f : 1;
                corruptionHit(p, (float) r.move().damage() * scale, 8);
            }
        }
    }
    private void finale(ServerLevel world) {
        getNavigation().stop(); setDeltaMovement(0, 0, 0);
        if (bar != null) bar.removeAllPlayers();
        if (!endingSent) { QueenEndings.join(this, endingTick, endingViewers); endingSent = true; }
        // 重连与后来进入现场的人只接续余下时长，不重播或重新清理世界。
        if (endingTick % 10 == 0) QueenEndings.join(this, endingTick, endingViewers);
        if (endingTick == 35) message(world, "last_words");
        if (endingTick >= QueenBattleRules.EXPLODE) {
            setInvisible(true);
            if (endingTick == QueenBattleRules.EXPLODE) {
                sound(world, SoundEvents.GENERIC_EXPLODE.value(), .65f);
                world.sendParticles(ParticleTypes.ENCHANT, getX(), getY() + 2, getZ(), 64, 2.5, 2, 2.5, .2);
                world.sendParticles(ParticleTypes.CHERRY_LEAVES, getX(), getY() + 2, getZ(), 36, 2, 1.8, 2, .02);
            }
            if (heart != null && world.getBlockEntity(heart) instanceof QueenHeart core && !core.getBlockState().getValue(QueenHeartBlock.DEFEATED)) core.defeated(getUUID());
        }
        if (endingTick >= QueenBattleRules.EXPLODE && !purified && heart != null) {
            WorldTreePurification.get(world).start(world, heart, QueenBattleRules.PURIFY_RADIUS);
            purified = true;
        }
        if (++endingTick >= QueenBattleRules.END) {
            if (!purified && heart != null) return;
            net.minecraft.world.entity.ExperienceOrb.award(world, position(), xpReward);
            discard();
        }
    }

    private @Nullable MoveRunner runner() {
        if (!(level() instanceof ServerLevel world)) return null;
        Moveset set = Movesets.get(world.getServer(), MOVESET);
        if (set == null) return null;
        if (runner == null || runner.moveset() != set) {
            String previous = action;
            runner = new MoveRunner(set, this, world.getGameTime());
            if (!previous.isEmpty() && set.move(previous) != null) runner.force(previous, world.getGameTime());
        }
        return runner;
    }
    public void play(String move) {
        MoveRunner r = runner();
        String name = move.isEmpty() ? "queen_idle" : move;
        if (r != null && r.moveset().move(name) != null) r.force(name, level().getGameTime());
    }
    @Override public double attackSpeed() { return 1; }
    @Override public void moveStarted(MoveRunner r) {
        if (r.cleared()) { action = ""; entityData.set(MOVE, ""); return; }
        var move = r.move();
        action = move.name().equals("queen_idle") ? "" : move.name();
        elapsed = 0; struck.clear(); getNavigation().stop(); origin = position();
        LivingEntity target = getTarget();
        aim = target == null ? getLookAngle().multiply(1, 0, 1).normalize() : target.position().subtract(position()).multiply(1, 0, 1).normalize();
        if (aim.lengthSqr() < .01) aim = new Vec3(0, 0, 1);
        setYRot((float) Math.toDegrees(Math.atan2(-aim.x, aim.z))); yBodyRot = getYRot();
        setXRot(0);
        entityData.set(MOVE, move.clip() + "|" + (long) r.start() + "|" + (float) r.rate() + "|" + (move.playback() == Moveset.Playback.LOOP));
        if (move.name().equals("queen_sword_rain")) {
            spellPoint = target == null ? position().add(aim.scale(9)) : target.position();
            if (level() instanceof ServerLevel world) QueenSpellEffects.send(world, QueenSpellPayload.SIGIL, spellPoint.add(0, 12, 0), Vec3.ZERO, 6, (int) (move.length() * 20));
        } else if (move.name().startsWith("queen_life_")) {
            spellPoint = target == null ? getEyePosition().add(aim.scale(12)) : target.getEyePosition();
            if (level() instanceof ServerLevel world) QueenSpellEffects.send(world, QueenSpellPayload.CAST, getEyePosition(), Vec3.ZERO, 1.4f, (int) (move.contact() * 20));
        }
        if (action.isEmpty()) wait = phase() >= 2 ? 18 : 32;
        if (level() instanceof ServerLevel world && move.hasContact())
            sound(world, action.equals("queen_roots") ? SoundEvents.EVOKER_PREPARE_SUMMON : SoundEvents.EVOKER_PREPARE_ATTACK, .7f);
    }
    @Override public void tick() {
        super.tick();
        if (!(level() instanceof ServerLevel world)) return;
        if (!isAlive()) return;
        if (tickCount % 20 == 0) refreshWard(world);
        mana = Math.min(600, mana + .5);
        if (phase() == 4) { finale(world); return; }
        if (world.getDifficulty() == net.minecraft.world.Difficulty.PEACEFUL) {
            getNavigation().stop();
            setTarget(null);
            if (runner != null) runner.force(action.equals("queen_guard") ? "queen_guard" : phase() == 3 ? "queen_bind" : "queen_idle", world.getGameTime());
            coreRunner = null;
            if (bar != null) bar.removeAllPlayers();
            return;
        }
        // 场地被管理员删掉时不留下孤立 Boss；暂时未加载的核心不能算被删。
        if (heart != null && world.isLoaded(heart) && !world.getBlockState(heart).is(ElvenBlight.HEART.get())) { discard(); return; }
        if (heart != null && distanceToSqr(Vec3.atCenterOf(heart)) > 32 * 32) {
            setPos(world.getBlockEntity(heart) instanceof QueenHeart core ? core.guardianPosition() : Vec3.atBottomCenterOf(heart));
            getNavigation().stop();
            play("queen_guard");
        }
        MoveRunner r = runner();
        if (r == null) { getNavigation().stop(); entityData.set(MOVE, ""); if (bar != null) bar.removeAllPlayers(); return; }
        r.tick(world.getGameTime());
        elapsed = (int) r.age(world.getGameTime());
        if (action.equals("queen_guard")) {
            getNavigation().stop();
            for (ServerPlayer p : world.players()) if (canFight(p) && p.distanceToSqr(this) < 14 * 14 && hasLineOfSight(p)) { engage(p); break; }
            return;
        }
        updateBar(world);
        if (transitioning()) {
            getNavigation().stop(); setDeltaMovement(0, getDeltaMovement().y, 0);
            if (elapsed == 12 || elapsed == 30) sound(world, elapsed == 12 ? SoundEvents.WOOD_BREAK : SoundEvents.EVOKER_CAST_SPELL, .55f);
            if (elapsed % 4 == 0) world.sendParticles(new net.minecraft.core.particles.DustParticleOptions(0xB7233C, 1.2f), getX(), getY() + 3, getZ(), 6, 1, 1.4, 1, .01);
            return;
        }
        if (phase() == 3) {
            getNavigation().stop(); setDeltaMovement(0, getDeltaMovement().y, 0);
            if (tickCount % 10 == 0) ensureTethers(world);
            coreAttack(world);
            return;
        }
        if (entering()) {
            getNavigation().stop();
            if (elapsed % 10 == 0) {
                world.sendParticles(ParticleTypes.ASH, getX(), getY() + 1.3, getZ(), 12, .8, 1, .8, .02);
                if (elapsed == 20) world.playSound(null, blockPosition(), SoundEvents.SCULK_SHRIEKER_SHRIEK, SoundSource.HOSTILE, .9f, .6f);
            }
            return;
        }
        if (!action.isEmpty()) {
            getNavigation().stop();
            if (action.equals("queen_resist") || action.equals("queen_aid")) {
                if (tickCount % 10 == 0) ensureTethers(world);
                if (phase() == 2) coreAttack(world);
                return;
            }
            setYRot((float) Math.toDegrees(Math.atan2(-aim.x, aim.z)));
            yBodyRot = getYRot();
            attackFrame(world, r);
            if (phase() == 2) coreAttack(world);
            return;
        }
        ServerPlayer target = world.players().stream().filter(p -> canFight(p) && p.distanceToSqr(this) < 40 * 40)
                .min(java.util.Comparator.comparingDouble(p -> p.distanceToSqr(this))).orElse(null);
        setTarget(target);
        if (target == null) {
            getNavigation().stop();
            if (++absentTicks >= 600 && heart != null) {
                setHealth(getMaxHealth()); changePhase(1); setPos(arena()); play("queen_guard");
                if (bar != null) bar.removeAllPlayers();
                absentTicks = 0; sequence = 0;
            }
            return;
        }
        absentTicks = 0;
        if (phase() == 2) coreAttack(world);
        getLookControl().setLookAt(target, 20, 20);
        if (distanceToSqr(target) > 4 * 4) getNavigation().moveTo(target, 1);
        else getNavigation().stop();
        if (--wait > 0 || !hasLineOfSight(target)) return;
        if (strain >= 24 || sequence > 0 && sequence % (phase() == 2 ? 2 : 4) == 0) {
            strain = 0; sequence++; play(phase() == 2 ? "queen_aid" : "queen_resist");
            message(world, "resist"); ensureTethers(world); return;
        }
        if (distanceToSqr(target) < 5 * 5 && sequence % 3 == 0) play("queen_reap");
        else {
            String next = switch (sequence % 6) { case 1 -> "queen_thorns"; case 2 -> "queen_life_fan"; case 3 -> "queen_roots"; case 4 -> "queen_life_ray"; default -> "queen_sword_rain"; };
            if (mana < (next.equals("queen_sword_rain") ? 240 : 60)) next = "queen_roots";
            play(next);
        }
        sequence++;
    }

    private static boolean canFight(Player p) { return p.isAlive() && !p.isCreative() && !p.isSpectator(); }

    /** 这里只画前摇提示；命中和招式结束均由 MoveRunner 的数据时间表触发。 */
    private void attackFrame(ServerLevel world, MoveRunner r) {
        int tick = (int) r.age(world.getGameTime());
        if (!r.move().hasContact() || tick >= r.move().contact() * 20 || tick % 4 != 0) return;
        if (r.move().name().equals("queen_sword_rain")) {
            // 剑阵覆盖范围先在地面显形，落点锁定后不追着玩家脚下瞬移。
            for (int i = 0; i < 24; i++) {
                double angle = i * Math.PI / 12;
                world.sendParticles(new net.minecraft.core.particles.DustParticleOptions(0xB7233C, .8f), spellPoint.x + Math.cos(angle) * 4.5, spellPoint.y + .12, spellPoint.z + Math.sin(angle) * 4.5, 1, 0, 0, 0, 0);
            }
        }
        switch (r.move().name()) {
            case "queen_reap" -> warning(world, origin.add(aim.scale(3)), .7);
            case "queen_roots" -> ring(world, 2.5, true);
            case "queen_thorns" -> { for (int i = 2; i <= 16; i++) warning(world, origin.add(aim.scale(i)), .08); }
            default -> { }
        }
    }
    private void castMagic(String id, MoveRunner r) {
        if (!(level() instanceof ServerLevel world)) return;
        Vec3 from = getEyePosition();
        if (id.equals("rain")) {
            double angle = r.contactIndex() * 2.39996323, radius = Math.sqrt((r.contactIndex() + .5) / r.move().contacts().size()) * 4.5;
            from = spellPoint.add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
        } else {
            Vec3 direction = spellPoint.subtract(getEyePosition()).normalize();
            setYRot((float) Math.toDegrees(Math.atan2(-direction.x, direction.z))); yBodyRot = getYRot();
            setXRot((float) -Math.toDegrees(Math.atan2(direction.y, Math.hypot(direction.x, direction.z))));
        }
        QueenSpells.cast(this, id, from, getTarget());
    }
    @Override public void contact(MoveRunner r) {
        if (!(level() instanceof ServerLevel world)) return;
        float damage = (float) r.move().damage();
        switch (r.move().name()) {
            case "queen_life_fan" -> castMagic("fan", r);
            case "queen_life_ray" -> castMagic("ray", r);
            case "queen_sword_rain" -> castMagic("rain", r);
            case "queen_reap" -> {
                for (ServerPlayer p : targets(world, 5.5)) {
                    Vec3 delta = p.position().subtract(origin).multiply(1, 0, 1);
                    if (delta.lengthSqr() <= 25 && delta.normalize().dot(aim) >= .5 && hasLineOfSight(p)) hit(world, p, damage);
                }
                sound(world, SoundEvents.PLAYER_ATTACK_SWEEP, .65f);
            }
            case "queen_roots" -> {
                double radius = (r.move().contacts().get(r.contactIndex()) - r.move().contact()) * 20 * .38 + 1;
                ring(world, radius, false);
                for (ServerPlayer p : targets(world, 13)) {
                    double d = p.position().multiply(1, 0, 1).distanceTo(origin.multiply(1, 0, 1));
                    if (Math.abs(d - radius) < .65 && p.getY() < origin.y + .65 && p.getY() > origin.y - 1 && hasLineOfSight(p)) hit(world, p, damage);
                }
            }
            case "queen_thorns" -> {
                for (int i = 1; i <= 16; i++) {
                    Vec3 p = origin.add(aim.scale(i));
                    world.sendParticles(new net.minecraft.core.particles.DustParticleOptions(0xB7233C, 1), p.x, p.y + .5, p.z, 3, .3, .7, .3, .02);
                }
                for (ServerPlayer p : targets(world, 18)) {
                    Vec3 d = p.position().subtract(origin);
                    double along = d.dot(aim), across = Math.abs(d.x * aim.z - d.z * aim.x);
                    if (along > 0 && along < 17 && across < 1.2 && Math.abs(d.y) < 2 && hasLineOfSight(p)) hit(world, p, damage);
                }
                sound(world, SoundEvents.EVOKER_CAST_SPELL, .65f);
            }
            default -> { }
        }
    }
    private List<ServerPlayer> targets(ServerLevel world, double range) {
        return world.players().stream().filter(p -> canFight(p) && p.distanceToSqr(origin) <= range * range).toList();
    }
    private void hit(ServerLevel world, ServerPlayer player, float damage) {
        if (!struck.add(player.getUUID())) return;
        float scale = switch (world.getDifficulty()) { case EASY -> .75f; case HARD -> 1.25f; default -> 1; };
        corruptionHit(player, damage * scale, 10);
    }
    /** 女王和腐败之心的打击都是腐败伤害（生命的子类型）：走 Logos 的生命结算，真的掉了血再吸魔力。 */
    private void corruptionHit(ServerPlayer player, float damage, double drain) {
        float before = player.getHealth() + player.getAbsorptionAmount();
        dev.logos.dimension.corruption.CorruptionDamage.hit(this, player, damage);
        if (player.getHealth() + player.getAbsorptionAmount() < before) CorruptionContact.drainMana(player, drain);
    }
    private void ring(ServerLevel world, double radius, boolean warning) {
        int count = Math.max(12, (int) (radius * 8));
        for (int i = 0; i < count; i++) {
            double a = i * Math.PI * 2 / count;
            Vec3 p = origin.add(Math.cos(a) * radius, .15, Math.sin(a) * radius);
            world.sendParticles(warning ? ParticleTypes.ASH : new net.minecraft.core.particles.DustParticleOptions(0xB7233C, 1), p.x, p.y, p.z, 1, .03, .1, .03, .005);
        }
    }
    private void warning(ServerLevel world, Vec3 p, double spread) { world.sendParticles(ParticleTypes.ASH, p.x, p.y + .2, p.z, 3, spread, .1, spread, .005); }
    private void sound(ServerLevel world, net.minecraft.sounds.SoundEvent sound, float pitch) { world.playSound(null, blockPosition(), sound, SoundSource.HOSTILE, 1, pitch); }

    @Override public boolean hurtServer(ServerLevel world, DamageSource source, float amount) {
        // 招式损坏或缺失时允许原版伤害，不把女王锁在无法完成的免伤状态。
        amount = incoming(world, source, amount);
        if (Movesets.get(world.getServer(), MOVESET) == null) return super.hurtServer(world, source, amount);
        if (action.equals("queen_guard") && source.getEntity() instanceof ServerPlayer player && canFight(player)) engage(player);
        if ((entering() || transitioning()) && !source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY)) return false;
        if (!source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            if (phase() >= 3 || amount <= 0 || !Float.isFinite(amount) || !(source.getEntity() instanceof LivingEntity)) return false;
            if (invulnerableTime > 10) return false;
            invulnerableTime = 20;
            strain += Math.min(amount, 12);
            // 命中有反馈，但伤的是控制韧性，不能提前杀死受控制的女王。
            world.broadcastEntityEvent(this, (byte) 2);
            return true;
        }
        return super.hurtServer(world, source, amount);
    }
    @Override public void die(DamageSource source) {
        if (level() instanceof ServerLevel world && heart != null && world.getBlockEntity(heart) instanceof QueenHeart core) core.defeated(getUUID());
        if (bar != null) bar.removeAllPlayers();
        super.die(source);
    }
    private void updateBar(ServerLevel world) {
        if (bar == null) bar = new ServerBossEvent(getUUID(), Component.translatable("entity.logos_dimension.corrupted_elf_queen"), BossEvent.BossBarColor.PURPLE, BossEvent.BossBarOverlay.PROGRESS);
        bar.setProgress(Mth.clamp(getHealth() / getMaxHealth(), 0, 1));
        bar.setName(Component.translatable("boss.logos_dimension.queen.phase" + phase()));
        for (ServerPlayer p : List.copyOf(bar.getPlayers())) if (p.level() != world || !p.isAlive() || p.distanceToSqr(this) > 48 * 48) bar.removePlayer(p);
        for (ServerPlayer p : world.players()) if (!p.isSpectator() && p.distanceToSqr(this) < 40 * 40) bar.addPlayer(p);
    }
    @Override public void remove(RemovalReason reason) { if (bar != null) bar.removeAllPlayers(); super.remove(reason); }
    @Override public void stopSeenByPlayer(ServerPlayer player) { super.stopSeenByPlayer(player); if (bar != null) bar.removePlayer(player); }

    @Override protected void addAdditionalSaveData(ValueOutput out) {
        super.addAdditionalSaveData(out);
        if (heart != null) { out.putInt("HeartX", heart.getX()); out.putInt("HeartY", heart.getY()); out.putInt("HeartZ", heart.getZ()); }
        out.putBoolean("Guarding", action.equals("queen_guard"));
        out.putBoolean("Emerging", entering());
        out.putBoolean("Entrance", action.equals("queen_entrance"));
        out.putBoolean("IntroPlayed", introPlayed);
        out.putInt("EmergenceTick", elapsed);
        out.putInt("CorePhase", phase()); out.putInt("BrokenRoots", brokenRoots()); out.putFloat("ControlStrain", strain);
        out.putInt("QueenSequence", sequence); out.putInt("EndingTick", endingTick); out.putBoolean("Purified", purified);
        out.putLong("PhaseTime", entityData.get(PHASE_TIME)); out.putLong("QueenPhaseAge", Math.max(0, level().getGameTime() - entityData.get(PHASE_TIME))); out.putString("QueenAction", action);
        out.putBoolean("ThreeBars", true); out.putDouble("QueenMana", mana);
    }
    @Override protected void readAdditionalSaveData(ValueInput in) {
        super.readAdditionalSaveData(in);
        if (!in.getBooleanOr("ThreeBars", false)) { getAttribute(Attributes.MAX_HEALTH).setBaseValue(QueenBattleRules.HEALTH); setHealth(QueenBattleRules.HEALTH); }
        // 存档里带着旧版的属性基础值（例如 75% 法术抗性），读完按当前的抗性表重写。
        if (level() instanceof ServerLevel server) traits(server).apply(this);
        mana = Math.clamp(in.getDoubleOr("QueenMana", 600), 0, 600);
        if (in.getInt("HeartY").isPresent()) heart = new BlockPos(in.getIntOr("HeartX", 0), in.getIntOr("HeartY", 0), in.getIntOr("HeartZ", 0));
        if (heart != null) { entityData.set(HEART_POS, heart); entityData.set(BOUND, true); }
        introPlayed=in.getBooleanOr("IntroPlayed",in.getBooleanOr("Emerging",false));
        entityData.set(PHASE, Math.clamp(in.getIntOr("CorePhase", 1), 1, 4));
        entityData.set(BROKEN, in.getIntOr("BrokenRoots", 0) & 7); strain = in.getFloatOr("ControlStrain", 0);
        sequence = Math.max(0, in.getIntOr("QueenSequence", 0)); endingTick = Math.clamp(in.getIntOr("EndingTick", 0), 0, QueenBattleRules.END);
        purified = in.getBooleanOr("Purified", false); // 读档后重建相对时钟，卸载期间不让核心下降或收缩演出在后台快进。
        long phaseAge = Math.max(0, in.getLongOr("QueenPhaseAge", phase() == 4 ? endingTick : level().getGameTime() - in.getLongOr("PhaseTime", level().getGameTime())));
        entityData.set(PHASE_TIME, level().getGameTime() - phaseAge);
        runner = null; coreRunner = null; pressureStruck.clear();
        String savedMove = in.getStringOr("QueenAction", "");
        if (savedMove.equals("queen_chain_break") || savedMove.equals("queen_core_fall")) { restoreMove(savedMove, Math.clamp(in.getIntOr("EmergenceTick", 0), 0, savedMove.equals("queen_chain_break") ? 60 : 64)); return; }
        if (phase() >= 3) { restoreMove(phase() == 4 ? "queen_sacrifice" : "queen_bind", phase() == 4 ? endingTick : 0); return; }
        if (in.getBooleanOr("Guarding", false)) play("queen_guard");
        else if (in.getBooleanOr("Emerging", false)) { boolean entrance=in.getBooleanOr("Entrance",false); restoreMove(entrance?"queen_entrance":"queen_rise", Mth.clamp(in.getIntOr("EmergenceTick", 0), 0, entrance?QueenEntrances.TICKS:RISE_TICKS)); }
        else if (in.getStringOr("QueenAction", "").equals("queen_resist") || in.getStringOr("QueenAction", "").equals("queen_aid")) {
            restoreMove(in.getStringOr("QueenAction", "queen_resist"), Math.clamp(in.getIntOr("EmergenceTick", 0), 0, QueenBattleRules.RESIST_TICKS));
        } else play("");
    }
    private void restoreMove(String name, int age) {
        MoveRunner r = runner();
        if (r != null && r.moveset().move(name) != null) { r.force(name, level().getGameTime() - age); elapsed = age; }
    }
    @Override public Identifier npcAnimationAsset() { return LogosDimension.id("corrupted_elf_queen"); }
    @Override public @Nullable CombatMove npcCombatMove() {
        String current = entityData.get(MOVE);
        if (!current.equals(clientAction)) {
            clientAction = current;
            String[] parts = current.split("\\|");
            clientMove = parts.length < 2 ? null : new CombatMove(parts[0], Long.parseLong(parts[1]), parts.length > 2 ? Float.parseFloat(parts[2]) : 1,
                    parts.length > 3 ? Boolean.parseBoolean(parts[3]) : parts[0].equals("queen_guard") || parts[0].equals("queen_bind"));
        }
        return clientMove;
    }
    @Override public void registerControllers(AnimatableManager.ControllerRegistrar c) { NpcAnimations.registerControllers(this, c); }
    @Override public AnimatableInstanceCache getAnimatableInstanceCache() { return cache; }
}
