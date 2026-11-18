package dev.logos.dimension.companion;

import java.util.Map;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import org.jspecify.annotations.Nullable;

/**
 * 精灵公主的表情和小动作（docs/ELF_PRINCESS_COMPANION.md 第 7 节）。动画见她模型的 README（forest-elf-model/elf_princess_companion）；
 * 这里只决定什么时候用哪一个。性格落在这几处：
 * <ul>
 *   <li>天然呆、缺一点危机感：敌人冒出来时她先愣一下，过一拍才「哇！」地吓一跳（{@code act_late_startle}）；新地方先发呆（{@code face_daze}）。</li>
 *   <li>使命感：每天早上为森林祈愿（{@code act_pray}）；在林子里偶尔停下来听森林（{@code act_listen}）；主人打倒强敌时给自己打气（{@code act_resolve}）。</li>
 *   <li>笨拙的温柔：主人受伤就担心（{@code face_worried}），主人摔了一跤也是担心，不笑话他。</li>
 * </ul>
 */
final class PrincessMood extends CompanionMood {
    /** 每段 misc 动画的长度（刻），与动画文件一致（单元测试对过）。 */
    static final Map<String, Integer> LENGTH = Map.ofEntries(Map.entry("summon", 84), Map.entry("act_curtsy", 52), Map.entry("act_sheepish", 40),
            Map.entry("act_pray", 72), Map.entry("act_resolve", 44), Map.entry("act_head_tilt", 36), Map.entry("act_listen", 60),
            Map.entry("act_wave", 40), Map.entry("act_petted", 56), Map.entry("act_late_startle", 56), Map.entry("eat", 48), Map.entry("refuse", 20),
            Map.entry("withdraw", 72));
    /** 敌人冒出来以后，她过多久才反应过来（刻）。 */
    static final int LATE = 14;

    /** 吓一跳的动作里，到这一刻（刻）才「哇！」地跳起来，之后才回过神来出手。 */
    static final int STARTLE_JUMP = 28;

    private long strongKillSeen, fallSeen, prayDay = -1, nextTilt, nextListen, foeSeen = -10_000, startleAt = -1, startleBegan = -10_000, nextStartle;
    private int lookedAt;
    private boolean content;
    private long faceUntil, worriedUntil = -10_000;

    PrincessMood(CompanionEntity c) {
        super(c, LENGTH);
    }

    /** 睡醒：歪头眨眨眼，像是忘了自己在哪儿。 */
    @Override
    protected void wokeUp() {
        act("act_head_tilt");
    }

    // ---------------------------------------------------------------- 遇到事情：小动作

    @Override
    protected void react(ServerPlayer owner, long now) {
        OwnerWatch w = OwnerWatch.of(owner);
        boolean engaged = engaged();
        double dist = owner.distanceTo(c);
        // 慢半拍的惊吓：敌人冒出来那一刻她没反应，过 LATE 刻才吓一跳（一阵只吓一次）
        if (startleAt >= 0 && now >= startleAt) {
            startleAt = -1;
            startleBegan = now;
            nextStartle = now + 2400;
            act("act_late_startle");
            CompanionLines.say(c, owner, "late_startle", 40);
            return;
        }
        if (ownerReturned(dist, now) && !engaged) {
            act("act_wave");
            return;
        }
        if (w.lastStrongKill > strongKillSeen && now - w.lastStrongKill < 40) {
            strongKillSeen = w.lastStrongKill;
            act("act_resolve");
            return;
        }
        if (now - w.lastHurt < 10 && w.lastHurtAmount >= 8 && w.lastHurt > surprisedAt) {
            surprisedAt = now;
            worriedUntil = now + 160;
        }
        if (w.lastFall > fallSeen && now - w.lastFall < 30) {
            // 主人摔了一跤：担心地看着，不笑话
            fallSeen = w.lastFall;
            worriedUntil = now + 100;
        }
        if (engaged) return;
        var bond = c.bond(owner);
        if (bond.satiety() < CompanionBond.HUNGRY && now >= nextHungry && dist < 10) {
            nextHungry = now + 3600;
            CompanionLines.say(c, owner, "hungry", 50);
            act("act_sheepish");
            return;
        }
        long clock = c.level().getOverworldClockTime(), day = clock / 24000, time = clock % 24000;
        // 每天早上：为森林祈愿
        if (time < 1500 && day != prayDay && dist < 10) {
            prayDay = day;
            act("act_pray");
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
        // 在林子里：偶尔停下来听森林的声音
        if (now >= nextListen && c.getRandom().nextInt(90) == 0 && c.level().getBiome(c.blockPosition()).is(BiomeTags.IS_FOREST)) {
            nextListen = now + 2400;
            act("act_listen");
        }
    }

    /**
     * 还没回过神来（天然呆）：敌人冒出来以后、她吓一跳跳起来之前，不出手。主人血少了（一半以下）就顾不上发呆了。
     */
    boolean hesitating(ServerPlayer owner, long now) {
        if (owner.getHealth() < owner.getMaxHealth() * .5f) {
            startleAt = -1;
            return false;
        }
        return startleAt >= 0 || "act_late_startle".equals(playing()) && now - startleBegan < STARTLE_JUMP;
    }

    /** 每半秒看一眼：有没有刚冒出来、冲着主人或她来的敌人（给慢半拍的惊吓用）。 */
    private void noticeFoes(ServerPlayer owner, long now) {
        boolean near = !c.level().getEntitiesOfClass(Mob.class, c.getBoundingBox().inflate(10),
                m -> m.isAlive() && m instanceof Enemy && (m.getTarget() == owner || m.getTarget() == c)).isEmpty();
        if (near && now - foeSeen > 200 && now >= nextStartle && startleAt < 0) startleAt = now + LATE;
        if (near) foeSeen = now;
    }

    // ---------------------------------------------------------------- 平时：情绪

    @Override
    protected @Nullable String chooseFace(ServerPlayer owner, long now) {
        OwnerWatch w = OwnerWatch.of(owner);
        noticeBiome(now);
        noticeFoes(owner, now);
        // 吓一跳之前：还在发呆，什么都没发觉
        if (startleAt >= 0) return "face_daze";
        if (engaged()) return "face_determined";
        if (c.talking()) return now < talkFaceUntil && talkFace != null ? talkFace : "face_content";
        if (now - surprisedAt < 30) return "face_surprised";
        if (now < delightUntil) return "face_happy";
        if (owner.getHealth() < owner.getMaxHealth() * .35f || now < worriedUntil) return "face_worried";
        if (now - pettedAt < 200) return pets >= 3 ? "face_flustered" : "face_happy";
        if (now - poutAt < 300) return "face_pout";
        if (now < curiousUntil) return "face_daze";
        if (night(c.level()) && w.activity(now, owner.isSleeping()).ordinal() >= OwnerWatch.Activity.IDLE.ordinal()) return "face_sleepy";
        if (now >= faceUntil) {
            content = c.getRandom().nextFloat() < .4f;
            faceUntil = now + 300 + c.getRandom().nextInt(200);
        }
        return content ? "face_content" : null;
    }
}
