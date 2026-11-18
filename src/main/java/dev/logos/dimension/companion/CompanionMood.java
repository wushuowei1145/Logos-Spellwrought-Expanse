package dev.logos.dimension.companion;

import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * 同伴的表情和小动作（docs/COMPANIONS.md 第 2 节第 14 项）：每半秒按处境挑一种情绪（{@code face_*}，循环），
 * 遇到事情时插一个小动作（{@code act_*}，播一次）。都走 {@code misc} 控制器，优先级：
 * 入场 / 小动作 → 睡着 → 姿势 → 情绪。小动作有长短，播完自动交回情绪。
 *
 * <p>这里是每位都一样的部分（播、排队、记下发生过什么）；什么时候用哪一个表情、哪一个小动作，每位同伴自己写
 * （{@link #react}、{@link #chooseFace}），因为各自模型里的动画不一样（白梅：{@link ShiraumeMood}；精灵公主：{@link PrincessMood}）。
 */
abstract class CompanionMood {
    /** 两个小动作之间至少隔这么久，免得一直在演。 */
    static final int ACT_GAP = 200;

    protected final CompanionEntity c;
    /** 每段 misc 动画的长度（刻），与她的动画文件一致。 */
    private final Map<String, Integer> lengths;
    private @Nullable String act, face, pose;
    private long actUntil;
    protected long nextAct;
    private boolean curled;
    protected long pettedAt = -10_000, poutAt = -10_000, surprisedAt = -10_000, curiousUntil = -10_000, delightUntil = -10_000, afraidUntil = -10_000;
    protected long nextHungry;
    /** 说着话时的表情（对话里她回话时定），到哪一刻。 */
    protected @Nullable String talkFace;
    protected long talkFaceUntil;
    protected int pets;
    protected long farSince = -1;
    protected @Nullable Holder<Biome> biome;

    protected CompanionMood(CompanionEntity c, Map<String, Integer> lengths) {
        this.c = c;
        this.lengths = lengths;
    }

    protected long now() {
        return c.level().getGameTime();
    }

    int length(String name) {
        return lengths.getOrDefault(name, 40);
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
        play(name, length(name));
        nextAct = now() + length(name) + ACT_GAP;
    }

    boolean acting() {
        return act != null && now() < actUntil;
    }

    /** 掐掉正在播的小动作（她要出手了）。入场不掐。 */
    void cut() {
        if (act != null && !act.equals("summon")) {
            act = null;
            actUntil = 0;
        }
    }

    /** 正在播的小动作（没有为 null）。 */
    @Nullable String playing() {
        return acting() ? act : null;
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
        if (!on) wokeUp();
    }

    /** 睡醒的那一下（白梅：伸个懒腰）。 */
    protected void wokeUp() {}

    boolean curled() {
        return curled;
    }

    /** 整身姿势（白梅：{@code soak} 泡温泉、{@code pact_wait} 跪坐歇着）；null 收起。小动作播完回到它。 */
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

    /** 怕了一阵（白梅：附近有狗）。 */
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
                face = chooseFace(owner, now);
            }
        }
        String misc = act != null ? act : curled ? sleepAnimation() : pose != null ? pose : face == null ? "" : face;
        c.setMisc(misc);
    }

    /** 睡着时的动画。 */
    protected String sleepAnimation() {
        return "sleep";
    }

    /** 遇到事情：该插一个小动作就 {@link #act}。 */
    protected abstract void react(ServerPlayer owner, long now);

    /** 这半秒该是什么表情（null 是平常的样子）。 */
    protected abstract @Nullable String chooseFace(ServerPlayer owner, long now);

    /** 正在打（迎战、护主……）。 */
    protected boolean engaged() {
        return c.mind() != null && c.mind().engaged();
    }

    /** 主人正看着她（7 格内，视线偏不过 12°）。 */
    protected boolean lookingAtMe(ServerPlayer owner) {
        Vec3 to = c.getEyePosition().subtract(owner.getEyePosition());
        double d = to.length();
        return d < 7 && d > .5 && owner.getViewVector(1).dot(to.scale(1 / d)) > Math.cos(Math.toRadians(12));
    }

    /** 走进了另一种生物群系：好奇一阵（{@link #curiousUntil}）。 */
    protected void noticeBiome(long now) {
        Holder<Biome> here = c.level().getBiome(c.blockPosition());
        if (biome != null && !here.is(biome) && now > 200) curiousUntil = now + 100;
        biome = here;
    }

    /** 主人从远处回来：返回真时该挥手了（离开 20 格以上 10 秒，又回到 6 格内）。 */
    protected boolean ownerReturned(double dist, long now) {
        if (dist > 20) {
            if (farSince < 0) farSince = now;
        } else if (dist < 6) {
            boolean back = farSince >= 0 && now - farSince > 200;
            farSince = -1;
            return back;
        }
        return false;
    }

    protected static boolean night(net.minecraft.world.level.Level level) {
        return level.isDarkOutside();
    }
}
