package dev.logos.dimension.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.logos.dimension.client.worldtree.WorldtreeClient;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 世界树之弓左键横着拉弓时，原版“右键没按着就松弓”改看左键：左键按着就继续拉，松开左键才放箭。
 * 只在这把弓横着拉的时候生效，其余时候原样返回。
 */
@Mixin(Minecraft.class)
public abstract class WorldtreeKeybindsMixin {
    @WrapOperation(method = "handleKeybinds", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;isDown()Z"))
    private boolean logos_dimension$worldtreeSideways(KeyMapping key, Operation<Boolean> original) {
        var options = ((Minecraft) (Object) this).options;
        if (key == options.keyUse && WorldtreeClient.sideways()) return options.keyAttack.isDown();
        return original.call(key);
    }
}
