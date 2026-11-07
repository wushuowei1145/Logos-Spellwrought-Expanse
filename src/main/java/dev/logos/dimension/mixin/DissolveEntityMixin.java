package dev.logos.dimension.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.logos.dimension.client.dissolve.Dissolves;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 消散中的实体只画还留下的部分。没有东西消散时收集器原样传下去。 */
@Mixin(EntityRenderDispatcher.class)
public abstract class DissolveEntityMixin {
    @Inject(method = "extractEntity", at = @At("RETURN"))
    private <E extends Entity> void dissolveExtracted(E entity, float partialTicks, CallbackInfoReturnable<EntityRenderState> cir) {
        Dissolves.extracted(entity, cir.getReturnValue());
    }

    @WrapMethod(method = "submit")
    private void dissolveSubmit(EntityRenderState state, CameraRenderState camera, double x, double y, double z, PoseStack pose, SubmitNodeCollector collector, Operation<Void> original) {
        original.call(state, camera, x, y, z, pose, Dissolves.entityCollector(collector, state, x, y, z, pose));
    }
}
