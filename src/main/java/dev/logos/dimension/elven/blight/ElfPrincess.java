package dev.logos.dimension.elven.blight;

import com.geckolib.animatable.instance.AnimatableInstanceCache;
import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.util.GeckoLibUtil;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.combat.MoveRunner;
import dev.logos.dimension.combat.Moveset;
import dev.logos.dimension.combat.Movesets;
import dev.logos.dimension.companion.CompanionLines;
import dev.logos.dimension.companion.CompanionSheet;
import dev.logos.dimension.companion.CompanionTactic;
import dev.logos.dimension.companion.CompanionTalk;
import dev.logos.dimension.companion.TalkMenu;
import dev.logos.dimension.corruption.CorruptionNetworks;
import dev.logos.dimension.corruption.mob.CorruptionMobs;
import dev.logos.dimension.item.NaturesForceItem;
import dev.logos.dimension.npc.NpcAnimated;
import dev.logos.dimension.npc.NpcAnimations;
import dev.logos.dimension.npc.NpcParts;
import dev.logos.dimension.npc.NpcPartsHolder;
import dev.logos.lang.Element;
import dev.logos.runtime.Vfx;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 精灵公主的战斗版（docs/CORRUPTED_QUEEN_REMAKE.md 第 3、5.2 节）：按剧本行动的友方 NPC，不归哪个玩家所有。
 *
 * <ul>
 * <li>由腐败之心在世界树门口生成（{@link QueenHeart}），每棵树一位。门口和她说话：讲腐败之心和外面供养女王的核心，交出自然之力；
 * 选「走吧」她先进大厅，在入口等着。她不到场，女王坐着不起身。
 * <li>阶段一她留在入口；阶段二镜头切到树根祭坛，她从上面落下来、蹲着落地、站起来，用 Logos 的生命光束净化腐败之心
 * （进度在女王身上，见 {@link HeartPurification}）。
 * <li>战斗外打不死她；战斗里玩家也伤不到她。生命掉光时倒地，不会死：有人在身边按住使用键 3 秒扶起，没人扶 20 秒后自己起来。
 * <li>战斗里的提示都由她说出来（{@link #say}，画面下方的字幕），不往屏幕上写状态。
 * <li>外观先用固定的一套森林精灵零件（模型以后另做）；动作走招式文件 {@code combat/movesets/elf_princess.json}。
 * </ul>
 * 打赢后的同伴版是另一种实体（第 5 步）。
 */
public class ElfPrincess extends PathfinderMob implements NpcAnimated, NpcPartsHolder, MoveRunner.Actor, CompanionTalk.Talker {
    public static final String TALK = "elf_princess";
    private static final Identifier MOVESET = LogosDimension.id("elf_princess");
    private static final Identifier ASSET = LogosDimension.id("forest_elf");
    public static final float MAX_HEALTH = 120;
    /** 扶起要按住的刻数、没人扶自己起来的刻数、防御脉冲的冷却。 */
    public static final int REVIVE_TICKS = 60, SELF_RISE_TICKS = 400, PULSE_COOLDOWN = 200;
    /** 从祭坛上方多高落下、落多少刻、落地后蹲着再站起来多少刻（落地动作 {@code princess_land}）。 */
    public static final double DROP_HEIGHT = 7;
    public static final int FALL_TICKS = 12, LAND_TICKS = 30, LEAP_TICKS = FALL_TICKS + LAND_TICKS;
    /** 字幕里她名字的颜色（同对话界面的嫩绿）。 */
    public static final int NAME_COLOR = 0xB8E6A0;
    /** 公主暂用的森林精灵外观（模型做好后替换）。 */
    private static final Map<String, String> LOOK = Map.of("hair", "hair_priestess_braids", "headwear", "headwear_twig_crown", "ears", "ear_long",
            "eyes", "eyes_droopy", "mouth", "mouth_smile", "outfit", "outfit_floral_gown");

    /** 在门口等、先进大厅、在大厅入口等、跃上祭坛、在祭坛上净化、倒地、打完了。 */
    public enum Role { GATE, AHEAD, WAITING, LEAP, CHANNEL, DOWNED, AFTER }

    private static final EntityDataAccessor<Integer> ROLE = SynchedEntityData.defineId(ElfPrincess.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<String> MOVE = SynchedEntityData.defineId(ElfPrincess.class, EntityDataSerializers.STRING);

    static {
        TalkMenu.register(TALK, List.of(
                new TalkMenu.Option(TalkMenu.CHAT, TalkMenu.Kind.MENU, List.of(TalkMenu.Option.server("topic.heart"), TalkMenu.Option.server("topic.cores"),
                        TalkMenu.Option.server("topic.queen"), TalkMenu.Option.server("topic.bow"), TalkMenu.Option.back()), null),
                TalkMenu.Option.server("ready"),
                new TalkMenu.Option(TalkMenu.BYE, TalkMenu.Kind.CLOSE, List.of(), null)));
    }

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private @Nullable BlockPos heart;
    private final Set<UUID> met = new HashSet<>(), gifted = new HashSet<>();
    private @Nullable MoveRunner runner;
    private String clientMove = "";
    private @Nullable CombatMove clientCombat;
    private Vec3 leapFrom = Vec3.ZERO, leapTo = Vec3.ZERO;
    private int leapTick, downedTicks, pulseCooldown, stuck;
    private @Nullable Vec3 beamTo;
    private boolean beaming, allowDeath;
    /** 正在扶她的人、已经按了几刻、上一次按的时刻。 */
    private @Nullable UUID reviver;
    private int reviveTicks;
    private long reviveLast;
    /** 这一场倒地、被扶起、自己起来的次数（结局对话挑台词用）。 */
    private int downs, revives, selfRises;
    /** 自己起来过、还没报给女王扣进度的次数。 */
    private int pendingSetbacks;
    private @Nullable UUID talking;
    /** 在门口喊过的玩家（不存档：重进世界再喊一次也无妨）。 */
    private final Set<UUID> called = new HashSet<>();

    public ElfPrincess(EntityType<? extends ElfPrincess> type, Level level) {
        super(type, level);
        setPersistenceRequired();
        xpReward = 0;
    }

    public static AttributeSupplier.Builder attributes() {
        return PathfinderMob.createMobAttributes().add(Attributes.MAX_HEALTH, MAX_HEALTH).add(Attributes.MOVEMENT_SPEED, .3)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1).add(Attributes.FOLLOW_RANGE, 48);
    }

    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(ROLE, Role.GATE.ordinal());
        builder.define(MOVE, "");
    }

    @Override protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 8) {
            @Override public boolean canUse() { return calm() && super.canUse(); }
            @Override public boolean canContinueToUse() { return calm() && super.canContinueToUse(); }
        });
    }

    // ---------------------------------------------------------------- 状态

    public Role role() { return Role.values()[Math.clamp(entityData.get(ROLE), 0, Role.values().length - 1)]; }
    private void role(Role role) { entityData.set(ROLE, role.ordinal()); }
    /** 不在战斗里（在门口、在路上、在入口等、打完了）。 */
    public boolean calm() { Role r = role(); return r == Role.GATE || r == Role.AHEAD || r == Role.WAITING || r == Role.AFTER; }
    /** 到场了：在大厅入口等着，或者已经在战斗里。 */
    public boolean ready() { return role() != Role.GATE && role() != Role.AHEAD; }
    public boolean channeling() { return role() == Role.CHANNEL; }
    public boolean downed() { return role() == Role.DOWNED; }
    public @Nullable BlockPos heart() { return heart; }
    public void home(BlockPos heart) { this.heart = heart.immutable(); }
    public int downs() { return downs; }
    public int revives() { return revives; }
    public int selfRises() { return selfRises; }
    /** 自己起来过几次还没扣进度；取走即清零。 */
    public int takeSetbacks() { int n = pendingSetbacks; pendingSetbacks = 0; return n; }

    private Direction facing(ServerLevel world) {
        if (heart != null && world.isLoaded(heart)) {
            var state = world.getBlockState(heart);
            if (state.hasProperty(QueenHeartBlock.FACING)) return state.getValue(QueenHeartBlock.FACING);
        }
        return Direction.SOUTH;
    }

    /** 战场标记在世界里的位置。 */
    public Vec3 marker(ServerLevel world, Vec3 template) {
        return heart == null ? position() : QueenArena.at(heart, facing(world), template);
    }

    private QueenArena arena(ServerLevel world) { return QueenArena.get(world.getServer()); }

    // ---------------------------------------------------------------- 剧本

    /** 回到门口（战斗重置、读档发现位置不对）。 */
    public void returnToGate(ServerLevel world) {
        stopBeam(world);
        noPhysics = false;
        setNoGravity(false);
        role(Role.GATE);
        setHealth(getMaxHealth());
        downedTicks = 0;
        reviver = null;
        Vec3 gate = marker(world, arena(world).gate());
        setPos(gate);
        getNavigation().stop();
        face(gate.add(QueenArena.direction(facing(world), new Vec3(0, 0, -1))));
        play("princess_idle");
    }

    /** 先进大厅，在入口等。 */
    public void goAhead(ServerLevel world) {
        if (role() != Role.GATE) return;
        role(Role.AHEAD);
        stuck = 0;
    }

    /** 直接站到大厅入口（测试、开战时她还在路上）。 */
    public void arrive(ServerLevel world) {
        if (!calm()) return;
        role(Role.WAITING);
        getNavigation().stop();
        Vec3 at = marker(world, arena(world).entrance());
        setPos(at);
        if (heart != null) face(Vec3.atBottomCenterOf(heart));
    }

    /**
     * 落到祭坛上开始净化（阶段二开始时女王叫她）：镜头这时已经切到祭坛（{@link CorruptedElfQueen}），
     * 她从祭坛正上方 {@link #DROP_HEIGHT} 格落进画面，蹲着落地，再站起来施法。
     */
    public void leap(ServerLevel world) {
        if (role() == Role.LEAP || role() == Role.CHANNEL || role() == Role.DOWNED) return;
        stopBeam(world);
        getNavigation().stop();
        leapTo = marker(world, arena(world).altar());
        leapFrom = leapTo.add(0, DROP_HEIGHT, 0);
        leapTick = 0;
        noPhysics = true;
        setNoGravity(true);
        setPos(leapFrom);
        if (heart != null) face(Vec3.atBottomCenterOf(heart).add(0, getEyeHeight(), 0));
        role(Role.LEAP);
        play("princess_leap");
    }

    /** 她说一句（画面下方的字幕，48 格内的玩家看得到）：{@code line.logos_dimension.elf_princess.<id>}。 */
    public void say(String id, Object... args) {
        CompanionLines.speak(this, key("name"), NAME_COLOR, line(id), 80, args);
    }

    /** 同上，但这一场里只说一次。返回这次说了没有。 */
    public boolean sayOnce(String id, Object... args) {
        if (CompanionLines.said(this, line(id))) return false;
        say(id, args);
        return true;
    }

    /** 同上，但 {@code gap} 刻内不重复。 */
    public boolean sayEvery(String id, int gap, Object... args) {
        return CompanionLines.speakEvery(this, key("name"), NAME_COLOR, line(id), 80, gap, args);
    }

    private static String line(String id) { return "line.logos_dimension." + TALK + "." + id; }

    /** 战斗打完：收起光束，站在原地（结局的蹲看、对话在第 5 步）。 */
    public void finish(ServerLevel world) {
        stopBeam(world);
        noPhysics = false;
        setNoGravity(false);
        if (role() == Role.DOWNED) setHealth(getMaxHealth());
        role(Role.AFTER);
        play("princess_idle");
    }

    /** 女王每刻告诉她光束打到哪（腐败之心的位置会随窗口升降）。 */
    public void aim(@Nullable Vec3 heartCenter) { beamTo = heartCenter; }

    private void face(Vec3 at) {
        Vec3 d = at.subtract(position());
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        setYRot(yaw); yBodyRot = yaw; yHeadRot = yaw;
        setXRot((float) -Math.toDegrees(Math.atan2(d.y - getEyeHeight(), Math.hypot(d.x, d.z))));
    }

    private int beamId() { return getId() * 31 + 0x51ce; }

    private void stopBeam(ServerLevel world) {
        if (beaming) Vfx.beamEnd(world, beamId(), beamTo == null ? position() : beamTo, Element.LIFE);
        beaming = false;
    }

    // ---------------------------------------------------------------- 每刻

    @Override public void tick() {
        super.tick();
        if (!(level() instanceof ServerLevel world) || !isAlive()) return;
        MoveRunner r = runner();
        if (r != null) r.tick(world.getGameTime());
        if (pulseCooldown > 0) pulseCooldown--;
        switch (role()) {
            case GATE -> { holdAt(world, marker(world, arena(world).gate())); callOut(world); }
            case AHEAD -> walkAhead(world);
            case WAITING -> holdAt(world, marker(world, arena(world).entrance()));
            case LEAP -> leaping(world);
            case CHANNEL -> channel(world);
            case DOWNED -> lying(world);
            case AFTER -> getNavigation().stop();
        }
        if (reviver != null && world.getGameTime() - reviveLast > 8) { reviver = null; reviveTicks = 0; }
    }

    /** 有人第一次走近门口：她朝那边喊一声（玩家就知道该和她说话，不用屏幕上写）。 */
    private void callOut(ServerLevel world) {
        if (tickCount % 10 != 0 || talking != null) return;
        for (ServerPlayer p : world.players()) {
            if (!p.isAlive() || p.isSpectator() || p.distanceToSqr(this) > 16 * 16 || met.contains(p.getUUID()) || !called.add(p.getUUID())) continue;
            say("call_out");
            getLookControl().setLookAt(p, 30, 30);
            return;
        }
    }

    private void holdAt(ServerLevel world, Vec3 at) {
        if (talking != null) { getNavigation().stop(); return; }
        if (position().distanceToSqr(at) > 4 && getNavigation().isDone()) getNavigation().moveTo(at.x, at.y, at.z, .8);
        if (position().distanceToSqr(at) > 64 * 64) setPos(at);
    }

    private void walkAhead(ServerLevel world) {
        Vec3 at = marker(world, arena(world).entrance());
        if (position().distanceToSqr(at) < 2.5) { arrive(world); return; }
        if (talking != null) { getNavigation().stop(); return; }
        if (getNavigation().isDone()) getNavigation().moveTo(at.x, at.y, at.z, .9);
        // 路走不通（被人堵住、寻路失败）就直接过去，不让开战卡在她身上。
        if (++stuck > 400) arrive(world);
    }

    /** 落下（越落越快）→ 落地蹲住 → 站起来抬手，然后开始引导。 */
    private void leaping(ServerLevel world) {
        leapTick++;
        setDeltaMovement(Vec3.ZERO);
        fallDistance = 0;
        if (leapTick <= FALL_TICKS) {
            float t = leapTick / (float) FALL_TICKS;
            setPos(leapFrom.lerp(leapTo, t * t));
            if (leapTick % 2 == 0) world.sendParticles(ParticleTypes.CHERRY_LEAVES, getX(), getY() + 1.2, getZ(), 2, .25, .5, .25, .01);
            if (leapTick == FALL_TICKS) land(world);
            return;
        }
        setPos(leapTo);
        if (leapTick >= LEAP_TICKS) {
            role(Role.CHANNEL);
            play("princess_channel");
            world.playSound(null, blockPosition(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.NEUTRAL, 1.2f, 1);
        }
    }

    private void land(ServerLevel world) {
        noPhysics = false;
        setNoGravity(false);
        setPos(leapTo);
        play("princess_land");
        world.sendParticles(ParticleTypes.CHERRY_LEAVES, getX(), getY() + .15, getZ(), 26, 1.1, .1, 1.1, .06);
        world.sendParticles(ParticleTypes.HAPPY_VILLAGER, getX(), getY() + .2, getZ(), 16, 1.2, .15, 1.2, .02);
        world.playSound(null, blockPosition(), SoundEvents.AZALEA_LEAVES_FALL, SoundSource.NEUTRAL, 1.4f, .9f);
        world.playSound(null, blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.NEUTRAL, 1, 1.2f);
    }

    private void channel(ServerLevel world) {
        getNavigation().stop();
        setDeltaMovement(0, getDeltaMovement().y, 0);
        if (beamTo != null) {
            face(beamTo);
            Vec3 hands = getEyePosition().add(getLookAngle().scale(.6)).add(0, -.35, 0);
            Vfx.beam(world, beamId(), hands, beamTo, Element.LIFE, .32, -1, 0);
            beaming = true;
        }
        // 防御脉冲：贴身的衍生物被一圈生命之力推开（不伤它们，冷却 10 秒）。
        if (pulseCooldown == 0 && !"princess_pulse".equals(currentMove())) {
            boolean close = !world.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(2.5), e -> e.isAlive() && CorruptionMobs.isCorrupted(e)
                    && !(e instanceof CorruptedElfQueen)).isEmpty();
            if (close) play("princess_pulse");
        }
    }

    private void lying(ServerLevel world) {
        getNavigation().stop();
        stopBeam(world);
        if (++downedTicks % 20 == 0) world.sendParticles(ParticleTypes.HAPPY_VILLAGER, getX(), getY() + .3, getZ(), 2, .4, .1, .4, 0);
        // 有人在扶：她身边的光一点点亮起来（扶到哪儿了，看光就知道）。
        if (reviver != null && reviveTicks > 0 && tickCount % 3 == 0) {
            int glow = 1 + reviveTicks * 6 / REVIVE_TICKS;
            world.sendParticles(ParticleTypes.HAPPY_VILLAGER, getX(), getY() + .5, getZ(), glow, .45, .4, .45, .01);
            if (tickCount % 15 == 0) world.playSound(null, blockPosition(), SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.NEUTRAL, .5f,
                    .8f + reviveTicks * .8f / REVIVE_TICKS);
        }
        if (downedTicks >= SELF_RISE_TICKS) { selfRises++; pendingSetbacks++; rise(world); say("self_rise"); }
    }

    private void down(ServerLevel world) {
        if (role() != Role.CHANNEL && role() != Role.LEAP) return;
        noPhysics = false;
        setNoGravity(false);
        stopBeam(world);
        role(Role.DOWNED);
        downs++;
        downedTicks = 0;
        reviver = null;
        reviveTicks = 0;
        play("princess_downed");
        world.playSound(null, blockPosition(), SoundEvents.PLAYER_HURT, SoundSource.NEUTRAL, 1, 1.3f);
        // 第一次倒下说清楚要人拉她一把（按住使用键），之后简短一点。
        if (!sayOnce("downed_first")) say("downed");
    }

    private void rise(ServerLevel world) {
        setHealth(getMaxHealth() * .5f);
        downedTicks = 0;
        reviver = null;
        reviveTicks = 0;
        role(Role.CHANNEL);
        play("princess_rise");
        world.sendParticles(ParticleTypes.HAPPY_VILLAGER, getX(), getY() + 1, getZ(), 20, .5, .7, .5, .02);
    }

    /** 测试用：直接倒地、按扶起算起来。 */
    public void forceDown() { if (level() instanceof ServerLevel world) { setHealth(1); down(world); } }

    public void forceRevive() { if (level() instanceof ServerLevel world && downed()) { revives++; rise(world); } }

    // ---------------------------------------------------------------- 招式

    private @Nullable MoveRunner runner() {
        if (!(level() instanceof ServerLevel world)) return null;
        Moveset set = Movesets.get(world.getServer(), MOVESET);
        if (set == null) return null;
        if (runner == null || runner.moveset() != set) runner = new MoveRunner(set, this, world.getGameTime());
        return runner;
    }

    private void play(String move) {
        MoveRunner r = runner();
        if (r != null && r.moveset().move(move) != null) r.force(move, level().getGameTime());
    }

    private String currentMove() { return runner == null ? "" : runner.move().name(); }

    @Override public double attackSpeed() { return 1; }

    @Override public void moveStarted(MoveRunner r) {
        if (r.cleared()) { entityData.set(MOVE, ""); return; }
        var move = r.move();
        entityData.set(MOVE, move.name().equals("princess_idle") ? "" : move.clip() + "|" + (long) r.start() + "|" + (float) r.rate() + "|" + (move.playback() == Moveset.Playback.LOOP));
    }

    @Override public void contact(MoveRunner r) {
        if (!(level() instanceof ServerLevel world) || !r.move().name().equals("princess_pulse")) return;
        for (LivingEntity e : world.getEntitiesOfClass(LivingEntity.class, getBoundingBox().inflate(4), e -> e.isAlive() && CorruptionMobs.isCorrupted(e)
                && !(e instanceof CorruptedElfQueen))) {
            Vec3 d = e.position().subtract(position()).multiply(1, 0, 1);
            if (d.lengthSqr() < 1e-4) d = new Vec3(1, 0, 0);
            e.knockback(1.4, -d.x, -d.z);
        }
        world.sendParticles(ParticleTypes.HAPPY_VILLAGER, getX(), getY() + .2, getZ(), 40, 2.5, .1, 2.5, .05);
        world.playSound(null, blockPosition(), SoundEvents.AMETHYST_CLUSTER_BREAK, SoundSource.NEUTRAL, 1, .8f);
        pulseCooldown = PULSE_COOLDOWN;
    }

    // ---------------------------------------------------------------- 受伤

    @Override public boolean hurtServer(ServerLevel world, DamageSource source, float amount) {
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            allowDeath = true;
            return super.hurtServer(world, source, amount);
        }
        // 战斗外打不死；战斗里玩家（和玩家的箭、法术）伤不到她。
        if (role() != Role.CHANNEL && role() != Role.LEAP) return false;
        if (source.getEntity() instanceof Player || source.getEntity() instanceof ElfPrincess) return false;
        boolean hurt = super.hurtServer(world, source, amount);
        if (getHealth() <= 1) down(world);
        return hurt;
    }

    /** 战斗里生命掉不到 0：掉光时停在 1，随后倒地。 */
    @Override public void setHealth(float health) {
        if (!allowDeath && health < 1 && !level().isClientSide()) health = 1;
        super.setHealth(health);
    }

    @Override public boolean isPushable() { return calm(); }
    @Override public boolean removeWhenFarAway(double distance) { return false; }
    @Override public boolean canBeLeashed() { return false; }

    @Override protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!(player instanceof ServerPlayer p) || !(level() instanceof ServerLevel world)) return InteractionResult.SUCCESS;
        if (downed()) {
            // 按住使用键：客户端每 4 刻发一次交互，连着按满 3 秒扶起。
            long now = world.getGameTime();
            if (!p.getUUID().equals(reviver) || now - reviveLast > 8) { reviver = p.getUUID(); reviveTicks = 0; }
            else reviveTicks += (int) (now - reviveLast);
            reviveLast = now;
            if (reviveTicks >= REVIVE_TICKS) { revives++; rise(world); say("revived"); }
            return InteractionResult.SUCCESS;
        }
        if (canTalk(p)) { CompanionTalk.begin(p, this); return InteractionResult.SUCCESS; }
        return InteractionResult.PASS;
    }

    // ---------------------------------------------------------------- 对话

    @Override public String talkId() { return TALK; }

    @Override public boolean canTalk(ServerPlayer p) { return isAlive() && calm() && p.isAlive() && p.distanceTo(this) < CompanionTalk.RANGE; }

    @Override public CompanionSheet talkSheet(ServerPlayer p) {
        return new CompanionSheet(getId(), TALK, "", List.of(), List.of(), 0, 0, "", 0, 0, 0, false, CompanionTactic.ADAPTIVE.id, false, 0, 0, 0);
    }

    private int cores(ServerLevel world) {
        return heart == null ? 0 : CorruptionNetworks.get(world).coresWithin(heart, 64);
    }

    @Override public List<CompanionTalk.Line> talkGreeting(ServerPlayer p) {
        ServerLevel world = p.level();
        if (role() == Role.AFTER) return List.of(CompanionTalk.Line.of(key("after")));
        List<CompanionTalk.Line> lines = new ArrayList<>();
        if (met.add(p.getUUID())) {
            lines.add(CompanionTalk.Line.of(key("greet_first")));
            lines.add(CompanionTalk.Line.of(key("greet_cores"), cores(world)));
            if (gift(p)) lines.add(CompanionTalk.Line.of(key("gift")));
        } else lines.add(CompanionTalk.Line.of(key(role() == Role.GATE ? "greet" : "greet_inside")));
        return lines;
    }

    /** 交出自然之力：每人一次；弄丢了、身上没有时再给。 */
    private boolean gift(ServerPlayer p) {
        boolean has = p.getInventory().contains(s -> s.getItem() instanceof NaturesForceItem);
        if (has) return false;
        gifted.add(p.getUUID());
        ItemStack bow = new ItemStack(NaturesForceItem.ITEM.get());
        if (!p.getInventory().add(bow)) p.drop(bow, false);
        return true;
    }

    @Override public CompanionTalk.Reply talkPick(ServerPlayer p, String option) {
        ServerLevel world = p.level();
        return switch (option) {
            case "topic.heart" -> CompanionTalk.Reply.say(CompanionTalk.Line.of(key("heart")), CompanionTalk.Line.of(key("heart_2")));
            case "topic.cores" -> CompanionTalk.Reply.say(CompanionTalk.Line.of(key("cores"), cores(world)), CompanionTalk.Line.of(key("cores_2")));
            case "topic.queen" -> CompanionTalk.Reply.say(CompanionTalk.Line.of(key("queen")), CompanionTalk.Line.of(key("queen_2")));
            case "topic.bow" -> gift(p) ? CompanionTalk.Reply.say(CompanionTalk.Line.of(key("gift")), CompanionTalk.Line.of(key("bow")))
                    : CompanionTalk.Reply.say(CompanionTalk.Line.of(key("bow")));
            case "ready" -> {
                if (role() == Role.GATE) { goAhead(world); yield CompanionTalk.Reply.say(CompanionTalk.Line.of(key("ready"))); }
                yield CompanionTalk.Reply.say(CompanionTalk.Line.of(key("ready_inside")));
            }
            default -> CompanionTalk.Reply.NONE;
        };
    }

    private static String key(String line) { return "talk.logos_dimension." + TALK + "." + line; }

    @Override public void talkStarted(ServerPlayer p) {
        talking = p.getUUID();
        getNavigation().stop();
        getLookControl().setLookAt(p, 30, 30);
    }

    @Override public void talkEnded(ServerPlayer p, boolean bye) {
        if (p.getUUID().equals(talking)) talking = null;
    }

    // ---------------------------------------------------------------- 外观与动画

    @Override public NpcParts.Race npcRace() { return NpcParts.FOREST_ELF; }
    @Override public Map<String, String> npcAppearance() { return NpcParts.FOREST_ELF.sanitize(LOOK); }
    @Override public Identifier npcAnimationAsset() { return ASSET; }

    @Override public @Nullable CombatMove npcCombatMove() {
        String current = entityData.get(MOVE);
        if (!current.equals(clientMove)) {
            clientMove = current;
            String[] parts = current.split("\\|");
            clientCombat = parts.length < 4 ? null : new CombatMove(parts[0], Long.parseLong(parts[1]), Float.parseFloat(parts[2]), Boolean.parseBoolean(parts[3]));
        }
        return clientCombat;
    }

    @Override public void registerControllers(AnimatableManager.ControllerRegistrar c) { NpcAnimations.registerControllers(this, c); }
    @Override public AnimatableInstanceCache getAnimatableInstanceCache() { return cache; }

    // ---------------------------------------------------------------- 存档

    @Override protected void addAdditionalSaveData(ValueOutput out) {
        super.addAdditionalSaveData(out);
        if (heart != null) out.putLong("Heart", heart.asLong());
        out.putString("Role", role().name());
        out.putInt("DownedTicks", downedTicks);
        out.putInt("Downs", downs); out.putInt("Revives", revives); out.putInt("SelfRises", selfRises);
        out.putString("Met", String.join(",", met.stream().map(UUID::toString).toList()));
        out.putString("Gifted", String.join(",", gifted.stream().map(UUID::toString).toList()));
    }

    @Override protected void readAdditionalSaveData(ValueInput in) {
        super.readAdditionalSaveData(in);
        heart = in.getLong("Heart").map(BlockPos::of).orElse(null);
        Role role;
        try { role = Role.valueOf(in.getStringOr("Role", "GATE")); } catch (IllegalArgumentException e) { role = Role.GATE; }
        // 跃到一半存档：读档直接落在祭坛上。
        if (role == Role.LEAP) role = Role.CHANNEL;
        role(role);
        downedTicks = in.getIntOr("DownedTicks", 0);
        downs = in.getIntOr("Downs", 0); revives = in.getIntOr("Revives", 0); selfRises = in.getIntOr("SelfRises", 0);
        uuids(in.getStringOr("Met", ""), met);
        uuids(in.getStringOr("Gifted", ""), gifted);
        runner = null;
        play(switch (role) { case CHANNEL -> "princess_channel"; case DOWNED -> "princess_downed"; default -> "princess_idle"; });
    }

    private static void uuids(String text, Set<UUID> into) {
        into.clear();
        for (String s : text.split(",")) {
            if (s.isBlank()) continue;
            try { into.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) {}
        }
    }

    /** 测试用：这一场的计数清零（战斗重置时女王也会清）。 */
    public void resetCounts() { downs = 0; revives = 0; selfRises = 0; pendingSetbacks = 0; CompanionLines.forget(this); }
}
