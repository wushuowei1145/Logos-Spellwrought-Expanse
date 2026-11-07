package dev.logos.dimension.mixin;

import dev.logos.dimension.client.camera.CameraDirector;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 运镜改镜头的唯一入口。{@code alignWithEntity} 之后的视锥、投影、地形剔除、雾和声音都按镜头算，
 * 所以只要在这里换掉位姿，其余都会跟上。
 */
@Mixin(Camera.class)
public abstract class CameraShotMixin {
    @Shadow protected abstract void setPosition(Vec3 position);
    @Shadow protected abstract void setRotation(float yRot, float xRot, float roll);

    @Inject(method = "alignWithEntity", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Camera;detached:Z", opcode = Opcodes.PUTFIELD))
    private void cameraShotCaptureEyes(float partialTicks, CallbackInfo ci) {
        CameraDirector.captureEyes((Camera) (Object) this);
    }

    @Inject(method = "alignWithEntity", at = @At("TAIL"))
    private void cameraShotApply(float partialTicks, CallbackInfo ci) {
        CameraDirector.Pose pose = CameraDirector.frame((Camera) (Object) this, partialTicks);
        if (pose == null) return;
        setPosition(pose.pos());
        setRotation(pose.yaw(), pose.pitch(), pose.roll());
    }
}
