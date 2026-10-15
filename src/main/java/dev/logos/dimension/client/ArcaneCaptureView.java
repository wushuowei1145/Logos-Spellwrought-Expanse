package dev.logos.dimension.client;

import com.lowdragmc.lowdraglib2.registry.RegistrationEnvironment;
import com.lowdragmc.lowdraglib2.registry.annotation.LDLRegisterClient;
import com.lowdragmc.lowdraglib2.uitest.*;
import dev.logos.dimension.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.registries.Registries;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

/** Development-only recording scenario. In normal play no recorder is armed or images allocated. */
@LDLRegisterClient(name="smooth_array_capture",group="smooth_array",registry=UIScenario.REGISTRY,environment=RegistrationEnvironment.DEV_ONLY)
@EventBusSubscriber(modid=LogosDimension.ID,value=Dist.CLIENT)
public class ArcaneCaptureView extends AlchemicalViews.Sky {
    private static final ExecutorService WRITER=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"logos-preview-writer");t.setDaemon(true);return t;});
    private static volatile Recording active;
    private int oldFov;
    private static final class Recording {
        final Path directory=Path.of("captures/smooth-array");
        final long start=System.nanoTime(),end=start+12_000_000_000L;
        final AtomicInteger pending=new AtomicInteger(),written=new AtomicInteger();
        final List<String> stamps=new ArrayList<>();
        long next=start;int requested;volatile Throwable error;volatile boolean done;
        Recording(){try{Files.createDirectories(directory);}catch(Exception e){error=e;done=true;}}
    }
    @SubscribeEvent static void capture(RenderFrameEvent.Post event){
        var r=active;if(r==null||r.done)return;long now=System.nanoTime();
        if(now>=r.end||r.error!=null){
            if(r.pending.get()==0){
                try{Files.write(r.directory.resolve("timestamps.csv"),r.stamps);}catch(Exception e){r.error=e;}
                r.done=true;
            }
            return;
        }
        if(now<r.next||r.pending.get()>=2)return;
        r.next=now+33_333_333L;r.pending.incrementAndGet();
        int index=r.requested++;
        r.stamps.add(String.format(Locale.ROOT,"%05d.png,%.9f",index,(now-r.start)/1e9));
        try{
            Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget(),image->WRITER.execute(()->{
                try(image){image.writeToFile(r.directory.resolve(String.format(Locale.ROOT,"%05d.png",index)));r.written.incrementAndGet();}
                catch(Exception e){r.error=e;}finally{r.pending.decrementAndGet();}
            }));
        }catch(Exception e){r.error=e;r.pending.decrementAndGet();}
    }
    @Override protected float pitch(){return -88;}
    @Override protected int settleTicks(){return 100;}
    @Override public void configure(ScenarioOptions o){o.tags("smooth_array").defaultTimeoutMs(45_000).scenarioTimeoutMs(120_000);}
    @Override protected void extra(ScenarioBuilder s){
        s.step("normal lens",ctx->{var mc=Minecraft.getInstance();oldFov=mc.options.fov().get();mc.options.fov().set(70);})
            .runCommand("alchemy phase nigredo")
            .server("reset animation clock",ctx->{var level=ctx.player().level();level.clockManager().setTotalTicks(level.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(OpusPhase.CLOCK),0);})
            .ticks(50).step("record actual rendered frames",ctx->active=new Recording())
            .waitUntil("continuous recording complete",ctx->active!=null&&active.done)
            .check("actual frames recorded without errors",ctx->active.error==null&&active.written.get()>=180)
            .screenshot("smooth_glass_array")
            .teardown("stop recorder and restore lens",ctx->{active=null;Minecraft.getInstance().options.fov().set(oldFov);});
    }
}
