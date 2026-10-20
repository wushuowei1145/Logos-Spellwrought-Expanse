package dev.logos.dimension.cloudsea;

import com.mojang.serialization.MapCodec;
import dev.logos.dimension.LogosDimension;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.*;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.*;

/** Native sweet-berry growth, soil, bonemeal and thorns; larger seasonal blue fruit. */
public final class JourneyBerryBushBlock extends SweetBerryBushBlock {
    public static final MapCodec<SweetBerryBushBlock> CODEC=simpleCodec(JourneyBerryBushBlock::new);
    public JourneyBerryBushBlock(Properties p){super(p);registerDefaultState(defaultBlockState().setValue(SeasonalLeavesBlock.SEASON,0));}
    @Override public MapCodec<SweetBerryBushBlock> codec(){return CODEC;}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder){super.createBlockStateDefinition(builder);builder.add(SeasonalLeavesBlock.SEASON);}
    @Override protected VoxelShape getShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context){
        return switch(state.getValue(AGE)){case 0->Block.column(12,0,10);case 1->Block.column(16,0,17);default->Block.column(20,0,22);};
    }
    // Mature shrubs also need seasonal updates and old-save support checks.
    @Override protected boolean isRandomlyTicking(BlockState state){return true;}
    @Override protected void randomTick(BlockState state,ServerLevel level,BlockPos pos,RandomSource random){
        if(!state.canSurvive(level,pos)){level.destroyBlock(pos,true);return;}
        if(level.dimension().equals(LogosDimension.CLOUDSEA)){
            int season=JourneySeasons.of(level);
            if(state.getValue(SeasonalLeavesBlock.SEASON)!=season){state=state.setValue(SeasonalLeavesBlock.SEASON,season);level.setBlock(pos,state,Block.UPDATE_CLIENTS);}
        }
        super.randomTick(state,level,pos,random);
    }
    @Override protected ItemStack getCloneItemStack(LevelReader level,BlockPos pos,BlockState state,boolean includeData){return new ItemStack(this);}
    @Override protected InteractionResult useWithoutItem(BlockState state,Level level,BlockPos pos,Player player,BlockHitResult hit){
        int age=state.getValue(AGE);
        if(age<2||player.isSpectator()||!player.getAbilities().mayBuild)return InteractionResult.PASS;
        if(level instanceof ServerLevel server){
            // Same harvest ranges as vanilla: 1–2 at age 2, 2–3 at age 3.
            popResource(server,pos,new ItemStack(this,1+server.getRandom().nextInt(2)+(age==3?1:0)));
            var picked=state.setValue(AGE,1);server.setBlock(pos,picked,Block.UPDATE_CLIENTS);
            server.playSound(null,pos,SoundEvents.SWEET_BERRY_BUSH_PICK_BERRIES,SoundSource.BLOCKS,1,.8f+server.getRandom().nextFloat()*.4f);
            server.gameEvent(GameEvent.BLOCK_CHANGE,pos,GameEvent.Context.of(player,picked));
        }
        return InteractionResult.SUCCESS;
    }
}
