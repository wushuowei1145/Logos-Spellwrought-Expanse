package dev.logos.dimension.client.shiraume;

import com.geckolib.cache.model.GeoBone;
import com.geckolib.constant.dataticket.DataTicket;
import com.geckolib.renderer.base.BoneSnapshots;
import com.geckolib.renderer.base.GeoRenderState;
import com.geckolib.renderer.base.RenderPassInfo;
import dev.logos.client.VfxParts;
import dev.logos.dimension.client.NpcRenderer;
import dev.logos.dimension.client.dissolve.Dissolves;
import dev.logos.dimension.npc.NpcAnimated;
import dev.logos.dimension.shiraume.Shiraume;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * 白梅人形的渲染：身体照 NPC 的通用渲染；流火画在 {@code Weapon} 骨骼上（{@link RyukaOnBone}），跪坐时还没有刀，
 * 起身的 1.4–2.8 秒从拳中凝聚出来，挥刀时留刀光（{@link ShiraumeTrails}）；背后光环的封印每放一次投技烧掉一枚；
 * 出招前双眼的红光画在眼球骨骼上。
 */
public class ShiraumeRenderer<R extends LivingEntityRenderState & GeoRenderState> extends NpcRenderer<Shiraume, R> {
    /** 刀的样子：-2 不画（还没凝出来），-1 完整，0–1 凝聚的进度。 */
    private static final DataTicket<Float> SWORD = DataTicket.create("logos_dimension_shiraume_sword", Float.class);
    private static final DataTicket<Integer> HALO = DataTicket.create("logos_dimension_shiraume_halo", Integer.class);
    private static final DataTicket<Float> EYES = DataTicket.create("logos_dimension_shiraume_eyes", Float.class);
    private static final DataTicket<Integer> ID = DataTicket.create("logos_dimension_shiraume_id", Integer.class);
    private static final DataTicket<ItemStackRenderState> FIRE = DataTicket.create("logos_dimension_shiraume_fire", ItemStackRenderState.class);
    /** 此刻正在挥刀、刀身完整：留刀光（{@link ShiraumeTrails}）。 */
    private static final DataTicket<Boolean> TRAIL = DataTicket.create("logos_dimension_shiraume_trail", Boolean.class);
    /** 起身时刀凝聚的那一段（动作时间，秒），与 tools/shiraume_moves.py 的 rise 标记相同。 */
    static final float MANIFEST = 1.4f, MANIFEST_END = 2.8f;
    private static final SoftGlow EYE_GLOW = new SoftGlow();
    /** 光在眼球骨骼枢轴前面一点（模型像素，相对枢轴），浮在眼睛表面。 */
    private static final Vector3f LEFT_EYE = new Vector3f(0, 0, -.7f), RIGHT_EYE = new Vector3f(0, 0, -.7f);

    public ShiraumeRenderer(EntityRendererProvider.Context context) {
        super(context, "shiraume");
        this.shadowRadius = .8f;
    }

    /** 封印之间里很暗（杀生石和底座的灯都拆掉了）：Boss 最低按方块光 {@value #MIN_LIGHT} 画，看得清招式。 */
    static final int MIN_LIGHT = 11;

    @Override
    protected int getBlockLightLevel(Shiraume boss, net.minecraft.core.BlockPos pos) {
        return Math.max(MIN_LIGHT, super.getBlockLightLevel(boss, pos));
    }

    @Override
    public void addRenderData(Shiraume boss, Void relatedObject, R state, float partialTick) {
        super.addRenderData(boss, relatedObject, state, partialTick);
        float sword = sword(boss, partialTick);
        state.addGeckolibData(SWORD, sword);
        state.addGeckolibData(HALO, boss.haloBurned());
        state.addGeckolibData(EYES, ShiraumeClient.eyes(boss.getId(), partialTick));
        state.addGeckolibData(ID, boss.getId());
        if (sword == -1 && !boss.isDeadOrDying()) state.addGeckolibData(FIRE, RyukaOnBone.fire(boss));
        state.addGeckolibData(TRAIL, sword == -1 && ShiraumeTrails.swinging(boss));
    }

    /** 刀此刻画成什么样：跪坐时没有；起身时按动作时间凝聚；其余完整。 */
    static float sword(Shiraume boss, float partialTick) {
        NpcAnimated.CombatMove move = boss.npcCombatMove();
        if (move == null) return -1;
        if (move.animation().equals(Shiraume.PREFIX + "seiza")) return -2;
        if (move.animation().equals(Shiraume.PREFIX + "rise")) {
            float t = (boss.level().getGameTime() + partialTick - move.start()) / 20f * move.speed();
            if (t < MANIFEST) return -2;
            if (t < MANIFEST_END) return (t - MANIFEST) / (MANIFEST_END - MANIFEST);
        }
        return -1;
    }

    @Override
    public void adjustRenderPose(RenderPassInfo<R> info) {
        super.adjustRenderPose(info);
        var bones = info.model().boneLookup().get();
        GeoBone weapon = bones.get("Weapon");
        if (weapon != null && info.getOrDefaultGeckolibData(SWORD, -1f) > -2) info.addPerBoneRender(weapon, this::submitSword);
        if (info.getOrDefaultGeckolibData(EYES, -1f) >= 0) {
            GeoBone left = bones.get("face_left_ball"), right = bones.get("face_right_ball");
            if (left != null) info.addPerBoneRender(left, (i, b, out) -> eye(i, LEFT_EYE));
            if (right != null) info.addPerBoneRender(right, (i, b, out) -> eye(i, RIGHT_EYE));
        }
    }

    /** 背后光环里烧掉的封印不画。 */
    @Override
    public void adjustModelBonesForRender(RenderPassInfo<R> info, BoneSnapshots snapshots) {
        super.adjustModelBonesForRender(info, snapshots);
        int burned = info.getOrDefaultGeckolibData(HALO, 0);
        for (int i = 0; i < burned && i < Shiraume.HALO_SEALS; i++) snapshots.ifPresent("halo_seal_" + i, s -> s.skipRender(true));
    }

    private void submitSword(RenderPassInfo<R> info, GeoBone bone, SubmitNodeCollector out) {
        float sword = info.getOrDefaultGeckolibData(SWORD, -1f);
        RyukaOnBone.submit(info.poseStack(), out, info.packedLight(), new Vector3f(), sword >= 0 ? sword : -1,
                info.getGeckolibData(FIRE), "shiraume" + info.getOrDefaultGeckolibData(ID, 0));
        ShiraumeTrails.feed(info.poseStack(), new Vector3f(), info.getOrDefaultGeckolibData(ID, 0), info.getOrDefaultGeckolibData(TRAIL, false));
    }

    /** 双眼红光：眼球的位置换成相对镜头的世界坐标，交给泛光一团星芒。 */
    private void eye(RenderPassInfo<R> info, Vector3f at) {
        if (Dissolves.shadowPass()) return;
        float age = info.getOrDefaultGeckolibData(EYES, -1f);
        if (age < 0) return;
        Vector3f p = info.poseStack().last().pose().transformPosition(at.x / 16, at.y / 16, at.z / 16, new Vector3f());
        eyeGlint(new Vec3(p.x, p.y, p.z), age, 1);
    }

    /** 一只眼的红光（相对镜头的坐标）：一下亮起、星芒拉长、很快暗下去。 */
    static void eyeGlint(Vec3 relative, float age, float size) {
        float k = age < .25f ? age / .25f : 1 - (age - .25f) / .75f;
        if (k <= .01f) return;
        var cam = Minecraft.getInstance().gameRenderer.getMainCamera();
        var view = new VfxParts.View(cam.position(), new Vec3(cam.leftVector()).scale(-1), new Vec3(cam.upVector()));
        SoftGlow glow = EYE_GLOW.begin(view);
        Vec3 at = cam.position().add(relative);
        glow.star(at, .22f * size * (.6f + .4f * k), .95f * size * k, VfxParts.argb(Math.min(1, k * 1.2f), 0xFF2A32));
        glow.disc(at, .08f * size, VfxParts.argb(k, 0xFFF0F0));
        glow.flush();
    }
}
