package dev.logos.dimension.client.music;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.music.BenchSeat;
import dev.logos.dimension.music.InstrumentBlock;
import dev.logos.dimension.music.InstrumentKind;
import dev.logos.dimension.music.InstrumentSeat;
import dev.logos.dimension.music.StarbloomBenchBlock;
import dev.logos.dimension.music.orchestra.BeatFollower;
import dev.logos.dimension.music.orchestra.BeatMap;
import dev.logos.dimension.music.orchestra.ConductorPattern;
import dev.logos.dimension.music.orchestra.EnsemblePayloads;
import dev.logos.dimension.music.orchestra.Judgement;
import dev.logos.dimension.music.orchestra.PodiumBlock;
import dev.logos.dimension.music.orchestra.PodiumBlockEntity;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.CalculatePlayerTurnEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.jspecify.annotations.Nullable;

/**
 * 站在指挥台上拿着指挥棒时的操作（docs/ORCHESTRA.md 第 17 节）：
 * <ul>
 * <li><b>左键</b>：打一拍。乐团停着时打两拍起奏（预备拍就是这两拍的间隔，乐团在下一拍进来）；演奏中每一拍对到最近的拍子，
 *     乐团跟着你的速度（{@link BeatFollower}）。该来的一拍不来，乐团停在那里（延长），再打一拍接着走。
 *     按住左键拖动是<b>画拍子</b>：视角不动，鼠标是棒尖，往下落到底弹起来的那一下算一拍，画得大小就是力度。</li>
 * <li><b>右键</b>：准星指着的乐器（乐手、琴凳、乐谱架、管风琴的音管都算）选中它演奏的声部，再点一次取消；准星对着指挥台时照旧开始 / 停止。</li>
 * <li><b>滚轮</b>：选中声部的响度，没选中时全体的响度，一格 1 dB。</li>
 * </ul>
 * 不打拍子乐团就按设定的速度自己走；走下指挥台、放下指挥棒也是。只有自己看得到棒尖的轨迹、拍点的火花和左上角的提示。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class BatonInput {
    private static final Identifier HUD = LogosDimension.id("conducting_hud");
    /** 右键选声部看多远（格）。 */
    static final double REACH = 48;
    /** 画拍子：棒尖往下落的速度（像素 / 秒）超过这么多、再往回弹才算一拍；两拍至少隔这么久。 */
    private static final double DRAW_SPEED = 260, MIN_GAP_NS = 150_000_000L;
    /** 画拍子：落差这么多像素是平常的力度。 */
    private static final double NORMAL_STROKE = 160;

    private static @Nullable BlockPos podium;
    private static @Nullable BeatFollower follower;
    private static long countIn;
    private static float expression = 1;
    // 画拍子
    private static boolean drawing;
    private static double mouseX, mouseY, velocityY, strokeTop, strokeBottom;
    private static boolean falling;
    private static long lastIctus, lastFrame, pressed;
    // 反馈
    private static long feedbackAt;
    private static @Nullable Component feedback;
    private static int feedbackColor;
    private static long lastUse, lastTrail;
    // 判定（像音游）：最近一拍的档次、离拍子多少毫秒、什么时候、连击
    private static @Nullable Judgement judgement;
    private static double judgedMs;
    private static long judgedAt;
    private static int combo;
    /** 离拍子多少毫秒以内算按到拍子上（和判定的 GREAT 一样宽）。 */
    static final double ON_BEAT_MS = 80;
    // 准星上的金色星星：什么时候迸出的、每颗的方向（弧度）
    private static long clickedAt;
    private static final double[] STAR_ANGLES = new double[6];
    /** 测试用：代替真的鼠标（{x, y, 按下为 1}）；为 null 时用真的。 */
    static double @Nullable [] testMouse;
    /** 测试用：打过几拍（按键、画拍子都算）。 */
    static int beatsTaken;
    /** 刚发出去、还没同步回来的响度（声部 → dB；-1 是全体）。 */
    private static final Map<Integer, long[]> PENDING = new HashMap<>();

    private BatonInput() {}

    /** 自己正在给这座指挥台打拍子。 */
    static boolean leading(BlockPos at) {
        return at.equals(podium) && follower != null && follower.leading();
    }

    /** 指挥状态下自己脚下的指挥台；不在指挥时为 null。 */
    static @Nullable BlockPos conductingPodium() {
        Player p = Minecraft.getInstance().player;
        return p != null && PodiumBlock.conducting(p) ? PodiumBlock.under(p) : null;
    }

    private static @Nullable PodiumBlockEntity podiumEntity(BlockPos at) {
        var level = Minecraft.getInstance().level;
        return level == null ? null : PodiumBlock.entityAt(level, at);
    }

    // —— 输入 ——

    @SubscribeEvent
    static void keys(InputEvent.InteractionKeyMappingTriggered event) {
        var mc = Minecraft.getInstance();
        BlockPos at = conductingPodium();
        if (at == null || mc.screen != null) return;
        if (event.isAttack()) {
            event.setCanceled(true);
            event.setSwingHand(false);
            pressed = System.nanoTime();
            beat(at, pressed);
        } else if (event.isUseItem()) {
            HitResult hit = aim();
            // 准星对着指挥台：照旧开始 / 停止
            if (hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK
                    && mc.level.getBlockState(b.getBlockPos()).getBlock() instanceof PodiumBlock) {
                return;
            }
            event.setCanceled(true);
            event.setSwingHand(false);
            long now = System.nanoTime();
            if (now - lastUse < 200_000_000L) return;   // 按住右键会连着触发
            lastUse = now;
            select(at, hit);
        }
    }

    @SubscribeEvent
    static void scroll(InputEvent.MouseScrollingEvent event) {
        BlockPos at = conductingPodium();
        if (at == null || Minecraft.getInstance().screen != null || event.getScrollDeltaY() == 0) return;
        event.setCanceled(true);
        dynamics(at, event.getScrollDeltaY() > 0 ? 1 : -1);
    }

    /** 画拍子时视角不动（鼠标是棒尖）。 */
    @SubscribeEvent
    static void turn(CalculatePlayerTurnEvent event) {
        if (drawing) event.setMouseSensitivity(-1 / 3.0);   // 原版的灵敏度换算 (s·0.6+0.2)³ 在这里为 0
    }

    /** 测试、按键：打一拍。 */
    static void beat(BlockPos at, long now) {
        beatsTaken++;
        if (!at.equals(podium)) {
            podium = at;
            follower = null;
            countIn = 0;
        }
        Conducting.Ensemble e = Conducting.ensemble(at);
        if (e == null) {
            // 乐团停着：打两拍起奏
            PodiumBlockEntity be = podiumEntity(at);
            if (be == null || be.score().isEmpty()) return;
            long gap = now - countIn;
            if (countIn != 0 && gap >= 200_000_000L && gap <= 2_000_000_000L) {
                countIn = 0;
                follower = null;
                ClientPacketDistributor.sendToServer(new EnsemblePayloads.PodiumControl(at, EnsemblePayloads.PodiumControl.START, 0, (int) (gap / 1_000_000)));
                flash(Component.translatable("hud.logos_dimension.conducting.go"), 0xFFE8C15A);
                started = now;
                startGapMs = gap / 1_000_000.0;
            } else {
                countIn = now;
                flash(Component.translatable("hud.logos_dimension.conducting.one_more"), 0xFFF2EAD0);
            }
            return;
        }
        if (e.beats == null) return;
        if (follower == null) {
            follower = new BeatFollower(e.rateMilli > 0 ? e.rateMilli / 1000.0 : 1);
            // 刚用两拍起奏：把起奏那一拍当作上一拍，速度接得上
            if (now - started < 4_000_000_000L && startGapMs > 0) follower.primed(started, e.clock.position(started), startGapMs);
        }
        int pos = e.clock.position(now);
        boolean wasLeading = follower.leading(), wasHeld = follower.held();
        // 节拍器开着时按节拍器（设定的速度）判定，关着时按乐团的拍子
        PodiumBlockEntity be = podiumEntity(at);
        boolean metronome = be != null && be.metronome();
        double metronomeMs = metronome ? Metronome.offsetMs(e.beats, now) : 0;
        BeatFollower.Beat b = follower.tap(now, pos, e.beats);
        e.clock.set(pos, b.clockRate(), now);
        e.rateMilli = (int) Math.round(b.clockRate() * 1000);
        send(at, pos, b.clockRate(), false);
        // 刚接手、刚从延长接着走：节拍器从这里起按设定的速度走（位置不跳，拍子还是乐团的拍子）
        if (!wasLeading || wasHeld) Metronome.lead(e, now);
        judge(wasHeld ? 0 : metronome ? metronomeMs : b.errorMs(), now);
    }

    /**
     * 一拍：按到拍子上（{@link #ON_BEAT_MS} 以内）准星上迸出一小簇金色的星星，左上角写早晚多少毫秒。
     * 档次和连击照样算着（{@link Judgement}，留给以后的音游玩法），平时不显示。
     */
    private static void judge(double errorMs, long now) {
        Judgement j = Judgement.of(errorMs);
        judgement = j;
        judgedMs = errorMs;
        judgedAt = now;
        combo = j.combo(combo);
        boolean onBeat = Math.abs(errorMs) <= ON_BEAT_MS;
        if (onBeat) click(now);
        int color = onBeat ? 0xFFE8C15A : 0xFFB0B0B0;
        if (Math.abs(errorMs) < 5) flash(Component.translatable("hud.logos_dimension.conducting.on_time"), color);
        else flash(Component.translatable(errorMs < 0 ? "hud.logos_dimension.conducting.early" : "hud.logos_dimension.conducting.late",
            Math.round(Math.abs(errorMs))), color);
    }

    private static long started;
    private static double startGapMs;

    private static void send(BlockPos at, int pos, double clockRate, boolean held) {
        ClientPacketDistributor.sendToServer(new EnsemblePayloads.ConductBeat(at, pos, (int) Math.round(clockRate * 1000),
            Math.round(expression * 1000), held));
    }

    // —— 每一帧 ——

    @SubscribeEvent
    static void frame(RenderFrameEvent.Pre event) {
        var mc = Minecraft.getInstance();
        long now = System.nanoTime();
        BlockPos at = conductingPodium();
        if (at == null || mc.screen != null) {
            drawing = false;
            if (at == null && podium != null) {
                // 走下指挥台、放下指挥棒：服务端让乐团按设定的速度接着走
                podium = null;
                follower = null;
                countIn = 0;
            }
            lastFrame = now;
            return;
        }
        Conducting.Ensemble e = Conducting.ensemble(at);
        if (e == null && follower != null) follower = null;   // 乐团停了
        if (e != null && follower != null && follower.overdue(now)) {
            int pos = e.clock.position(now);
            e.clock.set(pos, 0, now);
            e.rateMilli = 0;
            send(at, pos, 0, true);
            flash(Component.translatable("hud.logos_dimension.conducting.fermata"), 0xFFF2EAD0);
        }
        draw(at, now);
        if (e != null) {
            PodiumBlockEntity be = podiumEntity(at);
            Metronome.update(at, e, leading(at), be != null && be.metronome(), now);
            trail(mc.player, now);
        }
        lastFrame = now;
    }

    /** 按住左键拖动：鼠标是棒尖，往下落、再弹起来的那一下算一拍，落差是力度。 */
    private static void draw(BlockPos at, long now) {
        var mc = Minecraft.getInstance();
        double[] fake = testMouse;
        boolean down = fake != null ? fake[2] > 0 : mc.options.keyAttack.isDown();
        double x = fake != null ? fake[0] : mc.mouseHandler.xpos(), y = fake != null ? fake[1] : mc.mouseHandler.ypos();
        if (!down) {
            drawing = false;
            mouseX = x;
            mouseY = y;
            return;
        }
        if (!drawing) {
            drawing = true;
            falling = false;
            velocityY = 0;
            strokeTop = strokeBottom = y;
            mouseX = x;
            mouseY = y;
            return;
        }
        double dt = Math.max(1e-3, (now - lastFrame) / 1e9);
        double dy = y - mouseY;   // 屏幕往下为正
        mouseX = x;
        mouseY = y;
        velocityY = velocityY * 0.5 + dy / dt * 0.5;
        if (velocityY > DRAW_SPEED) {
            falling = true;
            strokeBottom = Math.max(strokeBottom, y);
        } else if (falling && velocityY < -DRAW_SPEED * 0.3) {
            // 落到底弹起：一拍
            falling = false;
            double size = strokeBottom - strokeTop;
            strokeTop = strokeBottom = y;
            if (now - lastIctus > MIN_GAP_NS && now - pressed > MIN_GAP_NS) {
                lastIctus = now;
                expression = (float) (expression * 0.5 + Math.max(0.45, Math.min(1.45, size / NORMAL_STROKE)) * 0.5);
                beat(at, now);
            }
        } else if (!falling) {
            strokeTop = Math.min(strokeTop, y);
        }
    }

    /** 棒尖的轨迹：身前的拍子平面上，一串小金点（只有自己看得到）。 */
    private static void trail(Player player, long now) {
        if (now - lastTrail < 25_000_000L) return;
        lastTrail = now;
        Vec3 tip = tipInWorld(player);
        if (tip != null) player.level().addParticle(new DustParticleOptions(0xE8C15A, 0.35f), tip.x, tip.y, tip.z, 0, 0, 0);
    }

    /** 棒尖在世界里的大致位置：胸前一步远的竖直平面上，按 {@link Conducting#poseOf} 的图形。 */
    static @Nullable Vec3 tipInWorld(Player player) {
        Conducting.Pose pose = Conducting.poseOf(player);
        if (pose == null) return null;
        Vec3 fwd = Vec3.directionFromRotation(0, player.yBodyRot), right = new Vec3(-fwd.z, 0, fwd.x);
        double amp = pose.amplitude() * pose.blend();
        return player.position().add(0, 1.15, 0).add(fwd.scale(0.6)).add(right.scale(0.18 + pose.tip()[0] * 0.3 * amp))
            .add(0, pose.tip()[1] * 0.32 * amp, 0);
    }

    // —— 选声部、响度 ——

    /** 准星指着什么（方块或骑着座位的乐手），看 {@link #REACH} 格远。 */
    static @Nullable HitResult aim() {
        var mc = Minecraft.getInstance();
        Player player = mc.player;
        if (player == null) return null;
        Vec3 from = player.getEyePosition(), look = player.getViewVector(1);
        Vec3 to = from.add(look.scale(REACH));
        BlockHitResult block = mc.level.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        Vec3 end = block.getType() == HitResult.Type.MISS ? to : block.getLocation();
        var entity = ProjectileUtil.getEntityHitResult(mc.level, player, from, end, player.getBoundingBox().expandTowards(end.subtract(from)).inflate(1),
            e -> e instanceof LivingEntity && e.getVehicle() != null && !e.isSpectator(), 0);
        return entity != null ? entity : block;
    }

    /** 准星指着的东西是乐团里的哪件乐器（主块）；不是乐器时为 null。 */
    static @Nullable BlockPos memberAt(Level level, PodiumBlockEntity be, HitResult hit) {
        BlockPos pos = null;
        if (hit instanceof net.minecraft.world.phys.EntityHitResult eh) {
            Entity vehicle = eh.getEntity().getVehicle();
            if (vehicle instanceof BenchSeat seat) pos = seat.bench();
            else if (vehicle instanceof InstrumentSeat seat) pos = seat.instrument();
        } else if (hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK) {
            pos = bh.getBlockPos();
        }
        if (pos == null) return null;
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof StarbloomBenchBlock bench) {
            BlockPos linked = StarbloomBenchBlock.linked(level, pos, state);
            return linked != null ? linked : bench.mainPos(state, pos);
        }
        if (state.getBlock() instanceof InstrumentBlock block) return block.mainPos(state, pos);
        if (state.getBlock() instanceof dev.logos.dimension.music.organ.OrganPipeBlock
                || state.getBlock() instanceof dev.logos.dimension.music.organ.OrganTrumpetBlock) {
            // 管风琴的音管：算作最近的那座管风琴
            BlockPos best = null;
            double bestDist = Double.MAX_VALUE;
            for (BlockPos m : be.members()) {
                if (MusicClient.kindAt(m) != InstrumentKind.PIPE_ORGAN) continue;
                double d = m.distSqr(pos);
                if (d < bestDist) {
                    bestDist = d;
                    best = m;
                }
            }
            return best;
        }
        return null;
    }

    /** 右键：选中准星指着的乐器演奏的声部；它的声部已经选中时取消。 */
    static void select(BlockPos at, @Nullable HitResult hit) {
        var mc = Minecraft.getInstance();
        PodiumBlockEntity be = podiumEntity(at);
        if (be == null || hit == null || hit.getType() == HitResult.Type.MISS) return;
        BlockPos member = memberAt(mc.level, be, hit);
        if (member == null) return;
        if (!be.members().contains(member)) {
            mc.gui.setOverlayMessage(Component.translatable("message.logos_dimension.orchestra.not_member"), false);
            return;
        }
        int mask = be.mask(member);
        if (mask == 0) {
            mc.gui.setOverlayMessage(Component.translatable("message.logos_dimension.orchestra.resting"), false);
            return;
        }
        int current = Conducting.selectedPart(at);
        if (current >= 0 && (mask & (1 << current)) != 0) {
            Conducting.select(at, -1);
            mc.gui.setOverlayMessage(Component.translatable("message.logos_dimension.orchestra.selected_none"), false);
            return;
        }
        int part = Integer.numberOfTrailingZeros(mask);
        Conducting.select(at, part);
        mc.gui.setOverlayMessage(Component.translatable("message.logos_dimension.orchestra.selected", PodiumScreen.partName(be, part)), false);
    }

    /** 滚轮：选中声部的响度（没选中时全体），一格 1 dB。 */
    static void dynamics(BlockPos at, int step) {
        PodiumBlockEntity be = podiumEntity(at);
        if (be == null) return;
        int part = Conducting.selectedPart(at);
        int current = part >= 0 ? be.gain(part) : be.master();
        long[] pending = PENDING.get(part);
        long now = System.currentTimeMillis();
        if (pending != null && now - pending[1] < 1500) current = (int) pending[0];
        int db = Math.max(PodiumBlockEntity.MIN_GAIN, Math.min(PodiumBlockEntity.MAX_GAIN, current + step));
        PENDING.put(part, new long[]{db, now});
        if (part >= 0) {
            ClientPacketDistributor.sendToServer(new EnsemblePayloads.PodiumControl(at, EnsemblePayloads.PodiumControl.GAIN, part, db));
        } else {
            ClientPacketDistributor.sendToServer(new EnsemblePayloads.PodiumControl(at, EnsemblePayloads.PodiumControl.MASTER, db, 0));
        }
        Component who = part >= 0 ? PodiumScreen.partName(be, part) : Component.translatable("hud.logos_dimension.conducting.everyone");
        Minecraft.getInstance().gui.setOverlayMessage(Component.translatable("hud.logos_dimension.conducting.volume", who, signed(db)), false);
    }

    /** 测试用：这一声部（-1 为全体）刚调到的响度。 */
    static int pendingDb(int part) {
        long[] p = PENDING.get(part);
        return p == null ? Integer.MIN_VALUE : (int) p[0];
    }

    private static String signed(int db) {
        return (db > 0 ? "+" : "") + db + " dB";
    }

    private static void flash(Component text, int color) {
        feedback = text;
        feedbackColor = color;
        feedbackAt = System.nanoTime();
    }

    // —— 左上角的提示 ——

    @SubscribeEvent
    static void layers(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.CHAT, HUD, BatonInput::hud);
    }

    private static void hud(GuiGraphicsExtractor g, DeltaTracker delta) {
        var mc = Minecraft.getInstance();
        BlockPos at = conductingPodium();
        if (at == null || mc.options.hideGui || mc.screen != null) return;
        PodiumBlockEntity be = podiumEntity(at);
        if (be == null) return;
        var font = mc.font;
        Conducting.Ensemble e = Conducting.ensemble(at);
        long now = System.nanoTime();
        MutableComponent line1, line2;
        if (e == null) {
            line1 = Component.translatable(be.score().isEmpty() ? "hud.logos_dimension.conducting.no_score" : "hud.logos_dimension.conducting.count_in");
            line2 = Component.empty();
        } else {
            int pos = e.clock.position(now);
            line1 = Component.empty();
            if (e.beats != null) {
                BeatMap.Beat b = e.beats.at(Math.max(-1, pos));
                ConductorPattern.Conducted c = ConductorPattern.conduct(b);
                int bpm = (int) Math.round(60000.0 / c.beatMs() * (e.rateMilli > 0 ? e.rateMilli / 1000.0 : (follower != null ? follower.rate() : 1)));
                line1 = Component.translatable("hud.logos_dimension.conducting.position", b.bar() + 1, c.beat() + 1, c.beats(), bpm);
            }
            String state = e.rateMilli == 0 ? "fermata" : leading(at) ? "following" : e.leader != null ? "following_other" : "by_itself";
            line2 = Component.translatable("hud.logos_dimension.conducting." + state);
        }
        int part = Conducting.selectedPart(at);
        int db = part >= 0 ? be.gain(part) : be.master();
        if (pendingDb(part) != Integer.MIN_VALUE && PENDING.get(part) != null && System.currentTimeMillis() - PENDING.get(part)[1] < 1500) db = pendingDb(part);
        MutableComponent line3 = Component.translatable("hud.logos_dimension.conducting.selected",
            part >= 0 ? PodiumScreen.partName(be, part).copy().withColor(Conducting.PART_COLORS[part] | 0xFF000000)
                : Component.translatable("hud.logos_dimension.conducting.everyone"), signed(db));
        int w = Math.max(font.width(line1), Math.max(font.width(line2), font.width(line3))) + 8;
        boolean fresh = feedback != null && now - feedbackAt < 700_000_000L;
        if (fresh) w = Math.max(w, font.width(feedback) + 8);
        int h = 35 + (fresh ? 10 : 0);
        int x = 2, y = 2;
        g.fill(x, y, x + w, y + h, 0x90101014);
        g.text(font, line1, x + 4, y + 3, 0xFFE8E0D0);
        g.text(font, line2.withStyle(ChatFormatting.GRAY), x + 4, y + 13, 0xFFB8B0A0);
        g.text(font, line3, x + 4, y + 23, 0xFFE8E0D0);
        if (fresh) {
            int alpha = (int) (255 * (1 - (now - feedbackAt) / 700_000_000.0));
            g.text(font, feedback, x + 4, y + 33, (Math.max(16, alpha) << 24) | (feedbackColor & 0xFFFFFF));
        }
        stars(g, now);
    }

    private static void click(long now) {
        clickedAt = now;
        var rand = java.util.concurrent.ThreadLocalRandom.current();
        double turn = rand.nextDouble() * Math.PI * 2;
        for (int i = 0; i < STAR_ANGLES.length; i++) {
            STAR_ANGLES[i] = turn + Math.PI * 2 * i / STAR_ANGLES.length + (rand.nextDouble() - 0.5) * 0.6;
        }
    }

    /**
     * 按到拍子上时准星上的点击效果：中间一闪，六颗小金星往外飞（0.45 秒，先快后慢），边飞边变小、淡出。像素画的十字星。
     */
    private static void stars(GuiGraphicsExtractor g, long now) {
        if (clickedAt == 0) return;
        double age = Math.max(0, (now - clickedAt) / 1_000_000.0);
        if (age > 450) return;
        double t = age / 450;
        float cx = g.guiWidth() / 2f, cy = g.guiHeight() / 2f;
        if (age < 120) {
            float f = (float) (1 - age / 120);
            star(g, cx, cy, 3, argb(f, 0xFFF3B0), argb(f * 0.8f, 0xFFD54A));
        }
        double out = 1 - (1 - t) * (1 - t) * (1 - t);
        float alpha = (float) (t < 0.55 ? 1 : 1 - (t - 0.55) / 0.45);
        int arm = t < 0.4 ? 2 : 1;
        for (int i = 0; i < STAR_ANGLES.length; i++) {
            double r = 4 + 13 * out * (i % 2 == 0 ? 1 : 0.78);
            star(g, cx + (float) (Math.cos(STAR_ANGLES[i]) * r), cy + (float) (Math.sin(STAR_ANGLES[i]) * r), arm,
                argb(alpha, 0xFFF3B0), argb(alpha * 0.9f, 0xE8B23A));
        }
    }

    /** 一颗十字星：中心一点亮色，四条臂（长 arm）金色。 */
    private static void star(GuiGraphicsExtractor g, float x, float y, int arm, int core, int edge) {
        if ((core >>> 24) < 4) return;
        int ix = Math.round(x), iy = Math.round(y);
        g.fill(ix - arm, iy, ix + arm + 1, iy + 1, edge);
        g.fill(ix, iy - arm, ix + 1, iy, edge);
        g.fill(ix, iy + 1, ix + 1, iy + arm + 1, edge);
        g.fill(ix, iy, ix + 1, iy + 1, core);
    }

    private static int argb(float alpha, int rgb) {
        return ((int) (Math.max(0, Math.min(1, alpha)) * 255) << 24) | rgb;
    }

    @SubscribeEvent
    static void loggedOut(ClientPlayerNetworkEvent.LoggingOut event) {
        podium = null;
        follower = null;
        countIn = 0;
        PENDING.clear();
    }

    /** 测试用：起奏前两拍的间隔（毫秒）。 */
    static double countInGapMs() {
        return startGapMs;
    }

    /** 测试用：最近一拍的判定、离拍子多少毫秒、连击。 */
    static @Nullable Judgement judgement() {
        return judgement;
    }

    static double judgedMs() {
        return judgedMs;
    }

    static int combo() {
        return combo;
    }

    /** 测试用：最近一次星星什么时候迸出的（纳秒，没有为 0）。 */
    static long clickedAt() {
        return clickedAt;
    }

    /** 测试用：画拍子给的力度。 */
    static float expression() {
        return expression;
    }

    /** 测试用：回到没在指挥的样子。 */
    static void reset() {
        judgement = null;
        combo = 0;
        clickedAt = 0;
        Metronome.reset();
        podium = null;
        follower = null;
        countIn = 0;
        testMouse = null;
        drawing = false;
        expression = 1;
        PENDING.clear();
    }

    /** 测试用：自己打拍子的跟随者（没在打时为 null）。 */
    static @Nullable BeatFollower follower() {
        return follower;
    }
}
