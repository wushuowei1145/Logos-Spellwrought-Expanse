package dev.logos.dimension.client;

import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.*;
import com.mojang.math.Axis;
import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.cloudsea.CloudseaBiome;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.*;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.*;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.context.ContextKey;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;

/** A synchronized sky phenomenon, not an entity: it has no AI, collisions, loot or saved mobs. */
@EventBusSubscriber(modid=LogosDimension.ID,value=Dist.CLIENT)
public final class CloudseaClient {
    public static final ResourceKey<WorldClock> CLOCK=ResourceKey.create(Registries.WORLD_CLOCK,LogosDimension.id("cloudsea"));
    private static final ContextKey<Frame> FRAME=new ContextKey<>(LogosDimension.id("cloudsea_frame"));
    private static CloudseaBiome lastBiome;
    private record Frame(float travel,float visibility,float beat,float eyeY,int shadowY,double x,double z){}
    private static RenderType material(String name,boolean depthWrite){return RenderType.create(name,RenderSetup.builder(RenderPipeline.builder(RenderPipelines.MATRICES_PROJECTION_SNIPPET)
            .withLocation(LogosDimension.id("pipeline/"+name)).withVertexShader("core/position_tex_color").withFragmentShader("core/position_tex_color")
            .withSampler("Sampler0").withVertexFormat(DefaultVertexFormat.BLOCK,VertexFormat.Mode.QUADS).withCull(false)
            .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL,depthWrite)).withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT)).build())
            .withTexture("Sampler0",LogosDimension.id("textures/environment/cloudsea_white.png")).sortOnUpload().createRenderSetup());}
    private static final RenderType WHALE=material("cloudsea_whale",false),SHADOW=material("cloudsea_whale_shadow",false);
    public static long ticks(Level level){return level.clockManager().getTotalTicks(level.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(CLOCK));}
    public static float whaleVisibility(long ticks){int t=(int)Math.floorMod(ticks,3600);return t<480?Math.min(1,Math.min(t/60f,(480-t)/60f)):0;}
    @SubscribeEvent static void extract(ExtractLevelRenderStateEvent e){
        if(!e.getLevel().dimension().equals(LogosDimension.CLOUDSEA))return;
        var cam=e.getRenderState().cameraRenderState;long time=ticks(e.getLevel());float vis=whaleVisibility(time);
        float partial=e.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        float travel=(((time%3600)+partial)/480f-.5f)*380;
        double whaleZ=180+travel*.5,wx=-.5735764364*whaleZ,wz=.8191520443*whaleZ;
        int ground=e.getLevel().hasChunk((int)Math.floor(wx)>>4,(int)Math.floor(wz)>>4)
                ?e.getLevel().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,(int)Math.floor(wx),(int)Math.floor(wz)):0;
        e.getRenderState().setRenderData(FRAME,new Frame(travel,vis,(time+partial)*.025f,(float)cam.pos.y,ground,cam.pos.x,cam.pos.z));
    }
    @SubscribeEvent(priority=EventPriority.LOWEST) static void submit(SubmitCustomGeometryEvent e){
        var f=e.getLevelRenderState().getRenderData(FRAME);if(f==null||f.eyeY<0)return;
        var pose=e.getPoseStack();var out=LogosSkyOverlay.collector(e.getSubmitNodeCollector());
        if(f.visibility>0){
            pose.pushPose();pose.translate(-f.x,497-f.eyeY,-f.z);pose.mulPose(Axis.YP.rotationDegrees(-35));
            pose.translate(0,0,180+f.travel*.5);pose.mulPose(Axis.XP.rotationDegrees((float)Math.sin(f.beat)*3));
            out.submitCustomGeometry(pose,WHALE,(p,v)->whale(p,v,f));pose.popPose();
        }
        double whaleZ=180+f.travel*.5,wx=-.5735764364*whaleZ,wz=.8191520443*whaleZ;
        if(f.visibility>0&&f.shadowY>0&&f.eyeY<f.shadowY+55&&Math.hypot(wx-f.x,wz-f.z)<200){
            pose.pushPose();pose.translate(wx-f.x,f.shadowY-f.eyeY+.03,wz-f.z);pose.mulPose(Axis.YP.rotationDegrees(-35));
            out.submitCustomGeometry(pose,SHADOW,(p,v)->{
                for(int i=0;i<12;i++){float z0=-50+i*9,z1=z0+9,r0=(float)Math.sin(i*Math.PI/12)*34,r1=(float)Math.sin((i+1)*Math.PI/12)*34;
                    quad(p,v,new float[][]{{-r0,0,z0,0,0},{r0,0,z0,1,0},{r1,0,z1,1,1},{-r1,0,z1,0,1}},((int)(f.visibility*34)<<24)|0x24324D);}
            });pose.popPose();
        }
    }
    private static void whale(PoseStack.Pose p,VertexConsumer v,Frame f){
        float[][] rings={{-152,4,4},{-115,10,7},{-78,22,12},{-40,35,18},{0,43,23},{40,45,25},{75,40,24},{94,24,18},{102,3,5}};
        for(int i=0;i<rings.length-1;i++)for(int j=0;j<16;j++){
            float a=(float)(j*Math.PI/8),b=(float)((j+1)*Math.PI/8);var r=rings[i];var s=rings[i+1];
            int color=j>=9&&j<=14?0x6FADC2:0x355A84;if(j==11||j==13)color=0xA5D4DD;
            quad(p,v,new float[][]{{(float)Math.cos(a)*r[1],(float)Math.sin(a)*r[2],r[0],0,0},{(float)Math.cos(a)*s[1],(float)Math.sin(a)*s[2],s[0],1,0},{(float)Math.cos(b)*s[1],(float)Math.sin(b)*s[2],s[0],1,1},{(float)Math.cos(b)*r[1],(float)Math.sin(b)*r[2],r[0],0,1}},((int)(f.visibility*255)<<24)|color);
        }
        float fin=(float)Math.sin(f.beat)*9;
        for(int side:new int[]{-1,1}){
            quad(p,v,new float[][]{{side*32,-7,20,0,0},{side*85,-22+fin,-22,1,0},{side*79,-24+fin,-49,1,1},{side*30,-11,-10,0,1}},((int)(f.visibility*255)<<24)|0x4D7B9C);
            quad(p,v,new float[][]{{0,0,-146,0,0},{side*49,fin,-159,1,0},{side*42,fin,-188,1,1},{0,-2,-173,0,1}},((int)(f.visibility*255)<<24)|0x668AA8);
        }
    }
    private static void quad(PoseStack.Pose p,VertexConsumer v,float[][] a,int color){
        float ux=a[1][0]-a[0][0],uy=a[1][1]-a[0][1],uz=a[1][2]-a[0][2],wx=a[2][0]-a[0][0],wy=a[2][1]-a[0][1],wz=a[2][2]-a[0][2];
        float nx=uy*wz-uz*wy,ny=uz*wx-ux*wz,nz=ux*wy-uy*wx,len=(float)Math.sqrt(nx*nx+ny*ny+nz*nz);if(len<.00001f){ny=1;len=1;}
        for(var q:a)v.addVertex(p,q[0],q[1],q[2]).setColor(color).setUv(q[3],q[4]).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(p,nx/len,ny/len,nz/len);
    }
    @SubscribeEvent static void tick(ClientTickEvent.Post e){
        var mc=Minecraft.getInstance();if(mc.level==null||mc.player==null||!mc.level.dimension().equals(LogosDimension.CLOUDSEA)){lastBiome=null;return;}
        long t=ticks(mc.level);var p=mc.player.position();var random=mc.level.getRandom();
        if(t%24==0){
            CloudseaBiome kind=null;var holder=mc.level.getBiome(mc.player.blockPosition());for(var candidate:CloudseaBiome.values())if(holder.is(LogosDimension.id(candidate.id()))){kind=candidate;break;}
            var particle=mc.player.getY()<0?ParticleTypes.ENCHANT:kind==null?ParticleTypes.END_ROD:switch(kind.form){case PETAL,HANGING->ParticleTypes.SPORE_BLOSSOM_AIR;case SPIRE->ParticleTypes.ELECTRIC_SPARK;case DUNE,STEPS->ParticleTypes.ENCHANT;default->ParticleTypes.END_ROD;};
            mc.level.addParticle(particle,p.x+(random.nextDouble()-.5)*14,p.y+random.nextDouble()*3,p.z+(random.nextDouble()-.5)*14,kind!=null&&kind.form==CloudseaBiome.Form.RIBBON?.025:0,kind!=null&&kind.form==CloudseaBiome.Form.HANGING?-.01:.006,0);
            if(kind!=null&&kind!=lastBiome){lastBiome=kind;mc.level.playLocalSound(p.x,p.y,p.z,kind.form==CloudseaBiome.Form.STEPS?SoundEvents.NOTE_BLOCK_BELL.value():SoundEvents.NOTE_BLOCK_CHIME.value(),SoundSource.AMBIENT,.10f,.7f+(kind.ordinal()%3)*.15f,false);}
        }
        if(t%3600==90)mc.level.playLocalSound(p.x,p.y+70,p.z,SoundEvents.DOLPHIN_AMBIENT,SoundSource.AMBIENT,.25f,.4f,false);
        if(t%600==0)mc.level.playLocalSound(p.x,p.y,p.z,SoundEvents.NOTE_BLOCK_CHIME.value(),SoundSource.AMBIENT,.10f,.7f,false);
    }
    @SubscribeEvent static void fog(ViewportEvent.ComputeFogColor e){
        var mc=Minecraft.getInstance();if(mc.level==null||mc.player==null||!mc.level.dimension().equals(LogosDimension.CLOUDSEA))return;
        if(mc.player.getY()<-65){e.setRed(.22f);e.setGreen(.17f);e.setBlue(.28f);}
    }
    private CloudseaClient(){}
}
