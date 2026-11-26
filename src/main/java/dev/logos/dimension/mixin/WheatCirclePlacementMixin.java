package dev.logos.dimension.mixin;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.wheat.WheatCirclePlacement;
import java.util.Optional;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 仅拦截队徽怪圈的起始池，不改变其他原版拼接结构。 */
@Mixin(JigsawStructure.class)
public abstract class WheatCirclePlacementMixin {
    @Inject(method="findGenerationPoint",at=@At("HEAD"),cancellable=true)
    private void logosdimension$oneCompleteCircle(Structure.GenerationContext context,CallbackInfoReturnable<Optional<Structure.GenerationStub>> cir){
        var pool=((JigsawStructure)(Object)this).getStartPool();
        if(pool.unwrapKey().map(key->key.identifier().equals(LogosDimension.id("wheat/crop_circle"))).orElse(false)&&!WheatCirclePlacement.allows(context))
            cir.setReturnValue(Optional.empty());
    }
}
