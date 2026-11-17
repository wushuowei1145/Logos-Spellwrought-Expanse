package dev.logos.dimension.client;

import java.nio.file.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.common.NeoForge;

/** 仅由开发场景注册：每两刻抓一张真实帧，录制不进入正式游戏的渲染路径。 */
public final class QueenEntranceCapture {
    private final Path folder;
    private int last=-1;
    private boolean began;
    private QueenEntranceCapture(Path folder){this.folder=folder;}
    public static void start(){
        var folder=Minecraft.getInstance().gameDirectory.toPath().resolve("queen-entry-frames");
        try { Files.createDirectories(folder); } catch(java.io.IOException e){throw new IllegalStateException(e);}
        System.out.println("QUEEN_ENTRY_FRAMES="+folder.toAbsolutePath());
        NeoForge.EVENT_BUS.register(new QueenEntranceCapture(folder));
    }
    @SubscribeEvent public void frame(RenderFrameEvent.Post e){
        if(!QueenEntranceClient.active()){
            if(began)NeoForge.EVENT_BUS.unregister(this);
            return;
        }
        began=true;
        int sample=(int)(QueenEntranceClient.age()/2);
        if(sample==last)return;
        last=sample;
        var target=folder.resolve(String.format("%03d.png",sample));
        Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget(),2,image->
            java.util.concurrent.CompletableFuture.runAsync(()->{
                try(image){image.writeToFile(target);}catch(java.io.IOException error){throw new IllegalStateException(error);}
            }));
    }
}
