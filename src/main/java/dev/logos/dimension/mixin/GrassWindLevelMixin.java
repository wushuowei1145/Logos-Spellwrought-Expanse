package dev.logos.dimension.mixin;

import dev.logos.dimension.client.WindGrass;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 挖草、换季和放方块时撤销对应的草模型缓存。 */
@Mixin(ClientLevel.class)
public abstract class GrassWindLevelMixin {
    @Inject(method = "setBlocksDirty", at = @At("TAIL"))
    private void grassChanged(BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
        WindGrass.changed(pos);
    }

    @Inject(method = "sendBlockUpdated", at = @At("TAIL"))
    private void serverGrassChanged(BlockPos pos, BlockState oldState, BlockState newState, int flags, CallbackInfo ci) {
        WindGrass.changed(pos);
    }
}
