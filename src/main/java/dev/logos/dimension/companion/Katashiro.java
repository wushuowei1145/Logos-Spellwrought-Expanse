package dev.logos.dimension.companion;

import com.geckolib.animatable.GeoEntity;
import com.geckolib.animatable.instance.AnimatableInstanceCache;
import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.animation.AnimationController;
import com.geckolib.animation.RawAnimation;
import com.geckolib.util.GeckoLibUtil;
import dev.logos.dimension.dissolve.DissolveShape;
import dev.logos.dimension.dissolve.Dissolving;
import dev.logos.dimension.foxfire.FoxfireFlame;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/**
 * 形代：白梅倒下时留在原地、替她烧掉的纸人（docs/SHIRAUME_COMPANION.md 第 7.5 节）。
 * 从她胸口的高度飘落（动画 {@code drop}），落地就被幽蓝的狐火点着，四边卷起来（{@code burn}），由消散带走。
 * 模型在仓库外 {@code fox_model/nine_tailed_fox_companion/katashiro}。不存档。
 */
public class Katashiro extends Entity implements GeoEntity {
    /** 落地（drop 的 land 标记 0.86 秒）、点着、烧尽（消散 28 刻）、保险的移除。 */
    static final int LAND = 17, IGNITE = 20, BURN_TICKS = 28, END = 70;
    private static final RawAnimation SEQUENCE = RawAnimation.begin().thenPlay("drop").thenPlayAndHold("burn");

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    public Katashiro(EntityType<? extends Katashiro> type, Level level) {
        super(type, level);
        noPhysics = true;
        setNoGravity(true);
    }

    /** 在地面上 {@code at} 放一张，脸朝随机的方向。 */
    static void drop(ServerLevel level, Vec3 at) {
        Katashiro k = new Katashiro(Companions.KATASHIRO.get(), level);
        k.snapTo(at.x, at.y, at.z, level.getRandom().nextFloat() * 360, 0);
        level.addFreshEntity(k);
    }

    @Override
    public void tick() {
        super.tick();
        if (!(level() instanceof ServerLevel level)) return;
        Vec3 p = position();
        if (tickCount == LAND) level.sendParticles(ParticleTypes.CHERRY_LEAVES, p.x, p.y + .1, p.z, 6, .2, .05, .2, .01);
        if (tickCount == IGNITE) {
            Dissolving.entity(this, DissolveShape.noise(), BURN_TICKS, FoxfireFlame.KITSUNE.core);
            level.playSound(null, p.x, p.y, p.z, SoundEvents.SOUL_ESCAPE.value(), SoundSource.NEUTRAL, 1, 1.1f);
            level.playSound(null, p.x, p.y, p.z, SoundEvents.FIRECHARGE_USE, SoundSource.NEUTRAL, .5f, 1.4f);
        }
        if (tickCount >= IGNITE && tickCount < IGNITE + BURN_TICKS) {
            // 幽蓝的狐火：原版的灵魂火焰一簇簇往上窜，带着蓝色的尘，偶尔飘起一缕魂（docs/FOXFIRE.md）
            level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, p.x, p.y + .1, p.z, 3, .17, .03, .22, .025);
            level.sendParticles(new DustParticleOptions(FoxfireFlame.KITSUNE.core, 1.1f), p.x, p.y + .2, p.z, 2, .16, .08, .2, 0);
            if (tickCount % 5 == 0) level.sendParticles(ParticleTypes.SOUL, p.x, p.y + .2, p.z, 1, .12, .05, .12, .02);
        }
        if (tickCount >= END) discard();
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {}

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {}

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {}

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<Katashiro>("main", 0, test -> test.setAndContinue(SEQUENCE)));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    public boolean isAttackable() {
        return false;
    }
}
