package dev.logos.dimension.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.item.*;
import net.minecraft.client.model.effects.SpearAnimations;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;

/** 主盾副矛的两个使用姿态共存，几何与动画继续使用原版长矛实现。 */
@EventBusSubscriber(modid = LogosDimension.ID, value = Dist.CLIENT)
public final class SpearShieldVisual {
    public static boolean kineticActive(HumanoidRenderState state) {
        return state.isUsingItem && state.useItemHand == InteractionHand.MAIN_HAND
                && state.getMainHandItemStack().is(AncientEmbraceItem.ITEM.get())
                && state.getUseItemStackForArm(state.mainArm.getOpposite()).is(ElfSpearItem.ITEM.get());
    }
    @SubscribeEvent static void extensions(RegisterClientExtensionsEvent event) {
        event.registerItem(new IClientItemExtensions() {
            @Override public boolean applyForgeHandTransform(PoseStack poses, LocalPlayer player, HumanoidArm arm,
                    ItemStack stack, float partial, float height, float attack) {
                if (!SpearShieldGuard.kineticActive(player) || arm != player.getMainArm().getOpposite()) return false;
                int invert = arm == HumanoidArm.RIGHT ? 1 : -1;
                poses.translate(invert * .56f, -.52f, -.72f);
                float held = stack.getUseDuration(player) - (player.getUseItemRemainingTicks() - partial + 1);
                SpearAnimations.firstPersonUse(player.getTicksSinceLastKineticHitFeedback(partial), poses, held, arm, stack);
                return true;
            }
        }, ElfSpearItem.ITEM.get());
    }
    private SpearShieldVisual() {}
}
