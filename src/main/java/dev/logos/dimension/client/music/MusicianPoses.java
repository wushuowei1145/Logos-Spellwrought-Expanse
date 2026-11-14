package dev.logos.dimension.client.music;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.music.BenchSeat;
import dev.logos.dimension.music.HandheldInstrumentItem;
import dev.logos.dimension.music.InstrumentBlock;
import dev.logos.dimension.music.InstrumentKind;
import dev.logos.dimension.music.StarbloomBenchBlock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.renderstate.RegisterRenderStateModifiersEvent;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 坐在星辰花琴凳上演奏的人（docs/ORCHESTRA.md 第 14 节）：玩家、原版人形生物、附属 NPC 共用同一份手位。
 *
 * <ul>
 * <li>手持乐器：乐器和双手的位置来自 {@link HeldRig#place}，坐标是玩家模型根坐标（像素，以脖子为原点）。</li>
 * <li>键盘、竖琴：双手的位置写在琴凳坐标里（像素；琴凳朝北：x 向东、y 向上、z 向南，坐的人双脚在
 *     {@link StarbloomBenchBlock#FEET}），跟着这件乐器此刻在响的音走：低音左手、高音右手，弹下时手往下按一下。</li>
 * </ul>
 * 这两种坐标就是「手要到的位置」（IK 目标）：原版的人形模型手臂是直的，转过去指着它；NPC 的骨架有手肘，两段手臂正好够到
 * （{@link NpcMusician}）。以后别的骨架只要把这两种坐标换算到自己的骨骼上就能套用。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class MusicianPoses {
    /** HANDHELD 手持乐器；KEYS 键盘；HARP 竖琴；BOWED 大提琴、低音提琴琴后拉弓；MALLETS 钟琴、管钟、定音鼓拿槌敲（{@link StationPoses}）。 */
    public enum Mode { HANDHELD, KEYS, HARP, BOWED, MALLETS }

    /**
     * 一位演奏者此刻的样子。坐标都在「演奏者面朝北」的方块像素里（x 向东、y 向上、z 向南；琴凳上就是琴凳坐标）。
     * @param kind 手里的手持乐器，或琴凳关联的乐器、工位上的乐器
     * @param move 手持乐器、拉弓的琴在响时为 1（弓来回、乐器轻摆）
     * @param left 键盘、竖琴：左手的位置；敲的乐器：左手槌头的位置（像素）；手持乐器、拉弓时为 null，敲的乐器左手不拿槌时也为 null
     * @param right 同上，右手
     * @param feet 演奏者双脚的位置
     * @param standing 站着演奏（低音提琴、钟琴、管钟）
     * @param seat 坐着时凳面比双脚高多少像素；edge 凳面前沿在双脚前多少像素
     */
    public record Musician(Mode mode, InstrumentKind kind, float move, @Nullable Vector3f left, @Nullable Vector3f right, Vector3f feet,
                           boolean standing, float seat, float edge) {}

    static final ContextKey<Musician> MUSICIAN = new ContextKey<>(LogosDimension.id("musician"));
    /** 一位键盘手、竖琴手两只手此刻在键盘上的位置（0 最低音、1 最高音，平滑地移过去）和按下的程度。 */
    static final class Hands {
        float low = 0.3f, high = 0.7f, lowPress, highPress;
        long last;
    }

    private static final Map<Integer, Hands> HANDS = new HashMap<>();
    /** 键盘一半以下归左手。 */
    private static final int SPLIT = 60;

    private MusicianPoses() {}

    /** 这位坐在琴凳上的人此刻怎么演奏；没坐在琴凳上、手里没有乐器又没关联乐器时为 null（只是坐着）。 */
    public static @Nullable Musician of(LivingEntity entity) {
        if (entity.getVehicle() instanceof dev.logos.dimension.music.InstrumentSeat post) return StationPoses.post(entity, post);
        if (!(entity.getVehicle() instanceof BenchSeat seat)) return null;
        Level level = entity.level();
        BlockPos bench = seat.bench();
        BlockState benchState = level.getBlockState(bench);
        if (!(benchState.getBlock() instanceof StarbloomBenchBlock)) return null;
        BlockPos linked = StarbloomBenchBlock.linked(level, bench, benchState);
        if (linked == null) {
            if (!(entity.getMainHandItem().getItem() instanceof HandheldInstrumentItem item)) return null;
            boolean playing = VoiceBank.recentlyPlayed(bench) || MusicClient.recentlyPlayed(entity.getId())
                || VoiceBank.holding(MusicClient.heldKey(entity.getId()));
            return new Musician(Mode.HANDHELD, item.kind(), playing ? 1 : 0, null, null, feet(benchState, false), false, SEAT,
                (float) (StarbloomBenchBlock.FEET.z - StarbloomBenchBlock.FRONT_EDGE));
        }
        BlockState mainState = level.getBlockState(linked);
        if (!(mainState.getBlock() instanceof InstrumentBlock block)) return null;
        InstrumentKind kind = block.kind(mainState);
        if (kind == null) return null;
        Vector3f feet = feet(benchState, true);
        if (kind == InstrumentKind.TIMPANI) return StationPoses.timpani(level, bench, feet);
        Hands hands = hands(entity.getId(), kind, VoiceBank.notesAt(linked), SPLIT);
        Direction facing = benchState.getValue(StarbloomBenchBlock.FACING);
        Vector3f[] at = kind == InstrumentKind.CONCERT_HARP ? harp(hands, lateral(bench, facing, linked))
            : keys(kind, hands, keySpan(bench, facing, linked, mainState, block, kind), feet);
        return new Musician(kind == InstrumentKind.CONCERT_HARP ? Mode.HARP : Mode.KEYS, kind, 0, at[0], at[1], feet, false, SEAT,
            (float) (feet.z - StarbloomBenchBlock.FRONT_EDGE));
    }

    /** 琴凳的凳面比坐的人双脚高多少像素。 */
    private static final float SEAT = (float) (StarbloomBenchBlock.TOP - StarbloomBenchBlock.FEET.y);

    private static Vector3f feet(BlockState benchState, boolean linked) {
        var f = StarbloomBenchBlock.feet(benchState, linked);
        return new Vector3f((float) f.x, (float) f.y, (float) f.z);
    }

    /** 两只手在键盘上的位置（0–1）和按下的程度：左手跟着低音、右手跟着高音，平滑移过去；没音时回到各自的半边。 */
    static Hands hands(int id, InstrumentKind kind, List<int[]> notes, int split) {
        long now = System.nanoTime();
        Hands h = HANDS.computeIfAbsent(id, k -> new Hands());
        float dt = h.last == 0 ? 1 : Math.min(1, (now - h.last) / 1e9f);
        float lowSum = 0, highSum = 0, lowPress = 0, highPress = 0;
        int lowCount = 0, highCount = 0;
        float range = Math.max(1, kind.high() - kind.low());
        for (int[] n : notes) {
            float u = Math.max(0, Math.min(1, (n[0] - kind.low()) / range));
            float press = Math.max(0, 1 - n[1] / 120f);
            if (n[0] < split) {
                lowSum += u;
                lowCount++;
                lowPress = Math.max(lowPress, press);
            } else {
                highSum += u;
                highCount++;
                highPress = Math.max(highPress, press);
            }
        }
        float targetLow = lowCount > 0 ? lowSum / lowCount : 0.3f, targetHigh = highCount > 0 ? highSum / highCount : 0.7f;
        float k = 1 - (float) Math.exp(-Math.max(dt, 0) / 0.06f);
        h.low += (targetLow - h.low) * k;
        h.high += (targetHigh - h.high) * k;
        // 两只手不交叉
        if (h.high - h.low < 0.12f) {
            float mid = (h.low + h.high) / 2;
            h.low = mid - 0.06f;
            h.high = mid + 0.06f;
        }
        h.lowPress = lowPress;
        h.highPress = highPress;
        h.last = now;
        if (HANDS.size() > 256) HANDS.clear();
        return h;
    }

    /** 琴键顶面的高（像素，从地面算）：同 tools/instrument_models/keyboards.py、organ.py 里各件乐器的键盘。 */
    private static float keyTop(InstrumentKind kind) {
        return switch (kind) {
            case HARPSICHORD -> 12.2f;
            case CELESTA -> 11.2f;
            case PIPE_ORGAN -> 14.8f;
            default -> 13f;
        };
    }

    /** 键盘在琴凳坐标里的左右两端（像素）：只看挨着琴凳那一排的格子，两头各留出琴身的边。 */
    private static float[] keySpan(BlockPos bench, Direction facing, BlockPos main, BlockState mainState, InstrumentBlock block, InstrumentKind kind) {
        Direction right = facing.getClockWise();
        BlockPos front = bench.relative(facing);
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (BlockPos c : block.cells(mainState, main)) {
            int depth = (c.getX() - front.getX()) * facing.getStepX() + (c.getZ() - front.getZ()) * facing.getStepZ();
            if (depth != 0 || c.getY() != front.getY()) continue;
            int lateral = (c.getX() - bench.getX()) * right.getStepX() + (c.getZ() - bench.getZ()) * right.getStepZ();
            lo = Math.min(lo, lateral * 16);
            hi = Math.max(hi, lateral * 16 + 16);
        }
        if (lo > hi) return new float[]{2, 14};
        float inset = kind == InstrumentKind.HARPSICHORD || kind == InstrumentKind.CELESTA ? 1.5f : 2.5f;
        return new float[]{lo + inset, hi - inset};
    }

    /** 键盘：手搭在琴键靠外的一段上（离琴凳前沿 1.2 像素），左右按音高，弹下时往下按。手伸得到的范围以内。 */
    private static Vector3f[] keys(InstrumentKind kind, Hands h, float[] span, Vector3f feet) {
        float y = keyTop(kind) + 1.0f;
        float xl = Math.max(feet.x - 12, Math.min(feet.x + 12, span[0] + h.low * (span[1] - span[0])));
        float xr = Math.max(feet.x - 12, Math.min(feet.x + 12, span[0] + h.high * (span[1] - span[0])));
        return new Vector3f[]{new Vector3f(xl, y - 0.9f * h.lowPress, -1.2f), new Vector3f(xr, y - 0.9f * h.highPress, -1.2f)};
    }

    /** 竖琴琴弦所在的平面（琴凳坐标的 z，像素）：琴在琴凳正前方一格，弦在那一格朝北放置时 z = 13（tools/instrument_models/strings.py 的 HARP_SHIFT）。 */
    static final float HARP_STRINGS_Z = 13 - 16;
    /** 手在琴弦上左右走的范围（琴那一格里的 x，像素）：最低音弦在 x = 5.9；最右边几根高音短弦挂在肩膀上方，手只走到 11.2。 */
    private static final float HARP_LOW_X = 6.1f, HARP_HIGH_X = 11.2f;

    /** main 那一格比琴凳主块往演奏者右手边偏多少像素（单人琴凳正前方的乐器为 0）。 */
    private static float lateral(BlockPos bench, Direction facing, BlockPos main) {
        Direction right = facing.getClockWise();
        return 16 * ((main.getX() - bench.getX()) * right.getStepX() + (main.getZ() - bench.getZ()) * right.getStepZ());
    }

    /**
     * 竖琴：琴弦平面左右立在前面一格、正对演奏者，低音在左；弦的下端沿共鸣箱往右斜升（x 每往右 1 像素高 2.74），越往右弦越短、越高。
     * 两只手按音高在弦上左右移，停在弦的中段偏上（左手 15、右手往上到 19 像素，不低于弦下端 1.5 像素），手心停在弦前 0.6 像素
     * （手指搭在弦上），拨弦时往回一勾。
     */
    private static Vector3f[] harp(Hands h, float dx) {
        return new Vector3f[]{harpHand(h.low, h.lowPress, dx), harpHand(h.high, h.highPress, dx)};
    }

    private static Vector3f harpHand(float u, float press, float dx) {
        float x = HARP_LOW_X + u * (HARP_HIGH_X - HARP_LOW_X);
        float bottom = 4.3f + 2.74f * (x - 5.9f);
        return new Vector3f(dx + x, Math.max(15 + 4 * u, bottom + 1.5f), HARP_STRINGS_Z + 0.6f + 1.0f * press);
    }

    /**
     * 原版的手臂是直的、没有手肘：槌头要落的点常常比一臂还近，直接指过去手会越过它、槌杆朝外翻。
     * 所以手臂指向槌头上方 5、往回 3 像素的地方（模型像素：y 向下、z 向后），槌从手里斜着落到槌头的位置。
     */
    static Vector3f malletAim(Vector3f head) {
        return new Vector3f(head.x, head.y - 5, head.z + 3);
    }

    /** 琴凳坐标（像素）→ 人形模型根坐标（像素），以坐的人双脚为准。 */
    static Vector3f benchToModel(Vector3f bench, Vector3f feet) {
        return HeldRig.toModel(bench, feet);
    }

    // —— 原版人形模型（玩家、僵尸、骷髅……）——

    /**
     * 渲染状态里记下这位演奏者（{@link #MUSICIAN}），手持乐器时返回要画的乐器（{@link HandheldPoses#HELD}），并且不画手里原本拿着的物品。
     * 玩家经 {@link HandheldPoses}、原版人形生物经下面的渲染状态修改器进来。
     */
    static HandheldPoses.@Nullable Held prepare(LivingEntity entity, HumanoidRenderState state) {
        Musician m = of(entity);
        state.setRenderData(MUSICIAN, m);
        if (m == null) return null;
        state.rightHandItemState.clear();
        state.leftHandItemState.clear();
        return switch (m.mode()) {
            case HANDHELD -> HandheldPoses.heldFor(m.kind(), entity, m.move());
            case BOWED -> HandheldPoses.bowFor(m.kind(), entity, m.move());
            case MALLETS -> new HandheldPoses.Held(m.kind(), null, StationPoses.tool(m.kind(), entity), 0);
            default -> null;
        };
    }

    /**
     * 原版在 setupAnim 里摆完姿势之后（{@code MusicianModelMixin}）：双臂伸向手位。放在最后是因为僵尸之类的模型会在 setupAnim
     * 末尾再抬一次手，在这之前摆的会被盖掉。
     */
    public static void afterSetupAnim(HumanoidModel<?> model, HumanoidRenderState state) {
        Musician m = state.getRenderData(MUSICIAN);
        if (m == null) return;
        boolean leftHanded = state.mainArm == HumanoidArm.LEFT;
        if (m.mode() == Mode.HANDHELD) {
            HeldRig.Place place = HeldRig.place(m.kind(), model, false, true, leftHanded, state.ageInTicks, m.move());
            HandheldPoses.reach(leftHanded ? model.leftArm : model.rightArm, place.mainHand(), 0, 0, leftHanded ? 1 : -1);
            HandheldPoses.reach(leftHanded ? model.rightArm : model.leftArm, place.offHand(), 0, 0, leftHanded ? -1 : 1);
        } else if (m.mode() == Mode.BOWED) {
            // 同玩家坐在大提琴后：右手运弓、左手按琴颈，腿夹着琴（低音提琴站着）
            HandheldPoses.pose(m.kind(), model, state);
        } else if (m.mode() == Mode.MALLETS) {
            for (boolean left : new boolean[]{true, false}) {
                Vector3f target = left ? m.left() : m.right();
                var arm = left ? model.leftArm : model.rightArm;
                if (target == null) {
                    arm.xRot = 0;
                    arm.yRot = 0;
                    arm.zRot = left ? -0.05f : 0.05f;
                } else {
                    HandheldPoses.reach(arm, malletAim(benchToModel(target, m.feet())), 0, 0, left ? 1 : -1);
                }
            }
            if (m.standing()) {
                model.rightLeg.xRot = 0;
                model.leftLeg.xRot = 0;
                model.rightLeg.yRot = 0.08f;
                model.leftLeg.yRot = -0.08f;
            } else {
                model.rightLeg.xRot = -0.8f;
                model.leftLeg.xRot = -0.8f;
                model.rightLeg.yRot = 0.12f;
                model.leftLeg.yRot = -0.12f;
            }
            model.rightLeg.zRot = 0;
            model.leftLeg.zRot = 0;
        } else if (m.left() != null && m.right() != null) {
            HandheldPoses.reach(model.leftArm, benchToModel(m.left(), m.feet()), 0, 0, 1);
            HandheldPoses.reach(model.rightArm, benchToModel(m.right(), m.feet()), 0, 0, -1);
            // 原版的腿没有膝盖：坐在乐器前时往前下方斜伸（原版骑乘那样平伸会插进琴身）；竖琴的底座离琴凳前沿只有 3 像素，
            // 腿再往下垂一些，脚落在底座后面的踏板那里（斜伸 0.8 会从琴身里穿过去）
            float legs = m.mode() == Mode.HARP ? -0.5f : -0.8f;
            model.rightLeg.xRot = legs;
            model.leftLeg.xRot = legs;
            model.rightLeg.yRot = 0.12f;
            model.leftLeg.yRot = -0.12f;
            model.rightLeg.zRot = 0;
            model.leftLeg.zRot = 0;
        }
    }

    @SubscribeEvent
    @SuppressWarnings({"unchecked", "rawtypes"})
    static void modifiers(RegisterRenderStateModifiersEvent event) {
        event.registerEntityModifier((Class) HumanoidMobRenderer.class, (java.util.function.BiConsumer<Mob, HumanoidRenderState>)
            (mob, state) -> state.setRenderData(HandheldPoses.HELD, prepare(mob, state)));
    }

    @SubscribeEvent
    @SuppressWarnings({"unchecked", "rawtypes"})
    static void layers(EntityRenderersEvent.AddLayers event) {
        for (EntityType<?> type : event.getEntityTypes()) {
            if (event.getRenderer(type) instanceof HumanoidMobRenderer renderer) renderer.addLayer(new MobLayer(renderer));
        }
    }

    /**
     * 原版人形模型（玩家、生物）手里的琴槌：手在肩指向 {@link #malletAim} 的那条线上、一臂远（手心离肩 9 像素），
     * 槌从手里伸到槌头该落的点。
     */
    static void drawMallets(PoseStack pose, SubmitNodeCollector collector, int light, int outline, HumanoidModel<?> model, Musician m,
                            net.minecraft.client.renderer.item.@Nullable ItemStackRenderState tool) {
        if (tool == null) return;
        for (boolean left : new boolean[]{true, false}) {
            Vector3f target = left ? m.left() : m.right();
            if (target == null) continue;
            Vector3f t = benchToModel(target, m.feet());
            var arm = left ? model.leftArm : model.rightArm;
            Vector3f shoulder = new Vector3f(arm.x + (left ? 1 : -1), arm.y, arm.z);
            Vector3f hand = malletAim(t).sub(shoulder).normalize().mul(9).add(shoulder);
            HandheldPoses.drawOn(pose, collector, light, outline, model,
                HeldRig.along(hand, t, StationPoses.toolGrip(m.kind()), StationPoses.toolHead(m.kind())), tool);
        }
    }

    /** 原版人形生物手里的乐器（和弓）：画在 {@link HeldRig} 算好的位置上（同玩家的那一层）。 */
    private static final class MobLayer<S extends HumanoidRenderState, M extends HumanoidModel<S>> extends RenderLayer<S, M> {
        MobLayer(RenderLayerParent<S, M> parent) {
            super(parent);
        }

        @Override
        public void submit(PoseStack pose, SubmitNodeCollector collector, int light, S state, float yRot, float xRot) {
            HandheldPoses.Held held = state.getRenderData(HandheldPoses.HELD);
            if (held == null || state.isInvisible) return;
            M model = getParentModel();
            Musician m = state.getRenderData(MUSICIAN);
            if (m != null && m.mode() == Mode.MALLETS) {
                drawMallets(pose, collector, light, state.outlineColor, model, m, held.bow());
                return;
            }
            HeldRig.Place place = held.kind().seated() ? HeldRig.seated(held.kind(), model, state.ageInTicks, held.move())
                : HeldRig.place(held.kind(), model, state.isCrouching, false, state.mainArm == HumanoidArm.LEFT, state.ageInTicks, held.move());
            if (held.instrument() != null && place.instrument() != null) {
                HandheldPoses.drawOn(pose, collector, light, state.outlineColor, model, place.instrument(), held.instrument());
            }
            if (held.bow() != null && place.bow() != null) HandheldPoses.drawOn(pose, collector, light, state.outlineColor, model, place.bow(), held.bow());
        }
    }
}
