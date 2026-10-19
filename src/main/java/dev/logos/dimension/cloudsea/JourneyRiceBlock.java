package dev.logos.dimension.cloudsea;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.*;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.material.*;
import org.jspecify.annotations.Nullable;

/** Native crop growth/bonemeal, rooted in shallow fresh water like seagrass. */
public final class JourneyRiceBlock extends CropBlock implements LiquidBlockContainer {
    public static final MapCodec<JourneyRiceBlock> CODEC=simpleCodec(JourneyRiceBlock::new);
    public JourneyRiceBlock(BlockBehaviour.Properties properties){super(properties);}
    @Override public MapCodec<JourneyRiceBlock> codec(){return CODEC;}
    @Override protected boolean mayPlaceOn(BlockState soil,BlockGetter level,BlockPos pos){return soil.is(Blocks.MUD)||soil.is(Blocks.CLAY);}
    @Override public @Nullable BlockState getStateForPlacement(BlockPlaceContext context){
        var fluid=context.getLevel().getFluidState(context.getClickedPos());
        return fluid.is(FluidTags.WATER)&&fluid.isFull()?super.getStateForPlacement(context):null;
    }
    @Override protected ItemLike getBaseSeedId(){return CloudseaBlocks.RICE.get();}
    @Override protected FluidState getFluidState(BlockState state){return Fluids.WATER.getSource(false);}
    @Override protected BlockState updateShape(BlockState state,LevelReader level,ScheduledTickAccess ticks,BlockPos pos,Direction direction,BlockPos neighbor,BlockState neighborState,RandomSource random){
        var result=super.updateShape(state,level,ticks,pos,direction,neighbor,neighborState,random);
        if(!result.isAir())ticks.scheduleTick(pos,Fluids.WATER,Fluids.WATER.getTickDelay(level));
        return result;
    }
    @Override public boolean canPlaceLiquid(@Nullable LivingEntity user,BlockGetter level,BlockPos pos,BlockState state,Fluid fluid){return false;}
    @Override public boolean placeLiquid(LevelAccessor level,BlockPos pos,BlockState state,FluidState fluid){return false;}
}
