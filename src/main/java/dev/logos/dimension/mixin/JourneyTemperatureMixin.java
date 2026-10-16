package dev.logos.dimension.mixin;

import dev.logos.dimension.cloudsea.JourneySeasons;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Biome.class)
public abstract class JourneyTemperatureMixin {
    @Inject(method="getTemperature",at=@At("RETURN"),cancellable=true)
    private void journeyTemperature(BlockPos pos,int seaLevel,CallbackInfoReturnable<Float> result){
        Integer season=JourneySeasons.CONTEXT.get();
        if(season!=null)result.setReturnValue(result.getReturnValue()+switch(season){case 1->.16f;case 2->-.12f;case 3->-.85f;default->0f;});
    }
}
