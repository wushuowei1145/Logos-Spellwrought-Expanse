package dev.logos.dimension.client;

import dev.logos.dimension.LogosDimension;
import dev.logos.dimension.client.camera.CameraDirector;
import dev.logos.dimension.elven.blight.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;

/** 镜头前的三层透明前景：近血管、远薄膜、孢子；借视差与遮挡揭示真实世界里的女王。 */
@EventBusSubscriber(modid=LogosDimension.ID,value=Dist.CLIENT)
public final class QueenEntranceClient {
    private static final Identifier NEAR=LogosDimension.id("textures/gui/queen_entry_near.png"),FAR=LogosDimension.id("textures/gui/queen_entry_far.png");
    private static ClientLevel level;
    private static QueenEntrancePayload cue;
    private static long began;
    private static long frames;
    private QueenEntranceClient(){}
    @SubscribeEvent static void packets(RegisterClientPayloadHandlersEvent e){
        e.register(QueenEntrancePayload.TYPE,(p,ctx)->{level=Minecraft.getInstance().level;cue=p;began=level==null?0:level.getGameTime();CameraDirector.attachRig(QueenEntranceClient::aim);});
    }
    private static CameraDirector.Aim aim(float age,float partial){
        if(!active())return null;
        var queen=level.getEntity(cue.queen());
        var pos=queen.getPosition(partial);
        var side=cue.front().cross(new net.minecraft.world.phys.Vec3(0,1,0));
        float k=smooth(Math.clamp((age-24)/42,0,1));
        var far=pos.add(cue.front().scale(10)).add(side.scale(5)).add(0,5,0);
        var near=pos.add(cue.front().scale(5.5)).add(side.scale(3.5)).add(0,2.4,0);
        var camera=dev.logos.dimension.camera.CameraShots.reach(level,pos.add(0,1.6,0),far.lerp(near,k));
        return new CameraDirector.Aim(camera,pos.add(0,Mth.lerp(k,5.5,2.6),0),0);
    }
    private static float smooth(float t){return t*t*(3-2*t);}
    public static long frames(){return frames;}
    public static float age(){return level==null?-1:level.getGameTime()-began+Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);}
    public static boolean active(){
        var mc=Minecraft.getInstance();
        if(cue==null||mc.level!=level||mc.player==null||!mc.player.isAlive()||age()>=QueenEntrances.TICKS)return false;
        var queen=level.getEntity(cue.queen());
        return queen!=null&&queen.isAlive();
    }
    public static float corePulse(BlockPos at){
        if(!active()||!cue.heart().equals(at))return 0;
        float t=age();
        return (float)(Math.exp(-Math.pow((t-20)/5,2))*.8+Math.exp(-Math.pow((t-40)/8,2)));
    }
    /** 临时使用中等广角容纳悬吊核心，退出后自然回到玩家原来的视野设置。 */
    @SubscribeEvent static void fov(ViewportEvent.ComputeFov e){
        if(active()&&e.usedConfiguredFov())e.setFOV(Mth.lerp(CameraDirector.weight(),e.getFOV(),64));
    }
    /** 即使运镜隐藏界面，前景仍要画；上下避开已有遮幅，不覆盖返回后的 HUD。 */
    @SubscribeEvent(priority=EventPriority.LOWEST,receiveCanceled=true) static void foreground(RenderGuiEvent.Pre e){
        if(!active())return;
        frames++;
        var g=e.getGuiGraphics();
        int w=g.guiWidth(),h=g.guiHeight(),bar=Math.round(h*.125f*CameraDirector.bars());
        float t=age(),enter=smooth(Math.clamp(t/16,0,1)),open=smooth(Math.clamp((t-22)/40,0,1));
        float shown=enter*(1-open);
        if(shown>.001){
            g.enableScissor(0,bar,w,h-bar);
            for(int layer=0;layer<2;layer++){
                float shift=(1-enter)+(layer==0?.8f:1.05f)*open;
                int width=Math.round(w*(layer==0?.42f:.5f));
                float drift=(float)Math.sin(t*.035+layer)*h*.013f;
                int left=Math.round(-width*shift),right=w-width-left;
                var pose=g.pose();pose.pushMatrix();pose.translate(0,drift);
                g.blit(layer==0?FAR:NEAR,left,bar,width+left,h-bar,0,1,0,1);
                g.blit(layer==0?FAR:NEAR,right,bar,right+width,h-bar,1,0,0,1);
                pose.popMatrix();
            }
            for(int i=0;i<42;i++){
                float x=(float)((i*.6180339%1)*w+Math.sin(t*.017+i)*w*.014);
                float y=(float)((i*.381966%1)*h-t*(.1+i%4*.035));y=(y%h+h)%h;
                int a=Math.round(shown*(42+i%4*17));
                int size=i%8==0?2:1;
                g.fill((int)x,(int)y,(int)x+size,(int)y+size,a<<24|0xC97377);
            }
            g.disableScissor();
        }
        BossTitle.draw(g,Minecraft.getInstance().font,t-(QueenEntrances.TICKS-BossTitle.GONE),
                Component.translatable("gui.logos_dimension.queen.title_zh"),
                null,
                Component.translatable("gui.logos_dimension.queen.title_en"));
    }
}
