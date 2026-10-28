package dev.logos.dimension.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.logos.dimension.client.SpearShieldVisual;
import dev.logos.dimension.item.ElfSpearItem;
import net.minecraft.client.model.effects.SpearAnimations;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;

/** 只在绘制反手矛臂时放开原版单使用手限制，盾臂仍保留正常格挡姿态。 */
@Mixin(SpearAnimations.class)
public abstract class SpearShieldAnimationMixin {
    @WrapMethod(method = "thirdPersonHandUse")
    private static void bothArms(ModelPart arm, ModelPart head, boolean right, ItemStack item,
                                 HumanoidRenderState state, Operation<Void> original) {
        boolean dual = SpearShieldVisual.kineticActive(state) && item.is(ElfSpearItem.ITEM.get());
        boolean using = state.isUsingItem;
        try {
            if (dual) state.isUsingItem = false;
            original.call(arm, head, right, item, state);
        } finally { state.isUsingItem = using; }
    }
}
