package dev.logos.dimension.client;

import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.*;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.elven.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.*;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.*;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** 只探测已加载近景，每刻最多十八个噪声格；不做动态光照或服务端逐灯更新。 */
@EventBusSubscriber(modid=LogosDimension.ID,value=Dist.CLIENT)
public final class ElvenBreathingLights {
    private static final LinkedHashMap<BlockPos,Vec3> LIGHTS=new LinkedHashMap<>();
    private static ClientLevel previous;
    private static BlockPos origin;
    private static int cursor;
    private static final ContextKey<Frame> FRAME=new ContextKey<>(LogosDimension.id("elven_breath"));
    private record Frame(double ticks,List<Vec3> lights){}
    private record Dot(Vector3f center,float fade){}
    private static final RenderType GLOW=RenderType.create("elven_breath",RenderSetup.builder(RenderPipeline.builder(RenderPipelines.MATRICES_PROJECTION_SNIPPET)
            .withLocation(LogosDimension.id("pipeline/elven_breath")).withVertexShader("core/position_tex_color").withFragmentShader("core/position_tex_color")
            .withSampler("Sampler0").withVertexFormat(DefaultVertexFormat.BLOCK,VertexFormat.Mode.QUADS).withCull(false)
            .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL,false)).withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT)).build())
            .withTexture("Sampler0",LogosDimension.id("textures/environment/elven_breath.png")).sortOnUpload().createRenderSetup());
    public static int active(){return LIGHTS.size();}
    static void clear(){LIGHTS.clear();origin=null;cursor=0;}
    @SubscribeEvent static void tick(ClientTickEvent.Post event){
        var mc=Minecraft.getInstance();
        if(previous!=mc.level){clear();previous=mc.level;}
        int cap=ElvenLightPattern.capacity(mc.options.particles().get().ordinal());
        if(mc.level==null||mc.player==null||!mc.level.dimension().equals(LogosDimension.CLOUDSEA)||cap==0){clear();return;}
        if(mc.isPaused())return;
        var level=mc.level;var eye=mc.player.getEyePosition();
        LIGHTS.entrySet().removeIf(e->e.getValue().distanceToSqr(eye)>48*48||!level.hasChunk(e.getKey().getX()>>4,e.getKey().getZ()>>4));
        while(LIGHTS.size()>cap)LIGHTS.remove(LIGHTS.keySet().iterator().next());
        var now=mc.player.blockPosition();
        if(origin==null||origin.distSqr(now)>12*12){origin=now;cursor=0;}
        // 三维格覆盖森林地面、枝条和树冠；噪声点只贴近实体表面，不铺满空气。
        for(int i=0;i<18;i++){
            int cell=cursor++%(13*13*9),gx=Math.floorDiv(origin.getX(),6)+cell%13-6,gz=Math.floorDiv(origin.getZ(),6)+(cell/13)%13-6,gy=Math.floorDiv(origin.getY(),6)+cell/(13*13)-4;
            long hash=ElvenLightPattern.hash(gx,gy,gz);if((hash&7)>4)continue;
            var point=new BlockPos(gx*6+ElvenLightPattern.jitter(hash,8),gy*6+ElvenLightPattern.jitter(hash,24),gz*6+ElvenLightPattern.jitter(hash,40));
            if(!level.hasChunk(point.getX()>>4,point.getZ()>>4)||point.getY()<level.getMinY()||point.getY()>level.getMaxY())continue;
            if(!level.getBiome(point).is(LogosDimension.id("elven_forest")))continue;
            for(int dy=-2;dy<=2;dy++){
                var at=point.above(dy);if(!level.isEmptyBlock(at))continue;
                boolean attached=false;
                for(var direction:Direction.values()){
                    var adjacent=at.relative(direction);
                    if(!level.hasChunk(adjacent.getX()>>4,adjacent.getZ()>>4))continue;
                    var support=level.getBlockState(adjacent);
                    if(support.is(BlockTags.LOGS)||support.is(BlockTags.LEAVES)||support.is(ElvenBlocks.BOUGH_LIGHT.get())||direction==Direction.DOWN&&support.is(BlockTags.SUPPORTS_VEGETATION)){attached=true;break;}
                }
                if(!attached)continue;
                Vec3 pos=at.getCenter().add(((hash>>>16)&255)/512.0-.25,-.17,((hash>>>32)&255)/512.0-.25);
                if(pos.distanceToSqr(eye)>48*48)break;
                if(LIGHTS.size()<cap||LIGHTS.containsKey(at))LIGHTS.put(at.immutable(),pos);
                break;
            }
        }
    }
    @SubscribeEvent static void extract(ExtractLevelRenderStateEvent event){
        if(!event.getLevel().dimension().equals(LogosDimension.CLOUDSEA)||LIGHTS.isEmpty())return;
        // 采用维度游戏时钟，使位置重建、切换视角和客户端间保持相同节拍。
        event.getRenderState().setRenderData(FRAME,new Frame(event.getLevel().getGameTime()+event.getDeltaTracker().getGameTimeDeltaPartialTick(false),List.copyOf(LIGHTS.values())));
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) static void submit(SubmitCustomGeometryEvent event){
        var frame=event.getLevelRenderState().getRenderData(FRAME);if(frame==null)return;
        var cam=event.getLevelRenderState().cameraRenderState;var pose=event.getPoseStack();
        var out=LogosSkyOverlay.collector(event.getSubmitNodeCollector());double pulse=ElvenLightPattern.pulse(frame.ticks);
        var inverse=new Quaternionf(cam.orientation).conjugate();var dots=new ArrayList<Dot>();
        for(var at:frame.lights){
            float fade=(float)Math.clamp((48-at.distanceTo(cam.pos))/10,0,1);if(fade==0)continue;
            dots.add(new Dot(new Vector3f((float)(at.x-cam.pos.x),(float)(at.y-cam.pos.y),(float)(at.z-cam.pos.z)).rotate(inverse),fade));
        }
        // 所有光晕一次提交，避免为每个光点创建独立渲染节点。
        pose.pushPose();pose.mulPose(cam.orientation);
        out.submitCustomGeometry(pose,GLOW,(p,v)->{
            for(var dot:dots){
                float radius=(float)(.32+.32*pulse);int alpha=(int)((.13+.67*pulse)*dot.fade*255);
                quad(p,v,dot.center,radius,(alpha<<24)|0xECFFD5);
                quad(p,v,dot.center,.06f,(alpha<<24)|0xFFF5D6);
            }
        });pose.popPose();
    }
    private static void quad(PoseStack.Pose p,VertexConsumer v,Vector3f center,float r,int color){
        float[][] q={{-r,-r,0,1},{r,-r,1,1},{r,r,1,0},{-r,r,0,0}};
        for(var a:q)v.addVertex(p,center.x+a[0],center.y+a[1],center.z).setColor(color).setUv(a[2],a[3]).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(p,0,0,1);
    }
    private ElvenBreathingLights(){}
}
