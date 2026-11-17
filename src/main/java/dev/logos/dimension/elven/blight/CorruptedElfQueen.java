package dev.logos.dimension.elven.blight;

import com.geckolib.animatable.instance.AnimatableInstanceCache;
import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.util.GeckoLibUtil;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.corruption.Corruption;
import dev.logos.dimension.corruption.CorruptionContact;
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
public class CorruptedElfQueen extends PathfinderMob implements NpcAnimated {
    public static final int RISE_TICKS = 40;
    private static final EntityDataAccessor<String> MOVE = SynchedEntityData.defineId(CorruptedElfQueen.class, EntityDataSerializers.STRING);
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private final Set<UUID> struck = new HashSet<>();
    private @Nullable BlockPos heart;
    private @Nullable ServerBossEvent bar;
    private String action = "", clientAction = "";
    private @Nullable CombatMove clientMove;
    private int elapsed, wait = 20, sequence;
    private boolean introPlayed;
    private Vec3 aim = Vec3.ZERO, origin = Vec3.ZERO;

    public CorruptedElfQueen(EntityType<? extends CorruptedElfQueen> type, Level world) {
        super(type, world);
        setPersistenceRequired();
        xpReward = 80;
    }

    public static AttributeSupplier.Builder attributes() {
        return PathfinderMob.createMobAttributes().add(Attributes.MAX_HEALTH, 360).add(Attributes.MOVEMENT_SPEED, .28)
                .add(Attributes.ARMOR, 6).add(Attributes.KNOCKBACK_RESISTANCE, 1).add(Attributes.FOLLOW_RANGE, 40).add(Attributes.STEP_HEIGHT, 1);
    }

    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) { super.defineSynchedData(builder); builder.define(MOVE, ""); }

    public void guard(BlockPos at) { heart = at.immutable(); play("queen_guard"); }
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

    private void play(String clip) {
        action = clip;
        elapsed = 0;
        struck.clear();
        getNavigation().stop();
        origin = position();
        LivingEntity target = getTarget();
        aim = target == null ? getLookAngle().multiply(1, 0, 1).normalize() : target.position().subtract(position()).multiply(1, 0, 1).normalize();
        if (aim.lengthSqr() < .01) aim = new Vec3(0, 0, 1);
        setYRot((float) Math.toDegrees(Math.atan2(-aim.x, aim.z)));
        yBodyRot = getYRot();
        entityData.set(MOVE, clip.isEmpty() ? "" : clip + "|" + level().getGameTime());
    }

    @Override public void tick() {
        super.tick();
        if (!(level() instanceof ServerLevel world)) return;
        if (!isAlive()) return;
        if (world.getDifficulty() == net.minecraft.world.Difficulty.PEACEFUL) {
            getNavigation().stop();
            setTarget(null);
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
        if (action.equals("queen_guard")) {
            getNavigation().stop();
            for (ServerPlayer p : world.players()) if (canFight(p) && p.distanceToSqr(this) < 14 * 14 && hasLineOfSight(p)) { engage(p); break; }
            return;
        }
        updateBar(world);
        if (entering()) {
            getNavigation().stop();
            if (++elapsed % 10 == 0) {
                world.sendParticles(ParticleTypes.ASH, getX(), getY() + 1.3, getZ(), 12, .8, 1, .8, .02);
                if (elapsed == 20) world.playSound(null, blockPosition(), SoundEvents.SCULK_SHRIEKER_SHRIEK, SoundSource.HOSTILE, .9f, .6f);
            }
            if (elapsed >= (action.equals("queen_entrance") ? QueenEntrances.TICKS : RISE_TICKS)) { play(""); wait = 35; }
            return;
        }
        if (!action.isEmpty()) {
            getNavigation().stop();
            setYRot((float) Math.toDegrees(Math.atan2(-aim.x, aim.z)));
            yBodyRot = getYRot();
            attack(world, ++elapsed);
            return;
        }
        ServerPlayer target = world.players().stream().filter(p -> canFight(p) && p.distanceToSqr(this) < 40 * 40)
                .min(java.util.Comparator.comparingDouble(p -> p.distanceToSqr(this))).orElse(null);
        setTarget(target);
        if (target == null) { getNavigation().stop(); return; }
        getLookControl().setLookAt(target, 20, 20);
        if (distanceToSqr(target) > 4 * 4) getNavigation().moveTo(target, 1);
        else getNavigation().stop();
        if (--wait > 0 || !hasLineOfSight(target)) return;
        if (distanceToSqr(target) < 5 * 5 && sequence % 3 == 0) play("queen_reap");
        else play(sequence % 2 == 0 ? "queen_roots" : "queen_thorns");
        sequence++;
    }

    private static boolean canFight(Player p) { return p.isAlive() && !p.isCreative() && !p.isSpectator(); }

    private void attack(ServerLevel world, int tick) {
        switch (action) {
            case "queen_reap" -> {
                if (tick == 1) sound(world, SoundEvents.EVOKER_PREPARE_ATTACK, .8f);
                if (tick < 22 && tick % 3 == 0) warning(world, origin.add(aim.scale(3)), .7);
                if (tick == 24) {
                    for (ServerPlayer p : targets(world, 5.5)) {
                        Vec3 delta = p.position().subtract(origin).multiply(1, 0, 1);
                        if (delta.lengthSqr() <= 25 && delta.normalize().dot(aim) >= .5 && hasLineOfSight(p)) hit(world, p, 8);
                    }
                    sound(world, SoundEvents.PLAYER_ATTACK_SWEEP, .65f);
                }
                if (tick >= 48) finish();
            }
            case "queen_roots" -> {
                if (tick == 1) sound(world, SoundEvents.EVOKER_PREPARE_SUMMON, .65f);
                if (tick < 30 && tick % 4 == 0) ring(world, 2.5, true);
                if (tick >= 30 && tick <= 58) {
                    double radius = (tick - 30) * .38 + 1;
                    if (tick % 2 == 0) ring(world, radius, false);
                    for (ServerPlayer p : targets(world, 13)) {
                        double d = p.position().multiply(1, 0, 1).distanceTo(origin.multiply(1, 0, 1));
                        // 跳离地面可躲，整圈扫过也只结算一次。
                        if (Math.abs(d - radius) < .65 && p.getY() < origin.y + .65 && p.getY() > origin.y - 1 && hasLineOfSight(p)) hit(world, p, 7);
                    }
                }
                if (tick >= 76) finish();
            }
            case "queen_thorns" -> {
                if (tick == 1) sound(world, SoundEvents.EVOKER_PREPARE_ATTACK, .65f);
                if (tick < 32 && tick % 4 == 0) for (int i = 2; i <= 16; i++) warning(world, origin.add(aim.scale(i)), .08);
                if (tick == 34) {
                    for (int i = 1; i <= 16; i++) {
                        Vec3 p = origin.add(aim.scale(i));
                        world.sendParticles(ParticleTypes.SCULK_SOUL, p.x, p.y + .5, p.z, 3, .3, .7, .3, .02);
                    }
                    for (ServerPlayer p : targets(world, 18)) {
                        Vec3 d = p.position().subtract(origin);
                        double along = d.dot(aim), across = Math.abs(d.x * aim.z - d.z * aim.x);
                        if (along > 0 && along < 17 && across < 1.2 && Math.abs(d.y) < 2 && hasLineOfSight(p)) hit(world, p, 10);
                    }
                    sound(world, SoundEvents.EVOKER_CAST_SPELL, .65f);
                }
                if (tick >= 62) finish();
            }
            default -> finish();
        }
    }

    private List<ServerPlayer> targets(ServerLevel world, double range) {
        return world.players().stream().filter(p -> canFight(p) && p.distanceToSqr(origin) <= range * range).toList();
    }
    private void hit(ServerLevel world, ServerPlayer player, float damage) {
        if (!struck.add(player.getUUID())) return;
        var source = new DamageSource(world.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(Corruption.DAMAGE), this);
        float scale = switch (world.getDifficulty()) { case EASY -> .75f; case HARD -> 1.25f; default -> 1; };
        if (player.hurtServer(world, source, damage * scale)) CorruptionContact.drainMana(player, 10);
    }
    private void ring(ServerLevel world, double radius, boolean warning) {
        int count = Math.max(12, (int) (radius * 8));
        for (int i = 0; i < count; i++) {
            double a = i * Math.PI * 2 / count;
            Vec3 p = origin.add(Math.cos(a) * radius, .15, Math.sin(a) * radius);
            world.sendParticles(warning ? ParticleTypes.ASH : ParticleTypes.SCULK_SOUL, p.x, p.y, p.z, 1, .03, .1, .03, .005);
        }
    }
    private void warning(ServerLevel world, Vec3 p, double spread) { world.sendParticles(ParticleTypes.ASH, p.x, p.y + .2, p.z, 3, spread, .1, spread, .005); }
    private void sound(ServerLevel world, net.minecraft.sounds.SoundEvent sound, float pitch) { world.playSound(null, blockPosition(), sound, SoundSource.HOSTILE, 1, pitch); }
    private void finish() { play(""); wait = getHealth() < getMaxHealth() / 2 ? 18 : 32; }

    @Override public boolean hurtServer(ServerLevel world, DamageSource source, float amount) {
        if (action.equals("queen_guard") && source.getEntity() instanceof ServerPlayer player && canFight(player)) engage(player);
        if (entering() && !source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY)) return false;
        return super.hurtServer(world, source, amount);
    }
    @Override public void die(DamageSource source) {
        if (level() instanceof ServerLevel world && heart != null && world.getBlockEntity(heart) instanceof QueenHeart core) core.defeated(getUUID());
        if (bar != null) bar.removeAllPlayers();
        super.die(source);
    }
    private void updateBar(ServerLevel world) {
        if (bar == null) bar = new ServerBossEvent(UUID.randomUUID(), Component.translatable("entity.logos_dimension.corrupted_elf_queen"), BossEvent.BossBarColor.PURPLE, BossEvent.BossBarOverlay.PROGRESS);
        bar.setProgress(Mth.clamp(getHealth() / getMaxHealth(), 0, 1));
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
    }
    @Override protected void readAdditionalSaveData(ValueInput in) {
        super.readAdditionalSaveData(in);
        if (in.getInt("HeartY").isPresent()) heart = new BlockPos(in.getIntOr("HeartX", 0), in.getIntOr("HeartY", 0), in.getIntOr("HeartZ", 0));
        introPlayed=in.getBooleanOr("IntroPlayed",in.getBooleanOr("Emerging",false));
        if (in.getBooleanOr("Guarding", false)) play("queen_guard");
        else if (in.getBooleanOr("Emerging", false)) { boolean entrance=in.getBooleanOr("Entrance",false); play(entrance?"queen_entrance":"queen_rise"); elapsed = Mth.clamp(in.getIntOr("EmergenceTick", 0), 0, entrance?QueenEntrances.TICKS:RISE_TICKS); entityData.set(MOVE, action + "|" + (level().getGameTime() - elapsed)); }
    }
    @Override public Identifier npcAnimationAsset() { return LogosDimension.id("corrupted_elf_queen"); }
    @Override public @Nullable CombatMove npcCombatMove() {
        String current = entityData.get(MOVE);
        if (!current.equals(clientAction)) {
            clientAction = current;
            int split = current.indexOf('|');
            clientMove = split < 0 ? null : new CombatMove(current.substring(0, split), Long.parseLong(current.substring(split + 1)), 1, current.startsWith("queen_guard|"));
        }
        return clientMove;
    }
    @Override public void registerControllers(AnimatableManager.ControllerRegistrar c) { NpcAnimations.registerControllers(this, c); }
    @Override public AnimatableInstanceCache getAnimatableInstanceCache() { return cache; }
}
