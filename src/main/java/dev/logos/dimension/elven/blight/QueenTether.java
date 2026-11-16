package dev.logos.dimension.elven.blight;

import java.util.UUID;
import net.minecraft.network.syncher.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.*;
import org.jspecify.annotations.Nullable;

/** 可被原版近战、动作武器和投射物命中的血管根结；第 3 号是下降核心的实体判定。 */
public final class QueenTether extends PathfinderMob {
    private static final EntityDataAccessor<Integer> SLOT = SynchedEntityData.defineId(QueenTether.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> OWNER = SynchedEntityData.defineId(QueenTether.class, EntityDataSerializers.INT);
    private @Nullable UUID owner;
    private int missing;
    private int spawnPhase = 1;
    public QueenTether(EntityType<? extends QueenTether> type, Level level) {
        super(type, level); setNoAi(true); setNoGravity(true); setPersistenceRequired(); xpReward = 0;
        // 根结是女王身体的一部分，抗性同女王（Logos 命中前读这些属性）。
        if (level instanceof ServerLevel server) dev.logos.dimension.combat.CreatureTraits.of(server.getServer(), CorruptedElfQueen.TRAITS).apply(this);
    }
    public static AttributeSupplier.Builder attributes() { return PathfinderMob.createMobAttributes().add(Attributes.MAX_HEALTH, 32).add(Attributes.KNOCKBACK_RESISTANCE, 1); }
    @Override protected void defineSynchedData(SynchedEntityData.Builder b) { super.defineSynchedData(b); b.define(SLOT, 0); b.define(OWNER, -1); }
    public int slot() { return entityData.get(SLOT); }
    public int spawnPhase() { return spawnPhase; }
    public boolean belongsTo(CorruptedElfQueen queen) { return queen.getUUID().equals(owner); }
    public @Nullable CorruptedElfQueen queen() {
        // 服务端按持久 UUID 寻主，避免重载后运行时实体编号复用而串到另一场战斗。
        if (level() instanceof ServerLevel world) return owner != null && world.getEntity(owner) instanceof CorruptedElfQueen q ? q : null;
        return level().getEntity(entityData.get(OWNER)) instanceof CorruptedElfQueen q ? q : null;
    }
    public void bind(CorruptedElfQueen q, int slot) {
        owner = q.getUUID(); spawnPhase = q.phase(); entityData.set(SLOT, slot); entityData.set(OWNER, q.getId());
        if (slot == 3) getAttribute(Attributes.MAX_HEALTH).setBaseValue(QueenBattleRules.HEALTH);
        setHealth(slot == 3 ? q.getHealth() : QueenBattleRules.rootHealth(q.phase())); refreshDimensions();
    }
    @Override protected EntityDimensions getDefaultDimensions(Pose pose) { return slot() == 3 ? EntityDimensions.scalable(3.8f, 5.2f) : super.getDefaultDimensions(pose); }
    @Override public void onSyncedDataUpdated(EntityDataAccessor<?> key) { super.onSyncedDataUpdated(key); if (SLOT.equals(key)) refreshDimensions(); }
    @Override public boolean canBeLeashed() { return false; }
    @Override public boolean isPushable() { return false; }
    @Override public boolean hurtServer(ServerLevel world, DamageSource source, float amount) {
        var q = queen();
        if (q == null || spawnPhase != q.phase() || amount <= 0 || !Float.isFinite(amount) || !(source.getEntity() instanceof LivingEntity) || source.getEntity() == q) return false;
        // Logos 在进入 hurtServer 前已按属性算过元素抗性；这里只补抗性表里的条件倍数和核心庇护，不重复算。
        amount = q.incoming(world, source, amount);
        if (slot() == 3) {
            if (!QueenBattleRules.coreExposed(q.phase(), q.brokenRoots())) return false;
            setHealth(q.getHealth());
            float before = getHealth();
            boolean hit = super.hurtServer(world, source, amount);
            // 原版伤害事件、附魔与冷却先结算，再把实际损失交给核心；不能凭空绕过它们。
            if (hit && q.phase() == 3) q.damageCore(world, source, Math.max(0, before - getHealth()));
            if (isAlive()) setHealth(q.getHealth());
            return hit;
        }
        if (!q.rootsOpen() || QueenBattleRules.severed(q.brokenRoots(), slot())) return false;
        return super.hurtServer(world, source, amount);
    }
    @Override public void die(DamageSource source) {
        var q = queen();
        if (slot() == 3 && q != null && level() instanceof ServerLevel world) {
            q.damageCore(world, source, q.getHealth()); setHealth(1); return;
        }
        if (q != null) q.sever(slot(), source);
        discard();
    }
    @Override public void tick() {
        super.tick(); setDeltaMovement(0, 0, 0);
        if (!(level() instanceof ServerLevel world)) return;
        var q = queen();
        if (q == null) { if (++missing > 200) discard(); return; }
        missing = 0;
        entityData.set(OWNER, q.getId());
        if (!q.isAlive() || q.phase() != spawnPhase || q.phase() >= QueenBattleRules.ENDING || (slot() != 3 && (!q.rootsOpen() || QueenBattleRules.severed(q.brokenRoots(), slot())))) { discard(); return; }
        if (slot() == 3) setPos(q.corePosition(0));
    }
    @Override protected void addAdditionalSaveData(ValueOutput out) { super.addAdditionalSaveData(out); if (owner != null) out.putString("Queen", owner.toString()); out.putInt("TetherSlot", slot()); out.putInt("CorePhase", spawnPhase); }
    @Override protected void readAdditionalSaveData(ValueInput in) {
        super.readAdditionalSaveData(in); entityData.set(SLOT, Math.clamp(in.getIntOr("TetherSlot", 0), 0, 3)); spawnPhase = Math.clamp(in.getIntOr("CorePhase", 1), 1, 3);
        try { owner = UUID.fromString(in.getStringOr("Queen", "")); } catch (IllegalArgumentException ignored) { owner = null; }
        if (level() instanceof ServerLevel server) dev.logos.dimension.combat.CreatureTraits.of(server.getServer(), CorruptedElfQueen.TRAITS).apply(this);
    }
}
