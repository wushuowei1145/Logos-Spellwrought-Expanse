package dev.logos.dimension.companion;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.dissolve.DissolveShape;
import dev.logos.dimension.dissolve.Dissolving;
import dev.logos.dimension.item.WorldtreeBowItem;
import dev.logos.dimension.ultimate.UltimateDef;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 精灵公主，玩家的同伴（docs/ELF_PRINCESS_COMPANION.md）。每位同伴都有的那些在 {@link CompanionEntity}；这里是她自己的：
 * <ul>
 *   <li>世界树之弓（{@link PrincessBow}）：敌人远时锁定追踪箭，近身时横着拉弓散射、往后跳。在她手里蓄力更快。</li>
 *   <li>生命涌动（{@link PrincessMagic}）：治疗、护盾、给主人缠上生命之力、荆棘缠绕困住强敌、万分之一。随契印解锁。</li>
 *   <li>伤害和治疗都按她自己的法术强度算（{@link #spellPower}）：她自己的 + 羁绊 + 继承主人的，随主人的属性和契印变。</li>
 *   <li>不会死：撑不住时世界树把她收回去休养（{@link #withdrawing}，代替倒下），一朵荷花把她包起来沉进地里。</li>
 * </ul>
 * 召唤和收回用袋装世界树（{@link BaggedWorldTreeItem}）。决策在 {@link PrincessMind}，表情和小动作在 {@link PrincessMood}，对话在 {@link PrincessTalk}。
 */
public class ElfPrincessCompanion extends CompanionEntity {
    public static final String ID = "elf_princess";
    public static final Identifier ASSET = LogosDimension.id("elf_princess_companion");
    /**
     * 她的数：弓手，比白梅单薄一点（生命 32、护甲 2）；她的本事在法术强度上，自己 0.7 起、每枚契印 +0.05，羁绊每段 +0.05，
     * 继承比例同白梅（三成起、每枚 +8%、第九枚全部）。主人法强为 1、没盖契印时她正好是 1.0。
     */
    public static final CompanionDef.Growth GROWTH = new CompanionDef.Growth(32, 2, 0, 4, .7, 4, .5, .25, .05, 4, .5, .05, .3, .08, 1, 9);
    /** 撤退以后在世界树里歇多久（5 分钟，同白梅）；契印一共几枚（叶之契）。 */
    public static final int REST_TICKS = 6000, MAX_SEALS = 9;
    /**
     * 奥义「万分之一 · 世界树祈愿矢」（docs/ELF_PRINCESS_COMPANION.md 第 11 节）：冷却 40 秒、基准耗魔 300。
     * 单人和连携第一位演完整版（104 刻），连携里第二位起演缩短版（44 刻）；馈赠是森之加护。
     */
    public static final UltimateDef ULTIMATE = new UltimateDef(800, 300, PrincessUltimate::full, PrincessUltimate::condensed);
    /** 入场动画多长、撤退动画多长（刻），与模型一致。 */
    static final int SUMMON_TICKS = 84, WITHDRAW_TICKS = 72;
    /** 入场动画的时间标记（刻）：开花、花瓣飞散。 */
    static final int SUMMON_BLOOM = 34, SUMMON_SCATTER = 48;
    /** 撤退动画的时间标记（刻）：花苞合拢、沉下去。 */
    static final int WITHDRAW_CLOSED = 50, WITHDRAW_SINK = 52;
    /** 打完多久才把弓收起来（刻）。 */
    static final int SHEATHE_AFTER = 120;
    /** 她的颜色：薄荷绿（粒子、消散）。 */
    static final int MINT = 0x8CD9B0;

    final PrincessBow bow = new PrincessBow(this);
    final PrincessMagic magic = new PrincessMagic(this);
    /** 她此刻的法术强度（倍数）和三部分：她自己的、羁绊、继承。 */
    private float spellPower = 1;
    private CompanionStats.Part power = new CompanionStats.Part(1, 0, 0);
    private boolean armed;
    private long lastFight = -10_000, summonAt = -1, withdrawAt = -1;

    public ElfPrincessCompanion(EntityType<? extends ElfPrincessCompanion> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return PathfinderMob.createMobAttributes().add(Attributes.MAX_HEALTH, GROWTH.health()).add(Attributes.MOVEMENT_SPEED, .3)
                .add(Attributes.FOLLOW_RANGE, 48).add(Attributes.ARMOR, GROWTH.armor()).add(Attributes.STEP_HEIGHT, 1).add(Attributes.ATTACK_DAMAGE, 1);
    }

    @Override
    public CompanionDef def() {
        return Companions.PRINCESS_DEF;
    }

    @Override
    protected CompanionMood createMood() {
        return new PrincessMood(this);
    }

    @Override
    protected CompanionMind createMind() {
        return new PrincessMind(this);
    }

    @Override
    public @Nullable PrincessMind mind() {
        return (PrincessMind) super.mind();
    }

    @Override
    protected int summonTicks() {
        return SUMMON_TICKS;
    }

    @Override
    public Identifier npcAnimationAsset() {
        return ASSET;
    }

    // ---------------------------------------------------------------- 法术强度

    /** 她的法术强度（倍数）：箭的生命伤害、治疗、护盾、荆棘都乘它（Logos 的法术按她身上的 {@code logos:spell_power} 自己乘）。 */
    public float spellPower() {
        return spellPower;
    }

    public CompanionStats.Part powerParts() {
        return power;
    }

    /** 主人的长期法术强度（不算药水）：通用的法强乘生命法强。 */
    static double ownerPower(Player owner) {
        return CompanionStats.spellPower(owner, "spell_power", "life_spell_power");
    }

    @Override
    protected void statsApplied(Player owner, int seals, int stage) {
        power = CompanionStats.power(GROWTH, ownerPower(owner), seals, stage);
        spellPower = (float) power.total();
        Optional<Holder.Reference<Attribute>> attr = BuiltInRegistries.ATTRIBUTE.get(Identifier.fromNamespaceAndPath("logos", "spell_power"));
        // 属性的基础值是 1：继承那一份修饰补上差
        attr.ifPresent(a -> CompanionStats.set(this, a, spellPower - 1));
    }

    // ---------------------------------------------------------------- 弓

    /** 弓拿在手里没有（打起来才拿出来，打完歇一会儿收起来）。 */
    public boolean armed() {
        return armed;
    }

    /** 拿出弓（一小阵叶子）或收起来。正在吃东西、撤退时不换手。 */
    void arm(boolean on) {
        arm(on, false);
    }

    /** 同上；{@code quiet} 时不冒叶子（奥义开场镜头贴着地面拍新苗，飘过的叶子会糊满画面）。 */
    void arm(boolean on, boolean quiet) {
        if (armed == on || eating() || withdrawing()) return;
        armed = on;
        setItemSlot(EquipmentSlot.MAINHAND, restingMainHand());
        if (on && !quiet && level() instanceof ServerLevel level) {
            Vec3 hand = getEyePosition().add(Vec3.directionFromRotation(0, yBodyRot - 40).scale(.35)).add(0, -.35, 0);
            level.sendParticles(ParticleTypes.CHERRY_LEAVES, hand.x, hand.y, hand.z, 6, .15, .2, .15, .02);
            level.sendParticles(new DustParticleOptions(MINT, .9f), hand.x, hand.y, hand.z, 8, .15, .25, .15, 0);
            level.playSound(null, getX(), getY(), getZ(), SoundEvents.AZALEA_LEAVES_PLACE, SoundSource.NEUTRAL, .6f, 1.3f);
        }
    }

    /** 记一次「在打」：拿出弓；打完 {@link #SHEATHE_AFTER} 刻后收起来。 */
    void fought(long now) {
        lastFight = now;
        arm(true);
    }

    @Override
    protected ItemStack restingMainHand() {
        return armed ? new ItemStack(WorldtreeBowItem.ITEM.get()) : ItemStack.EMPTY;
    }

    @Override
    public boolean npcAiming() {
        return isUsingItem() || bow.drawing();
    }

    @Override
    protected boolean busyHands() {
        return bow.drawing() || magic.casting();
    }

    // ---------------------------------------------------------------- 每刻

    @Override
    void startSummon() {
        super.startSummon();
        summonAt = level().getGameTime();
    }

    @Override
    protected boolean tickSpecial(ServerLevel level, long now) {
        if (withdrawAt < 0) return false;
        tickWithdraw(level, now);
        return true;
    }

    @Override
    protected void tickActions(ServerLevel level, long now, @Nullable ServerPlayer p) {
        if (summonAt >= 0) summonEffects(level, now - summonAt);
        bow.tick(level, now);
        magic.tick(level, now, p);
        if (armed && !bow.drawing() && !magic.casting() && now - lastFight > SHEATHE_AFTER) arm(false);
    }

    /** 入场时配合动画的粒子和声音：花苞拱出地面、开花、花瓣飞散。 */
    private void summonEffects(ServerLevel level, long t) {
        Vec3 at = position();
        if (t == 0) {
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, at.x, at.y + .2, at.z, 14, .5, .1, .5, 0);
            level.playSound(null, at.x, at.y, at.z, SoundEvents.ROOTED_DIRT_BREAK, SoundSource.NEUTRAL, .8f, .9f);
            level.playSound(null, at.x, at.y, at.z, SoundEvents.BIG_DRIPLEAF_TILT_UP, SoundSource.NEUTRAL, .8f, .8f);
        } else if (t == SUMMON_BLOOM) {
            level.playSound(null, at.x, at.y, at.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.NEUTRAL, 1.2f, 1.1f);
            level.playSound(null, at.x, at.y, at.z, SoundEvents.AZALEA_LEAVES_BREAK, SoundSource.NEUTRAL, 1, .8f);
        } else if (t == SUMMON_SCATTER) {
            level.sendParticles(ParticleTypes.CHERRY_LEAVES, at.x, at.y + 1.2, at.z, 50, 1.2, .9, 1.2, .06);
            level.sendParticles(new DustParticleOptions(MINT, 1.3f), at.x, at.y + 1, at.z, 24, .8, .8, .8, 0);
            level.playSound(null, at.x, at.y, at.z, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.NEUTRAL, .8f, 1.5f);
        } else if (t > SUMMON_TICKS) {
            summonAt = -1;
        }
    }

    @Override
    protected void clientTick() {
        // 额冠的生命晶偶尔飘起一点淡绿的光
        if (tickCount % 30 == 0 && !isInvisible() && !hiddenForSummon()) {
            Vec3 at = getEyePosition().add(0, .25, 0);
            level().addParticle(new DustParticleOptions(MINT, .35f), at.x + (random.nextDouble() - .5) * .3, at.y, at.z + (random.nextDouble() - .5) * .3,
                    0, .02, 0);
        }
    }

    // ---------------------------------------------------------------- 撤退（代替倒下）

    /** 撤退中（世界树正把她收回去）：打不到、不动，播完就回到袋装世界树里歇着。 */
    public boolean withdrawing() {
        return withdrawAt >= 0;
    }

    /**
     * 开始撤退（docs/ELF_PRINCESS_COMPANION.md 第 6 节）：致命的一下不死，世界树把她收回去休养。由 {@link Companions} 的死亡事件调用
     * （取消死亡，同不死图腾那一步之后），命令、测试也用。
     */
    void startWithdraw(ServerLevel level) {
        if (withdrawing()) return;
        withdrawAt = level.getGameTime();
        setHealth(1);
        bow.cancel();
        magic.cancel();
        stopEating();
        armed = false;
        setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        setMove("");
        getNavigation().stop();
        setDeltaMovement(Vec3.ZERO);
        mood.play("withdraw", WITHDRAW_TICKS + 4);
        level.playSound(null, getX(), getY(), getZ(), SoundEvents.PLAYER_HURT, SoundSource.NEUTRAL, .6f, 1.5f);
    }

    private void tickWithdraw(ServerLevel level, long now) {
        getNavigation().stop();
        setDeltaMovement(getDeltaMovement().multiply(0, 1, 0));
        long t = now - withdrawAt;
        Vec3 at = position();
        if (t == WITHDRAW_CLOSED) {
            level.playSound(null, at.x, at.y, at.z, SoundEvents.AZALEA_LEAVES_PLACE, SoundSource.NEUTRAL, 1, .7f);
            level.sendParticles(new DustParticleOptions(MINT, 1.1f), at.x, at.y + 1.4, at.z, 18, .5, .6, .5, 0);
        }
        if (t == WITHDRAW_SINK) {
            level.playSound(null, at.x, at.y, at.z, SoundEvents.ROOTED_DIRT_PLACE, SoundSource.NEUTRAL, 1, .7f);
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, at.x, at.y + .2, at.z, 16, .6, .1, .6, 0);
        }
        if (t >= WITHDRAW_TICKS) {
            ServerPlayer p = ownerPlayer();
            if (p != null) {
                Companions.fell(p, this);
                BaggedWorldTreeItem.resting(p, def().restTicks());
            }
            withdrawn++;
            discard();
        }
    }

    /** 测试用：撤退回去过几次。 */
    private static int withdrawn;

    public static int withdrawn() {
        return withdrawn;
    }

    /** 测试用：放过几轮锁定、几轮散射、射出过几支追踪箭。 */
    public static int volleys() {
        return PrincessBow.volleys;
    }

    public static int fans() {
        return PrincessBow.fans;
    }

    public static int homingShot() {
        return PrincessBow.homing;
    }

    /** 测试用：治过几次、给过几次护盾、缠过几次生命之力、放过几次荆棘、万分之一用过几次。 */
    public static int mends() {
        return PrincessMagic.mends;
    }

    public static int shields() {
        return PrincessMagic.shields;
    }

    public static int blesses() {
        return PrincessMagic.blesses;
    }

    public static int snares() {
        return PrincessMagic.snares;
    }

    public static int chances() {
        return PrincessMagic.chances;
    }

    /** 测试用：常青之息给同伴回过几次血。 */
    public static int breaths() {
        return PrincessMagic.breaths;
    }

    @Override
    protected boolean immune() {
        return withdrawing();
    }

    @Override
    public boolean isPushable() {
        return !withdrawing() && super.isPushable();
    }

    @Override
    protected void onDie() {
        bow.cancel();
        magic.cancel();
    }

    /** 整个人由 misc 动画摆（睡着、撤退）：客户端这时不叠加视线。 */
    @Override
    public boolean curled() {
        String m = misc();
        return "sleep".equals(m) || "withdraw".equals(m);
    }

    // ---------------------------------------------------------------- 入场、收回、闪回的样子

    @Override
    protected void appearBurst(ServerLevel level, Vec3 at) {
        // 现身由入场动画和 summonEffects 表现
    }

    @Override
    protected void onLeave() {
        bow.cancel();
        magic.cancel();
    }

    @Override
    protected void dissolveAway(int ticks) {
        Dissolving.entity(this, DissolveShape.parse("down"), ticks, MINT | Dissolving.PETALS);
    }

    @Override
    public void puff(ServerLevel level, Vec3 at) {
        level.sendParticles(ParticleTypes.CHERRY_LEAVES, at.x, at.y, at.z, 12, .3, .45, .3, .03);
        level.sendParticles(new DustParticleOptions(MINT, 1.2f), at.x, at.y, at.z, 14, .25, .4, .25, 0);
    }

    @Override
    public void blinkSound(ServerLevel level, Vec3 at) {
        level.playSound(null, at.x, at.y, at.z, SoundEvents.AZALEA_LEAVES_BREAK, SoundSource.NEUTRAL, .9f, 1.3f);
        level.playSound(null, at.x, at.y, at.z, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.NEUTRAL, .6f, 1.5f);
    }

    // ---------------------------------------------------------------- 信物

    @Override
    protected boolean blankToken(ItemStack held) {
        return BaggedWorldTreeItem.isBlank(held);
    }

    /** 盖了一枚叶之契：她提着裙子行个礼，身边冒出一阵叶子和荷花瓣。 */
    @Override
    protected void sealed(ServerPlayer sp, int seals) {
        mood.act("act_curtsy");
        CompanionLines.say(this, sp, "seal", 60);
        if (level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER, getX(), getY() + .8, getZ(), 20, .5, .6, .5, 0);
            level.sendParticles(ParticleTypes.CHERRY_LEAVES, getX(), getY() + 1.2, getZ(), 24, .5, .5, .5, .03);
            level.playSound(null, getX(), getY(), getZ(), SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.NEUTRAL, 1, 1.4f);
        }
    }

    /** 袋装世界树丢了：拿一株树苗来，她用一捧土和布袋子再做一个，写上你的名字。 */
    @Override
    protected boolean remake(ServerPlayer sp, ItemStack held) {
        if (!held.is(ItemTags.SAPLINGS) || BaggedWorldTreeItem.carries(sp)) return false;
        if (!sp.getAbilities().instabuild) held.shrink(1);
        ItemStack bag = BaggedWorldTreeItem.bound(sp);
        if (!sp.getInventory().add(bag)) sp.drop(bag, false);
        mood.act("act_head_tilt");
        CompanionLines.say(this, sp, "remake", 60);
        return true;
    }

    // ---------------------------------------------------------------- 说话

    @Override
    public CompanionSheet talkSheet(ServerPlayer p) {
        return PrincessTalk.sheet(this, p);
    }

    @Override
    public List<CompanionTalk.Line> talkGreeting(ServerPlayer p) {
        return PrincessTalk.greeting(this, p);
    }

    @Override
    public CompanionTalk.Reply talkPick(ServerPlayer p, String option) {
        return PrincessTalk.pick(this, p, option);
    }

    @Override
    protected void talkFinished(ServerPlayer p, boolean bye) {
        PrincessTalk.ended(this, p, bye);
    }

    @Override
    public void talkInterrupted(ServerPlayer p) {
        PrincessTalk.interrupted(this, p);
    }

    @Override
    public CompanionTalk.@Nullable Line talkBack(ServerPlayer p) {
        return PrincessTalk.back(this, p);
    }

    /** 角色面板里展示的那一位（客户端临时造的）：拿着弓，表情 {@code face}。 */
    public void showcase(String face) {
        entityData.set(MISC, face);
        entityData.set(MOVE, "");
        armed = true;
        setItemSlot(EquipmentSlot.MAINHAND, restingMainHand());
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        boolean hurt = super.hurtServer(level, source, amount);
        if (hurt) fought(level.getGameTime());
        return hurt;
    }
}
