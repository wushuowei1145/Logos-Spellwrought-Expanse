package dev.logos.dimension.client.companion;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.logos.dimension.client.dissolve.Dissolves;
import dev.logos.dimension.client.longsword.LongswordRig;
import dev.logos.dimension.companion.LacertaCompanion;
import dev.logos.dimension.foxfire.FoxfireFlame;
import dev.logos.dimension.item.CrimsonFlameLongswordItem;
import java.util.Map;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * 把炽焰长剑画在拉塞塔模型的 {@code Weapon} 骨骼上（docs/LACERTA_COMPANION.md 第 5 节）：用玩家那把同一份战斗网格（{@link LongswordRig}），
 * 剑招的 NPC 版动作已经把剑的位置烘进了 {@code Weapon} 骨骼（附属 tools/crimson_flame_longsword_moves.py，网格 × 0.85）。模型里只有一根空骨骼。
 * 拔剑时从柄往尖凝出来，收剑播完从尖往柄散掉，边缘是龙炎的熔金。
 */
public final class CrimsonOnBone {
    /** 网格单位 → 模型像素：同烘焙时拉塞塔骨架的 weapon_scale。 */
    static final float SCALE = .85f;
    /** 凝聚、消散边缘的颜色：龙炎的焰心。 */
    static final int EDGE = FoxfireFlame.DRAGON.bright;

    private static int draws;

    private CrimsonOnBone() {}

    public static int draws() {
        return draws;
    }

    /** {@code progress}：小于 0 是完整的剑；0–1 凝出来（{@code appear}）或散掉的进度。 */
    static void submit(PoseStack pose, SubmitNodeCollector out, int light, float progress, boolean appear, Object key) {
        LongswordRig rig = LongswordRig.get(CrimsonFlameLongswordItem.COMBAT_ID, false);
        if (rig == null) return;
        draws++;
        float wsp = rig.weaponScale();
        Vector3f ref = rig.weaponPivot();
        Matrix4f toMesh = new Matrix4f().scale(SCALE / 16).scale(1 / wsp).translate(-ref.x, -ref.y, -ref.z);
        Matrix4f weapon = new Matrix4f(toMesh).translate(ref).scale(wsp);
        var sample = new LongswordRig.Sample(Map.of("Weapon", toMesh), new Vector3f(), weapon, 0, new Vector3f(), new Vector3f());
        if (progress >= 0) {
            rig.submitDissolving(sample, pose, out, light, Math.min(1, progress), appear, false, EDGE, key);
            return;
        }
        rig.submit(sample, pose, out, light, null, false, true, false);
        if (!Dissolves.shadowPass()) rig.submitGlow(sample, pose, out, false, .4f, 0xFFFFFF);
    }

    /**
     * 剑此刻画成什么样（{@code [进度, 是不是在凝]}）：拔剑后 {@link #MANIFEST} 刻里凝出来，之后完整（-1）；收剑后 {@link #MANIFEST} 刻里散掉；
     * 再之后不画（null）。
     */
    static float[] state(LacertaCompanion c, float partialTick) {
        float age = c.level().getGameTime() - c.swordChangedAt() + partialTick;
        if (c.swordShown()) return age >= MANIFEST ? new float[]{-1, 1} : new float[]{Math.max(0, age / MANIFEST), 1};
        return age >= MANIFEST ? null : new float[]{Math.max(0, age / MANIFEST), 0};
    }

    /** 凝出、散掉各要几刻（同 LacertaBlade.MANIFEST）。 */
    static final int MANIFEST = 6;
}
