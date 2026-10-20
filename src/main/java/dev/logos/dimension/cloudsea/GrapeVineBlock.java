package dev.logos.dimension.cloudsea;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BonemealableBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.common.CommonHooks;

/** Vanilla vine attachment/placement, with separate renewable leaf and fruit harvests. */
public final class GrapeVineBlock extends VineBlock implements BonemealableBlock {
    public static final MapCodec<VineBlock> CODEC=simpleCodec(GrapeVineBlock::new);
    public static final IntegerProperty AGE=IntegerProperty.create("age",0,3);
    public static final BooleanProperty FOLIAGE=BooleanProperty.create("foliage");

    public GrapeVineBlock(Properties properties){
        super(properties);
        registerDefaultState(defaultBlockState().setValue(AGE,0).setValue(FOLIAGE,true));
    }
    @Override public MapCodec<VineBlock> codec(){return CODEC;}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block,BlockState> builder){
        super.createBlockStateDefinition(builder);builder.add(AGE,FOLIAGE);
    }
    @Override protected boolean isRandomlyTicking(BlockState state){return true;}
    @Override protected void randomTick(BlockState state,ServerLevel level,BlockPos pos,RandomSource random){
        // Also removes unsupported pre-0.5 floating grape plants without scanning loaded chunks.
        if(!state.canSurvive(level,pos)){level.destroyBlock(pos,true);return;}
        if(isValidBonemealTarget(level,pos,state)&&level.getRawBrightness(pos,0)>=7
                &&CommonHooks.canCropGrow(level,pos,state,random.nextInt(5)==0)){
            grow(level,pos,state);CommonHooks.fireCropGrowPost(level,pos,state);
        }
    }
    private static void grow(ServerLevel level,BlockPos pos,BlockState state){
        var grown=state.getValue(FOLIAGE)?state.setValue(AGE,Math.min(3,state.getValue(AGE)+1)):state.setValue(FOLIAGE,true).setValue(AGE,0);
        level.setBlockAndUpdate(pos,grown);level.gameEvent(GameEvent.BLOCK_CHANGE,pos,GameEvent.Context.of(grown));
    }
    @Override protected InteractionResult useWithoutItem(BlockState state,Level level,BlockPos pos,Player player,BlockHitResult hit){
        return harvest(state,level,pos,player,player.isShiftKeyDown(),ItemStack.EMPTY,InteractionHand.MAIN_HAND);
    }
    @Override protected InteractionResult useItemOn(ItemStack stack,BlockState state,Level level,BlockPos pos,Player player,InteractionHand hand,BlockHitResult hit){
        if(stack.is(Items.SHEARS))return harvest(state,level,pos,player,true,stack,hand);
        if(stack.is(Items.BONE_MEAL)&&isValidBonemealTarget(level,pos,state))return InteractionResult.PASS;
        return super.useItemOn(stack,state,level,pos,player,hand,hit);
    }
    private InteractionResult harvest(BlockState state,Level level,BlockPos pos,Player player,boolean leaf,ItemStack shears,InteractionHand hand){
        if(player.isSpectator()||!player.getAbilities().mayBuild)return InteractionResult.PASS;
        if(leaf?!state.getValue(FOLIAGE):!state.getValue(FOLIAGE)||state.getValue(AGE)!=3)return InteractionResult.PASS;
        if(level instanceof ServerLevel server){
            var drop=new ItemStack(leaf?CloudseaBlocks.GRAPE_LEAF.get():CloudseaBlocks.GRAPES.get());
            if(!player.addItem(drop))popResource(server,pos,drop);
            var harvested=leaf?state.setValue(FOLIAGE,false).setValue(AGE,0):state.setValue(AGE,0);
            server.setBlockAndUpdate(pos,harvested);
            server.playSound(null,pos,SoundEvents.SWEET_BERRY_BUSH_PICK_BERRIES,SoundSource.BLOCKS,.7f,1.05f);
            server.gameEvent(GameEvent.BLOCK_CHANGE,pos,GameEvent.Context.of(player,harvested));
            if(!shears.isEmpty())shears.hurtAndBreak(1,player,hand);
        }
        return InteractionResult.SUCCESS;
    }
    @Override public boolean isValidBonemealTarget(LevelReader level,BlockPos pos,BlockState state){
        return state.canSurvive(level,pos)&&(!state.getValue(FOLIAGE)||state.getValue(AGE)<3);
    }
    @Override public boolean isBonemealSuccess(Level level,RandomSource random,BlockPos pos,BlockState state){return true;}
    @Override public void performBonemeal(ServerLevel level,RandomSource random,BlockPos pos,BlockState state){grow(level,pos,state);}
}
