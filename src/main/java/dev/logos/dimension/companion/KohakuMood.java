package dev.logos.dimension.companion;

import java.util.Map;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * 琥珀的表情和小动作（docs/KOHAKU_COMPANION.md 第 12 节）。动画见她模型的 README（kohaku_companion）；这里只决定什么时候用哪一个。
 * 性格落在这几处：
 * <ul>
 *   <li>懒：常态是半垂的眼皮（模型的常驻层）；一阵没事干就嫌麻烦（{@code face_bored}），偶尔叹口气（{@code act_sigh}）、洗洗脸（{@code act_groom}）。</li>
 *   <li>天才：打起来竖瞳（{@code face_focus}）；主人打倒强敌时坏笑一下（{@code face_smug}）。</li>
 *   <li>猫：睡醒像猫一样伸个大懒腰（{@code act_cat_stretch}）；出了水抖一抖（{@code act_shake}）；新地方、夜里好奇（{@code face_curious}）。</li>
 *   <li>外冷内热：被看着、说着话时安心地慢眨眼（{@code face_content}）；被摸多了不好意思（{@code face_flustered}）。</li>
 * </ul>
 */
final class KohakuMood extends CompanionMood {
    /** 每段 misc 动画的长度（刻），与动画文件一致（单元测试对过）。 */
    static final Map<String, Integer> LENGTH = Map.ofEntries(Map.entry("summon", 64), Map.entry("death", 116), Map.entry("act_stretch", 68),
            Map.entry("act_cat_stretch", 108), Map.entry("act_sigh", 52), Map.entry("act_coin_flip", 56), Map.entry("act_formula", 56),
            Map.entry("act_alchemy", 64), Map.entry("act_petted", 64), Map.entry("act_groom", 64), Map.entry("act_shake", 32),
            Map.entry("act_guard", 40), Map.entry("act_wave", 36), Map.entry("act_head_tilt", 36), Map.entry("eat", 48), Map.entry("refuse", 20),
            Map.entry("pick", 24));
    /** 一阵没事干：主人不动多久（刻）她开始嫌麻烦。 */
    static final int BORED_AFTER = 600;

    private long strongKillSeen, nextIdleAct, smugUntil = -10_000, surprisedUntil = -10_000, guardUntil = -10_000;
    private int lookedAt;
    private boolean wet;

    KohakuMood(CompanionEntity c) {
        super(c, LENGTH);
    }

    /** 睡醒：像猫一样伸个大懒腰（维护者定的 {@code act_cat_stretch}）。 */
    @Override
    protected void wokeUp() {
        act("act_cat_stretch");
    }

    /** 得意一阵（盖爪之契、主人打倒强敌）。 */
    void smug(long now) {
        smugUntil = now + 160;
    }

    /** 护膜碎了：吓一跳。 */
    void wardBroke(long now) {
        surprisedUntil = now + 30;
    }

    // ---------------------------------------------------------------- 遇到事情：小动作

    @Override
    protected void react(ServerPlayer owner, long now) {
        OwnerWatch w = OwnerWatch.of(owner);
        boolean engaged = engaged();
        double dist = owner.distanceTo(c);
        if (ownerReturned(dist, now) && !engaged) {
            act("act_wave");
            return;
        }
        if (w.lastStrongKill > strongKillSeen && now - w.lastStrongKill < 40) {
            strongKillSeen = w.lastStrongKill;
            smug(now);
        }
        // 主人挨了一大下：她出手护着（打着的时候是护短的哈气脸）
        if (now - w.lastHurt < 10 && w.lastHurtAmount >= 6) guardUntil = now + 60;
        // 出了水、雨停了：抖一抖
        boolean wetNow = c.isInWaterOrRain();
        if (wet && !wetNow && !engaged) {
            wet = false;
            act("act_shake");
            return;
        }
        wet = wetNow;
        if (engaged) return;
        var bond = c.bond(owner);
        if (bond.satiety() < CompanionBond.HUNGRY && now >= nextHungry && dist < 10) {
            nextHungry = now + 3600;
            CompanionLines.say(c, owner, "hungry", 50);
            act("act_sigh");
            return;
        }
        if (lookingAtMe(owner) && w.still(now) > 20) {
            lookedAt += 10;
        } else {
            lookedAt = 0;
        }
        // 一阵没事干：叹口气、洗洗脸
        if (w.still(now) > BORED_AFTER && now >= nextIdleAct && c.getRandom().nextInt(6) == 0) {
            nextIdleAct = now + 900 + c.getRandom().nextInt(600);
            act(c.getRandom().nextBoolean() ? "act_groom" : "act_sigh");
        }
    }

    // ---------------------------------------------------------------- 平时：情绪

    @Override
    protected @Nullable String chooseFace(ServerPlayer owner, long now) {
        OwnerWatch w = OwnerWatch.of(owner);
        noticeBiome(now);
        if (now < surprisedUntil) return "face_surprised";
        if (engaged()) return now < guardUntil ? "face_guard" : "face_focus";
        // 不出手的时候哈着气
        if ("act_guard".equals(playing())) return "face_guard";
        if (c.talking()) return now < talkFaceUntil && talkFace != null ? talkFace : "face_content";
        if (now < delightUntil) return "face_happy";
        if (now < smugUntil) return "face_smug";
        if (now - pettedAt < 200) return pets >= 3 ? "face_flustered" : "face_content";
        if (now - poutAt < 300) return "face_bored";
        if (owner.getHealth() < owner.getMaxHealth() * .35f) return "face_sad";
        if (lookedAt >= 40) return "face_content";
        if (now < curiousUntil) return "face_curious";
        boolean idle = w.activity(now, owner.isSleeping()).ordinal() >= OwnerWatch.Activity.IDLE.ordinal();
        if (night(c.level()) && idle) return "face_sleepy";
        if (w.still(now) > BORED_AFTER) return "face_bored";
        return "face_lazy";
    }
}
