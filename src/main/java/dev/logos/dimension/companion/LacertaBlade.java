package dev.logos.dimension.companion;

import dev.logos.dimension.combat.CombatWeapon;
import dev.logos.dimension.combat.Moveset;
import dev.logos.dimension.combat.Movesets;
import dev.logos.dimension.combat.NpcWeapon;
import dev.logos.dimension.combat.Parry;
import dev.logos.dimension.combat.WeaponPilot;
import dev.logos.dimension.item.CrimsonFlameLongswordItem;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileDeflection;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 拉塞塔的炽焰长剑（docs/LACERTA_COMPANION.md 第 5 节）：跑玩家那一份招式文件 {@code crimson_flame_longsword}（docs/WEAPON_AI.md），
 * 动作是模型里的 {@code crimson_*}（招式文件顶层 {@code ai.clips}）。
 * <ul>
 *   <li>她「按键」（{@link NpcWeapon}）：身法、时间窗、缓冲和玩家一样，空中坠星要先跳起来、横扫要先横防；挑哪一招在 {@link LacertaMind}，
 *       按法交给驾驶员（{@link WeaponPilot}，照招式文件的 {@code ai}）。</li>
 *   <li>每一剑都是龙炎（剑的 {@code strike}）：她的攻击 + 剑的 {@link #SWORD} 点，乘招式的倍数；带火的招式缠身，逆鳞时每一剑都缠身。
 *       冲刺、起落、横扫的游脉、坠星的落点、挑飞和下砸都照玩家的剑。</li>
 *   <li>龙骑武艺（第二印，她自己的本事，不在招式文件里）：没在出招时挡得下近战和弹射物（托脊横防时几乎都挡得住），近战弹开、
 *       对方僵直，她趁机接破军龙牙；弹射物一剑扫回去。</li>
 *   <li>拔剑时剑从柄往尖凝出来，收剑播完散掉（客户端照 {@link LacertaCompanion#swordShown} 画）。</li>
 * </ul>
 */
final class LacertaBlade implements NpcWeapon.Owner {
    /** 角色名：她自己的招式文件放在 {@code combat/movesets/lacerta/}（docs/WEAPON_AI.md 第 4 节；现在没有，用剑的）。 */
    static final String CHARACTER = "lacerta";
    /** 炽焰长剑的攻击（同空手的玩家拿着它）：她的一剑 = 攻击 + 它，再乘招式倍数。 */
    static final float SWORD = 8;
    /** 剑够得着多远（格，从眼睛量到目标身上最近的一点）：她握的是 0.85 倍的剑，全长约 1.8 格。 */
    static final double REACH = 2.9;
    /** 攻速：同玩家拿着这把剑（攻击招式按 1.5 / attack_speed_base 播）。 */
    static final double ATTACK_SPEED = 1.5;
    /** 弹开以后几刻能反击。 */
    static final int PARRY_RECOVER = 3;
    /** 挡下的概率：平时（没在出招）近战、弹射物；托脊横防时。 */
    static final float BLOCK_MELEE = .55f, BLOCK_PROJECTILE = .7f, BLOCK_GUARDING = .95f;
    /** 凝剑、散剑各要几刻（客户端按这个画）。 */
    static final int MANIFEST = 6;

    private final LacertaCompanion c;
    private @Nullable ItemStack sword;
    final NpcWeapon hands = new NpcWeapon(this);
    final WeaponPilot pilot = new WeaponPilot(hands);
    private @Nullable LivingEntity aim;

    LacertaBlade(LacertaCompanion c) {
        this.c = c;
    }

    // ---------------------------------------------------------------- 拿着剑的她（NpcWeapon.Owner）

    @Override
    public LivingEntity entity() {
        return c;
    }

    @Override
    public ItemStack weapon() {
        if (sword == null) sword = new ItemStack(CrimsonFlameLongswordItem.ITEM.get());
        return sword;
    }

    @Override
    public Vec3 aim() {
        LivingEntity at = aimed();
        if (at != null) {
            Vec3 to = at.getBoundingBox().getCenter().subtract(c.getEyePosition());
            if (to.lengthSqr() > 1e-6) return to.normalize();
        }
        return forward();
    }

    @Override
    public @Nullable LivingEntity aimed() {
        return hands.drawn() && aim != null && aim.isAlive() ? aim : null;
    }

    @Override
    public double reach() {
        return REACH;
    }

    @Override
    public double attackDamage() {
        return c.attack() + SWORD;
    }

    @Override
    public double attackSpeed() {
        return ATTACK_SPEED;
    }

    @Override
    public boolean intends(String flag) {
        return hands.intends(flag);
    }

    @Override
    public boolean mayHit(Entity e) {
        return e instanceof LivingEntity l && l != c && l.isAlive() && c.canHarm(l) && c.hostileTo(l);
    }

    /** 逆鳞时每一剑都带火。 */
    @Override
    public boolean ignites() {
        return c.seal.wrathful();
    }

    @Override
    public String character() {
        return CHARACTER;
    }

    @Override
    public void showMove(@Nullable String clip, long start, float rate, boolean loop) {
        if (clip == null) {
            aim = null;
            pilot.clear();
            c.setMove("");
            c.setSwordShown(false);
            return;
        }
        c.setMove(clip + "|" + start + "|" + rate + "|" + loop);
        Moveset.Move m = hands.move();
        if (m != null) STARTED.merge(m.name(), 1, Integer::sum);
    }

    @Override
    public void landed(LivingEntity target) {
        landed++;
        c.fought(c.level().getGameTime());
    }

    // ---------------------------------------------------------------- 状态

    /** 剑在手上（招式状态机在跑）。 */
    boolean drawn() {
        return hands.drawn();
    }

    Moveset.@Nullable Move move() {
        return hands.move();
    }

    /** 正在出招：这一招有接触、还没砍完（出招时挡不了）。 */
    boolean attacking() {
        return hands.attacking();
    }

    /** 在待机（可以走动、出下一招）。 */
    boolean ready() {
        Moveset.Move m = move();
        return m != null && "idle".equals(m.name()) && pilot.goal() == null;
    }

    boolean guarding() {
        Moveset.Move m = move();
        return m != null && ("guard".equals(m.name()) || "guard_hit".equals(m.name()));
    }

    boolean staggered() {
        return hands.staggered();
    }

    /**
     * 可以挑下一招：驾驶员没在按着上一招；在待机或横防里，这一招砍完已经过了 {@link NpcWeapon#RECOVER} 刻、在收势；
     * 弹开以后过了 {@link #PARRY_RECOVER} 刻。拔剑、收剑、僵直时不行。
     */
    boolean canFollowUp() {
        Moveset.Move m = move();
        if (m == null || pilot.goal() != null || "draw".equals(m.name())) return false;
        if ("parry".equals(m.name())) return hands.age() >= PARRY_RECOVER;
        return hands.canFollowUp();
    }

    /** 这一招的软冷却（秒）：她用的招式文件里这一招的 {@code ai.cooldown}（几种用法取最长的）。 */
    double cooldown(String move) {
        if (!(c.level() instanceof ServerLevel level)) return 0;
        CombatWeapon weapon = CombatWeapon.of(weapon());
        if (weapon == null) return 0;
        var server = level.getServer();
        double cd = 0;
        for (var u : Movesets.ai(server, Movesets.forCharacter(server, CHARACTER, weapon.moveset())).uses(move)) cd = Math.max(cd, u.cooldown());
        return cd;
    }

    /** 刚被她弹开、还在僵直里的那个（过了反击的时候、死了就是 null）。 */
    @Nullable LivingEntity punishing(long now) {
        return hands.parriedFoe();
    }

    long punishUntil() {
        return hands.parriedUntil();
    }

    // ---------------------------------------------------------------- 拔剑、收剑

    /** 拔剑（已经在手上就不动）：先播绯焰风车的拔剑，剑从柄往尖凝出来。 */
    boolean draw() {
        if (drawn()) return true;
        if (!(c.level() instanceof ServerLevel level)) return false;
        c.setSwordShown(true);
        if (!hands.draw()) {
            c.setSwordShown(false);
            return false;
        }
        level.playSound(null, c.getX(), c.getY(), c.getZ(), SoundEvents.FIRECHARGE_USE, SoundSource.NEUTRAL, .5f, 1.4f);
        draws++;
        return true;
    }

    /** 收剑：播收剑（剑花、驻剑致礼），播完剑散掉。 */
    void sheathe() {
        if (!drawn()) return;
        aim = null;
        pilot.clear();
        hands.sheathe();
    }

    /** 立刻收掉（倒下、被收回）。 */
    void drop() {
        aim = null;
        pilot.clear();
        hands.drop();
        c.setMove("");
        c.setSwordShown(false);
    }

    /** 出一招（{@link #canFollowUp} 时接受）：对着 {@code at}，交给驾驶员按过去（转移图里走不到就不出）。 */
    boolean attack(String name, @Nullable LivingEntity at) {
        if (!canFollowUp()) return false;
        LivingEntity before = aim;
        aim = at;
        if (pilot.go(name)) return true;
        aim = before;
        return false;
    }

    /** 托脊横防（站住挡，挡下就弹开；第二印龙骑武艺）：按住右键。 */
    boolean guard() {
        if (!drawn() || !c.seal.has(LacertaSeals.ARTS)) return false;
        if (guarding()) return true;
        if (!canFollowUp()) return false;
        aim = null;
        hands.hold(true);
        return guarding();
    }

    /** 测试用：回到待机（不管在出哪一招）。 */
    void idleForTest() {
        pilot.clear();
        hands.hold(false);
        hands.force("idle");
    }

    /** 放下横防回到待机：松开右键。 */
    void unguard() {
        if (guarding() || hands.using()) hands.hold(false);
    }

    // ---------------------------------------------------------------- 每刻

    void tick(ServerLevel level) {
        pilot.tick();
        hands.tick();
    }

    private Vec3 forward() {
        float yaw = c.getYRot() * Mth.DEG_TO_RAD;
        return new Vec3(-Mth.sin(yaw), 0, Mth.cos(yaw));
    }

    static Vec3 nearest(AABB box, Vec3 p) {
        return new Vec3(Mth.clamp(p.x, box.minX, box.maxX), Mth.clamp(p.y, box.minY, box.maxY), Mth.clamp(p.z, box.minZ, box.maxZ));
    }

    // ---------------------------------------------------------------- 格挡、被弹反

    /**
     * 有东西打她（龙骑武艺，第二印）：没在出招、没在僵直里时有很大机会挡下（挡下返回真，这一下不受伤）。近战弹开，对方（玩家或会做出反应的
     * 招式生物）僵直，她记下来趁机反击；弹射物一剑扫回去。不管是不是正按着横防、横防按了多久，挡下都是弹开（玩家只有刚举剑那一下是）。
     */
    boolean tryBlock(ServerLevel level, DamageSource source) {
        if (!drawn() || attacking() || staggered() || !c.seal.has(LacertaSeals.ARTS)) return false;
        Entity direct = source.getDirectEntity();
        boolean projectile = direct instanceof Projectile;
        boolean melee = Parry.melee(source);
        if (!projectile && !melee) return false;
        float chance = guarding() ? BLOCK_GUARDING : projectile ? BLOCK_PROJECTILE : BLOCK_MELEE;
        if (c.getRandom().nextFloat() >= chance) return false;
        blocks++;
        aim = null;
        pilot.clear();
        hands.force("parry");
        if (source.getEntity() instanceof LivingEntity attacker) c.getLookControl().setLookAt(attacker, 60, 60);
        level.playSound(null, c.getX(), c.getY(), c.getZ(), SoundEvents.ANVIL_LAND, SoundSource.NEUTRAL, .35f, 1.7f);
        if (!melee) {
            Projectile p = (Projectile) direct;
            p.deflect(ProjectileDeflection.REVERSE, c, EntityReference.<Entity>of(c), false);
            dev.logos.dimension.shiraume.ShiraumeDeflect.claim(p, c);
        } else if (Parry.stagger(source, c) && source.getEntity() instanceof LivingEntity attacker) {
            hands.remember(attacker, level.getGameTime());
        }
        return true;
    }

    /** 她这一剑被 {@code by} 弹开：进入 NPC 的僵直。 */
    boolean parried(LivingEntity by) {
        aim = null;
        pilot.clear();
        if (!hands.parried()) return false;
        staggers++;
        return true;
    }

    // ---------------------------------------------------------------- 测试用

    /** 每一招开始过几次。 */
    static final Map<String, Integer> STARTED = new HashMap<>();
    static int landed, blocks, staggers, draws;

    static int started(String move) {
        return STARTED.getOrDefault(move, 0);
    }
}
