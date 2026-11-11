package dev.logos.dimension.client.music;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.music.orchestra.Orchestra;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.asm.enumextension.EnumProxy;
import net.neoforged.neoforge.client.IArmPoseTransformer;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import org.jspecify.annotations.Nullable;

/**
 * 指挥的姿势（docs/ORCHESTRA.md 第 7 节）：站在指挥台上、拿着指挥棒、乐团在演奏时，右臂让棒尖按拍子画图形
 * （{@link Conducting#poseOf}），幅度随响度；有声部要进来时左手提前一拍指向它，响的段落左手跟着右手做镜像，平时自然下垂。
 * 预备拍从站姿抬起来，收束之后慢慢放下。别人看到的和自己看到的按同一个时钟算。第一人称里拿棒的手也跟着拍子动。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class ConductingPoses {
    public static final EnumProxy<HumanoidModel.ArmPose> CONDUCT = new EnumProxy<>(HumanoidModel.ArmPose.class,
        true, true, (IArmPoseTransformer) (m, s, a) -> pose(m, s));

    /**
     * 拍子平面在身前的位置与大小（模型像素：−Y 向上、−Z 向前、+X 是身体左侧；肩的支点在 y=2）。拍点（图形的最低处）手臂在水平下约 28°，
     * 普通的拍弹到约 11°，每小节最后一拍弹到水平上约 20°；左右按图形张开，第二拍往身前收、第三拍往外。
     */
    private static final float BASE_X = -4.0f, BASE_Y = 6.6f, BASE_Z = -8.6f, WIDTH = 8.0f, HEIGHT = 7.7f;
    /** 不指挥时手的位置（垂在身侧稍前）。 */
    private static final float REST_X = -5.5f, REST_Y = 11.5f, REST_Z = -2.0f;

    private ConductingPoses() {}

    @SubscribeEvent
    static void extensions(RegisterClientExtensionsEvent event) {
        event.registerItem(new IClientItemExtensions() {
            @Override
            public HumanoidModel.@Nullable ArmPose getArmPose(LivingEntity entity, InteractionHand hand, ItemStack stack) {
                if (hand != InteractionHand.MAIN_HAND) return null;
                return Conducting.poseOf(entity) != null ? CONDUCT.getValue() : null;
            }

            @Override
            public boolean applyForgeHandTransform(PoseStack pose, LocalPlayer player, HumanoidArm arm, ItemStack item, float partialTick,
                                                   float equip, float swing) {
                Conducting.Pose p = Conducting.poseOf(player);
                if (p == null) return false;
                // 跟原版拿东西的位置一样起步（blend 为 0 时不跳），再按棒尖在拍子平面上的位置挪动、俯仰
                int side = arm == HumanoidArm.RIGHT ? 1 : -1;
                float b = (float) p.blend();
                float x = (float) (p.tip()[0] * p.amplitude()) * b, y = (float) (p.tip()[1] * p.amplitude()) * b;
                pose.translate(side * (0.56f + x * 0.16f), -0.52f + equip * -0.6f + y * 0.2f, -0.72f);
                pose.mulPose(com.mojang.math.Axis.XP.rotationDegrees(y * 18 - 8 * b));
                return true;
            }
        }, Orchestra.BATON.get());
    }

    private static void pose(HumanoidModel<?> model, HumanoidRenderState state) {
        if (!(state instanceof AvatarRenderState avatar) || Minecraft.getInstance().level == null) return;
        var entity = Minecraft.getInstance().level.getEntity(avatar.id);
        Conducting.Pose p = entity == null ? null : Conducting.poseOf(entity);
        if (p == null) return;
        boolean left = avatar.mainArm == HumanoidArm.LEFT;
        float sx = left ? -1 : 1;
        double amp = p.amplitude();
        float tx = (float) (BASE_X - p.tip()[0] * WIDTH * amp);
        float ty = (float) (BASE_Y - p.tip()[1] * HEIGHT * amp);
        float b = (float) p.blend();
        // 惯用手拿棒（左撇子整幅镜像）
        ModelPart main = left ? model.leftArm : model.rightArm;
        ModelPart off = left ? model.rightArm : model.leftArm;
        reach(main, sx * Mth.lerp(b, REST_X, tx), Mth.lerp(b, REST_Y, ty), Mth.lerp(b, REST_Z, BASE_Z), left ? 1 : -1);
        if (p.cue() != null && entity != null && b > 0.5f) {
            // 左手指向要进来的声部：世界方向换到模型里（按身体朝向），抬到肩高
            Vec3 d = p.cue().subtract(entity.position().add(0, 1.3, 0)).normalize();
            double yaw = Math.toRadians(avatar.bodyRot);
            double fx = -Math.sin(yaw), fz = Math.cos(yaw);
            double lx = Math.cos(yaw), lz = Math.sin(yaw);
            float mx = (float) (d.x * lx + d.z * lz), mz = (float) -(d.x * fx + d.z * fz);
            float my = (float) Mth.clamp(-d.y, -0.6, 0.15);
            reach(off, sx * 6 + mx * 10, 2 + my * 10, mz * 10, left ? -1 : 1);
        } else if (p.mirrorLeft() && b > 0.5f) {
            reach(off, -sx * Mth.lerp(b, REST_X, tx), Mth.lerp(b, REST_Y, ty) + 0.5f, BASE_Z + 1, left ? -1 : 1);
        } else {
            // 垂在身侧、略往后、略往外：手不碰身前的谱架
            off.xRot = 0.06f;
            off.yRot = 0;
            off.zRot = sx * -0.1f;
        }
    }

    /** 手臂指向 (x, y, z)：同 {@link HandheldPoses} 的做法（手臂中线在支点外侧 1 像素）。 */
    private static void reach(ModelPart arm, float x, float y, float z, float side) {
        float dx = x - (arm.x + side), dy = y - arm.y, dz = z - arm.z;
        float len = Mth.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-3f) return;
        arm.xRot = -(float) Math.acos(Mth.clamp(dy / len, -1, 1));
        arm.yRot = (float) Math.atan2(-dx, -dz);
        arm.zRot = 0;
    }
}
