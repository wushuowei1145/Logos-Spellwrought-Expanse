package dev.logos.dimension.companion;

import com.geckolib.animatable.instance.AnimatableInstanceCache;
import com.geckolib.animatable.manager.AnimatableManager;
import com.geckolib.util.GeckoLibUtil;
import dev.logos.dimension.npc.LogosMagicBridge;
import dev.logos.dimension.npc.NpcAnimated;
import dev.logos.dimension.npc.NpcAnimations;
import dev.logos.dimension.npc.NpcDeathPose;
import dev.logos.runtime.LastingCompanion;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 同伴的基类（docs/COMPANIONS.md 第 4 节）：每位同伴都有的那些——主人、契、数值、表情和台词、对话、吃东西、摸头、盖契印、召唤和收回、
 * 闪回和防困（在 {@link CompanionMind} 里）。她自己的打法、生活和过场由子类加。
 *
 * <p>对 Logos 来说她是主人的长期同伴（{@link LastingCompanion}）：一人一个，没有时限，跟随和收回都由她自己做。
 * 动画走附属的 NPC 驱动：走、跑由主控制器按状态播；入场、表情、小动作、睡觉、吃东西由 {@code misc} 播（{@link #setMisc}）；
 * 战斗动作由 {@code combat} 播（{@link #setMove}）。
 */
public abstract class CompanionEntity extends PathfinderMob implements NpcAnimated, LastingCompanion, CompanionTalk.Talker {
    /** 生命上限到这么多算强敌（表情、反应、她的强敌技能都看它）。 */
    public static final float STRONG = 40;
    /** 吃东西（eat，2.4 秒）：三口、咽下去。与各位模型的 eat 动画一致。 */
    static final int EAT_TICKS = 48, EAT_SWALLOW = 35;
    static final int[] EAT_BITES = {12, 20, 28};
    /** 收回时先挥手这么多刻，再散去。 */
    static final int LEAVE_WAVE = 20, LEAVE_DISSOLVE = 12;
    /** 入场最初几刻客户端不画（misc 控制器有 2 刻过渡，免得先看到原尺寸的她）。 */
    static final int HIDDEN_TICKS = 2;

    protected static final EntityDataAccessor<String> MISC = SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.STRING);
    protected static final EntityDataAccessor<String> MOVE = SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.STRING);

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    final CompanionMood mood;
    private @Nullable CompanionMind mind;
    protected @Nullable UUID owner;
    /** 她的攻击（{@link CompanionStats#apply} 算好记下来）：白梅一发狐火弹的基础伤害，各位按自己的打法用。 */
    private float attack;
    /** 主人盖了几枚契印（{@link CompanionStats#apply} 记下来）。 */
    private int seals;
    private boolean staying;
    /** 正在和她说话的人（对话界面开着，{@link CompanionTalk}）。 */
    private @Nullable UUID talkWith;
    private long leavingAt = -1;
    protected int sinceHurt = 1000;
    private float deathYaw = Float.NaN;
    // ---- 吃东西
    private long eatStart = -1;
    private ItemStack eating = ItemStack.EMPTY;
    private boolean eatShared;
    // ---- 客户端
    private String moveFrom = "";
    private @Nullable CombatMove clientMove;

    protected CompanionEntity(EntityType<? extends CompanionEntity> type, Level level) {
        super(type, level);
        setPersistenceRequired();
        xpReward = 0;
        mood = createMood();
    }

    /** 她是哪一位。 */
    public abstract CompanionDef def();

    /** 她现在穿的那一套的 ID（客户端也读得到）；没有装束可换的同伴是空字符串。连携切入照这个画她。 */
    public String outfitId() {
        return "";
    }

    protected abstract CompanionMood createMood();

    protected abstract CompanionMind createMind();

    /** 入场动画多长（刻），与她的 summon 动画一致。 */
    protected abstract int summonTicks();

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(MISC, "");
        builder.define(MOVE, "");
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this) {
            @Override
            public boolean canUse() {
                return canFloat() && super.canUse();
            }
        });
        mind = createMind();
        goalSelector.addGoal(1, mind);
    }

    /** 掉进水里往上浮（白梅泡温泉时不浮）。 */
    protected boolean canFloat() {
        return true;
    }

    // ---------------------------------------------------------------- 主人与契

    /** 认主并按主人算一次数值。 */
    void bind(ServerPlayer player) {
        owner = player.getUUID();
        CompanionStats.apply(this, player, bond(player).seals());
        setHealth(getMaxHealth());
    }

    public @Nullable UUID ownerId() {
        return owner;
    }

    /** 主人：在线、活着、和她在同一个维度时才有。 */
    public @Nullable ServerPlayer ownerPlayer() {
        if (owner == null || !(level() instanceof ServerLevel level)) return null;
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(owner);
        return p != null && p.isAlive() && p.level() == level ? p : null;
    }

    /** 主人身上和她的契。 */
    public Companions.Bond bond(Player p) {
        return p.getData(def().bond().get());
    }

    void setBond(Player p, Companions.Bond b) {
        p.setData(def().bond().get(), b);
    }

    public CompanionPouch pouch(Player p) {
        return p.getData(def().pouch().get());
    }

    /** 她是在外面跟着主人的同伴（白梅另有在封印之间等人、立契的样子）。 */
    public boolean isCompanion() {
        return true;
    }

    public boolean staying() {
        return staying;
    }

    void setStaying(boolean on) {
        staying = on;
    }

    /** 主人拿着信物潜行右键：让她留在原地或跟上。 */
    void toggleStay(ServerPlayer p) {
        staying = !staying;
        mood.act(staying ? "act_head_tilt" : "act_wave");
        stayToggled(p, staying);
    }

    /** 留下、跟上之后她怎么表示（白梅：屏幕上提示；别的同伴说一句）。 */
    protected void stayToggled(ServerPlayer p, boolean staying) {
        CompanionLines.say(this, p, staying ? "stay" : "follow", 40);
    }

    public @Nullable CompanionMind mind() {
        return mind;
    }

    public CompanionMood mood() {
        return mood;
    }

    /**
     * 她能不能伤到这个东西：不伤别的同伴、主人的宠物和使魔；玩家只在「攻击玩家」开着时（{@link #mayFight}）。
     */
    public boolean canHarm(Entity e) {
        if (e == this || e instanceof CompanionEntity) return false;
        if (e instanceof Player target) return mayFight(target);
        // 主人的宠物（驯服的动物，车万女仆这类有主人的生物也算）
        if (owner != null && e instanceof OwnableEntity pet && pet.getOwner() != null && owner.equals(pet.getOwner().getUUID())) return false;
        return owner == null || !owner.equals(LogosMagicBridge.masterOf(e));
    }

    /**
     * 别的玩家她打不打（作战策略里的「攻击玩家」）：开着才打，主人自己、创造和旁观的不打；服务器关了 PvP、和主人同队不许互伤时也不打
     * （同主人自己能不能打他）。打谁还是照心智挑的：打主人的、主人在打的、打她的，不会去找路过的玩家。
     */
    public boolean mayFight(Player target) {
        if (owner == null || target.getUUID().equals(owner) || target.isCreative() || target.isSpectator()) return false;
        ServerPlayer p = ownerPlayer();
        return p != null && bond(p).pvp() && p.canHarmPlayer(target);
    }

    /**
     * 她当作敌人的：她正在对付的那个（心智挑的：主人在打的、{@code sic} 指的、打她的……不一定是敌对生物）、敌对生物、
     * 冲着她或主人来的。路过的动物和村民不算。
     */
    public boolean hostileTo(LivingEntity e) {
        if (e == getTarget() || e instanceof Enemy || e == aimed()) return true;
        if (mind != null && e == mind.foe()) return true;
        return e instanceof Mob m && (m.getTarget() == this || m.getTarget() != null && m.getTarget().getUUID().equals(owner));
    }

    /** 她手上这一招正对着的（白梅：这一刀砍的）。 */
    protected @Nullable LivingEntity aimed() {
        return null;
    }

    public float attack() {
        return attack;
    }

    void setAttack(float a) {
        attack = a;
    }

    public int seals() {
        return seals;
    }

    void setSeals(int n) {
        seals = n;
    }

    /**
     * 她这一刻的生命、攻击、护甲、韧性（她自己的、羁绊给的、继承的）。默认按定义里的成长（{@link CompanionDef.Growth}）算；
     * 拉塞塔的继承比例是分段、不封顶的（{@link LacertaStats}），自己算。
     */
    protected CompanionStats.Parts statParts(CompanionStats.Owner owner, int seals, int stage) {
        return CompanionStats.parts(def().growth(), owner, seals, stage);
    }

    /** 继承主人保护类魔咒的比例（默认同护甲的继承比例）。 */
    protected double protectionRatio() {
        return def().growth().armorRatio(seals);
    }

    /**
     * 护甲和魔咒都算过以后，这一下还剩多少：{@code protection} 是继承来的魔咒保护（已乘比例）。默认照原版封顶 20 点（减八成）；
     * 拉塞塔超出原版上限的护甲、韧性、魔咒保护在这里再减一点（{@link LacertaStats#overflow}）。
     */
    protected float reduceDamage(net.minecraft.world.damagesource.DamageSource source, float damage, float protection) {
        return protection > 0 ? net.minecraft.world.damagesource.CombatRules.getDamageAfterMagicAbsorb(damage, protection) : damage;
    }

    /** {@link CompanionStats#apply} 算完生命、护甲、攻击以后：她自己的数（精灵公主：法术强度）。 */
    protected void statsApplied(Player owner, int seals, int stage) {}

    // ---------------------------------------------------------------- 入场、收回

    void startSummon() {
        mood.play("summon", summonTicks());
    }

    /** 召唤时她现身的那一下（粒子、声音），在她脚边往上一点的 {@code at}。 */
    protected abstract void appearBurst(ServerLevel level, Vec3 at);

    /** 收回：挥挥手，然后散去。 */
    void leave() {
        if (!(level() instanceof ServerLevel level)) return;
        leavingAt = level.getGameTime();
        getNavigation().stop();
        stopEating();
        onLeave();
        mood.act("act_wave");
    }

    /** 收回时先收起手里的东西（白梅：散掉流火）。 */
    protected void onLeave() {}

    /** 挥完手散去（{@link #LEAVE_DISSOLVE} 刻）。 */
    protected abstract void dissolveAway(int ticks);

    public boolean leaving() {
        return leavingAt >= 0;
    }

    /** Logos 要她回去（主人叫出了别的长期同伴，或者对她用了 {@code dismiss}）：挥手散去。 */
    @Override
    public void sendHome() {
        ServerPlayer p = ownerPlayer();
        if (p != null && bond(p).out().map(getUUID()::equals).orElse(false)) {
            Companions.dismiss(p, def());
            return;
        }
        if (!leaving()) leave();
    }

    /** 闪回、闪开时两头的那一团（白梅：淡粉狐火和樱花瓣）。 */
    public abstract void puff(ServerLevel level, Vec3 at);

    /** 闪回时的声音。 */
    public void blinkSound(ServerLevel level, Vec3 at) {
        level.playSound(null, at.x, at.y, at.z, SoundEvents.FOX_TELEPORT, SoundSource.NEUTRAL, .8f, 1.3f);
    }

    // ---------------------------------------------------------------- 每刻

    @Override
    public void tick() {
        super.tick();
        deathYaw = isDeadOrDying() ? NpcDeathPose.hold(this, deathYaw) : Float.NaN;
        if (level() instanceof ServerLevel level) {
            if (!isDeadOrDying()) serverTick(level);
        } else {
            clientTick();
        }
    }

    protected void clientTick() {}

    private void serverTick(ServerLevel level) {
        long now = level.getGameTime();
        sinceHurt++;
        if (tickSpecial(level, now)) return;
        if (leavingAt >= 0) {
            if (now - leavingAt == LEAVE_WAVE) dissolveAway(LEAVE_DISSOLVE);
            if (now - leavingAt > LEAVE_WAVE + LEAVE_DISSOLVE + 20) discard();
            mood.tick(now);
            return;
        }
        ServerPlayer p = ownerPlayer();
        // 对话在别处结束了（主人下线、换了维度……没经过 talkEnded）：别一直站着等
        if (talkWith != null && tickCount % 20 == 0 && CompanionTalk.partner(this) == null) talkWith = null;
        if (tickCount % 20 == 0) {
            // 主人不在（下线、死亡、去了别的维度），或者契上记的已经不是她（旧存档里留下的那一位）：悄悄离开
            if (p == null || !bond(p).out().map(getUUID()::equals).orElse(false)) {
                discard();
                return;
            }
        }
        if (p != null) {
            if (tickCount % 100 == 0) CompanionStats.apply(this, p, bond(p).seals());
            if (tickCount % CompanionBond.HUNGER_TICKS == 0 && tickCount > 0) {
                var b = bond(p);
                setBond(p, b.withSatiety(b.satiety() - 1));
            }
            tickWithOwner(level, p);
        }
        if (sinceHurt > 100 && tickCount % 40 == 0 && getHealth() < getMaxHealth()) heal(1);
        tickActions(level, now, p);
        tickEating(level, now, p);
        tickAfterEating(level, now, p);
        mood.tick(now);
    }

    /** 在别的样子里（白梅：等人、立契、化作花瓣离开）：自己处理这一刻，返回真时不再做同伴的事。 */
    protected boolean tickSpecial(ServerLevel level, long now) {
        return false;
    }

    /** 主人在身边时每刻要看的（白梅：狐火灯）。 */
    protected void tickWithOwner(ServerLevel level, ServerPlayer p) {}

    /** 她手上的动作（施法、拉弓、刀……）。 */
    protected void tickActions(ServerLevel level, long now, @Nullable ServerPlayer p) {}

    /** 吃东西之后再看的（白梅：摘浆果）。 */
    protected void tickAfterEating(ServerLevel level, long now, @Nullable ServerPlayer p) {}

    // ---------------------------------------------------------------- 吃东西

    public boolean eating() {
        return eatStart >= 0;
    }

    /** 手上正忙着（拿着刀、正在施法、拉着弓）：不吃东西。 */
    protected boolean busyHands() {
        return false;
    }

    /** 吃一口 {@code food}（一份）。{@code shared}：主人亲手给的（一起吃饭，加好感）。 */
    boolean startEating(ItemStack food, boolean shared) {
        if (eating() || busyHands() || !(level() instanceof ServerLevel level)) return false;
        eating = food.copyWithCount(1);
        eatShared = shared;
        eatStart = level.getGameTime();
        setItemSlot(EquipmentSlot.MAINHAND, eating.copy());
        getNavigation().stop();
        mood.play("eat", EAT_TICKS);
        return true;
    }

    private void tickEating(ServerLevel level, long now, @Nullable ServerPlayer p) {
        if (eatStart < 0) return;
        int t = (int) (now - eatStart);
        for (int bite : EAT_BITES) {
            if (t == bite) {
                Vec3 mouth = getEyePosition().add(Vec3.directionFromRotation(0, yBodyRot).scale(.22)).add(0, -.12, 0);
                level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, eating.getItem()), mouth.x, mouth.y, mouth.z, 4, .05, .05, .05, .04);
                level.playSound(null, getX(), getY(), getZ(), SoundEvents.GENERIC_EAT.value(), SoundSource.NEUTRAL, .5f, 1.4f + getRandom().nextFloat() * .2f);
            }
        }
        if (t == EAT_SWALLOW && p != null) {
            CompanionFood.Taste taste = CompanionFood.taste(def().id(), eating);
            heal(CompanionFood.heal(eating));
            CompanionFood.effects(level, this, eating);
            var b = bond(p);
            long day = level.getOverworldClockTime() / 24000;
            float gain = eatShared ? CompanionBond.meal(taste == CompanionFood.Taste.FAVORITE, b.mealsOn(day), b.satiety()) : 0;
            b = b.withSatiety(CompanionBond.fed(b.satiety(), CompanionFood.nutrition(eating))).withAffection(b.affection() + gain);
            if (eatShared) b = b.withMeal(day, b.mealsOn(day) + 1);
            setBond(p, b);
            swallowed(level);
            if (taste == CompanionFood.Taste.FAVORITE) {
                level.sendParticles(ParticleTypes.HEART, getX(), getY() + 1.35, getZ(), 2, .2, .1, .2, 0);
                mood.delighted(now);
            }
            meals++;
        }
        if (t >= EAT_TICKS) stopEating();
    }

    /** 咽下去的那一下（第 {@link #EAT_SWALLOW} 刻）：默认一声小嗝；拉塞塔是鼻尖一小团满足的暖烟（她不打嗝）。 */
    protected void swallowed(ServerLevel level) {
        level.playSound(null, getX(), getY(), getZ(), SoundEvents.PLAYER_BURP, SoundSource.NEUTRAL, .35f, 1.6f);
    }

    void stopEating() {
        if (eatStart < 0) return;
        eatStart = -1;
        eating = ItemStack.EMPTY;
        setItemSlot(EquipmentSlot.MAINHAND, restingMainHand());
    }

    /** 不吃东西时手里拿的（白梅空着手；精灵公主拿着她的弓）。 */
    protected ItemStack restingMainHand() {
        return ItemStack.EMPTY;
    }

    /** 测试用：吃过几口。 */
    private static int meals;

    public static int meals() {
        return meals;
    }

    // ---------------------------------------------------------------- 互动

    /**
     * 右键她：主人潜行右键和她说话（对话）、空手摸头、拿吃的喂她、拿空白信物盖契印、信物丢了时拿补的材料让她再做一个。
     * 她自己的情况（白梅在封印之间等人）在 {@link #interactSpecial} 里先处理。
     */
    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND || isDeadOrDying()) return super.mobInteract(player, hand);
        if (level().isClientSide()) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer sp) || !sp.getUUID().equals(owner) || leaving()) return InteractionResult.PASS;
        InteractionResult special = interactSpecial(sp);
        if (special != null) return special;
        if (sp.isShiftKeyDown()) {
            CompanionTalk.begin(sp, this);
            return InteractionResult.SUCCESS;
        }
        ItemStack held = sp.getItemInHand(hand);
        if (held.isEmpty()) {
            getNavigation().stop();
            getLookControl().setLookAt(sp, 40, 40);
            mood.petted(level().getGameTime());
            var b = bond(sp);
            long day = level().getOverworldClockTime() / 24000;
            setBond(sp, b.withAffection(b.affection() + CompanionBond.pat(b.patsOn(day))).withPats(day, b.patsOn(day) + 1));
            Companions.favoured(sp, this);
            return InteractionResult.SUCCESS;
        }
        if (blankToken(held)) {
            if (!sp.getAbilities().instabuild) held.shrink(1);
            int n = Companions.addSeal(sp, def());
            sealed(sp, n);
            return InteractionResult.SUCCESS;
        }
        if (remake(sp, held)) return InteractionResult.SUCCESS;
        CompanionFood.Taste taste = CompanionFood.taste(def().id(), held);
        if (taste != CompanionFood.Taste.NOT_FOOD) {
            if (eating() || busyHands()) return InteractionResult.PASS;
            if (taste == CompanionFood.Taste.REFUSED) {
                mood.act("refuse");
                CompanionLines.say(this, sp, "refuse", 40);
                return InteractionResult.SUCCESS;
            }
            if (bond(sp).satiety() >= CompanionBond.SATIETY_MAX - .5f && taste != CompanionFood.Taste.FAVORITE && !CompanionFood.always(held)) {
                CompanionLines.say(this, sp, "full", 40);
                return InteractionResult.SUCCESS;
            }
            if (startEating(held, true)) {
                if (!sp.getAbilities().instabuild) held.shrink(1);
                Companions.favoured(sp, this);
            }
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    /** 她自己先处理的右键（白梅：在封印之间等人时开始立契）；不处理返回 null。 */
    protected @Nullable InteractionResult interactSpecial(ServerPlayer sp) {
        return null;
    }

    /** 主人当着她的面摸了、喂了另一位同伴 {@code other}（拉塞塔：偶尔吃醋）。 */
    protected void ownerFavoured(ServerPlayer owner, CompanionEntity other) {}

    /** 她捡起了地上的 {@code stack}（已放进包袱；拉塞塔：亮晶晶的喊「我的！」）。 */
    protected void lootTaken(ServerPlayer owner, ItemStack stack) {}

    /** 这是她的空白信物（拿着右键她盖一枚契印）。 */
    protected abstract boolean blankToken(ItemStack held);

    /** 盖了第 {@code seals} 枚契印：她的反应。 */
    protected abstract void sealed(ServerPlayer sp, int seals);

    /** 主人的信物丢了，拿着补的材料来找她：给主人再做一个，返回真。 */
    protected abstract boolean remake(ServerPlayer sp, ItemStack held);

    // ---------------------------------------------------------------- 受伤与倒下

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
        if (immune()) return false;
        Entity by = source.getEntity();
        if (by != null && (by.getUUID().equals(owner) || by instanceof CompanionEntity)) {
            // 主人打她：不疼，但会闹别扭
            if (by.getUUID().equals(owner)) mood.pouted(level.getGameTime());
            return false;
        }
        if (leaving() || ignores(source)) return false;
        if (amount > 0 && !isInvulnerableTo(level, source) && blocked(level, source)) return false;
        boolean hurt = super.hurtServer(level, source, amount);
        if (hurt) sinceHurt = 0;
        return hurt;
    }

    /** 这一刻什么都伤不到她（白梅在封印之间等人时）。 */
    protected boolean immune() {
        return false;
    }

    /** 这种伤她不受（白梅：浆果丛扎不到她）。 */
    protected boolean ignores(DamageSource source) {
        return false;
    }

    /** 她挡下了这一下（白梅：刀弹开）。 */
    protected boolean blocked(ServerLevel level, DamageSource source) {
        return false;
    }

    @Override
    public boolean causeFallDamage(double fallDistance, float multiplier, DamageSource source) {
        return false;
    }

    @Override
    public void die(DamageSource source) {
        // 先把手里的吃的收掉，免得当作装备掉出来
        stopEating();
        onDie();
        super.die(source);
        // 死亡事件被取消了（精灵公主撤退、别的模组救下来）：她还活着，别当作倒下
        if (!isDeadOrDying()) return;
        entityData.set(MOVE, "");
        entityData.set(MISC, "");
        ServerPlayer p = ownerPlayer();
        if (p != null) {
            Companions.fell(p, this);
            fell(p);
        }
    }

    /** 倒下前收起手里的东西。 */
    protected void onDie() {}

    /** 倒下以后对主人表示（白梅：屏幕上提示要歇多久）。 */
    protected void fell(ServerPlayer p) {}

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    // ---------------------------------------------------------------- 存档

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        if (owner != null) output.store("Owner", UUIDUtil.CODEC, owner);
        output.putBoolean("Staying", staying);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        owner = input.read("Owner", UUIDUtil.CODEC).orElse(null);
        staying = input.getBooleanOr("Staying", false);
    }

    // ---------------------------------------------------------------- 动画

    void setMisc(String name) {
        if (!entityData.get(MISC).equals(name)) entityData.set(MISC, name);
    }

    String misc() {
        return entityData.get(MISC);
    }

    void setMove(String text) {
        if (!entityData.get(MOVE).equals(text)) entityData.set(MOVE, text);
    }

    String move() {
        return entityData.get(MOVE);
    }

    /**
     * 展示用（客户端临时造的、不放进世界的那一位，见连携切入）：表情播 {@code face}（{@code face_*}），身体一直播 {@code pose}（循环）。
     * 两段都在她的动画文件里；名字不在时那一层不播。
     */
    public void stage(String face, String pose) {
        setMisc(face);
        setMove(pose + "|0|1|true");
    }

    @Override
    public String npcMiscAnimation() {
        String name = entityData.get(MISC);
        return name.isEmpty() ? null : name;
    }

    /** 整个人由 misc 动画摆（睡着……）：客户端这时不叠加视线。 */
    public boolean curled() {
        return "sleep".equals(entityData.get(MISC));
    }

    /** 入场最初几刻不画（客户端用）。 */
    public boolean hiddenForSummon() {
        return "summon".equals(entityData.get(MISC)) && tickCount <= HIDDEN_TICKS;
    }

    @Override
    public CombatMove npcCombatMove() {
        String text = entityData.get(MOVE);
        if (!text.equals(moveFrom)) {
            moveFrom = text;
            clientMove = null;
            String[] p = text.split("\\|");
            if (p.length == 4) {
                try {
                    clientMove = new CombatMove(p[0], Long.parseLong(p[1]), Float.parseFloat(p[2]), Boolean.parseBoolean(p[3]));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return clientMove;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        NpcAnimations.registerControllers(this, controllers);
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }

    // ---------------------------------------------------------------- 说话（CompanionTalk.Talker）

    /** 有人在和她说话（服务端）。 */
    public boolean talking() {
        return talkWith != null;
    }

    @Override
    public String talkId() {
        return def().id();
    }

    @Override
    public boolean canTalk(ServerPlayer p) {
        return isCompanion() && !leaving() && !isDeadOrDying() && owner != null && owner.equals(p.getUUID());
    }

    @Override
    public void talkStarted(ServerPlayer p) {
        talkWith = p.getUUID();
        getNavigation().stop();
    }

    @Override
    public void talkEnded(ServerPlayer p, boolean bye) {
        talkWith = null;
        talkFinished(p, bye);
    }

    /** 对话结束以后她的反应（说了「一会再聊」时挥挥手）。 */
    protected void talkFinished(ServerPlayer p, boolean bye) {}
}
