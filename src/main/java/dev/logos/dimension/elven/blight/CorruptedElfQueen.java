package dev.logos.dimension.elven.blight;

import com.geckolib.animatable.instance.AnimatableInstanceCache;
import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.util.GeckoLibUtil;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.camera.CameraPoint;
import dev.logos.dimension.camera.CameraShot;
import dev.logos.dimension.camera.CameraShots;
import dev.logos.dimension.combat.*;
import dev.logos.dimension.companion.CompanionLines;
import dev.logos.dimension.corruption.Corruption;
import dev.logos.dimension.corruption.CorruptionContact;
import dev.logos.dimension.corruption.CorruptionDamage;
import dev.logos.dimension.corruption.CorruptionNetworks;
import dev.logos.dimension.corruption.mob.CorruptionMobs;
import dev.logos.dimension.npc.NpcAnimated;
import dev.logos.dimension.npc.NpcAnimations;
import dev.logos.dimension.screenfx.ScreenEffects;
import dev.logos.dimension.screenfx.ScreenFlash;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
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
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent;
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 腐化女王（docs/CORRUPTED_QUEEN_REMAKE.md 第 5 节）。坐在腐败之心下，公主到场后才开战。
 *
 * <ul>
 * <li>阶段一（被操控）：单挑。近战加五种 Logos 腐败法术，范围招在地上留下腐败污秽。血量打空进入阶段二。
 * <li>阶段二（守护净化）：镜头切到祭坛，公主落下来开始净化腐败之心（{@link HeartPurification}）。女王被线吊着站在场地正中，
 * 不再受伤，只朝玩家放攻击法术；腐败之心每隔几秒用腐败光柱轰击场地，落点铺上脉络；隧道里出衍生物，优先打公主，
 * 砍断高台上的血管接头让它们少一些、慢一些。净化每到一个刻度腐败之心降下来，谁砍中它一刀就是一段处决（固定 20%）。
 * <li>阶段三（狂乱）：第五刀斩断连着女王的线，她跪倒；腐败之心胡乱轰击，脉络从中央往外漫到快淹没祭坛，这时她站起来。
 * <li>阶段四：拥心共殒（结局，见 {@link QueenEndings}）。
 * </ul>
 * 命中由服务端结算，形状、出招顺序、耗魔在招式文件里（{@link QueenMoves}）；全部打击是腐败伤害（{@link CorruptionDamage}）。
 * 屏幕上不写状态：玩家看画面，提示和数量都由公主说出来（{@link ElfPrincess#say}）。
 */
public class CorruptedElfQueen extends PathfinderMob implements NpcAnimated, MoveRunner.Actor {
    public static final Identifier MOVESET = LogosDimension.id("corrupted_queen"), CORE_MOVESET = LogosDimension.id("queen_core");
    public static final int RISE_TICKS = 40;
    /** 砍中腐败之心之后这么多刻里女王和腐败之心都不出招（处决的镜头）。 */
    public static final int CUT_PAUSE = 34;
    private static final EntityDataAccessor<String> MOVE = SynchedEntityData.defineId(CorruptedElfQueen.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Integer> PHASE = SynchedEntityData.defineId(CorruptedElfQueen.class, EntityDataSerializers.INT);
    /** 砍断的主血管（第 i 位是第 i 条）。 */
    private static final EntityDataAccessor<Integer> BROKEN = SynchedEntityData.defineId(CorruptedElfQueen.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Long> PHASE_TIME = SynchedEntityData.defineId(CorruptedElfQueen.class, EntityDataSerializers.LONG);
    private static final EntityDataAccessor<BlockPos> HEART_POS = SynchedEntityData.defineId(CorruptedElfQueen.class, EntityDataSerializers.BLOCK_POS);
    private static final EntityDataAccessor<Boolean> BOUND = SynchedEntityData.defineId(CorruptedElfQueen.class, EntityDataSerializers.BOOLEAN);
    /** 周围还在供养她的腐败核心数（核心庇护），每秒刷新一次。 */
    private static final EntityDataAccessor<Integer> WARD = SynchedEntityData.defineId(CorruptedElfQueen.class, EntityDataSerializers.INT);
    /** 净化进度、已经斩掉几段、降下来还剩几刻：同步给血条、腐败之心的升降和周围那圈光。 */
    private static final EntityDataAccessor<Float> PURIFY = SynchedEntityData.defineId(CorruptedElfQueen.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> CLEARED = SynchedEntityData.defineId(CorruptedElfQueen.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> WINDOW = SynchedEntityData.defineId(CorruptedElfQueen.class, EntityDataSerializers.INT);
    /** 公主的实体编号（客户端画净化圈用），没有为 -1。 */
    private static final EntityDataAccessor<Integer> PRINCESS = SynchedEntityData.defineId(CorruptedElfQueen.class, EntityDataSerializers.INT);
    /** 抗性表 data/logos_dimension/creature_traits/corrupted_queen.json，血管接头也用这一份。 */
    public static final Identifier TRAITS = LogosDimension.id("corrupted_queen");
    public static final TagKey<DamageType> BYPASSES_WARD = TagKey.create(Registries.DAMAGE_TYPE, LogosDimension.id("bypasses_blight_ward"));
    /** 波次里的衍生物带这个标签，重置和结局时清掉。 */
    public static final String WAVE_TAG = "logos_dimension.queen_wave";
    /** 字幕里女王名字的颜色。 */
    public static final int NAME_COLOR = 0xE58FA6;
    private static final List<java.util.function.Supplier<? extends EntityType<? extends Mob>>> WAVE_TYPES = List.of(CorruptionMobs.FILTH_ZOMBIE,
            CorruptionMobs.FILTH_SKELETON, CorruptionMobs.FILTH_SLIME, CorruptionMobs.FILTH_ZOMBIE, CorruptionMobs.FILTH_SILVERFISH);

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private final Set<UUID> struck = new HashSet<>();
    private @Nullable BlockPos heart;
    private @Nullable ServerBossEvent bar;
    private String action = "", clientAction = "";
    private @Nullable CombatMove clientMove;
    private int elapsed, wait = 20, sequence;
    private boolean introPlayed;
    private float scale = 1;
    private int endingTick, absentTicks, waveTimer, waveCount, cutPause;
    private @Nullable MoveRunner runner, coreRunner;
    private boolean endingSent, purified, allowDeath;
    private final Set<UUID> endingViewers = new HashSet<>();
    /** 这一下光柱的落点和已经打到的。 */
    private final List<Vec3> pillarSpots = new ArrayList<>();
    private final Set<UUID> pillarStruck = new HashSet<>();
    private Vec3 aim = Vec3.ZERO, origin = Vec3.ZERO, staggerFrom = Vec3.ZERO;
    private Vec3 spellPoint = Vec3.ZERO;
    private double mana = 600;
    private final HeartPurification purification = new HeartPurification();
    private final List<UUID> waveMobs = new ArrayList<>();
    private final LongOpenHashSet filth = new LongOpenHashSet();
    /** 轰下来的光柱一共几道（测试用）。 */
    private int pillarsFired;
    /** 最近一下伤害算过抗性表和核心庇护之后、护甲之前的量（测试用）。 */
    private float lastIncoming;


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
        entityData.set(WARD, ward == null ? 0 : CorruptionNetworks.get(world).coresWithin(blockPosition(), ward.radius()));
    }
    /** 核心庇护这一刻减多少（0–1）。 */
    public double wardReduction(ServerLevel world) {
        var ward = traits(world).ward();
        return ward == null ? 0 : ward.reduction(ward());
    }
    /** 受到的伤害先按抗性表乘，再扣核心庇护（虚空等 {@code bypasses_blight_ward} 标签里的不扣）。血管接头也走这里。 */
    public float incoming(ServerLevel world, DamageSource source, float amount) {
        amount = traits(world).adjust(source, amount);
        if (!source.is(BYPASSES_WARD)) amount *= (float) (1 - wardReduction(world));
        return amount;
    }

    public static AttributeSupplier.Builder attributes() {
        return PathfinderMob.createMobAttributes().add(Attributes.MAX_HEALTH, QueenBattleRules.HEALTH).add(Attributes.MOVEMENT_SPEED, .28)
                .add(Attributes.ARMOR, QueenBattleRules.ARMOR).add(Attributes.KNOCKBACK_RESISTANCE, 1).add(Attributes.FOLLOW_RANGE, 40).add(Attributes.STEP_HEIGHT, 1);
    }

    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(MOVE, ""); builder.define(PHASE, 1); builder.define(BROKEN, 0); builder.define(PHASE_TIME, 0L); builder.define(HEART_POS, BlockPos.ZERO);
        builder.define(BOUND, false); builder.define(WARD, 0); builder.define(PURIFY, 0f); builder.define(CLEARED, 0);
        builder.define(WINDOW, 0); builder.define(PRINCESS, -1);
    }

    // ---------------------------------------------------------------- 查询

    public void guard(BlockPos at) { heart = at.immutable(); entityData.set(HEART_POS, heart); entityData.set(BOUND, true); play("queen_guard"); }
    public String action() { return action; }
    public int actionTick() { return elapsed; }
    public boolean entering() { return action.equals("queen_rise") && phase() == 1 || action.equals("queen_entrance"); }
    public boolean introPlayed() { return introPlayed; }
    public double mana() { return mana; }
    public boolean payMana(double amount) { if (mana < amount) return false; mana -= amount; return true; }
    public int phase() { return entityData.get(PHASE); }
    public float phaseAge(float partial) { return level().getGameTime() - entityData.get(PHASE_TIME) + partial; }
    public int brokenRoots() { return entityData.get(BROKEN); }
    public float scale() { return scale; }
    public @Nullable BlockPos heartPosition() { return entityData.get(BOUND) ? entityData.get(HEART_POS) : heart; }
    public int endingTick() { return endingTick; }
    public HeartPurification purification() { return purification; }
    /** 客户端和服务端都能读的净化状态。 */
    public float purifyProgress() { return entityData.get(PURIFY); }
    public int purifyCleared() { return entityData.get(CLEARED); }
    public int purifyWindow() { return entityData.get(WINDOW); }
    /** 离下一次降下还差几成（客户端画腐败之心周围那圈光合拢到哪儿）。 */
    public float purifyToNext() {
        var view = new HeartPurification();
        view.sync(purifyProgress(), purifyCleared(), purifyWindow());
        return view.toNext();
    }
    public int princessId() { return entityData.get(PRINCESS); }
    public List<UUID> waveMobs() { return List.copyOf(waveMobs); }
    public int filthCount() { return filth.size(); }
    public int pillarsFired() { return pillarsFired; }
    /** 测试用：最近一下伤害算过 Logos 抗性、抗性表和核心庇护之后（护甲之前）的量。 */
    public float lastIncoming() { return lastIncoming; }
    /** 阶段二开头镜头切到祭坛、公主落下来的那几秒：这时她不出招、不受伤。 */
    public boolean transitioning() { return phase() == 2 && phaseAge(0) < QueenBattleRules.INTRO; }
    /** 血管接头这时能砍。 */
    public boolean veinsOpen() { return phase() == 2 && !transitioning(); }
    /** 刚砍中腐败之心、镜头在看处决（谁都不出招）。 */
    public boolean cutting() { return cutPause > 0; }
    public Vec3 arena() {
        var at = heartPosition();
        return at != null && level().getBlockEntity(at) instanceof QueenHeart core ? core.guardianPosition() : level().isClientSide() ? position() : origin;
    }
    public Direction facing() {
        var at = heartPosition();
        if (at != null) { var state = level().getBlockState(at); if (state.hasProperty(QueenHeartBlock.FACING)) return state.getValue(QueenHeartBlock.FACING); }
        return Direction.SOUTH;
    }
    /** 树根祭坛（公主站的地方）。 */
    public Vec3 altar() {
        var at = heartPosition();
        return at == null ? arena() : QueenArena.at(at, facing(), level() instanceof ServerLevel world ? QueenArena.get(world.getServer()).altar() : QueenArena.DEFAULT.altar());
    }
    /** 狂乱时脉络最远漫到离中央多远：快到祭坛（留 {@link QueenBattleRules#ALTAR_CLEAR} 格）。 */
    public double frenzyLimit() { return Math.max(4, altar().subtract(arena()).horizontalDistance() - QueenBattleRules.ALTAR_CLEAR + 1); }
    /** 腐败之心（悬吊模型的底面中心）这一刻的位置：降下来时压低。 */
    public Vec3 corePosition(float partial) {
        var at = heartPosition();
        Vec3 top = at == null ? arena().add(0, 7, 0) : at.getBottomCenter();
        int window = purifyWindow();
        float left = window <= 0 ? 0 : window - partial, age = window <= 0 ? 0 : HeartPurification.WINDOW_TICKS - window + partial;
        return top.add(0, QueenBattleRules.coreDrop(phase(), phaseAge(partial), age, left), 0);
    }
    /** 腐败之心的中心（光束、光柱从这里出来）。 */
    public Vec3 coreCenter(float partial) { return corePosition(partial).add(0, 2.6, 0); }
    public @Nullable ElfPrincess princess(ServerLevel world) {
        return heart != null && world.getBlockEntity(heart) instanceof QueenHeart core ? core.princess(world) : null;
    }

    // ---------------------------------------------------------------- 开战

    public void engage(ServerPlayer target) {
        if (!action.equals("queen_guard") || !(level() instanceof ServerLevel world)) return;
        setTarget(target);
        // 多人：生命按入场时 40 格内的人数加（每多一人 +50%）。
        long fighters = world.players().stream().filter(p -> canFight(p) && p.distanceToSqr(arena()) < 40 * 40).count();
        scale = 1 + .5f * Math.max(0, fighters - 1);
        getAttribute(Attributes.MAX_HEALTH).setBaseValue(QueenBattleRules.HEALTH * scale);
        setHealth(getMaxHealth());
        purification.reset(scale);
        syncPurification();
        var princess = princess(world);
        if (princess != null) {
            princess.arrive(world);
            princess.resetCounts();
            // 外面还剩几个核心在护着她，公主看得出来，玩家看不出来。
            refreshWard(world);
            if (ward() > 0) princess.say("ward", ward()); else princess.say("ward_none");
        }
        boolean first = !introPlayed;
        introPlayed = true;
        play(first ? "queen_entrance" : "queen_rise");
        if (first) QueenEntrances.play(this, heart == null ? blockPosition().above(7) : heart);
        if (heart != null && level().getBlockEntity(heart) instanceof QueenHeart core) core.engaged(getUUID());
    }

    private void changePhase(int phase) {
        entityData.set(PHASE, phase); entityData.set(PHASE_TIME, level().getGameTime());
        coreRunner = null; struck.clear(); pillarSpots.clear(); cutPause = 0;
    }

    private List<ServerPlayer> fighters(ServerLevel world, double range) {
        return world.players().stream().filter(p -> canFight(p) && p.distanceToSqr(arena()) < range * range).toList();
    }

    /**
     * 阶段一打空：被线拽回中央，公主落到祭坛上开始净化。镜头直接切到祭坛，看她从上面落进画面、蹲着落地、站起来施法。
     */
    private void beginPurification(ServerLevel world) {
        setHealth(1);
        changePhase(2);
        play("queen_resist");
        staggerFrom = position();
        mana = 600;
        waveTimer = QueenBattleRules.INTRO + 80;
        var princess = princess(world);
        if (princess != null) {
            princess.leap(world);
            Vec3 altar = altar(), toCenter = arena().subtract(altar).multiply(1, 0, 1).normalize();
            Vec3 look = altar.add(0, 1.1, 0);
            Vec3 from = CameraShots.reach(world, look, altar.add(toCenter.scale(7.5)).add(toCenter.cross(new Vec3(0, 1, 0)).scale(2.5)).add(0, 1.6, 0));
            var shot = CameraShot.of(CameraPoint.world(from), CameraPoint.world(look)).timing(0, QueenBattleRules.INTRO + 4, 14).letterbox();
            for (ServerPlayer p : fighters(world, 48)) CameraShots.play(p, shot);
        }
        ensureVeins(world);
    }

    /** 第五刀：连着她的线断了，她跪倒；腐败之心狂乱。 */
    private void beginFrenzy(ServerLevel world) {
        changePhase(QueenBattleRules.FRENZY);
        play("queen_kneel");
        setTarget(null);
        getNavigation().stop();
        for (UUID id : waveMobs) if (world.getEntity(id) instanceof LivingEntity e) e.discard();
        waveMobs.clear();
        for (QueenTether t : tethers(world)) t.discard();
        if (bar != null) bar.removeAllPlayers();
        sound(world, SoundEvents.CHAIN_BREAK, .55f);
        sound(world, SoundEvents.WARDEN_ROAR, .7f);
        var princess = princess(world);
        if (princess != null) princess.say("severed");
    }

    /** 狂乱：腐败之心胡乱轰击，脉络从中央往外漫；快淹到祭坛时女王站起来，拥心。 */
    private void frenzy(ServerLevel world, @Nullable ElfPrincess princess) {
        int age = (int) phaseAge(0);
        if (princess != null) princess.aim(coreCenter(0));
        if (age == QueenBattleRules.FRENZY_LINE && princess != null) princess.say("frenzy");
        if (age == QueenBattleRules.FRENZY_RISE) {
            play("queen_rise");
            if (princess != null) princess.say("embrace");
        }
        if (age >= QueenBattleRules.FRENZY_TICKS) { sacrifice(world); return; }
        coreAttack(world);
        // 脉络从中央往外漫，越漫越快，一直到快淹没祭坛。
        double reach = QueenBattleRules.frenzyReach(age, frenzyLimit());
        List<Vec3> spots = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            double a = random.nextDouble() * Math.PI * 2, d = reach * Math.sqrt(.35 + .65 * random.nextDouble());
            spots.add(arena().add(Math.cos(a) * d, 0, Math.sin(a) * d));
        }
        leaveFilth(world, spots, QueenBattleRules.FRENZY_FILTH_CAP);
        if (age % 20 == 0) sound(world, SoundEvents.SCULK_BLOCK_SPREAD, .6f);
    }

    /** 拥心共殒。脉络留到她自爆、净化开始的那一刻。 */
    private void sacrifice(ServerLevel world) {
        for (UUID id : waveMobs) if (world.getEntity(id) instanceof LivingEntity e) e.discard();
        waveMobs.clear();
        for (QueenTether t : tethers(world)) t.discard();
        changePhase(QueenBattleRules.ENDING);
        endingTick = 0;
        play("queen_sacrifice");
        var princess = princess(world);
        if (princess != null) princess.finish(world);
    }

    /** 所有人战死或离开：女王回到坐姿，公主回到大厅入口，进度清零，战斗里长出的污秽和衍生物消散。 */
    public void resetBattle(ServerLevel world) {
        cleanup(world);
        changePhase(1);
        entityData.set(BROKEN, 0);
        setHealth(getMaxHealth());
        purification.reset(1);
        syncPurification();
        setPos(arena());
        setTarget(null);
        play("queen_guard");
        if (bar != null) bar.removeAllPlayers();
        absentTicks = 0; sequence = 0; waveCount = 0;
        var princess = princess(world);
        if (princess != null) { princess.returnToGate(world); princess.goAhead(world); princess.arrive(world); princess.resetCounts(); }
    }

    /** 清掉战斗里放出的衍生物、地上的污秽和血管接头。 */
    private void cleanup(ServerLevel world) {
        for (UUID id : waveMobs) if (world.getEntity(id) instanceof LivingEntity e) e.discard();
        waveMobs.clear();
        clearAllFilth(world);
        for (QueenTether t : tethers(world)) t.discard();
    }

    private void clearAllFilth(ServerLevel world) {
        for (long key : filth) {
            BlockPos at = BlockPos.of(key);
            if (world.isLoaded(at) && world.getBlockState(at).is(Corruption.VEIN.get())) world.removeBlock(at, false);
        }
        filth.clear();
    }

    private void syncPurification() {
        entityData.set(PURIFY, purification.progress());
        entityData.set(CLEARED, purification.cleared());
        entityData.set(WINDOW, purification.window());
    }

    // ---------------------------------------------------------------- 血管与腐败之心

    public List<QueenTether> tethers(ServerLevel world) {
        return world.getEntitiesOfClass(QueenTether.class, getBoundingBox().inflate(42), e -> e.belongsTo(this) && e.isAlive());
    }

    /** 高台上可砍的血管接头：每条还连着、够得着的主血管一个。 */
    private void ensureVeins(ServerLevel world) {
        if (heart == null) return;
        var existing = tethers(world);
        var arena = QueenArena.get(world.getServer());
        for (int slot = 0; slot < HeartVeins.MAIN; slot++) {
            final int n = slot;
            if (!HeartVeins.reachable(slot) || QueenBattleRules.severed(brokenRoots(), slot) || existing.stream().anyMatch(e -> e.slot() == n)) continue;
            Vec3 at = QueenArena.at(heart, facing(), HeartVeins.knot(slot, arena.galleryStand()));
            BlockPos block = BlockPos.containing(at);
            // 只在空位上放，不删玩家放的方块。
            if (!world.getBlockState(block).getCollisionShape(world, block).isEmpty()) continue;
            var root = ElvenBlight.TETHER.get().create(world, EntitySpawnReason.TRIGGERED);
            if (root == null) continue;
            root.bind(this, slot);
            root.setPos(at);
            world.addFreshEntity(root);
        }
    }

    private void ensureHeartProxy(ServerLevel world) {
        for (QueenTether t : tethers(world)) if (t.slot() == QueenTether.HEART) return;
        var proxy = ElvenBlight.TETHER.get().create(world, EntitySpawnReason.TRIGGERED);
        if (proxy == null) return;
        proxy.bind(this, QueenTether.HEART);
        proxy.setPos(corePosition(0));
        world.addFreshEntity(proxy);
    }

    /** 还连着、够得着的血管有几条。 */
    public int veinsLeft() { return QueenBattleRules.reachableVeins() - Integer.bitCount(brokenRoots()); }

    /** 血管接头被砍断（只结算一次）。还剩几条由公主说出来。 */
    public void sever(int slot, DamageSource source) {
        if (!(level() instanceof ServerLevel world) || slot < 0 || slot >= HeartVeins.MAIN || !veinsOpen() || QueenBattleRules.severed(brokenRoots(), slot)) return;
        entityData.set(BROKEN, brokenRoots() | 1 << slot);
        sound(world, SoundEvents.WOOD_BREAK, .6f);
        var princess = princess(world);
        if (princess != null) { if (veinsLeft() > 0) princess.say("vein_cut", veinsLeft()); else princess.say("veins_done"); }
    }

    /**
     * 降下来时砍中腐败之心一刀（近战、箭、法术都算，只要是玩家打的）：处决特写，固定斩掉一段（最大生命的 20%），它升回去。
     * 返回这一下算了没有。
     */
    public boolean heartHit(ServerLevel world, DamageSource source) {
        if (!purification.open() || cutting() || !(source.getEntity() instanceof ServerPlayer attacker) || !canFight(attacker)) return false;
        if (purification.cut() != HeartPurification.Event.CLEARED) return false;
        syncPurification();
        for (QueenTether t : tethers(world)) if (t.slot() == QueenTether.HEART) t.discard();
        heartCut(world, attacker);
        var princess = princess(world);
        if (purification.done()) { beginFrenzy(world); return true; }
        cutPause = CUT_PAUSE;
        if (princess != null) princess.say("cut_" + purification.cleared());
        return true;
    }

    /**
     * 一刀的处决：砍的人镜头切到腐败之心跟前（遮幅），一次黑白闪，一道刀光斜着劈过它，被劈开的那一侧喷出腐败的汁液。
     * 别人在世界里看到刀光和喷出来的汁液。
     */
    private void heartCut(ServerLevel world, ServerPlayer attacker) {
        Vec3 c = coreCenter(0);
        Vec3 toward = attacker.getEyePosition().subtract(c).multiply(1, 0, 1);
        toward = toward.lengthSqr() < 1e-4 ? new Vec3(0, 0, 1) : toward.normalize();
        Vec3 side = toward.cross(new Vec3(0, 1, 0));
        double tilt = random.nextBoolean() ? 1 : -1;
        // 刀从左上劈到右下（或反过来）；汁液从刀口朝刀走的那一侧喷出去。
        Vec3 slash = side.scale(tilt).add(0, -.75, 0).normalize();
        // 刀口在朝着砍的人的那一面（中心在心里面，画在那儿会被心挡住）。
        Vec3 gash = c.add(toward.scale(3));
        Vec3 from = CameraShots.reach(world, c, c.add(toward.scale(8)).add(side.scale(2.2 * tilt)).add(0, -.8, 0));
        CameraShots.play(attacker, CameraShot.of(CameraPoint.world(from), CameraPoint.world(c)).timing(0, CUT_PAUSE - 10, 10).letterbox());
        for (ServerPlayer p : fighters(world, 48)) ScreenEffects.flash(p, ScreenFlash.at(CameraPoint.world(c)));
        QueenSpellEffects.send(world, QueenSpellPayload.CUT, gash, slash, 2.6f, 40);
        world.playSound(null, BlockPos.containing(c), SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.4f, .7f);
        world.playSound(null, BlockPos.containing(c), SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 1.2f, .6f);
        world.playSound(null, BlockPos.containing(c), SoundEvents.HONEY_BLOCK_BREAK, SoundSource.HOSTILE, 1.6f, .6f);
        world.playSound(null, BlockPos.containing(c), SoundEvents.WARDEN_HEARTBEAT, SoundSource.HOSTILE, 1.4f, .5f);
    }

    private void windowOpened(ServerLevel world, @Nullable ElfPrincess princess) {
        sound(world, SoundEvents.WARDEN_SONIC_CHARGE, 1.3f);
        ensureHeartProxy(world);
        if (princess != null) princess.say("window");
    }

    // ---------------------------------------------------------------- 每刻

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
    private QueenMoves.Set moves(ServerLevel world) { return QueenMoves.of(world.getServer(), MOVESET); }

    @Override public double attackSpeed() { return 1; }
    @Override public void moveStarted(MoveRunner r) {
        if (r.cleared()) { action = ""; entityData.set(MOVE, ""); return; }
        var move = r.move();
        action = move.name().equals("queen_idle") ? "" : move.name();
        elapsed = 0; struck.clear(); getNavigation().stop(); origin = position();
        LivingEntity target = getTarget();
        aim = target == null ? getLookAngle().multiply(1, 0, 1).normalize() : target.position().subtract(position()).multiply(1, 0, 1).normalize();
        if (aim.lengthSqr() < .01) aim = new Vec3(0, 0, 1);
        if (!move.name().equals("queen_kneel") && !move.name().equals("queen_resist")) { setYRot((float) Math.toDegrees(Math.atan2(-aim.x, aim.z))); yBodyRot = getYRot(); }
        setXRot(0);
        entityData.set(MOVE, move.clip() + "|" + (long) r.start() + "|" + (float) r.rate() + "|" + (move.playback() == Moveset.Playback.LOOP));
        if (level() instanceof ServerLevel world && "spell".equals(moves(world).shape(move.name()).kind())) {
            if (move.name().equals("queen_sword_rain")) {
                spellPoint = target == null ? position().add(aim.scale(9)) : target.position();
                QueenSpellEffects.send(world, QueenSpellPayload.SIGIL, spellPoint.add(0, 12, 0), Vec3.ZERO, 6, (int) (move.length() * 20));
            } else {
                spellPoint = target == null ? getEyePosition().add(aim.scale(12)) : target.getEyePosition();
                QueenSpellEffects.send(world, QueenSpellPayload.CAST, getEyePosition(), Vec3.ZERO, 1.4f, (int) (move.contact() * 20));
            }
        }
        if (action.isEmpty()) wait = phase() >= 2 ? 22 : 32;
        if (level() instanceof ServerLevel world && move.hasContact())
            sound(world, action.equals("queen_roots") ? SoundEvents.EVOKER_PREPARE_SUMMON : SoundEvents.EVOKER_PREPARE_ATTACK, .7f);
    }

    @Override public void tick() {
        super.tick();
        if (!(level() instanceof ServerLevel world)) return;
        if (!isAlive()) return;
        if (tickCount % 20 == 0) refreshWard(world);
        mana = Math.min(600, mana + .5);
        if (cutPause > 0) cutPause--;
        var princess = princess(world);
        entityData.set(PRINCESS, princess == null ? -1 : princess.getId());
        if (phase() == QueenBattleRules.ENDING) { finale(world); return; }
        if (world.getDifficulty() == Difficulty.PEACEFUL) {
            getNavigation().stop();
            setTarget(null);
            if (runner != null) runner.force(action.equals("queen_guard") ? "queen_guard" : phase() == 3 ? "queen_kneel" : "queen_idle", world.getGameTime());
            coreRunner = null;
            if (bar != null) bar.removeAllPlayers();
            return;
        }
        // 场地被管理员删掉时不留下孤立 Boss；暂时未加载的核心不能算被删。
        if (heart != null && world.isLoaded(heart) && !world.getBlockState(heart).is(ElvenBlight.HEART.get())) { cleanup(world); discard(); return; }
        // 腐败之心记着的女王不是她（例如树心被重新生成过）：她不再属于这场战斗，不留两个女王。
        if (heart != null && world.isLoaded(heart) && world.getBlockEntity(heart) instanceof QueenHeart core && core.queen() != null && !core.queen().equals(getUUID())) {
            cleanup(world); discard(); return;
        }
        if (heart != null && distanceToSqr(Vec3.atCenterOf(heart)) > 32 * 32) {
            setPos(world.getBlockEntity(heart) instanceof QueenHeart core ? core.guardianPosition() : Vec3.atBottomCenterOf(heart));
            getNavigation().stop();
        }
        MoveRunner r = runner();
        if (r == null) { getNavigation().stop(); entityData.set(MOVE, ""); if (bar != null) bar.removeAllPlayers(); return; }
        r.tick(world.getGameTime());
        elapsed = (int) r.age(world.getGameTime());
        if (action.equals("queen_guard")) { guarding(world); return; }
        if (phase() == QueenBattleRules.FRENZY) {
            // 跪着，不再攻击；狂乱是腐败之心的事。
            getNavigation().stop(); setDeltaMovement(0, getDeltaMovement().y, 0);
            face(arena().add(QueenArena.direction(facing(), new Vec3(0, 0, 3))));
            frenzy(world, princess);
            return;
        }
        updateBar(world);
        // 所有人战死或离开 40 格超过 30 秒：重置。
        if (fighters(world, 40).isEmpty()) {
            if (++absentTicks >= QueenBattleRules.ABSENT && heart != null) { resetBattle(world); return; }
        } else absentTicks = 0;
        if (phase() == 2) guarding2(world, princess);
        if (transitioning()) {
            // 被线拽回中央。
            getNavigation().stop(); setDeltaMovement(0, getDeltaMovement().y, 0);
            if (elapsed <= 30) setPos(staggerFrom.lerp(arena(), QueenBattleRules.smooth(elapsed / 30f)));
            if (elapsed % 4 == 0) world.sendParticles(new DustParticleOptions(0xB4286E, 1.2f), getX(), getY() + 3, getZ(), 6, 1, 1.4, 1, .01);
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
            if (action.equals("queen_resist")) return;
            setYRot((float) Math.toDegrees(Math.atan2(-aim.x, aim.z)));
            yBodyRot = getYRot();
            attackFrame(world, r);
            return;
        }
        if (cutting()) { getNavigation().stop(); return; }
        fight(world);
    }

    private void guarding(ServerLevel world) {
        getNavigation().stop();
        for (ServerPlayer p : world.players()) {
            if (!canFight(p) || p.distanceToSqr(this) >= 14 * 14 || !hasLineOfSight(p)) continue;
            // 绕路进来也跳不过公主：她不到场，女王不起身（坐着一动不动，门口的公主会喊住走近的人）。
            if (heart != null && world.getBlockEntity(heart) instanceof QueenHeart core && !core.princessReady(world)) return;
            engage(p);
            return;
        }
    }

    /** 阶段二：净化进度、降下、波次、腐败之心的光柱、地上的污秽、公主的提醒。 */
    private void guarding2(ServerLevel world, @Nullable ElfPrincess princess) {
        int age = (int) phaseAge(0);
        if (princess != null) {
            for (int n = princess.takeSetbacks(); n > 0; n--) purification.setback(HeartPurification.SELF_RISE_SETBACK);
            princess.aim(coreCenter(0));
            if (age == QueenBattleRules.INTRO + 6) princess.say("land");
            if (age == QueenBattleRules.INTRO + 170 && veinsLeft() > 0) princess.say("veins", veinsLeft());
        }
        if (!transitioning()) {
            var event = purification.tick(princess != null && princess.channeling(), princess != null && princess.downed());
            switch (event) {
                case OPENED -> windowOpened(world, princess);
                case FAILED -> {
                    sound(world, SoundEvents.WARDEN_ROAR, 1.4f);
                    for (QueenTether t : tethers(world)) if (t.slot() == QueenTether.HEART) t.discard();
                    if (princess != null) princess.say("window_failed");
                }
                default -> { }
            }
            if (purification.open()) ensureHeartProxy(world);
            syncPurification();
        }
        if (princess != null && tickCount % 10 == 0) clearFilth(world, princess.position(), HeartPurification.circle(purification.progress()));
        if (tickCount % 20 == 0) ensureVeins(world);
        if (transitioning()) return;
        waves(world, princess);
        if (!purification.open() && !cutting()) coreAttack(world);
    }

    /**
     * 没在出招时：挑最近的玩家（从不挑公主），按出招顺序出招。阶段一走过去打；阶段二被线吊在场地正中，只放攻击法术。
     */
    private void fight(ServerLevel world) {
        LivingEntity target = world.players().stream().filter(p -> canFight(p) && p.distanceToSqr(this) < 40 * 40)
                .min(java.util.Comparator.comparingDouble(p -> p.distanceToSqr(this))).orElse(null);
        setTarget(target);
        if (target == null) { getNavigation().stop(); return; }
        getLookControl().setLookAt(target, 20, 20);
        var plan = moves(world).plan();
        if (phase() == 2) {
            getNavigation().stop();
            if (position().distanceToSqr(arena()) > 1) setPos(position().lerp(arena(), .2));
            if (--wait > 0 || !hasLineOfSight(target)) return;
            // 魔力不够的法术跳过，都不够就等一会儿回魔。
            for (int i = 0; i < Math.max(1, plan.spells().size()); i++) {
                String next = plan.spell(sequence++);
                if (next.isEmpty()) return;
                if (mana >= cost(world, next)) { play(next); return; }
            }
            wait = 20;
            return;
        }
        if (distanceToSqr(target) > 16) getNavigation().moveTo(target, 1);
        else getNavigation().stop();
        if (--wait > 0 || !hasLineOfSight(target)) return;
        String next = plan.pick(sequence, distanceTo(target));
        if (mana < cost(world, next) && plan.fallback() != null) next = plan.fallback();
        play(next);
        sequence++;
    }

    /** 这一招一共要多少魔力（每次接触各付一次）。 */
    private double cost(ServerLevel world, String move) {
        var shape = moves(world).shape(move);
        var m = runner == null ? null : runner.moveset().move(move);
        return shape.mana() * (m == null ? 1 : Math.max(1, m.contacts().size()));
    }

    private static boolean canFight(Player p) { return p.isAlive() && !p.isCreative() && !p.isSpectator(); }

    // ---------------------------------------------------------------- 出招

    /** 这里只画前摇提示；命中和招式结束均由 MoveRunner 的数据时间表触发。 */
    private void attackFrame(ServerLevel world, MoveRunner r) {
        int tick = (int) r.age(world.getGameTime());
        if (!r.move().hasContact() || tick >= r.move().contact() * 20 || tick % 4 != 0) return;
        var shape = moves(world).shape(r.move().name());
        switch (shape.kind()) {
            case "cone" -> warning(world, origin.add(aim.scale(shape.reach() * .6)), .7);
            case "ring" -> ring(world, origin, 2.5, true);
            case "line" -> { for (int i = 2; i <= shape.reach(); i++) warning(world, origin.add(aim.scale(i)), .08); }
            case "spell" -> {
                if (r.move().name().equals("queen_sword_rain")) {
                    // 剑阵覆盖范围先在地面显形，落点锁定后不追着玩家脚下瞬移。
                    for (int i = 0; i < 24; i++) {
                        double angle = i * Math.PI / 12;
                        world.sendParticles(new DustParticleOptions(0xB7233C, .8f), spellPoint.x + Math.cos(angle) * shape.reach(), spellPoint.y + .12, spellPoint.z + Math.sin(angle) * shape.reach(), 1, 0, 0, 0, 0);
                    }
                }
            }
            default -> { }
        }
    }

    private void castMagic(ServerLevel world, QueenMoves.Shape shape, MoveRunner r) {
        if (shape.spell() == null) return;
        Vec3 from = getEyePosition();
        if (shape.spell().equals("rain")) {
            double angle = r.contactIndex() * 2.39996323, radius = Math.sqrt((r.contactIndex() + .5) / r.move().contacts().size()) * shape.reach();
            from = spellPoint.add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
        } else {
            Vec3 direction = spellPoint.subtract(getEyePosition()).normalize();
            setYRot((float) Math.toDegrees(Math.atan2(-direction.x, direction.z))); yBodyRot = getYRot();
            // 贴地的荆棘沿地面走，不朝下压。
            float pitch = shape.spell().startsWith("thorn") ? 0 : (float) -Math.toDegrees(Math.atan2(direction.y, Math.hypot(direction.x, direction.z)));
            setXRot(pitch);
        }
        QueenSpells.cast(this, shape.spell(), from, getTarget(), shape.mana());
    }

    @Override public void contact(MoveRunner r) {
        // 处决的镜头里不出手（砍的人正看着特写）。
        if (!(level() instanceof ServerLevel world) || cutting()) return;
        var shape = moves(world).shape(r.move().name());
        if (shape.kind().equals("spell")) { castMagic(world, shape, r); return; }
        if (shape.kind().isEmpty()) return;
        double seconds = r.move().contacts().get(r.contactIndex()) - r.move().contact();
        switch (shape.kind()) {
            case "ring" -> ring(world, origin, shape.ring(seconds), false);
            case "line" -> {
                for (int i = 1; i <= shape.reach(); i++) {
                    Vec3 p = origin.add(aim.scale(i));
                    world.sendParticles(new DustParticleOptions(0xB7233C, 1), p.x, p.y + .5, p.z, 3, .3, .7, .3, .02);
                }
                sound(world, SoundEvents.EVOKER_CAST_SPELL, .65f);
            }
            case "cone" -> sound(world, SoundEvents.PLAYER_ATTACK_SWEEP, .65f);
            default -> { }
        }
        for (LivingEntity victim : victims(world, origin, shape.reach() + 2)) {
            if (shape.hits(origin, aim, victim.position(), seconds) && hasLineOfSight(victim)) hit(world, victim, (float) r.move().damage());
        }
        if (r.contactIndex() == r.move().contacts().size() - 1 && shape.filth() > 0) leaveFilth(world, filthSpots(shape, origin, aim), QueenBattleRules.FILTH_CAP);
    }

    /** 范围里能被打到的：能打的玩家、腐败衍生物（腐败伤害给它们回血）。女王从不打公主，打她的只有衍生物。 */
    private List<LivingEntity> victims(ServerLevel world, Vec3 center, double range) {
        List<LivingEntity> out = new ArrayList<>();
        for (ServerPlayer p : world.players()) if (canFight(p) && p.distanceToSqr(center) <= range * range) out.add(p);
        for (LivingEntity e : world.getEntitiesOfClass(LivingEntity.class, new net.minecraft.world.phys.AABB(center, center).inflate(range, 4, range),
                e -> e.isAlive() && e != this && !(e instanceof QueenTether) && CorruptionMobs.isCorrupted(e))) out.add(e);
        return out;
    }

    private void hit(ServerLevel world, LivingEntity victim, float damage) {
        if (!struck.add(victim.getUUID())) return;
        corruptionHit(victim, damage * difficulty(world), 10);
    }

    private static float difficulty(ServerLevel world) {
        return switch (world.getDifficulty()) { case EASY -> .75f; case HARD -> 1.25f; default -> 1; };
    }

    /** 女王和腐败之心的打击都是腐败伤害（生命的子类型）：走 Logos 的生命结算，玩家真的掉了血再吸魔力。 */
    private void corruptionHit(LivingEntity victim, float damage, double drain) {
        float before = victim.getHealth() + victim.getAbsorptionAmount();
        CorruptionDamage.hit(this, victim, damage);
        if (victim instanceof ServerPlayer player && player.getHealth() + player.getAbsorptionAmount() < before) CorruptionContact.drainMana(player, drain);
    }

    // ---------------------------------------------------------------- 腐败之心的光柱

    /** 腐败之心也是普通 MoveRunner：阶段二隔几秒一下 {@code core_pillar}，狂乱时一直 {@code core_frenzy}。 */
    private final MoveRunner.Actor coreActor = new MoveRunner.Actor() {
        @Override public double attackSpeed() { return 1; }
        @Override public void moveStarted(MoveRunner r) {
            pillarStruck.clear();
            pillarSpots.clear();
            if (!(level() instanceof ServerLevel world)) return;
            var fighters = fighters(world, 32);
            boolean frenzy = phase() == QueenBattleRules.FRENZY;
            // 每个人头上都可能来一下（最多三处）；狂乱时整片场地胡乱落。
            int count = frenzy ? 1 : Math.clamp(fighters.size(), 1, 3);
            for (int i = 0; i < count; i++) {
                Vec3 spot = pillarSpot(world, frenzy || fighters.isEmpty() ? null : fighters.get(random.nextInt(fighters.size())));
                if (spot == null) continue;
                pillarSpots.add(spot);
                int telegraph = Math.max(2, (int) (r.move().contact() * 20 / r.rate()));
                var shape = moves(world, CORE_MOVESET).shape(r.move().name());
                QueenSpellEffects.send(world, QueenSpellPayload.PILLAR_MARK, spot, Vec3.ZERO, (float) shape.reach(), telegraph);
            }
            if (!frenzy && !pillarSpots.isEmpty()) {
                var princess = princess(world);
                if (princess != null) princess.sayOnce("pillar_warn");
            }
        }
        @Override public void contact(MoveRunner r) { if (level() instanceof ServerLevel world) pillarContact(world, r); }
    };

    private QueenMoves.Set moves(ServerLevel world, Identifier set) { return QueenMoves.of(world.getServer(), set); }

    /** 光柱落点：在 {@code near} 身边 4 格内（没有就整片场地里随便一处），不落在祭坛附近，贴着地面。 */
    private @Nullable Vec3 pillarSpot(ServerLevel world, @Nullable ServerPlayer near) {
        Vec3 altar = altar(), center = arena();
        double hall = heart == null ? 20 : QueenArena.get(world.getServer()).hallRadius() - 2;
        for (int attempt = 0; attempt < 8; attempt++) {
            Vec3 at;
            if (near != null) {
                double a = random.nextDouble() * Math.PI * 2, d = random.nextDouble() * 4;
                at = near.position().add(Math.cos(a) * d, 0, Math.sin(a) * d);
            } else {
                double a = random.nextDouble() * Math.PI * 2, d = 2 + Math.sqrt(random.nextDouble()) * (hall - 2);
                at = center.add(Math.cos(a) * d, 0, Math.sin(a) * d);
            }
            if (at.subtract(center).multiply(1, 0, 1).length() > hall) continue;
            if (at.subtract(altar).multiply(1, 0, 1).length() < QueenBattleRules.ALTAR_CLEAR) continue;
            Vec3 ground = ground(world, at);
            if (ground != null) return ground;
        }
        return null;
    }

    /** {@code at} 这一竖列在场地地面上下 4 格内最高的能站的地方。 */
    private @Nullable Vec3 ground(ServerLevel world, Vec3 at) {
        int top = Mth.floor(arena().y) + 4;
        for (int y = top; y >= top - 8; y--) {
            BlockPos below = BlockPos.containing(at.x, y - 1, at.z), here = below.above();
            if (world.getBlockState(here).getCollisionShape(world, here).isEmpty() && world.getBlockState(below).isFaceSturdy(world, below, Direction.UP))
                return new Vec3(at.x, y, at.z);
        }
        return null;
    }

    private void coreAttack(ServerLevel world) {
        boolean frenzy = phase() == QueenBattleRules.FRENZY;
        if (!frenzy && fighters(world, 32).isEmpty()) { coreRunner = null; return; }
        var set = Movesets.get(world.getServer(), CORE_MOVESET);
        if (set == null) { coreRunner = null; return; }
        String want = frenzy ? "core_frenzy" : "core_pillar";
        if (coreRunner == null || coreRunner.moveset() != set) {
            coreRunner = new MoveRunner(set, coreActor, world.getGameTime());
            if (set.move(want) != null) coreRunner.force(want, world.getGameTime());
        }
        if (!coreRunner.move().name().equals(want) && set.move(want) != null) coreRunner.force(want, world.getGameTime());
        coreRunner.tick(world.getGameTime());
    }

    private void pillarContact(ServerLevel world, MoveRunner r) {
        var shape = moves(world, CORE_MOVESET).shape(r.move().name());
        Vec3 from = coreCenter(0).add(0, -2.2, 0);
        for (Vec3 spot : pillarSpots) {
            pillarsFired++;
            QueenSpellEffects.send(world, QueenSpellPayload.PILLAR, from, spot, (float) shape.reach(), 14);
            world.playSound(null, BlockPos.containing(spot), SoundEvents.WARDEN_SONIC_BOOM, SoundSource.HOSTILE, .45f, 1.6f);
            world.playSound(null, BlockPos.containing(spot), SoundEvents.PLAYER_SPLASH_HIGH_SPEED, SoundSource.HOSTILE, 1.1f, .6f);
            for (LivingEntity victim : victims(world, spot, shape.reach() + 1)) {
                if (shape.hits(spot, Vec3.ZERO, victim.position(), 0) && pillarStruck.add(victim.getUUID()))
                    corruptionHit(victim, (float) r.move().damage() * difficulty(world), 8);
            }
            if (shape.filth() > 0) {
                List<Vec3> spots = new ArrayList<>();
                for (int i = 0; i < shape.filth(); i++) {
                    double a = random.nextDouble() * Math.PI * 2, d = random.nextDouble() * shape.reach();
                    spots.add(spot.add(Math.cos(a) * d, 0, Math.sin(a) * d));
                }
                leaveFilth(world, spots, phase() == QueenBattleRules.FRENZY ? QueenBattleRules.FRENZY_FILTH_CAP : QueenBattleRules.FILTH_CAP);
            }
        }
    }

    // ---------------------------------------------------------------- 污秽

    private List<Vec3> filthSpots(QueenMoves.Shape shape, Vec3 from, Vec3 dir) {
        List<Vec3> spots = new ArrayList<>();
        for (int i = 0; i < shape.filth(); i++) {
            if (shape.kind().equals("line")) spots.add(from.add(dir.scale(2 + (shape.reach() - 2) * (i + .5) / shape.filth())));
            else {
                double a = random.nextDouble() * Math.PI * 2, d = 3 + random.nextDouble() * Math.max(1, shape.reach() - 3);
                spots.add(from.add(Math.cos(a) * d, 0, Math.sin(a) * d));
            }
        }
        return spots;
    }

    /** 在地面上留几块腐败污秽（贴地的腐败脉络）。只放在空气里、下面是实心地面的格子；记下来，重置、结局时清掉。 */
    private void leaveFilth(ServerLevel world, List<Vec3> spots, int cap) {
        var state = Corruption.VEIN.get().defaultBlockState().setValue(MultifaceBlock.getFaceProperty(Direction.DOWN), true);
        int floor = Mth.floor(arena().y + .2);
        for (Vec3 s : spots) {
            if (filth.size() >= cap) return;
            // 不铺到祭坛上（公主站的地方）。
            if (s.subtract(altar()).multiply(1, 0, 1).length() < 2.5) continue;
            for (int dy = 0; dy <= 1; dy++) {
                BlockPos at = BlockPos.containing(s.x, floor + dy, s.z);
                BlockPos below = at.below();
                if (world.getBlockState(at).isAir() && world.getBlockState(below).isFaceSturdy(world, below, Direction.UP)) {
                    world.setBlock(at, state, 3);
                    filth.add(at.asLong());
                    break;
                }
            }
        }
    }

    /** 公主的净化圈把地上的污秽推回去。 */
    private void clearFilth(ServerLevel world, Vec3 center, double radius) {
        var it = filth.iterator();
        while (it.hasNext()) {
            BlockPos at = BlockPos.of(it.nextLong());
            double dx = at.getX() + .5 - center.x, dz = at.getZ() + .5 - center.z;
            if (dx * dx + dz * dz > radius * radius) continue;
            if (world.getBlockState(at).is(Corruption.VEIN.get())) {
                world.removeBlock(at, false);
                world.sendParticles(ParticleTypes.HAPPY_VILLAGER, at.getX() + .5, at.getY() + .2, at.getZ() + .5, 2, .3, .05, .3, 0);
            }
            it.remove();
        }
    }

    // ---------------------------------------------------------------- 衍生物

    private void waves(ServerLevel world, @Nullable ElfPrincess princess) {
        waveMobs.removeIf(id -> !(world.getEntity(id) instanceof LivingEntity e) || !e.isAlive());
        if (tickCount % 20 == 0) for (UUID id : waveMobs) {
            if (!(world.getEntity(id) instanceof Mob mob)) continue;
            // 衍生物优先打公主；谁刚打了它，它就去追谁（引开是玩法的一部分）。
            LivingEntity by = mob.getLastHurtByMob();
            if (by instanceof Player p && canFight(p) && mob.tickCount - mob.getLastHurtByMobTimestamp() < 100) mob.setTarget(p);
            else if (princess != null && princess.channeling()) mob.setTarget(princess);
            else if (mob.getTarget() == null || !mob.getTarget().isAlive() || mob.getTarget() instanceof ElfPrincess)
                mob.setTarget(world.getNearestPlayer(mob, 40) instanceof ServerPlayer p && canFight(p) ? p : null);
            // 站在战斗里长出的污秽上慢慢回血。
            if (filth.contains(mob.blockPosition().asLong()) && mob.getHealth() < mob.getMaxHealth()) mob.heal(1);
        }
        if (--waveTimer > 0) return;
        int cut = Integer.bitCount(brokenRoots());
        waveTimer = QueenBattleRules.WAVE_INTERVAL + QueenBattleRules.WAVE_DELAY_PER_VEIN * cut;
        int count = Math.min(QueenBattleRules.waveSize(fighters(world, 40).size(), cut), QueenBattleRules.WAVE_CAP - waveMobs.size());
        var tunnels = QueenArena.get(world.getServer()).tunnels();
        for (int i = 0; i < count && heart != null; i++) {
            Vec3 at = QueenArena.at(heart, facing(), tunnels.get((waveCount + i) % tunnels.size())).add(random.nextDouble() - .5, 0, random.nextDouble() - .5);
            Mob mob = WAVE_TYPES.get((waveCount + i) % WAVE_TYPES.size()).get().create(world, EntitySpawnReason.MOB_SUMMONED);
            if (mob == null) continue;
            mob.snapTo(at.x, at.y, at.z, random.nextFloat() * 360, 0);
            mob.finalizeSpawn(world, world.getCurrentDifficultyAt(BlockPos.containing(at)), EntitySpawnReason.MOB_SUMMONED, null);
            mob.setPersistenceRequired();
            mob.addTag(WAVE_TAG);
            if (princess != null && princess.channeling()) mob.setTarget(princess);
            if (world.addFreshEntity(mob)) {
                waveMobs.add(mob.getUUID());
                world.sendParticles(ParticleTypes.CRIMSON_SPORE, at.x, at.y + 1, at.z, 16, .4, .6, .4, .02);
            }
        }
        waveCount += Math.max(1, count);
        if (count > 0) {
            sound(world, SoundEvents.SCULK_SHRIEKER_SHRIEK, 1.4f);
            if (princess != null) princess.sayOnce("wave");
        }
    }

    // ---------------------------------------------------------------- 结局

    private void finale(ServerLevel world) {
        getNavigation().stop(); setDeltaMovement(0, 0, 0);
        if (bar != null) bar.removeAllPlayers();
        if (!endingSent) { QueenEndings.join(this, endingTick, endingViewers); endingSent = true; }
        // 重连与后来进入现场的人只接续余下时长，不重播或重新清理世界。
        if (endingTick % 10 == 0) QueenEndings.join(this, endingTick, endingViewers);
        if (endingTick == 35) CompanionLines.speak(this, "entity.logos_dimension.corrupted_elf_queen", NAME_COLOR, "line.logos_dimension.corrupted_elf_queen.last_words", 70);
        if (endingTick >= QueenBattleRules.EXPLODE) {
            setInvisible(true);
            if (endingTick == QueenBattleRules.EXPLODE) {
                sound(world, SoundEvents.GENERIC_EXPLODE.value(), .65f);
                world.sendParticles(ParticleTypes.ENCHANT, getX(), getY() + 2, getZ(), 64, 2.5, 2, 2.5, .2);
                world.sendParticles(ParticleTypes.CHERRY_LEAVES, getX(), getY() + 2, getZ(), 36, 2, 1.8, 2, .02);
                // 漫了一地的脉络随着她一起散掉。
                clearAllFilth(world);
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
            allowDeath = true;
            discard();
        }
    }

    // ---------------------------------------------------------------- 小工具

    private void face(Vec3 at) {
        Vec3 d = at.subtract(position());
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        setYRot(yaw); yBodyRot = yaw; yHeadRot = yaw;
    }
    private void ring(ServerLevel world, Vec3 center, double radius, boolean warning) {
        int count = Math.max(12, (int) (radius * 8));
        for (int i = 0; i < count; i++) {
            double a = i * Math.PI * 2 / count;
            Vec3 p = center.add(Math.cos(a) * radius, .15, Math.sin(a) * radius);
            world.sendParticles(warning ? ParticleTypes.ASH : new DustParticleOptions(0xB7233C, 1), p.x, p.y, p.z, 1, .03, .1, .03, .005);
        }
    }
    private void warning(ServerLevel world, Vec3 p, double spread) { world.sendParticles(ParticleTypes.ASH, p.x, p.y + .2, p.z, 3, spread, .1, spread, .005); }
    private void sound(ServerLevel world, SoundEvent sound, float pitch) { world.playSound(null, blockPosition(), sound, SoundSource.HOSTILE, 1, pitch); }

    // ---------------------------------------------------------------- 受伤

    @Override public boolean hurtServer(ServerLevel world, DamageSource source, float amount) {
        // 招式损坏或缺失时允许原版伤害，不把女王锁在无法完成的免伤状态。
        amount = incoming(world, source, amount);
        lastIncoming = amount;
        if (Movesets.get(world.getServer(), MOVESET) == null) return super.hurtServer(world, source, amount);
        boolean bypass = source.is(DamageTypeTags.BYPASSES_INVULNERABILITY);
        if (bypass) { allowDeath = true; return super.hurtServer(world, source, amount); }
        if (action.equals("queen_guard")) {
            if (source.getEntity() instanceof ServerPlayer player && canFight(player) && heart != null
                    && world.getBlockEntity(heart) instanceof QueenHeart core && core.princessReady(world)) engage(player);
            return false;
        }
        if (entering() || transitioning() || phase() >= 3) return false;
        if (amount <= 0 || !Float.isFinite(amount) || !(source.getEntity() instanceof LivingEntity attacker) || CorruptionDamage.aligned(attacker)) return false;
        if (phase() == 2) {
            // 被腐败之心用线吊着：打不动她，只溅起一点腐败。公主会提醒一次（隔半分钟才再说）。
            if (invulnerableTime > 10) return false;
            invulnerableTime = 20;
            world.sendParticles(new DustParticleOptions(0xB4286E, 1), getX(), getY() + 1.4, getZ(), 8, .5, .8, .5, .02);
            world.playSound(null, blockPosition(), SoundEvents.SHIELD_BLOCK.value(), SoundSource.HOSTILE, .8f, .6f);
            var princess = princess(world);
            if (princess != null && attacker instanceof Player) princess.sayEvery("queen_shielded", 600);
            return false;
        }
        boolean hurt = super.hurtServer(world, source, amount);
        if (phase() == 1 && getHealth() <= 1 && heart != null && isAlive()) beginPurification(world);
        return hurt;
    }

    /**
     * 阶段一打空时停在 1 点进入阶段二；只有结局和 {@code /kill} 能让她真的死。用 {@code /summon} 单独召出来、
     * 没有腐败之心的女王没有公主和净化，打空就照常死。
     */
    @Override public void setHealth(float health) {
        if (!allowDeath && heart != null && health < 1 && !level().isClientSide()) health = 1;
        super.setHealth(health);
    }

    @Override public void die(DamageSource source) {
        if (level() instanceof ServerLevel world) {
            cleanup(world);
            if (heart != null && world.getBlockEntity(heart) instanceof QueenHeart core) core.defeated(getUUID());
        }
        if (bar != null) bar.removeAllPlayers();
        super.die(source);
    }

    private void updateBar(ServerLevel world) {
        if (bar == null) bar = new ServerBossEvent(getUUID(), Component.translatable("entity.logos_dimension.corrupted_elf_queen"), BossEvent.BossBarColor.PURPLE, BossEvent.BossBarOverlay.PROGRESS);
        bar.setProgress(Mth.clamp(phase() == 1 ? getHealth() / getMaxHealth() : purification.health(), 0, 1));
        bar.setName(Component.translatable("boss.logos_dimension.queen.phase" + Math.min(2, phase())));
        for (ServerPlayer p : List.copyOf(bar.getPlayers())) if (p.level() != world || !p.isAlive() || p.distanceToSqr(this) > 48 * 48) bar.removePlayer(p);
        for (ServerPlayer p : world.players()) if (!p.isSpectator() && p.distanceToSqr(this) < 40 * 40) bar.addPlayer(p);
    }
    @Override public void remove(RemovalReason reason) { if (bar != null) bar.removeAllPlayers(); super.remove(reason); }
    @Override public void stopSeenByPlayer(ServerPlayer player) { super.stopSeenByPlayer(player); if (bar != null) bar.removePlayer(player); }

    // ---------------------------------------------------------------- 存档

    /** 存档版本：5 是五刀处决和狂乱（第 4 步返工）；更早的存档从阶段一重来。 */
    private static final int REMAKE = 5;

    @Override protected void addAdditionalSaveData(ValueOutput out) {
        super.addAdditionalSaveData(out);
        if (heart != null) { out.putInt("HeartX", heart.getX()); out.putInt("HeartY", heart.getY()); out.putInt("HeartZ", heart.getZ()); }
        out.putBoolean("Guarding", action.equals("queen_guard"));
        out.putBoolean("Emerging", entering());
        out.putBoolean("Entrance", action.equals("queen_entrance"));
        out.putBoolean("IntroPlayed", introPlayed);
        out.putInt("EmergenceTick", elapsed);
        out.putInt("CorePhase", phase()); out.putInt("BrokenVeins", brokenRoots());
        out.putInt("QueenSequence", sequence); out.putInt("EndingTick", endingTick); out.putBoolean("Purified", purified);
        out.putLong("PhaseTime", entityData.get(PHASE_TIME)); out.putLong("QueenPhaseAge", Math.max(0, level().getGameTime() - entityData.get(PHASE_TIME))); out.putString("QueenAction", action);
        out.putInt("Remake", REMAKE); out.putDouble("QueenMana", mana); out.putFloat("BattleScale", scale);
        out.putInt("WaveTimer", waveTimer); out.putInt("WaveCount", waveCount);
        out.putString("WaveMobs", String.join(",", waveMobs.stream().map(UUID::toString).toList()));
        var spots = out.list("BattleFilth", com.mojang.serialization.Codec.LONG);
        for (long key : filth) spots.add(key);
        purification.save(out);
    }
    @Override protected void readAdditionalSaveData(ValueInput in) {
        super.readAdditionalSaveData(in);
        // 旧版的存档（三段断链、第 4 步初版的三个窗口）直接从阶段一重来，不沿用旧的生命和阶段。
        boolean remake = in.getIntOr("Remake", 0) >= REMAKE;
        scale = Math.max(1, in.getFloatOr("BattleScale", 1));
        if (!remake) { getAttribute(Attributes.MAX_HEALTH).setBaseValue(QueenBattleRules.HEALTH); setHealth(QueenBattleRules.HEALTH); }
        // 存档里带着旧版的属性基础值（例如 75% 法术抗性），读完按当前的抗性表重写。
        if (level() instanceof ServerLevel server) traits(server).apply(this);
        mana = Math.clamp(in.getDoubleOr("QueenMana", 600), 0, 600);
        if (in.getInt("HeartY").isPresent()) heart = new BlockPos(in.getIntOr("HeartX", 0), in.getIntOr("HeartY", 0), in.getIntOr("HeartZ", 0));
        if (heart != null) { entityData.set(HEART_POS, heart); entityData.set(BOUND, true); }
        introPlayed = in.getBooleanOr("IntroPlayed", in.getBooleanOr("Emerging", false));
        entityData.set(PHASE, remake ? Math.clamp(in.getIntOr("CorePhase", 1), 1, 4) : 1);
        entityData.set(BROKEN, remake ? in.getIntOr("BrokenVeins", 0) & 0xFF : 0);
        sequence = Math.max(0, in.getIntOr("QueenSequence", 0)); endingTick = Math.clamp(in.getIntOr("EndingTick", 0), 0, QueenBattleRules.END);
        purified = in.getBooleanOr("Purified", false);
        waveTimer = in.getIntOr("WaveTimer", 0); waveCount = in.getIntOr("WaveCount", 0);
        waveMobs.clear();
        for (String s : in.getStringOr("WaveMobs", "").split(",")) {
            if (s.isBlank()) continue;
            try { waveMobs.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) {}
        }
        filth.clear();
        for (long key : in.listOrEmpty("BattleFilth", com.mojang.serialization.Codec.LONG)) filth.add(key);
        if (remake) purification.load(in); else purification.reset(scale);
        syncPurification();
        // 读档后重建相对时钟，卸载期间不让核心下降或收缩演出在后台快进。
        long phaseAge = Math.max(0, in.getLongOr("QueenPhaseAge", phase() == 4 ? endingTick : level().getGameTime() - in.getLongOr("PhaseTime", level().getGameTime())));
        entityData.set(PHASE_TIME, level().getGameTime() - phaseAge);
        runner = null; coreRunner = null; pillarSpots.clear();
        String savedMove = in.getStringOr("QueenAction", "");
        if (phase() >= 3) { restoreMove(phase() == 4 ? "queen_sacrifice" : phaseAge >= QueenBattleRules.FRENZY_RISE ? "queen_rise" : "queen_kneel", phase() == 4 ? endingTick : 0); return; }
        if (!remake || in.getBooleanOr("Guarding", false)) play("queen_guard");
        else if (in.getBooleanOr("Emerging", false)) { boolean entrance = in.getBooleanOr("Entrance", false); restoreMove(entrance ? "queen_entrance" : "queen_rise", Mth.clamp(in.getIntOr("EmergenceTick", 0), 0, entrance ? QueenEntrances.TICKS : RISE_TICKS)); }
        else if (savedMove.equals("queen_resist")) restoreMove(savedMove, Math.clamp(in.getIntOr("EmergenceTick", 0), 0, QueenBattleRules.RESIST_TICKS));
        else play("");
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
                    parts.length > 3 ? Boolean.parseBoolean(parts[3]) : parts[0].equals("queen_guard"));
        }
        return clientMove;
    }
    @Override public void registerControllers(AnimatableManager.ControllerRegistrar c) { NpcAnimations.registerControllers(this, c); }
    @Override public AnimatableInstanceCache getAnimatableInstanceCache() { return cache; }
}
