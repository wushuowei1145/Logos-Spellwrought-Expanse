package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.*;
import dev.logos.dimension.corruption.CorruptionNetworks;
import dev.logos.dimension.elven.blight.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;

/** 在自然生成的林地拆区域核心，并用真实客户端右键剥除一块残污。 */
@LDLRegisterClient(name="elven_blight_cleanup", group="elven_blight", registry=UIScenario.REGISTRY, environment=RegistrationEnvironment.DEV_ONLY)
public class ElvenBlightCleanupViews implements UIScenario {
    private BlockPos node, other, probe, hoeTarget;
    private BlockState original, hoeOriginal;
    @Override public void configure(ScenarioOptions o) { o.tags("elven_blight").defaultTimeoutMs(360000).scenarioTimeoutMs(600000); }
    @Override public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("difficulty peaceful")
            .step("设置净化镜头", ctx -> { var mc=Minecraft.getInstance();mc.options.hideGui=true;mc.options.renderDistance().set(6);mc.options.broadcastOptions(); })
            .server("寻找自然区域核心",ctx->{
                var world=ctx.server().getLevel(LogosDimension.CLOUDSEA);
                var at=NativeWorlds.locateBiome(world,"blighted_elven_forest");
                var candidates=new ArrayList<BlockPos>();
                for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++){
                    var chunk=world.getChunk((at.getX()>>4)+x,(at.getZ()>>4)+z);
                    for(var p:chunk.getBlockEntitiesPos())if(world.getBlockEntity(p) instanceof BlightNode)candidates.add(p);
                }
                candidates.sort(Comparator.comparingDouble(p->p.distSqr(at)));
                node=candidates.getFirst();other=candidates.get(1);
                probe=substrate(world,node);original=BlightMemoryBlock.original(world.getBlockState(probe));
                hoeTarget=substrate(world,other);hoeOriginal=BlightMemoryBlock.original(world.getBlockState(hoeTarget));
                Vec3 feet=node.getBottomCenter().add(10,8,-14),d=node.getCenter().subtract(feet.add(0,ctx.player().getEyeHeight(),0));
                DimensionTravel.teleport(ctx.player(),world,feet,(float)Math.toDegrees(Math.atan2(-d.x,d.z)),(float)-Math.toDegrees(Math.atan2(d.y,Math.hypot(d.x,d.z))));
                ctx.player().getAbilities().flying=true;ctx.player().onUpdateAbilities();
                world.getWeatherData().setRaining(false);
            })
            .waitUntilServer("区域原材质登记完成",ctx->{
                var data=CorruptionNetworks.get(ctx.player().level());var network=data.ofCore(node);
                return network!=null&&data.at(probe)==network&&data.originalAt(ctx.player().level(),probe)==original&&!BlightReclamation.get(ctx.player().level()).pending();
            }).ticks(40)
            .checkServer("确为自然污染且有健康底材",ctx->ctx.player().level().getBlockState(probe).getBlock() instanceof BlightMemoryBlock)
            .screenshot("crimson_region_before")
            .server("拆除一个区域核心",ctx->ctx.player().level().destroyBlock(node,false,ctx.player()))
            .ticks(28).screenshot("crimson_region_cleansing")
            .waitUntilServer("清理波还原原材质",ctx->ctx.player().level().getBlockState(probe).getBlock()==original.getBlock())
            .ticks(120)
            .checkServer("邻区核心与污染保留",ctx->ctx.player().level().getBlockState(other).is(ElvenBlight.NODE.get())&&ctx.player().level().getBlockState(hoeTarget).getBlock() instanceof BlightMemoryBlock)
            .screenshot("crimson_region_after")
            .server("准备手动清理残污",ctx->{
                ctx.player().setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.IRON_HOE));
                DimensionTravel.teleport(ctx.player(),ctx.player().level(),hoeTarget.getBottomCenter().add(0,2,-2),0,40);
                ctx.player().getAbilities().flying=true;ctx.player().onUpdateAbilities();
            }).ticks(15)
            .step("用锄右键剥除污染",ctx->{
                var mc=Minecraft.getInstance();
                mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,new BlockHitResult(hoeTarget.getCenter().add(0,0,-.5),Direction.NORTH,hoeTarget,false));
            })
            .waitUntilServer("手动清理保留底材",ctx->ctx.player().level().getBlockState(hoeTarget).getBlock()==hoeOriginal.getBlock())
            .checkServer("手动清理没有摧毁区域核心",ctx->ctx.player().level().getBlockState(other).is(ElvenBlight.NODE.get()))
            .teardown("恢复界面",ctx->Minecraft.getInstance().options.hideGui=false);
    }
    private static BlockPos substrate(net.minecraft.server.level.ServerLevel level,BlockPos node){
        for(int y=1;y<=12;y++)if(level.getBlockState(node.below(y)).getBlock() instanceof BlightMemoryBlock)return node.below(y);
        throw new IllegalStateException("核心下方没有可还原污染");
    }
}
