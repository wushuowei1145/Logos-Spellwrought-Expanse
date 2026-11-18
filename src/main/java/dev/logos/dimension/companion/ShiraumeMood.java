package dev.logos.dimension.companion;

import java.util.Map;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;

/**
 * 白梅的表情和小动作（docs/SHIRAUME_COMPANION.md 第 7.3 节）。情绪和小动作的意思见她模型 README「第 5 版」，这里只决定什么时候用哪一个。
 */
final class ShiraumeMood extends CompanionMood {
    /** 每段 misc 动画的长度（刻），与动画文件一致。 */
    static final Map<String, Integer> LENGTH = Map.ofEntries(Map.entry("summon", 56), Map.entry("act_petted", 60), Map.entry("act_proud", 60),
            Map.entry("act_sleeve_laugh", 52), Map.entry("act_stretch", 72), Map.entry("act_startle", 32), Map.entry("act_wave", 48),
            Map.entry("act_head_tilt", 36), Map.entry("act_sneeze", 36), Map.entry("eat", 48), Map.entry("pick", 24), Map.entry("refuse", 20),
            Map.entry("pact", 192));

    private long strongKillSeen, fallSeen, stretchDay = -1, nextTilt, nextSneeze;
    private int lookedAt;
    private boolean content;
    private long faceUntil;

    ShiraumeMood(CompanionEntity c) {
        super(c, LENGTH);
    }

    /** 睡醒伸个懒腰。 */
    @Override
    protected void wokeUp() {
        act("act_stretch");
    }

    // ---------------------------------------------------------------- 遇到事情：小动作

    @Override
    protected void react(ServerPlayer owner, long now) {
        OwnerWatch w = OwnerWatch.of(owner);
        boolean engaged = engaged();
        double dist = owner.distanceTo(c);
        // 主人从远处回来：挥手
        if (ownerReturned(dist, now) && !engaged) {
            act("act_wave");
            return;
        }
        if (w.lastStrongKill > strongKillSeen && now - w.lastStrongKill < 40) {
            strongKillSeen = w.lastStrongKill;
            act("act_proud");
            return;
        }
        if (now - w.lastHurt < 10 && w.lastHurtAmount >= 8 && w.lastHurt > surprisedAt) {
            surprisedAt = now;
            if (!engaged) {
                act("act_startle");
                return;
            }
        }
        if (w.lastFall > fallSeen && now - w.lastFall < 30 && owner.getHealth() > owner.getMaxHealth() * .5f && !engaged) {
            fallSeen = w.lastFall;
            // 主人摔了一跤：掩着袖子笑
            act("act_sleeve_laugh");
            return;
        }
        if (engaged) return;
        // 饿了：隔一阵提一句
        var bond = c.bond(owner);
        if (bond.satiety() < CompanionBond.HUNGRY && now >= nextHungry && dist < 10) {
            nextHungry = now + 3600;
            CompanionLines.say(owner, "hungry", 50);
            act("act_head_tilt");
            return;
        }
        long clock = c.level().getOverworldClockTime(), day = clock / 24000, time = clock % 24000;
        if (time < 1500 && day != stretchDay && dist < 10) {
            stretchDay = day;
            act("act_stretch");
            return;
        }
        if (lookingAtMe(owner) && w.still(now) > 20) {
            lookedAt += 10;
            if (lookedAt >= 40 && now >= nextTilt) {
                lookedAt = 0;
                nextTilt = now + 1200;
                act("act_head_tilt");
                return;
            }
        } else {
            lookedAt = 0;
        }
        // 淋着雨：偶尔打个喷嚏
        if (now >= nextSneeze && c.getRandom().nextInt(120) == 0 && c.level().isRainingAt(c.blockPosition().above())) {
            nextSneeze = now + 2400;
            act("act_sneeze");
        }
    }

    // ---------------------------------------------------------------- 平时：情绪

    @Override
    protected @Nullable String chooseFace(ServerPlayer owner, long now) {
        OwnerWatch w = OwnerWatch.of(owner);
        noticeBiome(now);
        boolean engaged = engaged();
        if (engaged) return "face_angry";
        if (c.talking()) return now < talkFaceUntil && talkFace != null ? talkFace : "face_content";
        if (now < afraidUntil) return "face_flustered";
        if (now - surprisedAt < 30) return "face_surprised";
        if (now < delightUntil) return "face_happy";
        if (owner.getHealth() < owner.getMaxHealth() * .35f) return "face_sad";
        if (now - pettedAt < 200) return pets >= 3 ? "face_shy" : "face_happy";
        if (now - poutAt < 300) return "face_pout";
        if (now < curiousUntil) return "face_curious";
        if (night(c.level()) && w.activity(now, owner.isSleeping()).ordinal() >= OwnerWatch.Activity.IDLE.ordinal()) return "face_sleepy";
        // 没什么事：多数时候是平常的样子，有时眯起眼安心地笑
        if (now >= faceUntil) {
            content = c.getRandom().nextFloat() < .35f;
            faceUntil = now + 300 + c.getRandom().nextInt(200);
        }
        return content ? "face_content" : null;
    }
}
