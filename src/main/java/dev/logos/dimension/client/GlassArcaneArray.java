package dev.logos.dimension.client;

import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.vertex.*;
import dev.logos.dimension.LogosDimension;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.*;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.LightCoordsUtil;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

/** Three annular glass supports with finite bevels; no scene texture, framebuffer copies or raymarch. */
final class GlassArcaneArray {
    static final float HEIGHT=420;
    private static final RenderType GLASS=RenderType.create("crucible_arcane_glass",RenderSetup.builder(
        RenderPipeline.builder(RenderPipelines.MATRICES_PROJECTION_SNIPPET,RenderPipelines.GLOBALS_SNIPPET)
            .withLocation(LogosDimension.id("pipeline/arcane_glass"))
            .withVertexShader(LogosDimension.id("core/arcane_glass")).withFragmentShader(LogosDimension.id("core/arcane_glass"))
            .withSampler("Sampler0").withVertexFormat(DefaultVertexFormat.BLOCK,VertexFormat.Mode.QUADS)
            .withCull(false).withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL,false))
            .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT)).build())
        .withTexture("Sampler0",LogosDimension.id("textures/environment/cloudsea_white.png")).sortOnUpload().createRenderSetup());

    static void submit(SubmitCustomGeometryEvent e,ArcaneArrayMotion.Frame f){
        var pose=e.getPoseStack();
        pose.pushPose();pose.translate(0,HEIGHT,0);pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(f.outerAngle()));
        pose.scale(f.unfold(),1,f.unfold());
        LogosSkyOverlay.collector(e.getSubmitNodeCollector()).submitCustomGeometry(pose,GLASS,(p,v)->{
            for(int sector=0;sector<12;sector++){
                double mid=(sector+.5)*Math.PI/6;
                float shift=f.fracture()*(18+sector%3*9),dx=(float)Math.cos(mid)*shift,dz=(float)Math.sin(mid)*shift;
                float dy=f.fracture()*(float)Math.sin(sector*1.8+f.time()*.012)*14;
                annulus(p,v,184,247,sector*Math.PI/6,(sector+1)*Math.PI/6-.006,4,dx,dy,dz,0xB6B7DCED);
            }
        });pose.popPose();
        pane(e,f.orbitAngle(),HEIGHT-9,.88f+f.opening()*.16f,(float)Math.sin(f.time()*.009)*11,78,181,0x9D94C9DF);
        pane(e,f.sealAngle(),HEIGHT-25+f.opening()*20,.76f+f.opening()*.28f,(float)Math.cos(f.time()*.007)*7,0,80,0xACBCDCD6);
    }
    private static void pane(SubmitCustomGeometryEvent e,float angle,float height,float scale,float tilt,float inner,float outer,int color){
        var pose=e.getPoseStack();pose.pushPose();pose.translate(0,height,0);pose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(angle));
        pose.mulPose(com.mojang.math.Axis.XP.rotationDegrees(tilt));pose.scale(scale,scale,scale);
        LogosSkyOverlay.collector(e.getSubmitNodeCollector()).submitCustomGeometry(pose,GLASS,(p,v)->annulus(p,v,inner,outer,0,Math.PI*2,64,0,0,0,color));
        pose.popPose();
    }
    private static void annulus(PoseStack.Pose p,VertexConsumer v,float inner,float outer,double from,double to,int steps,float dx,float dy,float dz,int color){
        for(int i=0;i<steps;i++){
            double a=from+(to-from)*i/steps,b=from+(to-from)*(i+1)/steps;
            point(p,v,inner,a,dx,dy,dz,color,0,-1,0);point(p,v,outer,a,dx,dy,dz,color,0,-1,0);
            point(p,v,outer,b,dx,dy,dz,color,0,-1,0);point(p,v,inner,b,dx,dy,dz,color,0,-1,0);
            // A physically separated thin edge catches light when the plate tilts.
            float nx=(float)Math.cos((a+b)*.5),nz=(float)Math.sin((a+b)*.5);
            point(p,v,outer,a,dx,dy-.8f,dz,color,nx,0,nz);point(p,v,outer,a,dx,dy+.8f,dz,color,nx,0,nz);
            point(p,v,outer,b,dx,dy+.8f,dz,color,nx,0,nz);point(p,v,outer,b,dx,dy-.8f,dz,color,nx,0,nz);
        }
    }
    private static void point(PoseStack.Pose p,VertexConsumer v,float r,double a,float dx,float dy,float dz,int color,float nx,float ny,float nz){
        float x=(float)Math.cos(a)*r,z=(float)Math.sin(a)*r;
        v.addVertex(p,x+dx,dy,z+dz).setColor(color).setUv(.5f+x/494,.5f+z/494)
            .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(p,nx,ny,nz);
    }
    private GlassArcaneArray(){}
}
