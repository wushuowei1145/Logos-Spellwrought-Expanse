package dev.logos.dimension.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.logos.dimension.cloudsea.CloudseaCloudField;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.LightCoordsUtil;

/** A bounded, background-built cloud surface. It stays in world space when a player flies through it. */
public final class CloudseaCloudRenderer {
    private record Cell(int x,int z){}
    public record Frame(double x,double y,double z,double time,List<CloudseaCloudField.Mesh> meshes){}
    private static final LinkedHashMap<Cell,CompletableFuture<CloudseaCloudField.Mesh>> CACHE=new LinkedHashMap<>(96,.75f,true);
    private static final ExecutorService WORKER=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(64),r->{var t=new Thread(r,"logos-cloud-mesh");t.setDaemon(true);t.setPriority(Thread.NORM_PRIORITY-1);return t;});
    private static int ready;
    public static int readyMeshes(){return ready;}
    public static synchronized Frame capture(double x,double y,double z,double time){
        int cx=(int)Math.floor(x/CloudseaCloudField.CELL_SIZE),cz=(int)Math.floor(z/CloudseaCloudField.CELL_SIZE);
        var cells=new ArrayList<Cell>(49);
        for(int dz=-3;dz<=3;dz++)for(int dx=-3;dx<=3;dx++)cells.add(new Cell(cx+dx,cz+dz));
        cells.sort(Comparator.comparingInt(c->Math.abs(c.x-cx)+Math.abs(c.z-cz)));
        var meshes=new ArrayList<CloudseaCloudField.Mesh>(49);
        for(var cell:cells){
            var future=CACHE.get(cell);
            if(future==null){try{future=CompletableFuture.supplyAsync(()->CloudseaCloudField.mesh(cell.x,cell.z),WORKER);CACHE.put(cell,future);}catch(RejectedExecutionException busy){continue;}}
            if(future.isDone()&&!future.isCompletedExceptionally()&&!future.isCancelled())meshes.add(future.join());
        }
        while(CACHE.size()>81){var first=CACHE.keySet().iterator().next();var old=CACHE.remove(first);old.cancel(false);}
        ready=meshes.size();
        return new Frame(x,y,z,time,List.copyOf(meshes));
    }
    public static void draw(PoseStack.Pose pose,VertexConsumer output,Frame frame){
        for(var mesh:frame.meshes){
            double ox=(double)mesh.cellX()*CloudseaCloudField.CELL_SIZE,oz=(double)mesh.cellZ()*CloudseaCloudField.CELL_SIZE;
            float[] data=mesh.vertices();
            for(int triangle=0;triangle<data.length;triangle+=18){
                for(int corner=0;corner<4;corner++){
                    int i=triangle+Math.min(corner,2)*6;
                    double wx=ox+data[i],wz=oz+data[i+2],dx=wx-frame.x,dz=wz-frame.z;
                    float distance=(float)Math.sqrt(dx*dx+dz*dz);
                    float farFade=Math.clamp((1150-distance)/390,0,1);
                    float dy=data[i+1]-(float)frame.y;
                    float close=(float)Math.sqrt(dx*dx+dz*dz+dy*dy);
                    float nearFade=Math.clamp(close/22,0,1);
                    float nx=data[i+3],ny=data[i+4],nz=data[i+5];
                    float facing=Math.abs((float)((dx*nx+dy*ny+dz*nz)/Math.max(close,.001f)));
                    // Grazing rays pass through less cloud: soften the silhouette without
                    // billboards, a full-screen filter or additional geometry passes.
                    float rimOpacity=.10f+.90f*Math.clamp(facing*3,0,1);
                    int alpha=(int)(farFade*nearFade*rimOpacity*250);
                    float light=.84f+.13f*Math.max(ny,0)+.035f*Math.max(nx*.7f+nz*.3f,0);
                    float haze=1-farFade;
                    int red=(int)(242*light*(1-haze)+156*haze),green=(int)(249*light*(1-haze)+183*haze),blue=(int)(255*light*(1-haze)+202*haze);
                    float breath=(float)Math.sin(frame.time*.007+wx*.011+wz*.009)*.8f;
                    output.addVertex(pose,(float)dx,dy+breath,(float)dz).setColor((alpha<<24)|(red<<16)|(green<<8)|blue)
                            .setUv(.5f,.5f).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightCoordsUtil.FULL_BRIGHT).setNormal(pose,nx,ny,nz);
                }
            }
        }
    }
    private CloudseaCloudRenderer(){}
}
