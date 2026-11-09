package dev.logos.dimension.client.shiraume;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.logos.dimension.client.dissolve.Dissolves;
import dev.logos.dimension.client.longsword.LongswordAuras;
import dev.logos.dimension.client.longsword.LongswordRig;
import dev.logos.dimension.client.longsword.RyukaClient;
import dev.logos.dimension.dissolve.Dissolving;
import dev.logos.dimension.item.RyukaItem;
import dev.logos.dimension.shiraume.ShiraumeEntities;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * 把流火画在白梅模型的 {@code Weapon} 骨骼上：用玩家流火同一份战斗网格（{@link LongswordRig}），
 * 所以凝聚、消散、晶刃的自发光和淡粉狐火都和玩家手里的一样。模型里只有一根空骨骼，刀不并进模型。
 *
 * <p>骨骼回调里的姿势以「模型像素 / 16」为单位，原点在这根骨骼的枢轴上（GeckoLib 画方块前才把枢轴移回去），
 * 已经带着骨骼的静止旋转与动作。
 * 战斗网格的方块已经按参考骨架放好（Weapon 枢轴 + 网格 × 视角缩放），这里先退回网格坐标，再按白梅的倍数放大。
 */
final class RyukaOnBone {
    /** 网格单位 → 模型像素：与 tools/shiraume_moves.py 的 WEAPON_SCALE 相同。 */
    static final float SCALE = 2.4f;
    private static final int EDGE = ShiraumeEntities.CHERRY | Dissolving.PETALS;
    private static final ItemStack STACK = new ItemStack(RyukaItem.ITEM.get());

    private RyukaOnBone() {}

    /** 本帧刀身上的淡粉狐火（物品渲染状态，取物品模型时就换好）；在渲染数据阶段取。 */
    static ItemStackRenderState fire(LivingEntity holder) {
        ItemStackRenderState item = new ItemStackRenderState();
        Minecraft.getInstance().getItemModelResolver().updateForLiving(item, STACK, ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, holder);
        RyukaClient.pinkFire(item);
        return item;
    }

    /**
     * 在骨骼回调里画刀。{@code offset} 是网格原点相对骨骼枢轴的位置（网格单位，未旋转）；
     * {@code progress} 小于 0 时画完整的刀（加自发光与狐火），0–1 时按凝聚的进度从柄往尖长出。
     */
    static void submit(PoseStack pose, SubmitNodeCollector out, int light, Vector3f offset, float progress,
                       @Nullable ItemStackRenderState fire, Object key) {
        LongswordRig rig = LongswordRig.get(RyukaItem.WEAPON.id(), false);
        if (rig == null) return;
        float wsp = rig.weaponScale();
        Vector3f ref = rig.weaponPivot();
        Matrix4f toMesh = new Matrix4f().scale(SCALE / 16)
                .translate(offset).scale(1 / wsp).translate(-ref.x, -ref.y, -ref.z);
        Matrix4f weapon = new Matrix4f(toMesh).translate(ref).scale(wsp);
        var sample = new LongswordRig.Sample(Map.of("Weapon", toMesh), new Vector3f(), weapon, 0, new Vector3f(), new Vector3f());
        if (progress >= 0) {
            rig.submitDissolving(sample, pose, out, light, Math.min(1, progress), true, false, EDGE, key);
            return;
        }
        rig.submit(sample, pose, out, light, null, false, true, false);
        if (!Dissolves.shadowPass()) rig.submitGlow(sample, pose, out, false, .55f, 0xFFFFFF);
        if (fire != null) LongswordAuras.submit(fire, sample, pose, out);
    }
}
