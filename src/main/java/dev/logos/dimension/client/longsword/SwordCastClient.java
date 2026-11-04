package dev.logos.dimension.client.longsword;

import dev.logos.client.CastPoints;
import dev.logos.client.ChargeStyle;
import dev.logos.client.ClientCharges;
import dev.logos.client.VfxParts;
import dev.logos.client.WandTips;
import dev.logos.dimension.longsword.LongswordPayload;
import dev.logos.dimension.longsword.SwordCasting;
import dev.logos.item.WandItem;
import dev.logos.lang.Element;
import dev.logos.runtime.ManaWrap;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

/**
 * 施法剑（黑钢长剑）在客户端的表现：剑尖挂点、第三人称施法时剑尖随视线俯仰、剑身铭文发光。
 * <p>
 * 剑尖挂点按 Logos 的法杖规范写在物品模型的 {@code wand_tip} 组里（{@link WandTips#tip}），这里换到武器网格坐标
 * （网格原点在握柄，即物品模型的 (8, 1, 8)），每帧按剑实际画出的位置上报给 {@link CastPoints}：蓄力特效聚在剑尖，
 * 光柱、射线从剑尖画出。
 */
public final class SwordCastClient {
    /** 物品模型里握柄的位置（武器网格的原点）。 */
    private static final Vector3f GRIP = new Vector3f(8, 1, 8);
    /** 第三人称剑尖跟随视线的俯仰上限（度）。 */
    private static final float MAX_PITCH = 60;
    /** 各施法者最近一次施法或缠绕的元素：施放之后蓄力状态已经没了，收势时的余光仍用它的颜色。 */
    private static final Map<Integer, Element> LAST = new HashMap<>();
    /** 第一人称手部渲染开始时的姿势（见 {@link #beginHands}）。 */
    private static final Matrix4f HAND_BASE = new Matrix4f();

    private SwordCastClient() {}

    /** 施法剑的剑尖在武器网格里的位置；不是法杖时为 null。 */
    public static Vector3f tip(ItemStack stack) {
        if (!(stack.getItem() instanceof WandItem)) return null;
        return new Vector3f(WandTips.tip(stack.getItem())).sub(GRIP);
    }

    /** 第三人称施法时右臂跟随视线俯仰的角度（度，正值上抬）；{@code pitch} 是视线俯仰（原版：向下为正）。 */
    public static float aim(LongswordPayload p, double now, float pitch) {
        double age = (now - p.started()) * p.speed();
        float weight = switch (p.move()) {
            case SwordCasting.CHARGE -> smooth((float) (age / Math.max(1, p.blend() * 20)));
            case SwordCasting.BEAM -> 1;
            case SwordCasting.THRUST -> 1 - smooth((float) ((age - 5) / 7));
            case SwordCasting.LOWER -> 1 - smooth((float) (age / 8));
            default -> 0;
        };
        return -Mth.clamp(pitch, -MAX_PITCH, MAX_PITCH) * weight;
    }

    /** 剑身铭文的亮度（0–1）：蓄力时随进度变亮，施放时最亮随后消退，光柱持续时常亮；持有魔力缠绕时一直微微呼吸着亮。 */
    public static float glow(LivingEntity caster, LongswordPayload p, double now, float partialTick) {
        double age = (now - p.started()) * p.speed();
        var charge = ClientCharges.of(caster);
        if (charge != null) LAST.put(caster.getId(), charge.element);
        float cast = switch (p.move()) {
            case SwordCasting.CHARGE -> smooth((float) (age / 5)) * (.45f + .55f * (charge == null ? 0 : charge.progress(partialTick)));
            case SwordCasting.BEAM -> 1;
            case SwordCasting.THRUST -> 1 - smooth((float) ((age - 4) / 10));
            case SwordCasting.LOWER -> 1 - smooth((float) (age / 8));
            default -> 0;
        };
        var wrap = ManaWrap.of(caster);
        float wrapped = 0;
        if (wrap != null) {
            if (charge == null && cast <= 0) LAST.put(caster.getId(), wrap.element());
            wrapped = .6f + .15f * Mth.sin((float) now * .15f);
        }
        return Math.max(cast, wrapped);
    }

    /** 铭文发光的颜色：施法或缠绕的元素色（偏亮），没见过时用奥秘。 */
    public static int color(LivingEntity caster) {
        var style = ChargeStyle.of(LAST.getOrDefault(caster.getId(), Element.ARCANE));
        return VfxParts.lerpColor(style.main(), style.light(), .35f);
    }

    /** 第一人称手部渲染开始（{@code ItemInHandRenderer.renderHandsWithItems} 开头）时的姿势：原版是世界轴向，光影包下是视图空间。 */
    public static void beginHands(Matrix4fc pose) { HAND_BASE.set(pose); }

    /** 第三人称：{@code pose} 是相对镜头的世界空间、已经换到剑骨架空间的姿势，{@code tip} 是骨架空间里的剑尖。 */
    public static void reportThirdPerson(int entityId, Matrix4fc pose, Vector3f tip) {
        var mc = Minecraft.getInstance();
        // 光影包的阴影阶段也会画本机玩家的身体，第一人称时不能用它盖掉手部的剑尖。
        if (mc.getCameraEntity() != null && entityId == mc.getCameraEntity().getId() && mc.options.getCameraType().isFirstPerson()) return;
        var at = pose.transformPosition(new Vector3f(tip));
        Vec3 camera = mc.gameRenderer.getMainCamera().position();
        CastPoints.report(entityId, camera.add(at.x, at.y, at.z));
    }

    /** 第一人称：{@code pose} 是手部渲染里换到剑骨架空间的姿势，{@code tip} 同上。 */
    public static void reportFirstPerson(int entityId, Matrix4fc pose, Vector3f tip) {
        var mc = Minecraft.getInstance();
        var camera = mc.gameRenderer.getMainCamera();
        var at = new Matrix4f(HAND_BASE).invert().mul(pose).transformPosition(new Vector3f(tip));
        camera.rotation().transform(at);
        CastPoints.report(entityId, camera.position().add(at.x, at.y, at.z));
    }

    private static float smooth(float t) {
        t = Mth.clamp(t, 0, 1);
        return t * t * (3 - 2 * t);
    }
}
