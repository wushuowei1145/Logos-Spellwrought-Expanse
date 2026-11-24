package dev.logos.dimension.elven.blight;

import dev.logos.dimension.combat.CreatureTraits;
import java.util.UUID;
import net.minecraft.network.syncher.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.ai.attributes.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.*;
import org.jspecify.annotations.Nullable;

/**
 * 腐败之心身上能被原版近战、动作武器、投射物和法术打到的部分：第 0–7 号是高台上的主血管接头（{@link HeartVeins}），
 * 第 {@link #HEART} 号是降下来的腐败之心本体（玩家砍中一刀斩掉一段，见 {@link CorruptedElfQueen#heartHit}）。
 */
public final class QueenTether extends PathfinderMob {
    public static final int HEART = HeartVeins.MAIN;
    /** 血管接头的生命。 */
    public static final float VEIN_HEALTH = 32;
    private static final EntityDataAccessor<Integer> SLOT = SynchedEntityData.defineId(QueenTether.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> OWNER = SynchedEntityData.defineId(QueenTether.class, EntityDataSerializers.INT);
    private @Nullable UUID owner;
    private int missing;
    /** 重做前（三段断链）的根结读档后直接清掉。 */
    private boolean current = true;

    public QueenTether(EntityType<? extends QueenTether> type, Level level) {
        super(type, level); setNoAi(true); setNoGravity(true); setPersistenceRequired(); xpReward = 0;
        // 血管是腐败之心的一部分，抗性同女王（Logos 命中前读这些属性）。
        if (level instanceof ServerLevel server) CreatureTraits.of(server.getServer(), CorruptedElfQueen.TRAITS).apply(this);
    }
    public static AttributeSupplier.Builder attributes() { return PathfinderMob.createMobAttributes().add(Attributes.MAX_HEALTH, VEIN_HEALTH).add(Attributes.KNOCKBACK_RESISTANCE, 1); }
    @Override protected void defineSynchedData(SynchedEntityData.Builder b) { super.defineSynchedData(b); b.define(SLOT, 0); b.define(OWNER, -1); }
    public int slot() { return entityData.get(SLOT); }
    public boolean heart() { return slot() == HEART; }
    public boolean belongsTo(CorruptedElfQueen queen) { return queen.getUUID().equals(owner); }
    public @Nullable CorruptedElfQueen queen() {
        // 服务端按持久 UUID 寻主，避免重载后运行时实体编号复用而串到另一场战斗。
        if (level() instanceof ServerLevel world) return owner != null && world.getEntity(owner) instanceof CorruptedElfQueen q ? q : null;
        return level().getEntity(entityData.get(OWNER)) instanceof CorruptedElfQueen q ? q : null;
    }
    public void bind(CorruptedElfQueen q, int slot) {
        owner = q.getUUID(); entityData.set(SLOT, slot); entityData.set(OWNER, q.getId());
        float health = slot == HEART ? 1000 : VEIN_HEALTH * q.scale();
        getAttribute(Attributes.MAX_HEALTH).setBaseValue(health);
        setHealth(health); refreshDimensions();
    }
    @Override protected EntityDimensions getDefaultDimensions(Pose pose) { return heart() ? EntityDimensions.scalable(3.8f, 5.2f) : super.getDefaultDimensions(pose); }
    @Override public void onSyncedDataUpdated(EntityDataAccessor<?> key) { super.onSyncedDataUpdated(key); if (SLOT.equals(key)) refreshDimensions(); }
    @Override public boolean canBeLeashed() { return false; }
    @Override public boolean isPushable() { return false; }
    @Override public boolean hurtServer(ServerLevel world, DamageSource source, float amount) {
        var q = queen();
        if (q == null || amount <= 0 || !Float.isFinite(amount) || !(source.getEntity() instanceof LivingEntity) || source.getEntity() == q) return false;
        if (heart()) {
            // 降下来的腐败之心不按伤害量扣：玩家砍中一刀就斩掉一段；本体不掉血，只给命中反馈。
            if (!q.heartHit(world, source)) return false;
            world.broadcastEntityEvent(this, (byte) 2);
            return true;
        }
        if (!q.veinsOpen() || QueenBattleRules.severed(q.brokenRoots(), slot())) return false;
        // Logos 在进入 hurtServer 前已按属性算过元素抗性；这里只补抗性表里的条件倍数和核心庇护，不重复算。
        return super.hurtServer(world, source, q.incoming(world, source, amount));
    }
    @Override public void die(DamageSource source) {
        var q = queen();
        if (heart()) { setHealth(getMaxHealth()); return; }
        if (q != null) q.sever(slot(), source);
        discard();
    }
    @Override public void tick() {
        super.tick(); setDeltaMovement(0, 0, 0);
        if (!(level() instanceof ServerLevel world)) return;
        if (!current) { discard(); return; }
        var q = queen();
        if (q == null) { if (++missing > 200) discard(); return; }
        missing = 0;
        entityData.set(OWNER, q.getId());
        boolean keep = q.isAlive() && q.phase() == 2
                && (heart() ? q.purification().open() : !QueenBattleRules.severed(q.brokenRoots(), slot()));
        if (!keep) { discard(); return; }
        if (heart()) setPos(q.corePosition(0));
    }
    @Override protected void addAdditionalSaveData(ValueOutput out) {
        super.addAdditionalSaveData(out);
        if (owner != null) out.putString("Queen", owner.toString());
        out.putInt("TetherSlot", slot()); out.putInt("Remake", 4);
    }
    @Override protected void readAdditionalSaveData(ValueInput in) {
        super.readAdditionalSaveData(in);
        entityData.set(SLOT, Math.clamp(in.getIntOr("TetherSlot", 0), 0, HEART));
        current = in.getIntOr("Remake", 0) >= 4;
        try { owner = UUID.fromString(in.getStringOr("Queen", "")); } catch (IllegalArgumentException ignored) { owner = null; }
        if (level() instanceof ServerLevel server) CreatureTraits.of(server.getServer(), CorruptedElfQueen.TRAITS).apply(this);
    }
}
