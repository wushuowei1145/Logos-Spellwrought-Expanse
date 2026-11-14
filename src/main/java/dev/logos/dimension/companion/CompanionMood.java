package dev.logos.dimension.companion;

import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 白梅的表情和小动作（docs/SHIRAUME_COMPANION.md 第 7.3 节）：每半秒按处境挑一种情绪（{@code face_*}，循环），
 * 遇到事情时插一个小动作（{@code act_*}，播一次）。都走 {@code misc} 控制器，优先级：
 * 入场 / 小动作 → 蜷着睡 → 姿势（泡温泉、跪坐歇着）→ 情绪。小动作有长短，播完自动交回情绪。
 *
 * <p>情绪和小动作的意思见模型 README「第 5 版」，这里只决定什么时候用哪一个。
 */
final class CompanionMood {
    /** 每段 misc 动画的长度（刻），与动画文件一致。 */
    static final Map<String, Integer> LENGTH = Map.ofEntries(Map.entry("summon", 56), Map.entry("act_petted", 60), Map.entry("act_proud", 60),
            Map.entry("act_sleeve_laugh", 52), Map.entry("act_stretch", 72), Map.entry("act_startle", 32), Map.entry("act_wave", 48),
            Map.entry("act_head_tilt", 36), Map.entry("act_sneeze", 36), Map.entry("eat", 48), Map.entry("pick", 24), Map.entry("refuse", 20),
            Map.entry("pact", 192));
    /** 两个小动作之间至少隔这么久，免得一直在演。 */
    static final int ACT_GAP = 200;

    private final ShiraumeCompanion c;
    private @Nullable String act, face, pose;
    private long actUntil, faceUntil, nextAct;
    private boolean curled;
    private long pettedAt = -10_000, poutAt = -10_000, surprisedAt = -10_000, curiousUntil = -10_000, delightUntil = -10_000, afraidUntil = -10_000;
    private long nextHungry;
    /** 说着话时的表情（对话里她回话时定），到哪一刻。 */
    private @Nullable String talkFace;
    private long talkFaceUntil;
    private int pets;
    private long strongKillSeen, fallSeen, stretchDay = -1, nextTilt, nextSneeze;
    private long farSince = -1;
    private int lookedAt;
    private @Nullable Holder<Biome> biome;
    private boolean content;

    CompanionMood(ShiraumeCompanion c) {
        this.c = c;
    }

    private long now() {
        return c.level().getGameTime();
    }

    /** 立刻播 {@code name}，长 {@code ticks} 刻。 */
    void play(String name, int ticks) {
        act = name;
        actUntil = now() + ticks;
        c.setMisc(name);
    }

    /** 播一个小动作（入场还没播完时不打断）。 */
    void act(String name) {
        if (act != null && act.equals("summon") && now() < actUntil) return;
        play(name, LENGTH.getOrDefault(name, 40));
        nextAct = now() + LENGTH.getOrDefault(name, 40) + ACT_GAP;
    }

    boolean acting() {
        return act != null && now() < actUntil;
    }

    /** 入场动画还在播。 */
    boolean summoning() {
        return "summon".equals(act) && now() < actUntil;
    }

    @Nullable String face() {
        return face;
    }

    void curl(boolean on) {
        if (curled == on) return;
        curled = on;
        // 睡醒伸个懒腰
        if (!on) act("act_stretch");
    }

    boolean curled() {
        return curled;
    }

    /** 整身姿势（{@code soak} 泡温泉、{@code pact_wait} 跪坐歇着）；null 收起。小动作播完回到它。 */
    void pose(@Nullable String name) {
        pose = name;
    }

    @Nullable String pose() {
        return pose;
    }

    /** 吃到最喜欢的：开心一阵。 */
    void delighted(long now) {
        delightUntil = now + 200;
    }

    /** 附近有狗（狼）：慌一阵，耳朵压低（日本传说里狐狸怕狗）。 */
    void afraid(long now) {
        afraidUntil = now + 40;
    }

    void petted(long now) {
        pets = now - pettedAt < 600 ? pets + 1 : 1;
        pettedAt = now;
        act("act_petted");
    }

    void pouted(long now) {
        poutAt = now;
    }

    /** 说话时摆 {@code face} 这个表情 {@code ticks} 刻（之后说着话就是安心的样子）。 */
    void talk(String face, long now, int ticks) {
        talkFace = face;
        talkFaceUntil = now + ticks;
    }

    void tick(long now) {
        if (act != null && now >= actUntil) act = null;
        if (now % 10 == 0 && !c.leaving()) {
            ServerPlayer owner = c.ownerPlayer();
            if (owner != null) {
                if (!acting() && !curled && now >= nextAct) react(owner, now);
                chooseFace(owner, now);
            }
        }
        String misc = act != null ? act : curled ? "sleep" : pose != null ? pose : face == null ? "" : face;
        c.setMisc(misc);
    }

    // ---------------------------------------------------------------- 遇到事情：小动作

    private void react(ServerPlayer owner, long now) {
        OwnerWatch w = OwnerWatch.of(owner);
        boolean engaged = c.mind() != null && c.mind().engaged();
        double dist = owner.distanceTo(c);
        // 主人从远处回来：挥手
        if (dist > 20) {
            if (farSince < 0) farSince = now;
        } else if (dist < 6) {
            if (farSince >= 0 && now - farSince > 200 && !engaged) {
                farSince = -1;
                act("act_wave");
                return;
            }
            farSince = -1;
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
        var bond = owner.getData(Companions.BOND);
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

    private boolean lookingAtMe(ServerPlayer owner) {
        Vec3 to = c.getEyePosition().subtract(owner.getEyePosition());
        double d = to.length();
        return d < 7 && d > .5 && owner.getViewVector(1).dot(to.scale(1 / d)) > Math.cos(Math.toRadians(12));
    }

    // ---------------------------------------------------------------- 平时：情绪

    private void chooseFace(ServerPlayer owner, long now) {
        OwnerWatch w = OwnerWatch.of(owner);
        Holder<Biome> here = c.level().getBiome(c.blockPosition());
        if (biome != null && !here.is(biome) && now > 200) curiousUntil = now + 100;
        biome = here;
        boolean engaged = c.mind() != null && c.mind().engaged();
        String want;
        if (engaged) want = "face_angry";
        else if (c.talking()) want = now < talkFaceUntil && talkFace != null ? talkFace : "face_content";
        else if (now < afraidUntil) want = "face_flustered";
        else if (now - surprisedAt < 30) want = "face_surprised";
        else if (now < delightUntil) want = "face_happy";
        else if (owner.getHealth() < owner.getMaxHealth() * .35f) want = "face_sad";
        else if (now - pettedAt < 200) want = pets >= 3 ? "face_shy" : "face_happy";
        else if (now - poutAt < 300) want = "face_pout";
        else if (now < curiousUntil) want = "face_curious";
        else if (night(c.level()) && w.activity(now, owner.isSleeping()).ordinal() >= OwnerWatch.Activity.IDLE.ordinal()) want = "face_sleepy";
        else {
            // 没什么事：多数时候是平常的样子，有时眯起眼安心地笑
            if (now >= faceUntil) {
                content = c.getRandom().nextFloat() < .35f;
                faceUntil = now + 300 + c.getRandom().nextInt(200);
            }
            want = content ? "face_content" : null;
        }
        face = want;
    }

    private static boolean night(net.minecraft.world.level.Level level) {
        return level.isDarkOutside();
    }
}
