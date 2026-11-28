package dev.logos.dimension.companion;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.camera.CameraPoint;
import dev.logos.dimension.item.LogosNatureBridge;
import dev.logos.dimension.screenfx.ScreenEffects;
import dev.logos.dimension.screenfx.ScreenFlash;
import dev.logos.dimension.ultimate.RelayGift;
import dev.logos.dimension.ultimate.UltimateCast;
import dev.logos.dimension.ultimate.UltimateChain;
import dev.logos.dimension.ultimate.UltimateHit;
import dev.logos.dimension.ultimate.UltimateScript;
import dev.logos.dimension.ultimate.UltimateTargets;
import dev.logos.dimension.worldtree.Worldtree;
import dev.logos.dimension.worldtree.WorldtreeDraw;
import dev.logos.lang.Element;
import dev.logos.lang.Motion;
import dev.logos.runtime.Effects;
import dev.logos.runtime.PillarBurst;
import dev.logos.runtime.Vfx;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jspecify.annotations.Nullable;

/**
 * 精灵公主的奥义「万分之一 · 世界树祈愿矢」在服务端怎么演（docs/ELF_PRINCESS_COMPANION.md 第 11 节）：
 * 动作、声音、粒子、Logos 的特效，治疗、护盾、伤害和易伤。时刻照 {@link PrincessUltimatePlan} 的时间表；
 * 种子、新苗、主箭、法阵和挂着的光矢由客户端照同一张表画（{@code PrincessUltimateFx}），镜头在 {@code PrincessUltimateShow}。
 * <ul>
 *   <li>莲华光环（{@code ring}）：她身边 {@link PrincessUltimatePlan#HEAL_RADIUS} 格内她照顾的（主人、宠物、使魔、同伴、森林精灵），
 *       加上主人和在场的同伴：回血、清掉中毒/凋零/缓慢/虚弱、上一层 {@link PrincessUltimatePlan#SHIELD_TICKS} 刻的生命护盾。</li>
 *   <li>光矢（{@code launch}–{@code land}）：从法阵下面一支接一支落下，追着圈里的敌人（没有敌人时落在圈里的地上），每支一下生命伤害。</li>
 *   <li>圣箭贯地（{@code impact}）：圈里的敌人吃主要的生命伤害、10 秒生命易伤 II，被震退、震起来；黑白闪一次；金色光柱。</li>
 * </ul>
 * 伤害都走 {@link UltimateCast#hit}，打出去用 Logos 的生命伤害入口（同她的箭：Logos 再乘她的生命法强）。
 */
@EventBusSubscriber(modid = LogosDimension.ID)
public final class PrincessUltimate implements UltimateScript {
    /** 薄荷绿、亮薄荷、暖金（同客户端）。 */
    static final int MINT = ElfPrincessCompanion.MINT, LIGHT = 0xA8F0C6, GOLD = 0xF2D58A;
    /** 清掉的负面状态（规范：中毒、凋零、缓慢、虚弱）。 */
    static final List<Holder<MobEffect>> CLEANSED = List.of(MobEffects.POISON, MobEffects.WITHER, MobEffects.SLOWNESS, MobEffects.WEAKNESS);
    /** Logos 法术护盾挂在吸收上限上的修饰（{@code Effects.shield}）：护盾到期清空时一起摘掉，叶之护才认得出「没有护盾了」。 */
    private static final Identifier LOGOS_SHIELD = Identifier.fromNamespaceAndPath("logos", "shield");

    private final PrincessUltimatePlan.Timeline plan;
    private Vec3 center = Vec3.ZERO, apex = Vec3.ZERO, dir = new Vec3(0, 0, 1), seed = Vec3.ZERO, nock = Vec3.ZERO;
    private float power = 1;
    private int trail;
    private long seedValue;
    /** 光矢追谁（第一支落下时挑好）；没有敌人时是空的，落在地上。 */
    private final List<LivingEntity> rain = new ArrayList<>();
    private final Vec3[] landed = new Vec3[PrincessUltimatePlan.RAIN];

    private PrincessUltimate(PrincessUltimatePlan.Timeline plan) {
        this.plan = plan;
    }

    /** 完整版（单人奥义、连携里的第一位）。 */
    public static PrincessUltimate full() {
        return new PrincessUltimate(PrincessUltimatePlan.FULL);
    }

    /** 缩短版（连携里第二位起）。 */
    public static PrincessUltimate condensed() {
        return new PrincessUltimate(PrincessUltimatePlan.CONDENSED);
    }

    public PrincessUltimatePlan.Timeline plan() {
        return plan;
    }

    @Override
    public int length() {
        return plan.length();
    }

    @Override
    public int impact() {
        return plan.impact();
    }

    @Override
    public void start(UltimateCast cast) {
        ElfPrincessCompanion c = (ElfPrincessCompanion) cast.companion();
        if (c == null) return;
        ServerLevel level = cast.level();
        c.getNavigation().stop();
        c.bow.cancel();
        c.magic.cancel();
        c.stopEating();
        c.setMove("");
        c.arm(true, true);
        c.fought(level.getGameTime());
        power = c.spellPower();
        center = cast.aim();
        dir = PrincessUltimatePlan.toward(c.position(), center, c.getLookAngle());
        apex = PrincessUltimatePlan.apex(center, PrincessUltimatePlan.height(level, center));
        seed = PrincessUltimatePlan.seed(c.position(), dir);
        nock = PrincessUltimatePlan.nock(c.position(), dir);
        trail = -50_000_000 - (c.getId() & 0xFFFFF) * 16;
        seedValue = cast.seed();
        // 完整版先合手祈愿（种子破土、抽枝时），缩短版一上来就是满弓
        if (plan.sprouts()) c.mood.play("act_pray", plan.nock());
        else draw(c, level, level.getGameTime());
    }

    @Override
    public void tick(UltimateCast cast, int t) {
        ElfPrincessCompanion c = (ElfPrincessCompanion) cast.companion();
        if (c == null) return;
        ServerLevel level = cast.level();
        long now = level.getGameTime();
        c.getNavigation().stop();
        c.setDeltaMovement(c.getDeltaMovement().multiply(0, 1, 0));
        face(c, t < plan.nock() ? seed : t < plan.plunge() ? apex : center);
        c.fought(now);

        if (t == plan.seed()) sprout(level);
        if (t == plan.grow()) grow(level);
        if (plan.sprouts() && t >= plan.grow() && t < plan.nock()) vine(level, (t - plan.grow()) / (float) (plan.nock() - plan.grow()));
        if (t == plan.nock() && plan.sprouts()) draw(c, level, now);
        if (t == plan.ring()) ring(cast, c, level);
        if (t == plan.full()) full(c, level, now);
        if (t > plan.full() && t < plan.release() && (t - plan.full()) % 3 == 0) riser(level, c, (t - plan.full()) / (float) (plan.release() - plan.full()));
        if (t == plan.release()) release(c, level);
        if (t == plan.apex()) bloom(level);
        for (int k = 0; k < PrincessUltimatePlan.RAIN; k++) {
            if (t == plan.appear(k)) chime(level, k);
            if (t == plan.launch(k)) launch(cast, level, k);
            if (t > plan.launch(k) && t < plan.land(k)) fly(level, k, (t - plan.launch(k)) / (float) plan.fall());
            if (t == plan.land(k)) land(cast, c, level, k);
        }
        if (t == plan.plunge()) sound(level, apex, SoundEvents.ARROW_SHOOT, 2.5f, .5f);
        if (t == plan.impact()) strike(cast, c, level);
        if (t > plan.impact() && t < plan.wipe() + 4 && t % 2 == 0) afterglow(level, (t - plan.impact()) / (float) (plan.wipe() + 4 - plan.impact()));
        if (t == plan.wipe()) sound(level, center, SoundEvents.AZALEA_LEAVES_BREAK, 1.2f, .7f);
    }

    @Override
    public void end(UltimateCast cast, boolean interrupted) {
        ElfPrincessCompanion c = (ElfPrincessCompanion) cast.companion();
        if (c != null) {
            ItemStack bow = c.getMainHandItem();
            bow.remove(Worldtree.DRAW.get());
            if (c.isUsingItem()) c.stopUsingItem();
            c.mood.cut();
        }
        // 被打断时，还在天上飞的光矢收掉拖尾
        if (interrupted) {
            ServerLevel level = cast.level();
            for (int k = 0; k < PrincessUltimatePlan.RAIN; k++) if (cast.age() > plan.launch(k) && cast.age() < plan.land(k))
                Vfx.trailEnd(level, trail - k, apex, Element.LIFE, false);
        }
    }

    @Override
    public RelayGift gift(UltimateCast cast) {
        return new ForestBlessing(cast.stage().index(), cast.owner().getUUID(), power);
    }

    // ---------------------------------------------------------------- 她的样子

    /** 身子正对着目标，视线看着 {@code at}（拉弓时渲染器按视线把两臂连着弓抬起来）。 */
    private void face(ElfPrincessCompanion c, Vec3 at) {
        float yaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
        c.setYRot(yaw);
        c.yBodyRot = yaw;
        c.setYHeadRot(yaw);
        c.getLookControl().setLookAt(at.x, at.y, at.z, 360, 90);
    }

    /** 搭箭、开始拉：弓上凝出虚影箭（同她平时拉弓），表情认真起来。 */
    private void draw(ElfPrincessCompanion c, ServerLevel level, long now) {
        c.mood.play("face_determined", plan.length() - plan.nock());
        ItemStack bow = c.getMainHandItem();
        bow.set(Worldtree.DRAW.get(), WorldtreeDraw.begin(now, false));
        c.startUsingItem(InteractionHand.MAIN_HAND);
        sound(level, nock, SoundEvents.CROSSBOW_LOADING_END.value(), 1, 1.3f);
    }

    /** 拉满：实箭出现在弓上，弓弦绷紧。 */
    private void full(ElfPrincessCompanion c, ServerLevel level, long now) {
        ItemStack bow = c.getMainHandItem();
        WorldtreeDraw draw = bow.get(Worldtree.DRAW.get());
        if (draw != null && !draw.hasFull()) bow.set(Worldtree.DRAW.get(), draw.withFull(now));
        sound(level, nock, SoundEvents.CROSSBOW_LOADING_MIDDLE.value(), 1, .9f);
    }

    /** 蓄力时一声比一声高（Riser），身上泛起薄荷绿的光屑。 */
    private void riser(ServerLevel level, ElfPrincessCompanion c, float k) {
        sound(level, nock, SoundEvents.AMETHYST_BLOCK_CHIME, 1.2f, .9f + .7f * k);
        level.sendParticles(new DustParticleOptions(MINT, .9f), c.getX(), c.getY() + 1, c.getZ(), 6, .35, .55, .35, 0);
    }

    // ---------------------------------------------------------------- 种子、新苗

    /** 种子破土：一声脆响加一声风铃，地缝里冒出几点绿光。 */
    private void sprout(ServerLevel level) {
        sound(level, seed, SoundEvents.ROOTED_DIRT_BREAK, 1, 1.25f);
        sound(level, seed, SoundEvents.AMETHYST_BLOCK_CHIME, 1.3f, 1.7f);
        // 镜头这时贴着地面拍新苗：只冒几点小光，大颗的粒子会糊满画面
        level.sendParticles(new DustParticleOptions(LIGHT, .5f), seed.x, seed.y + .05, seed.z, 4, .12, .02, .12, 0);
    }

    /** 新苗骤然抽枝：叶子沙沙作响。 */
    private void grow(ServerLevel level) {
        sound(level, seed, SoundEvents.BIG_DRIPLEAF_TILT_UP, 1.2f, 1.3f);
        sound(level, seed, SoundEvents.AZALEA_LEAVES_PLACE, 1.2f, .9f);
    }

    /** 藤蔓螺旋着往上蹿到她手里：长到哪一头沙沙响。 */
    private void vine(ServerLevel level, float k) {
        Vec3 at = seed.lerp(nock, k);
        double a = k * Math.PI * 4;
        double r = .55 * (1 - k) + .08;
        Vec3 side = PrincessUltimatePlan.side(dir);
        Vec3 p = at.add(dir.scale(Math.cos(a) * r)).add(side.scale(Math.sin(a) * r));
        // 藤蔓本身由客户端画；这一镜镜头贴着藤蔓转，不再冒粒子（贴着镜头的粒子会糊满画面）
        if ((int) (k * 11) % 4 == 0) sound(level, p, SoundEvents.AZALEA_LEAVES_BREAK, .7f, 1.2f + k * .4f);
    }

    // ---------------------------------------------------------------- 莲华光环：回血、清负面、护盾

    private void ring(UltimateCast cast, ElfPrincessCompanion c, ServerLevel level) {
        sound(level, c.position(), SoundEvents.AMETHYST_BLOCK_RESONATE, 1.4f, 1.1f);
        sound(level, c.position(), SoundEvents.BEACON_POWER_SELECT, .7f, 1.6f);
        Vec3 from = c.getEyePosition();
        for (LivingEntity e : allies(level, cast.owner(), c.position(), PrincessUltimatePlan.HEAL_RADIUS)) {
            e.heal((float) (PrincessUltimatePlan.HEAL * power));
            for (Holder<MobEffect> bad : CLEANSED) e.removeEffect(bad);
            shield(e, (float) (PrincessUltimatePlan.SHIELD * power), level.getGameTime());
            Vfx.burst(level, e, from, Element.LIFE, Vfx.Burst.HEAL);
            // 她自己身上不冒：这一镜镜头贴着她拍
            if (e != c) PrincessMagic.around(level, ParticleTypes.HAPPY_VILLAGER, e, 6);
        }
        // 一圈光叶从莲华外沿往外荡开
        Vec3 at = c.position();
        for (int i = 0; i < 24; i++) {
            double a = Math.PI * 2 * i / 24;
            level.sendParticles(ParticleTypes.CHERRY_LEAVES, at.x + Math.cos(a) * 2, at.y + .15, at.z + Math.sin(a) * 2, 0, Math.cos(a), .05, Math.sin(a), .5);
        }
    }

    /**
     * 她这一下照顾到的：{@code at} 身边 {@code radius} 格内她照顾的（{@link PrincessMagic#cared}），加上主人和主人在场的同伴（不论多远）。
     */
    static List<LivingEntity> allies(ServerLevel level, ServerPlayer owner, Vec3 at, double radius) {
        Set<LivingEntity> out = new LinkedHashSet<>();
        if (owner.level() == level && owner.isAlive()) out.add(owner);
        for (CompanionDef d : Companions.ALL) {
            CompanionEntity other = Companions.out(owner, d);
            if (other != null && other.level() == level && other.isAlive()) out.add(other);
        }
        out.addAll(level.getEntitiesOfClass(LivingEntity.class, new AABB(at, at).inflate(radius),
                e -> PrincessMagic.cared(e, owner) && e.distanceToSqr(at) <= radius * radius));
        return new ArrayList<>(out);
    }

    // ---------------------------------------------------------------- 放箭、莲华

    private void release(ElfPrincessCompanion c, ServerLevel level) {
        ItemStack bow = c.getMainHandItem();
        bow.remove(Worldtree.DRAW.get());
        if (c.isUsingItem()) c.stopUsingItem();
        sound(level, nock, SoundEvents.ARROW_SHOOT, 1.6f, .75f);
        sound(level, nock, SoundEvents.AMETHYST_BLOCK_RESONATE, 1.2f, 1.6f);
        // 弓弦弹出一圈音爆：垂直于箭的方向的一圈光点
        Vec3 up = apex.subtract(nock).normalize();
        Vec3 a = up.cross(new Vec3(0, 1, 0));
        a = a.lengthSqr() < 1e-4 ? new Vec3(1, 0, 0) : a.normalize();
        Vec3 b = a.cross(up).normalize();
        Vec3 at = nock.add(up.scale(.6));
        for (int i = 0; i < 24; i++) {
            double r = Math.PI * 2 * i / 24;
            Vec3 o = a.scale(Math.cos(r)).add(b.scale(Math.sin(r)));
            level.sendParticles(new DustParticleOptions(LIGHT, 1.1f), at.x + o.x * .5, at.y + o.y * .5, at.z + o.z * .5, 1, 0, 0, 0, 0);
        }
        level.sendParticles(ParticleTypes.CHERRY_LEAVES, at.x, at.y, at.z, 10, .3, .3, .3, .08);
    }

    /** 主箭在敌群正上方停住，绽开莲华法阵：宏大的一声和声。 */
    private void bloom(ServerLevel level) {
        sound(level, apex, SoundEvents.AMETHYST_BLOCK_RESONATE, 3, .7f);
        sound(level, apex, SoundEvents.BEACON_ACTIVATE, 2.5f, 1.4f);
        level.sendParticles(new DustParticleOptions(GOLD, 1.6f), apex.x, apex.y, apex.z, 40, 3, .3, 3, 0);
        level.sendParticles(ParticleTypes.CHERRY_LEAVES, apex.x, apex.y, apex.z, 30, 4, .4, 4, .02);
    }

    /** 一支光矢凝出来：叮。 */
    private void chime(ServerLevel level, int k) {
        Vec3 at = PrincessUltimatePlan.slot(apex, seedValue, k);
        sound(level, at, SoundEvents.AMETHYST_BLOCK_CHIME, 2.2f, 1.1f + .07f * k);
    }

    // ---------------------------------------------------------------- 光矢

    /** 第一支落下时挑好追谁：圈里的敌人从近到远，主目标排最前；不够十二个就轮着追。 */
    private void pick(UltimateCast cast, ElfPrincessCompanion c, ServerLevel level) {
        rain.clear();
        double r = PrincessUltimatePlan.RAIN_RADIUS;
        List<LivingEntity> found = level.getEntitiesOfClass(LivingEntity.class, new AABB(center, center).inflate(r, 6, r),
                e -> UltimateTargets.harmable(cast.owner(), c, e) && horizontal(e.position(), center) <= r);
        found.sort(Comparator.comparingDouble(e -> horizontal(e.position(), center)));
        LivingEntity main = cast.target();
        if (main != null && found.remove(main)) found.addFirst(main);
        rain.addAll(found);
    }

    private void launch(UltimateCast cast, ServerLevel level, int k) {
        if (k == 0 && cast.companion() instanceof ElfPrincessCompanion c) pick(cast, c, level);
        Vec3 from = PrincessUltimatePlan.slot(apex, cast.seed(), k);
        Vec3 to = landing(cast, k);
        Vfx.trail(level, trail - k, from, to.subtract(from).normalize(), Element.LIFE, Motion.FORWARD, .16, Vfx.Guise.PLAIN);
        if ((k & 1) == 0) sound(level, from, SoundEvents.ARROW_SHOOT, 1.6f, 1.3f + .05f * k);
    }

    private void fly(ServerLevel level, int k, float u) {
        Vec3 from = PrincessUltimatePlan.slot(apex, seedValue, k);
        Vec3 to = landed[k] != null ? landed[k] : center;
        Vec3 at = from.lerp(to, u);
        Vfx.trail(level, trail - k, at, to.subtract(from).normalize(), Element.LIFE, Motion.FORWARD, .16, Vfx.Guise.PLAIN);
    }

    /** 第 {@code k} 支追谁、落在哪：轮着追圈里的敌人（胸口）；一个都没有时落在圈里的地上。 */
    private Vec3 landing(UltimateCast cast, int k) {
        LivingEntity t = target(k);
        Vec3 at = t != null ? t.position().add(0, t.getBbHeight() * .5, 0) : PrincessUltimatePlan.ground(center, cast.seed(), k);
        landed[k] = at;
        return at;
    }

    private @Nullable LivingEntity target(int k) {
        if (rain.isEmpty()) return null;
        LivingEntity t = rain.get(k % rain.size());
        return t.isAlive() ? t : null;
    }

    private void land(UltimateCast cast, ElfPrincessCompanion c, ServerLevel level, int k) {
        LivingEntity t = target(k);
        Vec3 at = t != null ? t.position().add(0, t.getBbHeight() * .5, 0) : landed[k] != null ? landed[k] : center;
        Vfx.trailEnd(level, trail - k, at, Element.LIFE, true);
        if (t != null) cast.hit(t, (float) PrincessUltimatePlan.RAIN_DAMAGE, life(c));
        else Vfx.burst(level, at.add(0, .2, 0), Element.LIFE, 12);
        level.sendParticles(ParticleTypes.CHERRY_LEAVES, at.x, at.y, at.z, 6, .3, .2, .3, .05);
        sound(level, at, SoundEvents.ARROW_HIT, 1.1f, .8f + .03f * k);
        if ((k & 1) == 1) sound(level, at, SoundEvents.AMETHYST_BLOCK_BREAK, 1.2f, .7f);
    }

    // ---------------------------------------------------------------- 圣箭贯地

    private void strike(UltimateCast cast, ElfPrincessCompanion c, ServerLevel level) {
        ServerPlayer owner = cast.owner();
        // 每段过场只闪这一次（出手那一拍）
        if (cast.filmed()) ScreenEffects.flash(owner, ScreenFlash.at(CameraPoint.world(center.add(0, 1, 0))));
        PillarBurst.play(level, center, .9f);
        Vfx.burst(level, center.add(0, .5, 0), Element.LIFE, 60);
        level.playSound(null, center.x, center.y, center.z, SoundEvents.GENERIC_EXPLODE, SoundSource.NEUTRAL, 2.2f, .6f);
        sound(level, center, SoundEvents.AMETHYST_BLOCK_RESONATE, 2.5f, .5f);
        double r = PrincessUltimatePlan.IMPACT_RADIUS;
        var vulnerability = LogosNatureBridge.lifeVulnerability();
        for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(center, center).inflate(r, 4, r),
                e -> UltimateTargets.harmable(owner, c, e) && horizontal(e.position(), center) <= r)) {
            cast.hit(e, (float) PrincessUltimatePlan.IMPACT_DAMAGE, life(c));
            if (!e.isAlive()) continue;
            vulnerability.ifPresent(v -> e.addEffect(new MobEffectInstance(v, PrincessUltimatePlan.VULNERABILITY_TICKS,
                    PrincessUltimatePlan.VULNERABILITY_LEVEL), c));
            Vec3 away = new Vec3(e.getX() - center.x, 0, e.getZ() - center.z);
            away = away.lengthSqr() < 1e-4 ? dir : away.normalize();
            double resist = 1 - Math.clamp(e.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE), 0, 1);
            e.setDeltaMovement(e.getDeltaMovement().add(away.scale(PrincessUltimatePlan.KNOCK * resist)).add(0, PrincessUltimatePlan.LIFT * resist, 0));
            e.hurtMarked = true;
        }
        // 沿地面荡开的根系、花瓣冲击环
        for (int i = 0; i < 64; i++) {
            double a = Math.PI * 2 * i / 64;
            level.sendParticles(ParticleTypes.CHERRY_LEAVES, center.x + Math.cos(a), center.y + .2, center.z + Math.sin(a), 0, Math.cos(a), .08, Math.sin(a), .8);
            if ((i & 1) == 0) level.sendParticles(new DustParticleOptions(GOLD, 1.5f), center.x + Math.cos(a) * 1.5, center.y + .3, center.z + Math.sin(a) * 1.5,
                    0, Math.cos(a), 0, Math.sin(a), .6);
        }
    }

    /** 花海余韵：圈里柔柔地冒着花叶和绿光，越来越少。 */
    private void afterglow(ServerLevel level, float k) {
        int n = Math.max(1, Math.round(10 * (1 - k)));
        double r = PrincessUltimatePlan.IMPACT_RADIUS * .7;
        level.sendParticles(ParticleTypes.CHERRY_LEAVES, center.x, center.y + .4, center.z, n, r, .2, r, .01);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, center.x, center.y + .2, center.z, Math.max(1, n / 2), r, .1, r, 0);
        level.sendParticles(new DustParticleOptions(MINT, .8f), center.x, center.y + .15, center.z, n, r, .05, r, 0);
    }

    // ---------------------------------------------------------------- 工具

    /** 生命伤害：Logos 的入口（同她的箭），清掉无敌帧；打掉了血才算打中。 */
    static UltimateCast.Dealer life(LivingEntity caster) {
        return (t, amount) -> {
            float before = t.getHealth() + t.getAbsorptionAmount();
            LogosNatureBridge.damage(caster, t, amount);
            return !t.isAlive() || t.getHealth() + t.getAbsorptionAmount() < before;
        };
    }

    private static double horizontal(Vec3 a, Vec3 b) {
        return Math.hypot(a.x - b.x, a.z - b.z);
    }

    private static void sound(ServerLevel level, Vec3 at, SoundEvent sound, float volume, float pitch) {
        level.playSound(null, at.x, at.y, at.z, sound, SoundSource.NEUTRAL, volume, pitch);
    }

    // ---------------------------------------------------------------- 护盾到期

    /** 一层带期限的生命护盾：到期时还剩多少就收走多少（别的护盾留着）。 */
    private record Shield(LivingEntity entity, long until, float amount) {}

    private static final Map<UUID, Shield> SHIELDS = new HashMap<>();

    /** 给 {@code e} 一层 {@code amount} 的生命护盾（Logos 法术护盾），{@link PrincessUltimatePlan#SHIELD_TICKS} 刻后到期。 */
    static void shield(LivingEntity e, float amount, long now) {
        Effects.shield(e, amount);
        Shield old = SHIELDS.get(e.getUUID());
        float total = amount + (old != null && old.until > now ? old.amount : 0);
        SHIELDS.put(e.getUUID(), new Shield(e, now + PrincessUltimatePlan.SHIELD_TICKS, total));
    }

    /** 测试用：{@code e} 身上还挂着她的护盾。 */
    public static boolean shielded(LivingEntity e) {
        return SHIELDS.containsKey(e.getUUID());
    }

    @SubscribeEvent
    static void expire(ServerTickEvent.Post event) {
        if (SHIELDS.isEmpty()) return;
        for (Iterator<Shield> it = SHIELDS.values().iterator(); it.hasNext(); ) {
            Shield s = it.next();
            LivingEntity e = s.entity;
            if (e.isRemoved() || !e.isAlive()) {
                it.remove();
                continue;
            }
            if (e.level().getGameTime() < s.until) continue;
            it.remove();
            float left = Math.max(0, e.getAbsorptionAmount() - s.amount);
            e.setAbsorptionAmount(left);
            if (left <= 0) {
                var max = e.getAttribute(Attributes.MAX_ABSORPTION);
                if (max != null) max.removeModifier(LOGOS_SHIELD);
            }
        }
    }

    @SubscribeEvent
    static void stopped(ServerStoppedEvent event) {
        SHIELDS.clear();
    }

    // ---------------------------------------------------------------- 接力馈赠：森之加护

    /**
     * 森之加护（规范 4.2 第 2 条）：紧接着的下一段威力 × {@link PrincessUltimatePlan#BLESSING_POWER}、耗魔 × {@link PrincessUltimatePlan#BLESSING_MANA}；
     * 一直留到最后一击，最后一击第一下打中时，在打中的地方再荡开一次全队治愈脉冲（她交出馈赠时的法强）。
     */
    public static final class ForestBlessing implements RelayGift {
        private final int from;
        private final UUID owner;
        private final float power;
        private boolean pulsed;

        public ForestBlessing(int from, UUID owner, float power) {
            this.from = from;
            this.owner = owner;
            this.power = power;
        }

        /** 是不是紧接着她的那一段（她排最后时就是玩家的最后一击）。 */
        public boolean next(UltimateChain.Stage stage) {
            return stage.index() == from + 1;
        }

        @Override
        public double manaFactor(UltimateChain.Stage stage) {
            return next(stage) ? PrincessUltimatePlan.BLESSING_MANA : 1;
        }

        @Override
        public double power(UltimateChain.Stage stage) {
            return next(stage) ? PrincessUltimatePlan.BLESSING_POWER : 1;
        }

        @Override
        public boolean keep(UltimateChain.Stage done) {
            return true;
        }

        /** 治愈脉冲荡过了没有（测试用）。 */
        public boolean pulsed() {
            return pulsed;
        }

        @Override
        public void hit(UltimateCast cast, UltimateHit hit) {
            if (pulsed || !cast.stage().finisher() || !cast.owner().getUUID().equals(owner)) return;
            pulsed = true;
            pulse(cast.level(), cast.owner(), hit.target().position(), power);
        }
    }

    /** 全队治愈脉冲：最后一击打中的地方荡开一圈光叶，主人、在场的同伴和那一圈里她照顾的都回一口血。 */
    static void pulse(ServerLevel level, ServerPlayer owner, Vec3 at, float power) {
        pulses++;
        for (LivingEntity e : allies(level, owner, at, PrincessUltimatePlan.PULSE_RADIUS)) {
            e.heal((float) (PrincessUltimatePlan.PULSE_HEAL * power));
            Vfx.burst(level, e, at, Element.LIFE, Vfx.Burst.HEAL);
        }
        Vfx.burst(level, at.add(0, .5, 0), Element.LIFE, 40);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.NEUTRAL, 2, 1.2f);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.BEACON_POWER_SELECT, SoundSource.NEUTRAL, 1, 1.5f);
        ParticleOptions leaf = ParticleTypes.CHERRY_LEAVES;
        for (int i = 0; i < 48; i++) {
            double a = Math.PI * 2 * i / 48;
            level.sendParticles(leaf, at.x + Math.cos(a), at.y + .3, at.z + Math.sin(a), 0, Math.cos(a), .06, Math.sin(a), .7);
            if ((i & 1) == 0) level.sendParticles(new DustParticleOptions(LIGHT, 1.2f), at.x + Math.cos(a) * 1.2, at.y + .4, at.z + Math.sin(a) * 1.2,
                    0, Math.cos(a), 0, Math.sin(a), .5);
        }
    }

    /** 测试用：治愈脉冲荡过几次。 */
    private static int pulses;

    public static int pulses() {
        return pulses;
    }
}
