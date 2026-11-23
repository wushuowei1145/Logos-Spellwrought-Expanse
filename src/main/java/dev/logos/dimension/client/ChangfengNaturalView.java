package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.*;
import dev.logos.dimension.cloudsea.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/** 真实原版噪声生成的草原；不放置、替换或整平地表方块。 */
@LDLRegisterClient(name="changfeng_natural",group="changfeng",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)
public class ChangfengNaturalView implements UIScenario {
    private BlockPos ground;
    private int heightSpan;
    @Override public void configure(ScenarioOptions o) { o.tags("changfeng","native_worldgen").defaultTimeoutMs(180000).scenarioTimeoutMs(300000); }
    /** 客户端 32×32 格内贴着地面、已换成宽叶草簇的草；双层草只数下半。 */
    static int widened(BlockPos center){
        var mc=Minecraft.getInstance();var level=mc.level;var models=mc.getModelManager().getBlockStateModelSet();
        int count=0;
        for(int x=-16;x<16;x++)for(int z=-16;z<16;z++){
            int bx=center.getX()+x,bz=center.getZ()+z;
            var p=new BlockPos(bx,level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,bx,bz),bz);
            var state=level.getBlockState(p);
            if(models.get(state) instanceof ChangfengGrassModel model&&ChangfengGrassModel.appearance(level,p,state,model.original())!=model.original())count++;
        }
        return count;
    }
    private static boolean core(net.minecraft.world.level.biome.Climate.Sampler sampler,int x,int z){
        var p=sampler.sample(x>>2,18,z>>2);
        double t=net.minecraft.world.level.biome.Climate.unquantizeCoord(p.temperature());
        double h=net.minecraft.world.level.biome.Climate.unquantizeCoord(p.humidity());
        double c=net.minecraft.world.level.biome.Climate.unquantizeCoord(p.continentalness());
        double e=net.minecraft.world.level.biome.Climate.unquantizeCoord(p.erosion());
        return t>=-.35&&t<=.55&&h>=-.6&&h<=.2&&c>=0&&c<=1.0&&e>=.15;
    }
    @Override public void define(ScenarioBuilder s) {
        s.setGameMode(GameType.CREATIVE).runCommand("difficulty peaceful")
            .step("自然草原镜头", ctx->{ var mc=Minecraft.getInstance();mc.options.hideGui=true;mc.options.renderDistance().set(8);mc.options.fov().set(70);mc.options.broadcastOptions(); })
            .server("定位自然长风草原", ctx->{
                var level=ctx.server().getLevel(LogosDimension.CLOUDSEA);
                var origin=NativeWorlds.locateBiome(level,CloudseaBiome.SILK_FOLDS.id());
                var generator=level.getChunkSource().getGenerator();var random=level.getChunkSource().randomState();
                ground=null;heightSpan=10000;
                // 在纯气候采样中先找平原主体，避开与森林、山坡混合的过渡边缘。
                search:for(int ring=0;ring<=32;ring++)for(int dz=-ring;dz<=ring;dz++)for(int dx=-ring;dx<=ring;dx++){
                    if(ring>0&&Math.abs(dx)!=ring&&Math.abs(dz)!=ring)continue;
                    int bx=origin.getX()+dx*32,bz=origin.getZ()+dz*32;
                    if(!core(random.sampler(),bx,bz))continue;
                    boolean inside=true;
                    for(int oz:new int[]{-16,0,16})for(int ox:new int[]{-16,0,16})if(!core(random.sampler(),bx+ox,bz+oz))inside=false;
                    if(!inside)continue;
                    int y=generator.getBaseHeight(bx,bz,net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG,level,random),lo=y,hi=y;
                    for(int oz:new int[]{-16,0,16})for(int ox:new int[]{-16,0,16}){
                        int h=generator.getBaseHeight(bx+ox,bz+oz,net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG,level,random);
                        lo=Math.min(lo,h);hi=Math.max(hi,h);
                    }
                    if(hi-lo>4)continue;
                    var p=new BlockPos(bx,y-1,bz);level.getChunk(p);
                    if(!level.getBiome(p.above()).is(LogosDimension.id(CloudseaBiome.SILK_FOLDS.id())))continue;
                    ground=p;heightSpan=hi-lo;break search;
                }
                if(ground==null)throw new IllegalStateException("没有找到连片平原主体的自然样地");
                System.out.println("CHANGFENG_NATURAL "+ground+" heightSpan32="+heightSpan);
                level.clockManager().setTotalTicks(level.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(JourneySeasons.CLOCK),6000);
                level.getWeatherData().setRaining(false);level.getWeatherData().setThundering(false);
                DimensionTravel.teleport(ctx.player(),level,ground.getBottomCenter().add(0,4,0),135,12);
                ctx.player().getAbilities().flying=true;ctx.player().onUpdateAbilities();
            })
            .waitUntil("自然草原区块已到达",ctx->{var mc=Minecraft.getInstance();return mc.level!=null&&mc.level.dimension().equals(LogosDimension.CLOUDSEA)&&mc.level.hasChunk(ground.getX()>>4,ground.getZ()>>4);})
            .ticks(160)
            .waitUntil("样地周围区块已到达",ctx->{var mc=Minecraft.getInstance();for(int z=-4;z<=4;z++)for(int x=-4;x<=4;x++)if(!mc.level.hasChunk((ground.getX()>>4)+x,(ground.getZ()>>4)+z))return false;return true;})
            .ticks(100)
            .checkServer("仍为原版噪声生成器",ctx->NativeWorlds.vanilla(ctx.player().level()))
            .checkServer("三十二格样地保持平坦",ctx->heightSpan<=6)
            .check("位于长风草原",ctx->Minecraft.getInstance().level.getBiome(ground.above()).is(LogosDimension.id(CloudseaBiome.SILK_FOLDS.id())))
            .check("自然草地换上宽叶草簇",ctx->widened(ground.above())>0)
            .screenshot("changfeng_natural")
            .step("俯瞰连片平原",ctx->Minecraft.getInstance().player.setXRot(27))
            .ticks(100).screenshot("changfeng_natural_overview")
            .teardown("恢复界面",ctx->Minecraft.getInstance().options.hideGui=false);
    }
}
