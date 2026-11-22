package dev.logos.dimension.jiansui;

import dev.logos.dimension.taixuan.TaixuanSite;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * 第三阶段「坠渊」的场景（docs/JIAN_SUI_BOSS.md 第 10.2、13 节）：一个不存档、不动、碰不到的实体，放在场地下面空洞的竖轴上。
 * 客户端围着它画外面那一层（几层往上滚的圆筒和顶、底）、只看的碎块（断柱、下坠的魂灯、斜着的地板块、断链）和往上飞的尘土，
 * 见 {@code client.jiansui.FallStageRenderer}。场景本身一动不动，四周一直往上流，人就觉得自己在往下掉。
 *
 * <p>整场只有一个生成包；后来的人靠实体追踪拿到它；结束时删掉它就干净了，服务器崩了也不会留下东西。
 * 同步的只有：地标朝向（碎块的摆放按它转）、往上流的速度，以及速度从哪一刻开始变（终结打中的那一下一下子停住，见 {@link #stopNow}）。
 */
public class FallStage extends Entity {
    /** 往上流的速度（格/秒）：一直匀速，像终端速度（第 10.2 节）。 */
    public static final float FALL_SPEED = 12f;
    /** {@link #easeSpeed} 把速度变到新值要多少刻（试场景、以后要慢慢变速时用；终结用的是一下子停住）。 */
    public static final int EASE_TICKS = 40;
    /** 实体放在竖轴上的世界高度：空洞（-57..-20）的中间。 */
    public static final double CENTER_Y = -38.5;

    private static final EntityDataAccessor<Integer> ROTATION = SynchedEntityData.defineId(FallStage.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> SPEED = SynchedEntityData.defineId(FallStage.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> SPEED_FROM = SynchedEntityData.defineId(FallStage.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> SPEED_SINCE = SynchedEntityData.defineId(FallStage.class, EntityDataSerializers.INT);

    public FallStage(EntityType<? extends FallStage> type, Level level) {
        super(type, level);
        noPhysics = true;
        setNoGravity(true);
    }

    /** 在这处太玄灵境的空洞里放一个场景，四周马上就是全速（白屏退去的那一刻已经在往下掉）。 */
    public static FallStage spawn(ServerLevel level, TaixuanSite site) {
        var stage = new FallStage(JianSuiEntities.FALL_STAGE.get(), level);
        Vec3 at = site.axis(CENTER_Y);
        stage.snapTo(at.x, at.y, at.z, 0, 0);
        stage.entityData.set(ROTATION, site.rotation().ordinal());
        level.addFreshEntity(stage);
        return stage;
    }

    /** 地标朝向，同 {@link net.minecraft.world.level.block.Rotation#ordinal()}。 */
    public int rotationIndex() {
        return entityData.get(ROTATION);
    }

    /** 往上流的速度慢慢变到 {@code speed}（格/秒），{@link #EASE_TICKS} 刻变完。 */
    public void easeSpeed(float speed) {
        entityData.set(SPEED_FROM, speedAt(level().getGameTime()));
        entityData.set(SPEED_SINCE, (int) level().getGameTime());
        entityData.set(SPEED, speed);
    }

    /** 往上流的东西一下子停住（终结打中的那一下，第 11 节）。 */
    public void stopNow() {
        entityData.set(SPEED_FROM, 0f);
        entityData.set(SPEED_SINCE, (int) level().getGameTime());
        entityData.set(SPEED, 0f);
    }

    /** {@code gameTime} 时往上流的速度（格/秒）。 */
    public float speedAt(double gameTime) {
        float k = Mth.clamp((float) (gameTime - entityData.get(SPEED_SINCE)) / EASE_TICKS, 0, 1);
        k = k * k * (3 - 2 * k);
        return Mth.lerp(k, entityData.get(SPEED_FROM), entityData.get(SPEED));
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(ROTATION, 0);
        builder.define(SPEED, FALL_SPEED);
        builder.define(SPEED_FROM, FALL_SPEED);
        builder.define(SPEED_SINCE, 0);
    }

    @Override
    public void tick() {
        super.tick();
        setDeltaMovement(Vec3.ZERO);
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean isAttackable() {
        return false;
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {}

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {}
}
