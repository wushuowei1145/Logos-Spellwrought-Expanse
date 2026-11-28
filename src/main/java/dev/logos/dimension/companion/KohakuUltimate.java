package dev.logos.dimension.companion;

import dev.logos.dimension.camera.CameraPoint;
import dev.logos.dimension.item.LogosNatureBridge;
import dev.logos.dimension.screenfx.ScreenEffects;
import dev.logos.dimension.screenfx.ScreenFlash;
import dev.logos.dimension.ultimate.RelayGift;
import dev.logos.dimension.ultimate.UltimateCast;
import dev.logos.dimension.ultimate.UltimateChain;
import dev.logos.dimension.ultimate.UltimateDef;
import dev.logos.dimension.ultimate.UltimateHit;
import dev.logos.dimension.ultimate.UltimateScript;
import dev.logos.lang.Element;
import dev.logos.runtime.Effects;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.Tags;
import org.jspecify.annotations.Nullable;

/**
 * 琥珀的奥义「星辰花炼成 · 凝时琥珀」（docs/KOHAKU_COMPANION.md 第 20 节；分镜稿 {@code kohaku_ult_storyboard.md}）。
 * 服务端这一半：她的动作、声音、世界里的粒子、出手那一拍的伤害、凝时、冲击帧，以及交给下一位的「炼金回流」。
 * 画面（分镜运镜、画面凝进琥珀、碎镜、星辰花）在客户端的 {@code KohakuUltimateShow}，按同一张 {@link Beats} 走。
 *
 * <p>完整版八镜 106 刻：举杖吟唱 → 敌群凝时 → 画面坍缩凝进琥珀 → 急拉成掌中琥珀 → 指尖轻弹 → 全屏碎镜 → 星辰花爆破（出手，第 75 刻）→ 碎晶擦镜。
 * 缩短版 42 刻：凝时一瞬 → 掌中琥珀 → 轻弹 → 碎镜 → 爆破（第 26 刻）→ 擦镜。两版的伤害和馈赠一样。
 */
public final class KohakuUltimate implements UltimateScript {
    /**
     * 一版的节拍（刻）：开演以后第几刻进哪一镜。
     *
     * @param length   一共多少刻
     * @param impact   出手：星辰花炸开、结算伤害和凝时、交出馈赠
     * @param stop     切到敌群、时间停住（反色、色散、波纹，这时抓下那一帧画面）
     * @param collapse 画面坍缩进琥珀
     * @param palm     急拉：琥珀悬在她手边
     * @param flick    极近的特写
     * @param touch    指尖碰到晶面（星芒、裂纹）
     * @param shatter  全屏碎镜
     * @param wipe     碎晶擦镜
     * @param chant    有没有起手的举杖吟唱（缩短版没有，第 0 刻就是凝时）
     */
    public record Beats(int length, int impact, int stop, int collapse, int palm, int flick, int touch, int shatter, int wipe, boolean chant) {
        /** 抓那一帧画面的时刻：切到敌群以后稍等一下（第一帧可能还是上一镜）。 */
        public float capture() {
            return stop + 1.5f;
        }
    }

    /** 完整版（分镜稿的八镜）。 */
    public static final Beats FULL = new Beats(106, 75, 14, 27, 43, 55, 58, 65, 91, true);
    /** 缩短版（连携里第二位起）：留凝时、掌中琥珀、轻弹碎镜和爆破，再擦镜。 */
    public static final Beats CONDENSED = new Beats(42, 26, 0, 6, 10, 16, 17, 22, 32, false);
    /** 炼成阵的半径（格）：阵里的都挨打。 */
    public static final double RADIUS = 7.5;
    /** 普通敌怪凝时多久（3.5 秒）；Boss 改成同样久的缓慢 IV。 */
    public static final int SEAL_TICKS = 70;
    /** 每只的基础奥秘伤害；Logos 再乘她的法术强度，框架再乘连携倍率和馈赠。 */
    public static final float DAMAGE = 24;
    /** 炼金回流：返还主人这一次实际花掉的魔力的几成。 */
    public static final double REFLUX = .4;

    private final Beats beats;
    private @Nullable Reflux gift;
    private final List<LivingEntity> sealed = new ArrayList<>();
    private int struck;

    private KohakuUltimate(Beats beats) {
        this.beats = beats;
    }

    /** 完整版。 */
    public static KohakuUltimate full() {
        return new KohakuUltimate(FULL);
    }

    /** 缩短版。 */
    public static KohakuUltimate condensed() {
        return new KohakuUltimate(CONDENSED);
    }

    public Beats beats() {
        return beats;
    }

    /** 出手时打中了几只（测试用）。 */
    public int struck() {
        return struck;
    }

    /** 出手时封住的（测试用）。 */
    public List<LivingEntity> sealed() {
        return List.copyOf(sealed);
    }

    @Override
    public int length() {
        return beats.length;
    }

    @Override
    public int impact() {
        return beats.impact;
    }

    // ---------------------------------------------------------------- 每刻

    @Override
    public void start(UltimateCast cast) {
        if (!(cast.companion() instanceof KohakuCompanion k)) return;
        k.magic.cancel();
        k.moves.cancel();
        k.mood.cut();
        // 竖瞳一直亮到擦镜
        k.mood.play("face_focus", beats.wipe);
        long now = cast.level().getGameTime();
        face(k, cast.aim());
        // 完整版：长杖举到胸前往地上一拄；缩短版直接是抬手托着琥珀的那个样子
        k.setMove(beats.chant ? "cast_ward|" + now + "|1|false" : "cutin|" + now + "|1|true");
        if (beats.chant) sound(cast.level(), k.position(), SoundEvents.AMETHYST_BLOCK_CHIME, .9f, 1.5f);
    }

    @Override
    public void tick(UltimateCast cast, int t) {
        if (!(cast.companion() instanceof KohakuCompanion k)) return;
        ServerLevel level = cast.level();
        long now = level.getGameTime();
        Vec3 aim = cast.aim();
        k.getNavigation().stop();
        k.getLookControl().setLookAt(aim.x, aim.y + 1, aim.z, 30, 30);
        if (beats.chant && t < beats.stop) chant(level, k, t);
        if (t == beats.stop) {
            // 「嗡——咔」：真空一样的凝结声
            sound(level, aim, SoundEvents.BEACON_DEACTIVATE, 1.2f, .6f);
            sound(level, aim, SoundEvents.AMETHYST_BLOCK_RESONATE, 1, .7f);
        }
        if (t == beats.collapse) sound(level, cast.owner().position(), SoundEvents.RESPAWN_ANCHOR_CHARGE, .8f, 1.5f);
        if (t == beats.palm) {
            k.setMove("cutin|" + now + "|1|true");
            sound(level, k.position(), SoundEvents.AMETHYST_BLOCK_RESONATE, .7f, 1.4f);
        }
        if (t == beats.touch) {
            // 指尖一碰：清脆到头皮发麻的一声
            sound(level, k.position(), SoundEvents.AMETHYST_BLOCK_BREAK, 1, 1.9f);
            sound(level, k.position(), SoundEvents.AMETHYST_CLUSTER_HIT, .8f, 2f);
        }
        if (t >= beats.shatter && t < beats.impact && (t - beats.shatter) % 3 == 0)
            sound(level, cast.owner().position(), SoundEvents.GLASS_BREAK, .6f, 1.5f + .1f * (t - beats.shatter) / 3f);
        if (t == beats.impact) strike(cast, k, level, aim);
        if (t > beats.impact && t < beats.impact + 12 && (t - beats.impact) % 2 == 0) bloom(level, aim, t - beats.impact);
        if (t == beats.wipe) {
            k.setMove("");
            k.mood.play("face_smug", beats.length - beats.wipe + 30);
            sound(level, k.position(), SoundEvents.AMETHYST_BLOCK_CHIME, 1, 1.2f);
            sound(level, k.position(), SoundEvents.AMETHYST_BLOCK_CHIME, .8f, 1.7f);
        }
    }

    @Override
    public void end(UltimateCast cast, boolean interrupted) {
        if (!(cast.companion() instanceof KohakuCompanion k)) return;
        String move = k.move();
        if (move.startsWith("cutin") || move.startsWith("cast_ward")) k.setMove("");
        if (interrupted) k.mood.cut();
    }

    @Override
    public @Nullable RelayGift gift(UltimateCast cast) {
        gift = new Reflux(cast.stage().index(), paid(cast));
        return gift;
    }

    /** 主人这一次实际花了多少魔力：基准 × 第几段的折扣 × 前面交过来的馈赠的折扣（同 {@code Ultimates} 收的）；创造模式没花。 */
    static int paid(UltimateCast cast) {
        if (cast.owner().hasInfiniteMaterials()) return 0;
        UltimateDef def = cast.def() != null ? cast.def().ultimate() : KohakuCompanion.ULTIMATE;
        double gifts = 1;
        for (RelayGift g : cast.gifts()) gifts *= g.manaFactor(cast.stage());
        return cost(def.mana(), cast.stage().index(), gifts);
    }

    /** 第 {@code stage} 段（0 起）、馈赠折扣 {@code gifts} 时要花的魔力。 */
    public static int cost(int base, int stage, double gifts) {
        return (int) Math.round(base * UltimateChain.manaFactor(stage) * gifts);
    }

    /** 炼金回流返还多少。 */
    public static int refund(int paid) {
        return (int) Math.round(paid * REFLUX);
    }

    /** 最近一次炼金回流返还了多少（测试用）。 */
    public static int lastRefund() {
        return Reflux.last;
    }

    /** 下一段打中被她封着的一共几次（测试用）。 */
    public static int cracks() {
        return Reflux.cracks;
    }

    // ---------------------------------------------------------------- 举杖吟唱

    /** 杖尖亮起来，几道小光环和炼金符号绕着长杖往上升。 */
    private static void chant(ServerLevel level, KohakuCompanion k, int t) {
        Vec3 f = Vec3.directionFromRotation(0, k.getYRot()).multiply(1, 0, 1).normalize(), right = new Vec3(-f.z, 0, f.x);
        Vec3 tip = k.position().add(right.scale(.35)).add(f.scale(.2)).add(0, 1.9, 0);
        if (t % 3 == 0) level.sendParticles(new DustParticleOptions(KohakuCompanion.AMBER, .6f), tip.x, tip.y, tip.z, 2, .06, .06, .06, 0);
        // 一圈符号绕着杖升起来：每刻转一点
        double a = t * .55;
        for (int i = 0; i < 2; i++) {
            double b = a + i * Math.PI, r = .55;
            Vec3 p = k.position().add(right.scale(.35)).add(Math.cos(b) * r, .4 + t * .09, Math.sin(b) * r);
            level.sendParticles(ParticleTypes.ENCHANT, p.x, p.y, p.z, 1, 0, 0, 0, 0);
        }
        if (t == 6) sound(level, tip, SoundEvents.AMETHYST_BLOCK_RESONATE, .6f, 1.8f);
    }

    // ---------------------------------------------------------------- 出手

    /**
     * 星辰花炸开：阵里（{@link #RADIUS} 格）每一只受奥秘伤害；普通敌怪凝时 {@link #SEAL_TICKS} 刻，Boss 改成缓慢 IV 加打断，再推一下。
     * 主人、主人的使魔和宠物、别的同伴她本来就伤不到（{@link CompanionEntity#canHarm}）。
     */
    private void strike(UltimateCast cast, KohakuCompanion k, ServerLevel level, Vec3 center) {
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(center, center).inflate(RADIUS + 1, 4, RADIUS + 1),
                e -> e != k && e.isAlive() && k.canHarm(e) && (e instanceof Enemy || k.hostileTo(e)))) {
            double dx = e.getX() - center.x, dz = e.getZ() - center.z;
            if (Math.sqrt(dx * dx + dz * dz) > RADIUS + e.getBbWidth() / 2) continue;
            cast.hit(e, DAMAGE, (target, amount) -> Effects.damage(k, target, amount, Element.ARCANE));
            struck++;
            if (!e.isAlive()) continue;
            if (e.is(Tags.EntityTypes.BOSSES)) {
                e.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, SEAL_TICKS, 3, false, true, true), k);
                e.stopUsingItem();
                if (e instanceof Mob m) m.getNavigation().stop();
                Vec3 out = new Vec3(dx, 0, dz);
                if (out.lengthSqr() > 1e-4) e.knockback(.8, -out.x, -out.z);
            } else {
                KohakuSeal.seal(e, SEAL_TICKS);
                sealed.add(e);
                if (gift != null) gift.hold(e);
            }
        }
        // 冲击帧：一次，短的两拍（满规格的四拍留给玩家的最后一击）
        ScreenEffects.flash(cast.owner(), ScreenFlash.at(CameraPoint.world(center.add(0, 1.2, 0)))
                .frames(ScreenFlash.Frame.GLOW, ScreenFlash.Frame.SILHOUETTE));
        sound(level, center, SoundEvents.GENERIC_EXPLODE.value(), 1.2f, .55f);
        sound(level, center, SoundEvents.AMETHYST_CLUSTER_BREAK, 1.4f, .5f);
        sound(level, center, SoundEvents.AMETHYST_BLOCK_BREAK, 1, .7f);
        level.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, 0xFFFFE6B0), center.x, center.y + 1.2, center.z, 1, 0, 0, 0, 0);
        level.sendParticles(ParticleTypes.END_ROD, center.x, center.y + 1, center.z, 60, 1.2, .8, 1.2, .35);
        level.sendParticles(new DustParticleOptions(KohakuCompanion.AMBER, 1.6f), center.x, center.y + .6, center.z, 80, RADIUS * .5, .4, RADIUS * .5, 0);
    }

    /** 爆开以后几刻：琥珀碎屑从阵心往外喷。 */
    private static void bloom(ServerLevel level, Vec3 center, int after) {
        double r = 1 + after * .5;
        for (int i = 0; i < 12; i++) {
            double a = i * Math.PI / 6 + after * .3;
            Vec3 p = center.add(Math.cos(a) * r, .3 + Mth.sin(after * .4f) * .2, Math.sin(a) * r);
            level.sendParticles(new DustParticleOptions(i % 3 == 0 ? 0xFFF3D6 : KohakuCompanion.AMBER, 1.3f), p.x, p.y, p.z, 1, .1, .1, .1, 0);
        }
    }

    private static void face(KohakuCompanion k, Vec3 at) {
        double dx = at.x - k.getX(), dz = at.z - k.getZ();
        if (dx * dx + dz * dz < 1e-4) return;
        float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90;
        k.setYRot(yaw);
        k.setYBodyRot(yaw);
        k.setYHeadRot(yaw);
    }

    private static void sound(ServerLevel level, Vec3 at, SoundEvent sound, float volume, float pitch) {
        level.playSound(null, at.x, at.y, at.z, sound, SoundSource.NEUTRAL, volume, pitch);
    }

    // ---------------------------------------------------------------- 炼金回流

    /**
     * 接力馈赠「炼金回流」：交出时立刻返还主人这一次实际花掉的魔力的 {@link #REFLUX}；她封住的目标一直按着（时间到了也不放），
     * 直到下一段演完，保证下一段打得着。下一段打中一只被封着的，琥珀壳先碎开一下。
     */
    static final class Reflux implements RelayGift {
        private final int from, paid;
        private final Set<UUID> held = new HashSet<>(), cracked = new HashSet<>();
        private boolean released;
        /** 下一段打中被封着的一共几次、最近一次返还了多少（测试用）。 */
        static int cracks, last;

        Reflux(int from, int paid) {
            this.from = from;
            this.paid = paid;
        }

        void hold(LivingEntity e) {
            if (released || !held.add(e.getUUID())) return;
            KohakuSeal.hold(e.getUUID());
        }

        @Override
        public void handed(UltimateCast cast) {
            ServerPlayer owner = cast.owner();
            int back = refund(paid);
            last = back;
            if (back <= 0) return;
            LogosNatureBridge.mana(owner, Math.min(LogosNatureBridge.maxMana(owner), LogosNatureBridge.mana(owner) + back));
            owner.level().playSound(null, owner.getX(), owner.getY(), owner.getZ(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, .5f, 1.4f);
        }

        @Override
        public void hit(UltimateCast cast, UltimateHit hit) {
            LivingEntity t = hit.target();
            if (!held.contains(t.getUUID()) || !cracked.add(t.getUUID())) return;
            cracks++;
            if (!(t.level() instanceof ServerLevel level)) return;
            Vec3 c = t.position().add(0, t.getBbHeight() / 2, 0);
            level.sendParticles(new DustParticleOptions(KohakuCompanion.AMBER, 1.4f), c.x, c.y, c.z, 24, t.getBbWidth() / 2, t.getBbHeight() / 3,
                    t.getBbWidth() / 2, 0);
            level.sendParticles(ParticleTypes.END_ROD, c.x, c.y, c.z, 8, .3, .4, .3, .12);
            level.playSound(null, c.x, c.y, c.z, SoundEvents.AMETHYST_CLUSTER_BREAK, SoundSource.NEUTRAL, .8f, 1.3f);
        }

        @Override
        public boolean keep(UltimateChain.Stage done) {
            // 只管交出之后的那一段：它演完就放开按着的
            if (done.index() > from) release();
            return false;
        }

        @Override
        public void cleared(ServerLevel level) {
            release();
        }

        private void release() {
            if (released) return;
            released = true;
            for (UUID id : held) KohakuSeal.release(id);
        }
    }
}
