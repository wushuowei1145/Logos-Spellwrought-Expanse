package dev.logos.dimension.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 处决特写的时间表，按刻算（可以有小数）。服务端（定住目标多久）和每个看得见目标的客户端（镜头、身体、画面、声音）
 * 都由同一个 {@link ExecutionShot} 算出同一张表，所以只发一次包，不用逐刀同步。
 * <pre>
 * 抛起 ─ 黑白 ─ 一刀刀划过（越来越快）─ 静下来 ─ 重击（变红，定格）─ 拍下去 ─ 落地白一拍 ─ 恢复颜色，镜头回去
 * </pre>
 * 默认 12 刀时，从抛起到落地约 2 秒，镜头再用半秒回到玩家。
 * <p>可交互的处决（{@link ExecutionShot#interactive}）：第一刀的时刻起留 {@link ExecutionShot#window} 刻让处决的人自己点，
 * 刀在点到时由 {@link #add} 一刀刀加进来；重击、拍下、落地的时刻只看这段时长，和砍了几刀无关，所以两端仍能各算一遍。
 * 机位在这段的三分之一、三分之二处切，节拍是第一刀和每次切机位后的第一刀。
 * <p>飞身刺（{@link ExecutionShot.Choreo#THRUST}）没有刀，时刻是固定的（下面 {@code THRUST_} 开头的几个，整刻，服务端可以直接拿来排事）：
 * <pre>
 * 原画顿帧 ─ 黑白：蹲下蓄势 ─ 起跳 ─ 刺中 ─ 刺着不动 ─ 定格（不读深度，服务端趁这时换场景）─ 接着演的彩色过场接手
 * </pre>
 * 阶段只有 {@link Phase#LAUNCH}（原画顿帧）、{@link Phase#INK}（黑白）、{@link Phase#AFTER}（定格的尾巴）；不对调、不变红、落地不白。
 */
public final class ExecutionTimeline {
    /** 画面按 24 帧一拍地换（和黑白闪一样），一拍是多少刻。身体的动作也一拍一拍地跳，像动画里的「一拍一张」。 */
    public static final float FRAME = 20f / 24f;
    /** 抛到最高点。 */
    public static final float APEX = 4.4f;
    /** 快到最高点时画面突然变黑白。 */
    public static final float INK = 3.4f;
    public static final float FIRST_SLASH = 6;
    /**
     * 第一刀和第二刀隔多久，最后两刀隔多久：越砍越快。最短也要隔两拍多，
     * 对调的那一拍和下一刀对调的那一拍之间才有一拍换回来，否则两刀连成一片看不出对调了两次。
     */
    static final float FIRST_GAP = 2.4f, LAST_GAP = 1.7f;
    /** 一刀从画面一边划到另一边要多久（约两拍）。 */
    public static final float STROKE = 1.7f;
    /** 刀头划过身体中心是这一刀的第几成：身体在这时挨打，画面在这时对调。 */
    public static final float CONTACT = .35f;
    /** 最后一刀划完到重击之间静下来多久：蓄势。 */
    public static final float QUIET = 4;
    /** 重击后定格多久（顿帧）。 */
    public static final float HIT_STOP = 5;
    /** 从半空拍到地上用多久。 */
    public static final float SLAM = 2.4f;
    /** 落地后停多久镜头才往回走。 */
    public static final float SETTLE = 3;
    /** 落地后站起来用多久，和镜头回程差不多一起结束。 */
    public static final float RECOVER = 9;
    /** 镜头切进来、回到玩家各用多久。 */
    public static final int BLEND_IN = 4, BLEND_OUT = 11;
    /** 节拍上的两次对调至少隔多久（0.35 秒）：这样任何一秒里都不会超过三次全屏黑白交替。 */
    public static final float MIN_BEAT_GAP = 7;

    // 飞身刺的时刻：客户端从收到包起算的刻数（服务端发包那一刻起算也一样，同一条连接上后面的包延迟相同）

    /** 飞身刺：打中后原画顿帧到这一刻，镜头同时切到侧面；然后画面变黑白（第一下明暗变化）。 */
    public static final int THRUST_INK = 3;
    /** 出手的人在目标前方几格外蹲下蓄势，从这一刻起。 */
    public static final int THRUST_CROUCH = 3;
    /** 起跳，沿一道弧线飞过去。 */
    public static final int THRUST_LEAP = 6;
    /** 刺中胸口：一笔直的刺光、火花、视角猛推一下、镜头抖；目标下一拍往后一仰。 */
    public static final int THRUST_CONTACT = 11;
    /** 从这一刻起画面定格：一直放这一刻画出来的那一帧，不再读深度，服务端趁这时把人和目标传送走也看不出来。 */
    public static final int THRUST_FREEZE = 14;
    /**
     * 接着演的一方最晚在这一刻换场景（传送人和目标）：服务端的实体追踪最多晚两刻发出去，远处的实体再插值 3 刻，
     * 都要在 {@link #THRUST_COLOUR} 之前落定。旁观的人（没有定格）也在这一刻不再按特写摆人。
     */
    public static final int THRUST_SWAP = THRUST_FREEZE + 2;
    /** 接着演的彩色过场该在这一刻接手（它的运镜一到，黑白就结束：第二下明暗变化）。 */
    public static final int THRUST_COLOUR = 22;
    /** 到了 {@link #THRUST_COLOUR} 还没接手时再定格多久：网络慢一点也不会先回到玩家。 */
    public static final int THRUST_TAIL = 8;
    /** 飞身刺的镜头离两人多远（格）：出手的人自己看得近，旁边看特写的人看得远一些。 */
    public static final double THRUST_NEAR = 5.5, THRUST_FAR = 8.5;

    public enum Phase {
        /** 抛起，还是原画。 */
        LAUNCH,
        /** 黑白，刀光一道道穿过。 */
        INK,
        /** 重击后背景是红的，一直到落地。 */
        RED,
        /** 落地那一拍全白。 */
        IMPACT,
        /** 恢复颜色，镜头回去。 */
        AFTER,
        DONE
    }

    /**
     * 一刀。角度和位置都在画面上：从目标中心看，刀朝 {@code angle}（弧度，0 朝右、逆时针）方向划过去。
     *
     * @param offset 刀离目标中心多远，按目标在画面上的半径算，所以每刀都穿过身体
     * @param bend 刀痕弯多少（画面高度的倒数）
     * @param width 粗细，1 是普通的一刀
     * @param force 身体被打歪多少，1 是普通的一刀
     * @param twist 身体被打得绕竖轴拧多少（弧度）
     */
    public record Slash(float start, float angle, float offset, float bend, float width, float force, float twist) {
        public float contact() {
            return start + STROKE * CONTACT;
        }

        /** 第 {@code age} 刻时刀头走到第几成：0 还没进画面，1 连刀尾都出去了。 */
        public float progress(float age) {
            return (age - start) / STROKE;
        }
    }

    /**
     * 一个机位，从 {@code start} 刻起用，直到下一个机位硬切过来。镜头在目标最高点的身体中心朝这个方向退开：
     * 方向是目标朝向的局部坐标（{@code side} 右手边、{@code up} 上、{@code forward} 前方），{@code roll} 是画面歪多少度。
     */
    public record Cut(float start, double side, double up, double forward, double distance, float roll) {}

    /** 可交互的处决：攒满几刀时镜头推到最近、速度线最密。 */
    static final int LIVE_BUILD = 16;

    private final ExecutionShot shot;
    private final List<Slash> slashes;
    private final Slash finisher;
    private final float finalHit, slamStart, impact, returnAt;
    private final List<Cut> cuts;
    /** 节拍上的刀（第几刀）：第一刀、切机位的那两刀、最后一刀，太挤的去掉。 */
    private final List<Integer> beats;
    /** 可交互时：上一刀的角度（下一刀相对它转）。 */
    private double lastAngle;

    public ExecutionTimeline(ExecutionShot shot) {
        this.shot = shot;
        Random random = new Random(shot.seed());
        if (shot.choreo() == ExecutionShot.Choreo.THRUST) {
            // 没有刀：刺中的那一笔客户端自己画。重击、落地都放在黑白结束的那一刻，阶段里不出现红色和落地白
            slashes = List.of();
            beats = List.of();
            finisher = new Slash(THRUST_CONTACT - STROKE * CONTACT, 0, 0, 0, 1, 1, 0);
            finalHit = slamStart = impact = THRUST_COLOUR;
            returnAt = THRUST_COLOUR + THRUST_TAIL;
            // 只有一个机位：在两人连线的哪一侧由种子定
            cuts = List.of(new Cut(0, random.nextBoolean() ? 1 : -1, 0, 0, THRUST_NEAR, 0));
            return;
        }
        if (shot.interactive()) {
            slashes = new ArrayList<>();
            beats = new ArrayList<>();
            lastAngle = random.nextDouble() * Math.TAU;
            float end = windowEnd();
            finalHit = end + QUIET;
            double down = Math.toRadians(30 + random.nextDouble() * 15);
            float finalAngle = (float) (random.nextBoolean() ? -down : Math.PI + down);
            finisher = new Slash(finalHit - STROKE * CONTACT, finalAngle, 0, (float) (random.nextDouble() - .5) * .3f, 4, 2.4f, 0);
            slamStart = finalHit + HIT_STOP;
            impact = slamStart + SLAM;
            returnAt = impact + SETTLE;
            double mirror = random.nextBoolean() ? 1 : -1;
            float third = shot.window() / 3f;
            cuts = List.of(new Cut(0, mirror, -.42, .45, 4.3, 0),
                    new Cut(FIRST_SLASH + third, -.85 * mirror, -.18, .62, 3.3, (float) (14 * mirror)),
                    new Cut(FIRST_SLASH + 2 * third, -.25 * mirror, -.6, 1, 3.7, (float) (-11 * mirror)),
                    new Cut(end, .9 * mirror, -.22, -.55, 5.4, (float) (7 * mirror)));
            return;
        }
        int n = shot.slashes();
        var list = new ArrayList<Slash>(n);
        float at = FIRST_SLASH;
        double angle = random.nextDouble() * Math.TAU;
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                at += n <= 2 ? FIRST_GAP : FIRST_GAP + (LAST_GAP - FIRST_GAP) * (i - 1) / (n - 2f);
                // 相邻两刀至少差 60 度，看得出是换了方向
                angle += (random.nextBoolean() ? 1 : -1) * Math.toRadians(60 + random.nextDouble() * 100);
            }
            list.add(new Slash(at, (float) (angle % Math.TAU), (float) (random.nextDouble() - .5) * .9f, (float) (random.nextDouble() - .5) * .5f,
                    .6f + random.nextFloat() * .6f, .75f + random.nextFloat() * .5f, (random.nextBoolean() ? 1 : -1) * (.15f + random.nextFloat() * .2f)));
        }
        slashes = List.copyOf(list);
        float last = list.getLast().start() + STROKE;
        finalHit = last + QUIET;
        // 重击从上往下斜着劈，接下来正好把它往下拍
        double down = Math.toRadians(30 + random.nextDouble() * 15);
        float finalAngle = (float) (random.nextBoolean() ? -down : Math.PI + down);
        finisher = new Slash(finalHit - STROKE * CONTACT, finalAngle, 0, (float) (random.nextDouble() - .5) * .3f, 4, 2.4f, 0);
        slamStart = finalHit + HIT_STOP;
        impact = slamStart + SLAM;
        returnAt = impact + SETTLE;

        // 三个机位轮流硬切：侧面低角度、另一侧近一点歪着、正面仰拍；最后一刀划完切到远一点的机位，静下来等重击
        double mirror = random.nextBoolean() ? 1 : -1;
        var cutList = new ArrayList<Cut>();
        cutList.add(new Cut(0, mirror, -.42, .45, 4.3, 0));
        if (n >= 4) {
            cutList.add(new Cut(list.get(n / 3).start() - .05f, -.85 * mirror, -.18, .62, 3.3, (float) (14 * mirror)));
            cutList.add(new Cut(list.get(2 * n / 3).start() - .05f, -.25 * mirror, -.6, 1, 3.7, (float) (-11 * mirror)));
        }
        cutList.add(new Cut(last, .9 * mirror, -.22, -.55, 5.4, (float) (7 * mirror)));
        cuts = List.copyOf(cutList);

        // 节拍：和切镜落在同一刀上，切镜加对调是一个重音；挨得太近的去掉，每秒不超过三次
        var candidates = new java.util.TreeSet<Integer>();
        candidates.add(0);
        if (n >= 4) {
            candidates.add(n / 3);
            candidates.add(2 * n / 3);
        }
        candidates.add(n - 1);
        var beatList = new ArrayList<Integer>();
        for (int i : candidates) {
            if (beatList.isEmpty() || list.get(i).contact() - list.get(beatList.getLast()).contact() >= MIN_BEAT_GAP) beatList.add(i);
        }
        beats = List.copyOf(beatList);
    }

    /** 节拍上的刀（第几刀）。 */
    public List<Integer> beats() {
        return List.copyOf(beats);
    }

    public boolean interactive() {
        return shot.interactive();
    }

    public ExecutionShot.Choreo choreo() {
        return shot.choreo();
    }

    /** 是飞身刺。 */
    public boolean thrust() {
        return shot.choreo() == ExecutionShot.Choreo.THRUST;
    }

    /** 可交互时：点击窗口在哪一刻关上（之后不再收刀，静一会儿就是重击）；不可交互时是最后一刀划完的时刻；飞身刺是刺中的时刻。 */
    public float windowEnd() {
        if (thrust()) return THRUST_CONTACT;
        return shot.interactive() ? FIRST_SLASH + shot.window() : slashes.getLast().start() + STROKE;
    }

    /** 可交互时，第 {@code age} 刻还收不收新的一刀。 */
    public boolean open(float age) {
        return shot.interactive() && age >= FIRST_SLASH && age < windowEnd();
    }

    /**
     * 可交互时加一刀，从第 {@code at} 刻开始划（刀头走 {@link #CONTACT} 成时打中）。角度、位置、力度按种子和这是第几刀
     * 随出来，每端加同样多刀就一样。返回这一刀。
     */
    public Slash add(float at) {
        if (!shot.interactive()) throw new IllegalStateException("不是可交互的处决");
        int i = slashes.size();
        Random random = new Random(shot.seed() * 0x9E3779B97F4A7C15L + i * 31L + 17);
        if (i > 0) lastAngle += (random.nextBoolean() ? 1 : -1) * Math.toRadians(60 + random.nextDouble() * 100);
        Slash slash = new Slash(at, (float) (lastAngle % Math.TAU), (float) (random.nextDouble() - .5) * .9f, (float) (random.nextDouble() - .5) * .5f,
                .6f + random.nextFloat() * .6f, .75f + random.nextFloat() * .5f, (random.nextBoolean() ? 1 : -1) * (.15f + random.nextFloat() * .2f));
        slashes.add(slash);
        // 节拍：第一刀，以及每次切机位后的第一刀；和上一个节拍隔得太近就不算
        Cut cut = cut(slash.contact());
        boolean firstAfterCut = i == 0 || cut(slashes.get(i - 1).contact()) != cut;
        if (firstAfterCut && (beats.isEmpty() || slash.contact() - slashes.get(beats.getLast()).contact() >= MIN_BEAT_GAP)) beats.add(i);
        return slash;
    }

    /** 第 {@code i} 刀划过时是否对调。飞身刺从不对调。 */
    public boolean swaps(int i) {
        if (thrust()) return false;
        return switch (shot.swaps()) {
            case EVERY -> true;
            case BEATS -> beats.contains(i);
            case NONE -> false;
        };
    }

    /** 已经有几成的刀划过了身体（0 到 1）：镜头越推越近、速度线越来越密，按它走。飞身刺刺中之前是 0，之后是 1。 */
    public float buildUp(float age) {
        if (thrust()) return age >= THRUST_CONTACT ? 1 : 0;
        int done = 0;
        for (Slash s : slashes) if (s.contact() <= age) done++;
        if (shot.interactive()) return Math.min(1, done / (float) LIVE_BUILD);
        return done / (float) slashes.size();
    }

    public ExecutionShot shot() {
        return shot;
    }

    /** 重击之前的刀；可交互时是到现在为止加进来的（只读）。 */
    public List<Slash> slashes() {
        return java.util.Collections.unmodifiableList(slashes);
    }

    /** 最后的重击：比普通的一刀粗得多。 */
    public Slash finisher() {
        return finisher;
    }

    /** 重击打中的那一刻：背景变红，开始定格。 */
    public float finalHit() {
        return finalHit;
    }

    public float slamStart() {
        return slamStart;
    }

    /** 落地。 */
    public float impact() {
        return impact;
    }

    /** 镜头从这一刻起往回走。 */
    public float returnAt() {
        return returnAt;
    }

    /** 镜头在特写机位上停多少整刻（不含切进来的那几刻）。 */
    public int hold() {
        if (thrust()) return duration() - THRUST_INK;
        return (int) Math.ceil(returnAt) - BLEND_IN;
    }

    /**
     * 整段（含镜头回到玩家）一共多少刻。服务端按它定住目标。
     * 飞身刺只到黑白结束：一般由接着演的过场接手；没人接手时镜头之后再用 {@link #blendOut} 刻回到玩家。
     */
    public int duration() {
        if (thrust()) return THRUST_COLOUR + THRUST_TAIL;
        return BLEND_IN + hold() + BLEND_OUT;
    }

    /** 镜头切进来用几刻：飞身刺正好是原画顿帧的那几刻。 */
    public int blendIn() {
        return thrust() ? THRUST_INK : BLEND_IN;
    }

    /** 镜头回到玩家用几刻。 */
    public int blendOut() {
        return BLEND_OUT;
    }

    /** 飞身刺刺中、抛起的样式重击打中的那一刻。 */
    public float contact() {
        return thrust() ? THRUST_CONTACT : finalHit;
    }

    /** 飞身刺这一刻是不是定格（放定格那一帧，不读深度）：从 {@link #THRUST_FREEZE} 到黑白结束，包括等接手的尾巴。 */
    public boolean freezing(float age) {
        return thrust() && age >= THRUST_FREEZE && age < THRUST_COLOUR + THRUST_TAIL;
    }

    public List<Cut> cuts() {
        return cuts;
    }

    /** 第 {@code age} 刻用的机位。 */
    public Cut cut(float age) {
        Cut current = cuts.getFirst();
        for (Cut c : cuts) if (age >= c.start()) current = c;
        return current;
    }

    public Phase phase(float age) {
        if (thrust()) {
            // 飞身刺：原画顿帧、黑白、定格等接手的尾巴
            if (age < THRUST_INK) return Phase.LAUNCH;
            if (age < THRUST_COLOUR) return Phase.INK;
            return age < duration() ? Phase.AFTER : Phase.DONE;
        }
        if (age < INK) return Phase.LAUNCH;
        if (age < finalHit) return Phase.INK;
        if (age < impact) return Phase.RED;
        if (age < impact + FRAME) return Phase.IMPACT;
        if (age < duration()) return Phase.AFTER;
        return Phase.DONE;
    }

    /** 第 {@code age} 刻是第几拍。 */
    public static int frame(float age) {
        return (int) Math.floor(age / FRAME);
    }

    /** 落到这一拍的开头：画面和身体一拍一拍地换。 */
    public static float stepped(float age) {
        return frame(age) * FRAME;
    }

    /** 这一拍是不是正好有一刀对调的刀划过身体：对调黑白就在这一拍，下一拍换回来。 */
    public boolean swapped(float age) {
        if (phase(age) != Phase.INK) return false;
        int f = frame(age);
        for (int i = 0; i < slashes.size(); i++) if (swaps(i) && frame(slashes.get(i).contact()) == f) return true;
        return false;
    }

    /** 这一段里一共对调几拍。 */
    public int swapFrames() {
        var frames = new java.util.HashSet<Integer>();
        for (int i = 0; i < slashes.size(); i++) {
            float contact = slashes.get(i).contact();
            if (swaps(i) && phase(contact) == Phase.INK) frames.add(frame(contact));
        }
        return frames.size();
    }

    /**
     * 挨一下的力度随时间的变化：一下子打歪，然后慢慢回来。{@code since} 是挨打后过了多少刻，{@code peak} 刻时最歪（值为 1）。
     */
    public static float punch(float since, float peak) {
        if (since <= 0) return 0;
        float t = since / peak;
        return (float) (t * Math.exp(1 - t));
    }
}
