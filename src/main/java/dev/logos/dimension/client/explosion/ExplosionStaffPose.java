package dev.logos.dimension.client.explosion;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.logos.client.WandPose;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.explosion.ExplosionStaffItem;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import org.jspecify.annotations.Nullable;

/**
 * 吟唱时怎么拿爆裂魔杖：第三人称用 Logos 法杖蓄力的姿势（手臂抬起指向准星）；第一人称把杖往画面中间抬、往前送，
 * 吟唱越久抖得越厉害，蓄满后稳住，让红宝珠和它上面的光留在视野里。
 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class ExplosionStaffPose {
    private static final float RAISE_TICKS = 6;

    private ExplosionStaffPose() {}

    @SubscribeEvent
    static void extensions(RegisterClientExtensionsEvent event) {
        event.registerItem(new IClientItemExtensions() {
            @Override
            public HumanoidModel.@Nullable ArmPose getArmPose(LivingEntity entity, InteractionHand hand, ItemStack stack) {
                return chanting(entity, hand) ? WandPose.CHARGE.getValue() : null;
            }

            @Override
            public boolean applyForgeHandTransform(PoseStack pose, LocalPlayer player, HumanoidArm arm, ItemStack stack, float partialTick,
                    float equipProcess, float swingProcess) {
                InteractionHand hand = arm == player.getMainArm() ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
                if (!chanting(player, hand)) return false;
                handInView(pose, player, arm, partialTick, equipProcess);
                return true;
            }
        }, ExplosionStaffItem.ITEM.get());
    }

    private static boolean chanting(LivingEntity entity, InteractionHand hand) {
        return entity.isUsingItem() && entity.getUsedItemHand() == hand && entity.getUseItem().is(ExplosionStaffItem.ITEM.get());
    }

    private static void handInView(PoseStack pose, LocalPlayer player, HumanoidArm arm, float partialTick, float equipProcess) {
        int side = arm == HumanoidArm.RIGHT ? 1 : -1;
        float t = Mth.clamp((player.getTicksUsingItem() + partialTick) / RAISE_TICKS, 0, 1);
        float k = t * t * (3 - 2 * t);
        float p = ExplosionClient.progress(player.getId());
        float shake = (p < 0 || p >= 1 ? .1f : .25f + p) * .008f;
        float time = player.tickCount + partialTick;
        pose.translate(side * .56f, -.52f + equipProcess * -.6f, -.72f);
        // 平时（模型的第一人称展示）杖头往画面里斜 6 度，红宝珠才不出右边；抬起来时往回扶正一点，宝珠停在准星右上方
        pose.translate(-side * .1f * k + Mth.sin(time * 2.1f) * shake, .1f * k + Mth.cos(time * 2.7f) * shake, -.1f * k);
        pose.mulPose(Axis.XP.rotationDegrees(-12 * k));
        pose.mulPose(Axis.ZP.rotationDegrees(side * -3 * k));
    }
}
