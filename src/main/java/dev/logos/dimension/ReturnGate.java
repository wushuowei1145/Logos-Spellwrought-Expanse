package dev.logos.dimension;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.logos.dimension.world.AlchemicalChunkGenerator;
import dev.logos.dimension.world.AlchemicalLayout;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.jspecify.annotations.Nullable;

/** A reusable exit belongs to the destination world, never to the cauldron's destruction binding. */
@EventBusSubscriber(modid=LogosDimension.ID)
public final class ReturnGate extends SavedData {
    public record Landing(BlockPos feet,BlockPos portal) {
        static final Codec<Landing> CODEC=RecordCodecBuilder.create(i->i.group(
                BlockPos.CODEC.fieldOf("feet").forGetter(Landing::feet),BlockPos.CODEC.fieldOf("portal").forGetter(Landing::portal)).apply(i,Landing::new));
    }
    public static final Codec<ReturnGate> CODEC=Landing.CODEC.optionalFieldOf("landing").codec()
            .xmap(value->new ReturnGate(value.orElse(null)),data->Optional.ofNullable(data.landing));
    public static final SavedDataType<ReturnGate> TYPE=new SavedDataType<>(LogosDimension.id("return_gate"),()->new ReturnGate(null),CODEC);
    private @Nullable Landing landing;
    private ReturnGate(@Nullable Landing landing){this.landing=landing;}
    public static @Nullable Landing prepare(ServerLevel level){
        if(!(level.getChunkSource().getGenerator() instanceof AlchemicalChunkGenerator generator))return null;
        var data=level.getDataStorage().computeIfAbsent(TYPE);
        var saved=data.landing;
        if(saved!=null){
            level.getChunk(saved.portal());
            if(safeFeet(level,saved.feet()) && (level.getBlockState(saved.portal()).is(DimensionBlocks.VORTEX.get()) || freePortal(level,saved.portal()))){
                if(!level.getBlockState(saved.portal()).is(DimensionBlocks.VORTEX.get()))level.setBlockAndUpdate(saved.portal(),DimensionBlocks.VORTEX.get().defaultBlockState());
                return saved;
            }
        }
        var at=DimensionTravel.findLanding(level,generator.terrain().layout().center(0,0),AlchemicalLayout.Region.AETHER);
        if(at==null)return null;
        var feet=BlockPos.containing(at);
        for(int distance=2;distance<=4;distance++)for(var direction:Direction.Plane.HORIZONTAL)for(int dy:new int[]{0,1,-1,2,-2}){
            var portal=feet.relative(direction,distance).above(dy);
            level.getChunk(portal);
            if(!safeFeet(level,feet)||!freePortal(level,portal))continue;
            level.setBlockAndUpdate(portal,DimensionBlocks.VORTEX.get().defaultBlockState());
            data.landing=new Landing(feet,portal);data.setDirty();return data.landing;
        }
        return null;
    }
    private static boolean safeFeet(ServerLevel level,BlockPos pos){
        return level.isEmptyBlock(pos)&&level.isEmptyBlock(pos.above())&&level.getBlockState(pos.below()).isFaceSturdy(level,pos.below(),Direction.UP);
    }
    private static boolean freePortal(ServerLevel level,BlockPos pos){return safeFeet(level,pos)&&level.getFluidState(pos).isEmpty();}
    public static void tell(ServerPlayer player,Landing landing){
        var p=landing.portal();player.sendSystemMessage(Component.translatable("message.logos_dimension.return_gate",p.getX(),p.getY(),p.getZ()));
    }
    @SubscribeEvent static void login(PlayerEvent.PlayerLoggedInEvent event){
        if(event.getEntity() instanceof ServerPlayer player&&LogosDimension.isCrucible(player.level().dimension())){
            var gate=prepare(player.level());if(gate!=null)tell(player,gate);
        }
    }
}
